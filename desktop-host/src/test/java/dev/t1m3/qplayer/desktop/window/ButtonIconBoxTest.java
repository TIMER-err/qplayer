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
import io.github.timer_err.qml4j.render.items.core.Text;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A Button's leading icon must keep its horizontal box even before the icon
 * typeface is available. The icon is a bare Text whose measured advance is 0
 * when the icon font has not resolved, which used to shrink the content Row and
 * leave the button background short on the right while the glyph still painted
 * over it (and pushed the label left). The icon now carries a fixed em box, so
 * the background always matches the drawn content.
 */
public class ButtonIconBoxTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void iconKeepsItsWidthWithoutTheIconFont() throws Exception {
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
            // Deliberately NOT loading any typeface: the icon glyph cannot be
            // measured, which is exactly the state this test guards against.
            view = QmlView.withStockTypes(new QmlEngine())
                    .resources(new ClasspathResourceLoader())
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance());
            view.load("import QtQuick\nimport miuix.Core\n"
                    + "Rectangle { width: 400; height: 120\n"
                    + "  Button { id: b; objectName: \"probe\"\n"
                    + "    x: 16; y: 16; text: \"播放全部\"; icon: \"play_arrow\" }\n"
                    + "}");
            settle(view);

            Item button = view.findByObjectName("probe");
            assertNotNull(button);

            Text icon = findIconText(button);
            Text label = findLabelText(button);
            assertNotNull(icon);
            assertNotNull(label);

            // The icon box is fixed at 18dp even though the glyph has no advance.
            assertEquals("icon box must stay 18dp without the icon font",
                    18f, icon.width.peekFloat(), 0.5f);

            // The label sits after the icon box + the 8dp row spacing, so a
            // centred row keeps the text away from the icon instead of
            // overlapping it at x=0.
            assertTrue("label must start after the icon box, got x=" + label.x.peekFloat(),
                    label.x.peekFloat() >= 18f);

            // The button background tracks the full content width: icon (18) +
            // spacing (8) + label (measured) + 2 * horizontalPadding (32).
            float expected = 18f + 8f + label.width.peekFloat() + 32f;
            assertEquals("button must size to icon + label + padding",
                    expected, button.width.peekFloat(), 0.5f);
        } finally {
            if (view != null) { try { view.dispose(); } catch (Throwable ignored) { } }
            if (player != null) { try { player.shutdownAsync().join(); } catch (Throwable ignored) { } }
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static Text findIconText(Item root) {
        return findText(root, "play_arrow");
    }

    private static Text findLabelText(Item root) {
        return findText(root, "播放全部");
    }

    private static Text findText(Item root, String value) {
        if (root instanceof Text && value.equals(((Text) root).text.peek())) {
            return (Text) root;
        }
        for (Item child : root.children) {
            Text hit = findText(child, value);
            if (hit != null) return hit;
        }
        return null;
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
