package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>粗排必须看得见「结构」这一项</b>——否则精细评分的最优解根本进不了决赛圈
 * （2026-10-06 新增）。
 *
 * <h3>这一条钉的是什么</h3>
 *
 * <p>粗排（{@code planCollect} ①）只留 {@link AutoCue#SHORTLIST} 对进决赛圈，而它那五项
 * （连续性 / 出场走向 / 进场走向 / 低频 / 人声）全是<strong>接缝局部</strong>性质，
 * 一项都不看「这一刀切在不在音乐上的结构边界上」。可精细评分里结构是**加分**、
 * {@link AutoCue#STRUCTURE_OUTRO_SCALE} 放大之后出场侧一项就顶 0.15 分——
 * 于是"精细评分的最优解"可能压根轮不到被评分。
 *
 * <p>实测（参考对 skydive → 春めく）：Apple 的落点 {@code 181.4455 s}（乐句线）那一对在
 * 粗排里排 {@code 233/870}（另一对 {@code 278/870}）——两个都在前 96 之外。
 * 这就是之前「强度闸门」「乐句规则」踩过的同一个坑（见 {@code AutoCue} 里
 * {@code COARSE_GATE_PENALTY} / {@code COARSE_INTENSITY_WEIGHT} 那两段的注释）。
 *
 * <h3>夹具（可证伪的形状）</h3>
 *
 * <p>合成一对素材，把参考对那个形状放大成一眼能看出来的量级：
 *
 * <ul>
 *   <li><b>A（240 s）</b>：0~180 s 一直是 0.90（一大段高潮），180 s 之后掉到 0.35
 *       （尾声）。能量法的结构地标实测落在 <b>180.00 s</b>——它是一个
 *       <b>平坦的位置</b>（起音 0、每格不涨不跌），而粗排本来最喜欢"每格跌得快、
 *       起音大"的台阶两侧；</li>
 *   <li><b>B（200 s）</b>：前 30 s 0.35（安静引子，电平与 A 的尾声平台**故意一样**），
 *       之后 0.90。不设结构点（进场侧那半项按中性算，夹具只测出场侧）；</li>
 *   <li>构造上，粗排<strong>最喜欢</strong>的是台阶那一格（180 s：起音大、每格跌得快），
 *       而地标那一格起音为 0、每格不涨不跌，粗排代价高——正是参考对的形状。</li>
 * </ul>
 *
 * <p>于是这一条把两件事分开断言，**合起来才是可证伪的**：
 *
 * <ol>
 *   <li>{@link #theFineScoringItselfPrefersTheStructureLine}：用精细那套算术算出来，
 *       地标 180.00 s 那一对的**总分确实更高**（结构加分压过局部项目）——所以"选它"才是对的；</li>
 *   <li>{@link #thePlanActuallyLandsOnTheStructureLine}：跑真正的 {@link AutoCue#plan}，
 *       出场点必须落进地标的容忍带、而且不许跑到它之后——所以"结构那一族真的进了决赛圈"。
 *       **粗排看不见结构时第 1 条照样成立、第 2 条必红**（见下面的红-前）。</li>
 * </ol>
 *
 * <p><b>红-前（实测，2026-10-06 复跑）</b>：把 {@code planCollect} 里那一行
 * {@code coarse -= COARSE_FINE_SCALE * align} 去掉（也就是回到"粗排完全看不见
 * 结构/小节/新颖"的旧行为），<b>第 ② 条当场红、第 ① 条仍然绿</b>，实测失败信息逐字是
 * <pre>
 *   thePlanActuallyLandsOnTheStructureLine：
 *       「出场点必须落进结构目标的容忍带里，而且**不许落在它之后**……
 *        实测 <b>182.38</b> s vs 地标 180.0 s」
 * </pre>
 * 也就是它跑到了乐句线<strong>之后</strong>（A 已经掉下去了才切）。
 * 把那一行留着时实测落在 <b>177.62 s</b>（乐句线之前 2.38 s，与 Apple 在参考对上
 * "略早于乐理线"的形状一致）——这一条转绿。
 * 两条合起来才是"精细最优解进不了决赛圈"的直接证据。
 */
public class AutoCueCoarseStructureAdmissionTest {

    private static final double HOP = AutoCue.HOP_SECS;
    private static final double WINDOW = 5.0;

    /** 高潮结束、开始衰减的时刻——结构地标就落在这一段衰减里。 */
    private static final double STEP_SECS = 180.0;

    /** 衰减段的长度（秒）：要**平滑**，这样地标那一格不会被旧口径奖励。 */
    private static final double FADE_SECS = 6.0;

    /** 一个远离结构地标的对照点（A 的高潮中间）。 */
    private static final double MID_SECS = 100.0;

    /** A：180 s 高潮之后掉进尾声——结构地标（能量法）落在台阶前那一格。 */
    static AutoCue.Envelope outroWithAPlainStructureLine() {
        int hops = (int) Math.round(240.0 / HOP);
        float[] rms = new float[hops];
        for (int i = 0; i < hops; i++) {
            double t = i * HOP;
            if (t < STEP_SECS) {
                rms[i] = 0.90f;                       // 一大段高潮（平坦）
            } else if (t < STEP_SECS + FADE_SECS) {
                rms[i] = (float) (0.90 - 0.50 * (t - STEP_SECS) / FADE_SECS);
            } else {
                rms[i] = 0.35f;                       // 尾声平台（平坦：这里没有"每格跌得快"）
            }
        }
        return AutoCue.envelopeOf(rms, 240.0, null, 0, null, null, 0, -1f, -1f);
    }

    /** B：前 30 s 安静引子，之后主歌/副歌。 */
    static AutoCue.Envelope introWithAQuietHead() {
        int hops = (int) Math.round(200.0 / HOP);
        float[] rms = new float[hops];
        for (int i = 0; i < hops; i++) {
            // 引子的电平与 A 的尾声平台**故意做得一样**（0.35）：这样"粗排自己"
            // 最喜欢的是 A 尾声里那些又便宜（整窗均值接得上）、丢尾又少的点
            // ——离结构地标很远。结构加分进粗排之后，地标那一族才赢得过它们。
            rms[i] = (float) (i * HOP < 30.0 ? 0.35 : 0.90);
        }
        return AutoCue.envelopeOf(rms, 200.0);
    }

    /** 精细评分那一套算术（与 {@code planCollect} ② 的顺序一致：九项 → 结构 → 小节 → 新颖）。 */
    private static double fineTotal(AutoCue.Envelope outro, AutoCue.Envelope intro,
                                    double cut, double entry) {
        double outroStructure = AutoCue.outroStartSecs(outro);
        return AutoCue.evaluate(outro, intro, cut, entry, WINDOW)
                .withStructure(AutoCue.structureTerm(cut, outroStructure,
                        entry + WINDOW * 0.5, AutoCue.introStartSecs(intro),
                        AutoCue.STRUCTURE_OUTRO_SCALE, 1.0))
                .withBar(AutoCue.barBonus(outro, intro, cut, entry))
                .withNovelty(AutoCue.noveltyBonus(outro, intro, cut, entry))
                .total;
    }

    /**
     * <b>① 精细评分自己就偏好那条乐句线</b>：地标 180.00 s 那一对的总分高于高潮中间那一对。
     *
     * <p>这一条与粗排无关（直接调 {@code evaluate} + 那三个加分项），所以它是第 ② 条的
     * **前提**：只有"精细最优解在地标上"成立时，第 ② 条红了才说明是粗排的错。
     */
    @Test
    public void theFineScoringItselfPrefersTheStructureLine() {
        AutoCue.Envelope outro = outroWithAPlainStructureLine();
        AutoCue.Envelope intro = introWithAQuietHead();
        double landmark = AutoCue.outroStartSecs(outro);
        assertTrue("夹具：结构地标要落在衰减段里（实测 " + landmark + " s）",
                landmark >= STEP_SECS - 1.0 && landmark <= STEP_SECS + FADE_SECS);

        // 同一个进场点（B 的安静引子里）下比较：地标那一刀 vs 高潮中间那一刀
        double onLine = fineTotal(outro, intro, landmark, 25.0);
        double midTrack = fineTotal(outro, intro, MID_SECS, 25.0);
        assertTrue("精细评分必须偏好结构地标那一对（地标 " + onLine + " vs 高潮中间 "
                + midTrack + "）", onLine > midTrack);
    }

    /**
     * <b>② 计划真的落在那条乐句线上</b>——也就是"精细最优解进得了决赛圈"。
     *
     * <p>红-前：去掉粗排里那一项结构加分（{@code coarse -= COARSE_FINE_SCALE * align}），
     * 这一条当场红——实测落到 <b>182.38 s</b>（乐句线 180.00 之后，A 已经掉下去了），
     * 而第 ① 条仍然绿。
     */
    @Test
    public void thePlanActuallyLandsOnTheStructureLine() {
        AutoCue.Envelope outro = outroWithAPlainStructureLine();
        AutoCue.Envelope intro = introWithAQuietHead();
        double landmark = AutoCue.outroStartSecs(outro);
        AutoCue.Cue cue = AutoCue.plan(outro, intro, new double[]{WINDOW});
        assertNotNull("夹具上应当挑得出点位", cue);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[coarse-structure] 计划：出场 %.2f（结构地标 %.2f、高潮中间 %.1f）· 进场 %.2f"
                        + " · 窗口 %.2f%n    note：%s",
                cue.outroCutSecs, landmark, MID_SECS, cue.introEntrySecs, cue.windowSecs, cue.note));
        assertTrue("出场点必须落进结构目标的容忍带里，而且**不许落在它之后**——"
                        + "Apple 的实测（181.1）也是略早于那条乐理线（181.4455）；实测 "
                        + cue.outroCutSecs + " s vs 地标 " + landmark + " s",
                cue.outroCutSecs <= landmark + HOP
                        && cue.outroCutSecs >= landmark - AutoCue.STRUCTURE_TOLERANCE_SECS);
        // 「走向」那一项的措辞仍在说明行里（describe 的读数与打分是同一个量）
        assertTrue("说明行里应当有走向那句（在起/在收/平稳）：" + cue.note,
                cue.note.contains("在起") || cue.note.contains("在收") || cue.note.contains("平稳"));
    }
}
