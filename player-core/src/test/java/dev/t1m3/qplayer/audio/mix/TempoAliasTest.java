package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>倍半关系（tempo_alias）</b>（2026-10-07，用户点名的洞）。
 *
 * <p>用户原话：「87 BPM 和 174 BPM 这种倍半关系的曲子，在你这里速度门直接判"不合"
 * （50% 的差），而 AM 会认出来"这是同一个速度的两倍"，然后按 4 拍（alias==2 时 2 拍）
 * 为量子去算叠唱区」。
 *
 * <p>这条判据用**可证的数值**钉三件事：识别倍半、量子取 2 拍、以及"识别不出来时如实说没有"。
 */
public class TempoAliasTest {

    @Test
    public void doubleAndHalfAreRecognised() {
        assertEquals("174 与 87 ⇒ A 是 B 的两倍", 2.0, RouteGates.tempoAlias(174.0, 87.0), 1e-9);
        assertEquals("87 与 174 ⇒ A 是 B 的一半", 0.5, RouteGates.tempoAlias(87.0, 174.0), 1e-9);
        assertEquals("同速 ⇒ 1", 1.0, RouteGates.tempoAlias(120.0, 120.0), 1e-9);
    }

    @Test
    public void theTempoGateNoLongerRejectsAHalfDoublePair() {
        // 用户描述的症结：50% 的速度差本来会被判"不合"
        double diff = Math.abs(174.0 - 87.0) / ((174.0 + 87.0) * 0.5);
        assertTrue("原始速度差确实很大（" + diff + "）—— 所以旧门会判不合",
                diff > RouteGates.LOOSE_TEMPO);
        assertTrue("但倍半关系必须被识别出来（这才是速度门该看的）",
                RouteGates.tempoAlias(174.0, 87.0) == 2.0);
    }

    @Test
    public void quantumIsFourBeatsNormallyAndTwoBeatsForAliasPairs() {
        assertEquals("同速/无关系 ⇒ 4 拍量子", 4.0, RouteGates.quantumBeats(1.0), 1e-9);
        assertEquals("无关系 ⇒ 4 拍", 4.0, RouteGates.quantumBeats(0.0), 1e-9);
        assertEquals("倍半（alias==2）⇒ **2 拍**量子（用户原话）",
                2.0, RouteGates.quantumBeats(2.0), 1e-9);
        assertEquals("倍半（alias==0.5）⇒ 同样是 2 拍", 2.0, RouteGates.quantumBeats(0.5), 1e-9);
    }

    @Test
    public void theGateItselfAcceptsAHalfDoublePairButStillRejectsUnrelatedOnes() {
        RouteGates.Signals alias = new RouteGates.Signals();
        alias.bpmA = Double.valueOf(174.0);
        alias.bpmB = Double.valueOf(87.0);
        alias.tempoDiff = Double.valueOf(2.0 / 3.0);          // 50% 的差（旧的判法会判不合）
        alias.keyScore = Double.valueOf(0.9);
        alias.overlapSecs = Double.valueOf(8.0);
        // 2026-10-07 改：交叉淡化那一族（他的 smart）**本来就没有速度因子** ✗
        // ⇒ 这条判据要指向**带 tempo_alias 因子**的路由：他的 filtered-direct id 8
        //（strict_tempo · incoming_beat_count · **tempo_alias**）。
        java.util.List<Double> f = RouteGates.factorsFor(MixStyle.FILTER_HIGH_TO_LOW, alias);
        assertEquals("倍半 ⇒ tempo_alias 那一项给 1（不判不合）", 1.0, f.get(0).doubleValue(), 1e-9);
        // 对照：不是倍半的一对（差 50%），该门仍然拦
        RouteGates.Signals unrelated = new RouteGates.Signals();
        unrelated.bpmA = Double.valueOf(150.0);
        unrelated.bpmB = Double.valueOf(100.0);
        unrelated.tempoDiff = Double.valueOf(0.4);
        unrelated.keyScore = Double.valueOf(0.9);
        unrelated.overlapSecs = Double.valueOf(8.0);
        assertEquals("不是倍半 ⇒ tempo_alias 那一项给 0（整条被筛掉）", 0.0,
                RouteGates.factorsFor(MixStyle.FILTER_HIGH_TO_LOW, unrelated)
                        .get(0).doubleValue(), 1e-9);
    }

    @Test
    public void theOverlapBeatsSnapToTheQuantumTheAliasChooses() {
        // 同速 / 无关系 ⇒ 量子 4 拍：拍数吸到 4 的整数倍
        assertEquals("16 拍 ⇒ 16（已是 4 的倍数）", 16.0,
                RouteGates.snapBeatsToQuantum(16.0, 1.0), 1e-9);
        assertEquals("14 拍 ⇒ 16（向最近的 4 的倍数靠）", 16.0,
                RouteGates.snapBeatsToQuantum(14.0, 1.0), 1e-9);
        assertEquals("6 拍 ⇒ 8", 8.0, RouteGates.snapBeatsToQuantum(6.0, 0.0), 1e-9);
        // **只补足、不缩短**（2026-10-07 新增，钉住上面那个窗口不随 scale 变长的 bug）：
        // 这两个数来自"8 拍 × window_scale 1.3 / 1.6"，四舍五入会把它们**同时**变成 12
        // ⇒ 用户把滑块拖大窗口却不变 ✗。量子只能往上补。
        assertEquals("10.4 拍（8×1.3）⇒ 12（补足）",
                12.0, RouteGates.snapBeatsToQuantum(10.4, 0.0), 1e-9);
        assertEquals("12.8 拍（8×1.6）⇒ 16（**不许缩回 12**）",
                16.0, RouteGates.snapBeatsToQuantum(12.8, 0.0), 1e-9);
        // 倍半 ⇒ 量子 2 拍：拍数吸到 2 的整数倍（这才是两边都能落准的公共量子）
        assertEquals("14 拍 ⇒ 14（已是 2 的倍数）", 14.0,
                RouteGates.snapBeatsToQuantum(14.0, 2.0), 1e-9);
        assertEquals("15 拍 ⇒ 16（向最近的 2 的倍数靠）", 16.0,
                RouteGates.snapBeatsToQuantum(15.0, 2.0), 1e-9);
        assertEquals("1 拍 ⇒ 2（至少一个量子，不允许 0 拍）", 2.0,
                RouteGates.snapBeatsToQuantum(1.0, 2.0), 1e-9);
        assertEquals("0.5 拍 ⇒ 4（同速时量子是 4，至少一个量子）", 4.0,
                RouteGates.snapBeatsToQuantum(0.5, 1.0), 1e-9);
        // 算不出来 ⇒ 原样
        assertEquals("拍数非正 ⇒ 原样（不猜）", -3.0, RouteGates.snapBeatsToQuantum(-3.0, 1.0), 1e-9);
        assertEquals("NaN ⇒ 原样", Double.NaN, RouteGates.snapBeatsToQuantum(Double.NaN, 1.0), 1e-9);
    }

    @Test
    public void unrelatedTemposAreReportedAsHavingNoRelation() {
        assertEquals("没有倍半关系 ⇒ 0（不猜）", 0.0, RouteGates.tempoAlias(120.0, 100.0), 1e-9);
        assertEquals("差一点点不算倍半（容差之外）",
                0.0, RouteGates.tempoAlias(150.0, 87.0), 1e-9);
        assertEquals("非正数 ⇒ 0（不猜）", 0.0, RouteGates.tempoAlias(0.0, 87.0), 1e-9);
        assertEquals("NaN ⇒ 0（不猜）", 0.0, RouteGates.tempoAlias(Double.NaN, 87.0), 1e-9);
    }
}
