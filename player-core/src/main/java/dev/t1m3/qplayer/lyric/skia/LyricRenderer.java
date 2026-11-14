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
     * Minimum interlude (ms) to show the dot row: from the moment the previous
     * group releases focus to the moment the next one takes it. 1700 ms here is a
     * raw 2250 ms gap between the lines. AMLL's reference uses 4000 ms, which
     * filters out most short verse-to-verse pauses; {@link #renderInterludeDots}
     * scales its phases so short gaps still get fade-in/hold/exit windows.
     */
    private static final long INTERLUDE_THRESHOLD_MS = 1700L;
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

    // ---- Focus scaling ----------------------------------------------------
    // Inactive lines render at DESELECTED_SCALE; the active group grows to
    // EMPHASIS_SCALE. Interpolated by the group's own timed activeK, so a line keeps
    // its emphasis for as long as it is sung, however early the column starts
    // scrolling toward the next one.
    private static final float DESELECTED_SCALE = 0.97f;
    private static final float EMPHASIS_SCALE = 1.14f;
    /** Space kept under the newest focused line when several lines are focused. */
    private static final float FOCUS_BOTTOM_MARGIN_PX = 24f;

    private List<LyricLine> lines = Collections.emptyList();
    /** Whether every line in the current song has usable monotonic per-token
     *  timing to sweep/lift with — real for per-syllable sources, or
     *  synthetic-but-evenly-spread for plain LRC when
     *  {@link LyricConfig#linearAnimForPlainLrc} is on. False only for plain
     *  LRC with that setting off, where lines light up as one block instead.
     *  Computed once at {@link #setLyrics}, not per-frame, since it also
     *  decided how the lines were tokenized. */
    private boolean animatablePerToken = false;
    /** True only when the source itself carries real per-word/per-syllable timing.
     * Synthetic timing generated for plain LRC must never swell sustained words. */
    private boolean perSyllableSource = false;
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
    /** Per-line spring motion of the column; see {@link LyricColumnMotion}. */
    private final LyricColumnMotion motion = new LyricColumnMotion();
    /** Per group: when it gives up focus ({@link LyricMotion#releaseTimes}). */
    private long[] releaseMs = new long[0];

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
    /** Renderer-owned timing fragments for oversized source tokens or explicit
     * inner line breaks; the original source syllables and timings stay intact. */
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

    /** Glide the column to the next playback position (an explicit seek). */
    public void easeSeekOnNextRender() {
        cancelUserScrollForSeek();
        motion.jumpOnNextStep();
    }

    /** Glide the column to the playback position found after a render resume. */
    public void easeScrollOnNextRender() {
        cancelUserScrollForSeek();
        motion.jumpOnNextStep();
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
        this.perSyllableSource = prepared.perSyllableSource;
        this.groups = prepared.groups;
        this.lineToGroup = prepared.lineToGroup;
        this.releaseMs = LyricMotion.releaseTimes(prepared.groups);

        this.activeGroupIndex = -1;
        motion.reset();
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
        final long[] releaseMs = this.releaseMs;
        if (lines.isEmpty()) return;

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

        boolean spring = Boolean.TRUE.equals(cfg.springPhysics.getValue());
        boolean scaleOn = Boolean.TRUE.equals(cfg.scaleEmphasis.getValue());
        boolean glowOn = Boolean.TRUE.equals(cfg.glow.getValue());
        boolean shadowOn = Boolean.TRUE.equals(cfg.dropShadow.getValue());

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

        // ---- Layout. Every height below is either fully open or closed for this
        // frame; LyricColumnMotion turns each change into one spring motion per line.
        // (Ramping heights over time made the scroll spring and the ramp pull lines
        // in opposite directions in turn.)
        //
        // The interlude dot row opens when the previous group releases focus and
        // closes when the next one takes it, so opening it and scrolling to it, and
        // closing it and scrolling on to the next line, are single events.
        if (interludeBuf.length != groups.size()) interludeBuf = new float[groups.size()];
        float[] interludeBefore = interludeBuf;
        boolean inInterlude = false;
        int interludeNextGroup = -1;
        long interludeStartMs = 0L;
        long interludeEndMs = 0L;
        for (int gi = 0; gi < groups.size(); gi++) {
            long gapStart = gi == 0 ? 0L : releaseMs[gi - 1];
            long gapEnd = LyricMotion.fadeInStart(groups.get(gi));
            boolean open = gapEnd - gapStart >= INTERLUDE_THRESHOLD_MS
                    && positionMs >= gapStart && positionMs < gapEnd;
            interludeBefore[gi] = open ? INTERLUDE_DOTS_ROW_H : 0f;
            if (open) {
                inInterlude = true;
                interludeNextGroup = gi;
                interludeStartMs = gapStart;
                interludeEndMs = gapEnd;
            }
        }

        // Line positions are STATIC w.r.t. the zoom: the depth scale is a purely
        // visual, centre-anchored transform that doesn't move the line's centre, so
        // it never feeds back into the scroll target. Lines stack at their natural
        // heights.
        //
        // A background line takes space only while its group holds focus: closed for
        // upcoming lines (no empty gap below), reclaimed once the group releases.
        if (effHeightsBuf.length != n) effHeightsBuf = new float[n];
        float[] effHeights = effHeightsBuf;
        for (int i = 0; i < n; i++) {
            float h = lineHeights[i];
            if (LyricTimeline.isBackground(lines.get(i).vocalChannel)
                    && !LyricMotion.focused(positionMs, groups.get(lineToGroup[i]),
                    releaseMs[lineToGroup[i]])) {
                h = 0f;
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

        // ---- Focus. A group takes focus at its first visible brightening, ahead of
        // its start, and gives it up at its release time (LyricMotion.releaseTimes).
        // The view is anchored on the EARLIEST group holding focus (AMLL scrolls to
        // its first buffered line), so the anchor only ever moves forward: newer
        // overlapping lines appear below it and the column scrolls on when the
        // earliest one finishes. A group's release and its background slot closing
        // are one event, so lines above never sink back after scrolling up. With
        // nothing focused (a pause between lines) the view holds on the latest group.
        int anchorGroup = -1;
        int focusFirst = -1;
        int focusLast = -1;
        for (int gi = 0; gi < groups.size(); gi++) {
            LyricTimeline.Group g = groups.get(gi);
            if (LyricMotion.fadeInStart(g) > positionMs) break;
            anchorGroup = gi;
            if (positionMs < releaseMs[gi]) {
                if (focusFirst < 0) focusFirst = gi;
                focusLast = gi;
            }
        }
        if (focusFirst < 0) focusFirst = focusLast = anchorGroup;
        activeGroupIndex = anchorGroup;

        // Scroll target: the earliest focused line's centre on the alignment line. If
        // the newest focused line would fall off the bottom, scroll on just enough to
        // show it, never so far that the earliest one leaves the top.
        //
        // EXCEPTION: during an interlude the target is the reserved dot row, which
        // scrolls into place like a line.
        float targetScroll = 0f;
        int scrollAnchorLine = 0;
        if (focusFirst >= 0) {
            LyricTimeline.Group first = groups.get(focusFirst);
            LyricTimeline.Group last = groups.get(focusLast);
            float firstTop = lineTops[first.from];
            targetScroll = firstTop + effHeights[first.from] * 0.5f;
            float lastBottom = lineTops[last.to - 1] + effHeights[last.to - 1];
            float room = columnHeight * (1f - ALIGN_POSITION) - FOCUS_BOTTOM_MARGIN_PX;
            if (lastBottom - targetScroll > room) targetScroll = lastBottom - room;
            targetScroll = Math.min(targetScroll,
                    firstTop + columnHeight * ALIGN_POSITION - ACTIVE_GROUP_TOP_MARGIN_PX);
            scrollAnchorLine = first.from;
        }

        if (inInterlude) {
            float slotH = interludeBefore[interludeNextGroup];
            int nextFrom = groups.get(interludeNextGroup).from;
            targetScroll = lineTops[nextFrom] - slotH * 0.5f;
            scrollAnchorLine = nextFrom;
        }

        // Scroll bounds shared by the auto-follow AND manual scroll, so neither can run
        // a line past an edge into blank. When the lyrics are taller than the column,
        // pin the first line's top to the column top and the last line's bottom to the
        // column bottom; the active line still centres (ALIGN_POSITION) once there is
        // enough lyric above/below it. Shorter-than-column lyrics don't scroll.
        float scrollMinimum = targetScroll;
        float scrollMaximum = targetScroll;
        float contentEnd = lineTops[n - 1] + effHeights[n - 1];
        if (contentEnd > columnHeight) {
            float pad = columnHeight * SCROLL_EDGE_PAD;
            scrollMinimum = columnHeight * ALIGN_POSITION - pad;
            scrollMaximum = contentEnd - columnHeight * (1f - ALIGN_POSITION) + pad;
        }

        float centerY = topY + columnHeight * ALIGN_POSITION;
        userScroll.setViewport(centerY, scrollMinimum, scrollMaximum);
        targetScroll = userScroll.clamp(targetScroll);

        // A jump of the followed line (a seek) ends manual scrolling.
        if (userScroll.isActive() && userScroll.previousAnchor() != Integer.MIN_VALUE
                && Math.abs(scrollAnchorLine - userScroll.previousAnchor())
                > LyricColumnMotion.JUMP_LINES) {
            userScroll.cancel();
            motion.jumpOnNextStep();
        }
        userScroll.setPreviousAnchor(scrollAnchorLine);

        long nowNs = System.nanoTime();
        boolean userDriven = userScroll.isActive();
        float view = userDriven
                ? userScroll.step(targetScroll, nowNs, scrollAnchorLine) : targetScroll;
        // The draw window follows the focused line, or the user's view while
        // scrolling by hand, so the lines scrolled to are inside it.
        int windowCenter = userDriven
                ? LyricScrollController.lineIndexAt(lineTops, n, view) : scrollAnchorLine;
        int start = Math.max(0, windowCenter - VISIBLE_RADIUS);
        int end = Math.min(n, windowCenter + VISIBLE_RADIUS + 1);
        motion.step(nowNs, centerY, lineTops, n, start, end, view, userDriven,
                scrollAnchorLine, spring);
        userScroll.setLastRenderedOffset(motion.renderedOffset());

        litBandValid = false;
        for (int i = start; i < end; i++) {
            LyricLine line = lines.get(i);
            LyricTimeline.Group myGroup = groups.get(lineToGroup[i]);

            float activeK = LyricMotion.active(positionMs, myGroup);

            LyricLine.VocalChannel ch = line.vocalChannel;
            boolean isBg = LyricTimeline.isBackground(ch);
            float align = alignOf(ch);

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

            float lineYTop = motion.top(i);

            // Viewport cull: VISIBLE_RADIUS keeps far lines in the spring window, but
            // only a handful fit in the column — skip the draw work for lines fully
            // outside it. Margin covers the emphasis zoom + glow bleed.
            if (lineYTop + lineHeights[i] < topY - 32f || lineYTop > topY + columnHeight + 32f) {
                continue;
            }

            int[] starts = rowStarts[i];
            if (starts == null || cachedShapedRows[i] == null) continue;
            int subRowCount = Math.max(1, starts.length - 1);

            // The block the drawn rows actually occupy, so a sub-line (translation
            // / romaji) lines up under its own text however the line is aligned.
            float blockLeftX = Float.MAX_VALUE;
            float blockRightX = leftX;

            // Every line gets a scale transform. A background line pops out of the top
            // of its slot while its group holds focus and shrinks back into it as the
            // slot is reclaimed. Main lines use focus scaling, DESELECTED_SCALE growing
            // to EMPHASIS_SCALE with the group's timed activeK, anchored at the line's
            // CENTRE so growing it never shifts its centre.
            float anchorX = leftX + columnWidth * align;
            float scale;
            float anchorY;
            if (isBg) {
                float presence = LyricMotion.backgroundPresence(
                        positionMs, myGroup, releaseMs[lineToGroup[i]]);
                if (presence < BG_VISIBLE_THRESHOLD) continue;
                scale = BG_SCALE_IDLE + (1f - BG_SCALE_IDLE) * presence;
                anchorY = lineYTop;
            } else if (scaleOn) {
                float mainTextH = rowHeight + (subRowCount - 1) * rowHeightLyricWrap;
                scale = DESELECTED_SCALE + (EMPHASIS_SCALE - DESELECTED_SCALE) * activeK;
                anchorY = lineYTop + mainTextH * 0.5f;
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
                        glowOn, shadowOn, perSyllableSource);

                if (rowX < blockLeftX) blockLeftX = rowX;
                if (rowX + visWidth > blockRightX) blockRightX = rowX + visWidth;
            }

            // Sub-lines sit inside the block the rows above actually occupy and
            // follow the same alignment. Y must match the wrapped block's real
            // stacked height (first row full, extra rows at the wrap height).
            if (blockLeftX > blockRightX) blockLeftX = leftX; // nothing drawn
            float subY = lineYTop + rowHeight
                    + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap) + 4f
                    + (subRowCount > 1 ? WRAP_SUB_GAP : 0f);
            subY = drawSubline(blockLeftX, lineSubHeight, showRomaji, i, align,
                    baseAlpha, blockRightX, subY, cachedRomajiRows, shadowOn);
            drawSubline(blockLeftX, lineSubHeight, showTranslation, i, align,
                    baseAlpha, blockRightX, subY, cachedTranslationRows, shadowOn);

            canvas.restore();
        }

        // ---- Interlude dots (AMLL `InterludeDots`, inline in layout) ----
        // The dot row has its reserved INTERLUDE_DOTS_ROW_H slot in lineTops via
        // interludeBefore[]; the view has scrolled that slot into place, so the dots
        // are drawn in it. Math is a 1:1 port of AMLL's interlude-dots.ts.
        if (inInterlude) {
            LyricTimeline.Group interludeNext = groups.get(interludeNextGroup);
            long interludeDur = interludeEndMs - interludeStartMs;
            float slotH = interludeBefore[interludeNextGroup];
            if (slotH > 4f) {
                // Top of the upcoming line, as drawn, so the dots ride the column.
                int nf = interludeNext.from;
                float nextTop = (nf >= start && nf < end)
                        ? motion.top(nf) : centerY + lineTops[nf] - motion.renderedOffset();
                // Centre the dots between the two LINES OF TEXT, not the slot edges:
                // the next line's text starts nextTextOffset below its slot top.
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

    public void scrollDown(float y, long eventTimeNanos) {
        userScroll.pointerDown(y, eventTimeNanos);
    }

    public void scrollMove(float y, long eventTimeNanos) {
        userScroll.pointerMove(y, eventTimeNanos);
    }

    public void scrollUp(long eventTimeNanos) {
        userScroll.pointerUp(eventTimeNanos);
    }

    public void scrollCancel() {
        userScroll.pointerCancel();
    }

    public void scrollByWheel(float notches) {
        userScroll.wheel(notches);
    }

    public long timeAtScreenY(float screenY) {
        return userScroll.timeAtScreenY(screenY, lines, lineTopsBuf, cachedLineHeights);
    }
}
