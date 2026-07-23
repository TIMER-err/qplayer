package dev.t1m3.qplayer.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

import dev.t1m3.qplayer.customapi.CustomApiConfig;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Desktop {@code settings} context global: the JSON-file-backed twin of the
 * Android {@code AppSettings}. Exposes the identical reactive Property surface so
 * {@code Main.qml}'s {@code settings.*} bindings are unchanged, persisting to
 * {@code <AppDirs.base()>/settings.json} instead of SharedPreferences. There are
 * no system-bar insets on desktop (both 0).
 */
public final class DesktopSettings extends QObject implements LyricCompositor.SettingsBridge {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    public final Property<Object> darkMode = new Property<>(MODE_SYSTEM);
    public final Property<Boolean> monetEnabled = new Property<>(Boolean.TRUE);
    public final Property<Boolean> resolvedDark = new Property<>(Boolean.FALSE);
    public final Property<Boolean> unblockEnabled = new Property<>(Boolean.TRUE);
    public final Property<Boolean> mirrorEnabled = new Property<>(Boolean.TRUE);
    /** Issue #15's "内置字体 / 系统默认字体" toggle. Applies live to the host-drawn
     *  lyric page (Fonts.setUseSystemFont, Skija FontMgr — works on every platform);
     *  QML's own UI text (buttons/labels/settings) only re-reads this at the next
     *  app launch (DesktopWindow.ensureView → loadFonts), and only actually changes
     *  on Windows (qml4j's uiTypefaces needs raw font-file bytes, not a Typeface
     *  object, so that half is a best-effort disk read of a system font file). */
    public final Property<Boolean> useSystemFont = new Property<>(Boolean.FALSE);
    /** Windows font-picker UI, one step further than useSystemFont above: pin the
     *  lyric page to one specific installed family instead of just "system default".
     *  Empty = no override (falls back to useSystemFont/bundled). Takes effect the
     *  same way useSystemFont does — live for the lyric page (Fonts.setCustomFamily),
     *  restart-required and Windows-only for QML's own UI text (DesktopWindow's
     *  registry lookup). Desktop-only (no Android twin): QML guards on
     *  `typeof settings.lyricFontFamily !== "undefined"`. */
    public final Property<String> lyricFontFamily = new Property<>("");
    /** Every family FontMgr knows about, populated once at startup for the picker's
     *  list (see Fonts.listFamilies()). Not persisted — cheap to regenerate. */
    public final Property<java.util.List<String>> availableFontFamilies =
            new Property<>(java.util.Collections.emptyList());

    public final Property<Object> lyricFontSize = new Property<>(28);
    public final Property<Object> lyricFontWeight = new Property<>(2);
    public final Property<Object> lyricLineSpacing = new Property<>(200);
    public final Property<Boolean> lyricSpring = new Property<>(Boolean.TRUE);
    public final Property<Boolean> lyricScale = new Property<>(Boolean.TRUE);
    public final Property<Boolean> lyricGlow = new Property<>(Boolean.TRUE);
    /** Plain-LRC (no real per-syllable timing) lines: on synthesizes an evenly-
     *  spaced per-character sweep (linear front-to-back); off lights the whole
     *  line up together as one block. See LyricConfig#linearAnimForPlainLrc. */
    public final Property<Boolean> lyricLinearAnim = new Property<>(Boolean.TRUE);
    public final Property<Boolean> lyricEdgeBlur = new Property<>(Boolean.FALSE);
    public final Property<Boolean> lyricBgStatic = new Property<>(Boolean.FALSE);
    /** Manual lyric-timing offset in ms; see {@link LyricConfig#offsetMs}. */
    public final Property<Object> lyricOffsetMs = new Property<>(0);

    public final Property<Object> maxCacheSizeMB = new Property<>(200);

    // No system bars on desktop; kept for QML binding parity (always 0).
    public final Property<Double> topInset = new Property<>(0.0);
    public final Property<Double> bottomInset = new Property<>(0.0);

    // Desktop-only: root directory for the local library scan. Not present in
    // AppSettings (Android uses MediaStore), so QML guards on
    // `typeof settings.musicFolder !== "undefined"` to show desktop-only UI.
    public final Property<String> musicFolder = new Property<>("");

    // Desktop-only: root directory for the local-library + netease disk cache
    // (covers/lyrics/audio). Independent of musicFolder — same "typeof
    // settings.cacheFolder !== 'undefined'" QML guard applies.
    public final Property<String> cacheFolder = new Property<>("");

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

    public interface DarkListener { void onDark(boolean dark); }
    public interface MonetListener { void onMonet(boolean enabled); }
    public interface UnblockListener { void onUnblock(boolean enabled); }
    public interface MirrorListener { void onMirror(boolean enabled); }
    public interface CacheSizeListener { void onCacheMaxSizeMB(long mb); }
    public interface MusicFolderListener { void onMusicFolder(String path); }
    public interface CacheFolderListener { void onCacheFolder(String path); }
    public interface CustomApiListener { void onCustomApiConfig(CustomApiConfig cfg); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private File file;
    private JsonObject store = new JsonObject();
    private boolean systemDark;
    private DarkListener darkListener;
    private MonetListener monetListener;
    private UnblockListener unblockListener;
    private MirrorListener mirrorListener;
    private CacheSizeListener cacheSizeListener;
    private MusicFolderListener musicFolderListener;
    private CacheFolderListener cacheFolderListener;
    private CustomApiListener customApiListener;

    public void setDarkListener(DarkListener l) { this.darkListener = l; }
    public void setMonetListener(MonetListener l) { this.monetListener = l; }
    public void setUnblockListener(UnblockListener l) { this.unblockListener = l; }
    public void setMirrorListener(MirrorListener l) { this.mirrorListener = l; }
    public void setCacheSizeListener(CacheSizeListener l) { this.cacheSizeListener = l; }
    public void setMusicFolderListener(MusicFolderListener l) { this.musicFolderListener = l; }
    public void setCacheFolderListener(CacheFolderListener l) { this.cacheFolderListener = l; }
    public void setCustomApiListener(CustomApiListener l) { this.customApiListener = l; }

    public boolean resolvedDarkValue() { return Boolean.TRUE.equals(resolvedDark.peek()); }

    @Override
    public float topInset() { return 0f; }

    @Override
    public boolean lyricBgStatic() { return Boolean.TRUE.equals(lyricBgStatic.peek()); }

    /** Read persisted values and seed the properties + interceptors. Call on the
     *  main thread before the render thread starts. */
    public void load() {
        file = new File(AppDirs.base(), "settings.json");
        store = readStore(file);
        systemDark = detectSystemDark();

        darkMode.set(getInt("darkMode", MODE_SYSTEM));
        monetEnabled.set(getBool("monet", true));
        unblockEnabled.set(getBool("unblock", true));
        mirrorEnabled.set(getBool("mirror", isSimplifiedChinese()));
        recompute();
        if (monetListener != null) monetListener.onMonet(Boolean.TRUE.equals(monetEnabled.peek()));
        if (unblockListener != null) unblockListener.onUnblock(Boolean.TRUE.equals(unblockEnabled.peek()));
        if (mirrorListener != null) mirrorListener.onMirror(Boolean.TRUE.equals(mirrorEnabled.peek()));

        darkMode.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            put("darkMode", asInt(p.peek()));
            recompute();
        });
        monetEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            put("monet", on);
            if (monetListener != null) monetListener.onMonet(on);
        });
        unblockEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            put("unblock", on);
            if (unblockListener != null) unblockListener.onUnblock(on);
        });
        mirrorEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            put("mirror", on);
            if (mirrorListener != null) mirrorListener.onMirror(on);
        });

        useSystemFont.set(getBool("useSystemFont", false));
        Fonts.setUseSystemFont(Boolean.TRUE.equals(useSystemFont.peek()));
        useSystemFont.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            boolean on = Boolean.TRUE.equals(p.peek());
            put("useSystemFont", on);
            // Live for the host-drawn lyric page; QML's own text needs a restart
            // (DesktopWindow re-reads this Property only at the next loadFonts()
            // call, in ensureView() — see the Property's own doc comment above).
            Fonts.setUseSystemFont(on);
        });

        availableFontFamilies.set(java.util.Arrays.asList(sortedFamilies()));
        lyricFontFamily.set(getString("lyricFontFamily", ""));
        Fonts.setCustomFamily(lyricFontFamily.peek());
        lyricFontFamily.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            String family = asStr(p.peek());
            put("lyricFontFamily", family);
            // Live for the host-drawn lyric page; QML's own text needs a restart
            // (DesktopWindow re-reads this Property only at the next loadFonts()
            // call, in ensureView()) and only actually resolves a file on Windows.
            Fonts.setCustomFamily(family);
        });

        lyricFontSize.set(getInt("lyricFontSize", 28));
        lyricFontWeight.set(getInt("lyricFontWeight", 2));
        lyricLineSpacing.set(getInt("lyricLineSpacing", 200));
        lyricSpring.set(getBool("lyricSpring", true));
        lyricScale.set(getBool("lyricScale", true));
        lyricGlow.set(getBool("lyricGlow", true));
        lyricLinearAnim.set(getBool("lyricLinearAnim", true));
        lyricEdgeBlur.set(getBool("lyricEdgeBlur", false));
        lyricBgStatic.set(getBool("lyricBgStatic", false));
        lyricOffsetMs.set(getInt("lyricOffsetMs", 0));
        applyLyricConfig();
        lyricFontSize.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            put("lyricFontSize", asInt(p.peek()));
            applyLyricConfig();
        });
        lyricFontWeight.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            put("lyricFontWeight", asInt(p.peek()));
            applyLyricConfig();
        });
        lyricLineSpacing.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            put("lyricLineSpacing", asInt(p.peek()));
            applyLyricConfig();
        });
        lyricSpring.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("lyricSpring", Boolean.TRUE.equals(p.peek()));
            applyLyricConfig();
        });
        lyricScale.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("lyricScale", Boolean.TRUE.equals(p.peek()));
            applyLyricConfig();
        });
        lyricGlow.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("lyricGlow", Boolean.TRUE.equals(p.peek()));
            applyLyricConfig();
        });
        lyricLinearAnim.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("lyricLinearAnim", Boolean.TRUE.equals(p.peek()));
            applyLyricConfig();
        });
        lyricEdgeBlur.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("lyricEdgeBlur", Boolean.TRUE.equals(p.peek()));
            applyLyricConfig();
        });
        lyricBgStatic.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("lyricBgStatic", Boolean.TRUE.equals(p.peek()));
        });
        lyricOffsetMs.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            put("lyricOffsetMs", asInt(p.peek()));
            applyLyricConfig();
        });

        maxCacheSizeMB.set(getInt("maxCacheSizeMB", 200));
        if (cacheSizeListener != null) cacheSizeListener.onCacheMaxSizeMB(asInt(maxCacheSizeMB.peek()));
        maxCacheSizeMB.setInterceptor((p, v) -> {
            p.setBypassInterceptor(asInt(v));
            int mb = asInt(p.peek());
            put("maxCacheSizeMB", mb);
            if (cacheSizeListener != null) cacheSizeListener.onCacheMaxSizeMB(mb);
        });

        String defaultMusicFolder = new File(System.getProperty("user.home", "."), "Music").getAbsolutePath();
        musicFolder.set(getString("musicFolder", defaultMusicFolder));
        musicFolder.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            Object raw = p.peek();
            String val = raw instanceof String ? (String) raw : "";
            put("musicFolder", val);
            if (musicFolderListener != null) musicFolderListener.onMusicFolder(val);
        });

        cacheFolder.set(getString("cacheFolder", AppDirs.cacheBase()));
        cacheFolder.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            Object raw = p.peek();
            String val = raw instanceof String ? (String) raw : "";
            put("cacheFolder", val);
            if (cacheFolderListener != null) cacheFolderListener.onCacheFolder(val);
        });

        customApiEnabled.set(getBool("customApiEnabled", false));
        customApiSearchUrl.set(getString("customApiSearchUrl", ""));
        customApiSearchListPath.set(getString("customApiSearchListPath", ""));
        customApiIdPath.set(getString("customApiIdPath", ""));
        customApiNamePath.set(getString("customApiNamePath", ""));
        customApiArtistPath.set(getString("customApiArtistPath", ""));
        customApiAlbumPath.set(getString("customApiAlbumPath", ""));
        customApiCoverPath.set(getString("customApiCoverPath", ""));
        customApiDurationPath.set(getString("customApiDurationPath", ""));
        customApiUrlUrl.set(getString("customApiUrlUrl", ""));
        customApiUrlResultPath.set(getString("customApiUrlResultPath", ""));
        customApiLyricUrl.set(getString("customApiLyricUrl", ""));
        customApiLyricResultPath.set(getString("customApiLyricResultPath", ""));
        customApiHeaders.set(getString("customApiHeaders", ""));
        rebuildCustomApiConfig();
        customApiEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiEnabled", Boolean.TRUE.equals(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiSearchUrl.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiSearchUrl", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiSearchListPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiSearchListPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiIdPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiIdPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiNamePath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiNamePath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiArtistPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiArtistPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiAlbumPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiAlbumPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiCoverPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiCoverPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiDurationPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiDurationPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiUrlUrl.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiUrlUrl", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiUrlResultPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiUrlResultPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiLyricUrl.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiLyricUrl", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiLyricResultPath.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiLyricResultPath", asStr(p.peek()));
            rebuildCustomApiConfig();
        });
        customApiHeaders.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("customApiHeaders", asStr(p.peek()));
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

    /** Case-insensitive sorted snapshot of every installed font family, for the
     *  Windows font-picker UI's list. */
    private static String[] sortedFamilies() {
        String[] names = Fonts.listFamilies();
        java.util.Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

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

    private void recompute() {
        int mode = asInt(darkMode.peek());
        boolean dark = mode == MODE_DARK || (mode == MODE_SYSTEM && systemDark);
        resolvedDark.set(dark);
        if (darkListener != null) darkListener.onDark(dark);
    }

    // --- JSON store helpers ---------------------------------------------------

    private static JsonObject readStore(File f) {
        try {
            if (f.isFile()) {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JsonObject o = GSON.fromJson(json, JsonObject.class);
                if (o != null) return o;
            }
        } catch (Exception e) {
            Logger.warn("settings read failed: {}", e);
        }
        return new JsonObject();
    }

    private int getInt(String key, int def) {
        try {
            return store.has(key) ? store.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private boolean getBool(String key, boolean def) {
        try {
            return store.has(key) ? store.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private String getString(String key, String def) {
        try {
            return store.has(key) ? store.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private void put(String key, int v) { store.addProperty(key, v); persist(); }
    private void put(String key, boolean v) { store.addProperty(key, v); persist(); }
    private void put(String key, String v) { store.addProperty(key, v); persist(); }

    private void persist() {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            Files.write(file.toPath(), GSON.toJson(store).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.warn("settings write failed: {}", e);
        }
    }

    private boolean detectSystemDark() {
        // Best-effort: a GTK/Qt theme name hint, else default light. Refined later.
        String t = System.getenv("GTK_THEME");
        return t != null && t.toLowerCase().contains("dark");
    }

    private static boolean isSimplifiedChinese() {
        java.util.Locale loc = java.util.Locale.getDefault();
        if (!"zh".equals(loc.getLanguage())) return false;
        if ("Hant".equalsIgnoreCase(loc.getScript())) return false;
        String c = loc.getCountry();
        return !("TW".equals(c) || "HK".equals(c) || "MO".equals(c));
    }

    private static int asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : MODE_SYSTEM;
    }
}
