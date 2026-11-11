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
        verifyNavigation(1134, "navRailItem1");
    }

    @Test
    public void compactBottomBarDispatchesTheRequestedTab() throws Exception {
        verifyNavigation(480, "miuixNavigationItem1");
    }

    /**
     * Expanded, each sidebar entry's selected/hover pill must leave a visible gap
     * to its neighbour; edge-to-edge pills read as one unbroken block rather than
     * separate destinations.
     */
    @Test
    public void expandedRailEntriesDoNotTouchEachOther() throws Exception {
        withShell(1134, 806, (view, player) -> {
            Item first = view.findByObjectName("navRailItem0");
            Item second = view.findByObjectName("navRailItem1");
            Item firstPill = view.findByObjectName("navRailItem0Pill");
            Item secondPill = view.findByObjectName("navRailItem1Pill");
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
        });
    }

    /**
     * A short window (or a large interface scale) used to squeeze the rail between
     * its brand and footer until the last destination was clipped away. The
     * destinations now scroll above a pinned footer: every entry must stay inside
     * the scrollable content and the footer must stay inside the window.
     */
    @Test
    public void shortWindowKeepsEveryDestinationReachable() throws Exception {
        withShell(1134, 480, (view, player) -> {
            Item scroll = view.findByObjectName("navRailScroll");
            Item settingsEntry = view.findByObjectName("navRailSettings");
            assertNotNull(scroll);
            assertNotNull(settingsEntry);
            float contentHeight = ((Number) ((Property<?>) scroll.getClass()
                    .getField("contentHeight").get(scroll)).peek()).floatValue();
            for (int i = 0; i < 4; i++) {
                Item entry = view.findByObjectName("navRailItem" + i);
                if (entry == null) break;
                Item list = entry.parent.peek();
                float bottom = list.y.peekFloat() + entry.y.peekFloat() + entry.height.peekFloat();
                assertTrue("navRailItem" + i + " must lie inside the scrollable content",
                        bottom <= contentHeight + 0.5f);
            }
            java.util.Map<String, Object> footer = view.root().mapFromItem(settingsEntry, 0, 0);
            float footerBottom = ((Number) footer.get("y")).floatValue()
                    + settingsEntry.height.peekFloat();
            assertTrue("pinned settings entry must stay inside the window, bottom=" + footerBottom,
                    footerBottom <= 480f + 0.5f);
            assertTrue("destinations must be scrollable when they do not fit",
                    contentHeight > scroll.height.peekFloat());
        });
    }

    /**
     * Desktop Escape and Android Back reach QML as a host back press, never as a key
     * event on the popup. With the overflow menu open, Back must close the menu and
     * leave the current destination alone instead of navigating home underneath it.
     */
    @Test
    public void backPressClosesOpenMenuBeforeNavigating() throws Exception {
        withShell(480, 806, (view, player) -> {
            click(view, view.findByObjectName("miuixNavigationItem1"));
            settle(view);
            // Song lists own menus too; take the overflow menu's own overlay
            // before open() reparents it to the scene root.
            Item overlay = findIn(view.findByObjectName("compactOverflowMenu"), "miuixMenuOverlay");
            assertNotNull(overlay);
            click(view, view.findByObjectName("compactOverflowAction"));
            settle(view);
            assertTrue("overflow menu must open", overlay.visible.peek());

            player.pressBack();
            player.pump();
            settle(view);

            assertTrue("Back must dismiss the open menu", !overlay.visible.peek());
            Property<?> nextPage = (Property<?>) view.root().getClass()
                    .getField("nextPage").get(view.root());
            assertEquals("Back must not navigate while it closes the menu",
                    1, ((Number) nextPage.peek()).intValue());
        });
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        long start = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            queue.flush();
            view.renderer().layoutOnly(view.root());
            view.tickAnimations(start + i * 400_000_000L);
        }
        queue.flush();
    }

    private interface ShellCheck {
        void run(QmlView view, PlayerController player) throws Exception;
    }

    private void withShell(int width, int height, ShellCheck check) throws Exception {
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
            // The first-run source picker is intentionally modal. These tests cover
            // the ordinary shell after onboarding, matching issue #38.
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
                view.root().height.set(height);
                queue.flush();
                view.renderer().layoutOnly(view.root());
                long animationStart = System.nanoTime();
                view.tickAnimations(animationStart);
                view.tickAnimations(animationStart + 1_000_000_000L);
                for (int i = 0; i < 3; i++) {
                    queue.flush();
                    view.renderer().layoutOnly(view.root());
                }
                queue.flush();
                check.run(view, player);
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

    private void verifyNavigation(int width, String targetName) throws Exception {
        withShell(width, 806, (view, player) -> {
            Item target = view.findByObjectName(targetName);
            assertNotNull(targetName, target);
            assertTrue("Navigation target must retain a hit-testable width",
                    target.width.peekFloat() > 0);
            click(view, target);
            view.dirtyQueue().flush();

            Property<?> nextPage = (Property<?>) view.root().getClass()
                    .getField("nextPage").get(view.root());
            assertEquals("Search tab must receive the pointer click at width " + width,
                    1, ((Number) nextPage.peek()).intValue());
        });
    }

    private static void click(QmlView view, Item target) {
        java.util.Map<String, Object> mapped = view.root().mapFromItem(target,
                target.width.peekFloat() / 2f, target.height.peekFloat() / 2f);
        float x = ((Number) mapped.get("x")).floatValue();
        float y = ((Number) mapped.get("y")).floatValue();
        view.dispatchPointerDown(x, y);
        view.dispatchPointerUp(x, y);
    }

    private static Item findIn(Item item, String name) {
        if (item == null) return null;
        if (name.equals(item.objectName.peek())) return item;
        for (Item child : item.children) {
            Item found = findIn(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
