package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.Syllable;

import io.github.timer_err.qml4j.render.text.UnicodeBreaks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure Unicode text segmentation and row-breaking used by lyric renderers. */
final class LyricTextLayout {

    private static final float MIN_FINAL_ROW_WIDTH_RATIO = 0.60f;

    private LyricTextLayout() {}

    static int[] wrapStarts(List<Syllable> syllables, float[] widths, float maxWidth) {
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[syllables.size() + 1];
        for (int i = 0; i < syllables.size(); i++) {
            offsets[i] = text.length();
            String value = syllables.get(i).text;
            if (value != null) text.append(value);
        }
        offsets[syllables.size()] = text.length();
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(text.toString());
        int[] starts = wrapStarts(breaks, offsets, widths, maxWidth);
        if (text.length() > 0 && breaks.mandatory[text.length()]) {
            starts = Arrays.copyOf(starts, starts.length + 1);
            starts[starts.length - 1] = syllables.size();
        }
        return starts;
    }

    /** UTF-16 row starts for translation/romanization, including a final blank row. */
    static int[] wrapTextStarts(UnicodeBreaks.Analysis breaks, float[] widths, float maxWidth) {
        int[] starts = wrapStarts(breaks, breaks.graphemes, widths, maxWidth);
        for (int i = 0; i < starts.length; i++) starts[i] = breaks.graphemes[starts[i]];
        int end = breaks.graphemes[breaks.graphemes.length - 1];
        if (end > 0 && breaks.mandatory[end]) {
            starts = Arrays.copyOf(starts, starts.length + 1);
            starts[starts.length - 1] = end;
        }
        return starts;
    }

    /**
     * Maps the shared Unicode rules to already measured units. Offsets may refer
     * to either timed syllables or graphemes, including a slice of a larger text.
     * Only the smallest indivisible unit may overflow the available width.
     */
    static int[] wrapStarts(UnicodeBreaks.Analysis breaks, int[] offsets,
                            float[] widths, float maxWidth) {
        int size = widths.length;
        if (size == 0) return new int[]{0, 0};
        float limit = Math.max(1f, maxWidth);
        float[] prefix = new float[size + 1];
        boolean[] safe = new boolean[size + 1];
        boolean[] preferred = new boolean[size + 1];
        boolean[] mandatory = new boolean[size + 1];
        for (int i = 0; i <= size; i++) {
            int offset = offsets[i];
            safe[i] = breaks.line[offset] || breaks.emergency[offset];
            preferred[i] = breaks.line[offset];
            mandatory[i] = breaks.mandatory[offset]
                    && (i == 0 || offset != offsets[i - 1]);
            if (i < size) prefix[i + 1] = prefix[i] + Math.max(0f, widths[i]);
        }
        // Slice endpoints are retained; inner boundaries must remain Unicode-safe.
        safe[0] = safe[size] = true;
        preferred[size] = true;
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(0);
        int rowStart = 0;
        while (rowStart < size) {
            int preferredEnd = -1;
            int emergencyEnd = -1;
            for (int end = rowStart + 1; end <= size; end++) {
                if (!safe[end]) continue;
                if (!rowFits(prefix, safe, rowStart, end, limit)) break;
                emergencyEnd = end;
                if (preferred[end]) preferredEnd = end;
                if (mandatory[end]) break;
            }
            int next = preferredEnd > rowStart ? preferredEnd : emergencyEnd;
            if (next <= rowStart) {
                next = rowStart + 1;
                while (next < size && !safe[next]) next++;
            }
            starts.add(next);
            rowStart = next;
        }
        softenFinalOrphan(starts, prefix, safe, preferred, mandatory, limit);
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) result[i] = starts.get(i);
        return result;
    }

    private static void softenFinalOrphan(ArrayList<Integer> starts, float[] prefix,
                                          boolean[] safe, boolean[] preferred,
                                          boolean[] mandatory, float maxWidth) {
        if (starts.size() < 3) return;
        int end = starts.get(starts.size() - 1);
        int splitSlot = starts.size() - 2;
        int split = starts.get(splitSlot);
        int previousStart = starts.get(splitSlot - 1);
        if (mandatory[split]) return;
        float previousWidth = prefix[split] - prefix[previousStart];
        float finalWidth = prefix[end] - prefix[split];
        float currentRatio = shorterToLongerRatio(previousWidth, finalWidth);
        if (currentRatio >= MIN_FINAL_ROW_WIDTH_RATIO) return;

        int best = split;
        float bestRatio = currentRatio;
        for (int candidate = split - 1; candidate > previousStart; candidate--) {
            if (!safe[candidate] || !preferred[candidate]
                    || !rowFits(prefix, safe, previousStart, candidate, maxWidth)
                    || !rowFits(prefix, safe, candidate, end, maxWidth)) continue;
            float upper = prefix[candidate] - prefix[previousStart];
            float lower = prefix[end] - prefix[candidate];
            float ratio = shorterToLongerRatio(upper, lower);
            if (ratio > bestRatio + 0.0001f) {
                best = candidate;
                bestRatio = ratio;
            }
            if (ratio >= MIN_FINAL_ROW_WIDTH_RATIO) {
                best = candidate;
                break;
            }
        }
        if (best != split) starts.set(splitSlot, best);
    }

    private static float shorterToLongerRatio(float a, float b) {
        float longer = Math.max(a, b);
        return longer <= 0.001f ? 1f : Math.min(a, b) / longer;
    }

    private static boolean rowFits(float[] prefix, boolean[] safe, int start, int end,
                                   float maxWidth) {
        if (prefix[end] - prefix[start] <= maxWidth + 0.5f) return true;
        for (int i = start + 1; i < end; i++) if (safe[i]) return false;
        return true;
    }

    static int[] graphemeBoundaries(String text) {
        return UnicodeBreaks.analyze(text).graphemes;
    }

    /** Renderer-owned fragments retain the exact source text and timing coverage. */
    static List<Syllable> splitTimedSyllable(Syllable syllable, int[] offsets) {
        if (offsets.length <= 2) return Collections.singletonList(syllable);
        ArrayList<Syllable> fragments = new ArrayList<>(offsets.length - 1);
        int length = syllable.text.length();
        for (int i = 0; i + 1 < offsets.length; i++) {
            int start = offsets[i];
            int end = offsets[i + 1];
            long startMs = syllable.startMs + Math.round(syllable.durationMs * (start / (double) length));
            long endMs = end == length ? syllable.startMs + syllable.durationMs
                    : syllable.startMs + Math.round(syllable.durationMs * (end / (double) length));
            fragments.add(new Syllable(syllable.text.substring(start, end), startMs,
                    Math.max(0L, endMs - startMs)));
        }
        return fragments;
    }

    /** Hard-break controls belong to the source/timing offsets, not the glyph run. */
    static int visibleRowEnd(String text, int start, int end) {
        while (end > start) {
            int codePoint = text.codePointBefore(end);
            if (codePoint != '\r' && codePoint != '\n' && codePoint != '\u000B'
                    && codePoint != '\f' && codePoint != '\u0085'
                    && codePoint != '\u2028' && codePoint != '\u2029') break;
            end -= Character.charCount(codePoint);
        }
        return end;
    }

    static int[][] displayWordRanges(String text) {
        if (text == null || text.isEmpty()) return new int[0][];
        ArrayList<int[]> result = new ArrayList<>();
        int[] graphemes = graphemeBoundaries(text);
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            int count = Character.charCount(codePoint);
            if (!isWordCodePoint(codePoint)) {
                offset = graphemeAtOrAfter(graphemes, offset + count);
                continue;
            }
            int start = offset;
            if (isEastAsianCodePoint(codePoint)) {
                offset = graphemeAtOrAfter(graphemes, offset + count);
            } else {
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                offset += count;
                while (offset < text.length()) {
                    int next = text.codePointAt(offset);
                    if (isCombiningMark(next)) {
                        offset += Character.charCount(next);
                        continue;
                    }
                    if ((next == '\'' || next == 0x2019 || next == '-')
                            && hasWordCodePointAfter(text, offset + Character.charCount(next))) {
                        offset += Character.charCount(next);
                        continue;
                    }
                    if (!isWordCodePoint(next) || isEastAsianCodePoint(next)) break;
                    Character.UnicodeScript nextScript = Character.UnicodeScript.of(next);
                    if (script != Character.UnicodeScript.COMMON
                            && nextScript != Character.UnicodeScript.COMMON && nextScript != script) break;
                    offset += Character.charCount(next);
                }
            }
            offset = graphemeAtOrAfter(graphemes, offset);
            result.add(new int[]{start, offset});
        }
        return result.toArray(new int[0][]);
    }

    private static int graphemeAtOrAfter(int[] graphemes, int offset) {
        int index = Arrays.binarySearch(graphemes, offset);
        return graphemes[index >= 0 ? index : -index - 1];
    }

    static int[][] displayWordSyllableRanges(String text, int[] syllableOffsets) {
        int[][] words = displayWordRanges(text);
        int[][] result = new int[words.length][4];
        for (int i = 0; i < words.length; i++) {
            int start = words[i][0];
            int end = words[i][1];
            result[i][0] = start;
            result[i][1] = end;
            result[i][2] = syllableAtOffset(syllableOffsets, start);
            result[i][3] = syllableAtOffset(syllableOffsets, Math.max(start, end - 1));
        }
        return result;
    }

    private static int syllableAtOffset(int[] offsets, int target) {
        int low = 0;
        int high = offsets.length - 2;
        int result = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (offsets[mid] <= target) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.min(result, offsets.length - 2);
    }

    private static boolean hasWordCodePointAfter(String text, int offset) {
        return offset < text.length() && isWordCodePoint(text.codePointAt(offset));
    }

    private static boolean isWordCodePoint(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || isCombiningMark(codePoint);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isEastAsianCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

}
