package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.WindowChromeStub;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.lyric.LrcParser;
import dev.t1m3.qplayer.qml.BundledQml;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import io.github.humbleui.skija.Surface;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

public class LyricNotificationInputTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void dismissingAToastDoesNotSeekOrStartALyricDrag() throws Exception {
        String oldBase = AppDirs.base(), oldCache = AppDirs.cacheBase();
        PlayerController player = null;
        QmlView view = null;
        DesktopWindow window = null;
        try (BundledQml bundle = new BundledQml("Main.qml")) {
            Path profile = temporary.newFolder().toPath();
            AppDirs.setBase(profile.toString());
            AppDirs.setCacheBase(profile.resolve("cache").toString());
            AudioBackend audio = (AudioBackend) Proxy.newProxyInstance(
                    AudioBackend.class.getClassLoader(), new Class<?>[]{AudioBackend.class},
                    (proxy, method, args) -> {
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                    });
            player = new PlayerController(audio, track -> { });
            player.sourceSetupRequired.set(false);
            player.sourceSetupPending.set(false);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = bundle.configure(QmlView.withStockTypes(new QmlEngine(bundle)), resources);
            view.context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance()).context("hostWindow", new WindowChromeStub());
            DesktopWindow.loadFonts(view, resources);
            bundle.load(view);
            view.dirtyQueue().install();
            window = new DesktopWindow(resources, player, settings);
            set(window, "view", view);
            set(window, "fbW", 1144);
            set(window, "fbH", 822);
            view.root().width.set(1144);
            view.root().height.set(822);
            player.lyrics.set(LrcParser.parse("[00:00]One\n[00:05]Two\n[00:10]Three\n[00:15]Four"));
            player.lyricsCoverOnly.set(false);
            player.lyricsOpen.set(true);
            player.lyricSlide.set(1.0);
            set(window.compositor(), "lyricSlide", 1f);
            settle(view);
            try (Surface surface = Surface.makeRasterN32Premul(1144, 822)) {
                window.compositor().composite(surface.getCanvas(), view.renderer(), view,
                        player, settings, null, 1f, 1144, 822);
            }
            player.toast.set("Dismiss me");
            advance(view, 300);
            Item dismiss = find(view.findByObjectName("lyricChrome"), "notificationDismiss");
            assertNotNull(dismiss);
            Item slot = dismiss.parent.peek().parent.peek();
            assertTrue(slot.isVisible());
            Map<String, Object> centre = view.root().mapFromItem(dismiss, 18, 18);
            float x = ((Number) centre.get("x")).floatValue();
            float y = ((Number) centre.get("y")).floatValue();
            assertTrue("control: nearby bare lyrics remain scrollable",
                    window.compositor().lyricsScrollable(x, y - 100, 1144, 822, 0));
            InputBridge input = new InputBridge(window);
            pointer(input, "onPress", x, y);
            pointer(input, "onMove", x + 1, y + 1);
            pointer(input, "onRelease", x + 1, y + 1);
            advance(view, 200);
            assertFalse("the visible notification must actually close", slot.isVisible());
            assertTrue("dismissed notifications must not block the lyric area",
                    window.compositor().lyricsScrollable(x, y, 1144, 822, 0));
        } finally {
            if (view != null) { view.dirtyQueue().uninstall(); view.dispose(); }
            if (window != null) window.compositor().dispose();
            if (player != null) player.shutdownAsync().join();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Item find(Item item, String name) {
        if (name.equals(item.objectName.peek())) return item;
        for (Item child : item.children) {
            Item result = find(child, name);
            if (result != null) return result;
        }
        return null;
    }

    private static void pointer(InputBridge input, String name, float x, float y) throws Exception {
        Method method = InputBridge.class.getDeclaredMethod(name, float.class, float.class);
        method.setAccessible(true);
        method.invoke(input, x, y);
    }

    private static void settle(QmlView view) {
        for (int i = 0; i < 4; i++) {
            view.dirtyQueue().flush();
            view.renderer().layoutOnly(view.root());
        }
    }

    private static void advance(QmlView view, int millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        do {
            view.tickAnimations(System.nanoTime());
            settle(view);
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
    }
}
