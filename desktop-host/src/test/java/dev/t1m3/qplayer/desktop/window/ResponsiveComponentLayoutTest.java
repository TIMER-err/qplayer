package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.settings.SettingGroup;
import dev.t1m3.qplayer.settings.SettingSpec;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Guards responsive widths that used to collapse after a zero-sized first layout. */
public class ResponsiveComponentLayoutTest {

    @Test
    public void wideDialogContentKeepsThePanelWidthAfterOverlayReparenting() {
        ClasspathResourceLoader resources = new ClasspathResourceLoader();
        QmlView view = QmlView.withStockTypes(new QmlEngine()).resources(resources);
        try {
            view.load("import QtQuick\nimport miuix.Core\n"
                    + "Item { width: 1134; height: 806\n"
                    + "  Dialog { id: dialog; objectName: \"dialog\"\n"
                    + "    Item { objectName: \"dialogContent\"; width: dialog.contentWidth; height: 48 }\n"
                    + "    Component.onCompleted: dialog.open()\n"
                    + "  }\n"
                    + "}");
            settle(view);
            Item content = view.findByObjectName("dialogContent");
            assertNotNull(content);
            assertTrue("Wide dialog body must not collapse to a character-wide column",
                    content.width.peekFloat() >= 300f);
        } finally {
            view.dispose();
        }
    }

    @Test
    public void emptyStateCentersMeasuredContentInsideTheAvailableRegion() {
        ClasspathResourceLoader resources = new ClasspathResourceLoader();
        QmlView view = QmlView.withStockTypes(new QmlEngine()).resources(resources);
        try {
            view.load("import QtQuick\nimport QtQuick.Layouts\nimport \"components\"\n"
                    + "Item { width: 348; height: 746\n"
                    + "  ColumnLayout { anchors.fill: parent; spacing: 0\n"
                    + "    Item { Layout.fillWidth: true; Layout.preferredHeight: 260 }\n"
                    + "    Item { Layout.fillWidth: true; Layout.fillHeight: true\n"
                    + "      EmptyState { objectName: \"emptyState\"; anchors.centerIn: parent\n"
                    + "        title: \"No local playlists\"; message: \"Songs from any source can live here\" }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}");
            settle(view);
            Item state = view.findByObjectName("emptyState");
            Item content = view.findByObjectName("emptyStateContent");
            assertNotNull(state);
            assertNotNull(content);
            assertTrue("Empty-state copy must not collapse to the badge width",
                    content.width.peekFloat() >= 280f);
            assertEquals("Empty-state content must be horizontally centred",
                    state.width.peekFloat() / 2f,
                    content.x.peekFloat() + content.width.peekFloat() / 2f, 0.5f);
            assertEquals("Empty-state content must be vertically centred after measuring",
                    state.height.peekFloat() / 2f,
                    content.y.peekFloat() + content.height.peekFloat() / 2f, 0.5f);
        } finally {
            view.dispose();
        }
    }

    @Test
    public void settingsRadioOptionsFillTheWideCard() {
        SettingsCore settings = new SettingsCore();
        settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
        SettingSpec graphics = null;
        for (SettingGroup group : settings.groups("appearance")) {
            for (SettingSpec row : group.rows) {
                if ("graphicsBackend".equals(row.key)) graphics = row;
            }
        }
        assertNotNull(graphics);

        ClasspathResourceLoader resources = new ClasspathResourceLoader();
        QmlView view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                .context("settings", settings).context("i18n", I18n.instance())
                .context("testSpec", graphics);
        try {
            view.load("import QtQuick\nimport \"settings\"\n"
                    + "SettingRadioRow { width: 880; spec: testSpec }");
            settle(view);
            Item option = view.findByObjectName("settingRadioOption0");
            assertNotNull(option);
            assertTrue("Radio option must use the available settings-card width",
                    option.width.peekFloat() >= 800f);
        } finally {
            view.dispose();
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
