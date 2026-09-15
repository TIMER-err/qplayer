package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.lyric.skia.DesktopLyricRenderer;
import io.github.timer_err.qml4j.runtime.color.StyleManager;

import java.util.Map;

/** Immutable Monet scheme roles shared by the QML chrome and Java lyric pass. */
final class DesktopLyricPalette {

    final String surface;
    final String outline;
    final String primary;
    final String onSurfaceVariant;
    final String secondary;
    final String secondaryContainer;
    final String onSecondaryContainer;
    final DesktopLyricRenderer.Colors lyricColors;

    private DesktopLyricPalette(String surface, String outline, String primary,
                                String onSurfaceVariant, String secondary,
                                String secondaryContainer, String onSecondaryContainer,
                                String shadow, String sungOverride, String unsungOverride) {
        this.surface = surface;
        this.outline = outline;
        this.primary = primary;
        this.onSurfaceVariant = onSurfaceVariant;
        this.secondary = secondary;
        this.secondaryContainer = secondaryContainer;
        this.onSecondaryContainer = onSecondaryContainer;
        // The chrome roles above always stay Monet — only the lyric text itself
        // honours the user's fixed colours, and an empty override keeps the Monet
        // role that has always been used for that slot.
        String sung = override(sungOverride, primary);
        String unsungPrevious = override(unsungOverride, onSurfaceVariant);
        String unsungNext = override(unsungOverride, secondary);
        this.lyricColors = new DesktopLyricRenderer.Colors(
                argb(unsungPrevious), argb(sung), argb(unsungNext), argb(shadow));
    }

    private static String override(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    static DesktopLyricPalette capture(boolean dark) {
        return capture(scheme(dark), dark, "", "");
    }

    static Object scheme(boolean dark) {
        StyleManager manager = (StyleManager) StyleManager.__instance();
        return (dark ? manager.darkScheme : manager.lightScheme).peek();
    }

    static DesktopLyricPalette capture(Object raw, boolean dark,
                                       String sungOverride, String unsungOverride) {
        Map<?, ?> scheme = raw instanceof Map ? (Map<?, ?>) raw : null;
        return new DesktopLyricPalette(
                role(scheme, "surfaceContainerHigh", dark ? "#28282b" : "#e9e7ec"),
                role(scheme, "outlineVariant", dark ? "#49454f" : "#cac4d0"),
                role(scheme, "primary", dark ? "#d0bcff" : "#6750a4"),
                role(scheme, "onSurfaceVariantColor", dark ? "#cac4d0" : "#49454f"),
                role(scheme, "secondary", dark ? "#ccc2dc" : "#625b71"),
                role(scheme, "secondaryContainer", dark ? "#4a4458" : "#e8def8"),
                role(scheme, "onSecondaryContainerColor", dark ? "#e8def8" : "#1d192b"),
                role(scheme, "shadow", "#000000"),
                sungOverride, unsungOverride);
    }

    private static String role(Map<?, ?> scheme, String name, String fallback) {
        if (scheme == null) return fallback;
        Object value = scheme.get(name);
        return value instanceof String && !((String) value).isEmpty()
                ? (String) value : fallback;
    }

    private static int argb(String value) {
        String hex = value == null ? "" : value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            long parsed = Long.parseLong(hex, 16);
            if (hex.length() <= 6) parsed |= 0xFF000000L;
            return (int) parsed;
        } catch (NumberFormatException ignored) {
            return 0xFFFFFFFF;
        }
    }

}
