package dev.t1m3.qplayer.unblock;

/**
 * Title/artist matching for the search-based unblock sources (kuwo, bodian).
 * Ported from SPlayer's {@code electron/server/unblock/match.ts}: a search hit is
 * only accepted when both the song name and the artist plausibly match the
 * original, so we never hand back a completely different track.
 */
final class MatchUtil {

    private MatchUtil() {}

    /** Lowercase + strip bracketed suffixes like "(Live)" / "（伴奏）". */
    static String normalizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("[（(][^）)]*[）)]", "")
                .trim();
    }

    /** Lowercase + collapse all artist separators (&,/、，,;；) to single spaces. */
    static String normalizeArtist(String artist) {
        if (artist == null) return "";
        return artist.toLowerCase()
                .replaceAll("[&/、，,;；]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * True when {@code resultName}/{@code resultArtist} match the wanted song.
     * Name and artist each use a bidirectional {@code contains} (tolerant of one
     * side carrying a suffix); an empty wanted field skips that check.
     */
    static boolean isMatch(String resultName, String resultArtist,
                           String wantName, String wantArtist) {
        String rn = normalizeName(resultName);
        if (rn.isEmpty()) return false;
        String on = normalizeName(wantName);
        if (!on.isEmpty() && !rn.contains(on) && !on.contains(rn)) return false;

        if (resultArtist != null && wantArtist != null && !wantArtist.isEmpty()) {
            String ra = normalizeArtist(resultArtist);
            String oa = normalizeArtist(wantArtist);
            if (!ra.isEmpty() && !oa.isEmpty() && !ra.contains(oa) && !oa.contains(ra)) {
                return false;
            }
        }
        return true;
    }
}
