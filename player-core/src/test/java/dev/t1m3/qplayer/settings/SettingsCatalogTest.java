package dev.t1m3.qplayer.settings;

import dev.t1m3.qplayer.i18n.I18n;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsCatalogTest {

    @Test
    public void systemTitleBarIsDesktopOnlyAndDefaultsOff() {
        SettingSpec spec = setting("windowDecorated");

        assertEquals(Boolean.FALSE, spec.def);
        assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
        assertFalse(spec.appliesTo(SettingsCatalog.ANDROID));
    }

    @Test
    public void lyricSizingUsesDottedFixedStepSliders() {
        SettingSpec fontSize = setting("lyricFontSize");
        SettingSpec lineSpacing = setting("lyricLineSpacing");

        assertEquals(SettingSpec.SLIDER, fontSize.type);
        assertTrue(fontSize.dots);
        assertEquals(1, fontSize.step);

        assertEquals(SettingSpec.SLIDER, lineSpacing.type);
        assertTrue(lineSpacing.dots);
        assertEquals(5, lineSpacing.step);
    }

    @Test
    public void maximumCacheUsesPlainSlider() {
        SettingSpec cache = setting("maxCacheSizeMB");

        assertEquals(SettingSpec.SLIDER, cache.type);
        assertFalse(cache.dots);
        assertEquals(50, cache.min);
        assertEquals(1024, cache.max);
    }

    @Test
    public void pageTransitionDefaultsToZoomAndOffersAccessibleFallback() {
        SettingSpec transition = setting(SettingsCatalog.PAGE_TRANSITION_KEY);

        assertEquals(SettingSpec.DROPDOWN, transition.type);
        assertEquals(SettingsCatalog.PAGE_TRANSITION_ZOOM, transition.def);
        // Options are language keys; the catalog resolves them at render time.
        assertEquals("settings.pageTransition.zoom", transition.options.get(0));
        assertTrue(transition.options.contains("settings.pageTransition.none"));
        assertEquals("Zoom In / Out", I18n.tr(transition.options.get(0)));
        assertTrue(transition.appliesTo(SettingsCatalog.DESKTOP));
        assertTrue(transition.appliesTo(SettingsCatalog.ANDROID));
    }

    @Test
    public void temperaUsesTheExistingLyricTabAndDoesNotAddMobileTabs() {
        // Spelled out rather than counted: a diff here says which tab appeared.
        // 桌面歌词 is a real tab but a desktop-only one — SettingsCore.categories()
        // drops it on a host with no rows for it.
        assertEquals(java.util.Arrays.asList(
                        SettingsCatalog.APPEARANCE, SettingsCatalog.PLAYBACK,
                        SettingsCatalog.LYRIC, SettingsCatalog.DESKTOP_LYRIC,
                        SettingsCatalog.LOCAL, SettingsCatalog.PLUGINS,
                        SettingsCatalog.ABOUT),
                SettingsCatalog.CATEGORIES);
        assertFalse(SettingsCatalog.specs().stream().anyMatch(spec -> "temperaEnabled".equals(spec.key)));
        for (String key : new String[]{"temperaWholeLine",
                "temperaGlyphSettleStretch", "temperaImages"}) {
            SettingSpec spec = setting(key);
            assertEquals(SettingsCatalog.LYRIC, spec.category);
            assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
            assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
        }
    }

    private static SettingSpec setting(String key) {
        return SettingsCatalog.specs().stream()
                .filter(candidate -> key.equals(candidate.key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " setting missing"));
    }
}
