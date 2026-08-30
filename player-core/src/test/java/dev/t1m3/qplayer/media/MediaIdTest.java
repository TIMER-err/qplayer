package dev.t1m3.qplayer.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaIdTest {

    @Test
    public void roundTripsUnicodeAndReservedCharacters() {
        MediaId id = MediaId.of("netease", MediaKind.SONG, "歌: en+dure / 1");
        assertEquals("netease:song:%E6%AD%8C%3A%20en%2Bdure%20%2F%201", id.toString());
        assertEquals(id, MediaId.parse(id.toString()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsProviderSpoofingSyntax() {
        MediaId.of("NetEase:other", MediaKind.SONG, "1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRawColonInNativeId() {
        MediaId.parse("netease:song:a:b");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongKind() {
        MediaId.parse("netease:album:1").requireKind(MediaKind.SONG);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedUtf8() {
        MediaId.parse("netease:song:%FF");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsControlCharactersInNativeId() {
        MediaId.of("fixture", MediaKind.SONG, "line\nbreak");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnboundedNativeId() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 2049; i++) value.append('x');
        MediaId.of("fixture", MediaKind.SONG, value.toString());
    }
}
