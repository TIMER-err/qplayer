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

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Pointer-level coverage for the compact and wide responsive navigation shells. */
public class ResponsiveNavigationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void wideNavigationRailDispatchesTheRequestedTab() throws Exception {
        verifyNavigation(1134, "miuixRailItem1");
    }

    @Test
    public void compactBottomBarDispatchesTheRequestedTab() throws Exception {
        verifyNavigation(480, "miuixNavigationItem1");
    }

    /**
     * Expanded, each rail entry's selected/hover pill used to be exactly as tall as
     * its slot, so the pills of neighbouring entries met edge to edge and the column
     * read as one unbroken block rather than four destinations.
     */
    @Test
    public void expandedRailEntriesDoNotTouchEachOther() throws Exception {
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
            player.sourceSetupRequired.set(false);
            player.sourceSetupPending.set(false);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance()).context("hostWindow", new WindowChromeStub());
            view.load(new String(resources.load("Main.qml"), StandardCharsets.UTF_8));

            DirtyQueue queue = view.dirtyQueue();
            queue.install();
            try {
                view.root().width.set(1134);
                view.root().height.set(806);
                queue.flush();
                view.renderer().layoutOnly(view.root());
                queue.flush();
                long animationStart = System.nanoTime();
                view.tickAnimations(animationStart);
                // Past the 350ms expand animation, so _progress has settled at 1.
                view.tickAnimations(animationStart + 1_000_000_000L);
                for (int i = 0; i < 3; i++) {
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                }
                queue.flush();

                Item first = view.findByObjectName("miuixRailItem0");
                Item second = view.findByObjectName("miuixRailItem1");
                Item firstPill = view.findByObjectName("miuixRailIndicator0");
                Item secondPill = view.findByObjectName("miuixRailIndicator1");
                assertNotNull(first);
                assertNotNull(second);
                assertNotNull(firstPill);
                assertNotNull(secondPill);

                float firstBottom = first.y.peekFloat() + firstPill.y.peekFloat()
                        + firstPill.height.peekFloat();
                float secondTop = second.y.peekFloat() + secondPill.y.peekFloat();
                assertTrue("rail entries must be visually separated: first pill ends at "
                                + firstBottom + ", next starts at " + secondTop,
                        secondTop - firstBottom >= 4f);
            } finally {
                queue.uninstall();
            }
        } finally {
            if (view != null) view.dispose();
            if (player != null) player.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private void verifyNavigation(int width, String targetName) throws Exception {
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
            // The first-run source picker is intentionally modal. This test is
            // about the ordinary shell after onboarding, matching issue #38.
            player.sourceSetupRequired.set(false);
            player.sourceSetupPending.set(false);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance()).context("hostWindow", new WindowChromeStub());
            view.load(new String(resources.load("Main.qml"), StandardCharsets.UTF_8));

            DirtyQueue queue = view.dirtyQueue();
            queue.install();
            try {
                view.root().width.set(width);
                view.root().height.set(806);
                queue.flush();
                view.renderer().layoutOnly(view.root());
                queue.flush();
                long animationStart = System.nanoTime();
                view.tickAnimations(animationStart);
                view.tickAnimations(animationStart + 1_000_000_000L);
                // Main.qml contains nested responsive anchors and repeaters; let
                // them reach the same settled geometry as a presented frame.
                for (int i = 0; i < 3; i++) {
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                }
                queue.flush();

                Item target = view.findByObjectName(targetName);
                assertNotNull(targetName, target);
                assertTrue("Navigation target must retain a hit-testable width",
                        target.width.peekFloat() > 0);
                click(view, target);
                queue.flush();

                Property<?> nextPage = (Property<?>) view.root().getClass()
                        .getField("nextPage").get(view.root());
                assertEquals("Search tab must receive the pointer click at width " + width,
                        1, ((Number) nextPage.peek()).intValue());
            } finally {
                queue.uninstall();
            }
        } finally {
            if (view != null) view.dispose();
            if (player != null) player.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static void click(QmlView view, Item target) {
        java.util.Map<String, Object> mapped = view.root().mapFromItem(target,
                target.width.peekFloat() / 2f, target.height.peekFloat() / 2f);
        float x = ((Number) mapped.get("x")).floatValue();
        float y = ((Number) mapped.get("y")).floatValue();
        view.dispatchPointerDown(x, y);
        view.dispatchPointerUp(x, y);
    }
}
