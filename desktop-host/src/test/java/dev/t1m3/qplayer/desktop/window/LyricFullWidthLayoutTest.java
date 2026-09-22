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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The lyric page's full-width layout, which has no cover and puts the transport
 * and progress along the bottom so the lyrics own the page.
 *
 * <p>Both chromes exist at once and crossfade on {@code player.lyricFullWidth},
 * a value the HOST eases (LyricCompositor) rather than a Behavior here, so that
 * the QML band and the host-drawn column cannot drift apart. That makes the
 * layout reachable from a test by just setting the property.
 */
public class LyricFullWidthLayoutTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void fullWidthChromeSpansThePageAndKeepsTheProgressBarLast() throws Exception {
        withOverlay(1.0, (view, overlay) -> {
            Item wide = view.findByObjectName("lyricWideChrome");
            Item classic = view.findByObjectName("lyricLandscapeChrome");
            Item transport = view.findByObjectName("lyricWideTransport");
            Item progress = view.findByObjectName("lyricWideProgress");
            assertNotNull(wide);
            assertNotNull(classic);
            assertNotNull(transport);
            assertNotNull(progress);

            assertTrue("the bottom band must span the page, not half of it: "
                            + wide.width.peekFloat(),
                    wide.width.peekFloat() >= overlay.width.peekFloat() - 0.5f);
            float bandBottom = wide.y.peekFloat() + wide.height.peekFloat();
            assertTrue("the band must sit at the bottom of the page: bottom=" + bandBottom
                            + " page=" + overlay.height.peekFloat(),
                    Math.abs(bandBottom - overlay.height.peekFloat()) <= 1f);
            // "Progress bar at the bottom" is the point of the layout: below the
            // transport row, not above it as in the column beside the cover.
            assertTrue("the progress bar must sit below the transport row: progress y="
                            + progress.y.peekFloat() + " transport y=" + transport.y.peekFloat(),
                    progress.y.peekFloat() > transport.y.peekFloat());
            // The cover lives in the classic chrome; full width drops it.
            assertTrue("the cover chrome must be faded out: opacity="
                            + classic.opacity.peekFloat(),
                    classic.opacity.peekFloat() <= 0.01f);
        });
    }

    @Test
    public void classicChromeIsTheOneShownWhenTheModeIsOff() throws Exception {
        withOverlay(0.0, (view, overlay) -> {
            Item wide = view.findByObjectName("lyricWideChrome");
            Item classic = view.findByObjectName("lyricLandscapeChrome");
            assertNotNull(wide);
            assertNotNull(classic);
            assertTrue("the bottom band must be gone, not merely transparent: it still "
                            + "hit-tests over the page otherwise",
                    !Boolean.TRUE.equals(wide.visible.peek()));
            assertTrue("the cover chrome must be fully shown: opacity="
                            + classic.opacity.peekFloat(),
                    classic.opacity.peekFloat() >= 0.99f);
        });
    }

    private interface Check {
        void run(QmlView view, Item overlay);
    }

    /** Load LyricOverlay.qml in a landscape window with the given full-width value. */
    private void withOverlay(double fullWidth, Check check) throws Exception {
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
            player.lyricFullWidth.set(fullWidth);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance());
            // Instantiated from a root in the resource root rather than loaded as
            // a bare string: LyricOverlay.qml's own `import "."` only resolves to
            // its sibling components (PlaybackCoverImage, LyricProgress, ...) when
            // the engine loads it from its real path.
            view.load("import QtQuick\nimport \"components\"\n"
                    + "LyricOverlay { objectName: \"lyricOverlayRoot\" }");

            DirtyQueue queue = view.dirtyQueue();
            queue.install();
            try {
                // Wider than it is tall: the page only offers either landscape
                // chrome in that shape.
                view.root().width.set(1280);
                view.root().height.set(760);
                for (int i = 0; i < 3; i++) {
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                }
                queue.flush();
                check.run(view, view.root());
            } finally {
                queue.uninstall();
            }
        } finally {
            if (view != null) view.dispose();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }
}
