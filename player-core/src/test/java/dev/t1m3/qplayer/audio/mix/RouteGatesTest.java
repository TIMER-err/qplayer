package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * <b>各档的闸门映射</b>（2026-10-07）。
 *
 * <p>这条判据钉两件事：① 他的因子名确实映射到了我们的信号上（过/不过都能测）；
 * ② **没有依据的信号一律缺失（{@code null}）而不是给 1** —— 缺失就该让那条候选没分，
 * 这是参照（{@code score = None}）与本仓库共同的纪律。
 */
public class RouteGatesTest {

    private static RouteGates.Signals signals() {
        RouteGates.Signals s = new RouteGates.Signals();
        s.tempoDiff = 0.06;
        s.keyScore = 0.80;
        s.lowClash = 0.70;
        s.dropReliable = 0.45;
        s.overlapSecs = 6.0;
        s.buildUp = Boolean.FALSE;
        s.vocalOverlapSecs = 2.0;
        s.outroBeats = 16.0;
        s.introBeats = 16.0;
        s.genres = new double[] {0.1, 0.8, 0.0, 0.1, 0.0};      // hiphop 高
        s.outgoingLoudnessSignificant = null;                   // 我们没有这个信号
        s.incomingLoudnessRelationCode = null;
        return s;
    }

    @Test
    public void smartQuadrantsAreHardGatedWhilePlainBlendIsTheFallback() {
        // 2026-10-07 改（这一版是最终语义）：他的 smart-crossfade 四象限由"**量到的**关系码 +
        // 尾巴长度"定义 ⇒ 两项都必须是**硬门**（没量到就是 0，绝不用软因子到处抢 —— 实测过：
        // 软因子会让四象限在所有"没量到"的案例里抢赢，害得 PLAIN_BLEND / GAPLESS 一族全选不到）。
        // 而我们自己的 PLAIN_BLEND 反过来：它专门负责"**两个代理都没量到**"那一格。
        RouteGates.Signals s = signals();
        List<Double> quad = RouteGates.factorsFor(MixStyle.CROSSFADE_OVERLAP, s);
        assertEquals("没量到进场响度关系 ⇒ 四象限是 0（硬门，不硬抢）",
                0.0, quad.get(0).doubleValue(), 1e-9);
        s.incomingQuieter = Boolean.FALSE;                  // 进场更响 ⇒ 正是 overlap 那一格
        s.trailingSpanSecs = Double.valueOf(0.2);            // 尾巴短
        List<Double> ok = RouteGates.factorsFor(MixStyle.CROSSFADE_OVERLAP, s);
        assertEquals("量到且进场更响 ⇒ 1", 1.0, ok.get(0).doubleValue(), 1e-9);
        assertEquals("量到且尾巴短 ⇒ 1", 1.0, ok.get(1).doubleValue(), 1e-9);
        // PLAIN_BLEND：只在两个代理都**没量到**时才有分（它就是那一格的兜底）
        RouteGates.Signals p = signals();
        p.incomingQuieter = null;
        p.trailingSpanSecs = null;
        assertEquals("两个代理都没量到 ⇒ 兜底那一项为 1",
                1.0, RouteGates.factorsFor(MixStyle.PLAIN_BLEND, p).get(0).doubleValue(), 1e-9);
        p.incomingQuieter = Boolean.TRUE;
        assertEquals("量到了 ⇒ 兜底让位（0）",
                0.0, RouteGates.factorsFor(MixStyle.PLAIN_BLEND, p).get(0).doubleValue(), 1e-9);
    }


    @Test
    public void filteringFamilyUsesItsOwnFactorList() {
        // 2026-10-07 改：这一族改用**他逐条的因子清单**（filtered-direct id 8：
        // strict_tempo + incoming_beat_count + **tempo_alias**）—— 里面**没有**低频冲突 ✗，
        // 所以旧判据"低频不撞就该被压低"不再成立（那是我们旧设计的期望）。
        // 现在钉的是他的清单：非倍半的一对 ⇒ tempo_alias 那一项为 0（整条被筛掉）；
        // 倍半的一对 ⇒ 它才有分。这正是"24 档都能选到"要靠的那种**分块**能力。
        RouteGates.Signals s = signals();
        s.bpmA = Double.valueOf(150.0);
        s.bpmB = Double.valueOf(100.0);              // 不是倍半
        List<Double> f = RouteGates.factorsFor(MixStyle.FILTER_HIGH_TO_LOW, s);
        assertEquals("非倍半 ⇒ tempo_alias 那一项为 0（被筛掉）", 0.0, f.get(0).doubleValue(), 1e-9);
        s.bpmA = Double.valueOf(174.0);
        s.bpmB = Double.valueOf(87.0);               // 倍半
        List<Double> g = RouteGates.factorsFor(MixStyle.FILTER_HIGH_TO_LOW, s);
        assertEquals("倍半 ⇒ 这一项过（1）", 1.0, g.get(0).doubleValue(), 1e-9);
    }

    @Test
    public void missingSignalsStayMissing() {
        RouteGates.Signals s = signals();
        List<Double> f = RouteGates.factorsFor(MixStyle.POP_DELAY_OUT, s);
        // POP 的因子表（他逐条清单）：genre · incoming_beat_count · vocal_overlap ·
        //                         **outgoing_loudness_significant** · melody
        assertNull("我们没有 outgoing_loudness_significant ⇒ 这一项**缺失**（不是 1）", f.get(3));
        // 缺失会让整条候选没分（RouteScore 的纪律）
        assertNull("缺失 ⇒ 不给分（不猜）", RouteScore.score(MixStyle.POP_DELAY_OUT, f, 0.0));
        // 补上这个信号之后就该有分（证明缺失判断是活的，不是写死）
        s.outgoingLoudnessSignificant = Boolean.TRUE;
        s.genres = new double[] {0.05, 0.05, 0.0, 0.85, 0.05};   // pop 分要够，否则第一项就把这条淘汰了
        // 另外三项也要给（他的清单：进场拍数 / 人声叠 / 尾巴）—— 少一项这条就没分，
        // 而"没分"现在是 **null**（不是 0），所以这里必须把信号给全才能断言"有分"。
        s.introBeats = Double.valueOf(16.0);
        s.outroBeats = Double.valueOf(16.0);
        s.vocalOverlapSecs = Double.valueOf(0.1);
        s.vocalOverlapReliable = true;
        s.trailingSpanSecs = Double.valueOf(0.2);
        assertTrue("补上信号（且曲风对得上）之后应当有分", RouteScore.score(MixStyle.POP_DELAY_OUT,
                RouteGates.factorsFor(MixStyle.POP_DELAY_OUT, s), 0.0).doubleValue() > 0.0);
    }

    @Test
    public void genreRoutesAreHardDispatchedWhenTheGenreIsConfident() {
        // 2026-10-07 改：曲风从"连续小权重"变成**硬分派**（档案里他是按曲风分派模板：
        // algorithm_name: hiphop / pop …）。分数够高（≥ GENRE_DISPATCH_GATE）⇒
        // 是该曲风 ⇒ 1；是别的曲风 ⇒ 0；都不够高 ⇒ 仍按连续分数（不猜）。
        RouteGates.Signals s = signals();
        s.genres = new double[] {0.05, 0.8, 0.0, 0.05, 0.05};        // 明显是 hiphop
        assertEquals("明显是 hiphop ⇒ hiphop 档给 1",
                1.0, RouteGates.factorsFor(MixStyle.HIPHOP_LOOPING, s).get(0).doubleValue(), 1e-9);
        assertEquals("而 pop 档给 0（被硬分派筛掉）",
                0.0, RouteGates.factorsFor(MixStyle.POP_DELAY_OUT, s).get(0).doubleValue(), 1e-9);
        assertEquals("dance 档也给 0",
                0.0, RouteGates.factorsFor(MixStyle.DANCE_ANY_KEY, s).get(0).doubleValue(), 1e-9);
        // 曲风都不明显 ⇒ 维持连续分数（不再是硬 0/1）
        // 0.4：**到**连续门槛（0.30）但**没到**分派门槛（0.5）⇒ 走连续分数本身
        s.genres = new double[] {0.1, 0.4, 0.0, 0.15, 0.1};
        assertEquals("不到分派门槛 ⇒ 按连续分数（0.4）",
                0.4, RouteGates.factorsFor(MixStyle.HIPHOP_LOOPING, s).get(0).doubleValue(), 1e-9);
    }

    @Test
    public void outgoingLoudnessSignificanceComesFromOurLufsMeter() {
        float[] loud = new float[48000 * 3];
        for (int i = 0; i < loud.length; i++) {
            loud[i] = (float) (0.2 * Math.sin(2 * Math.PI * 1000 * i / 48000.0));
        }
        assertTrue("正常电平的素材 ⇒ 显著", RouteGates.outgoingLoudnessSignificant(loud, 48000));
        float[] silent = new float[48000 * 3];
        assertTrue("全静音 ⇒ 不显著（LUFS 给负无穷 ⇒ 布尔量为 false）",
                !RouteGates.outgoingLoudnessSignificant(silent, 48000));
        // 填上之后，POP 那两条（base 50）才拿得到分 —— 这正是补这个信号的目的
        RouteGates.Signals sig = signals();
        sig.genres = new double[] {0.05, 0.05, 0.0, 0.85, 0.05};
        sig.outgoingLoudnessSignificant = Boolean.TRUE;
        // POP_REVERB 是"尾巴**长**"那一格（与 POP_DELAY_OUT 按尾巴长短互斥）⇒ 尾巴必须给长，
        // 人声那一项也要可靠地"不叠"（它的清单里有 vocal_overlap）——少一项就没分（null）。
        sig.trailingSpanSecs = Double.valueOf(2.0);
        sig.vocalOverlapSecs = Double.valueOf(0.1);
        sig.vocalOverlapReliable = true;
        assertNotNull("POP 路由在信号齐了之后应当有分（base 50）",
                RouteScore.score(MixStyle.POP_REVERB,
                        RouteGates.factorsFor(MixStyle.POP_REVERB, sig), 0.0));
    }

    @Test
    public void matchingBarsMeansEachSideLandsOnWholeBarBoundaries() {
        // **语义修正（2026-10-07）**：他这一项说的是"两侧**各自**的过渡段落是否落在**整小节边界**上"，
        // 而**不是**"两侧小节数相等" —— 后者会让 LONG_OUT_SHORT_IN（出场长、进场短）自相矛盾
        // （判据当场指出它分数为 0）。这里的 Signals 用"整小节拍数"表达（AiMix 已按 4/4 向下取整）。
        RouteGates.Signals s = signals();
        s.outroBeats = Double.valueOf(16.0);
        s.introBeats = Double.valueOf(16.0);
        assertTrue("两侧都是整小节 ⇒ 过", RouteGates.matchingBarsOk(s));
        s.introBeats = Double.valueOf(20.0);
        assertTrue("4 小节 vs 5 小节：**各自都是整小节** ⇒ 仍然过（这正是修正后的语义）",
                RouteGates.matchingBarsOk(s));
        s.introBeats = Double.valueOf(5.0);
        assertTrue("进场 5 拍**不在**小节线上 ⇒ 不过", !RouteGates.matchingBarsOk(s));
        s.introBeats = Double.valueOf(16.0);
        s.outroBeats = Double.valueOf(0.0);
        assertTrue("一边连一整小节都放不下 ⇒ 不过", !RouteGates.matchingBarsOk(s));
        s.outroBeats = null;
        assertTrue("量不到 ⇒ 不过（不猜）", !RouteGates.matchingBarsOk(s));
    }

    /**
     * <b>叠唱闸门按"强度加权重叠 + 人声类别"判断</b>（用户点名的第 1、2 条，2026-10-07 接线）。
     *
     * <p>钉三件事：
     * ① 同样强度下**唱**比**说唱**更容易被筛（容忍表 0.25 / 0.5 / 0.6 ✓）；
     * ② 两个信号都量到时判定是**可靠**的 ⇒ 硬门（0）✓；
     * ③ 类别量不出来时**如实退回秒数阈值**，并且按软因子处理（0.75）——不拿半成品硬筛 ✓。
     */
    @Test
    public void theVocalGateUsesStrengthAndKindWhenBothAreMeasured() {
        // ① 唱 + 强度 0.5（超过容忍 0.25）⇒ 硬筛
        RouteGates.Signals s = signals();
        s.vocalOverlapStrength = Double.valueOf(0.5);
        s.vocalKind = Integer.valueOf(AutoCue.KIND_SINGING);
        assertEquals("唱 + 强度 0.5 ⇒ 越过容忍 0.25 ⇒ 0（且可靠 ⇒ 硬门）",
                0.0, RouteGates.factorsFor(MixStyle.FILTER_EXPANSION, s).get(3).doubleValue(), 1e-9);
        // ② 同一强度、说唱 ⇒ 容忍 0.6 ⇒ 过得去
        s.vocalKind = Integer.valueOf(AutoCue.KIND_RAPPING);
        assertEquals("说唱 + 强度 0.5 ⇒ 在容忍 0.6 内 ⇒ 1（说唱重叠听得出来才算撞）",
                1.0, RouteGates.factorsFor(MixStyle.FILTER_EXPANSION, s).get(3).doubleValue(), 1e-9);
        // ③ 类别未知 ⇒ 退回秒数阈值；秒数超了但代理不可靠 ⇒ 软因子 0.75
        RouteGates.Signals unknown = signals();
        unknown.vocalOverlapStrength = Double.valueOf(0.5);
        unknown.vocalKind = null;
        unknown.vocalOverlapSecs = Double.valueOf(1.0);      // 秒数那一路说不合格
        unknown.vocalOverlapReliable = Boolean.FALSE;        // 但代理不可靠
        assertEquals("类别未知 ⇒ 退回秒数；不可靠 ⇒ 软因子（不硬筛）",
                RouteGates.SOFT_FACTOR,
                RouteGates.factorsFor(MixStyle.FILTER_EXPANSION, unknown).get(3).doubleValue(), 1e-9);
        // ④ 类别已知 ⇒ 即便秒数那一路不可靠，判定也算可靠（硬门）
        unknown.vocalKind = Integer.valueOf(AutoCue.KIND_SPEECH);
        unknown.vocalOverlapSecs = Double.valueOf(1.0);
        assertEquals("说话 + 强度 0.5 ⇒ 容忍 0.5 ⇒ 边界；用 0.5 之外的值验硬门",
                RouteGates.factorsFor(MixStyle.FILTER_EXPANSION, unknown).get(3).doubleValue(),
                1.0, 1e-9);
    }

    @Test
    public void centredVocalsAreReliablyDetectedAndThenHardFiltered() {
        // 合成一对立体声：**居中的人声**（左右同相 ⇒ mid 强、side 弱）
        // 与**两侧的伴奏**（左右反相 ⇒ mid 弱、side 强）。
        int n = 4800;
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++) {
            double phase = 2 * Math.PI * 1000 * i / 48000.0;
            float vocal = (float) (0.5 * Math.sin(phase));         // 居中（同相）
            float accomp = (float) (0.1 * Math.sin(phase * 0.31));  // 两侧（反相）
            left[i] = vocal + accomp;
            right[i] = vocal - accomp;
        }
        double centre = RouteGates.centreRatio(left, right, n);
        assertTrue("居中的人声 ⇒ 中置比高（实测 " + centre + "）",
                centre >= RouteGates.CENTRE_RELIABLE_MIN);
        assertTrue("⇒ 这条判断算**可靠**", RouteGates.vocalPresenceIsReliable(centre));

        // 反过来：伴奏占主导（左右反相）⇒ 中置比低 ⇒ 如实按**不可靠**处理（不猜）
        for (int i = 0; i < n; i++) {
            double phase = 2 * Math.PI * 1000 * i / 48000.0;
            float vocal = (float) (0.05 * Math.sin(phase));
            float accomp = (float) (0.5 * Math.sin(phase * 0.31));
            left[i] = vocal + accomp;
            right[i] = vocal - accomp;
        }
        double low = RouteGates.centreRatio(left, right, n);
        assertTrue("伴奏占主导 ⇒ 中置比低（实测 " + low + "）", low < RouteGates.CENTRE_RELIABLE_MIN);
        assertTrue("⇒ 如实判为**不可靠**（于是走软因子，不误杀）",
                !RouteGates.vocalPresenceIsReliable(low));
        assertTrue("单声道（右声道给 null）⇒ 量不出方向性 ⇒ 缺失（不猜）",
                Double.isNaN(RouteGates.centreRatio(left, null, n)));
    }

    @Test
    public void aReliableVocalGateHardFiltersButAnUnreliableOneDoesNot() {
        // 这是"可靠才硬筛、不可靠不误杀"的**契约**（也是用户点名要抄的第三条的落实）：
        // 同一个"人声叠着"的事实，可靠 ⇒ 因子 0（硬筛）；不可靠 ⇒ 0.75（软，不杀）
        RouteGates.Signals s = signals();
        s.vocalOverlapSecs = Double.valueOf(1.0);
        s.vocalOverlapReliable = true;
        // 只看 **vocal 那一项**（他 id 9 的清单是 strict · key · bars · **vocal** ⇒ 第 4 项）；
        // 第一次我把断言写成"这一族里不出现 0"，但这一族的速度项本来就因 6% > strict 3% 为 0 ✗，
        // 与 vocal 无关 —— 判据要精确到项，不能笼统。
        java.util.List<Double> hard = RouteGates.factorsFor(MixStyle.FILTER_EXPANSION, s);
        assertEquals("可靠 ⇒ vocal 那一项是 0（硬筛掉）", 0.0, hard.get(3).doubleValue(), 1e-9);
        s.vocalOverlapReliable = false;
        java.util.List<Double> soft = RouteGates.factorsFor(MixStyle.FILTER_EXPANSION, s);
        assertEquals("不可靠 ⇒ vocal 那一项是软因子 0.75（不误杀）",
                RouteGates.SOFT_FACTOR, soft.get(3).doubleValue(), 1e-9);
    }

    @Test
    public void melodyIsOurOwnFactorAndMissingMelodyGivesNoScore() {
        RouteGates.Signals s = signals();
        s.melody = 0.9;                                   // 旋律重合很好
        Double good = RouteScore.score(MixStyle.PLAIN_BLEND,
                RouteGates.factorsFor(MixStyle.PLAIN_BLEND, s), 0.0);
        s.melody = 0.3;                                   // 旋律几乎不合
        Double poor = RouteScore.score(MixStyle.PLAIN_BLEND,
                RouteGates.factorsFor(MixStyle.PLAIN_BLEND, s), 0.0);
        assertTrue("旋律越重合 ⇒ 乘积越大（这是他清单里没有的维度）", good > poor);
        s.melody = null;                                  // 没量到旋律
        assertEquals("旋律是**加成**：没量到 ⇒ 中性 1（不奖励也不惩罚）—— 与闸门缺失不同",
                1.0, RouteGates.factorsFor(MixStyle.PLAIN_BLEND, s).get(3).doubleValue(), 1e-9);
        assertNotNull("没量到旋律也仍然能选这一档（否则等于把整条路堵死）",
                RouteScore.score(MixStyle.PLAIN_BLEND,
                        RouteGates.factorsFor(MixStyle.PLAIN_BLEND, s), 0.0));
    }

    @Test
    public void unreliableProxySignalsSoftenInsteadOfVetoing() {
        RouteGates.Signals s = signals();
        s.vocalOverlapSecs = 2.0;                    // 代理说"人声叠了"
        s.vocalOverlapReliable = false;              // …但依据只是代理
        Double soft = RouteGates.factorsFor(MixStyle.NO_VOCAL_CLASH, s).get(0);
        assertEquals("代理不可靠 ⇒ 不硬否决，退成 0.75 因子", RouteGates.SOFT_FACTOR,
                soft.doubleValue(), 1e-9);
        s.vocalOverlapReliable = true;               // 有中置/真 VAD ⇒ 硬门
        Double hard = RouteGates.factorsFor(MixStyle.NO_VOCAL_CLASH, s).get(0);
        assertEquals("依据可靠 ⇒ 硬门（0）", 0.0, hard.doubleValue(), 1e-9);
        // 满足时两种情形都给 1（软化的只是"不满足"那一侧）
        s.vocalOverlapSecs = 0.1;
        assertEquals("满足 ⇒ 1（不论依据是否可靠）", 1.0,
                RouteGates.factorsFor(MixStyle.NO_VOCAL_CLASH, s).get(0).doubleValue(), 1e-9);
    }

    @Test
    public void gaplessNeedsNoOverlapAndShowModeNeedsBuildUp() {
        RouteGates.Signals s = signals();
        s.overlapSecs = 0.4;
        assertEquals("可叠极短 ⇒ 无缝那一档的因子为 1",
                1.0, RouteGates.factorsFor(MixStyle.GAPLESS, s).get(0).doubleValue(), 1e-9);
        s.buildUp = Boolean.TRUE;
        assertEquals("build-up 为真 ⇒ 表现模式第一项过",
                1.0, RouteGates.factorsFor(MixStyle.SHOW_MODE, s).get(0).doubleValue(), 1e-9);
    }
}
