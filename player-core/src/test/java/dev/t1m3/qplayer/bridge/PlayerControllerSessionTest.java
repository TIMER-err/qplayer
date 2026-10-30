package dev.t1m3.qplayer.bridge;

import static org.junit.Assert.*;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.StreamDescriptor;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.qml.QmlRuntimeLock;
import dev.t1m3.qplayer.store.AppDirs;

import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerControllerSessionTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule public final ControllerTestLifecycle lifecycle = new ControllerTestLifecycle();
    private String oldBase;
    private String oldCache;
    private PlayerController controller;
    private Backend backend;
    private PlaybackSession session;

    @Before
    public void createController() throws Exception {
        oldBase = AppDirs.base();
        oldCache = AppDirs.cacheBase();
        Path base = temporaryFolder.newFolder("session").toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
        backend = new Backend();
        controller = new PlayerController(backend, track -> {}, NeteaseClient.INSTANCE);
        Field field = PlayerController.class.getDeclaredField("playbackSession");
        field.setAccessible(true);
        session = (PlaybackSession) field.get(controller);
    }

    @After
    public void closeController() {
        try {
            if (controller != null) lifecycle.close(controller);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    @Test
    public void retirementCancelsAnAutomaticUpdateDuringItsTlsHandshake() throws Exception {
        CompletableFuture<Socket> accepted = new CompletableFuture<>();
        CompletableFuture<Void> handshake = new CompletableFuture<>();
        CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            OkHttpClient client =
                    new OkHttpClient.Builder()
                            .proxy(
                                    new Proxy(
                                            Proxy.Type.HTTP,
                                            new InetSocketAddress(
                                                    "127.0.0.1", listener.getLocalPort())))
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build();
            Field checker = PlayerController.class.getDeclaredField("releaseUpdateChecker");
            checker.setAccessible(true);
            checker.set(controller, new ReleaseUpdateChecker(client));
            Thread peer =
                    new Thread(
                            () -> {
                                try (Socket socket = listener.accept()) {
                                    accepted.complete(socket);
                                    socket.setSoTimeout(5000);
                                    InputStream input = socket.getInputStream();
                                    byte[] ending = new byte[] {13, 10, 13, 10};
                                    int matched = 0;
                                    for (int size = 0; matched != 4 && size < 8192; size++) {
                                        int next = input.read();
                                        if (next < 0) throw new EOFException("proxy request ended");
                                        matched =
                                                next == ending[matched]
                                                        ? matched + 1
                                                        : (next == 13 ? 1 : 0);
                                    }
                                    if (matched != 4)
                                        throw new IOException("missing CONNECT headers");
                                    socket.getOutputStream()
                                            .write(
                                                    "HTTP/1.1 200 Connection Established\r\n\r\n"
                                                            .getBytes(StandardCharsets.US_ASCII));
                                    socket.getOutputStream().flush();
                                    if (input.read() != 22)
                                        throw new IOException("expected TLS handshake");
                                    handshake.complete(null);
                                    try {
                                        byte[] buffer = new byte[1024];
                                        while (input.read(buffer) != -1) {}
                                    } catch (java.net.SocketException reset) {
                                        // Cancelling a handshake may close with TCP reset instead
                                        // of EOF.
                                    }
                                    disconnected.complete(true);
                                } catch (Exception error) {
                                    handshake.completeExceptionally(error);
                                    disconnected.completeExceptionally(error);
                                }
                            },
                            "delayed-update-peer");
            peer.setDaemon(true);
            peer.start();
            try {
                controller.setCurrentVersion("1.9.3-rc.2");
                controller.setUpdateMirror(false);
                controller.checkForUpdateManual();
                handshake.get(5, TimeUnit.SECONDS);
                controller.shutdownAsync().get(3, TimeUnit.SECONDS);
                assertTrue(
                        "retirement must close the real socket, not just complete a future",
                        disconnected.get(2, TimeUnit.SECONDS));
            } finally {
                Socket socket = accepted.getNow(null);
                if (socket != null) socket.close();
                listener.close();
                peer.join(2000);
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdownNow();
            }
        }
    }

    @Test
    public void resolvingTheSelectedSongCannotUndoUserPause() throws Exception {
        Track song = song("a");
        long request = select(song);
        controller.mediaPause();
        complete(request, song, "https://fixture.invalid/current");
        assertFalse(controller.isPlaying());
        assertFalse(backend.playing);
        assertEquals("https://fixture.invalid/current", backend.source);
    }

    @Test
    public void aToBToARejectsTheFirstAResponse() throws Exception {
        Track a = song("a");
        long first = select(a);
        select(song("b"));
        long second = select(a);
        complete(first, a, "https://fixture.invalid/stale");
        assertEquals(0, backend.plays);
        complete(second, a, "https://fixture.invalid/fresh");
        assertEquals(1, backend.plays);
        assertEquals("https://fixture.invalid/fresh", backend.source);
    }

    @Test
    public void seekDuringResolutionSuppliesTheLatestStartPosition() throws Exception {
        Track song = song("seek");
        long request = select(song);
        controller.mediaSeek(42000L);
        complete(request, song, "https://fixture.invalid/seek");
        assertEquals(42000L, backend.position);
        assertEquals(42000L, controller.playbackSnapshot().positionMs());
    }

    @Test
    public void immutableHostSnapshotDoesNotAliasMutableQueueMetadataOrArtwork() throws Exception {
        Track song = song("snapshot");
        song.coverBytes = new byte[] {1, 2, 3};
        select(song);
        PlaybackSnapshot first = controller.playbackSnapshot();
        song.title = "changed";
        song.coverBytes[0] = 9;
        assertEquals("snapshot", first.track().title());
        assertEquals(1, first.track().artwork().copyBytes()[0]);
        byte[] copy = first.track().artwork().copyBytes();
        copy[0] = 8;
        assertEquals(1, first.track().artwork().copyBytes()[0]);
        assertSame(first.track().artwork(), controller.playbackSnapshot().track().artwork());
    }

    @Test
    public void exitListenerNeverRunsUnderRuntimeLockEvenWithInlineExecutor() throws Exception {
        controller.setMainExecutor(Runnable::run);
        CountDownLatch called = new CountDownLatch(1);
        AtomicBoolean locked = new AtomicBoolean(true);
        controller.setExitListener(
                () -> {
                    locked.set(Thread.holdsLock(QmlRuntimeLock.MONITOR));
                    called.countDown();
                });
        synchronized (QmlRuntimeLock.MONITOR) {
            controller.requestExit();
        }
        assertTrue(called.await(3, TimeUnit.SECONDS));
        assertFalse("a host may join the render thread from this callback", locked.get());
    }

    @Test
    public void stagingAudioDoesNotCompleteTheFinalHostSave() throws Exception {
        Path source = temporaryFolder.newFile("source.mp3").toPath();
        Files.write(source, new byte[] {1, 2, 3, 4});
        Path stage = temporaryFolder.newFile("stage.mp3").toPath();
        CountDownLatch staged = new CountDownLatch(1);
        CompletableFuture<Boolean> hostCompletion = new CompletableFuture<>();
        PlayerController.FileSavePicker picker =
                (name, mime, writer) -> {
                    writer.accept(
                            stage.toString(),
                            success -> {
                                assertTrue(success);
                                staged.countDown();
                            });
                    return hostCompletion;
                };
        Method save =
                PlayerController.class.getDeclaredMethod(
                        "saveLocalAudioCopy",
                        PlayerController.FileSavePicker.class,
                        Track.class,
                        String.class);
        save.setAccessible(true);
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> result =
                (CompletableFuture<Boolean>)
                        save.invoke(controller, picker, song("save"), source.toString());
        assertTrue(staged.await(3, TimeUnit.SECONDS));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(stage));
        assertFalse("stage is not the SAF/final destination", result.isDone());
        hostCompletion.complete(Boolean.FALSE);
        assertFalse(result.get(3, TimeUnit.SECONDS));
    }

    @Test
    public void alreadyEmittedSourceEventsCannotHandleTheNextSongAfterMainQueueDelay()
            throws Exception {
        Track a = new Track();
        a.source = Track.Source.LOCAL;
        a.filePath = "a.mp3";
        a.title = "a";
        Track b = new Track();
        b.source = Track.Source.LOCAL;
        b.filePath = "b.mp3";
        b.title = "b";
        controller.scanTracks(java.util.Arrays.asList(a, b));
        controller.play(0);
        Runnable errorA = backend.error;
        Runnable completeA = backend.complete;
        Runnable startedA = backend.started;
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> main =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        controller.setMainExecutor(main::add);
        errorA.run();
        completeA.run();
        controller.play(1);
        startedA.run();
        Runnable pending;
        while ((pending = main.poll()) != null) pending.run();
        assertEquals("b", controller.playbackSnapshot().track().title());
        assertEquals("old error/completion cannot skip or retry the next song", 2, backend.plays);
        assertEquals("b.mp3", backend.source);
    }

    @Test
    public void publicManualCacheActionPublishesItsActualSuccessfulCompletion() throws Exception {
        CompletableFuture<Boolean> transfer = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Path> target =
                new java.util.concurrent.atomic.AtomicReference<>();
        AudioCacheCoordinator coordinator =
                installCacheTransport(
                        (track, path) -> {
                            target.set(java.nio.file.Paths.get(path));
                            started.countDown();
                            return transfer;
                        });
        Track track = song("manual-success");
        controller.scanTracks(java.util.Collections.singletonList(track));
        controller.cacheMediaSong(track.canonicalId());
        assertTrue(started.await(3, TimeUnit.SECONDS));
        awaitToast("toast.cache.started");
        Files.createDirectories(target.get().getParent());
        Files.write(target.get(), new byte[] {1, 2, 3});
        transfer.complete(true);
        ControllerTestLifecycle.releaseCompletedCache(coordinator);
        awaitToast("toast.cache.done");
        assertTrue(controller.diskCache.isActivelyCached(track.canonicalId()));
    }

    @Test
    public void publicManualCacheActionPublishesItsActualFailedCompletion() throws Exception {
        CompletableFuture<Boolean> transfer = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AudioCacheCoordinator coordinator =
                installCacheTransport(
                        (track, path) -> {
                            started.countDown();
                            return transfer;
                        });
        Track track = song("manual-failure");
        controller.scanTracks(java.util.Collections.singletonList(track));
        controller.cacheMediaSong(track.canonicalId());
        assertTrue(started.await(3, TimeUnit.SECONDS));
        transfer.complete(false);
        ControllerTestLifecycle.releaseCompletedCache(coordinator);
        awaitToast("toast.cache.failed");
        assertFalse(controller.diskCache.isActivelyCached(track.canonicalId()));
    }

    private void awaitToast(String key) throws InterruptedException {
        String expected = dev.t1m3.qplayer.i18n.I18n.tr(key);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            controller.pump();
            if (expected.equals(controller.toast.peek())) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, controller.toast.peek());
    }

    private AudioCacheCoordinator installCacheTransport(AudioCacheCoordinator.Downloader downloader)
            throws Exception {
        Field io = PlayerController.class.getDeclaredField("ioWorker");
        io.setAccessible(true);
        AudioCacheCoordinator coordinator =
                new AudioCacheCoordinator(
                        controller.diskCache,
                        (java.util.concurrent.Executor) io.get(controller),
                        downloader,
                        track -> {});
        Field owner = PlayerController.class.getDeclaredField("audioCacheCoordinator");
        owner.setAccessible(true);
        owner.set(controller, coordinator);
        return coordinator;
    }

    @Test
    public void pausedLocalPlaybackWithLyricsRetiresWithoutCatalogNetworkOrProducerCompletion()
            throws Exception {
        Path audio = temporaryFolder.newFile("local.mp3").toPath();
        Path lyric = temporaryFolder.newFile("local.lrc").toPath();
        Files.write(
                lyric, "[00:00.00]local lyrics".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Track local = new Track();
        local.source = Track.Source.LOCAL;
        local.filePath = audio.toString();
        local.lyricFilePath = lyric.toString();
        local.title = "local";
        controller.scanTracks(java.util.Collections.singletonList(local));
        controller.play(0);
        controller.mediaPause();
        controller.shutdownAsync().get(5, TimeUnit.SECONDS);
        Field worker = PlayerController.class.getDeclaredField("worker");
        worker.setAccessible(true);
        assertTrue(((java.util.concurrent.ExecutorService) worker.get(controller)).isTerminated());
        Field io = PlayerController.class.getDeclaredField("ioWorker");
        io.setAccessible(true);
        assertTrue(((java.util.concurrent.ExecutorService) io.get(controller)).isTerminated());
    }

    private long select(Track track) {
        synchronized (QmlRuntimeLock.MONITOR) {
            session.queue.clear();
            session.queue.add(track);
            session.index = 0;
            long request = session.select(true);
            session.resolving = true;
            return request;
        }
    }

    private void complete(long request, Track track, String url) throws Exception {
        StreamDescriptor stream = new StreamDescriptor();
        stream.url = url;
        stream.cacheable = false;
        Method method =
                PlayerController.class.getDeclaredMethod(
                        "completePluginResolution",
                        long.class,
                        Track.class,
                        MediaId.class,
                        StreamDescriptor.class,
                        Throwable.class,
                        long.class);
        method.setAccessible(true);
        synchronized (QmlRuntimeLock.MONITOR) {
            method.invoke(
                    controller,
                    request,
                    track,
                    MediaId.parse(track.canonicalId()),
                    stream,
                    null,
                    0L);
        }
    }

    private static Track song(String id) {
        Track song = new Track();
        song.applyCanonicalId("fixture:song:" + id);
        song.source = Track.Source.PLUGIN;
        song.title = id;
        song.durationMs = 120000L;
        return song;
    }

    private static final class Backend implements AudioBackend {
        private int plays;
        private String source;
        private long position;
        private boolean playing;
        private Runnable started;
        private Runnable error;
        private Runnable complete;

        @Override
        public void play(String source, long position) {
            this.plays++;
            this.source = source;
            this.position = position;
            this.playing = true;
            if (started != null) started.run();
        }

        @Override
        public void pause() {
            playing = false;
        }

        @Override
        public void resume() {
            playing = true;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void seek(long position) {
            this.position = position;
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public long duration() {
            return 120000L;
        }

        @Override
        public void setVolume(float volume) {}

        @Override
        public void setOnComplete(Runnable callback) {
            complete = callback;
        }

        @Override
        public void setOnStarted(Runnable callback) {
            started = callback;
        }

        @Override
        public void setOnError(Runnable callback) {
            error = callback;
        }

        @Override
        public void release() {
            playing = false;
        }
    }
}
