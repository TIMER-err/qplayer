package dev.t1m3.qplayer.audio.mashup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>M0 的端到端验收</b>：两条已知 BPM 的合成素材 → 60 s 成品 WAV。
 *
 * <p>设计文档 §9 的 M0 判据：拍偏差 ≤ 5 ms、长度误差 0 帧、峰值 ≤ −1 dBFS。
 * 拍偏差由 {@link MashupRendererTest} 逐帧钉死（那条更严：精确到采样）；这里补的是
 * <b>整条链</b>——渲染 → 限幅 → 写 WAV → 读回字节，以及"同一份输入渲染两次必须
 * 逐字节相同"（可复现是方案里的目标 3，不是附赠品）。
 */
public class MashupSmokeTest {

    private static final int RATE = 48_000;
    private static final int SECONDS = 60;
    /** 120 BPM ⇒ 每拍 0.5 s。 */
    private static final int BEAT = RATE / 2;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sixtySecondsOfMashupWritesADeterministicWav() throws IOException {
        PcmTrack instrumental = grooveInstrumental();
        PcmTrack vocals = beatLockedVocal();

        Path first = tmp.newFolder("a").toPath().resolve("m0.wav");
        Path second = tmp.newFolder("b").toPath().resolve("m0.wav");

        MashupRenderer.Report r1 = renderTo(first, instrumental, vocals);
        MashupRenderer.Report r2 = renderTo(second, instrumental, vocals);

        long expectedFrames = (long) RATE * SECONDS;
        assertEquals("长度误差必须是 0 帧", expectedFrames, r1.frames);
        assertEquals(expectedFrames, r2.frames);

        assertEquals("成品必须是 44 字节头 + s16 立体声",
                WavWriter.HEADER_BYTES + expectedFrames * 2 * 2, Files.size(first));
        assertTrue("峰值不得超过 −1 dBFS 天花板，实际 " + r1.peakAfterLimiter,
                r1.peakAfterLimiter <= ceiling() + 1e-4f);
        assertTrue("成品必须真的响（否则等于静音导出），实际 " + r1.peakAfterLimiter,
                r1.peakAfterLimiter > 0.1f);

        // 可复现：同一份输入 + 同一份请求 ⇒ 逐字节相同
        assertArrayEquals("同一份输入渲染两次必须逐字节相同",
                Files.readAllBytes(first), Files.readAllBytes(second));

        // 读回：头里的长度字段与真实帧数一致（写坏了播放器会算错时长）
        ByteBuffer head = ByteBuffer.wrap(Files.readAllBytes(first)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, head.getShort(22));
        assertEquals(RATE, head.getInt(24));
        assertEquals((int) (expectedFrames * 2 * 2), head.getInt(40));
    }

    private static MashupRenderer.Report renderTo(Path file, PcmTrack instrumental,
                                                  PcmTrack vocals) throws IOException {
        MashupRenderer.Report report;
        try (WavWriter writer = WavWriter.open(file, RATE, 2)) {
            report = MashupRenderer.render(
                    MashupRenderer.builder(RATE, 2)
                            .instrumental(instrumental, 0)
                            .vocals(vocals, TimeMap.identity((long) RATE * SECONDS))
                            .frames((long) RATE * SECONDS)
                            .gains(0.55f, 0.75f)
                            .build(),
                    writer::write);
        }
        return report;
    }

    /** 伴奏：底鼓 + 持续低音 + 每拍一个"军鼓"噪声（全部确定性，不用随机源）。 */
    private static PcmTrack grooveInstrumental() {
        int frames = RATE * SECONDS;
        float[] left = new float[frames];
        float[] right = new float[frames];
        long noise = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < frames; i++) {
            int inBeat = i % BEAT;
            double t = (double) inBeat / RATE;
            // 底鼓：60 Hz 衰减正弦，前 120 ms
            double kick = t < 0.12
                    ? 0.7 * Math.sin(2 * Math.PI * 60 * t) * Math.exp(-t * 28.0) : 0.0;
            // 低音：110 Hz（整段），缓慢起伏
            double bass = 0.22 * Math.sin(2 * Math.PI * 110 * (double) i / RATE);
            // 反拍"军鼓"：每拍后半段的一小段确定性噪声
            double snare = 0.0;
            if (t >= 0.25 && t < 0.30) {
                noise ^= noise << 13;
                noise ^= noise >>> 7;
                noise ^= noise << 17;
                snare = 0.10 * ((noise & 0xFFFF) / 32768.0 - 1.0) * Math.exp(-(t - 0.25) * 40.0);
            }
            float v = (float) (kick + bass + snare);
            left[i] = v;
            right[i] = v;
        }
        return PcmTrack.stereo(left, right);
    }

    /** 人声（合成）：与拍对齐的颤音，用来验证"叠上去"的加法与淡入淡出。 */
    private static PcmTrack beatLockedVocal() {
        int frames = RATE * SECONDS;
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++) {
            int inBeat = i % BEAT;
            double t = (double) inBeat / RATE;
            // 每拍一个"音节"：220 Hz 基频 + 两个泛音，120 ms 衰减
            double env = t < 0.16 ? Math.exp(-t * 14.0) : 0.0;
            double f = 220.0 * (1.0 + 0.004 * Math.sin(2 * Math.PI * 5.5 * (double) i / RATE));
            double v = env * (0.30 * Math.sin(2 * Math.PI * f * (double) i / RATE)
                    + 0.12 * Math.sin(2 * Math.PI * 2 * f * (double) i / RATE)
                    + 0.06 * Math.sin(2 * Math.PI * 3 * f * (double) i / RATE));
            left[i] = (float) v;
            // 人声稍宽一点：右声道相位略偏，避免"完全居中"被当成单声道素材
            right[i] = (float) (v * 0.92);
        }
        return PcmTrack.stereo(left, right);
    }

    private static float ceiling() {
        return (float) Math.pow(10.0, -1.0 / 20.0);
    }
}
