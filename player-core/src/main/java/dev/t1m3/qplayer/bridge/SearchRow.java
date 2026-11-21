package dev.t1m3.qplayer.bridge;

/**
 * One row in the unified search-results list (SearchPage.qml): a flattened,
 * uniformly-shaped view over provider, built-in compatibility and local results.
 * {@code kind}
 * + {@code index} let {@link PlayerController#playSearchRow(int)} route a click
 * back to the right source-specific play method without re-deriving identity.
 */
public final class SearchRow {
    /** "plugin" | "netease" | "local" | "custom". */
    public String kind;
    /** Index into the source list named by {@link #kind}. */
    public int index;
    public String name;
    public String artist;
    /** Id of the artist (netease rows only; 0 for local/custom) — lets the row
     *  open the artist's page. */
    public long artistId;
    /** Every credited artist's id/name, CSV-encoded the same way as
     *  {@link dev.t1m3.qplayer.netease.dto.NeteaseSong#artistIdsCsv} (netease
     *  rows only; empty for local/custom) — lets "查看歌手" list all of a
     *  song's creators instead of only the first. Plain Strings, not a nested
     *  List field: see that field's javadoc for why. */
    public String artistIdsCsv = "";
    public String artistNamesCsv = "";
    public String coverThumbPath;
    /** Menu identity. Exactly one is populated for each source kind. Keeping these
     *  on the flattened row lets SongContextMenu work without reaching back into a
     *  source-specific result list. */
    public long id;
    public String filePath;
    public String customId;
    /** Canonical source-qualified id for plugin rows. */
    public String mediaId = "";
    public String artistMediaId = "";
    /** Provider owning {@link #mediaId}; empty for legacy/local rows. */
    public String providerId = "";
    public boolean menuEnabled;
    /** Display label for {@link #kind} (provider name or "本地"), shown as a
     *  small per-row tag in SearchPage.qml's unified list — precomputed here so
     *  QML doesn't need its own kind-to-label mapping. */
    public String kindLabel;
}
