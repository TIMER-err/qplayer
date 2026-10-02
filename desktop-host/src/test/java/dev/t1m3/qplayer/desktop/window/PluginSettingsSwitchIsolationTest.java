package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.plugin.PluginCatalogEntry;
import dev.t1m3.qplayer.plugin.PluginRow;
import dev.t1m3.qplayer.plugin.PluginUiContributionRow;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Guards the enabled switch when one persistent settings page is reused for plugins. */
public final class PluginSettingsSwitchIsolationTest {
    public static final class Selection {
        public final Property<String> pluginId = new Property<>("alpha");
    }

    public static final class PlayerProbe {
        public final Property<List<PluginRow>> sourcePlugins =
                new Property<>(rows(false, false));
        public final Property<List<PluginCatalogEntry>> pluginCatalogEntries =
                new Property<>(Collections.emptyList());
        public final Property<List<PluginUiContributionRow>> pluginUiContributions =
                new Property<>(Collections.emptyList());
        public final Property<Boolean> pluginInstallBusy = new Property<>(false);

        private String changedPluginId = "";
        private boolean changedEnabled;

        public void setSourcePluginEnabled(String pluginId, boolean enabled) {
            changedPluginId = pluginId;
            changedEnabled = enabled;
            sourcePlugins.set(rows("alpha".equals(pluginId) ? enabled : enabled("alpha"),
                    "beta".equals(pluginId) ? enabled : enabled("beta")));
        }

        private boolean enabled(String pluginId) {
            for (PluginRow row : sourcePlugins.peek()) {
                if (pluginId.equals(row.id)) return row.enabled;
            }
            return false;
        }
    }

    @Test
    public void enablingOnePluginDoesNotCarryIntoAnotherPluginsSwitch() throws Exception {
        Selection selection = new Selection();
        PlayerProbe player = new PlayerProbe();
        QmlView view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResourceLoader())
                .context("player", player)
                .context("selection", selection)
                .context("i18n", I18n.instance());
        try {
            view.load("import QtQuick\nimport \"settings\"\n"
                    + "Item { width: 900; height: 700\n"
                    + "  PluginSettingsPage { anchors.fill: parent; pluginId: selection.pluginId }\n"
                    + "}");
            settle(view);

            Item toggle = view.findByObjectName("pluginEnableSwitch");
            assertNotNull(toggle);
            assertFalse(checked(toggle));

            click(view, toggle);
            settle(view);
            assertEquals("The action must target only the page's plugin",
                    "alpha", player.changedPluginId);
            assertTrue(player.changedEnabled);
            assertTrue(enabled(player.sourcePlugins.peek(), "alpha"));
            assertFalse(enabled(player.sourcePlugins.peek(), "beta"));

            selection.pluginId.set("beta");
            settle(view);
            assertFalse("The persistent switch must reload beta's disabled state",
                    checked(toggle));
        } finally {
            view.dispose();
        }
    }

    private static List<PluginRow> rows(boolean alphaEnabled, boolean betaEnabled) {
        List<PluginRow> rows = new ArrayList<>();
        rows.add(row("alpha", alphaEnabled));
        rows.add(row("beta", betaEnabled));
        return rows;
    }

    private static PluginRow row(String id, boolean enabled) {
        PluginRow row = new PluginRow();
        row.id = id;
        row.name = id;
        row.version = "1.0.0";
        row.enabled = enabled;
        return row;
    }

    private static boolean enabled(List<PluginRow> rows, String id) {
        for (PluginRow row : rows) {
            if (id.equals(row.id)) return row.enabled;
        }
        return false;
    }

    private static boolean checked(Item toggle) throws Exception {
        Property<?> checked = (Property<?>) toggle.getClass().getField("checked").get(toggle);
        return Boolean.TRUE.equals(checked.peek());
    }

    private static void click(QmlView view, Item target) {
        Map<String, Object> mapped = view.root().mapFromItem(target,
                target.width.peekFloat() / 2f, target.height.peekFloat() / 2f);
        float x = ((Number) mapped.get("x")).floatValue();
        float y = ((Number) mapped.get("y")).floatValue();
        view.dispatchPointerDown(x, y);
        view.dispatchPointerUp(x, y);
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            for (int i = 0; i < 4; i++) {
                queue.flush();
                view.renderer().layoutOnly(view.root());
            }
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }
}
