package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.lyric.skia.DesktopLyricRenderer;
import dev.t1m3.qplayer.util.Logger;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;
import io.github.timer_err.qml4j.render.items.core.Item;

import java.util.concurrent.locks.LockSupport;

/** Owns the floating lyric window's backend, qml4j scene, and frame clock. */
final class DesktopLyricRenderThread extends Thread {

    private static final long FRAME_NANOS = 1_000_000_000L / 60L;

    private final DesktopLyricWindow owner;
    private final GraphicsBackend backend;
    private volatile boolean running = true;

    DesktopLyricRenderThread(DesktopLyricWindow owner) {
        super("qplayer-desktop-lyric-render");
        this.owner = owner;
        this.backend = new GLBackend(owner.window(), true);
    }

    void shutdown() {
        running = false;
        LockSupport.unpark(this);
    }

    @Override
    public void run() {
        QmlView view = null;
        DesktopLyricRenderer lyricRenderer = null;
        Paint backgroundPaint = null;
        Paint outlinePaint = null;
        boolean firstFramePresented = false;
        try {
            DesktopLyricWindow.FramebufferSize size = owner.framebufferSize();
            backend.init(size.width(), size.height());
            DesktopLyricState state = new DesktopLyricState(owner);
            lyricRenderer = new DesktopLyricRenderer();
            DesktopLyricChromeMotion chromeMotion = new DesktopLyricChromeMotion();
            backgroundPaint = new Paint().setMode(PaintMode.FILL);
            outlinePaint = new Paint().setMode(PaintMode.STROKE).setStrokeWidth(1f);
            Rect windowRect = Rect.makeWH(DesktopLyricWindow.WIDTH, DesktopLyricWindow.HEIGHT);
            Item chromeCanvas;
            Item lockedControl;
            synchronized (QmlRuntimeLock.MONITOR) {
                view = QmlView.withStockTypes(new QmlEngine())
                        .resources(owner.resources())
                        .compilationCache(owner.qmlCompilationCache(),
                                owner.qmlCompilationCache().sceneKey("DesktopLyric.qml"));
                // QmlView defaults picture caching to false. In qml4j 0.2.x the
                // corresponding Item.contentCacheEnabled switch is unfortunately
                // static, so constructing this second, otherwise independent view
                // disables invalidation for the main view's cached cover/buttons.
                // Keep both instances on the same enabled mode until qml4j makes
                // that switch renderer-local.
                view.renderer().setPictureCache(true);
                DesktopWindow.loadFonts(view, owner.resources());
                view.context("desktopLyric", state);
                view.load(owner.qmlSource());
                if (view.root() != null) {
                    view.root().width.set((double) DesktopLyricWindow.WIDTH);
                    view.root().height.set((double) DesktopLyricWindow.HEIGHT);
                }
                chromeCanvas = view.findByObjectName("desktopLyricChromeCanvas");
                lockedControl = view.findByObjectName("desktopLyricLockedControl");
                if (chromeCanvas == null || lockedControl == null) {
                    throw new IllegalStateException("desktop lyric chrome subtrees not found");
                }
            }
            Logger.info("desktop lyric render thread ready (backend {})", backend.kind());
            while (running) {
                if (!owner.isEnabled() || !owner.hasPublishedSnapshot()) {
                    LockSupport.parkNanos(250_000_000L);
                    continue;
                }
                long frameStart = System.nanoTime();
                DirtyQueue dirty = view.dirtyQueue();
                synchronized (QmlRuntimeLock.MONITOR) {
                    dirty.install();
                    try {
                        owner.drainInput(view);
                        state.update(owner.snapshot(), frameStart);
                        view.tickAnimations(frameStart);
                        dirty.flush();
                        DesktopLyricChromeMotion.Frame chrome = chromeMotion.update(
                                owner.isPointerInside() && !owner.isMousePassthrough(), frameStart);
                        size = owner.framebufferSize();
                        backend.resize(size.width(), size.height());
                        Canvas canvas = backend.acquireCanvas();
                        Renderer renderer = view.renderer();
                        renderer.setGpuContext(backend.recordingContext());
                        int save = canvas.save();
                        try {
                            canvas.scale(
                                    (float) size.width() / DesktopLyricWindow.WIDTH,
                                    (float) size.height() / DesktopLyricWindow.HEIGHT);
                            // Layout is still owned by qml4j, while motion is applied
                            // to the complete chrome subtree on the actual Skia canvas.
                            // This avoids animated Transform properties invalidating
                            // the process-global qml4j picture cache only at endpoints.
                            renderer.layoutOnly(view.root());
                            DesktopLyricPalette palette = state.palette();
                            backgroundPaint.setColor(Renderer.parseColor(palette.surface));
                            backgroundPaint.setAlphaf(chrome.backgroundOpacity());
                            canvas.drawRect(windowRect, backgroundPaint);
                            outlinePaint.setColor(Renderer.parseColor(palette.outline));
                            outlinePaint.setAlphaf(chrome.backgroundOpacity());
                            canvas.drawRect(windowRect, outlinePaint);

                            if (chrome.opacity() > 0.001f) {
                                int alpha = Math.round(chrome.opacity() * 255f);
                                int layer = canvas.saveLayerAlpha(windowRect, alpha);
                                canvas.translate(DesktopLyricWindow.WIDTH * 0.5f,
                                        DesktopLyricWindow.HEIGHT * 0.5f);
                                canvas.scale(chrome.scaleX(), chrome.scaleY());
                                canvas.translate(DesktopLyricWindow.WIDTH * -0.5f,
                                        DesktopLyricWindow.HEIGHT * -0.5f);
                                renderer.renderSubtree(canvas, chromeCanvas,
                                        DesktopLyricWindow.WIDTH, DesktopLyricWindow.HEIGHT);
                                canvas.restoreToCount(layer);
                            }
                            // Passthrough must always remain reversible. Its locked
                            // control deliberately bypasses both canvas scale and fade.
                            if (owner.isMousePassthrough()) {
                                renderer.renderSubtree(canvas, lockedControl,
                                        DesktopLyricWindow.WIDTH, DesktopLyricWindow.HEIGHT);
                            }
                            lyricRenderer.render(canvas,
                                    DesktopLyricWindow.LYRIC_LEFT, 6f,
                                    DesktopLyricWindow.WIDTH - DesktopLyricWindow.LYRIC_LEFT
                                            - DesktopLyricWindow.LYRIC_RIGHT_MARGIN,
                                    DesktopLyricWindow.HEIGHT - 12f,
                                    state.frame(), state.fallbackText(),
                                    state.fontFamilyValue(), state.fontSizeValue(),
                                    state.fontWeightValue(),
                                    state.shadowValue(), state.outlineValue(),
                                    state.palette().lyricColors,
                                    state.positionMs(), frameStart);
                        } finally {
                            canvas.restoreToCount(save);
                        }
                    } finally {
                        dirty.uninstall();
                    }
                }
                backend.present();
                if (!firstFramePresented) {
                    firstFramePresented = true;
                    owner.onFirstFrameRendered();
                }
                long remaining = FRAME_NANOS - (System.nanoTime() - frameStart);
                if (remaining > 0L) LockSupport.parkNanos(remaining);
            }
        } catch (Throwable error) {
            if (running) owner.onRenderError(error);
        } finally {
            synchronized (QmlRuntimeLock.MONITOR) {
                try {
                    if (view != null) GpuCaches.invalidate(view.root());
                } catch (Throwable ignored) {
                }
                try {
                    if (view != null) view.dispose();
                } catch (Throwable ignored) {
                }
            }
            if (lyricRenderer != null) lyricRenderer.close();
            if (backgroundPaint != null) backgroundPaint.close();
            if (outlinePaint != null) outlinePaint.close();
            try {
                backend.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

}
