package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Playlist-card geometry that qml4j will not catch at compile time: a card
 * first realized hidden (the floating copy of a drag) still has to have a
 * non-zero cover, and the title size has to follow {@code libraryCardSize}.
 */
public class PlaylistCardLayoutTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void aCardRevealedAfterCreationStillHasACoverBox() throws Exception {
        withView("import QtQuick\nimport \"components\"\n"
                        + "Item { id: host; width: 400; height: 400\n"
                        + "  property bool shown: false\n"
                        + "  Item { width: 400; height: 400; cachedLayout: true\n"
                        + "    PlaylistCard { objectName: \"floating\"; tile: 160\n"
                        + "      visible: host.shown; dragging: true\n"
                        + "      name: host.shown ? \"list\" : \"\" }\n"
                        + "  }\n"
                        + "  Component.onCompleted: host.shown = true\n"
                        + "}",
                view -> {
                    Item card = view.findByObjectName("floating");
                    assertNotNull(card);
                    assertEquals(160f, card.width.peekFloat(), 0.5f);
                    assertTrue("caption slot must add height beyond the cover",
                            card.height.peekFloat() > 160f);
                    Item cover = findIn(card, "playlistCardCover");
                    assertNotNull(cover);
                    assertTrue("cover box was " + cover.width.peekFloat()
                                    + "x" + cover.height.peekFloat(),
                            cover.width.peekFloat() > 100f
                                    && cover.height.peekFloat() > 100f);
                    Item placeholder = findIn(card, "coverPlaceholder");
                    assertNotNull(placeholder);
                    assertTrue("placeholder was " + placeholder.width.peekFloat()
                                    + "x" + placeholder.height.peekFloat(),
                            placeholder.width.peekFloat() > 100f
                                    && placeholder.height.peekFloat() > 100f);
                });
    }

    @Test
    public void titleSizeFollowsTheTile() throws Exception {
        withView("import QtQuick\nimport \"components\"\n"
                        + "Item { width: 900; height: 400\n"
                        + "  PlaylistCard { objectName: \"small\"; tile: 100; name: \"s\" }\n"
                        + "  PlaylistCard { objectName: \"large\"; x: 200; tile: 260; name: \"l\" }\n"
                        + "}",
                view -> {
                    Item smallTitle = findIn(view.findByObjectName("small"), "playlistCardTitle");
                    Item largeTitle = findIn(view.findByObjectName("large"), "playlistCardTitle");
                    assertNotNull(smallTitle);
                    assertNotNull(largeTitle);
                    float smallSize = propertyFloat(smallTitle, "fontSize");
                    float largeSize = propertyFloat(largeTitle, "fontSize");
                    assertTrue("small title " + smallSize + " should be below default 16",
                            smallSize < 16f);
                    assertTrue("large title " + largeSize + " should be above default 16",
                            largeSize > 16f);
                    assertTrue(largeSize > smallSize);
                });
    }

    @Test
    public void reorderPressDoesNotStealUntilLongPress() throws Exception {
        withView("import QtQuick\nimport \"components\"\n"
                        + "PlaylistCard { objectName: \"card\"; tile: 160; reorderable: true; name: \"a\" }",
                view -> {
                    Item drag = findIn(view.findByObjectName("card"), "playlistCardDragArea");
                    assertNotNull(drag);
                    assertFalse("short press must leave the Flickable free to scroll",
                            Boolean.TRUE.equals(property(drag, "preventStealing").peek()));
                });
    }

    private interface Check {
        void run(QmlView view) throws Exception;
    }

    private void withView(String qml, Check check) throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        PlayerController player = null;
        QmlView view = null;
        try {
            Path base = temporary.newFolder().toPath();
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
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            view = QmlView.withStockTypes(new QmlEngine())
                    .resources(new ClasspathResourceLoader())
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance());
            view.load(qml);
            settle(view);
            check.run(view);
        } finally {
            if (view != null) {
                try { view.dispose(); } catch (Throwable ignored) { }
            }
            if (player != null) {
                try { player.shutdown(); } catch (Throwable ignored) { }
            }
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    @SuppressWarnings("unchecked")
    private static io.github.timer_err.qml4j.engine.binding.Property<Object> property(Item item, String name)
            throws Exception {
        return (io.github.timer_err.qml4j.engine.binding.Property<Object>) item.getClass().getField(name).get(item);
    }

    private static float propertyFloat(Item item, String name) throws Exception {
        Object value = property(item, name).peek();
        if (value instanceof Number) return ((Number) value).floatValue();
        throw new AssertionError(name + " peeked as " + value);
    }

    private static Item findIn(Item root, String objectName) {
        if (root == null) return null;
        if (objectName.equals(root.objectName.peek())) return root;
        for (Item child : root.children) {
            Item hit = findIn(child, objectName);
            if (hit != null) return hit;
        }
        return null;
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            for (int i = 0; i < 3; i++) {
                queue.flush();
                view.renderer().layoutOnly(view.root());
            }
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }
}
