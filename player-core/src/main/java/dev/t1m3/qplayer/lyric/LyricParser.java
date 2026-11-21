package dev.t1m3.qplayer.lyric;

import dev.t1m3.qplayer.media.LyricsPayload;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Dispatcher: pick the right backend by file extension, read the file with
 * UTF-8 first and fall back to GBK (common encoding for LRC files of Chinese
 * songs). Translation / romaji sidecars are merged into the main line list.
 */
public final class LyricParser {

    private LyricParser() {}

    public static List<LyricLine> parse(String mainPath,
                                        String translationPath,
                                        String romajiPath) {
        List<LyricLine> lines = parseFile(mainPath);
        if (lines.isEmpty()) return lines;
        if (translationPath != null) attachSidecar(lines, translationPath, true);
        if (romajiPath != null) attachSidecar(lines, romajiPath, false);
        return lines;
    }

    /**
     * Compose lyrics from Netease's {@code /song/lyric/v1} text payloads.
     * Picks YRC over LRC when available (YRC is syllable-level → enables
     * AMLL-style per-syllable rendering), then attaches translation +
     * romanisation as sidecars.
     */
    public static List<LyricLine> fromNeteaseStrings(String yrc, String lrc,
                                                       String tlyric, String romalrc) {
        List<LyricLine> base;
        if (yrc != null && !yrc.isEmpty()) {
            base = YrcParser.parse(yrc);
        } else if (lrc != null && !lrc.isEmpty()) {
            base = LrcParser.parse(lrc);
        } else {
            return Collections.emptyList();
        }
        if (base.isEmpty()) return base;
        if (tlyric != null && !tlyric.isEmpty()) attachSidecarContent(base, tlyric, true);
        if (romalrc != null && !romalrc.isEmpty()) attachSidecarContent(base, romalrc, false);
        return base;
    }

    /** Parse the source-neutral lyric assets returned by a plugin. The plugin only
     * supplies text and format metadata; parsing, timing objects and rendering stay
     * entirely host-owned. */
    public static List<LyricLine> fromPluginAssets(LyricsPayload payload) {
        if (payload == null || payload.assets == null || payload.assets.isEmpty()) {
            return Collections.emptyList();
        }
        LyricsPayload.Asset original = null;
        LyricsPayload.Asset translation = null;
        LyricsPayload.Asset romanization = null;
        for (LyricsPayload.Asset asset : payload.assets) {
            if (asset == null || asset.text == null || asset.text.isEmpty()) continue;
            String role = asset.role != null ? asset.role.toLowerCase(Locale.ROOT) : "original";
            if (("translation".equals(role) || "translated".equals(role)) && translation == null) {
                translation = asset;
            } else if (("romanization".equals(role) || "romaji".equals(role)) && romanization == null) {
                romanization = asset;
            } else if (original == null) {
                original = asset;
            }
        }
        if (original == null) return Collections.emptyList();
        List<LyricLine> lines = parseContent(original.format, original.text);
        if (lines.isEmpty()) return lines;
        if (translation != null) attachParsedSidecar(lines,
                parseContent(translation.format, translation.text), true);
        if (romanization != null) attachParsedSidecar(lines,
                parseContent(romanization.format, romanization.text), false);
        return lines;
    }

    private static List<LyricLine> parseContent(String format, String content) {
        String lower = format != null ? format.toLowerCase(Locale.ROOT) : "lrc";
        if ("ttml".equals(lower)) return TtmlParser.parse(content);
        if ("qrc".equals(lower)) return QrcParser.parse(content);
        if ("yrc".equals(lower)) return YrcParser.parse(content);
        if ("eslrc".equals(lower)) return EsLrcParser.parse(content);
        if ("word-time-lrc".equals(lower) || "wordtimelrc".equals(lower)) {
            return WordTimeLrcParser.parse(content);
        }
        if (WordTimeLrcParser.looksLikeWordTimeLrc(content)) {
            return WordTimeLrcParser.parse(content);
        }
        return LrcParser.parse(content);
    }

    private static void attachParsedSidecar(List<LyricLine> mainLines,
                                             List<LyricLine> sidecar,
                                             boolean isTranslation) {
        if (sidecar == null || sidecar.isEmpty()) return;
        int j = 0;
        for (LyricLine line : sidecar) {
            long start = line.startMs();
            while (j + 1 < mainLines.size()
                    && Math.abs(mainLines.get(j + 1).startMs() - start)
                    < Math.abs(mainLines.get(j).startMs() - start)) {
                j++;
            }
            if (isTranslation) mainLines.get(j).translation = line.text();
            else mainLines.get(j).romaji = line.text();
        }
    }

    /** Same logic as {@link #attachSidecar} but from a raw content string. */
    private static void attachSidecarContent(List<LyricLine> mainLines, String content, boolean isTranslation) {
        List<LyricLine> sidecar = LrcParser.parse(content);
        if (sidecar.isEmpty()) return;
        int j = 0;
        for (LyricLine s : sidecar) {
            long start = s.startMs();
            while (j + 1 < mainLines.size()
                    && Math.abs(mainLines.get(j + 1).startMs() - start)
                    < Math.abs(mainLines.get(j).startMs() - start)) {
                j++;
            }
            String text = s.text();
            if (isTranslation) mainLines.get(j).translation = text;
            else mainLines.get(j).romaji = text;
        }
    }

    public static List<LyricLine> parseFile(String path) {
        if (path == null) return Collections.emptyList();
        String content = readWithEncodingFallback(path);
        if (content == null) return Collections.emptyList();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ttml")) return TtmlParser.parse(content);
        if (lower.endsWith(".lys")) return LysParser.parse(content);
        if (lower.endsWith(".qrc")) return QrcParser.parse(content);
        if (lower.endsWith(".yrc")) return YrcParser.parse(content);
        if (lower.endsWith(".eslrc")) return EsLrcParser.parse(content);
        // Default: standard LRC (with optional A2 inline <...> extension).
        return LrcParser.parse(content);
    }

    /**
     * Sidecar is always LRC-style (single line per timestamp). Match each
     * sidecar entry to the nearest main line by start time and attach as
     * translation or romaji.
     */
    private static void attachSidecar(List<LyricLine> mainLines, String path, boolean isTranslation) {
        List<LyricLine> sidecar = parseFile(path);
        if (sidecar.isEmpty()) return;
        int j = 0;
        for (LyricLine s : sidecar) {
            long start = s.startMs();
            // Advance j while the next main line is closer to `start`.
            while (j + 1 < mainLines.size()
                    && Math.abs(mainLines.get(j + 1).startMs() - start)
                    < Math.abs(mainLines.get(j).startMs() - start)) {
                j++;
            }
            String text = s.text();
            if (isTranslation) mainLines.get(j).translation = text;
            else mainLines.get(j).romaji = text;
        }
    }

    private static String readWithEncodingFallback(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            // UTF-8 first; fall back to GBK if it produces replacement chars in
            // the result (a quick heuristic that catches mis-decoded CJK).
            String utf8 = new String(bytes, StandardCharsets.UTF_8);
            if (utf8.indexOf('�') < 0) return utf8;
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception e) {
                return utf8;
            }
        } catch (IOException e) {
            Logger.warn("LyricParser: failed to read {}: {}", path, e.getMessage());
            return null;
        }
    }
}
