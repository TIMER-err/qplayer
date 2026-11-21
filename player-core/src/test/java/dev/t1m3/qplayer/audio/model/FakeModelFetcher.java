package dev.t1m3.qplayer.audio.model;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的取字节实现：把「一个镜像」做成内存里的一份字节，或者一种失败。
 *
 * <p>为什么不用真的网络：这个子系统的四条路径（成功 / 哈希不符 / 中断 /
 * 镜像回退）都要能在没有网络、没有几十兆文件的机器上跑完。这个类让它们变成
 * 几十字节的内存操作，同时<b>走的还是产品代码那条路</b>（{@link ModelDownloads}
 * 一行都没被绕过）。
 *
 * <p>三种「不对的响应」要分清楚，它们在现实里是三件不同的事：
 * <ul>
 *   <li>{@link #fail} —— 连不上 / 404：连响应体都没有。</li>
 *   <li>{@link #resetAfter} —— 传输中被掐断（掉线、被墙）：读的时候抛 IOException。</li>
 *   <li>{@link #endAfter} —— 干净地提前结束（服务端发少了 / 文件被截断）：
 *       读到一半 EOF，长度与哈希都对不上。</li>
 * </ul>
 */
final class FakeModelFetcher implements ModelFetcher {

    private final Map<String, byte[]> bodies = new LinkedHashMap<String, byte[]>();
    private final Map<String, String> failures = new LinkedHashMap<String, String>();
    private final Map<String, Integer> resets = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> ends = new LinkedHashMap<String, Integer>();
    private final Map<String, Long> slowness = new LinkedHashMap<String, Long>();
    private final List<String> silent = new ArrayList<String>();
    private final List<String> requests = new ArrayList<String>();

    /**
     * 这个 URL <b>连上了但一个字节都不给</b>：响应建立得成功，读的时候一直等到
     * {@link Response#close()} 被调用，然后像真 socket 被关掉那样抛 IOException。
     *
     * <p>它对应现实里最常见的那种「卡在 0%」：TCP 通了、请求也发出去了，
     * 然后服务器（或者中间那个代理）什么都不说。注意 {@code close()} 会解除阻塞——
     * 这正是产品代码那条「首字节预算」唯一的抓手（{@code HttpURLConnection.disconnect()}
     * 关掉 socket，阻塞中的 read 就会抛），所以这个假实现必须照做，否则它测的就不是
     * 同一条路。
     */
    FakeModelFetcher silent(String url) {
        silent.add(url);
        return this;
    }

    /** 这个 URL 会返回这些字节（完整、正确）。 */
    FakeModelFetcher serve(String url, byte[] body) {
        bodies.put(url, body);
        return this;
    }

    /** 这个 URL 会失败（连不上 / 404 / 被墙）。 */
    FakeModelFetcher fail(String url, String message) {
        failures.put(url, message);
        return this;
    }

    /** 发前 {@code count} 个字节之后就断线（读的时候抛 IOException）。 */
    FakeModelFetcher resetAfter(String url, byte[] body, int count) {
        bodies.put(url, body);
        resets.put(url, Integer.valueOf(count));
        return this;
    }

    /** 只发前 {@code count} 个字节，然后干净地结束（等于被截断的文件）。 */
    FakeModelFetcher endAfter(String url, byte[] body, int count) {
        bodies.put(url, body);
        ends.put(url, Integer.valueOf(count));
        return this;
    }

    /**
     * 每次 read 之前睡一会儿，把一次传输拉长到能看见进度回调的长度。
     *
     * <p>进度是<b>按时间</b>节流的（{@link ModelDownloads#PROGRESS_INTERVAL_MS}），
     * 几十字节的传输只会有一次「开始时」的回调。要断言「进度真的在推进」，
     * 传输本身就得慢到跨过那个间隔——不该为了测试去改产品代码里的节流。
     */
    FakeModelFetcher slow(String url, long millisPerRead) {
        slowness.put(url, Long.valueOf(millisPerRead));
        return this;
    }

    /** 按顺序记录每一次被请求的 URL（用来断言镜像顺序与「没有多余的请求」）。 */
    List<String> requests() {
        return Collections.unmodifiableList(requests);
    }

    int openCount() {
        return requests.size();
    }

    @Override
    public Response open(final String url) throws IOException {
        requests.add(url);
        String failure = failures.get(url);
        if (failure != null) {
            throw new IOException(failure);
        }
        final boolean isSilent = silent.contains(url);
        final byte[] body = isSilent ? new byte[0] : bodies.get(url);
        if (body == null) {
            throw new IOException("没有这个 URL：" + url);
        }
        Integer reset = resets.get(url);
        Integer end = ends.get(url);
        final boolean cut = reset != null;
        final int limit = reset != null ? reset.intValue()
                : end != null ? end.intValue() : body.length;
        Long nap = slowness.get(url);
        final long sleepMillis = nap == null ? 0L : nap.longValue();
        final java.util.concurrent.CountDownLatch closed =
                new java.util.concurrent.CountDownLatch(1);

        return new Response() {
            @Override
            public InputStream body() {
                if (isSilent) {
                    return new SilentStream(closed);
                }
                return new FakeStream(body, limit, cut, sleepMillis);
            }

            @Override
            public long contentLength() {
                return isSilent ? -1L : limit;
            }

            @Override
            public String host() {
                return ModelManifest.hostOf(url);
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
    }

    /**
     * 「服务器什么都不说」的那条流：每个 read 都等到 {@link Response#close()}，
     * 然后像被关掉的 socket 一样抛 IOException。一个字节都不返回。
     */
    private static final class SilentStream extends InputStream {

        private final java.util.concurrent.CountDownLatch closed;

        SilentStream(java.util.concurrent.CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public int read() throws IOException {
            return awaitClosed();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return awaitClosed();
        }

        private int awaitClosed() throws IOException {
            try {
                // 上限 5 分钟：真被挂住时测试会失败，而不是永远不返回
                if (!closed.await(300L, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IOException("服务器一直没有发数据，也没有被关掉");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("读被中断");
            }
            throw new IOException("连接已关闭（服务器没有发过任何数据）");
        }
    }

    /** 到 {@code limit} 就断（{@code cut}）或者干净结束。 */
    private static final class FakeStream extends InputStream {

        private final byte[] body;
        private final int limit;
        private final boolean throwAtEnd;
        private final long sleepMillis;
        private int at;

        FakeStream(byte[] body, int limit, boolean throwAtEnd, long sleepMillis) {
            this.body = body;
            this.limit = limit;
            this.throwAtEnd = throwAtEnd;
            this.sleepMillis = sleepMillis;
        }

        @Override
        public int read() throws IOException {
            if (at >= limit) {
                if (throwAtEnd) {
                    throw new IOException("连接中断（已收 " + at + " 字节）");
                }
                return -1;
            }
            return body[at++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (sleepMillis > 0L) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (at >= limit) {
                if (throwAtEnd) {
                    throw new IOException("连接中断（已收 " + at + " 字节）");
                }
                return -1;
            }
            int n = Math.min(len, limit - at);
            System.arraycopy(body, at, b, off, n);
            at += n;
            return n;
        }
    }

    // ---- 造数据的小工具 -----------------------------------------------------

    /** 一串确定的伪随机字节（同一个种子每次都一样，测试可重复）。 */
    static byte[] bytes(int length, int seed) {
        byte[] out = new byte[length];
        int state = seed * 7919 + 13;
        for (int i = 0; i < length; i++) {
            state = state * 1103515245 + 12345;
            out[i] = (byte) ((state >>> 16) & 0xff);
        }
        return out;
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一份只含一个模型的清单（体积/哈希按给定的字节算出来，不手抄）。 */
    static String manifestJsonFor(String name, String file, byte[] body, String... mirrors) {
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < mirrors.length; i++) {
            if (i > 0) {
                m.append(',');
            }
            m.append('"').append(mirrors[i]).append('"');
        }
        return "{\"mirrors\":[" + m + "],"
                + "\"manual\":{\"links\":[{\"label\":\"网盘\",\"url\":\"https://example.com/x\","
                + "\"code\":\"abcd\"}],\"note\":\"\"},"
                + "\"models\":[{\"name\":\"" + name + "\",\"file\":\"" + file + "\","
                + "\"bytes\":" + body.length + ",\"sha256\":\"" + sha256(body) + "\","
                + "\"enables\":\"beatGrid\",\"license\":\"MIT\","
                + "\"upstream\":\"https://example.com/up\",\"note\":\"测试权重\"}]}";
    }
}
