package io.github.timer_err.qml4j.android;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;
import io.github.timer_err.qml4j.render.SurfaceBackend;
import io.github.timer_err.qml4j.render.items.input.TextEditable;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.android.lyric.LyricRenderer;
import dev.t1m3.qplayer.android.lyric.LyricSkia;
import dev.t1m3.qplayer.lyric.LyricLine;

import java.util.List;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class QmlGLSurfaceView extends GLSurfaceView {

    private final String qmlSource;
    private final QmlEngine engine;
    private final ResourceLoader resources;
    private QmlView view;
    private SkijaGlSurface surface;
    private PlayerController controller;
    private volatile boolean failed;
    private ErrorListener errorListener;
    private SplashListener splashListener;
    private boolean readyFired;
    // Mirror QmlView.renderFrame's idle fast-path: skip the layout pass when no
    // property changed since the last frame, so a static screen costs only paint.
    private long renderedVersion = -1;

    /** Drives a host splash while the QML tree compiles: per-component progress
     *  (on the GL thread) and a one-shot ready signal at the first painted frame. */
    public interface SplashListener {
        void onProgress(String name, int count);
        void onReady();
    }

    public void setSplashListener(SplashListener l) {
        this.splashListener = l;
    }

    // Host-drawn lyric page: a full-screen Skija overlay rendered on top of the
    // QML scene when controller.lyricsOpen. Drawn directly (no QML type) using
    // Haedus's LyricRenderer. lyricSlide eases 0->1 as it opens/closes; lastLyrics
    // detects a track change so we re-feed the renderer.
    private final LyricRenderer lyricRenderer = new LyricRenderer();
    private final dev.t1m3.qplayer.android.lyric.FluidBackground fluidBg =
            new dev.t1m3.qplayer.android.lyric.FluidBackground(System.nanoTime());
    private List<LyricLine> lastLyrics;
    private float lyricSlide;
    // Touch tracking while the overlay is up (device px), for swipe-down / tap-to-close.
    private float lyricDownY, lyricDownX;
    private boolean lyricMoved;

    /** Notified (with a full stack trace) when QML load/render throws, so the
     *  host can surface the error instead of the GL thread crashing the app. */
    public interface ErrorListener {
        void onError(String trace);
    }

    public void setErrorListener(ErrorListener l) {
        this.errorListener = l;
    }
    // Logical-pixel scale: QML is authored in dp; render at the screen density so
    // Material components are physically sized (and the canvas drawn larger).
    private final float uiScale;

    public QmlGLSurfaceView(Context ctx, QmlEngine engine, String qmlSource, ResourceLoader resources,
                            float uiScale) {
        super(ctx);
        this.engine = engine;
        this.qmlSource = qmlSource;
        this.resources = resources;
        this.uiScale = uiScale > 0 ? uiScale : 1f;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 8);
        setRenderer(new GlRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // While the lyric overlay is up it owns all touches (close gestures only);
        // nothing reaches the QML scene behind it.
        if (controller != null && Boolean.TRUE.equals(controller.lyricsOpen.peek())) {
            return onLyricTouch(ev);
        }
        final int action = ev.getActionMasked();
        final float x = ev.getX() / uiScale;
        final float y = ev.getY() / uiScale;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                queueEvent(new Runnable() {
                    @Override public void run() {
                        if (view == null) return;
                        boolean hitTextEditable = view.pickTextEditable(x, y) != null;
                        view.dispatchPointerDown(x, y);
                        if (hitTextEditable) showImeOnUiThread();
                    }
                });
                return true;
            case MotionEvent.ACTION_MOVE:
                queueEvent(new Runnable() {
                    @Override public void run() {
                        if (view != null) view.dispatchPointerMove(x, y);
                    }
                });
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                queueEvent(new Runnable() {
                    @Override public void run() {
                        if (view != null) view.dispatchPointerUp(x, y);
                    }
                });
                return true;
            default:
                return false;
        }
    }

    // Lyric overlay gestures: the bottom transport bar (progress drag + buttons),
    // otherwise tap the header strip or swipe down to dismiss.
    private boolean lyDragProg;
    private int lyDownBtn = -1;

    private boolean onLyricTouch(MotionEvent ev) {
        float w = surface.width() / uiScale, h = surface.height() / uiScale;
        float lx = ev.getX() / uiScale, ly = ev.getY() / uiScale;
        float barTop = h - L_TRANSPORT_H;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lyricDownX = ev.getX();
                lyricDownY = ev.getY();
                lyricMoved = false;
                lyDragProg = false;
                lyDownBtn = -1;
                if (ly >= barTop) {
                    if (onProgress(lx, ly, w, barTop)) {
                        lyDragProg = true;
                        seekToFrac(lx, w);
                    } else {
                        lyDownBtn = buttonAt(lx, ly, w, barTop);
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (lyDragProg) {
                    seekToFrac(lx, w);
                    return true;
                }
                if (lyDownBtn < 0
                        && (Math.abs(ev.getY() - lyricDownY) > 12 * uiScale
                            || Math.abs(ev.getX() - lyricDownX) > 12 * uiScale)) {
                    lyricMoved = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (lyDragProg) {
                    lyDragProg = false;
                    return true;
                }
                if (lyDownBtn >= 0) {
                    if (buttonAt(lx, ly, w, barTop) == lyDownBtn) fireLyricButton(lyDownBtn);
                    lyDownBtn = -1;
                    return true;
                }
                float dy = ev.getY() - lyricDownY;
                float dx = ev.getX() - lyricDownX;
                boolean tapHeader = !lyricMoved && lyricDownY < 130 * uiScale;
                boolean swipeDown = dy > 120 * uiScale && dy > Math.abs(dx);
                if (tapHeader || swipeDown) closeLyrics();
                return true;
            }
            default:
                return true;
        }
    }

    private boolean onProgress(float lx, float ly, float w, float barTop) {
        float py = barTop + 30f;
        return ly >= py - 18f && ly <= py + 14f && lx >= L_PAD - 10f && lx <= w - L_PAD + 10f;
    }

    // 0 mode, 1 prev, 2 play/pause, 3 next, 4 like; -1 if no button is hit.
    private int buttonAt(float lx, float ly, float w, float barTop) {
        float cy = barTop + 94f;
        if (ly < cy - 30f || ly > cy + 30f) return -1;
        float cx = w / 2f, gap = 62f;
        float[] xs = {cx - 2f * gap, cx - gap, cx, cx + gap, cx + 2f * gap};
        for (int i = 0; i < xs.length; i++) {
            if (Math.abs(lx - xs[i]) < 30f) return i;
        }
        return -1;
    }

    private void seekToFrac(float lx, float w) {
        float frac = Math.max(0f, Math.min(1f, (lx - L_PAD) / (w - 2f * L_PAD)));
        final long ms = (long) (frac * controller.durationMs.peek());
        queueEvent(() -> {
            if (controller != null) controller.seek(ms);
        });
    }

    private void fireLyricButton(int b) {
        queueEvent(() -> {
            if (controller == null) return;
            switch (b) {
                case 0: controller.cyclePlayMode(); break;
                case 1: controller.prev(); break;
                case 2: controller.toggle(); break;
                case 3: controller.next(); break;
                case 4: controller.toggleLike(); break;
                default: break;
            }
        });
    }

    private void closeLyrics() {
        queueEvent(new Runnable() {
            @Override public void run() {
                if (controller != null) controller.setLyricsOpen(false);
            }
        });
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return view != null && view.focused() instanceof TextEditable;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (view != null && view.focused() instanceof TextEditable) {
            TextEditable ti = (TextEditable) view.focused();
            int pos = ti.cursorPosition();
            outAttrs.initialSelStart = pos;
            outAttrs.initialSelEnd = pos;
        }
        return new QmlInputConnection(this, true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK
                && controller != null && Boolean.TRUE.equals(controller.lyricsOpen.peek())) {
            closeLyrics();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK
                && controller != null && Boolean.TRUE.equals(controller.queueOpen.peek())) {
            queueEvent(() -> controller.setQueueOpen(false));
            return true;
        }
        if (dispatchKeyEvent(event, true)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (dispatchKeyEvent(event, false)) return true;
        return super.onKeyUp(keyCode, event);
    }

    boolean dispatchKeyEvent(KeyEvent event, boolean down) {
        if (view == null) return false;
        if (!(view.focused() instanceof TextEditable)) return false;
        if (down && handleClipboardShortcut(event)) return true;
        final int mapped = mapKeyCode(event.getKeyCode());
        final String text;
        if (mapped == 0) {
            int unicode = event.getUnicodeChar();
            if (unicode == 0) return false;
            text = new String(Character.toChars(unicode));
        } else {
            text = null;
        }
        final boolean isDown = down;
        final boolean shift = event.isShiftPressed();
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view != null) view.dispatchKey(mapped, text, isDown, shift);
            }
        });
        return true;
    }

    private boolean handleClipboardShortcut(KeyEvent event) {
        if (!event.isCtrlPressed()) return false;
        int kc = event.getKeyCode();
        if (kc != KeyEvent.KEYCODE_C && kc != KeyEvent.KEYCODE_X && kc != KeyEvent.KEYCODE_V) return false;
        final int action = kc;
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                if (action == KeyEvent.KEYCODE_C) view.copy();
                else if (action == KeyEvent.KEYCODE_X) view.cut();
                else view.paste();
            }
        });
        return true;
    }

    void commitTextFromIme(final CharSequence text) {
        if (text == null) return;
        final String s = text.toString();
        if (s.isEmpty()) return;
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view != null) view.dispatchKey(0, s, true);
            }
        });
    }

    QmlView qmlView() {
        return view;
    }

    /** Expose a controller to QML as the {@code player} context global. Must be
     *  set before the GL thread first lays out (i.e. right after construction). */
    public void setController(PlayerController c) {
        this.controller = c;
    }

    void sendSyntheticKey(final int code, final String text) {
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                view.dispatchKey(code, text, true, false);
                view.dispatchKey(code, text, false, false);
            }
        });
    }

    void deleteFromIme(final int beforeLength) {
        if (beforeLength <= 0) return;
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                for (int i = 0; i < beforeLength; i++) {
                    view.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
                }
            }
        });
    }

    void performImeEnter() {
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view != null) view.dispatchKey(QmlView.KEY_ENTER, null, true);
            }
        });
    }

    private static int mapKeyCode(int kc) {
        if (kc == KeyEvent.KEYCODE_DEL) return QmlView.KEY_BACKSPACE;
        if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) return QmlView.KEY_ENTER;
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) return QmlView.KEY_LEFT;
        if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) return QmlView.KEY_RIGHT;
        if (kc == KeyEvent.KEYCODE_DPAD_UP) return QmlView.KEY_UP;
        if (kc == KeyEvent.KEYCODE_DPAD_DOWN) return QmlView.KEY_DOWN;
        if (kc == KeyEvent.KEYCODE_MOVE_HOME) return QmlView.KEY_HOME;
        if (kc == KeyEvent.KEYCODE_MOVE_END) return QmlView.KEY_END;
        return 0;
    }

    private void hideImeOnUiThread() {
        runOnUi(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = imm();
                if (imm == null) return;
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        });
    }

    private void showImeOnUiThread() {
        runOnUi(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = imm();
                if (imm == null) return;
                requestFocus();
                imm.restartInput(QmlGLSurfaceView.this);
                imm.showSoftInput(QmlGLSurfaceView.this, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private InputMethodManager imm() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void runOnUi(Runnable r) {
        Context ctx = getContext();
        if (ctx instanceof Activity) ((Activity) ctx).runOnUiThread(r);
    }

    private final class GlRenderer implements GLSurfaceView.Renderer {
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            surface = new SkijaGlSurface();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            if (failed) return;
            surface.resize(width, height);
            try {
                if (view == null) {
                    view = QmlView.withStockTypes(engine).resources(resources);
                    view.setClipboard(new AndroidClipboard(getContext()));
                    view.setFocusListener((nf, of) -> {
                        if (of instanceof TextEditable && !(nf instanceof TextEditable)) {
                            hideImeOnUiThread();
                        }
                    });
                    if (controller != null) view.context("player", controller);
                    view.setCompileProgressListener((name, count) -> {
                        SplashListener l = splashListener;
                        if (l != null) l.onProgress(name, count);
                    });
                    view.load(qmlSource);
                }
                if (view.root() != null) {
                    view.root().width.set(width / uiScale);
                    view.root().height.set(height / uiScale);
                }
            } catch (Throwable t) {
                reportError(t);
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (failed || view == null) return;
            DirtyQueue dq = view.dirtyQueue();
            dq.install();
            try {
                long t0 = System.nanoTime();
                long v0 = Property.changeVersion();
                if (controller != null) controller.pump();
                view.tickAnimations(System.nanoTime());
                dq.flush();
                profBumpTick += Property.changeVersion() - v0;
                long t1 = System.nanoTime();
                Canvas canvas = surface.acquireCanvas();
                // When the lyric page is fully open it covers the screen with an
                // opaque backdrop, so skip rendering the QML scene underneath
                // entirely — otherwise a heavy list page keeps repainting behind
                // it and drops frames. Render the scene during the slide (when
                // part of it still shows) and whenever the page isn't fully open.
                boolean lyricsOpen = controller != null
                        && Boolean.TRUE.equals(controller.lyricsOpen.peek());
                boolean sceneCovered = lyricsOpen && lyricSlide >= 0.999f;
                if (!sceneCovered) {
                    int sc = canvas.save();
                    canvas.scale(uiScale, uiScale);
                    // The view's own renderer is wired with the component factory + resource
                    // loader (via resources()); reuse it rather than a bare Renderer.
                    io.github.timer_err.qml4j.render.Renderer renderer = view.renderer();
                    renderer.setGpuContext(surface.recordingContext());
                    boolean skipLayout = Property.changeVersion() == renderedVersion;
                    if (skipLayout) profSkips++;
                    long vBeforeRender = Property.changeVersion();
                    renderer.render(canvas, view.root(), skipLayout);
                    renderedVersion = Property.changeVersion();
                    profBumpRender += renderedVersion - vBeforeRender;
                    canvas.restoreToCount(sc);
                }
                drawLyricOverlay(canvas);
                long t1b = System.nanoTime();
                surface.present();
                profileFrame(t0, t1, t1b, System.nanoTime());
                if (!readyFired) {
                    readyFired = true;
                    SplashListener l = splashListener;
                    if (l != null) l.onReady();
                }
            } catch (Throwable t) {
                reportError(t);
            } finally {
                dq.uninstall();
            }
        }
    }

    // Host-drawn lyric page: a fluid (SkSL, cover-keyed) backdrop with the
    // Haedus LyricRenderer on top, sliding up over the QML scene. Runs every
    // frame (RENDERMODE_CONTINUOUSLY) so the slide + scroll springs animate.
    @SuppressWarnings("unchecked")
    private void drawLyricOverlay(Canvas canvas) {
        if (controller == null) return;
        boolean open = Boolean.TRUE.equals(controller.lyricsOpen.peek());
        float target = open ? 1f : 0f;
        // critically-damped-ish ease toward the target; cheap and frame-stable.
        lyricSlide += (target - lyricSlide) * 0.22f;
        if (Math.abs(target - lyricSlide) < 0.002f) lyricSlide = target;
        if (lyricSlide <= 0.001f && !open) return;

        // Re-feed the renderer when the track's lyric list changes (identity).
        Object lyObj = controller.lyrics.peek();
        if (lyObj != lastLyrics) {
            lastLyrics = (List<LyricLine>) lyObj;
            lyricRenderer.setLyrics(lastLyrics);
        }

        float w = surface.width() / uiScale;
        float h = surface.height() / uiScale;
        float ease = lyricSlide * lyricSlide * (3f - 2f * lyricSlide);   // smoothstep
        float offY = (1f - ease) * h;

        int sc = canvas.save();
        canvas.scale(uiScale, uiScale);
        canvas.translate(0f, offY);

        // 1) fluid backdrop, keyed by the current track.
        byte[] cover = (byte[]) controller.coverBytes.peek();
        String key = controller.title.peek() + "|" + controller.coverUrl.peek();
        fluidBg.render(canvas, w, h, cover, key, System.nanoTime());

        // 2) header: drag handle + title + artist.
        float pad = 28f;
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setColor(0x66FFFFFF);
            canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(
                    w / 2f - 18f, 14f, 36f, 4f, 2f), p);

            p.setColor(0xFFFFFFFF);
            String title = controller.title.peek();
            if (title != null && !title.isEmpty()) {
                canvas.drawString(title, pad, 74f,
                        dev.t1m3.qplayer.android.lyric.Fonts.getMedium(24f), p);
            }
            p.setColor(0xB3FFFFFF);
            String artist = controller.artist.peek();
            if (artist != null && !artist.isEmpty()) {
                canvas.drawString(artist, pad, 102f,
                        dev.t1m3.qplayer.android.lyric.Fonts.getRegular(15f), p);
            }
        }

        // 3) lyrics column below the header. Render into a layer, then multiply
        // a vertical alpha gradient (DST_IN) so lines fade to transparent toward
        // the top/bottom edges instead of being hard-clipped.
        float topY = 140f;
        float colH = h - topY - L_TRANSPORT_H;
        io.github.humbleui.types.Rect colRect = io.github.humbleui.types.Rect.makeXYWH(0f, topY, w, colH);
        int lc = canvas.saveLayer(colRect, null);
        LyricSkia.setCanvas(canvas);
        lyricRenderer.render(canvas, pad, topY, w - 2f * pad, colH, controller.position());
        try (Paint mask = new Paint()) {
            float f = Math.min(0.4f, 40f / colH);   // fade band as a fraction of the column
            io.github.humbleui.skija.Shader grad = io.github.humbleui.skija.Shader.makeLinearGradient(
                    0f, topY, 0f, topY + colH,
                    new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                    new float[]{0f, f, 1f - f, 1f});
            mask.setShader(grad);
            mask.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
            canvas.drawRect(colRect, mask);
            grad.close();
        }
        canvas.restoreToCount(lc);

        // 4) transport controls along the bottom.
        drawLyricTransport(canvas, w, h);

        canvas.restoreToCount(sc);
    }

    // Reserved height (logical px) for the lyric-page transport bar at the bottom.
    private static final float L_TRANSPORT_H = 136f;
    private static final float L_PAD = 28f;

    // Shaped icon lines cached by name@size -- a fixed, tiny set, so this never
    // grows unbounded and avoids re-shaping every frame (Painter caches the same way).
    private final java.util.Map<String, io.github.humbleui.skija.TextLine> lyricIcons =
            new java.util.HashMap<>();

    private io.github.humbleui.skija.TextLine iconLine(String name, float size) {
        String key = name + "@" + size;
        io.github.humbleui.skija.TextLine l = lyricIcons.get(key);
        if (l == null) {
            l = io.github.humbleui.skija.TextLine.make(
                    name, dev.t1m3.qplayer.android.lyric.Fonts.getIcon(size));
            lyricIcons.put(key, l);
        }
        return l;
    }

    private void drawIcon(Canvas canvas, String name, float cx, float cy, float size, int color, Paint p) {
        io.github.humbleui.skija.TextLine line = iconLine(name, size);
        p.setColor(color);
        canvas.drawTextLine(line, cx - line.getWidth() / 2f, cy + size * 0.36f, p);
    }

    private void drawLyricTransport(Canvas canvas, float w, float h) {
        if (controller == null) return;
        float barTop = h - L_TRANSPORT_H;
        long dur = controller.durationMs.peek();
        long pos = controller.position();
        float frac = dur > 0 ? Math.min(1f, Math.max(0f, (float) pos / dur)) : 0f;

        // Bottom scrim so white controls stay legible over a bright fluid backdrop.
        try (Paint scrim = new Paint()) {
            io.github.humbleui.skija.Shader g = io.github.humbleui.skija.Shader.makeLinearGradient(
                    0f, barTop - 28f, 0f, h,
                    new int[]{0x00000000, 0x73000000});
            scrim.setShader(g);
            canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(0f, barTop - 28f, w, L_TRANSPORT_H + 28f), scrim);
            g.close();
        }

        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            // progress track + fill + knob
            float py = barTop + 30f, x0 = L_PAD, x1 = w - L_PAD;
            p.setColor(0x40FFFFFF);
            canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(x0, py - 2f, x1 - x0, 4f, 2f), p);
            p.setColor(0xFFFFFFFF);
            float fw = (x1 - x0) * frac;
            if (fw > 0f) canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(x0, py - 2f, fw, 4f, 2f), p);
            canvas.drawCircle(x0 + fw, py, 6f, p);
            // times
            Font tf = dev.t1m3.qplayer.android.lyric.Fonts.getRegular(11f);
            p.setColor(0xB3FFFFFF);
            canvas.drawString(fmtTime(pos), x0, py + 22f, tf, p);
            String tot = fmtTime(dur);
            canvas.drawString(tot, x1 - tf.measureTextWidth(tot), py + 22f, tf, p);
            // buttons
            float cy = barTop + 94f, cx = w / 2f, gap = 62f;
            int mode = controller.playMode.peek();
            String modeIcon = mode == 1 ? "shuffle" : (mode == 2 ? "repeat_one" : "repeat");
            drawIcon(canvas, modeIcon, cx - 2f * gap, cy, 26f, mode == 0 ? 0x99FFFFFF : 0xFF82B1FF, p);
            drawIcon(canvas, "skip_previous", cx - gap, cy, 32f, 0xFFFFFFFF, p);
            drawIcon(canvas, Boolean.TRUE.equals(controller.playing.peek()) ? "pause" : "play_arrow",
                    cx, cy, 44f, 0xFFFFFFFF, p);
            drawIcon(canvas, "skip_next", cx + gap, cy, 32f, 0xFFFFFFFF, p);
            boolean liked = Boolean.TRUE.equals(controller.currentLiked.peek());
            drawIcon(canvas, "favorite", cx + 2f * gap, cy, 26f, liked ? 0xFFFF6E8A : 0x99FFFFFF, p);
        }
    }

    private static String fmtTime(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000, m = s / 60, r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    // Lightweight frame profiler: accumulates layout (tick+flush) vs paint
    // (render) time and the wall-clock gap between frames, logging a summary
    // every ~120 frames so scroll hitches show up in the in-app log.
    private long profLastFrameNanos;
    private int profFrames;
    private int profSkips;
    private long profBumpTick, profBumpRender;
    private double profLayoutMs, profRenderMs, profPresentMs, profGapMs, profMaxGapMs;

    private void profileFrame(long t0, long t1, long t1b, long t2) {
        profLayoutMs += (t1 - t0) / 1_000_000.0;
        profRenderMs += (t1b - t1) / 1_000_000.0;
        profPresentMs += (t2 - t1b) / 1_000_000.0;
        if (profLastFrameNanos != 0L) {
            double gap = (t2 - profLastFrameNanos) / 1_000_000.0;
            profGapMs += gap;
            if (gap > profMaxGapMs) profMaxGapMs = gap;
        }
        profLastFrameNanos = t2;
        if (++profFrames >= 120) {
            dev.t1m3.qplayer.util.Logger.info(
                "frame: {}fps render {}ms skip {}/{} bumps tick {} render {} (per120)",
                Math.round(1000.0 / (profGapMs / profFrames)),
                round1(profRenderMs / profFrames),
                profSkips, profFrames,
                profBumpTick, profBumpRender);
            profFrames = 0;
            profSkips = 0;
            profBumpTick = profBumpRender = 0;
            profLayoutMs = profRenderMs = profPresentMs = profGapMs = profMaxGapMs = 0;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // First QML load/render failure: stop the loop (so it doesn't crash-spin),
    // dump the trace to a file we can pull, and hand it to the host to display.
    private void reportError(Throwable t) {
        if (failed) return;
        failed = true;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        // App-private external dir needs no permission and always succeeds:
        //   /sdcard/Android/data/<pkg>/files/qplayer-crash.log
        writeTrace(getContext().getExternalFilesDir(null), trace);
        // Also try public Downloads (may be denied under scoped storage).
        writeTrace(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), trace);
        ErrorListener l = errorListener;
        if (l != null) l.onError(trace);
    }

    private static void writeTrace(java.io.File dir, String trace) {
        if (dir == null) return;
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "qplayer-crash.log"), false)) {
            w.write(trace);
        } catch (Throwable ignored) {
        }
    }

    private static final class SkijaGlSurface implements SurfaceBackend {
        private DirectContext context;
        private BackendRenderTarget target;
        private Surface surface;
        private int width, height;

        @Override
        public void init(int w, int h) {
            this.width = w;
            this.height = h;
            context = DirectContext.makeGL();
            rebuild();
        }

        @Override
        public Canvas acquireCanvas() {
            return surface.getCanvas();
        }

        @Override
        public void present() {
            context.flush();
        }

        @Override
        public void resize(int w, int h) {
            if (context == null) {
                init(w, h);
                return;
            }
            if (w == width && h == height) return;
            this.width = w;
            this.height = h;
            rebuild();
        }

        @Override
        public int width() { return width; }

        @Override
        public int height() { return height; }

        @Override
        public DirectContext recordingContext() { return context; }

        @Override
        public void dispose() {
            if (surface != null) { surface.close(); surface = null; }
            if (target != null) { target.close(); target = null; }
            if (context != null) { context.close(); context = null; }
        }

        private void rebuild() {
            if (surface != null) surface.close();
            if (target != null) target.close();
            target = BackendRenderTarget.makeGL(width, height, 0, 8, 0,
                                                FramebufferFormat.GR_GL_RGBA8);
            surface = Surface.makeFromBackendRenderTarget(
                context, target,
                SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888,
                ColorSpace.getSRGB());
        }
    }
}
