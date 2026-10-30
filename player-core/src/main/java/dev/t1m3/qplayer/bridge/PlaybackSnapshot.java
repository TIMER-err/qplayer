package dev.t1m3.qplayer.bridge;

/** One coherent host-facing transport and metadata observation. */
public final class PlaybackSnapshot {
    private final TrackMetadata track;
    private final boolean playing;
    private final long positionMs;
    private final long durationMs;
    private final long revision;
    private final long seekRevision;

    PlaybackSnapshot(TrackMetadata track, boolean playing, long positionMs, long durationMs,
                     long revision, long seekRevision) {
        this.track = track;
        this.playing = playing;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.revision = revision;
        this.seekRevision = seekRevision;
    }

    public TrackMetadata track() { return track; }
    public boolean playing() { return playing; }
    public long positionMs() { return positionMs; }
    public long durationMs() { return durationMs; }
    public long revision() { return revision; }
    public long seekRevision() { return seekRevision; }

    public static final class TrackMetadata {
        private final String canonicalId;
        private final String title;
        private final String artist;
        private final String album;
        private final String filePath;
        private final String coverUrl;
        private final String coverPath;
        private final long durationMs;
        private final Artwork artwork;

        TrackMetadata(String canonicalId, String title, String artist, String album, String filePath,
                      String coverUrl, String coverPath, long durationMs, Artwork artwork) {
            this.canonicalId = canonicalId;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.filePath = filePath;
            this.coverUrl = coverUrl;
            this.coverPath = coverPath;
            this.durationMs = durationMs;
            this.artwork = artwork;
        }

        public String canonicalId() { return canonicalId; }
        public String title() { return title; }
        public String artist() { return artist; }
        public String album() { return album; }
        public String filePath() { return filePath; }
        public String coverUrl() { return coverUrl; }
        public String coverPath() { return coverPath; }
        public long durationMs() { return durationMs; }
        public Artwork artwork() { return artwork; }
    }

    /** Immutable artwork, shared by snapshots until the actual cover bytes change. */
    public static final class Artwork {
        private final byte[] bytes;
        Artwork(byte[] bytes) { this.bytes = bytes.clone(); }
        public byte[] copyBytes() { return bytes.clone(); }
    }
}
