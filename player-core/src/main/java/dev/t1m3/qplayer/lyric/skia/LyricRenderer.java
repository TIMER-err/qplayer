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
     * Apple Music instrumentalBreakVisualizationMinSeconds = 7.0.
     */
    private static final long INTERLUDE_THRESHOLD_MS = 7000L;
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
    /** 默认放大倍率(Apple Music 值);实际值由设置 lyricEmphasisScale 提供。 */
    private static final float EMPHASIS_SCALE = 1.14f;

    // ---- Scroll spring tunings ----
    // 行切换/seek 弹簧参数：LyricBlossom 模式（melodifyMotion=true，默认）由
    // LyricSprings 逐帧计算；原版模式用下面的 AMLL 调参常量。
    // Steadier fixed spring while seeking or during an interlude.
    private static final double SCROLL_STIFFNESS_INTERLUDE = 55.0;
    private static final double SCROLL_DAMPING_INTERLUDE = 10.1;
    // Non-spring fallback uses the same current k/damping pair, without cascade.
    private static final double SCROLL_STIFFNESS_FIRM = 65.0;
    private static final double SCROLL_DAMPING_FIRM = 11.0;
    // ── 原版（AMLL）动效常量 ──
    private static final double SCROLL_STIFFNESS_MIN = 65.0;
    private static final double SCROLL_STIFFNESS_MAX = 65.0;
    private static final double SCROLL_INTERVAL_MIN_MS = 100.0;
    private static final double SCROLL_INTERVAL_MAX_MS = 800.0;
    private static final double SCROLL_DAMPING_MULT = 1.365; // damping ≈ 11.0 @ k=65, ζ≈0.68
    private static final double SEEK_SPRING_STIFFNESS = 180.0;
    private static final double SEEK_SPRING_DAMPING = 28.0;
    // Per-line scroll cascade (Apple Specs.lineDelay = 0.05). The active line and
    // everything ABOVE it move together (delay 0) — lockstep preserves their
    // spacing so the active line never rises into a still-stationary line above it
    // (the overlap) and never stalls before moving (the hitch). Only the lines
    // BELOW the active line trail, with a shrinking step, for a downward wave.
    private static final double LINE_DELAY_S = 0.05;
    private static final double LINE_DELAY_DECAY = 1.05;
    /** Duration of render-resume/unclassified-jump scrolling; quartic ease-out. */
    private static final long DISCONTINUITY_EASE_DURATION_NS = 500_000_000L;
    // A seek that moves the anchor more than this many lines snaps the whole column
    // instead of spring-scrolling: a long spring would animate the lines that
    // happen to overlap the old window while the freshly-revealed lines just appear,
    // a jarring half-animate/half-flash mix. Small jumps still spring smoothly.
    private static final int SNAP_JUMP_LINES = 6;

    /** 提前跳转后，锚点已经越过的旧行用 lerp 平滑熄火（DIM_LERP_RATE≈150ms 内灭掉）。 */
    private static final float DIM_LERP_RATE = 12f;

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
    // 初始参数无实际意义：每次 seek 都会按模式重设（LyricSprings.seekSpring 或原版 SEEK_SPRING）。
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
    private double melodifySpringStiffness = 65.0;
    private double melodifySpringDamping = 11.0;
    /** 原版方向级联用：+1 前进（向上滚），-1 回退（向下滚）。 */
    private int cascadeDir = 1;
    /** Discontinuous position changes move the whole column with a non-spring ease-out. */
    private boolean seekEaseNextRender = false;
    private boolean seekEaseActive = false;
    /** Explicit playback seeks use the original rigid global spring. */
    private boolean seekSpringNextRender = false;
    private boolean seekSpringActive = false;
    private long seekEaseStartNs = 0L;
    private float seekEaseFrom = 0f;
    private float seekEaseTo = 0f;
    // Lyric-list switch (automix handover / track change): keep the old viewport so
    // the new list glides from it to its anchor instead of snapping. Null when there
    // is no pending start position. Empty-list intermediate states keep it alive.
    private Float pendingSeekFrom = null;
    private boolean seekEaseLiveTarget = false;
    // After a lyric-list switch glide, relaunch the per-line cascade so the fresh
    // rows float up in sequence instead of sitting where the rigid glide left them.
    private boolean cascadeRelaunch = false;
    private static final float CASCADE_DROP_PX = 240f;
    private long springAnchorChangeNs = 0L;
    private long springLastNs = 0L;
    /** 上一帧播放位置（LyricBlossom 逆向 seek 检测：时钟硬跳变 = seek）。 */
    private long lastFramePosMs = -1L;
    /** 提前跳转后旧行快速熄火系数（1=正常亮度，lerp 到 0 熄灭）。 */
    private float[] lineDimK = new float[0];
    // Anticipation trigger: the passed line's sweep slides from the trigger point
    // (line end - 710ms) to the line end at FAST_SWEEP speed instead of snapping
    // the whole row to "read". Wall-clock start per line, -1 when inactive.
    private long[] fastSweepAnchorMs = new long[0];
    private long[] fastSweepFromMs = new long[0];
    private static final float FAST_SWEEP_MS = 250f;
    /** 行级缩放弹簧（照搬 Melodify scaleSpring）：与位置弹簧同步换参、同步级联延迟，
     *  换行时缩放与跳转一起动。 */
    private SpringAnim[] scaleSpring = new SpringAnim[0];
    private boolean scaleSpringInit = false;
    /** 缩放级联延迟（秒）：换行帧按有效距离填充，之后每行用完清零（连续换行不清零）。 */
    private double[] scaleCascadeDelayBuf = new double[0];

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
    private float layoutKeyEmph = -1f;
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
            case BOLD:
                return Fonts.Weight.BOLD;
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
        boolean hadOld = this.lines != null && !this.lines.isEmpty();
        float prevScroll = (float) this.scrollAnim.getValue();
        clearLayoutCache();
        boolean linearPlainLrc = Boolean.TRUE.equals(LyricConfig.instance.linearAnimForPlainLrc.getValue());
        LyricTimeline.Prepared prepared = LyricTimeline.prepare(newLines, linearPlainLrc);
        if (hadOld) pendingSeekFrom = prevScroll;
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
        this.lastFramePosMs = -1L;
        this.lineDimK = new float[0];
        this.scaleSpring = new SpringAnim[0];
        this.scaleSpringInit = false;
        this.scaleCascadeDelayBuf = new double[0];
        userScroll.reset();
        // A fresh non-empty list after a switch: glide from the previous viewport to
        // the new anchor (fast quartic ease), instead of snapping to the top.
        if (!prepared.lines.isEmpty() && pendingSeekFrom != null) {
            seekEaseActive = true;
            seekEaseLiveTarget = true;
            seekEaseStartNs = System.nanoTime();
            seekEaseFrom = pendingSeekFrom;
            seekEaseTo = 0f; // live-updated to the anchor each frame until done
            pendingSeekFrom = null;
        }
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
        layoutKeyLines = null;
        layoutKeyLyricFont = null;
        layoutKeySubFont = null;
        layoutKeyBgFont = null;
        layoutKeyBgSubFont = null;
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
        float emphasisScale = cfg.emphasisScale.getValue();
        if (!(emphasisScale > 0f) || !(emphasisScale < 3f)) emphasisScale = EMPHASIS_SCALE;

        // Spring physics toggle: retune the scroll spring only when the flag flips
        // (carries current value/velocity into the new tuning — no snap).
        boolean spring = Boolean.TRUE.equals(cfg.springPhysics.getValue());
        // 歌词动效引擎：true = LyricBlossom/Melodify（念完即切/提前跳转/gap 弹簧/
        // 距离级联/缩放同步/已过行秒亮）；false = 原版 AMLL 动效。
        boolean melodifyMotion = Boolean.TRUE.equals(cfg.melodifyMotion.getValue());
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

        // Melodify 行盒 = 字体实际高度(getHeight) + pad——粗体/大 descent 字形
        // (g/y 下伸、i 上挑)不会溢出压到下一行。qplayer 用户行距作为下限兜底。
        float lyricMetricsH = lyricFont.getMetrics().getDescent() - lyricFont.getMetrics().getAscent();
        float bgMetricsH = bgFont.getMetrics().getDescent() - bgFont.getMetrics().getAscent();
        float subMetricsH = subFont.getMetrics().getDescent() - subFont.getMetrics().getAscent();
        float bgSubMetricsH = bgSubFont.getMetrics().getDescent() - bgSubFont.getMetrics().getAscent();
        if (Boolean.TRUE.equals(cfg.autoLineSpacing.getValue())) {
            // Melodify 行间距公式:单行占位 = 字高(H) + 10U(行内 pad) + 45U(行间 pad)
            // + 25(间距),U = 0.82051282051282048。H 用真实字形度量。开启后行距滑块无效。
            rowHeightRatio = (lyricMetricsH + 8.205128f + 36.923077f + 25f) / lyricFontSize;
        }
        float metricPad = 8f;
        float rowHeightLyric = Math.max(lyricFontSize * rowHeightRatio, lyricMetricsH + metricPad);
        float rowHeightLyricWrap = Math.max(lyricFontSize * WRAPPED_ROW_HEIGHT_RATIO, lyricMetricsH + metricPad);
        float rowHeightBg = Math.max(bgFontSize * rowHeightRatio, bgMetricsH + metricPad);
        float rowHeightBgWrap = Math.max(bgFontSize * WRAPPED_ROW_HEIGHT_RATIO, bgMetricsH + metricPad);
        float subLineHeight = Math.max(subFontSize * SUB_ROW_HEIGHT_RATIO, subMetricsH + metricPad);
        float bgSubLineHeight = Math.max(bgSubFontSize * SUB_ROW_HEIGHT_RATIO, bgSubMetricsH + metricPad);

        boolean showRomaji = cfg.showRomaji.getValue();
        boolean showTranslation = cfg.showTranslation.getValue();

        // ---- Layout pass. Wrapping + per-line heights depend only on the inputs
        // below, NOT on the play head, so compute them once and cache. HarfBuzz is
        // intentionally confined to this rebuild; playback frames only read TextBlob
        // and caret arrays.
        int n = lines.size();
        int colW = Math.round(columnWidth);
        boolean layoutValid = cachedRowStarts != null
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
                && layoutKeyEmph == emphasisScale
                && layoutKeyLyricFont == lyricFont
                && layoutKeySubFont == subFont
                && layoutKeyBgFont == bgFont
                && layoutKeyBgSubFont == bgSubFont;
        if (!layoutValid) {
            int[][] rowStarts = new int[n][];
            float[] lineHeights = new float[n];
            ShapedText[][] romajiRows = new ShapedText[n][];
            ShapedText[][] translationRows = new ShapedText[n][];
            ShapedRow[][] shapedRows = new ShapedRow[n][];
            List<List<Syllable>> layoutSyllables = new ArrayList<>(n);
            float[][] sylWidths = new float[n][];
            for (int i = 0; i < n; i++) {
                LyricLine line = lines.get(i);
                boolean isBg = LyricTimeline.isBackground(line.vocalChannel);
                Font font = isBg ? bgFont : lyricFont;
                Font lineSubFont = isBg ? bgSubFont : subFont;
                float rowHeight = isBg ? rowHeightBg : rowHeightLyric;
                float lineSubHeight = isBg ? bgSubLineHeight : subLineHeight;

                // Wrap against the EMPHASIZED width: a main line scales up to
                // EMPHASIS_SCALE when active, so break it as if the column were
                // 1/EMPHASIS_SCALE narrower — then the scaled-up line fills the
                // real column exactly instead of overflowing and clipping mid-word.
                // BG lines never scale past 1.0, and when emphasis is off no line
                // scales, so both wrap to the full column.
                float wrapW = (isBg || !scaleOn) ? columnWidth : columnWidth / emphasisScale;

                List<Syllable> rowSyllables = textShaper.splitOversizedSyllables(
                        line.syllables, font, wrapW);
                layoutSyllables.add(rowSyllables);
                float[] widths = textShaper.shapeSyllableAdvances(rowSyllables, font);
                sylWidths[i] = widths;
                rowStarts[i] = LyricTextLayout.wrapStarts(rowSyllables, widths, wrapW);
                int subRowCount = Math.max(1, rowStarts[i].length - 1);
                shapedRows[i] = new ShapedRow[subRowCount];
                for (int r = 0; r < subRowCount; r++) {
                    int from = rowStarts[i][r];
                    int to = rowStarts[i][r + 1];
                    shapedRows[i][r] = textShaper.shapeMainRow(rowSyllables, from, to, font);
                }

                float lh = rowHeight + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                boolean hasSub = (line.romaji != null && showRomaji) || (line.translation != null && showTranslation);
                // Wrapped rows use the tight wrap height, so a sub-line sitting right
                // under the last row feels cramped — give it a little extra breathing
                // room (reserved here so neighbours don't overlap; drawn at subY).
                if (hasSub && subRowCount > 1) lh += WRAP_SUB_GAP;
                if (line.romaji != null && showRomaji) {
                    romajiRows[i] = textShaper.shapeWrappedText(line.romaji, lineSubFont, wrapW);
                    lh += lineSubHeight * romajiRows[i].length;
                }
                if (line.translation != null && showTranslation) {
                    translationRows[i] = textShaper.shapeWrappedText(
                            line.translation, lineSubFont, wrapW);
                    lh += lineSubHeight * translationRows[i].length;
                }
                lh += lineGap;
                // BG lines reserve their full layout height upfront so neighbouring
                // lines never shift when the BG scales in / collapses.
                lineHeights[i] = lh;
            }
            LyricTextShaper.closeRows(cachedShapedRows);
            rowRenderer.clearRasterCache();
            LyricTextShaper.closeTexts(cachedRomajiRows);
            LyricTextShaper.closeTexts(cachedTranslationRows);
            cachedRowStarts = rowStarts;
            cachedLineHeights = lineHeights;
            cachedRomajiRows = romajiRows;
            cachedTranslationRows = translationRows;
            cachedShapedRows = shapedRows;
            cachedLayoutSyllables = layoutSyllables;
            cachedSylWidths = sylWidths;
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
            layoutKeyEmph = emphasisScale;
            layoutKeyLyricFont = lyricFont;
            layoutKeySubFont = subFont;
            layoutKeyBgFont = bgFont;
            layoutKeyBgSubFont = bgSubFont;
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
        // 翻译/罗马音行字体的 ascent（负值）——drawSubLine 的 y 是 baseline，
        // 用它把文本顶压到主行文本底之下，避免 y/g 这类大 descent 字符与翻译行重叠。
        float subAscent = subFont.getMetrics().getAscent();
        float bgSubAscent = bgSubFont.getMetrics().getAscent();

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

        // 行槽位：和声（背景）行槽位随 activeK 动态展开/收起（收起后间距恢复）。
        // "展开不挤"由 targetScroll 的 BG 展开补偿保证（见下）——空间顺着滚动上移
        // 提供，下方歌词不被挤下去。
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

        // ── 行切换时机 ──
        //    LyricBlossom 模式：① 念完即切——锚点是第一个"结束时间还没到"的组，
        //    当前行 endMs 一过立即切到下一组（空隙也照切）；② 提前跳转——无手动滚动时
        //    posMs + FAST_SWEEP_MS ≥ 下一组 startMs 提前切（跳过背景行由 group 结构
        //    天然保证）。
        //    原版模式：锚点在下一组进入淡入窗口（fadeInStart = startMs-450ms）时切。
        int anchorGroup = -1;
        int timelineGroupIndex = -1;
        if (melodifyMotion) {
            for (int gi = 0; gi < groups.size(); gi++) {
                LyricTimeline.Group g = groups.get(gi);
                if (g.endMs > positionMs) {
                    anchorGroup = gi;
                    break;
                }
            }
            if (anchorGroup < 0) anchorGroup = groups.size() - 1;
            for (int gi = 0; gi < groups.size(); gi++) {
                LyricTimeline.Group g = groups.get(gi);
                if (g.startMs > positionMs) break;
                timelineGroupIndex = gi;
            }
            if (!userScroll.isActive() && anchorGroup + 1 < groups.size()
                    && positionMs + (long) (LyricSprings.ANTICIPATION_S * 1000.0)
                            >= groups.get(anchorGroup + 1).startMs) {
                anchorGroup++;
            }
        } else {
            for (int gi = 0; gi < groups.size(); gi++) {
                LyricTimeline.Group g = groups.get(gi);
                if (LyricMotion.fadeInStart(g) > positionMs) break;
                anchorGroup = gi;
                if (g.startMs <= positionMs) timelineGroupIndex = gi;
            }
        }
        activeGroupIndex = anchorGroup;

        // ── 时钟硬跳变 = seek（LyricBlossom 逆向检测；原版模式不做）──
        double posSec = positionMs / 1000.0;
        boolean clockSeek = melodifyMotion
                && lastFramePosMs >= 0L
                && LyricSprings.isHardClockDiscontinuity(lastFramePosMs / 1000.0, posSec);
        long seekDeltaMs = lastFramePosMs >= 0L ? positionMs - lastFramePosMs : 0L;
        lastFramePosMs = positionMs;

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
        // 与锚点组同时演唱的合唱块最小组索引（供旧行熄火判断 isPartner 用）。
        int overlapBlockFrom = activeGroupIndex;
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
            overlapBlockFrom = blockFromGroup;
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
            // BG 槽位展开补偿（用户要求）：居中只含 Δ/2（groupBottom 含 BG 展开），
            // 下方行会被推下 Δ/2。再补 Δ/2 使下方行纹丝不动——和声展开的空间全部由
            // 上方内容上移（顺滚动方向）提供；收起时反向，间距自然恢复。
            float bgExpand = 0f;
            for (int j = activeGroup.from; j < activeGroup.to; j++) {
                if (LyricTimeline.isBackground(lines.get(j).vocalChannel)) {
                    bgExpand += lineHeights[j]
                            * LyricMotion.active(positionMs, groups.get(lineToGroup[j]));
                }
            }
            targetScroll += bgExpand * 0.5f;
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
                if (LyricMotion.fadeInStart(nextGroup) > positionMs) {
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
        boolean startSpringSeek = explicitSeek || (melodifyMotion && clockSeek);
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
        // 换行帧（弹簧锚点变化或 seek）——缩放弹簧与位置弹簧在这一帧同步换参、填级联延迟。
        boolean anchorChangedFrame = anchorIdx != springAnchorPrev;
        if (spring) {
            if (lineCurTop.length != n) {
                lineCurTop = new float[n];
                lineVelTop = new float[n];
                lineSpringInit = false;
            }
            if (scaleSpring.length != n) {
                scaleSpring = new SpringAnim[n];
                for (int i = 0; i < n; i++) scaleSpring[i] = new SpringAnim();
                scaleSpringInit = false;
            }
            if (anchorIdx != springAnchorPrev) {
                if (!melodifyMotion && springAnchorPrev != Integer.MIN_VALUE) {
                    // 原版方向级联：+1 前进（向上滚），-1 回退（向下滚）。
                    cascadeDir = (anchorIdx > springAnchorPrev) ? 1 : -1;
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
            if (melodifyMotion) {
                // LyricBlossom 逆向：seek 弹簧（大跨度 1/100/18，短距临界 T=0.1 m=2）。
                int prevAnchor = springAnchorPrev;
                LyricSprings.Physics seekP = LyricSprings.seekSpring(
                        prevAnchor >= 0 ? anchorIdx - prevAnchor : 0, seekDeltaMs / 1000.0);
                seekAnim.setParams(seekP.mass, seekP.damping, seekP.stiffness);
            } else {
                // 原版刚性 seek 弹簧（轻微过阻尼）。
                seekAnim.setParams(SEEK_SPRING_STIFFNESS, SEEK_SPRING_DAMPING);
            }
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
            if (seekEaseLiveTarget) seekEaseTo = targetScroll;
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
                if (seekEaseLiveTarget) {
                    // List-switch glide done: relaunch the per-line cascade so the
                    // new song's rows float up in sequence from below the anchor.
                    seekEaseLiveTarget = false;
                    cascadeRelaunch = true;
                    springAnchorChangeNs = nowNs;
                    if (lineCurTop.length == n) {
                        for (int i = start; i < end; i++) {
                            lineCurTop[i] = centerY + lineTops[i] - scrollY + CASCADE_DROP_PX;
                            lineVelTop[i] = 0f;
                        }
                    }
                }
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

        // ── 弹簧物理 ──
        //    LyricBlossom 模式：seek → seekSpring；正常换行 → lineTransitionSpring(gap)
        //    + retime（临近行按剩余时间收紧）。
        //    原版模式：动态调参（行间隔越密越硬，间奏稳态），ζ≈0.68。
        double scrollStiffness;
        double scrollDamping;
        if (melodifyMotion) {
            if (anchorChangedFrame || cascadeRelaunch || clockSeek || explicitSeek) {
                if (clockSeek || explicitSeek) {
                    int prevAnchor = springAnchorPrev;
                    LyricSprings.Physics p = LyricSprings.seekSpring(
                            prevAnchor >= 0 ? anchorIdx - prevAnchor : 0, seekDeltaMs / 1000.0);
                    melodifySpringStiffness = p.stiffness;
                    melodifySpringDamping = p.damping;
                } else {
                    double gap = 0.0;
                    if (activeGroup != null && activeGroupIndex > 0) {
                        gap = Math.max(activeGroup.startMs - groups.get(activeGroupIndex - 1).endMs, 0L) / 1000.0;
                    }
                    LyricSprings.Physics p = LyricSprings.lineTransitionSpring(animatablePerToken, gap);
                    boolean continuous = !userScroll.isActive()
                            && activeGroup != null
                            && activeGroup.endMs / 1000.0 - posSec - 0.5 < 0.6;
                    p = LyricSprings.retimeLineSpring(p,
                            activeGroup != null ? lines.get(activeGroup.from).endMs() / 1000.0 : posSec,
                            posSec, continuous);
                    melodifySpringStiffness = p.stiffness;
                    melodifySpringDamping = p.damping;
                }
            }
            scrollStiffness = melodifySpringStiffness;
            scrollDamping = melodifySpringDamping;
        } else if (inInterlude) {
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

        // ── 级联延迟 ──
        //    LyricBlossom 模式：Y 延迟 = max(有效距离-1, 0)·0.05s（跳过背景行），方向
        //    无关；连续换行延迟清零；手动滚动/seek 全部 0。缩放延迟同公式但连续不清零，
        //    只在换行帧填充。
        //    原版模式：方向衰减波（锚点同侧锁步，另一侧 0.05s 起逐行 ÷1.05）。
        if (cascadeDelayBuf.length < n) cascadeDelayBuf = new double[n];
        boolean cascadeDisabled = userScroll.isActive() || clockSeek || explicitSeek;
        boolean continuousNow = melodifyMotion && !cascadeDisabled
                && activeGroup != null
                && activeGroup.endMs / 1000.0 - posSec - 0.5 < 0.6;
        if (melodifyMotion) {
            // 级联锚点 = 视口内第一个可见行（照搬 Melodify firstVisibleLineIndex）——
            // 不能用渲染窗口 start（anchorIdx-VISIBLE_RADIUS，16 行远），否则锚点行也
            // 有 ≈0.75s 延迟，"念完即切"看起来像"等下一行开始才切换"。
            int cascadeAnchor = 0;
            for (int i = 0; i < n; i++) {
                if (centerY + lineTops[i] - targetScroll + lineHeights[i] > topY) {
                    cascadeAnchor = i;
                    break;
                }
            }
            // Melodify: 级联延迟对所有行(含视口外)生效——整屏依次浮起,而非只视口内。
            for (int i = 0; i < n; i++) {
                int dist = LyricSprings.validLineDistance(lines, cascadeAnchor, i);
                double sDelay = LyricSprings.cascadeDelay(dist, cascadeDisabled);
                cascadeDelayBuf[i] = continuousNow ? 0.0 : sDelay;
            }
            if (scaleCascadeDelayBuf.length < n) scaleCascadeDelayBuf = new double[n];
            if (anchorChangedFrame || cascadeRelaunch) {
                for (int i = 0; i < n; i++) {
                    int dist = LyricSprings.validLineDistance(lines, cascadeAnchor, i);
                    scaleCascadeDelayBuf[i] = LyricSprings.cascadeDelay(dist, cascadeDisabled);
                }
                cascadeRelaunch = false;
            } else {
                for (int i = 0; i < n; i++) scaleCascadeDelayBuf[i] = 0.0;
            }
        } else {
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
        }

        // Melodify 级联:所有行(含视口外)都走弹簧+级联延迟,新进入视口的行从旧位置
        // 依次浮起,而不是 snap 到位——这是级联"幅度"的主要来源。
        if (spring && !rigidMode && springDt > 0.0) {
            if (!lineSpringInit) {
                for (int i = 0; i < n; i++) {
                    if (lineCurTop.length > i) {
                        lineCurTop[i] = centerY + lineTops[i] - targetScroll;
                        lineVelTop[i] = 0f;
                    }
                }
            } else {
                for (int i = 0; i < n; i++) {
                    if (lineCurTop.length <= i) break;
                    if (sinceAnchorChange >= cascadeDelayBuf[i]) {
                        stepLineSpring(i, centerY + lineTops[i] - targetScroll,
                                springDt, scrollStiffness, scrollDamping);
                    }
                }
            }
        }

        litBandValid = false;
        for (int i = start; i < end; i++) {
            LyricLine line = lines.get(i);
            LyricTimeline.Group myGroup = groups.get(lineToGroup[i]);

            float activeK = LyricMotion.active(positionMs, myGroup);

            // ── 提前跳转后，锚点已经越过的旧行用 lerp 平滑熄火（约 150ms 内灭掉），
            //    不做瞬间跳变也不走 LyricMotion 的慢淡出。亮度、缩放、上浮、辉光、
            //    模糊带全部跟随 activeK 一起回落（LyricBlossom 模式；原版模式无此行为）。
            //    真正的合唱搭档（在 overlapBlockFrom..锚点之间的 duet block）仍在
            //    合唱，保持高亮不受影响。 ──
            int myGroupIdx = lineToGroup[i];
            boolean isPartner = myGroupIdx >= overlapBlockFrom && myGroupIdx < activeGroupIndex;
            if (melodifyMotion) {
                if (lineDimK.length != n) {
                    lineDimK = new float[n];
                    java.util.Arrays.fill(lineDimK, 1f);
                    fastSweepAnchorMs = new long[n];
                    java.util.Arrays.fill(fastSweepAnchorMs, -1L);
                    fastSweepFromMs = new long[n];
                }
                float dimTarget = (myGroupIdx < activeGroupIndex && !isPartner) ? 0f : 1f;
                float dimK = Math.min(1f, (float) Math.max(0.0, springDt) * DIM_LERP_RATE);
                lineDimK[i] += (dimTarget - lineDimK[i]) * dimK;
                activeK *= lineDimK[i];
            }

            LyricLine.VocalChannel ch = line.vocalChannel;
            boolean isBg = LyricTimeline.isBackground(ch);
            boolean alignRight = ch == LyricLine.VocalChannel.DUET_RIGHT
                    || ch == LyricLine.VocalChannel.BACKGROUND_RIGHT;

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
                lineYTop = lineCurTop[i];
            }

            // 缩放弹簧与位置弹簧同一时钟步进（照搬 Melodify：两者同步移动）。
            if (spring && scaleSpring.length == n) {
                scaleSpring[i].update(Math.max(0.0, springDt));
            }

            // Viewport cull: VISIBLE_RADIUS keeps far lines in the spring window
            // (stepped just above), but only a handful fit in the column — skip the
            // draw work (saveLayer/sweep/glow/drawString) for lines fully outside it.
            // Margin covers the emphasis zoom + glow bleed.
            if (lineYTop + lineHeights[i] < topY - 32f || lineYTop > topY + columnHeight + 32f) {
                continue;
            }

            int[] starts = rowStarts[i];
            int subRowCount = Math.max(1, starts.length - 1);

            // Track widest sub-row so right-aligned sub-lines line up with
            // the visual right edge of the lyric block.
            float maxRowWidth = 0f;
            float maxRowRightX = leftX; // for sub-line right-anchor

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
            float anchorX = alignRight ? (leftX + columnWidth) : leftX;
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
                boolean firstScaleFrame = !scaleSpringInit;
                boolean scaleWasVisible = i >= prevVisStart && i < prevVisEnd;
                boolean isAnchor = myGroupIdx == activeGroupIndex;
                boolean inMultiBlock = overlapBlockFrom < activeGroupIndex
                        && myGroupIdx >= overlapBlockFrom && myGroupIdx <= activeGroupIndex;
                if (melodifyMotion) {
                    // 缩放弹簧（照搬 Melodify scaleSpring）：换行帧与位置弹簧同步换参
                    // （同物理）＋同步级联延迟，缩放和跳转一起动。目标：合唱块内随
                    // activeK 渐变，否则锚点满放大 / 其他行 0.97 离散翻转。
                    float scaleTarget = inMultiBlock
                            ? DESELECTED_SCALE + (emphasisScale - DESELECTED_SCALE) * activeK
                            : (isAnchor ? emphasisScale : DESELECTED_SCALE);
                    if (spring && scaleSpring.length == n) {
                        if (anchorChangedFrame || cascadeRelaunch || clockSeek || explicitSeek) {
                            // Melodify scaleSpringParams: mass 2 / damping 25 / stiffness 100,
                            // ζ≈0.88 近临界——缩放不跟行切换弹簧的欠阻尼参数,无过冲回弹。
                            scaleSpring[i].setParams(2.0, 25.0, 100.0);
                        }
                        if (firstScaleFrame || !scaleWasVisible) {
                            scaleSpring[i].setValue(scaleTarget);
                        } else if (!scaleSpring[i].hasPendingTarget()
                                && scaleTarget != scaleSpring[i].getTargetPosition()) {
                            // 级联延迟等待中（hasPendingTarget）不重设 target——
                            // 否则会把换行帧的延迟清零，缩放提前开始（"先缩放完再滚动"）。
                            scaleSpring[i].setTargetPosition(scaleTarget, scaleCascadeDelayBuf[i]);
                        }
                        scaleCascadeDelayBuf[i] = 0.0;
                        scale = (float) scaleSpring[i].getValue();
                    } else {
                        // 非弹簧模式：直接跟 activeK 渐变（保持既有观感）。
                        scale = DESELECTED_SCALE + (emphasisScale - DESELECTED_SCALE) * activeK;
                    }
                } else if (spring) {
                    // 原版：到固定中线距离的渐进放大（行滚到中线附近才放大）。
                    float ref = Math.max(40f, lineHeights[i]);
                    float prog = 1f - Math.min(1f, Math.abs(lineCenter - centerY) / ref);
                    float emph = activeK * prog;
                    scale = DESELECTED_SCALE + (emphasisScale - DESELECTED_SCALE) * emph;
                } else {
                    float emph = activeK;
                    scale = DESELECTED_SCALE + (emphasisScale - DESELECTED_SCALE) * emph;
                }
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
                float rowX = alignRight
                        ? Math.max(leftX, leftX + columnWidth - visWidth)
                        : leftX;

                float wrapRowH = (r == 0) ? rowHeight : (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                float rowBaselineY = lineYTop + rowHeight + r * wrapRowH - descent - 4f;
                // 已过行 instant sweep（LyricBlossom 模式，照搬 Melodify）：提前跳转/念完
                // 即切后，锚点已经越过的旧行立即按"整行已唱完"配色，不再跟着 posMs 继续
                // 扫光——传 effPosMs = 行结束+1（sweep 全亮、lift 饱和、glow 关闭），而
                // 不是 Long.MAX_VALUE（会让 lift 的 cos 巨参溢出成 NaN）。真正的合唱搭档
                // （duet block）仍在合唱，照常扫。原版模式用真实 positionMs。
                long effPosMs = positionMs;
                long fastSweepStartMs = line.endMs()
                        - (long) (LyricSprings.ANTICIPATION_S * 1000.0)
                        - (long) FAST_SWEEP_MS;
                boolean preSwitchSweep = myGroupIdx == activeGroupIndex
                        && positionMs >= fastSweepStartMs && positionMs < line.endMs();
                boolean postSwitchSweep = myGroupIdx < activeGroupIndex && !isPartner;
                if (melodifyMotion && (preSwitchSweep || postSwitchSweep)) {
                    long endMs = line.endMs() + 1L;
                    long anchor = fastSweepAnchorMs[i];
                    if (anchor < 0L) {
                        anchor = nowNs / 1_000_000L;
                        fastSweepAnchorMs[i] = anchor;
                        // Start from the sweep position the fast-forward began at
                        // (continuous with the pre-switch state — no reset/jump back),
                        // then fast-forward to the line end (fully read) within 260ms —
                        // well before the 710ms anchor switch, so the row reads as
                        // "already finished" the moment the switch happens.
                        fastSweepFromMs[i] = Math.min(positionMs, endMs);
                    }
                    float prog = Math.min(1f, (nowNs / 1_000_000L - anchor) / FAST_SWEEP_MS);
                    effPosMs = fastSweepFromMs[i]
                            + (long) ((endMs - fastSweepFromMs[i]) * prog);
                } else {
                    fastSweepAnchorMs[i] = -1L;
                }
                rowRenderer.drawRow(cachedLayoutSyllables.get(i), shapedRow,
                        rowX - lead, rowBaselineY,
                        ascent, descent, effPosMs, baseAlpha, activeK, animatablePerToken, spring,
                        glowOn, shadowOn, wordGlowSupported);

                if (visWidth > maxRowWidth) {
                    maxRowWidth = visWidth;
                    maxRowRightX = rowX + visWidth;
                }
            }

            // Sub-lines anchor to the lyric block's right edge (right-align)
            // or to leftX (left-align). Y must match the wrapped block's real
            // stacked height (first row full, extra rows at the wrap height) —
            // using subRowCount*rowHeight overshoots and pushes translation /
            // romaji too far below a multi-row line.
            // drawSubLine 的 y 是 baseline：先落到主行最后一行文本视觉底
            // （lineYTop + 行盒 - 4，已含 descent），再上移 ascent（负值）把翻译行
            // 文本顶贴住主行底，+6px 留间距——大 descent 字符（y/g 尾巴）不再
            // 与翻译行重叠。
            float mainBottom = lineYTop + rowHeight
                    + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap) - 4f;
            float subAscentForLine = isBg ? bgSubAscent : subAscent;
            float subY = mainBottom - subAscentForLine + 6f
                    + (subRowCount > 1 ? WRAP_SUB_GAP : 0f);
            subY = drawSubline(leftX, lineSubHeight, showRomaji, i, alignRight,
                    baseAlpha, maxRowRightX, subY, cachedRomajiRows, shadowOn);
            subY = drawSubline(leftX, lineSubHeight, showTranslation, i, alignRight,
                    baseAlpha, maxRowRightX, subY, cachedTranslationRows, shadowOn);

            canvas.restore();
        }

        if (spring) {
            prevVisStart = start;
            prevVisEnd = end;
            lineSpringInit = true;
            scaleSpringInit = true;
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
                float nextTextOffset = rowHeightLyric + lyricAscent - lyricDescent - 4f;
                float prevTextBottom = nextTop - slotH;
                float nextTextTop = nextTop + nextTextOffset;
                float anchorY = (prevTextBottom + nextTextTop) * 0.5f - INTERLUDE_DOT_RADIUS;
                // Place the dots on the side the upcoming line is aligned to: left for
                // MAIN / left-duet, right for right-channel lines.
                LyricLine.VocalChannel nextCh = lines.get(interludeNext.from).vocalChannel;
                boolean dotsRight = nextCh == LyricLine.VocalChannel.DUET_RIGHT
                        || nextCh == LyricLine.VocalChannel.BACKGROUND_RIGHT;
                float dotsWidth = 2f * INTERLUDE_DOT_RADIUS + 2f * INTERLUDE_DOT_SPACING;
                float dotsX = dotsRight ? Math.max(leftX, leftX + columnWidth - dotsWidth) : leftX;
                renderInterludeDots(canvas, dotsX, anchorY,
                        positionMs - interludeStartMs, interludeDur);
            }
        }
    }

    private float drawSubline(float leftX, float subLineHeight,
                              boolean showRomaji, int i, boolean alignRight,
                              float baseAlpha, float maxRowRightX, float subY,
                              ShapedText[][] cachedRomajiRows, boolean shadowOn) {
        ShapedText[] romajiRows = cachedRomajiRows[i];
        if (romajiRows != null && showRomaji) {
            for (ShapedText romajiRow : romajiRows) {
                rowRenderer.drawSubLine(romajiRow, leftX, maxRowRightX, subY,
                        baseAlpha * 0.75f, alignRight, shadowOn);
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
