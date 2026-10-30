package dev.t1m3.qplayer.desktop.audio;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ProgressiveAudioTest {
    @Test(timeout = 10_000)
    public void uncachedSeekUsesRangeBeforeTheSequentialTailArrives() throws Exception {
        byte[] payload = wav(256_000);
        CountDownLatch head = new CountDownLatch(1);
        CountDownLatch tail = new CountDownLatch(1);
        AtomicReference<String> rangeSeen = new AtomicReference<>();
        ExecutorService requests = Executors.newCachedThreadPool();
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(requests);
        server.createContext("/audio", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
            if (range != null) {
                rangeSeen.set(range);
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + (payload.length - 1) + "/" + payload.length);
            }
            exchange.sendResponseHeaders(range == null ? 200 : 206, payload.length - start);
            try (var output = exchange.getResponseBody()) {
                if (range == null) {
                    output.write(payload, 0, 300);
                    output.flush();
                    head.countDown();
                    tail.await(8, TimeUnit.SECONDS);
                    output.write(payload, 300, payload.length - 300);
                } else {
                    output.write(payload, start, payload.length - start);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException expectedOnCancellation) {
                // Replaced Range requests close the original response deliberately.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (SeekableByteSource bytes = new HttpByteSource(url(server), Map.of())) {
            assertTrue(head.await(2, TimeUnit.SECONDS));
            byte[] first = new byte[12];
            assertEquals(12, bytes.read(first, 0, 12));
            int offset = payload.length - 128;
            Future<byte[]> sought = consumer.submit(() -> {
                bytes.seek(offset);
                byte[] result = new byte[128];
                int got = 0;
                while (got < result.length) got += bytes.read(result, got, result.length - got);
                return result;
            });
            assertArrayEquals(Arrays.copyOfRange(payload, offset, payload.length), sought.get(2, TimeUnit.SECONDS));
            assertEquals("bytes=" + offset + "-", rangeSeen.get());
            assertEquals("the first sequential response is still withheld", 1L, tail.getCount());
            bytes.seek(0);
            assertEquals(12, bytes.read(first, 0, 12));
            assertArrayEquals(Arrays.copyOf(payload, 12), first);
            // Return from moov-like tail metadata to a cached prefix, then cross
            // its sparse hole: read() itself must request the missing interval.
            bytes.seek(296);
            byte[] crossing = new byte[16];
            int copied = 0;
            while (copied < crossing.length) {
                int n = bytes.read(crossing, copied, crossing.length - copied);
                assertTrue(n > 0);
                copied += n;
            }
            assertArrayEquals(Arrays.copyOfRange(payload, 296, 312), crossing);
        } finally {
            tail.countDown();
            consumer.shutdownNow();
            server.stop(0);
            requests.shutdownNow();
        }
    }

    @Test(timeout = 10_000)
    public void seekBackToShortCachedPrefixRefillsBeforeTheBackendBufferGate() throws Exception {
        byte[] payload = wav(256_000);
        CountDownLatch distantHead = new CountDownLatch(1);
        CountDownLatch releaseUnrelatedTails = new CountDownLatch(1);
        AtomicReference<String> refillRange = new AtomicReference<>();
        ExecutorService requests = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(requests);
        server.createContext("/audio", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
            if (range != null) {
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + (payload.length - 1) + "/" + payload.length);
            }
            exchange.sendResponseHeaders(range == null ? 200 : 206, payload.length - start);
            try (var output = exchange.getResponseBody()) {
                if (start == 300) {
                    refillRange.set(range);
                    output.write(payload, start, payload.length - start);
                } else {
                    int count = range == null ? 300 : 256;
                    output.write(payload, start, count);
                    output.flush();
                    if (range != null) distantHead.countDown();
                    releaseUnrelatedTails.await(8, TimeUnit.SECONDS);
                    output.write(payload, start + count, payload.length - start - count);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException expectedOnCancellation) {
                // Both unrelated responses are intentionally cancelled by seeks.
            } finally { exchange.close(); }
        });
        server.start();
        try (PcmSource pcm = PcmSource.open(url(server), Map.of())) {
            pcm.seek(4_000);
            assertTrue(distantHead.await(2, TimeUnit.SECONDS));
            pcm.seek(0);

            // Match DesktopAudioBackend.decodeInto: it will not enter read()
            // until this gate succeeds. Direct reads here would hide the bug.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pcm.hasBuffered(4_096) && System.nanoTime() < deadline) Thread.sleep(10L);
            assertTrue("returning to a short island must refill without entering read()", pcm.hasBuffered(4_096));
            assertEquals("bytes=300-", refillRange.get());
            assertEquals("the far response must still be withheld", 1L, releaseUnrelatedTails.getCount());
            byte[] decoded = new byte[8_192];
            assertEquals(decoded.length, pcm.read(decoded, 0, decoded.length));
            assertArrayEquals(Arrays.copyOfRange(payload, 44, 44 + decoded.length), decoded);
        } finally {
            releaseUnrelatedTails.countDown();
            server.stop(0);
            requests.shutdownNow();
        }
    }

    @Test(timeout = 10_000)
    public void serverIgnoringRangeFallsBackToCorrectSequentialOffsets() throws Exception {
        byte[] payload = wav(128_000);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) { output.write(payload); }
        });
        server.start();
        try (SeekableByteSource bytes = new HttpByteSource(url(server), Map.of())) {
            int offset = payload.length - 4;
            bytes.seek(offset);
            byte[] result = new byte[4];
            assertEquals(4, bytes.read(result, 0, 4));
            assertArrayEquals(Arrays.copyOfRange(payload, offset, payload.length), result);
            assertEquals(-1, bytes.read(result, 0, 4));
        } finally { server.stop(0); }
    }

    @Test(timeout = 10_000)
    public void closeCancelsBlockedHttpReadWithoutWaitingForSocketTimeout() throws Exception {
        CountDownLatch head = new CountDownLatch(1);
        CountDownLatch tail = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            exchange.sendResponseHeaders(200, 1_000);
            head.countDown();
            try { tail.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        server.start();
        SeekableByteSource bytes = new HttpByteSource(url(server), Map.of());
        try {
            assertTrue(head.await(2, TimeUnit.SECONDS));
            Future<Integer> blocked = worker.submit(() -> bytes.read(new byte[12], 0, 12));
            bytes.close();
            try {
                blocked.get(1, TimeUnit.SECONDS);
                fail("cancellation is not EOF");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }
        } finally {
            bytes.close();
            tail.countDown();
            worker.shutdownNow();
            server.stop(0);
        }
    }

    static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/audio";
    }

    static byte[] wav(int pcmBytes) {
        ByteBuffer out = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + pcmBytes);
        out.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(16_000).putInt(32_000);
        out.putShort((short) 2).putShort((short) 16);
        out.put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcmBytes);
        for (int i = 0; i < pcmBytes / 2; i++) out.putShort((short) (i * 97));
        return out.array();
    }
}
