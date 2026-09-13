package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AMLL-style Enhanced LRC ({@code .eslrc}): every syllable carries its own
 * LRC-shaped {@code [mm:ss.xxx]} prefix and the line is terminated by a final
 * timestamp with no trailing text.
 *
 * <pre>{@code
 * [00:02.739]衰[00:03.202]草[00:03.689]连...[00:09.764]
 * }</pre>
 *
 * Each text run between two timestamps becomes a {@link Syllable} whose
 * start is the leading timestamp and duration is the gap to the next. The
 * trailing tag (text after it is empty) acts purely as the end marker.
 *
 * <p>Not to be confused with the older A2 {@code [line]<word>...} extension
 * baked into {@link LrcParser} — that one keeps a single line-level anchor
 * with inline {@code <...>} word marks.
 */
public final class EsLrcParser {

    private static final Pattern TS = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");

    private EsLrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            Matcher m = TS.matcher(line);
            List<long[]> stamps = new ArrayList<>(); // [posMs, sourceEnd]
            List<String> texts = new ArrayList<>();
            int lastTagEnd = 0;
            int idx = 0;
            while (m.find()) {
                if (idx > 0) {
                    // Text between the previous tag and this one is the
                    // previous syllable's text.
                    texts.add(line.substring(lastTagEnd, m.start()));
                }
                stamps.add(new long[]{parseTime(m.group(1), m.group(2), m.group(3))});
                lastTagEnd = m.end();
                idx++;
            }
            // Tail text after the last tag (if any). The canonical AMLL form
            // closes the line with a bare timestamp and no trailing text, but
            // be lenient.
            if (lastTagEnd < line.length()) {
                texts.add(line.substring(lastTagEnd));
            }
            if (stamps.isEmpty()) continue;

            LyricLine ll = new LyricLine();
            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i);
                if (text.isEmpty()) continue;
                long start = stamps.get(i)[0];
                long nextStart = i + 1 < stamps.size() ? stamps.get(i + 1)[0] : start + 500L;
                long dur = Math.max(0L, nextStart - start);
                ll.syllables.add(new Syllable(text, start, dur));
            }
            if (!ll.syllables.isEmpty()) out.add(ll);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }

    private static long parseTime(String minStr, String secStr, String fracStr) {
        long min = Long.parseLong(minStr);
        long sec = Long.parseLong(secStr);
        return min * 60_000L + sec * 1000L + LrcParser.fractionMs(fracStr);
    }
}
