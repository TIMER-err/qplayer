package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetEase Cloud Music YRC parser. Each line is:
 *
 * <pre>{@code
 * [lineStartMs,lineDurationMs](sylStart,sylDur,wordFlag)字(sylStart,sylDur,wordFlag)字...
 * }</pre>
 *
 * Unlike LYS/QRC where the timing tuple follows the syllable text, YRC puts
 * each {@code (start,dur,flag)} tuple <i>before</i> the syllable it applies
 * to. The trailing {@code wordFlag} is currently unused (0 in all observed
 * AMLL exports), but parsed so the regex matches.
 *
 * <p>YRC has no vocal-channel encoding, but background vocals are inlined into
 * the main line wrapped in full-width parentheses {@code （…）} (NetEase's
 * convention — the brackets are their own zero/short-duration syllables). We
 * split those out into a separate {@link LyricLine.VocalChannel#BACKGROUND}
 * line appended right after the main one, matching how the TTML path models
 * {@code x-bg}. Everything else maps to MAIN.
 */
public final class YrcParser {

    private static final Pattern LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)]");
    /** (start,dur,flag) followed by greedy text up to the next '(' or end. */
    private static final Pattern SYLLABLE = Pattern.compile("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)");

    private YrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher header = LINE_HEADER.matcher(line);
            if (!header.find()) continue;

            LyricLine main = new LyricLine();
            LyricLine bg = new LyricLine();
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND;
            boolean inBg = false;

            Matcher m = SYLLABLE.matcher(line);
            m.region(header.end(), line.length());
            while (m.find()) {
                long start = Long.parseLong(m.group(1));
                long dur = Long.parseLong(m.group(2));
                String text = m.group(4);
                if (text == null) text = "";

                boolean hasOpen = text.indexOf('（') >= 0;
                boolean hasClose = text.indexOf('）') >= 0;
                // The bracket characters are markers, not lyric content — strip
                // them; a syllable that was nothing but a bracket vanishes.
                String stripped = text.replace("（", "").replace("）", "");
                // The opening bracket switches into the BG channel for this
                // syllable onward; the closing one stays in BG, then switches out.
                boolean toBg = inBg || hasOpen;
                if (hasOpen) inBg = true;
                if (!stripped.isEmpty()) {
                    (toBg ? bg : main).syllables.add(new Syllable(stripped, start, dur));
                }
                if (hasClose) inBg = false;
            }
            if (!main.syllables.isEmpty()) out.add(main);
            if (!bg.syllables.isEmpty()) out.add(bg);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
