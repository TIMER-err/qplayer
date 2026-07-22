package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-word/character timestamped lyric text some custom-API backends return for
 * QQ-sourced lyrics (observed from {@code go-music-api}'s {@code /music/lyric}
 * endpoint): every syllable is immediately followed by its own {@code [mm:ss.xx]}
 * timestamp, e.g. {@code "朝[00:42.31]花[00:42.51]岁[00:42.79]"}. Distinct from
 * real QQ QRC ({@link QrcParser}, a leading {@code [ms,ms]} line header plus
 * {@code text(ms,ms)} syllables) and from plain line-level LRC (one
 * {@code [mm:ss.xx]} at the very start of the line only) — treating this as
 * plain LRC left every bracket tag sitting in the displayed text as literal
 * garbage instead of being consumed as timing.
 */
public final class WordTimeLrcParser {

    private static final Pattern TOKEN = Pattern.compile("(.*?)\\[(\\d+):(\\d+(?:\\.\\d+)?)]");

    private WordTimeLrcParser() {}

    /** Cheap heuristic so callers can pick this parser over plain LRC: true if
     *  `content` has at least one char-then-bracket token anywhere. Plain LRC's
     *  bracket only ever opens a line, immediately after a newline/start, so a
     *  bracket preceded by non-empty non-newline text is a strong tell. */
    public static boolean looksLikeWordTimeLrc(String content) {
        if (content == null) return false;
        Matcher m = TOKEN.matcher(content);
        while (m.find()) {
            if (!m.group(1).isEmpty()) return true;
        }
        return false;
    }

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            List<String> texts = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            Matcher m = TOKEN.matcher(line);
            while (m.find()) {
                String text = m.group(1);
                long mins = Long.parseLong(m.group(2));
                double secs = Double.parseDouble(m.group(3));
                texts.add(text != null ? text : "");
                times.add(mins * 60_000L + Math.round(secs * 1000.0));
            }
            // texts/times are offset by one relative to what you'd naively expect:
            // for "[T0]A[T1]B[T2]", the regex above pairs A with T1 (the bracket
            // immediately AFTER it), not T0. But per the format's own semantics A
            // is sung from T0 to T1 — its start is the PRECEDING bracket, and its
            // own trailing bracket (T1) is its end, not its start. So texts[i]'s
            // real start/end is (times[i-1], times[i]); texts[0] is always the
            // empty run before the line's first bracket and carries no syllable.
            LyricLine ll = new LyricLine();
            int n = texts.size();
            for (int i = 1; i < n; i++) {
                String text = texts.get(i);
                if (text.isEmpty()) continue;
                long start = times.get(i - 1);
                long dur = Math.max(0L, times.get(i) - start);
                ll.syllables.add(new Syllable(text, start, dur));
            }
            if (!ll.syllables.isEmpty()) out.add(ll);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
