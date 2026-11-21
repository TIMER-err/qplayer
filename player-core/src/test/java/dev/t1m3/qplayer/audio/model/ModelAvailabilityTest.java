package dev.t1m3.qplayer.audio.model;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 可用性状态机。三条规矩各有一条测试钉着
 * （对应 folia 的 {@code src/services/automix/modelAvailability.ts}）：
 *
 * <ol>
 *   <li>「磁盘上有没有权重」是唯一的问题；</li>
 *   <li>从 false 起步（这个值会变成界面上的一句承诺）；</li>
 *   <li>没有「本次会话内放弃」的闩锁——推理返回空之后只重新问磁盘。</li>
 * </ol>
 *
 * <p>{@code ModelDownloads} 与 {@code ModelAvailability} 都是全局静态状态，
 * 所以每条测试前后都要擦干净（与 {@code BeatModelsTest} 同一条纪律）。
 */
public class ModelAvailabilityTest {

    private static final String NAME = "beat_this";
    private static final String FILE = "beat_this.onnx";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * 「一份权重」在这个测试里只有 16 个字节。
     *
     * <p>可用性检查只比<b>大小</b>，大小是多少与它无关；真去造一个 83MB 的文件
     * 只会让测试慢几十秒、吃掉几十兆堆——那正是「测试要便宜」这条纪律要避免的事。
     */
    private static final byte[] WEIGHTS = FakeModelFetcher.bytes(16, 3);

    @Before
    public void resetStatics() {
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    @After
    public void cleanUp() {
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    @Test
    public void startsFalseSoTheUiNeverPromisesSomethingItDoesNotHave() {
        assertFalse("起点必须是 false：起点高就是在空目录上承诺一个跑不起来的功能",
                ModelAvailability.canRun(NAME));
        assertFalse(ModelAvailability.canRun("htdemucs"));
        assertFalse("不认识的名字也是 false", ModelAvailability.canRun("ghost"));
        assertFalse(ModelAvailability.canRun(null));
    }

    @Test
    public void theOnlyQuestionIsWhetherTheFileIsOnDisk() throws IOException {
        installStore(folder.getRoot());
        assertFalse(ModelAvailability.refresh().get(NAME).booleanValue());

        // 只是「有这个名字的文件」还不算——一次 stat 顺手比大小就把它排除了
        write(new File(folder.getRoot(), FILE), new byte[]{1, 2, 3});
        assertFalse("大小对不上的文件不是权重", ModelAvailability.canRun(NAME));

        write(new File(folder.getRoot(), FILE), WEIGHTS);
        assertTrue("大小对得上就承认它在：这一次 stat 本来就要做",
                ModelAvailability.refresh().get(NAME).booleanValue());
        assertTrue(ModelAvailability.canRun(NAME));

        // 删掉之后立刻回到 false：存在的就是磁盘，没别的
        assertTrue(new File(folder.getRoot(), FILE).delete());
        assertFalse(ModelAvailability.refresh().get(NAME).booleanValue());
    }

    @Test
    public void aFailedRunDoesNotLatchTheModelOffForTheSession() throws IOException {
        installStore(folder.getRoot());
        write(new File(folder.getRoot(), FILE), WEIGHTS);
        ModelAvailability.refresh();
        assertTrue(ModelAvailability.canRun(NAME));

        // 推理返回空：可能只是工作进程重启掐断了在途请求，权重还在
        ModelAvailability.noteFailed(NAME);
        assertTrue("空结果不是「模型坏了」的证据：不许把它关掉一个会话", ModelAvailability.canRun(NAME));

        // 权重真的没了 → false（这才是唯一可行动的那条分支）
        assertTrue(new File(folder.getRoot(), FILE).delete());
        ModelAvailability.noteFailed(NAME);
        assertFalse(ModelAvailability.canRun(NAME));

        // 又下回来了 → 立刻自愈，不需要重启 App
        write(new File(folder.getRoot(), FILE), WEIGHTS);
        assertTrue(ModelAvailability.refresh().get(NAME).booleanValue());
        assertTrue(ModelAvailability.canRun(NAME));
    }

    @Test
    public void aFailedRefreshKeepsTheLastAnswerRatherThanDowngradingAWorkingBuild() {
        installStore(folder.getRoot());
        writeQuietly(new File(folder.getRoot(), FILE), WEIGHTS);
        ModelAvailability.refresh();
        assertTrue(ModelAvailability.canRun(NAME));

        // 一次读失败（这里用「目录求值就抛」来模拟）不该把一套本来能用的构建
        // 降级成「什么都没有」——那会把一个暂时性的问题变成一个看起来永久的问题
        ModelDownloads broken = new ModelDownloads(new Supplier<File>() {
            @Override
            public File get() {
                throw new IllegalStateException("磁盘这次读不了");
            }
        }, ModelManifest.embedded(), new FakeModelFetcher());
        ModelDownloads.install(broken);

        ModelAvailability.refresh();
        assertTrue("上一次的答案必须留着", ModelAvailability.canRun(NAME));
    }

    @Test
    public void resetGoesBackToAllFalse() throws IOException {
        installStore(folder.getRoot());
        write(new File(folder.getRoot(), FILE), WEIGHTS);
        ModelAvailability.refresh();
        assertTrue(ModelAvailability.canRun(NAME));

        ModelAvailability.reset();
        assertFalse(ModelAvailability.canRun(NAME));
        for (Boolean value : ModelAvailability.snapshot().values()) {
            assertFalse("reset 之后每一项都该是 false", value.booleanValue());
        }
        // 起点那份「全 false」是按内嵌清单铺的——**数量从清单本身算**，
        // 而不是写死一个数：加模型是常事，而「快照少了一项」才是这条测试要挡的东西
        // （少一项的表现是那个模型永远显示「不可用」，怎么下都下不出来）。
        assertEquals("快照必须覆盖内嵌清单里的每一个模型",
                ModelManifest.embedded().models().size(),
                ModelAvailability.snapshot().size());
    }

    private void installStore(File dir) {
        ModelManifest manifest = ModelManifest.parse(
                FakeModelFetcher.manifestJsonFor(NAME, FILE, WEIGHTS, "https://m.test/{file}"));
        ModelDownloads.install(new ModelDownloads(dir, manifest, new FakeModelFetcher()));
        ModelAvailability.reset();
    }

    private static void write(File file, byte[] body) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(body);
        } finally {
            out.close();
        }
    }

    private static void writeQuietly(File file, byte[] body) {
        try {
            write(file, body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
