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
    public void independentPageOpensFromHomeAndFadesWithoutChangingLyrics() throws Exception {
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
                Item entry = view.findByObjectName("openTempera");
                assertNotNull(entry);
                assertFalse(settings.has("temperaEnabled"));
                assertFalse(player.temperaOpen.peek());
                assertFalse(page.wantsFrame(player));
                // The ordinary lyric page works independently, with no Tempera side effect.
                player.setLyricsOpen(true);
                player.lyricSlide.set(1.0);
                queue.flush();
                assertTrue(normal.visible.peek());
                assertFalse(page.wantsFrame(player));
                player.setLyricsOpen(false);
                player.lyricSlide.set(0.0);
                long clock = 1_000_000_000L;
                page.advanceFade(player, clock);
                for (int width : new int[]{1100, 320, 240, 800}) {
                    view.root().width.set(width);
                    view.root().height.set(720);
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                    queue.flush();
                    assertTrue(entry.visible.peek());
                    assertTrue(entry.width.peekFloat() > 0);
                    assertTrue(entry.width.peekFloat() <= entry.parent.peek().width.peekFloat());
                    click(view, entry);
                    assertTrue(player.temperaOpen.peek());
                    assertFalse(player.lyricsOpen.peek());
                    page.advanceFade(player, clock += 125_000_000L);
                    assertEquals(0.5f, page.opacity(), 0.001f);
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                    assertEquals(0f, chrome.y.peekFloat(), 0.001f);
                    page.advanceFade(player, clock += 125_000_000L);
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                    queue.flush();
                    assertTrue(chrome.visible.peek());
                    assertFalse(normal.visible.peek());
                    assertEquals(0.0, player.lyricSlide.peek(), 0.001);
                    assertEquals(width, chrome.width.peekFloat(), 0.01f);
                    try (Surface surface = Surface.makeRasterN32Premul(width, 720)) {
                        page.render(surface.getCanvas(), player, 1f, width, 720,
                                System.nanoTime(), false);
                        view.renderer().renderSubtree(surface.getCanvas(), chrome, width, 720);
                    }
                    Item close = view.findByObjectName("temperaClose");
                    assertNotNull(close);
                    assertEquals("close", ((io.github.timer_err.qml4j.engine.binding.Property<?>)
                            close.getClass().getField("icon").get(close)).peek());
                    click(view, close);
                    assertFalse("Close button at width " + width, player.temperaOpen.peek());
                    assertTrue(page.wantsFrame(player));
                    page.advanceFade(player, clock += 125_000_000L);
                    assertEquals(0.5f, page.opacity(), 0.001f);
                    page.advanceFade(player, clock += 125_000_000L);
                    assertFalse(page.wantsFrame(player));
                    queue.flush();
                }
                // Escape closes only Tempera, preserving the underlying lyric page.
                player.setLyricsOpen(true);
                player.lyricSlide.set(1.0);
                player.setTemperaOpen(true);
                player.pressBack();
                player.pump();
                queue.flush();
                assertFalse(player.temperaOpen.peek());
                assertTrue(player.lyricsOpen.peek());
                assertTrue(normal.visible.peek());
            } finally {
                queue.uninstall();
            }
            SettingsCore android = new SettingsCore();
            android.load(new JsonSettingsStore(), SettingsCatalog.ANDROID);
            assertFalse(android.has("temperaWholeLine"));
            assertEquals(6, android.categories().size());
        } finally {
            if (view != null) view.dispose();
            page.dispose();
            if (player != null) player.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }
    private static void click(QmlView view, Item target) {
        float x = target.width.peekFloat() / 2f;
        float y = target.height.peekFloat() / 2f;
        for (Item item = target; item != null; item = item.parent.peek()) {
            x += item.x.peekFloat();
            y += item.y.peekFloat();
        }
        view.dispatchPointerDown(x, y);
        view.dispatchPointerUp(x, y);
    }
}
