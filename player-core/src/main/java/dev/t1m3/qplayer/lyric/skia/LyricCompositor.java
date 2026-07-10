package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
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

    // Reserved height (logical px) for the lyric-page transport bar at the bottom
    // (portrait, where the transport sits under the lyrics).
    private static final float L_TRANSPORT_H = 136f;
    // Landscape lyric column insets: the column moves to the right half and the
    // cover + transport live in the QML chrome on the left, so the column can run
    // nearly the full height. Kept in sync with LyricOverlay.qml and lyricsScrollable.
    private static final float L_LANDSCAPE_TOP = 24f;
    private static final float L_LANDSCAPE_BOTTOM = 24f;

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

    // Edge-fade gradient + mask paint, cached by column top + height.
    private float lyShaderColH = -1f;
    private float lyShaderTopY = -1f;
    private Shader lyFadeShader;
    private final Paint lyMaskPaint = new Paint();

    // Apple-Music progressive edge blur (opt-in). The column is drawn twice with two
    // COMPLEMENTARY masks that never overlap: a blurred copy kept only at the edges, and a
    // sharp copy kept only across a plateau around the active line. Neither is a solid base
    // under the other, so the edges read as a real blur (no sharp glyphs bleeding through)
    // and the active line stays perfectly clean (no blurred halo — that halo was the glow
    // artefact). The masks ramp smoothly, so the sharp->blur transition is a continuous
    // vertical gradient, not per-line steps. Peak blur scales with the font size; masks are
    // cached by column geometry.
    private static final float EDGE_BLUR_SIGMA_RATIO = 0.11f; // blur sigma = fontSize × this
    // Crossfade stops as a fraction of column height, centred on the active line
    // (ALIGN_POSITION 0.35): sharp across 0.26–0.44, blur toward both edges.
    private static final float[] BAND_STOPS = {0f, 0.26f, 0.44f, 0.70f, 1f};
    private final Paint blurBasePaint = new Paint();
    private ImageFilter blurFilter;
    private float blurSigmaApplied = -1f;
    private Shader blurBandShader;  // opaque at edges, transparent in the focus band
    private Shader sharpBandShader; // the complement — opaque in the focus band
    private float bandShaderColH = -1f;
    private float bandShaderTopY = -1f;
    private float bandFracTop = -1f, bandFracBottom = -1f; // cached lit-band fractions
    // Crossfade width beyond the lit band, as a fraction of column height: the sharp
    // plateau covers the lit lines, then ramps to full blur over this distance.
    private static final float BAND_RAMP = 0.24f;

    // Lyrics-column zoom transition: eases 1 -> 0 when a track goes cover-only and back,
    // so the host lyric column scales + fades in lockstep with the QML cover's zoom
    // (SPlayer's whole-content zoom). TAU sets the settle time (~200 ms).
    private static final float LYRIC_ZOOM_TAU = 0.13f;
    private float lyricShow = 0f;
    private long lyricShowNs = 0L;
    private final Paint lyLayerPaint = new Paint();

    // Cached lyric-column rect + cover-key string (both were rebuilt every frame).
    private Rect lyColRect;
    private float lyColW = -1f, lyColH = -1f, lyColTopY = -1f, lyColLeft = -1f;
    // Last published cover-only flag, read by lyricsScrollable off the input thread.
    private boolean coverOnlyCached;
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

    /** Whether a touch/drag at logical {@code (x,y)} should scroll the lyric column: the
     *  page must be fully open and the point inside the lyric band. In landscape the
     *  column is the right half only (the left half holds the QML cover + transport),
     *  and a cover-only track has no column to scroll. */
    public boolean lyricsScrollable(float x, float y, float surfaceWLogical,
                                    float surfaceHeightLogical, float topInset) {
        if (lyricSlide < 0.99f || !lyricRenderer.hasLines()) return false;
        if (surfaceWLogical > surfaceHeightLogical) {
            if (coverOnlyCached) return false;
            float topY = topInset + L_LANDSCAPE_TOP;
            float bottomY = surfaceHeightLogical - L_LANDSCAPE_BOTTOM;
            return x >= surfaceWLogical * 0.5f && y >= topY && y <= bottomY;
        }
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
            // Lyric page fully covers the main scene: the fluid backdrop is opaque, so
            // DRAWING the scene is pure waste. The chrome subtree (renderSubtree doesn't
            // run layout) just needs its own anchors settled, so settle only that subtree
            // — not the whole resident tree. The per-frame progress-bar tick bumps the
            // change version, so this branch runs every frame; drawing the scene cost
            // ~14 ms and settling the full tree ~5 ms, both wasted while covered.
            renderer.layoutOnly(lyricChrome != null ? lyricChrome : view.root());
            renderedVersion = Property.changeVersion();
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

        // Lyric-column geometry by orientation. Portrait stacks the column full-width
        // between the QML title and transport bands; landscape moves it to the right
        // half (the QML chrome draws cover + transport on the left). A cover-only track
        // (no lyrics / instrumental) drops the side column in landscape so the cover
        // can center. Kept in sync with LyricOverlay.qml and lyricsScrollable.
        boolean landscape = w > h;
        boolean coverOnly = Boolean.TRUE.equals(controller.lyricsCoverOnly.peek());
        coverOnlyCached = coverOnly;
        float pad = 28f;
        float colLeft, colTopY, colW, colH;
        if (landscape) {
            colLeft = w * 0.5f;
            colTopY = topInset + L_LANDSCAPE_TOP;
            colW = (w - colLeft) - 2f * pad;
            colH = h - colTopY - L_LANDSCAPE_BOTTOM;
        } else {
            colLeft = 0f;
            colTopY = lyricTopY(topInset);
            colW = w - 2f * pad;
            colH = h - colTopY - L_TRANSPORT_H;
        }
        ensureLyricShaders(colTopY, colH);
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
        // (DST_IN) so lines fade toward the top/bottom edges. Skipped entirely for a
        // cover-only track (no lyrics / instrumental) in EITHER orientation — the QML
        // cover shows in its place (centered portrait, in the left chrome landscape).
        // Ease the column's zoom state toward 1 (has lyrics) or 0 (cover-only), so the
        // switch scales + fades the whole lyric layer instead of it just blinking out.
        float zoomTarget = coverOnly ? 0f : 1f;
        long zoomNow = System.nanoTime();
        if (lyricShowNs != 0L) {
            float dt = (zoomNow - lyricShowNs) / 1_000_000_000f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) lyricShow += (zoomTarget - lyricShow) * (1f - (float) Math.exp(-dt / LYRIC_ZOOM_TAU));
        } else {
            lyricShow = zoomTarget;
        }
        lyricShowNs = zoomNow;
        if (Math.abs(lyricShow - zoomTarget) < 0.002f) lyricShow = zoomTarget;

        if (lyricShow > 0.001f) {
            if (lyColRect == null || lyColLeft != colLeft || lyColTopY != colTopY
                    || lyColW != w || lyColH != colH) {
                lyColRect = Rect.makeXYWH(colLeft, colTopY, w - colLeft, colH);
                lyColLeft = colLeft;
                lyColTopY = colTopY;
                lyColW = w;
                lyColH = colH;
            }
            Rect colRect = lyColRect;
            long pos = controller.position();
            // Alpha-composite the whole column at lyricShow, and zoom it 0.95 -> 1 about
            // its own centre — matching the QML cover's zoom on the opposite side.
            int alpha = Math.round(Math.max(0f, Math.min(1f, lyricShow)) * 255f);
            lyLayerPaint.setAlpha(alpha);
            int lc = canvas.saveLayer(colRect, alpha < 255 ? lyLayerPaint : null);
            float s = 0.95f + 0.05f * lyricShow;
            float cx = colLeft + (w - colLeft) * 0.5f;
            float cy = colTopY + colH * 0.5f;
            int zc = canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(s, s);
            canvas.translate(-cx, -cy);
            LyricSkia.setCanvas(canvas);
            if (Boolean.TRUE.equals(LyricConfig.instance.edgeBlur.getValue())) {
                drawProgressiveBlurColumn(canvas, colRect, colLeft + pad, colTopY, colW, colH, pos);
            } else {
                lyricRenderer.render(canvas, colLeft + pad, colTopY, colW, colH, pos);
            }
            canvas.restoreToCount(zc);
            lyMaskPaint.setShader(lyFadeShader);
            lyMaskPaint.setBlendMode(BlendMode.DST_IN);
            canvas.drawRect(colRect, lyMaskPaint);
            lyMaskPaint.setShader(null);
            canvas.restoreToCount(lc);
        }

        canvas.restoreToCount(sc);
    }

    // Progressive edge blur: draw a uniformly blurred column, then overlay a sharp copy
    // masked to a plateau around the active line. The mask ramps smoothly, so the
    // sharp->blur transition is a continuous vertical gradient (Apple-Music depth of
    // field) rather than the per-line steps a per-row blur produced. Two column renders +
    // one blur pass — only on the opt-in path.
    private void drawProgressiveBlurColumn(Canvas canvas, Rect colRect, float leftX,
                                           float colTopY, float colW, float colH, long pos) {
        float sigma = Math.max(1f, LyricConfig.instance.lyricFontSize.getValue() * EDGE_BLUR_SIGMA_RATIO);
        ensureBlurFilter(sigma);

        // Blurred copy, kept only at the edges.
        int mb = canvas.saveLayer(colRect, null);
        int b = canvas.saveLayer(colRect, blurBasePaint);
        lyricRenderer.render(canvas, leftX, colTopY, colW, colH, pos);
        canvas.restoreToCount(b);
        // Build the band from THIS frame's lit lines (render just refreshed them), so
        // the sharp plateau covers the whole active group rather than one anchor line.
        ensureBandShaders(colTopY, colH, lyricRenderer.litBandBounds());
        lyMaskPaint.setShader(blurBandShader);
        lyMaskPaint.setBlendMode(BlendMode.DST_IN);
        canvas.drawRect(colRect, lyMaskPaint);
        lyMaskPaint.setShader(null);
        canvas.restoreToCount(mb);

        // Sharp copy, kept only across the focus band (complementary mask, no overlap).
        int ms = canvas.saveLayer(colRect, null);
        lyricRenderer.render(canvas, leftX, colTopY, colW, colH, pos);
        lyMaskPaint.setShader(sharpBandShader);
        lyMaskPaint.setBlendMode(BlendMode.DST_IN);
        canvas.drawRect(colRect, lyMaskPaint);
        lyMaskPaint.setShader(null);
        canvas.restoreToCount(ms);
    }

    private void ensureBlurFilter(float sigma) {
        if (sigma == blurSigmaApplied && blurFilter != null) return;
        blurSigmaApplied = sigma;
        if (blurFilter != null) blurFilter.close();
        blurFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
        blurBasePaint.setImageFilter(blurFilter);
    }

    // Build the crossfade masks so the sharp plateau spans the currently-lit lines
    // (lit = screen-space {top, bottom}, or null → the fixed ALIGN-centred plateau).
    // The plateau then ramps to full blur over BAND_RAMP on each side. This is what
    // keeps every line of a multi-line active group sharp, not just the anchor line.
    private void ensureBandShaders(float topY, float colH, float[] lit) {
        float fTop, fBot;
        if (lit != null && colH > 0f) {
            fTop = (lit[0] - topY) / colH;
            fBot = (lit[1] - topY) / colH;
            if (fBot < fTop) { float m = fTop; fTop = fBot; fBot = m; }
        } else {
            fTop = BAND_STOPS[1];   // 0.26 — the original fixed plateau
            fBot = BAND_STOPS[2];   // 0.44
        }
        if (topY == bandShaderTopY && colH == bandShaderColH && blurBandShader != null
                && Math.abs(fTop - bandFracTop) < 0.004f && Math.abs(fBot - bandFracBottom) < 0.004f) {
            return;
        }
        bandShaderTopY = topY;
        bandShaderColH = colH;
        bandFracTop = fTop;
        bandFracBottom = fBot;
        if (blurBandShader != null) blurBandShader.close();
        if (sharpBandShader != null) sharpBandShader.close();
        float[] stops = monotonic(fTop - BAND_RAMP, fTop, fBot, fBot + BAND_RAMP);
        int o = 0xFFFFFFFF, t = 0x00FFFFFF;
        blurBandShader = Shader.makeLinearGradient(0f, topY, 0f, topY + colH,
                new int[]{o, t, t, o}, stops);   // blur kept outside the plateau
        sharpBandShader = Shader.makeLinearGradient(0f, topY, 0f, topY + colH,
                new int[]{t, o, o, t}, stops);   // sharp kept across the plateau
    }

    // Clamp four gradient stops into [0,1] and force them strictly increasing (Skija
    // rejects equal/out-of-order positions).
    private static float[] monotonic(float s0, float s1, float s2, float s3) {
        float[] s = {s0, s1, s2, s3};
        float eps = 1e-4f;
        for (int i = 0; i < s.length; i++) {
            if (s[i] < 0f) s[i] = 0f;
            else if (s[i] > 1f) s[i] = 1f;
        }
        for (int i = 1; i < s.length; i++) {
            if (s[i] <= s[i - 1]) s[i] = Math.min(1f, s[i - 1] + eps);
        }
        return s;
    }

    // Rebuild the cached lyric gradients only when the column top or height changes
    // (top tracks the status-bar inset, which can settle late; both change on a
    // portrait<->landscape flip).
    private void ensureLyricShaders(float topY, float colH) {
        if (topY == lyShaderTopY && colH == lyShaderColH && lyFadeShader != null) return;
        lyShaderTopY = topY;
        lyShaderColH = colH;
        if (lyFadeShader != null) lyFadeShader.close();
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
