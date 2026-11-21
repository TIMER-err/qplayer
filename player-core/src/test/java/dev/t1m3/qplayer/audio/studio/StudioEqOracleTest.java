package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P1 的判据：<strong>参数均衡的实测频响必须与 RBJ 手册的解析式逐点吻合</strong>，
 * 旁路必须逐位直通，声道路由必须只动该动的那一路。
 *
 * <h2>为什么用「正弦稳态」量频响，而不是与自己的曲线函数对比</h2>
 *
 * <p>{@link EqResponse} 是给界面画曲线的，它和处理器用的是同一组系数工厂——拿它当尺子
 * 只能证明「两边算的是同一个错误」。所以这里的尺子是<strong>测试里独立抄写的 RBJ 公式</strong>
 * （双精度、直接照手册），量法则是对处理器<strong>喂正弦、等稳态、量幅度</strong>。
 * 两条独立的路对上，才说明滤波器真的做对了。
 *
 * <p>判据定 ±0.15 dB：单精度浮点 + 稳态判定本身会有零点零几 dB 的抖动，而 0.15 dB 已经
 * 是「调音台上看得见、听得见」的量级。
 */
public class StudioEqOracleTest {

    private static final int RATE = 48_000;

    /** 量一个频率的稳态增益（dB）：喂正弦、丢掉前 0.25 秒、量后 0.25 秒的 RMS。 */
    private static double measuredDb(List<EqBand> bands, double freqHz) {
        int settle = RATE / 4;
        int measure = RATE / 4;
        EqProcessor eq = new EqProcessor(RATE, 2);
        eq.setBands(bands);
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, settle + measure);
        int n = settle + measure;
        buf.setFrames(n);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < n; i++) {
            float v = (float) (0.25 * Math.sin(2 * Math.PI * freqHz * i / RATE));
            l.put(i, v);
            r.put(i, v);
        }
        eq.process(buf);
        double in = 0;
        double out = 0;
        float[] backing = buf.backingArray();
        int base = buf.channelBase(0);
        for (int i = settle; i < n; i++) {
            double ref = 0.25 * Math.sin(2 * Math.PI * freqHz * i / RATE);
            in += ref * ref;
            double o = backing[base + i];
            out += o * o;
        }
        return 20.0 * Math.log10(Math.sqrt(out / Math.max(in, 1e-30)));
    }

    // ------------------------------------------------------------------
    // 独立抄写的 RBJ 公式（双精度）——这里的解析式是**尺子**，不引用产品代码
    // ------------------------------------------------------------------

    private static double[] peakingCoeffs(double f, double q, double gainDb) {
        double a = Math.pow(10, gainDb / 40.0);
        double w0 = 2 * Math.PI * f / RATE;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double a0 = 1 + alpha / a;
        return new double[]{(1 + alpha * a) / a0, -2 * cos / a0, (1 - alpha * a) / a0,
                -2 * cos / a0, (1 - alpha / a) / a0};
    }

    private static double[] lowShelfCoeffs(double f, double q, double gainDb) {
        double a = Math.pow(10, gainDb / 40.0);
        double w0 = 2 * Math.PI * f / RATE;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double sqrtA = Math.sqrt(a);
        double a0 = (a + 1) + (a - 1) * cos + 2 * sqrtA * alpha;
        return new double[]{a * ((a + 1) - (a - 1) * cos + 2 * sqrtA * alpha) / a0,
                2 * a * ((a - 1) - (a + 1) * cos) / a0,
                a * ((a + 1) - (a - 1) * cos - 2 * sqrtA * alpha) / a0,
                -2 * ((a - 1) + (a + 1) * cos) / a0,
                ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha) / a0};
    }

    private static double[] highShelfCoeffs(double f, double q, double gainDb) {
        double a = Math.pow(10, gainDb / 40.0);
        double w0 = 2 * Math.PI * f / RATE;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double sqrtA = Math.sqrt(a);
        double a0 = (a + 1) - (a - 1) * cos + 2 * sqrtA * alpha;
        return new double[]{a * ((a + 1) + (a - 1) * cos + 2 * sqrtA * alpha) / a0,
                -2 * a * ((a - 1) + (a + 1) * cos) / a0,
                a * ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha) / a0,
                2 * ((a - 1) - (a + 1) * cos) / a0,
                ((a + 1) - (a - 1) * cos - 2 * sqrtA * alpha) / a0};
    }

    private static double[] notchCoeffs(double f, double q) {
        double w0 = 2 * Math.PI * f / RATE;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double a0 = 1 + alpha;
        return new double[]{1 / a0, -2 * cos / a0, 1 / a0, -2 * cos / a0, (1 - alpha) / a0};
    }

    private static double notchDb(double f, double q, double probe) {
        return coeffsDb(notchCoeffs(f, q), probe);
    }

    private static double[] highPassCoeffs(double f, double q) {
        double w0 = 2 * Math.PI * f / RATE;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double a0 = 1 + alpha;
        return new double[]{(1 + cos) / 2 / a0, -(1 + cos) / a0, (1 + cos) / 2 / a0,
                -2 * cos / a0, (1 - alpha) / a0};
    }

    /** 一串系数的幅度响应（dB）。 */
    private static double coeffsDb(double[] c, double f) {
        double w = 2 * Math.PI * f / RATE;
        double cw = Math.cos(w);
        double sw = Math.sin(w);
        double c2 = Math.cos(2 * w);
        double s2 = Math.sin(2 * w);
        double nre = c[0] + c[1] * cw + c[2] * c2;
        double nim = -(c[1] * sw + c[2] * s2);
        double dre = 1 + c[3] * cw + c[4] * c2;
        double dim = -(c[3] * sw + c[4] * s2);
        double den = Math.sqrt(dre * dre + dim * dim);
        return 20 * Math.log10(Math.sqrt(nre * nre + nim * nim) / den);
    }

    // ------------------------------------------------------------------

    /** 峰值段：中心频率处就是设定的增益，远处趋于 0 dB。 */
    @Test
    public void peakingMatchesTheCookbook() {
        double f = 1000;
        double q = 1.0;
        for (double gain : new double[]{-12, -6, 6, 12}) {
            List<EqBand> bands = Arrays.asList(EqBand.peak(f, gain, q));
            double[] coeffs = peakingCoeffs(f, q, gain);
            for (double probe : new double[]{60, 200, 1000, 4000, 12000}) {
                double want = coeffsDb(coeffs, probe);
                double got = measuredDb(bands, probe);
                assertEquals("峰值 " + gain + "dB @" + probe + "Hz", want, got, 0.15);
            }
        }
    }

    /** 架式段：0 dB 时精确恒等（`A = 1` 让分子分母相同），这是拖到中点时必须发生的事。 */
    @Test
    public void shelvesAtZeroGainAreTransparent() {
        for (EqBand band : Arrays.asList(EqBand.of(BandType.LOW_SHELF, 200, 0.0, 0.7071),
                EqBand.of(BandType.HIGH_SHELF, 6000, 0.0, 0.7071))) {
            assertTrue("0 dB 的架式应当被当作透明：" + band, band.isTransparent());
            for (double probe : new double[]{50, 500, 5000}) {
                assertEquals("0 dB 架式不该改任何频段：" + band + " @" + probe,
                        0.0, measuredDb(Arrays.asList(band), probe), 1e-6);
            }
        }
    }

    /** 低架/高架：远端的平台值与设定一致（低架在低频端 = gain，高架在高频端 = gain）。 */
    @Test
    public void shelvesReachTheRequestedPlateau() {
        double gain = 8.0;
        double low = measuredDb(Arrays.asList(EqBand.of(BandType.LOW_SHELF, 200, gain, 0.7071)), 30);
        double lowWant = coeffsDb(lowShelfCoeffs(200, 0.7071, gain), 30);
        double high = measuredDb(Arrays.asList(EqBand.of(BandType.HIGH_SHELF, 6000, gain, 0.7071)),
                16000);
        double highWant = coeffsDb(highShelfCoeffs(6000, 0.7071, gain), 16000);
        System.out.printf("### 低架 +8 dB：30 Hz 实测 %.2f dB（解析 %.2f）；"
                        + "高架 +8 dB：16 kHz 实测 %.2f dB（解析 %.2f）%n",
                Double.valueOf(low), Double.valueOf(lowWant), Double.valueOf(high),
                Double.valueOf(highWant));
        assertEquals("低架平台", lowWant, low, 0.15);
        assertEquals("高架平台", highWant, high, 0.15);
    }

    /** 高通 12/24/48 dB/oct：斜率实测（每翻一倍频程衰减多少 dB）。 */
    @Test
    public void highPassSlopesAreAsAdvertised() {
        double fc = 1000;
        double[] expected = {12, 24, 48};
        for (int i = 0; i < expected.length; i++) {
            int slope = EqBand.SLOPES[i];
            List<EqBand> bands = Arrays.asList(EqBand.of(BandType.HIGH_PASS, fc, 0, 0.7071, slope,
                    ChannelTarget.STEREO, true));
            // 量点取 fc/2 与 fc/4（正好差一个倍频程）：再往下衰减就掉进浮点的分辨率里了
            // ——我第一版取 fc/8 与 fc/16，48 dB/oct 那一档量出来是 1.5 dB/oct，
            // 那不是滤波器的错，是信号本身已经到 -144 dB（f32 里没有有效位了）。
            double a = measuredDb(bands, fc / 2.0);
            double b = measuredDb(bands, fc / 4.0);
            double measured = b - a;      // 往低频走一个倍频程，衰减了多少 dB
            System.out.printf("### 高通 %d dB/oct：实测 %.1f dB/oct%n", Integer.valueOf(slope),
                    Double.valueOf(-measured));
            assertEquals("高通斜率 " + slope, slope, -measured, 1.5);
            // 顺带核一下单级与解析式
            if (slope == 12) {
                for (double probe : new double[]{100, 1000, 8000}) {
                    assertEquals("12 dB/oct @" + probe,
                            coeffsDb(highPassCoeffs(fc, 0.7071), probe),
                            measuredDb(bands, probe), 0.15);
                }
            }
        }
    }

    /** 多段叠加：各段的 dB 直接相加（线性系统的乘积 = 对数域的加法）。 */
    @Test
    public void cascadedBandsAddUpInDb() {
        double lowShelf = 6.0;
        double peak = -4.0;
        List<EqBand> bands = Arrays.asList(
                EqBand.of(BandType.LOW_SHELF, 120, lowShelf, 0.7071),
                EqBand.peak(3000, peak, 1.4));
        for (double probe : new double[]{50, 400, 3000, 10000}) {
            double single = measuredDb(Arrays.asList(bands.get(0)), probe)
                    + measuredDb(Arrays.asList(bands.get(1)), probe);
            double both = measuredDb(bands, probe);
            assertEquals("两段叠加 @" + probe + "Hz", single, both, 0.02);
        }
        // 界面上画曲线用的那个函数也顺手核一遍（它要给用户看，不能偏）
        double[] grid = {50, 400, 3000, 10000};
        double[] curve = EqResponse.db(bands, RATE, grid, 0.0);
        for (int i = 0; i < grid.length; i++) {
            assertEquals("曲线 @" + grid[i], measuredDb(bands, grid[i]), curve[i], 0.15);
        }
    }

    /** 陷波：中心频率被压下去（Q 越大越窄），两侧基本不动。 */
    @Test
    public void notchKillsTheTargetFrequency() {
        List<EqBand> bands = Arrays.asList(EqBand.of(BandType.NOTCH, 1000, 0, 6.0));
        double centre = measuredDb(bands, 1000);
        System.out.printf("### 陷波 1 kHz Q6：中心 %.1f dB，1.5 kHz %.2f dB%n",
                Double.valueOf(centre), Double.valueOf(measuredDb(bands, 1500)));
        assertTrue("陷波中心必须压下去：" + centre, centre < -60.0);
        for (double probe : new double[]{700, 1500, 4000}) {
            // 「两侧该动多少」不是我能拍的：按 RBJ 的陷波式子算出来当尺子
            double want = notchDb(1000, 6.0, probe);
            assertEquals("陷波 @" + probe, want, measuredDb(bands, probe), 0.15);
        }
    }

    /** 全通：幅度不变（相位段不在这里判，频响必须是平的）。 */
    @Test
    public void allPassKeepsTheMagnitudeFlat() {
        List<EqBand> bands = Arrays.asList(EqBand.of(BandType.ALL_PASS, 1000, 0, 0.7));
        for (double probe : new double[]{100, 1000, 10000}) {
            assertEquals("全通 @" + probe, 0.0, measuredDb(bands, probe), 0.02);
        }
    }

    /** 段关掉之后必须是**一个采样都不碰**（不是「增益为 0 的通路」）。 */
    @Test
    public void disabledBandsAreNotProcessed() {
        List<EqBand> off = Arrays.asList(EqBand.peak(1000, 12, 1.0).withEnabled(false),
                EqBand.of(BandType.HIGH_PASS, 500, 0, 0.7071, 24, ChannelTarget.STEREO, false));
        EqProcessor eq = new EqProcessor(RATE, 2);
        eq.setBands(off);
        assertFalse("全关的段表不该被当作在动", eq.active());
        assertEquals("关掉的段不该改任何频段", 0.0, measuredDb(off, 1000), 1e-6);
    }

    /** 频率/Q/增益越界时必须被夹住（界面、预设文件、命令行三条路都可能送进来）。 */
    @Test
    public void outOfRangeParametersAreClamped() {
        EqBand wild = EqBand.peak(1e9, 900, 5000);
        assertEquals(EqBand.MAX_FREQ, wild.freqHz, 1e-9);
        assertEquals(EqBand.MAX_GAIN, wild.gainDb, 1e-9);
        assertEquals(EqBand.MAX_Q, wild.q, 1e-9);
        EqBand negative = EqBand.peak(-10, -900, -3);
        assertEquals(EqBand.MIN_FREQ, negative.freqHz, 1e-9);
        assertEquals(EqBand.MIN_GAIN, negative.gainDb, 1e-9);
        assertEquals(EqBand.MIN_Q, negative.q, 1e-9);
        // NaN 不许传播（会污染整条系数链）
        EqBand nan = EqBand.peak(Double.NaN, Double.NaN, Double.NaN);
        assertFalse("NaN 必须被换成默认值", Double.isNaN(nan.freqHz) || Double.isNaN(nan.gainDb)
                || Double.isNaN(nan.q));
    }

    /** 段数上限：给再多也只处理前 MAX_BANDS 段（界面不会给多，但预设文件可能被手改）。 */
    @Test
    public void bandCountIsCapped() {
        List<EqBand> many = new ArrayList<EqBand>();
        for (int i = 0; i < StudioRack.MAX_BANDS + 8; i++) {
            many.add(EqBand.peak(100 + i * 100, 3, 1.0));
        }
        StudioRack rack = StudioRack.eq(many);
        assertEquals(StudioRack.MAX_BANDS, rack.bands.size());
    }
}
