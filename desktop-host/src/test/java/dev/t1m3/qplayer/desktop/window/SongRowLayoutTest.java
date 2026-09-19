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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Row geometry that has to be computed rather than declared.
 *
 * <p>qml4j accepts {@code Text.verticalAlignment} but it does not affect
 * painting, so any label that reserves more height than its own line has to
 * place itself. That is easy to get wrong and invisible to the compiler, hence
 * a measurement here.
 */
public class SongRowLayoutTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void theSourceBadgeLabelIsVerticallyCentredInItsPill() throws Exception {
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
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance());
            view.load("import QtQuick\nimport \"components\"\n"
                    + "Item { width: 900; height: 64\n"
                    + "  SongRow { width: 900; height: 64\n"
                    + "    rowTitle: \"song\"; rowArtist: \"artist\"; tag: \"QQ音乐\" }\n"
                    + "}");
            settle(view);

            Item pill = view.findByObjectName("songSourceBadge");
            Item label = view.findByObjectName("songSourceBadgeLabel");
            assertNotNull("the source badge must render", pill);
            assertNotNull(label);

            float pillHeight = pill.height.peekFloat();
            float lineHeight = label.implicitHeight.peekFloat();
            assertTrue("the label must actually measure a line", lineHeight > 0f);
            assertTrue("the pill has to be taller than the line for this to matter",
                    pillHeight > lineHeight);

            // The label's own centre against the pill's centre. Half a pixel of
            // slack for the odd/even rounding; anything more is a visible offset.
            float labelCentre = label.y.peekFloat() + lineHeight / 2f;
            float pillCentre = pillHeight / 2f;
            assertTrue("source badge label is off-centre by "
                            + Math.abs(labelCentre - pillCentre) + "px (label y="
                            + label.y.peekFloat() + ", line=" + lineHeight
                            + ", pill=" + pillHeight + ")",
                    Math.abs(labelCentre - pillCentre) <= 0.5f);
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

    /** The grip and the remove button share the right edge; overlapping them would
     *  make one of the two unhittable. */
    @Test
    public void theReorderGripDoesNotSitOnTheRemoveButton() throws Exception {
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
            view.load("import QtQuick\nimport \"components\"\n"
                    + "Item { width: 900; height: 64\n"
                    + "  SongRow { width: 900; height: 64\n"
                    + "    rowTitle: \"song\"; rowArtist: \"artist\"\n"
                    + "    removable: true; reorderable: true }\n"
                    + "}");
            settle(view);

            Item grip = view.findByObjectName("songRowDragHandle");
            Item remove = view.findByObjectName("queueRemoveButton");
            assertNotNull("a reorderable row must offer a grip", grip);
            assertNotNull(remove);

            float gripRight = grip.x.peekFloat() + grip.width.peekFloat();
            assertTrue("the grip must sit left of the remove button, not under it: "
                            + "grip ends at " + gripRight + ", remove starts at "
                            + remove.x.peekFloat(),
                    gripRight <= remove.x.peekFloat());
            assertTrue("and stay inside the row",
                    grip.x.peekFloat() >= 0f && gripRight <= 900f);
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

    /**
     * Dragging against an edge has to scroll the list, or a row can never be
     * moved further than one screenful. The geometry is pure arithmetic, so it
     * is checked directly rather than by simulating a gesture.
     */
    @Test
    public void draggingIntoAnEdgeScrollsTheListFasterTheDeeperItGoes() throws Exception {
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
            // Probe items carry the results out: qml4j exposes no reader for a
            // QML-declared property, but Item.x is a real field and takes the
            // negative values an upward scroll produces.
            view.load("import QtQuick\nimport \"components\"\n"
                    + "Item { width: 900; height: 400\n"
                    + "  VirtualSongList { id: list; width: 900; height: 400; isLocal: true\n"
                    + "    reorderable: true\n"
                    + "    list: [{title: \"a\", artist: \"x\"}, {title: \"b\", artist: \"y\"}] }\n"
                    + "  Item { id: midProbe; objectName: \"midProbe\" }\n"
                    + "  Item { id: nearTopProbe; objectName: \"nearTopProbe\" }\n"
                    + "  Item { id: farTopProbe; objectName: \"farTopProbe\" }\n"
                    + "  Item { id: bottomProbe; objectName: \"bottomProbe\" }\n"
                    + "  Component.onCompleted: {\n"
                    + "    list._updateAutoScroll(200); midProbe.x = list._autoScrollStep\n"
                    + "    list._updateAutoScroll(60);  nearTopProbe.x = list._autoScrollStep\n"
                    + "    list._updateAutoScroll(2);   farTopProbe.x = list._autoScrollStep\n"
                    + "    list._updateAutoScroll(398); bottomProbe.x = list._autoScrollStep\n"
                    + "  }\n"
                    + "}");
            settle(view);

            float middle = probeX(view, "midProbe");
            float nearTop = probeX(view, "nearTopProbe");
            float farTop = probeX(view, "farTopProbe");
            float bottom = probeX(view, "bottomProbe");

            assertEquals("the middle of the list must not scroll", 0f, middle, 0.001f);
            assertTrue("dragging near the top scrolls up, got " + nearTop, nearTop < 0f);
            assertTrue("dragging near the bottom scrolls down, got " + bottom, bottom > 0f);
            assertTrue("deeper into the edge must be faster: " + farTop + " vs " + nearTop,
                    Math.abs(farTop) > Math.abs(nearTop));
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

    private static float probeX(QmlView view, String objectName) {
        Item probe = view.findByObjectName(objectName);
        assertNotNull("missing probe " + objectName, probe);
        return probe.x.peekFloat();
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
