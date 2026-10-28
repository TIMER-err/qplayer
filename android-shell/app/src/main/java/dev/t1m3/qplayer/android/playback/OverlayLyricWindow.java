package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageButton;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import dev.t1m3.qplayer.android.ui.QPlayerActivity;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import io.github.timer_err.qml4j.android.R;
import io.github.timer_err.qml4j.runtime.color.StyleManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Always-on-top lyric panel using {@code SYSTEM_ALERT_WINDOW}. No root, no
 * Xposed: the same overlay permission QQ Music uses for desktop lyrics.
 *
 * <p>Looks like the PC desktop lyric: a rounded Monet surface with a hairline
 * outline, previous / current / next rows (current one swept syllable by
 * syllable, translation tucked under it) and a cubic ease-out slide on line
 * changes. The painting is a port of {@code DesktopLyricRenderer} onto the
 * platform {@link Canvas}.
 */
public final class OverlayLyricWindow {

    private static volatile boolean wanted;
    private static volatile SettingsCore settings;
    private static OverlayLyricWindow instance;

    private final Context app;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private LyricView view;
    private WindowManager.LayoutParams params;
    private boolean attached;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean dragging;
    private View controls;
    private WindowManager.LayoutParams controlParams;
    private PlayerController controller;
    // Overlay drawing used to ride PlaybackService's 100 ms checkpoint worker
    // plus View.postInvalidateOnAnimation(). Both stall when the Activity is
    // backgrounded: OEMs pause Choreographer vsync for the overlay, and the
    // checkpoint executor can be deferred. Drive frames from the main Handler
    // instead so a foreground PlaybackService keeps lyrics moving.
    private static final long FRAME_MS = 16L;
    private boolean ticking;
    private final Runnable frameTick = this::onFrame;
    private final Consumer<List<LyricLine>> lyricsListener = ignored ->
            main.post(this::invalidateView);

    public static void bindSettings(SettingsCore core) {
        settings = core;
        OverlayLyricWindow live = instance;
        if (live != null) live.main.post(live::applyStyle);
    }

    public static void restyle() {
        OverlayLyricWindow live = instance;
        if (live != null) live.main.post(live::applyStyle);
    }

    /** Activity kept this overlay alive across rotation via configChanges.
     *  Re-clamp the stored fractional position onto the new screen. */
    public static void onConfigurationChanged() {
        OverlayLyricWindow live = instance;
        if (live != null) live.main.post(live::relayoutForScreen);
    }

    public static void setWanted(Context ctx, boolean on) {
        wanted = on;
        OverlayLyricWindow live = instance;
        if (live != null) live.main.post(live::applyWanted);
        else if (on && PlaybackService.isRunning()) attach(ctx);
    }

    public static boolean isWanted() {
        return wanted;
    }

    public static boolean isLocked() {
        SettingsCore s = settings;
        return s != null && s.bool(SettingsCatalog.OVERLAY_LYRIC_LOCKED_KEY);
    }

    public static void toggleLocked() {
        SettingsCore s = settings;
        if (s == null) return;
        s.setValue(SettingsCatalog.OVERLAY_LYRIC_LOCKED_KEY, !s.bool(SettingsCatalog.OVERLAY_LYRIC_LOCKED_KEY));
    }

    static boolean canDraw(Context ctx) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    public static void attach(Context ctx) {
        if (instance != null) return;
        instance = new OverlayLyricWindow(ctx.getApplicationContext());
        instance.applyWanted();
    }

    public static void detach() {
        OverlayLyricWindow live = instance;
        instance = null;
        if (live != null) live.main.post(() -> {
            live.unbindLyrics();
            live.controller = null;
            live.hide();
        });
    }

    public static void tick(PlayerController controller) {
        OverlayLyricWindow live = instance;
        if (live != null) live.update(controller);
    }

    private OverlayLyricWindow(Context app) {
        this.app = app;
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = app.getSharedPreferences("overlay.lyric", Context.MODE_PRIVATE);
    }

    private void applyWanted() {
        if (wanted && canDraw(app)) show();
        else hide();
    }

    private void show() {
        if (attached || wm == null) return;
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        float d = dm.density;

        view = new LyricView(app);
        view.setOnTouchListener(this::onTouch);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                Math.round(dm.widthPixels - 32 * d),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        applyStyle();
        restorePosition();
        try {
            wm.addView(view, params);
            attached = true;
            scheduleFrame();
        } catch (Throwable ignored) {
            attached = false;
            view = null;
        }
    }

    private void applyStyle() {
        if (view == null || params == null) return;
        SettingsCore s = settings;
        int fontPx = s != null ? s.intOf(SettingsCatalog.OVERLAY_LYRIC_FONT_SIZE_KEY) : 16;
        if (fontPx < 12) fontPx = 16;
        int widthPct = s != null ? s.intOf(SettingsCatalog.OVERLAY_LYRIC_WIDTH_KEY) : 90;
        if (widthPct < 50) widthPct = 90;
        int spacingPct = s != null ? s.intOf(SettingsCatalog.OVERLAY_LYRIC_LINE_SPACING_KEY) : 210;
        spacingPct = Math.max(100, Math.min(300, spacingPct));
        int heightDp = s != null ? s.intOf(SettingsCatalog.OVERLAY_LYRIC_HEIGHT_KEY) : 0;
        heightDp = Math.max(0, Math.min(200, heightDp));
        int opacityPct = s != null ? s.intOf(SettingsCatalog.OVERLAY_LYRIC_BG_OPACITY_KEY) : 63;
        opacityPct = Math.max(0, Math.min(100, opacityPct));
        String sung = s != null ? s.str(SettingsCatalog.OVERLAY_LYRIC_SUNG_COLOR_KEY) : "";
        String unsung = s != null ? s.str(SettingsCatalog.OVERLAY_LYRIC_UNSUNG_COLOR_KEY) : "";
        boolean outline = s == null || s.bool(SettingsCatalog.OVERLAY_LYRIC_OUTLINE_KEY);
        String outlineColor = s != null ? s.str(SettingsCatalog.OVERLAY_LYRIC_OUTLINE_COLOR_KEY) : "";
        boolean dark = s == null || !Boolean.FALSE.equals(s.resolvedDark.peek());
        boolean locked = s != null && s.bool(SettingsCatalog.OVERLAY_LYRIC_LOCKED_KEY);

        view.configure(fontPx, opacityPct / 100f, parseColor(sung, 0), parseColor(unsung, 0), dark,
                outline, parseColor(outlineColor, 0), spacingPct / 100f, heightDp);

        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        params.width = Math.round(dm.widthPixels * widthPct / 100f);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (locked) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.flags = flags;
        if (attached) {
            try { wm.updateViewLayout(view, params); } catch (Throwable ignored) {}
        }
        clampPosition();
        if (locked) hideControls();
        else placeControls();
    }

    private void relayoutForScreen() {
        if (!attached || params == null) return;
        applyStyle();
        restorePosition();
        if (attached) {
            try { wm.updateViewLayout(view, params); } catch (Throwable ignored) {}
        }
        placeControls();
    }

    private void restorePosition() {
        if (params == null) return;
        float d = app.getResources().getDisplayMetrics().density;
        int[] screen = overlayScreenSize();
        int lyricW = overlayWidth();
        int lyricH = overlayHeight();
        int savedW = prefs.getInt("sw", screen[0]);
        int savedH = prefs.getInt("sh", screen[1]);
        float nx = prefs.contains("nx")
                ? prefs.getFloat("nx", 0.5f)
                : storedFraction(prefs.getInt("x", Math.round(16 * d)), savedW, lyricW, 0.5f);
        float ny = prefs.contains("ny")
                ? prefs.getFloat("ny", 0.72f)
                : storedFraction(prefs.getInt("y", Math.round(savedH * 0.72f)), savedH, lyricH, 0.72f);
        params.x = mappedPosition(nx, screen[0], lyricW);
        params.y = mappedPosition(ny, screen[1], lyricH);
    }

    private void persistPosition() {
        if (params == null) return;
        int[] screen = overlayScreenSize();
        prefs.edit()
                .putFloat("nx", storedFraction(params.x, screen[0], overlayWidth(), 0.5f))
                .putFloat("ny", storedFraction(params.y, screen[1], overlayHeight(), 0.72f))
                .putInt("x", params.x)
                .putInt("y", params.y)
                .putInt("sw", screen[0])
                .putInt("sh", screen[1])
                .apply();
    }

    private void clampPosition() {
        if (params == null) return;
        int[] screen = overlayScreenSize();
        int x = mappedPosition(storedFraction(params.x, screen[0], overlayWidth(), 0.5f),
                screen[0], overlayWidth());
        int y = mappedPosition(storedFraction(params.y, screen[1], overlayHeight(), 0.72f),
                screen[1], overlayHeight());
        if (params.x == x && params.y == y) return;
        params.x = x;
        params.y = y;
        if (attached) {
            try { wm.updateViewLayout(view, params); } catch (Throwable ignored) {}
        }
    }

    /** 0–1 fraction of the movable range [0, screen - size]. */
    static int mappedPosition(float fraction, int screen, int size) {
        int max = Math.max(0, screen - Math.max(1, size));
        if (max == 0) return 0;
        float f = Math.max(0f, Math.min(1f, fraction));
        return Math.round(f * max);
    }

    static float storedFraction(int pos, int screen, int size, float fallback) {
        int max = Math.max(0, screen - Math.max(1, size));
        if (max == 0) return fallback;
        return Math.max(0f, Math.min(1f, pos / (float) max));
    }

    private int overlayWidth() {
        if (params != null && params.width > 0) return params.width;
        if (view != null && view.getWidth() > 0) return view.getWidth();
        int[] screen = overlayScreenSize();
        return Math.max(1, screen[0]);
    }

    private int overlayHeight() {
        if (view != null) {
            int h = view.getHeight();
            if (h <= 0) h = view.getMeasuredHeight();
            if (h > 0) return h;
        }
        float density = app.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(64 * density));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Same idle rule as {@code DesktopLyricState.fallback}: keep the current
     *  track while paused. "未播放" is only for no-track. */
    static String fallbackText(boolean playing, String title, String artist) {
        if (title == null || title.isEmpty()) return playing ? "" : I18n.tr("player.idle");
        if (artist == null || artist.isEmpty()) return title;
        return title + "  ·  " + artist;
    }

    /** @return the parsed colour, or {@code fallback} for an empty/invalid value. */
    private static int parseColor(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            if (hex.charAt(0) != '#') hex = "#" + hex;
            return Color.parseColor(hex);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void hide() {
        stopFrame();
        hideControls();
        LyricView live = view;
        if (!attached || wm == null || live == null) {
            attached = false;
            view = null;
            return;
        }
        live.stop();
        try {
            wm.removeView(live);
        } catch (Throwable ignored) {
        }
        attached = false;
        view = null;
    }

    private boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = params.x;
                downY = params.y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && dx * dx + dy * dy > 64) dragging = true;
                if (dragging && attached) {
                    params.x = downX + Math.round(dx);
                    params.y = downY + Math.round(dy);
                    try { wm.updateViewLayout(view, params); } catch (Throwable ignored) {}
                    placeControls();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    persistPosition();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    toggleControls();
                }
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    private void toggleControls() {
        if (controls != null) {
            hideControls();
            return;
        }
        if (wm == null || params == null) return;
        float d = app.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(app);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(8 * d);
        bar.setPadding(pad, pad, pad, pad);
        GradientDrawable capsule = new GradientDrawable();
        capsule.setColor(0xE628282B);
        capsule.setCornerRadius(28 * d);
        bar.setBackground(capsule);
        boolean playing = controller != null && controller.isPlaying();
        // Five equal slots so pause sits on the capsule's centre:
        //   [lock] [prev] [pause] [next] [open]
        bar.addView(iconButton(io.github.timer_err.qml4j.android.R.drawable.ic_overlay_lock,
                I18n.tr("overlay.controls.lock"), false, OverlayLyricWindow::toggleLocked));
        bar.addView(iconButton(io.github.timer_err.qml4j.android.R.drawable.ic_overlay_prev,
                I18n.tr("overlay.controls.prev"), false, () -> {
                    if (controller != null) controller.prev();
                }));
        bar.addView(iconButton(playing
                        ? io.github.timer_err.qml4j.android.R.drawable.ic_overlay_pause
                        : io.github.timer_err.qml4j.android.R.drawable.ic_overlay_play,
                I18n.tr("overlay.controls.toggle"), true, () -> {
                    if (controller != null) controller.toggle();
                    hideControls();
                }));
        bar.addView(iconButton(io.github.timer_err.qml4j.android.R.drawable.ic_overlay_next,
                I18n.tr("overlay.controls.next"), false, () -> {
                    if (controller != null) controller.next();
                }));
        bar.addView(iconButton(io.github.timer_err.qml4j.android.R.drawable.ic_overlay_open,
                I18n.tr("overlay.controls.openApp"), false, this::openPlayer));
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        controlParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        controlParams.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(bar, controlParams);
            controls = bar;
            placeControls();
            bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> placeControls());
        } catch (Throwable ignored) {
            controls = null;
            controlParams = null;
        }
    }

    /** Sit the control capsule centred above or below the lyric window.
     *  Prefer above; flip below when the top of the screen is too tight. Never
     *  clamp onto the lyrics themselves. */
    private void placeControls() {
        if (controls == null || controlParams == null || params == null || wm == null) return;
        int[] screen = overlayScreenSize();
        int lyricW = view != null && view.getWidth() > 0 ? view.getWidth() : Math.max(1, params.width);
        int lyricH = view != null && view.getHeight() > 0 ? view.getHeight() : overlayHeight();
        if (controls.getWidth() <= 0 || controls.getHeight() <= 0) {
            controls.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        }
        int barW = controls.getWidth() > 0 ? controls.getWidth() : Math.max(1, controls.getMeasuredWidth());
        int barH = controls.getHeight() > 0 ? controls.getHeight() : Math.max(1, controls.getMeasuredHeight());
        int gap = Math.round(8 * app.getResources().getDisplayMetrics().density);
        int[] xy = controlPosition(params.x, params.y, lyricW, lyricH, barW, barH,
                screen[0], screen[1], gap);
        if (controlParams.x == xy[0] && controlParams.y == xy[1]) return;
        controlParams.x = xy[0];
        controlParams.y = xy[1];
        try { wm.updateViewLayout(controls, controlParams); } catch (Throwable ignored) {}
    }

    /** Centre the bar on the lyric window's horizontal midpoint. Prefer a gap
     *  above; if that would leave the screen, use the matching gap below.
     *  When neither side has a full gap, keep the bar off the lyric strip
     *  (even if that clips a few pixels of the bar). */
    static int[] controlPosition(int lyricX, int lyricY, int lyricW, int lyricH,
                                 int barW, int barH, int screenW, int screenH, int gap) {
        barW = Math.max(1, barW);
        barH = Math.max(1, barH);
        screenW = Math.max(1, screenW);
        screenH = Math.max(1, screenH);
        gap = Math.max(0, gap);
        int x = lyricX + (lyricW - barW) / 2;
        x = Math.max(0, Math.min(Math.max(0, screenW - barW), x));

        int aboveY = lyricY - gap - barH;
        int belowY = lyricY + lyricH + gap;
        int y;
        if (aboveY >= 0) {
            y = aboveY;
        } else if (belowY + barH <= screenH) {
            y = belowY;
        } else if (lyricY >= screenH - (lyricY + lyricH)) {
            y = Math.min(aboveY, 0);
        } else {
            y = belowY;
        }
        return new int[]{x, y};
    }

    private int[] overlayScreenSize() {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{Math.max(1, bounds.width()), Math.max(1, bounds.height())};
        }
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        return new int[]{Math.max(1, dm.widthPixels), Math.max(1, dm.heightPixels)};
    }

    private ImageButton iconButton(int icon, String label, boolean emphasized, Runnable action) {
        float d = app.getResources().getDisplayMetrics().density;
        int size = Math.round(40 * d);
        ImageButton button = new ImageButton(app);
        button.setImageResource(icon);
        button.setContentDescription(label);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(Math.round(8 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(emphasized ? 0xFF4A4458 : Color.TRANSPARENT);
        button.setBackground(bg);
        button.setColorFilter(emphasized ? 0xFFE8DEF8 : 0xFFCAC4D0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(Math.round(4 * d), 0, Math.round(4 * d), 0);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void openPlayer() {
        hideControls();
        Intent open = new Intent(app, QPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { app.startActivity(open); } catch (Throwable ignored) {}
    }

    private void hideControls() {
        View live = controls;
        if (live == null || wm == null) {
            controls = null;
            controlParams = null;
            return;
        }
        try { wm.removeView(live); } catch (Throwable ignored) {}
        controls = null;
        controlParams = null;
    }

    private void update(PlayerController controller) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> update(controller));
            return;
        }
        bindController(controller);
        if (!wanted) {
            hide();
            return;
        }
        if (!attached) {
            if (canDraw(app)) show();
            else return;
        }
        if (view != null) view.bind(this.controller);
        scheduleFrame();
    }

    private void bindController(PlayerController next) {
        if (controller == next) return;
        unbindLyrics();
        controller = next;
        if (controller != null) controller.lyrics.addListener(lyricsListener);
    }

    private void unbindLyrics() {
        PlayerController previous = controller;
        if (previous != null) previous.lyrics.removeListener(lyricsListener);
    }

    private void invalidateView() {
        if (view != null) view.invalidate();
    }

    private void onFrame() {
        ticking = false;
        if (!wanted || !attached || view == null) return;
        view.invalidate();
        scheduleFrame();
    }

    private void scheduleFrame() {
        if (ticking || !attached) return;
        ticking = true;
        boolean playing = controller != null && controller.isPlaying();
        main.postDelayed(frameTick, playing ? FRAME_MS : 200L);
    }

    private void stopFrame() {
        ticking = false;
        main.removeCallbacks(frameTick);
    }

    /** Desktop-lyric look painted with the platform canvas; self-animating at the display rate. */
    private static final class LyricView extends View {

        private static final float SIDE_SIZE_RATIO = 0.72f;
        private static final float TRANSLATION_SIZE_RATIO = 0.52f;
        private static final float TRANSLATION_OPTICAL_GAP = -3f;
        private static final long LINE_TRANSITION_NANOS = 380_000_000L;
        private static final float SHADOW_OPACITY = 0.32f;

        private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        private volatile PlayerController controller;
        private List<LyricLine> lastLines;
        private boolean lastLinear;
        private LyricTimeline.Prepared prepared;

        private int fontPx = 16;
        /** Row pitch as a multiple of the main text line (settings 行间距). */
        private float spacingFactor = 2.1f;
        /** Explicit window height in dp; 0 = auto (three rows). */
        private int fixedHeightDp = 0;
        private float bgOpacity = 0.63f;
        private int sungOverride;
        private int unsungOverride;
        private boolean textOutline = true;
        private int outlineOverride;
        private boolean dark = true;
        private int surface;
        private int outline;
        private int cSung;
        private int cPrev;
        private int cNext;
        private int cShadow = Color.BLACK;

        private Visual current;
        private Visual outgoing;
        private long transitionStart;
        private int transitionDir = 1;
        private boolean running = true;

        LyricView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(Math.max(1f, density));
            haloPaint.setStyle(Paint.Style.STROKE);
            haloPaint.setStrokeJoin(Paint.Join.ROUND);
            if (Build.VERSION.SDK_INT >= 28) {
                textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            }
            configure(16, 0.63f, 0, 0, true, true, 0, 2.1f, 0);
        }

        void configure(int fontPx, float bgOpacity, int sung, int unsung, boolean dark,
                       boolean textOutline, int outlineColor, float spacingFactor, int fixedHeightDp) {
            this.fontPx = fontPx;
            this.bgOpacity = bgOpacity;
            this.sungOverride = sung;
            this.unsungOverride = unsung;
            this.dark = dark;
            this.textOutline = textOutline;
            this.outlineOverride = outlineColor;
            this.spacingFactor = Math.max(1f, spacingFactor);
            this.fixedHeightDp = Math.max(0, fixedHeightDp);
            resolvePalette();
            current = null;
            outgoing = null;
            requestLayout();
            invalidate();
        }

        void bind(PlayerController c) {
            controller = c;
            if (running) postInvalidateOnAnimation();
        }

        void stop() {
            running = false;
        }

        private void resolvePalette() {
            Map<?, ?> scheme = null;
            try {
                StyleManager manager = (StyleManager) StyleManager.__instance();
                Object raw = (dark ? manager.darkScheme : manager.lightScheme).peek();
                if (raw instanceof Map) scheme = (Map<?, ?>) raw;
            } catch (Throwable ignored) {
            }
            surface = role(scheme, "surfaceContainerHigh", dark ? 0xFF28282B : 0xFFE9E7EC);
            outline = role(scheme, "outlineVariant", dark ? 0xFF49454F : 0xFFCAC4D0);
            int primary = role(scheme, "primary", dark ? 0xFFD0BCFF : 0xFF6750A4);
            int variant = role(scheme, "onSurfaceVariantColor", dark ? 0xFFCAC4D0 : 0xFF49454F);
            int secondary = role(scheme, "secondary", dark ? 0xFFCCC2DC : 0xFF625B71);
            cShadow = role(scheme, "shadow", Color.BLACK);
            cSung = sungOverride != 0 ? sungOverride : primary;
            cPrev = unsungOverride != 0 ? unsungOverride : variant;
            cNext = unsungOverride != 0 ? unsungOverride : secondary;
        }

        private static int role(Map<?, ?> scheme, String name, int fallback) {
            if (scheme == null) return fallback;
            Object value = scheme.get(name);
            if (!(value instanceof String) || ((String) value).isEmpty()) return fallback;
            try {
                return Color.parseColor((String) value);
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        private float mainSize() {
            return fontPx * getResources().getDisplayMetrics().scaledDensity * 1.15f;
        }

        /** Pitch between adjacent rows: main text height scaled by the 行间距
         *  multiplier. */
        private float slot() {
            return Math.max(1f, mainSize() * spacingFactor);
        }

        /** How many rows fit the current height. Fewer than three drops the
         *  previous row, fewer than two also drops the next row, so a short
         *  window degrades to current+next, then current only. */
        private int visibleRows(float heightPx) {
            float slot = slot();
            if (slot <= 0f) return 3;
            int n = (int) Math.floor(heightPx / slot + 0.001f);
            return Math.max(1, Math.min(3, n));
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int height = fixedHeightDp > 0
                    ? Math.round(fixedHeightDp * density)
                    : Math.round(slot() * 3f);
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            running = false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            long now = System.nanoTime();

            rect.set(0f, 0f, w, h);
            float radius = 16f * density;
            surfacePaint.setColor(surface);
            surfacePaint.setAlpha(Math.round(bgOpacity * 255f));
            canvas.drawRoundRect(rect, radius, radius, surfacePaint);
            if (bgOpacity > 0.02f) {
                outlinePaint.setColor(outline);
                outlinePaint.setAlpha(Math.round(bgOpacity * 255f));
                rect.inset(0.5f * density, 0.5f * density);
                canvas.drawRoundRect(rect, radius, radius, outlinePaint);
            }

            Frame snapshot = readFrame();
            if (current == null || !current.matches(snapshot)) {
                Visual replacement = new Visual(snapshot, mainSize(), textPaint);
                if (current != null) {
                    outgoing = current;
                    transitionDir = outgoing.groupIndex >= 0 && replacement.groupIndex >= 0
                            && replacement.groupIndex < outgoing.groupIndex ? -1 : 1;
                    transitionStart = now;
                }
                current = replacement;
            }
            current.update(snapshot);

            float padX = 20f * density;
            float left = padX;
            float width = Math.max(1f, w - padX * 2f);
            float slot = slot();
            int save = canvas.save();
            canvas.clipRect(left, 0f, left + width, h);
            if (outgoing != null) {
                float raw = (now - transitionStart) / (float) LINE_TRANSITION_NANOS;
                if (raw >= 1f) {
                    outgoing = null;
                    drawVisual(canvas, current, left, width, h, 0f, 1f, false);
                } else {
                    float inv = 1f - Math.max(0f, raw);
                    float eased = 1f - inv * inv * inv;
                    drawVisual(canvas, outgoing, left, width, h,
                            transitionDir * -slot * eased, 1f - eased, true);
                    drawVisual(canvas, current, left, width, h,
                            transitionDir * slot * (1f - eased), eased, false);
                }
            } else {
                drawVisual(canvas, current, left, width, h, 0f, 1f, false);
            }
            canvas.restoreToCount(save);
        }

        private Frame readFrame() {
            PlayerController c = controller;
            Frame out = new Frame();
            if (c == null) return out;
            out.playing = c.isPlaying();
            String title = c.title.peek();
            String artist = c.artist.peek();
            title = title == null ? "" : title.trim();
            artist = artist == null ? "" : artist.trim();
            // Same idle rule as DesktopLyricState.fallback: keep the current
            // track's lyrics (and title) while paused. "未播放" is only for
            // no-track. lyricClockPosition() already freezes at the pause point.
            out.fallback = fallbackText(out.playing, title, artist);
            if (title.isEmpty() && !out.playing) return out;
            // Lyrics are applied asynchronously after a track switch, and the
            // render thread that publishes them pauses while the app is
            // backgrounded — the controller clears the list on the calling thread
            // first, but a lyric arriving late for a since-skipped track would
            // still land here. Guard by identity: only lines belonging to the
            // track the controller says is playing may reach the window; anything
            // else falls back to the title row until the right lyric arrives.
            out.mediaId = c.currentMediaId.peek();
            List<LyricLine> lines = c.lyrics.peek();
            boolean usable = lines != null && !lines.isEmpty();
            boolean linear = Boolean.TRUE.equals(LyricConfig.instance.linearAnimForPlainLrc.getValue());
            if (!usable) {
                prepared = null;
            } else if (lines != lastLines || linear != lastLinear) {
                prepared = LyricTimeline.prepare(lines, linear);
            }
            lastLines = usable ? lines : null;
            lastLinear = linear;
            long pos = Math.max(0L, c.lyricClockPosition() - LyricConfig.instance.offsetMs.getValue());
            out.positionMs = pos;
            out.timeline = prepared == null ? null : LyricTimeline.frameAt(prepared, pos);
            return out;
        }

        private void drawVisual(Canvas canvas, Visual v, float left, float width, float height,
                                float dy, float alpha, boolean complete) {
            if (v == null || alpha <= 0.001f) return;
            float slot = slot();
            int rows = visibleRows(height);
            float side = v.mainSize * SIDE_SIZE_RATIO;
            float trans = Math.max(12f * density, v.mainSize * TRANSLATION_SIZE_RATIO);

            // Rows are anchored so the CURRENT line sits at slot index 1 in the
            // full 3-row layout. In the 2- and 1-row fallbacks the same pitch is
            // kept and the top rows are simply dropped, so tightening the window
            // never re-flows the text — the current line stays put.
            float topPad = Math.max(0f, (height - slot * rows) * 0.5f);
            float currentCenter = topPad + slot * (rows >= 3 ? 1.5f : 0.5f) + dy;

            if (rows >= 3) {
                drawStatic(canvas, v.previous, side, left, currentCenter - slot, width, 1f, cPrev, alpha);
            }

            float mainCenter = currentCenter;
            float transCenter = currentCenter;
            boolean hasTranslation = !v.translation.isEmpty();
            if (hasTranslation) {
                mainCenter -= (spacing(trans) + TRANSLATION_OPTICAL_GAP * density) * 0.5f;
                transCenter += (spacing(v.mainSize) + TRANSLATION_OPTICAL_GAP * density) * 0.5f;
            }
            drawCurrent(canvas, v, left, mainCenter, width, alpha, complete);
            if (hasTranslation) {
                drawStatic(canvas, v.translation, trans, left, transCenter, width,
                        v.progress, cNext, alpha * 0.88f);
            }
            if (rows >= 2) {
                drawStatic(canvas, v.next, side, left, currentCenter + slot, width, 0f, cNext, alpha);
            }
        }

        private float spacing(float size) {
            textPaint.setTextSize(size);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            return fm.descent - fm.ascent + fm.leading;
        }

        private void drawCurrent(Canvas canvas, Visual v, float left, float centerY,
                                 float viewport, float alpha, boolean complete) {
            if (v.current.isEmpty()) return;
            textPaint.setTextSize(v.mainSize);
            float rowWidth = textPaint.measureText(v.current);
            float baseline = baseline(centerY);
            float x = textX(left, viewport, rowWidth, v.progress);

            drawShadowAndHalo(canvas, v.current, x, baseline, v.mainSize, cSung, alpha);
            float sweep = complete ? rowWidth : v.sweepWidth(textPaint, rowWidth);
            if (sweep >= rowWidth - 0.01f) {
                drawText(canvas, v.current, x, baseline, cSung, alpha);
                return;
            }
            drawText(canvas, v.current, x, baseline, cPrev, alpha);
            if (sweep <= 0.01f) return;
            int save = canvas.save();
            canvas.clipRect(x, centerY - spacing(v.mainSize), x + sweep, centerY + spacing(v.mainSize));
            drawText(canvas, v.current, x, baseline, cSung, alpha);
            canvas.restoreToCount(save);
        }

        private void drawStatic(Canvas canvas, String text, float size, float left, float centerY,
                                float viewport, float progress, int color, float alpha) {
            if (text.isEmpty()) return;
            textPaint.setTextSize(size);
            float textWidth = textPaint.measureText(text);
            float x = textX(left, viewport, textWidth, progress);
            float baseline = baseline(centerY);
            drawShadowAndHalo(canvas, text, x, baseline, size, color, alpha);
            drawText(canvas, text, x, baseline, color, alpha);
        }

        private void drawShadowAndHalo(Canvas canvas, String text, float x, float baseline,
                                       float size, int color, float alpha) {
            drawText(canvas, text, x + 0.75f * density, baseline + 1.25f * density,
                    cShadow, alpha * SHADOW_OPACITY);
            if (textOutline) {
                haloPaint.setTextSize(size);
                haloPaint.setTypeface(textPaint.getTypeface());
                haloPaint.setStrokeWidth(Math.max(1.4f * density, size * 0.10f));
                haloPaint.setColor(outlineOverride != 0 ? outlineOverride
                        : (luma(color) > 0.5f ? Color.BLACK : Color.WHITE));
                haloPaint.setAlpha(Math.round(clamp01(alpha) * 0.85f * 255f));
                canvas.drawText(text, x, baseline, haloPaint);
            }
        }

        private void drawText(Canvas canvas, String text, float x, float baseline,
                              int color, float alpha) {
            textPaint.setColor(color);
            textPaint.setAlpha(Math.round(clamp01(alpha) * (color >>> 24) ));
            canvas.drawText(text, x, baseline, textPaint);
        }

        private float baseline(float centerY) {
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            return centerY - (fm.ascent + fm.descent) * 0.5f;
        }

        private static float textX(float left, float viewport, float textWidth, float progress) {
            if (textWidth <= viewport) return left + (viewport - textWidth) * 0.5f;
            return left - (textWidth - viewport) * clamp01(progress);
        }

        private static float luma(int argb) {
            return 0.2126f * Color.red(argb) / 255f + 0.7152f * Color.green(argb) / 255f
                    + 0.0722f * Color.blue(argb) / 255f;
        }

        private static float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }

    /** One tick's worth of player state, read on the UI thread. */
    private static final class Frame {
        boolean playing;
        String fallback = "";
        /** Canonical id of the track this frame's lyrics belong to. */
        String mediaId = "";
        long positionMs;
        LyricTimeline.Frame timeline;
    }

    /** The three rows (plus translation) for one lyric group and its sweep progress. */
    private static final class Visual {
        final int groupIndex;
        final String mediaId;
        final String previous;
        final String current;
        final String translation;
        final String next;
        final boolean animatablePerToken;
        final List<Syllable> syllables;
        final float mainSize;
        float progress;
        long positionMs;

        Visual(Frame f, float mainSize, Paint paint) {
            LyricTimeline.Frame t = f.timeline;
            this.mainSize = mainSize;
            this.mediaId = f.mediaId;
            groupIndex = t != null ? t.groupIndex : -1;
            previous = t != null ? safe(t.previous) : "";
            String cur = t != null ? safe(t.current) : "";
            current = cur.isEmpty() ? safe(f.fallback) : cur;
            translation = t != null ? safe(t.translation) : "";
            next = t != null ? safe(t.next) : "";
            animatablePerToken = t != null && t.animatablePerToken;
            List<Syllable> s = t != null ? t.currentSyllables : null;
            syllables = s == null ? Collections.<Syllable>emptyList() : s;
        }

        boolean matches(Frame f) {
            LyricTimeline.Frame t = f.timeline;
            String cur = t != null ? safe(t.current) : "";
            if (cur.isEmpty()) cur = safe(f.fallback);
            return mediaId.equals(f.mediaId)
                    && groupIndex == (t != null ? t.groupIndex : -1)
                    && previous.equals(t != null ? safe(t.previous) : "")
                    && current.equals(cur)
                    && translation.equals(t != null ? safe(t.translation) : "")
                    && next.equals(t != null ? safe(t.next) : "");
        }

        void update(Frame f) {
            LyricTimeline.Frame t = f.timeline;
            progress = t != null ? Math.max(0f, Math.min(1f, t.progress)) : 0f;
            positionMs = f.positionMs;
        }

        /** Width of the already-sung part of the current row. */
        float sweepWidth(Paint paint, float rowWidth) {
            if (!animatablePerToken || syllables.isEmpty()) return rowWidth * progress;
            StringBuilder prefix = new StringBuilder();
            float startX = 0f;
            for (int index = 0; index < syllables.size(); index++) {
                Syllable syllable = syllables.get(index);
                long start = syllable.startMs;
                long end = LyricTimeline.syllableEndMs(syllables, index);
                if (positionMs < start) return Math.min(rowWidth, startX);
                prefix.append(syllable.text);
                float endX = paint.measureText(prefix.toString());
                if (positionMs < end) {
                    float p = (positionMs - start) / (float) (end - start);
                    return Math.max(0f, Math.min(rowWidth, startX + (endX - startX) * p));
                }
                startX = endX;
            }
            return rowWidth;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
