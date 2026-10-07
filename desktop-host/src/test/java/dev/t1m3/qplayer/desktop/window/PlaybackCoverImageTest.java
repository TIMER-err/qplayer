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

public class PlaybackCoverImageTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void originalStyleFillsTheBoxAndHidesTheVinyl() throws Exception {
        withCover(0, (view, settings) -> {
            Item disc = view.findByObjectName("playbackCoverDisc");
            Item vinyl = view.findByObjectName("playbackCoverVinyl");
            Item art = view.findByObjectName("playbackCoverArt");
            Item spindle = view.findByObjectName("playbackCoverSpindle");
            assertNotNull(disc);
            assertNotNull(vinyl);
            assertNotNull(art);
            assertNotNull(spindle);
            assertFalse(Boolean.TRUE.equals(vinyl.visible.peek()));
            assertFalse(Boolean.TRUE.equals(spindle.visible.peek()));
            assertEquals(200f, art.width.peekFloat(), 0.5f);
            assertEquals(200f, art.height.peekFloat(), 0.5f);
        });
    }

    @Test
    public void vinylStylePutsACircularLabelOnALargerDisc() throws Exception {
        withCover(1, (view, settings) -> {
            Item vinyl = view.findByObjectName("playbackCoverVinyl");
            Item art = view.findByObjectName("playbackCoverArt");
            Item spindle = view.findByObjectName("playbackCoverSpindle");
            assertNotNull(vinyl);
            assertTrue(Boolean.TRUE.equals(vinyl.visible.peek()));
            assertTrue(Boolean.TRUE.equals(spindle.visible.peek()));
            assertEquals(200f, vinyl.width.peekFloat(), 0.5f);
            assertEquals(200f, vinyl.height.peekFloat(), 0.5f);
            float artSize = art.width.peekFloat();
            assertTrue("label must sit inside the disc, got " + artSize,
                    artSize > 100f && artSize < 160f);
            assertEquals(artSize, art.height.peekFloat(), 0.5f);
            assertEquals(artSize / 2f, propertyFloat(art, "radius"), 0.5f);
            assertTrue("spindle must be smaller than the label",
                    spindle.width.peekFloat() < artSize * 0.2f);
        });
    }

    private interface Check {
        void run(QmlView view, SettingsCore settings) throws Exception;
    }

    private void withCover(int style, Check check) throws Exception {
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
            settings.setValue(SettingsCatalog.COVER_STYLE_KEY, style);
            view = QmlView.withStockTypes(new QmlEngine())
                    .resources(new ClasspathResourceLoader())
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance());
            view.load("import QtQuick\nimport \"components\"\n"
                    + "PlaybackCoverImage { objectName: \"cover\"; width: 200; height: 200\n"
                    + "  playing: true }");
            settle(view);
            check.run(view, settings);
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
    private static float propertyFloat(Item item, String name) throws Exception {
        Object value = ((io.github.timer_err.qml4j.engine.binding.Property<Object>)
                item.getClass().getField(name).get(item)).peek();
        if (value instanceof Number) return ((Number) value).floatValue();
        throw new AssertionError(name + " peeked as " + value);
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
