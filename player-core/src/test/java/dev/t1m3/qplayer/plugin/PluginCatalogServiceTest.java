package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PluginCatalogServiceTest {
    private static final String PUBLISHER_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEb/6w4W2oyOmqybld26Cnv17mXEA6x1Xkw"
                    + "4zCrd+hfAnmcEJjBTKtC9jO0Q4DVDOzXsmehlW+PJRINAZ99up6aQ==";
    private static final String RELEASE_JSON = "{"
            + "\"tag_name\":\"v0.2.0\","
            + "\"assets\":["
            + "{\"name\":\"netease-0.2.0.qplug.sha256\","
            + "\"browser_download_url\":\"https://example.invalid/wrong\"},"
            + "{\"name\":\"netease-0.2.0.qplug\","
            + "\"browser_download_url\":\"https://github.com/o/r/releases/download/v0.2.0/n.qplug\"}"
            + "]}";


    @Test public void latestReleaseFallsBackToTheNextCandidateAndPicksTheQplugAsset()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PluginCatalogService service = new PluginCatalogService(
                new PluginPackageVerifier(), Collections.singletonList(source()), (url, limit) -> {
                    if (calls.getAndIncrement() == 0) throw new IOException("unreachable");
                    return RELEASE_JSON.getBytes("UTF-8");
                });

        List<PluginCatalogEntry> entries = service.loadLatest(false);

        assertEquals(2, calls.get());
        PluginCatalogEntry entry = entries.get(0);
        assertEquals("netease", entry.id);
        assertEquals("0.2.0", entry.version);
        assertEquals("https://github.com/o/r/releases/download/v0.2.0/n.qplug", entry.downloadUrl);
        assertFalse(entry.publisherKey.isEmpty());
    }

    @Test public void releaseWithoutAQplugAssetIsRejected() {
        PluginCatalogService service = new PluginCatalogService(
                new PluginPackageVerifier(),
                Collections.singletonList(source()),
                (url, limit) -> "{\"tag_name\":\"v1.0.0\",\"assets\":[]}".getBytes("UTF-8"));
        org.junit.Assert.assertThrows(IOException.class, () -> service.loadLatest(false));
    }

    @Test public void catalogRejectsDuplicateIdsAndInvalidPublisherKeys() throws Exception {
        String source = "{\"id\":\"netease\",\"name\":\"N\",\"description\":\"D\","
                + "\"repo\":\"owner/repo\",\"publisherKey\":\"" + PUBLISHER_KEY + "\"}";
        for (String json : Arrays.asList(
                "[" + source + "," + source + "]",
                "[{\"id\":\"qq\",\"name\":\"Q\",\"description\":\"D\","
                        + "\"repo\":\"owner/repo\",\"publisherKey\":\"bad\"}]")) {
            org.junit.Assert.assertThrows(IOException.class,
                    () -> PluginCatalogService.parseSources(json.getBytes("UTF-8")));
        }
    }

    @Test
    public void cancellationWaitsForInjectedDownloaderExitAndDoesNotRetry() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PluginCatalogService service = new PluginCatalogService(new PluginPackageVerifier(),
                () -> Collections.singletonList(source()), (url, limit) -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try { release.await(); }
                        catch (InterruptedException ignored) { interrupted.countDown(); }
                    }
                    throw new IOException("fixture downloader has exited");
                });
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?> loading = worker.submit(() -> {
            try { return service.loadLatest(false); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        });
        try {
            org.junit.Assert.assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture<Void> stopped = service.cancelPendingOperations();
            org.junit.Assert.assertTrue(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(stopped.isDone());
            release.countDown();
            stopped.get(2, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> loading.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            org.junit.Assert.assertThrows(IOException.class, () -> service.loadLatest(false));
        } finally {
            release.countDown();
            service.close();
            worker.shutdownNow();
        }
    }

    private static PluginCatalogService.Source source() {
        return new PluginCatalogService.Source("netease", "网易云音乐", "description",
                "owner/repo", PUBLISHER_KEY);
    }
}
