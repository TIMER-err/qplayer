package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P8 表头的判据：每个数都对着<strong>标准里的定义</strong>量。
 *
 * <ul>
 *   <li><b>LUFS 的校准常数</b>：BS.1770 那条 {@code −0.691} 的用处就是「1 kHz 正弦、
 *       −20 dBFS → −20 LUFS」。这条是整张表的锚点：它一错，所有响度读数整体平移；</li>
 *   <li><b>真峰 ≥ 采样峰</b>，而且对 1 kHz 正弦两者几乎相等（低于奈奎斯特很多时
 *       采样点本来就抓得到峰）；对一个高于 1/4 采样率的信号，真峰必须<strong>明显大于</strong>
 *       采样峰（那正是它存在的理由）；</li>
 *   <li><b>相关性的三个端点</b>：单声道 +1、反相 −1、无关 ≈ 0；</li>
 *   <li><b>峰值保持与复位</b>：读数只升不降，复位后归零。</li>
 * </ul>
 */
public class StudioMetersTest {

    private static final int RATE = 48_000;

    private static AudioBuf tone(double hz, double peak, int frames, boolean invertRight) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            float v = (float) (peak * Math.sin(2 * Math.PI * hz * i / RATE));
            l.put(i, v);
            r.put(i, invertRight ? -v : v);
        }
        return buf;
    }

    /** 喂 {@code seconds} 秒的信号，返回最后的快照。 */
    private static StudioMeters.Snapshot run(StudioMeters meters, double hz, double peak,
                                             double seconds, boolean invertRight) {
        int block = 1024;
        int total = (int) (seconds * RATE);
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, block);
        for (int at = 0; at + block <= total; at += block) {
            buf.setFrames(block);
            java.nio.FloatBuffer l = buf.channelWriter(0);
            java.nio.FloatBuffer r = buf.channelWriter(1);
            for (int i = 0; i < block; i++) {
                float v = (float) (peak * Math.sin(2 * Math.PI * hz * (at + i) / RATE));
                l.put(i, v);
                r.put(i, invertRight ? -v : v);
            }
            meters.process(buf);
        }
        return meters.snapshot();
    }

    /**
     * <b>响度的锚点（实测值，不是我从标准里背下来的数）。</b>
     *
     * <p>BS.1770 那条 {@code −0.691} 的用处是让读数落在一个人能记住的地方。本机实测：
     * <strong>两路同相、每路 −20 dBFS 的 1 kHz 正弦 → −20.0 LUFS</strong>
     * （K 加权在 1 kHz 的响应 + 双声道均方相加正好把它拉回这个整数上）。
     *
     * <p><b>如实说明（不要把这当成「与参考表头对齐」）</b>：这里量的锚点来自本实现自己的
     * 实测，<strong>没有</strong>与第三方 LUFS 表（ffmpeg 的 ebur128、或某个插件）逐帧对过。
     * 要对齐需要在那台机器上装一个参考实现——这是这一档的已知缺口，写在 docs §9.9。
     * 可以确定成立的是：滤波器系数取自 BS.1770-4 的表（48 kHz）、双声道均方相加、
     * 电平线性（下一条判据实测 6 dB → 6.00 LU）。
     */
    @Test
    public void lufsIsCalibratedOnTheMeasuredAnchor() {
        double peak = Math.pow(10.0, -20.0 / 20.0);
        StudioMeters meters = new StudioMeters(RATE, 2);
        StudioMeters.Snapshot snap = run(meters, 1000.0, peak, 1.0, false);
        System.out.printf("### 响度锚点：1 kHz −20 dBFS（双声道同相）→ M %.2f / S %.2f / I %.2f LUFS%n",
                Double.valueOf(snap.lufsM), Double.valueOf(snap.lufsS), Double.valueOf(snap.lufsI));
        assertEquals("两路同相的 1 kHz 正弦、每路 −20 dBFS", -20.0, snap.lufsM, 0.3);
        assertEquals("S 窗（3 秒窗，喂了 1 秒——窗口里其余部分是零，所以读数只会更低）",
                -20.0, snap.lufsS, 6.0);
        assertEquals("整段积分同样", -20.0, snap.lufsI, 0.3);
    }

    /** 响度随电平线性变化：低 6 dB 的信号读数也低 6 LU。 */
    @Test
    public void lufsTracksLevel() {
        StudioMeters loud = new StudioMeters(RATE, 2);
        double a = run(loud, 1000.0, Math.pow(10.0, -20.0 / 20.0), 1.0, false).lufsM;
        StudioMeters quiet = new StudioMeters(RATE, 2);
        double b = run(quiet, 1000.0, Math.pow(10.0, -26.0 / 20.0), 1.0, false).lufsM;
        System.out.printf("### 响度跟随电平：−20 dBFS → %.2f LUFS，−26 dBFS → %.2f LUFS（差 %.2f）%n",
                Double.valueOf(a), Double.valueOf(b), Double.valueOf(a - b));
        assertEquals("低 6 dB 就该低 6 LU", 6.0, a - b, 0.3);
    }

    /**
     * <b>真峰：采样峰抓不到的那些峰，真峰抓得到。</b>
     *
     * <p>喂 19.5 kHz（接近奈奎斯特的一半的两倍……准确说：每个周期只有 2.46 个采样），
     * 采样点几乎必然错过真正的波峰；而 4× 过采样之后的重建峰明显更高。
     */
    @Test
    public void truePeakCatchesWhatSamplePeakMisses() {
        double peak = 0.9;
        StudioMeters meters = new StudioMeters(RATE, 2);
        StudioMeters.Snapshot snap = run(meters, 19500.0, peak, 1.0, false);
        System.out.printf("### 19.5 kHz：采样峰 %.3f（%.2f dBFS），真峰 %.3f（%.2f dBTP）%n",
                Double.valueOf(snap.peak), Double.valueOf(snap.peakDb()),
                Double.valueOf(snap.truePeak), Double.valueOf(snap.truePeakDb()));
        assertTrue("真峰必须 ≥ 采样峰（定义如此）", snap.truePeak >= snap.peak - 1e-9);
        assertTrue("高频时真峰要明显高于采样峰（实测 " + snap.truePeak + " vs " + snap.peak + "）",
                snap.truePeak > snap.peak * 1.05);

        // 1 kHz：两者几乎相等（采样点抓得到峰）
        StudioMeters low = new StudioMeters(RATE, 2);
        StudioMeters.Snapshot lowSnap = run(low, 1000.0, peak, 1.0, false);
        System.out.printf("### 1 kHz：采样峰 %.4f，真峰 %.4f（差 %.2f dB）%n",
                Double.valueOf(lowSnap.peak), Double.valueOf(lowSnap.truePeak),
                Double.valueOf(lowSnap.truePeakDb() - lowSnap.peakDb()));
        // 容差 0.4 dB：零填充插值的通带纹波让 1 kHz 上偏 +0.26 dB（实测）。
        // BS.1770 对真峰表的容差是 ±0.5 dB，所以这条仍在标准内——但把它写出来，
        // 免得下次有人看到「真峰比采样峰高 0.26 dB」时以为抓到了什么峰。
        assertEquals("1 kHz 上真峰与采样峰应当几乎相等（零填充插值的通带纹波 +0.26 dB）", 0.0,
                lowSnap.truePeakDb() - lowSnap.peakDb(), 0.4);
    }

    /** 相关性的三个端点：同相 +1、反相 −1、两路不同频率 ≈ 0。 */
    @Test
    public void correlationCoversItsThreeEndpoints() {
        StudioMeters same = new StudioMeters(RATE, 2);
        double inPhase = run(same, 1000.0, 0.4, 1.0, false).correlation;
        StudioMeters inverted = new StudioMeters(RATE, 2);
        double antiPhase = run(inverted, 1000.0, 0.4, 1.0, true).correlation;
        System.out.printf("### 相位相关性：同相 %+.4f，反相 %+.4f%n",
                Double.valueOf(inPhase), Double.valueOf(antiPhase));
        assertEquals("同相必须是 +1", 1.0, inPhase, 0.01);
        assertEquals("反相必须是 −1", -1.0, antiPhase, 0.01);

        // 两路不同频率（互为无关）→ 相关性接近 0
        StudioMeters mixed = new StudioMeters(RATE, 2);
        int block = 1024;
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, block);
        for (int at = 0; at + block <= RATE; at += block) {
            buf.setFrames(block);
            java.nio.FloatBuffer l = buf.channelWriter(0);
            java.nio.FloatBuffer r = buf.channelWriter(1);
            for (int i = 0; i < block; i++) {
                l.put(i, (float) (0.4 * Math.sin(2 * Math.PI * 700.0 * (at + i) / RATE)));
                r.put(i, (float) (0.4 * Math.sin(2 * Math.PI * 1300.0 * (at + i) / RATE)));
            }
            mixed.process(buf);
        }
        double uncorrelated = mixed.snapshot().correlation;
        System.out.printf("### 无关的两路（700 / 1300 Hz）：相关性 %+.4f%n",
                Double.valueOf(uncorrelated));
        assertTrue("无关信号的相关性应当接近 0（实测 " + uncorrelated + "）",
                Math.abs(uncorrelated) < 0.1);
    }

    /** 峰值保持只升不降，复位归零；相关性的文字说明覆盖三档。 */
    @Test
    public void peakHoldRisesAndResets() {
        StudioMeters meters = new StudioMeters(RATE, 2);
        run(meters, 1000.0, 0.9, 0.5, false);
        double first = meters.snapshot().peak;
        run(meters, 1000.0, 0.1, 0.5, false);            // 变小了
        double after = meters.snapshot().peak;
        assertEquals("峰值保持不该被小信号拉下来", first, after, 1e-9);
        assertTrue("先前的读数应当是 0.9 上下", first > 0.85);
        meters.clearPeakHold();
        assertEquals("复位之后归零", 0.0, meters.snapshot().peak, 1e-9);
        assertTrue("说明文字要给出人话：" + meters.snapshot().correlationLabel(),
                !meters.snapshot().correlationLabel().isEmpty());
    }

    /** 静音：读数落到底，而不是 NaN/Infinity。 */
    @Test
    public void silenceReadsTheFloorNotNaN() {
        StudioMeters meters = new StudioMeters(RATE, 2);
        int block = 1024;
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, block);
        for (int at = 0; at + block <= RATE / 2; at += block) {
            buf.setFrames(block);
            for (int ch = 0; ch < 2; ch++) {
                java.nio.FloatBuffer w = buf.channelWriter(ch);
                for (int i = 0; i < block; i++) {
                    w.put(i, 0f);
                }
            }
            meters.process(buf);
        }
        StudioMeters.Snapshot snap = meters.snapshot();
        System.out.printf("### 静音：%s%n", snap);
        assertEquals("峰值", 0.0, snap.peak, 1e-9);
        assertTrue("响度要落到底（−120）而不是 NaN：M=" + snap.lufsM,
                snap.lufsM <= -100.0 && !Double.isNaN(snap.lufsM));
        assertEquals("静音的相关性报 0（没有信号就没有相关性）", 0.0, snap.correlation, 1e-9);
    }
}
