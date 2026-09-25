package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.settings.SettingSpec;
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
        withOverlay(1.0, (view, overlay, unusedSettings) -> {
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
            // The cover lives in the classic chrome; full width pushes it off
            // the left edge and then stops rendering it entirely, so it cannot
            // keep hit-testing over a page whose chrome moved to the bottom.
            assertTrue("the cover chrome must be gone: visible="
                            + classic.visible.peek() + " x=" + classic.x.peekFloat(),
                    !Boolean.TRUE.equals(classic.visible.peek()));
        });
    }

    /** The layout is a way of looking at the page, so it is toggled from the page
     *  and has no settings row of its own — but the value is still stored. */
    @Test
    public void theToggleLivesOnThePageAndNotInTheSettingsList() {
        SettingsCore settings = new SettingsCore();
        settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
        assertTrue("the setting must still be stored and applied",
                settings.has("lyricFullWidth"));
        for (SettingSpec row : settings.rows(SettingsCatalog.LYRIC)) {
            assertTrue("a hidden setting must not render a row: " + row.key,
                    !"lyricFullWidth".equals(row.key));
        }
    }

    @Test
    public void classicChromeIsTheOneShownWhenTheModeIsOff() throws Exception {
        withOverlay(0.0, (view, overlay, unusedSettings) -> {
            Item wide = view.findByObjectName("lyricWideChrome");
            Item classic = view.findByObjectName("lyricLandscapeChrome");
            Item toggle = view.findByObjectName("lyricFullWidthBtn");
            assertNotNull(wide);
            assertNotNull(classic);
            assertNotNull(toggle);
            assertTrue("the page's own toggle must be reachable in a wide window",
                    Boolean.TRUE.equals(toggle.visible.peek()));
            assertTrue("the bottom band must be gone, not merely transparent: it still "
                            + "hit-tests over the page otherwise",
                    !Boolean.TRUE.equals(wide.visible.peek()));
            assertTrue("the cover chrome must be fully in place: visible="
                            + classic.visible.peek() + " x=" + classic.x.peekFloat(),
                    Boolean.TRUE.equals(classic.visible.peek())
                            && Math.abs(classic.x.peekFloat()) <= 0.5f);
        });
    }

    /**
     * The button's own visibility condition really is evaluated — including the
     * {@code settings.has} term that keeps it off a host without the setting.
     * Without this, a term that silently failed to resolve would look identical
     * to a working one on desktop, where every term is true anyway.
     */
    @Test
    public void theToggleIsAbsentOnAHostWithoutTheSetting() throws Exception {
        withOverlay(0.0, SettingsCatalog.ANDROID, (view, overlay, unusedSettings) -> {
            Item toggle = view.findByObjectName("lyricFullWidthBtn");
            assertNotNull(toggle);
            assertTrue("a host that does not have the setting must not offer the button",
                    !Boolean.TRUE.equals(toggle.visible.peek()));
        });
    }

    /**
     * Clicking the button must actually reach it. Rendering in the right place
     * and being hit-testable there are different things in this engine, and the
     * two buttons beside it were reported working while this one was not.
     */
    @Test
    public void clickingTheToggleFlipsTheSetting() throws Exception {
        withOverlay(0.0, SettingsCatalog.DESKTOP, (view, overlay, settings) -> {
            Item toggle = view.findByObjectName("lyricFullWidthBtn");
            assertNotNull(toggle);
            assertTrue("precondition: the button must be on screen",
                    Boolean.TRUE.equals(toggle.visible.peek()));
            assertTrue("precondition: the setting starts off",
                    !Boolean.TRUE.equals(settings.value("lyricFullWidth")));

            float cx = absoluteX(toggle) + toggle.width.peekFloat() / 2f;
            float cy = absoluteY(toggle) + toggle.height.peekFloat() / 2f;
            view.dispatchPointerDown(cx, cy);
            view.dispatchPointerUp(cx, cy);
            view.dirtyQueue().flush();

            assertTrue("a click at the button's own centre (" + cx + ", " + cy
                            + ") must toggle the setting; its box is "
                            + toggle.width.peekFloat() + "x" + toggle.height.peekFloat(),
                    Boolean.TRUE.equals(settings.value("lyricFullWidth")));
        });
    }

    private static float absoluteX(Item item) {
        float x = 0f;
        for (Item i = item; i != null; i = i.parent.peek()) x += i.x.peekFloat();
        return x;
    }

    private static float absoluteY(Item item) {
        float y = 0f;
        for (Item i = item; i != null; i = i.parent.peek()) y += i.y.peekFloat();
        return y;
    }

    private interface Check {
        void run(QmlView view, Item overlay, SettingsCore settings);
    }

    private void withOverlay(double fullWidth, Check check) throws Exception {
        withOverlay(fullWidth, SettingsCatalog.DESKTOP, check);
    }

    /** Load LyricOverlay.qml in a landscape window with the given full-width value. */
    private void withOverlay(double fullWidth, String platform, Check check) throws Exception {
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
            // A track WITH lyrics: lyricsCoverOnly defaults to true (no lyrics
            // loaded yet), and the page then shows the cover instead of any of
            // this — the layout only exists when there is a column to lay out.
            player.lyricsCoverOnly.set(false);
            player.lyricFullWidth.set(fullWidth);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), platform);
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
                check.run(view, view.root(), settings);
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
