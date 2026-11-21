package dev.t1m3.qplayer.audio.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 真的走一遍 HTTP：本机起一个极小的服务，测的是 player-core 那份
 * {@link NetModelFetcher}（默认实现，桌面端用的就是它）。
 *
 * <p>为什么要有这一条：其余测试用假的取字节实现跑通了「下载 → 校验 → 改名」的逻辑，
 * 但那一步跳过了唯一一件只有真实实现才能证明的事——<b>HTTP 那一层</b>：
 * 状态码、跟随跳转、未知长度、以及「读到的字节就是响应体」。
 * 这里全部落在 {@code 127.0.0.1} 上，几十毫秒，不碰外网。
 *
 * <p>清单本身只接受 https 镜像（明文下载上百兆的可执行内容没有可接受的理由），
 * 所以这里<b>不</b>构造清单，而是直接测取字节那一层——镜像顺序与失败回退
 * 由 {@link ModelDownloadsTest} 用假实现钉着。这不是漏测，是两件事分开放。
 */
public class NetModelFetcherTest {

    private static final byte[] BODY = FakeModelFetcher.bytes(4096, 11);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private String base;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/good/weights.onnx", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(200, BODY.length);
                OutputStream out = exchange.getResponseBody();
                out.write(BODY);
                out.close();
            }
        });
        server.createContext("/chunked/weights.onnx", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // 长度 0 = 分块传输：Content-Length 拿不到，正是要测的那条路
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write(BODY);
                out.close();
            }
        });
        server.createContext("/redirect/weights.onnx", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Location", base + "/good/weights.onnx");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        server.createContext("/gone/weights.onnx", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void readsTheBodyAndReportsLengthAndHost() throws IOException {
        NetModelFetcher fetcher = new NetModelFetcher("QPlayer-test/1.0");
        ModelFetcher.Response response = fetcher.open(base + "/good/weights.onnx");
        try {
            assertEquals(BODY.length, response.contentLength());
            assertEquals("127.0.0.1:" + server.getAddress().getPort(), response.host());
            byte[] got = readAll(response.body());
            assertEquals("读到的字节必须就是响应体", BODY.length, got.length);
            assertEquals(sha256(BODY), sha256(got));
        } finally {
            response.close();
        }
    }

    /** 分块传输时 Content-Length 是拿不到的：{@code -1}，而不是一个瞎猜的数。 */
    @Test
    public void unknownLengthIsMinusOneRatherThanAGuess() throws IOException {
        ModelFetcher.Response response =
                new NetModelFetcher("QPlayer-test/1.0").open(base + "/chunked/weights.onnx");
        try {
            assertEquals(-1L, response.contentLength());
            assertEquals(BODY.length, readAll(response.body()).length);
        } finally {
            response.close();
        }
    }

    /** hf-mirror 与 GitHub release 都会跳转，所以跟随跳转必须是默认行为。 */
    @Test
    public void followsRedirects() throws IOException {
        ModelFetcher.Response response =
                new NetModelFetcher("QPlayer-test/1.0").open(base + "/redirect/weights.onnx");
        try {
            assertEquals(BODY.length, readAll(response.body()).length);
        } finally {
            response.close();
        }
    }

    /** 非 2xx 要变成异常，消息里带上状态码与主机——界面显示的失败原因就是这句话。 */
    @Test
    public void nonSuccessStatusBecomesAnExceptionThatNamesTheHost() {
        try {
            new NetModelFetcher("QPlayer-test/1.0").open(base + "/gone/weights.onnx").close();
            fail("404 必须抛");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
            assertTrue("要说清主机：" + e.getMessage(),
                    e.getMessage().contains("127.0.0.1:" + server.getAddress().getPort()));
            assertTrue("要说清状态码：" + e.getMessage(), e.getMessage().contains("404"));
        }
    }

    /** 连不上的时候也是一个能给人看的 IOException，而不是别的什么东西。 */
    @Test
    public void connectionRefusedIsAnIoSException() {
        // 端口 1 上不会有服务在听（保留端口，连上是不可能的）
        try {
            new NetModelFetcher("QPlayer-test/1.0").open("http://127.0.0.1:1/x.onnx").close();
            fail("连不上必须抛");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * 整条链路走真的 HTTP：清单里的 https 镜像被换成本机地址，剩下的一步不落地走
     * {@link ModelDownloads#download}（流式哈希、{@code .part}、改名、进度）。
     *
     * <p>把清单里的 URL 换成本机地址，是因为清单只接受 https 镜像（明文下载上百兆的
     * 可执行内容没有可接受的理由），而本机的测试服务是 http。换的是地址，不是路径——
     * 产品代码那几行一行都没绕过。
     */
    @Test
    public void theWholeDownloadPathWorksOverRealHttp() {
        final NetModelFetcher http = new NetModelFetcher("QPlayer-test/1.0");
        final String local = "http://127.0.0.1:" + server.getAddress().getPort();
        ModelFetcher rewriteToLocalhost = new ModelFetcher() {
            @Override
            public Response open(String url) throws IOException {
                return http.open(url.replace("https://mirror.test", local + "/good"));
            }
        };

        ModelManifest manifest = ModelManifest.parse(FakeModelFetcher.manifestJsonFor(
                "probe", "weights.onnx", BODY, "https://mirror.test/{file}"));
        ModelDownloads downloads = new ModelDownloads(folder.getRoot(), manifest, rewriteToLocalhost);

        ModelDownloads.Snapshot result = downloads.download(downloads.manifest().require("probe"));

        assertEquals(ModelDownloads.STATE_READY, result.state);
        assertEquals(Boolean.TRUE, result.verified);
        // 记录的是**清单里那条 URL** 的主机（界面上要告诉用户「用的是哪个镜像」）。
        // 真实运行时请求的就是它；这里的本机改写只是测试装置，不该出现在这条记录里。
        assertEquals("mirror.test", result.mirror);
        assertEquals(BODY.length, downloads.fileFor(downloads.manifest().require("probe")).length());
        assertTrue("半成品必须已经改名走",
                !downloads.partFor(downloads.manifest().require("probe")).exists());
    }

    /** 本机上的 404 也要能让整条链路把失败讲清楚（而不是抛异常穿透出去）。 */
    @Test
    public void aRealHttpFailureIsReportedAsAFailureNotAnException() {
        final NetModelFetcher http = new NetModelFetcher("QPlayer-test/1.0");
        final String local = "http://127.0.0.1:" + server.getAddress().getPort();
        ModelFetcher rewriteToLocalhost = new ModelFetcher() {
            @Override
            public Response open(String url) throws IOException {
                return http.open(url.replace("https://mirror.test", local + "/gone"));
            }
        };

        ModelManifest manifest = ModelManifest.parse(FakeModelFetcher.manifestJsonFor(
                "probe", "weights.onnx", BODY, "https://mirror.test/{file}"));
        ModelDownloads downloads = new ModelDownloads(folder.getRoot(), manifest, rewriteToLocalhost);

        ModelDownloads.Snapshot result = downloads.download(downloads.manifest().require("probe"));

        assertEquals(ModelDownloads.STATE_FAILED, result.state);
        assertTrue("原因里要有状态码：" + result.error, result.error.contains("404"));
        assertEquals(1, result.attempts.size());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
