package dev.t1m3.qplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

import dev.t1m3.qplayer.android.lyric.LyricConfig;

/**
 * QML-facing app settings ({@code settings} context global): dark-mode policy and
 * Monet toggle, persisted to SharedPreferences. {@link #resolvedDark} folds the
 * mode and the live system night setting into the boolean that drives
 * StyleManager.isDarkTheme via a QML Binding.
 *
 * <p>Property writes must happen on the render thread. {@link #load} runs once
 * before the GL thread starts; QML writes run on the render thread; system config
 * changes are funnelled back through the render thread by the caller.
 */
public final class AppSettings extends QObject {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    // Object-typed: QML numeric writes arrive as Long, so a Property<Integer> would
    // ClassCastException in the interceptor. The interceptor normalizes to Integer.
    /** 0 = follow system, 1 = light, 2 = dark. */
    public final Property<Object> darkMode = new Property<>(MODE_SYSTEM);
    public final Property<Boolean> monetEnabled = new Property<>(Boolean.TRUE);
    public final Property<Boolean> resolvedDark = new Property<>(Boolean.FALSE);
    /** Source-switching for grey/VIP/trial netease tracks (gdstudio / bodian / kuwo). */
    public final Property<Boolean> unblockEnabled = new Property<>(Boolean.TRUE);

    // Lyric-page typography (Object-typed: QML numeric writes arrive as Long).
    /** Lyric main font size in px (14–40). */
    public final Property<Object> lyricFontSize = new Property<>(28);
    /** Lyric font weight: 0 Thin, 1 Light, 2 Regular, 3 Medium. */
    public final Property<Object> lyricFontWeight = new Property<>(2);
    /** Lyric line-height as a percent of font size (100–250 → 1.0×–2.5×). */
    public final Property<Object> lyricLineSpacing = new Property<>(200);
    /** Apple-style spring physics (scroll + per-syllable lift) on the lyric page. */
    public final Property<Boolean> lyricSpring = new Property<>(Boolean.TRUE);
    /** Active-line depth scaling (emphasis zoom) on the lyric page. */
    public final Property<Boolean> lyricScale = new Property<>(Boolean.TRUE);
    /** White glow behind sung syllables on the lyric page. */
    public final Property<Boolean> lyricGlow = new Property<>(Boolean.TRUE);
    /** Static fluid background (render once + cache) vs. animated. */
    public final Property<Boolean> lyricBgStatic = new Property<>(Boolean.FALSE);

    // System-bar insets in QML logical units (px / density), for edge-to-edge layout:
    // the top bar drops below the status bar, the bottom nav clears the gesture bar.
    public final Property<Double> topInset = new Property<>(0.0);
    public final Property<Double> bottomInset = new Property<>(0.0);

    /** Set the system-bar insets (render thread). */
    public void setInsets(double top, double bottom) {
        topInset.set(top);
        bottomInset.set(bottom);
    }

    /** Notified (on the thread that mutates the policy) when the resolved dark
     *  value changes, so the host can repaint the system bars. */
    public interface DarkListener {
        void onDark(boolean dark);
    }

    /** Notified when the Monet toggle changes so the host can re-apply the seed. */
    public interface MonetListener {
        void onMonet(boolean enabled);
    }

    /** Notified when the source-switching toggle changes. */
    public interface UnblockListener {
        void onUnblock(boolean enabled);
    }

    private SharedPreferences prefs;
    private boolean systemDark;
    private DarkListener darkListener;
    private MonetListener monetListener;
    private UnblockListener unblockListener;

    public void setDarkListener(DarkListener l) {
        this.darkListener = l;
    }

    public void setMonetListener(MonetListener l) {
        this.monetListener = l;
    }

    public void setUnblockListener(UnblockListener l) {
        this.unblockListener = l;
    }

    public boolean resolvedDarkValue() {
        return Boolean.TRUE.equals(resolvedDark.peek());
    }

    /** Read persisted values + current system night mode and seed the properties.
     *  Call on the main thread before the renderer starts. */
    public void load(Context ctx) {
        prefs = ctx.getSharedPreferences("qplayer.settings", Context.MODE_PRIVATE);
        systemDark = isSystemDark(ctx);
        darkMode.set(prefs.getInt("darkMode", MODE_SYSTEM));
        monetEnabled.set(prefs.getBoolean("monet", true));
        unblockEnabled.set(prefs.getBoolean("unblock", true));
        recompute();
        if (monetListener != null) monetListener.onMonet(Boolean.TRUE.equals(monetEnabled.peek()));
        if (unblockListener != null) unblockListener.onUnblock(Boolean.TRUE.equals(unblockEnabled.peek()));
        darkMode.setInterceptor((p, v) -> {
            // Normalize to Integer (QML hands us a Long) so reads compare as ints.
            p.setBypassInterceptor(asInt(v));
            prefs.edit().putInt("darkMode", asInt(p.peek())).apply();
            recompute();
        });
        monetEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            prefs.edit().putBoolean("monet", on).apply();
            if (monetListener != null) monetListener.onMonet(on);
        });
        unblockEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            prefs.edit().putBoolean("unblock", on).apply();
            if (unblockListener != null) unblockListener.onUnblock(on);
        });

        lyricFontSize.set(prefs.getInt("lyricFontSize", 28));
        lyricFontWeight.set(prefs.getInt("lyricFontWeight", 2));
        lyricLineSpacing.set(prefs.getInt("lyricLineSpacing", 200));
        lyricSpring.set(prefs.getBoolean("lyricSpring", true));
        lyricScale.set(prefs.getBoolean("lyricScale", true));
        lyricGlow.set(prefs.getBoolean("lyricGlow", true));
        lyricBgStatic.set(prefs.getBoolean("lyricBgStatic", false));
        applyLyricConfig();
        lyricFontSize.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            prefs.edit().putInt("lyricFontSize", asInt(p.peek())).apply();
            applyLyricConfig();
        });
        lyricFontWeight.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            prefs.edit().putInt("lyricFontWeight", asInt(p.peek())).apply();
            applyLyricConfig();
        });
        lyricLineSpacing.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            prefs.edit().putInt("lyricLineSpacing", asInt(p.peek())).apply();
            applyLyricConfig();
        });
        lyricSpring.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("lyricSpring", Boolean.TRUE.equals(p.peek())).apply();
            applyLyricConfig();
        });
        lyricScale.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("lyricScale", Boolean.TRUE.equals(p.peek())).apply();
            applyLyricConfig();
        });
        lyricGlow.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("lyricGlow", Boolean.TRUE.equals(p.peek())).apply();
            applyLyricConfig();
        });
        lyricBgStatic.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("lyricBgStatic", Boolean.TRUE.equals(p.peek())).apply();
        });
    }

    /** Push the lyric typography settings into the host renderer's config. */
    private void applyLyricConfig() {
        LyricConfig c = LyricConfig.instance;
        c.lyricFontSize.setValue(asInt(lyricFontSize.peek()));
        int w = Math.max(0, Math.min(3, asInt(lyricFontWeight.peek())));
        c.fontWeight.setValue(LyricConfig.FontWeight.values()[w]);
        c.lineSpacing.setValue(asInt(lyricLineSpacing.peek()) / 100f);
        c.springPhysics.setValue(Boolean.TRUE.equals(lyricSpring.peek()));
        c.scaleEmphasis.setValue(Boolean.TRUE.equals(lyricScale.peek()));
        c.glow.setValue(Boolean.TRUE.equals(lyricGlow.peek()));
    }

    /** System night mode changed; call on the render thread. */
    public void applySystemDark(boolean dark) {
        if (systemDark == dark) return;
        systemDark = dark;
        recompute();
    }

    private void recompute() {
        int mode = asInt(darkMode.peek());
        boolean dark = mode == MODE_DARK || (mode == MODE_SYSTEM && systemDark);
        resolvedDark.set(dark);
        if (darkListener != null) darkListener.onDark(dark);
    }

    public static boolean isSystemDark(Context ctx) {
        int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : MODE_SYSTEM;
    }
}
