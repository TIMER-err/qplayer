package dev.t1m3.qplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

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

    /** Notified (on the thread that mutates the policy) when the resolved dark
     *  value changes, so the host can repaint the system bars. */
    public interface DarkListener {
        void onDark(boolean dark);
    }

    private SharedPreferences prefs;
    private boolean systemDark;
    private DarkListener darkListener;

    public void setDarkListener(DarkListener l) {
        this.darkListener = l;
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
        recompute();
        darkMode.setInterceptor((p, v) -> {
            // Normalize to Integer (QML hands us a Long) so reads compare as ints.
            p.setBypassInterceptor(asInt(v));
            prefs.edit().putInt("darkMode", asInt(p.peek())).apply();
            recompute();
        });
        monetEnabled.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            prefs.edit().putBoolean("monet", Boolean.TRUE.equals(p.peek())).apply();
        });
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
