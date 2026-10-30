package dev.t1m3.qplayer.desktop.media;

import dev.t1m3.qplayer.model.Track;
import org.junit.Test;

import static org.junit.Assert.*;

public class MprisControlsTest {
    @Test
    public void sameTitleAndAlbumStillPublishDistinctRealTrackIds() {
        Track first = track("demo:song:Aa");
        Track second = track("demo:song:BB");
        assertEquals(first.title, second.title);
        assertEquals(first.album, second.album);
        assertNotEquals(MprisControls.trackId(first.canonicalId()), MprisControls.trackId(second.canonicalId()));
        assertTrue(MprisControls.trackId(first.canonicalId()).matches("/dev/t1m3/qplayer/track/[A-Za-z0-9_]+"));
        assertEquals(MprisControls.trackId(first.canonicalId()), MprisControls.trackId(track("demo:song:Aa").canonicalId()));
    }

    @Test
    public void differentProvidersNeverShareAnIdentity() {
        assertNotEquals(MprisControls.trackId(track("first:song:42").canonicalId()),
                MprisControls.trackId(track("second:song:42").canonicalId()));
        assertEquals("/org/mpris/MediaPlayer2/TrackList/NoTrack", MprisControls.trackId(null));
    }

    private static Track track(String id) {
        Track track = new Track();
        track.mediaId = id;
        track.title = "same title";
        track.album = "same album";
        return track;
    }
}
