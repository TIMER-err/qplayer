package dev.t1m3.qplayer.audio.model;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * store 的测试：<b>「有没有」与「对不对」是两件事</b>、下载那条链的硬要求、
 * 以及可用性那三条规矩。
 *
 * <h2>这个类测的是<b>共享</b>的那一份实现</h2>
 *
 * <p>{@link ModelStore} 自己不再实现下载：清单只有一份（{@link ModelManifest}）、
 * 下载只有一份（{@link ModelDownloads}）、目录只有一处（{@code AppDirs.modelsDir()}）。
 * 所以下面的断言走的是 {@link ModelStore#install} 这个<b>入口</b>，
 * 验的是共享实现的行为——那正是「拍点这条路与分轨那条路必须一致」的那部分。
 * 固定目录 + {@link FakeModelFetcher} 让四条路径（成功 / 哈希不符 / 截断 / 镜像回退）
 * 在几十字节的内存里跑完，<b>一行产品代码都不绕过、一个字节都不下载</b>。
 *
 * <h2>硬要求逐条对应哪条测试</h2>
 *
 * <ul>
 *   <li>镜像按顺序试 —— {@link #mirrorsAreTriedInOrderAndTheFirstHashMatchWins}</li>
 *   <li>第一个哈希匹配的胜出 —— 同上（第一个故意给字节数对、内容不对的东西）</li>
 *   <li>写 {@code <file>.part} 再改名 —— {@link #theWeightLandsUnderItsRealNameAndNoPartIsLeft}</li>
 *   <li>校验不过绝不采用 —— {@link #aTruncatedResponseIsNeverAdopted}、
 *       {@link #aWrongChecksumIsNeverAdopted}</li>
 *   <li>把「用了哪个镜像」记下来 —— {@link #mirrorsAreTriedInOrderAndTheFirstHashMatchWins}</li>
 *   <li>权重绝不进包 —— {@link BeatModelSourceTest}</li>
 * </ul>
 */
public class ModelStoreTest {

    /** 测试用的模型名与文件名（不必是真的 beat_this，验的是链子不是权重）。 */
    private static final String NAME = "beat_this";

    private static final String FILE = "beat_this.onnx";

    private static final String MIRROR_A = "https://hf-mirror.example.com/{file}";

    private static final String MIRROR_B = "https://second.example.com/{file}";

    private static final String MIRROR_C = "https://third.example.com/{file}";

    @After
    public void restoreGlobalState() {
        // 这两个都是全局静态状态：换过就要擦干净，否则会串到别的测试类
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    // ==================================================================
    // 一、「有没有」与「对不对」
    // ==================================================================

    /** 文件不存在时 {@code verify} 返回 false 而不是报错——「还没下」与「下坏了」是两件事。 */
    @Test
    public void verifyReturnsFalseForMissingFile() {
        Fixture f = fixture(FakeModelFetcher.bytes(4096, 1));
        assertFalse(ModelStore.verify(f.entry));
        assertFalse(ModelStore.isPresent(f.entry));
        assertFalse(ModelStore.available(NAME));
    }

    /** 内容与校验值不符时必须判为不通过。 */
    @Test
    public void verifyDetectsAWrongChecksum() throws IOException {
        Fixture f = fixture(FakeModelFetcher.bytes(4096, 2));
        // 大小对得上、内容不对：这正是「只比大小」会漏掉的那种
        write(f.file, FakeModelFetcher.bytes(4096, 3));

        assertTrue("大小对得上，present 应当为真", ModelStore.isPresent(f.entry));
        assertFalse("内容不符却报告校验通过——这正是必须挡住的情况",
                ModelStore.verify(f.entry));
        assertTrue("可用性只问「有没有」，所以它仍然为真（这是有意的）",
                ModelStore.available(NAME));
    }

    /** 校验值正确时必须判为通过，且 {@code hashFile} 与一次性哈希一致。 */
    @Test
    public void verifyAcceptsMatchingContent() throws IOException {
        byte[] body = FakeModelFetcher.bytes(4096, 4);
        Fixture f = fixture(body);
        write(f.file, body);

        assertTrue(ModelStore.verify(f.entry));
        assertEquals(FakeModelFetcher.sha256(body), ModelStore.hashFile(f.file));
    }

    /** 大小不对时直接判否，不必读完整个文件。 */
    @Test
    public void verifyRejectsAWrongSize() throws IOException {
        Fixture f = fixture(FakeModelFetcher.bytes(4096, 5));
        write(f.file, FakeModelFetcher.bytes(100, 5));
        assertFalse(ModelStore.verify(f.entry));
    }

    /**
     * 大文件的分块哈希必须与一次性哈希一致。
     *
     * <p>校验动辄上百兆的文件，{@code hashFile} 是分块读的；这块边界如果和
     * 一次性路径不一致，就会出现「明明是同一条规矩却结果不同」。
     */
    @Test
    public void hashFileMatchesOneshotForLargeInput() throws IOException {
        File dir = tempDir("hash-large");
        File path = new File(dir, "big.bin");
        // 3 MiB 多一点点——跨过 1 MiB 的读缓冲边界
        byte[] data = FakeModelFetcher.bytes(3_100_000, 6);
        write(path, data);

        assertEquals("分块哈希与一次性哈希不一致",
                FakeModelFetcher.sha256(data), ModelStore.hashFile(path));
        deleteRecursively(dir);
    }

    /**
     * 清单里<b>不存在</b>「没有校验值」这个状态——{@link ModelManifest} 在解析时就挡住了。
     *
     * <p>上游那一版（Sonalis 的 {@code store.rs}）里 {@code sha256} 可以是空串，
     * 于是需要一条「空值不能声称通过」的运行期测试。folia 形状的清单把它变成不可表达：
     * 不是 64 位十六进制就直接读不出来。这条测试钉的就是这件事——
     * <b>把「忘了填校验值」从一个运行期判断变成一个加载期错误</b>。
     */
    @Test
    public void aMissingChecksumIsUnrepresentable() {
        String json = "{\"mirrors\":[\"" + MIRROR_A + "\"],\"models\":[{"
                + "\"name\":\"" + NAME + "\",\"file\":\"" + FILE + "\",\"bytes\":10,"
                + "\"sha256\":\"\",\"license\":\"MIT\",\"upstream\":\"https://x\"}]}";
        try {
            ModelManifest.parse(json);
            fail("缺校验值的条目不该解析得出来");
        } catch (ModelException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("sha256"));
        }
    }

    // ==================================================================
    // 二、下载那条链
    // ==================================================================

    /**
     * <b>镜像按顺序试，第一个字节匹配哈希的胜出</b>，并且把「用了哪个镜像」记下来。
     *
     * <p>第一个镜像故意发一份字节数对、哈希不对的东西——它不是「连不上」，
     * 而是「连上了、下完了、内容是错的」，只有哈希那一关能挡住它。
     */
    @Test
    public void mirrorsAreTriedInOrderAndTheFirstHashMatchWins() {
        byte[] good = FakeModelFetcher.bytes(4096, 7);
        byte[] impostor = FakeModelFetcher.bytes(4096, 8);
        Fixture f = fixture(good);
        FakeModelFetcher net = new FakeModelFetcher();
        net.serve(ModelManifest.expand(MIRROR_A, f.entry), impostor);
        net.serve(ModelManifest.expand(MIRROR_B, f.entry), good);
        net.serve(ModelManifest.expand(MIRROR_C, f.entry), good);
        f.installWith(net);

        ModelDownloads.Snapshot snap = ModelStore.install(f.entry);

        assertTrue("第二个镜像的字节是对的，就该成功：" + snap.error, snap.isReady());
        assertEquals("必须按清单顺序试，而且第三个镜像不该被碰",
                "[" + ModelManifest.expand(MIRROR_A, f.entry) + ", "
                        + ModelManifest.expand(MIRROR_B, f.entry) + "]",
                net.requests().toString());
        assertEquals("用了哪个镜像必须被记下来", "second.example.com", snap.mirror);
        assertTrue(ModelStore.verify(f.entry));
    }

    /** <b>写 {@code <file>.part} 再改名</b>：落地之后真名下有文件、{@code .part} 不留。 */
    @Test
    public void theWeightLandsUnderItsRealNameAndNoPartIsLeft() {
        byte[] good = FakeModelFetcher.bytes(8192, 9);
        Fixture f = fixture(good);
        FakeModelFetcher net = new FakeModelFetcher();
        net.serve(ModelManifest.expand(MIRROR_A, f.entry), good);
        f.installWith(net);

        // .part 的路径就是「真名 + .part」（用户明确要求这个形状）
        assertEquals(new File(f.dir, FILE + ".part"), f.downloads.partFor(f.entry));
        assertEquals(new File(f.dir, FILE), f.downloads.fileFor(f.entry));

        ModelDownloads.Snapshot snap = ModelStore.install(f.entry);

        assertTrue(snap.isReady());
        assertTrue("真名下必须有那份文件", f.file.isFile());
        assertEquals(good.length, f.file.length());
        assertFalse("改名之后不该留下 .part", f.downloads.partFor(f.entry).exists());
        assertEquals("hf-mirror.example.com", snap.mirror);
    }

    /**
     * <b>被截断的响应绝不采用</b>：干净地提前结束（服务端发少了）。
     *
     * <p>这是最阴的一种失败：HTTP 200、没有异常、字节就是少了。
     * 真名下永远不该出现它——那会是一个「看起来装好了」的坏文件，
     * 而它加载得起来、答得自信、答案是错的。
     */
    @Test
    public void aTruncatedResponseIsNeverAdopted() {
        byte[] good = FakeModelFetcher.bytes(8192, 10);
        Fixture f = fixture(good);
        FakeModelFetcher net = new FakeModelFetcher();
        net.endAfter(ModelManifest.expand(MIRROR_A, f.entry), good, 3000);
        net.endAfter(ModelManifest.expand(MIRROR_B, f.entry), good, 3000);
        net.endAfter(ModelManifest.expand(MIRROR_C, f.entry), good, 3000);
        f.installWith(net);

        ModelDownloads.Snapshot snap = ModelStore.install(f.entry);

        assertFalse("截断的响应不该被当成装好了", snap.isReady());
        assertFalse("真名下不能有它——那会是一个「看起来装好了」的坏文件", f.file.exists());
        assertFalse(ModelStore.isPresent(f.entry));
        assertFalse(ModelStore.verify(f.entry));
        assertFalse("可用性必须如实为 false", ModelStore.available(NAME));
    }

    /** <b>哈希不符绝不采用</b>：字节数对得上，但内容被换了（镜像替换）。 */
    @Test
    public void aWrongChecksumIsNeverAdopted() {
        byte[] good = FakeModelFetcher.bytes(8192, 11);
        byte[] impostor = FakeModelFetcher.bytes(8192, 12);
        Fixture f = fixture(good);
        FakeModelFetcher net = new FakeModelFetcher();
        net.serve(ModelManifest.expand(MIRROR_A, f.entry), impostor);
        net.serve(ModelManifest.expand(MIRROR_B, f.entry), impostor);
        net.serve(ModelManifest.expand(MIRROR_C, f.entry), impostor);
        f.installWith(net);

        ModelDownloads.Snapshot snap = ModelStore.install(f.entry);

        assertFalse(snap.isReady());
        assertFalse("字节数一样、内容不对——真名下尤其不能有它", f.file.exists());
        assertFalse(ModelStore.available(NAME));
        assertEquals("三个镜像都该试过", 3, net.requests().size());
    }

    /** 连不上时如实失败，且什么都不留。 */
    @Test
    public void aFailedTransferLeavesNothingBehind() {
        Fixture f = fixture(FakeModelFetcher.bytes(4096, 13));
        FakeModelFetcher net = new FakeModelFetcher();
        net.fail(ModelManifest.expand(MIRROR_A, f.entry), "连不上");
        net.fail(ModelManifest.expand(MIRROR_B, f.entry), "连不上");
        net.fail(ModelManifest.expand(MIRROR_C, f.entry), "连不上");
        f.installWith(net);

        ModelDownloads.Snapshot snap = ModelStore.install(f.entry);

        assertFalse(snap.isReady());
        assertFalse(f.file.exists());
        assertTrue(snap.error.length() > 0);
    }

    /** 已经装好且校验通过时不该再下载一遍。 */
    @Test
    public void anAlreadyPresentWeightIsNotDownloadedAgain() throws IOException {
        byte[] good = FakeModelFetcher.bytes(4096, 14);
        Fixture f = fixture(good);
        write(f.file, good);
        FakeModelFetcher net = new FakeModelFetcher();
        f.installWith(net);

        ModelDownloads.Snapshot snap = ModelStore.install(f.entry);

        assertTrue(snap.isReady());
        assertEquals("已经在了就不该发请求", 0, net.openCount());
    }

    // ==================================================================
    // 三、可用性：一个布尔，只问磁盘
    // ==================================================================

    /**
     * <b>可用性就是「磁盘上有没有那个文件」</b>，而且从 {@code false} 起步。
     *
     * <p>这条值会变成界面上的一句承诺，所以起点要低：先低估一个回合就自愈，
     * 而在一个空目录上承诺「可以做对拍」是另一回事。
     */
    @Test
    public void availabilityStartsFalseAndIsJustTheDisk() throws IOException {
        byte[] good = FakeModelFetcher.bytes(4096, 15);
        Fixture f = fixture(good);

        assertFalse("空目录必须是 false", ModelStore.available(NAME));
        assertFalse(ModelAvailability.canRun(NAME));

        write(f.file, good);

        assertTrue("文件一出现就该为 true，不需要谁去清状态", ModelStore.available(NAME));
        ModelAvailability.refresh();
        assertTrue("刷新之后界面上那个承诺才是对的", ModelAvailability.canRun(NAME));

        assertTrue(f.file.delete());
        assertFalse("文件没了就该回到 false", ModelStore.available(NAME));
        ModelAvailability.refresh();
        assertFalse(ModelAvailability.canRun(NAME));
    }

    /** <b>下载落地后自愈</b>：下完重新问一次就是 true，不需要重启也不需要清缓存。 */
    @Test
    public void availabilitySelfHealsAfterTheDownloadLands() {
        byte[] good = FakeModelFetcher.bytes(4096, 16);
        Fixture f = fixture(good);
        FakeModelFetcher net = new FakeModelFetcher();
        net.serve(ModelManifest.expand(MIRROR_A, f.entry), good);
        f.installWith(net);

        ModelAvailability.refresh();
        assertFalse("下之前是 false", ModelAvailability.canRun(NAME));

        assertTrue(ModelStore.install(f.entry).isReady());

        assertTrue("下完就该是 true（自愈），不用等谁去清什么", ModelStore.available(NAME));
        ModelAvailability.refresh();
        assertTrue(ModelAvailability.canRun(NAME));
    }

    /**
     * <b>没有「本次会话内放弃」的闩锁。</b>
     *
     * <p>推理返回空既可能是权重不在、也可能是进程重启掐断了在途请求，两者都会自愈，
     * 所以唯一该做的是重新问磁盘。这条钉住 {@link ModelAvailability#noteFailed}
     * <b>不会</b>把模型在本会话里关掉。
     */
    @Test
    public void aFailureDoesNotLatchTheModelOff() throws IOException {
        byte[] good = FakeModelFetcher.bytes(4096, 17);
        Fixture f = fixture(good);
        write(f.file, good);
        ModelAvailability.refresh();
        assertTrue(ModelAvailability.canRun(NAME));

        // 一次推理返回了空
        ModelAvailability.noteFailed(NAME);

        assertTrue("空结果不是「坏了」的证据，不许闩死", ModelAvailability.canRun(NAME));
        assertTrue(ModelStore.available(NAME));
    }

    // ==================================================================
    // 四、残留清理
    // ==================================================================

    /**
     * {@code .part} 残留的清理：三条规矩一起验。
     *
     * <p>① 刚写下的 {@code .part} <b>不能</b>删——它多半正在被写，删掉它等于毁掉
     * 另一次正在进行的下载（而失败会出现在对方那里，这边什么也看不到）；
     * ② 足够旧的 {@code .part} 要删，否则断一次积一个，每个上百兆；
     * ③ 正常文件永远不动。
     *
     * <p>「旧」是把「现在」挪到未来构造出来的，而不是去改文件的修改时间。
     */
    @Test
    public void stalePartCleanupIsConservative() throws IOException {
        File dir = tempDir("part-clean");
        // 同名目录不该被当成残留文件
        assertTrue(new File(dir, "sub.part").mkdirs());

        File fresh = new File(dir, FILE + ".part");
        write(fresh, "half downloaded".getBytes("UTF-8"));
        File model = new File(dir, FILE);
        write(model, "model".getBytes("UTF-8"));
        File noExt = new File(dir, "part");
        write(noExt, "x".getBytes("UTF-8"));
        File otherExt = new File(dir, "notes.part.txt");
        write(otherExt, "x".getBytes("UTF-8"));

        // ① 现在这一刻：一个都不该删（公开入口走的就是这条路）
        assertEquals("刚写的 .part 被删了", 0, ModelStore.cleanStaleParts(dir));
        assertTrue("正在被写的 .part 没了，这次下载就毁了", fresh.isFile());

        // ② 「现在」推到 48 小时之后：那个 .part 已经够旧
        long later = System.currentTimeMillis() + 48L * 60L * 60L * 1000L;
        assertEquals("够旧的 .part 应当被清掉", 1,
                ModelStore.cleanPartsAsOf(dir, ModelStore.STALE_PART_AGE_MILLIS, later));
        assertFalse("够旧的残留没清掉，会一直积在模型目录里", fresh.exists());

        // ③ 其余一律不动
        assertTrue("模型文件被误删了", model.isFile());
        assertTrue("没有后缀的文件不该按 .part 处理", noExt.isFile());
        assertTrue("后缀不是 .part 的文件不该动", otherExt.isFile());
        assertTrue("同名目录不该动", new File(dir, "sub.part").isDirectory());

        deleteRecursively(dir);
    }

    /** 目录不存在时清理入口返回 0，而不是报错——没下过模型就没有残留。 */
    @Test
    public void stalePartCleanupToleratesAMissingDirectory() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "qplayer-test-part-nodir");
        deleteRecursively(dir);
        assertEquals("目录不存在不是错误，清理入口不该因此失败",
                0, ModelStore.cleanStaleParts(dir));
        assertEquals("null 目录也不该炸", 0, ModelStore.cleanStaleParts(null));
    }

    /**
     * 启动时的清理<b>必须真的被调到</b>，而且调的是启动时给的那个目录。
     *
     * <p>钉这条的理由很直接：清理写好之后<b>一个调用方都没有</b>的话，
     * 方法再对也等于没接上。这里用一个记账的动作替掉真正的清理，
     * 要问的就是「启动路径有没有伸手去叫它」。
     */
    @Test
    public void startupCleanupIsActuallyInvoked() throws InterruptedException {
        final File dir = new File(System.getProperty("java.io.tmpdir"), "qplayer-test-part-startup");
        final AtomicReference<File> seen = new AtomicReference<File>();
        final CountDownLatch done = new CountDownLatch(1);

        Thread t = ModelStore.spawnStalePartCleanupWith(dir, new ModelStore.PartCleaner() {
            @Override
            public int clean(File d) {
                seen.set(d);
                done.countDown();
                return 0;
            }
        });
        assertNotNull("后台清理线程应当能创建", t);

        assertTrue("启动时的清理没有被调用——这个入口就又回到了「没有调用方」的状态",
                done.await(10, TimeUnit.SECONDS));
        assertEquals("清理的不是启动时给的那个目录", dir, seen.get());
        t.join(10_000);
    }

    /**
     * 启动<b>不能等</b>清理：磁盘慢的时候同步扫一遍目录就是「启动被拖住」的样子。
     *
     * <p>卡住的时间是<b>有限的</b>（3 秒）：万一真被写成同步调用，表现会是
     * 「测试挂死」而不是「测试失败」，而挂死是最难查的一种失败。
     */
    @Test
    public void startupCleanupDoesNotHoldUpStartup() throws InterruptedException {
        final File dir = new File(System.getProperty("java.io.tmpdir"), "qplayer-test-part-block");
        final CountDownLatch began = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean(false);

        Thread t = ModelStore.spawnStalePartCleanupWith(dir, new ModelStore.PartCleaner() {
            @Override
            public int clean(File d) {
                began.countDown();
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finished.set(true);
                return 0;
            }
        });
        assertNotNull(t);

        assertTrue("后台清理一直没开始", began.await(10, TimeUnit.SECONDS));
        assertFalse("启动等在了清理上——磁盘慢的时候这就是启动被拖住的样子",
                finished.get());

        t.join(20_000);
        assertTrue("被放行之后后台清理应当跑完", finished.get());
    }

    /** 清理出错<b>不能影响启动</b>：磁盘只读、权限不足都只该是「这次没清成」。 */
    @Test
    public void aFailingCleanupDoesNotBreakStartup() throws InterruptedException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "qplayer-test-part-fail");
        Thread t = ModelStore.spawnStalePartCleanupWith(dir, new ModelStore.PartCleaner() {
            @Override
            public int clean(File d) {
                throw ModelException.io("磁盘只读");
            }
        });
        assertNotNull(t);
        t.join(10_000);
        assertFalse("线程应当已经结束（清理失败不该把它留住）", t.isAlive());
    }

    /** 公开入口（启动路径用的就是它）在目录不存在时也得安静收场。 */
    @Test
    public void startupCleanupEntrySurvivesAMissingDirectory() throws InterruptedException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "qplayer-test-part-nodir2");
        deleteRecursively(dir);

        Thread t = ModelStore.spawnStalePartCleanup(dir);
        assertNotNull("后台清理线程应当能创建", t);
        t.join(10_000);
        assertFalse(t.isAlive());
    }

    // ==================================================================
    // 测试夹具
    // ==================================================================

    /** 一个固定目录 + 一份单条目清单 + 一个可换的取字节实现。 */
    private static final class Fixture {

        final File dir;
        final ModelManifest manifest;
        final ModelManifestEntry entry;
        final File file;
        ModelDownloads downloads;

        Fixture(File dir, ModelManifest manifest, ModelManifestEntry entry) {
            this.dir = dir;
            this.manifest = manifest;
            this.entry = entry;
            this.file = new File(dir, entry.file);
        }

        /** 把全局那一份换成本夹具（目录固定、清单是本测试造的那份）。 */
        void installWith(ModelFetcher fetcher) {
            downloads = new ModelDownloads(dir, manifest, fetcher);
            ModelDownloads.install(downloads);
        }
    }

    private static Fixture fixture(byte[] body) {
        File dir = tempDir("store");
        ModelManifest manifest = ModelManifest.parse(FakeModelFetcher.manifestJsonFor(
                NAME, FILE, body, MIRROR_A, MIRROR_B, MIRROR_C));
        Fixture f = new Fixture(dir, manifest, manifest.require(NAME));
        f.installWith(new FakeModelFetcher());
        return f;
    }

    private static File tempDir(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "qplayer-test-" + name);
        deleteRecursively(dir);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
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
