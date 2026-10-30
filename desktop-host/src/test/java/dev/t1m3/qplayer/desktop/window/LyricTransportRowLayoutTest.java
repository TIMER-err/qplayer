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
 * Pause stays on the row centre; play-mode / like hug prev/next instead of
 * sitting on the page edges. Narrow widths shrink rather than overflow.
 */
public class LyricTransportRowLayoutTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void pauseStaysCentredAndSideButtonsHugPrevNext() throws Exception {
        withRow(360, (view, row) -> {
            Item pause = view.findByObjectName("lyricPauseButton");
            Item prev = view.findByObjectName("lyricPrevButton");
            Item next = view.findByObjectName("lyricNextButton");
            Item mode = view.findByObjectName("lyricPlayModeButton");
            Item like = view.findByObjectName("lyricLikeButton");
            assertNotNull(pause);
            assertNotNull(prev);
            assertNotNull(next);
            assertNotNull(mode);
            assertNotNull(like);

            float rowMid = absX(row) + row.width.peekFloat() / 2f;
            float pauseMid = absX(pause) + pause.width.peekFloat() / 2f;
            assertEquals("pause must sit on the row centre", rowMid, pauseMid, 1.5f);

            float prevMid = absX(prev) + prev.width.peekFloat() / 2f;
            float nextMid = absX(next) + next.width.peekFloat() / 2f;
            assertEquals("prev/next must sit the same distance from pause",
                    pauseMid - prevMid, nextMid - pauseMid, 1.5f);
            float trioMid = (prevMid + pauseMid + nextMid) / 3f;
            assertEquals("prev/pause/next as a group must sit on the row centre",
                    rowMid, trioMid, 1.5f);

            float modeRight = absX(mode) + mode.width.peekFloat();
            float prevLeft = absX(prev);
            assertTrue("play-mode must sit immediately left of previous, gap="
                            + (prevLeft - modeRight),
                    prevLeft - modeRight >= 0f && prevLeft - modeRight <= 24f);

            float nextRight = absX(next) + next.width.peekFloat();
            float likeLeft = absX(like);
            assertTrue("like must sit immediately right of next, gap="
                            + (likeLeft - nextRight),
                    likeLeft - nextRight >= 0f && likeLeft - nextRight <= 24f);

            float likeRight = absX(like) + like.width.peekFloat();
            float rowRight = absX(row) + row.width.peekFloat();
            assertTrue("like must stay inside the row", likeRight <= rowRight + 0.5f);
            assertTrue("play-mode must stay inside the row", absX(mode) >= absX(row) - 0.5f);
        });
    }

    @Test
    public void narrowRowShrinksButtonsInsteadOfOverflowing() throws Exception {
        withRow(160, (view, row) -> {
            Item pause = view.findByObjectName("lyricPauseButton");
            Item like = view.findByObjectName("lyricLikeButton");
            Item mode = view.findByObjectName("lyricPlayModeButton");
            assertNotNull(pause);
            assertNotNull(like);
            assertNotNull(mode);
            assertTrue("buttons must shrink below 40px on a 160px row, got "
                            + pause.width.peekFloat(),
                    pause.width.peekFloat() <= 36f);
            float likeRight = absX(like) + like.width.peekFloat();
            float rowRight = absX(row) + row.width.peekFloat();
            assertTrue("shrunk row must not overflow: like=" + likeRight
                            + " row=" + rowRight,
                    likeRight <= rowRight + 0.5f);
            assertTrue(absX(mode) >= absX(row) - 0.5f);
            float rowMid = absX(row) + row.width.peekFloat() / 2f;
            float pauseMid = absX(pause) + pause.width.peekFloat() / 2f;
            assertEquals("pause stays centred when shrunk", rowMid, pauseMid, 1.5f);
        });
    }

    private interface Check {
        void run(QmlView view, Item row) throws Exception;
    }

    private void withRow(float width, Check check) throws Exception {
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
                    + "LyricTransportRow { objectName: \"row\"; width: " + width + " }");
            settle(view);
            Item row = view.findByObjectName("row");
            assertNotNull(row);
            check.run(view, row);
        } finally {
            if (view != null) {
                try { view.dispose(); } catch (Throwable ignored) { }
            }
            if (player != null) {
                try { player.shutdownAsync().join(); } catch (Throwable ignored) { }
            }
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static float absX(Item item) {
        float x = 0f;
        Item cur = item;
        while (cur != null) {
            x += cur.x.peekFloat();
            cur = cur.parent.peek();
        }
        return x;
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
