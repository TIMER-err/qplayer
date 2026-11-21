package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * <b>参照打分核</b>（2026-10-07）：{@code P = base × Π(factors)} + {@code 0.001 × D}。
 *
 * <p>这条判据钉住三条性质 —— 它们正是"乘性硬闸"与"加权求和"的分水岭：
 * ① 任一因子为 0 ⇒ 整条淘汰（哪怕别的因子很大）；② 位置项只值千分之一，
 * 翻不了闸门的盘；③ 信号缺失 ⇒ 不给分（不猜）。
 */
public class RouteScoreTest {

    private static final double EPS = 1e-9;

    @Test
    public void anyZeroFactorKillsTheCandidate() {
        Double s = RouteScore.score(MixStyle.HIPHOP_LOOPING,            // base 100，最高
                Arrays.asList(1.0, 1.0, 0.0, 5.0), 999.0);
        // 2026-10-07 内核按他的口径对齐：**P > 0 才有分** ⇒ 闸门没过 ⇒ 根本没有分（null），
        // 而不是 0。这是更强的性质（0 分还可能被"矮子里拔将军"选中 ✗，实测踩过）。
        assertNull("有闸门没过 ⇒ 根本没有分（别的因子再大也没用）", s);
    }

    @Test
    public void positionOnlyBreaksTiesAndNeverBeatsAGate() {
        double a = RouteScore.score(MixStyle.PLAIN_BLEND, Arrays.asList(1.0), 0.0);      // base 10
        double b = RouteScore.score(MixStyle.PLAIN_BLEND, Arrays.asList(1.0), 100000.0);
        assertTrue("位置项给到十万 ⇒ 多约 100 分（0.001 × D）", b - a > 99.0);
        // **真正的性质**：闸门没过 ⇒ 再大的位置优势也救不回来（这是"闸门决定一切"）
        Double gated = RouteScore.score(MixStyle.HIPHOP_LOOPING,      // base 100（最高）
                Arrays.asList(0.0), 1e12);                            // 闸门没过 + 天文数字的位置优势
        assertNull("闸门没过就是**没有分**，位置项再大也翻不了盘", gated);
        // 反过来也如实：D 可以为负且不夹紧 ⇒ 一个很大的负 D 能把高分候选压得很低
        // （参照原文就写着 No clamping / D may be negative —— 判据量事实，不量愿望）
        double sunk = RouteScore.score(MixStyle.HIPHOP_LOOPING, Arrays.asList(1.0), -1e9);
        assertTrue("负 D 会如实压低分数（不做保护性夹紧）", sunk < 0.0);
    }

    @Test
    public void missingSignalsGiveNoScore() {
        assertNull("信号缺失 ⇒ 不给分（不猜）",
                RouteScore.score(MixStyle.PLAIN_BLEND, Arrays.asList(1.0, null), 0.0));
        assertNull("整组缺失同理", RouteScore.score(MixStyle.PLAIN_BLEND, null, 0.0));
    }

    @Test
    public void theBestPickIsByProductNotBySum() {
        List<MixStyle> styles = Arrays.asList(MixStyle.PLAIN_BLEND, MixStyle.FILTER_HIGH_TO_LOW);
        // 朴素融合：一个因子 0.9（乘积 0.9 × base10 = 9）；滤波：两个因子各 0.7（乘积 0.49 × base20 = 9.8）
        List<List<Double>> sets = Arrays.asList(Arrays.asList(0.9), Arrays.asList(0.7, 0.7));
        List<Double> pos = Arrays.asList(0.0, 0.0);
        assertEquals("乘性核下胜者是乘积×base 更大的那条", MixStyle.FILTER_HIGH_TO_LOW,
                RouteScore.best(styles, sets, pos));
        // 对照：如果是"加权求和"，0.9 会赢 0.7+0.7=1.4 的判法完全不同 —— 这条判据就是防走偏的
    }

    @Test
    public void selectionIsDeterministicAndNeverDrawsLots() {
        // 用户明确要求：**不要参照那套"种子（两首歌毫秒数）取 UInt64 模"的抽签** ——
        // 同样的素材必须得到同样的结果。这条判据把"确定性"钉死：
        List<MixStyle> styles = Arrays.asList(MixStyle.PLAIN_BLEND, MixStyle.FILTER_HIGH_TO_LOW,
                MixStyle.DANCE_ANY_KEY);
        List<List<Double>> sets = Arrays.asList(Arrays.asList(0.5), Arrays.asList(0.5, 0.5),
                Arrays.asList(0.4));
        List<Double> pos = Arrays.asList(0.0, 0.0, 0.0);
        MixStyle first = RouteScore.best(styles, sets, pos);
        for (int i = 0; i < 200; i++) {
            assertEquals("同样的输入必须给出同样的结果（不许有任何随机源）",
                    first, RouteScore.best(styles, sets, pos));
        }
        // 完全平手时按**固定顺序**取（列表里靠前的胜）——这就是"抽签"的确定性替代
        List<MixStyle> tie = Arrays.asList(MixStyle.PLAIN_BLEND, MixStyle.CROSSFADE_LONG);
        List<List<Double>> same = Arrays.asList(Arrays.asList(1.0), Arrays.asList(1.0));
        List<Double> zero = Arrays.asList(0.0, 0.0);
        assertEquals("base 10 对 base 10、因子相同 ⇒ 取列表里靠前的那个（固定顺序）",
                MixStyle.PLAIN_BLEND, RouteScore.best(tie, same, zero));
    }

    @Test
    public void baseScoresFollowTheReferenceList() {
        assertEquals("嘻哈两条是 base 100（参照清单）", 100, RouteScore.base(MixStyle.HIPHOP_LOOPING));
        assertEquals("Filter expansion 是 base 50", 50, RouteScore.base(MixStyle.FILTER_EXPANSION));
        assertEquals("filtered 家族 20", 20, RouteScore.base(MixStyle.FILTER_HIGH_TO_LOW));
        assertEquals("dance 17", 17, RouteScore.base(MixStyle.DANCE_ANY_KEY));
        assertEquals("smart 10", 10, RouteScore.base(MixStyle.CROSSFADE_LONG));
        assertEquals("兜底 1", 1, RouteScore.base(MixStyle.GAPLESS));
        assertEquals("我们独有档（表现模式/学习EQ）用 BASE_OURS",
                RouteScore.BASE_OURS, RouteScore.base(MixStyle.SHOW_MODE));
    }
}
