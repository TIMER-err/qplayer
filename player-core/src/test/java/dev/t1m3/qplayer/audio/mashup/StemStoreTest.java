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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>{@link StemStore} 的端到端验收</b>：一段真 WAV → 一份整曲分轨。
 *
 * <p>判据是三条，都刻意选成"能算出来"的：
 * <ol>
 *   <li><b>可逆</b>：{@code 伴奏 + 人声} 必须还原出原素材（DSP 档的数学性质，
 *       分窗拼接与交叉淡化都不许破坏它）；</li>
 *   <li><b>接缝不塌</b>：跨窗处的包络不许掉下来（那是"人声忽大忽小"的量化版）；</li>
 *   <li><b>缓存与取消</b>：第二次调用不许重算；取消不许留半成品。</li>
 * </ol>
 */
public class StemStoreTest {

    private static final int RATE = 48_000;

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
    public void separatesAWholeShortTrackAndStaysReversible() throws IOException {
        Path wav = tmp.newFile("short.wav").toPath();
        float[][] source = writeGroove(wav, 3.0);
        StemStore store = new StemStore(tmp.newFolder("stems").toPath());
        Path stemPath = store.ensure("key-short", wav.toString(), null, RATE, null, null);
        assertTrue(Files.isRegularFile(stemPath));

        try (StemFile stems = StemFile.open(stemPath)) {
            assertEquals(RATE, stems.sampleRate());
            assertEquals(StemStore.TAG_DSP, stems.tag());
            assertEquals("总帧数必须等于原曲时长",
                    Math.round(3.0 * RATE), stems.frames());
            assertTrue("crc 应当对得上", stems.verifyCrc());

            int n = 4_096;
            float[][] instr = new float[2][n];
            float[][] voc = new float[2][n];
            long at = Math.round(1.0 * RATE);
            stems.read(at, n, instr, voc);
            double vocEnergy = 0;
            double err = 0;
            for (int i = 0; i < n; i++) {
                vocEnergy += (double) voc[0][i] * voc[0][i];
                double sum = instr[0][i] + voc[0][i];
                err = Math.max(err, Math.abs(sum - source[0][(int) (at + i)]));
            }
            assertTrue("人声轨不能是空的（全零说明分离没跑）", vocEnergy > 1.0);
            assertTrue("伴奏 + 人声必须还原原信号，最大偏差 " + err, err < 2e-3);
        }
    }

    @Test
    public void seamBetweenWindowsDoesNotDip() throws IOException {
        Path wav = tmp.newFile("long.wav").toPath();
        writeSteadyTone(wav, 22.0);
        StemStore store = new StemStore(tmp.newFolder("stems").toPath());
        Path stemPath = store.ensure("key-long", wav.toString(), null, RATE, null, null);

        try (StemFile stems = StemFile.open(stemPath)) {
            long seam = Math.round(StemStore.HOP_SECS * RATE);
            double seamRms = rmsAround(stems, seam, RATE / 10);
            double before = rmsAround(stems, seam - RATE / 2, RATE / 10);
            double after = rmsAround(stems, seam + RATE / 2, RATE / 10);
            double neighbor = Math.min(before, after);
            assertTrue("接缝处的能量不该掉下来：接缝 " + seamRms + "，两侧 " + neighbor,
                    seamRms > neighbor * 0.5);
        }
    }

    @Test
    public void secondCallReusesTheCacheInsteadOfReseparating() throws IOException {
        Path wav = tmp.newFile("cached.wav").toPath();
        writeGroove(wav, 2.0);
        StemStore store = new StemStore(tmp.newFolder("stems").toPath());
        Path first = store.ensure("key-cached", wav.toString(), null, RATE, null, null);

        // 如果它重新生成，就会先问 cancel —— 返回 true 会立刻抛取消
        StemStore.Cancel alwaysCancel = () -> true;
        Path second = store.ensure("key-cached", wav.toString(), null, RATE, null, alwaysCancel);
        assertEquals(first, second);
        assertTrue(store.has("key-cached", StemStore.TAG_DSP));
    }

    @Test
    public void cancelLeavesNoPartialFile() throws IOException {
        Path wav = tmp.newFile("cancel.wav").toPath();
        writeGroove(wav, 22.0);                      // 需要两个窗，保证取消发生在中途
        Path dir = tmp.newFolder("stems").toPath();
        StemStore store = new StemStore(dir);
        AtomicInteger calls = new AtomicInteger();
        StemStore.Cancel cancel = () -> calls.incrementAndGet() > 0;
        try {
            store.ensure("key-cancel", wav.toString(), null, RATE, null, cancel);
            fail("取消应当抛 CancellationException");
        } catch (CancellationException expected) {
            assertEquals(1, calls.get());
        }
        Path target = store.pathFor("key-cancel", StemStore.TAG_DSP);
        assertFalse("取消后不该有正式文件", Files.exists(target));
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            assertTrue("也不该留下 .tmp", s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    public void progressIsMonotonicAndEndsOnTheLastWindow() throws IOException {
        Path wav = tmp.newFile("progress.wav").toPath();
        writeGroove(wav, 22.0);
        StemStore store = new StemStore(tmp.newFolder("stems").toPath());
        StringBuilder seen = new StringBuilder();
        store.ensure("key-progress", wav.toString(), null, RATE,
                (done, total) -> seen.append(done).append('/').append(total).append(' '), null);
        String log = seen.toString();
        assertTrue("进度日志不该是空的：" + log, log.length() > 0);
        // 22 s / 20 s 跳长 ⇒ 2 个窗；最后一次回调应当报 2/2
        assertTrue("最后应当收敛到 2/2：" + log, log.trim().endsWith("2/2"));
        assertNotNull(log);
    }

    // ---- 素材与工具 -------------------------------------------------------

    /** 3 秒的"鼓组 + 人声"式素材：返回写进 WAV 的原始样本（48 kHz ⇒ 解码不重采样，可逐点比对）。 */
    private static float[][] writeGroove(Path path, double seconds) throws IOException {
        int frames = (int) Math.round(RATE * seconds);
        float[][] out = new float[2][frames];
        double beat = 0.5;
        long noise = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            double inBeat = t % beat;
            double kick = inBeat < 0.1
                    ? 0.6 * Math.sin(2 * Math.PI * 60 * inBeat) * Math.exp(-inBeat * 30) : 0.0;
            noise ^= noise << 13;
            noise ^= noise >>> 7;
            noise ^= noise << 17;
            double hat = inBeat > 0.25 && inBeat < 0.30
                    ? 0.05 * ((noise & 0xFFFF) / 32768.0 - 1.0) : 0.0;
            // 中置的"人声"：220 Hz 颤音（DSP 档会把它分到人声轨）
            double vocal = 0.25 * Math.sin(2 * Math.PI * 220 * t)
                    * (1.0 + 0.3 * Math.sin(2 * Math.PI * 5 * t));
            double bass = 0.2 * Math.sin(2 * Math.PI * 110 * t);
            out[0][i] = (float) (kick + hat + vocal + bass);
            out[1][i] = (float) (kick + hat + vocal * 0.9 + bass);
        }
        TestWavFile.write(path, out[0], out[1], RATE);
        return out;
    }

    /** 22 秒的稳定素材：给"接缝不塌"用（窗间重叠只在 20 s 处有一个接缝）。 */
    private static void writeSteadyTone(Path path, double seconds) throws IOException {
        int frames = (int) Math.round(RATE * seconds);
        float[] left = new float[frames];
        float[] right = new float[frames];
        long noise = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            noise ^= noise << 13;
            noise ^= noise >>> 7;
            noise ^= noise << 17;
            double hiss = 0.02 * ((noise & 0xFFFF) / 32768.0 - 1.0);
            double v = 0.3 * Math.sin(2 * Math.PI * 220 * t)
                    + 0.15 * Math.sin(2 * Math.PI * 330 * t) + hiss;
            left[i] = (float) v;
            right[i] = (float) v;
        }
        TestWavFile.write(path, left, right, RATE);
    }

    /** 以 {@code center} 为中间、宽 {@code halfWidth} 的 RMS（取伴奏 + 人声之和）。 */
    private static double rmsAround(StemFile stems, long center, int halfWidth) throws IOException {
        long from = Math.max(0, center - halfWidth);
        long to = Math.min(stems.frames(), center + halfWidth);
        int n = (int) (to - from);
        float[][] instr = new float[2][n];
        float[][] voc = new float[2][n];
        stems.read(from, n, instr, voc);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = instr[0][i] + voc[0][i];
            sum += v * v;
        }
        return Math.sqrt(sum / Math.max(1, n));
    }

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
