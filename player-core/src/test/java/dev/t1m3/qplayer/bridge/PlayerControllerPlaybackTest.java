package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.plugin.PluginCredentialVault;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerControllerPlaybackTest {

    private static final long ASYNC_FADE_TIMEOUT_MS = 3000L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void encryptedCredentialNoticeWaitsForValidatedLogin() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("credential-notice-gate").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            java.lang.reflect.Method received = PlayerController.class.getDeclaredMethod(
                    "showPluginCredentialNotice", PluginCredentialVault.CredentialEvent.class);
            received.setAccessible(true);
            received.invoke(controller, PluginCredentialVault.CredentialEvent.ENCRYPTED);

            assertEquals(0L, controller.credentialNoticeRevision.peek().longValue());

            java.lang.reflect.Method publish = PlayerController.class.getDeclaredMethod(
                    "publishPendingCredentialEncryptedNotice");
            publish.setAccessible(true);
            publish.invoke(controller);

            assertEquals(1, controller.credentialNoticeType.peek().intValue());
            assertEquals(1L, controller.credentialNoticeRevision.peek().longValue());

            received.invoke(controller, PluginCredentialVault.CredentialEvent.ENCRYPTED);
            java.lang.reflect.Method clear = PlayerController.class.getDeclaredMethod(
                    "clearPublishedAccount");
            clear.setAccessible(true);
            clear.invoke(controller);
            publish.invoke(controller);

            assertEquals(1L, controller.credentialNoticeRevision.peek().longValue());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void selectingTrackAfterSessionRestoreDoesNotReplayItOnResume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("state").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":42000,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"restored\",\"durationMs\":120000,\"filePath\":\"restored.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"selected\",\"durationMs\":120000,\"filePath\":\"selected.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);

            // Choosing a queue entry is a real playAt() before the restored entry's
            // play button has consumed needsReplay.
            controller.playQueueIndex(1);
            assertEquals(1, backend.playCalls);

            controller.toggle(); // pause
            controller.toggle(); // resume must use the already-loaded backend

            assertEquals(1, backend.playCalls);
            assertEquals(1, backend.resumeCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void restoredCurrentTrackIsLikeableBeforePlaybackStarts() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("restore-likeable").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":42000,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"neteaseId\":123,\"title\":\"restored\","
                    + "\"durationMs\":120000,\"filePath\":\"restored.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.pump();

            assertTrue(controller.currentLikeable.peek());
            assertFalse(controller.currentLiked.peek());
            assertEquals(0, backend.playCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void mediaSessionControlsUseTheSameFadeAsManualToggle() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("media-pause").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            // The MediaSession intent flips immediately (state must be reported right
            // away), but the notification/lock-screen/dynamic-island pause now rides
            // the same ramp-down as a manual toggle() pause instead of cutting audio.
            int pausesBeforeMediaCommand = backend.pauseCalls;
            controller.mediaPause();
            assertFalse(controller.isPlaying());
            assertEquals(pausesBeforeMediaCommand, backend.pauseCalls);
            assertTrue(backend.playing);

            waitForVolume(backend, 0f, ASYNC_FADE_TIMEOUT_MS);
            // The final fade tick publishes zero volume immediately before it
            // invokes the deferred pause callback. Wait for that second observable
            // state as well instead of racing the two adjacent worker operations.
            waitForPauseCalls(backend, pausesBeforeMediaCommand + 1,
                    ASYNC_FADE_TIMEOUT_MS);
            assertEquals(pausesBeforeMediaCommand + 1, backend.pauseCalls);
            assertFalse(backend.playing);

            // Resuming from the notification fades back in from silence, symmetric
            // with the pause.
            int resumesBeforeMediaCommand = backend.resumeCalls;
            controller.mediaResume();
            assertTrue(controller.isPlaying());
            assertEquals(resumesBeforeMediaCommand + 1, backend.resumeCalls);
            assertTrue(backend.playing);
            assertEquals(0f, backend.volume, 0.05f);

            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);
            assertEquals(resumesBeforeMediaCommand + 1, backend.resumeCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void mediaResumeDuringPauseFadePicksUpTheRampWithoutDoubleResume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("media-pause-resume-race").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            // playAt() itself unconditionally pauses the backend once up front (there
            // is nothing playing yet to fade), so the "no extra pause" baseline is
            // captured here, not literal 0.
            int pausesBeforeMediaCommand = backend.pauseCalls;
            controller.mediaPause();
            Thread.sleep(120L); // catch the fade-out mid-ramp, before it reaches silence
            assertTrue(backend.volume < 0.8f);
            assertTrue(backend.playing); // the deferred backend.pause() hasn't landed yet

            int resumesBeforeMediaCommand = backend.resumeCalls;
            controller.mediaResume();
            assertTrue(controller.isPlaying());
            // Backend was never actually paused -- pick the ramp back up instead of
            // issuing a redundant resume().
            assertEquals(resumesBeforeMediaCommand, backend.resumeCalls);
            assertTrue(backend.playing);

            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);
            // The superseded pause's deferred completion must not fire late and
            // pause the backend out from under the resume.
            Thread.sleep(1000L);
            assertTrue(backend.playing);
            assertEquals(pausesBeforeMediaCommand, backend.pauseCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void lyricClockWaitsForAudioAndFollowsRealPauseState() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("lyric-clock").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);

            // play() only means the decoder was asked to start. Lyrics must remain at
            // the baseline until the backend confirms that audio is actually audible.
            backend.position = 800L;
            assertFalse(controller.isLyricClockRunning());
            assertEquals(0L, controller.lyricClockPosition());

            backend.fireStarted();
            assertTrue(controller.isLyricClockRunning());

            backend.position = 3210L;
            controller.toggle();
            // The pause button starts an audible fade-out. The backend is still
            // playing during that tail, so lyrics must continue with it.
            assertTrue(controller.isLyricClockRunning());
            assertEquals(3210L, controller.lyricClockPosition());

            backend.position = 3500L;
            assertEquals(3500L, controller.lyricClockPosition());

            // Once the fade completes and the backend really pauses, both clocks
            // stop at the same position. Resume therefore has no catch-up jump.
            backend.pause();
            assertFalse(controller.isLyricClockRunning());
            backend.position = 3500L;
            assertEquals(3500L, controller.lyricClockPosition());

            controller.toggle();
            assertTrue(controller.isLyricClockRunning());
            assertEquals(3500L, controller.lyricClockPosition());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void fadeFinishesWithoutRenderPumpAndPreservesUserVolume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("fade-clock").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setVolume(0.6f);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();

            // Deliberately never call controller.pump(): a hidden/destroyed window
            // must not strand playback at the first quiet fade sample.
            waitForVolume(backend, 0.6f, ASYNC_FADE_TIMEOUT_MS);
            assertEquals(0.6f, backend.volume, 0.02f);
            assertTrue(backend.volumeWrites > 2);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void replacingTrackCancelsOutgoingFadeCompletion() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("fade-switch").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"durationMs\":120000,\"filePath\":\"two.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();

            controller.next();
            int pausesAfterReplacement = backend.pauseCalls;
            // The second source is intentionally left in its loading window. The old
            // track's delayed fade completion must not pause this new request later.
            Thread.sleep(1050L);
            assertEquals(pausesAfterReplacement, backend.pauseCalls);
            assertTrue(backend.playing);
            assertEquals(0.8f, backend.volume, 0.001f);

            backend.fireStarted();
            assertEquals(0.8f, backend.volume, 0.001f);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void seekingAwayFromNaturalEndFadeRestoresFullGain() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("fade-end-seek").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            backend.position = 119500L;
            controller.pump();
            // The fade worker can start late on a busy CI runner. Wait for an
            // observable fade sample instead of assuming one landed within 120 ms.
            waitForVolumeBelow(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);
            assertTrue(backend.volume < 0.8f);

            controller.seek(1000L);
            assertEquals(0.8f, backend.volume, 0.001f);
            Thread.sleep(550L); // the cancelled old end fade would have reached zero
            assertEquals(0.8f, backend.volume, 0.001f);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void unifiedSearchRowsKeepSourceMenuIdentity() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("search-menu").toPath();
            AppDirs.setBase(base.toString());
            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            NeteaseSong netease = new NeteaseSong();
            netease.id = 42L;
            netease.name = "network";
            netease.artistId = 7L;
            netease.artistIdsCsv = "7,8";
            netease.artistNamesCsv = "first\u0001second";
            Track local = new Track();
            local.title = "local";
            local.filePath = "/music/local.flac";
            controller.searchResults.set(Arrays.asList(netease));
            controller.localSearchResults.set(Arrays.asList(local));
            controller.rebuildSearchRows();

            java.util.List<SearchRow> rows = controller.searchRows.peek();
            assertEquals(2, rows.size());
            assertTrue(rows.get(0).menuEnabled);
            assertEquals(42L, rows.get(0).id);
            assertEquals(7L, rows.get(0).artistId);
            assertEquals("7,8", rows.get(0).artistIdsCsv);
            assertEquals("first\u0001second", rows.get(0).artistNamesCsv);
            assertTrue(rows.get(1).menuEnabled);
            assertEquals("/music/local.flac", rows.get(1).filePath);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void convertsLegacyTextSearchHistoryToVersionedJson() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("search-history").toPath();
            AppDirs.setBase(base.toString());
            Path legacy = base.resolve("search_history.txt");
            Files.write(legacy, " first \nsecond\nfirst\n".getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            long deadline = System.currentTimeMillis() + 2000L;
            while (controller.searchHistory.peek().size() < 2
                    && System.currentTimeMillis() < deadline) {
                controller.pump();
                Thread.sleep(10L);
            }
            controller.pump();

            assertEquals(Arrays.asList("first", "second"), controller.searchHistory.peek());
            Path json = AppDirs.stateFile("search-history.json");
            assertTrue(Files.isRegularFile(json));
            String saved = new String(Files.readAllBytes(json), StandardCharsets.UTF_8);
            assertTrue(saved.contains("\"version\":1"));
            assertTrue(saved.contains("\"items\":[\"first\",\"second\"]"));
            assertFalse(Files.exists(legacy));
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void changingSearchTextImmediatelyDropsPreviousMixedSourceRows() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("search-generation").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            NeteaseSong netease = new NeteaseSong();
            netease.id = 1L;
            Track local = new Track();
            local.filePath = "/music/old.flac";
            controller.searchResults.set(Arrays.asList(netease));
            controller.localSearchResults.set(Arrays.asList(local));
            controller.rebuildSearchRows();
            assertEquals(2, controller.searchRows.peek().size());

            controller.prepareSearch("new keyword");

            assertTrue(controller.searchResults.peek().isEmpty());
            assertTrue(controller.localSearchResults.peek().isEmpty());
            assertTrue(controller.searchRows.peek().isEmpty());
            assertEquals(Integer.valueOf(0), controller.resultCount.peek());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static final class FakeAudioBackend implements AudioBackend {
        // volatile: written on the fade-tick worker thread, read from the test
        // thread. playCalls/pauseCalls/resumeCalls used to be plain ints -- a
        // volatile write to `volume` earlier in the same tick (see
        // applyEffectiveVolume) gives no JMM guarantee that a later plain write on
        // that same thread (this pauseCalls++) is visible yet, so a test polling
        // only `volume` via waitForVolume could observe silence before the
        // deferred pause() had actually landed.
        volatile int playCalls;
        volatile int pauseCalls;
        volatile int resumeCalls;
        volatile boolean playing;
        long position;
        Runnable onStarted;
        volatile float volume = 0.8f;
        volatile int volumeWrites;

        @Override public void play(String source, long startMs) {
            playCalls++;
            position = startMs;
            playing = true;
        }

        @Override public void pause() { pauseCalls++; playing = false; }

        @Override public void resume() {
            resumeCalls++;
            playing = true;
        }

        @Override public boolean isPlaying() { return playing; }
        @Override public void seek(long ms) { position = ms; }
        @Override public long position() { return position; }
        @Override public long duration() { return 120000L; }
        @Override public void setVolume(float volume) {
            this.volume = volume;
            volumeWrites++;
        }
        @Override public void setOnComplete(Runnable callback) { }
        @Override public void setOnStarted(Runnable callback) { onStarted = callback; }
        @Override public void release() { playing = false; }

        void fireStarted() {
            if (onStarted != null) onStarted.run();
        }
    }

    private static void waitForVolume(FakeAudioBackend backend, float target,
                                      long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (Math.abs(backend.volume - target) > 0.001f
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    private static void waitForVolumeBelow(FakeAudioBackend backend, float upperBound,
                                           long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (backend.volume >= upperBound && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    @Test
    public void restoredQueueKeepsAllArtistCredits() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("restore-artist-credits").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"neteaseId\":42,\"title\":\"song\","
                    + "\"artist\":\"first / second\",\"artistId\":7,"
                    + "\"artistIdsCsv\":\"7,8\",\"artistNamesCsv\":\"first\\u0001second\","
                    + "\"durationMs\":120000,\"filePath\":\"song.flac\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.pump();

            Track restored = controller.queueTracks.peek().get(0);
            assertEquals(7L, restored.artistId);
            assertEquals("7,8", restored.artistIdsCsv);
            assertEquals("first\u0001second", restored.artistNamesCsv);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static void waitForPauseCalls(FakeAudioBackend backend, int target,
                                          long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (backend.pauseCalls < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }
}
