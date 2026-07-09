package dev.t1m3.qplayer.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

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

    public final Property<Object> lyricFontSize = new Property<>(28);
    public final Property<Object> lyricFontWeight = new Property<>(2);
    public final Property<Object> lyricLineSpacing = new Property<>(200);
    public final Property<Boolean> lyricSpring = new Property<>(Boolean.TRUE);
    public final Property<Boolean> lyricScale = new Property<>(Boolean.TRUE);
    public final Property<Boolean> lyricGlow = new Property<>(Boolean.TRUE);
    public final Property<Boolean> lyricBgStatic = new Property<>(Boolean.FALSE);

    public final Property<Object> maxCacheSizeMB = new Property<>(200);

    // No system bars on desktop; kept for QML binding parity (always 0).
    public final Property<Double> topInset = new Property<>(0.0);
    public final Property<Double> bottomInset = new Property<>(0.0);

    // Desktop-only: root directory for the local library scan. Not present in
    // AppSettings (Android uses MediaStore), so QML guards on
    // `typeof settings.musicFolder !== "undefined"` to show desktop-only UI.
    public final Property<String> musicFolder = new Property<>("");

    public interface DarkListener { void onDark(boolean dark); }
    public interface MonetListener { void onMonet(boolean enabled); }
    public interface UnblockListener { void onUnblock(boolean enabled); }
    public interface MirrorListener { void onMirror(boolean enabled); }
    public interface CacheSizeListener { void onCacheMaxSizeMB(long mb); }
    public interface MusicFolderListener { void onMusicFolder(String path); }

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

    public void setDarkListener(DarkListener l) { this.darkListener = l; }
    public void setMonetListener(MonetListener l) { this.monetListener = l; }
    public void setUnblockListener(UnblockListener l) { this.unblockListener = l; }
    public void setMirrorListener(MirrorListener l) { this.mirrorListener = l; }
    public void setCacheSizeListener(CacheSizeListener l) { this.cacheSizeListener = l; }
    public void setMusicFolderListener(MusicFolderListener l) { this.musicFolderListener = l; }

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

        lyricFontSize.set(getInt("lyricFontSize", 28));
        lyricFontWeight.set(getInt("lyricFontWeight", 2));
        lyricLineSpacing.set(getInt("lyricLineSpacing", 200));
        lyricSpring.set(getBool("lyricSpring", true));
        lyricScale.set(getBool("lyricScale", true));
        lyricGlow.set(getBool("lyricGlow", true));
        lyricBgStatic.set(getBool("lyricBgStatic", false));
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
        lyricBgStatic.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            put("lyricBgStatic", Boolean.TRUE.equals(p.peek()));
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
