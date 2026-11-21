package dev.t1m3.qplayer.audio.model;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 下载这条路本身：成功 / 哈希不符 / 中断 / 镜像回退。
 *
 * <p><b>一条真模型都不下。</b> 每次用几十到几百 KB 的内存字节造一份「镜像」，
 * 跑的却是产品代码那条完整的路（{@link ModelDownloads}），所以这四条路径
 * 在没有网络、没有 108MB 文件的机器上也能真的跑过。
 */
public class ModelDownloadsTest {

    private static final String NAME = "test_model";
    private static final String FILE = "test_model.onnx";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final byte[] good = FakeModelFetcher.bytes(512, 42);

    private ModelDownloads store(FakeModelFetcher fetcher, String... mirrors) {
        ModelManifest manifest = ModelManifest.parse(
                FakeModelFetcher.manifestJsonFor(NAME, FILE, good, mirrors));
        return new ModelDownloads(folder.getRoot(), manifest, fetcher);
    }

    // ---- 成功 ---------------------------------------------------------------

    @Test
    public void installDownloadsHashesAndRenames() {
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .serve("https://mirror-one.test/models/" + FILE, good);
        ModelDownloads downloads = store(fetcher, "https://mirror-one.test/models/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        List<String> states = new ArrayList<String>();
        ModelDownloads.Snapshot result = downloads.download(entry, collecting(states));

        assertEquals(ModelDownloads.STATE_READY, result.state);
        assertEquals(Boolean.TRUE, result.verified);
        assertEquals("mirror-one.test", result.mirror);
        assertEquals(100, result.percent);

        File target = downloads.fileFor(entry);
        assertTrue("文件应当在真名下", target.isFile());
        assertEquals(good.length, target.length());
        assertFalse("半成品必须已经被改名走", downloads.partFor(entry).exists());
        assertEquals("只该请求那一个镜像", 1, fetcher.openCount());
        assertTrue("应当先发一次下载中：" + states, states.contains(ModelDownloads.STATE_DOWNLOADING));
        assertEquals(ModelDownloads.STATE_READY, states.get(states.size() - 1));
    }

    @Test
    public void progressCallbacksAdvanceWhileBytesArrive() {
        // 512KB = 8 次 64KB 的读，每次 sleep 60ms → 远超过 200ms 的节流间隔，
        // 所以中途一定会发出带百分比的进度（这不是「碰运气」，是时间上的必然）。
        byte[] big = FakeModelFetcher.bytes(512 * 1024, 5);
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .serve("https://m.test/" + FILE, big)
                .slow("https://m.test/" + FILE, 60L);
        ModelManifest manifest = ModelManifest.parse(
                FakeModelFetcher.manifestJsonFor(NAME, FILE, big, "https://m.test/{file}"));
        ModelDownloads downloads = new ModelDownloads(folder.getRoot(), manifest, fetcher);

        final List<Integer> percents = new ArrayList<Integer>();
        downloads.download(downloads.manifest().require(NAME), new ModelDownloads.Listener() {
            @Override
            public void onState(ModelDownloads.Snapshot snapshot) {
                if (ModelDownloads.STATE_DOWNLOADING.equals(snapshot.state)) {
                    percents.add(Integer.valueOf(snapshot.percent));
                }
            }
        });

        assertTrue("进度回调不该只有「开始」那一次：" + percents, percents.size() >= 2);
        int last = percents.get(percents.size() - 1).intValue();
        assertTrue("中途的进度应当大于 0：" + percents, last > 0);
        assertTrue("中途的进度不该已经报 100：" + percents, last < 100);
    }

    // ---- 哈希不符：绝不采用 --------------------------------------------------

    @Test
    public void wrongBytesAreNeverAdopted() {
        byte[] impostor = FakeModelFetcher.bytes(512, 99);
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .serve("https://liar.test/" + FILE, impostor);
        ModelDownloads downloads = store(fetcher, "https://liar.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        ModelDownloads.Snapshot result = downloads.download(entry);

        assertEquals(ModelDownloads.STATE_FAILED, result.state);
        assertTrue("失败原因必须点名 sha256：" + result.error, result.error.contains("sha256"));
        assertTrue("必须说清不会被采用：" + result.error, result.error.contains("不会采用"));
        assertFalse("坏字节绝不能落在真名下", downloads.fileFor(entry).exists());
        assertFalse("这份垃圾没有留着的理由", downloads.partFor(entry).exists());
        assertEquals(1, result.attempts.size());
        assertTrue(result.attempts.get(0).contains("liar.test"));
    }

    /** 干净地少发一段（被截断的文件）：长度先对不上，绝不能改名。 */
    @Test
    public void shortResponseIsRejectedBySize() {
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .endAfter("https://short.test/" + FILE, good, 100);
        ModelDownloads downloads = store(fetcher, "https://short.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        ModelDownloads.Snapshot result = downloads.download(entry);

        assertEquals(ModelDownloads.STATE_FAILED, result.state);
        assertTrue("原因里要有字节数：" + result.error, result.error.contains("100 字节"));
        assertFalse(downloads.fileFor(entry).exists());
        assertFalse(downloads.partFor(entry).exists());
    }

    // ---- 中断：留住 .part，绝不留半个可用文件 ---------------------------------

    @Test
    public void interruptedTransferLeavesOnlyAPartFile() {
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .resetAfter("https://flaky.test/" + FILE, good, 200);
        ModelDownloads downloads = store(fetcher, "https://flaky.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        ModelDownloads.Snapshot result = downloads.download(entry);

        assertEquals(ModelDownloads.STATE_FAILED, result.state);
        assertTrue("原因里要有中断这件事：" + result.error, result.error.contains("连接中断"));
        assertFalse("真名下绝不能出现半个文件", downloads.fileFor(entry).exists());
        assertTrue("半成品留在 .part 里", downloads.partFor(entry).exists());
        assertEquals(200, downloads.partFor(entry).length());
    }

    @Test
    public void canceledDownloadNeverRenamesAndSaysWhereThePartIs() {
        FakeModelFetcher fetcher = new FakeModelFetcher().serve("https://m.test/" + FILE, good);
        final ModelDownloads downloads = store(fetcher, "https://m.test/{file}");
        final ModelManifestEntry entry = downloads.manifest().require(NAME);

        // 在「开始下载」那一次回调里按取消：真实界面按的也是这个时机（用户看到转圈就点取消）
        ModelDownloads.Listener listener = new ModelDownloads.Listener() {
            @Override
            public void onState(ModelDownloads.Snapshot snapshot) {
                if (ModelDownloads.STATE_DOWNLOADING.equals(snapshot.state)) {
                    downloads.cancel(NAME);
                }
            }
        };
        ModelDownloads.Snapshot result = downloads.download(entry, listener);

        assertEquals(ModelDownloads.STATE_CANCELED, result.state);
        assertTrue(result.error.contains(".part"));
        assertFalse(downloads.fileFor(entry).exists());
        assertEquals("取消了就不该再发请求", 0, fetcher.openCount());
    }

    /**
     * <b>连上了但一个字节都不来：必须在首字节预算内被判成失败、如实报出原因、
     * 并且换下一个镜像——而不是永远挂着。</b>
     *
     * <p>这条是用户报的「卡在 0%」的核心。现实里的形状是：TCP 通了、请求发出去了、
     * 服务器（或中间那个代理）什么都不说。过去这里只有
     * {@link NetModelFetcher#READ_TIMEOUT_MS}（<b>每一次读</b> 60 秒）挡着，
     * 所以三条镜像轮流沉默 = 三分钟一动不动的 0%，而日志里一个字都没有。
     *
     * <p>断言落到三件事上，缺一不可：
     * <ol>
     *   <li>它<b>结束了</b>（在预算内，不是 60 秒、更不是永远）；</li>
     *   <li>原因<b>说的是「首字节没有任何数据」</b>，而不是一句泛泛的网络错误——
     *       用户与排查的人要能从这里读出「是那个镜像不说话」；</li>
     *   <li>它<b>换到了下一个镜像并成功</b>：预算的意义是让下载继续走下去，
     *       不是把整次下载判死。</li>
     * </ol>
     *
     * <p>预算用测试自己的短值（{@link ModelDownloads#firstByteBudgetMs}），
     * 而产品默认值另有断言钉住它与「实测最快那条镜像」的关系——见
     * {@link #theFirstByteBudgetIsLooseEnoughForTheSlowestWorkingMirror()}。
     */
    @Test
    public void aSilentMirrorIsDroppedWithinTheFirstByteBudgetAndTheNextOneIsTried() {
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .silent("https://mute.test/" + FILE)
                .serve("https://good.test/" + FILE, good);
        ModelDownloads downloads = store(fetcher,
                "https://mute.test/{file}", "https://good.test/{file}");
        downloads.firstByteBudgetMs = 3_000L;
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        long started = System.currentTimeMillis();
        ModelDownloads.Snapshot result = downloads.download(entry);
        long took = System.currentTimeMillis() - started;

        assertEquals("沉默的镜像之后必须换到下一个并成功", ModelDownloads.STATE_READY, result.state);
        assertEquals("good.test", result.mirror);
        assertEquals("两个镜像都要被试过", 2, fetcher.openCount());
        assertEquals("沉默那一次的失败要留在记录里（成功那次不产生原因）",
                1, result.attempts.size());
        String first = result.attempts.get(0);
        assertTrue("原因必须点名那个镜像：" + first, first.contains("mute.test"));
        assertTrue("原因必须说清是「首字节没有任何数据」：" + first,
                first.contains("首字节") && first.contains("没有任何数据"));
        assertTrue("照实的等待秒数也要在：" + first, first.contains("3 秒"));
        assertTrue("必须在首字节预算内结束，实际用了 " + took + " ms（上限给 15 秒余量）",
                took < 15_000L);
    }

    /**
     * 首字节预算不许短到把「能用的那条慢镜像」掐掉。
     *
     * <p>这不是形式主义：清单里的首选镜像 {@code hf-mirror.com} 是本机实测唯一
     * 可达的那条，而它对 83MB 那个权重的首字节是 6.5~10.8 秒（见
     * {@link ModelDownloads#FIRST_BYTE_TIMEOUT_MS} 的注释）。预算要是设在 15 秒，
     * 一次正常但稍慢的响应就会被判死，而后面两条镜像在本机是直接 TLS 失败的——
     * 也就是说「加个短预算」会把唯一能用的路掐掉，比不加更糟。
     */
    @Test
    public void theFirstByteBudgetIsLooseEnoughForTheSlowestWorkingMirror() {
        assertTrue("首字节预算要明显长于实测最慢的 10.8 秒："
                        + ModelDownloads.FIRST_BYTE_TIMEOUT_MS,
                ModelDownloads.FIRST_BYTE_TIMEOUT_MS >= 20_000L);
        assertTrue("但它仍要比「每一次读」的 60 秒明显短，否则加它就没有意义："
                        + ModelDownloads.FIRST_BYTE_TIMEOUT_MS,
                ModelDownloads.FIRST_BYTE_TIMEOUT_MS <= NetModelFetcher.READ_TIMEOUT_MS / 2L);
        assertTrue("卡住这件事必须比预算更早说出来："
                        + ModelDownloads.STALL_NOTICE_MS,
                ModelDownloads.STALL_NOTICE_MS < ModelDownloads.FIRST_BYTE_TIMEOUT_MS);
    }

    /**
     * 「多久没有新数据」这件事必须能从快照上看出来——界面上的那句话只认这个字段。
     *
     * <p>进度百分比在「慢而活着」与「连上了但一个字节都不来」两种情况下是同一个数，
     * 所以 {@link ModelDownloads.Snapshot#stalled} 是唯一能把它们分开的东西：
     * 它到了阈值就必须为真，没到就必须为假（否则界面上会一直挂着一句假警报）。
     */
    @Test
    public void aSnapshotSaysWhenNothingHasArrivedForAWhile() {
        ModelDownloads.Snapshot fresh = new ModelDownloads.Snapshot(NAME, FILE,
                ModelDownloads.STATE_DOWNLOADING, 1000L, 0L, 0, "m.test", "",
                java.util.Collections.<String>emptyList(), false, null,
                new File(folder.getRoot(), FILE), 1_000L, 0L);
        assertFalse("刚发出去的快照不该说自己卡住了", fresh.stalled);

        ModelDownloads.Snapshot stalled = new ModelDownloads.Snapshot(NAME, FILE,
                ModelDownloads.STATE_DOWNLOADING, 1000L, 0L, 0, "m.test", "",
                java.util.Collections.<String>emptyList(), false, null,
                new File(folder.getRoot(), FILE),
                ModelDownloads.STALL_NOTICE_MS + 1_000L, ModelDownloads.STALL_NOTICE_MS);
        assertTrue("到了阈值就必须说卡住", stalled.stalled);
        assertEquals("等待秒数要能给人看", ModelDownloads.STALL_NOTICE_MS / 1000L,
                stalled.sinceLastByteSecs());

        // 「卡住」只对「下载中」有意义：一个已就绪的快照不许带这个标记
        ModelDownloads.Snapshot ready = new ModelDownloads.Snapshot(NAME, FILE,
                ModelDownloads.STATE_READY, 1000L, 1000L, 100, "m.test", "",
                java.util.Collections.<String>emptyList(), true, Boolean.TRUE,
                new File(folder.getRoot(), FILE), 9_000L, 9_000L);
        assertFalse("不是下载中的快照不该说自己在卡", ready.stalled);
    }

    /**
     * 卡住的时候手动兜底链接就该出现，而不是等三个镜像各超时一遍之后。
     *
     * <p>理由：用户在第 10 秒就已经能判断「这条路今天不通」了，而界面上那句话说的
     * 也正是这件事。等到两分钟后再摆出同一批链接，中间那段时间他就是对着一个
     * 不动的数字干等。
     */
    @Test
    public void aStalledDownloadOffersTheManualFallback() {
        ModelDownloads downloads = store(new FakeModelFetcher(), "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        ModelRow quiet = ModelRow.of(entry, new ModelDownloads.Snapshot(NAME, FILE,
                ModelDownloads.STATE_DOWNLOADING, 1000L, 512L, 51, "m.test", "",
                java.util.Collections.<String>emptyList(), false, null,
                new File(folder.getRoot(), FILE), 2_000L, 0L));
        assertFalse("正常推进中不该把手动那条路摆出来", quiet.manualVisible);
        assertEquals(512L, quiet.received);
        assertEquals("已收到多少要给人看：", "512 B".length() > 0, quiet.receivedText.length() > 0);

        ModelRow stuck = ModelRow.of(entry, new ModelDownloads.Snapshot(NAME, FILE,
                ModelDownloads.STATE_DOWNLOADING, 1000L, 0L, 0, "m.test", "",
                java.util.Collections.<String>emptyList(), false, null,
                new File(folder.getRoot(), FILE), 12_000L, 12_000L));
        assertTrue("卡住了就该把手动兜底摆出来", stuck.manualVisible);
        assertTrue("这一行也要如实说自己卡住了", stuck.stalled);
    }

    /**
     * <b>「为什么失败」必须留得住，直到有东西把它解释掉。</b>
     *
     * <p>这一条是上面那条的邻居，也是同一个抱怨的另一半：失败与被取消这两档只活在
     * {@link ModelDownloads#status} 的「最近一次」里，而磁盘上什么都没有——按磁盘现算的
     * 答案永远是「未下载」。要是 {@code status} 不把它们留住，那句「原因：…」在发出去的
     * 第一帧就被换成「未下载」，而它恰好是唯一能解释「卡在 0%」的话。
     *
     * <p>反过来，磁盘上真有一份大小对得上的文件时，上一次的失败<b>必须让位</b>：
     * 用户手动把权重搬进来（那条临时办法）之后，界面得改口说「已下载」。
     */
    @Test
    public void aFailureStaysVisibleUntilTheDiskSaysOtherwise() throws IOException {
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .fail("https://dead.test/" + FILE, "连不上");
        ModelDownloads downloads = store(fetcher, "https://dead.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        assertEquals("还没跑过的时候是「未下载」",
                ModelDownloads.STATE_ABSENT, downloads.status(entry).state);

        ModelDownloads.Snapshot failed = downloads.download(entry);
        assertEquals(ModelDownloads.STATE_FAILED, failed.state);
        ModelDownloads.Snapshot after = downloads.status(entry);
        assertEquals("失败必须留在状态里，而不是立刻变回「未下载」",
                ModelDownloads.STATE_FAILED, after.state);
        assertTrue("原因也要留住：" + after.error, after.error.contains("连不上"));
        assertEquals("逐条原因也要留住", 1, after.attempts.size());

        // 手动把同一份文件搬进来：磁盘说了算
        write(downloads.fileFor(entry), good);
        ModelDownloads.Snapshot manual = downloads.status(entry);
        assertEquals("磁盘上有大小对得上的文件时，上次的失败必须让位",
                ModelDownloads.STATE_READY, manual.state);
        assertNull("没做过完整校验就不许说校验过了", manual.verified);
    }

    /** 取消也一样留得住（那是用户按的，界面必须承认这一次动作发生过）。 */
    @Test
    public void aCanceledDownloadStaysVisibleToo() {
        FakeModelFetcher fetcher = new FakeModelFetcher().serve("https://m.test/" + FILE, good);
        final ModelDownloads downloads = store(fetcher, "https://m.test/{file}");
        final ModelManifestEntry entry = downloads.manifest().require(NAME);

        downloads.download(entry, new ModelDownloads.Listener() {
            @Override
            public void onState(ModelDownloads.Snapshot snapshot) {
                if (ModelDownloads.STATE_DOWNLOADING.equals(snapshot.state)) {
                    downloads.cancel(NAME);
                }
            }
        });

        ModelDownloads.Snapshot after = downloads.status(entry);
        assertEquals(ModelDownloads.STATE_CANCELED, after.state);
        assertTrue("要说清半成品留在哪儿：" + after.error, after.error.contains(".part"));
    }

    // ---- 镜像回退 -----------------------------------------------------------

    @Test
    public void fallsBackThroughMirrorsInOrderAndRecordsWhichOneWorked() {
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .fail("https://blocked.test/" + FILE, "连接被重置")
                .serve("https://liar.test/" + FILE, FakeModelFetcher.bytes(512, 7))
                .serve("https://good.test/" + FILE, good);
        ModelDownloads downloads = store(fetcher,
                "https://blocked.test/{file}", "https://liar.test/{file}", "https://good.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        ModelDownloads.Snapshot result = downloads.download(entry);

        assertEquals(ModelDownloads.STATE_READY, result.state);
        assertEquals("要记下最后用的是哪个镜像", "good.test", result.mirror);
        assertEquals("按清单顺序试，一个都不许跳",
                java.util.Arrays.asList("https://blocked.test/" + FILE,
                        "https://liar.test/" + FILE, "https://good.test/" + FILE),
                fetcher.requests());
        // 成功也要把之前失败过的镜像留着：首选镜像正是中国大陆唯一可达的那条，
        // 一次静默回退意味着「最重要的那条路烂掉了，而所有日志都说一切正常」
        assertEquals(2, result.attempts.size());
        assertTrue(result.attempts.get(0).contains("blocked.test"));
        assertTrue(result.attempts.get(1).contains("sha256"));
    }

    @Test
    public void checksumFailureIsReportedAheadOfAPlainNetworkFailure() {
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .fail("https://blocked.test/" + FILE, "连不上")
                .serve("https://liar.test/" + FILE, FakeModelFetcher.bytes(512, 3));
        ModelDownloads downloads = store(fetcher,
                "https://blocked.test/{file}", "https://liar.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        ModelDownloads.Snapshot result = downloads.download(entry);

        assertEquals(ModelDownloads.STATE_FAILED, result.state);
        // 「有人给你发了另一份东西」比「某个镜像连不上」重要得多，
        // 被普通网络错误盖掉会让人往错的方向查
        assertTrue("报出来的应当是校验那条：" + result.error, result.error.contains("sha256"));
        assertEquals(2, result.attempts.size());
    }

    // ---- 磁盘上已有的东西 ----------------------------------------------------

    @Test
    public void anAlreadyVerifiedCopyIsNotDownloadedAgain() throws IOException {
        FakeModelFetcher fetcher = new FakeModelFetcher();
        ModelDownloads downloads = store(fetcher, "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);
        write(downloads.fileFor(entry), good);

        ModelDownloads.Snapshot result = downloads.download(entry);

        assertEquals(ModelDownloads.STATE_READY, result.state);
        assertEquals(Boolean.TRUE, result.verified);
        assertEquals("已经装好就不该再发一次请求", 0, fetcher.openCount());
    }

    @Test
    public void aTruncatedCopyIsReportedAndReplacedByADownload() throws IOException {
        FakeModelFetcher fetcher = new FakeModelFetcher().serve("https://m.test/" + FILE, good);
        ModelDownloads downloads = store(fetcher, "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);
        // 上一次掉电/复制中断留下的半截文件：名字对、内容短
        write(downloads.fileFor(entry), new byte[]{1, 2, 3, 4});

        ModelDownloads.Snapshot cheap = downloads.status(entry);
        assertEquals(ModelDownloads.STATE_CORRUPT, cheap.state);
        assertFalse("大小对不上的文件不算「有」", downloads.present(entry));
        // 大小对不上就已经**确定**它不是那份权重了（截断必然改大小），
        // 所以这里是 false 而不是「没查过」；只有大小对得上的时候才轮得到
        // 「没查过完整哈希」这个答案（null）。
        assertEquals(Boolean.FALSE, cheap.verified);

        ModelDownloads.Snapshot fixed = downloads.download(entry);
        assertEquals(ModelDownloads.STATE_READY, fixed.state);
        assertEquals(good.length, downloads.fileFor(entry).length());
    }

    /** 同样大小、内容不对（镜像替换最容易做出来的样子）：只有完整哈希能抓出来。 */
    @Test
    public void sameSizeWrongContentIsCaughtOnlyByTheFullHash() throws IOException {
        ModelDownloads downloads = store(new FakeModelFetcher(), "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);
        byte[] impostor = FakeModelFetcher.bytes(good.length, 8);
        write(downloads.fileFor(entry), impostor);

        // 便宜的那一趟看不出来（大小对得上）
        ModelDownloads.Snapshot cheap = downloads.status(entry);
        assertEquals(ModelDownloads.STATE_READY, cheap.state);
        assertTrue(downloads.present(entry));
        assertNull("大小对得上时，没查过就不许说查过了", cheap.verified);

        ModelDownloads.Snapshot verified = downloads.verify(entry);
        assertEquals(ModelDownloads.STATE_CORRUPT, verified.state);
        assertEquals(Boolean.FALSE, verified.verified);
        assertTrue(verified.error.contains("sha256"));
    }

    @Test
    public void cheapAuditNamesTheTruncatedFiles() throws IOException {
        ModelDownloads downloads = store(new FakeModelFetcher(), "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);
        assertTrue("磁盘上什么都没有时不该报问题", downloads.cheapAudit().isEmpty());

        write(downloads.fileFor(entry), new byte[]{9, 9});
        List<String> problems = downloads.cheapAudit();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains(NAME));
        assertTrue("要说清是谁要多大：" + problems.get(0), problems.get(0).contains("512"));
    }

    @Test
    public void removeDeletesTheFileAndAnyPart() throws IOException {
        ModelDownloads downloads = store(new FakeModelFetcher(), "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);
        write(downloads.fileFor(entry), good);
        write(downloads.partFor(entry), new byte[]{1});

        downloads.remove(entry);

        assertFalse(downloads.fileFor(entry).exists());
        assertFalse(downloads.partFor(entry).exists());
        assertEquals(ModelDownloads.STATE_ABSENT, downloads.status(entry).state);
    }

    @Test
    public void aFreshStartThrowsAwayThePreviousPart() throws IOException {
        // 不做断点续传：上一次留下的半成品先清掉，否则一个更旧的 .part 混进来
        // 只会让排查变糊涂（而且它占着最多 108MB）
        FakeModelFetcher fetcher = new FakeModelFetcher().serve("https://m.test/" + FILE, good);
        ModelDownloads downloads = store(fetcher, "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);
        write(downloads.partFor(entry), new byte[]{1, 2, 3});

        assertEquals(ModelDownloads.STATE_READY, downloads.download(entry).state);
        assertEquals(good.length, downloads.fileFor(entry).length());
        assertFalse(downloads.partFor(entry).exists());
    }

    @Test
    public void noMirrorsAtAllIsReportedRatherThanThrowing() {
        ModelDownloads downloads = new ModelDownloads(folder.getRoot(),
                ModelManifest.parse(FakeModelFetcher.manifestJsonFor(NAME, FILE, good)),
                new FakeModelFetcher());
        ModelDownloads.Snapshot result = downloads.download(downloads.manifest().require(NAME));

        assertEquals(ModelDownloads.STATE_FAILED, result.state);
        assertTrue(result.error.contains("镜像"));
    }

    @Test
    public void statusAllCoversEveryEntryInManifestOrder() {
        ModelDownloads downloads = store(new FakeModelFetcher(), "https://m.test/{file}");
        List<ModelDownloads.Snapshot> all = downloads.statusAll();
        assertEquals(1, all.size());
        assertEquals(NAME, all.get(0).name);
        assertEquals(ModelDownloads.STATE_ABSENT, all.get(0).state);
    }

    private static ModelDownloads.Listener collecting(final List<String> states) {
        return new ModelDownloads.Listener() {
            @Override
            public void onState(ModelDownloads.Snapshot snapshot) {
                states.add(snapshot.state);
            }
        };
    }

    private static void write(File file, byte[] body) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            fail("建不出目录：" + parent);
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(body);
        } finally {
            out.close();
        }
    }

    /** 存在性检查不许碰文件内容：这里钉住它只用 {@code stat}。 */
    @Test
    public void presentIsAPlainStat() throws IOException {
        ModelDownloads downloads = store(new FakeModelFetcher(), "https://m.test/{file}");
        ModelManifestEntry entry = downloads.manifest().require(NAME);
        assertFalse(downloads.present("nope"));
        assertFalse(downloads.present(entry));
        write(downloads.fileFor(entry), good);
        assertTrue(downloads.present(entry));
        assertTrue(downloads.present(NAME));
    }

    @Test
    public void unknownEntryIsRejected() {
        ModelDownloads downloads = store(new FakeModelFetcher(), "https://m.test/{file}");
        assertNull(downloads.manifest().get("ghost"));
        try {
            downloads.download(null);
            fail("null 条目必须抛");
        } catch (ModelException e) {
            assertNotNull(e.getMessage());
        }
    }

    /**
     * 条目<b>自带 urls</b> 时，用它的、按它的顺序，镜像一条都不碰。
     *
     * <p>这条只为一种条目存在：不在那三条镜像上的东西（目前是 {@code reference} 里的
     * {@code mel_spectrogram}——镜像那三条路托管的是 folia 那两个文件，对它必然是 404）。
     * 规矩与镜像那条路是同一条：<b>按顺序试、第一个哈希匹配的胜出</b>，
     * 所以第一个地址故意给「字节数对、内容不对」的一份，断言它会落到第二个。
     *
     * <p>「覆盖而不是追加」也是这里钉住的：把注定 404 的镜像排在前面，
     * 只会让「为什么这么久才报错」变成一个谜。
     */
    @Test
    public void anEntryWithItsOwnUrlsUsesThemInsteadOfTheMirrors() {
        String ownA = "https://own-a.test/" + FILE;
        String ownB = "https://own-b.test/" + FILE;
        FakeModelFetcher fetcher = new FakeModelFetcher()
                .serve(ownA, FakeModelFetcher.bytes(good.length, 77))
                .serve(ownB, good);
        String json = "{\"mirrors\":[\"https://mirror.test/{file}\"],\"models\":["
                + "{\"name\":\"" + NAME + "\",\"file\":\"" + FILE + "\","
                + "\"bytes\":" + good.length + ",\"sha256\":\"" + FakeModelFetcher.sha256(good)
                + "\",\"license\":\"MIT\",\"upstream\":\"https://example.com/up\","
                + "\"enables\":\"\",\"urls\":[\"" + ownA + "\",\"" + ownB + "\"]}]}";
        ModelDownloads downloads = new ModelDownloads(folder.getRoot(),
                ModelManifest.parse(json), fetcher);
        ModelManifestEntry entry = downloads.manifest().require(NAME);

        assertEquals("自带的地址就是候选的全部，按给出的顺序",
                java.util.Arrays.asList(ownA, ownB), downloads.urlsFor(entry));

        ModelDownloads.Snapshot result = downloads.download(entry);

        assertEquals(ModelDownloads.STATE_READY, result.state);
        assertEquals("第一个哈希匹配的胜出", "own-b.test", result.mirror);
        assertEquals(1, result.attempts.size());
        assertEquals("镜像那条路一个字节都不该被请求",
                java.util.Arrays.asList(ownA, ownB), fetcher.requests());
    }
}
