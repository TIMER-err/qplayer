package dev.t1m3.qplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

import dev.t1m3.qplayer.customapi.CustomApiConfig;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;

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
public final class AppSettings extends QObject
        implements dev.t1m3.qplayer.lyric.skia.LyricCompositor.SettingsBridge {

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
    /** Route GitHub release (update) downloads through the gh-proxy mirror. Default on
     *  for Simplified-Chinese systems, where github.com downloads are slow/blocked. */
    public final Property<Boolean> mirrorEnabled = new Property<>(Boolean.TRUE);
    /** Issue #15's "内置字体 / 系统默认字体" toggle. Applies live to the host-drawn
     *  lyric page (Fonts.setUseSystemFont, Skija FontMgr). Unlike desktop, QML's own
     *  UI text (buttons/labels) does NOT follow this yet — Android's font loading in
     *  QmlGLSurfaceView/QPlayerActivity is a separate, independent path not wired up
     *  here; only the Skija-side lyric-page half is implemented on this platform. */
    public final Property<Boolean> useSystemFont = new Property<>(Boolean.FALSE);

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
    /** Plain-LRC (no real per-syllable timing) lines: on synthesizes an evenly-
     *  spaced per-character sweep (linear front-to-back); off lights the whole
     *  line up together as one block. See LyricConfig#linearAnimForPlainLrc. */
    public final Property<Boolean> lyricLinearAnim = new Property<>(Boolean.TRUE);
    /** Apple-Music edge blur: unfocused lyric lines blur progressively toward the edges. */
    public final Property<Boolean> lyricEdgeBlur = new Property<>(Boolean.FALSE);
    /** Static fluid background (render once + cache) vs. animated. */
    public final Property<Boolean> lyricBgStatic = new Property<>(Boolean.FALSE);
    /** Manual lyric-timing offset in ms; see {@link LyricConfig#offsetMs}. */
    public final Property<Object> lyricOffsetMs = new Property<>(0);

    // Cache settings (Object-typed: QML numeric writes arrive as Long).
    /** Maximum disk cache size in MB (audio + lyrics + images). 0 = unlimited. */
    public final Property<Object> maxCacheSizeMB = new Property<>(200);

    // System-bar insets in QML logical units (px / density), for edge-to-edge layout:
    // the top bar drops below the status bar, the bottom nav clears the gesture bar.
    public final Property<Double> topInset = new Property<>(0.0);
    public final Property<Double> bottomInset = new Property<>(0.0);

    // User-configured third-party "custom API" music source (independent of the
    // built-in netease source) — see dev.t1m3.qplayer.customapi.CustomApiConfig
    // for the URL-template + JSON-path-mapping adapter this feeds.
    public final Property<Boolean> customApiEnabled = new Property<>(Boolean.FALSE);
    public final Property<String> customApiSearchUrl = new Property<>("");
    public final Property<String> customApiSearchListPath = new Property<>("");
    public final Property<String> customApiIdPath = new Property<>("");
    public final Property<String> customApiNamePath = new Property<>("");
    public final Property<String> customApiArtistPath = new Property<>("");
    public final Property<String> customApiAlbumPath = new Property<>("");
    public final Property<String> customApiCoverPath = new Property<>("");
    public final Property<String> customApiDurationPath = new Property<>("");
    public final Property<String> customApiUrlUrl = new Property<>("");
    public final Property<String> customApiUrlResultPath = new Property<>("");
    public final Property<String> customApiLyricUrl = new Property<>("");
    public final Property<String> customApiLyricResultPath = new Property<>("");
    public final Property<String> customApiHeaders = new Property<>("");

    /** Set the system-bar insets (render thread). */
    public void setInsets(double top, double bottom) {
        topInset.set(top);
        bottomInset.set(bottom);
    }

    // --- LyricCompositor.SettingsBridge: the two values the lyric page reads ---

    @Override
    public float topInset() {
        Double t = topInset.peek();
        return t == null ? 0f : t.floatValue();
    }

    @Override
    public boolean lyricBgStatic() {
        return Boolean.TRUE.equals(lyricBgStatic.peek());
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

    /** Notified when source-switching toggle changes. */
    public interface UnblockListener {
        void onUnblock(boolean enabled);
    }

    /** Notified when the update-mirror toggle changes. */
    public interface MirrorListener {
        void onMirror(boolean enabled);
    }

    /** Notified when max cache size changes. */
    public interface CacheSizeListener {
        void onCacheMaxSizeMB(long mb);
    }

    /** Notified (with a fresh snapshot) whenever the custom-API-source config changes. */
    public interface CustomApiListener {
        void onCustomApiConfig(CustomApiConfig cfg);
    }

    private SharedPreferences prefs;
    private boolean systemDark;
    private DarkListener darkListener;
    private MonetListener monetListener;
    private UnblockListener unblockListener;
    private MirrorListener mirrorListener;
    private CacheSizeListener cacheSizeListener;
    private CustomApiListener customApiListener;

    public void setDarkListener(DarkListener l) {
        this.darkListener = l;
    }

    public void setMonetListener(MonetListener l) {
        this.monetListener = l;
    }

    public void setUnblockListener(UnblockListener l) {
        this.unblockListener = l;
    }

    public void setMirrorListener(MirrorListener l) {
        this.mirrorListener = l;
    }

    public void setCacheSizeListener(CacheSizeListener l) {
        this.cacheSizeListener = l;
    }

    public void setCustomApiListener(CustomApiListener l) {
        this.customApiListener = l;
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
        mirrorEnabled.set(prefs.getBoolean("mirror", isSimplifiedChinese()));
        recompute();
        if (monetListener != null) monetListener.onMonet(Boolean.TRUE.equals(monetEnabled.peek()));
        if (unblockListener != null) unblockListener.onUnblock(Boolean.TRUE.equals(unblockEnabled.peek()));
        if (mirrorListener != null) mirrorListener.onMirror(Boolean.TRUE.equals(mirrorEnabled.peek()));
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
        mirrorEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            prefs.edit().putBoolean("mirror", on).apply();
            if (mirrorListener != null) mirrorListener.onMirror(on);
        });

        useSystemFont.set(prefs.getBoolean("useSystemFont", false));
        Fonts.setUseSystemFont(Boolean.TRUE.equals(useSystemFont.peek()));
        useSystemFont.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            prefs.edit().putBoolean("useSystemFont", on).apply();
            Fonts.setUseSystemFont(on);
        });

        lyricFontSize.set(prefs.getInt("lyricFontSize", 28));
        lyricFontWeight.set(prefs.getInt("lyricFontWeight", 2));
        lyricLineSpacing.set(prefs.getInt("lyricLineSpacing", 200));
        lyricSpring.set(prefs.getBoolean("lyricSpring", true));
        lyricScale.set(prefs.getBoolean("lyricScale", true));
        lyricGlow.set(prefs.getBoolean("lyricGlow", true));
        lyricLinearAnim.set(prefs.getBoolean("lyricLinearAnim", true));
        lyricEdgeBlur.set(prefs.getBoolean("lyricEdgeBlur", false));
        lyricBgStatic.set(prefs.getBoolean("lyricBgStatic", false));
        lyricOffsetMs.set(prefs.getInt("lyricOffsetMs", 0));
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
        lyricLinearAnim.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("lyricLinearAnim", Boolean.TRUE.equals(p.peek())).apply();
            applyLyricConfig();
        });
        lyricEdgeBlur.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("lyricEdgeBlur", Boolean.TRUE.equals(p.peek())).apply();
            applyLyricConfig();
        });
        lyricBgStatic.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("lyricBgStatic", Boolean.TRUE.equals(p.peek())).apply();
        });
        lyricOffsetMs.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            prefs.edit().putInt("lyricOffsetMs", asInt(p.peek())).apply();
            applyLyricConfig();
        });

        // Cache settings.
        maxCacheSizeMB.set(prefs.getInt("maxCacheSizeMB", 200));
        if (cacheSizeListener != null) cacheSizeListener.onCacheMaxSizeMB(asInt(maxCacheSizeMB.peek()));
        maxCacheSizeMB.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            int mb = asInt(p.peek());
            prefs.edit().putInt("maxCacheSizeMB", mb).apply();
            if (cacheSizeListener != null) cacheSizeListener.onCacheMaxSizeMB(mb);
        });

        customApiEnabled.set(prefs.getBoolean("customApiEnabled", false));
        customApiSearchUrl.set(prefs.getString("customApiSearchUrl", ""));
        customApiSearchListPath.set(prefs.getString("customApiSearchListPath", ""));
        customApiIdPath.set(prefs.getString("customApiIdPath", ""));
        customApiNamePath.set(prefs.getString("customApiNamePath", ""));
        customApiArtistPath.set(prefs.getString("customApiArtistPath", ""));
        customApiAlbumPath.set(prefs.getString("customApiAlbumPath", ""));
        customApiCoverPath.set(prefs.getString("customApiCoverPath", ""));
        customApiDurationPath.set(prefs.getString("customApiDurationPath", ""));
        customApiUrlUrl.set(prefs.getString("customApiUrlUrl", ""));
        customApiUrlResultPath.set(prefs.getString("customApiUrlResultPath", ""));
        customApiLyricUrl.set(prefs.getString("customApiLyricUrl", ""));
        customApiLyricResultPath.set(prefs.getString("customApiLyricResultPath", ""));
        customApiHeaders.set(prefs.getString("customApiHeaders", ""));
        rebuildCustomApiConfig();
        customApiEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("customApiEnabled", Boolean.TRUE.equals(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiSearchUrl.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiSearchUrl", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiSearchListPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiSearchListPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiIdPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiIdPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiNamePath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiNamePath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiArtistPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiArtistPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiAlbumPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiAlbumPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiCoverPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiCoverPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiDurationPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiDurationPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiUrlUrl.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiUrlUrl", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiUrlResultPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiUrlResultPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiLyricUrl.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiLyricUrl", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiLyricResultPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiLyricResultPath", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
        customApiHeaders.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putString("customApiHeaders", asStr(p.peek())).apply();
            rebuildCustomApiConfig();
        });
    }

    /** Rebuild a {@link CustomApiConfig} from the current field values and push it
     *  to the listener — called once after load() seeds all fields, and again from
     *  every field's interceptor so a live edit takes effect without a restart. */
    private void rebuildCustomApiConfig() {
        if (customApiListener == null) return;
        CustomApiConfig cfg = new CustomApiConfig();
        cfg.enabled = Boolean.TRUE.equals(customApiEnabled.peek());
        cfg.searchUrl = asStr(customApiSearchUrl.peek());
        cfg.searchListPath = asStr(customApiSearchListPath.peek());
        cfg.idPath = asStr(customApiIdPath.peek());
        cfg.namePath = asStr(customApiNamePath.peek());
        cfg.artistPath = asStr(customApiArtistPath.peek());
        cfg.albumPath = asStr(customApiAlbumPath.peek());
        cfg.coverPath = asStr(customApiCoverPath.peek());
        cfg.durationPath = asStr(customApiDurationPath.peek());
        cfg.urlUrl = asStr(customApiUrlUrl.peek());
        cfg.urlResultPath = asStr(customApiUrlResultPath.peek());
        cfg.lyricUrl = asStr(customApiLyricUrl.peek());
        cfg.lyricResultPath = asStr(customApiLyricResultPath.peek());
        cfg.extraHeaders = asStr(customApiHeaders.peek());
        customApiListener.onCustomApiConfig(cfg);
    }

    private static String asStr(Object v) {
        return v instanceof String ? (String) v : "";
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
        c.linearAnimForPlainLrc.setValue(Boolean.TRUE.equals(lyricLinearAnim.peek()));
        c.edgeBlur.setValue(Boolean.TRUE.equals(lyricEdgeBlur.peek()));
        c.offsetMs.setValue(asInt(lyricOffsetMs.peek()));
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

    /** Whether the system locale is Simplified Chinese (zh, excluding the Traditional
     *  TW/HK/MO regions) — used to default the GitHub download mirror on. */
    private static boolean isSimplifiedChinese() {
        java.util.Locale loc = java.util.Locale.getDefault();
        if (!"zh".equals(loc.getLanguage())) return false;
        if ("Hant".equalsIgnoreCase(loc.getScript())) return false;
        String c = loc.getCountry();
        return !("TW".equals(c) || "HK".equals(c) || "MO".equals(c));
    }

    public static boolean isSystemDark(Context ctx) {
        int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : MODE_SYSTEM;
    }
}
