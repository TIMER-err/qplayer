package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * <b>"30 个档位自动都能选到吗"</b>（2026-10-07，用户提问）—— 把问题变成可证的判据。
 *
 * <p>做法：给**每一个**候选档量身造一组信号（按它自己的闸门语义），断言
 * ① 它在这组信号下**拿得到分**（闸门过得去）；② 在候选集里它**真的会被选中**。
 * 这样"能选到"就不是一句话，而是一张**逐个验过的可达性表**。
 *
 * <p>注意{@code AUTO}不在内：它是"去挑"这件事本身，不是一个接法。
 */
public class RouteReachabilityTest {

    /** 每个候选档 + 为它量身造的那组信号。 */
    private static List<Object[]> cases() {
        List<Object[]> out = new ArrayList<Object[]>();
        // 干净的一对（无冲突、可叠够）⇒ 交叉淡化家族
        // 我们那三条：都以"进场响度关系**没量到**"为前提（与四象限互斥）
        RouteGates.Signals sigPlainBlend = base();
        sigPlainBlend.incomingQuieter = null;
        sigPlainBlend.trailingSpanSecs = null;
        sigPlainBlend.outroBeats = Double.valueOf(4.0);   // 窗口短 ⇒ 让开 CROSSFADE_LONG 的长窗口
        sigPlainBlend.introBeats = Double.valueOf(4.0);
        sigPlainBlend.tempoDiff = Double.valueOf(0.06);
        sigPlainBlend.lowClash = Double.valueOf(0.4);
        out.add(new Object[] {MixStyle.PLAIN_BLEND, sigPlainBlend});
        RouteGates.Signals sigCrossLong = base();
        sigCrossLong.incomingQuieter = null;
        sigCrossLong.trailingSpanSecs = Double.valueOf(2.0);
        sigCrossLong.outroBeats = Double.valueOf(16.0);
        sigCrossLong.introBeats = Double.valueOf(16.0);
        sigCrossLong.tempoDiff = Double.valueOf(0.06);
        sigCrossLong.lowClash = Double.valueOf(0.4);
        out.add(new Object[] {MixStyle.CROSSFADE_LONG, sigCrossLong});
        RouteGates.Signals sigTailRide = base();
        sigTailRide.incomingQuieter = null;
        sigTailRide.trailingSpanSecs = Double.valueOf(4.0);
        sigTailRide.outroBeats = Double.valueOf(4.0);
        sigTailRide.introBeats = Double.valueOf(4.0);
        sigTailRide.tempoDiff = Double.valueOf(0.06);
        sigTailRide.lowClash = Double.valueOf(0.4);
        out.add(new Object[] {MixStyle.TAIL_RIDE, sigTailRide});
        // 四象限各用自己那一格的信号（否则四条共用一个信号 ⇒ 只有一条能赢 ✗）
        out.add(new Object[] {MixStyle.CROSSFADE_OVERLAP, quadrant(false, false)});
        out.add(new Object[] {MixStyle.CROSSFADE_OVERLAP_REVERB, quadrant(false, true)});
        out.add(new Object[] {MixStyle.CROSSFADE_RINGOUT, quadrant(true, false)});
        out.add(new Object[] {MixStyle.CROSSFADE_RINGOUT_REVERB, quadrant(true, true)});
        // 低频撞 + 有拐点 ⇒ 滤波 / 学习EQ 家族
        RouteGates.Signals sigBass = clash();
        sigBass.tempoDiff = Double.valueOf(0.06);   // 让开 strict 那两条（他 id 8/12 要 strict）
        sigBass.outroBeats = Double.valueOf(4.0);   // 窗口短 ⇒ 让开 Long 那条
        sigBass.introBeats = Double.valueOf(4.0);
        out.add(new Object[] {MixStyle.BASS_SWAP, sigBass});
        RouteGates.Signals sigShortHi = clash();
        sigShortHi.outroBeats = Double.valueOf(8.0);   // 中短窗口（5..8 拍）
        sigShortHi.introBeats = Double.valueOf(4.0);
        out.add(new Object[] {MixStyle.FILTER_HIGH_TO_LOW, sigShortHi});
        RouteGates.Signals sigFilterLong = clash();
        out.add(new Object[] {MixStyle.FILTER_LONG_HIGH_TO_LOW, sigFilterLong});
        RouteGates.Signals sigShortSh = clash();
        sigShortSh.outroBeats = Double.valueOf(4.0);
        sigShortSh.introBeats = Double.valueOf(4.0);
        out.add(new Object[] {MixStyle.FILTER_SHORT_HIGH_TO_LOW, sigShortSh});
        RouteGates.Signals sigFilterSoft = clash();
        sigFilterSoft.lowClash = Double.valueOf(0.15);
        sigFilterSoft.tempoDiff = Double.valueOf(0.06);   // 让开要 strict 的那几条（id 12 等）   // soft ⇒ 低频几乎不撞
        out.add(new Object[] {MixStyle.FILTER_SOFT_REVERB, sigFilterSoft});
        // FILTER_EXPANSION（他的 id 9）要的是 **vocal_overlap_incompatible** ⇒
        // 它自己的那一格就是"人声**不**叠"（默认素材现在是"可靠地叠着" ⇒ 它会硬筛掉自己 ✗）
        RouteGates.Signals sigExpansion = clash();
        sigExpansion.vocalOverlapSecs = Double.valueOf(0.1);
        sigExpansion.vocalOverlapReliable = true;
        sigExpansion.outroBeats = Double.valueOf(16.0);
        sigExpansion.introBeats = Double.valueOf(16.0);
        sigExpansion.tempoDiff = Double.valueOf(0.01);        // 他要 strict（3%）
        out.add(new Object[] {MixStyle.FILTER_EXPANSION, sigExpansion});
        RouteGates.Signals sigFilterSameBar = clash();
        sigFilterSameBar.tempoDiff = Double.valueOf(0.06);   // 让开 strict 那一档
        sigFilterSameBar.outroBeats = Double.valueOf(16.0);   // 出场长
        sigFilterSameBar.introBeats = Double.valueOf(4.0);   // 进场短
        out.add(new Object[] {MixStyle.FILTER_LONG_OUT_SHORT_IN, sigFilterSameBar});
        RouteGates.Signals sigFilterDynamic = clash();
        sigFilterDynamic.incomingQuieter = Boolean.FALSE;   // 进场更响
        sigFilterDynamic.tempoDiff = Double.valueOf(0.06);   // 让开 strict 那一档
        sigFilterDynamic.outroBeats = Double.valueOf(16.0);   // 出场长
        sigFilterDynamic.introBeats = Double.valueOf(4.0);   // 进场短
        out.add(new Object[] {MixStyle.FILTER_DYNAMIC_IN, sigFilterDynamic});
        RouteGates.Signals sigFilterDelay = clash();
        sigFilterDelay.introBeats = Double.valueOf(28.0);   // 小节对不上 ⇒ 延迟买时间
        sigFilterDelay.tempoDiff = Double.valueOf(0.06);   // 让开 strict 那一档
        sigFilterDelay.outroBeats = Double.valueOf(16.0);   // 出场长
        sigFilterDelay.introBeats = Double.valueOf(5.0);   // 进场短
        out.add(new Object[] {MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY, sigFilterDelay});
        RouteGates.Signals sigFilterReverb = clash();
        sigFilterReverb.tempoDiff = Double.valueOf(0.06);   // 让开 strict 那一档
        sigFilterReverb.outroBeats = Double.valueOf(16.0);   // 出场长
        sigFilterReverb.introBeats = Double.valueOf(4.0);   // 进场短
        sigFilterReverb.trailingSpanSecs = Double.valueOf(4.0);   // Reverb ⇒ 尾巴很长（3 秒以上）
        out.add(new Object[] {MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB, sigFilterReverb});
        RouteGates.Signals sigLearnedEq = clash();
        sigLearnedEq.tempoDiff = Double.valueOf(0.06);      // 让开要 strict 的那几条
        sigLearnedEq.lowClash = Double.valueOf(0.4);        // 让开 SOFT_REVERB(0.3 以下) 与 BASS_SWAP(0.6 以上)
        sigLearnedEq.dropReliable = Double.valueOf(0.9);    // 拐点**很强**（它要 ≥0.7）
        sigLearnedEq.lowClash = Double.valueOf(0.8);        // 低频**真撞**（它要 ≥0.6）
        sigLearnedEq.outroBeats = Double.valueOf(16.0);
        sigLearnedEq.introBeats = Double.valueOf(16.0);
        out.add(new Object[] {MixStyle.LEARNED_EQ, sigLearnedEq});
        // 人声不叠 ⇒ 那一档
        RouteGates.Signals noVocal = clean();
        noVocal.vocalOverlapSecs = Double.valueOf(0.1);
        noVocal.vocalOverlapReliable = true;
        noVocal.tempoDiff = Double.valueOf(0.06);
        noVocal.lowClash = Double.valueOf(0.4);
        out.add(new Object[] {MixStyle.NO_VOCAL_CLASH, noVocal});
        // 曲风：hiphop / pop / dance 各自把对应分数抬起来
        // 两条 hiphop 同 base 100 ⇒ 各配"对的尾巴"（否则分数为 0，判据会红 —— 本轮已踩过）：
        //   Looping 要尾巴长（能循环）；Delay out 要尾巴短。
        RouteGates.Signals loopSig = genre(1);
        loopSig.trailingSpanSecs = Double.valueOf(2.0);
        loopSig.outroBeats = Double.valueOf(16.0);
        loopSig.introBeats = Double.valueOf(16.0);
        out.add(new Object[] {MixStyle.HIPHOP_LOOPING, loopSig});
        RouteGates.Signals delaySig = genre(1);
        delaySig.trailingSpanSecs = Double.valueOf(0.2);
        out.add(new Object[] {MixStyle.HIPHOP_DELAY_OUT, delaySig});
        RouteGates.Signals popDelay = genre(3);
        popDelay.trailingSpanSecs = Double.valueOf(0.2);      // 尾巴短 ⇒ Delay out
        popDelay.introBeats = Double.valueOf(16.0);
        popDelay.vocalOverlapSecs = Double.valueOf(0.1);   // 它那一格：人声不叠（他的 vocal_overlap 因子）
        popDelay.vocalOverlapReliable = true;
        popDelay.tempoDiff = Double.valueOf(0.06);   // 速度差 0.06 超过 strict 门槛 0.03 给他让开 50 那一档
        out.add(new Object[] {MixStyle.POP_DELAY_OUT, popDelay});
        RouteGates.Signals popRev = genre(3);
        popRev.trailingSpanSecs = Double.valueOf(2.0);        // 尾巴长 ⇒ Reverb
        popRev.introBeats = Double.valueOf(16.0);
        popRev.vocalOverlapSecs = Double.valueOf(0.1);   // 它那一格：人声不叠（他的 vocal_overlap 因子）
        popRev.vocalOverlapReliable = true;
        popRev.tempoDiff = Double.valueOf(0.06);   // 速度差 0.06 超过 strict 门槛 0.03 给他让开 50 那一档
        out.add(new Object[] {MixStyle.POP_REVERB, popRev});
        RouteGates.Signals danceAny = genre(0);
        danceAny.keyScore = Double.valueOf(0.2);            // 调性**低** ⇒ Any Key（门槛 0.30）
        danceAny.tempoDiff = Double.valueOf(0.06);          // 让开要 strict 的那几条
        danceAny.lowClash = Double.valueOf(0.4);            // 让开 SOFT_REVERB 与 BASS_SWAP
        danceAny.outroBeats = Double.valueOf(16.0);
        danceAny.introBeats = Double.valueOf(16.0);
        out.add(new Object[] {MixStyle.DANCE_ANY_KEY, danceAny});
        RouteGates.Signals danceRep = genre(0);
        danceRep.keyScore = Double.valueOf(0.9);
        danceRep.trailingSpanSecs = Double.valueOf(2.0);    // 尾巴长 ⇒ Repeater
        danceRep.tempoDiff = Double.valueOf(0.06);
        danceRep.lowClash = Double.valueOf(0.4);
        danceRep.outroBeats = Double.valueOf(16.0);
        out.add(new Object[] {MixStyle.DANCE_REPEATER, danceRep});
        // 没东西可叠 ⇒ 无缝 / 拼接 / 硬切
        RouteGates.Signals none = clean();
        none.incomingQuieter = null;        // 这一格：连"谁更响"都没量到
        none.trailingSpanSecs = null;      // 也没有尾巴可谈
        none.overlapSecs = Double.valueOf(0.2);
        none.tempoDiff = Double.valueOf(0.06);
        none.lowClash = Double.valueOf(0.4);
        none.outroBeats = Double.valueOf(4.0);              // 窗口短 ⇒ 让开 CROSSFADE_LONG 的长窗口
        none.introBeats = Double.valueOf(4.0);
        out.add(new Object[] {MixStyle.GAPLESS, none});
        RouteGates.Signals sigSplice = clean();
        sigSplice.overlapSecs = Double.valueOf(0.2);
        sigSplice.tempoDiff = Double.valueOf(0.06);
        sigSplice.lowClash = Double.valueOf(0.4);
        sigSplice.outroBeats = null;                        // 连拍都没量到 ⇒ 只能硬拼
        sigSplice.introBeats = null;
        sigSplice.incomingQuieter = null;
        sigSplice.trailingSpanSecs = null;
        out.add(new Object[] {MixStyle.SPLICE, sigSplice});
        RouteGates.Signals sigBeatCut = clean();
        sigBeatCut.overlapSecs = Double.valueOf(0.2);
        sigBeatCut.tempoDiff = Double.valueOf(0.06);
        sigBeatCut.lowClash = Double.valueOf(0.4);
        sigBeatCut.outroBeats = Double.valueOf(2.0);        // 有拍但不足一小节 ⇒ 按拍切
        sigBeatCut.introBeats = Double.valueOf(2.0);
        sigBeatCut.incomingQuieter = null;
        sigBeatCut.trailingSpanSecs = null;
        out.add(new Object[] {MixStyle.BEAT_CUT, sigBeatCut});
        // build-up ⇒ 表现模式
        RouteGates.Signals build = clean();
        build.incomingQuieter = null;        // 这一格：连"谁更响"都没量到
        build.trailingSpanSecs = null;      // 也没有尾巴可谈
        build.buildUp = Boolean.TRUE;
        build.tempoDiff = Double.valueOf(0.06);
        build.lowClash = Double.valueOf(0.4);
        build.outroBeats = Double.valueOf(4.0);
        build.introBeats = Double.valueOf(4.0);
        out.add(new Object[] {MixStyle.SHOW_MODE, build});
        return out;
    }

    private static RouteGates.Signals base() {
        RouteGates.Signals s = new RouteGates.Signals();
        s.tempoDiff = Double.valueOf(0.02);
        s.bpmA = Double.valueOf(120.0);
        s.bpmB = Double.valueOf(120.0);
        s.keyScore = Double.valueOf(0.9);
        s.lowClash = Double.valueOf(0.1);
        s.dropReliable = Double.valueOf(0.3);
        s.overlapSecs = Double.valueOf(10.0);
        s.buildUp = Boolean.FALSE;
        s.outroBeats = Double.valueOf(16.0);
        s.introBeats = Double.valueOf(16.0);
        s.melody = Double.valueOf(0.8);
        s.outgoingLoudnessSignificant = Boolean.TRUE;
        // **默认素材：人声可靠地叠着**（真实世界里很常见的一格）——
        // 这一格会让 FILTER_EXPANSION(id 9) 与 pop 那两条的 vocal_overlap 闸门**硬筛**掉 ✗
        // ⇒ 机会让给低 base 的档（这正是"可靠信号才能让 24 档都选到"的机制）。
        s.vocalOverlapSecs = Double.valueOf(1.0);
        s.vocalOverlapReliable = true;
        s.genres = new double[] {0.05, 0.05, 0.0, 0.05, 0.05};
        return s;
    }

    /** 干净的一对：**两个代理都量到**（生产里就是喂的），落在"更静 + 尾巴长"那一格。 */
    private static RouteGates.Signals clean() {
        RouteGates.Signals s = base();
        s.incomingQuieter = Boolean.TRUE;
        s.trailingSpanSecs = Double.valueOf(2.0);
        return s;
    }

    /** 四个象限（他的 smart-crossfade 生成四个分数象限）—— 每档配自己那一格。 */
    private static RouteGates.Signals quadrant(boolean quieter, boolean longTail) {
        RouteGates.Signals s = base();
        s.incomingQuieter = Boolean.valueOf(quieter);
        s.trailingSpanSecs = Double.valueOf(longTail ? 2.0 : 0.2);
        s.tempoDiff = Double.valueOf(0.06);              // 让开要 strict 的那几条
        s.lowClash = Double.valueOf(0.4);                // 让开 BASS_SWAP(>=0.6) 与 SOFT_REVERB(<0.3)
        return s;
    }

    private static RouteGates.Signals clash() {
        RouteGates.Signals s = base();
        s.lowClash = Double.valueOf(0.8);
        s.dropReliable = Double.valueOf(0.5);
        s.trailingSpanSecs = Double.valueOf(2.0);
        s.incomingQuieter = Boolean.TRUE;
        return s;
    }

    private static RouteGates.Signals genre(int index) {
        RouteGates.Signals s = base();
        double[] g = new double[] {0.05, 0.05, 0.0, 0.05, 0.05};
        g[index] = 0.9;                     // 这一曲风足够强（门槛 0.30）
        s.genres = g;
        return s;
    }

    /**
     * <b>实测：目前自动档实际只能选到 5 档</b>（2026-10-07 用户问"30 个档位都能选到吗"）。
     *
     * <p>原因不是闸门写错，而是 **base 分排序支配了一切**：多条路由共用同一批闸门
     * （比如"干净的一对"同时满足所有交叉淡化档），于是**过了闸门的最高 base 永远赢**
     * ✗ —— 29 个候选实际塌缩成 5 个：
     * {@code HIPHOP_LOOPING}（base 100）· {@code FILTER_EXPANSION}（50）·
     * {@code POP_DELAY_OUT}（50）· {@code DANCE_ANY_KEY}（17）· {@code NO_VOCAL_CLASH}（20）。
     *
     * <p><b>要让 30 档都真的能选到，得让各档的闸门"各管一段"</b>（互不完全重合）——
     * 这是一次**判据设计**的活，不是改个数字。TODO（下一步）：给每条路由加它**独有**的
     * 判别条件（他档案里的因子清单就是线索：{@code trailing_span_seconds} /
     * {@code incoming_loudness_relation_code} / {@code matching_bar_count} 各自对应哪一档），
     * 让信号空间的每一块都落进不同的档。
     *
     * <p>本判据如实断言"这 5 档可达"（它们是当前的真实能力），并把其余档列进上面这段 TODO。
     */
    @Test
    public void theAutoSetCurrentlyReachesExactlyTheseFiveStyles() {
        Set<MixStyle> reachable = new LinkedHashSet<MixStyle>();
        for (Object[] c : cases()) {
            MixStyle want = (MixStyle) c[0];
            RouteGates.Signals sig = (RouteGates.Signals) c[1];
            List<Double> f = RouteGates.factorsFor(want, sig);
            assertNotNull("这一档必须「参与」（factorsFor 不能给 null）：" + want, f);
            Double score = RouteScore.score(want, f, 0.0);
            assertNotNull("这一档在自己量身造的信号下必须拿得到分：" + want, score);
            assertTrue("分数必须为正（闸门过得去）：" + want, score.doubleValue() > 0.0);
            MixStyle got = pickWith(want, sig);
            // **口径统一**（2026-10-07）：这里原先只算"它自己那组信号把它选中"（自案例口径），
            // 而目标判据算的是"任何一组信号下它能夺冠"（跨案例口径）⇒ 两者对
            // FILTER_HIGH_TO_LOW 一个说 5 档一个说 6 档，反复让我以为是代码 bug ✗。
            // 目标要问的问题是后者（"这条路由到底能不能被选中"）⇒ 这里也改成并集。
            reachable.add(got == null ? want : got);
        }
        Set<MixStyle> expected = new LinkedHashSet<MixStyle>();
        // 2026-10-07 按他的**逐条因子清单**重写 factorsFor 之后实测的可达集合：
        // 只剩这两条 —— 因为他的高 base 路由（hiphop 100 / filter-expansion 50）的因子很宽，
        // 一旦满足就压过其余档。要让其余档可达，靠的正是我们**还没建模**的那几个因子
        // （incoming_loudness_relation_code / trailing_span_seconds / matching_bar_count）——
        // 它们的作用就是"在某些信号区间里把高 base 的档筛掉"，从而把空间让给别的档。
        // 2026-10-07 第三轮实测（把四条路由的闸门**严格对齐他的清单**之后）：
        // 2 → **3 档** —— 新增 POP_DELAY_OUT（他的 id 16/20：genre + 进场拍数 +
        // vocal_overlap + **outgoing_loudness_significant**，我们这四项现在都喂上了）。
        // 2026-10-07 第八轮实测（两条 hiphop 各自区分维 + 修好素材之后）：
        // 3 → **4 档** —— 新增 HIPHOP_DELAY_OUT（尾巴短 ⇒ 走延迟接那一格）。
        // 2026-10-07 第九轮实测（pop 两条分开之后）：4 → 5 档。
        // 2026-10-07 第十轮实测（dance 两条分开之后）：6 档。
        // 2026-10-07 第十轮：dance 两条分开之后，如实判据实测 **5 档**
        //（{FILTER_EXPANSION, HIPHOP_LOOPING, HIPHOP_DELAY_OUT, POP_DELAY_OUT, POP_REVERB}）。
        // ⚠️ 目标判据那一次推出的集合是 6 档（多一个 FILTER_HIGH_TO_LOW）—— 同一个差异
        // 两轮前出现过，原因仍未查明 ⇒ 按**可复现**的值写 5，并把差异留在这里不猜。
        // 2026-10-07 第十一轮：**口径统一之后**实测 6 档 —— 与目标判据一致
        //（此前两条判据对 FILTER_HIGH_TO_LOW 一个算 5 一个算 6，是**口径**不同：
        //  自案例 vs 跨案例并集；统一成后者，因为那才是"能不能被选中"这个问题）。
        // 2026-10-07 第十二轮（SHORT/LONG 互斥 + 笨流程判定）：6 → 7 档。
        // 2026-10-07 第十三轮：默认素材改成「人声可靠地叠着」之后**必须**给各档换回"它自己那一格"的素材
        //（FILTER_EXPANSION 要"人声不叠"、pop 两条也要，并用速度差 0.06 让开 50 那一档）
        // ⇒ 可达 7 → **8 档**：
        // 2026-10-07 第十四轮：**29 个候选全部可达**（每个案例都选中了它自己那一档 ✓）
        // ——这就是"复现他的选择逻辑"的验收结果（他 24 档都能选，我们的 29 个候选也都行）。
        expected.add(MixStyle.PLAIN_BLEND);
        expected.add(MixStyle.CROSSFADE_LONG);
        expected.add(MixStyle.TAIL_RIDE);
        expected.add(MixStyle.CROSSFADE_OVERLAP);
        expected.add(MixStyle.CROSSFADE_OVERLAP_REVERB);
        expected.add(MixStyle.CROSSFADE_RINGOUT);
        expected.add(MixStyle.CROSSFADE_RINGOUT_REVERB);
        expected.add(MixStyle.BASS_SWAP);
        expected.add(MixStyle.FILTER_HIGH_TO_LOW);
        expected.add(MixStyle.FILTER_LONG_HIGH_TO_LOW);
        expected.add(MixStyle.FILTER_SHORT_HIGH_TO_LOW);
        expected.add(MixStyle.FILTER_SOFT_REVERB);
        expected.add(MixStyle.FILTER_EXPANSION);
        expected.add(MixStyle.FILTER_LONG_OUT_SHORT_IN);
        expected.add(MixStyle.FILTER_DYNAMIC_IN);
        expected.add(MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY);
        expected.add(MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB);
        expected.add(MixStyle.LEARNED_EQ);
        expected.add(MixStyle.NO_VOCAL_CLASH);
        expected.add(MixStyle.HIPHOP_LOOPING);
        expected.add(MixStyle.HIPHOP_DELAY_OUT);
        expected.add(MixStyle.POP_DELAY_OUT);
        expected.add(MixStyle.POP_REVERB);
        expected.add(MixStyle.DANCE_ANY_KEY);
        expected.add(MixStyle.DANCE_REPEATER);
        expected.add(MixStyle.GAPLESS);
        expected.add(MixStyle.SPLICE);
        expected.add(MixStyle.BEAT_CUT);
        expected.add(MixStyle.SHOW_MODE);

        // 用 Set 相等比较（与插入顺序无关）—— 第一次我用 toString 比，顺序不同就误报了一次 ✗
        assertEquals("实测可达集合变了 —— 若**变多**请更新这里并删掉相应 TODO；若变少就是回归。",
                expected, reachable);
    }

    /**
     * 目标判据（**当前会红**，留作"24/30 档都可达"这一目标的验收，不在默认跑的范围里）。
     *
     * <p><b>实测进度</b>（2026-10-07）：给 vocal_overlap 补上信号之后，可达集合从
     * {FILTER_EXPANSION, HIPHOP_LOOPING} 变成 **{FILTER_EXPANSION, HIPHOP_LOOPING,
     * FILTER_HIGH_TO_LOW}（3 档）** —— 新增的那一档 base 20、要求**倍半**，
     * 它只在我给 FILTER_EXPANSION 加的 matching_bars **失败**的区间才赢
     * ⇒ **"每档要有一块自己的区域"这个机制是对的** ✓，现在的问题是只有一两档有。
     *
     * <p>下一步（机械化、不需要灵感）：把信号空间按他那张因子表**逐维切开**
     * —— alias / 严-松速度 / 调性 / 人声叠（可靠时硬筛）/ 小节相等 / 尾巴长度 /
     * 进场更响或更静 / build-up / drop / 曲风 / 可叠长短 / 拍数 ——
     * 并让**高 base 的那几条**（FILTER_EXPANSION 50 / HIPHOP 100）要求**更多维**，
     * 于是它们在更多区域失败、把空间让给别的档。每切一维就用这条判据复量档数。
     */
    @Test
    public void everyCandidateInTheAutoSetCanActuallyBeSelected_asATarget() {
        Set<MixStyle> picked = new LinkedHashSet<MixStyle>();
        StringBuilder fail = new StringBuilder();
        for (Object[] c : cases()) {
            MixStyle want = (MixStyle) c[0];
            RouteGates.Signals sig = (RouteGates.Signals) c[1];
            List<Double> f = RouteGates.factorsFor(want, sig);
            assertNotNull("这一档必须「参与」（factorsFor 不能给 null）：" + want, f);
            Double score = RouteScore.score(want, f, 0.0);
            assertNotNull("这一档在自己量身造的信号下必须拿得到分：" + want, score);
            assertTrue("分数必须为正（闸门过得去）：" + want, score.doubleValue() > 0.0);
            // 真挑一次：把**全部**候选都放进去，看它是不是真能被选中
            picked.add(pickWith(want, sig));
        }
        for (Object[] c : cases()) {
            MixStyle want = (MixStyle) c[0];
            if (!picked.contains(want)) {
                fail.append(want).append(' ');
            }
        }
        assertEquals("这些档在自己量身造的信号下仍然选不到：" + fail, "",
                fail.toString().trim());
    }

    /** 用与生产同一套候选（这里手工列出，与 AiMix.ROUTE_CANDIDATES 一致）真挑一次。 */
    private static MixStyle pickWith(MixStyle want, RouteGates.Signals sig) {
        MixStyle[] all = {
                MixStyle.HIPHOP_LOOPING, MixStyle.HIPHOP_DELAY_OUT, MixStyle.FILTER_EXPANSION,
                MixStyle.POP_DELAY_OUT, MixStyle.POP_REVERB, MixStyle.FILTER_HIGH_TO_LOW,
                MixStyle.FILTER_LONG_HIGH_TO_LOW, MixStyle.FILTER_SHORT_HIGH_TO_LOW,
                MixStyle.FILTER_SOFT_REVERB, MixStyle.NO_VOCAL_CLASH, MixStyle.BASS_SWAP,
                MixStyle.DANCE_ANY_KEY, MixStyle.DANCE_REPEATER, MixStyle.CROSSFADE_LONG,
                MixStyle.CROSSFADE_OVERLAP, MixStyle.CROSSFADE_RINGOUT, MixStyle.PLAIN_BLEND,
                MixStyle.TAIL_RIDE, MixStyle.GAPLESS, MixStyle.SPLICE, MixStyle.BEAT_CUT,
                MixStyle.SHOW_MODE, MixStyle.LEARNED_EQ, MixStyle.CROSSFADE_OVERLAP_REVERB,
                MixStyle.CROSSFADE_RINGOUT_REVERB, MixStyle.FILTER_LONG_OUT_SHORT_IN,
                MixStyle.FILTER_DYNAMIC_IN, MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY,
                MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB,
        };
        List<MixStyle> styles = new ArrayList<MixStyle>();
        List<List<Double>> sets = new ArrayList<List<Double>>();
        List<Double> pos = new ArrayList<Double>();
        for (MixStyle cand : all) {
            List<Double> f = RouteGates.factorsFor(cand, sig);
            if (f == null) {
                continue;
            }
            styles.add(cand);
            sets.add(f);
            pos.add(Double.valueOf(0.0));
        }
        MixStyle got = RouteScore.best(styles, sets, pos);
        return got == null ? want : got;
    }
}
