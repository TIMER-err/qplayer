package dev.t1m3.qplayer.audio.model;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 接缝（{@link BeatModels}）的测试：工厂注入、「为什么不可用」要如实分段报出、
 * 以及给模型前端用的重采样辅助。
 *
 * <p>这一层<b>不碰 ONNX</b>：{@link BeatDetector} 是接口，测试自己造一个替身。
 * 真模型上的推理在 {@code desktop-host} 的 {@code OnnxBeatDetectorTest} 里跑。
 *
 * <p>清单与目录都换成测试自己那一份（{@link ModelDownloads#install}）——
 * 这条正是「一份清单、一处目录」带来的好处：接缝不必知道文件在哪，
 * 测试也不必去碰真实的模型目录。
 */
public class BeatModelsTest {

    private static final String FILE = "beat_this.onnx";

    private static final String MIRROR = "https://hf-mirror.example.com/{file}";

    @After
    public void uninstall() {
        // 三个都是全局静态状态：每条测试跑完都擦干净，免得影响别的测试类
        BeatModels.uninstall();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    /** 没装底座时必须<b>如实报出原因</b>，而不是返回 null 或默默走硬算。 */
    @Test
    public void openWithoutOpenerReportsWhy() {
        BeatModels.uninstall();
        assertTrue(!BeatModels.isInstalled());
        try {
            BeatModels.openPreferred();
            fail("没装底座时不该打开成功");
        } catch (ModelException e) {
            assertEquals(ModelException.Kind.NOT_AVAILABLE, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains("推理底座"));
        }
    }

    /** 模型文件不存在（还没下载）也要能区分出来。 */
    @Test
    public void openReportsAMissingModelFile() {
        fixture(FakeModelFetcher.bytes(512, 21));
        BeatModels.install(new RecordingOpener());

        try {
            BeatModels.open(BeatModels.PREFERRED_ID);
            fail("模型文件不存在时不该打开成功");
        } catch (ModelException e) {
            assertEquals(ModelException.Kind.IO, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains("还没下载"));
        }
    }

    /** 清单里没有的 id 是另一类错误（{@code UNKNOWN}），不能与「没下载」混成一句。 */
    @Test
    public void openReportsAnUnknownId() {
        fixture(FakeModelFetcher.bytes(512, 22));
        BeatModels.install(new RecordingOpener());

        try {
            BeatModels.open("beat_this_small");
            fail("不在清单里的 id 不该被打开");
        } catch (ModelException e) {
            assertEquals(ModelException.Kind.UNKNOWN, e.kind());
        }
    }

    /** 文件在、底座在 → 正常打开，而且路径就是清单那一处目录里的那一个文件。 */
    @Test
    public void openPreferredOpensTheDeclaredFile() throws IOException {
        Fixture f = fixture(FakeModelFetcher.bytes(512, 23));
        write(f.file, FakeModelFetcher.bytes(512, 23));
        RecordingOpener opener = new RecordingOpener();
        BeatModels.install(opener);

        assertTrue("文件在了，可用性就该为真", BeatModels.isAvailable(BeatModels.PREFERRED_ID));

        BeatDetector det = BeatModels.openPreferred();
        try {
            assertEquals(BeatModels.PREFERRED_ID, det.label());
            assertEquals("打开的就是清单里那个文件", f.file, opener.lastPath);
            assertSame(FakeModelFetcher.class, FakeModelFetcher.class); // 占位，避免未使用告警
        } finally {
            det.close();
        }
    }

    /**
     * <b>没有自动的第二选择。</b>
     *
     * <p>即便是「目录里恰好躺着一个别的 checkpoint」这种情形，也必须失败：
     * 模型来源以 folia 的清单为权威，那份清单里拍点模型只有一条；
     * 在权威清单之外悄悄换一个导出，出错的症状是拍点整体偏移，
     * 而日志里只会说「用了模型」——恰恰查不出来。
     */
    @Test
    public void thereIsNoAutomaticSecondChoice() throws IOException {
        Fixture f = fixture(FakeModelFetcher.bytes(512, 24));
        // 目录里放一个清单没提过的文件
        write(new File(f.dir, "beat_this_small.onnx"), FakeModelFetcher.bytes(512, 25));
        BeatModels.install(new RecordingOpener());

        try {
            BeatModels.openPreferred();
            fail("权威清单里的模型不在时不该有别的模型顶上");
        } catch (ModelException e) {
            assertEquals(ModelException.Kind.IO, e.kind());
        }
        assertTrue("不在清单里的文件不该被当成可用", !BeatModels.isAvailable("beat_this_small"));
    }

    /** 采样率相同必须<b>原样返回</b>：多跑一遍 sinc 核只会在最低有效位上引入差异。 */
    @Test
    public void resampleMonoIsPassthroughAtTheSameRate() {
        float[] pcm = new float[1000];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) Math.sin((double) i * 0.01);
        }
        assertSame(pcm, BeatModels.resampleMono(pcm, 22_050, 22_050));
    }

    /**
     * 44.1 kHz → 22.05 kHz 的帧数必须<b>精确</b>。
     *
     * <p>这一步背后是 {@code Resampler.drain} 那条已知缺陷的修法：
     * 只 {@code pull} 不收尾时，流末尾那 16 帧「未来输入」永远凑不齐，
     * 输出会<b>静默少 16 帧</b>。一拍就是 44100 帧输入，比例恰好是 2.0，
     * 所以预期输出是整数 22050。
     */
    @Test
    public void resampleMonoProducesExactlyTheExpectedFrameCount() {
        float[] pcm = new float[44_100];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) Math.sin((double) i * 0.05);
        }
        float[] out = BeatModels.resampleMono(pcm, 44_100, 22_050);
        assertEquals("下采样 2 倍，一秒的输入应当正好给出一秒的输出", 22_050, out.length);
    }

    /** 升采样同理：22.05 kHz → 44.1 kHz。 */
    @Test
    public void resampleMonoUpsamplesTheSameWay() {
        float[] pcm = new float[22_050];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) Math.sin((double) i * 0.05);
        }
        float[] out = BeatModels.resampleMono(pcm, 22_050, 44_100);
        assertEquals(44_100, out.length);
    }

    /** 直流信号重采样之后仍然是直流（核归一化到和为 1，这条性质是可测的）。 */
    @Test
    public void resampleMonoKeepsDcGainAtUnity() {
        float[] pcm = new float[44_100];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = 0.5f;
        }
        float[] out = BeatModels.resampleMono(pcm, 44_100, 22_050);
        for (int i = 64; i < out.length - 64; i++) {
            assertTrue("第 " + i + " 帧应当是 0.5，实际 " + out[i], Math.abs(out[i] - 0.5f) < 1e-4f);
        }
    }

    /** 空输入不炸。 */
    @Test
    public void resampleMonoToleratesEmptyInput() {
        assertEquals(0, BeatModels.resampleMono(new float[0], 44_100, 22_050).length);
    }

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 记账用的底座：真的构造一个假的 {@link BeatDetector}，但不做任何推理。 */
    private static final class RecordingOpener implements BeatModels.Opener {

        File lastPath;

        @Override
        public BeatDetector open(String label, File path, MelParams params, PeakParams peaks) {
            lastPath = path;
            return new FakeDetector(label, params, peaks);
        }
    }

    /** 假检测器：{@code beats} 永远返回空。 */
    static final class FakeDetector implements BeatDetector {

        private final String label;
        private final MelParams params;
        private final PeakParams peaks;
        private boolean closed;

        FakeDetector(String label, MelParams params, PeakParams peaks) {
            this.label = label;
            this.params = params;
            this.peaks = peaks;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public MelParams melParams() {
            return params;
        }

        @Override
        public PeakParams peakParams() {
            return peaks;
        }

        @Override
        public float[] beats(float[] pcm) {
            return new float[0];
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }

    // ------------------------------------------------------------------

    /** 固定目录 + 单条目清单（条目名就是 {@link BeatModels#PREFERRED_ID}）。 */
    private static final class Fixture {

        final File dir;
        final ModelManifestEntry entry;
        final File file;

        Fixture(File dir, ModelManifestEntry entry) {
            this.dir = dir;
            this.entry = entry;
            this.file = new File(dir, entry.file);
        }
    }

    private static Fixture fixture(byte[] body) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "qplayer-test-beatmodels");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs() || dir.isDirectory());

        ModelManifest manifest = ModelManifest.parse(FakeModelFetcher.manifestJsonFor(
                BeatModels.PREFERRED_ID, FILE, body, MIRROR));
        ModelDownloads.install(new ModelDownloads(dir, manifest, new FakeModelFetcher()));
        return new Fixture(dir, manifest.require(BeatModels.PREFERRED_ID));
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }

    private static void write(File f, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }
}
