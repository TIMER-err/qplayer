package dev.t1m3.qplayer.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QueuePersistenceTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingAndEmptyDocumentsRestoreNoSelection() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("queue.json");
        QueuePersistence persistence = new QueuePersistence(file, Runnable::run);
        assertEmpty(persistence.load());
        writeUtf8(file, "{\"playIndex\":8,\"positionMs\":99,\"playMode\":2,\"tracks\":[]}");
        assertEmpty(persistence.load());
    }

    @Test
    public void snapshotDetachesMutableTracksAndDropsResolvedStreams() throws Exception {
        Track original = new Track();
        original.source = Track.Source.NETEASE;
        original.neteaseId = 123;
        original.title = "original";
        original.streamUrl = "https://secret.example/audio";
        original.streamHeaders.put("Authorization", "secret");
        original.streamExpiresAtMs = 9000;
        original.streamCacheable = false;
        original.trial = true;
        original.renditionId = "other:song:123";
        original.coverBytes = new byte[] { 1, 2 };
        original.embeddedLyricText = "transient tags";
        List<Track> input = new ArrayList<>(Collections.singletonList(original));
        QueuePersistence.Snapshot snapshot = new QueuePersistence.Snapshot(input, 0, 900, 2);
        assertNull("capture must not lazily mutate live identity", original.mediaId);
        original.title = "changed";
        original.neteaseId = 999;
        input.clear();
        Track first = snapshot.tracks().get(0);
        assertEquals("original", first.title);
        assertEquals("netease:song:123", first.canonicalId());
        assertNull(first.streamUrl);
        assertTrue(first.streamHeaders.isEmpty());
        assertEquals(0L, first.streamExpiresAtMs);
        assertFalse(first.trial);
        assertTrue(first.streamCacheable);
        assertNull(first.renditionId);
        assertNull(first.coverBytes);
        assertNull(first.embeddedLyricText);
        first.title = "accessor mutation";
        first.streamHeaders.put("mutable", "value");
        Track second = snapshot.tracks().get(0);
        assertNotSame(first, second);
        assertEquals("original", second.title);
        assertTrue(second.streamHeaders.isEmpty());
        assertEquals(0, snapshot.playIndex());
        assertEquals(900L, snapshot.positionMs());
        assertEquals(2, snapshot.playMode());

        Path file = temporaryFolder.getRoot().toPath().resolve("queue.json");
        QueuePersistence persistence = new QueuePersistence(file, Runnable::run);
        persistence.save(snapshot).join();
        JsonObject row = document(file).getAsJsonArray("tracks").get(0).getAsJsonObject();
        for (String key : Arrays.asList("streamUrl", "streamHeaders", "streamExpiresAtMs", "trial",
                "streamCacheable", "renditionId", "coverBytes", "embeddedLyricText")) {
            assertFalse(key, row.has(key));
        }
        assertEquals("original", persistence.load().tracks().get(0).title);
    }

    @Test
    public void localRoundTripRetainsAllLyricResourcesAndMetadata() throws Exception {
        Path folder = temporaryFolder.newFolder("round-trip").toPath();
        Track track = new Track();
        track.filePath = folder.resolve("audio.flac").toString();
        track.contentUri = "content://media/external/audio/media/12";
        track.title = "title\t\"\\\n\u0000中文";
        track.artist = "first,second";
        track.artistId = 15;
        track.artistMediaId = "provider:artist:first";
        track.artistIdsCsv = "15,16";
        track.artistNamesCsv = "first,second";
        track.album = "album";
        track.durationMs = 123456;
        track.coverLocalPath = folder.resolve("cover.jpg").toString();
        track.lyricFilePath = resource(folder, "external-main.lrc");
        track.translationFilePath = resource(folder, "external-translated.lrc");
        track.romajiFilePath = resource(folder, "external-romanized.lrc");
        QueuePersistence persistence = new QueuePersistence(folder.resolve("queue.json"), Runnable::run);
        persistence.save(new QueuePersistence.Snapshot(Collections.singletonList(track), 0, 42000, 1)).join();
        QueuePersistence.Snapshot loaded = persistence.load();
        Track restored = loaded.tracks().get(0);
        assertEquals(track.canonicalId(), restored.canonicalId());
        assertEquals(track.contentUri, restored.contentUri);
        assertEquals(track.filePath, restored.filePath);
        assertEquals(track.title, restored.title);
        assertEquals(track.artist, restored.artist);
        assertEquals(track.artistId, restored.artistId);
        assertEquals(track.artistMediaId, restored.artistMediaId);
        assertEquals(track.artistIdsCsv, restored.artistIdsCsv);
        assertEquals(track.artistNamesCsv, restored.artistNamesCsv);
        assertEquals(track.album, restored.album);
        assertEquals(track.durationMs, restored.durationMs);
        assertEquals(track.coverLocalPath, restored.coverLocalPath);
        assertEquals(track.lyricFilePath, restored.lyricFilePath);
        assertEquals(track.translationFilePath, restored.translationFilePath);
        assertEquals(track.romajiFilePath, restored.romajiFilePath);
        assertEquals(42000L, loaded.positionMs());
        assertEquals(1, loaded.playMode());
    }

    @Test
    public void legacyLocalRowsDiscoverReadableSidecarsInScannerPriorityOrder() throws Exception {
        Path folder = temporaryFolder.newFolder("legacy-local").toPath();
        Path audio = folder.resolve("song.flac");
        Files.createFile(audio);
        Files.createDirectory(folder.resolve("song.ttml"));
        String lyrics = resource(folder, "song.lys");
        resource(folder, "song.qrc");
        resource(folder, "song.lrc");
        String translation = resource(folder, "song.translation.lrc");
        String romaji = resource(folder, "song.romaji.lys");
        JsonObject row = new JsonObject();
        row.addProperty("source", "LOCAL");
        row.addProperty("filePath", audio.toString());
        Path file = folder.resolve("queue.json");
        writeUtf8(file, "{\"tracks\":[" + row + "]}");
        Track restored = new QueuePersistence(file, Runnable::run).load().tracks().get(0);
        assertEquals(lyrics, restored.lyricFilePath);
        assertEquals(translation, restored.translationFilePath);
        assertEquals(romaji, restored.romajiFilePath);
        assertFalse(restored.canonicalId().isEmpty());
    }

    @Test
    public void providerAndLegacyIdentitiesKeepTheirOriginalRoutingAndCoverRules() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("providers.json");
        writeUtf8(file, "{\"playIndex\":99,\"positionMs\":-5,\"playMode\":99,\"tracks\":["
                + "{\"neteaseId\":12,\"coverUrl\":\"https://p1.music.126.net/cover.jpg\"},"
                + "{\"source\":\"CUSTOM_API\",\"customId\":\"not-numeric\",\"coverUrl\":\"https://custom/art\"},"
                + "{\"source\":\"PLUGIN\",\"mediaId\":\"netease:song:34\",\"coverUrl\":\"https://plugin/art\"},"
                + "{\"source\":\"PLUGIN\",\"mediaId\":\"independent:song:abc\",\"coverUrl\":\"https://independent/art\"},"
                + "{\"source\":\"unknown-legacy-value\",\"neteaseId\":56}]}" );
        QueuePersistence persistence = new QueuePersistence(file, Runnable::run);
        QueuePersistence.Snapshot snapshot = persistence.load();
        List<Track> tracks = snapshot.tracks();
        assertEquals(4, snapshot.playIndex());
        assertEquals(0L, snapshot.positionMs());
        assertEquals(2, snapshot.playMode());
        assertEquals(Track.Source.NETEASE, tracks.get(0).source);
        assertEquals("netease:song:12", tracks.get(0).canonicalId());
        assertEquals(NeteaseClient.thumbUrl(tracks.get(0).coverUrl), tracks.get(0).coverThumbPath);
        assertEquals(Track.Source.CUSTOM_API, tracks.get(1).source);
        assertEquals("legacy-custom:song:not-numeric", tracks.get(1).canonicalId());
        assertEquals(tracks.get(1).coverUrl, tracks.get(1).coverThumbPath);
        assertEquals(Track.Source.PLUGIN, tracks.get(2).source);
        assertEquals("netease:song:34", tracks.get(2).canonicalId());
        assertEquals(34L, tracks.get(2).neteaseId);
        assertEquals(tracks.get(2).coverUrl, tracks.get(2).coverThumbPath);
        assertEquals(Track.Source.PLUGIN, tracks.get(3).source);
        assertEquals("independent:song:abc", tracks.get(3).canonicalId());
        assertEquals(tracks.get(3).coverUrl, tracks.get(3).coverThumbPath);
        assertEquals(Track.Source.NETEASE, tracks.get(4).source);
        assertEquals("netease:song:56", tracks.get(4).canonicalId());
        persistence.save(snapshot).join();
        assertEquals(Track.Source.PLUGIN, persistence.load().tracks().get(2).source);
    }

    @Test
    public void thumbnailFallbackAndCanonicalSourceMigrationMatchLegacyQueue() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("covers.json");
        Track track = new Track();
        track.filePath = "local.mp3";
        track.coverThumbPath = "cached-cover.jpg";
        QueuePersistence persistence = new QueuePersistence(file, Runnable::run);
        persistence.save(new QueuePersistence.Snapshot(Collections.singletonList(track), 0, 0, 0)).join();
        assertEquals("cached-cover.jpg", persistence.load().tracks().get(0).coverLocalPath);
        track.coverThumbPath = "https://remote/thumbnail";
        persistence.save(new QueuePersistence.Snapshot(Collections.singletonList(track), 0, 0, 0)).join();
        assertNull(persistence.load().tracks().get(0).coverLocalPath);
        writeUtf8(file, "{\"tracks\":[{\"source\":\"NETEASE\","
                + "\"mediaId\":\"independent:song:canonical\",\"neteaseId\":123,"
                + "\"streamUrl\":\"https://expired/audio\",\"streamHeaders\":{\"Authorization\":\"secret\"}}]}");
        Track migrated = persistence.load().tracks().get(0);
        assertEquals(Track.Source.PLUGIN, migrated.source);
        assertEquals("independent:song:canonical", migrated.canonicalId());
        assertNull(migrated.streamUrl);
        assertTrue(migrated.streamHeaders.isEmpty());
    }

    @Test
    public void writesStayOrderedAndCallerCancellationCannotDropAcceptedSave() throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("ordered.json");
        ManualExecutor executor = new ManualExecutor();
        QueuePersistence persistence = new QueuePersistence(file, executor);
        CompletableFuture<Void> first = persistence.save(snapshot("first"));
        CompletableFuture<Void> second = persistence.save(snapshot("second"));
        assertEquals(1, executor.tasks.size());
        first.cancel(false);
        executor.runNext();
        assertEquals("first", persistence.load().tracks().get(0).title);
        assertEquals(1, executor.tasks.size());
        executor.runNext();
        second.join();
        assertEquals("second", persistence.load().tracks().get(0).title);
        assertTrue(executor.tasks.isEmpty());
        assertFalse(Files.exists(file.resolveSibling("ordered.json.pending")));
    }

    @Test
    public void failedWriteIsReportedWithoutPoisoningLaterSaves() throws Exception {
        Path blocker = temporaryFolder.getRoot().toPath().resolve("blocked-parent");
        Files.createFile(blocker);
        QueuePersistence persistence = new QueuePersistence(blocker.resolve("queue.json"), Runnable::run);
        try {
            persistence.save(snapshot("cannot-write")).join();
            fail("Expected an IO failure");
        } catch (CompletionException expected) {
            assertTrue(expected.getCause() instanceof UncheckedIOException);
        }
        Files.delete(blocker);
        persistence.save(snapshot("recovered")).join();
        assertEquals("recovered", persistence.load().tracks().get(0).title);
    }

    private static QueuePersistence.Snapshot snapshot(String title) {
        Track track = new Track();
        track.filePath = "song.mp3";
        track.title = title;
        return new QueuePersistence.Snapshot(Collections.singletonList(track), 0, 0, 0);
    }

    private static String resource(Path folder, String name) throws Exception {
        Path file = folder.resolve(name);
        writeUtf8(file, "[00:00.00]lyric");
        return file.toString();
    }

    private static JsonObject document(Path file) throws Exception {
        return new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void writeUtf8(Path file, String content) throws Exception {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertEmpty(QueuePersistence.Snapshot snapshot) {
        assertTrue(snapshot.tracks().isEmpty());
        assertEquals(-1, snapshot.playIndex());
        assertEquals(0L, snapshot.positionMs());
        assertEquals(0, snapshot.playMode());
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }
}
