package dev.t1m3.qplayer.audio.model;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 取字节的<b>接缝</b>：player-core 只描述「我要这个 URL 的字节流」，
 * 真正怎么发这个请求由宿主决定。
 *
 * <p>分层与 {@code decode/Decoder.install(...)}、{@code output/OutputDevices.install(...)}
 * 完全一致：共享逻辑（清单、哈希校验、{@code .part} 改名、镜像按序重试、进度）都在
 * player-core，只有「怎么连网」这一件事归宿主。所以 player-core 里不会出现任何
 * Android API，而安卓那份实现能加它才有的东西——比如先问 {@code ConnectivityManager}，
 * 离线时立刻给一句「设备当前没有网络」，而不是让用户等 30 秒超时。
 *
 * <p>为什么不让接缝负责写盘/校验：那会把「下载成功」与「文件是对的」这两件事
 * 拆到两处实现里去，而两份实现迟早在校验上不一致——{@link ModelDownloads} 的注释里
 * 写了为什么这一条是这个子系统的全部意义。
 *
 * <p>实现约定：
 * <ul>
 *   <li>只有拿到 2xx 才返回；其余情况抛 {@link IOException}，消息要能给人看。</li>
 *   <li>{@link Response#body()} 返回的流由调用方关闭（{@link Response#close()} 负责收尾）。</li>
 *   <li>{@link Response#contentLength()} 拿不到时返回 {@code -1}——<b>这不是错误</b>：
 *       进度条按清单里的体积估，最终判据只有哈希。</li>
 * </ul>
 */
public interface ModelFetcher {

    /**
     * 打开一个 URL。
     *
     * @throws IOException 连不上、超时、非 2xx、被取消
     */
    Response open(String url) throws IOException;

    /** 一次已经打开的响应。 */
    interface Response extends Closeable {

        /** 响应体。调用方负责不把它读漏（读漏了就当没下完，哈希那关会挡住）。 */
        InputStream body();

        /** Content-Length；未知为 {@code -1}。 */
        long contentLength();

        /** 这个响应来自哪个主机（错误信息与界面上「用了哪个镜像」显示的就是它）。 */
        String host();

        /** 关掉连接。**必须幂等**：出错路径与成功路径都会调它。 */
        @Override
        void close();
    }
}
