package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link FlowSorter} 的测试：进度、缓存、失败如实上报、取消。
 *
 * <h3>为什么每条测试都是同步的</h3>
 *
 * <p>{@code FlowSorter} 把 {@link Executor} 作为构造参数，这里传一个
 * <strong>直接执行</strong>的执行器（{@code command.run()}）——于是整趟任务在
 * {@link FlowSorter#start} 里跑完，断言可以紧接着写，不需要等、不需要
 * {@code Thread.sleep}，也就没有「本机慢一点就随机红」的那种测试。
 *
 * <p>线程模型本身（真的换一条线程去跑）由 {@code PlayerController} 决定，
 * 而它做的事就是「给一个单线程 executor」——那是这里唯一没有覆盖的部分，
 * 如实记在交付报告里。
 */
public class FlowSorterTest {

    /** 直接执行：把「另一条线程」这一层换成同步，让断言可以立刻写。 */
    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    /**
     * 一段合成 PCM。
     *
     * <p>刻意压得很短（几百毫秒）：本类测的是<strong>编排</strong>——进度、
     * 缓存、取消、失败怎么报——不是分析质量（那是 {@code FlowAnalyzerTest}
     * 与 {@code AnalysisTest} 的事）。三十秒乘上几百首会把这个测试类变成
     * 一个内存与时间上的怪兽，而它多验到的东西是零。
     */
    private static float[] synth(int rate, double secs, float amplitude) {
        int n = (int) ((double) rate * secs);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = (float) Math.sin((double) i * 0.05) * amplitude;
        }
        return v;
    }

    /**
     * 一个假的「读法」：按句柄返回合成 PCM，并记下每次读。
     *
     * <p>句柄以 {@code fail:} 开头时抛 {@link AudioException}，
     * 用来钉「一首歌读不出来不该让整趟任务失败」。
     */
    private static final class FakeReader implements FlowAnalyzer.Reader {

        final List<String> reads = new ArrayList<>();

        @Override
        public Automix.Mono read(String source, Map<String, String> headers,
                                 boolean tail, double secs) {
            reads.add(source + (tail ? "#tail" : "#head"));
            if (source.startsWith("fail:")) {
                throw AudioException.decode("打不开：" + source);
            }
            int rate = 44_100;
            double span = Math.min(secs <= 0.0 ? 0.2 : secs, 0.2);
            float amplitude = source.startsWith("loud:") ? 0.8f : 0.05f;
            return new Automix.Mono(synth(rate, span, amplitude), rate, null);
        }
    }

    /** 收集回调的监听器。 */
    private static final class Recorder implements FlowSorter.Listener {

        int progressCalls = 0;
        int lastDone = 0;
        int lastTotal = 0;
        FlowSorter.Result result;
        String failure;
        Runnable onEachProgress;

        @Override
        public void onProgress(int done, int total) {
            progressCalls++;
            lastDone = done;
            lastTotal = total;
            if (onEachProgress != null) {
                onEachProgress.run();
            }
        }

        @Override
        public void onFinished(FlowSorter.Result result) {
            this.result = result;
        }

        @Override
        public void onFailed(String message) {
            this.failure = message;
        }
    }

    private static FlowSorter.Request request(String key, String source) {
        return new FlowSorter.Request(key, source, null, key);
    }

    // ---- 正常路径 ---------------------------------------------------------

    /** 两首都读得出来：进度逐个上报，结果与请求逐个对齐，没有失败。 */
    @Test
    public void analyzesEveryRequestAndReportsProgress() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        Recorder recorder = new Recorder();

        sorter.start(Arrays.asList(
                request("a", "file:/a.mp3"), request("b", "file:/b.mp3")), recorder);

        assertNull("不该有整体失败：" + recorder.failure, recorder.failure);
        assertNotNull(recorder.result);
        assertFalse(recorder.result.cancelled);
        assertEquals(2, recorder.result.features.size());
        assertNotNull(recorder.result.features.get(0));
        assertNotNull(recorder.result.features.get(1));
        assertTrue(recorder.result.failures.isEmpty());
        assertEquals(2, recorder.progressCalls);
        assertEquals(2, recorder.lastDone);
        assertEquals(2, recorder.lastTotal);
        // 每首歌读两遍：开头 30 秒 + 结尾 15 秒
        assertEquals(4, reader.reads.size());
    }

    /**
     * 缓存：同一个 {@link FlowSorter.Request#key} 第二次进来不再解码。
     *
     * <p>这是「改完歌单再排一次」能不能用得住的关键——没有它，
     * 一百首的队列每次都要重头解码一遍，用户不会点第二次。
     */
    @Test
    public void theSecondRunOfTheSameTrackComesFromTheCache() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        List<FlowSorter.Request> requests = Collections.singletonList(request("a", "file:/a.mp3"));

        Recorder first = new Recorder();
        sorter.start(requests, first);
        assertEquals(2, reader.reads.size());
        assertEquals(1, sorter.cachedCount());

        Recorder second = new Recorder();
        sorter.start(requests, second);
        assertEquals("第二次不该再碰解码器", 2, reader.reads.size());
        assertNotNull(second.result);
        assertNotNull(second.result.features.get(0));
    }

    /** 换了文件（键变了）就必须重新解码：缓存键把大小/时间也算进去了。 */
    @Test
    public void aDifferentFileUnderTheSameNameIsNotServedFromTheCache() {
        assertFalse("大小不同必须是不同的键",
                FlowSorter.cacheKey("local:x", 100, 5).equals(FlowSorter.cacheKey("local:x", 101, 5)));
        assertFalse("修改时间不同必须是不同的键",
                FlowSorter.cacheKey("local:x", 100, 5).equals(FlowSorter.cacheKey("local:x", 100, 6)));
        assertEquals("同样的三样东西必须是同一个键",
                FlowSorter.cacheKey("local:x", 100, 5), FlowSorter.cacheKey("local:x", 100, 5));
    }

    // ---- 按需解析（没有句柄时现去问一次） ---------------------------------

    /**
     * 没有句柄时，装了 {@link FlowSorter.Resolver} 就会现去解析，然后照常分析。
     *
     * <p>这一条对应真机上那个报告——「一键排列只能排几首，剩下全说分析不了」：
     * 在线曲目的直链<strong>只有播放过才存在</strong>，所以一个刚打开的歌单里绝大多数
     * 歌都没有句柄。之前的行为是全部记成「没有可用的音源句柄」。
     */
    @Test
    public void aMissingHandleIsResolvedOnDemandAndThenAnalyzed() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        final List<Integer> asked = new ArrayList<>();
        sorter.setResolver((index, request) -> {
            asked.add(Integer.valueOf(index));
            return FlowSorter.Resolution.ok("loud:resolved" + index, null);
        });
        Recorder recorder = new Recorder();

        sorter.start(Arrays.asList(request("a", null), request("b", "")), recorder);

        assertEquals("两首都要问一次", Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)), asked);
        assertTrue("解析出来了就不该再有失败：" + recorder.result.failures,
                recorder.result.failures.isEmpty());
        assertNotNull(recorder.result.features.get(0));
        assertNotNull(recorder.result.features.get(1));
        assertTrue("解析出来的句柄必须真的被拿去读：" + reader.reads,
                reader.reads.contains("loud:resolved0#head"));
    }

    /** 解析不出来时，**原因要如实报出来**，而不是笼统一句「没有句柄」。 */
    @Test
    public void aFailedResolutionIsReportedWithItsOwnReason() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        sorter.setResolver((index, request) ->
                FlowSorter.Resolution.failed("插件不支持解析直链（netease）"));
        Recorder recorder = new Recorder();

        sorter.start(Collections.singletonList(request("a", null)), recorder);

        assertEquals(1, recorder.result.failures.size());
        assertEquals("插件不支持解析直链（netease）", recorder.result.failures.get(0).detail);
        assertNull(recorder.result.features.get(0));
        assertTrue("解析都没成，不该去读任何东西", reader.reads.isEmpty());
    }

    /** 解析器抛异常只废掉那一首，不让整趟失败。 */
    @Test
    public void aThrowingResolverFailsOnlyThatTrack() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        sorter.setResolver((index, request) -> {
            if (index == 0) {
                throw new IllegalStateException("解析器炸了");
            }
            return FlowSorter.Resolution.ok("file:/b.mp3", null);
        });
        Recorder recorder = new Recorder();

        sorter.start(Arrays.asList(request("a", null), request("b", null)), recorder);

        assertNull("整体不该失败：" + recorder.failure, recorder.failure);
        assertEquals(1, recorder.result.failures.size());
        assertTrue("要把异常消息带出来：" + recorder.result.failures.get(0).detail,
                recorder.result.failures.get(0).detail.contains("解析器炸了"));
        assertNotNull("第二首照常分析", recorder.result.features.get(1));
    }

    /**
     * <strong>缓存命中时绝不去联网解析。</strong>
     *
     * <p>第二次排同一个歌单时句柄可能又没了（直链过期），但特征还在缓存里——
     * 这时再解析一次就是白花流量。这条钉住「先看缓存、命中就完全不碰句柄」。
     */
    @Test
    public void aCachedTrackIsNeverResolvedAgain() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        sorter.start(Collections.singletonList(request("same", "file:/a.mp3")), new Recorder());
        assertEquals(1, sorter.cachedCount());

        final boolean[] asked = {false};
        sorter.setResolver((index, request) -> {
            asked[0] = true;
            return FlowSorter.Resolution.ok("file:/a.mp3", null);
        });
        Recorder second = new Recorder();
        sorter.start(Collections.singletonList(request("same", null)), second);

        assertFalse("缓存命中就不该再去解析直链（那是白花流量）", asked[0]);
        assertNotNull(second.result.features.get(0));
        assertTrue(second.result.failures.isEmpty());
    }

    /** 没装解析器时行为与从前完全一致：缺句柄 ⇒ 如实记为「没有可用的音源句柄」。 */
    @Test
    public void withoutAResolverAMissingHandleIsStillReportedAsSuch() {
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(new FakeReader()));
        Recorder recorder = new Recorder();

        sorter.start(Collections.singletonList(request("a", null)), recorder);

        assertEquals(1, recorder.result.failures.size());
        assertEquals("没有可用的音源句柄", recorder.result.failures.get(0).detail);
    }

    // ---- 失败如实上报 -----------------------------------------------------

    /**
     * 一首读不出来：它自己那一位是 {@code null} 并进失败名单，
     * <strong>其余照排</strong>。
     *
     * <p>这是常态而不是异常：流媒体的直链会过期，离线时整个队列都可能打不开。
     * 让整个排列失败，用户什么也得不到；如实标出「这几首没分析出来」，
     * 剩下的仍然能排——这是本模块对「不许假装排好了」的落点。
     */
    @Test
    public void oneUnreadableTrackFailsAloneAndTheRestStillAnalyze() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        Recorder recorder = new Recorder();

        sorter.start(Arrays.asList(
                request("good", "file:/good.mp3"),
                request("bad", "fail:/bad.mp3"),
                request("also-good", "file:/also.mp3")), recorder);

        assertNull(recorder.failure);
        assertNotNull(recorder.result);
        assertEquals(3, recorder.result.features.size());
        assertNotNull(recorder.result.features.get(0));
        assertNull("读不出来的那一首必须是 null，不许编一份特征", recorder.result.features.get(1));
        assertNotNull(recorder.result.features.get(2));

        assertEquals(1, recorder.result.failures.size());
        assertEquals("bad", recorder.result.failures.get(0).label);
        assertTrue("失败原因要带出来给日志：" + recorder.result.failures.get(0).detail,
                recorder.result.failures.get(0).detail.contains("bad.mp3"));
        // 失败的那一首也走完了进度（用户看到的进度条不该卡在 2/3）
        assertEquals(3, recorder.lastDone);
    }

    /** 没有音源句柄的那一首不算「分析过」，也如实记为失败。 */
    @Test
    public void aTrackWithoutASourceHandleIsReportedAsFailed() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        Recorder recorder = new Recorder();

        sorter.start(Collections.singletonList(
                new FlowSorter.Request("k", null, null, "未解析的在线歌曲")), recorder);

        assertEquals(1, recorder.result.features.size());
        assertNull(recorder.result.features.get(0));
        assertEquals(1, recorder.result.failures.size());
        assertEquals("未解析的在线歌曲", recorder.result.failures.get(0).label);
        assertTrue("不该为一个没有句柄的曲目去碰解码器", reader.reads.isEmpty());
    }

    /** 失败不进缓存：下一次重排会再试一遍（直链过期是暂时的）。 */
    @Test
    public void failuresAreNotCached() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        List<FlowSorter.Request> requests =
                Collections.singletonList(request("bad", "fail:/x.mp3"));

        sorter.start(requests, new Recorder());
        assertEquals(0, sorter.cachedCount());
        sorter.start(requests, new Recorder());
        // 每次尝试在读「开头」那一遍就抛了，所以两趟一共两次读
        assertEquals("第二次应当再试一遍", 2, reader.reads.size());
    }

    // ---- 取消 -------------------------------------------------------------

    /**
     * 取消：第一首跑完就停，后面的位补成 {@code null} 并标记已取消。
     *
     * <p>补 null 而不是返回一个短列表：调用方拿到的列表永远与请求逐个对齐，
     * 「少了什么」不需要它自己去猜。
     */
    @Test
    public void cancelStopsBeforeTheNextTrackAndPadsTheRest() {
        final FakeReader reader = new FakeReader();
        final FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        final Recorder recorder = new Recorder();
        // 第一首报完进度就取消——模拟用户点「取消」
        recorder.onEachProgress = new Runnable() {
            @Override
            public void run() {
                sorter.cancel();
            }
        };

        sorter.start(Arrays.asList(
                request("a", "file:/a.mp3"),
                request("b", "file:/b.mp3"),
                request("c", "file:/c.mp3")), recorder);

        assertNotNull(recorder.result);
        assertTrue("应当被标记为已取消", recorder.result.cancelled);
        assertEquals("列表仍然与请求逐个对齐", 3, recorder.result.features.size());
        assertNotNull("第一首是在取消之前跑完的", recorder.result.features.get(0));
        assertNull(recorder.result.features.get(1));
        assertNull(recorder.result.features.get(2));
        assertEquals("第二首起就不该再碰解码器", 2, reader.reads.size());
    }

    /**
     * 任务排进去之后、跑起来之前就被取消：一位都不填，但列表长度仍与请求对齐。
     *
     * <p>协作式取消的边界条件——只有在下<strong>一首之前</strong>检查标志，
     * 所以「还没开始」这一档也必须落进「已取消 + 全 null」，而不是跑完整个队列。
     */
    @Test
    public void cancelBeforeTheBatchStartedLeavesEverythingUnknown() {
        final FakeReader reader = new FakeReader();
        final FlowSorter[] holder = new FlowSorter[1];
        Executor cancelThenRun = new Executor() {
            @Override
            public void execute(Runnable command) {
                holder[0].cancel();
                command.run();
            }
        };
        holder[0] = new FlowSorter(cancelThenRun, new FlowAnalyzer(reader));
        Recorder recorder = new Recorder();

        holder[0].start(Arrays.asList(request("a", "file:/a.mp3"), request("b", "file:/b.mp3")),
                recorder);

        assertNotNull(recorder.result);
        assertTrue(recorder.result.cancelled);
        assertEquals(2, recorder.result.features.size());
        assertNull(recorder.result.features.get(0));
        assertNull(recorder.result.features.get(1));
        assertTrue("一首都不该读", reader.reads.isEmpty());
        assertEquals(0, recorder.progressCalls);
    }

    // ---- 不变量 -----------------------------------------------------------

    /** 空请求列表：不崩、立刻报完成。 */
    @Test
    public void anEmptyBatchFinishesImmediately() {
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(new FakeReader()));
        Recorder recorder = new Recorder();
        sorter.start(new ArrayList<FlowSorter.Request>(), recorder);
        assertNotNull(recorder.result);
        assertTrue(recorder.result.features.isEmpty());
        assertEquals(0, recorder.progressCalls);
    }

    /** 构造参数为 null 立刻报错，而不是等到跑起来才在后台线程上炸。 */
    @Test
    public void nullCollaboratorsAreRejectedAtConstruction() {
        try {
            new FlowSorter(null, new FlowAnalyzer(new FakeReader()));
            fail("executor 为 null 时应当拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("executor"));
        }
        try {
            new FlowSorter(DIRECT, null);
            fail("analyzer 为 null 时应当拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("analyzer"));
        }
    }

    /** 缓存是 LRU 且有上限：分析过的曲目数不会无限增长。 */
    @Test
    public void theFeatureCacheIsBounded() {
        FakeReader reader = new FakeReader();
        FlowSorter sorter = new FlowSorter(DIRECT, new FlowAnalyzer(reader));
        List<FlowSorter.Request> many = new ArrayList<>();
        for (int i = 0; i < FlowSorter.CACHE_MAX + 5; i++) {
            many.add(request("k" + i, "file:/t" + i + ".mp3"));
        }
        sorter.start(many, new Recorder());
        assertEquals("缓存应当有上限", FlowSorter.CACHE_MAX, sorter.cachedCount());
    }
}
