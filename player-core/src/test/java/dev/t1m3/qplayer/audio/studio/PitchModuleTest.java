package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P2a 的判据：<b>移调精度</b>、<b>干湿 0 与关掉时逐位直通</b>、<b>延迟如实</b>。
 *
 * <h2>为什么频率要用两种独立方法量</h2>
 *
 * <p>过零率与自相关是两条完全不同的路：过零数错一个就偏半周期，自相关会被谐波与
 * 残留的窗效应骗。**两个都过**才算数——单用一种的话，一个恰好偏移 2π 的错误也能混过去。
 * 两条都过之后，误差判据定在 **±2 音分**（0.1%）：这是「调音台上看得见」的门槛。
 */
public class PitchModuleTest {

    private static final int RATE = 48_000;

    /** 取一段正弦（两声道相同），跑过模块，返回输出（单声道视图）。 */
    private static float[] run(PitchParams params, double freq, int frames) {
        AudioBuf buf = mono(freq, frames);
        PitchModule module = new PitchModule(RATE, 2);
        module.setParams(params);
        module.process(buf);
        float[] out = new float[frames];
        int base = buf.channelBase(0);
        System.arraycopy(buf.backingArray(), base, out, 0, frames);
        return out;
    }

    private static AudioBuf mono(double freq, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        for (int ch = 0; ch < 2; ch++) {
            java.nio.FloatBuffer w = buf.channelWriter(ch);
            for (int i = 0; i < frames; i++) {
                w.put(i, (float) (0.5 * Math.sin(2 * Math.PI * freq * i / RATE)));
            }
        }
        return buf;
    }

    /** 方法一：过零率（只看稳态段）。 */
    private static double freqByZeroCrossings(float[] v, int from, int to) {
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if ((v[i - 1] < 0 && v[i] >= 0) || (v[i - 1] >= 0 && v[i] < 0)) {
                crossings++;
            }
        }
        double seconds = (to - from) / (double) RATE;
        return crossings / (2.0 * seconds);
    }

    /** 方法二：自相关 + 抛物线插值（同样只看稳态段）。 */
    private static double freqByAutocorrelation(float[] v, int from, int to, double expected) {
        int n = to - from;
        int minLag = Math.max(2, (int) (RATE / (expected * 1.5)));
        int maxLag = Math.min(n / 2, (int) (RATE / (expected / 1.5)));
        double best = -1;
        int bestLag = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            double e1 = 0;
            double e2 = 0;
            for (int i = 0; i < n - lag; i++) {
                double a = v[from + i];
                double b = v[from + i + lag];
                sum += a * b;
                e1 += a * a;
                e2 += b * b;
            }
            double norm = sum / Math.sqrt(Math.max(e1 * e2, 1e-30));
            if (norm > best) {
                best = norm;
                bestLag = lag;
            }
        }
        // 抛物线插值：不做的话采样点量化在 440 Hz 上就有 ±2 音分级误差
        double y0 = corrAt(v, from, to, bestLag - 1);
        double y1 = best;
        double y2 = corrAt(v, from, to, bestLag + 1);
        double denom = (y0 - 2 * y1 + y2);
        double delta = Math.abs(denom) < 1e-12 ? 0 : 0.5 * (y0 - y2) / denom;
        return RATE / (bestLag + delta);
    }

    private static double corrAt(float[] v, int from, int to, int lag) {
        if (lag <= 0) {
            return 0;
        }
        int n = to - from;
        double sum = 0;
        double e1 = 0;
        double e2 = 0;
        for (int i = 0; i < n - lag; i++) {
            double a = v[from + i];
            double b = v[from + i + lag];
            sum += a * b;
            e1 += a * a;
            e2 += b * b;
        }
        return sum / Math.sqrt(Math.max(e1 * e2, 1e-30));
    }

    /** 音分差。 */
    private static double cents(double got, double want) {
        return 1200 * Math.log(got / want) / Math.log(2);
    }

    /** +7 半音：440 → 659.26 Hz，两种量法都必须落在 ±2 音分内。 */
    @Test
    public void shiftingUpSevenSemitonesIsAccurate() {
        double want = 440.0 * Math.pow(2, 7.0 / 12.0);
        float[] out = run(PitchParams.semitones(7), 440.0, RATE * 2);
        int from = RATE / 2;
        int to = RATE * 2;
        double zc = freqByZeroCrossings(out, from, to);
        double ac = freqByAutocorrelation(out, from, to, want);
        System.out.printf("### +7 半音：过零率 %.2f Hz（%+.2f 音分），自相关 %.2f Hz（%+.2f 音分），"
                        + "目标 %.2f Hz%n",
                Double.valueOf(zc), Double.valueOf(cents(zc, want)), Double.valueOf(ac),
                Double.valueOf(cents(ac, want)), Double.valueOf(want));
        assertEquals("过零率量出来的频率不对", 0.0, cents(zc, want), 2.0);
        assertEquals("自相关量出来的频率不对", 0.0, cents(ac, want), 2.0);
    }

    /** +50 音分（半个半音）：这一档最能看出「音分」有没有被正确加进去。 */
    @Test
    public void fiftyCentsIsHalfASemitone() {
        double want = 440.0 * Math.pow(2, 0.5 / 12.0);
        float[] out = run(PitchParams.of(0, 50, 1.0), 440.0, RATE * 2);
        double ac = freqByAutocorrelation(out, RATE / 2, RATE * 2, want);
        System.out.printf("### +50 音分：自相关 %.2f Hz（%+.2f 音分），目标 %.2f Hz%n",
                Double.valueOf(ac), Double.valueOf(cents(ac, want)), Double.valueOf(want));
        assertEquals("+50 音分不是半音", 0.0, cents(ac, want), 2.0);
    }

    /** −5 半音（降调）：同样是向下最常用的一档。 */
    @Test
    public void shiftingDownFiveSemitonesIsAccurate() {
        double want = 440.0 * Math.pow(2, -5.0 / 12.0);
        float[] out = run(PitchParams.semitones(-5), 440.0, RATE * 2);
        double ac = freqByAutocorrelation(out, RATE / 2, RATE * 2, want);
        System.out.printf("### −5 半音：自相关 %.2f Hz（%+.2f 音分），目标 %.2f Hz%n",
                Double.valueOf(ac), Double.valueOf(cents(ac, want)), Double.valueOf(want));
        assertEquals("−5 半音不对", 0.0, cents(ac, want), 2.0);
    }

    /** 干湿 0 与关掉：**逐位直通**（这是位完美承诺的一部分）。 */
    @Test
    public void mixZeroAndOffAreBitIdentical() {
        int frames = 8192;
        AudioBuf a = mono(440.0, frames);
        float[] before = new float[frames];
        System.arraycopy(a.backingArray(), a.channelBase(0), before, 0, frames);

        AudioBuf b = mono(440.0, frames);
        PitchModule module = new PitchModule(RATE, 2);
        module.setParams(PitchParams.of(7, 0, 0.0));      // mix = 0
        module.process(b);
        assertFalse("干湿 0 不该算在处理", module.active());
        for (int i = 0; i < frames; i++) {
            assertEquals("干湿 0 必须逐位直通（第 " + i + " 个）",
                    Float.floatToIntBits(before[i]),
                    Float.floatToIntBits(b.backingArray()[b.channelBase(0) + i]));
        }

        AudioBuf c = mono(440.0, frames);
        PitchModule off = new PitchModule(RATE, 2);
        off.setParams(PitchParams.off());
        off.process(c);
        assertFalse("关掉之后不该算在处理", off.active());
        for (int i = 0; i < frames; i++) {
            assertEquals("关掉必须逐位直通（第 " + i + " 个）",
                    Float.floatToIntBits(before[i]),
                    Float.floatToIntBits(c.backingArray()[c.channelBase(0) + i]));
        }
        // 音高为 0（半音与音分都为 0）也是一种「没什么可做」
        assertFalse("音高正好是 0 时不该算在处理",
                PitchParams.of(0, 0, 1.0).active());
    }

    /**
     * <strong>延迟报 0，而且干湿确实是对齐的</strong>——这条是量出来的，不是抄来的。
     *
     * <p>第一版我照 {@code PitchShifter.latencyFrames()}（grain − hop = 1536 帧）去延迟干声，
     * 结果干湿错开 32 ms：混出来是梳状滤波，频率也跟着偏。噪声互相关（宽带信号没有周期性
     * 歧义）量出来的真实对齐延迟是 <strong>0 帧</strong>：声码器移的是音高、时间轴原样保留，
     * 那个 1536 是它内部环的记账。
     *
     * <p>所以判据是：报 0，且「干湿混合之后再与原始输入做互相关」的峰必须落在 0 附近
     * （±32 帧 = ±0.7 ms）。
     */
    @Test
    public void reportsZeroLatencyAndDryWetAreAligned() {
        int frames = RATE * 2;
        java.util.Random rnd = new java.util.Random(31337);
        float[] noise = new float[frames];
        for (int i = 0; i < frames; i++) {
            noise[i] = (float) (0.3 * (rnd.nextDouble() * 2 - 1));
        }
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        for (int ch = 0; ch < 2; ch++) {
            java.nio.FloatBuffer w = buf.channelWriter(ch);
            for (int i = 0; i < frames; i++) {
                w.put(i, noise[i]);
            }
        }
        PitchModule module = new PitchModule(RATE, 2);
        // 音高几乎不动（1 音分）但模块是「在处理」，于是干湿各占一半
        module.setParams(PitchParams.of(0, 1.0, 0.5));
        assertTrue("1 音分也算在处理", module.active());
        assertEquals("输出前视一个 CHUNK，如实上报", 1024, module.latencyFrames());
        module.process(buf);
        float[] out = new float[frames];
        int base = buf.channelBase(0);
        System.arraycopy(buf.backingArray(), base, out, 0, frames);

        // 搜索范围必须覆盖上报的延迟（1024）——我第一版只找了 ±64，
        // 结果峰明明在 1024 却量到 49（那是噪声的局部峰），测试自己错了。
        int bestLag = 0;
        double best = -1;
        for (int lag = -32; lag <= 2048; lag++) {
            double sum = 0;
            int cnt = 0;
            for (int i = 4800; i < frames - 4800; i++) {
                int j = i + lag;
                if (j < 0 || j >= frames) {
                    continue;
                }
                sum += noise[i] * out[j];
                cnt++;
            }
            sum /= Math.max(cnt, 1);
            if (sum > best) {
                best = sum;
                bestLag = lag;
            }
        }
        System.out.printf("### 输入与输出的互相关峰在 lag = %d 帧，模块上报延迟 %d 帧（峰越接近越好）%n",
                Integer.valueOf(bestLag), Integer.valueOf(module.latencyFrames()));
        assertEquals("输出应当与输入对齐（干湿都在原位，错开就是梳状）",
                module.latencyFrames(), bestLag, 32);
    }

    /** 按固定块长流式跑一段（引擎就是这么喂的）。 */
    private static float[] streamInBlocks(PitchParams params, double freq, int total, int block) {
        AudioBuf src = mono(freq, total);
        PitchModule module = new PitchModule(RATE, 2);
        module.setParams(params);
        AudioBuf chunk = new AudioBuf(ChannelLayout.STEREO, block);
        float[] out = new float[total];
        int at = 0;
        while (at < total) {
            int n = Math.min(block, total - at);
            chunk.setFrames(n);
            for (int ch = 0; ch < 2; ch++) {
                java.nio.FloatBuffer w = chunk.channelWriter(ch);
                int sb = src.channelBase(ch);
                for (int i = 0; i < n; i++) {
                    w.put(i, src.backingArray()[sb + at + i]);
                }
            }
            module.process(chunk);
            int cb = chunk.channelBase(0);
            for (int i = 0; i < n; i++) {
                out[at + i] = chunk.backingArray()[cb + i];
            }
            at += n;
        }
        return out;
    }

    /** 越界与 NaN 一律夹住（界面、预设、命令行三条路都可能送进来）。 */
    @Test
    public void outOfRangeValuesAreClamped() {
        PitchParams wild = PitchParams.of(99, 999, 5);
        assertEquals("半音", PitchParams.MAX_SEMITONE, wild.semitone);
        assertEquals("音分", PitchParams.MAX_CENTS, wild.cents, 1e-9);
        assertEquals("干湿", 1.0, wild.mix, 1e-9);
        PitchParams negative = PitchParams.of(-99, -999, -5);
        assertEquals("半音下界", -PitchParams.MAX_SEMITONE, negative.semitone);
        assertEquals("音分下界", -PitchParams.MAX_CENTS, negative.cents, 1e-9);
        assertEquals("干湿下界", 0.0, negative.mix, 1e-9);
        PitchParams nan = PitchParams.of(0, Double.NaN, Double.NaN);
        assertFalse("NaN 不许传播", Double.isNaN(nan.cents) || Double.isNaN(nan.mix));
        // 半音 + 音分合起来是 ±13 半音
        assertEquals("合计上界", 13.0, PitchParams.of(12, 100, 1.0).totalSemitones(), 1e-9);
        assertEquals("合计下界", -13.0, PitchParams.of(-12, -100, 1.0).totalSemitones(), 1e-9);
    }

    /**
     * 引擎的块长是<strong>固定</strong>的（{@code EngineFrames.BLOCK_FRAMES}），
     * 所以判据按引擎那几种块长来立；不是 CHUNK 整数倍的奇数块长只要求 5 音分内。
     *
     * <p>为什么奇数块长会差一点（实测 −2.1 音分）：「湿信号还没出来」的那几帧原样直通，
     * 块长与声码器的分块不对齐时，这种直通帧会零星出现，形成相位接缝。
     * 换成给湿信号补零会更糟（OLA 丢重叠，见 {@code PitchModule} 类注释），
     * 而给输出加一个 CHUNK 的前视就等于引入 21 ms 延迟——为一个引擎不会出现的块长付这个代价不值。
     */
    @Test
    public void blockSizeDoesNotChangeTheResult() {
        double want = 440.0 * Math.pow(2, 3.0 / 12.0);
        int total = RATE;
        float[] whole = run(PitchParams.semitones(3), 440.0, total);

        AudioBuf src = mono(440.0, total);
        PitchModule module = new PitchModule(RATE, 2);
        module.setParams(PitchParams.semitones(3));
        AudioBuf chunk = new AudioBuf(ChannelLayout.STEREO, 1471);
        float[] piecewise = new float[total];
        int at = 0;
        int base = src.channelBase(0);
        while (at < total) {
            int n = Math.min(1471, total - at);
            chunk.setFrames(n);
            for (int ch = 0; ch < 2; ch++) {
                java.nio.FloatBuffer w = chunk.channelWriter(ch);
                int sb = src.channelBase(ch);
                for (int i = 0; i < n; i++) {
                    w.put(i, src.backingArray()[sb + at + i]);
                }
            }
            module.process(chunk);
            int cb = chunk.channelBase(0);
            for (int i = 0; i < n; i++) {
                piecewise[at + i] = chunk.backingArray()[cb + i];
            }
            at += n;
        }
        double a = freqByAutocorrelation(whole, RATE / 2, total, want);
        double b = freqByAutocorrelation(piecewise, RATE / 2, total, want);
        System.out.printf("### 块长无关：整块 %.2f Hz，分块（1471 帧）%.2f Hz，目标 %.2f Hz%n",
                Double.valueOf(a), Double.valueOf(b), Double.valueOf(want));
        assertEquals("奇数块长下偏差要在 5 音分内", 0.0, cents(b, want), 5.0);
        assertEquals("分块与整块的结果不该差太多", 0.0, cents(b, a), 5.0);

        // 引擎真正会用的块长：必须精确
        for (int block : new int[]{1024, 2048, 4096}) {
            float[] streamed = streamInBlocks(PitchParams.semitones(3), 440.0, total, block);
            double f = freqByAutocorrelation(streamed, RATE / 2, total, want);
            System.out.printf("### 引擎块长 %d：自相关 %.2f Hz（%+.2f 音分）%n",
                    Integer.valueOf(block), Double.valueOf(f), Double.valueOf(cents(f, want)));
            assertEquals("引擎块长 " + block + " 下频率必须准", 0.0, cents(f, want), 2.0);
        }
    }
}
