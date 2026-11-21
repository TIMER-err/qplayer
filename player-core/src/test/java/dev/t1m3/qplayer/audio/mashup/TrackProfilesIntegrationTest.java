package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.mix.TestWavFile;
import dev.t1m3.qplayer.audio.model.BeatModels;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>{@link TrackProfiles} 的端到端验收</b>：一段真的能被解码的 WAV → 一份档案。
 *
 * <p>用合成素材而不是真歌：120 BPM 是我们自己摆进去的，于是"BPM 测出来是 120"
 * 这条判据有真值可对，而真歌没有。
 */
public class TrackProfilesIntegrationTest {

    private static final int RATE = 44_100;
    private static final double BPM = 120.0;
    private static final double SECONDS = 30.0;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private Decoder.Opener saved;

    @Before
    public void installSeams() {
        saved = currentOpener();
        Decoder.install(TestWavFile.opener());
        BeatModels.uninstall();
        ModelDownloads.uninstall();
    }

    @After
    public void restoreSeams() {
        BeatModels.uninstall();
        ModelDownloads.uninstall();
        Decoder.install(saved);
    }

    @Test
    public void measuresDurationTempoBarsLoudnessAndChroma() throws IOException {
        Path wav = tmp.newFile("groove-120.wav").toPath();
        writeGroove(wav);

        TrackProfile profile = TrackProfiles.analyze("test:groove", wav.toString(), null);

        assertEquals(SECONDS, profile.durationSecs, 0.05);
        assertTrue("必须测到拍点", profile.hasTempo());
        assertEquals("120 BPM 的合成素材应当测出 120：" + profile.bpm, BPM, profile.bpm, 3.0);
        assertEquals("4 拍一小节 ⇒ 2.0 s", 2.0, profile.barSecs, 0.2);
        assertTrue("30 s 里应当有十几小节，实际 " + profile.bars(), profile.bars() >= 10);
        assertTrue("响度必须算出来，实际 " + profile.lufs, Double.isFinite(profile.lufs));
        assertTrue("响度得落在合理区间：" + profile.lufs,
                profile.lufs > -50 && profile.lufs < 0);
        assertNotNull("有 chroma 就必须给出平均色度", profile.meanChroma);
        assertEquals(12, profile.meanChroma.length);
        float sum = 0;
        for (float v : profile.meanChroma) {
            sum += v;
        }
        assertEquals("平均色度应归一化到和为 1", 1.0, sum, 1e-3);
    }

    @Test
    public void cachesTheProfileInTheStore() throws IOException {
        Path wav = tmp.newFile("cached.wav").toPath();
        writeGroove(wav);
        ProfileStore store = new ProfileStore(tmp.newFolder("cache").toPath());

        TrackProfile first = TrackProfiles.load(store, wav.toString(), null);
        assertNotNull(first);
        assertEquals("缓存键应当是文件内容的 sha256",
                ProfileStore.keyOf(wav.toString()), first.key);

        TrackProfile second = TrackProfiles.load(store, wav.toString(), null);
        // 落盘文本按 %.6f 存，读回来只保证到这个精度（这也正是"档案不是原始数据"的意思）
        assertEquals(first.bpm, second.bpm, 1e-4);
        assertEquals(first.durationSecs, second.durationSecs, 1e-4);
        assertEquals(first.bars(), second.bars());
    }

    // ---- 素材 -------------------------------------------------------------

    /** 120 BPM：每拍一个底鼓、每半拍一个踩镲、整段一条低音。 */
    private static void writeGroove(Path path) throws IOException {
        int frames = (int) Math.round(RATE * SECONDS);
        float[] left = new float[frames];
        float[] right = new float[frames];
        double beatSecs = 60.0 / BPM;
        long noise = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            double inBeat = t % beatSecs;
            // 底鼓
            double kick = inBeat < 0.12
                    ? 0.75 * Math.sin(2 * Math.PI * 55 * inBeat) * Math.exp(-inBeat * 26.0) : 0.0;
            // 每半拍一个踩镲（确定性噪声）
            double inHalf = t % (beatSecs / 2);
            double hat = 0.0;
            if (inHalf < 0.03) {
                noise ^= noise << 13;
                noise ^= noise >>> 7;
                noise ^= noise << 17;
                hat = 0.08 * ((noise & 0xFFFF) / 32768.0 - 1.0) * Math.exp(-inHalf * 90.0);
            }
            // 低音（C2 + 五度）
            double bass = 0.18 * (Math.sin(2 * Math.PI * 65.4 * t)
                    + 0.5 * Math.sin(2 * Math.PI * 98.0 * t));
            float v = (float) (kick + hat + bass);
            left[i] = v;
            right[i] = v;
        }
        TestWavFile.write(path, left, right, RATE);
    }

    /** 读全局解码器工厂（{@code Decoder} 没有 getter，与其它测试同一套反射）。 */
    private static Decoder.Opener currentOpener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
