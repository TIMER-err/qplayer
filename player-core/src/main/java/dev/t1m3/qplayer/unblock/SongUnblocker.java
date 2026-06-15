package dev.t1m3.qplayer.unblock;

import java.util.Arrays;
import java.util.List;

import dev.t1m3.qplayer.util.Logger;

/**
 * Source-switching entry point for songs the official netease endpoint can't
 * return (grey/VIP/region-locked tracks, or trial-only results). Tries each
 * enabled source in order and returns the first playable url, mirroring
 * SPlayer's {@code getUnlockSongUrl} fallback chain.
 *
 * <p>All network/parse failures are swallowed per-source, so a dead source never
 * blocks the next one. Runs entirely off the caller's worker thread.
 */
public final class SongUnblocker {

    /** Available unblock backends. */
    public enum Source { NETEASE, BODIAN, KUWO }

    private SongUnblocker() {}

    // netease (gdstudio, id-based) first: cheapest and most reliable; then the
    // two search-based sources. Order matches the first-success-wins chain.
    private static volatile List<Source> order =
            Arrays.asList(Source.NETEASE, Source.BODIAN, Source.KUWO);

    /** Override the source order / enabled set (e.g. from settings). Empty disables unblocking. */
    public static void setSources(List<Source> sources) {
        if (sources != null) order = sources;
    }

    /**
     * Resolve a playable url for a blocked netease song.
     *
     * @param songId official netease song id (used by the netease/gdstudio source)
     * @param name   song title (for search-source matching)
     * @param artist artist name(s) (for search-source matching)
     * @return a playable url, or null if every enabled source failed
     */
    public static String resolve(long songId, String name, String artist) {
        String keyword = buildKeyword(name, artist);
        for (Source s : order) {
            String url = null;
            try {
                switch (s) {
                    case NETEASE: url = NeteaseSource.resolve(songId); break;
                    case KUWO:    url = KuwoSource.resolve(keyword, name, artist); break;
                    case BODIAN:  url = BodianSource.resolve(keyword, name, artist); break;
                    default: break;
                }
            } catch (RuntimeException e) {
                Logger.warn("unblock[{}] threw: {}", s, e.getMessage());
            }
            if (url != null && !url.isEmpty()) {
                Logger.info("unblock: resolved {} via {}", songId, s);
                return url;
            }
        }
        return null;
    }

    private static String buildKeyword(String name, String artist) {
        String n = name == null ? "" : name.trim();
        String a = artist == null ? "" : artist.trim();
        if (a.isEmpty()) return n;
        return n + "-" + a;
    }
}
