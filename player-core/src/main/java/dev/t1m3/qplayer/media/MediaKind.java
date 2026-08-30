package dev.t1m3.qplayer.media;

/** Stable kinds used in provider-qualified media ids and the plugin ABI. */
public enum MediaKind {
    SONG("song"), ARTIST("artist"), ALBUM("album"), PLAYLIST("playlist"), USER("user");

    private final String wireName;

    MediaKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static MediaKind fromWireName(String value) {
        for (MediaKind kind : values()) {
            if (kind.wireName.equals(value)) return kind;
        }
        throw new IllegalArgumentException("unknown media kind: " + value);
    }
}
