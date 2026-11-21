package dev.t1m3.qplayer.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DownloadNameTest {
    @Test
    public void sanitizesPathCharacters() {
        assertEquals("a b.mp3", PlayerController.sanitizeDownloadName("a/b.mp3"));
        assertEquals("audio", PlayerController.sanitizeDownloadName(".."));
        assertEquals("audio", PlayerController.sanitizeDownloadName(""));
    }

    @Test
    public void suggestedNameUsesTitleArtistAndExtension() {
        assertEquals("海阔天空 - Beyond.mp3",
                PlayerController.suggestedAudioName("海阔天空", "Beyond",
                        "https://cdn.example/x.mp3?token=1", "audio/mpeg"));
        assertEquals("audio.flac",
                PlayerController.suggestedAudioName("", "", "https://cdn.example/x.flac", ""));
        assertEquals("song.m4a",
                PlayerController.suggestedAudioName("song", "", "https://cdn.example/a", "audio/mp4"));
    }

    @Test
    public void downloadQualityFallsBackToPlaybackThenExhigh() {
        assertEquals("lossless", PlayerController.sanitizeDownloadQuality("Lossless", "standard"));
        assertEquals("hires", PlayerController.sanitizeDownloadQuality("hires", "exhigh"));
        assertEquals("exhigh", PlayerController.sanitizeDownloadQuality("nope", "exhigh"));
        assertEquals("standard", PlayerController.sanitizeDownloadQuality("", "standard"));
        assertEquals("exhigh", PlayerController.sanitizeDownloadQuality(null, null));
    }

    @Test
    public void lyricTextPrefersOriginalAsset() {
        dev.t1m3.qplayer.media.LyricsPayload payload = new dev.t1m3.qplayer.media.LyricsPayload();
        dev.t1m3.qplayer.media.LyricsPayload.Asset translation = new dev.t1m3.qplayer.media.LyricsPayload.Asset();
        translation.role = "translation";
        translation.text = "[00:00.00]hello";
        dev.t1m3.qplayer.media.LyricsPayload.Asset original = new dev.t1m3.qplayer.media.LyricsPayload.Asset();
        original.role = "original";
        original.text = "[00:00.00]你好";
        payload.assets.add(translation);
        payload.assets.add(original);
        assertEquals("[00:00.00]你好", PlayerController.lyricText(payload));
    }
}
