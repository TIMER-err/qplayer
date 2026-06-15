package dev.t1m3.qplayer.android.lyric;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Point;
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
    private static final float INTERLUDE_DOTS_ROW_H = 52f;
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
    private static final float LIFT_PEAK_PX = 1.5f;
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
     * Width of the gradient mask sweep band, in pixels. AMLL derives this
     * from {@code wordHeight × wordFadeWidth}; for a 30px font that's
     * ~30px. Wider = softer transition between lit and unlit text.
     */
    private static final float SWEEP_FADE_PX = 30f;
    /**
     * Mask alpha on the unlit side. AMLL defaults to {@code --dark-mask-alpha=0.2}
     * — so an inactive syllable in the active line is 20% as opaque as a
     * sung one. Multiplies with the line's baseAlpha at composite.
     */
    private static final float DARK_MASK_ALPHA = 0.2f;
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
    private static final long BG_POP_IN_MS = 220L;
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
    private final SpringAnim scrollAnim = new SpringAnim(180.0, 28.0);

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
    private float[] lineTopsBuf = new float[0];
    private float[] interludeBuf = new float[0];
    // Reused per active line each frame (syllable left edges); was new float[n+1].
    private float[] sylLeftBuf = new float[0];
    // Reused saveLayer paint for the active row's composite alpha; was a native
    // Paint allocated per active line per frame. Kept alive for the renderer's
    // lifetime (one line's saveLayer/restore completes before the next), so the
    // "keep alive until restore" constraint below is satisfied without per-call new.
    private final io.github.humbleui.skija.Paint lyricLayerPaint = new io.github.humbleui.skija.Paint();
    /** Reusable paint for interlude dots — avoids per-frame allocation. */
    private final io.github.humbleui.skija.Paint dotPaint = new io.github.humbleui.skija.Paint();
    private List<LyricLine> layoutKeyLines;
    private int layoutKeyN;
    private int layoutKeyLyricSize;
    private int layoutKeySubSize;
    private int layoutKeyColW = -1;
    private boolean layoutKeyBold;
    private boolean layoutKeyRomaji;
    private boolean layoutKeyTranslation;

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
                if (l.syllables != null) {
                    for (Syllable s : l.syllables) {
                        if (s != null && s.text != null && !s.text.trim().isEmpty()) {
                            hasText = true;
                            break;
                        }
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
        boolean useBold = cfg.fontWeight.getValue() == LyricConfig.FontWeight.MEDIUM;

        Font lyricFont = useBold ? Fonts.getMedium(lyricFontSize) : Fonts.getRegular(lyricFontSize);
        Font subFont = useBold ? Fonts.getMedium(subFontSize) : Fonts.getRegular(subFontSize);
        Font bgFont = useBold ? Fonts.getMedium(bgFontSize) : Fonts.getRegular(bgFontSize);

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

        float rowHeightLyric = lyricFontSize * ROW_HEIGHT_RATIO;
        float rowHeightLyricWrap = lyricFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float rowHeightBg = bgFontSize * ROW_HEIGHT_RATIO;
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
                && layoutKeyBold == useBold
                && layoutKeyRomaji == showRomaji
                && layoutKeyTranslation == showTranslation;
        if (!layoutValid) {
            int[][] rowStarts = new int[n][];
            float[] lineHeights = new float[n];
            String[][] romajiRows = new String[n][];
            String[][] translationRows = new String[n][];
            for (int i = 0; i < n; i++) {
                LyricLine line = lines.get(i);
                boolean isBg = isBackground(line.vocalChannel);
                Font font = isBg ? bgFont : lyricFont;
                float rowHeight = isBg ? rowHeightBg : rowHeightLyric;

                rowStarts[i] = wrapStarts(line.syllables, font, columnWidth);
                int subRowCount = Math.max(1, rowStarts[i].length - 1);

                float lh = rowHeight + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                boolean hasSub = (line.romaji != null && showRomaji) || (line.translation != null && showTranslation);
                // Wrapped rows use the tight wrap height, so a sub-line sitting right
                // under the last row feels cramped — give it a little extra breathing
                // room (reserved here so neighbours don't overlap; drawn at subY).
                if (hasSub && subRowCount > 1) lh += WRAP_SUB_GAP;
                if (line.romaji != null && showRomaji) {
                    romajiRows[i] = wrapText(line.romaji, subFont, columnWidth);
                    lh += subLineHeight * romajiRows[i].length;
                }
                if (line.translation != null && showTranslation) {
                    translationRows[i] = wrapText(line.translation, subFont, columnWidth);
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
            layoutKeyLines = lines;
            layoutKeyN = n;
            layoutKeyLyricSize = lyricFontSize;
            layoutKeySubSize = subFontSize;
            layoutKeyColW = colW;
            layoutKeyBold = useBold;
            layoutKeyRomaji = showRomaji;
            layoutKeyTranslation = showTranslation;
        }
        int[][] rowStarts = cachedRowStarts;
        float[] lineHeights = cachedLineHeights;

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

        // Cumulative tops = stacked cached heights + the per-frame interlude slots.
        if (lineTopsBuf.length != n) lineTopsBuf = new float[n];
        float[] lineTops = lineTopsBuf;
        for (int i = 0; i < n; i++) {
            float prevBottom = i == 0 ? 0f : lineTops[i - 1] + lineHeights[i - 1];
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
                latestActiveGroup = gi;
            } else {
                latestActiveGroup = gi;
            }
        }
        // If nothing is currently buffered (we're in a between-line gap),
        // fall back to the latest already-passed group so scroll keeps
        // sitting on the last sung line until the next phrase starts.
        if (anchorGroup < 0) anchorGroup = latestActiveGroup;
        activeGroupIndex = anchorGroup;

        LineGroup activeGroup = (activeGroupIndex >= 0 && activeGroupIndex < groups.size())
                ? groups.get(activeGroupIndex) : null;

        // Scroll target = the active group's MAIN LINE centre (ignoring
        // BG height). Putting the main centre at ALIGN_POSITION keeps the
        // sung lyric pinned at 35% regardless of whether its BG is
        // currently popped open, collapsed, or animating in-between.
        // Using group centre instead would slide the main line up/down
        // as the BG inflates, defeating the BG-doesn't-affect-scroll
        // requirement.
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
            targetScroll = (mTop + mBottom) * 0.5f;
        }

        // Interlude detection covers THREE shapes:
        //   1. Intro: positionMs < groups[0].startMs, gap = [0, group[0].start)
        //   2. Between groups: activeGroup just finished, gap to next
        //   3. Outro: after last group — no dots (no "next" to anchor to)
        // End trimmed by INTERLUDE_TRAIL_TRIM_MS so the dots collapse a
        // moment before the next line sings.
        LineGroup nextGroup = null;
        long gapStart = -1L;
        if (activeGroup == null && groups.size() > 0
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
        float scrollY = (float) scrollAnim.animate(targetScroll);
        float centerY = topY + columnHeight * ALIGN_POSITION;

        int anchorIdx = activeGroup != null ? activeGroup.from : 0;
        int start = Math.max(0, anchorIdx - VISIBLE_RADIUS);
        int end = Math.min(n, anchorIdx + VISIBLE_RADIUS + 1);

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
            // baseAlpha interpolates idle ↔ active so the line's overall
            // brightness rises/falls with the group transition rather
            // than snapping at the boundary.
            float idleBase = isBg ? 0.32f : 0.42f;
            float activeBase = isBg ? 0.70f : 1f;
            float baseAlpha = idleBase + (activeBase - idleBase) * activeK;

            // Top of this line's first sub-row in screen space. For BG
            // lines this gets overridden below to sit flush against the
            // main+sub block — lineTops[bgIdx] is exactly the same Y
            // (BG lineHeight = 0) but reading the explicit anchor makes
            // the intent obvious in the BG transform path.
            float lineYTop = centerY + lineTops[i] - scrollY;

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
            boolean bgTransform = isBg;
            if (bgTransform) {
                float bgScaleK = computeBgScaleK(positionMs, myGroup);
                if (bgScaleK < BG_VISIBLE_THRESHOLD) continue;
                float anchorX = alignRight ? (leftX + columnWidth) : leftX;
                float anchorY = lineYTop;
                float scale = BG_SCALE_IDLE + (1f - BG_SCALE_IDLE) * bgScaleK;
                canvas.save();
                canvas.translate(anchorX, anchorY);
                canvas.scale(scale, scale);
                canvas.translate(-anchorX, -anchorY);
            }

            for (int r = 0; r < subRowCount; r++) {
                int from = starts[r];
                int to = (r + 1 < starts.length) ? starts[r + 1] : line.syllables.size();

                float rowWidth = measureRange(line.syllables, from, to, font);
                float rowX = alignRight
                        ? Math.max(leftX, leftX + columnWidth - rowWidth)
                        : leftX;

                float wrapRowH = (r == 0) ? rowHeight : (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                float rowBaselineY = lineYTop + rowHeight + r * wrapRowH - font.getMetrics().getDescent() - 4f;
                // Per-syllable lift gates on the SOURCE type, not the
                // line. A YRC song with an occasional one-字 line should
                // still lift that line; an LRC song should never lift.
                // {@link #hasPerSyllableTiming} is computed once at
                // setLyrics time so the lift amplitude is consistent
                // across every line of the same song.
                boolean enableLift = hasPerSyllableTiming;
                drawSyllableRange(line.syllables, from, to, rowX, rowBaselineY, font,
                        positionMs, baseAlpha, activeK, enableLift);

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
            String[] romajiRows = cachedRomajiRows[i];
            if (romajiRows != null && showRomaji) {
                for (int r = 0; r < romajiRows.length; r++) {
                    drawSubLine(romajiRows[r], leftX, maxRowRightX, subY, subFont,
                            baseAlpha * 0.75f, alignRight);
                    subY += subLineHeight;
                }
            }
            String[] translationRows = cachedTranslationRows[i];
            if (translationRows != null && showTranslation) {
                for (int r = 0; r < translationRows.length; r++) {
                    drawSubLine(translationRows[r], leftX, maxRowRightX, subY, subFont,
                            baseAlpha * 0.75f, alignRight);
                    subY += subLineHeight;
                }
            }

            if (bgTransform) {
                canvas.restore();
            }
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
                float dotsTop = centerY + lineTops[interludeNext.from] - slotH - scrollY;
                float anchorY = dotsTop + (slotH * 0.5f - INTERLUDE_DOT_RADIUS); // vertical centre - dot radius
                renderInterludeDots(canvas, leftX, anchorY,
                        positionMs - interludeStartMs, interludeDur);
            }
        }
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
        long openAt = prevEnd;
        long closeAt = currStart;
        if (positionMs < openAt || positionMs > closeAt) return 0f;
        float t;
        long inside = positionMs - openAt;
        long total = closeAt - openAt;
        if (inside < lead) {
            t = inside / (float) lead;
        } else if (closeAt - positionMs < trail) {
            t = (closeAt - positionMs) / (float) trail;
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
        if (v > hi) return hi;
        return v;
    }

    /**
     * Greedy syllable-boundary wrapper. Returns the starting syllable index
     * of each sub-row plus a trailing sentinel (= syllables.size()) so callers
     * can iterate {@code [starts[r], starts[r+1])} without bounds tricks.
     *
     * <p>A single syllable wider than {@code maxW} still occupies its own
     * sub-row (we don't split syllables — that would break the per-syllable
     * sweep + lift timing model). Such a syllable visually clips at the
     * column edge; the surrounding panel clipRect saves it from spilling
     * over the cover or the controls.
     */
    private static int[] wrapStarts(List<Syllable> syllables, Font font, float maxW) {
        int sz = syllables.size();
        if (sz == 0) return new int[]{0, 0};

        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        float curW = 0f;
        for (int i = 0; i < sz; i++) {
            float sw = perCharWidth(syllables.get(i).text, font);
            if (i > 0 && curW + sw > maxW) {
                starts.add(i);
                curW = 0f;
            }
            curW += sw;
        }
        starts.add(sz);
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        return arr;
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
     * {@link #drawSyllable} actually advances. Using {@code measureTextWidth}
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

    private static float measureRange(List<Syllable> syllables, int from, int to, Font font) {
        float w = 0f;
        for (int i = from; i < to; i++) {
            w += perCharWidth(syllables.get(i).text, font);
        }
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
    private void drawSyllableRange(List<Syllable> syllables, int from, int to,
                                   float startX, float baselineY, Font font, long pos,
                                   float baseAlpha, float activeK, boolean enableLift) {
        if (from >= to) return;
        Canvas canvas = LyricSkia.getCanvas();

        if (activeK <= 0.001f) {
            float x = startX;
            for (int i = from; i < to; i++) {
                Syllable s = syllables.get(i);
                Paint paint = LyricSkia.scratchPaint();
                paint.setColor(0xFFFFFFFF);
                paint.setAlphaf(baseAlpha);
                paint.setAntiAlias(true);
                canvas.drawString(s.text, x, baselineY, font, paint);
                x += perCharWidth(s.text, font);
            }
            return;
        }

        // Pre-compute syllable left edges + row geometry.
        int n = to - from;
        if (sylLeftBuf.length < n + 1) sylLeftBuf = new float[n + 1];
        float[] sylLeft = sylLeftBuf;
        sylLeft[0] = startX;
        for (int i = 0; i < n; i++) {
            sylLeft[i + 1] = sylLeft[i] + perCharWidth(syllables.get(from + i).text, font);
        }
        float rowRightX = sylLeft[n];
        if (rowRightX - startX <= 0f) return;

        // Line-level (LRC) lyrics have no real per-syllable timing — the
        // karaoke sweep over the same-timed tokens would read as a fake
        // per-character wipe. Gate the sweep (and lift) on per-syllable timing;
        // without it the line just lights up as a whole via baseAlpha/activeK.
        boolean animate = enableLift;
        float sweepX = animate ? computeSweepX(syllables, from, to, sylLeft, pos) : 0f;

        // Layer bounds wide enough for the peak lift + glyph descender.
        float ascent = font.getMetrics().getAscent();   // negative
        float descent = font.getMetrics().getDescent(); // positive
        Rect layerBounds = Rect.makeLTRB(
                startX - 4f,
                baselineY + ascent - LIFT_PEAK_PX - 4f,
                rowRightX + 4f,
                baselineY + descent + 4f);

        // saveLayer with the row's composite alpha. Restore will blend
        // the masked layer back into the main canvas at baseAlpha.
        //
        // Lifecycle warning: Skia's saveLayer keeps a *pointer* (not a
        // copy) to the paint it was given, dereferenced on restore. So
        // layerPaint must stay alive until canvas.restore() — closing it
        // immediately after saveLayer (as the original try-finally did)
        // freed the native peer mid-layer, and restore composited from a
        // dangling pointer. Symptom: the global canvas matrix would
        // corrupt and every subsequent HUD draw rendered scaled from
        // (0,0) ("整个 skia 叠加层都被以(0,0)为中心放大"). Reused field, so
        // it's always alive; never closed per call.
        Paint layerPaint = lyricLayerPaint;
        layerPaint.setAlphaf(baseAlpha);
        canvas.saveLayer(layerBounds, layerPaint);
        try {
            // 1) Per-syllable lift (translateY) — AMLL spec.
            // Each syllable runs its own progress-based ease-out from
            // 0 → -LIFT_PEAK_PX over LIFT_MIN_DURATION_MS (1 s). With
            // typical lyric pacing of ~250 ms per syllable, 3-4
            // syllables are simultaneously mid-rise at any moment, so
            // the per-字 lifts overlap into a continuous wave that
            // flows across the row — sung words still rising, current
            // word rising fastest, upcoming words at 0 until their
            // own start time. After completion each syllable holds at
            // peak (fill-forwards), so trailing words stay lifted.
            Paint textPaint = LyricSkia.scratchPaint();
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setAlphaf(1f);
            textPaint.setAntiAlias(true);
            for (int s = 0; s < n; s++) {
                Syllable syl = syllables.get(from + s);
                long sStart = syl.startMs;
                long ownEnd = sStart + Math.max(0L, syl.durationMs);
                long effDur = Math.max(LIFT_MIN_DURATION_MS, ownEnd - sStart);

                float t;
                if (pos <= sStart) t = 0f;
                else if (pos >= sStart + effDur) t = 1f;
                else t = (pos - sStart) / (float) effDur;
                float k = 1f - (1f - t) * (1f - t) * (1f - t);
                float lift = enableLift ? -LIFT_PEAK_PX * k * activeK : 0f;

                canvas.drawString(syl.text, sylLeft[s], baselineY + lift, font, textPaint);
            }

            // 2) Horizontal sweep mask via DST_IN. The "dark" side fades
            // from 1.0 (idle: no darkening, row uniform baseAlpha) to
            // DARK_MASK_ALPHA = 0.2 (active: unsung region visibly
            // dimmer than sung). Coupling to activeK avoids the snap on
            // enter: crossing startMs no longer instantly drops the
            // unsung region from 0.42 to 0.2; both endpoints lerp.
            if (animate) {
                float maskDark = 1f - (1f - DARK_MASK_ALPHA) * activeK;
                applySweepMask(canvas, layerBounds, sweepX, maskDark);
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
     * Apply AMLL's mask-image gradient as a DST_IN draw on the current
     * saveLayer. The destination's alpha gets multiplied by the source
     * alpha — bright (1.0) on the left of the sweep, dark (DARK_MASK_ALPHA)
     * on the right, with a {@code SWEEP_FADE_PX}-wide blend.
     */
    private static void applySweepMask(Canvas canvas, Rect bounds, float sweepX, float darkAlpha) {
        int bright = 0xFFFFFFFF;
        int dark = ((int) (darkAlpha * 255f) << 24) | 0x00FFFFFF;

        float x0 = sweepX - SWEEP_FADE_PX * 0.5f;
        float x1 = sweepX + SWEEP_FADE_PX * 0.5f;
        float left = bounds.getLeft() - 1f;
        float right = bounds.getRight() + 1f;
        float span = right - left;
        if (span <= 0f) span = 1f;
        float u0 = (x0 - left) / span;
        float u1 = (x1 - left) / span;
        if (u0 < 0f) u0 = 0f;
        if (u1 > 1f) u1 = 1f;
        if (u1 <= u0) u1 = Math.min(1f, u0 + 0.0001f);

        int[] colors = {bright, bright, dark, dark};
        float[] stops = {0f, u0, u1, 1f};

        try (Paint p = new Paint();
             Shader g = Shader.makeLinearGradient(
                     new Point(left, bounds.getTop()),
                     new Point(right, bounds.getTop()),
                     colors, stops)) {
            p.setShader(g);
            p.setBlendMode(BlendMode.DST_IN);
            canvas.drawRect(bounds, p);
        }
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
     * BG line scale curve. Snappy non-linear ease (smoothstep S-curve)
     * without any spring overshoot — earlier overshoot via easeOutBack
     * read as a "twitch" on a small element like the BG row. Pop-in
     * and pop-out are symmetric: scale rises smoothly 0→1 over
     * {@link #BG_POP_IN_MS}, falls smoothly 1→0 over {@link #BG_POP_OUT_MS}.
     */
    private static float computeBgScaleK(long positionMs, LineGroup g) {
        if (positionMs < g.startMs - BG_POP_IN_MS) return 0f;
        if (positionMs < g.startMs) {
            float k = (positionMs - (g.startMs - BG_POP_IN_MS)) / (float) BG_POP_IN_MS;
            return smoothstep(0f, 1f, k);
        }
        if (positionMs < g.endMs) return 1f;
        float dt = (positionMs - g.endMs) / (float) BG_POP_OUT_MS;
        if (dt >= 1f) return 0f;
        return 1f - smoothstep(0f, 1f, dt);
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
