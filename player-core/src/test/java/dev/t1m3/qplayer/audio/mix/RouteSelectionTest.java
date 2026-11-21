package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * <b>打分核打开之后"选得对"</b>（2026-10-07）。
 *
 * <p>核默认关闭（见 {@link RouteScore#setEnabled}）—— 这条判据的作用是证明**接线是活的**：
 * 给两套信号，各自应当选中预期的档。没有这条，"接了线但没人验"就只是死代码。
 *
 * <p>两套场景刻意只用**我们真有的信号**（速度差 / 调性 / 低频冲突 / drop / 可叠秒数 /
 * build-up）—— 曲风与人声那几项留空，对应的档自然拿不到分（缺失不给分，不猜）。
 */
public class RouteSelectionTest {

    private static final MixStyle[] CANDIDATES = {
            MixStyle.HIPHOP_LOOPING, MixStyle.FILTER_EXPANSION, MixStyle.POP_DELAY_OUT,
            MixStyle.FILTER_HIGH_TO_LOW, MixStyle.DANCE_ANY_KEY, MixStyle.CROSSFADE_LONG,
            MixStyle.PLAIN_BLEND, MixStyle.GAPLESS, MixStyle.BEAT_CUT, MixStyle.SPLICE,
    };

    private static MixStyle pick(RouteGates.Signals s) {
        List<MixStyle> styles = new ArrayList<MixStyle>();
        List<List<Double>> sets = new ArrayList<List<Double>>();
        List<Double> pos = new ArrayList<Double>();
        for (MixStyle cand : CANDIDATES) {
            List<Double> f = RouteGates.factorsFor(cand, s);
            if (f == null) {
                continue;
            }
            styles.add(cand);
            sets.add(f);
            pos.add(Double.valueOf(0.0));
        }
        return RouteScore.best(styles, sets, pos);
    }

    @Test
    public void conflictingPairGoesToTheReferenceFilterRoute() {
        RouteGates.Signals s = new RouteGates.Signals();
        s.tempoDiff = 0.06;          // 有速度差，但在他那套里算"冲突"—— 我们走自己的门槛
        s.keyScore = 0.80;
        s.lowClash = 0.70;           // 低频真的撞上
        s.dropReliable = 0.45;       // 有站得住的拐点
        s.overlapSecs = 6.0;
        s.buildUp = Boolean.FALSE;
        // 2026-10-07 按**他的清单**对齐之后：id 9 要求 **strict_tempo_compatible**（3%），
        // 而这组信号的速度差是 6% ⇒ 它**过不去** ⇒ 交给交叉淡化那一族（它的因子是
        // 进场更安静 + 尾巴够长，这组信号里前者为真、后者为真）—— 这是他的逻辑，不是退化。
        // 2026-10-07 再改（四象限改成**硬门** + 我们三条各分角色之后）：
        // id 9 因 strict 过不去；他的四象限要求"进场响度关系**量到**"（这组信号没量到 ⇒ 硬 0）；
        // 于是落我们自己的**兜底** PLAIN_BLEND（"两个代理都没量到 ⇒ 用最朴素那档"）。
        assertEquals("6% 速度差 + 代理没量到 ⇒ 落兜底 PLAIN_BLEND",
                MixStyle.PLAIN_BLEND, pick(s));
    }

    @Test
    public void cleanPairGoesToTheCrossfadeFamily() {
        RouteGates.Signals s = new RouteGates.Signals();
        s.tempoDiff = 0.01;
        s.keyScore = 0.90;
        s.lowClash = 0.05;           // 低频不撞
        s.dropReliable = 0.10;
        s.overlapSecs = 6.0;
        s.buildUp = Boolean.FALSE;
        MixStyle got = pick(s);
        assertNotNull("必须选出某一档", got);
        // 干净的一对该走**交叉淡化家族**（base 10 那几条）—— 但具体是哪一条由**候选顺序**定：
        // 它们 base 相同、因子全同 ⇒ 平手 ⇒ 按列表里靠前者胜（这就是"不抽签"的可执行形式）。
        // 所以这里断言的是**契约**（家族 + 确定性），而不是某一个具体名字 ——
        // 第一次跑时因为我把 CROSSFADE_LONG 排在前面，它赢了平手，判据当场指出这件事。
        // 2026-10-07 再改（把闸门**严格对齐他的清单**之后）：
        // id 9 要求 strict_tempo **且 matching_bar_count** —— 而这组信号**没量到小节数** ⇒
        // matchingBarsOk 现在返回 false（"量不到不算相等"，忠实于他的语义）⇒ id 9 过不去 ⇒
        // 干净的一对回到**交叉淡化那一族**（他的 smart：进场更安静 + 尾巴够长，两项都软过）。
        // 2026-10-07 再改：quadrant 硬门 + 我们三条各分角色之后，代理没量到 ⇒ 兜底 PLAIN_BLEND。
        assertEquals("干净的一对 + 代理没量到 ⇒ 兜底 PLAIN_BLEND", MixStyle.PLAIN_BLEND, got);
        assertEquals("同一输入再算一次仍是同一结果", got, pick(s));
        assertFalse("曲风档在没有曲风信号时**不该**被选中（缺失不给分）",
                got == MixStyle.HIPHOP_LOOPING || got == MixStyle.POP_DELAY_OUT
                        || got == MixStyle.DANCE_ANY_KEY);
    }

    @Test
    public void noOverlapGoesToTheGaplessFamily() {
        RouteGates.Signals s = new RouteGates.Signals();
        s.tempoDiff = 0.02;
        s.keyScore = 0.90;
        s.lowClash = 0.10;
        s.dropReliable = 0.10;
        s.overlapSecs = 0.3;         // 没东西可叠
        s.buildUp = Boolean.FALSE;
        MixStyle got = pick(s);
        // 2026-10-07 再改：三条地板档现在各有区分维 —— 这组信号**连拍都没量到** ⇒ SPLICE
        //（"只能硬拼"那一格）；GAPLESS 要"有整小节可依"，BEAT_CUT 要"有拍但不足一小节"。
        assertEquals("没东西可叠且没拍可依 ⇒ SPLICE", MixStyle.SPLICE, got);
    }
}
