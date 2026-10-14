package dev.t1m3.qplayer.settings;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Overlay-lyric catalog placement and defaults, without loading SettingsCore
 *  (that path pulls Skija's native font manager). */
public class OverlayLyricSettingsTest {

    @Test
    public void overlayTabIsAndroidOnlyAndLyricTabNoLongerOwnsIt() {
        Set<String> androidOverlay = keysOn(SettingsCatalog.OVERLAY_LYRIC, SettingsCatalog.ANDROID);
        Set<String> desktopOverlay = keysOn(SettingsCatalog.OVERLAY_LYRIC, SettingsCatalog.DESKTOP);
        Set<String> androidLyric = keysOn(SettingsCatalog.LYRIC, SettingsCatalog.ANDROID);

        assertTrue(androidOverlay.contains(SettingsCatalog.OVERLAY_LYRIC_KEY));
        assertTrue(androidOverlay.contains(SettingsCatalog.OVERLAY_LYRIC_LOCKED_KEY));
        assertTrue(androidOverlay.contains(SettingsCatalog.OVERLAY_LYRIC_OUTLINE_KEY));
        assertTrue(androidOverlay.contains(SettingsCatalog.OVERLAY_LYRIC_OUTLINE_COLOR_KEY));
        assertTrue(androidOverlay.contains(SettingsCatalog.OVERLAY_LYRIC_LINE_SPACING_KEY));
        assertTrue(androidOverlay.contains(SettingsCatalog.OVERLAY_LYRIC_HEIGHT_KEY));
        assertTrue(desktopOverlay.isEmpty());

        assertFalse(androidLyric.contains(SettingsCatalog.OVERLAY_LYRIC_KEY));
        assertFalse(androidLyric.contains(SettingsCatalog.OVERLAY_LYRIC_LOCKED_KEY));
        assertFalse(androidLyric.contains(SettingsCatalog.OVERLAY_LYRIC_OUTLINE_KEY));

        assertTrue(SettingsCatalog.CATEGORIES.contains(SettingsCatalog.OVERLAY_LYRIC));
        assertEquals(SettingsCatalog.LYRIC, SettingsCatalog.CATEGORIES.get(
                SettingsCatalog.CATEGORIES.indexOf(SettingsCatalog.OVERLAY_LYRIC) - 1));
    }

    @Test
    public void outlineDefaultsOnAndColourEmptyMeansAutomatic() {
        SettingSpec outline = spec(SettingsCatalog.OVERLAY_LYRIC_OUTLINE_KEY);
        SettingSpec color = spec(SettingsCatalog.OVERLAY_LYRIC_OUTLINE_COLOR_KEY);
        assertEquals(Boolean.TRUE, outline.def);
        assertEquals(SettingSpec.SWITCH, outline.type);
        assertEquals("", color.def);
        assertEquals(SettingSpec.COLOR, color.type);
        assertEquals(SettingsCatalog.OVERLAY_LYRIC, outline.category);
        assertEquals(SettingsCatalog.OVERLAY_LYRIC, color.category);
        assertEquals(SettingsCatalog.ANDROID, outline.platform);
        assertEquals(SettingsCatalog.ANDROID, color.platform);
    }

    @Test
    public void spacingDefaultsToLegacyPitchAndHeightDefaultsToAuto() {
        SettingSpec spacing = spec(SettingsCatalog.OVERLAY_LYRIC_LINE_SPACING_KEY);
        assertEquals(210, spacing.def);
        assertEquals(100, spacing.min);
        assertEquals(300, spacing.max);
        assertEquals(100, spacing.scale);
        assertEquals(SettingsCatalog.OVERLAY_LYRIC, spacing.category);
        assertEquals(SettingsCatalog.ANDROID, spacing.platform);

        // 0 is the sentinel for "auto: three rows" — the value must survive as
        // a real 0 rather than the slider's minimum.
        SettingSpec height = spec(SettingsCatalog.OVERLAY_LYRIC_HEIGHT_KEY);
        assertEquals(0, height.def);
        assertEquals(0, height.min);
        assertEquals(SettingsCatalog.OVERLAY_LYRIC, height.category);
        assertEquals(SettingsCatalog.ANDROID, height.platform);
    }

    private static SettingSpec spec(String key) {
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (key.equals(s.key)) return s;
        }
        throw new AssertionError("missing spec: " + key);
    }

    private static Set<String> keysOn(String category, String platform) {
        Set<String> out = new HashSet<>();
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (s.hidden || !category.equals(s.category) || !s.appliesTo(platform)) continue;
            out.add(s.key);
        }
        return out;
    }
}
