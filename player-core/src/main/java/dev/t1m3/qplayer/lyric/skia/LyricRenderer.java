package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple Music-style lyric column. Lines are left-anchored at {@code leftX}
 * and wrap into a column of width {@code columnWidth}. The active line is
 * vertically centered in the visible area; surrounding lines flow above/
 * below in a dimmer style. Per-character lift on the active line carries
 * the highlight (font size doesn't change).
 *
 * <p>Layout pass runs every frame: each line is broken at syllable
 * boundaries when its width exceeds the column, and every wrapped sub-row
 * counts toward the line's total height. Scroll is driven by cumulative
 * {@code lineTops}, not a fixed per-line spacing, so wrapped lines push
 * later lines down without overlapping.
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
     * Peak lift amplitude in pixels (negative = upward). AMLL's
     * reference value is 0.05em ≈ 1.5 px at 30 px font. Larger values
     * break the wave illusion: with 4 px+ amplitude the height step
     * between adjacent syllables becomes individually visible and the
     * effect reads as "each word kicks independently" instead of "a
     * smooth wave flows through the line". Match AMLL exactly so
     * multiple in-flight syllables blend continuously.
     */
    private static final float LIFT_PEAK_PX = 2.0f; // Apple Specs.syllableLift = 2.0
    /**
     * Per-syllable lift duration floor. AMLL uses {@code max(1000ms, wordDur)}
     * for {@code initFloatAnimation}: each syllable's translateY animation
     * runs for at least a full second. With typical pop pacing (~250 ms
     * per syllable) that means 3-4 syllables are simultaneously mid-rise
     * at any given moment, and the per-字 lifts visually fuse into a
     * continuous wave that flows along the line — already-sung words
     * are still rising, currently-sung words rise faster, upcoming words
     * are at 0 until their own delay elapses.
     *
     * <p>We used to clamp this to 200 ms to make per-字 progress
     * "visible" within a single syllable, but that killed the wave
     * overlap that gives AMLL its signature trailing-lift feel.
     */
    private static final long LIFT_MIN_DURATION_MS = 1000L;
    /**
     * Width of the gradient mask sweep band, in pixels — the lit→unlit feather that
     * rides the sweep head across the active line. The head itself tracks the play
     * head (no easing), so a wide feather is the ONLY thing that makes the per-字
     * reveal read as slow: each glyph lingers half-lit while the whole band crosses
     * it. AMLL/Apple use ~40 (feather 40.0); 16 keeps a soft edge (no aliasing) while
     * lighting each character crisply as the head passes.
     */
    private static final float SWEEP_FADE_PX = 16f;
    /**
     * Mask alpha on the unlit side of the active line's sweep. Multiplies the
     * line's baseAlpha at composite, so the active line's not-yet-sung text is
     * {@code activeBase * DARK_MASK_ALPHA}. Kept ≥ the deselected idle alpha
     * (0.30 main / 0.24 BG) so a line BRIGHTENS as it activates instead of first
     * dipping below the surrounding lines and then lighting up. Deselected lines
     * are dimmed to keep a strong sung/unsung sweep contrast on the active line.
     */
    private static final float DARK_MASK_ALPHA = 0.36f;
    /**
     * How long the active → idle handoff lasts after a group's endMs.
     * During this window the line's lift smoothly drops to 0 and its alpha
     * crossfades from the active bright to the idle dim, so finishing a
     * line doesn't snap the visuals.
     */
    private static final long ACTIVE_FADE_OUT_MS = 400L;
    /**
     * Mirror of {@link #ACTIVE_FADE_OUT_MS} for the lead-in. Starting
     * activeK from 0 at {@code startMs} would snap the line's alpha
     * (idle 0.42 → active × dark-mask 0.2) and read as a sudden dim;
     * letting it rise over the 200ms before {@code startMs} gives the
     * baseAlpha (and the mask's dark alpha) time to crossfade smoothly
     * up to playback levels.
     */
    private static final long ACTIVE_FADE_IN_MS = 200L;
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
     * Snappy pop-in window for the BG scale animation, in ms. Distinct
     * from the group's {@link #ACTIVE_FADE_IN_MS} so the BG can shoot
     * out fast (with overshoot) while the row's alpha still crossfades
     * at its calmer pace.
     */
    private static final long BG_POP_IN_MS = 460L;
    /**
     * Small lead so the BG row trails the main line a touch before popping in.
     */
    private static final long BG_POP_IN_DELAY_MS = 150L;
    /**
     * Pop-out (collapse) window. Slightly longer so the shrink reads as deliberate.
     */
    private static final long BG_POP_OUT_MS = 280L;
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
    // Breathing room (fraction of the column) left beyond the first / last line at the
    // scroll extremes: a touch over half the column so the ends have a generous run-out
    // (and the auto-follow keeps centring lines naturally rather than pinning at edges).
    private static final float SCROLL_EDGE_PAD = 0.7f;

    // ---- Depth scaling (Apple Specs) -------------------------------------
    // Inactive lines render at deselectedTransform (0.98×); the active group
    // grows to emphasizingScaleRange's upper bound (1.14×). Interpolated by
    // activeK so the scale crossfades with the highlight rather than snapping.
    private static final float DESELECTED_SCALE = 0.98f;
    private static final float EMPHASIS_SCALE = 1.14f;

    // ---- Scroll spring tunings (ported from AMLL computeLinePosYSpringParams) --
    // The per-line scroll spring is slightly OVERDAMPED (ζ = damping/(2√k) = 1.1),
    // so it never overshoots — the "spring" feel comes from velocity carry-over and
    // a stiffness that scales with how fast lines are arriving, not from bounce.
    // Stiffness lerps MIN..MAX as the gap between the active line and the previous
    // one shrinks from MAX_INTERVAL to MIN_INTERVAL (fast lines = snappier).
    private static final double SCROLL_STIFFNESS_MIN = 170.0;
    private static final double SCROLL_STIFFNESS_MAX = 220.0;
    private static final double SCROLL_INTERVAL_MIN_MS = 100.0;
    private static final double SCROLL_INTERVAL_MAX_MS = 800.0;
    private static final double SCROLL_DAMPING_MULT = 2.2; // damping = √stiffness × 2.2 → ζ≈1.1
    // Steadier fixed spring while seeking or during an interlude.
    private static final double SCROLL_STIFFNESS_INTERLUDE = 90.0;
    private static final double SCROLL_DAMPING_INTERLUDE = 15.0;
    // Non-spring fallback: the previous slightly-overdamped tuning, no cascade.
    private static final double SCROLL_STIFFNESS_FIRM = 180.0;
    private static final double SCROLL_DAMPING_FIRM = 28.0;
    // Apple liftSpring: mass 1, stiffness 14, damping 7 → ω0=√14, ζ≈0.935.
    private static final double LIFT_OMEGA0 = 3.7416574; // sqrt(14)
    private static final double LIFT_ZETA = 0.935414;    // 7 / (2·√14)
    // Peak opacity of the white glow behind a fully-sung syllable.
    private static final float GLOW_ALPHA = 0.55f;
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
    /**
     * Whether the loaded lyric source carries per-syllable timing.
     * {@code true} when at least one line has &gt; 1 syllable —
     * implies YRC / LYS / TTML / QRC etc. {@code false} for plain LRC
     * (parser produces a single line-wide syllable per line). Used as
     * the global gate for the per-syllable lift: a single-字 line in a
     * YRC source should still lift, but the same line shape in a LRC
     * source shouldn't, so the per-line {@code size() > 1} heuristic
     * was producing inconsistent lift between songs depending on how
     * many one-word lines each had.
     */
    private boolean hasPerSyllableTiming = false;
    /**
     * Lines bundled into "active groups". A solo line is its own group; a
     * pair (or chain) of overlapping DUET_LEFT / DUET_RIGHT lines becomes
     * a single group. Used so the active highlight + scroll target stick
     * to the whole duet block until the last voice finishes — without
     * this, the moment the second singer starts mid-phrase the first
     * singer's row would flip to "non-active" and freeze its sweep.
     */
    private List<LineGroup> groups = Collections.emptyList();
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
    private boolean litBandSmoothInit;
    private long litBandSmoothNs;
    /**
     * Spring-driven vertical scroll. Stiffness/damping pair tuned to settle
     * a typical line jump in ~500ms with a barely-visible overshoot,
     * matching Apple Music's lyric flow. Duration-based easing would
     * restart on every line change; the spring carries velocity through.
     */
    // Damping ≈ 2·√stiffness = critical damping → no overshoot. With
    // the previous (180, 22) tuning the spring was underdamped, so a
    // target jump (e.g. interlude exit → next group) would overshoot
    // and the column visibly bounced back. (28 > 2·√180 ≈ 26.83 is
    // very slightly overdamped, which keeps the motion strictly
    // monotonic toward the target.)
    private final SpringAnim scrollAnim = new SpringAnim(SCROLL_STIFFNESS_FIRM, SCROLL_DAMPING_FIRM);
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
    private String[][] cachedRomajiRows;
    private String[][] cachedTranslationRows;
    // Per-syllable advance widths per line, cached with the layout. Measuring a
    // syllable's width re-shapes text and (via perCharWidth) allocated a String
    // per character every frame for every visible row — the lyric page's biggest
    // per-frame garbage source. Widths depend only on (text, font), which the
    // layout key already covers, so cache them and the per-frame draw reads floats.
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
    private int cascadeDir = 1; // +1 advancing (scroll up), -1 seeking back (scroll down)
    // True while a big seek is being eased over with the simple global scroll spring
    // (per-line cascade suspended until it settles, then handed back seamlessly).
    private boolean bigSeekEasing = false;
    private long springAnchorChangeNs = 0L;
    private long springLastNs = 0L;

    // --- Manual scroll (drag / fling) ------------------------------------------
    // While the user drags the lyric column its position is hand-controlled; the
    // karaoke highlight keeps following the play head. Releasing flings with engine-
    // style inertia (windowed release velocity + constant deceleration). After an idle
    // period, the next line change eases the column back to the follow position using
    // scrollAnim -- the very spring the seek ("调整进度") adjustment rides.
    private static final float SCROLL_DECEL = 2400f;     // px/s^2 (fling deceleration)
    private static final float SCROLL_MIN_FLING = 60f;   // px/s below which fling stops
    private static final long SCROLL_IDLE_RETURN_NS = 4_000_000_000L; // 4s idle before auto-return
    private static final int SCROLL_VEL_SAMPLES = 8;
    private static final float SCROLL_VEL_WINDOW = 0.09f; // s of history for release velocity
    private boolean userScrollActive;   // drag, fling, idle-hold, or returning
    private boolean userDragging;       // finger currently down
    private boolean userFling;          // coasting after release
    private boolean userReturning;      // easing back to follow via scrollAnim
    private float userScroll;           // content-space offset (same space as scrollY)
    private float userFlingVel;         // px/s
    private long userScrollLastNs;      // fling integration clock
    private long userLastInteractNs;    // last drag/fling activity (idle timer)
    private int userHoldAnchor = Integer.MIN_VALUE; // active line when interaction stopped
    private int userScrollPrevAnchor = Integer.MIN_VALUE; // detect a seek jump to cancel scroll
    private float lastScrollY;          // last rendered scrollY (seeds a fresh drag)
    private float lastCenterY;          // last centerY (maps a tapped screen y to a line)
    private float scrollMin, scrollMax; // content-space clamp bounds (set each frame)
    private final long[] dragSampleNs = new long[SCROLL_VEL_SAMPLES];
    private final float[] dragSampleY = new float[SCROLL_VEL_SAMPLES];
    private int dragSampleCount;

    // Reused per active line each frame (syllable left edges); was new float[n+1].
    private float[] sylLeftBuf = new float[0];
    // Reused saveLayer paint for the active row's composite alpha; was a native
    // Paint allocated per active line per frame. Kept alive for the renderer's
    // lifetime (one line's saveLayer/restore completes before the next), so the
    // "keep alive until restore" constraint below is satisfied without per-call new.
    private final io.github.humbleui.skija.Paint lyricLayerPaint = new io.github.humbleui.skija.Paint();
    /**
     * Reusable paint for interlude dots — avoids per-frame allocation.
     */
    private final io.github.humbleui.skija.Paint dotPaint = new io.github.humbleui.skija.Paint();
    // Reused sweep-mask state. The fixed-band gradient shader is cached and only
    // rebuilt when its dark colour changes (activeK fade); the head is positioned
    // by translating the canvas over a cached oversized rect, so a steady sweep
    // allocates nothing. sweepShaderDark = NaN forces the first build.
    private final io.github.humbleui.skija.Paint sweepPaint = new io.github.humbleui.skija.Paint();
    private final int[] sweepColors = new int[2];
    private final float[] sweepStops = new float[2];
    private Shader sweepShader;
    private float sweepShaderDark = Float.NaN;
    private final Rect sweepBigRect = Rect.makeLTRB(-100000f, -100000f, 100000f, 100000f);
    // White glow behind active syllables (Apple Specs.glowColor/glowRadius). Drawn as
    // one blurred saveLayer for the whole row, NOT a mask-filter blur per glyph: a
    // per-glyph blur rasterizes a separate blur for every syllable, so a word-by-word
    // line with N syllables paid N blurs every frame (and ~2N during a line change,
    // when two rows are active) — the lyric-page scroll stutter. glowGlyphPaint draws
    // the plain glyphs into glowLayerPaint's blur layer, which blurs the lot once.
    private final io.github.humbleui.skija.Paint glowGlyphPaint = newGlowGlyphPaint();
    private final io.github.humbleui.skija.Paint glowLayerPaint = newGlowLayerPaint();
    // Per-syllable lift offset + glow alpha for the active row, filled once per row and
    // reused by the glow pass and the text pass (avoids recomputing the spring twice).
    private float[] liftBuf = new float[0];
    private float[] glowAlphaBuf = new float[0];

    private static io.github.humbleui.skija.Paint newGlowGlyphPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        p.setAntiAlias(true);
        return p;
    }

    private static io.github.humbleui.skija.Paint newGlowLayerPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        // Apple glowRadius = 5.0; Skia blur sigma ≈ radius * 0.5. One layer blur over
        // the row replaces the old per-glyph mask-filter blur.
        p.setImageFilter(io.github.humbleui.skija.ImageFilter.makeBlur(
                2.5f, 2.5f, io.github.humbleui.skija.FilterTileMode.CLAMP));
        return p;
    }

    private List<LyricLine> layoutKeyLines;
    private int layoutKeyN;
    private int layoutKeyLyricSize;
    private int layoutKeySubSize;
    private int layoutKeyColW = -1;
    private Fonts.Weight layoutKeyWeight;
    private float layoutKeyRowRatio = -1f;
    private boolean layoutKeyRomaji;
    private boolean layoutKeyTranslation;
    private boolean layoutKeyScale = true;

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

    public void setLyrics(List<LyricLine> newLines) {
        // Strip whitespace-only / fully-empty lines. Some TTML / LRC
        // sources put a blank <p>/[mm:ss] in the middle of a long
        // interlude to mark "the music continues here" — it has a
        // timestamp but no rendered text. Keeping them would make
        // {@link #buildGroups} treat the blank as a normal singing
        // line, eating the gap and suppressing interlude dots.
        List<LyricLine> filtered;
        if (newLines == null || newLines.isEmpty()) {
            filtered = Collections.emptyList();
        } else {
            filtered = new java.util.ArrayList<>(newLines.size());
            for (LyricLine l : newLines) {
                if (l == null) continue;
                boolean hasText = false;
                for (Syllable s : l.syllables) {
                    if (s != null && s.text != null && !s.text.trim().isEmpty()) {
                        hasText = true;
                        break;
                    }
                }
                if (hasText) filtered.add(l);
            }
        }
        this.lines = filtered;
        this.groups = buildGroups(this.lines);
        this.lineToGroup = new int[this.lines.size()];
        for (int gi = 0; gi < this.groups.size(); gi++) {
            LineGroup g = this.groups.get(gi);
            for (int i = g.from; i < g.to; i++) lineToGroup[i] = gi;
        }
        // Detect source type once per load: any line with multi-syllable
        // means the source is per-syllable (YRC / LYS / TTML / QRC).
        // Pure LRC always parses to exactly one syllable per line.
        boolean perSyl = false;
        for (LyricLine l : this.lines) {
            if (l.syllables.size() > 1) {
                perSyl = true;
                break;
            }
        }
        this.hasPerSyllableTiming = perSyl;

        // LRC (line-level) lyrics parse to a single syllable per line, so the
        // syllable-boundary wrap never finds a break point and long lines run
        // off the edge. Split each line's lone syllable into wrap tokens (one
        // per CJK char, Latin split on spaces) that share the line's timing —
        // wrapStarts can then break between them. Per-syllable sources already
        // carry their own break points, so leave them untouched.
        if (!perSyl) {
            for (LyricLine l : this.lines) {
                if (l.syllables.size() != 1) continue;
                List<Syllable> toks = tokenizeForWrap(l.syllables.get(0));
                if (toks.size() > 1) {
                    l.syllables.clear();
                    l.syllables.addAll(toks);
                }
            }
        }

        this.activeGroupIndex = -1;
        this.scrollAnim.setValue(0);
        this.lineSpringInit = false;
        this.bigSeekEasing = false;
        this.springAnchorPrev = Integer.MIN_VALUE;
        // Drop any manual scroll from the previous track.
        this.userScrollActive = false;
        this.userDragging = false;
        this.userFling = false;
        this.userReturning = false;
        this.userScrollPrevAnchor = Integer.MIN_VALUE;
        this.userHoldAnchor = Integer.MIN_VALUE;
    }

    // Break a whole-line syllable into wrap-friendly tokens that keep its
    // timing: each CJK character is its own token; Latin text splits on spaces
    // (the run of spaces rides with the preceding token's trailing break).
    private static List<Syllable> tokenizeForWrap(Syllable s) {
        String text = s.text == null ? "" : s.text;
        long start = s.startMs;
        long dur = s.durationMs;
        List<Syllable> out = new java.util.ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ') {
                int j = i;
                while (j < n && text.charAt(j) == ' ') j++;
                out.add(new Syllable(text.substring(i, j), start, dur));
                i = j;
            } else if (isWrapCjk(c)) {
                out.add(new Syllable(String.valueOf(c), start, dur));
                i++;
            } else {
                int j = i;
                while (j < n && text.charAt(j) != ' ' && !isWrapCjk(text.charAt(j))) j++;
                out.add(new Syllable(text.substring(i, j), start, dur));
                i = j;
            }
        }
        return out;
    }

    private static boolean isWrapCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)    // CJK unified ideographs
                || (c >= 0x3040 && c <= 0x30FF) // hiragana + katakana
                || (c >= 0xAC00 && c <= 0xD7A3); // hangul syllables
    }

    /**
     * A contiguous range of lines that should activate / scroll together.
     */
    private static final class LineGroup {
        final int from;   // inclusive
        final int to;     // exclusive
        final long startMs;
        final long endMs;

        LineGroup(int from, int to, long startMs, long endMs) {
            this.from = from;
            this.to = to;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        boolean contains(int i) {
            return i >= from && i < to;
        }
    }

    /**
     * Build groups the way AMLL does: a group is one main line plus any
     * BACKGROUND-channel lines that immediately follow it. Two consecutive
     * non-BG lines are two separate groups regardless of channel
     * (DUET_LEFT vs DUET_RIGHT) — AMLL doesn't merge by agent.
     *
     * <p>Cross-group scroll behaviour (the "v1 still singing while v2
     * starts" feedback) is handled at render time via the buffered-group
     * rule, not by merging groups here.
     */
    private static List<LineGroup> buildGroups(List<LyricLine> lines) {
        List<LineGroup> out = new ArrayList<>();
        int n = lines.size();
        int i = 0;
        while (i < n) {
            int j = i + 1;
            long endMs = lines.get(i).endMs();
            // Absorb BG lines that follow the main line.
            while (j < n && isBackground(lines.get(j).vocalChannel)) {
                endMs = Math.max(endMs, lines.get(j).endMs());
                j++;
            }
            out.add(new LineGroup(i, j, lines.get(i).startMs(), endMs));
            i = j;
        }
        return out;
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
        return new float[]{litBandTopSmooth, litBandBottomSmooth};
    }

    public void render(Canvas canvas, float leftX, float topY,
                       float columnWidth, float columnHeight, long positionMs) {
        // Snapshot mutable state so a concurrent setLyrics() mid-frame can't
        // replace lines/groups/lineToGroup underneath us (ArrayIndexOutOfBounds).
        final java.util.List<LyricLine> lines = this.lines;
        final java.util.List<LineGroup> groups = this.groups;
        final int[] lineToGroup = this.lineToGroup;
        if (lines.isEmpty()) return;

        LyricConfig cfg = LyricConfig.instance;
        int lyricFontSize = cfg.lyricFontSize.getValue();
        int subFontSize = cfg.subFontSize.getValue();
        // BG font derived from the main lyric font (~70%) — the standalone
        // bgFontSize config was tuned independently and ended up reading
        // visually overweight against the active line. Tying it to the
        // lyric size keeps the BG legibly smaller across user font-size
        // changes too.
        int bgFontSize = Math.max(10, Math.round(lyricFontSize * 0.7f));
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

        // Animation-friendly font flags. Skia defaults snap text baselines
        // to integer pixels (isBaselineSnapped=true) and grid-fit glyphs
        // via hinting — so a smooth fractional translateY would still
        // render at integer y, giving the "jumps several pixels per
        // frame" feel the user reported. Disabling baseline snap + going
        // to subpixel positioning makes the lift continuous on the GPU.
        configureForAnimation(lyricFont);
        configureForAnimation(bgFont);
        // subFont is static text (no animation) but we still want it crisp
        // and consistent with the lyric font's anti-alias level.
        configureForAnimation(subFont);

        float rowHeightLyric = lyricFontSize * rowHeightRatio;
        float rowHeightLyricWrap = lyricFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float rowHeightBg = bgFontSize * rowHeightRatio;
        float rowHeightBgWrap = bgFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float subLineHeight = subFontSize * SUB_ROW_HEIGHT_RATIO;

        boolean showRomaji = cfg.showRomaji.getValue();
        boolean showTranslation = cfg.showTranslation.getValue();

        // ---- Layout pass. Wrapping + per-line heights depend only on the inputs
        // below, NOT on the play head, so compute them once and cache. wrapStarts()
        // reshapes text and allocates an int[] per line; doing that for all N lines
        // every frame was the lyric page's dominant per-frame cost + garbage.
        int n = lines.size();
        int colW = Math.round(columnWidth);
        boolean layoutValid = cachedRowStarts != null
                && layoutKeyLines == lines
                && layoutKeyN == n
                && layoutKeyLyricSize == lyricFontSize
                && layoutKeySubSize == subFontSize
                && layoutKeyColW == colW
                && layoutKeyWeight == weight
                && layoutKeyRowRatio == rowHeightRatio
                && layoutKeyRomaji == showRomaji
                && layoutKeyTranslation == showTranslation
                && layoutKeyScale == scaleOn;
        if (!layoutValid) {
            int[][] rowStarts = new int[n][];
            float[] lineHeights = new float[n];
            String[][] romajiRows = new String[n][];
            String[][] translationRows = new String[n][];
            float[][] sylWidths = new float[n][];
            for (int i = 0; i < n; i++) {
                LyricLine line = lines.get(i);
                boolean isBg = isBackground(line.vocalChannel);
                Font font = isBg ? bgFont : lyricFont;
                float rowHeight = isBg ? rowHeightBg : rowHeightLyric;

                // Wrap against the EMPHASIZED width: a main line scales up to
                // EMPHASIS_SCALE when active, so break it as if the column were
                // 1/EMPHASIS_SCALE narrower — then the scaled-up line fills the
                // real column exactly instead of overflowing and clipping mid-word.
                // BG lines never scale past 1.0, and when emphasis is off no line
                // scales, so both wrap to the full column.
                float wrapW = (isBg || !scaleOn) ? columnWidth : columnWidth / EMPHASIS_SCALE;

                int sylCount = line.syllables.size();
                float[] widths = new float[sylCount];
                for (int s = 0; s < sylCount; s++) {
                    String st = line.syllables.get(s).text;
                    widths[s] = perCharWidth(st, fontForText(st, font));
                }
                sylWidths[i] = widths;
                rowStarts[i] = wrapStarts(line.syllables, widths, wrapW);
                int subRowCount = Math.max(1, rowStarts[i].length - 1);

                float lh = rowHeight + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                boolean hasSub = (line.romaji != null && showRomaji) || (line.translation != null && showTranslation);
                // Wrapped rows use the tight wrap height, so a sub-line sitting right
                // under the last row feels cramped — give it a little extra breathing
                // room (reserved here so neighbours don't overlap; drawn at subY).
                if (hasSub && subRowCount > 1) lh += WRAP_SUB_GAP;
                if (line.romaji != null && showRomaji) {
                    romajiRows[i] = wrapText(line.romaji, subFont, wrapW);
                    lh += subLineHeight * romajiRows[i].length;
                }
                if (line.translation != null && showTranslation) {
                    translationRows[i] = wrapText(line.translation, subFont, wrapW);
                    lh += subLineHeight * translationRows[i].length;
                }
                lh += lineGap;
                // BG lines reserve their full layout height upfront so neighbouring
                // lines never shift when the BG scales in / collapses.
                lineHeights[i] = lh;
            }
            cachedRowStarts = rowStarts;
            cachedLineHeights = lineHeights;
            cachedRomajiRows = romajiRows;
            cachedTranslationRows = translationRows;
            cachedSylWidths = sylWidths;
            layoutKeyLines = lines;
            layoutKeyN = n;
            layoutKeyLyricSize = lyricFontSize;
            layoutKeySubSize = subFontSize;
            layoutKeyColW = colW;
            layoutKeyWeight = weight;
            layoutKeyRowRatio = rowHeightRatio;
            layoutKeyRomaji = showRomaji;
            layoutKeyTranslation = showTranslation;
            layoutKeyScale = scaleOn;
        }
        int[][] rowStarts = cachedRowStarts;
        float[] lineHeights = cachedLineHeights;
        float[][] sylWidths = cachedSylWidths;

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
            interludeBefore[gi] = computeInterludeSlot(positionMs, prevEnd, effectiveEnd);
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
            if (isBackground(lines.get(i).vocalChannel)) {
                h *= computeActiveK(positionMs, groups.get(lineToGroup[i]));
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

        // Resolve "active group" the AMLL way: every group whose time
        // window [startMs, endMs] contains the play head is considered
        // active; the *anchor* group (the one we scroll to) is the
        // earliest such group. So if v1 hasn't finished and v2 has
        // already started, scroll stays anchored on v1 — v2 appears
        // below it on-screen but the column doesn't jump up. This is the
        // "min(bufferedGroups)" rule from AMLL's commitPlayerTimeState.
        int anchorGroup = -1;
        int latestActiveGroup = -1;
        for (int gi = 0; gi < groups.size(); gi++) {
            LineGroup g = groups.get(gi);
            if (g.startMs > positionMs) break;
            // A group is still buffered if pos hasn't passed its endMs;
            // pick the earliest such group as the scroll anchor.
            if (positionMs < g.endMs) {
                if (anchorGroup < 0) anchorGroup = gi;
            }
            latestActiveGroup = gi;
        }
        // If nothing is currently buffered (we're in a between-line gap),
        // fall back to the latest already-passed group so scroll keeps
        // sitting on the last sung line until the next phrase starts.
        if (anchorGroup < 0) anchorGroup = latestActiveGroup;
        activeGroupIndex = anchorGroup;

        LineGroup activeGroup = (activeGroupIndex >= 0 && activeGroupIndex < groups.size())
                ? groups.get(activeGroupIndex) : null;

        // Scroll target = the active group's main-line centre, PLUS the group's
        // currently-reserved BG height. The BG slot collapses when unfocused (above),
        // so anchoring on the content just below it keeps the lines beneath still while
        // the reservation grows; the main line eases up by that amount to open the gap
        // for its BG — the Apple-Music "focus lands, line lifts, space appears" feel,
        // carried by the per-line spring so the lines below barely stir.
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
            int mIdx = activeGroup.from;
            float mTop = lineTops[mIdx];
            float mBottom = mTop + lineHeights[mIdx];
            float reservedBg = 0f;
            for (int j = activeGroup.from + 1; j < activeGroup.to; j++) {
                if (isBackground(lines.get(j).vocalChannel)) reservedBg += effHeights[j];
            }
            targetScroll = (mTop + mBottom) * 0.5f + reservedBg;
        }

        // Interlude detection covers THREE shapes:
        //   1. Intro: positionMs < groups[0].startMs, gap = [0, group[0].start)
        //   2. Between groups: activeGroup just finished, gap to next
        //   3. Outro: after last group — no dots (no "next" to anchor to)
        // End trimmed by INTERLUDE_TRAIL_TRIM_MS so the dots collapse a
        // moment before the next line sings.
        LineGroup nextGroup = null;
        long gapStart = -1L;
        if (activeGroup == null && !groups.isEmpty()
                && positionMs < groups.get(0).startMs) {
            // Intro
            nextGroup = groups.get(0);
            gapStart = 0L;
            interludeNextGroup = 0;
        } else if (activeGroup != null && activeGroupIndex + 1 < groups.size()
                && positionMs >= activeGroup.endMs) {
            // Between groups
            nextGroup = groups.get(activeGroupIndex + 1);
            gapStart = activeGroup.endMs;
            interludeNextGroup = activeGroupIndex + 1;
        }
        if (nextGroup != null) {
            long effectiveEnd = nextGroup.startMs - INTERLUDE_TRAIL_TRIM_MS;
            long gap = effectiveEnd - gapStart;
            if (positionMs < effectiveEnd && gap >= INTERLUDE_THRESHOLD_MS) {
                inInterlude = true;
                interludeStartMs = gapStart;
                float slotH = interludeBefore[interludeNextGroup];
                float dotsTop = lineTops[nextGroup.from] - slotH;
                targetScroll = dotsTop + slotH * 0.5f;
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
        if (n > 0) {
            float contentEnd = lineTops[n - 1] + effHeights[n - 1];
            if (contentEnd > columnHeight) {
                float pad = columnHeight * SCROLL_EDGE_PAD;
                scrollMin = columnHeight * ALIGN_POSITION - pad;
                scrollMax = contentEnd - columnHeight * (1f - ALIGN_POSITION) + pad;
            } else {
                scrollMin = scrollMax = targetScroll;
            }
            if (targetScroll < scrollMin) targetScroll = scrollMin;
            else if (targetScroll > scrollMax) targetScroll = scrollMax;
        }

        float centerY = topY + columnHeight * ALIGN_POSITION;
        lastCenterY = centerY;

        int anchorIdx = activeGroup != null ? activeGroup.from : 0;
        // The draw window normally tracks the active line, but a manual scroll can pull
        // the view far from it — center the window on the on-screen scroll position then,
        // or the lines you scrolled to (being outside anchorIdx ± VISIBLE_RADIUS) are
        // never drawn and the page goes blank. lastScrollY is the previous frame's offset.
        int windowCenter = userScrollActive ? lineIndexAt(lineTops, n, lastScrollY) : anchorIdx;
        int start = Math.max(0, windowCenter - VISIBLE_RADIUS);
        int end = Math.min(n, windowCenter + VISIBLE_RADIUS + 1);

        // Per-line scroll springs (spring mode only). Each visible line chases its
        // resting top `centerY + lineTops[i] - targetScroll`; the global scrollAnim
        // above still drives the rigid fallback when spring is off.
        long nowNs = System.nanoTime();
        double springDt = 0.0;
        if (spring) {
            if (lineCurTop.length != n) {
                lineCurTop = new float[n];
                lineVelTop = new float[n];
                lineSpringInit = false;
            }
            if (anchorIdx != springAnchorPrev) {
                // Direction the column is travelling: +1 advancing (content scrolls
                // up), -1 seeking back (content scrolls down). Drives which side of
                // the active line leads the cascade.
                if (springAnchorPrev != Integer.MIN_VALUE) {
                    cascadeDir = (anchorIdx > springAnchorPrev) ? 1 : -1;
                    // Big seek: suspend the per-line spring for this switch and ease
                    // the whole column over with the simple global scroll spring (the
                    // same animation the spring-off option uses), then hand back to
                    // the per-line spring. A long per-line spring would animate the
                    // lines overlapping the old window while freshly-revealed lines
                    // just appear — the half-animate/half-flash mix.
                    if (Math.abs(anchorIdx - springAnchorPrev) > SNAP_JUMP_LINES
                            && springAnchorPrev >= 0 && springAnchorPrev < n) {
                        bigSeekEasing = true;
                        // Seed the global spring at the column's current scroll so the
                        // ease starts where we are instead of jumping.
                        scrollAnim.setValue(centerY + lineTops[springAnchorPrev] - lineCurTop[springAnchorPrev]);
                    }
                }
                springAnchorPrev = anchorIdx;
                springAnchorChangeNs = nowNs;
            }
            springDt = (nowNs - springLastNs) / 1_000_000_000.0;
            if (springDt > 0.05) springDt = 0.05;
            if (springDt < 0.0) springDt = 0.0;
            springLastNs = nowNs;
        }
        double sinceAnchorChange = (nowNs - springAnchorChangeNs) / 1_000_000_000.0;

        // Global scroll spring: drives the rigid fallback (spring off) AND the
        // big-seek ease (spring on). Animated every frame so it stays warm; when a
        // big seek is easing, the column rides it until it settles, then the per-line
        // spring takes back over from the synced positions.
        boolean rigidMode = !spring || bigSeekEasing;

        // A big position jump (progress-bar seek) cancels manual scroll so the column
        // snaps back to following the play head via the normal ease.
        if (userScrollActive && userScrollPrevAnchor != Integer.MIN_VALUE
                && Math.abs(anchorIdx - userScrollPrevAnchor) > SNAP_JUMP_LINES) {
            userScrollActive = false;
            userDragging = false;
            userFling = false;
            userReturning = false;
            scrollAnim.setValue(lastScrollY);
        }
        userScrollPrevAnchor = anchorIdx;

        float scrollY;
        if (userScrollActive) {
            // Hand-controlled: move the whole column rigidly to the user's offset (or
            // the scrollAnim ease while returning); the highlight keeps tracking pos.
            rigidMode = true;
            scrollY = stepUserScroll(targetScroll, nowNs, anchorIdx);
        } else {
            scrollY = (float) scrollAnim.animate(targetScroll);
            if (bigSeekEasing && Math.abs(scrollY - targetScroll) < 0.5f) {
                bigSeekEasing = false;
            }
        }
        lastScrollY = scrollY;

        // Dynamic scroll-spring tuning (AMLL): steady during an interlude, else
        // stiffer the faster lines are arriving (shorter gap to the previous line).
        double scrollStiffness;
        double scrollDamping;
        if (inInterlude) {
            scrollStiffness = SCROLL_STIFFNESS_INTERLUDE;
            scrollDamping = SCROLL_DAMPING_INTERLUDE;
        } else {
            LineGroup prevG = (activeGroupIndex > 0) ? groups.get(activeGroupIndex - 1) : null;
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
            LineGroup myGroup = groups.get(lineToGroup[i]);

            float activeK = computeActiveK(positionMs, myGroup);

            LyricLine.VocalChannel ch = line.vocalChannel;
            boolean isBg = isBackground(ch);
            boolean alignRight = ch == LyricLine.VocalChannel.DUET_RIGHT
                    || ch == LyricLine.VocalChannel.BACKGROUND_RIGHT;

            Font font = isBg ? bgFont : lyricFont;
            float rowHeight = isBg ? rowHeightBg : rowHeightLyric;
            float descent = isBg ? bgDescent : lyricDescent;
            float ascent = isBg ? bgAscent : lyricAscent;
            float[] lineSylW = sylWidths[i];
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
                float bgScaleK = computeBgScaleK(positionMs, myGroup);
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
                int from = starts[r];
                int to = (r + 1 < starts.length) ? starts[r + 1] : line.syllables.size();

                float rowWidth = sumWidths(lineSylW, from, to);
                float rowX = alignRight
                        ? Math.max(leftX, leftX + columnWidth - rowWidth)
                        : leftX;

                float wrapRowH = (r == 0) ? rowHeight : (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                float rowBaselineY = lineYTop + rowHeight + r * wrapRowH - descent - 4f;
                // Per-syllable lift gates on the SOURCE type, not the
                // line. A YRC song with an occasional one-字 line should
                // still lift that line; an LRC song should never lift.
                // {@link #hasPerSyllableTiming} is computed once at
                // setLyrics time so the lift amplitude is consistent
                // across every line of the same song.
                boolean enableLift = hasPerSyllableTiming;
                drawSyllableRange(line.syllables, lineSylW, from, to, rowX, rowBaselineY, font,
                        ascent, descent, positionMs, baseAlpha, activeK, enableLift, spring, glowOn);

                if (rowWidth > maxRowWidth) {
                    maxRowWidth = rowWidth;
                    maxRowRightX = rowX + rowWidth;
                }
            }

            // Sub-lines anchor to the lyric block's right edge (right-align)
            // or to leftX (left-align). Y must match the wrapped block's real
            // stacked height (first row full, extra rows at the wrap height) —
            // using subRowCount*rowHeight overshoots and pushes translation /
            // romaji too far below a multi-row line.
            float subY = lineYTop + rowHeight
                    + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap) + 4f
                    + (subRowCount > 1 ? WRAP_SUB_GAP : 0f);
            subY = drawSubline(leftX, subFont, subLineHeight, showRomaji, i, alignRight, baseAlpha, maxRowRightX, subY, cachedRomajiRows);
            subY = drawSubline(leftX, subFont, subLineHeight, showTranslation, i, alignRight, baseAlpha, maxRowRightX, subY, cachedTranslationRows);

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
            LineGroup interludeNext = groups.get(interludeNextGroup);
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
                        : centerY + lineTops[nf] - (spring && !userScrollActive ? targetScroll : scrollY);
                // Centre the dots between the two LINES OF TEXT, not the slot edges.
                // The slot top (nextTop - slotH) sits at the previous line's bottom,
                // but the next line's text starts nextTextOffset below its slot top
                // (line-height leaves that gap above the glyphs). Without accounting
                // for it the dots hug the previous line and drift with line spacing.
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

    private float drawSubline(float leftX, Font subFont, float subLineHeight, boolean showRomaji, int i, boolean alignRight, float baseAlpha, float maxRowRightX, float subY, String[][] cachedRomajiRows) {
        String[] romajiRows = cachedRomajiRows[i];
        if (romajiRows != null && showRomaji) {
            for (String romajiRow : romajiRows) {
                drawSubLine(romajiRow, leftX, maxRowRightX, subY, subFont,
                        baseAlpha * 0.75f, alignRight);
                subY += subLineHeight;
            }
        }
        return subY;
    }

    /**
     * Time-driven height of the interlude dot slot. Smoothstep-ramps
     * up over 150 ms starting AT the gap (no pre-lead — anticipating
     * the gap made the active line drift up before its sung phase
     * actually ended). Holds full height through the body. Collapses
     * over 150 ms ending at the trimmed gap end.
     */
    private static float computeInterludeSlot(long positionMs, long prevEnd, long currStart) {
        long lead = 150L;
        long trail = 150L;
        if (positionMs < prevEnd || positionMs > currStart) return 0f;
        float t;
        long inside = positionMs - prevEnd;
        if (inside < lead) {
            t = inside / (float) lead;
        } else if (currStart - positionMs < trail) {
            t = (currStart - positionMs) / (float) trail;
        } else {
            t = 1f;
        }
        // Smoothstep — same Hermite curve as our smoothstep helper.
        t = Math.max(0f, Math.min(1f, t));
        float eased = t * t * (3f - 2f * t);
        return INTERLUDE_DOTS_ROW_H * eased;
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

    /**
     * Word-aware greedy wrapper over syllables. Returns the starting syllable
     * index of each sub-row plus a trailing sentinel (= syllables.size()).
     *
     * <p>Per-syllable (YRC) lyrics split a word into several timed syllables, so
     * breaking at any syllable boundary would tear a word across two rows. A break
     * is only taken at a WORD boundary (whitespace at the junction, or a CJK char
     * on either side); on overflow mid-word we back up to the word's start and move
     * the whole word down. A single word wider than the column is the one case that
     * still breaks mid-word (otherwise it could never fit) — it clips at the edge.
     */
    private static int[] wrapStarts(List<Syllable> syls, float[] sylWidths, float maxW) {
        int sz = sylWidths.length;
        if (sz == 0) return new int[]{0, 0};

        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        int rowStart = 0;
        int wordStart = 0;   // start of the current word within the row (latest break point)
        float curW = 0f;
        for (int i = 0; i < sz; i++) {
            if (i > rowStart && canBreakBefore(syls, i)) wordStart = i;
            if (i > rowStart && curW + sylWidths[i] > maxW) {
                int breakAt = (wordStart > rowStart) ? wordStart : i;
                starts.add(breakAt);
                rowStart = breakAt;
                wordStart = breakAt;
                curW = 0f;
                for (int j = rowStart; j <= i; j++) curW += sylWidths[j];
                continue;
            }
            curW += sylWidths[i];
        }
        starts.add(sz);
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        return arr;
    }

    /**
     * Whether a row may break before syllable {@code i}: only at a real word
     * boundary — whitespace at the junction, or a CJK character on either side
     * (CJK breaks per character). Latin syllables of one word (no space between)
     * return false so the word stays intact.
     */
    private static boolean canBreakBefore(List<Syllable> syls, int i) {
        String prev = syls.get(i - 1).text;
        String cur = syls.get(i).text;
        if (prev == null || prev.isEmpty() || cur == null || cur.isEmpty()) return true;
        char last = prev.charAt(prev.length() - 1);
        char first = cur.charAt(0);
        if (Character.isWhitespace(last) || Character.isWhitespace(first)) return true;
        return isWrapCjk(last) || isWrapCjk(first);
    }

    /**
     * Set the cached {@link Font} to a configuration that allows smooth
     * fractional Y movement:
     * <ul>
     *   <li>{@code baselineSnapped=false} — without this Skia rounds every
     *       drawString y to the nearest integer pixel, defeating sub-pixel
     *       lift animation entirely.</li>
     *   <li>{@code subpixel=true} — glyph positions are reported as
     *       fractional and the rasterizer produces sub-pixel-accurate AA
     *       masks instead of grid-fit ones.</li>
     *   <li>{@code hinting=NONE} — keeps glyph outlines untouched so they
     *       don't snap to the grid when y changes by less than 1px.</li>
     *   <li>{@code edging=SUBPIXEL_ANTI_ALIAS} — best AA quality.</li>
     * </ul>
     * The {@link Fonts} cache returns the same {@code Font} instance for a
     * given (face,size) pair, so calling this every frame is cheap and
     * idempotent (set-to-same-value is a no-op in skia).
     */
    private static void configureForAnimation(Font f) {
        f.setBaselineSnapped(false);
        f.setSubpixel(true);
        f.setHinting(FontHinting.NONE);
        f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
    }

    private static boolean isBackground(LyricLine.VocalChannel ch) {
        return ch == LyricLine.VocalChannel.BACKGROUND
                || ch == LyricLine.VocalChannel.BACKGROUND_LEFT
                || ch == LyricLine.VocalChannel.BACKGROUND_RIGHT;
    }

    /**
     * Per-character measurement so the measured total matches what
     * {@link #drawSyllableRange} actually advances. Using {@code measureTextWidth}
     * on the whole string yields a slightly smaller total because of
     * kerning, which makes right-aligned positions jitter on the active line.
     */
    private static float perCharWidth(String text, Font font) {
        float w = 0f;
        for (int i = 0; i < text.length(); i++) {
            w += font.measureTextWidth(String.valueOf(text.charAt(i)));
        }
        return w;
    }

    // Hangul: Syllables + Jamo Extended-B (AC00-D7FF), Jamo (1100-11FF), Compatibility
    // Jamo (3130-318F), Jamo Extended-A (A960-A97F). The bundled PingFang face has none.
    private static boolean needsKorean(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7FF) || (c >= 0x1100 && c <= 0x11FF)
                    || (c >= 0x3130 && c <= 0x318F) || (c >= 0xA960 && c <= 0xA97F)) {
                return true;
            }
        }
        return false;
    }

    // The Korean fallback face at `base`'s size when `text` contains Hangul (and the
    // platform ships one), else `base`. Measure and draw call this with the same
    // (text, base), so cached syllable widths and drawn advances stay aligned.
    private static Font fontForText(String text, Font base) {
        if (!needsKorean(text)) return base;
        // Fonts.korean returns a cache-owned, cross-frame Font — borrowed, not owned
        // here, so it must NOT be closed (try-with-resources would free it mid-cache).
        // noinspection resource
        Font ko = Fonts.korean(base.getSize());
        return ko != null ? ko : base;
    }

    // ---- Manual scroll touch API (called on the render/GL thread) -------------

    /**
     * Finger down on the lyric column: take over scrolling from the current position.
     */
    public void scrollDown(float y) {
        userScrollActive = true;
        userDragging = true;
        userFling = false;
        userReturning = false;
        // Start from the column's current position, but inside the scroll bounds so a
        // grab at the song's very end (where the follow centres the last line, past the
        // bottom bound) doesn't jump mid-drag.
        userScroll = clampScroll(lastScrollY);
        userFlingVel = 0f;
        dragSampleCount = 0;
        addDragSample(y);
        userLastInteractNs = System.nanoTime();
    }

    /**
     * Drag: the column follows the finger 1:1 (content moves opposite finger).
     */
    public void scrollMove(float y) {
        if (!userDragging || dragSampleCount == 0) return;
        float prevY = dragSampleY[dragSampleCount - 1];
        userScroll = clampScroll(userScroll - (y - prevY));
        addDragSample(y);
        userLastInteractNs = System.nanoTime();
    }

    /**
     * Release: coast with the windowed release velocity (engine-style inertia).
     */
    public void scrollUp() {
        if (!userDragging) return;
        userDragging = false;
        userFlingVel = computeFlingVel();
        userFling = Math.abs(userFlingVel) > SCROLL_MIN_FLING;
        userScrollLastNs = System.nanoTime();
        userLastInteractNs = userScrollLastNs;
    }

    public void scrollCancel() {
        scrollUp();
    }

    /**
     * Start time (ms) of the lyric line under a tapped screen y, or -1 if the tap landed
     * in the blank run-out beyond the first/last line. Uses the last frame's geometry.
     */
    public long timeAtScreenY(float screenY) {
        int n = lines.size();
        if (n == 0 || lineTopsBuf.length < n
                || cachedLineHeights == null || cachedLineHeights.length < n) {
            return -1L;
        }
        float contentY = screenY - lastCenterY + lastScrollY;
        if (contentY < lineTopsBuf[0]
                || contentY >= lineTopsBuf[n - 1] + cachedLineHeights[n - 1]) {
            return -1L;
        }
        int i = lineIndexAt(lineTopsBuf, n, contentY);
        LyricLine line = lines.get(i);
        if (line.syllables.isEmpty()) return -1L;
        return line.syllables.get(0).startMs;
    }

    private void addDragSample(float y) {
        if (dragSampleCount == SCROLL_VEL_SAMPLES) {
            // Left-shift to drop the oldest sample. The overlapping src/dest ranges are
            // safe: System.arraycopy is specified to copy via a temp array when src == dest.
            // noinspection all
            System.arraycopy(dragSampleNs, 1, dragSampleNs, 0, SCROLL_VEL_SAMPLES - 1);
            // noinspection all
            System.arraycopy(dragSampleY, 1, dragSampleY, 0, SCROLL_VEL_SAMPLES - 1);
            dragSampleCount--;
        }
        dragSampleNs[dragSampleCount] = System.nanoTime();
        dragSampleY[dragSampleCount] = y;
        dragSampleCount++;
    }

    // Content velocity (px/s) = -(finger displacement)/(elapsed) across the newest
    // sample back to the oldest within SCROLL_VEL_WINDOW. Same windowed estimate the
    // engine's Flickable uses, so a jittery final sample can't reverse the fling.
    private float computeFlingVel() {
        if (dragSampleCount < 2) return 0f;
        long newest = dragSampleNs[dragSampleCount - 1];
        int oldest = dragSampleCount - 1;
        for (int i = dragSampleCount - 1; i >= 0; i--) {
            if ((newest - dragSampleNs[i]) / 1_000_000_000f > SCROLL_VEL_WINDOW) break;
            oldest = i;
        }
        float dt = (newest - dragSampleNs[oldest]) / 1_000_000_000f;
        if (dt < 0.001f) return 0f;
        return -(dragSampleY[dragSampleCount - 1] - dragSampleY[oldest]) / dt;
    }

    private float clampScroll(float v) {
        if (v < scrollMin) return scrollMin;
        return Math.min(v, scrollMax);
    }

    // The line whose top is at/above a content-space scroll offset (the line sitting at
    // the centre line for that offset) — binary search since lineTops is increasing.
    private static int lineIndexAt(float[] lineTops, int n, float scroll) {
        if (n <= 1) return 0;
        int lo = 0, hi = n - 1, res = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineTops[mid] <= scroll) {
                res = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return res;
    }

    // Advance the user-controlled scroll for this frame and return the column offset.
    // Drag holds; fling coasts under SCROLL_DECEL; once idle past SCROLL_IDLE_RETURN_NS
    // the next line change arms the return, eased back through scrollAnim (the seek
    // spring) so the snap-back matches the progress-adjust motion exactly.
    private float stepUserScroll(float targetScroll, long nowNs, int anchorIdx) {
        if (userDragging) {
            userHoldAnchor = anchorIdx;
            userScrollLastNs = nowNs;
            return userScroll;
        }
        if (userFling) {
            userHoldAnchor = anchorIdx;
            float dt = (nowNs - userScrollLastNs) / 1_000_000_000f;
            userScrollLastNs = nowNs;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) {
                float next = userScroll + userFlingVel * dt;
                userScroll = clampScroll(next);
                if (userScroll != next) userFlingVel = 0f;   // hit an edge
                else userFlingVel = decayVel(userFlingVel, dt);
                if (Math.abs(userFlingVel) < SCROLL_MIN_FLING) userFling = false;
            }
            return userScroll;
        }
        if (userReturning) {
            float scrollY = (float) scrollAnim.animate(targetScroll);
            if (Math.abs(scrollY - targetScroll) < 0.5f) {
                userReturning = false;
                userScrollActive = false;
            }
            return scrollY;
        }
        // Idle hold: wait until the song moves to a new line, then ease back.
        if ((nowNs - userLastInteractNs) > SCROLL_IDLE_RETURN_NS && anchorIdx != userHoldAnchor) {
            userReturning = true;
            scrollAnim.setValue(userScroll);
            return (float) scrollAnim.animate(targetScroll);
        }
        return userScroll;
    }

    private static float decayVel(float v, float dt) {
        float d = SCROLL_DECEL * dt;
        if (v > 0f) return Math.max(0f, v - d);
        return Math.min(0f, v + d);
    }

    /**
     * Sum cached per-syllable widths over {@code [from, to)} — no measuring, no alloc.
     */
    private static float sumWidths(float[] sylWidths, int from, int to) {
        float w = 0f;
        for (int i = from; i < to; i++) w += sylWidths[i];
        return w;
    }

    /**
     * Greedy width-wrap of a plain sub-line (romaji / translation) into rows that fit
     * {@code maxWidth}. Breaks at spaces when present (latin / romaji), else at the
     * character boundary (CJK translations have no spaces). Called only from the cached
     * layout pass, so the per-row allocation is not on the per-frame path.
     */
    private static String[] wrapText(String text, Font font, float maxWidth) {
        if (text == null || text.isEmpty()) return new String[]{""};
        font = fontForText(text, font);
        if (font.measureTextWidth(text) <= maxWidth) return new String[]{text};
        java.util.List<String> rows = new java.util.ArrayList<>();
        int n = text.length();
        int lineStart = 0;
        int lastSpace = -1;
        float w = 0f;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            float cw = font.measureTextWidth(String.valueOf(c));
            if (c == ' ') lastSpace = i;
            if (w + cw > maxWidth && i > lineStart) {
                int breakAt = lastSpace > lineStart ? lastSpace : i;
                int nextStart = lastSpace > lineStart ? lastSpace + 1 : i;
                rows.add(text.substring(lineStart, breakAt));
                lineStart = nextStart;
                lastSpace = -1;
                i = nextStart;
                w = 0f;
                continue;
            }
            w += cw;
            i++;
        }
        if (lineStart < n) rows.add(text.substring(lineStart));
        return rows.toArray(new String[0]);
    }

    private static void drawSubLine(String text, float leftX, float rightAnchorX, float y,
                                    Font font, float alpha, boolean alignRight) {
        font = fontForText(text, font);
        float x = alignRight ? rightAnchorX - font.measureTextWidth(text) : leftX;
        Paint paint = LyricSkia.scratchPaint();
        paint.setColor(0xFFE6E6E6);
        paint.setAlphaf(alpha);
        paint.setAntiAlias(true);
        LyricSkia.getCanvas().drawString(text, x, y, font, paint);
    }

    private int findActiveGroup(long pos) {
        int lo = 0, hi = groups.size() - 1, res = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (groups.get(mid).startMs <= pos) {
                res = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return res;
    }

    /**
     * Draw a sub-row, AMLL fidelity port.
     *
     * <p>Three things happen per row:
     * <ol>
     *   <li><b>Per-syllable lift</b> (AMLL {@code initFloatAnimation}).
     *       {@code translateY 0 → -0.05em} with {@code ease-out}, over
     *       {@code max(1000ms, sylDur)}. Stays at peak afterwards
     *       (fill-forwards). Scaled by {@code activeK} so the lift
     *       smoothly returns to 0 during the group's fade-out.</li>
     *   <li><b>Row-wide alpha sweep</b> (AMLL {@code mask-image} +
     *       animated {@code mask-position}). A horizontal gradient inside
     *       a saveLayer: bright on the left of the sweep head, dim
     *       ({@code DARK_MASK_ALPHA = 0.2}) on the right, blending over
     *       {@code SWEEP_FADE_PX}. The head moves between syllable left
     *       edges in proportion to time, so a row reads as a horizontal
     *       progress fill rather than per-syllable alpha steps.</li>
     *   <li><b>SaveLayer composite alpha</b>. The whole layer is blended
     *       back with {@code baseAlpha}, which the caller already faded
     *       between idle (0.42) and active (1.0) using {@code activeK}.
     *       So a finishing group's row dims smoothly without the mask
     *       sweep snapping anything.</li>
     * </ol>
     *
     * <p>{@code activeK ≤ 0.001} short-circuits to a flat per-syllable
     * draw — past the group's fade-out window, no animation work needed.
     */
    private void drawSyllableRange(List<Syllable> syllables, float[] sylWidths, int from, int to,
                                   float startX, float baselineY, Font font,
                                   float ascent, float descent, long pos,
                                   float baseAlpha, float activeK, boolean enableLift, boolean spring,
                                   boolean glowOn) {
        if (from >= to) return;
        Canvas canvas = LyricSkia.getCanvas();

        if (activeK <= 0.001f) {
            float x = startX;
            Paint paint = LyricSkia.scratchPaint();
            paint.setColor(0xFFFFFFFF);
            paint.setAlphaf(baseAlpha);
            paint.setAntiAlias(true);
            for (int i = from; i < to; i++) {
                String st = syllables.get(i).text;
                canvas.drawString(st, x, baselineY, fontForText(st, font), paint);
                x += sylWidths[i];
            }
            return;
        }

        // Pre-compute syllable left edges + row geometry.
        int n = to - from;
        if (sylLeftBuf.length < n + 1) sylLeftBuf = new float[n + 1];
        float[] sylLeft = sylLeftBuf;
        sylLeft[0] = startX;
        for (int i = 0; i < n; i++) {
            sylLeft[i + 1] = sylLeft[i] + sylWidths[from + i];
        }
        float rowRightX = sylLeft[n];
        if (rowRightX - startX <= 0f) return;

        // Line-level (LRC) lyrics have no real per-syllable timing — the
        // karaoke sweep over the same-timed tokens would read as a fake
        // per-character wipe. Gate the sweep (and lift) on per-syllable timing;
        // without it the line just lights up as a whole via baseAlpha.
        float sweepX = enableLift ? computeSweepX(syllables, from, to, sylLeft, pos) : 0f;

        // Layer bounds wide enough for the peak lift + glyph descender, plus the
        // white glow blur radius (~8px) so the glow isn't clipped at row edges. The
        // float overload avoids allocating a Rect per active row per frame (the
        // last per-frame allocation on the lyric draw path).
        Paint layerPaint = lyricLayerPaint;
        layerPaint.setAlphaf(baseAlpha);
        canvas.saveLayer(
                startX - 8f,
                baselineY + ascent - LIFT_PEAK_PX - 8f,
                rowRightX + 8f,
                baselineY + descent + 8f,
                layerPaint);
        try {
            // Per-syllable lift + glow alpha, computed once (the spring is non-trivial)
            // and reused by the glow pass and the text pass.
            if (liftBuf.length < n) liftBuf = new float[n];
            if (glowAlphaBuf.length < n) glowAlphaBuf = new float[n];
            boolean anyGlow = false;
            for (int s = 0; s < n; s++) {
                Syllable syl = syllables.get(from + s);
                long sStart = syl.startMs;

                // Lift progress k: a soft spring step (Apple liftSpring) when spring
                // physics is on, else the fixed cubic ease-out over max(1s, dur).
                float k;
                if (spring) {
                    k = liftSpringK((pos - sStart) / 1000.0);
                } else {
                    long ownEnd = sStart + Math.max(0L, syl.durationMs);
                    long effDur = Math.max(LIFT_MIN_DURATION_MS, ownEnd - sStart);
                    float t;
                    if (pos <= sStart) t = 0f;
                    else if (pos >= sStart + effDur) t = 1f;
                    else t = (pos - sStart) / (float) effDur;
                    k = 1f - (1f - t) * (1f - t) * (1f - t);
                }
                liftBuf[s] = enableLift ? -LIFT_PEAK_PX * k * activeK : 0f;
                float ga = (enableLift && glowOn) ? activeK * k * GLOW_ALPHA : 0f;
                glowAlphaBuf[s] = ga;
                if (ga > 0.01f) anyGlow = true;
            }

            // Glow pass: draw the sung glyphs into a single blurred layer for the whole
            // row, so N syllables cost one blur instead of N.
            if (anyGlow) {
                canvas.saveLayer(
                        startX - 8f,
                        baselineY + ascent - LIFT_PEAK_PX - 8f,
                        rowRightX + 8f,
                        baselineY + descent + 8f,
                        glowLayerPaint);
                glowGlyphPaint.setColor(0xFFFFFFFF);
                for (int s = 0; s < n; s++) {
                    if (glowAlphaBuf[s] <= 0.01f) continue;
                    String st = syllables.get(from + s).text;
                    glowGlyphPaint.setAlphaf(glowAlphaBuf[s]);
                    canvas.drawString(st, sylLeft[s], baselineY + liftBuf[s], fontForText(st, font), glowGlyphPaint);
                }
                canvas.restore();
            }

            Paint textPaint = LyricSkia.scratchPaint();
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setAlphaf(1f);
            textPaint.setAntiAlias(true);
            for (int s = 0; s < n; s++) {
                String st = syllables.get(from + s).text;
                canvas.drawString(st, sylLeft[s], baselineY + liftBuf[s], fontForText(st, font), textPaint);
            }

            // Smooth horizontal sweep (DST_IN): bright on the sung side, dark on the
            // unsung side, blending over SWEEP_FADE_PX at the head.
            if (enableLift) {
                float maskDark = 1f - (1f - DARK_MASK_ALPHA) * activeK;
                applySweepMask(canvas, sweepX, maskDark);
            }
        } finally {
            canvas.restore();
        }
    }

    /**
     * Park sweepX outside the row before/after the line plays so the mask
     * presents a stable bright (post-finish) or dark (pre-start) state.
     * During playback, sweepX lerps between consecutive syllables' left
     * edges by {@code (pos - sStart) / (nextSStart - sStart)} — gives a
     * continuous head that never pauses at syllable boundaries.
     */
    private static float computeSweepX(List<Syllable> syllables, int from, int to,
                                       float[] sylLeft, long pos) {
        int n = to - from;
        long firstStart = syllables.get(from).startMs;
        Syllable last = syllables.get(to - 1);
        long lastEnd = last.startMs + Math.max(0L, last.durationMs);

        // Before the line: park way left so the mask is fully dark.
        if (pos < firstStart) return sylLeft[0] - SWEEP_FADE_PX * 2f;
        // After the line: park way right so the mask is fully bright.
        if (pos >= lastEnd) return sylLeft[n] + SWEEP_FADE_PX * 2f;

        for (int s = 0; s < n; s++) {
            Syllable syl = syllables.get(from + s);
            long sStart = syl.startMs;
            long sEnd = (s + 1 < n)
                    ? syllables.get(from + s + 1).startMs
                    : syl.startMs + Math.max(0L, syl.durationMs);
            if (sEnd <= sStart) sEnd = sStart + 1L;
            if (pos < sStart) return sylLeft[s];
            if (pos < sEnd) {
                float frac = (pos - sStart) / (float) (sEnd - sStart);
                float w = sylLeft[s + 1] - sylLeft[s];
                return sylLeft[s] + w * frac;
            }
        }
        return sylLeft[n] + SWEEP_FADE_PX * 2f;
    }

    /**
     * AMLL's mask-image gradient as a DST_IN draw on the current saveLayer: bright
     * (1.0) on the sung side, dark ({@code maskDark}) on the unsung side, blending
     * over {@code SWEEP_FADE_PX} at the head.
     *
     * <p>The gradient SHADER is built once for a fixed [0, SWEEP_FADE_PX] band
     * (CLAMP, so it's bright to the left and dark to the right) and reused — only
     * its dark colour changes (with activeK during the enter/exit fade), so it's
     * rebuilt only then, not every frame. Each frame the head is positioned by
     * translating the canvas, and a cached oversized rect is filled, so a steady
     * sweep allocates nothing (the old per-frame {@code makeLinearGradient} +
     * bounds Rect was the active row's residual GC churn).
     */
    private void applySweepMask(Canvas canvas, float sweepX, float maskDark) {
        if (sweepShader == null || sweepShaderDark != maskDark) {
            if (sweepShader != null) sweepShader.close();
            int dark = ((int) (maskDark * 255f) << 24) | 0x00FFFFFF;
            sweepColors[0] = 0xFFFFFFFF;
            sweepColors[1] = dark;
            sweepStops[0] = 0f;
            sweepStops[1] = 1f;
            sweepShader = Shader.makeLinearGradient(0f, 0f, SWEEP_FADE_PX, 0f, sweepColors, sweepStops);
            sweepShaderDark = maskDark;
        }
        sweepPaint.setShader(sweepShader);
        sweepPaint.setBlendMode(BlendMode.DST_IN);
        canvas.save();
        canvas.translate(sweepX - SWEEP_FADE_PX * 0.5f, 0f);
        canvas.drawRect(sweepBigRect, sweepPaint);
        canvas.restore();
        sweepPaint.setShader(null);
    }

    /**
     * Underdamped step response of Apple's liftSpring (mass 1,
     * damping 7). {@code tau} is elapsed seconds since the syl
     * returns ~0 at 0, settles toward 1 (fill-forwards) within
     * overshoot. Negative tau (syllable not started) → 0.
     * <p>
     * Integrate one line's scroll spring toward {@code target} over {@code dt}
     * seconds with the given (AMLL-derived) stiffness/damping, sub-stepping for
     * stiff-spring stability. Mirrors {@link SpringAnim} on the per-line arrays.
     */
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

    private static float liftSpringK(double tau) {
        if (tau <= 0.0) return 0f;
        double zw = LIFT_ZETA * LIFT_OMEGA0;
        double wd = LIFT_OMEGA0 * Math.sqrt(1.0 - LIFT_ZETA * LIFT_ZETA);
        double env = Math.exp(-zw * tau);
        double y = 1.0 - env * (Math.cos(wd * tau) + (zw / wd) * Math.sin(wd * tau));
        if (y < 0.0) y = 0.0;
        return (float) y;
    }

    /**
     * GLSL-style smoothstep: 0 below {@code a}, 1 above {@code b}, smooth in between.
     */
    private static float smoothstep(float a, float b, float x) {
        if (b <= a) return x < a ? 0f : 1f;
        float t = (x - a) / (b - a);
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    /**
     * BG line scale curve. The row trails the main line by
     * {@link #BG_POP_IN_DELAY_MS}, then pops in over {@link #BG_POP_IN_MS} with a
     * small easeOutBack overshoot (a gentle bounce that settles back to 1). Pop-out
     * stays a plain smoothstep collapse over {@link #BG_POP_OUT_MS}.
     */
    private static float computeBgScaleK(long positionMs, LineGroup g) {
        long popStart = g.startMs + BG_POP_IN_DELAY_MS;
        if (positionMs < popStart) return 0f;
        if (positionMs < popStart + BG_POP_IN_MS) {
            float k = (positionMs - popStart) / (float) BG_POP_IN_MS;
            return easeOutBackSmall(k);
        }
        if (positionMs < g.endMs) return 1f;
        float dt = (positionMs - g.endMs) / (float) BG_POP_OUT_MS;
        if (dt >= 1f) return 0f;
        return 1f - smoothstep(0f, 1f, dt);
    }

    // easeOutBack: rises past 1 then recoils back to it. Over the longer
    // BG_POP_IN_MS window this reads as a slow, visible bounce-back (the
    // "回弹" the user wants) rather than a quick twitch.
    private static float easeOutBackSmall(float x) {
        float c1 = 1.7f;
        float c3 = c1 + 1f;
        float t = x - 1f;
        return 1f + c3 * t * t * t + c1 * t * t;
    }

    /**
     * Group activation curve. 0 before {@code startMs - ACTIVE_FADE_IN_MS},
     * smoothsteps to 1 at {@code startMs}, holds 1 across the active
     * window, then smoothsteps back to 0 over {@code ACTIVE_FADE_OUT_MS}
     * after {@code endMs}. Used both at render time (alpha/lift/scale)
     * and at layout time (BG lineHeight collapse).
     */
    private static float computeActiveK(long positionMs, LineGroup g) {
        if (positionMs < g.startMs - ACTIVE_FADE_IN_MS) return 0f;
        if (positionMs < g.startMs) {
            float dt = (positionMs - (g.startMs - ACTIVE_FADE_IN_MS))
                    / (float) ACTIVE_FADE_IN_MS;
            return smoothstep(0f, 1f, dt);
        }
        if (positionMs < g.endMs) return 1f;
        float dt = (positionMs - g.endMs) / (float) ACTIVE_FADE_OUT_MS;
        return 1f - smoothstep(0f, 1f, dt);
    }
}
