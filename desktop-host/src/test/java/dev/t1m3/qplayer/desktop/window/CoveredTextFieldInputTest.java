package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.WindowChromeStub;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * qml4j hit-tests TextInput before overlay MouseAreas, so a search field still
 * instantiated under the lyric page would steal taps and pop the IME. Covered
 * fields must drop out of that hit test (TextInput.visible = false).
 */
public class CoveredTextFieldInputTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void lyricsPageHidesTheSearchTextInputUnderneath() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        QmlView view = null;
        PlayerController player = null;
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
            player.sourceSetupRequired.set(false);
            player.sourceSetupPending.set(false);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance())
                    .context("hostWindow", new WindowChromeStub());
            view.load(new String(resources.load("Main.qml"), StandardCharsets.UTF_8));

            DirtyQueue queue = view.dirtyQueue();
            queue.install();
            try {
                view.root().width.set(400);
                view.root().height.set(800);
                property(view.root(), "searchLoaded").set(true);
                property(view.root(), "page").set(1);
                settle(view, queue);

                Item input = view.findByObjectName("miuixTextFieldInput");
                assertNotNull("search TextInput should exist on the search tab", input);
                assertTrue("precondition: the search field is hittable",
                        Boolean.TRUE.equals(input.visible.peek()));

                player.lyricsOpen.set(true);
                player.lyricSlide.set(1.0);
                settle(view, queue);

                assertFalse("a TextInput under the lyric page must not remain hittable",
                        Boolean.TRUE.equals(input.visible.peek()));

                float cx = centreX(input);
                float cy = centreY(input);
                view.dispatchPointerDown(cx, cy);
                view.dispatchPointerUp(cx, cy);
                queue.flush();
                Object focus = property(input, "activeFocus").peek();
                assertFalse("tapping the buried search field must not focus it",
                        Boolean.TRUE.equals(focus));
            } finally {
                queue.uninstall();
            }
        } finally {
            if (view != null) view.dispose();
            if (player != null) player.shutdownAsync().join();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Property<T> property(Item item, String name) throws Exception {
        Field field = item.getClass().getField(name);
        return (Property<T>) field.get(item);
    }

    private static float centreX(Item item) {
        float x = item.width.peekFloat() / 2f;
        for (Item i = item; i != null; i = i.parent.peek()) x += i.x.peekFloat();
        return x;
    }

    private static float centreY(Item item) {
        float y = item.height.peekFloat() / 2f;
        for (Item i = item; i != null; i = i.parent.peek()) y += i.y.peekFloat();
        return y;
    }

    private static void settle(QmlView view, DirtyQueue queue) {
        for (int i = 0; i < 4; i++) {
            queue.flush();
            view.renderer().layoutOnly(view.root());
        }
        queue.flush();
    }
}
