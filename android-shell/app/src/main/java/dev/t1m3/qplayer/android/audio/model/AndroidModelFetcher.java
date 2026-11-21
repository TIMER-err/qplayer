package dev.t1m3.qplayer.android.audio.model;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import dev.t1m3.qplayer.audio.model.ModelFetcher;
import dev.t1m3.qplayer.audio.model.ModelFetchers;
import dev.t1m3.qplayer.audio.model.NetModelFetcher;

import java.io.IOException;

/**
 * 安卓宿主的取字节实现：core 的 {@link NetModelFetcher} + 一件只有安卓才做得到的事
 * ——<b>先问一句有没有网</b>。
 *
 * <h2>为什么「先问一句」值得单独一个类</h2>
 *
 * <p>手机上「飞行模式/流量关了/只有不通外网的内网」是常态，而不是异常。
 * 如果直接发请求，用户看到的是：点「下载」→ 转圈 → 30 秒后（连接超时）才出现
 * 「下载失败」，而且原因是一句 TCP 层的话。先问 {@code ConnectivityManager}，
 * 离线时立刻返回一句「设备当前没有网络」，这个失败就变成即时的、可行动的。
 *
 * <p>注意这里<b>只</b>做这一件事：真正的重试、退到下一个镜像、哈希校验都不在宿主里，
 * 它们在 player-core 的 {@code ModelDownloads} 里（两端共用一份）。宿主多做的每一件事
 * 都会变成两端行为不一致的入口。
 *
 * <h2>关于 {@code getActiveNetworkInfo} 的弃用</h2>
 *
 * <p>它在 API 29 起被标记弃用，官方建议用 {@code NetworkCallback}。这里仍然用它，
 * 理由是它<b>同步、不需要注册回调、也不需要生命周期</b>，而这个接缝的调用点是
 * 一个「打开一个 URL」的同步方法（{@code NetworkCallback} 是异步的，套进同步方法里
 * 要么阻塞等待、要么引入状态机，两者都比这行代码更糟）。它在 API 26（本项目的 minSdk）
 * 到最新版本上都还能用。真到它被移除的那天，替换点就是这一个方法。
 */
public final class AndroidModelFetcher implements ModelFetcher {

    private final Context context;
    private final NetModelFetcher inner;

    public AndroidModelFetcher(Context context) {
        this.context = context.getApplicationContext();
        this.inner = new NetModelFetcher("QPlayer-android/" + appVersion(this.context));
    }

    /** 装到接缝上（{@code Application.onCreate} 里一次，与解码/输出两个接缝同处）。 */
    public static void install(Context context) {
        ModelFetchers.install(new AndroidModelFetcher(context));
    }

    /**
     * 版本号从 {@code PackageManager} 拿，而不是 {@code BuildConfig}：
     * 这个模块的 namespace 是 qml4j 的（{@code io.github.timer_err.qml4j.android}），
     * 生成出来的 {@code BuildConfig} 不在本包下，而且 {@code buildConfig} 特性默认不开。
     * User-Agent 里带版本只是为了出事之后能回头看，不值得为它加一个构建开关。
     */
    private static String appVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "dev";
        }
    }

    @Override
    public Response open(String url) throws IOException {
        if (!online()) {
            // 直接抛，不走网络：这一条比任何 HTTP 错误码都更能说明问题
            throw new IOException("设备当前没有网络（ConnectivityManager 报告没有可用网络）");
        }
        return inner.open(url);
    }

    /** 现在有没有可用的网络。拿不到系统服务时按「有」处理——让真正的请求去说话。 */
    private boolean online() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            // 权限/厂商 ROM 差异都不该把「下载」变成一条看不懂的错误：交给网络层去试
            return true;
        }
    }
}
