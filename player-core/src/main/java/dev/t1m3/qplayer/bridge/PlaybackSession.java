package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.qml.QmlRuntimeLock;
import java.util.ArrayList;
import java.util.List;

/** Playback state is owned by the same lock as QML publication, including host controls. */
final class PlaybackSession {
    final List<Track> queue = new ArrayList<>();
    int index = -1;
    boolean playing;
    boolean resolving;
    long startPositionMs;
    private long request;

    long select(boolean desiredPlaying) {
        requireOwner();
        playing = desiredPlaying;
        resolving = false;
        return ++request;
    }

    long request() {
        requireOwner();
        return request;
    }

    boolean accepts(long candidate, Track track) {
        requireOwner();
        return request == candidate && current() == track;
    }

    Track current() {
        requireOwner();
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }


    void invalidate() {
        requireOwner();
        resolving = false;
        ++request;
    }

    private static void requireOwner() {
        if (!Thread.holdsLock(QmlRuntimeLock.MONITOR)) {
            throw new IllegalStateException("PlaybackSession requires QmlRuntimeLock");
        }
    }

}
