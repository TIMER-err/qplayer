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
import dev.t1m3.qplayer.lyric.tempera.TemperaCompositor;
import dev.t1m3.qplayer.lyric.tempera.TemperaHostPage;
import dev.t1m3.qplayer.lyric.tempera.TemperaTuning;

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
        /** Fluid renderer selected in settings; see SettingsCatalog.BG_STYLE_* values. */
        int lyricBgStyle();
        default boolean temperaWholeLine() { return false; }
        default int temperaGlyphSettleStretch() { return 50; }
        default boolean temperaImages() { return false; }
        default int lyricFontSize() { return 28; }
        default boolean resolvedDarkValue() { return true; }
        /** Wide-window layout: no cover, lyrics across the whole page, transport
         *  and progress along the bottom. Desktop-only setting, so this stays
         *  false on hosts that don't offer the row. */
        default boolean lyricFullWidth() { return false; }
    }

    // Reserved height (logical px) for the lyric-page transport bar at the bottom
    // (portrait, where the transport sits under the lyrics).
    private static final float L_TRANSPORT_H = 136f;
    // Landscape lyric column insets: the column moves to the right half and the
    // cover + transport live in the QML chrome on the left, so the column can run
    // nearly the full height. Kept in sync with LyricOverlay.qml and lyricsScrollable.
    private static final float L_LANDSCAPE_TOP = 24f;
    private static final float L_LANDSCAPE_BOTTOM = 24f;
    // Full-width layout: no cover, so the QML chrome becomes a band along the
    // bottom (title/artist, transport, progress) and the column reserves its
    // height instead of the left half's width. Kept in sync with LyricOverlay.qml.
    private static final float L_FULLWIDTH_BOTTOM = 152f;
    // Time constant (seconds) for the change between the two layouts.
    //
    // The column does NOT tween its width across it. The renderer's layout cache is
    // keyed on the column width and rebuilds at 8 lines a frame, so a width that
    // moves every frame restarts that rebuild every frame and the lyrics never
    // finish shaping — the whole transition would be blank. Instead the geometry
    // snaps once, at the halfway point, under a fade: the column dips out, re-wraps
    // while it is invisible, and comes back in the new layout, which is the same
    // move the page already makes when switching between cover and lyrics.
    private static final float FULLWIDTH_TAU = 0.14f;
    // Carve-out for LyricOverlay's top-right icon button row (offset-adjust +
    // cover-mode toggle, two 40px IconButtons side by side with ~6px gaps/margins):
    // in landscape the scrollable band starts only L_LANDSCAPE_TOP below the top,
    // which clips the bottom of that row. Without this the buttons' own pointer-down
    // is intermittently swallowed by the tap-to-seek/drag-to-scroll gesture below
    // instead of reaching the real QML controls. Grown with the row: 56 for one
    // button, 108 once the cover-mode button joined it, now 154 for three
    // (3 x 40px + two 6px gaps + the 6px margin, plus slack).
    private static final float OFFSET_BTN_CORNER_W = 154f;
    private static final float OFFSET_BTN_CORNER_H = 52f;
    // The same carve-out on the other side, for the close button. Only needed in
    // the full-width layout: everywhere else the scrollable band starts at the
    // half-width mark, which already leaves that corner to QML.
    private static final float BACK_BTN_CORNER_W = 56f;

    private final LyricRenderer lyricRenderer = new LyricRenderer();
    private final FluidBackground fluidBg = new FluidBackground(System.nanoTime());
    private final TemperaHostPage temperaPage = new TemperaHostPage();
    private TemperaTuning temperaTuning;
    private Item temperaChrome;

    private List<LyricLine> lastLyrics;
    private float lyricSlide;
    private long renderedVersion = -1;
    // Whether composite() skipped the main-tree relayout this frame (idle fast path).
    // The host reads it for its frame profiler — the skip decision lives here now, so
    // the shell can't compute it itself.
    private boolean skippedLayout;

    // The QML lyric-chrome subtree (objectName "lyricChrome"), rendered on top of
    // the host fluid; looked up once after the scene loads.
    private Item lyricChrome;

    // Wall-clock extrapolation of the coarse backend position for smooth lyric motion.
    // The clock uses PlayerController's actual-started state, not its play-button intent:
    // lyrics can finish loading before an async audio source has made a sound.
    private long lyRawLast = -1;
    private boolean lyClockRunningLast;
    private long lyBaseMs;
    private long lyBaseNanos;
    private long lyPlaybackRevision = -1L;
    private long lyClockSeekRevision = -1L;
    private long lySeekRevision = -1L;

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
    private final float[] bandStops = new float[4];
    private final int[] blurBandColors = new int[4];
    private final int[] sharpBandColors = new int[4];
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
    private Rect backdropClipRect;
    private float backdropClipW = -1f, backdropClipH = -1f;

    // Cached lyric-column rect + cover-key string (both were rebuilt every frame).
    private Rect lyColRect;
    private float lyColW = -1f, lyColH = -1f, lyColTopY = -1f, lyColLeft = -1f;
    // Last published cover-only flag, read by lyricsScrollable off the input thread.
    private boolean coverOnlyCached;
    // Eased 0..1 between the classic (lyrics in the right half) and full-width
    // layouts. Drives the QML chrome crossfade and the column's fade; the column's
    // GEOMETRY follows fullWidthApplied instead, which flips once at the midpoint.
    private float fullWidthEase;
    // The layout actually in force. Read by lyricsScrollable off the input thread.
    private volatile boolean fullWidthApplied;
    private long fullWidthNs;
    private Object lyKeyTitle, lyKeyUrl;
    private String lyCoverKey;

    /** The host-drawn lyric renderer; the shell feeds it scroll/seek gestures. */
    public LyricRenderer lyricRenderer() {
        return lyricRenderer;
    }

    /** Release scene-lifetime CPU/native resources. Must run on the owning render
     * thread after GPU-context caches have been invalidated. */
    public void dispose() {
        temperaPage.dispose();
        fluidBg.dispose();
        lyricRenderer.dispose();
        if (lyFadeShader != null) {
            lyFadeShader.close();
            lyFadeShader = null;
        }
        if (blurBandShader != null) {
            blurBandShader.close();
            blurBandShader = null;
        }
        if (sharpBandShader != null) {
            sharpBandShader.close();
            sharpBandShader = null;
        }
        if (blurFilter != null) {
            blurFilter.close();
            blurFilter = null;
        }
        lyMaskPaint.close();
        blurBasePaint.close();
        lyLayerPaint.close();
    }

    /** Render-thread recreation resumes at a discontinuous playback position. */
    public void onRenderResumed() {
        lyricRenderer.easeScrollOnNextRender();
    }

    /** Whether the last {@link #composite} skipped the main-tree relayout (idle fast
     *  path engaged). The host's frame profiler reads this — the skip decision lives
     *  here now, so the shell can no longer compute it inline. */
    public boolean skippedLayout() {
        return skippedLayout;
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
            if (x >= surfaceWLogical - OFFSET_BTN_CORNER_W && y <= topInset + OFFSET_BTN_CORNER_H) {
                return false;
            }
            boolean wide = fullWidthApplied;
            if (wide && x <= BACK_BTN_CORNER_W && y <= topInset + OFFSET_BTN_CORNER_H) {
                return false;
            }
            float topY = topInset + L_LANDSCAPE_TOP;
            float bottomY = surfaceHeightLogical
                    - (wide ? L_FULLWIDTH_BOTTOM : L_LANDSCAPE_BOTTOM);
            return x >= (wide ? 0f : surfaceWLogical * 0.5f) && y >= topY && y <= bottomY;
        }
        // Portrait's counterpart to the landscape branch's coverOnlyCached check
        // above: a cover-only track/view has no lyric column here either, and
        // without this the tap-to-seek gesture claimed the whole band regardless,
        // swallowing taps on LyricOverlay.qml's centred cover (and the pointer-down
        // was read as a seek, moving playback position) before QML ever saw them.
        if (coverOnlyCached) return false;
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
        temperaPage.advanceFade(controller, System.nanoTime());
        view.dirtyQueue().flush();
        if (temperaVisible(controller)) {
            configureTempera(settings);
            if (temperaChrome == null) temperaChrome = view.findByObjectName("temperaChrome");
            TemperaCompositor.composite(canvas, renderer, temperaChrome, temperaPage, controller,
                    uiScale, fbW, fbH, settings == null || settings.resolvedDarkValue(),
                    () -> compositeLyrics(canvas, renderer, view, controller, settings, ctx, uiScale, fbW, fbH));
            skippedLayout = false;
        } else {
            temperaPage.releaseResources();
            compositeLyrics(canvas, renderer, view, controller, settings, ctx, uiScale, fbW, fbH);
        }
    }

    public boolean temperaVisible(PlayerController controller) {
        return temperaPage.wantsFrame(controller);
    }

    private void configureTempera(SettingsBridge settings) {
        int stretch = settings == null ? 50 : settings.temperaGlyphSettleStretch();
        float settle = Math.max(0f, Math.min(1f, stretch / 100f));
        boolean wholeLine = settings != null && settings.temperaWholeLine();
        boolean images = settings != null && settings.temperaImages();
        if (temperaTuning == null || temperaTuning.glyphSettleStretch != settle
                || temperaTuning.wholeLineLyrics != wholeLine || temperaTuning.layerImagesEnabled != images) {
            temperaTuning = new TemperaTuning();
            temperaTuning.glyphSettleStretch = settle;
            temperaTuning.wholeLineLyrics = wholeLine;
            temperaTuning.layerImagesEnabled = images;
        }
        temperaPage.configure(temperaTuning, settings == null ? 1f : settings.lyricFontSize() / 28f,
                settings != null && settings.lyricBgStatic());
    }

    private void compositeLyrics(Canvas canvas, Renderer renderer, QmlView view,
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
        boolean lyricPageStillOpen = controller != null
                && Boolean.TRUE.equals(controller.lyricsOpen.peek());
        // Draw the main scene immediately on the first closing frame. Looking at
        // progress alone would still call the page "covered" for that one frame,
        // so a fading/zooming page would reveal an undrawn black surface beneath it.
        boolean fullyCovered = slidePrev >= 0.999 && lyricPageStillOpen;
        if (!fullyCovered) {
            int sc = canvas.save();
            canvas.scale(uiScale, uiScale);
            boolean skipLayout = Property.changeVersion() == renderedVersion;
            skippedLayout = skipLayout;
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
            skippedLayout = false;
        } else {
            // Fully covered and nothing changed: the whole main tree is skipped.
            skippedLayout = true;
        }
        drawLyricOverlay(canvas, controller, settings, ctx, uiScale, fbW, fbH);
        double slideNow = controller != null ? controller.lyricSlide.peek() : 0.0;
        if (slideNow > 0.001 && lyricChrome != null) {
            int scC = canvas.save();
            canvas.scale(uiScale, uiScale);
            if (fullyCovered && renderer.pictureCacheEnabled()) {
                // renderSubtree() intentionally bypasses qml4j's picture cache. Once
                // the lyric page is stable and fully covers the main scene, render it
                // as a temporary root so its static direct children (title/buttons)
                // replay recorded SkPictures. Continuously-changing progress chrome
                // is detected by qml4j as a hot spot and falls back to direct drawing.
                // Keep renderSubtree during the slide transition: alternating cache
                // roots with the main scene there would release/re-record boundaries
                // every frame and create the native churn this path is meant to avoid.
                renderer.render(canvas, lyricChrome, true);
            } else {
                renderer.renderSubtree(canvas, lyricChrome, lw, lh);
            }
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
        // The lyric page is a spatial bottom sheet, independent of the navigation
        // page preset. Ease a little more slowly than the old 0.22 response while
        // retaining the same interruptible, frame-stable approach.
        lyricSlide += (target - lyricSlide) * 0.18f;
        if (Math.abs(target - lyricSlide) < 0.002f) lyricSlide = target;
        // Publish to QML so the LyricOverlay chrome fades in/out in lockstep. set()
        // no-ops on an unchanged value, so once settled-closed this stops bumping the
        // change version.
        controller.lyricSlide.set((double) lyricSlide);
        // Closed and settled: nothing more to draw. RETURN BEFORE touching
        // lyricProgress — it changes every frame while playing, and setting it would
        // bump the change version every frame, defeating the renderer's idle skip.
        if (lyricSlide <= 0.001f && !open) return;

        // Per-frame playback fraction for the QML wavy progress bar and the word
        // renderer. backend.position() is coarse, so extrapolate with wall-clock time.
        // Crucially, isLyricClockRunning() stays false until backend.onStarted, then
        // follows the backend's real playback state. UI `playing` is only user intent:
        // it can lead audio during loading and turns false before a pause fade finishes.
        long durMs = controller.durationMs.peek();
        boolean clockRunning = controller.isLyricClockRunning();
        long raw = controller.lyricClockPosition();
        long nowN = System.nanoTime();
        long playbackRevision = controller.playbackRevision();
        long clockSeekRevision = controller.seekRevision();
        boolean discontinuity = playbackRevision != lyPlaybackRevision
                || clockSeekRevision != lyClockSeekRevision;
        if (discontinuity || raw != lyRawLast || clockRunning != lyClockRunningLast) {
            lyRawLast = raw;
            lyClockRunningLast = clockRunning;
            lyBaseMs = raw;
            lyBaseNanos = nowN;
            lyPlaybackRevision = playbackRevision;
            lyClockSeekRevision = clockSeekRevision;
        }
        long predMs = clockRunning ? lyBaseMs + (nowN - lyBaseNanos) / 1_000_000L : lyBaseMs;
        if (durMs > 0 && predMs > durMs) predMs = durMs;
        double progress = durMs > 0 ? Math.min(1.0, predMs / (double) durMs) : 0.0;
        Double lastProgress = controller.lyricProgress.peek();
        if (lastProgress == null || Math.abs(progress - lastProgress) >= 0.0002) {
            controller.lyricProgress.set(progress);
        }

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
        // OR'd with the user's manual lyrics/cover toggle (LyricOverlay.qml's cover
        // button / tap-cover-to-return) — see PlayerController.coverModeManual.
        boolean coverOnly = Boolean.TRUE.equals(controller.lyricsCoverOnly.peek())
                || Boolean.TRUE.equals(controller.coverModeManual.peek());
        coverOnlyCached = coverOnly;
        // Full-width is a wide-window layout, and it has nothing to say about a
        // track with no lyrics — that view is the cover, which this mode removes.
        boolean fullWidth = landscape && !coverOnly
                && settings != null && settings.lyricFullWidth();
        float fullWidthTarget = fullWidth ? 1f : 0f;
        long fullWidthNow = System.nanoTime();
        if (lyricSlide < 0.99f || fullWidthNs == 0L) {
            // Don't run the layout change underneath the page's own open/close
            // slide: one movement at a time reads as deliberate, two as a glitch.
            fullWidthEase = fullWidthTarget;
            fullWidthApplied = fullWidth;
        } else {
            float dt = (fullWidthNow - fullWidthNs) / 1_000_000_000f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) {
                fullWidthEase += (fullWidthTarget - fullWidthEase)
                        * (1f - (float) Math.exp(-dt / FULLWIDTH_TAU));
            }
            if (Math.abs(fullWidthEase - fullWidthTarget) < 0.002f) {
                fullWidthEase = fullWidthTarget;
            }
            // Swap the layout at the bottom of the fade, where nothing is visible.
            fullWidthApplied = fullWidthEase >= 0.5f;
        }
        fullWidthNs = fullWidthNow;
        // The QML chrome rides the host's own eased value rather than a Behavior of
        // its own, so the bottom band and the column cannot drift out of step.
        controller.lyricFullWidth.set((double) fullWidthEase);
        // How visible the column is mid-swap: 1 settled either way, 0 at the
        // halfway point where the geometry changes under it.
        float fullWidthSwap = Math.abs(2f * fullWidthEase - 1f);
        // Where the column is DRAWN slides continuously between the two layouts
        // even though the layout itself snaps at the halfway point: the drawn
        // origin follows the eased value, and the offset from the snapped origin
        // is a plain canvas translation. Both halves therefore describe the same
        // absolute travel, so the column glides across the page instead of
        // dissolving in place, and the alpha dip only has to hide the re-wrap at
        // the instant the two halves meet.
        float classicLeftEdge = w * 0.5f;
        float drawnLeftEdge = classicLeftEdge * (1f - fullWidthEase);
        float columnSlide = drawnLeftEdge - (fullWidthApplied ? 0f : classicLeftEdge);
        // Unassigned lines centre in the full-width layout; a left-aligned line
        // would leave two thirds of a wide window empty. Duet lines keep their own
        // side either way — that split is the whole point of the channel.
        lyricRenderer.setMainAlign(fullWidthApplied ? 0.5f : 0f);

        float pad = 28f;
        float colLeft, colTopY, colW, colH;
        if (landscape) {
            colLeft = fullWidthApplied ? 0f : w * 0.5f;
            colTopY = topInset + L_LANDSCAPE_TOP;
            colW = (w - colLeft) - 2f * pad;
            colH = h - colTopY
                    - (fullWidthApplied ? L_FULLWIDTH_BOTTOM : L_LANDSCAPE_BOTTOM);
        } else {
            colLeft = 0f;
            colTopY = lyricTopY(topInset);
            colW = w - 2f * pad;
            colH = h - colTopY - L_TRANSPORT_H;
        }
        ensureLyricShaders(colTopY, colH);
        int sc = canvas.save();
        canvas.scale(uiScale, uiScale);

        // The page always slides up from the bottom over the main scene. Its QML
        // chrome uses this same smoothstep progress, so both layers stay locked.
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
        int bgStyle = settings != null
                ? settings.lyricBgStyle() : FluidBackground.STYLE_PIXI_RENDERER;
        if (backdropClipRect == null || backdropClipW != w || backdropClipH != h) {
            backdropClipRect = Rect.makeWH(w, h);
            backdropClipW = w;
            backdropClipH = h;
        }
        int bgClip = canvas.save();
        canvas.clipRect(backdropClipRect);
        fluidBg.render(canvas, ctx, uiScale, w, h, cover, lyCoverKey,
                System.nanoTime(), bgStatic, bgStyle);
        canvas.restoreToCount(bgClip);

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
            // While the column is sliding between layouts it reaches outside its
            // own snapped box, and the layer bounds would cut it off mid-travel.
            // Widen them to the page for those few frames only — a permanently
            // page-wide layer would cost the composite every frame.
            Rect colRect = fullWidthSwap < 0.999f
                    ? Rect.makeXYWH(0f, colTopY, w, colH) : lyColRect;
            // Reuse predMs (computed above for the QML progress bar), NOT
            // controller.position() directly — that's raw backend.position(), which
            // is 0 while paused/not-yet-resumed (e.g. right after a session restore)
            // even though positionMs correctly holds the restored position. Using it
            // here left the lyric column always rendering from the very start.
            long pos = predMs - LyricConfig.instance.offsetMs.getValue();
            long seekRevision = controller.seekRevision();
            if (lySeekRevision < 0L) {
                // First lyric-page render has no previous visual position to animate from.
                lySeekRevision = seekRevision;
            } else if (seekRevision != lySeekRevision) {
                lySeekRevision = seekRevision;
                lyricRenderer.easeSeekOnNextRender();
            }
            // Alpha-composite the whole column at lyricShow, and zoom it 0.95 -> 1 about
            // its own centre — matching the QML cover's zoom on the opposite side.
            // fullWidthSwap dips to 0 while the layout changes underneath. The
            // column is still RENDERED at alpha 0 rather than skipped, so the
            // renderer's incremental re-wrap runs during the invisible window and
            // the lyrics are already shaped when they fade back in.
            // sqrt, so the dip is spent almost entirely at the seam: the column
            // stays readable for most of the travel and only blinks where the
            // line wrapping actually changes.
            int alpha = Math.round(Math.max(0f, Math.min(1f,
                    lyricShow * (float) Math.sqrt(fullWidthSwap))) * 255f);
            lyLayerPaint.setAlpha(alpha);
            int lc = canvas.saveLayer(colRect, alpha < 255 ? lyLayerPaint : null);
            float s = 0.95f + 0.05f * lyricShow;
            float cx = colLeft + (w - colLeft) * 0.5f;
            float cy = colTopY + colH * 0.5f;
            int zc = canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(s, s);
            canvas.translate(-cx, -cy);
            // The layout swap travels instead of dissolving: this is the offset
            // between where the column is drawn and where its snapped layout puts
            // it, and it is 0 the moment the transition settles.
            if (columnSlide != 0f) canvas.translate(columnSlide, 0f);
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
        blurFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL);
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
        monotonic(bandStops, fTop - BAND_RAMP, fTop, fBot, fBot + BAND_RAMP);
        int o = 0xFFFFFFFF, t = 0x00FFFFFF;
        blurBandColors[0] = o; blurBandColors[1] = t;
        blurBandColors[2] = t; blurBandColors[3] = o;
        sharpBandColors[0] = t; sharpBandColors[1] = o;
        sharpBandColors[2] = o; sharpBandColors[3] = t;
        blurBandShader = Shader.makeLinearGradient(0f, topY, 0f, topY + colH,
                blurBandColors, bandStops);   // blur kept outside the plateau
        sharpBandShader = Shader.makeLinearGradient(0f, topY, 0f, topY + colH,
                sharpBandColors, bandStops);   // sharp kept across the plateau
    }

    // Clamp four gradient stops into [0,1] and force them strictly increasing (Skija
    // rejects equal/out-of-order positions).
    private static void monotonic(float[] s, float s0, float s1, float s2, float s3) {
        s[0] = s0; s[1] = s1; s[2] = s2; s[3] = s3;
        float eps = 1e-4f;
        for (int i = 0; i < s.length; i++) {
            if (s[i] < 0f) s[i] = 0f;
            else if (s[i] > 1f) s[i] = 1f;
        }
        for (int i = 1; i < s.length; i++) {
            if (s[i] <= s[i - 1]) s[i] = Math.min(1f, s[i - 1] + eps);
        }
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
        temperaChrome = null;
        lyricChrome = null;
        renderedVersion = -1;
    }

    /** Release context-bound caches before the host destroys its GPU context. */
    public void invalidateGpuContext() {
        temperaPage.releaseResources();
        fluidBg.invalidateGpuContext();
    }
}
