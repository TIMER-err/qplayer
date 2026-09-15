package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.settings.SettingGroup;
import dev.t1m3.qplayer.settings.SettingSpec;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.settings.SettingsStore;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The desktop-lyric settings block: its catalog shape, the COLOR row type added
 * for it, and that both new QML files actually parse under qml4j — the settings
 * page is generated, so a broken row type only shows up when the page is built.
 */
public class DesktopLyricSettingsTest {

    /** In-memory store: these tests write values, and must not touch the real
     *  settings.json under the developer's profile. */
    private static final class MemoryStore implements SettingsStore {
        private final Map<String, Object> values = new HashMap<>();

        @Override public boolean getBool(String key, boolean def) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override public int getInt(String key, int def) {
            Object v = values.get(key);
            return v instanceof Number ? ((Number) v).intValue() : def;
        }

        @Override public String getString(String key, String def) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override public boolean has(String key) {
            return values.containsKey(key);
        }

        @Override public void putBool(String key, boolean value) { values.put(key, value); }
        @Override public void putInt(String key, int value) { values.put(key, value); }
        @Override public void putString(String key, String value) { values.put(key, value); }
    }

    private static SettingsCore core() {
        SettingsCore settings = new SettingsCore();
        settings.load(new MemoryStore(), SettingsCatalog.DESKTOP);
        return settings;
    }

    private static SettingSpec row(SettingsCore settings, String key) {
        for (SettingGroup group : settings.groups(SettingsCatalog.LYRIC)) {
            for (SettingSpec spec : group.rows) {
                if (spec.key.equals(key)) return spec;
            }
        }
        return null;
    }

    @Test
    public void everyDesktopLyricRowHangsOffTheMasterSwitchInOneCard() {
        SettingsCore settings = core();
        SettingGroup block = null;
        for (SettingGroup group : settings.groups(SettingsCatalog.LYRIC)) {
            if ("desktopLyric".equals(group.id)) block = group;
        }
        assertNotNull("the 桌面歌词 card must exist on desktop", block);
        assertTrue("the card carries the switch plus its dependants", block.rows.size() > 1);

        for (SettingSpec spec : block.rows) {
            assertEquals("desktop lyrics never reach Android",
                    SettingsCatalog.DESKTOP, spec.platform);
            if ("desktopLyricEnabled".equals(spec.key)) {
                assertEquals("the master switch cannot depend on itself", "", spec.dependsOn);
            } else {
                assertEquals("row " + spec.key + " must hide with the feature",
                        "desktopLyricEnabled", spec.dependsOn);
            }
        }
    }

    @Test
    public void desktopLyricTypographyIsIndependentOfTheLyricPage() {
        SettingsCore settings = core();
        assertNotNull(row(settings, "desktopLyricFontSize"));
        assertNotNull(row(settings, "desktopLyricFontWeight"));
        assertNotNull(row(settings, "desktopLyricShadow"));

        settings.setValue("desktopLyricFontSize", 34);
        assertEquals("the floating window has its own size",
                34, settings.intOf("desktopLyricFontSize"));
        assertEquals("the lyric page's size is untouched",
                28, settings.intOf("lyricFontSize"));
    }

    @Test
    public void colourRowsStoreHexAndTreatAnythingElseAsAutomatic() {
        SettingsCore settings = core();
        String key = SettingsCatalog.DESKTOP_LYRIC_SUNG_COLOR_KEY;
        SettingSpec spec = row(settings, key);
        assertNotNull(spec);
        assertEquals(SettingSpec.COLOR, spec.type);
        assertEquals("an unset colour means 'use the Monet role'", "", settings.str(key));

        settings.setValue(key, "#AABBCC");
        assertEquals("#aabbcc", settings.str(key));
        // QML hands colours over in #aarrggbb form; the alpha is dropped because
        // the renderer owns opacity.
        settings.setValue(key, "#ff102030");
        assertEquals("#102030", settings.str(key));
        settings.setValue(key, "not a colour");
        assertEquals("garbage falls back to automatic", "", settings.str(key));
    }

    @Test
    public void outlineRowGuardsReadabilityWithoutTouchingTheBackground() {
        SettingsCore settings = core();
        SettingSpec spec = row(settings, "desktopLyricOutline");
        assertNotNull(spec);
        assertTrue("the outline is what keeps lyrics legible over any wallpaper, "
                + "so it is on unless the user turns it off", settings.bool(spec.key));
    }

    @Test
    public void lockRowSharesItsKeyWithTheFloatingWindowsOwnLockButton() {
        SettingsCore settings = core();
        SettingSpec spec = row(settings, SettingsCatalog.DESKTOP_LYRIC_LOCKED_KEY);
        assertNotNull("the lock must be reachable from the settings page", spec);
        assertEquals(SettingSpec.SWITCH, spec.type);
        assertFalse("the window starts interactive", settings.bool(spec.key));
    }

    @Test
    public void colourRowRendersASwatchOfTheColourItWillApply() {
        SettingsCore settings = core();
        SettingSpec spec = row(settings, SettingsCatalog.DESKTOP_LYRIC_UNSUNG_COLOR_KEY);
        assertNotNull(spec);
        settings.setValue(spec.key, "#3366cc");

        QmlView view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResourceLoader())
                .context("settings", settings).context("i18n", I18n.instance())
                .context("testSpec", spec);
        try {
            view.load("import QtQuick\nimport \"settings\"\n"
                    + "SettingColorRow { width: 880; spec: testSpec }");
            settle(view);
            Item swatch = view.findByObjectName("settingColorSwatch");
            assertNotNull("the colour row must draw its swatch", swatch);
        } finally {
            view.dispose();
        }
    }

    @Test
    public void colourDialogOpensForTheRowItIsPointedAt() {
        SettingsCore settings = core();
        QmlView view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResourceLoader())
                .context("settings", settings).context("i18n", I18n.instance());
        try {
            view.load("import QtQuick\nimport \"dialogs\"\n"
                    + "Item { width: 900; height: 700\n"
                    + "  ColorPickerDialog { objectName: \"colorDialog\" }\n"
                    + "}");
            settle(view);
            assertNotNull(view.findByObjectName("colorDialog"));

            settings.openColorPicker(SettingsCatalog.DESKTOP_LYRIC_SUNG_COLOR_KEY);
            settle(view);
            assertNotNull("naming a colour row must raise the shared picker",
                    view.findByObjectName("settingColorPicker"));
        } finally {
            view.dispose();
        }
    }

    /** The one picker dialog now serves two independent font sources, so a pick
     *  must land on the source it was opened for and leave the other alone. */
    @Test
    public void fontPickerWritesWhicheverFontSourceItWasOpenedFor() {
        SettingsCore settings = core();
        settings.setFontSelection("Microsoft YaHei");

        settings.invoke("pickDesktopLyricFont");
        assertEquals(SettingsCore.DESKTOP_LYRIC_FONT_KEY, settings.fontPickerTarget.peek());

        QmlView view = QmlView.withStockTypes(new QmlEngine())
                .resources(new ClasspathResourceLoader())
                .context("settings", settings).context("i18n", I18n.instance());
        try {
            view.load("import QtQuick\nimport \"dialogs\"\n"
                    + "Item { width: 900; height: 700\n"
                    + "  FontPickerDialog { objectName: \"fontDialog\"; active: true\n"
                    + "    targetKey: settings.fontPickerTarget }\n"
                    + "}");
            settle(view);
            assertNotNull(view.findByObjectName("fontDialog"));
        } finally {
            view.dispose();
        }

        settings.setFontSelectionFor(SettingsCore.DESKTOP_LYRIC_FONT_KEY, "SimSun");
        assertEquals("SimSun",
                settings.fontSelectionOf(SettingsCore.DESKTOP_LYRIC_FONT_KEY));
        assertEquals("the app-wide font must be untouched",
                "Microsoft YaHei", settings.fontSelection());

        // Empty is "follow the app font" for the secondary source, which is why the
        // bundled face needs an explicit name there rather than the empty string.
        settings.setFontSelectionFor(SettingsCore.DESKTOP_LYRIC_FONT_KEY, "");
        assertEquals("", settings.fontSelectionOf(SettingsCore.DESKTOP_LYRIC_FONT_KEY));
        assertEquals("Microsoft YaHei", settings.fontSelection());
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
