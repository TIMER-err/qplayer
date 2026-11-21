package dev.t1m3.qplayer.desktop.audio.model;

import dev.t1m3.qplayer.audio.model.ModelFetcher;
import dev.t1m3.qplayer.audio.model.ModelFetchers;
import dev.t1m3.qplayer.audio.model.NetModelFetcher;

import java.io.IOException;

/**
 * 桌面宿主的取字节实现：{@link NetModelFetcher} + 一个能认出是谁在下载的 User-Agent。
 *
 * <p>它比安卓那一份薄，因为桌面没有安卓才有的那件事要做——{@code ConnectivityManager}
 * 那种「先问一句有没有网」的能力。桌面这个宿主上真正需要宿主自己决定的只有两件：
 *
 * <ul>
 *   <li><b>User-Agent 要带版本号。</b> 镜像与 CDN 会按 UA 做限流/拒绝，而「哪个版本
 *       在下这个文件」是出事之后唯一能回头看的东西。</li>
 *   <li><b>代理</b>：桌面走的是 JVM 自己的 {@code http.proxyHost} 那一套
 *       （启动参数 {@code -D} 或系统的 hosts 策略），这里不覆盖、也不自己读设置——
 *       覆盖它等于把用户配好的代理悄悄扔掉。</li>
 * </ul>
 *
 * <p>接缝本身的理由见 {@link ModelFetcher}：共享逻辑（清单、哈希、{@code .part} 改名、
 * 镜像按序）在 player-core，只有「怎么连网」归宿主。
 */
public final class DesktopModelFetcher implements ModelFetcher {

    private final NetModelFetcher inner;

    public DesktopModelFetcher(String appVersion) {
        this.inner = new NetModelFetcher(userAgent(appVersion));
    }

    /** 装到接缝上（Main 启动时一次）。 */
    public static void install(String appVersion) {
        ModelFetchers.install(new DesktopModelFetcher(appVersion));
    }

    static String userAgent(String appVersion) {
        String version = appVersion == null || appVersion.trim().isEmpty()
                ? "dev" : appVersion.trim();
        return "QPlayer/" + version + " (" + System.getProperty("os.name", "unknown") + ")";
    }

    @Override
    public Response open(String url) throws IOException {
        return inner.open(url);
    }
}
