package dev.t1m3.qplayer.desktop.lyric.tempera;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.WindowChromeStub;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import io.github.humbleui.skija.Surface;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class TemperaIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void mainSceneCompilesAndUsesOnlyTheSelectedLyricControls() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        PlayerController player = null;
        QmlView view = null;
        TemperaHostPage page = new TemperaHostPage();
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
                    .context("i18n", I18n.instance()).context("hostWindow", new WindowChromeStub());
            view.load(new String(resources.load("Main.qml"), StandardCharsets.UTF_8));
            Item chrome = view.findByObjectName("temperaChrome");
            Item normal = view.findByObjectName("lyricChrome");
            assertNotNull(chrome);
            assertNotNull(normal);
            DirtyQueue queue = view.dirtyQueue();
            queue.install();
            try {
                for (int width : new int[]{1100, 320, 240, 800}) {
                    view.root().width.set(width);
                    view.root().height.set(720);
                    player.setLyricsOpen(true);
                    for (int i = 0; i < 45; i++) page.advanceSlide(player);
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                    queue.flush();
                    assertTrue(chrome.visible.peek());
                    assertFalse(normal.visible.peek());
                    assertEquals(width, chrome.width.peekFloat(), 0.01f);
                    assertTrue(page.wantsFrame(player, true));
                    try (Surface surface = Surface.makeRasterN32Premul(width, 720)) {
                        page.render(surface.getCanvas(), player, 1f, width, 720,
                                System.nanoTime(), false);
                        view.renderer().renderSubtree(surface.getCanvas(), chrome, width, 720);
                    }
                    // Close from the actual QML capsule; its anchor must update after resize.
                    view.dispatchPointerDown(width - 130f, 666f);
                    view.dispatchPointerUp(width - 130f, 666f);
                    assertFalse("Close button at width " + width, player.lyricsOpen.peek());
                    assertTrue(page.wantsFrame(player, true)); // finishes the closing transition
                    for (int i = 0; i < 45; i++) page.advanceSlide(player);
                    assertFalse(page.wantsFrame(player, true));
                }
                settings.setValue("temperaEnabled", false);
                player.lyricSlide.set(1.0);
                queue.flush();
                assertFalse(chrome.visible.peek());
                assertTrue(normal.visible.peek());
            } finally {
                queue.uninstall();
            }
            SettingsCore android = new SettingsCore();
            android.load(new JsonSettingsStore(), SettingsCatalog.ANDROID);
            assertFalse(android.has("temperaEnabled"));
            assertEquals(6, android.categories().size());
        } finally {
            if (view != null) view.dispose();
            page.dispose();
            if (player != null) player.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }
}
