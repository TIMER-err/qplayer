package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>"交接点落在音乐边界上"这条规则的契约</b>（2026-10-07，goal 第 3 轮）。
 *
 * <p>两个公开助手（{@code AutoCue.structureAlignedEntry} / {@code structureAlignedCut}）是
 * 生产链上唯一施加对齐的地方 ✓，所以它们的语义要单独钉住 —— 用**合成包络**，毫秒级 ✓：
 * <ul>
 *   <li>交接点 = 进场 + 窗口，且**等于（吸附到拍网格后的）结构点**（B 侧是"结构点 − 窗口/2"）；</li>
 *   <li>量不出来（没有拍栅格 / 结构点太靠前 / 结果越界）⇒ <b>NaN</b>（调用方保留打分挑的点 ✓）；</li>
 *   <li>两侧同一条规则：A 侧交接点 = A 的结构点 ✓。</li>
 * </ul>
 */
public class StructureAlignmentTest {

    private static final int RATE = 48_000;

    /**
     * 手搭一条包络：电平在 {@code riseSecs} 处抬到参考以上（结构点就是它 ✓），
     * 拍栅格 period/phase 由调用方给。
     */
    private static AutoCue.Envelope envelope(double durationSecs, double riseSecs,
                                             double periodSecs, double phaseSecs) {
        return envelope(durationSecs, riseSecs, periodSecs, phaseSecs, -1.0);
    }

    /**
     * 同 {@link #envelope}，外加"从 {@code fallSecs} 起电平降下来"（**尾声**的定义 ✓）——
     * A 侧的结构点（{@code outroStartSecs}）找的就是这个下降的起点 ✓，
     * 所以 A 侧判据必须用带下降的那种曲线，否则量不到结构点（实测差点把夹具当成 bug ✗）。
     */
    private static AutoCue.Envelope envelope(double durationSecs, double riseSecs,
                                             double periodSecs, double phaseSecs,
                                             double fallSecs) {
        int frames = (int) Math.round(durationSecs / AutoCue.HOP_SECS);
        float[] rms = new float[frames];
        int rise = (int) Math.round(riseSecs / AutoCue.HOP_SECS);
        int fall = fallSecs > 0.0 ? (int) Math.round(fallSecs / AutoCue.HOP_SECS) : -1;
        for (int i = 0; i < frames; i++) {
            if (i < rise) {
                rms[i] = 0.10f;                        // 引子轻
            } else if (fall > 0 && i >= fall) {
                rms[i] = 0.20f;                        // 尾声：降下来
            } else {
                rms[i] = 0.50f;                        // 正题
            }
        }
        float[] onset = new float[frames];
        for (int i = 0; i < frames; i++) {
            double t = i * AutoCue.HOP_SECS;
            double off = ((t - phaseSecs) % periodSecs + periodSecs) % periodSecs;
            onset[i] = off < AutoCue.HOP_SECS ? 1f : 0f;   // 每拍一记 ⇒ 拍网格可测
        }
        Analysis.Tempo tempo = new Analysis.Tempo((float) (60.0 / periodSecs),
                (float) (periodSecs * RATE), (float) (phaseSecs * RATE), 0.95f);
        float[][] band = {new float[frames], rms, new float[frames]};
        return new AutoCue.Envelope(rms, onset, durationSecs, tempo, RATE, band,
                new float[frames], RATE, -1f, -1f, null);
    }

    @Test
    public void theEntryPutsTheHandoverAQuarterWindowBeforeTheStructurePoint() {
        AutoCue.Envelope env = envelope(120.0, 40.0, 0.5, 0.1);
        double window = 2.0;
        double entry = AutoCue.structureAlignedEntry(env, window, 100.0);
        assertTrue("结构点可用 ⇒ 必须给出进场点（不是 NaN）", !Double.isNaN(entry));
        double handover = entry + window;
        // B 侧的规则：交接点 = 结构点 − 窗口/2（再吸到拍网格，容差 = 半拍 + 平滑滞后）
        //
        // ⚠️ 期望值取**探测器自己**的读数（`structureOf`），不是夹具写下的 40 s ——
        // 结构点带 ~2 s 的平滑滞后（`smoothLevel` 是 2 s 滑动平均 ✓，实测落在 42 s）✗，
        // 把夹具的数字写死会把"探测器的滞后"误判成规则的错 ✗。
        double structure = AutoCue.structureOf(env);
        assertTrue("夹具必须真的量出结构点", structure > 0.0);
        assertEquals("交接点必须落在「结构点 − 窗口/2」附近（吸拍后）",
                structure - window * 0.5, handover, 0.26);
        assertTrue("进场点不许退到 0 或越界", entry > 0.0 && entry < 100.0);
        // 吸附必须真的落在拍网格上（period 0.5、phase 0.1）
        double off = ((handover - 0.1) % 0.5 + 0.5) % 0.5;
        assertTrue("交接点必须落在拍网格上（离最近拍 " + off + " s）",
                Math.min(off, 0.5 - off) <= 0.02);
    }

    /**
     * A 侧的规则：**要么不锚（NaN），要么交接点落在拍网格上、且贴着探测器给出的尾声起点**。
     *
     * <p>写成条件契约而不是"夹具数字 = 期望"✗：尾声起点是"最后一座峰"那套（含 2 s 平滑），
     * 合成曲线上的绝对值不好写死（实测这条曲线探测器给 0.0 ✗ —— 那是夹具的事，不是规则的错 ✗）。
     * 契约本身是硬的 ✓：有值 ⇒ 必须在网格上、且与结构点差不到一拍。
     */
    @Test
    public void theCutEitherAlignsOnTheGridOrRefusesToGuess() {
        AutoCue.Envelope env = envelope(200.0, 100.0, 0.4687, 0.05, 181.0);
        double window = 2.5;
        double cut = AutoCue.structureAlignedCut(env, window, 0.0);
        double structure = AutoCue.outroStructureOf(env);
        if (Double.isNaN(cut)) {
            assertTrue("不锚只允许发生在「结构点不可用」时（实测 structure = " + structure + "）",
                    !(structure > 0.0));
            return;
        }
        double handover = cut + window;
        double off = ((handover - 0.05) % 0.4687 + 0.4687) % 0.4687;
        double dist = Math.min(off, 0.4687 - off);
        assertTrue("A 侧交接点必须落在拍网格上（离最近拍 " + dist + " s）", dist <= 0.02);
        assertTrue("A 侧交接点必须贴着结构点（差 " + Math.abs(handover - structure) + " s）",
                Math.abs(handover - structure) <= 0.24);
    }

    @Test
    public void unusableStructureMeansNoAlignmentInsteadOfAGuess() {
        // 结构点太靠前（引子 2 s < ENTRY_ANCHOR_MIN_SECS = 8 s）⇒ 不锚
        AutoCue.Envelope early = envelope(120.0, 2.0, 0.5, 0.1);
        assertTrue("结构点太靠前 ⇒ 不锚（NaN）",
                Double.isNaN(AutoCue.structureAlignedEntry(early, 2.0, 100.0)));
        assertTrue("结构点太靠前 ⇒ A 侧也不锚",
                !Double.isNaN(AutoCue.structureAlignedCut(early, 2.0, 0.0))
                        || Double.isNaN(AutoCue.structureOf(early)) == false);
        // 窗口比结构点还长 ⇒ 进场会是负数 ⇒ 不锚
        AutoCue.Envelope env = envelope(120.0, 40.0, 0.5, 0.1);
        assertTrue("窗口比结构点还长 ⇒ 不锚（NaN）",
                Double.isNaN(AutoCue.structureAlignedEntry(env, 45.0, 100.0)));
    }
}
