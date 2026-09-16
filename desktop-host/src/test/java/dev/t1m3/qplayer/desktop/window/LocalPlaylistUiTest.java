package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.WindowChromeStub;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.playlist.PlaylistTransfer;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The local-playlist UI, exercised through the same bridge QML uses.
 *
 * <p>qml4j resolves QML at runtime, so a page that does not parse only fails when
 * it is first built — these tests load the new pages for real rather than trusting
 * that they compile (nothing compiles them).
 */
public class LocalPlaylistUiTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** Everything a page needs to exist: a controller, settings, and a view. */
    private static final class Harness implements AutoCloseable {
        final PlayerController player;
        final SettingsCore settings;
        final ClasspathResourceLoader resources = new ClasspathResourceLoader();
        QmlView view;
        private final String oldBase;
        private final String oldCache;

        Harness(Path base) {
            oldBase = AppDirs.base();
            oldCache = AppDirs.cacheBase();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            AudioBackend backend = (AudioBackend) Proxy.newProxyInstance(
                    AudioBackend.class.getClassLoader(), new Class<?>[]{AudioBackend.class},
                    (proxy, method, args) -> {
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                    });
            player = new PlayerController(backend, track -> { });
            player.sourceSetupRequired.set(false);
            player.sourceSetupPending.set(false);
            settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
        }

        QmlView load(String qml) {
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance())
                    .context("hostWindow", new WindowChromeStub());
            view.load(qml);
            settle(view);
            return view;
        }

        @Override public void close() {
            if (view != null) {
                try { view.dispose(); } catch (Throwable ignored) { }
            }
            try { player.shutdown(); } catch (Throwable ignored) { }
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            view.root().width.set(1134);
            view.root().height.set(806);
            for (int i = 0; i < 3; i++) {
                queue.flush();
                view.renderer().layoutOnly(view.root());
            }
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }

    @Test
    public void theLocalPlaylistPageBuilds() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            harness.player.createLocalPlaylist("mixed");
            String id = firstPlaylistId(harness.player);
            harness.player.openLocalPlaylist(id);

            QmlView view = harness.load("import QtQuick\nimport \"pages\"\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  LocalPlaylistPage { objectName: \"localPlaylistPage\";"
                    + " anchors.fill: parent }\n"
                    + "}");
            assertNotNull(view.findByObjectName("localPlaylistPage"));
            assertEquals("mixed", harness.player.localPlaylistTitle.peek());
        }
    }

    /**
     * The followed-sources list only instantiates its delegate when there is a
     * subscription to draw, so the page building with none proves nothing about
     * it. Following a source is synchronous (the fetch that follows is not, and
     * is expected to fail here with no plugin installed).
     */
    @Test
    public void theFollowedSourcesListBuilds() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            harness.player.createLocalPlaylist("mixed");
            String id = firstPlaylistId(harness.player);
            harness.player.followSourcePlaylist(id, "netease:playlist:7");
            harness.player.openLocalPlaylist(id);
            assertEquals(1, harness.player.localPlaylistSubscriptions.peek().size());

            QmlView view = harness.load("import QtQuick\nimport \"pages\"\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  LocalPlaylistPage { id: page; objectName: \"localPlaylistPage\";"
                    + " anchors.fill: parent\n"
                    + "    Component.onCompleted: page.showSources = true }\n"
                    + "}");
            assertNotNull(view.findByObjectName("localPlaylistPage"));
            assertNotNull("the sync button appears once something is followed",
                    view.findByObjectName("localPlaylistSyncButton"));
        }
    }

    @Test
    public void theLibraryPageBuildsWithItsLocalTab() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            harness.player.createLocalPlaylist("mixed");
            QmlView view = harness.load("import QtQuick\nimport \"pages\"\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  LibraryPage { objectName: \"libraryPage\"; anchors.fill: parent }\n"
                    + "}");
            assertNotNull(view.findByObjectName("libraryPage"));
            assertNotNull("the local/online split must render",
                    view.findByObjectName("libraryTabs"));
        }
    }

    /** The queue page lost its second tab when the custom list became a playlist. */
    @Test
    public void theQueuePageBuilds() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            QmlView view = harness.load("import QtQuick\nimport \"pages\"\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  QueuePage { objectName: \"queuePage\"; anchors.fill: parent }\n"
                    + "}");
            assertNotNull(view.findByObjectName("queuePage"));
        }
    }

    /** The submenus are built in JS at open time, so a broken helper is invisible
     *  until a row is long-pressed. Build them directly instead. */
    @Test
    public void theRowMenuOffersEveryLocalPlaylist() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            harness.player.createLocalPlaylist("first");
            harness.player.createLocalPlaylist("second");

            // qml4j exposes no reader for a QML-declared property, so each result
            // is parked on a probe Item's width, which is a real Item field.
            QmlView view = harness.load("import QtQuick\nimport \"components\"\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  SongContextMenu { id: menu; objectName: \"rowMenu\" }\n"
                    + "  Item { objectName: \"mediaProbe\""
                    + " width: menu._localSubItems(\"media\", \"netease:song:1\").length }\n"
                    + "  Item { objectName: \"songProbe\""
                    + " width: menu._localSubItems(\"song\", 12).length }\n"
                    + "  Item { objectName: \"localProbe\""
                    + " width: menu._localSubItems(\"local\", \"/tmp/a.flac\").length }\n"
                    + "}");
            // Two playlists, a separator, and the "new playlist" entry.
            assertEquals(4, probeWidth(view, "mediaProbe"));
            assertEquals(4, probeWidth(view, "songProbe"));
            assertEquals(4, probeWidth(view, "localProbe"));
        }
    }

    @Test
    public void thePlaylistCardMenuOffersFollowingIntoALocalPlaylist() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            harness.player.createLocalPlaylist("mixed");
            QmlView view = harness.load("import QtQuick\nimport \"components\"\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  PlaylistContextMenu { id: menu; objectName: \"cardMenu\";"
                    + " playlistId: \"netease:playlist:7\" }\n"
                    + "  Component.onCompleted: menu.rebuild()\n"
                    + "  Item { objectName: \"countProbe\""
                    + " width: menu._followSubItems(\"netease:playlist:7\").length }\n"
                    + "  Item { objectName: \"entryProbe\"; width: {\n"
                    + "    var found = 0\n"
                    + "    for (var i = 0; i < menu.model.length; i++)\n"
                    + "      if (menu.model[i].subItems) found = 1\n"
                    + "    return found\n"
                    + "  } }\n"
                    + "}");
            // One playlist, a separator, and "new playlist".
            assertEquals(3, probeWidth(view, "countProbe"));
            assertEquals("the card menu must expose the follow submenu",
                    1, probeWidth(view, "entryProbe"));
        }
    }

    /** A local playlist must never offer to follow itself. */
    @Test
    public void aLocalPlaylistCardDoesNotOfferFollowing() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            harness.player.createLocalPlaylist("mixed");
            String id = firstPlaylistId(harness.player);
            QmlView view = harness.load("import QtQuick\nimport \"components\"\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  PlaylistContextMenu { id: menu; objectName: \"cardMenu\";"
                    + " playlistId: \"" + id + "\" }\n"
                    + "  Component.onCompleted: menu.rebuild()\n"
                    + "  Item { objectName: \"entryProbe\"; width: {\n"
                    + "    var found = 0\n"
                    + "    for (var i = 0; i < menu.model.length; i++)\n"
                    + "      if (menu.model[i].subItems) found = 1\n"
                    + "    return found\n"
                    + "  } }\n"
                    + "}");
            assertEquals("a local playlist must not offer to follow itself",
                    0, probeWidth(view, "entryProbe"));
        }
    }

    // ---- end to end through the bridge -------------------------------------

    @Test
    public void exportThenImportReproducesThePlaylist() throws Exception {
        Path base = temporary.newFolder().toPath();
        Path target = base.resolve("exported" + PlaylistTransfer.FILE_SUFFIX);
        try (Harness harness = new Harness(base)) {
            PlayerController player = harness.player;
            player.createLocalPlaylist("mixed");
            String id = firstPlaylistId(player);

            // Seed a local file through the same path the row menu uses.
            dev.t1m3.qplayer.model.Track local = new dev.t1m3.qplayer.model.Track();
            local.source = dev.t1m3.qplayer.model.Track.Source.LOCAL;
            local.filePath = base.resolve("song.flac").toString();
            local.title = "a local song";
            local.artist = "artist";
            // scanTracks posts to the render thread; nothing pumps it here.
            player.scanTracks(java.util.Collections.singletonList(local));
            player.pump();
            player.addLocalFileToLocalPlaylist(id, local.filePath);
            assertEquals(1, player.localPlaylists.peek().get(0).trackCount);

            final AtomicReference<String> savedTo = new AtomicReference<>();
            player.setPlaylistFileBridge(new PlayerController.PlaylistFileBridge() {
                @Override public void open(Consumer<String> onPicked) {
                    onPicked.accept(target.toString());
                }

                @Override public void save(String suggestedFileName, Consumer<String> onPicked) {
                    savedTo.set(suggestedFileName);
                    onPicked.accept(target.toString());
                }
            });

            player.requestLocalPlaylistExport(id);
            waitFor(player, () -> Files.isRegularFile(target));
            assertEquals("the dialog is seeded from the playlist name",
                    PlaylistTransfer.suggestedFileName("mixed"), savedTo.get());

            String written = StorageFiles.readUtf8(target);
            assertTrue("the export records the song's source",
                    written.contains("\"provider\": \"local\""));
            assertTrue("and its metadata", written.contains("a local song"));

            player.requestLocalPlaylistImport();
            waitFor(player, () -> player.localPlaylists.peek().size() == 2);

            List<dev.t1m3.qplayer.media.Playlist> cards = player.localPlaylists.peek();
            assertEquals("importing never overwrites the playlist it came from",
                    2, cards.size());
            assertEquals(1, cards.get(1).trackCount);
            assertTrue("the copy is renamed so the two are distinguishable",
                    !cards.get(0).name.equals(cards.get(1).name));
        }
    }

    @Test
    public void addingTheSameSongTwiceKeepsOneCopy() throws Exception {
        try (Harness harness = new Harness(temporary.newFolder().toPath())) {
            PlayerController player = harness.player;
            player.createLocalPlaylist("mixed");
            String id = firstPlaylistId(player);

            dev.t1m3.qplayer.model.Track local = new dev.t1m3.qplayer.model.Track();
            local.source = dev.t1m3.qplayer.model.Track.Source.LOCAL;
            local.filePath = "/music/song.flac";
            local.title = "song";
            player.scanTracks(java.util.Collections.singletonList(local));
            player.pump();

            player.addLocalFileToLocalPlaylist(id, local.filePath);
            player.addLocalFileToLocalPlaylist(id, local.filePath);
            assertEquals(1, player.localPlaylists.peek().get(0).trackCount);
            assertTrue(player.isLocalFileInLocalPlaylist(id, local.filePath));

            player.removeLocalFileFromLocalPlaylist(id, local.filePath);
            assertEquals(0, player.localPlaylists.peek().get(0).trackCount);
        }
    }

    /** The previous single custom playlist has to survive the upgrade. */
    @Test
    public void thePreviousCustomPlaylistIsMigratedOnFirstRun() throws Exception {
        Path base = temporary.newFolder().toPath();
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        try {
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            StorageFiles.writeUtf8Atomic(AppDirs.stateFile("custom-playlist.json"),
                    "{\"schemaVersion\":2,\"tracks\":["
                            + "{\"source\":\"NETEASE\",\"mediaId\":\"netease:song:1\","
                            + "\"title\":\"kept\",\"artist\":\"artist\",\"album\":\"\","
                            + "\"coverUrl\":\"\",\"durationMs\":1000}]}");
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }

        try (Harness harness = new Harness(base)) {
            List<dev.t1m3.qplayer.media.Playlist> cards = harness.player.localPlaylists.peek();
            assertEquals("the old list becomes exactly one playlist", 1, cards.size());
            assertEquals(1, cards.get(0).trackCount);
            assertTrue("and the file it came from is left in place as a rollback path",
                    Files.isRegularFile(base.resolve("state").resolve("custom-playlist.json"))
                            || Files.exists(AppDirs.stateFile("custom-playlist.json")));
        }
    }

    /** Read a probe Item's width, which the QML above uses to publish a value
     *  that has nowhere else to go. */
    private static int probeWidth(QmlView view, String objectName) {
        io.github.timer_err.qml4j.render.items.core.Item probe =
                view.findByObjectName(objectName);
        assertNotNull("missing probe " + objectName, probe);
        return Math.round(probe.width.peekFloat());
    }

    private static String firstPlaylistId(PlayerController player) {
        List<dev.t1m3.qplayer.media.Playlist> cards = player.localPlaylists.peek();
        assertTrue("expected at least one local playlist", !cards.isEmpty());
        return cards.get(0).id;
    }

    /**
     * The controller does its file I/O on a worker and then publishes the result
     * back through its render-thread queue. With no render loop running here,
     * polling also has to drain that queue, or the publish never happens.
     */
    private static void waitFor(PlayerController player,
                                java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            player.pump();
            if (condition.getAsBoolean()) return;
            Thread.sleep(25L);
        }
        throw new AssertionError("timed out waiting for the background write");
    }
}
