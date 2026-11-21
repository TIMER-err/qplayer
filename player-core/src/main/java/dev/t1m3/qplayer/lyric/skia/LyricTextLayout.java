package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.Syllable;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure Unicode text segmentation and row-breaking used by lyric renderers. */
final class LyricTextLayout {

    private static final float MIN_FINAL_ROW_WIDTH_RATIO = 0.60f;

    private LyricTextLayout() {}

    static int[] wrapStarts(List<Syllable> syllables, float[] widths, float maxWidth) {
        int size = widths.length;
        if (size == 0) return new int[]{0, 0};
        float limit = Math.max(1f, maxWidth);
        float[] prefix = new float[size + 1];
        for (int i = 0; i < size; i++) prefix[i + 1] = prefix[i] + Math.max(0f, widths[i]);
        boolean[] safe = safeBreaks(syllables);
        boolean[] preferred = preferredBreaks(syllables);
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(0);
        int rowStart = 0;
        while (rowStart < size) {
            int preferredEnd = -1;
            int emergencyEnd = -1;
            for (int end = rowStart + 1; end <= size; end++) {
                if (end < size && !safe[end]) continue;
                if (!rowFits(prefix, safe, rowStart, end, limit)) break;
                emergencyEnd = end;
                if (preferred[end]) preferredEnd = end;
            }
            int next = preferredEnd > rowStart ? preferredEnd : emergencyEnd;
            if (next <= rowStart) {
                next = rowStart + 1;
                while (next < size && !safe[next]) next++;
            }
            starts.add(next);
            rowStart = next;
        }
        softenFinalOrphan(starts, prefix, safe, preferred, limit);
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) result[i] = starts.get(i);
        return result;
    }

    private static void softenFinalOrphan(ArrayList<Integer> starts, float[] prefix,
                                          boolean[] safe, boolean[] preferred,
                                          float maxWidth) {
        if (starts.size() < 3) return;
        int end = starts.get(starts.size() - 1);
        int splitSlot = starts.size() - 2;
        int split = starts.get(splitSlot);
        int previousStart = starts.get(splitSlot - 1);
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

    private static boolean[] safeBreaks(List<Syllable> syllables) {
        int size = syllables.size();
        boolean[] result = new boolean[size + 1];
        result[0] = true;
        result[size] = true;
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[size + 1];
        for (int i = 0; i < size; i++) {
            offsets[i] = text.length();
            String value = syllables.get(i).text;
            if (value != null) text.append(value);
        }
        offsets[size] = text.length();
        String fullText = text.toString();
        for (int i = 1; i < size; i++) result[i] = isSafeGraphemeBoundary(fullText, offsets[i]);
        return result;
    }

    private static boolean[] preferredBreaks(List<Syllable> syllables) {
        int size = syllables.size();
        boolean[] result = new boolean[size + 1];
        result[0] = true;
        result[size] = true;
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[size + 1];
        for (int i = 0; i < size; i++) {
            offsets[i] = text.length();
            String value = syllables.get(i).text;
            if (value != null) text.append(value);
        }
        offsets[size] = text.length();
        String fullText = text.toString();
        boolean[] unicodeBreaks = unicodeLineBreakOffsets(fullText);
        for (int i = 1; i < size; i++) {
            result[i] = isSafeGraphemeBoundary(fullText, offsets[i])
                    && (canBreakBefore(syllables, i) || unicodeBreaks[offsets[i]]);
        }
        return result;
    }

    private static boolean canBreakBefore(List<Syllable> syllables, int index) {
        String previous = syllables.get(index - 1).text;
        String current = syllables.get(index).text;
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) return true;
        char last = previous.charAt(previous.length() - 1);
        char first = current.charAt(0);
        return Character.isWhitespace(last) || Character.isWhitespace(first)
                || isWrapCjk(last) || isWrapCjk(first);
    }

    static int[] graphemeBoundaries(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(lineBreakLocale(text));
        iterator.setText(text);
        ArrayList<Integer> offsets = new ArrayList<>();
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            if (isSafeGraphemeBoundary(text, boundary)) offsets.add(boundary);
        }
        if (offsets.isEmpty() || offsets.get(0) != 0) offsets.add(0, 0);
        if (offsets.get(offsets.size() - 1) != text.length()) offsets.add(text.length());
        int[] result = new int[offsets.size()];
        for (int i = 0; i < result.length; i++) result[i] = offsets.get(i);
        return result;
    }

    static boolean[] unicodeLineBreakOffsets(String text) {
        boolean[] result = new boolean[text.length() + 1];
        BreakIterator iterator = BreakIterator.getLineInstance(lineBreakLocale(text));
        iterator.setText(text);
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            if (isSafeGraphemeBoundary(text, boundary)) result[boundary] = true;
        }
        result[0] = true;
        result[text.length()] = true;
        return result;
    }

    private static boolean isSafeGraphemeBoundary(String text, int offset) {
        if (offset <= 0 || offset >= text.length()) return true;
        int previous = text.codePointBefore(offset);
        int next = text.codePointAt(offset);
        int nextType = Character.getType(next);
        if (nextType == Character.NON_SPACING_MARK
                || nextType == Character.COMBINING_SPACING_MARK
                || nextType == Character.ENCLOSING_MARK
                || isVariationSelector(next) || isEmojiModifier(next) || next == 0x200D) {
            return false;
        }
        return previous != 0x200D && !isVirama(previous);
    }

    private static boolean isVariationSelector(int codePoint) {
        return (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }

    private static boolean isEmojiModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isVirama(int codePoint) {
        switch (codePoint) {
            case 0x094D: case 0x09CD: case 0x0A4D: case 0x0ACD:
            case 0x0B4D: case 0x0BCD: case 0x0C4D: case 0x0CCD:
            case 0x0D4D: case 0x0DCA: case 0x0E3A: case 0x0EBA:
            case 0x1039: case 0x17D2:
                return true;
            default:
                return false;
        }
    }

    private static Locale lineBreakLocale(String text) {
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.THAI) return new Locale("th");
            if (script == Character.UnicodeScript.KHMER) return new Locale("km");
            if (script == Character.UnicodeScript.LAO) return new Locale("lo");
            if (script == Character.UnicodeScript.MYANMAR) return new Locale("my");
            i += Character.charCount(codePoint);
        }
        return Locale.ROOT;
    }

    static int[][] displayWordRanges(String text) {
        if (text == null || text.isEmpty()) return new int[0][];
        ArrayList<int[]> result = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            int count = Character.charCount(codePoint);
            if (!isWordCodePoint(codePoint)) {
                offset += count;
                continue;
            }
            int start = offset;
            if (isEastAsianCodePoint(codePoint)) {
                offset += count;
                while (offset < text.length() && isCombiningMark(text.codePointAt(offset))) {
                    offset += Character.charCount(text.codePointAt(offset));
                }
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
            result.add(new int[]{start, offset});
        }
        return result.toArray(new int[0][]);
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

    private static boolean isWrapCjk(char value) {
        return (value >= 0x4E00 && value <= 0x9FFF)
                || (value >= 0x3040 && value <= 0x30FF)
                || (value >= 0xAC00 && value <= 0xD7A3);
    }
}
