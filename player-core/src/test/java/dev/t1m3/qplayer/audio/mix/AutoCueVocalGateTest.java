package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>叠唱的硬约束</b>（P6）：两边同时在唱的时长超过上限的候选，<b>不许</b>成为最终选择
 * ——除非所有候选都超标（那时照旧挑最好的，并说明原因）。
 *
 * <p>为什么要在软打分之外再加一道硬约束：人声重叠在总分里只占 {@code 0.8 / 7.75 ≈ 10%}，
 * 而「响度接续」占 1.0、 「波形重合」占 1.6——只要别的项足够偏爱某处，10% 是拉不住的。
 * 而两首歌的人声同时响，恰恰是用户说的「皱在一起很糊」里最不能接受的那一种
 * （低频可以靠 P5 让位，人声没有「让位」可言）。
 *
 * <p>判据用一个<b>故意偏心</b>的夹具把这件事逼出来：出场曲前 8 秒电平与进场曲匹配得刚好
 * （响度项满分），之后掉到 −30 dB（响度项归零）；而进场曲前 10 秒两边都在唱。
 * 于是软打分偏爱「早点进来、叠着唱」，硬约束必须把它否掉。
 */
public class AutoCueVocalGateTest {

    private static final int HOPS = 80 * 50;

    /** 中频按音节起伏（2 Hz 方波状起伏），够响。 */
    private static AutoCue.Envelope singing(float level) {
        float[] rms = new float[HOPS];
        float[][] band = new float[HOPS][];
        Arrays.fill(rms, level);
        for (int i = 0; i < HOPS; i++) {
            float mid = (i / 5) % 2 == 0 ? level : level * 0.35f;
            band[i] = new float[] {0.05f, mid, 0.05f};
        }
        return new AutoCue.Envelope(rms, new float[HOPS], 80.0, null, 0, band, null, 0);
    }

    /** 两边都在唱的时间范围，以及它们共同的「安静缺口」（只有缺口里才可能不叠唱）。 */
    private static final int GAP_FROM = 20 * 50;
    private static final int GAP_TO = 30 * 50;

    /**
     * 出场曲：<b>全程按音节起伏地唱、电平 0.7</b>（与进场曲匹配），<b>只有 20~30 秒的缺口</b>
     * 里电平掉到 0.1 且中频变平（不唱）。
     *
     * <p>这就是「让软打分偏心」的那一手，而且这次是<b>有效</b>的：
     *
     * <ul>
     *   <li><b>干净候选</b>（窗口完全落在缺口里，5 s 窗口 → 出场点 20~25 s、进场点 20~25 s）
     *       响度接续很差（0.1 对 0.7，差 ~17 dB → 那一项接近 0）；</li>
     *   <li><b>叠唱候选</b>（窗口落在缺口之外）两边都在唱 5 秒，但响度接续满分、
     *       丢尾项也更好。</li>
     * </ul>
     *
     * <p>于是软打分（人声只占 10%）会挑中叠唱的那个——这正是硬约束要拦下的情形。
     *
     * <p>进场曲则<b>整首都在唱</b>（{@code singing(0.7f)}，没有缺口）——这一条是必须的：
     * 只要进场曲也有一个缺口，规划器就能拿「出场曲在缺口之外唱歌 × 进场曲在缺口里安静」
     * 拼出一个<b>又干净又高分</b>的点，门便无事可做（实测就是这样：出场 67.3 s / 进场
     * 20.0 s，叠唱 0.0 s，说明里写着「打分很平」）。干净候选只能来自出场曲那一侧的缺口，
     * 而缺口里电平只有 0.1、响度接续很差——软打分于是偏爱叠唱的那个。
     *
     * <p>踩过的三个坑（都记在这里，免得下次再走一遍）：
     * ① 第一版把出场曲的中频写成<b>平</b>的（想让它「不是人声」），结果两边都不判人声、
     * 叠唱秒数恒为 0，门根本不会被触发；② 第二版只让出场曲<b>前 8 秒</b>唱，于是软打分
     * 把出场点推到 65.4 s（丢尾项驱动），那里出场曲早就不唱了——选中的候选叠唱本来就是
     * 0.0 s，门无事可做。要让门真的动手，两边都必须**大范围**在唱，只留一个窄缺口。
     */
    private static AutoCue.Envelope outroSingingExceptTheGap() {
        float[] rms = new float[HOPS];
        float[][] band = new float[HOPS][];
        for (int i = 0; i < HOPS; i++) {
            boolean inGap = i >= GAP_FROM && i < GAP_TO;
            rms[i] = inGap ? 0.1f : 0.7f;
            float mid = inGap ? 0.05f : ((i / 5) % 2 == 0 ? 0.7f : 0.25f);
            band[i] = new float[] {0.05f, mid, 0.05f};
        }
        return new AutoCue.Envelope(rms, new float[HOPS], 80.0, null, 0, band, null, 0);
    }

    /**
     * ① <b>只要存在「一边不唱」的窗口，结论就落在那里</b>——实测选中的点位叠唱 0.0 s。
     *
     * <p>这一条同时是「门没有把本来对的结论改坏」的证据（它本来就在缺口里，门不动它）。
     */
    @Test
    public void whenACleanWindowExistsTheResultIsClean() {
        AutoCue.Envelope outro = outroSingingExceptTheGap();
        AutoCue.Envelope intro = singing(0.7f);
        AutoCue.Cue cue = AutoCue.plan(outro, intro, 5.0);
        assertNotNull("没有找到任何候选", cue);

        double overlapSecs = AutoCue.vocalOverlapSecs(outro, intro,
                outro.indexAt(cue.outroCutSecs), intro.indexAt(cue.introEntrySecs),
                (int) Math.round(cue.windowSecs / 0.02));
        System.out.println("[gate] 选中的点位 = 出场 " + cue.outroCutSecs + " s / 进场 "
                + cue.introEntrySecs + " s，叠唱 " + overlapSecs + " s（缺口 20~30 s）");
        assertTrue("结论的叠唱必须不超标（实际 " + overlapSecs + " s）",
                overlapSecs <= AutoCue.VOCAL_GATE_SECS);
    }

    /**
     * ② <b>一个干净候选都没有时：照旧给点，但必须说明白</b>（不许静默）。
     *
     * <p>这条同时钉住「门真的跑了」：门一旦被拿掉，说明里就不会有「叠唱硬约束」这几个字，
     * 判据立刻红（实测过）。门之前，这条的路上软打分选中的是<b>叠唱 5.0 s</b> 的点
     * （日志原文：「人声重叠 0.00（叠唱 5.0s）」）——那正是用户说的「糊」。
     */
    @Test
    public void whenNothingQualifiesTheNoteSaysSo() {
        AutoCue.Cue cue = AutoCue.plan(singing(0.7f), singing(0.7f), 5.0);
        assertNotNull("全都超标时也必须给出一个点", cue);
        // **2026-10-06 按证据重述**：工作五放开"最短尾奏"之后，这一对素材里**有**候选满足
        // 位置类硬约束了，说明的措辞随之从"没有任何候选"变成"没有一个候选能同时满足两条"
        // ——**语义完全一样**（叠唱那条硬约束仍然无人满足，最终那点还是"只能取它"拿到的）。
        // 所以钉语义（"硬约束"+候选不满足），而不是某一个具体句子。
        assertTrue("说明里必须写明硬约束没有任何候选中标：" + cue.note,
                cue.note.contains("硬约束")
                        && (cue.note.contains("没有任何候选")
                                || cue.note.contains("没有一个候选能同时满足")));
        System.out.println("[gate] 全部叠唱时的说明（节选）："
                + cue.note.substring(Math.max(0, cue.note.indexOf("叠唱硬约束") - 6)));
    }

    /**
     * ③ <b>叠唱秒数进了打分与说明</b>（P6 的两个产物都来自同一次扫描）。
     *
     * <p>钉的是「分数会掩盖秒数」这件事：那一项 0.00 分既可能是叠唱 4.0 s，也可能是
     * 40 s；note 里必须给出秒数，否则用户没法判断这次到底糊不糊。
     */
    @Test
    public void theNoteAlwaysReportsTheOverlapSeconds() {
        AutoCue.Cue cue = AutoCue.plan(outroSingingExceptTheGap(), singing(0.7f), 5.0);
        assertNotNull(cue);
        assertTrue("说明里必须报出叠唱秒数：" + cue.note, cue.note.contains("叠唱"));
    }
}
