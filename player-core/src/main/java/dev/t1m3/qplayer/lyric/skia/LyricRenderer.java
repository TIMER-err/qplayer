package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedRow;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedText;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple Music-style lyric column. Lines are left-anchored at {@code leftX}
 * and wrap into a column of width {@code columnWidth}. The active line is
 * vertically centered in the visible area; surrounding lines flow above/
 * below in a dimmer style. Each visual row is shaped once with HarfBuzz and
 * cached as a TextBlob; a runtime shader applies independently timed syllable
 * lift without splitting that shaped row back into draw calls.
 *
 * <p>The layout/shaping pass is cached: each line is broken at syllable
 * boundaries when its width exceeds the column, and every wrapped sub-row
 * counts toward the line's total height. Scroll is driven by cumulative
 * {@code lineTops}, not a fixed per-line spacing, so wrapped lines push later
 * lines down without overlapping while playback frames reuse native blobs.
 */
public class LyricRenderer {

    /**
     * Row-height multiplier applied to the configured font size. 1.18×
     * is right at the typical sans-serif ascent+descent envelope — any
     * tighter and capital letters from adjacent rows start to touch.
     */
    private static final float ROW_HEIGHT_RATIO = 1.55f;
    /**
     * Tighter ratio for continuation sub-rows of a wrapped line. The
     * first sub-row still uses full {@link #ROW_HEIGHT_RATIO} so line-
     * to-line separation is unchanged; continuation rows use 1.0× so the
     * second half of a long lyric hugs the first.
     */
    private static final float WRAPPED_ROW_HEIGHT_RATIO = 1.0f;
    /**
     * Sub-line (translation / romaji) advance, relative to its own font size.
     */
    private static final float SUB_ROW_HEIGHT_RATIO = 1.1f;
    // Small extra gap above the translation/romaji block when the main lyric
    // wrapped. Kept well under one wrap-row height — too large (≈ a row) reads
    // as a blank line between the lyric and its translation.
    private static final float WRAP_SUB_GAP = 4f;

    /**
     * How many lines above/below the active line to actually draw.
     */
    private static final int VISIBLE_RADIUS = 16;
    /**
     * Minimum gap (ms) between two groups to insert an interlude dot row.
     * AMLL's reference uses 4000 ms, but that filters out most of the
     * short verse-to-verse pauses our user-tested songs actually have.
     * We use 2000 ms + proportional phase scaling in
     * {@link #renderInterludeDots} so short gaps still show dots with
     * fade-in/hold/exit windows scaled down to fit.
     */
    private static final long INTERLUDE_THRESHOLD_MS = 2000L;
    /**
     * AMLL trims the effective interlude end by 250 ms so the next
     * line has room to scroll in before it actually starts singing.
     */
    private static final long INTERLUDE_TRAIL_TRIM_MS = 250L;
    /**
     * Layout height (px) reserved for the inline interlude dot row. The
     * dots scroll into the centre position like a real lyric line.
     */
    private static final float INTERLUDE_DOTS_ROW_H = 40f;
    /**
     * Radius (px) of each interlude dot. Slot height + dot radius +
     * spacing all scale together to keep the dots visually balanced
     * inside their reserved row.
     */
    private static final float INTERLUDE_DOT_RADIUS = 6.8f;
    /**
     * Centre-to-centre horizontal spacing between dots.
     */
    private static final float INTERLUDE_DOT_SPACING = 27f;
    /**
     * BG line scale at rest (idle). 0 means "fully invisible until the
     * group activates" — BG grows out from the main line's bottom corner
     * on enter and collapses back to nothing on exit.
     */
    private static final float BG_SCALE_IDLE = 0f;
    /**
     * Skip drawing the BG layer below this activeK to avoid scale(0) artefacts.
     */
    private static final float BG_VISIBLE_THRESHOLD = 0.001f;
    /**
     * Pull the BG anchor this many pixels above the main+sub block bottom
     * so the BG content reads as "tucked into" the main line rather than
     * floating below it. With this set the BG's ascender region slightly
     * overlaps the trailing edge of the main's last sub-line, which fits
     * the "塞进 line 之间的缝隙" feedback.
     */
    private static final float BG_HUG_OFFSET_PX = 10f;
    /**
     * Vertical position of the active group's centre as a fraction of the
     * lyric column height. AMLL uses 0.35 by default (active sits above
     * geometric centre, so upcoming lines have more room below). 0.5
     * would centre exactly; 0.35 matches the reference player layout.
     */
    private static final float ALIGN_POSITION = 0.35f;
    /** Keep the first row of an unusually tall active group inside the lyric column. */
    private static final float ACTIVE_GROUP_TOP_MARGIN_PX = 12f;
    // Breathing room (fraction of the column) left beyond the first / last line at the
    // scroll extremes: a touch over half the column so the ends have a generous run-out
    // (and the auto-follow keeps centring lines naturally rather than pinning at edges).
    private static final float SCROLL_EDGE_PAD = 0.7f;

    // ---- Depth scaling (Apple Specs) -------------------------------------
    // Inactive lines render at deselectedTransform (0.97×); the active group
    // grows to emphasizingScaleRange's upper bound (1.14×). Interpolated by
    // activeK so the scale crossfades with the highlight rather than snapping.
    private static final float DESELECTED_SCALE = 0.97f;
    private static final float EMPHASIS_SCALE = 1.14f;

    // ---- Scroll spring tunings (ported from AMLL computeLinePosYSpringParams) --
    // Keep the established per-line cascade renderer, but use a gently
    // underdamped spring. ζ≈0.68 keeps one visible overshoot while damping
    // the repeated oscillation that becomes conspicuous across rapid line changes.
    // k=65 preserves the established response speed; only the settling is calmer.
    private static final double SCROLL_STIFFNESS_MIN = 65.0;
    private static final double SCROLL_STIFFNESS_MAX = 65.0;
    private static final double SCROLL_INTERVAL_MIN_MS = 100.0;
    private static final double SCROLL_INTERVAL_MAX_MS = 800.0;
    private static final double SCROLL_DAMPING_MULT = 1.365; // damping ≈ 11.0 @ k=65, ζ≈0.68
    // Steadier fixed spring while seeking or during an interlude.
    private static final double SCROLL_STIFFNESS_INTERLUDE = 55.0;
    private static final double SCROLL_DAMPING_INTERLUDE = 10.1;
    // Non-spring fallback uses the same current k/damping pair, without cascade.
    private static final double SCROLL_STIFFNESS_FIRM = 65.0;
    private static final double SCROLL_DAMPING_FIRM = 11.0;
    // The original rigid seek spring: slightly overdamped, so the whole column
    // glides to the new position without the newer fixed-duration tween or bounce.
    private static final double SEEK_SPRING_STIFFNESS = 180.0;
    private static final double SEEK_SPRING_DAMPING = 28.0;
    /** Duration of render-resume/unclassified-jump scrolling; quartic ease-out. */
    private static final long DISCONTINUITY_EASE_DURATION_NS = 500_000_000L;
    // Per-line scroll cascade (Apple Specs.lineDelay = 0.05). The active line and
    // everything ABOVE it move together (delay 0) — lockstep preserves their
    // spacing so the active line never rises into a still-stationary line above it
    // (the overlap) and never stalls before moving (the hitch). Only the lines
    // BELOW the active line trail, with a shrinking step, for a downward wave.
    private static final double LINE_DELAY_S = 0.05;
    private static final double LINE_DELAY_DECAY = 1.05;
    // A seek that moves the anchor more than this many lines snaps the whole column
    // instead of spring-scrolling: a long spring would animate the lines that
    // happen to overlap the old window while the freshly-revealed lines just appear,
    // a jarring half-animate/half-flash mix. Small jumps still spring smoothly.
    private static final int SNAP_JUMP_LINES = 6;

    private List<LyricLine> lines = Collections.emptyList();
    /** Whether every line in the current song has usable monotonic per-token
     *  timing to sweep/lift/glow with — real for per-syllable sources, or
     *  synthetic-but-evenly-spread for plain LRC when
     *  {@link LyricConfig#linearAnimForPlainLrc} is on. False only for plain
     *  LRC with that setting off, where lines light up as one block instead.
     *  Computed once at {@link #setLyrics}, not per-frame, since it also
     *  decided how the lines were tokenized. */
    private boolean animatablePerToken = false;
    /** True only when the source itself carries real per-word/per-syllable timing.
     * Synthetic timing generated for plain LRC must never enable word glow. */
    private boolean wordGlowSupported = false;
    /**
     * Lines bundled into "active groups". A solo line is its own group; a
     * pair (or chain) of overlapping DUET_LEFT / DUET_RIGHT lines becomes
     * a single group. Used so the active highlight + scroll target stick
     * to the whole duet block until the last voice finishes — without
     * this, the moment the second singer starts mid-phrase the first
     * singer's row would flip to "non-active" and freeze its sweep.
     */
    private List<LyricTimeline.Group> groups = Collections.emptyList();
    /**
     * Where a line with no assigned voice sits across the column: 0 left (the
     * classic layout, lyrics beside the cover), 0.5 centred (the full-width
     * layout, where a left-aligned line on a wide window would leave the right
     * two thirds empty). Duet lines ignore it and keep their own side — that
     * split is what the channel is for. A float rather than a flag so the host
     * can ease it while the layout animates between the two.
     */
    private float mainAlign;
    /**
     * Index into {@link #groups} per line. Sized to lines.size().
     */
    private int[] lineToGroup = new int[0];
    private int activeGroupIndex = -1;
    // Screen-space [top, bottom] spanned by the currently-lit lines in the last
    // render, accumulated from their actual drawn positions (so it tracks the spring
    // animation, BG pop-out and user scroll exactly). The edge-blur compositor reads
    // this to keep every lit line inside the sharp band, not just the anchor line.
    private float litBandTop, litBandBottom;
    private boolean litBandValid;
    // Time-smoothed copy of the lit band. A line joins the band when its activeK
    // crosses the 0.5 gate, which snaps the raw bottom down a whole line; easing the
    // exposed bounds toward that target turns the snap into a continuous crossfade of
    // the edge blur. Time-constant (seconds) sets how fast it catches up.
    private static final float LIT_BAND_TAU = 0.14f;
    private float litBandTopSmooth, litBandBottomSmooth;
    private final float[] litBandResult = new float[2];
    private boolean litBandSmoothInit;
    private long litBandSmoothNs;
    /**
     * Spring-driven vertical scroll. Stiffness/damping pair tuned to settle
     * a typical line jump in ~500ms with a barely-visible overshoot,
     * matching Apple Music's lyric flow. Duration-based easing would
     * restart on every line change; the spring carries velocity through.
     */
    // The global fallback uses the same k=65 / damping=11 tuning.
    private final SpringAnim scrollAnim = new SpringAnim(SCROLL_STIFFNESS_FIRM, SCROLL_DAMPING_FIRM);
    private final SpringAnim seekAnim = new SpringAnim(SEEK_SPRING_STIFFNESS, SEEK_SPRING_DAMPING);
    // Last spring-mode flag the scrollAnim was retuned for; -1 = not yet applied.
    private int lastSpringMode = -1;

    // Wrap layout cache. rowStarts (syllable break indices per line) and the
    // per-line heights depend only on (lines, font sizes, weight, column width,
    // sub-line visibility) — NOT on the play head — yet the layout pass recomputed
    // them, and reshaped+reallocated an int[] per line, every single frame. Cache
    // them and rebuild only when an input changes; per frame we recompute just the
    // play-head-dependent interlude slots and cumulative tops (plain arithmetic,
    // reused buffers). Mirrors the engine's "don't recompute invariants per frame".
    private int[][] cachedRowStarts;
    private float[] cachedLineHeights;
    // Wrapped sub-line rows per line (null when absent/hidden), cached with the layout
    // so the per-frame draw never re-splits or allocates.
    private ShapedText[][] cachedRomajiRows;
    private ShapedText[][] cachedTranslationRows;
    /** HarfBuzz output for every final visual row. TextBlob and caret positions are
     * immutable and reused until a layout input changes; playback never reshapes. */
    private ShapedRow[][] cachedShapedRows;
    /** Renderer-owned timing fragments used only when an oversized source token
     * must be split at a Unicode/grapheme boundary to make wrapping possible. */
    private List<List<Syllable>> cachedLayoutSyllables;
    // Per-syllable advances obtained from the full-line HarfBuzz result. These are
    // used only to choose wrap boundaries; actual drawing uses each row's TextBlob.
    private float[][] cachedSylWidths;
    private int lineLayoutRemaining;
    private static final int LAYOUT_BUDGET = 8;
    private static final int LAYOUT_FIRST_BUDGET = 33;
    private float[] lineTopsBuf = new float[0];
    private float[] effHeightsBuf = new float[0];
    private float[] interludeBuf = new float[0];
    // Per-line scroll springs (cascade). lineCurTop/lineVelTop track each line's
    // drawn top + velocity; only the visible window is integrated, off-window lines
    // snap to target. Active only when spring physics is on.
    private float[] lineCurTop = new float[0];
    private float[] lineVelTop = new float[0];
    // Per-line cascade delay (seconds) over the visible window. Reused buffer.
    private double[] cascadeDelayBuf = new double[0];
    private boolean lineSpringInit = false;
    private int prevVisStart = 0;
    private int prevVisEnd = 0;
    private int springAnchorPrev = Integer.MIN_VALUE;
    private int renderedAnchorPrev = Integer.MIN_VALUE;
    private int cascadeDir = 1; // +1 advancing (scroll up), -1 seeking back (scroll down)
    /** Discontinuous position changes move the whole column with a non-spring ease-out. */
    private boolean seekEaseNextRender = false;
    private boolean seekEaseActive = false;
    /** Explicit playback seeks use the original rigid global spring. */
    private boolean seekSpringNextRender = false;
    private boolean seekSpringActive = false;
    private long seekEaseStartNs = 0L;
    private float seekEaseFrom = 0f;
    private float seekEaseTo = 0f;
    private long springAnchorChangeNs = 0L;
    private long springLastNs = 0L;

    // Gesture scrolling is independent from Skia drawing and shared by every host.
    private final LyricScrollController userScroll = new LyricScrollController();

    /** Reusable paint for interlude dots — avoids per-frame allocation.
     */
    private final io.github.humbleui.skija.Paint dotPaint = new io.github.humbleui.skija.Paint();
    private final LyricTextShaper textShaper = new LyricTextShaper();
    private final LyricRowRenderer rowRenderer = new LyricRowRenderer();

    private List<LyricLine> layoutKeyLines;
    private int layoutKeyN;
    private int layoutKeyLyricSize;
    private float layoutKeySubSize;
    private float layoutKeyBgSubSize;
    private int layoutKeyColW = -1;
    private Fonts.Weight layoutKeyWeight;
    private float layoutKeyRowRatio = -1f;
    private boolean layoutKeyRomaji;
    private boolean layoutKeyTranslation;
    private boolean layoutKeyScale = true;
    private Font layoutKeyLyricFont;
    private Font layoutKeySubFont;
    private Font layoutKeyBgFont;
    private Font layoutKeyBgSubFont;

    private static Fonts.Weight toFontsWeight(LyricConfig.FontWeight w) {
        switch (w) {
            case THIN:
                return Fonts.Weight.THIN;
            case LIGHT:
                return Fonts.Weight.LIGHT;
            case MEDIUM:
                return Fonts.Weight.MEDIUM;
            default:
                return Fonts.Weight.REGULAR;
        }
    }

    /**
     * Renderer has at least one parsed lyric line — used by the view
     * layer to decide whether to draw the timeline or a "no lyrics" hint.
     */
    public boolean hasLines() {
        return !lines.isEmpty();
    }

    /** Release the renderer's reusable native Skia objects with its owning scene. */
    public void dispose() {
        clearLayoutCache();
        textShaper.close();
        rowRenderer.close();
        dotPaint.close();
    }

    /** Route the next playback-position change through the original rigid seek spring. */
    public void easeSeekOnNextRender() {
        cancelUserScrollForSeek();
        seekSpringNextRender = true;
    }

    /** Use the ordinary non-spring scroll transition after a render resume. */
    public void easeScrollOnNextRender() {
        cancelUserScrollForSeek();
        seekEaseNextRender = true;
    }

    /**
     * Immediately leave drag/fling/idle-hold mode before a lyric or progress seek.
     * Safe to call again when the seek revision reaches the compositor.
     */
    public void cancelUserScrollForSeek() {
        userScroll.cancel();
    }

    public void setLyrics(List<LyricLine> newLines) {
        clearLayoutCache();
        boolean linearPlainLrc = Boolean.TRUE.equals(LyricConfig.instance.linearAnimForPlainLrc.getValue());
        LyricTimeline.Prepared prepared = LyricTimeline.prepare(newLines, linearPlainLrc);
        this.lines = prepared.lines;
        this.animatablePerToken = prepared.animatablePerToken;
        this.wordGlowSupported = prepared.perSyllableSource;
        this.groups = prepared.groups;
        this.lineToGroup = prepared.lineToGroup;

        this.activeGroupIndex = -1;
        this.scrollAnim.setValue(0);
        this.seekAnim.setValue(0);
        this.lineSpringInit = false;
        this.seekEaseNextRender = false;
        this.seekEaseActive = false;
        this.seekSpringNextRender = false;
        this.seekSpringActive = false;
        this.springAnchorPrev = Integer.MIN_VALUE;
        this.renderedAnchorPrev = Integer.MIN_VALUE;
        userScroll.reset();
    }

    private void clearLayoutCache() {
        LyricTextShaper.closeRows(cachedShapedRows);
        rowRenderer.clearRasterCache();
        LyricTextShaper.closeTexts(cachedRomajiRows);
        LyricTextShaper.closeTexts(cachedTranslationRows);
        cachedShapedRows = null;
        cachedLayoutSyllables = null;
        cachedRomajiRows = null;
        cachedTranslationRows = null;
        cachedRowStarts = null;
        cachedSylWidths = null;
        cachedLineHeights = null;
        lineLayoutRemaining = 0;
        layoutKeyLines = null;
        layoutKeyLyricFont = null;
        layoutKeySubFont = null;
        layoutKeyBgFont = null;
        layoutKeyBgSubFont = null;
    }

    private int pivotLineIndex(long positionMs, int n) {
        int pivot = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            if (groups.get(gi).startMs <= positionMs) pivot = groups.get(gi).from;
            else break;
        }
        if (pivot < 0) pivot = 0;
        if (pivot >= n) pivot = Math.max(0, n - 1);
        return pivot;
    }

    private boolean shapeLineIfNeeded(int i, Font lyricFont, Font subFont,
                                      Font bgFont, Font bgSubFont,
                                      float rowHeightLyric, float rowHeightBg,
                                      float rowHeightLyricWrap, float rowHeightBgWrap,
                                      float subLineHeight, float bgSubLineHeight,
                                      float lineGap, float columnWidth, boolean scaleOn,
                                      boolean showRomaji, boolean showTranslation) {
        if (i < 0 || i >= layoutKeyN || cachedShapedRows[i] != null) return false;
        LyricLine line = lines.get(i);
        boolean isBg = LyricTimeline.isBackground(line.vocalChannel);
        Font font = isBg ? bgFont : lyricFont;
        Font lineSubFont = isBg ? bgSubFont : subFont;
        float rowHeight = isBg ? rowHeightBg : rowHeightLyric;
        float lineSubHeight = isBg ? bgSubLineHeight : subLineHeight;
        float wrapW = (isBg || !scaleOn) ? columnWidth : columnWidth / EMPHASIS_SCALE;

        List<Syllable> rowSyllables = textShaper.splitOversizedSyllables(
                line.syllables, font, wrapW);
        cachedLayoutSyllables.set(i, rowSyllables);
        float[] widths = textShaper.shapeSyllableAdvances(rowSyllables, font);
        cachedSylWidths[i] = widths;
        cachedRowStarts[i] = LyricTextLayout.wrapStarts(rowSyllables, widths, wrapW);
        int subRowCount = Math.max(1, cachedRowStarts[i].length - 1);
        cachedShapedRows[i] = new ShapedRow[subRowCount];
        for (int r = 0; r < subRowCount; r++) {
            int from = cachedRowStarts[i][r];
            int to = cachedRowStarts[i][r + 1];
            cachedShapedRows[i][r] = textShaper.shapeMainRow(rowSyllables, from, to, font);
        }

        float lh = rowHeight + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
        boolean hasSub = (line.romaji != null && showRomaji)
                || (line.translation != null && showTranslation);
        if (hasSub && subRowCount > 1) lh += WRAP_SUB_GAP;
        if (line.romaji != null && showRomaji) {
            cachedRomajiRows[i] = textShaper.shapeWrappedText(line.romaji, lineSubFont, wrapW);
            lh += lineSubHeight * cachedRomajiRows[i].length;
        }
        if (line.translation != null && showTranslation) {
            cachedTranslationRows[i] = textShaper.shapeWrappedText(
                    line.translation, lineSubFont, wrapW);
            lh += lineSubHeight * cachedTranslationRows[i].length;
        }
        cachedLineHeights[i] = lh + lineGap;
        lineLayoutRemaining--;
        return true;
    }

    /** Screen-space {top, bottom} of the currently-lit lines from the last
     *  {@link #render} call, eased over time so a line joining the lit set crossfades
     *  the edge blur instead of snapping it; null if nothing is lit (interlude /
     *  intro), letting the compositor fall back to its fixed plateau. Called once per
     *  frame by the compositor. */
    public float[] litBandBounds() {
        if (!litBandValid) { litBandSmoothInit = false; return null; }
        long now = System.nanoTime();
        if (!litBandSmoothInit) {
            litBandTopSmooth = litBandTop;
            litBandBottomSmooth = litBandBottom;
            litBandSmoothInit = true;
        } else {
            float dt = (now - litBandSmoothNs) / 1_000_000_000f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) {
                float a = 1f - (float) Math.exp(-dt / LIT_BAND_TAU);
                litBandTopSmooth += (litBandTop - litBandTopSmooth) * a;
                litBandBottomSmooth += (litBandBottom - litBandBottomSmooth) * a;
            }
        }
        litBandSmoothNs = now;
        litBandResult[0] = litBandTopSmooth;
        litBandResult[1] = litBandBottomSmooth;
        return litBandResult;
    }

    public void render(Canvas canvas, float leftX, float topY,
                       float columnWidth, float columnHeight, long positionMs) {
        // Snapshot mutable state so a concurrent setLyrics() mid-frame can't
        // replace lines/groups/lineToGroup underneath us (ArrayIndexOutOfBounds).
        final java.util.List<LyricLine> lines = this.lines;
        final java.util.List<LyricTimeline.Group> groups = this.groups;
        final int[] lineToGroup = this.lineToGroup;
        if (lines.isEmpty()) return;
        final boolean resumeEase = seekEaseNextRender;
        seekEaseNextRender = false;
        final boolean explicitSeek = seekSpringNextRender;
        seekSpringNextRender = false;

        LyricConfig cfg = LyricConfig.instance;
        int lyricFontSize = cfg.lyricFontSize.getValue();
        LyricFontSizing.Sizes fontSizes = LyricFontSizing.fromMain(lyricFontSize);
        float subFontSize = fontSizes.mainSubline;
        float bgFontSize = fontSizes.background;
        float bgSubFontSize = fontSizes.backgroundSubline;
        // lineGap forced to 0 — line-height (ROW_HEIGHT_RATIO * fontSize)
        // already carries enough vertical breathing room, and any extra
        // gap made the active line drift toward the column edge during
        // group transitions.
        float lineGap = 0f;
        Fonts.Weight weight = toFontsWeight(cfg.fontWeight.getValue());
        float rowHeightRatio = cfg.lineSpacing.getValue();

        // Spring physics toggle: retune the scroll spring only when the flag flips
        // (carries current value/velocity into the new tuning — no snap).
        boolean spring = Boolean.TRUE.equals(cfg.springPhysics.getValue());
        boolean scaleOn = Boolean.TRUE.equals(cfg.scaleEmphasis.getValue());
        boolean glowOn = Boolean.TRUE.equals(cfg.glow.getValue());
        boolean shadowOn = Boolean.TRUE.equals(cfg.dropShadow.getValue());
        int springMode = spring ? 1 : 0;
        if (springMode != lastSpringMode) {
            // scrollAnim only drives the non-spring fallback; per-line springs
            // handle the cascade in spring mode and are re-seeded next frame.
            scrollAnim.setParams(SCROLL_STIFFNESS_FIRM, SCROLL_DAMPING_FIRM);
            lineSpringInit = false;
            lastSpringMode = springMode;
        }

        Font lyricFont = Fonts.get(weight, lyricFontSize);
        Font subFont = Fonts.get(weight, subFontSize);
        Font bgFont = Fonts.get(weight, bgFontSize);
        Font bgSubFont = Fonts.get(weight, bgSubFontSize);

        // Animation-friendly font flags. Skia defaults snap text baselines
        // to integer pixels (isBaselineSnapped=true) and grid-fit glyphs
        // via hinting — so a smooth fractional translateY would still
        // render at integer y, giving the "jumps several pixels per
        // frame" feel the user reported. Disabling baseline snap + going
        // to subpixel positioning makes the lift continuous on the GPU.
        LyricTextShaper.configureForAnimation(lyricFont);
        LyricTextShaper.configureForAnimation(bgFont);
        // subFont is static text (no animation) but we still want it crisp
        // and consistent with the lyric font's anti-alias level.
        LyricTextShaper.configureForAnimation(subFont);
        LyricTextShaper.configureForAnimation(bgSubFont);

        float rowHeightLyric = lyricFontSize * rowHeightRatio;
        float rowHeightLyricWrap = lyricFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float rowHeightBg = bgFontSize * rowHeightRatio;
        float rowHeightBgWrap = bgFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float subLineHeight = subFontSize * SUB_ROW_HEIGHT_RATIO;
        float bgSubLineHeight = bgSubFontSize * SUB_ROW_HEIGHT_RATIO;

        boolean showRomaji = cfg.showRomaji.getValue();
        boolean showTranslation = cfg.showTranslation.getValue();

        // ---- Layout pass. Wrapping + per-line heights depend only on the inputs
        // below, NOT on the play head, so compute them once and cache. HarfBuzz is
        // intentionally confined to this rebuild; playback frames only read TextBlob
        // and caret arrays.
        int n = lines.size();
        int colW = Math.round(columnWidth);
        boolean layoutKeyMatches = cachedRowStarts != null
                && cachedShapedRows != null
                && cachedLayoutSyllables != null
                && layoutKeyLines == lines
                && layoutKeyN == n
                && layoutKeyLyricSize == lyricFontSize
                && layoutKeySubSize == subFontSize
                && layoutKeyBgSubSize == bgSubFontSize
                && layoutKeyColW == colW
                && layoutKeyWeight == weight
                && layoutKeyRowRatio == rowHeightRatio
                && layoutKeyRomaji == showRomaji
                && layoutKeyTranslation == showTranslation
                && layoutKeyScale == scaleOn
                && layoutKeyLyricFont == lyricFont
                && layoutKeySubFont == subFont
                && layoutKeyBgFont == bgFont
                && layoutKeyBgSubFont == bgSubFont;
        if (!layoutKeyMatches) {
            LyricTextShaper.closeRows(cachedShapedRows);
            rowRenderer.clearRasterCache();
            LyricTextShaper.closeTexts(cachedRomajiRows);
            LyricTextShaper.closeTexts(cachedTranslationRows);
            cachedRowStarts = new int[n][];
            cachedLineHeights = new float[n];
            cachedRomajiRows = new ShapedText[n][];
            cachedTranslationRows = new ShapedText[n][];
            cachedShapedRows = new ShapedRow[n][];
            cachedLayoutSyllables = new ArrayList<>(n);
            cachedSylWidths = new float[n][];
            for (int i = 0; i < n; i++) {
                cachedLayoutSyllables.add(null);
                cachedLineHeights[i] = rowHeightLyric + lineGap;
            }
            lineLayoutRemaining = n;
            layoutKeyLines = lines;
            layoutKeyN = n;
            layoutKeyLyricSize = lyricFontSize;
            layoutKeySubSize = subFontSize;
            layoutKeyBgSubSize = bgSubFontSize;
            layoutKeyColW = colW;
            layoutKeyWeight = weight;
            layoutKeyRowRatio = rowHeightRatio;
            layoutKeyRomaji = showRomaji;
            layoutKeyTranslation = showTranslation;
            layoutKeyScale = scaleOn;
            layoutKeyLyricFont = lyricFont;
            layoutKeySubFont = subFont;
            layoutKeyBgFont = bgFont;
            layoutKeyBgSubFont = bgSubFont;
        }
        if (lineLayoutRemaining > 0) {
            int budget = lineLayoutRemaining == n ? LAYOUT_FIRST_BUDGET : LAYOUT_BUDGET;
            int pivot = pivotLineIndex(positionMs, n);
            int shaped = 0;
            for (int d = 0; d < n && shaped < budget; d++) {
                int left = pivot - d;
                int right = pivot + d;
                if (d == 0) {
                    if (shapeLineIfNeeded(pivot, lyricFont, subFont, bgFont, bgSubFont,
                            rowHeightLyric, rowHeightBg, rowHeightLyricWrap, rowHeightBgWrap,
                            subLineHeight, bgSubLineHeight, lineGap, columnWidth, scaleOn,
                            showRomaji, showTranslation)) shaped++;
                    continue;
                }
                if (right < n && shapeLineIfNeeded(right, lyricFont, subFont, bgFont, bgSubFont,
                        rowHeightLyric, rowHeightBg, rowHeightLyricWrap, rowHeightBgWrap,
                        subLineHeight, bgSubLineHeight, lineGap, columnWidth, scaleOn,
                        showRomaji, showTranslation)) {
                    shaped++;
                    if (shaped >= budget) break;
                }
                if (left >= 0 && shapeLineIfNeeded(left, lyricFont, subFont, bgFont, bgSubFont,
                        rowHeightLyric, rowHeightBg, rowHeightLyricWrap, rowHeightBgWrap,
                        subLineHeight, bgSubLineHeight, lineGap, columnWidth, scaleOn,
                        showRomaji, showTranslation)) {
                    shaped++;
                }
            }
        }
        int[][] rowStarts = cachedRowStarts;
        float[] lineHeights = cachedLineHeights;

        // Font vertical metrics are invariant per (face,size) but Font.getMetrics()
        // allocates a fresh FontMetrics on every call — pull them once per frame
        // instead of per visible row.
        float lyricDescent = lyricFont.getMetrics().getDescent();
        float lyricAscent = lyricFont.getMetrics().getAscent();
        float bgDescent = bgFont.getMetrics().getDescent();
        float bgAscent = bgFont.getMetrics().getAscent();

        // Interlude row height — DYNAMIC, play-head driven. Grows 0 → full as the
        // play head nears the gap, holds, collapses as the next group starts; lines
        // below push down / spring back. So this and the cumulative tops below are
        // the only layout work that genuinely runs every frame. Buffers are reused.
        if (interludeBuf.length != groups.size()) interludeBuf = new float[groups.size()];
        float[] interludeBefore = interludeBuf;
        for (int gi = 0; gi < groups.size(); gi++) {
            interludeBefore[gi] = 0f;
            long prevEnd = (gi == 0) ? 0L : groups.get(gi - 1).endMs;
            long currStart = groups.get(gi).startMs;
            long effectiveEnd = currStart - INTERLUDE_TRAIL_TRIM_MS;
            long gap = effectiveEnd - prevEnd;
            if (gap < INTERLUDE_THRESHOLD_MS) continue;
            interludeBefore[gi] = LyricMotion.interludeSlot(
                    positionMs, prevEnd, effectiveEnd, INTERLUDE_DOTS_ROW_H);
        }

        // Line positions are STATIC w.r.t. the zoom: the depth scale is a purely
        // visual, centre-anchored transform that doesn't move the line's centre, so
        // it never feeds back into the scroll target. (An earlier version reflowed
        // line heights with the zoom, which made the target drift while the spring
        // chased it — the "bounce back".) Lines stack at their natural heights.

        // Per-frame effective heights: a BG line's slot collapses to nothing until its
        // group is FOCUSED, then opens to full height. Driven by the group's activeK
        // (focus), NOT the BG text's own pop — so the space is reserved the moment focus
        // lands (Apple-Music), and an idle / upcoming / already-sung line shows no empty
        // gap. The scroll compensation below turns each opening into the main line rising
        // rather than the lines beneath being shoved down.
        if (effHeightsBuf.length != n) effHeightsBuf = new float[n];
        float[] effHeights = effHeightsBuf;
        for (int i = 0; i < n; i++) {
            float h = lineHeights[i];
            if (LyricTimeline.isBackground(lines.get(i).vocalChannel)) {
                h *= LyricMotion.active(positionMs, groups.get(lineToGroup[i]));
            }
            effHeights[i] = h;
        }

        // Cumulative tops = stacked effective heights + the per-frame interlude slots.
        if (lineTopsBuf.length != n) lineTopsBuf = new float[n];
        float[] lineTops = lineTopsBuf;
        for (int i = 0; i < n; i++) {
            float prevBottom = i == 0 ? 0f : lineTops[i - 1] + effHeights[i - 1];
            // First line of a group with a preceding interlude gets the dot-row slot
            // inserted above it.
            int gi = lineToGroup[i];
            if (!groups.isEmpty() && gi >= 0 && gi < groups.size()
                    && groups.get(gi).from == i && interludeBefore[gi] > 0f) {
                prevBottom += interludeBefore[gi];
            }
            lineTops[i] = prevBottom;
        }

        // Switch as soon as the upcoming group enters its delayed visual fade-in window.
        // Scroll timing follows the first visible brightening of the next line, while
        // the previous line's sweep/fade continues independently through its own endMs.
        int anchorGroup = -1;
        int timelineGroupIndex = -1;
        for (int gi = 0; gi < groups.size(); gi++) {
            LyricTimeline.Group g = groups.get(gi);
            if (LyricMotion.fadeInStart(g) > positionMs) break;
            anchorGroup = gi;
            if (g.startMs <= positionMs) timelineGroupIndex = gi;
        }
        activeGroupIndex = anchorGroup;

        LyricTimeline.Group activeGroup = (activeGroupIndex >= 0 && activeGroupIndex < groups.size())
                ? groups.get(activeGroupIndex) : null;
        LyricTimeline.Group timelineGroup = (timelineGroupIndex >= 0 && timelineGroupIndex < groups.size())
                ? groups.get(timelineGroupIndex) : null;

        // Scroll target = the centre of the whole simultaneously-singing block. This
        // includes the active group (main + BG rows) and any immediately preceding
        // groups whose REAL time ranges overlap it. TTML duets are separate groups —
        // e.g. one agent can keep singing for several seconds after the other starts —
        // so centring only the newest group pushes the still-active upper singer out.
        // The active/animation anchor remains the newest group, preserving the early
        // handoff timing; only viewport placement uses the combined overlap block.
        //
        // EXCEPTION: when the play head is in an interlude (gap between
        // active group's end and next group's start ≥ INTERLUDE_THRESHOLD_MS),
        // the scroll target shifts to the reserved dot-row slot — the
        // dots scroll into the centre position like a real line, then
        // hand back to the next group's main centre as the interlude ends.
        float targetScroll = 0f;
        boolean inInterlude = false;
        int interludeNextGroup = -1;
        long interludeStartMs = 0L;  // gap start (0 for intro, prev.endMs otherwise)
        if (activeGroup != null) {
            int blockFromGroup = activeGroupIndex;
            while (blockFromGroup > 0) {
                LyricTimeline.Group firstIncluded = groups.get(blockFromGroup);
                LyricTimeline.Group previous = groups.get(blockFromGroup - 1);
                if (previous.endMs <= firstIncluded.startMs) break;
                // Do not let short pairwise overlaps form an indefinitely long
                // chain. Once the preceding group has completed its own visual
                // fade-out it no longer occupies viewport space, even if it used
                // to overlap the first group still included below it.
                if (LyricMotion.active(positionMs, previous) <= BG_VISIBLE_THRESHOLD) break;
                blockFromGroup--;
            }
            int blockFrom = groups.get(blockFromGroup).from;
            float blockTop = lineTops[blockFrom];
            // Finish at the newest active group's last row; groups after it have not
            // entered their fade/anchor window yet and must not affect placement.
            float groupBottom = lineTops[activeGroup.from] + effHeights[activeGroup.from];
            for (int j = activeGroup.from + 1; j < activeGroup.to; j++) {
                groupBottom = lineTops[j] + effHeights[j];
            }
            targetScroll = (blockTop + groupBottom) * 0.5f;
            // If the group is taller than the space above the 35% alignment line,
            // pure centring would still clip its first row. Bias the group downward
            // just enough to retain that row; lower rows may use the larger space below.
            float maxScrollKeepingTop = blockTop + columnHeight * ALIGN_POSITION
                    - ACTIVE_GROUP_TOP_MARGIN_PX;
            targetScroll = Math.min(targetScroll, maxScrollKeepingTop);
        }

        // Interlude detection covers THREE shapes:
        //   1. Intro: positionMs < groups[0].startMs, gap = [0, group[0].start)
        //   2. Between groups: activeGroup just finished, gap to next
        //   3. Outro: after last group — no dots (no "next" to anchor to)
        // End trimmed by INTERLUDE_TRAIL_TRIM_MS so the dots collapse a
        // moment before the next line sings.
        LyricTimeline.Group nextGroup = null;
        long gapStart = -1L;
        if (timelineGroup == null && !groups.isEmpty()
                && positionMs < groups.get(0).startMs) {
            // Intro
            nextGroup = groups.get(0);
            gapStart = 0L;
            interludeNextGroup = 0;
        } else if (timelineGroup != null && timelineGroupIndex + 1 < groups.size()
                && positionMs >= timelineGroup.endMs) {
            // Between groups
            nextGroup = groups.get(timelineGroupIndex + 1);
            gapStart = timelineGroup.endMs;
            interludeNextGroup = timelineGroupIndex + 1;
        }
        if (nextGroup != null) {
            long effectiveEnd = nextGroup.startMs - INTERLUDE_TRAIL_TRIM_MS;
            long gap = effectiveEnd - gapStart;
            if (positionMs < effectiveEnd && gap >= INTERLUDE_THRESHOLD_MS) {
                inInterlude = true;
                interludeStartMs = gapStart;
                // Once the next line enters its visual fade-in window, let the lyric
                // anchor move immediately but keep rendering the dots through their
                // own exit timeline. Before that handoff, dots remain the scroll target.
                if (activeGroupIndex != interludeNextGroup) {
                    float slotH = interludeBefore[interludeNextGroup];
                    float dotsTop = lineTops[nextGroup.from] - slotH;
                    targetScroll = dotsTop + slotH * 0.5f;
                }
            } else if (positionMs >= effectiveEnd && positionMs < nextGroup.startMs
                    && gap >= INTERLUDE_THRESHOLD_MS) {
                // EXIT TRAIL — dots no longer visible (we passed
                // effectiveEnd) but the next group hasn't started, so
                // the default activeGroup fallback would point back to
                // the previous group and yank scroll downward. Anchor
                // on the upcoming group now so scroll keeps moving
                // monotonically upward toward it.
                int nIdx = nextGroup.from;
                float nTop = lineTops[nIdx];
                float nBottom = nTop + lineHeights[nIdx];
                targetScroll = (nTop + nBottom) * 0.5f;
                interludeNextGroup = -1;
            } else {
                interludeNextGroup = -1;
            }
        }

        // Scroll bounds shared by the auto-follow AND manual scroll, so neither can run
        // a line past an edge into blank. When the lyrics are taller than the column,
        // pin the first line's top to the column top and the last line's bottom to the
        // column bottom; the active line still centres (ALIGN_POSITION) once there is
        // enough lyric above/below it. Shorter-than-column lyrics don't scroll.
        float scrollMinimum = targetScroll;
        float scrollMaximum = targetScroll;
        if (n > 0) {
            float contentEnd = lineTops[n - 1] + effHeights[n - 1];
            if (contentEnd > columnHeight) {
                float pad = columnHeight * SCROLL_EDGE_PAD;
                scrollMinimum = columnHeight * ALIGN_POSITION - pad;
                scrollMaximum = contentEnd - columnHeight * (1f - ALIGN_POSITION) + pad;
            }
        }

        float centerY = topY + columnHeight * ALIGN_POSITION;
        userScroll.setViewport(centerY, scrollMinimum, scrollMaximum);
        targetScroll = userScroll.clamp(targetScroll);

        int anchorIdx = activeGroup != null ? activeGroup.from : 0;
        // The draw window normally tracks the active line, but a manual scroll can pull
        // the view far from it — center the window on the on-screen scroll position then,
        // or the lines you scrolled to (being outside anchorIdx ± VISIBLE_RADIUS) are
        // never drawn and the page goes blank. The controller retains the previous offset.
        int windowCenter = userScroll.isActive()
                ? LyricScrollController.lineIndexAt(lineTops, n, userScroll.lastRenderedOffset())
                : anchorIdx;
        int start = Math.max(0, windowCenter - VISIBLE_RADIUS);
        int end = Math.min(n, windowCenter + VISIBLE_RADIUS + 1);

        // Per-line scroll springs (spring mode only). Each visible line chases its
        // resting top `centerY + lineTops[i] - targetScroll`; the global scrollAnim
        // above still drives the rigid fallback when spring is off.
        long nowNs = System.nanoTime();
        double springDt = 0.0;
        int previousRenderedAnchor = renderedAnchorPrev;
        boolean anchorChangedThisFrame = previousRenderedAnchor != Integer.MIN_VALUE
                && anchorIdx != previousRenderedAnchor;
        renderedAnchorPrev = anchorIdx;
        boolean largeAnchorJump = !explicitSeek && !resumeEase
                && !seekEaseActive && !seekSpringActive
                && previousRenderedAnchor != Integer.MIN_VALUE
                && Math.abs(anchorIdx - previousRenderedAnchor) > SNAP_JUMP_LINES;
        boolean startSpringSeek = explicitSeek;
        boolean startNonlinearEase = resumeEase || largeAnchorJump;
        float seekFromScroll = userScroll.lastRenderedOffset();
        if ((startSpringSeek || startNonlinearEase) && spring && !userScroll.isActive()
                && !seekEaseActive && !seekSpringActive
                && springAnchorPrev >= 0 && springAnchorPrev < n
                && springAnchorPrev < lineCurTop.length) {
            // Recover the currently drawn rigid offset from the old anchor line so
            // the seek tween begins exactly where the per-line cascade was visible.
            seekFromScroll = centerY + lineTops[springAnchorPrev] - lineCurTop[springAnchorPrev];
        }
        if (spring) {
            if (lineCurTop.length != n) {
                lineCurTop = new float[n];
                lineVelTop = new float[n];
                lineSpringInit = false;
            }
            if (anchorIdx != springAnchorPrev) {
                int previousAnchor = springAnchorPrev;
                // Direction the column is travelling: +1 advancing (content scrolls
                // up), -1 seeking back (content scrolls down). Drives which side of
                // the active line leads the cascade.
                if (previousAnchor != Integer.MIN_VALUE) {
                    cascadeDir = (anchorIdx > previousAnchor) ? 1 : -1;
                }
                springAnchorPrev = anchorIdx;
                springAnchorChangeNs = nowNs;
            }
            springDt = (nowNs - springLastNs) / 1_000_000_000.0;
            if (springDt > 0.05) springDt = 0.05;
            if (springDt < 0.0) springDt = 0.0;
            springLastNs = nowNs;
        }
        if (seekEaseActive && !startNonlinearEase && !startSpringSeek && anchorChangedThisFrame) {
            // Playback reached the next line before the seek tween finished. Hand
            // control back at the currently drawn positions; the per-line springs
            // continue from lineCurTop on this very frame instead of the tween later
            // snapping from its stale destination to the new anchor.
            seekEaseActive = false;
            scrollAnim.setValue(userScroll.lastRenderedOffset());
        }
        if (startSpringSeek) {
            // Match the old seek path: seed one rigid, near-critically-damped
            // global spring at the currently drawn offset and let it chase the
            // live target until settled. Per-line springs stay synchronized below.
            boolean wasSpringSeeking = seekSpringActive;
            seekEaseActive = false;
            seekSpringActive = true;
            // Progress-bar dragging produces several seek revisions. Preserve the
            // spring's velocity across those retargets, exactly as the old path did.
            if (!wasSpringSeeking) seekAnim.setValue(seekFromScroll);
            seekAnim.setTargetPosition(targetScroll);
            scrollAnim.setValue(seekFromScroll);
        }
        if (startNonlinearEase) {
            // Render resumes and unclassified large discontinuities move the column
            // rigidly with the decelerating tween. Explicit seeks use the old spring.
            seekSpringActive = false;
            seekEaseActive = true;
            seekEaseStartNs = nowNs;
            seekEaseFrom = seekFromScroll;
            seekEaseTo = targetScroll;
            scrollAnim.setValue(seekFromScroll);
        }
        double sinceAnchorChange = (nowNs - springAnchorChangeNs) / 1_000_000_000.0;

        // The global scroll value drives the rigid fallback when per-line spring
        // physics is off. Discontinuous transitions temporarily move the same rigid
        // column through either the old seek spring or the quartic resume tween.
        boolean rigidMode = !spring || seekEaseActive || seekSpringActive;

        // A big position jump (progress-bar seek) cancels manual scroll so the column
        // snaps back to following the play head via the normal ease.
        if (userScroll.isActive() && (startSpringSeek || startNonlinearEase
                || (userScroll.previousAnchor() != Integer.MIN_VALUE
                && Math.abs(anchorIdx - userScroll.previousAnchor()) > SNAP_JUMP_LINES))) {
            cancelUserScrollForSeek();
            scrollAnim.setValue(userScroll.lastRenderedOffset());
        }
        userScroll.setPreviousAnchor(anchorIdx);

        float scrollY;
        if (seekSpringActive) {
            scrollY = (float) seekAnim.animate(targetScroll);
            if (seekAnim.arrived()) {
                scrollY = targetScroll;
                seekSpringActive = false;
            }
            // Keep fallback/manual-return state warm for a seamless handoff.
            scrollAnim.setValue(scrollY);
        } else if (seekEaseActive) {
            float t = Math.min(1f, (nowNs - seekEaseStartNs)
                    / (float) DISCONTINUITY_EASE_DURATION_NS);
            float inv = 1f - t;
            // Quartic ease-out drops below the previous cubic curve's velocity after
            // the first quarter, leaving a longer, calmer approach to the destination.
            float inv2 = inv * inv;
            float eased = 1f - inv2 * inv2;
            scrollY = seekEaseFrom + (seekEaseTo - seekEaseFrom) * eased;
            if (t >= 1f) {
                // Do not snap to a target that drifted while the tween was running
                // (e.g. a BG row expanding). Finish at the tween's own destination;
                // normal line following picks up any tiny residual continuously.
                scrollY = seekEaseTo;
                seekEaseActive = false;
            }
            // Keep the unused fallback spring synchronized so handing control back
            // after the tween cannot reintroduce old velocity.
            scrollAnim.setValue(scrollY);
        } else if (userScroll.isActive()) {
            // Hand-controlled: move the whole column rigidly to the user's offset (or
            // the scrollAnim ease while returning); the highlight keeps tracking pos.
            rigidMode = true;
            scrollY = userScroll.step(targetScroll, nowNs, anchorIdx, scrollAnim);
        } else {
            scrollY = (float) scrollAnim.animate(targetScroll);
        }
        userScroll.setLastRenderedOffset(scrollY);

        // Dynamic scroll-spring tuning (AMLL): steady during an interlude, else
        // stiffer the faster lines are arriving (shorter gap to the previous line).
        double scrollStiffness;
        double scrollDamping;
        if (inInterlude) {
            scrollStiffness = SCROLL_STIFFNESS_INTERLUDE;
            scrollDamping = SCROLL_DAMPING_INTERLUDE;
        } else {
            LyricTimeline.Group prevG = (activeGroupIndex > 0) ? groups.get(activeGroupIndex - 1) : null;
            double interval = (activeGroup != null && prevG != null)
                    ? (activeGroup.startMs - prevG.startMs) : SCROLL_INTERVAL_MAX_MS;
            double ci = Math.max(SCROLL_INTERVAL_MIN_MS, Math.min(SCROLL_INTERVAL_MAX_MS, interval));
            double ratio = Math.pow(1.0 - (ci - SCROLL_INTERVAL_MIN_MS)
                    / (SCROLL_INTERVAL_MAX_MS - SCROLL_INTERVAL_MIN_MS), 0.2);
            scrollStiffness = SCROLL_STIFFNESS_MIN + ratio * (SCROLL_STIFFNESS_MAX - SCROLL_STIFFNESS_MIN);
            scrollDamping = Math.sqrt(scrollStiffness) * SCROLL_DAMPING_MULT;
        }

        // Per-line cascade delays. The active line plus everything on the LEADING
        // side (the side the column is moving toward) move in lockstep — spacing is
        // preserved so the active line never springs into a still-stationary
        // neighbour. Only the TRAILING side cascades, with a shrinking step, for a
        // wave. Leading side flips with travel direction so seeking either way is
        // overlap-free: advancing (scroll up) → top leads, lines below trail;
        // seeking back (scroll down) → bottom leads, lines above trail.
        if (cascadeDelayBuf.length < n) cascadeDelayBuf = new double[n];
        for (int i = start; i < end; i++) cascadeDelayBuf[i] = 0.0;
        double cascDelay = 0.0;
        double cascStep = LINE_DELAY_S;
        if (cascadeDir >= 0) {
            for (int i = Math.max(start, anchorIdx + 1); i < end; i++) {
                cascDelay += cascStep;
                cascStep /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = cascDelay;
            }
        } else {
            for (int i = Math.min(end - 1, anchorIdx - 1); i >= start; i--) {
                cascDelay += cascStep;
                cascStep /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = cascDelay;
            }
        }

        litBandValid = false;
        for (int i = start; i < end; i++) {
            LyricLine line = lines.get(i);
            LyricTimeline.Group myGroup = groups.get(lineToGroup[i]);

            float activeK = LyricMotion.active(positionMs, myGroup);

            LyricLine.VocalChannel ch = line.vocalChannel;
            boolean isBg = LyricTimeline.isBackground(ch);
            float align = alignOf(ch);

            Font font = isBg ? bgFont : lyricFont;
            float rowHeight = isBg ? rowHeightBg : rowHeightLyric;
            float lineSubHeight = isBg ? bgSubLineHeight : subLineHeight;
            float descent = isBg ? bgDescent : lyricDescent;
            float ascent = isBg ? bgAscent : lyricAscent;
            // baseAlpha interpolates idle ↔ active so the line's overall
            // brightness rises/falls with the group transition rather
            // than snapping at the boundary.
            float idleBase = isBg ? 0.18f : 0.22f;
            float activeBase = isBg ? 0.70f : 1f;
            float baseAlpha = idleBase + (activeBase - idleBase) * activeK;

            // Top of this line in screen space. Per-line spring mode: each line
            // springs to its resting top with a per-line stagger (cascade). Rigid
            // mode (spring off, or a big seek easing over): the single global
            // scrollAnim offset — and we keep lineCurTop synced to it so the per-line
            // spring resumes seamlessly from these positions when the ease ends.
            float restTop = centerY + lineTops[i] - targetScroll;
            float lineYTop;
            if (rigidMode) {
                lineYTop = centerY + lineTops[i] - scrollY;
                if (spring) {
                    lineCurTop[i] = lineYTop;
                    lineVelTop[i] = 0f;
                }
            } else {
                boolean wasVisible = i >= prevVisStart && i < prevVisEnd;
                if (!lineSpringInit || !wasVisible) {
                    lineCurTop[i] = restTop;
                    lineVelTop[i] = 0f;
                } else {
                    if (sinceAnchorChange >= cascadeDelayBuf[i] && springDt > 0.0) {
                        stepLineSpring(i, restTop, springDt, scrollStiffness, scrollDamping);
                    }
                }
                lineYTop = lineCurTop[i];
            }

            // Viewport cull: VISIBLE_RADIUS keeps far lines in the spring window
            // (stepped just above), but only a handful fit in the column — skip the
            // draw work (saveLayer/sweep/glow/drawString) for lines fully outside it.
            // Margin covers the emphasis zoom + glow bleed.
            if (lineYTop + lineHeights[i] < topY - 32f || lineYTop > topY + columnHeight + 32f) {
                continue;
            }

            int[] starts = rowStarts[i];
            if (starts == null || cachedShapedRows[i] == null) continue;
            int subRowCount = Math.max(1, starts.length - 1);

            // The block the drawn rows actually occupy, so a sub-line (translation
            // / romaji) lines up under its own text however the line is aligned.
            float maxRowWidth = 0f;
            float blockLeftX = Float.MAX_VALUE;
            float blockRightX = leftX;

            // BG lines now occupy their own pre-reserved slot in the
            // layout (lineHeights[i] = real height). Anchor stays at the
            // slot top — no longer overlaps the main line. The scale
            // animation pops the BG content out of its own slot, but the
            // slot itself is always there so neighbouring lines never
            // shift when the BG activates / collapses.
            // Every line gets a scale transform. BG lines keep their pop-in/out
            // scale (anchored at their slot top). Main lines use depth scaling —
            // deselected 0.98× growing to the active group's 1.14× emphasis — driven
            // by the scroll spring's progress so the zoom lands exactly as the line
            // settles, and anchored at the line's CENTRE so growing it never shifts
            // its centre (that downward push at arrival was the "bounce").
            float anchorX = leftX + columnWidth * align;
            float scale;
            float anchorY;
            if (isBg) {
                float bgScaleK = LyricMotion.backgroundScale(positionMs, myGroup);
                if (bgScaleK < BG_VISIBLE_THRESHOLD) continue;
                scale = BG_SCALE_IDLE + (1f - BG_SCALE_IDLE) * bgScaleK;
                anchorY = lineYTop;
            } else if (scaleOn) {
                float mainTextH = rowHeight + (subRowCount - 1) * rowHeightLyricWrap;
                float lineCenter = lineYTop + mainTextH * 0.5f;
                float emph;
                if (spring) {
                    // Proximity to the fixed centre line (where the active line
                    // settles), NOT to the line's own target. The spring position is
                    // continuous, so this never jumps when the target does — the
                    // outgoing line shrinks smoothly as it springs away, the incoming
                    // one grows as it springs in. No flash, no bounce.
                    float ref = Math.max(40f, lineHeights[i]);
                    float prog = 1f - Math.min(1f, Math.abs(lineCenter - centerY) / ref);
                    emph = activeK * prog;
                } else {
                    emph = activeK;
                }
                scale = DESELECTED_SCALE + (EMPHASIS_SCALE - DESELECTED_SCALE) * emph;
                anchorY = lineCenter;
            } else {
                scale = 1f;
                anchorY = lineYTop;
            }

            // Grow the lit band to this line's drawn extent when it's clearly active,
            // so a multi-line group (main + BG, or overlapping v1/v2) keeps ALL its
            // lit lines in the edge-blur sharp band — not just the anchor line.
            if (activeK >= 0.5f) {
                float lt = lineYTop, lb = lineYTop + lineHeights[i];
                if (!litBandValid) { litBandTop = lt; litBandBottom = lb; litBandValid = true; }
                else {
                    if (lt < litBandTop) litBandTop = lt;
                    if (lb > litBandBottom) litBandBottom = lb;
                }
            }

            canvas.save();
            canvas.translate(anchorX, anchorY);
            canvas.scale(scale, scale);
            canvas.translate(-anchorX, -anchorY);

            for (int r = 0; r < subRowCount; r++) {
                ShapedRow shapedRow = cachedShapedRows[i][r];
                // Drop leading whitespace on every row: a continuation row inherits the
                // space the source kept at the wrap point, and the first row can carry a
                // leading space from the source line itself (common in JP lyrics) — both
                // would sit the text one space in from the column's left edge.
                float lead = shapedRow.leadingWidth;
                float visWidth = shapedRow.width - lead;
                float rowX = Math.max(leftX, leftX + (columnWidth - visWidth) * align);

                float wrapRowH = (r == 0) ? rowHeight : (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                float rowBaselineY = lineYTop + rowHeight + r * wrapRowH - descent - 4f;
                rowRenderer.drawRow(cachedLayoutSyllables.get(i), shapedRow,
                        rowX - lead, rowBaselineY,
                        ascent, descent, positionMs, baseAlpha, activeK, animatablePerToken, spring,
                        glowOn, shadowOn, wordGlowSupported);

                if (visWidth > maxRowWidth) maxRowWidth = visWidth;
                if (rowX < blockLeftX) blockLeftX = rowX;
                if (rowX + visWidth > blockRightX) blockRightX = rowX + visWidth;
            }

            // Sub-lines sit inside the block the rows above actually occupy and
            // follow the same alignment. Y must match the wrapped block's real
            // stacked height (first row full, extra rows at the wrap height) —
            // using subRowCount*rowHeight overshoots and pushes translation /
            // romaji too far below a multi-row line.
            if (blockLeftX > blockRightX) blockLeftX = leftX; // nothing drawn
            float subY = lineYTop + rowHeight
                    + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap) + 4f
                    + (subRowCount > 1 ? WRAP_SUB_GAP : 0f);
            subY = drawSubline(blockLeftX, lineSubHeight, showRomaji, i, align,
                    baseAlpha, blockRightX, subY, cachedRomajiRows, shadowOn);
            subY = drawSubline(blockLeftX, lineSubHeight, showTranslation, i, align,
                    baseAlpha, blockRightX, subY, cachedTranslationRows, shadowOn);

            canvas.restore();
        }

        if (spring) {
            prevVisStart = start;
            prevVisEnd = end;
            lineSpringInit = true;
        }

        // ---- Interlude dots (AMLL `InterludeDots`, inline in layout) ----
        // The dot row already has its reserved INTERLUDE_DOTS_ROW_H slot
        // in lineTops via interludeBefore[]. When in an interlude, scroll
        // has shifted that slot to the centre — we just draw the dots in
        // it. Math is a 1:1 port of amll-dev/applemusic-like-lyrics/.../
        // interlude-dots.ts.
        if (inInterlude && interludeNextGroup >= 0) {
            LyricTimeline.Group interludeNext = groups.get(interludeNextGroup);
            // Use the trimmed window — same one the slot computeInterludeSlot
            // ramps against — so the dots' internal timeline matches the
            // slot's open/close timeline exactly. interludeStartMs is 0
            // for the intro, or prevGroup.endMs for between-group gaps.
            long effectiveEnd = interludeNext.startMs - INTERLUDE_TRAIL_TRIM_MS;
            long interludeDur = effectiveEnd - interludeStartMs;
            float slotH = interludeBefore[interludeNextGroup];
            if (slotH > 4f) {
                // Top of the upcoming line's reserved dot slot, spring-aware so the
                // dots ride the same cascade as the lines.
                int nf = interludeNext.from;
                float nextTop = (spring && nf >= start && nf < end)
                        ? lineCurTop[nf]
                        : centerY + lineTops[nf]
                        - (spring && !userScroll.isActive() ? targetScroll : scrollY);
                // Centre the dots between the two LINES OF TEXT, not the slot edges.
                // The slot top (nextTop - slotH) sits at the previous line's bottom,
                // but the next line's text starts nextTextOffset below its slot top
                // (line-height leaves that gap above the glyphs). Without accounting
                // for it the dots hug the previous line and drift with line spacing.
                float nextTextOffset = rowHeightLyric + lyricAscent - lyricDescent - 4f;
                float prevTextBottom = nextTop - slotH;
                float nextTextTop = nextTop + nextTextOffset;
                float anchorY = (prevTextBottom + nextTextTop) * 0.5f - INTERLUDE_DOT_RADIUS;
                // Place the dots where the upcoming line will be: left for a
                // left-duet, right for a right-channel line, and with the main
                // lines wherever those are aligned.
                LyricLine.VocalChannel nextCh = lines.get(interludeNext.from).vocalChannel;
                float dotsAlign = alignOf(nextCh);
                float dotsWidth = 2f * INTERLUDE_DOT_RADIUS + 2f * INTERLUDE_DOT_SPACING;
                float dotsX = Math.max(leftX, leftX + (columnWidth - dotsWidth) * dotsAlign);
                renderInterludeDots(canvas, dotsX, anchorY,
                        positionMs - interludeStartMs, interludeDur);
            }
        }
    }

    /** @see #mainAlign */
    public void setMainAlign(float align) {
        mainAlign = Math.max(0f, Math.min(1f, align));
    }

    /** Horizontal alignment for a line, 0 left / 0.5 centred / 1 right. */
    private float alignOf(LyricLine.VocalChannel ch) {
        if (ch == LyricLine.VocalChannel.DUET_RIGHT
                || ch == LyricLine.VocalChannel.BACKGROUND_RIGHT) return 1f;
        if (ch == LyricLine.VocalChannel.DUET_LEFT
                || ch == LyricLine.VocalChannel.BACKGROUND_LEFT) return 0f;
        return mainAlign;
    }

    private float drawSubline(float blockLeftX, float subLineHeight,
                              boolean showRomaji, int i, float align,
                              float baseAlpha, float blockRightX, float subY,
                              ShapedText[][] cachedRomajiRows, boolean shadowOn) {
        ShapedText[] romajiRows = cachedRomajiRows[i];
        if (romajiRows != null && showRomaji) {
            for (ShapedText romajiRow : romajiRows) {
                rowRenderer.drawSubLine(romajiRow, blockLeftX, blockRightX, subY,
                        baseAlpha * 0.75f, align, shadowOn);
                subY += subLineHeight;
            }
        }
        return subY;
    }

    // ===== Interlude dots (AMLL port) =====

    /**
     * Three breathing dots shown during interludes. Phase thresholds
     * scale with the actual gap duration: AMLL's fixed 500/1000/2000/
     * 750/375 ms windows assume gaps in the 10-30 s range, but a 2.5 s
     * verse pause needs them compressed proportionally or the dots
     * spend the whole gap fading in / out with no stable middle. We
     * pick {@code min(AMLL_default, gap × fraction)} for every phase
     * — long gaps land on AMLL defaults exactly, short gaps get a
     * fade-in/hold/exit distribution that fits.
     */
    private void renderInterludeDots(Canvas canvas, float leftX, float anchorY,
                                     long currentDuration, long interludeDuration) {
        if (currentDuration < 0L || currentDuration > interludeDuration) return;

        // No "invisible delay" window at the start — AMLL's 500 ms blank
        // before fade-in was the main visible-perceived latency the user
        // hit. Combined with the 300 ms slot lead and the spring scroll
        // catching up, the gap could be nearly a second old before any
        // dot appeared. Start fade-in at 0 so the dots arrive in sync
        // with the slot expanding.
        long fadeInStartMs = 0L;
        long fadeInEndMs = Math.min(600L, (long) (interludeDuration * 0.20));
        long scaleRampMs = Math.min(1500L, (long) (interludeDuration * 0.35));
        long exitScaleMs = Math.min(750L, (long) (interludeDuration * 0.20));
        long exitOpacityMs = Math.min(375L, (long) (interludeDuration * 0.10));
        if (fadeInEndMs <= fadeInStartMs) fadeInEndMs = fadeInStartMs + 1L;

        // Breath cycles: divide the whole interlude into ~1500 ms cycles
        // — each sin oscillation is one breath.
        double breatheDur = interludeDuration
                / Math.ceil(interludeDuration / 1500.0);
        double scale = 1.0;
        double globalOpacity = 1.0;

        // Sin breath modulation: ±5% scale around 1.0 (1/20 amplitude).
        scale *= Math.sin(1.5 * Math.PI
                - (currentDuration / breatheDur) * 2.0) / 20.0 + 1.0;

        // Entry ramp — easeOutExpo over scaleRampMs.
        if (currentDuration < scaleRampMs) {
            scale *= easeOutExpoD(currentDuration / (double) scaleRampMs);
        }

        // Global opacity fade-in window: 0-fadeInStart invisible,
        // fadeInStart..fadeInEnd ramps to 1.
        if (currentDuration < fadeInStartMs) {
            globalOpacity = 0.0;
        } else if (currentDuration < fadeInEndMs) {
            globalOpacity *= (currentDuration - fadeInStartMs)
                    / (double) (fadeInEndMs - fadeInStartMs);
        }

        // Exit: scale collapse via easeInOutBack in final exitScaleMs.
        long remaining = interludeDuration - currentDuration;
        if (remaining < exitScaleMs) {
            scale *= 1.0 - easeInOutBackD(
                    (exitScaleMs - remaining) / (double) exitScaleMs / 2.0);
        }
        // Opacity linear fade in final exitOpacityMs.
        if (remaining < exitOpacityMs) {
            globalOpacity *= Math.max(0.0,
                    Math.min(1.0, remaining / (double) exitOpacityMs));
        }

        // AMLL post-clamp: scale to 70 % of computed value.
        long dotsDur = Math.max(1L, interludeDuration - exitScaleMs);
        scale = Math.max(0.0, scale) * 0.7;
        if (scale < 0.01) return;

        // Per-dot staggered opacity: each dot follows the same ramp
        // shifted by dotsDur/3, clamped to [0.25, 1].
        double op0 = clampD(0.25, (currentDuration * 3.0 / dotsDur) * 0.75, 1.0);
        double op1 = clampD(0.25,
                ((currentDuration - dotsDur / 3.0) * 3.0 / dotsDur) * 0.75, 1.0);
        double op2 = clampD(0.25,
                ((currentDuration - dotsDur * 2.0 / 3.0) * 3.0 / dotsDur) * 0.75, 1.0);

        float dotRadius = INTERLUDE_DOT_RADIUS;
        float spacing = INTERLUDE_DOT_SPACING;
        float cx0 = leftX + dotRadius;
        float cy = anchorY + dotRadius;

        canvas.save();
        canvas.translate(cx0 + spacing, cy);
        canvas.scale((float) scale, (float) scale);
        canvas.translate(-(cx0 + spacing), -cy);
        try {
            double[] ops = {globalOpacity * op0, globalOpacity * op1, globalOpacity * op2};
            for (int i = 0; i < 3; i++) {
                Paint p = dotPaint;
                p.setColor(0xFFFFFFFF);
                float a = (float) Math.max(0.0, Math.min(1.0, ops[i]));
                p.setAlphaf(a);
                p.setAntiAlias(true);
                canvas.drawCircle(cx0 + i * spacing, cy, dotRadius, p);
            }
        } finally {
            canvas.restore();
        }
    }

    private static double easeInOutBackD(double x) {
        double c1 = 1.70158;
        double c2 = c1 * 1.525;
        return x < 0.5
                ? (Math.pow(2 * x, 2) * ((c2 + 1) * 2 * x - c2)) / 2
                : (Math.pow(2 * x - 2, 2) * ((c2 + 1) * (x * 2 - 2) + c2) + 2) / 2;
    }

    private static double easeOutExpoD(double x) {
        if (x >= 1.0) return 1.0;
        return 1.0 - Math.pow(2, -10.0 * x);
    }

    private static double clampD(double lo, double v, double hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }

    // ---- Manual scroll API (host input delegates to the reusable controller) ----

    public void scrollDown(float y) {
        userScroll.pointerDown(y);
    }

    public void scrollMove(float y) {
        userScroll.pointerMove(y);
    }

    public void scrollUp() {
        userScroll.pointerUp();
    }

    public void scrollCancel() {
        userScroll.pointerUp();
    }

    public void scrollByWheel(float notches) {
        userScroll.wheel(notches);
    }

    public long timeAtScreenY(float screenY) {
        return userScroll.timeAtScreenY(screenY, lines, lineTopsBuf, cachedLineHeights);
    }

    private void stepLineSpring(int i, float target, double dt, double stiffness, double damping) {
        double value = lineCurTop[i];
        double vel = lineVelTop[i];
        int steps = 1 + (int) (dt / 0.008);
        double sub = dt / steps;
        for (int s = 0; s < steps; s++) {
            double a = -stiffness * (value - target) - damping * vel;
            vel += a * sub;
            value += vel * sub;
        }
        if (Math.abs(vel) < 0.01 && Math.abs(value - target) < 0.05) {
            value = target;
            vel = 0.0;
        }
        lineCurTop[i] = (float) value;
        lineVelTop[i] = (float) vel;
    }

    /**
     * GLSL-style smoothstep: 0 below {@code a}, 1 above {@code b}, smooth in between.
     */
}
