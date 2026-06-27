package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;
import io.github.timer_err.qml4j.render.items.core.Item;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.LyricLine;

import java.util.List;

/**
 * Platform-neutral per-frame compositor for the qplayer scene: it draws the QML
 * main scene, the host-drawn lyric page (fluid SkSL backdrop + per-syllable
 * column sliding up over the scene), and the QML lyric chrome subtree on top —
 * the exact three-layer pass that used to live inline in the Android
 * {@code QmlGLSurfaceView.onDrawFrame}. Extracted here so the Android shell and
 * the desktop LWJGL host render identical lyrics.
 *
 * <p>All state is GL/render-thread only (no synchronization). The caller must
 * have already drained its dirty queue / pumped the controller / ticked
 * animations for this frame before invoking {@link #composite}; the compositor
 * reads {@link Property#changeVersion()} to drive the idle layout-skip.
 */
public final class LyricCompositor {

    /** The two host settings the lyric page reads. Android's AppSettings and the
     *  desktop DesktopSettings each implement this. */
    public interface SettingsBridge {
        /** Status-bar inset in logical px reserved above the lyric column (0 on desktop). */
        float topInset();
        /** Static cached backdrop instead of the animated fluid (battery saver). */
        boolean lyricBgStatic();
    }

    // Reserved height (logical px) for the lyric-page transport bar at the bottom.
    private static final float L_TRANSPORT_H = 136f;

    private final LyricRenderer lyricRenderer = new LyricRenderer();
    private final FluidBackground fluidBg = new FluidBackground(System.nanoTime());

    private List<LyricLine> lastLyrics;
    private float lyricSlide;
    private long renderedVersion = -1;

    // The QML lyric-chrome subtree (objectName "lyricChrome"), rendered on top of
    // the host fluid; looked up once after the scene loads.
    private Item lyricChrome;

    // Wall-clock extrapolation of the coarse backend position for a smooth progress bar.
    private long lyRawLast = -1;
    private boolean lyPlayingLast;
    private long lyBaseMs;
    private long lyBaseNanos;

    // Edge-fade gradient + mask paint, cached by surface height + column top.
    private float lyShaderH = -1f;
    private float lyShaderTopY = -1f;
    private Shader lyFadeShader;
    private final Paint lyMaskPaint = new Paint();

    // Cached lyric-column rect + cover-key string (both were rebuilt every frame).
    private Rect lyColRect;
    private float lyColW = -1f, lyColH = -1f;
    private Object lyKeyTitle, lyKeyUrl;
    private String lyCoverKey;

    /** The host-drawn lyric renderer; the shell feeds it scroll/seek gestures. */
    public LyricRenderer lyricRenderer() {
        return lyricRenderer;
    }

    /** Eased open fraction (0 closed, 1 fully covering the scene), for gesture gating. */
    public float lyricSlide() {
        return lyricSlide;
    }

    /** Lyric column top, in logical px: the status-bar inset plus the QML title band. */
    public float lyricTopY(float topInset) {
        return topInset + 144f;
    }

    /** Whether a touch/drag at logical {@code y} should scroll the lyric column: the
     *  page must be fully open and the point inside the lyric band. */
    public boolean lyricsScrollable(float y, float surfaceHeightLogical, float topInset) {
        if (lyricSlide < 0.99f || !lyricRenderer.hasLines()) return false;
        float topY = lyricTopY(topInset);
        float bottomY = surfaceHeightLogical - L_TRANSPORT_H;
        return y >= topY && y <= bottomY;
    }

    /**
     * Draw the full frame: QML main scene, host lyric overlay, QML lyric chrome.
     * The {@code renderer}'s GPU context must already be set by the caller; the
     * fluid backdrop needs the same {@code ctx} explicitly (the renderer keeps it
     * package-private).
     */
    public void composite(Canvas canvas, Renderer renderer, QmlView view,
                          PlayerController controller, SettingsBridge settings,
                          DirectContext ctx, float uiScale, int fbW, int fbH) {
        float lw = fbW / uiScale, lh = fbH / uiScale;
        if (lyricChrome == null) lyricChrome = view.findByObjectName("lyricChrome");

        // Three layers so the lyric page slides up OVER the main UI while its md3
        // chrome still sits above the host fluid:
        //  1) the QML main scene (skipped once the lyric page fully covers it),
        //  2) the host fluid backdrop + per-syllable lyrics (slides up),
        //  3) the lyric chrome subtree (title / wavy progress / transport).
        double slidePrev = controller != null ? controller.lyricSlide.peek() : 0.0;
        boolean fullyCovered = slidePrev >= 0.999;
        if (!fullyCovered) {
            int sc = canvas.save();
            canvas.scale(uiScale, uiScale);
            boolean skipLayout = Property.changeVersion() == renderedVersion;
            renderer.render(canvas, view.root(), skipLayout);
            renderedVersion = Property.changeVersion();
            canvas.restoreToCount(sc);
        } else if (Property.changeVersion() != renderedVersion) {
            // Lyric page fully covers the main scene so we skip drawing it — but the
            // chrome subtree (renderSubtree doesn't settle layout) relies on the main
            // render's layout pass. When something changed, settle once so the chrome's
            // anchors reflow; the draw is painted over by the fluid, so it stays hidden.
            int sc = canvas.save();
            canvas.scale(uiScale, uiScale);
            renderer.render(canvas, view.root(), false);
            renderedVersion = Property.changeVersion();
            canvas.restoreToCount(sc);
        }
        drawLyricOverlay(canvas, controller, settings, ctx, uiScale, fbW, fbH);
        double slideNow = controller != null ? controller.lyricSlide.peek() : 0.0;
        if (slideNow > 0.001 && lyricChrome != null) {
            int scC = canvas.save();
            canvas.scale(uiScale, uiScale);
            renderer.renderSubtree(canvas, lyricChrome, lw, lh);
            canvas.restoreToCount(scC);
        }
    }

    // Host-drawn lyric page: a fluid (SkSL, cover-keyed) backdrop with the
    // LyricRenderer on top, sliding up over the QML scene. Runs every frame so the
    // slide + scroll springs animate.
    private void drawLyricOverlay(Canvas canvas, PlayerController controller,
                                  SettingsBridge settings, DirectContext ctx,
                                  float uiScale, int fbW, int fbH) {
        if (controller == null) return;
        boolean open = Boolean.TRUE.equals(controller.lyricsOpen.peek());
        float target = open ? 1f : 0f;
        // Critically-damped-ish ease toward the target; cheap and frame-stable.
        lyricSlide += (target - lyricSlide) * 0.22f;
        if (Math.abs(target - lyricSlide) < 0.002f) lyricSlide = target;
        // Publish to QML so the LyricOverlay chrome fades in/out in lockstep. set()
        // no-ops on an unchanged value, so once settled-closed this stops bumping the
        // change version.
        controller.lyricSlide.set((double) lyricSlide);
        // Closed and settled: nothing more to draw. RETURN BEFORE touching
        // lyricProgress — it changes every frame while playing, and setting it would
        // bump the change version every frame, defeating the renderer's idle skip.
        if (lyricSlide <= 0.001f && !open) return;

        // Per-frame playback fraction for the QML wavy progress bar. backend.position()
        // is coarse (~5 Hz), so extrapolate from the last change with wall-clock time;
        // resync when the backend jumps (seek) or play/pause toggles.
        long durMs = controller.durationMs.peek();
        long raw = controller.position();
        boolean playing = Boolean.TRUE.equals(controller.playing.peek());
        long nowN = System.nanoTime();
        if (raw != lyRawLast || playing != lyPlayingLast) {
            lyRawLast = raw;
            lyPlayingLast = playing;
            lyBaseMs = raw;
            lyBaseNanos = nowN;
        }
        long predMs = playing ? lyBaseMs + (nowN - lyBaseNanos) / 1_000_000L : raw;
        if (durMs > 0 && predMs > durMs) predMs = durMs;
        controller.lyricProgress.set(durMs > 0 ? Math.min(1.0, predMs / (double) durMs) : 0.0);

        // Re-feed the renderer when the track's lyric list changes (identity).
        List<LyricLine> lyObj = controller.lyrics.peek();
        if (lyObj != lastLyrics) {
            lastLyrics = lyObj;
            lyricRenderer.setLyrics(lastLyrics);
        }

        float w = fbW / uiScale;
        float h = fbH / uiScale;
        float topInset = settings != null ? settings.topInset() : 0f;
        float topY = lyricTopY(topInset);
        ensureLyricShaders(h, topY);
        int sc = canvas.save();
        canvas.scale(uiScale, uiScale);

        // The page slides up from the bottom over the QML main scene (drawn in the prior
        // pass). The LyricOverlay chrome slides with the same smoothstep offset.
        float ease = lyricSlide * lyricSlide * (3f - 2f * lyricSlide); // smoothstep
        canvas.translate(0f, (1f - ease) * h);

        // 1) fluid backdrop, keyed by the current track. The key only changes on a
        // track switch, so rebuild the concatenated string only when an input does.
        byte[] cover = (byte[]) controller.coverBytes.peek();
        Object title = controller.title.peek();
        Object coverUrl = controller.coverUrl.peek();
        if (lyCoverKey == null || title != lyKeyTitle || coverUrl != lyKeyUrl) {
            lyKeyTitle = title;
            lyKeyUrl = coverUrl;
            lyCoverKey = title + "|" + coverUrl;
        }
        boolean bgStatic = settings != null && settings.lyricBgStatic();
        fluidBg.render(canvas, ctx, uiScale, w, h, cover, lyCoverKey,
                System.nanoTime(), bgStatic);

        // 2) the lyrics column. The title (top band) and transport (bottom band) are
        // drawn by the QML LyricOverlay on top of this; only the lyrics column is
        // host-drawn. Render into a layer, then multiply a vertical alpha gradient
        // (DST_IN) so lines fade toward the top/bottom edges.
        float pad = 28f;
        float colH = h - topY - L_TRANSPORT_H;
        if (lyColRect == null || lyColW != w || lyColH != colH) {
            lyColRect = Rect.makeXYWH(0f, topY, w, colH);
            lyColW = w;
            lyColH = colH;
        }
        Rect colRect = lyColRect;
        int lc = canvas.saveLayer(colRect, null);
        LyricSkia.setCanvas(canvas);
        lyricRenderer.render(canvas, pad, topY, w - 2f * pad, colH, controller.position());
        lyMaskPaint.setShader(lyFadeShader);
        lyMaskPaint.setBlendMode(BlendMode.DST_IN);
        canvas.drawRect(colRect, lyMaskPaint);
        lyMaskPaint.setShader(null);
        canvas.restoreToCount(lc);

        canvas.restoreToCount(sc);
    }

    // Rebuild the cached lyric gradients only when the surface height or column top
    // changes (top tracks the status-bar inset, which can settle late).
    private void ensureLyricShaders(float h, float topY) {
        if (h == lyShaderH && topY == lyShaderTopY && lyFadeShader != null) return;
        lyShaderH = h;
        lyShaderTopY = topY;
        if (lyFadeShader != null) lyFadeShader.close();
        float colH = h - topY - L_TRANSPORT_H;
        float f = Math.min(0.4f, 40f / colH);
        lyFadeShader = Shader.makeLinearGradient(
                0f, topY, 0f, topY + colH,
                new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, f, 1f - f, 1f});
    }

    /** Drop the cached chrome lookup so it re-resolves against a freshly loaded scene. */
    public void onSceneReloaded() {
        lyricChrome = null;
        renderedVersion = -1;
    }
}
