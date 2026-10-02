package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.settings.SettingsStore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Covers desktop-lyric color normalization. */
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



    @Test
    public void colourRowsStoreHexAndTreatAnythingElseAsAutomatic() {
        SettingsCore settings = core();
        String key = SettingsCatalog.DESKTOP_LYRIC_SUNG_COLOR_KEY;
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

}
