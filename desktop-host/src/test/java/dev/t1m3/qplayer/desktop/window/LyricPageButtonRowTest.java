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
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The lyric page's top-right button row, inside the whole Main.qml scene.
 *
 * <p>LyricOverlay on its own is not enough to trust a click here: the page is an
 * overlay over the rest of the app, so every item declared around it is a
 * candidate for swallowing a press that lands in the same place. The row's
 * newest button was reported dead on the real window while the two beside it
 * worked, which is a shape only the full scene can reproduce.
 */
public class LyricPageButtonRowTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void everyButtonInTheRowReceivesItsOwnClick() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
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
            PlayerController player = new PlayerController(backend, track -> { });
            player.sourceSetupRequired.set(false);
            player.sourceSetupPending.set(false);
            // The lyric page, fully open, on a track that has lyrics.
            player.lyricsCoverOnly.set(false);
            player.lyricsOpen.set(true);
            player.lyricSlide.set(1.0);
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
                // The real window's client area, so the row lands where it does
                // on screen rather than in some other layout branch.
                view.root().width.set(894);
                view.root().height.set(613);
                settle(view, queue);

                Item toggle = view.findByObjectName("lyricFullWidthBtn");
                assertNotNull("the full-width toggle must exist in the scene", toggle);
                assertTrue("precondition: the toggle is on screen",
                        Boolean.TRUE.equals(toggle.visible.peek()));

                // Control: the neighbour that works on the real window. If this
                // one fails too, the harness is wrong, not the button.
                boolean coverBefore = Boolean.TRUE.equals(player.coverModeManual.peek());
                click(view, queue, view.findByObjectName("lyricCoverModeBtn"));
                assertTrue("control: the cover-mode button beside it must receive a click",
                        Boolean.TRUE.equals(player.coverModeManual.peek()) != coverBefore);
                player.coverModeManual.set(coverBefore);
                settle(view, queue);

                assertTrue("precondition: the setting starts off",
                        !Boolean.TRUE.equals(settings.value("lyricFullWidth")));
                click(view, queue, toggle);
                assertTrue("the full-width toggle must receive a click at its own centre, "
                                + "like its neighbours do: centre=("
                                + centreX(toggle) + ", " + centreY(toggle) + ")",
                        Boolean.TRUE.equals(settings.value("lyricFullWidth")));
            } finally {
                queue.uninstall();
            }
        } finally {
            if (view != null) view.dispose();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static void click(QmlView view, DirtyQueue queue, Item item) {
        assertNotNull(item);
        float cx = centreX(item);
        float cy = centreY(item);
        view.dispatchPointerDown(cx, cy);
        view.dispatchPointerUp(cx, cy);
        queue.flush();
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
