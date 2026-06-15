package dev.t1m3.qplayer.lyric;

import dev.t1m3.qplayer.util.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AMLL flavoured TTML — canonical syllable-level format. Each {@code <p>}
 * within {@code <body>/<div>} is one line; inner {@code <span>} children are
 * syllables. Times come in TTML clock form ({@code s.fff}, {@code m:ss.fff}
 * or {@code h:mm:ss.fff}).
 *
 * <p>Vocal channel comes from {@code ttm:agent} references resolved against
 * the {@code <ttm:agent type="person|other"/>} declarations in {@code <head>}.
 * The repo convention is {@code person → MAIN}, {@code other → BACKGROUND};
 * we extend so that further agent ids fall through to MAIN.
 *
 * <p>Inline translation ({@code ttm:role="x-translation"}) and romaji
 * ({@code ttm:role="x-roman"}) sidecar spans are consumed onto the line's
 * {@code translation} / {@code romaji} fields; head-level
 * {@code <transliterations>} are also honoured (inline wins).
 */
public final class TtmlParser {

    private TtmlParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false); // we look up local names + prefixed attrs by string
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

            Map<String, LyricLine.VocalChannel> agents = readAgents(doc);
            Map<String, String> romajiByLineKey = readTransliterations(doc);

            NodeList pList = doc.getElementsByTagName("p");
            for (int i = 0; i < pList.getLength(); i++) {
                Element p = (Element) pList.item(i);
                LyricLine ll = new LyricLine();
                String agent = attr(p, "ttm:agent");
                if (agent == null) agent = attr(p, "agent");
                ll.vocalChannel = agents.getOrDefault(agent, LyricLine.VocalChannel.MAIN);

                // Match romaji from head-level transliterations against the
                // line's itunes:key. Sidecar .romaji.lrc files set by the
                // dispatcher get overridden by this only when present.
                String lineKey = attr(p, "itunes:key");
                if (lineKey == null) lineKey = attr(p, "key");
                if (lineKey != null) {
                    String r = romajiByLineKey.get(lineKey);
                    if (r != null && !r.isEmpty()) ll.romaji = r;
                }

                long lineStart = parseClock(attr(p, "begin"));
                long lineEnd = parseClock(attr(p, "end"));

                // BG vocal lines come out as separate LyricLines that we
                // append immediately after the main line — that's how AMLL
                // models them downstream (a group = mainLine + ≤1 bgLine).
                List<LyricLine> bgLines = new ArrayList<>();

                NodeList kids = p.getChildNodes();
                for (int j = 0; j < kids.getLength(); j++) {
                    Node kid = kids.item(j);
                    if (kid.getNodeType() == Node.ELEMENT_NODE
                            && kid.getNodeName().toLowerCase(Locale.ROOT).endsWith("span")) {
                        Element span = (Element) kid;
                        String role = attr(span, "ttm:role");
                        if (role == null) role = attr(span, "role");
                        String roleLc = role == null ? "" : role.toLowerCase(Locale.ROOT);

                        // Inline translation sidecar attached to the main line.
                        if (roleLc.startsWith("x-translation")) {
                            String t = span.getTextContent();
                            if (t != null && !t.trim().isEmpty()) ll.translation = t.trim();
                            continue;
                        }
                        // Inline romaji/transliteration sidecar. AMLL emits these as
                        // untimed spans after the syllables; without this branch they
                        // fall through to the syllable path below, polluting the main
                        // line's text and zeroing its end time (begin/end absent →
                        // parseClock 0 → endMs() 0, so the line vanishes instantly).
                        // Inline wins over any head-level transliteration match above.
                        if (roleLc.startsWith("x-roman")) {
                            String r = span.getTextContent();
                            if (r != null && !r.trim().isEmpty()) ll.romaji = r.trim();
                            continue;
                        }
                        // Background vocal — own LyricLine with its own
                        // syllables, channel mirrors the main line's duet
                        // side so right-channel BG aligns right too.
                        if (roleLc.equals("x-bg")) {
                            LyricLine bg = parseInlineBgSpan(span, ll.vocalChannel);
                            if (bg != null) bgLines.add(bg);
                            continue;
                        }
                        long s = parseClock(attr(span, "begin"));
                        long e = parseClock(attr(span, "end"));
                        String text = span.getTextContent();
                        if (text == null) text = "";
                        ll.syllables.add(new Syllable(text, s, Math.max(0L, e - s)));
                    } else if (kid.getNodeType() == Node.TEXT_NODE) {
                        String t = kid.getNodeValue();
                        if (t == null || t.isEmpty()) continue;
                        if (t.trim().isEmpty()) {
                            // Inter-span whitespace (the space in `</span> <span>`):
                            // fold a single space onto the previous syllable so words
                            // don't run together. Matters for space-separated scripts;
                            // a no-op for CJK, which has no inter-syllable spaces.
                            appendSpaceToLast(ll.syllables);
                        } else {
                            // Bare untimed text content (rare): keep as instant spacer.
                            long cursor = ll.syllables.isEmpty()
                                    ? lineStart
                                    : ll.syllables.get(ll.syllables.size() - 1).endMs();
                            ll.syllables.add(new Syllable(t, cursor, 0L));
                        }
                    }
                }

                if (ll.syllables.isEmpty()) {
                    // Line had no spans (line-level only) — synthesise a single
                    // syllable covering the whole p element.
                    String text = p.getTextContent();
                    if (text != null && !text.trim().isEmpty()) {
                        ll.syllables.add(new Syllable(text, lineStart, Math.max(0L, lineEnd - lineStart)));
                    }
                }
                if (!ll.syllables.isEmpty()) out.add(ll);
                // Append BG lines right after the main line so the renderer's
                // group-builder picks them up as sub-lines of this group.
                // Order is preserved rather than sorted-by-startMs at the end
                // — see the explicit "no sort" note below.
                for (LyricLine bg : bgLines) out.add(bg);
            }
        } catch (Throwable e) {
            Logger.exception(e);
        }
        // No global sort by startMs: BG lines often have the same or
        // overlapping startMs as their main line, and a sort by startMs
        // could swap them. We rely on document order = playback order =
        // group adjacency. The TTML body itself is in playback order.
        return out;
    }

    /**
     * Parse a {@code <span ttm:role="x-bg">} into its own LyricLine. Inner
     * timed spans become syllables; nested translation spans (rare but
     * possible) attach to the BG line, not the main line.
     */
    private static LyricLine parseInlineBgSpan(Element bgSpan, LyricLine.VocalChannel parentChannel) {
        LyricLine bg = new LyricLine();
        // Mirror the main line's duet side so a right-channel BG aligns
        // right. Solo / DUET_LEFT / MAIN → BACKGROUND (left-aligned default).
        if (parentChannel == LyricLine.VocalChannel.DUET_RIGHT) {
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND_RIGHT;
        } else if (parentChannel == LyricLine.VocalChannel.DUET_LEFT) {
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND_LEFT;
        } else {
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND;
        }

        NodeList inner = bgSpan.getChildNodes();
        for (int k = 0; k < inner.getLength(); k++) {
            Node innerNode = inner.item(k);
            if (innerNode.getNodeType() == Node.ELEMENT_NODE
                    && innerNode.getNodeName().toLowerCase(Locale.ROOT).endsWith("span")) {
                Element span = (Element) innerNode;
                String role = attr(span, "ttm:role");
                if (role == null) role = attr(span, "role");
                String roleLc = role == null ? "" : role.toLowerCase(Locale.ROOT);
                if (roleLc.startsWith("x-translation")) {
                    String t = span.getTextContent();
                    if (t != null && !t.trim().isEmpty()) bg.translation = t.trim();
                    continue;
                }
                if (roleLc.startsWith("x-roman")) {
                    String r = span.getTextContent();
                    if (r != null && !r.trim().isEmpty()) bg.romaji = r.trim();
                    continue;
                }
                long s = parseClock(attr(span, "begin"));
                long e = parseClock(attr(span, "end"));
                String text = span.getTextContent();
                if (text == null) text = "";
                bg.syllables.add(new Syllable(text, s, Math.max(0L, e - s)));
            } else if (innerNode.getNodeType() == Node.TEXT_NODE) {
                String t = innerNode.getNodeValue();
                if (t == null || t.isEmpty()) continue;
                if (t.trim().isEmpty()) {
                    appendSpaceToLast(bg.syllables);
                } else if (!bg.syllables.isEmpty()) {
                    long cursor = bg.syllables.get(bg.syllables.size() - 1).endMs();
                    bg.syllables.add(new Syllable(t, cursor, 0L));
                }
            }
        }
        return bg.syllables.isEmpty() ? null : bg;
    }

    /**
     * Map ttm:agent xml:ids to vocal channels. Single-agent files are
     * solo (everyone MAIN). Two-or-more-agent files are duets — first
     * agent in document order becomes DUET_LEFT, second DUET_RIGHT,
     * additional agents fall back to MAIN. The TTML {@code type} attribute
     * ("person" / "other") is metadata about *who* sings, not *how* the
     * line should be positioned, so we ignore it.
     */
    private static Map<String, LyricLine.VocalChannel> readAgents(Document doc) {
        // LinkedHashMap preserves declaration order so first → left, second → right.
        Map<String, LyricLine.VocalChannel> map = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            String name = n.getNodeName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(":agent") && !"agent".equals(name)) continue;
            Element el = (Element) n;
            String id = attr(el, "xml:id");
            if (id == null) id = attr(el, "id");
            if (id == null) continue;
            ids.add(id);
        }
        if (ids.size() <= 1) {
            for (String id : ids) map.put(id, LyricLine.VocalChannel.MAIN);
        } else {
            for (int i = 0; i < ids.size(); i++) {
                LyricLine.VocalChannel ch;
                if (i == 0) ch = LyricLine.VocalChannel.DUET_LEFT;
                else if (i == 1) ch = LyricLine.VocalChannel.DUET_RIGHT;
                else ch = LyricLine.VocalChannel.MAIN;
                map.put(ids.get(i), ch);
            }
        }
        return map;
    }

    /**
     * Build lineKey → romaji-text map from the
     * {@code <transliterations>/<transliteration>/<text for="L<n>">}
     * block. {@code text} elements live under {@code <iTunesMetadata>} in
     * Apple Music TTML; we identify them by their {@code for} attribute
     * referencing a {@code <p itunes:key>}.
     */
    private static Map<String, String> readTransliterations(Document doc) {
        Map<String, String> out = new HashMap<>();
        NodeList list = doc.getElementsByTagName("text");
        for (int i = 0; i < list.getLength(); i++) {
            Element el = (Element) list.item(i);
            String forKey = attr(el, "for");
            if (forKey == null) continue;
            String text = el.getTextContent();
            if (text == null) continue;
            text = text.trim();
            if (!text.isEmpty()) out.put(forKey, text);
        }
        return out;
    }

    /** Fold a single trailing space onto the last syllable (idempotent), so
     *  inter-span whitespace survives without spawning empty spacer syllables. */
    private static void appendSpaceToLast(List<Syllable> syllables) {
        if (syllables.isEmpty()) return;
        Syllable last = syllables.get(syllables.size() - 1);
        if (last.text.endsWith(" ")) return;
        syllables.set(syllables.size() - 1,
                new Syllable(last.text + " ", last.startMs, last.durationMs));
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v.isEmpty() ? null : v;
    }

    /**
     * Parse a TTML clock value (subset). Accepts {@code "12.345"},
     * {@code "1:23.456"} or {@code "0:01:23.456"} — millisecond resolution.
     * Returns 0 for null/blank input rather than throwing.
     */
    static long parseClock(String s) {
        if (s == null || s.isEmpty()) return 0L;
        String[] parts = s.split(":");
        try {
            double secondsPart = Double.parseDouble(parts[parts.length - 1]);
            long ms = Math.round(secondsPart * 1000.0);
            if (parts.length >= 2) ms += Long.parseLong(parts[parts.length - 2]) * 60_000L;
            if (parts.length >= 3) ms += Long.parseLong(parts[parts.length - 3]) * 3_600_000L;
            return ms;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
