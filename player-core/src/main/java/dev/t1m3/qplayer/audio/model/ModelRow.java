package dev.t1m3.qplayer.audio.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「模型」界面上的一行：一个模型的现状 + 清单里那些该给人看的字段。
 *
 * <p>字段全 {@code public}（没有 getter）是这一层的约定：QML 桥是按公开字段反射读的，
 * 而 {@code SearchRow}/{@code PluginRow} 这些既有行对象都是这个形状。
 *
 * <p><b>状态码与状态文案分开。</b> {@link #state} 是
 * {@link ModelDownloads} 里的机器可读码，界面据此决定画进度条还是画按钮；
 * {@link #stateText} 是本地化之后给人看的句子。把它们合成一个字段，
 * 界面就只能靠比较中文串来判断状态了。
 */
public final class ModelRow {

    /** 模型名（清单里的键，也是界面上「下载/取消/校验」传回去的参数）。 */
    public String name = "";

    /** 文件名。 */
    public String file = "";

    /** 本地化的显示名（例如「拍点模型 beat_this」）。 */
    public String title = "";

    /** {@link ModelDownloads} 的 {@code STATE_*} 之一。 */
    public String state = ModelDownloads.STATE_ABSENT;

    /** 本地化的状态句子（含下载百分比）。 */
    public String stateText = "";

    /** 人类可读体积，例如 {@code "79.2 MB"}。 */
    public String sizeText = "";

    /** 许可证（这决定了我们能不能托管它）。 */
    public String license = "";

    /** 上游主页。 */
    public String upstream = "";

    /** 这一份权重的说明（版本、取舍）。 */
    public String note = "";

    /** 它打开了什么能力（{@code beatGrid} / {@code stems}）。 */
    public String enables = "";

    /** 失败原因，给人看；没有失败就是空串。 */
    public String error = "";

    /** 这次（或上次）用的镜像主机名；空表示还没成功过。 */
    public String mirror = "";

    /** 磁盘上有一个大小对得上的文件。 */
    public boolean present;

    /** 通过完整 sha256 校验（本次会话里查过）。 */
    public boolean verified;

    /** 正在下载。 */
    public boolean downloading;

    /** 正在算哈希。 */
    public boolean verifying;

    /** 现在可以点「下载」（未就绪、且没有别的下载在跑）。 */
    public boolean canDownload;

    /** 现在可以点「取消」。 */
    public boolean canCancel;

    /** 现在可以点「校验」（磁盘上有文件）。 */
    public boolean canVerify;

    /** 现在可以点「删除」（磁盘上有文件）。 */
    public boolean canRemove;

    /** 0..100。 */
    public int percent;

    /** 这次已经收到的字节数（下载中才有意义）。 */
    public long received;

    /** 已收到多少，给人看（例如 {@code "34.8 MB"}）。 */
    public String receivedText = "";

    /** 这一次传输已经花了多少秒（下载中才有意义）。 */
    public long elapsedSecs;

    /** 距离上一个字节多少秒（下载中才有意义）。0 = 刚刚还在收字节。 */
    public long sinceLastByteSecs;

    /**
     * 已经有一段时间没有新字节了。
     *
     * <p>进度百分比在「慢而活着」与「连上了但一个字节都不来」两种情况下是同一个数，
     * 所以界面上必须另有一句话把这两者分开——这就是那句话的依据。
     */
    public boolean stalled;

    /** 大小与哈希其中一项对不上——**这一份不会被采用**。 */
    public boolean corrupt;

    /**
     * 所有镜像都失败时该显示的手动兜底链接。
     *
     * <p>只在 {@link #manualVisible} 为真时显示：空链接比没有链接更糟，
     * 而镜像正常时摆一堆网盘入口只会让人以为自动下载不好使。
     */
    public List<ModelManifest.ManualLink> manualLinks = new ArrayList<ModelManifest.ManualLink>();

    /** 是否该显示手动兜底那一块。 */
    public boolean manualVisible;

    /** 手动兜底那一块的补充说明（清单里的 {@code manual.note}，可为空串）。 */
    public String manualNote = "";

    /** 这一行代表的是不是一个「多文件模型组」（一个按钮下好几个文件）。 */
    public boolean bundle;

    /** 组里的文件名（按清单顺序；单文件时就是那一个）。给人看。 */
    public List<String> bundleFiles = new ArrayList<String>();

    /** 组里已经就绪的文件数（用于「已就绪 2/5」这种说法）。 */
    public int bundleReady;

    /** 组里的文件总数。 */
    public int bundleTotal;

    /**
     * 造一个「多文件模型组」的行。
     *
     * <p>为什么按组出行：ONNX 带外部权重时 {@code .onnx} 与 {@code .onnx.data} 是一对，
     * 少一个都建不起会话；ACE-Step 那一套更是一整套文件。让用户自己点十几次、
     * 还得知道该点哪几个，等于把实现细节推给他——而少下一个的表现是
     * 「文件在磁盘上、加载却失败」，最难查。
     */
    public static ModelRow bundleOf(List<ModelManifestEntry> group) {
        ModelRow row = new ModelRow();
        ModelManifestEntry first = group.get(0);
        row.bundle = true;
        row.bundleTotal = group.size();
        row.name = first.bundleId();
        long total = 0L;
        for (ModelManifestEntry entry : group) {
            total += entry.bytes;
            row.bundleFiles.add(entry.file);
        }
        row.file = group.size() + " 个文件";
        row.sizeText = ModelManifestEntry.humanBytes(total);
        row.license = first.license;
        row.upstream = first.upstream;
        row.note = first.note;
        row.enables = first.enables;
        return row;
    }

    /**
     * 一组文件的聚合状态：<strong>按最差的那一个算</strong>。
     *
     * <p>只要有一个没下完，这一组就没下完——这条判据不能反过来（「至少有一个下好了就算好」
     * 会让用户以为能用了）。进度按字节数加权，不然小文件下完会让进度条假性地跳一大格。
     */
    public void applyBundle(List<ModelDownloads.Snapshot> snaps, List<ModelManifestEntry> group) {
        long totalBytes = 0L;
        long receivedBytes = 0L;
        int ready = 0;
        boolean anyDownloading = false;
        boolean anyVerifying = false;
        boolean anyFailed = false;
        boolean anyCorrupt = false;
        boolean allPresent = true;
        String firstError = "";
        String firstMirror = "";
        long elapsed = 0L;
        long stalledFor = 0L;
        boolean stalled = false;
        for (int i = 0; i < snaps.size(); i++) {
            ModelDownloads.Snapshot snap = snaps.get(i);
            ModelManifestEntry entry = group.get(i);
            totalBytes += entry.bytes;
            receivedBytes += Math.min(snap.received, entry.bytes);
            if (snap.isReady()) {
                ready++;
            }
            allPresent = allPresent && snap.present;
            anyDownloading = anyDownloading || ModelDownloads.STATE_DOWNLOADING.equals(snap.state);
            anyVerifying = anyVerifying || ModelDownloads.STATE_VERIFYING.equals(snap.state);
            anyCorrupt = anyCorrupt || ModelDownloads.STATE_CORRUPT.equals(snap.state);
            if (ModelDownloads.STATE_FAILED.equals(snap.state)
                    || ModelDownloads.STATE_CANCELED.equals(snap.state)) {
                anyFailed = true;
                if (firstError.isEmpty()) {
                    firstError = snap.error;
                }
            }
            if (firstMirror.isEmpty()) {
                firstMirror = snap.mirror;
            }
            elapsed = Math.max(elapsed, snap.elapsedMs);
            if (snap.stalled) {
                stalled = true;
                stalledFor = Math.max(stalledFor, snap.sinceLastByteMs);
            }
        }
        bundleReady = ready;
        this.state = ready == group.size() ? ModelDownloads.STATE_READY
                : anyDownloading ? ModelDownloads.STATE_DOWNLOADING
                : anyVerifying ? ModelDownloads.STATE_VERIFYING
                : anyCorrupt ? ModelDownloads.STATE_CORRUPT
                : anyFailed ? ModelDownloads.STATE_FAILED
                : ModelDownloads.STATE_ABSENT;
        this.present = allPresent;
        this.verified = ready == group.size();
        this.percent = totalBytes <= 0L ? 0
                : (int) Math.min(100L, receivedBytes * 100L / totalBytes);
        this.received = receivedBytes;
        this.error = firstError;
        this.mirror = firstMirror;
        this.elapsedSecs = elapsed / 1000L;
        this.sinceLastByteSecs = stalledFor / 1000L;
        this.stalled = stalled;
        this.downloading = anyDownloading;
        this.verifying = anyVerifying;
        this.corrupt = anyCorrupt;
        this.canCancel = anyDownloading || anyVerifying;
    }

    /** 从清单条目 + 一次状态快照生成一行（文案由调用方填）。 */
    public static ModelRow of(ModelManifestEntry entry, ModelDownloads.Snapshot snapshot) {
        ModelRow row = new ModelRow();
        row.name = entry.name;
        row.file = entry.file;
        row.sizeText = entry.sizeText();
        row.license = entry.license;
        row.upstream = entry.upstream;
        row.note = entry.note;
        row.enables = entry.enables;
        if (snapshot != null) {
            row.apply(snapshot);
        }
        return row;
    }

    /** 用一次状态快照刷新这一行。 */
    public void apply(ModelDownloads.Snapshot snapshot) {
        state = snapshot.state;
        error = snapshot.error;
        mirror = snapshot.mirror;
        present = snapshot.present;
        verified = snapshot.verified != null && snapshot.verified.booleanValue();
        percent = snapshot.percent;
        received = snapshot.received;
        receivedText = snapshot.receivedText();
        elapsedSecs = snapshot.elapsedSecs();
        sinceLastByteSecs = snapshot.sinceLastByteSecs();
        stalled = snapshot.stalled;
        downloading = ModelDownloads.STATE_DOWNLOADING.equals(snapshot.state);
        verifying = ModelDownloads.STATE_VERIFYING.equals(snapshot.state);
        corrupt = ModelDownloads.STATE_CORRUPT.equals(snapshot.state)
                || ModelDownloads.STATE_FAILED.equals(snapshot.state)
                || ModelDownloads.STATE_CANCELED.equals(snapshot.state);
        canDownload = !downloading && !verifying;
        canCancel = downloading;
        canVerify = present && !downloading && !verifying;
        canRemove = present && !downloading && !verifying;
        // 手动兜底那条路：失败/取消之后当然要给，**卡住的时候也给**。
        // 「所有镜像都失败」要等两分钟才轮到（三次超时各一分钟），而用户在第二十秒
        // 就已经能判断「这条路今天不通」了——那时候把手动链接摆出来，比让他干等
        // 两分钟再看到同一批链接有用得多。它不假装什么：这几条链接本来就在清单里，
        // 显示它们不会改变自动下载的结果。
        manualVisible = ModelDownloads.STATE_FAILED.equals(snapshot.state)
                || ModelDownloads.STATE_CANCELED.equals(snapshot.state)
                || (downloading && snapshot.stalled);
    }

    /** 手动兜底链接（拷贝一份，免得 QML 拿到内部列表）。 */
    public void setManual(List<ModelManifest.ManualLink> links, String note) {
        manualLinks = links == null
                ? new ArrayList<ModelManifest.ManualLink>()
                : new ArrayList<ModelManifest.ManualLink>(links);
        manualNote = note == null ? "" : note;
    }

    /** 只读视图（给测试用）。 */
    public List<ModelManifest.ManualLink> manualLinksView() {
        return Collections.unmodifiableList(manualLinks);
    }
}
