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
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.input.TextEditable;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.WindowChromeStub;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.resources.CompressedResources;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class QmlGLSurfaceView extends GLSurfaceView {

    private final String qmlSource;
    private final QmlEngine engine;
    private final ResourceLoader resources;
    private QmlView view;
    private SkijaGlSurface surface;
    private PlayerController controller;
    private SettingsCore settings;
    private volatile boolean failed;
    private ErrorListener errorListener;
    private SplashListener splashListener;
    private volatile boolean readyFired;
    private volatile boolean disposed;
    private volatile boolean gpuPaused;
    private volatile boolean pauseRequested;
    private boolean glThreadPaused;

    /** Drives a host splash while the QML tree compiles: per-component progress
     *  (on the GL thread) and a one-shot ready signal at the first painted frame. */
    public interface SplashListener {
        void onProgress(String name, int count);
        void onReady();
    }

    public void setSplashListener(SplashListener l) {
        this.splashListener = l;
    }

    // Host-drawn lyric page (shared verbatim with the desktop host): the fluid
    // backdrop, per-syllable column, the three-layer scene composite and all its
    // cached shaders/state live in LyricCompositor. This view only owns the
    // platform-specific touch gestures that drive it.
    private final LyricCompositor compositor = new LyricCompositor();
    // Lyric-body gesture state (GL-thread only). lyGrab: the touch is ours (vs the QML
    // scene). lyMoved: it has passed the slop, so it's a scroll, not a tap-to-seek.
    private boolean lyGrab;
    private boolean lyMoved;
    private float lyDownY;
    // Movement (logical px) past which a lyric-body touch is a scroll rather than a tap.
    private static final float LYRIC_TAP_SLOP = 12f;

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
        // Release the EGL context while backgrounded. Keeping a full-screen Skija
        // context alive cost ~75 MB on the test device and made this foreground-media
        // process an OEM low-memory-killer target. onPause() explicitly invalidates
        // Canvas/fluid GPU caches first, so they rebuild cleanly on resume.
        setPreserveEGLContextOnPause(false);
        setRenderer(new GlRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public void onPause() {
        pauseRequested = true;
        // QmlView.load() performs the first D8 compilation on the GL thread. The
        // platform GLSurfaceView.onPause() waits for that thread without a timeout,
        // which turns a quick Home gesture during cold start into a main-thread ANR.
        // Let compilation finish off-screen, then pause from the ready callback.
        if (!readyFired || disposed || glThreadPaused) return;
        pauseGlThread();
    }

    private void pauseGlThread() {
        gpuPaused = true;
        runOnGlThreadAndWait(() -> {
            if (view != null) invalidateCanvasCaches(view.root());
            compositor.invalidateGpuContext();
            if (surface != null) {
                surface.dispose();
                surface = null;
            }
        });
        super.onPause();
        glThreadPaused = true;
    }

    @Override
    public void onResume() {
        pauseRequested = false;
        if (glThreadPaused) {
            super.onResume();
            glThreadPaused = false;
        }
    }

    /** Tear down QML bindings, images, shaders and decoder workers before an
     * Activity recreation drops this view. QmlView.dispose() is what unsubscribes
     * bindings from the process-wide shared PlayerController; omitting it retained
     * each destroyed Activity and its native image graph. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        runOnGlThreadAndWait(() -> {
            failed = true;
            if (view != null) {
                view.dispose();
                view = null;
            }
            compositor.dispose();
            if (surface != null) {
                surface.dispose();
                surface = null;
            }
            controller = null;
            settings = null;
            errorListener = null;
            splashListener = null;
        });
    }

    private void runOnGlThreadAndWait(Runnable action) {
        CountDownLatch done = new CountDownLatch(1);
        try {
            queueEvent(() -> {
                try {
                    action.run();
                } finally {
                    done.countDown();
                }
            });
            if (!done.await(1500, TimeUnit.MILLISECONDS)) {
                dev.t1m3.qplayer.util.Logger.warn("GL cleanup timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn("GL cleanup failed: {}", e.toString());
        }
    }

    private static void invalidateCanvasCaches(io.github.timer_err.qml4j.render.items.core.Item item) {
        if (item == null) return;
        if (item instanceof io.github.timer_err.qml4j.render.items.core.Canvas) {
            io.github.timer_err.qml4j.render.items.core.Canvas canvas =
                    (io.github.timer_err.qml4j.render.items.core.Canvas) item;
            if (canvas.backing != null) {
                try {
                    canvas.backing.close();
                } catch (Throwable ignored) {
                }
                canvas.backing = null;
                canvas.backingW = 0;
                canvas.backingH = 0;
                canvas.dirty = true;
                canvas.requestPaint();
            }
        }
        for (int i = 0; i < item.children.size(); i++) {
            invalidateCanvasCaches(item.children.get(i));
        }
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
                        // The lyric body (between the QML title and transport bands) is
                        // host-drawn with no QML controls under it, so a touch there is
                        // ours: a drag scrolls the column, a tap seeks to that line. Don't
                        // engage the scroll yet -- wait for movement so a tap stays a tap.
                        // Suppressed while the offset-adjust panel (real QML) is open over
                        // that same region.
                        boolean offsetPanelOpen = controller != null
                                && Boolean.TRUE.equals(controller.lyricOffsetPanelOpen.peek());
                        if (!offsetPanelOpen && compositor.lyricsScrollable(x, y, surface.width() / uiScale, surface.height() / uiScale, insetTop())) {
                            lyGrab = true;
                            lyDownY = y;
                            lyMoved = false;
                            return;
                        }
                        lyGrab = false;
                        boolean hitTextEditable = view.pickTextEditable(x, y) != null;
                        view.dispatchPointerDown(x, y);
                        if (hitTextEditable) showImeOnUiThread();
                    }
                });
                return true;
            case MotionEvent.ACTION_MOVE:
                queueEvent(new Runnable() {
                    @Override public void run() {
                        if (lyGrab) {
                            if (!lyMoved) {
                                if (Math.abs(y - lyDownY) > LYRIC_TAP_SLOP) {
                                    lyMoved = true;
                                    compositor.lyricRenderer().scrollDown(lyDownY);
                                    compositor.lyricRenderer().scrollMove(y);
                                }
                            } else {
                                compositor.lyricRenderer().scrollMove(y);
                            }
                            return;
                        }
                        if (view != null) view.dispatchPointerMove(x, y);
                    }
                });
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                queueEvent(new Runnable() {
                    @Override public void run() {
                        if (lyGrab) {
                            lyGrab = false;
                            if (lyMoved) {
                                compositor.lyricRenderer().scrollUp();
                            } else if (action == MotionEvent.ACTION_UP) {
                                long t = compositor.lyricRenderer().timeAtScreenY(lyDownY);
                                if (t >= 0L && controller != null) {
                                    compositor.lyricRenderer().cancelUserScrollForSeek();
                                    controller.seek(t + LyricConfig.instance.offsetMs.getValue());
                                }
                            }
                            return;
                        }
                        if (view != null) view.dispatchPointerUp(x, y);
                    }
                });
                return true;
            default:
                return false;
        }
    }

    // Status-bar inset in logical px, fed to the lyric compositor for column gating.
    private float insetTop() {
        return settings != null ? settings.topInset() : 0f;
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
        // BACK is handled centrally in QPlayerActivity.onBackPressed (so hardware and
        // gesture back share one path); let it fall through to the activity.
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
    public void setSettings(SettingsCore s) {
        this.settings = s;
    }

    /** Apply a system night-mode change on the render thread. */
    public void onSystemNightChanged(final boolean dark) {
        queueEvent(new Runnable() {
            @Override public void run() {
                if (settings != null) settings.setSystemDark(dark);
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
            if (disposed) return;
            surface = new SkijaGlSurface();
            gpuPaused = false;
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            if (failed || disposed || surface == null) return;
            surface.resize(width, height);
            try {
                if (view == null) {
                    view = QmlView.withStockTypes(engine).resources(resources);
                    // Cache stable top-level QML subtrees as SkPictures. This must be
                    // enabled before load() so every constructed Item participates in
                    // content invalidation; the lyric compositor also uses the cache-
                    // aware render path once its overlay fully covers the main scene.
                    view.renderer().setPictureCache(true);
                    view.setClipboard(new AndroidClipboard(getContext()));
                    view.setFocusListener((nf, of) -> {
                        if (of instanceof TextEditable && !(nf instanceof TextEditable)) {
                            hideImeOnUiThread();
                        }
                    });
                    if (controller != null) {
                        view.context("player", controller);
                        view.networkPolicy(controller::allowRemoteQmlResource);
                    }
                    if (settings != null) view.context("settings", settings);
                    // hostWindow (the desktop-only custom title bar bridge) must still
                    // resolve to something here: qml4j's compiler rejects an undeclared
                    // top-level identifier at compile time, even one only referenced
                    // inside a typeof guard, so shared-qml can't have it be simply
                    // absent on Android. See WindowChromeStub's javadoc (player-core).
                    view.context("hostWindow", new WindowChromeStub());
                    view.setCompileProgressListener((name, count) -> {
                        SplashListener l = splashListener;
                        if (l != null) l.onProgress(name, count);
                    });
                    // Provide the app fonts to the engine (it ships none of its own):
                    // bundled PingFang for the whole UI (Latin + CJK) + Material Symbols
                    // for icon glyphs.
                    byte[] reg = CompressedResources.load(resources, "fonts/PingFangSC-Regular.otf");
                    byte[] med = CompressedResources.load(resources, "fonts/PingFangSC-Medium.otf");
                    if (reg != null || med != null) view.uiTypefaces(reg, med);
                    byte[] iconFont = resources.load("fonts/MaterialSymbolsRounded.ttf");
                    if (iconFont != null) view.iconTypeface(iconFont);
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
            if (failed || disposed || gpuPaused || view == null || surface == null) return;
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
                io.github.timer_err.qml4j.render.Renderer renderer = view.renderer();
                renderer.setGpuContext(surface.recordingContext());
                // The QML main scene, the host lyric overlay (fluid backdrop + per-syllable
                // column) and the QML lyric chrome subtree are composited by the shared
                // LyricCompositor — the same code path the desktop LWJGL host runs.
                compositor.composite(canvas, renderer, view, controller, settings,
                        surface.recordingContext(), uiScale, surface.width(), surface.height());
                if (compositor.skippedLayout()) profSkips++;
                long t1b = System.nanoTime();
                surface.present();
                profileFrame(t0, t1, t1b, System.nanoTime());
                if (!readyFired) {
                    readyFired = true;
                    SplashListener l = splashListener;
                    if (l != null) l.onReady();
                    // Do not call GLSurfaceView.onPause() from its own GL thread.
                    // Re-check on the UI thread because the Activity may already
                    // have resumed while the initial QML compilation was running.
                    post(() -> {
                        if (pauseRequested && !disposed && !glThreadPaused) {
                            pauseGlThread();
                        }
                    });
                }
            } catch (Throwable t) {
                reportError(t);
            } finally {
                dq.uninstall();
            }
        }
    }

    // Lightweight frame profiler: accumulates layout (tick+flush) vs paint
    // (render) time and the wall-clock gap between frames, logging a summary
    // every ~120 frames so scroll hitches show up in the in-app log.
    private long profLastFrameNanos;
    private int profFrames;
    private int profSkips;
    private long profBumpTick;
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
            // Logging on the GL thread hits logcat + the in-app ring every ~2s at
            // 60fps. That itself is a hitch, and the ring used to force a whole-tree
            // relayout; only emit while the overlay is open (where the summary is
            // actually read).
            if (controller != null && controller.isLogVisible()) {
                dev.t1m3.qplayer.util.Logger.info(
                    "frame: {}fps tick {}ms render {}ms present {}ms max-gap {}ms skip {}/{} bumps tick {} (per120)",
                    Math.round(1000.0 / (profGapMs / profFrames)),
                    round1(profLayoutMs / profFrames),
                    round1(profRenderMs / profFrames),
                    round1(profPresentMs / profFrames),
                    round1(profMaxGapMs),
                    profSkips, profFrames,
                    profBumpTick);
            }
            profFrames = 0;
            profSkips = 0;
            profBumpTick = 0;
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
