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

/** Window-resize coverage for empty states inside the real responsive shell. */
public final class PlaceholderResizeTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sourceSetupPlaceholderTracksTheContentPanelAcrossBreakpoints() throws Exception {
        withScene((view, player) -> {
            player.sourceSetupRequired.set(true);
            settle(view);
            assertTracksPanelCentre(view, "sourceSetupEmptyState", "source setup", 1100, true);
            assertTracksPanelCentre(view, "sourceSetupEmptyState", "source setup", 480, false);
        });
    }

    @Test
    public void localPlaceholderTracksTheContentPanelAcrossBreakpoints() throws Exception {
        withScene((view, player) -> {
            player.sourceSetupRequired.set(false);
            property(view.root(), "localLoaded").set(true);
            property(view.root(), "page").set(3);
            settle(view);
            assertTracksPanelCentre(view, "localEmptyState", "local", 1100, true);
            assertTracksPanelCentre(view, "localEmptyState", "local", 480, false);
        });
    }

    private void withScene(SceneCheck check) throws Exception {
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
            player.sourceSetupPending.set(false);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance())
                    .context("hostWindow", new WindowChromeStub());
            view.renderer().setPictureCache(true);
            view.load(new String(resources.load("Main.qml"), StandardCharsets.UTF_8));
            view.root().height.set(806);
            check.run(view, player);
        } finally {
            if (view != null) view.dispose();
            if (player != null) player.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static void assertTracksPanelCentre(QmlView view, String stateName,
                                                 String label, int windowWidth,
                                                 boolean settleFully) throws Exception {
        view.root().width.set(windowWidth);
        if (settleFully) settle(view);
        else presentOneFrame(view);
        Item state = view.findByObjectName(stateName);
        assertNotNull(stateName, state);
        Item content = findIn(state, "emptyStateContent");
        assertNotNull(stateName + " content", content);
        Item mini = view.findByObjectName("miniPlayer");
        assertNotNull("miniPlayer", mini);
        Item badge = findIn(state, "emptyStateBadge");
        assertNotNull(stateName + " badge", badge);
        float panelLeft = absoluteX(state);
        float expectedLeft = windowWidth >= 840 ? 216f : (windowWidth >= 600 ? 80f : 0f);
        assertEquals(label + " panel must use the current navigation footprint",
                expectedLeft, panelLeft, 0.5f);
        assertEquals(label + " panel must reach the current window right edge",
                windowWidth, panelLeft + state.width.peekFloat(), 0.5f);
        float panelTop = absoluteY(state);
        assertEquals(label + " panel must stop at the current mini player",
                absoluteY(mini), panelTop + state.height.peekFloat(), 0.5f);
        float panelCentre = panelLeft + state.width.peekFloat() / 2f;
        float contentCentre = absoluteX(content) + content.width.peekFloat() / 2f;
        assertEquals(label + " placeholder must follow width " + windowWidth,
                panelCentre, contentCentre, 0.5f);
        assertEquals(label + " badge must be centred inside the bounded copy column",
                (content.width.peekFloat() - badge.width.peekFloat()) / 2f,
                badge.x.peekFloat(), 0.5f);
        float badgeCentre = absoluteX(badge) + badge.width.peekFloat() / 2f;
        float panelMiddle = panelTop + state.height.peekFloat() / 2f;
        float contentMiddle = absoluteY(content) + content.height.peekFloat() / 2f;
        assertEquals(label + " placeholder must track compact/wide vertical space",
                panelMiddle, contentMiddle, 0.5f);
        assertEquals(label + " visible badge must remain centred at width " + windowWidth,
                panelCentre, badgeCentre, 0.5f);
    }

    @SuppressWarnings("unchecked")
    private static <T> Property<T> property(Item item, String name) throws Exception {
        return (Property<T>) item.getClass().getField(name).get(item);
    }

    private static Item findIn(Item root, String objectName) {
        if (objectName.equals(root.objectName.peek())) return root;
        for (Item child : root.children) {
            Item hit = findIn(child, objectName);
            if (hit != null) return hit;
        }
        return null;
    }

    private static float absoluteX(Item item) {
        float x = 0f;
        for (Item node = item; node != null; node = node.parent.peek()) {
            x += node.x.peekFloat();
        }
        return x;
    }

    private static float absoluteY(Item item) {
        float y = 0f;
        for (Item node = item; node != null; node = node.parent.peek()) {
            y += node.y.peekFloat();
        }
        return y;
    }


    private static void presentOneFrame(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            long animationStart = System.nanoTime();
            view.tickAnimations(animationStart);
            view.tickAnimations(animationStart + 1_000_000_000L);
            queue.flush();
            view.renderer().layoutOnly(view.root());
        } finally {
            queue.uninstall();
        }
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            long animationStart = System.nanoTime();
            view.tickAnimations(animationStart);
            view.tickAnimations(animationStart + 1_000_000_000L);
            for (int i = 0; i < 4; i++) {
                queue.flush();
                view.renderer().layoutOnly(view.root());
            }
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }

    @FunctionalInterface
    private interface SceneCheck {
        void run(QmlView view, PlayerController player) throws Exception;
    }
}
