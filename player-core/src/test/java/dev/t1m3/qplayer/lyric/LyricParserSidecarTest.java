package dev.t1m3.qplayer.lyric;

import dev.t1m3.qplayer.media.LyricsPayload;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class LyricParserSidecarTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rawLrcAndPluginAssetsShareNearestTimeAndEarlierTieRule() {
        String main = "[00:01]first\n[00:03]second";
        String translated = "[00:02]tie\n[00:03.100]near second";
        List<LyricLine> raw = LyricParser.fromNeteaseStrings(null, main, translated, translated);
        assertEquals("tie", raw.get(0).translation);
        assertEquals("near second", raw.get(1).translation);
        assertEquals("tie", raw.get(0).romaji);
        LyricsPayload payload = new LyricsPayload();
        payload.assets.add(asset("original", "lrc", main));
        payload.assets.add(asset("translation", "lrc", translated));
        List<LyricLine> plugin = LyricParser.fromPluginAssets(payload);
        assertEquals(raw.get(0).translation, plugin.get(0).translation);
        assertEquals(raw.get(1).translation, plugin.get(1).translation);
    }

    @Test public void fileSidecarStillUsesItsOwnFormatRatherThanRawLrcParser() throws Exception {
        Path main = temporary.newFile("main.lrc").toPath();
        Path translated = temporary.newFile("translation.yrc").toPath();
        Files.write(main, "[00:01]first\n[00:03]second".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(translated, "[1000,1000](1000,1000,0)translation".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<LyricLine> lines = LyricParser.parse(main.toString(), translated.toString(), null);
        assertEquals("translation", lines.get(0).translation);
    }

    private static LyricsPayload.Asset asset(String role, String format, String text) {
        LyricsPayload.Asset asset = new LyricsPayload.Asset();
        asset.role = role;
        asset.format = format;
        asset.text = text;
        return asset;
    }
}
