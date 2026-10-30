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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class PlayerControllerPlaybackTest {

    private static final long ASYNC_FADE_TIMEOUT_MS = 3000L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule public final ControllerTestLifecycle lifecycle = new ControllerTestLifecycle();

    @Test
    public void retiringSceneCannotDetachReplacementWakeCallback() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("scene-wake").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            java.util.concurrent.atomic.AtomicInteger wakes = new java.util.concurrent.atomic.AtomicInteger();
            Runnable oldWake = () -> { throw new AssertionError("retired scene woke"); };
            Runnable replacement = wakes::incrementAndGet;
            controller.setRenderWake(oldWake);
            controller.setRenderWake(replacement);
            controller.clearRenderWake(oldWake);
            controller.pressBack();
            assertEquals(1, wakes.get());
            controller.clearRenderWake(replacement);
            controller.pressBack();
            assertEquals(1, wakes.get());
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

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
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void restoredLocalMetadataDoesNotInventAnOnlineLikeCapability() throws Exception {
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

            assertFalse("legacy metadata is not an installed source capability", controller.currentLikeable.peek());
            assertFalse(controller.currentLiked.peek());
            assertEquals(0, backend.playCalls);
        } finally {
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void pauseBeforeAudioStartsIsNotOverriddenByOnStarted() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("pause-before-start").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            assertTrue(controller.isPlaying());
            // Android MediaPlayer is still in prepareAsync here: play() has been
            // asked, but isPlaying() is false until onPrepared. FakeAudioBackend
            // otherwise starts immediately, which would take the audible fade path.
            backend.playing = false;

            int pausesBeforeToggle = backend.pauseCalls;
            controller.toggle();
            assertFalse(controller.isPlaying());
            assertEquals("pause at t=0 must not wait for a fade that has no audio yet",
                    pausesBeforeToggle + 1, backend.pauseCalls);
            assertFalse(backend.playing);

            backend.fireStarted();
            assertFalse("onStarted must not resurrect a user pause during prepare",
                    controller.isPlaying());
            assertFalse(backend.playing);
            assertEquals(pausesBeforeToggle + 2, backend.pauseCalls);
        } finally {
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
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
            assertFalse(Files.exists(legacy));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void addSearchHistoryDeduplicatesIgnoringCaseAndPromotesLatest() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("search-history-dedup").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            controller.addSearchHistory("  Foo  ");
            controller.addSearchHistory("bar");
            controller.addSearchHistory("foo");
            controller.addSearchHistory("   ");
            controller.addSearchHistory(null);

            long deadline = System.currentTimeMillis() + 2000L;
            while (controller.searchHistory.peek().size() < 2
                    && System.currentTimeMillis() < deadline) {
                controller.pump();
                Thread.sleep(10L);
            }
            controller.pump();

            assertEquals(Arrays.asList("foo", "bar"), controller.searchHistory.peek());
        } finally {
            if (controller != null) lifecycle.close(controller);
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
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void shufflePrevReturnsTheTrackThatWasActuallyPlaying() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("shuffle-prev").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"durationMs\":120000,\"filePath\":\"two.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"three\",\"durationMs\":120000,\"filePath\":\"three.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(false);
            controller.playQueueIndex(0);
            controller.setPlayMode(1);
            controller.next();
            assertTrue(playIndex(controller) != 0);
            controller.prev();
            assertEquals("prev in shuffle must return the song next() just left",
                    0, playIndex(controller));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void shufflePrevWalksPlayHistoryWithoutBouncing() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("shuffle-prev-stack").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"durationMs\":120000,\"filePath\":\"two.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"three\",\"durationMs\":120000,\"filePath\":\"three.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(false);
            controller.playQueueIndex(0);
            controller.playQueueIndex(1);
            controller.playQueueIndex(2);
            controller.setPlayMode(1);
            controller.prev();
            assertEquals(1, playIndex(controller));
            controller.prev();
            assertEquals("a second prev must not bounce back to the song we just left",
                    0, playIndex(controller));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void staleResolveAtTheSameIndexDoesNotSkipTheNewTrack() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("stale-resolve").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);

            Track first = localTrack("playlist-a.mp3", "first");
            Track second = localTrack("playlist-b.mp3", "second");
            Track third = localTrack("playlist-b-next.mp3", "third");
            setQueue(controller, 0, first, localTrack("playlist-a-next.mp3", "unused"));
            setQueue(controller, 0, second, third);

            java.lang.reflect.Method skip = PlayerController.class.getDeclaredMethod(
                    "skipUnplayable", Track.class, String.class);
            skip.setAccessible(true);
            skip.invoke(controller, first, "stale resolve");

            assertEquals("a superseded resolve must not walk past the song just selected",
                    0, playIndex(controller));
            assertEquals("second", controller.currentTrack().title);
            assertEquals(0, backend.playCalls);

            skip.invoke(controller, second, "actually unplayable");
            assertEquals(1, playIndex(controller));
            assertEquals("third", controller.currentTrack().title);
            assertEquals("playlist-b-next.mp3", backend.lastSource);
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void currentTrackIdentityIgnoresASharedQueueIndex() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("track-identity").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            Track first = localTrack("a.mp3", "first");
            Track second = localTrack("b.mp3", "second");
            setQueue(controller, 0, first);
            java.lang.reflect.Method isCurrent = PlayerController.class.getDeclaredMethod(
                    "isCurrentTrack", Track.class);
            isCurrent.setAccessible(true);
            assertTrue((Boolean) isCurrent.invoke(controller, first));

            setQueue(controller, 0, second);
            assertFalse("same slot, different song",
                    (Boolean) isCurrent.invoke(controller, first));
            assertTrue((Boolean) isCurrent.invoke(controller, second));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static Track localTrack(String path, String title) {
        Track track = new Track();
        track.source = Track.Source.LOCAL;
        track.filePath = path;
        track.title = title;
        track.durationMs = 120000L;
        return track;
    }

    private static void setQueue(PlayerController controller, int index, Track... tracks)
            throws Exception {
        java.lang.reflect.Field field = PlayerController.class.getDeclaredField("playbackSession");
        field.setAccessible(true);
        PlaybackSession session = (PlaybackSession) field.get(controller);
        synchronized (dev.t1m3.qplayer.qml.QmlRuntimeLock.MONITOR) {
            session.invalidate();
            session.queue.clear();
            session.queue.addAll(java.util.Arrays.asList(tracks));
            session.index = index;
            session.playing = index >= 0;
        }
    }

    private static int playIndex(PlayerController controller) throws Exception {
        java.lang.reflect.Field field = PlayerController.class.getDeclaredField("playbackSession");
        field.setAccessible(true);
        synchronized (dev.t1m3.qplayer.qml.QmlRuntimeLock.MONITOR) {
            return ((PlaybackSession) field.get(controller)).index;
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

        volatile String lastSource;

        @Override public void play(String source, long startMs) {
            playCalls++;
            lastSource = source;
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
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void myPlaylistsMergeEverySourceInOrder() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("my-playlists-merge").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            java.lang.reflect.Field orderField =
                    PlayerController.class.getDeclaredField("myPlaylistSourceOrder");
            orderField.setAccessible(true);
            orderField.set(controller, Arrays.asList("primary", "secondary"));
            java.lang.reflect.Field slicesField =
                    PlayerController.class.getDeclaredField("myPlaylistsBySource");
            slicesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, java.util.List<dev.t1m3.qplayer.media.Playlist>> slices =
                    (java.util.Map<String, java.util.List<dev.t1m3.qplayer.media.Playlist>>)
                            slicesField.get(controller);
            // Inserted secondary-first to prove publication follows the source order,
            // not whichever source answered first.
            slices.put("secondary", Arrays.asList(playlist("b:playlist:1", "second")));
            slices.put("primary", Arrays.asList(playlist("a:playlist:1", "first")));

            java.lang.reflect.Method publish =
                    PlayerController.class.getDeclaredMethod("publishMyPlaylists");
            publish.setAccessible(true);
            publish.invoke(controller);

            assertEquals(2, controller.sourceMyPlaylists.peek().size());
            assertEquals("first", controller.sourceMyPlaylists.peek().get(0).name);
            assertEquals("second", controller.sourceMyPlaylists.peek().get(1).name);
            assertEquals(2, controller.playlistCount.peek().intValue());

            java.lang.reflect.Method drop = PlayerController.class.getDeclaredMethod(
                    "dropSourceUserData", String.class);
            drop.setAccessible(true);
            drop.invoke(controller, "primary");

            // Signing out of one source must not empty 我的 for the others.
            assertEquals(1, controller.sourceMyPlaylists.peek().size());
            assertEquals("second", controller.sourceMyPlaylists.peek().get(0).name);
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void switchingPrimarySourceDropsItsContentButKeepsMyPlaylists() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("primary-switch-clear").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            controller.sourceRecommendPlaylists.set(
                    Arrays.asList(playlist("a:playlist:9", "recommended")));
            controller.loggedIn.set(true);
            controller.userName.set("someone");
            java.lang.reflect.Field orderField =
                    PlayerController.class.getDeclaredField("myPlaylistSourceOrder");
            orderField.setAccessible(true);
            orderField.set(controller, Arrays.asList("secondary"));
            java.lang.reflect.Field slicesField =
                    PlayerController.class.getDeclaredField("myPlaylistsBySource");
            slicesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, java.util.List<dev.t1m3.qplayer.media.Playlist>> slices =
                    (java.util.Map<String, java.util.List<dev.t1m3.qplayer.media.Playlist>>)
                            slicesField.get(controller);
            slices.put("secondary", Arrays.asList(playlist("b:playlist:1", "kept")));

            java.lang.reflect.Method clear =
                    PlayerController.class.getDeclaredMethod("clearSourceScopedContent");
            clear.setAccessible(true);
            clear.invoke(controller);

            assertTrue(controller.sourceRecommendPlaylists.peek().isEmpty());
            assertFalse(controller.loggedIn.peek());
            assertEquals("", controller.userName.peek());

            java.lang.reflect.Method publish =
                    PlayerController.class.getDeclaredMethod("publishMyPlaylists");
            publish.setAccessible(true);
            publish.invoke(controller);
            assertEquals(1, controller.sourceMyPlaylists.peek().size());
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void likedSongsStayScopedToTheirOwnSource() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("liked-per-source").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            java.lang.reflect.Field slicesField =
                    PlayerController.class.getDeclaredField("pluginLikedBySource");
            slicesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, java.util.Set<String>> slices =
                    (java.util.Map<String, java.util.Set<String>>) slicesField.get(controller);
            slices.put("netease", new java.util.LinkedHashSet<>(
                    Arrays.asList("netease:song:1", "netease:song:2")));
            slices.put("qq", new java.util.LinkedHashSet<>(
                    Arrays.asList("qq:song:004X")));

            java.lang.reflect.Method publish =
                    PlayerController.class.getDeclaredMethod("publishPluginLiked");
            publish.setAccessible(true);
            publish.invoke(controller);
            assertEquals(3, controller.likedCount.peek().intValue());
            int revisionAfterPublish = controller.likedRevision.peek();
            assertTrue(revisionAfterPublish > 0);

            java.lang.reflect.Method apply = PlayerController.class.getDeclaredMethod(
                    "applyPluginLiked",
                    dev.t1m3.qplayer.media.MediaId.class, boolean.class);
            apply.setAccessible(true);
            apply.invoke(controller, dev.t1m3.qplayer.media.MediaId.parse("qq:song:NEW"), true);
            assertTrue(controller.isMediaLiked("qq:song:NEW"));
            assertTrue(controller.likedRevision.peek() > revisionAfterPublish);

            // A later liked-list publish must keep the just-toggled id: toggle
            // writes the per-source slice, not only the union set.
            publish.invoke(controller);
            assertTrue("just-liked id survived a liked-list republish",
                    controller.isMediaLiked("qq:song:NEW"));

            java.lang.reflect.Method drop = PlayerController.class.getDeclaredMethod(
                    "dropSourceUserData", String.class);
            drop.setAccessible(true);
            drop.invoke(controller, "netease");

            // Signing out of one source must not forget the other's hearts
            // (the original qq song plus the one just liked).
            assertEquals(2, controller.likedCount.peek().intValue());
            assertTrue(controller.isMediaLiked("qq:song:004X"));
            assertTrue(controller.isMediaLiked("qq:song:NEW"));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void aPluginDrivingPlaybackOnlyAllowsItsOwnSongs() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("plugin-playback-guard").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            java.lang.reflect.Method blocked = PlayerController.class.getDeclaredMethod(
                    "blockedByPluginSession", Track.class);
            blocked.setAccessible(true);
            Track local = new Track();
            local.source = Track.Source.LOCAL;
            local.filePath = "/music/song.flac";
            Track netease = new Track();
            netease.source = Track.Source.PLUGIN;
            netease.mediaId = "netease:song:42";
            Track other = new Track();
            other.source = Track.Source.PLUGIN;
            other.mediaId = "qq:song:004X";

            // No plugin driving playback: everything plays.
            assertFalse((Boolean) blocked.invoke(controller, local));

            java.lang.reflect.Field owner =
                    PlayerController.class.getDeclaredField("pluginAutoAdvanceBlocker");
            owner.setAccessible(true);
            owner.set(controller, "netease");

            assertFalse("the hosting source's own songs still play",
                    (Boolean) blocked.invoke(controller, netease));
            assertTrue("a local file is not addressable by the driving plugin",
                    (Boolean) blocked.invoke(controller, local));
            assertTrue("another source's songs are not addressable either",
                    (Boolean) blocked.invoke(controller, other));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void replayingTheSameTrackKeepsLyricsWithoutARenderPump() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("loop-lyrics").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":2,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"durationMs\":120000,\"filePath\":\"two.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.playQueueIndex(0);
            controller.pump();

            List<dev.t1m3.qplayer.lyric.LyricLine> lines =
                    dev.t1m3.qplayer.lyric.LrcParser.parse(
                            "[00:01.00]第一行\n[00:05.00]第二行");
            controller.lyrics.set(lines);

            controller.playQueueIndex(0);
            controller.pump();
            assertSame("repeat-one must keep lyrics even after the restore queue drains",
                    lines, controller.lyrics.peek());

            controller.playQueueIndex(1);
            assertTrue("a real track change still blanks immediately",
                    controller.lyrics.peek() == null || controller.lyrics.peek().isEmpty());
            assertNotSame(lines, controller.lyrics.peek());
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void switchingTracksPublishesTitleWithoutARenderPump() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("overlay-switch").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"artist\":\"a\","
                    + "\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"artist\":\"b\","
                    + "\"durationMs\":120000,\"filePath\":\"two.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.playQueueIndex(0);
            // No pump(): the overlay ticks these Properties while GL is paused.
            assertEquals("one", controller.title.peek());
            assertEquals("a", controller.artist.peek());

            List<dev.t1m3.qplayer.lyric.LyricLine> oldLines =
                    dev.t1m3.qplayer.lyric.LrcParser.parse(
                            "[00:01.00]上一首\n[00:05.00]旧行");
            controller.lyrics.set(oldLines);

            controller.playQueueIndex(1);
            assertEquals("two", controller.title.peek());
            assertEquals("b", controller.artist.peek());
            assertTrue("a real track change still blanks immediately",
                    controller.lyrics.peek() == null || controller.lyrics.peek().isEmpty());
            assertNotSame(oldLines, controller.lyrics.peek());
            assertEquals(Integer.valueOf(-1), controller.lyricIndex.peek());
            assertFalse(Boolean.TRUE.equals(controller.coverModeManual.peek()));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void switchingTracksClearsManualCoverModeWithoutARenderPump() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("cover-mode-switch").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"durationMs\":120000,\"filePath\":\"two.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.playQueueIndex(0);
            controller.setCoverMode(true);
            assertTrue(Boolean.TRUE.equals(controller.coverModeManual.peek()));

            controller.playQueueIndex(1);
            assertFalse("next track must leave cover-only mode so new lyrics can show",
                    Boolean.TRUE.equals(controller.coverModeManual.peek()));
            assertEquals("two", controller.title.peek());
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void playNextInsertsAfterTheCurrentTrack() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("play-next").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"durationMs\":120000,\"filePath\":\"two.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));
            Path extra = temporaryFolder.newFolder("lib").toPath().resolve("three.mp3");
            Files.write(extra, new byte[]{1, 2, 3, 4});

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.playQueueIndex(0);
            controller.pump();
            assertEquals(2, controller.queueTracks.peek().size());

            java.lang.reflect.Field library =
                    PlayerController.class.getDeclaredField("library");
            library.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<Track> lib = (java.util.List<Track>) library.get(controller);
            Track third = new Track();
            third.source = Track.Source.LOCAL;
            third.title = "three";
            third.filePath = extra.toString();
            third.durationMs = 90_000L;
            lib.add(third);

            controller.playNextLocalFile(extra.toString());
            controller.pump();
            java.util.List<Track> after = controller.queueTracks.peek();
            assertEquals(3, after.size());
            assertEquals("one", after.get(0).title);
            assertEquals("three", after.get(1).title);
            assertEquals("two", after.get(2).title);
            assertEquals(0, playIndex(controller));
        } finally {
            if (controller != null) lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static dev.t1m3.qplayer.media.Playlist playlist(String id, String name) {
        dev.t1m3.qplayer.media.Playlist playlist = new dev.t1m3.qplayer.media.Playlist();
        playlist.id = id;
        playlist.name = name;
        return playlist;
    }

    private static void waitForPauseCalls(FakeAudioBackend backend, int target,
                                          long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (backend.pauseCalls < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }
}
