package dev.t1m3.qplayer.audio.model;

/**
 * 接缝的安装点，照 {@code Decoder.install(...)} / {@code OutputDevices.install(...)}
 * 的既有模式：共享代码只认接口，宿主在启动时把实现装进来。
 *
 * <p><b>没装的时候不是「没有这条路」，而是用 player-core 里的默认实现</b>
 * （{@link NetModelFetcher}，{@code java.net.HttpURLConnection}）。这条默认值有两个用处：
 * 单元测试可以直接构造假的取字节实现，不必先装一个宿主；而只跑逻辑的宿主
 * （未来的 CLI、测试夹具）不必为了下载模型写一个类。
 *
 * <p>{@link #install} 是全局静态状态，测试用完要 {@link #uninstall()} 擦干净——
 * 与 {@code BeatModels.install} 同一条纪律。
 */
public final class ModelFetchers {

    private static volatile ModelFetcher installed;

    private ModelFetchers() {
    }

    /** 安装宿主的取字节实现（桌面/安卓各一份，启动时一次）。 */
    public static void install(ModelFetcher fetcher) {
        installed = fetcher;
    }

    /** 卸载（{@code null} 等价于卸载）。给测试用。 */
    public static void uninstall() {
        installed = null;
    }

    /** 当前实现；宿主没装时返回默认的 {@link NetModelFetcher}。 */
    public static ModelFetcher current() {
        ModelFetcher f = installed;
        return f != null ? f : NetModelFetcher.defaultInstance();
    }

    /** 宿主是否装过自己的实现（不影响 {@link #current()} 的可用性，只用于日志/诊断）。 */
    public static boolean isInstalled() {
        return installed != null;
    }
}
