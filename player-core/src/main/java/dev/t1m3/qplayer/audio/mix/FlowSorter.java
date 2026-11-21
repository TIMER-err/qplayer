package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 在<strong>后台线程</strong>上把一串曲目量成特征，带缓存、进度与取消。
 *
 * <h2>为什么必须有这一层</h2>
 *
 * <p>一首歌要读两遍（开头 30 秒 + 结尾 15 秒）再各跑一次
 * {@link Analysis}：单首几十到几百毫秒，一整个队列就是<strong>分钟级</strong>。
 * 放在渲染线程上就是「点一下，界面卡住不动，用户以为死机」。
 * 所以：分析跑在调用方给的 {@link Executor} 上，进度通过 {@link Listener}
 * 报回调用方（调用方自己负责切回渲染线程——本类不碰任何 UI）。
 *
 * <h2>缓存</h2>
 *
 * <p>{@link Request#key} 是调用方给的稳定标识（本仓库用
 * {@code canonicalId + 文件大小 + 修改时间}）。同一首歌第二次进来直接命中缓存，
 * <strong>不再解码</strong>：改完歌单再排一次、拖一首进来再排一次，
 * 都只付新曲目的成本。
 *
 * <p>缓存只存<strong>成功</strong>的结果，不存失败。一次失败可能是网络抖了一下
 * （直链超时），把它缓存下来会让这首歌在这个进程的余生里永远是「没分析出来」。
 * 代价是重排一次会把失败的曲目再试一遍——那是可以接受的，而且更诚实。
 *
 * <p>缓存是<strong>进程内</strong>的：重启之后第一次排列要重新解码一遍。
 * 做成落盘的代价是一份要处理版本迁移与损坏的文件格式，收益只是「重启后第一次快一点」，
 * 现在不做。
 *
 * <h2>取消</h2>
 *
 * <p>取消是<strong>协作式</strong>的：取消标志在下<strong>一首之前</strong>被检查，
 * 所以正在解码的那一首会跑完（最多一两秒）才停。{@code Decoder} 的
 * {@code decodeInto} 没有中断点，硬打断只能靠丢弃线程——那条路会漏掉
 * {@code Decoder.close()}，留下一个没关的 {@code MediaExtractor}/{@code MediaCodec}。
 * 「点了取消还等一两秒」比「泄漏一个解码器」好。
 *
 * <h2>洁净室说明</h2>
 *
 * <p>见 {@link FlowFeatures} 的类注释。
 */
public final class FlowSorter {

    /** 缓存上限（首）。一首歌一条、七个 float，四百条也不过十几 KB。 */
    static final int CACHE_MAX = 400;

    /**
     * 要量的一首歌。
     *
     * <p>不可变：它会被交给另一条线程，可变对象在这里是数据竞争。
     */
    public static final class Request {

        /** 稳定缓存键。同一首歌必须给同一个键，否则缓存永远不命中。 */
        public final String key;

        /** 音源句柄；{@code null} / 空表示<strong>现在拿不到句柄</strong>（见类注释）。 */
        public final String source;

        /** 提供商要求的请求头；可为 {@code null}。 */
        public final Map<String, String> headers;

        /** 给人看的名字（曲名），只用于「哪几首没分析出来」的如实报告。 */
        public final String label;

        public Request(String key, String source, Map<String, String> headers, String label) {
            this.key = key;
            this.source = source;
            this.headers = headers;
            this.label = label;
        }
    }

    /** 一首失败的原因：{@code label} 给用户看，{@code detail} 给日志看。 */
    public static final class Failure {

        public final String label;

        /** 技术细节（异常消息）。可能是空串。 */
        public final String detail;

        public Failure(String label, String detail) {
            this.label = label;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** 一趟分析的结果。 */
    public static final class Result {

        /**
         * 与 {@link Request} 列表<strong>逐个对齐</strong>的特征；
         * 某一首失败时该位是 {@code null}。
         */
        public final List<FlowFeatures> features;

        /** 没能分析的曲目，按原顺序。空列表表示全部成功。 */
        public final List<Failure> failures;

        /** 是否被取消（取消时 {@link #features} 只填了已经跑完的那一段）。 */
        public final boolean cancelled;

        Result(List<FlowFeatures> features, List<Failure> failures, boolean cancelled) {
            this.features = features;
            this.failures = failures;
            this.cancelled = cancelled;
        }
    }

    /** 进度回调。<strong>在分析线程上调用</strong>，实现方自己负责切回 UI 线程。 */
    public interface Listener {

        /** 已完成 {@code done} / 共 {@code total} 首（缓存命中的也算完成）。 */
        void onProgress(int done, int total);

        /** 跑完了（也可能是被取消：看 {@link Result#cancelled}）。 */
        void onFinished(Result result);

        /**
         * 整趟任务没能跑起来（{@link FlowAnalyzer} 抛了不该抛的东西）。
         *
         * <p>「某一首歌失败」走的是 {@link Result#failures}，<strong>不是</strong>这里：
         * 一首歌读不出来是常态（流媒体直链过期），不该让整个排列失败。
         */
        void onFailed(String message);
    }

    /**
     * 「现在拿不到句柄」时现去解析一个的接缝（<strong>在分析线程上调用，可以阻塞</strong>）。
     *
     * <h3>为什么需要它</h3>
     *
     * <p>在线曲目的直链只有<strong>播放过</strong>（或预取过）才存在，所以一个刚打开的歌单里
     * 绝大多数歌都没有句柄。没有这条接缝时它们全部落进「没能分析」——用户在真机上看到的就是
     * 「只能排几首，剩下全说分析不了」（2026-09-20 实测）。
     *
     * <p>解析要走网络（每首一次直链请求），所以它<strong>由调用方注入</strong>：
     * 本类不认识插件、不认识 {@code MediaId}，只认识「给我一个能读的句柄，或者告诉我为什么不行」。
     * 不注入时行为与从前完全一致（缺句柄 ⇒ 如实记为失败）。
     */
    public interface Resolver {

        /**
         * 解析第 {@code index} 首（下标与 {@link #start} 收到的列表逐个对齐，
         * 调用方据此找回自己的曲目对象）。
         *
         * @return 解析结果；拿不到就把原因放进 {@link Resolution#reason}，永远不要返回 {@code null}
         */
        Resolution resolve(int index, Request request);
    }

    /** 一次解析的结果：要么给得出句柄，要么给出为什么给不出。 */
    public static final class Resolution {

        /** 可用句柄；{@code null} / 空表示解析失败。 */
        public final String source;

        /** 随句柄一起要带的请求头（可为 {@code null}）。 */
        public final Map<String, String> headers;

        /** 失败原因（给人看的一句）；成功时是空串。 */
        public final String reason;

        public Resolution(String source, Map<String, String> headers, String reason) {
            this.source = source;
            this.headers = headers;
            this.reason = reason == null ? "" : reason;
        }

        /** 解析成功。 */
        public static Resolution ok(String source, Map<String, String> headers) {
            return new Resolution(source, headers, "");
        }

        /** 解析失败，带上原因。 */
        public static Resolution failed(String reason) {
            return new Resolution(null, null, reason);
        }
    }

    private final Executor executor;
    private final FlowAnalyzer analyzer;

    /** 现去解析句柄的接缝；{@code null} = 不联网（行为与从前完全一致）。 */
    private volatile Resolver resolver;

    /** 装上「按需解析」的接缝（见 {@link Resolver}）。 */
    public void setResolver(Resolver value) {
        this.resolver = value;
    }

    /** 访问序 LRU。分析与遍历都在单条分析线程上，但仍按并发安全写。 */
    private final Map<String, FlowFeatures> cache =
            Collections.synchronizedMap(new LinkedHashMap<String, FlowFeatures>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FlowFeatures> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    /** 当前这一趟的代号。{@link #cancel()} 把它推进一位，正在跑的那一趟据此退出。 */
    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();

    public FlowSorter(Executor executor, FlowAnalyzer analyzer) {
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为 null");
        }
        if (analyzer == null) {
            throw new IllegalArgumentException("analyzer 不能为 null");
        }
        this.executor = executor;
        this.analyzer = analyzer;
    }

    /**
     * 开始一趟。
     *
     * <p>不阻塞：立刻返回，工作在 {@code executor} 上跑。
     * 同一时刻只该有一趟在跑——调用方（{@code PlayerController}）用一个布尔状态挡住重入。
     */
    public void start(final List<Request> requests, final Listener listener) {
        final List<Request> input = requests == null
                ? Collections.<Request>emptyList() : new ArrayList<>(requests);
        final long myGeneration = generation.incrementAndGet();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 名字不叫 run：那会与 Runnable.run() 撞名（Java 按参数个数重载，
                // 于是 run(input, gen, listener) 编译不过）。
                runBatch(input, myGeneration, listener);
            }
        });
    }

    /** 请求取消当前这一趟（协作式，见类注释）。没有在跑的一趟时是一个空操作。 */
    public void cancel() {
        generation.incrementAndGet();
    }

    /** 缓存里现在有多少首。只给测试与日志。 */
    public int cachedCount() {
        return cache.size();
    }

    private void runBatch(List<Request> requests, long myGeneration, Listener listener) {
        int total = requests.size();
        List<FlowFeatures> features = new ArrayList<>(total);
        List<Failure> failures = new ArrayList<>();
        int done = 0;
        boolean cancelled = false;
        try {
            for (int i = 0; i < total; i++) {
                if (generation.get() != myGeneration) {
                    // 取消：把剩下的位<strong>补成 null</strong> 让列表仍然逐个对齐，
                    // 而不是返回一个长度不等的列表让调用方自己去猜少了什么。
                    cancelled = true;
                    break;
                }
                Request request = requests.get(i);
                FlowFeatures measured = null;
                // 先看缓存：**命中就完全不需要句柄**，也就不该为它去联网解析一次直链。
                measured = cache.get(request.key);
                if (measured == null) {
                    String source = request.source;
                    Map<String, String> headers = request.headers;
                    String missing = null;
                    if (source == null || source.isEmpty()) {
                        // 现在没有句柄。在线曲目的直链只有**播放过**才存在，所以这里现去解析一次
                        // （见 Resolver）；没有装解析器时行为与从前一致：如实记为「没有句柄」。
                        Resolver current = resolver;
                        Resolution resolution = null;
                        if (current != null) {
                            try {
                                resolution = current.resolve(i, request);
                            } catch (Throwable error) {
                                resolution = Resolution.failed(message(error));
                            }
                        }
                        if (resolution == null || resolution.source == null
                                || resolution.source.isEmpty()) {
                            missing = resolution != null && !resolution.reason.isEmpty()
                                    ? resolution.reason : "没有可用的音源句柄";
                        } else {
                            source = resolution.source;
                            headers = resolution.headers;
                        }
                    }
                    if (missing != null) {
                        failures.add(new Failure(request.label, missing));
                    } else {
                        try {
                            measured = analyzer.analyze(source, headers);
                            if (measured != null) {
                                cache.put(request.key, measured);
                            }
                        } catch (Throwable error) {
                            measured = null;
                            failures.add(new Failure(request.label, message(error)));
                        }
                    }
                }
                features.add(measured);
                done++;
                listener.onProgress(done, total);
            }
            while (features.size() < total) {
                features.add(null);
            }
            listener.onFinished(new Result(features, failures, cancelled));
        } catch (Throwable fatal) {
            // 走到这里说明 onProgress 本身抛了（分析失败都被上面接住了）。
            // 如实上报，而不是返回一个看起来正常的结果。
            listener.onFailed(message(fatal));
        }
    }

    /** 异常消息，永不返回 {@code null}（{@code I18n} 与日志都不喜欢 null）。 */
    private static String message(Throwable error) {
        String m = error == null ? null : error.getMessage();
        return m == null || m.isEmpty() ? String.valueOf(error) : m;
    }

    /**
     * 缓存键。
     *
     * <p>把<strong>能被换掉同一首歌的东西</strong>都算进去：稳定标识 + 文件大小 +
     * 修改时间。少了大小与时间，换掉磁盘上同名的那首歌之后还会命中旧特征，
     * 而界面上不会有任何异常——排出来的顺序只是「有点怪」。
     *
     * <p>网络源没有大小/时间（传 0），键就退化成纯标识：同一首在线歌曲的音频
     * 不会变，这是可以接受的。
     */
    public static String cacheKey(String identity, long sizeBytes, long modifiedMs) {
        return (identity == null ? "" : identity) + '\u0000' + sizeBytes + '\u0000' + modifiedMs;
    }
}
