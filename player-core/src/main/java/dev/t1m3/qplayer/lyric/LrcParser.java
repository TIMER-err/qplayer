package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC parser with enhanced-LRC ("A2 extension") support.
 *
 * <p>Plain LRC is line-only: {@code [mm:ss.xx]text}. The A2 extension lets
 * authors interleave per-word timestamps in angle brackets:
 *
 * <pre>{@code
 * [00:12.00]<00:12.00>Hello <00:12.40>world <00:12.90>
 * }</pre>
 *
 * When any {@code <...>} tags appear in a line, each text run between
 * adjacent tags becomes its own {@link Syllable} with karaoke-style timing.
 * Lines without inline tags degenerate to a single line-spanning Syllable,
 * matching plain LRC behaviour.
 */
public final class LrcParser {

    private static final Pattern LINE_TS = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");
    private static final Pattern INLINE_TS = Pattern.compile("<(\\d+):(\\d+)(?:[.:](\\d+))?>");

    private LrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = LINE_TS.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                if (m.start() != lastEnd) break; // metadata or stray text
                stamps.add(parseTime(m.group(1), m.group(2), m.group(3)));
                lastEnd = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = line.substring(lastEnd);

            // Emit one LyricLine per line-prefix timestamp (LRC allows repeated
            // refrains to share text).
            for (long start : stamps) {
                LyricLine ll = new LyricLine();
                if (body.indexOf('<') >= 0) {
                    parseEnhancedBody(ll, body, start);
                } else {
                    ll.syllables.add(new Syllable(body, start, 0L)); // duration patched below
                }
                out.add(ll);
            }
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        // For lines that didn't get per-syllable timing, estimate the single
        // syllable's duration as the gap to the next line, clamped so a long
        // outro doesn't keep one line "active" indefinitely.
        for (int i = 0; i < out.size(); i++) {
            LyricLine ll = out.get(i);
            long start = ll.startMs();
            long next = i + 1 < out.size() ? out.get(i + 1).startMs() : start + 5000L;
            long gap = Math.min(8000L, Math.max(500L, next - start));
            // Only patch syllables with zero duration (i.e. produced by the
            // plain-LRC branch above) — enhanced lines already have timings.
            for (int j = 0; j < ll.syllables.size(); j++) {
                Syllable s = ll.syllables.get(j);
                if (s.durationMs == 0L) {
                    ll.syllables.set(j, new Syllable(s.text, s.startMs, gap));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Parses the body of an enhanced LRC line into one Syllable per text run
     * delimited by inline {@code <mm:ss.xx>} tags.
     *
     * <p>The line-prefix timestamp anchors the first syllable; each inline
     * tag closes the previous syllable (setting its duration) and starts the
     * next. A trailing tag with no following text marks the end-of-line time.
     */
    private static void parseEnhancedBody(LyricLine out, String body, long lineStart) {
        Matcher m = INLINE_TS.matcher(body);
        long cursorMs = lineStart;
        int cursorIdx = 0;
        // First text run uses the line-prefix time as its start.
        boolean opened = false;
        String pendingText = null;
        while (m.find()) {
            String segment = body.substring(cursorIdx, m.start());
            long tagMs = parseTime(m.group(1), m.group(2), m.group(3));
            if (opened && pendingText != null) {
                // Close the previous syllable: duration = tagMs - cursorMs.
                long dur = Math.max(0L, tagMs - cursorMs);
                out.syllables.add(new Syllable(pendingText, cursorMs, dur));
            } else if (!opened && !segment.isEmpty()) {
                // Text before the first inline tag — attribute to lineStart.
                long dur = Math.max(0L, tagMs - cursorMs);
                out.syllables.add(new Syllable(segment, cursorMs, dur));
            }
            cursorMs = tagMs;
            cursorIdx = m.end();
            pendingText = null;
            opened = true;
            // Buffer text up to the next tag (or end of line).
            int nextSearchFrom = cursorIdx;
            int nextTag = findNextTagStart(body, nextSearchFrom);
            String run = nextTag < 0 ? body.substring(nextSearchFrom) : body.substring(nextSearchFrom, nextTag);
            if (!run.isEmpty()) pendingText = run;
        }
        if (opened && pendingText != null) {
            // Last syllable has no closing tag — leave duration 0 so the
            // post-pass clamp fills it from the next line's gap.
            out.syllables.add(new Syllable(pendingText, cursorMs, 0L));
        }
        // Body had only tags, no text — fall back to a single empty syllable
        // so the line still occupies a slot in the timeline.
        if (out.syllables.isEmpty()) {
            out.syllables.add(new Syllable(body.replaceAll("<[^>]*>", ""), lineStart, 0L));
        }
    }

    private static int findNextTagStart(String s, int from) {
        int lt = s.indexOf('<', from);
        return lt;
    }

    private static long parseTime(String minStr, String secStr, String fracStr) {
        try {
            long min = Long.parseLong(minStr);
            long sec = Long.parseLong(secStr);
            long frac = 0L;
            if (fracStr != null) {
                // 2 digits = centiseconds, 3 = ms — normalise to ms.
                if (fracStr.length() == 2) frac = Long.parseLong(fracStr) * 10L;
                else if (fracStr.length() == 3) frac = Long.parseLong(fracStr);
                else frac = Long.parseLong(fracStr.substring(0, Math.min(3, fracStr.length())));
            }
            return min * 60_000L + sec * 1000L + frac;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
