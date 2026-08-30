package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.Album;
import dev.t1m3.qplayer.media.LyricsPayload;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;
import dev.t1m3.qplayer.media.MediaRef;
import dev.t1m3.qplayer.media.Page;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.ProviderHome;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.media.StreamDescriptor;
import dev.t1m3.qplayer.media.Artist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Validates JSON-like plugin responses and qualifies every native entity id. */
public final class PluginProviderService {
    private static final int MAX_LABEL_CHARS = 4096;
    private static final int MAX_DESCRIPTION_CHARS = 1024 * 1024;
    private static final int MAX_CURSOR_CHARS = 8192;
    private static final int MAX_STREAM_HEADERS = 64;
    private final PluginManager manager;
    private final CorePluginHostApi hostApi;

    public PluginProviderService(PluginManager manager, CorePluginHostApi hostApi) {
        this.manager = manager;
        this.hostApi = hostApi;
    }

    public CompletableFuture<Page<Song>> searchSongs(String provider, String query,
                                                      String cursor, int limit) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query != null ? query : "");
        arguments.put("cursor", cursor != null ? cursor : "");
        arguments.put("limit", Math.max(1, Math.min(100, limit)));
        return manager.invoke(provider, ProviderCapability.SEARCH_SONGS.wireName(), arguments)
                .thenApply(raw -> parseSongPage(provider, raw));
    }

    public CompletableFuture<StreamDescriptor> resolveStream(MediaId id, String quality) {
        id.requireKind(MediaKind.SONG);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", id.nativeId());
        arguments.put("quality", quality != null ? quality : "standard");
        return manager.invoke(id.provider(), ProviderCapability.RESOLVE_STREAM.wireName(), arguments)
                .thenApply(raw -> parseStream(id.provider(), raw));
    }

    public CompletableFuture<List<String>> hotSearch(String provider) {
        return manager.invoke(provider, ProviderCapability.HOT_SEARCH.wireName(),
                Collections.<String, Object>emptyMap()).thenApply(raw -> {
            List<Object> values = list(raw, "hot search response");
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                String keyword = optionalString(value).trim();
                if (!keyword.isEmpty()) result.add(keyword);
                if (result.size() >= 50) break;
            }
            return Collections.unmodifiableList(result);
        });
    }

    public CompletableFuture<ProviderHome> home(String provider, int limit) {
        return manager.invoke(provider, ProviderCapability.HOME.wireName(),
                Collections.<String, Object>singletonMap("limit", Math.max(1, Math.min(100, limit))))
                .thenApply(raw -> parseHome(provider, raw));
    }

    public CompletableFuture<Page<Album>> searchAlbums(String provider, String query,
                                                        String cursor, int limit) {
        return searchEntities(provider, ProviderCapability.SEARCH_ALBUMS, query, cursor, limit)
                .thenApply(raw -> parseAlbumPage(provider, raw));
    }

    public CompletableFuture<Page<Artist>> searchArtists(String provider, String query,
                                                          String cursor, int limit) {
        return searchEntities(provider, ProviderCapability.SEARCH_ARTISTS, query, cursor, limit)
                .thenApply(raw -> parseArtistPage(provider, raw));
    }

    public CompletableFuture<Playlist> playlist(MediaId id) {
        id.requireKind(MediaKind.PLAYLIST);
        return manager.invoke(id.provider(), ProviderCapability.PLAYLIST_DETAILS.wireName(),
                Collections.<String, Object>singletonMap("id", id.nativeId()))
                .thenApply(raw -> parsePlaylist(id.provider(), map(raw, "playlist")));
    }

    public CompletableFuture<List<Song>> songsByIds(String provider, List<String> canonicalIds) {
        List<String> nativeIds = new ArrayList<>();
        if (canonicalIds != null) {
            for (String value : canonicalIds) {
                MediaId id = MediaId.parse(value).requireKind(MediaKind.SONG);
                if (!provider.equals(id.provider())) {
                    throw new IllegalArgumentException("song provider mismatch");
                }
                nativeIds.add(id.nativeId());
                if (nativeIds.size() >= 500) break;
            }
        }
        return manager.invoke(provider, ProviderCapability.SONG_DETAILS.wireName(),
                Collections.<String, Object>singletonMap("ids", nativeIds)).thenApply(raw -> {
            List<Object> values = boundedList(raw, "songDetails response", 500);
            List<Song> songs = new ArrayList<>();
            for (Object value : values) songs.add(parseSong(provider, map(value, "song")));
            return Collections.unmodifiableList(songs);
        });
    }

    public CompletableFuture<Artist> artist(MediaId id) {
        id.requireKind(MediaKind.ARTIST);
        return manager.invoke(id.provider(), ProviderCapability.ARTIST_DETAILS.wireName(),
                Collections.<String, Object>singletonMap("id", id.nativeId()))
                .thenApply(raw -> parseArtist(id.provider(), map(raw, "artist")));
    }

    public CompletableFuture<Album> album(MediaId id) {
        id.requireKind(MediaKind.ALBUM);
        return manager.invoke(id.provider(), ProviderCapability.ALBUM_DETAILS.wireName(),
                Collections.<String, Object>singletonMap("id", id.nativeId()))
                .thenApply(raw -> parseAlbum(id.provider(), map(raw, "album")));
    }

    public CompletableFuture<List<Song>> recent(String provider, int limit) {
        return songList(provider, ProviderCapability.RECENT,
                Collections.<String, Object>singletonMap("limit", Math.max(1, Math.min(500, limit))));
    }

    public CompletableFuture<List<Playlist>> userPlaylists(String provider, int limit) {
        return manager.invoke(provider, ProviderCapability.USER_PLAYLISTS.wireName(),
                Collections.<String, Object>singletonMap("limit",
                        Math.max(1, Math.min(500, limit)))).thenApply(raw -> {
            List<Object> values = boundedList(raw, "userPlaylists response", 500);
            List<Playlist> result = new ArrayList<>();
            for (Object value : values) result.add(parsePlaylist(provider, map(value, "playlist")));
            return Collections.unmodifiableList(result);
        });
    }

    public CompletableFuture<List<Song>> recommendations(String provider, int limit) {
        return songList(provider, ProviderCapability.HOME,
                Collections.<String, Object>singletonMap("operation", "recommendSongs"));
    }

    /** Query the provider's liked-song set. The provider returns native song ids;
     * the host qualifies them before exposing or comparing them. */
    public CompletableFuture<Set<String>> likedSongs(String provider) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("operation", "list");
        return manager.invoke(provider, ProviderCapability.LIKE.wireName(), arguments)
                .thenApply(raw -> {
                    Set<String> result = new LinkedHashSet<>();
                    for (Object value : boundedList(raw, "like list response", 100_000)) {
                        String nativeId = optionalString(value);
                        if (!nativeId.isEmpty()) {
                            result.add(MediaId.of(provider, MediaKind.SONG, nativeId).toString());
                        }
                        if (result.size() >= 100_000) {
                            throw new PluginExecutionException("plugin returned too many liked songs");
                        }
                    }
                    return Collections.unmodifiableSet(result);
                });
    }

    public CompletableFuture<Boolean> setLiked(MediaId songId, boolean liked) {
        songId.requireKind(MediaKind.SONG);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("operation", "set");
        arguments.put("id", songId.nativeId());
        arguments.put("liked", liked);
        return manager.invoke(songId.provider(), ProviderCapability.LIKE.wireName(), arguments)
                .thenApply(PluginProviderService::successValue);
    }

    public CompletableFuture<Boolean> mutatePlaylist(MediaId playlistId, String operation,
                                                       List<MediaId> songs, String name) {
        playlistId.requireKind(MediaKind.PLAYLIST);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("operation", requiredOperation(operation));
        arguments.put("playlistId", playlistId.nativeId());
        if (name != null) arguments.put("name", name);
        List<String> nativeIds = new ArrayList<>();
        if (songs != null) {
            for (MediaId song : songs) {
                song.requireKind(MediaKind.SONG);
                if (!playlistId.provider().equals(song.provider())) {
                    throw new IllegalArgumentException("playlist and song provider mismatch");
                }
                nativeIds.add(song.nativeId());
                if (nativeIds.size() > 1000) {
                    throw new IllegalArgumentException("too many playlist mutation songs");
                }
            }
        }
        arguments.put("songIds", nativeIds);
        return manager.invoke(playlistId.provider(),
                        ProviderCapability.PLAYLIST_MUTATION.wireName(), arguments)
                .thenApply(PluginProviderService::successValue);
    }

    /** Create is the only playlist mutation without an existing playlist id.
     * Returns the canonical id of the new playlist, or an empty string when a
     * provider reports success without returning one. */
    public CompletableFuture<String> createPlaylist(String provider, String name, boolean privateList) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("operation", "create");
        arguments.put("name", name != null ? name : "");
        arguments.put("private", privateList);
        return manager.invoke(provider, ProviderCapability.PLAYLIST_MUTATION.wireName(), arguments)
                .thenApply(raw -> {
                    if (raw instanceof Map) {
                        String id = optionalString(map(raw, "create playlist response").get("id"));
                        return id.isEmpty() ? "" : qualify(provider, MediaKind.PLAYLIST, id);
                    }
                    String id = optionalString(raw);
                    return id.isEmpty() ? "" : qualify(provider, MediaKind.PLAYLIST, id);
                });
    }

    public CompletableFuture<Void> scrobble(MediaId songId, long playedMs,
                                             long durationMs, boolean completed) {
        songId.requireKind(MediaKind.SONG);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", songId.nativeId());
        arguments.put("playedMs", Math.max(0L, playedMs));
        arguments.put("durationMs", Math.max(0L, durationMs));
        arguments.put("completed", completed);
        return manager.invoke(songId.provider(), ProviderCapability.SCROBBLE.wireName(), arguments)
                .thenApply(ignored -> null);
    }

    public CompletableFuture<List<Song>> heartRecommendations(MediaId seedSong,
                                                                MediaId playlistId,
                                                                int limit) {
        seedSong.requireKind(MediaKind.SONG);
        playlistId.requireKind(MediaKind.PLAYLIST);
        if (!seedSong.provider().equals(playlistId.provider())) {
            throw new IllegalArgumentException("heart recommendation provider mismatch");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("songId", seedSong.nativeId());
        arguments.put("playlistId", playlistId.nativeId());
        arguments.put("limit", Math.max(1, Math.min(500, limit)));
        return songList(seedSong.provider(), ProviderCapability.HEART_RECOMMENDATION, arguments);
    }

    public CompletableFuture<String> share(MediaId id) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", id.nativeId());
        arguments.put("kind", id.kind().wireName());
        return manager.invoke(id.provider(), ProviderCapability.SHARE.wireName(), arguments)
                .thenApply(raw -> requiredString(raw, "share URL"));
    }

    public CompletableFuture<LyricsPayload> lyrics(MediaId id) {
        id.requireKind(MediaKind.SONG);
        return manager.invoke(id.provider(), ProviderCapability.LYRICS.wireName(),
                Collections.<String, Object>singletonMap("id", id.nativeId()))
                .thenApply(this::parseLyrics);
    }

    private Page<Song> parseSongPage(String provider, Object raw) {
        Map<String, Object> object = map(raw, "searchSongs response");
        List<Object> items = boundedList(object.get("items"), "searchSongs.items", 200);
        Page<Song> page = new Page<>();
        for (Object item : items) page.items.add(parseSong(provider, map(item, "song")));
        page.nextCursor = boundedString(object.get("nextCursor"), MAX_CURSOR_CHARS,
                "searchSongs.nextCursor", false);
        return page;
    }

    private CompletableFuture<Object> searchEntities(String provider, ProviderCapability capability,
                                                      String query, String cursor, int limit) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query != null ? query : "");
        arguments.put("cursor", cursor != null ? cursor : "");
        arguments.put("limit", Math.max(1, Math.min(100, limit)));
        return manager.invoke(provider, capability.wireName(), arguments);
    }

    private CompletableFuture<List<Song>> songList(String provider, ProviderCapability capability,
                                                    Map<String, Object> arguments) {
        return manager.invoke(provider, capability.wireName(), arguments).thenApply(raw -> {
            List<Object> values = boundedList(raw, capability.wireName() + " response", 500);
            List<Song> result = new ArrayList<>();
            for (Object value : values) result.add(parseSong(provider, map(value, "song")));
            return Collections.unmodifiableList(result);
        });
    }

    private ProviderHome parseHome(String provider, Object raw) {
        Map<String, Object> value = map(raw, "home response");
        ProviderHome home = new ProviderHome();
        Object songs = value.get("songs");
        if (songs instanceof List) {
            for (Object item : boundedList(songs, "home songs", 100)) {
                home.songs.add(parseSong(provider, map(item, "song")));
            }
        }
        Object playlists = value.get("playlists");
        if (playlists instanceof List) {
            for (Object item : boundedList(playlists, "home playlists", 100)) {
                home.playlists.add(parsePlaylist(provider, map(item, "playlist")));
            }
        }
        return home;
    }

    private Page<Album> parseAlbumPage(String provider, Object raw) {
        Map<String, Object> value = map(raw, "album search response");
        Page<Album> page = new Page<>();
        for (Object item : boundedList(value.get("items"), "album search items", 200)) {
            page.items.add(parseAlbum(provider, map(item, "album")));
        }
        page.nextCursor = boundedString(value.get("nextCursor"), MAX_CURSOR_CHARS,
                "album search nextCursor", false);
        return page;
    }

    private Page<Artist> parseArtistPage(String provider, Object raw) {
        Map<String, Object> value = map(raw, "artist search response");
        Page<Artist> page = new Page<>();
        for (Object item : boundedList(value.get("items"), "artist search items", 200)) {
            page.items.add(parseArtist(provider, map(item, "artist")));
        }
        page.nextCursor = boundedString(value.get("nextCursor"), MAX_CURSOR_CHARS,
                "artist search nextCursor", false);
        return page;
    }

    private Song parseSong(String provider, Map<String, Object> object) {
        Song song = new Song();
        song.id = qualify(provider, MediaKind.SONG, object.get("id"));
        song.title = boundedString(object.get("title"), MAX_LABEL_CHARS, "song.title", true);
        song.name = song.title;
        song.durationMs = boundedLong(object.get("durationMs"), 0L,
                31L * 24L * 60L * 60L * 1000L, "song.durationMs");
        song.artworkUrl = allowedUrl(provider, optionalString(object.get("artworkUrl")));
        song.coverUrl = song.artworkUrl;
        song.coverThumbPath = song.artworkUrl;
        song.isrc = optionalString(object.get("isrc"));
        song.playable = !Boolean.FALSE.equals(object.get("playable"));
        song.trial = Boolean.TRUE.equals(object.get("trial"));
        song.restricted = Boolean.TRUE.equals(object.get("restricted"));
        Object artists = object.get("artists");
        if (artists instanceof List) {
            for (Object rawArtist : boundedList(artists, "song artists", 64)) {
                Map<String, Object> artist = map(rawArtist, "song.artist");
                song.artists.add(new MediaRef(
                        qualify(provider, MediaKind.ARTIST, artist.get("id")),
                        boundedString(artist.get("name"), MAX_LABEL_CHARS,
                                "artist.name", true)));
            }
        }
        populateSongArtistAliases(song);
        Object rawAlbum = object.get("album");
        if (rawAlbum instanceof Map) {
            Map<String, Object> album = map(rawAlbum, "song.album");
            song.album = new MediaRef(
                    qualify(provider, MediaKind.ALBUM, album.get("id")),
                    boundedString(album.get("name"), MAX_LABEL_CHARS,
                            "album.name", true));
        }
        return song;
    }

    private Playlist parsePlaylist(String provider, Map<String, Object> object) {
        Playlist playlist = new Playlist();
        playlist.id = qualify(provider, MediaKind.PLAYLIST, object.get("id"));
        playlist.name = boundedString(object.get("name"), MAX_LABEL_CHARS,
                "playlist.name", true);
        playlist.description = boundedString(object.get("description"),
                MAX_DESCRIPTION_CHARS, "playlist.description", false);
        playlist.artworkUrl = allowedUrl(provider, optionalString(object.get("artworkUrl")));
        playlist.coverUrl = playlist.artworkUrl;
        playlist.coverThumbPath = playlist.artworkUrl;
        playlist.trackCount = boundedLong(object.get("trackCount"), 0L,
                Long.MAX_VALUE, "playlist.trackCount");
        playlist.playCount = boundedLong(object.get("playCount"), 0L,
                Long.MAX_VALUE, "playlist.playCount");
        playlist.subscribed = Boolean.TRUE.equals(object.get("subscribed"));
        playlist.owned = Boolean.TRUE.equals(object.get("owned"));
        playlist.mutable = playlist.owned;
        playlist.deletable = playlist.owned;
        if (object.containsKey("mutable")) playlist.mutable = Boolean.TRUE.equals(object.get("mutable"));
        if (object.containsKey("deletable")) playlist.deletable = Boolean.TRUE.equals(object.get("deletable"));
        if (object.get("owner") instanceof Map) {
            Map<String, Object> owner = map(object.get("owner"), "playlist.owner");
            String ownerId = optionalString(owner.get("id"));
            playlist.owner = new MediaRef(ownerId.isEmpty() ? ""
                    : qualify(provider, MediaKind.USER, ownerId),
                    boundedString(owner.get("name"), MAX_LABEL_CHARS,
                            "playlist.owner.name", false));
        }
        if (object.get("songs") instanceof List) {
            for (Object item : boundedList(object.get("songs"), "playlist songs", 5000)) {
                playlist.songs.add(parseSong(provider, map(item, "song")));
            }
        }
        return playlist;
    }

    private Album parseAlbum(String provider, Map<String, Object> object) {
        Album album = new Album();
        album.id = qualify(provider, MediaKind.ALBUM, object.get("id"));
        album.name = boundedString(object.get("name"), MAX_LABEL_CHARS,
                "album.name", true);
        album.artworkUrl = allowedUrl(provider, optionalString(object.get("artworkUrl")));
        album.coverUrl = album.artworkUrl;
        album.coverThumbPath = album.artworkUrl;
        album.publishTimeMs = longValue(object.get("publishTimeMs"), 0L);
        album.description = boundedString(object.get("description"),
                MAX_DESCRIPTION_CHARS, "album.description", false);
        album.trackCount = (int) boundedLong(object.get("trackCount"), 0L,
                1_000_000L, "album.trackCount");
        if (object.get("artists") instanceof List) {
            for (Object item : boundedList(object.get("artists"), "album artists", 64)) {
                Map<String, Object> artist = map(item, "album.artist");
                album.artists.add(new MediaRef(qualify(provider, MediaKind.ARTIST, artist.get("id")),
                        boundedString(artist.get("name"), MAX_LABEL_CHARS,
                                "artist.name", true)));
            }
        }
        if (!album.artists.isEmpty()) {
            album.artistName = album.artists.get(0).name;
            album.artistMediaId = album.artists.get(0).id;
        }
        if (object.get("songs") instanceof List) {
            for (Object item : boundedList(object.get("songs"), "album songs", 5000)) {
                album.songs.add(parseSong(provider, map(item, "song")));
            }
        }
        return album;
    }

    private Artist parseArtist(String provider, Map<String, Object> object) {
        Artist artist = new Artist();
        artist.id = qualify(provider, MediaKind.ARTIST, object.get("id"));
        artist.name = boundedString(object.get("name"), MAX_LABEL_CHARS,
                "artist.name", true);
        artist.artworkUrl = allowedUrl(provider, optionalString(object.get("artworkUrl")));
        artist.coverUrl = artist.artworkUrl;
        artist.coverThumbPath = artist.artworkUrl;
        artist.description = boundedString(object.get("description"),
                MAX_DESCRIPTION_CHARS, "artist.description", false);
        artist.albumCount = (int) boundedLong(object.get("albumCount"), 0L,
                1_000_000L, "artist.albumCount");
        artist.songCount = (int) boundedLong(object.get("songCount"), 0L,
                10_000_000L, "artist.songCount");
        if (object.get("songs") instanceof List) {
            for (Object item : boundedList(object.get("songs"), "artist songs", 1000)) {
                artist.songs.add(parseSong(provider, map(item, "song")));
            }
        }
        if (object.get("albums") instanceof List) {
            for (Object item : boundedList(object.get("albums"), "artist albums", 1000)) {
                artist.albums.add(parseAlbum(provider, map(item, "album")));
            }
        }
        return artist;
    }

    private static void populateSongArtistAliases(Song song) {
        StringBuilder names = new StringBuilder();
        StringBuilder ids = new StringBuilder();
        for (MediaRef artist : song.artists) {
            if (names.length() > 0) names.append(" / ");
            names.append(artist.name);
            if (ids.length() > 0) ids.append(',');
            ids.append(artist.id);
        }
        song.artist = names.toString();
        song.artistIdsCsv = ids.toString();
        song.artistNamesCsv = joinArtistNames(song.artists);
        if (!song.artists.isEmpty()) song.artistMediaId = song.artists.get(0).id;
    }

    private static String joinArtistNames(List<MediaRef> artists) {
        StringBuilder value = new StringBuilder();
        for (MediaRef artist : artists) {
            if (value.length() > 0) value.append((char) 1);
            value.append(artist.name);
        }
        return value.toString();
    }

    private StreamDescriptor parseStream(String provider, Object raw) {
        Map<String, Object> object = map(raw, "resolveStream response");
        StreamDescriptor descriptor = new StreamDescriptor();
        descriptor.url = requiredString(object.get("url"), "stream.url");
        if (!hostApi.allowsReturnedUrl(provider, descriptor.url)) {
            throw new PluginExecutionException("plugin returned a stream URL outside its grant");
        }
        Object headers = object.get("headers");
        if (headers instanceof Map) {
            if (((Map<?, ?>) headers).size() > MAX_STREAM_HEADERS) {
                throw new PluginExecutionException("stream returned too many headers");
            }
            for (Map.Entry<?, ?> header : ((Map<?, ?>) headers).entrySet()) {
                String name = String.valueOf(header.getKey());
                String value = String.valueOf(header.getValue());
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
                        || value.length() > 8192 || value.indexOf('\r') >= 0
                        || value.indexOf('\n') >= 0) {
                    throw new PluginExecutionException("stream returned an invalid header");
                }
                if ("Host".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
                        || "Connection".equalsIgnoreCase(name)) continue;
                descriptor.headers.put(name, value);
            }
        }
        descriptor.mimeType = optionalString(object.get("mimeType"));
        descriptor.expiresAtMs = longValue(object.get("expiresAtMs"), 0L);
        descriptor.trial = Boolean.TRUE.equals(object.get("trial"));
        descriptor.cacheable = !Boolean.FALSE.equals(object.get("cacheable"));
        Object rendition = object.get("renditionId");
        if (rendition != null) descriptor.renditionId = qualify(provider, MediaKind.SONG, rendition);
        return descriptor;
    }

    private LyricsPayload parseLyrics(Object raw) {
        Map<String, Object> object = map(raw, "lyrics response");
        List<Object> assets = list(object.get("assets"), "lyrics.assets");
        if (assets.size() > 12) throw new PluginExecutionException("plugin returned too many lyric assets");
        LyricsPayload payload = new LyricsPayload();
        for (Object rawAsset : assets) {
            Map<String, Object> value = map(rawAsset, "lyric asset");
            LyricsPayload.Asset asset = new LyricsPayload.Asset();
            asset.format = requiredString(value.get("format"), "lyric format").toLowerCase();
            if (!("lrc".equals(asset.format) || "yrc".equals(asset.format)
                    || "ttml".equals(asset.format))) {
                throw new PluginExecutionException("unsupported lyric format " + asset.format);
            }
            asset.role = optionalString(value.get("role"));
            if (asset.role.isEmpty()) asset.role = "original";
            if (!("original".equals(asset.role) || "translation".equals(asset.role)
                    || "romanization".equals(asset.role))) {
                throw new PluginExecutionException("unsupported lyric role " + asset.role);
            }
            asset.text = requiredString(value.get("text"), "lyric text");
            if (asset.text.length() > 4 * 1024 * 1024) {
                throw new PluginExecutionException("lyric text is too large");
            }
            payload.assets.add(asset);
        }
        return payload;
    }

    private static String requiredOperation(String value) {
        String operation = value != null ? value.trim() : "";
        if (!("add".equals(operation) || "remove".equals(operation)
                || "delete".equals(operation) || "subscribe".equals(operation)
                || "unsubscribe".equals(operation))) {
            throw new IllegalArgumentException("unsupported playlist operation: " + operation);
        }
        return operation;
    }

    private static boolean successValue(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Map) {
            Object success = ((Map<?, ?>) raw).get("success");
            return Boolean.TRUE.equals(success);
        }
        return false;
    }

    private String allowedUrl(String provider, String url) {
        return url.isEmpty() || hostApi.allowsReturnedUrl(provider, url) ? url : "";
    }

    private static String qualify(String provider, MediaKind kind, Object nativeId) {
        String value;
        if (nativeId instanceof Number) {
            Number number = (Number) nativeId;
            double d = number.doubleValue();
            value = d == Math.rint(d) ? Long.toString(number.longValue()) : number.toString();
        } else {
            value = requiredString(nativeId, kind.wireName() + ".id");
        }
        return MediaId.of(provider, kind, value).toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw, String label) {
        if (!(raw instanceof Map)) throw new PluginExecutionException(label + " must be an object");
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object raw, String label) {
        if (!(raw instanceof List)) throw new PluginExecutionException(label + " must be an array");
        return (List<Object>) raw;
    }

    private static List<Object> boundedList(Object raw, String label, int maximum) {
        List<Object> values = list(raw, label);
        if (values.size() > maximum) {
            throw new PluginExecutionException(label + " exceeds " + maximum + " items");
        }
        return values;
    }

    private static String requiredString(Object raw, String label) {
        String value = optionalString(raw);
        if (value.isEmpty()) throw new PluginExecutionException(label + " is empty");
        return value;
    }

    private static String optionalString(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String boundedString(Object raw, int maximum, String label,
                                        boolean required) {
        String value = optionalString(raw);
        if (required && value.isEmpty()) throw new PluginExecutionException(label + " is empty");
        if (value.length() > maximum) throw new PluginExecutionException(label + " is too long");
        return value;
    }

    private static long boundedLong(Object raw, long minimum, long maximum, String label) {
        if (raw == null) return minimum;
        if (!(raw instanceof Number)) throw new PluginExecutionException(label + " must be a number");
        long value = ((Number) raw).longValue();
        if (value < minimum || value > maximum) {
            throw new PluginExecutionException(label + " is out of range");
        }
        return value;
    }

    private static long longValue(Object raw, long fallback) {
        return raw instanceof Number ? ((Number) raw).longValue() : fallback;
    }
}
