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
            if (controller != null) controller.shutdown();
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

    /**
     * 媒体键的暂停/恢复与手动 {@code toggle()} 走同一条路：<strong>立刻</strong>落到后端。
     *
     * <p>这条测试原来叫 {@code mediaSessionControlsUseTheSameFadeAsManualToggle}，
     * 断言的是「暂停先花 900 ms 把音量渐变到 0、恢复再从 0 渐变回来」。那套**客户端音量淡变**
     * 已经删掉（见 {@code SettingsCatalog} 里「淡入淡出」那一行的说明）：它原本是引擎还没有
     * 接缝处理时拿音量去补接缝的办法，而现在接缝由引擎在输出流里做，客户端那条会把整条混音
     * （含正在进场的下一首）一起拉到 0。所以这里改成钉住**现在的**行为：
     * 暂停/恢复都是即时的，而且**不许再去动主增益**（音量是用户的）。
     */
    @Test
    public void mediaControlsPauseAndResumeImmediatelyWithoutTouchingVolume() throws Exception {
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
            controller.setVolume(0.8f);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            // playAt() 起播时会先无条件 pause 一次（那时还没东西在放），所以基线在这里取。
            int pausesBeforeMediaCommand = backend.pauseCalls;
            controller.mediaPause();
            assertFalse(controller.isPlaying());
            assertEquals("媒体键暂停必须立刻落到后端，不能再等一段音量渐变",
                    pausesBeforeMediaCommand + 1, backend.pauseCalls);
            assertFalse(backend.playing);
            assertEquals("暂停不该去改主增益", 0.8f, backend.volume, 0.001f);

            int resumesBeforeMediaCommand = backend.resumeCalls;
            controller.mediaResume();
            assertTrue(controller.isPlaying());
            assertEquals(resumesBeforeMediaCommand + 1, backend.resumeCalls);
            assertTrue(backend.playing);
            assertEquals("恢复也不该改主增益", 0.8f, backend.volume, 0.001f);
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
            // 客户端音量淡变删掉之后，按暂停就是**立刻**暂停（后端真的停了），
            // 所以歌词时钟必须当场跟着停——不再有「还在响的那 900 ms 尾巴」。
            assertFalse("暂停必须立刻落到后端", backend.playing);
            assertFalse(controller.isLyricClockRunning());
            assertEquals(3210L, controller.lyricClockPosition());

            // 恢复之后从同一位置继续，没有追帧跳变。
            backend.position = 3500L;
            controller.toggle();
            assertTrue("恢复必须立刻落到后端", backend.playing);
            assertTrue(controller.isLyricClockRunning());
            assertEquals(3500L, controller.lyricClockPosition());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /**
     * 音量是用户的：一设下去就该落到后端，**不必等渲染帧**——窗口被隐藏/销毁时
     * {@code pump()} 不再跑，播放不该因此卡在某一帧上。
     *
     * <p>这条测试原来叫 {@code fadeFinishesWithoutRenderPumpAndPreservesUserVolume}，
     * 还多断言了一句「淡变过程写了不止一次音量」（{@code volumeWrites > 2}）。客户端音量
     * 淡变已经删掉（见 {@code SettingsCatalog} 里「淡入淡出」那一行的说明），所以现在只钉
     * 「设了就是设了」这一件事。
     */
    @Test
    public void volumeIsAppliedWithoutRenderPump() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("volume-no-pump").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setVolume(0.6f);
            controller.playQueueIndex(0);
            backend.fireStarted();

            // Deliberately never call controller.pump(): a hidden/destroyed window
            // must not strand playback at some stale gain.
            waitForVolume(backend, 0.6f, ASYNC_FADE_TIMEOUT_MS);
            assertEquals(0.6f, backend.volume, 0.02f);
            assertTrue("音量至少要被写下去一次", backend.volumeWrites >= 1);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    // 另有两条淡变测试随功能一起删掉了，留个说明免得以后当成「不小心丢了测试」：
    //
    //   replacingTrackCancelsOutgoingFadeCompletion —— 测的是「换曲时那条被取代的淡变
    //   完成回调不许迟到、把新的播放请求暂停掉」。那是一个**客户端淡变调度器**才有的竞态，
    //   调度器已随功能删除，这个竞态不复存在。
    //
    //   seekingAwayFromNaturalEndFadeRestoresFullGain —— 测的是「曲末自动淡出、中途 seek
    //   走要恢复满增益」。曲末那一下现在由引擎在输出流里做（无缝换曲 / 自动混音），客户端
    //   再叠一层淡出会与过渡互相打架（它会把整条混音、含正在进场的下一首一起拉到 0）。
    //
    // 删掉的是**功能本身**，不是「断言碍事」；功能如果回来，这两条必须一并回来。

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
            controller.playQueueIndex(0);
            controller.setPlayMode(1);
            controller.next();
            assertTrue(playIndex(controller) != 0);
            controller.prev();
            assertEquals("prev in shuffle must return the song next() just left",
                    0, playIndex(controller));
        } finally {
            if (controller != null) controller.shutdown();
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
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static int playIndex(PlayerController controller) throws Exception {
        java.lang.reflect.Field field = PlayerController.class.getDeclaredField("playIndex");
        field.setAccessible(true);
        return field.getInt(controller);
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
        /** 引擎报的「现在放的是哪一首」——控制器用它核对自动交接（见 AudioBackend）。 */
        volatile String currentSource = "";

        /** 控制器认下交接时发的那条确认（{@link AudioBackend#confirmHandoff}）。 */
        final java.util.List<String> handoffConfirms =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        @Override public void play(String source, long startMs) {
            playCalls++;
            currentSource = source;
            position = startMs;
            playing = true;
        }

        @Override public String currentSource() { return currentSource; }

        @Override public void confirmHandoff(String source) { handoffConfirms.add(source); }

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
            if (controller != null) controller.shutdown();
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
            if (controller != null) controller.shutdown();
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

            java.lang.reflect.Method drop = PlayerController.class.getDeclaredMethod(
                    "dropSourceUserData", String.class);
            drop.setAccessible(true);
            drop.invoke(controller, "netease");

            // Signing out of one source must not forget the other's hearts.
            assertEquals(1, controller.likedCount.peek().intValue());
        } finally {
            if (controller != null) controller.shutdown();
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
            if (controller != null) controller.shutdown();
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
