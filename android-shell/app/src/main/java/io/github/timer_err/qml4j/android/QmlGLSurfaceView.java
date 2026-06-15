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
import dev.t1m3.qplayer.android.AppSettings;
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
    private AppSettings settings;
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
    // The lyric edge-fade gradient (native Shader) + its mask Paint depend only on the
    // surface height, so cache them and rebuild only when the height changes (rebuilding
    // every frame was steady native churn -> GC stutter).
    private float lyShaderH = -1f;
    private io.github.humbleui.skija.Shader lyFadeShader;
    private final Paint lyMaskPaint = new Paint();

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
        // Keep the EGL context across pause/resume. Otherwise the context (and all GL
        // resources) is destroyed when the app backgrounds, so Canvas-backed content
        // drawn into offscreen buffers -- the QR code, LoadingIndicator, the wavy
        // progress -- comes back blank until something triggers a repaint (a static
        // Canvas like the QR never does).
        setPreserveEGLContextOnPause(true);
        setRenderer(new GlRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // The lyric page chrome (title / progress / buttons) is QML on top of the host
        // lyric layer, so touches dispatch to the QML scene as usual -- the LyricOverlay
        // handles seek, transport and close.
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

    /** Expose app settings to QML as the {@code settings} context global. Set before
     *  the GL thread first lays out. */
    public void setSettings(AppSettings s) {
        this.settings = s;
    }

    /** Apply a system night-mode change on the render thread. */
    public void onSystemNightChanged(final boolean dark) {
        queueEvent(new Runnable() {
            @Override public void run() {
                if (settings != null) settings.applySystemDark(dark);
            }
        });
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
                    if (settings != null) view.context("settings", settings);
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
                // The host lyric layer (fluid backdrop + per-syllable lyrics) draws
                // FIRST, underneath the QML scene, so the QML lyric chrome (title /
                // wavy progress / icon buttons) composites on top of the fluid. When
                // the page is closed drawLyricOverlay paints nothing. The QML scene
                // always renders; its main UI fades out as the lyric page opens, so
                // only the (cheap) LyricOverlay chrome remains over the fluid.
                drawLyricOverlay(canvas);
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
        // Publish to QML so the LyricOverlay chrome fades in/out in lockstep.
        controller.lyricSlide.set((double) lyricSlide);
        if (lyricSlide <= 0.001f && !open) return;

        // Re-feed the renderer when the track's lyric list changes (identity).
        Object lyObj = controller.lyrics.peek();
        if (lyObj != lastLyrics) {
            lastLyrics = (List<LyricLine>) lyObj;
            lyricRenderer.setLyrics(lastLyrics);
        }

        float w = surface.width() / uiScale;
        float h = surface.height() / uiScale;
        ensureLyricShaders(h);
        // The fluid + lyrics fill the screen opaquely while open; the open/close
        // animation is a cross-fade -- the QML main UI fades out (revealing this) and
        // the QML LyricOverlay fades in, both off player.lyricSlide. No vertical slide
        // here (it would leave the app's opaque background showing through the gap).
        int sc = canvas.save();
        canvas.scale(uiScale, uiScale);

        // 1) fluid backdrop, keyed by the current track.
        byte[] cover = (byte[]) controller.coverBytes.peek();
        String key = controller.title.peek() + "|" + controller.coverUrl.peek();
        fluidBg.render(canvas, w, h, cover, key, System.nanoTime());

        // 2) fluid backdrop already drawn. The title (top band) and transport
        // (bottom band) are drawn by the QML LyricOverlay on top of this; only the
        // lyrics column is host-drawn. Render into a layer, then multiply a vertical
        // alpha gradient (DST_IN) so lines fade toward the top/bottom edges.
        float pad = 28f;
        float topY = 140f;
        float colH = h - topY - L_TRANSPORT_H;
        io.github.humbleui.types.Rect colRect = io.github.humbleui.types.Rect.makeXYWH(0f, topY, w, colH);
        int lc = canvas.saveLayer(colRect, null);
        LyricSkia.setCanvas(canvas);
        lyricRenderer.render(canvas, pad, topY, w - 2f * pad, colH, controller.position());
        lyMaskPaint.setShader(lyFadeShader);
        lyMaskPaint.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
        canvas.drawRect(colRect, lyMaskPaint);
        lyMaskPaint.setShader(null);
        canvas.restoreToCount(lc);

        canvas.restoreToCount(sc);
    }

    // Reserved height (logical px) for the lyric-page transport bar at the bottom.
    private static final float L_TRANSPORT_H = 136f;

    // Rebuild the cached lyric gradients only when the surface height changes.
    private void ensureLyricShaders(float h) {
        if (h == lyShaderH && lyFadeShader != null) return;
        lyShaderH = h;
        if (lyFadeShader != null) lyFadeShader.close();
        float topY = 140f, colH = h - topY - L_TRANSPORT_H;
        float f = Math.min(0.4f, 40f / colH);
        lyFadeShader = io.github.humbleui.skija.Shader.makeLinearGradient(
                0f, topY, 0f, topY + colH,
                new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, f, 1f - f, 1f});
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
