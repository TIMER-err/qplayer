package dev.t1m3.qplayer.audio.model;

import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * player-core 里那份默认的取字节实现：{@code java.net.HttpURLConnection}。
 *
 * <p>它<b>不是</b>「桌面实现」——桌面与安卓都可以直接用它（两端都有 {@code java.net}），
 * 桌面端就是这么用的（只是把 User-Agent 换成带版本号的）。它是 {@link ModelFetchers}
 * 在宿主什么都没装时的兜底，也是唯一一份不需要任何平台 API 的实现。
 *
 * <h2>两个超时是两件事，不能合成一个「总预算」</h2>
 *
 * <p>整笔传输给一个总预算是 folia 踩过的坑：它原来是 120 秒一笔，结果一次
 * 「hf-mirror 正在冷拉 83MB、慢但活着」被判成死，静默落到 GitHub——
 * 那条路中国大陆的用户根本用不了，而日志里什么都没说。所以这里：
 * <ul>
 *   <li>{@link #CONNECT_TIMEOUT_MS}：TCP 连不上的上限。</li>
 *   <li>{@link #READ_TIMEOUT_MS}：<b>每一次读</b>的上限，不是整笔传输的上限。
 *       一个还在推进的慢镜像不会被它掐掉；一个卡住不动的会。</li>
 * </ul>
 *
 * <p><b>「连上了但一个字节都不来」的短预算不在这里</b>，而在
 * {@link ModelDownloads#FIRST_BYTE_TIMEOUT_MS}：那边拿到 {@link Response} 之后
 * 由看门狗计时，超时就 {@link Response#close()}，阻塞中的 {@code read} 会抛出来。
 * 为什么不在这一层「先给响应头一个短超时、拿到头之后再换成 {@link #READ_TIMEOUT_MS}」：
 * <b>实测过，换不掉</b>——{@code setReadTimeout} 是在连接建立时落到 socket 上的，
 * 之后再调它不影响已经连上的那条连接（本机用一个「静默 5 秒再每 8 秒发 1 字节」的
 * 本地服务器验过：头阶段 6 秒超时生效，之后改成 20 秒仍然在第 6 秒被掐）。
 * 所以这里保持「一个连接一个读超时」，把「多久没动静」交给那边那个能关连接的人。
 *
 * <h2>重定向</h2>
 *
 * <p>交给 {@code HttpURLConnection} 自己跟随（同协议）。它<b>不</b>跟随
 * https → http 的降级跳转，这是 Java 的默认行为，我们不去绕过它：明文下载上百兆的
 * 可执行内容没有可接受的理由（与 {@link ModelManifest} 只接受 https 镜像同一条）。
 */
public final class NetModelFetcher implements ModelFetcher {

    /** 建立连接的上限。 */
    public static final int CONNECT_TIMEOUT_MS = 30_000;

    /**
     * 单次读的上限。
     *
     * <p>取 60 秒：慢链路上一块 {@code read} 可能等很久，但「迟迟一个字节都不来」
     * 与「来得很慢」必须区分开——只有前者该放弃这个镜像。
     */
    public static final int READ_TIMEOUT_MS = 60_000;

    private static final NetModelFetcher DEFAULT = new NetModelFetcher("QPlayer");

    private final String userAgent;

    public NetModelFetcher(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.trim();
        this.userAgent = ua.isEmpty() ? "QPlayer" : ua;
    }

    /** 宿主没装实现时用的那一份。 */
    public static NetModelFetcher defaultInstance() {
        return DEFAULT;
    }

    @Override
    public Response open(String url) throws IOException {
        HttpURLConnection conn = null;
        boolean handedOver = false;
        String host = ModelManifest.hostOf(url);
        long started = System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept", "application/octet-stream");
            // 不要压缩：Content-Length 要能与字节数对上，而哈希是对**解压后**的字节算的——
            // 一个偷偷压缩的服务器会让进度与「下了多少」全都说不清。
            conn.setRequestProperty("Accept-Encoding", "identity");

            int status = conn.getResponseCode();
            long headersMs = System.currentTimeMillis() - started;
            if (status < 200 || status >= 300) {
                // 每一次尝试都留一行：主机、HTTP 状态、响应头花了多久。
                // 「为什么一直 0%」在日志里必须能查到是哪个主机、什么状态码。
                Logger.warn("模型镜像 {} 返回 HTTP {} {}（响应头用时 {} 毫秒）", host,
                        Integer.valueOf(status), safeMessage(conn), Long.valueOf(headersMs));
                throw new IOException(host + " 返回 HTTP " + status
                        + " " + safeMessage(conn));
            }
            Logger.info("模型镜像 {} 返回 HTTP {}（响应头用时 {} 毫秒，Content-Length {}）",
                    host, Integer.valueOf(status), Long.valueOf(headersMs),
                    Long.valueOf(conn.getContentLengthLong()));

            Response response = new HttpResponse(conn, url);
            handedOver = true;
            return response;
        } catch (IOException e) {
            if (conn != null) {
                Logger.warn("模型镜像 {} 连不上（用时 {} 毫秒）：{}", host,
                        Long.valueOf(System.currentTimeMillis() - started), messageOf(e));
            }
            throw e;
        } finally {
            if (!handedOver && conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String messageOf(IOException e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.toString() : m;
    }

    /** {@code HTTP/1.1 404} 那一行的后半段；取不到就是空串。 */
    private static String safeMessage(HttpURLConnection conn) {
        try {
            String m = conn.getResponseMessage();
            return m == null ? "" : m;
        } catch (IOException e) {
            return "";
        }
    }

    /** 一个已经打开的 {@code HttpURLConnection} 响应。 */
    private static final class HttpResponse implements Response {

        private final HttpURLConnection conn;
        private final String host;
        private boolean closed;

        HttpResponse(HttpURLConnection conn, String url) {
            this.conn = conn;
            this.host = ModelManifest.hostOf(url);
        }

        @Override
        public InputStream body() {
            try {
                InputStream in = conn.getInputStream();
                return in != null ? in : new java.io.ByteArrayInputStream(new byte[0]);
            } catch (IOException e) {
                // 只有在 2xx 之后才可能走到这里（响应码那关已经过了），所以这是
                // 「连接在拿到头之后断了」——把它当成「响应体是空的」交给上层：
                // 上层会算出 0 字节、哈希不匹配，然后把这个镜像记成失败并换下一个。
                return new java.io.ByteArrayInputStream(new byte[0]);
            }
        }

        @Override
        public long contentLength() {
            long n = conn.getContentLengthLong();
            return n < 0L ? -1L : n;
        }

        @Override
        public String host() {
            return host;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            conn.disconnect();
        }
    }
}
