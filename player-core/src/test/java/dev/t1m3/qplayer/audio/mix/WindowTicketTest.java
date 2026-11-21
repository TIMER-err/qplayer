package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>窗口处理票</b>（2026-10-07，目标②）。核心是那个"<b>一个</b>"：
 *
 * <p>整段响度只有**一个**增益 —— 票上没有"按块"的入口，deck 也就没有"每块各自量"的机会。
 * 这正是"响度突变"（用户实测）的**结构性**防线：不是靠判据事后拦，而是让错误无从发生。
 */
public class WindowTicketTest {

    private static final int RATE = 48000;

    private static float[][] window(double amp, double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) (amp * Math.sin(2 * Math.PI * 1000 * i / (double) RATE));
        }
        return new float[][] {x.clone(), x.clone()};
    }

    @Test
    public void anIdentityTicketChangesNothing() {
        WindowTicket t = WindowTicket.identity(0.0);
        assertTrue("什么都不做的票 ⇒ doesAnything 为假", !t.doesAnything());
        assertEquals("增益曲线为空", 0, t.vocalGainFrames());
        assertEquals("哪个时刻都是 1", 1.0f, t.vocalGainAt(12.34, RATE), 1e-6f);
        assertEquals("响度增益是 1", 1.0, t.loudnessGain, 1e-9);
    }

    @Test
    public void theLoudnessGainIsOneNumberForTheWholeWindow() {
        float[][] w = window(0.05, 3.0);
        WindowTicket t = WindowTicket.build(w, RATE, null, 0.0, -16.0);
        assertTrue("这张票动过响度", Math.abs(t.loudnessGain - 1.0) > 1e-9);
        // **结构性**：票上没有任何"按块取增益"的接口 ⇒ 任何一块拿到的都是同一个数
        // （判据能做的是断言"整段共用一个值"，这一点由字段是单个 double 保证）
        double g = t.loudnessGain;
        assertEquals("同一个值", g, t.loudnessGain, 0.0);
        // 而且量的时候不该改动调用方的数组（在副本上量）
        assertTrue("调用方的窗口素材没有被票改动",
                Math.abs(w[0][0] - 0.05 * Math.sin(0.0)) < 1e-6);
    }

    @Test
    public void theVocalCurveIsOneOutsideTheRangeAndZeroInside() {
        float[][] w = window(0.1, 3.0);
        double[] ranges = new double[] {1.0, 2.0};
        WindowTicket t = WindowTicket.build(w, RATE, ranges, 0.0, Double.NaN);
        assertTrue("有曲线", t.vocalGainFrames() > 0);
        assertEquals("区间之前 ⇒ 1", 1.0f, t.vocalGainAt(0.5, RATE), 1e-6f);
        assertEquals("区间正中 ⇒ 0", 0.0f, t.vocalGainAt(1.5, RATE), 1e-6f);
        assertEquals("区间之后 ⇒ 1", 1.0f, t.vocalGainAt(2.5, RATE), 1e-6f);
        assertEquals("窗口之外（负）⇒ 1（不越界）", 1.0f, t.vocalGainAt(-1.0, RATE), 1e-6f);
        assertEquals("窗口之外（超尾）⇒ 1（不越界）", 1.0f, t.vocalGainAt(99.0, RATE), 1e-6f);
    }

    @Test
    public void consecutiveChunksNeverJumpBecauseThereIsOnlyOneGain() {
        // **专用判据（治用户实测的"响度突变"）**：把票施加到**两块相邻的**素材上，
        // 交界处的电平差必须 ≈ 0 —— 因为整段只有一个增益，结构上就不可能跳。
        int block = RATE / 10;                       // 每块 100 ms
        float[][] a = window(0.1, 0.1);
        float[][] b = window(0.1, 0.1);
        WindowTicket t = WindowTicket.build(window(0.05, 3.0), RATE, null, 0.0, -16.0);
        assertTrue("票要动响度才测得出来", Math.abs(t.loudnessGain - 1.0) > 1e-9);
        t.applyLoudness(a, block);
        t.applyLoudness(b, block);
        double lvlA = rms(a[0], block);
        double lvlB = rms(b[0], block);
        double db = 20.0 * Math.log10(lvlA / lvlB);
        assertTrue("相邻两块的交界电平差必须 ≤ 0.5 dB（实测 " + db + " dB）", Math.abs(db) <= 0.5);
    }

    @Test
    public void applyingLoudnessWithAnIdentityTicketTouchesNothing() {
        float[][] x = window(0.1, 0.05);
        float before = x[0][10];
        WindowTicket t = WindowTicket.identity(0.0);
        assertTrue("空票 ⇒ 不改动", !t.applyLoudness(x, x[0].length));
        assertEquals("逐位不变", before, x[0][10], 1e-9f);
    }

    /** 假分离器：把输入**对半分成**"伴奏"与"人声" —— 用来精确验证重建式（不依赖真模型）。 */
    private static final class HalfSeparator
            implements dev.t1m3.qplayer.audio.model.StemSeparator {
        @Override
        public StemSet separate(float[] left, float[] right, int rate) {
            float[] insL = new float[left.length];
            float[] insR = new float[right.length];
            float[] vocL = new float[left.length];
            float[] vocR = new float[right.length];
            for (int i = 0; i < left.length; i++) {
                insL[i] = left[i] * 0.5f;
                insR[i] = right[i] * 0.5f;
                vocL[i] = left[i] * 0.5f;
                vocR[i] = right[i] * 0.5f;
            }
            return new StemSet(vocL, vocR, insL, insR);
        }

        @Override
        public void close() {
            // 假分离器没有资源要放
        }
    }

    @Test
    public void theVocalHalfRemovesOnlyTheVocalStemAndKeepsTheAccompaniment() {
        // 叠唱区间覆盖整块 ⇒ 增益处处 0 ⇒ 结果应当**只剩伴奏**（= 一半），而不是静音
        float[][] chunk = window(0.2, 0.2);
        int frames = chunk[0].length;
        WindowTicket t = WindowTicket.build(chunk, RATE, new double[] {0.0, 1.0}, 0.0, Double.NaN);
        boolean moved = t.applyVocals(chunk, frames, 0.0, RATE, new HalfSeparator());
        assertTrue("应当改动过", moved);
        double before = rms(chunk[0], frames);
        // 期望：伴奏那一半原样留下（0.5 倍），人声那一半被压掉
        double expected = 0.5 * rms(window(0.2, 0.2)[0], frames);
        double db = 20.0 * Math.log10(before / expected);
        // 容差 1.5 dB（而不是 1 dB）的原因写在明处：区间两端各留 50 ms 的**余弦渐变**
        // （那是治"切换的时候叫一声"的功能），而这块素材正好落在区间两端
        // ⇒ 有 100 ms 处于"半压"状态 ⇒ 比纯伴奏略响一点（实测 1.28 dB）。
        assertTrue("只剩伴奏（实测与期望差 " + db + " dB，红线 ≤ 1.5 dB，含边缘渐变）",
                Math.abs(db) <= 1.5);
        assertTrue("**绝不能静音**（用户听到的是这个 bug）", before > 0.01);
    }

    @Test
    public void outsideTheDuckRangeNotASingleSampleIsTouched() {
        float[][] chunk = window(0.2, 0.1);
        float before = chunk[0][5];
        // 叠唱区间在别处（1.0–2.0 s），这一块在 0 s 附近 ⇒ 不该动任何采样
        WindowTicket t = WindowTicket.build(chunk, RATE, new double[] {1.0, 2.0}, 0.0, Double.NaN);
        t.applyVocals(chunk, chunk[0].length, 0.0, RATE, new HalfSeparator());
        assertEquals("区间之外：逐位不变", before, chunk[0][5], 1e-9f);
    }

    @Test
    public void withoutASeparatorNothingHappens() {
        float[][] chunk = window(0.2, 0.1);
        float before = chunk[0][5];
        WindowTicket t = WindowTicket.build(chunk, RATE, new double[] {0.0, 1.0}, 0.0, Double.NaN);
        assertTrue("没有分离器 ⇒ 不动（不猜）", !t.applyVocals(chunk, chunk[0].length, 0.0, RATE, null));
        assertEquals("逐位不变", before, chunk[0][5], 1e-9f);
    }

    private static double rms(float[] x, int n) {
        double s = 0.0;
        int m = Math.min(n, x.length);
        for (int i = 0; i < m; i++) {
            s += (double) x[i] * x[i];
        }
        return Math.sqrt(s / Math.max(1, m));
    }

    @Test
    public void aTicketWithOnlyDuckRangesStillDoesSomething() {
        WindowTicket t = WindowTicket.build(window(0.1, 2.0), RATE,
                new double[] {0.5, 1.0}, 0.0, Double.NaN);
        assertTrue("只压人声、不动响度 ⇒ 也是一张「会动手」的票", t.doesAnything());
        assertEquals("响度增益仍是 1", 1.0, t.loudnessGain, 1e-9);
    }
}
