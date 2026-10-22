package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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
import io.github.timer_err.qml4j.runtime.color.StyleManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    public static void bindSettings(SettingsCore core) {
        settings = core;
        OverlayLyricWindow live = instance;
        if (live != null) live.main.post(live::applyStyle);
    }

    public static void restyle() {
        OverlayLyricWindow live = instance;
        if (live != null) live.main.post(live::applyStyle);
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
        if (live != null) live.main.post(live::hide);
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
        params.x = prefs.getInt("x", Math.round(16 * d));
        params.y = prefs.getInt("y", Math.round(dm.heightPixels * 0.72f));
        applyStyle();
        try {
            wm.addView(view, params);
            attached = true;
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
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply();
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
        bar.setPadding(Math.round(8 * d), Math.round(6 * d), Math.round(8 * d), Math.round(6 * d));
        bar.setBackgroundColor(0xE628282B);
        bar.addView(controlButton(I18n.tr("overlay.controls.prev"), () -> {
            if (controller != null) controller.prev();
        }));
        bar.addView(controlButton(I18n.tr("overlay.controls.toggle"), () -> {
            if (controller != null) controller.toggle();
        }));
        bar.addView(controlButton(I18n.tr("overlay.controls.next"), () -> {
            if (controller != null) controller.next();
        }));
        bar.addView(controlButton(I18n.tr("overlay.controls.openApp"), this::openPlayer));
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
        controlParams.x = params.x;
        controlParams.y = Math.max(0, params.y - Math.round(56 * d));
        try {
            wm.addView(bar, controlParams);
            controls = bar;
        } catch (Throwable ignored) {
            controls = null;
            controlParams = null;
        }
    }

    private Button controlButton(String label, Runnable action) {
        Button button = new Button(app);
        button.setText(label);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(Math.round(10 * app.getResources().getDisplayMetrics().density), 0,
                Math.round(10 * app.getResources().getDisplayMetrics().density), 0);
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
        this.controller = controller;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> update(controller));
            return;
        }
        if (!wanted) {
            hide();
            return;
        }
        if (!attached) {
            if (canDraw(app)) show();
            else return;
        }
        if (view != null) view.bind(controller);
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

            if (running && (snapshot.playing || outgoing != null)) postInvalidateOnAnimation();
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
            if (!out.playing) {
                out.fallback = I18n.tr("player.idle");
                return out;
            }
            if (!title.isEmpty()) {
                out.fallback = artist.isEmpty() ? title : title + "  ·  " + artist;
            }
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
