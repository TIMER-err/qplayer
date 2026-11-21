package dev.t1m3.qplayer.audio.model;

import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 把权重弄到用户磁盘上的那条路：按清单的镜像顺序取、边下边算 sha256、
 * 验过之后才 {@code .part} 改名，并把「用了哪个镜像」「为什么失败」如实记下来。
 *
 * <h2>这个文件存在的全部意义是哈希那一关</h2>
 *
 * <p><b>「下载成功」与「能用」是两句不同的话。</b> 一个被截断或被镜像替换过的 ONNX
 * 不会大声报错——它会加载成功，然后自信地给出错误答案：对拍点来说就是每次过渡都落在
 * 拍点之外，日志里一个字都没有。所以：<b>哈希不过，绝不采用</b>——连改名都不做，
 * 真名下永远不会出现一个「看起来装好了」的坏文件。
 *
 * <p>用户的网盘下载走的是同一条路：放进模型目录之后由 {@link #verify} 按哈希确认，
 * 与 App 自己下的那份没有第二个、更宽松的信任级别（人搬运过来的文件并不因此更可信，
 * 而「以为自己下对了」恰恰是截断最常发生的方式）。
 *
 * <h2>presence 与 verified 是两件事</h2>
 *
 * <ul>
 *   <li>{@link #present} 只做一次 {@code stat}（顺带比大小）。它回答的是
 *       「磁盘上有没有权重」——这是<b>唯一</b>该被拿去做功能开关的问题，也是
 *       {@link ModelAvailability} 唯一会问的问题。</li>
 *   <li>{@link #verify} 读完整份文件算 sha256。它回答的是「手上这份对不对」，
 *       用在界面上的「校验」按钮、以及用之前。</li>
 * </ul>
 *
 * <p>把这两件事合成一件会得到一个很糟的结果：要么每次判断「有没有」都读 108MB，
 * 要么把「有文件」当成「文件是对的」去承诺一个功能。
 *
 * <h2>与 {@link ModelStore} 的分工（本轮收敛之后）</h2>
 *
 * <p><b>下载只有这一份实现。</b> 镜像按序试、第一个哈希匹配的胜出、写
 * {@code <file>.part} 再改名、校验不过绝不采用、把「用了哪个镜像」记下来——五条规矩
 * 全在这里，别的类不再各写一份。{@link ModelStore} 只留下推理路线要用的纯函数
 * （{@code hashFile}/{@code verify} 与 {@code .part} 残骸清理），并把「下回来」转发到这里，
 * 好让拍点这条路与分轨那条路走同一个入口。
 *
 * <p>清单也只有一份：{@link ModelManifest} 读 {@link ModelManifest#RESOURCE}
 * （folia 形状，{@code mirrors}/{@code manual}/{@code sha256}）。本轮之前这里还有第二份
 * （Sonalis 形状的 {@code models.json}）与第二套 {@code ModelSpec}，两条路线各读一份——
 * 两份描述迟早会不一致，而它们不一致时的表现方式是一个「自信但错误的拍点」。
 *
 * <p>本类自己的定位没变：它就是<b>用户按需下载</b>那条路——目录走 {@link AppDirs}
 * 并且用户能改、取字节走 {@link ModelFetcher} 接缝、失败要用返回值讲给设置页听。
 *
 * <h2>不做的事（照实记）</h2>
 *
 * <ul>
 *   <li><b>不做断点续传。</b> 中断留下的 {@code .part} 只是证据与占地方的东西，
 *       下一次下载会先把它删掉。真的要续传要考虑服务端 Range 语义与「续上的字节
 *       是否与已下部分同源」，那是另一个量级的事。</li>
 *   <li><b>不做 fsync。</b> 改名是原子的（同卷 rename），但掉电后仍可能出现
 *       「名字对、内容短」的文件——这正是启动时那次大小检查与用前的哈希校验要抓的。</li>
 *   <li><b>不扫描别处的文件。</b> 手动兜底那条路是「把文件放进模型目录」，
 *       不替用户在整块盘上找文件（慢、侵犯隐私，而且并不会多找到什么）。</li>
 * </ul>
 */
public final class ModelDownloads {

    /** 磁盘上没有（或大小对不上，等于没有）。 */
    public static final String STATE_ABSENT = "absent";

    /** 正在算 sha256（界面上是「校验中…」）。 */
    public static final String STATE_VERIFYING = "verifying";

    /** 正在下载。 */
    public static final String STATE_DOWNLOADING = "downloading";

    /** 文件在，且这次通过（或本来就通过）完整校验。 */
    public static final String STATE_READY = "ready";

    /** 文件在，但哈希/大小对不上——**不会被采用**。 */
    public static final String STATE_CORRUPT = "corrupt";

    /** 这次没能弄到一份好文件（每个镜像都失败了）。 */
    public static final String STATE_FAILED = "failed";

    /** 用户按了取消。 */
    public static final String STATE_CANCELED = "canceled";

    /**
     * 进度回调的最小间隔（毫秒）。
     *
     * <p>108MB 是几千个块，逐块回调会让界面每秒重画上百次，而人眼只能分辨其中一小部分。
     * folia 取的是同一个数。
     */
    public static final long PROGRESS_INTERVAL_MS = 200L;

    /**
     * <b>首字节预算</b>（毫秒）：连上了不等于在动。
     *
     * <p>{@link NetModelFetcher#READ_TIMEOUT_MS} 管的是<b>每一次读</b>，而它对
     * 「一个字节都不来」与「慢而活着」一视同仁——两种都要等满 60 秒才轮到下一个镜像，
     * 三条镜像轮流卡就是三分钟一动不动的 0%。所以「第一个字节」单独给一个预算：
     * 拿到响应体之后 {@code FIRST_BYTE_TIMEOUT_MS} 内一个字节都没到，就关掉这条连接、
     * 把原因记下来、换下一个镜像。
     *
     * <p><b>为什么是 30 秒而不是 15 秒</b>：这个数不能拍脑袋——它必须大于「能用的那条
     * 镜像最慢要多久才给第一个字节」，否则会把唯一能用的路掐掉。本机实测
     * {@code hf-mirror.com}（清单里的首选镜像，也是中国大陆唯一可达的那条）对
     * 83MB 那个权重的首字节是 <b>6.5～10.8 秒</b>（{@code curl -r 0-0} 与一段
     * {@code HttpURLConnection} 探针各测数轮：连上 TCP 0.7~1.9 秒，响应头 6.5 秒上下，
     * 首字节最慢 10.8 秒；拿到字节之后约 290 KB/s，整份 83MB 要四五分钟）。
     * 30 秒是最慢那次的三倍余量：慢启动的镜像不会被误杀，而真正不回话的镜像
     * 仍然是「等 30 秒换下一个」，不是 60 秒。
     */
    public static final long FIRST_BYTE_TIMEOUT_MS = 30_000L;

    /**
     * 「多久没有新数据」算卡住（毫秒）——到了这个数就必须在界面上说出来。
     *
     * <p>取 10 秒：比首字节预算短得多，所以「这个镜像没响应，再等 20 秒就换下一个」
     * 这句话会<b>先</b>出现。用户不必先盯着一个不动的 0% 猜半分钟才知道发生了什么。
     */
    public static final long STALL_NOTICE_MS = 10_000L;

    /** 看门狗每次发快照的间隔（毫秒）。 */
    private static final long WATCHDOG_INTERVAL_MS = 1_000L;

    /**
     * 首字节预算的<b>当前</b>取值，默认就是 {@link #FIRST_BYTE_TIMEOUT_MS}。
     *
     * <p>做成字段只为一件事：这条路的测试要真的走完「看门狗超时 → 关连接 →
     * 换下一个镜像」的全过程，而用真值 30 秒会让单测慢半分钟。
     * 生产代码从不改它（没有 setter，也没有任何设置项读它）。
     */
    volatile long firstByteBudgetMs = FIRST_BYTE_TIMEOUT_MS;

    /** 读写的块大小。 */
    private static final int CHUNK = 1 << 16;

    /** 一次下载/校验过程中，一个模型此刻的样子。不可变，可安全交给界面线程。 */
    public static final class Snapshot {

        public final String name;
        public final String file;
        /** 见本类的 {@code STATE_*} 常量。 */
        public final String state;
        /** 清单里的确切体积。 */
        public final long bytes;
        /** 这次已经收到的字节数（下载中才有意义）。 */
        public final long received;
        /** 0..100，给进度条用。 */
        public final int percent;
        /** 成功的那个镜像（或最近一次尝试的主机）。 */
        public final String mirror;
        /** 失败原因，给人看；没有失败就是空串。 */
        public final String error;
        /** 每个镜像各自的失败原因，按尝试顺序（成功的那个之前的都在这里）。 */
        public final List<String> attempts;
        /** 磁盘上有一个大小对得上的文件。 */
        public final boolean present;
        /** 是否通过完整 sha256 校验；{@code null} = 这次没查（只比过大小）。 */
        public final Boolean verified;
        /** 本地路径。 */
        public final File path;
        /**
         * 这一次传输已经花了多久（毫秒）。只有下载中的快照有意义，其余为 0。
         *
         * <p>为什么要把它放进快照而不是让界面自己记开始时间：界面上那一行可能在下载
         * <b>开始之后</b>才被创建（对话框是后打开的），它自己记的时间会从 0 开始，
         * 于是「已用 3 秒」与真相无关。
         */
        public final long elapsedMs;
        /**
         * 距离上一个字节多久了（毫秒）。0 表示刚开始、或者刚刚还在收字节。
         *
         * <p>这是「它在动还是死了」唯一的判据：进度百分比在「慢而活着」与
         * 「连上了但一个字节都不来」两种情况下都是同一个数，只有「多久没有新字节」
         * 能把它们分开。
         */
        public final long sinceLastByteMs;
        /** 已经 {@link #STALL_NOTICE_MS} 没有新数据了——界面必须把这件事说出来。 */
        public final boolean stalled;

        /** 全部字段的构造（{@code elapsedMs}/{@code sinceLastByteMs} 由下载那条路填）。 */
        Snapshot(String name, String file, String state, long bytes, long received, int percent,
                 String mirror, String error, List<String> attempts, boolean present,
                 Boolean verified, File path, long elapsedMs, long sinceLastByteMs) {
            this.name = name;
            this.file = file;
            this.state = state;
            this.bytes = bytes;
            this.received = received;
            this.percent = percent;
            this.mirror = mirror;
            this.error = error;
            this.attempts = Collections.unmodifiableList(attempts);
            this.present = present;
            this.verified = verified;
            this.path = path;
            this.elapsedMs = elapsedMs;
            this.sinceLastByteMs = sinceLastByteMs;
            // 派生而不是另传一个参数：只要「多久没有新字节」到了阈值，无论哪个调用点
            // 造的快照都必须说自己在卡住——漏传一次就等于界面又写了一个假的 0%。
            this.stalled = STATE_DOWNLOADING.equals(state) && sinceLastByteMs >= STALL_NOTICE_MS;
        }

        /** 与下载时长无关的那些状态（未下载/已就绪/失败/取消…）用这个。 */
        Snapshot(String name, String file, String state, long bytes, long received, int percent,
                 String mirror, String error, List<String> attempts, boolean present,
                 Boolean verified, File path) {
            this(name, file, state, bytes, received, percent, mirror, error, attempts, present,
                    verified, path, 0L, 0L);
        }

        /** 是否已经就绪（哈希对过）。 */
        public boolean isReady() {
            return STATE_READY.equals(state);
        }

        /** 已收到的字节数给人看（下载中的那一行用）。 */
        public String receivedText() {
            return bytesText(received);
        }

        /** 已经花了多少秒（整数，给人看）。 */
        public long elapsedSecs() {
            return elapsedMs / 1000L;
        }

        /** 距离上一个字节多少秒（整数，给人看）。 */
        public long sinceLastByteSecs() {
            return sinceLastByteMs / 1000L;
        }

        @Override
        public String toString() {
            return "Snapshot { " + name + ": " + state + (percent > 0 && percent < 100
                    ? " " + percent + "%" : "") + (mirror.isEmpty() ? "" : " @" + mirror)
                    + (elapsedMs > 0 ? " " + elapsedSecs() + "s" : "")
                    + (stalled ? " STALLED(" + sinceLastByteSecs() + "s)" : "")
                    + (error.isEmpty() ? "" : " —— " + error) + " }";
        }
    }

    /** 字节数给人看（1024 进制、一位小数），与 {@code ModelManifestEntry.sizeText()} 同一套说法。 */
    public static String bytesText(long bytes) {
        double mb = bytes / 1048576.0;
        if (mb >= 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
    }

    /** 状态变化的监听者（界面）。所有回调都在**调用 download/verify 的那个线程**上。 */
    public interface Listener {
        void onState(Snapshot snapshot);
    }

    private static volatile ModelDownloads shared;
    private static volatile boolean sharedInstalled;

    private final Supplier<File> dirSource;
    private final ModelManifest manifest;
    private final ModelFetcher fetcher;

    /** name -> 取消标记，一次一个（同一模型不允许两个下载同时在跑）。 */
    private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<String, AtomicBoolean>();
    /** 正在下载/校验的名字。 */
    private final Set<String> running = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    /** 最近一次发布的状态，供界面轮询（不必订阅）。 */
    private final Map<String, Snapshot> latest = new ConcurrentHashMap<String, Snapshot>();

    /**
     * @param dir     模型目录（每次调用时求值——用户在设置里改了位置之后，下一次下载
     *                与下一次判断都该看新目录）
     * @param manifest 清单
     * @param fetcher  取字节的实现
     */
    public ModelDownloads(Supplier<File> dir, ModelManifest manifest, ModelFetcher fetcher) {
        if (dir == null || manifest == null || fetcher == null) {
            throw new IllegalArgumentException("模型目录 / 清单 / 取字节实现都不能为空");
        }
        this.dirSource = dir;
        this.manifest = manifest;
        this.fetcher = fetcher;
    }

    /** 固定目录的便捷构造（测试与只服务一个目录的宿主）。 */
    public ModelDownloads(File dir, ModelManifest manifest, ModelFetcher fetcher) {
        this(constant(dir), manifest, fetcher);
    }

    private static Supplier<File> constant(final File dir) {
        if (dir == null) {
            throw new IllegalArgumentException("模型目录不能为空");
        }
        return new Supplier<File>() {
            @Override
            public File get() {
                return dir;
            }
        };
    }

    /**
     * App 全局那一份：目录来自 {@link AppDirs#modelsDir()}（每次求值），
     * 清单是内嵌的那份，取字节走 {@link ModelFetchers#current()}。
     *
     * <p>目录<b>不</b>在构造时固化：用户在设置里把模型文件夹指到别的盘之后，
     * 下一次下载就该落到新地方——固化下来会得到一个「设置改了、文件还是下到老地方」
     * 的幽灵 bug。
     */
    public static ModelDownloads shared() {
        ModelDownloads d = shared;
        if (d == null) {
            synchronized (ModelDownloads.class) {
                d = shared;
                if (d == null) {
                    d = new ModelDownloads(new Supplier<File>() {
                        @Override
                        public File get() {
                            return AppDirs.modelsDir().toFile();
                        }
                    }, ModelManifest.embedded(), new DelegatingFetcher());
                    shared = d;
                }
            }
        }
        return d;
    }

    /**
     * 「每次去问当前接缝」的那个取字节实现。
     *
     * <p>为什么不把宿主装的那一份<b>固化</b>在这里：全局那一份可能在任何东西之前被建出来
     * （界面第一帧问一次状态就够了），而宿主是在启动的某个点上才 {@code install(...)}
     * 自己的实现。固化下来会得到一个极难查的 bug——「接缝明明装了，下载用的还是默认实现」。
     * 目录用的是同一套办法（每次求值），理由也一样：设置里改了位置，下一次就该用新位置。
     */
    private static final class DelegatingFetcher implements ModelFetcher {
        @Override
        public Response open(String url) throws java.io.IOException {
            return ModelFetchers.current().open(url);
        }
    }

    /** 换掉全局那一份（宿主/测试；换了之后 {@link #shared()} 返回它）。 */
    public static void install(ModelDownloads downloads) {
        synchronized (ModelDownloads.class) {
            shared = downloads;
            sharedInstalled = downloads != null;
        }
    }

    /** 撤销替换，回到「按需重建」。测试跑完要调。 */
    public static void uninstall() {
        synchronized (ModelDownloads.class) {
            shared = null;
            sharedInstalled = false;
        }
    }

    /** 是否被宿主显式换过。 */
    public static boolean isInstalled() {
        return sharedInstalled;
    }

    public ModelManifest manifest() {
        return manifest;
    }

    public ModelFetcher fetcher() {
        return fetcher;
    }

    /** 当前模型目录。 */
    public File dir() {
        return dirSource.get();
    }

    /** 一个条目应该落在哪里。 */
    public File fileFor(ModelManifestEntry entry) {
        return new File(dir(), entry.file);
    }

    /** 正在下载的半成品路径。 */
    public File partFor(ModelManifestEntry entry) {
        return new File(dir(), entry.file + ".part");
    }

    /**
     * 一个条目要按顺序试的地址。
     *
     * <p>两种来源，同一条规矩（<b>按顺序试、第一个哈希匹配的胜出</b>）：
     *
     * <ul>
     *   <li>清单顶层的 {@code mirrors} 模板（{@code {file}} 展开）——folia 那两个权重走这条；</li>
     *   <li>条目自带的 {@code urls}——只给「不在那几条镜像上」的条目用
     *       （目前是 {@code reference} 里的 {@code mel_spectrogram}：镜像那三条路
     *       托管的是 folia 那两个文件，对它必然是 404）。</li>
     * </ul>
     *
     * <p>自带地址是<b>覆盖</b>而不是追加：把一串注定 404 的地址排在前面，
     * 只会让「为什么这么久才报错」变成一个谜，而它一个字节都验不过。
     */
    public List<String> urlsFor(ModelManifestEntry entry) {
        if (entry == null) {
            return Collections.emptyList();
        }
        if (!entry.urls.isEmpty()) {
            return entry.urls;
        }
        List<String> out = new ArrayList<String>();
        for (String template : manifest.mirrors()) {
            out.add(ModelManifest.expand(template, entry));
        }
        return out;
    }

    // ---- 存在性 -------------------------------------------------------------

    /**
     * 磁盘上有没有权重。<b>这是唯一该被拿去做功能开关的问题。</b>
     *
     * <p>只做一次 {@code stat}：比 folia 那个纯存在性检查多比一个大小，
     * 而这一次 stat 本来就要做——截断必然改大小，所以「被截断的旧文件」在这里
     * 就已经被排除了。它<b>不</b>证明内容是对的（那要读完整个文件），
     * 也正因如此不能把它当成「能用」：见 {@link #verify}。
     */
    public boolean present(ModelManifestEntry entry) {
        File f = fileFor(entry);
        return f.isFile() && f.length() == entry.bytes;
    }

    /** 按名字问存在性；名字不在清单里返回 {@code false}。 */
    public boolean present(String name) {
        ModelManifestEntry e = manifest.get(name);
        return e != null && present(e);
    }

    /**
     * 最近一次发布的状态；没跑过（或者磁盘上已经有了一份好文件）就是按磁盘现算一个。
     *
     * <p><b>失败与被取消这两档必须留住。</b> 它们只存在于 {@link #latest} 里：
     * 磁盘上什么都没有，所以「按磁盘现算」的答案是「未下载」——于是
     * 「为什么失败 / 半成品留在哪个 .part」那句唯一能解释 0% 的话，在发布出去后的
     * <b>第一帧</b>就被换掉了，用户永远看不到它（这一整套里最该说话的地方恰好全部哑掉）。
     *
     * <p>但<b>磁盘永远优先</b>：只要有一份大小对得上的文件，就说「已下载」（那是用户
     * 手动把权重搬进来的那条路，不能让上一次的失败把它盖住）。
     */
    public Snapshot status(ModelManifestEntry entry) {
        Snapshot last = latest.get(entry.name);
        if (last != null && isSticky(last)) {
            boolean inFlight = STATE_DOWNLOADING.equals(last.state)
                    || STATE_VERIFYING.equals(last.state);
            if (inFlight || cheapSnapshot(entry).state != STATE_READY) {
                return last;
            }
        }
        return cheapSnapshot(entry);
    }

    /** 哪些「最近一次」该盖住磁盘现状：进行中的当然要，结束了的失败/取消也要（见 {@link #status}）。 */
    private static boolean isSticky(Snapshot last) {
        return STATE_DOWNLOADING.equals(last.state)
                || STATE_VERIFYING.equals(last.state)
                || STATE_FAILED.equals(last.state)
                || STATE_CANCELED.equals(last.state);
    }

    /** 全部条目的现状（顺序同清单）。 */
    public List<Snapshot> statusAll() {
        List<Snapshot> out = new ArrayList<Snapshot>();
        for (ModelManifestEntry e : manifest.models()) {
            out.add(status(e));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 启动时那一遍<b>廉价</b>检查：对每个已经在磁盘上的文件比一次大小
     * （一次 {@code stat}，不读内容），返回有问题的那些说明。
     *
     * <p>为什么是比大小而不是算 sha256：被截断的旧文件（掉电、复制中断、网盘下了一半）
     * <b>必然改大小</b>，所以一次 stat 就能把它们全抓出来；而完整 sha256 要读完 108MB，
     * 放在启动路径上就是让用户等一件没人等的事。<b>它不是「校验通过」的证明</b>——
     * 那要 {@link #verify}，用在界面上的按钮与真正要用这份权重之前。
     */
    public List<String> cheapAudit() {
        List<String> problems = new ArrayList<String>();
        for (ModelManifestEntry e : manifest.models()) {
            File f = fileFor(e);
            if (f.isFile() && f.length() != e.bytes) {
                problems.add(e.name + "（" + f + "）：" + f.length() + " 字节，清单要的是 "
                        + e.bytes + " 字节——多半是被截断或没下完的文件，不会被采用");
            }
        }
        return problems;
    }

    /**
     * 只比大小的现状。
     *
     * <p>{@code verified} 留成 {@code null}：**没查过就不许说查过了**。
     * 界面据此显示「已下载（未校验）」，而不是一个假装确认过的「已就绪」。
     */
    private Snapshot cheapSnapshot(ModelManifestEntry entry) {
        File f = fileFor(entry);
        if (!f.isFile()) {
            return new Snapshot(entry.name, entry.file, STATE_ABSENT, entry.bytes, 0L, 0,
                    "", "", Collections.<String>emptyList(), false, null, f);
        }
        if (f.length() != entry.bytes) {
            String why = "文件大小 " + f.length() + " 字节，清单要的是 " + entry.bytes
                    + " 字节——多半是被截断的旧文件，不会使用";
            return new Snapshot(entry.name, entry.file, STATE_CORRUPT, entry.bytes, f.length(), 0,
                    "", why, Collections.<String>emptyList(), false, Boolean.FALSE, f);
        }
        return new Snapshot(entry.name, entry.file, STATE_READY, entry.bytes, entry.bytes, 100,
                "", "", Collections.<String>emptyList(), true, null, f);
    }

    // ---- 校验 ---------------------------------------------------------------

    /**
     * 完整校验：读完整份文件算 sha256 与清单比对。
     *
     * <p>108MB 要读完，所以<b>不要放在启动路径上</b>（启动那一遍只比大小，见
     * {@link #status}）。它的调用点是界面上的「校验」、以及手动兜底之后确认
     * 「用户放进来的那一份到底对不对」。
     */
    public Snapshot verify(ModelManifestEntry entry) {
        return verify(entry, null);
    }

    /** 同 {@link #verify(ModelManifestEntry)}，带状态回调。 */
    public Snapshot verify(ModelManifestEntry entry, Listener listener) {
        File f = fileFor(entry);
        if (!f.isFile()) {
            return publish(new Snapshot(entry.name, entry.file, STATE_ABSENT, entry.bytes, 0L, 0,
                    "", "", Collections.<String>emptyList(), false, null, f), listener);
        }

        String token = entry.name + "#verify";
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancels.put(token, cancel);
        running.add(token);
        try {
            publish(new Snapshot(entry.name, entry.file, STATE_VERIFYING, entry.bytes,
                    f.length(), 0, "", "", Collections.<String>emptyList(), true, null, f), listener);

            String digest;
            try {
                digest = hashFile(f);
            } catch (ModelException e) {
                return publish(failed(entry, f, "读不了这份文件：" + e.getMessage()), listener);
            }

            if (f.length() != entry.bytes || !digest.equalsIgnoreCase(entry.sha256)) {
                String why = describeMismatch(f.length(), digest, entry);
                return publish(new Snapshot(entry.name, entry.file, STATE_CORRUPT, entry.bytes,
                        f.length(), 0, "", why, Collections.<String>emptyList(), false,
                        Boolean.FALSE, f), listener);
            }
            return publish(new Snapshot(entry.name, entry.file, STATE_READY, entry.bytes,
                    entry.bytes, 100, "", "", Collections.<String>emptyList(), true,
                    Boolean.TRUE, f), listener);
        } finally {
            running.remove(token);
            cancels.remove(token);
        }
    }

    /** 对每个「磁盘上有东西」的条目做一遍完整校验，返回通过的条数。 */
    public int verifyAll(Listener listener) {
        int ok = 0;
        for (ModelManifestEntry e : manifest.models()) {
            if (!fileFor(e).isFile()) {
                continue;
            }
            if (verify(e, listener).isReady()) {
                ok++;
            }
        }
        return ok;
    }

    // ---- 下载 ---------------------------------------------------------------

    /**
     * 下载并安装一个模型，返回它最后的状态。
     *
     * <p><b>阻塞调用</b>，108MB 在慢链路上可能要好几分钟：调用方必须放在后台线程上，
     * 不要阻塞界面线程或播放线程。
     *
     * <p>失败用返回值讲，而不是抛异常——每一个失败都是普通事件（主机被墙、
     * 镜像冷启动、用户没网），而调用方是设置页，它要<b>显示</b>这些原因。
     * 只有「清单里根本没这个名字」才抛（那是代码错了，不是环境错了）。
     */
    public Snapshot download(ModelManifestEntry entry) {
        return download(entry, null);
    }

    /** 同 {@link #download(ModelManifestEntry)}，带状态回调。 */
    public Snapshot download(ModelManifestEntry entry, Listener listener) {
        if (entry == null) {
            throw ModelException.unknown("null");
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        if (!running.add(entry.name)) {
            // 同一个模型已经在下了：如实拒绝，而不是开第二条写同一个 .part 的流
            return publish(cheapSnapshot(entry), listener);
        }
        cancels.put(entry.name, cancel);
        List<String> attempts = new ArrayList<String>();
        try {
            File dir = dir();
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return publish(failed(entry, partFor(entry), "模型目录建不出来：" + dir), listener);
            }
            File target = fileFor(entry);

            // 已经装好且**完整校验通过** → 直接用。多读一遍 108MB 比重新下一遍便宜得多。
            if (target.isFile()) {
                Snapshot existing = verify(entry, listener);
                if (existing.isReady()) {
                    latest.put(entry.name, existing);
                    return existing;
                }
                // 存在但不对：**先留着**。重下失败的常见原因（没网、镜像挂）恰好是
                // 用户最不想再丢东西的时候，而那个坏文件本来就在那儿、不占新地方；
                // 等新字节验过、要改名之前再删（下面那一步）。
            }

            File part = partFor(entry);
            if (part.exists()) {
                // 上一次中断留下的半成品。不做续传（见类注释），所以先清掉：
                // 留着只会占最多 108MB，而且一个更旧的 .part 混进来会让排查变糊涂。
                part.delete();
            }

            publish(new Snapshot(entry.name, entry.file, STATE_DOWNLOADING, entry.bytes, 0L, 0,
                    "", "", Collections.<String>emptyList(), false, null, target), listener);

            List<String> urls = urlsFor(entry);
            if (urls.isEmpty()) {
                Logger.warn("模型 {}：清单里没有镜像地址，下载无法开始", entry.name);
                return publish(failedWithAttempts(entry, target,
                        "清单里没有镜像地址", attempts), listener);
            }
            Logger.info("模型 {}：开始下载 {} → {}（{} 个镜像，按顺序试）",
                    entry.name, entry.sizeText(), target, Integer.valueOf(urls.size()));

            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                if (cancel.get()) {
                    attempts.add("已取消");
                    Logger.info("模型 {}：用户取消（第 {} 个镜像之前）", entry.name,
                            Integer.valueOf(i + 1));
                    return publish(canceled(entry, target, attempts), listener);
                }
                String host = ModelManifest.hostOf(url);
                long attemptStart = System.currentTimeMillis();
                Logger.info("模型 {}：试第 {}/{} 个镜像 {} —— {}", entry.name,
                        Integer.valueOf(i + 1), Integer.valueOf(urls.size()), host, url);
                String failure = downloadOne(entry, url, part, target, cancel, listener);
                if (failure == null) {
                    Snapshot done = new Snapshot(entry.name, entry.file, STATE_READY, entry.bytes,
                            entry.bytes, 100, host, "",
                            attempts, true, Boolean.TRUE, target);
                    return publish(done, listener);
                }
                if (STATE_CANCELED.equals(failure)) {
                    attempts.add(host + ": 已取消");
                    return publish(canceled(entry, target, attempts), listener);
                }
                attempts.add(failure);
                Logger.warn("模型 {}：镜像 {} 失败（这次尝试共 {} 秒）：{}", entry.name, host,
                        Long.valueOf((System.currentTimeMillis() - attemptStart) / 1000L), failure);
                // 每个镜像的失败都要留着（哪怕后面一个成功了）——folia 的构建脚本里
                // 记着理由：首选镜像正是中国大陆唯一可达的那条，静默回退意味着
                // 「最重要的那条路烂掉了，而所有构建都还在说一切正常」。
            }

            String summary = pickSummary(attempts);
            Logger.warn("模型 {}：{} 个镜像都失败了，最后一个原因：{}", entry.name,
                    Integer.valueOf(urls.size()), summary);
            for (String a : attempts) {
                Logger.warn("模型 {}：逐条原因 —— {}", entry.name, a);
            }
            return publish(failedWithAttempts(entry, target, summary, attempts), listener);
        } finally {
            cancels.remove(entry.name);
            running.remove(entry.name);
        }
    }

    /**
     * 从单个镜像下载、校验、落地。返回 {@code null} 表示成功，否则返回「这个镜像为什么不行」。
     *
     * <p>写 {@code .part} 再改名，是因为另一个选择是「半个文件挂在真名下」：
     * 那时 {@link #present} 会认它（大小若凑巧对上）或者界面会把它当成装好了，
     * 而下游拿到的是一张被截断的图。
     */
    private String downloadOne(ModelManifestEntry entry, String url, File part, File target,
                               AtomicBoolean cancel, Listener listener) {
        String host = ModelManifest.hostOf(url);
        ModelFetcher.Response response = null;
        InputStream in = null;
        FileOutputStream out = null;
        long received = 0L;
        boolean keepPart = false;
        long started = System.currentTimeMillis();
        final AtomicLong got = new AtomicLong(0L);
        final AtomicLong lastByteAt = new AtomicLong(started);
        AttemptWatchdog watchdog = new AttemptWatchdog(entry, host, target, got, lastByteAt, listener);
        try {
            // 先画一次「正在连 X」：连上之前那一行也得有话说，否则用户在
            // CONNECT_TIMEOUT（30 秒）里看到的同样是一个没有解释的 0%。
            publish(new Snapshot(entry.name, entry.file, STATE_DOWNLOADING, entry.bytes, 0L, 0,
                    host, "", Collections.<String>emptyList(), false, null, target,
                    0L, 0L), listener);
            watchdog.start();
            response = fetcher.open(url);
            watchdog.attach(response);
            in = response.body();

            MessageDigest digest = sha256();
            out = new FileOutputStream(part);
            byte[] buf = new byte[CHUNK];
            long announced = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) {
                    // 中断：**留着 .part、绝不改名**。留着的那一份不是可用文件，
                    // 也不会被任何人当可用文件（真名下什么都没有）。
                    keepPart = true;
                    return STATE_CANCELED;
                }
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
                received += n;
                got.set(received);
                long now = System.currentTimeMillis();
                lastByteAt.set(now);
                if (now - announced >= PROGRESS_INTERVAL_MS) {
                    announced = now;
                    publish(new Snapshot(entry.name, entry.file, STATE_DOWNLOADING, entry.bytes,
                            received, percentOf(entry, received), host, "",
                            Collections.<String>emptyList(), false, null, target,
                            now - started, 0L), listener);
                }
            }

            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            response.close();
            response = null;

            String actual = hex(digest.digest());
            if (received != entry.bytes || !actual.equalsIgnoreCase(entry.sha256)) {
                // 完整但对不上：这个主机在发另一份东西，和它挂了是同一种待遇——
                // 换下一个。而这份 108MB 的垃圾没有留着的理由。
                // 原因里必须带上主机名：不然「有人给你发了另一份东西」这句话
                // 就没说是谁发的（folia 的失败信息也是这个形状）。
                Logger.warn("模型 {}：{} 传完了但内容不对 —— 收到 {} 字节，用时 {} 秒，{}",
                        entry.name, host, Long.valueOf(received),
                        Long.valueOf((System.currentTimeMillis() - started) / 1000L),
                        describeMismatch(received, actual, entry));
                part.delete();
                return host + ": " + describeMismatch(received, actual, entry);
            }

            // 5) 验过之后才动真名。Windows 上 renameTo 不覆盖已存在的目标，
            //    所以先把同名旧文件删掉（它已经确定是坏的或过期的）。
            if (target.exists() && !target.delete()) {
                part.delete();
                return host + ": 同名旧文件删不掉，无法改名：" + target;
            }
            if (!part.renameTo(target)) {
                part.delete();
                return host + ": 改名失败（" + part + " → " + target + "）";
            }
            Logger.info("模型 {}：{} 传完了 —— {} 字节，用时 {} 秒，sha256 对上，已落地 {}",
                    entry.name, host, Long.valueOf(received),
                    Long.valueOf((System.currentTimeMillis() - started) / 1000L), target);
            return null;
        } catch (IOException e) {
            // 传输中断（连接断、超时、磁盘写失败）。**留着 .part**，
            // 真名下仍然什么都没有。
            keepPart = true;
            String why = watchdog.tripped()
                    ? "首字节 " + (firstByteBudgetMs / 1000L) + " 秒没有任何数据（"
                      + watchdog.idleSeconds() + " 秒没有响应）"
                    : message(e);
            Logger.warn("模型 {}：{} 失败 —— 收到 {} 字节，用时 {} 秒，原因：{}",
                    entry.name, host, Long.valueOf(got.get()),
                    Long.valueOf((System.currentTimeMillis() - started) / 1000L), why);
            return host + ": " + why;
        } catch (RuntimeException e) {
            keepPart = true;
            Logger.warn("模型 {}：{} 抛错 —— 收到 {} 字节，用时 {} 秒，{}: {}",
                    entry.name, host, Long.valueOf(got.get()),
                    Long.valueOf((System.currentTimeMillis() - started) / 1000L),
                    e.getClass().getSimpleName(), message(e));
            return host + ": " + e.getClass().getSimpleName() + ": " + message(e);
        } finally {
            // 看门狗必须先停：它还在发快照，而这一次尝试的结论马上要写进 latest——
            // 晚一步的快照会把界面按回「下载中」，那正是「卡在 0%」的另一种长相。
            watchdog.stopAndJoin();
            closeQuietly(out);
            closeQuietly(in);
            if (response != null) {
                response.close();
            }
            if (!keepPart && part.exists() && received == 0L) {
                // 一个字节都没下到（连不上、404、被墙）→ 留着一个 0 字节的 .part
                // 只会让下一次排查多一个没意义的文件。
                part.delete();
            }
        }
    }

    private static int percentOf(ModelManifestEntry entry, long received) {
        return entry.bytes > 0 ? (int) Math.min(99L, received * 100L / entry.bytes) : 0;
    }

    /**
     * 一次传输的看门狗：<b>把「连上了但在干等」说出来，并且不再无限等下去</b>。
     *
     * <p>它每秒发一次快照（已收到多少、已用多久、距上一个字节多久），所以界面上的
     * 那一行即使一个字节都没来也在动——用户看得出「它在等镜像」而不是「它死了」。
     *
     * <p>首字节预算（{@link #FIRST_BYTE_TIMEOUT_MS}）到了还是一个字节都没到，它就把
     * 这条响应关掉：阻塞中的 {@code read} 会抛 IOException，这次尝试以
     * 「首字节超时」结束并换下一个镜像——而不是再等满 {@link NetModelFetcher#READ_TIMEOUT_MS}。
     *
     * <p>收到第一个字节之后它<b>只发快照、再也不关连接</b>：慢而活着的传输不会被它
     * 掐掉（{@link NetModelFetcher} 的类注释里那条设计约束）。
     */
    private final class AttemptWatchdog extends Thread {

        private final ModelManifestEntry entry;
        private final String host;
        private final File target;
        private final AtomicLong received;
        private final AtomicLong lastByteAt;
        private final Listener listener;
        private final Object lock = new Object();

        private volatile ModelFetcher.Response response;
        /** 响应体拿到手的时刻；在那之前「首字节预算」不计时（连接本身有 CONNECT_TIMEOUT）。 */
        private volatile long transferStart;
        private boolean stopped;
        private volatile boolean tripped;

        AttemptWatchdog(ModelManifestEntry entry, String host, File target, AtomicLong received,
                        AtomicLong lastByteAt, Listener listener) {
            super("qplayer-model-watchdog");
            setDaemon(true);
            this.entry = entry;
            this.host = host;
            this.target = target;
            this.received = received;
            this.lastByteAt = lastByteAt;
            this.listener = listener;
        }

        void attach(ModelFetcher.Response response) {
            this.response = response;
            this.transferStart = System.currentTimeMillis();
        }

        @Override
        public void run() {
            long started = System.currentTimeMillis();
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (lock) {
                    if (stopped) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    long bytes = received.get();
                    long idle = now - lastByteAt.get();
                    publish(new Snapshot(entry.name, entry.file, STATE_DOWNLOADING, entry.bytes,
                            bytes, percentOf(entry, bytes), host, "",
                            Collections.<String>emptyList(), false, null, target,
                            now - started, idle), listener);
                    long from = transferStart;
                    if (bytes == 0L && from > 0L && now - from >= firstByteBudgetMs) {
                        tripped = true;
                        ModelFetcher.Response r = response;
                        if (r != null) {
                            // 关掉它，阻塞中的 read 会抛出来 —— 这是我们唯一能
                            // 打断一次「服务器收下了请求然后不说话」的办法。
                            r.close();
                        }
                        return;
                    }
                }
            }
        }

        boolean tripped() {
            return tripped;
        }

        long idleSeconds() {
            return (System.currentTimeMillis() - lastByteAt.get()) / 1000L;
        }

        /** 停掉并且**等它真的退出**：见 downloadOne 的 finally。 */
        void stopAndJoin() {
            synchronized (lock) {
                stopped = true;
            }
            interrupt();
            try {
                join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 取消一个正在下载的模型。返回是否真的有东西被取消。 */
    public boolean cancel(String name) {
        AtomicBoolean flag = cancels.get(name);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    /** 是否有下载/校验在跑。 */
    public boolean isBusy(String name) {
        return running.contains(name);
    }

    /** 删掉本地文件（连同可能存在的 {@code .part}）。给「释放空间」用。 */
    public Snapshot remove(ModelManifestEntry entry) {
        File target = fileFor(entry);
        boolean had = target.exists();
        boolean deleted = !had || target.delete();
        partFor(entry).delete();
        Snapshot s = deleted ? cheapSnapshot(entry) : new Snapshot(entry.name, entry.file,
                STATE_CORRUPT, entry.bytes, 0L, 0, "", "删不掉：" + target,
                Collections.<String>emptyList(), true, null, target);
        latest.remove(entry.name);
        return s;
    }

    /** 所有镜像都失败时该显示给用户的原因：校验不符优先，其次按尝试顺序。 */
    private static String pickSummary(List<String> attempts) {
        for (String a : attempts) {
            if (a.indexOf("sha256") >= 0) {
                // 校验不符比「某个镜像连不上」重要得多，被普通网络错误盖掉会让人
                // 以为只是网络问题——而它其实是「有人给你发了另一份东西」。
                return a;
            }
        }
        return attempts.isEmpty() ? "所有镜像都失败了" : attempts.get(0);
    }

    private Snapshot failed(ModelManifestEntry entry, File path, String why) {
        return failedWithAttempts(entry, path, why, Collections.<String>emptyList());
    }

    private Snapshot failedWithAttempts(ModelManifestEntry entry, File path, String why,
                                        List<String> attempts) {
        return new Snapshot(entry.name, entry.file, STATE_FAILED, entry.bytes, 0L, 0, "", why,
                attempts, fileFor(entry).isFile(), null, path);
    }

    private Snapshot canceled(ModelManifestEntry entry, File path, List<String> attempts) {
        return new Snapshot(entry.name, entry.file, STATE_CANCELED, entry.bytes, 0L, 0, "",
                "已取消（半成品留在 " + entry.file + ".part，没有被当成可用文件）",
                attempts, fileFor(entry).isFile(), null, path);
    }

    private Snapshot publish(Snapshot s, Listener listener) {
        latest.put(s.name, s);
        if (listener != null) {
            try {
                listener.onState(s);
            } catch (RuntimeException e) {
                // 界面那条路炸了不该把一次已经成功的下载变成失败
            }
        }
        return s;
    }

    private static String describeMismatch(long received, String actual, ModelManifestEntry entry) {
        String prefix = received != entry.bytes
                ? received + " 字节（要的是 " + entry.bytes + " 字节）"
                : received + " 字节";
        return prefix + "，sha256 " + brief(actual) + "（要的是 " + brief(entry.sha256)
                + "）——**不会采用**";
    }

    /** 出错信息里只留前 12 位十六进制：够区分，又不至于把界面撑爆。 */
    private static String brief(String hex) {
        return hex.length() > 12 ? hex.substring(0, 12) + "…" : hex;
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.toString() : m;
    }

    // ---- 哈希 ---------------------------------------------------------------

    /** 分块读一个文件的 sha256（小写十六进制）。峰值内存只多一个块缓冲。 */
    public static String hashFile(File file) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            MessageDigest md = sha256();
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (IOException e) {
            throw ModelException.io(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        } finally {
            closeQuietly(in);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在 Java 8 与安卓 API 26 上都是必须支持的，走到这里说明运行时不完整
            throw ModelException.io("运行时不支持 SHA-256：" + e.getMessage(), e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 关一个资源失败没有补救动作，也不该盖掉真正的错误
        }
    }

    @Override
    public String toString() {
        return "ModelDownloads { dir: " + dir() + ", manifest: " + manifest + " }";
    }
}
