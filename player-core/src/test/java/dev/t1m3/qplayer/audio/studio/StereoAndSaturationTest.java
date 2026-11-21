package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P6（立体声工具）与 P7（染色）的判据。
 *
 * <p>立体声那几条都对着<strong>解析式</strong>量：宽度 0 = 左右逐位相同、宽度 2 = 侧信号
 * 精确翻倍、低音单声道 = 50 Hz 的侧信号被压 20 dB 以上而 1 kHz 几乎不动。
 * 染色那几条对着<strong>THD 的定义</strong>与<strong>混叠</strong>量：
 * 过采样开关必须让「12 kHz 正弦的三次谐波折叠」掉下去 ≥ 10 dB，否则那个开关就是摆设。
 */
public class StereoAndSaturationTest {

    private static final int RATE = 48_000;

    private static AudioBuf stereo(int frames, double leftHz, double rightHz, double peak) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            l.put(i, (float) (peak * Math.sin(2 * Math.PI * leftHz * i / RATE)));
            r.put(i, (float) (peak * Math.sin(2 * Math.PI * rightHz * i / RATE)));
        }
        return buf;
    }

    private static float[] channel(AudioBuf buf, int ch) {
        float[] out = new float[buf.frames()];
        int base = buf.channelBase(ch);
        System.arraycopy(buf.backingArray(), base, out, 0, out.length);
        return out;
    }

    /** 一段信号的侧信号能量（后半段，避开暂态）。 */
    private static double sideRms(AudioBuf buf) {
        float[] l = channel(buf, 0);
        float[] r = channel(buf, 1);
        double sum = 0;
        int n = 0;
        for (int i = l.length / 2; i < l.length; i++) {
            double s = (l[i] - r[i]) * 0.5;
            sum += s * s;
            n++;
        }
        return Math.sqrt(sum / Math.max(1, n));
    }

    // ==================================================================
    // P6 立体声
    // ==================================================================

    /** 中性参数 = 逐位直通，而且不算在工作。 */
    @Test
    public void neutralStereoIsBitExactPassthrough() {
        StereoModule module = new StereoModule(RATE, 2);
        module.setParams(StereoParams.neutral());
        assertFalse("中性参数不算在工作", module.active());
        assertEquals("延迟 0", 0, module.latencyFrames());
        AudioBuf buf = stereo(1024, 440, 660, 0.4);
        float[] beforeL = channel(buf, 0);
        float[] beforeR = channel(buf, 1);
        module.process(buf);
        for (int i = 0; i < 1024; i++) {
            assertEquals("中性必须逐位直通（左第 " + i + " 个）",
                    Float.floatToIntBits(beforeL[i]),
                    Float.floatToIntBits(buf.backingArray()[buf.channelBase(0) + i]));
            assertEquals("中性必须逐位直通（右第 " + i + " 个）",
                    Float.floatToIntBits(beforeR[i]),
                    Float.floatToIntBits(buf.backingArray()[buf.channelBase(1) + i]));
        }
    }

    /** 宽度 0 = 左右逐位相同（真正的单声道）；宽度 2 = 侧信号精确翻倍。 */
    @Test
    public void widthZeroCollapsesAndWidthTwoDoublesTheSide() {
        // 宽度 0
        AudioBuf mono = stereo(8192, 440, 660, 0.4);
        StereoModule zero = new StereoModule(RATE, 2);
        zero.setParams(new StereoParams(true, 0.0, 0.0, 0.0, false, 0.0));
        zero.process(mono);
        float[] l = channel(mono, 0);
        float[] r = channel(mono, 1);
        for (int i = 0; i < l.length; i++) {
            assertEquals("宽度 0 必须左右逐位相同（第 " + i + " 个）",
                    Float.floatToIntBits(l[i]), Float.floatToIntBits(r[i]));
        }

        // 宽度 2：侧信号精确翻倍
        AudioBuf base = stereo(8192, 440, 660, 0.4);
        double side1 = sideRms(base);
        AudioBuf wide = stereo(8192, 440, 660, 0.4);
        StereoModule two = new StereoModule(RATE, 2);
        two.setParams(new StereoParams(true, 2.0, 0.0, 0.0, false, 0.0));
        two.process(wide);
        double side2 = sideRms(wide);
        System.out.printf("### 宽度：侧信号 %.5f → 宽度 2 时 %.5f（比 %.3f，期望 2.000）%n",
                Double.valueOf(side1), Double.valueOf(side2), Double.valueOf(side2 / side1));
        assertEquals("宽度 2 必须精确翻倍", 2.0, side2 / side1, 0.01);
    }

    /**
     * 低音单声道：50 Hz 的侧信号被压 ≥ 20 dB，1 kHz 的侧信号变化 &lt; 0.5 dB。
     *
     * <p>做法：左右声道喂<strong>反相</strong>的同一个频率（于是整段信号都在侧信号里，
     * 主信号为 0），量侧信号的变化就是这条滤波器的频响。
     */
    @Test
    public void bassMonoRemovesOnlyTheLowSide() {
        // 「before」也要把右声道取反（否则左右相同、侧信号本来就是 0——第一版就是这么写的，
        // 量出来 +224 dB 这种荒唐数）
        AudioBuf lowReference = stereo(16384, 50, 50, 0.4);
        invertRight(lowReference);
        double lowBefore = sideRms(lowReference);
        AudioBuf low = stereo(16384, 50, 50, 0.4);
        invertRight(low);
        StereoModule module = new StereoModule(RATE, 2);
        // 分频点 200 Hz：LR4 高通在 50 Hz 处是 −24.6 dB（判据线是 20 dB）
        module.setParams(new StereoParams(true, 1.0, 200.0, 0.0, false, 0.0));
        module.process(low);
        double lowAfter = sideRms(low);
        double lowDb = 20 * Math.log10(lowAfter / Math.max(lowBefore, 1e-12));

        AudioBuf highReference = stereo(16384, 1000, 1000, 0.4);
        invertRight(highReference);
        double highBefore = sideRms(highReference);
        AudioBuf high = stereo(16384, 1000, 1000, 0.4);
        invertRight(high);
        module.process(high);
        double highAfter = sideRms(high);
        double highDb = 20 * Math.log10(highAfter / Math.max(highBefore, 1e-12));

        System.out.printf("### 低音单声道（200 Hz，LR4）：50 Hz 侧信号 %+.1f dB，1 kHz 侧信号 %+.2f dB%n",
                Double.valueOf(lowDb), Double.valueOf(highDb));
        assertTrue("50 Hz 必须被压 20 dB 以上（实测 " + lowDb + " dB）", lowDb < -20.0);
        assertEquals("1 kHz 几乎不该动", 0.0, highDb, 0.5);
        assertTrue("它算在工作", module.active());
    }

    /** 把右声道取反（让整段信号落在侧信号里）。 */
    private static void invertRight(AudioBuf buf) {
        int base = buf.channelBase(1);
        float[] backing = buf.backingArray();
        for (int i = 0; i < buf.frames(); i++) {
            backing[base + i] = -backing[base + i];
        }
    }

    /** 声道互换：左右精确对调。 */
    @Test
    public void channelSwapExchangesTheSides() {
        AudioBuf buf = stereo(4096, 440, 660, 0.4);
        float[] beforeL = channel(buf, 0);
        float[] beforeR = channel(buf, 1);
        StereoModule module = new StereoModule(RATE, 2);
        module.setParams(new StereoParams(true, 1.0, 0.0, 0.0, true, 0.0));
        module.process(buf);
        float[] afterL = channel(buf, 0);
        float[] afterR = channel(buf, 1);
        for (int i = 0; i < beforeL.length; i++) {
            assertEquals("左必须变成原来的右（第 " + i + " 个）",
                    Float.floatToIntBits(beforeR[i]), Float.floatToIntBits(afterL[i]));
            assertEquals("右必须变成原来的左（第 " + i + " 个）",
                    Float.floatToIntBits(beforeL[i]), Float.floatToIntBits(afterR[i]));
        }
    }

    /**
     * 交叉馈送（耳机串音）：左声道的信号要**延迟约 0.3 ms 并过低通**之后进右声道。
     *
     * <p>这条判据是为了补上一个真缺口：{@code StereoModule} 的两条延迟线
     * （侧向延迟 2 ms ≈ 97 帧、交叉馈送 0.3 ms ≈ 16 帧）<strong>共用一个写指针</strong>，
     * 而回绕按侧向延迟那条的长度做——只开交叉馈送时 {@code feedDelay[16]} 立刻越界，
     * 生产者线程当场结束（用户 2026-09-26 报的 {@code Index 16 out of bounds for length 16}）。
     * 原因是当时<strong>一条判据都没开过 {@code crossfeed > 0}</strong>：
     * 宽度 / 低音单声道 / 互换都有，唯独这条没有。
     *
     * <p>判据是对着<strong>声音该有的样子</strong>量的，不是「不崩就算过」：
     *
     * <ol>
     *   <li>左声道 200 Hz 正弦 + 右声道静音 → 右声道必须真的出现信号（而不是一直 0）；</li>
     *   <li>它出现的位置应当是「交叉馈送延迟 + 低通的群延迟」之后（量第一段非零的位置）；</li>
     *   <li>头部遮挡那条 700 Hz 低通必须真的在起作用：**5 kHz 的串音要比 200 Hz 低 ≥ 10 dB**
     *       （二阶 700 Hz 的解析值是 −17 dB 上下）。</li>
     * </ol>
     */
    @Test
    public void crossfeedLeaksTheLeftChannelIntoTheRightAfterTheDelay() {
        double delayFrames = Math.round(0.3 / 1000.0 * RATE);
        double low = crossfeedRatio(200.0);
        double high = crossfeedRatio(5000.0);
        double tilt = 20.0 * Math.log10(low / high);
        System.out.printf("### 交叉馈送：200 Hz 串音比 %.4f，5 kHz 串音比 %.4f"
                        + "（头部遮挡低通把它们拉开 %+.1f dB）%n",
                Double.valueOf(low), Double.valueOf(high), Double.valueOf(tilt));
        assertTrue("右声道必须真的收到左声道的串音（比值 " + low + "）", low > 0.1);

        // 文档（StereoParams 的类注释）写着：「交叉馈送 0.5：对侧电平精确为 −6 dB」——
        // 这是这条特性的**规格**，所以单独量一次：0.5 的交叉量下，200 Hz 的串音应当 ≈ 0.5。
        double half = crossfeedRatio(200.0, 0.5);
        System.out.printf("### 交叉馈送 0.5：对侧电平 %.4f（−6 dB = 0.5012）%n", Double.valueOf(half));
        assertEquals("交叉馈送 0.5 时对侧应当精确 −6 dB", 0.5012, half, 0.02);
        assertTrue("700 Hz 的头部遮挡低通必须让高频串音明显更小：只差 "
                        + tilt + " dB（解析值约 −17 dB）", tilt > 10.0);

        // 延迟：第一个非零采样应当落在「0.3 ms 的延迟线 + 低通的群延迟」之后
        AudioBuf buf = stereo(2048, 200.0, 0.0, 0.8);
        StereoModule module = new StereoModule(RATE, 2);
        module.setParams(new StereoParams(true, 1.0, 0.0, 0.0, false, 1.0));
        module.process(buf);
        float[] right = channel(buf, 1);
        int first = -1;
        for (int i = 0; i < right.length; i++) {
            if (Math.abs(right[i]) > 1e-4f) {
                first = i;
                break;
            }
        }
        System.out.println("### 交叉馈送：右声道第一个非零采样在第 " + first + " 帧（延迟线 "
                + delayFrames + " 帧）");
        assertTrue("右声道必须收到东西", first >= 0);
        assertTrue("串音不该早于延迟线（第 " + first + " 帧 < " + delayFrames + "）",
                first >= delayFrames - 2);
        assertTrue("串音出现得太晚（第 " + first + " 帧），不像 0.3 ms 的头部遮挡延迟",
                first <= delayFrames + 40);
    }

    private static double crossfeedRatio(double hz) {
        return crossfeedRatio(hz, 1.0);
    }

    /**
     * 交叉馈送后的「对侧电平 / **输入**电平」（左声道给一个纯音，右声道起始为静音）。
     *
     * <p>分母取<strong>输入</strong>而不是处理之后的左声道：文档那条规格
     * 「交叉馈送 0.5：对侧电平精确为 −6 dB，同侧几乎不动」里的 −6 dB 是相对原信号的
     * （同侧那一份自己也被 {@code keep} 缩了一点，拿它当分母会把这层缩水算进比值里）。
     */
    private static double crossfeedRatio(double hz, double amount) {
        AudioBuf buf = stereo(16_384, hz, 0.0, 0.8);
        float[] input = channel(buf, 0);           // 处理前的左声道（输入电平）
        StereoModule module = new StereoModule(RATE, 2);
        // 宽度 1、低音单声道 0、侧向延迟 0：这条链上只有交叉馈送在动
        module.setParams(new StereoParams(true, 1.0, 0.0, 0.0, false, amount));
        module.process(buf);
        float[] right = channel(buf, 1);
        double sumIn = 0;
        double sumOut = 0;
        int n = 0;
        for (int i = input.length / 2; i < input.length; i++) {
            sumIn += (double) input[i] * input[i];
            sumOut += (double) right[i] * right[i];
            n++;
        }
        return Math.sqrt(sumOut / n) / Math.max(Math.sqrt(sumIn / n), 1e-30);
    }

    // ==================================================================
    // P7 染色
    // ==================================================================

    private static double thdPercent(SaturationModule module, SaturationParams p, double freq) {
        module.setParams(p);
        int frames = 32768;
        AudioBuf buf = stereo(frames, freq, freq, 0.25);
        module.process(buf);
        float[] v = channel(buf, 0);
        return thd(v, freq);
    }

    /**
     * 总谐波失真（THD）：量 2..8 次谐波相对基波的能量。
     *
     * <p>用 Goertzel 逐频点算幅值（与 FFT 同一条路径的解析式），不引入频谱泄漏的麻烦。
     */
    private static double thd(float[] v, double baseHz) {
        int from = v.length / 2;
        int to = v.length;
        double fundamental = 0;
        double harmonics = 0;
        for (int h = 1; h <= 8; h++) {
            double mag = goertzel(v, from, to, baseHz * h);
            if (h == 1) {
                fundamental = mag;
            } else {
                harmonics += mag * mag;
            }
        }
        return Math.sqrt(harmonics) / Math.max(fundamental, 1e-12) * 100.0;
    }

    private static double goertzel(float[] v, int from, int to, double hz) {
        int n = to - from;
        double w = 2.0 * Math.PI * hz / RATE;
        double coeff = 2.0 * Math.cos(w);
        double s0 = 0;
        double s1 = 0;
        double s2 = 0;
        for (int i = 0; i < n; i++) {
            s0 = v[from + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * Math.cos(w);
        double imag = s2 * Math.sin(w);
        return Math.sqrt(real * real + imag * imag) * 2.0 / n;
    }

    /** 驱动越大 THD 越大；关掉时逐位直通。 */
    @Test
    public void saturationThdGrowsWithDriveAndOffIsBitExact() {
        SaturationModule module = new SaturationModule(RATE, 2);
        double small = thdPercent(module, SaturationParams.of(
                SaturationParams.Curve.TANH, 3.0, 0.0, 1.0, 1), 1000.0);
        double medium = thdPercent(module, SaturationParams.of(
                SaturationParams.Curve.TANH, 12.0, 0.0, 1.0, 1), 1000.0);
        double large = thdPercent(module, SaturationParams.of(
                SaturationParams.Curve.TANH, 24.0, 0.0, 1.0, 1), 1000.0);
        System.out.printf("### 染色 THD：驱动 3 dB → %.2f%%，12 dB → %.2f%%，24 dB → %.2f%%%n",
                Double.valueOf(small), Double.valueOf(medium), Double.valueOf(large));
        assertTrue("驱动越大 THD 越大（" + small + " → " + medium + " → " + large + "）",
                small < medium && medium < large);
        assertTrue("24 dB 驱动必须明显失真（实测 " + large + "%）", large > 3.0);

        // 关掉：逐位直通
        module.setParams(SaturationParams.off());
        assertFalse("关掉不算在工作", module.active());
        AudioBuf buf = stereo(1024, 1000, 1000, 0.4);
        float[] before = channel(buf, 0);
        module.process(buf);
        for (int i = 0; i < 1024; i++) {
            assertEquals("关掉必须逐位直通（第 " + i + " 个）",
                    Float.floatToIntBits(before[i]),
                    Float.floatToIntBits(buf.backingArray()[buf.channelBase(0) + i]));
        }
    }

    /**
     * <b>过采样真的把混叠压下去了。</b>
     *
     * <h3>为什么测试信号是 7 kHz 而不是 12 kHz（这是我第一版写错的地方）</h3>
     *
     * <p>12 kHz 是 48 kHz 的<strong>四分之一</strong>：它的奇次谐波（36 / 60 / 84 kHz…）
     * 折叠回来全都落在 12 kHz <strong>基频自己身上</strong>，于是「陷波之后量残余」这种测法
     * 根本分不开「基频」与「混叠」——判据量出来是「开过采样更差」（−4.5 dB），
     * 而真相是那个测法不成立。
     *
     * <p>7 kHz 没有这个问题：三次谐波 21 kHz 还在奈奎斯特之下（本来就折叠不了），
     * 五次谐波 35 kHz 折叠到 <strong>13 kHz</strong>——量 13 kHz 那一点的能量就是混叠，
     * 与基频和它的谐波都分得开。
     */
    @Test
    public void oversamplingReducesAliasing() {
        SaturationModule module = new SaturationModule(RATE, 2);
        double without = aliasLevel(module, false);
        double with = aliasLevel(module, true);
        double drop = 20 * Math.log10(without / Math.max(with, 1e-12));
        System.out.printf("### 混叠（7 kHz 的五次谐波折到 13 kHz）：过采样关 %.3e，2× 开 %.3e（降 %.1f dB）%n",
                Double.valueOf(without), Double.valueOf(with), Double.valueOf(drop));
        assertTrue("过采样必须把混叠压下去 ≥ 10 dB（实测 " + drop + " dB）", drop >= 10.0);
    }

    /** 量 13 kHz 那一点的能量：它是 7 kHz 的五次谐波（35 kHz）折叠回来的落点。 */
    private static double aliasLevel(SaturationModule module, boolean oversample) {
        SaturationParams p = SaturationParams.of(SaturationParams.Curve.TANH, 24.0, 0.0, 1.0,
                oversample ? 2 : 1);
        module.setParams(p);
        int frames = 65536;
        AudioBuf buf = stereo(frames, 7000.0, 7000.0, 0.3);
        module.process(buf);
        float[] v = channel(buf, 0);
        double a = goertzel(v, frames / 2, frames, 13000.0);
        double b = goertzel(v, frames / 2, frames, 13400.0);
        return Math.sqrt(a * a + b * b);
    }

    /** 直流阻断：带直流偏移的信号进去，输出的直流分量必须被压到 −40 dB 以下。 */
    @Test
    public void dcBlockerRemovesOffset() {
        SaturationModule module = new SaturationModule(RATE, 2);
        module.setParams(SaturationParams.of(SaturationParams.Curve.TANH, 6.0, -6.0, 1.0, 1));
        int frames = 32768;
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        for (int ch = 0; ch < 2; ch++) {
            java.nio.FloatBuffer w = buf.channelWriter(ch);
            for (int i = 0; i < frames; i++) {
                w.put(i, (float) (0.2 + 0.2 * Math.sin(2 * Math.PI * 440.0 * i / RATE)));
            }
        }
        module.process(buf);
        float[] v = channel(buf, 0);
        double mean = 0;
        for (int i = frames / 2; i < frames; i++) {
            mean += v[i];
        }
        mean /= (frames / 2);
        System.out.printf("### 直流阻断：输入直流 0.2，输出直流 %.2e%n", Double.valueOf(mean));
        assertTrue("输出直流必须被压到 −40 dB 以下（实测 " + mean + "）", Math.abs(mean) < 0.01);
    }
}
