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
