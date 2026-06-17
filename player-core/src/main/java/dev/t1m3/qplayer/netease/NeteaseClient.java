package dev.t1m3.qplayer.netease;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java port of the netease cloud-music web client. Encrypts every POST with
 * {@link NeteaseCrypto#weapi(String)}, persists cookies across runs, and
 * exposes a single {@link #weapiCall(String, Map)} for use by feature-
 * specific wrappers ({@code songUrl}, {@code playlistDetail} etc.).
 *
 * <p>No external HTTP dependency on purpose — we stay on {@code HttpURLConnection}
 * so this works on mc 1.8.9 (Java 8) without adding jars to the standalone
 * classloader.
 */
public final class NeteaseClient {

    /** Singleton — one cookie jar per Haedus install. */
    public static final NeteaseClient INSTANCE = new NeteaseClient();

    private static final String BASE = "https://music.163.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final Gson gson = new Gson();
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Path cookieFile;

    private NeteaseClient() {
        cookieFile = Paths.get(AppDirs.base(), "netease-cookies.json");
        loadCookies();
    }

    /**
     * POST to {@code /weapi/<path>?csrf_token=<csrf>}. {@code json} is the
     * pre-encrypted JSON body the endpoint expects; we'll auto-inject
     * {@code csrf_token} from the cookie jar if the caller didn't supply
     * one. Returns the raw response body as UTF-8 text — caller parses.
     */
    public synchronized String weapiCall(String path, Map<String, Object> json) throws IOException {
        String csrf = cookies.getOrDefault("__csrf", "");
        Map<String, Object> body = json == null ? new HashMap<String, Object>() : new HashMap<String, Object>(json);
        if (!body.containsKey("csrf_token")) body.put("csrf_token", csrf);

        String bodyJson = gson.toJson(body);
        Map<String, String> encrypted;
        try {
            encrypted = NeteaseCrypto.weapi(bodyJson);
        } catch (Exception e) {
            throw new IOException("weapi crypto failed: " + e.getMessage(), e);
        }

        String urlStr = BASE + "/weapi/" + path
                + "?csrf_token=" + urlEnc(csrf);
        String form = formEncode(encrypted);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", BASE);
            conn.setRequestProperty("Origin", BASE);
            String cookieHeader = cookieHeader();
            if (!cookieHeader.isEmpty()) conn.setRequestProperty("Cookie", cookieHeader);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp;
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            captureSetCookies(conn.getHeaderFields().get("Set-Cookie"));

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /weapi/{}: {}", code, path, truncate(resp, 200));
                throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    /** Convenience wrapper: weapiCall + parse JSON object response.
     *  Also performs cookie-expiry detection: a {@code code == 301} in the
     *  response body means the MUSIC_U session has expired server-side,
     *  so we wipe the local cookie jar to force a re-login. */
    public JsonObject weapiJson(String path, Map<String, Object> json) throws IOException {
        String resp = weapiCall(path, json);
        JsonElement el = new JsonParser().parse(resp);
        if (!el.isJsonObject()) throw new IOException("not a JSON object: " + truncate(resp, 200));
        JsonObject obj = el.getAsJsonObject();
        if (obj.has("code") && !obj.get("code").isJsonNull()
                && obj.get("code").getAsInt() == 301
                && isLoggedIn() && !"login/qrcode/client/login".equals(path)) {
            Logger.warn("Netease: session expired (code 301) on /weapi/{} — clearing cookies", path);
            clearCookies();
        }
        return obj;
    }

    // ---- Endpoint wrappers (Step 1 verification only — full set in Step 3) ----

    /**
     * Fetch a CDN url for the given song id. {@code level} is one of
     * {@code standard / higher / exhigh / lossless / hires / jyeffect / sky / jymaster}.
     * Returns null if the song is blocked/VIP-only and we don't have a
     * subscription cookie.
     */
    public String songUrl(long songId, String level) throws IOException {
        UrlInfo info = songUrlInfo(songId, level);
        return info == null ? null : info.url;
    }

    /** Official-url result with trial detection. {@link #url} is the CDN url (null
     *  if blocked/VIP/login-required); {@link #trial} is true when the only url
     *  netease returned is a {@code freeTrialInfo} preview clip, which callers
     *  may want to replace via an unblock source before settling for it. */
    public static final class UrlInfo {
        public final String url;
        public final boolean trial;
        public UrlInfo(String url, boolean trial) {
            this.url = url;
            this.trial = trial;
        }
    }

    /** Like {@link #songUrl} but also reports whether the returned url is a
     *  trial-only preview ({@code freeTrialInfo != null}). */
    public UrlInfo songUrlInfo(long songId, String level) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", "[" + songId + "]");
        body.put("level", level == null ? "standard" : level);
        body.put("encodeType", "mp3");
        JsonObject obj = weapiJson("song/enhance/player/url/v1", body);
        if (!obj.has("data") || !obj.get("data").isJsonArray()) return null;
        if (obj.get("data").getAsJsonArray().size() == 0) return null;
        JsonElement first = obj.get("data").getAsJsonArray().get(0);
        if (!first.isJsonObject()) return null;
        JsonObject song = first.getAsJsonObject();
        JsonElement url = song.get("url");
        if (url == null || url.isJsonNull()) return null;
        boolean trial = song.has("freeTrialInfo") && !song.get("freeTrialInfo").isJsonNull();
        return new UrlInfo(url.getAsString(), trial);
    }

    // ---- Discovery / search / playlist (N5) ----

    /**
     * Hot / personalised playlist grid for the home page. The
     * {@code /personalized/playlist} endpoint returns public picks even
     * without a logged-in cookie.
     */
    public List<NeteasePlaylist> personalizedPlaylists(int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("total", true);
        body.put("n", 1000);
        JsonObject obj = weapiJson("personalized/playlist", body);
        List<NeteasePlaylist> out = new ArrayList<>();
        if (obj.has("result") && obj.get("result").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("result")) {
                if (!el.isJsonObject()) continue;
                out.add(parsePlaylist(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * Search songs by keyword (type=1). Pagination via offset/limit.
     * Uses the legacy {@code /search/get} weapi endpoint — the newer
     * {@code /cloudsearch} requires eapi encryption which we don't ship
     * yet. The legacy endpoint still returns the same song fields, just
     * under the older {@code album/artists/duration} field names; the
     * {@link #parseSong} helper falls back to those.
     */
    public List<NeteaseSong> searchSongs(String keyword, int limit, int offset) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("s", keyword);
        body.put("type", 1);
        body.put("limit", limit);
        body.put("offset", offset);
        JsonObject obj = weapiJson("search/get", body);
        List<NeteaseSong> out = new ArrayList<>();
        if (obj.has("result") && obj.get("result").isJsonObject()) {
            JsonObject result = obj.getAsJsonObject("result");
            if (result.has("songs") && result.get("songs").isJsonArray()) {
                for (JsonElement el : result.getAsJsonArray("songs")) {
                    if (!el.isJsonObject()) continue;
                    out.add(parseSong(el.getAsJsonObject()));
                }
            }
        }
        return out;
    }

    /** Full playlist (metadata + first ~1000 track stubs). */
    public NeteasePlaylist playlistDetail(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId);
        body.put("n", 1000);
        body.put("s", 8);
        JsonObject obj = weapiJson("v6/playlist/detail", body);
        if (!obj.has("playlist") || !obj.get("playlist").isJsonObject()) return null;
        return parsePlaylist(obj.getAsJsonObject("playlist"));
    }

    /**
     * All tracks in a playlist. Netease returns track stubs in the playlist
     * detail's {@code trackIds} array; this method calls {@code /v3/song/detail}
     * to resolve them into full {@link NeteaseSong}s in chunks of up to
     * {@code limit} ids per request.
     */
    public List<NeteaseSong> playlistTracks(long playlistId, int limit) throws IOException {
        Map<String, Object> detailBody = new HashMap<>();
        detailBody.put("id", playlistId);
        detailBody.put("n", limit);
        detailBody.put("s", 0);
        JsonObject detail = weapiJson("v6/playlist/detail", detailBody);
        List<NeteaseSong> out = new ArrayList<>();
        if (!detail.has("playlist") || !detail.get("playlist").isJsonObject()) return out;
        JsonObject pl = detail.getAsJsonObject("playlist");
        // Newer responses populate tracks[] directly when n is small enough.
        if (pl.has("tracks") && pl.get("tracks").isJsonArray()) {
            JsonArray arr = pl.getAsJsonArray("tracks");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                out.add(parseSong(el.getAsJsonObject()));
            }
            return out;
        }
        // Otherwise iterate trackIds, batch-resolve via /v3/song/detail.
        if (!pl.has("trackIds") || !pl.get("trackIds").isJsonArray()) return out;
        StringBuilder ids = new StringBuilder("[");
        int n = 0;
        for (JsonElement el : pl.getAsJsonArray("trackIds")) {
            if (n >= limit) break;
            JsonObject ti = el.getAsJsonObject();
            JsonElement idEl = ti.get("id");
            if (idEl == null || idEl.isJsonNull()) continue;
            if (n > 0) ids.append(',');
            ids.append("{\"id\":").append(idEl.getAsLong()).append('}');
            n++;
        }
        ids.append(']');
        Map<String, Object> songBody = new HashMap<>();
        songBody.put("c", ids.toString());
        JsonObject songResp = weapiJson("v3/song/detail", songBody);
        if (songResp.has("songs") && songResp.get("songs").isJsonArray()) {
            for (JsonElement el : songResp.getAsJsonArray("songs")) {
                if (!el.isJsonObject()) continue;
                out.add(parseSong(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /** Common JSON decode for /cloudsearch songs[] / /song/detail songs[] / playlist tracks[]. */
    private static NeteaseSong parseSong(JsonObject s) {
        NeteaseSong out = new NeteaseSong();
        if (s.has("id") && !s.get("id").isJsonNull()) out.id = s.get("id").getAsLong();
        if (s.has("name") && !s.get("name").isJsonNull()) out.name = s.get("name").getAsString();
        if (s.has("dt"))   out.durationMs = s.get("dt").getAsLong();
        else if (s.has("duration")) out.durationMs = s.get("duration").getAsLong();
        if (s.has("fee"))  out.fee = s.get("fee").getAsInt() == 1;
        // Artists array: "ar" (new schema) or "artists" (old).
        JsonArray ar = s.has("ar") && s.get("ar").isJsonArray()
                ? s.getAsJsonArray("ar")
                : (s.has("artists") && s.get("artists").isJsonArray() ? s.getAsJsonArray("artists") : null);
        if (ar != null) {
            StringBuilder names = new StringBuilder();
            for (JsonElement ae : ar) {
                if (!ae.isJsonObject()) continue;
                JsonObject ao = ae.getAsJsonObject();
                if (!ao.has("name") || ao.get("name").isJsonNull()) continue;
                if (names.length() > 0) names.append(" / ");
                names.append(ao.get("name").getAsString());
            }
            if (names.length() > 0) out.artist = names.toString();
        }
        // Album object: "al" (new schema) or "album" (old).
        JsonObject al = s.has("al") && s.get("al").isJsonObject()
                ? s.getAsJsonObject("al")
                : (s.has("album") && s.get("album").isJsonObject() ? s.getAsJsonObject("album") : null);
        if (al != null) {
            if (al.has("name") && !al.get("name").isJsonNull()) out.album = al.get("name").getAsString();
            if (al.has("picUrl") && !al.get("picUrl").isJsonNull()) out.coverUrl = al.get("picUrl").getAsString();
        }
        return out;
    }

    private static NeteasePlaylist parsePlaylist(JsonObject p) {
        NeteasePlaylist out = new NeteasePlaylist();
        if (p.has("id") && !p.get("id").isJsonNull()) out.id = p.get("id").getAsLong();
        if (p.has("name") && !p.get("name").isJsonNull()) out.name = p.get("name").getAsString();
        if (p.has("picUrl")) out.coverUrl = p.get("picUrl").getAsString();
        else if (p.has("coverImgUrl")) out.coverUrl = p.get("coverImgUrl").getAsString();
        if (p.has("trackCount")) out.trackCount = p.get("trackCount").getAsInt();
        if (p.has("playCount")) out.playCount = p.get("playCount").getAsLong();
        if (p.has("description") && !p.get("description").isJsonNull())
            out.description = p.get("description").getAsString();
        if (p.has("creator") && p.get("creator").isJsonObject()) {
            JsonObject c = p.getAsJsonObject("creator");
            if (c.has("nickname") && !c.get("nickname").isJsonNull())
                out.creatorNickname = c.get("nickname").getAsString();
        }
        return out;
    }

    /**
     * Fetch full metadata for a single song. Used as a cover-URL fallback
     * when legacy {@code /search/get} returns album rows without
     * {@code picUrl} populated — {@code /v3/song/detail} always carries it.
     * Returns {@code null} if the song is missing or the API blocks us.
     */
    public NeteaseSong songDetail(long songId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("c", "[{\"id\":" + songId + "}]");
        JsonObject obj = weapiJson("v3/song/detail", body);
        if (!obj.has("songs") || !obj.get("songs").isJsonArray()) return null;
        JsonArray arr = obj.getAsJsonArray("songs");
        if (arr.size() == 0 || !arr.get(0).isJsonObject()) return null;
        return parseSong(arr.get(0).getAsJsonObject());
    }

    /**
     * Batch-fetch full metadata for multiple songs. Always includes
     * {@code picUrl} even when the search API omits it.
     */
    public List<NeteaseSong> songDetails(List<Long> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(ids.get(i)).append('}');
        }
        sb.append(']');
        Map<String, Object> body = new HashMap<>();
        body.put("c", sb.toString());
        JsonObject obj = weapiJson("v3/song/detail", body);
        List<NeteaseSong> out = new ArrayList<>();
        if (!obj.has("songs") || !obj.get("songs").isJsonArray()) return out;
        for (JsonElement el : obj.getAsJsonArray("songs")) {
            if (!el.isJsonObject()) continue;
            out.add(parseSong(el.getAsJsonObject()));
        }
        return out;
    }

    // ---- User account / library (N6) ----

    /**
     * Resolve the logged-in user's uid via {@code /w/nuser/account/get}. The
     * weapi endpoint returns {@code {profile: {userId: ...}}} when MUSIC_U
     * cookie is valid, or {@code profile=null} when the session expired.
     * Returns 0 when not logged in.
     */
    public long loginUid() throws IOException {
        if (!isLoggedIn()) return 0L;
        JsonObject obj = weapiJson("w/nuser/account/get", new HashMap<String, Object>());
        if (!obj.has("profile") || obj.get("profile").isJsonNull()) return 0L;
        JsonObject p = obj.getAsJsonObject("profile");
        if (!p.has("userId") || p.get("userId").isJsonNull()) return 0L;
        return p.get("userId").getAsLong();
    }

    /**
     * All playlists owned + subscribed by the given user. Netease guarantees
     * that the first playlist (index 0) when {@code uid} matches the logged-
     * in user is the special "我喜欢的音乐" list. Subsequent entries split
     * between user-created and user-subscribed — distinguish via the
     * {@code creator.userId == uid} test on the caller side if you need to.
     */
    public List<NeteasePlaylist> userPlaylists(long uid, int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("includeVideo", true);
        JsonObject obj = weapiJson("user/playlist", body);
        List<NeteasePlaylist> out = new ArrayList<>();
        if (obj.has("playlist") && obj.get("playlist").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("playlist")) {
                if (!el.isJsonObject()) continue;
                NeteasePlaylist pl = parsePlaylist(el.getAsJsonObject());
                // Track creator uid so the caller can split owned vs subscribed.
                JsonObject p = el.getAsJsonObject();
                if (p.has("creator") && p.get("creator").isJsonObject()) {
                    JsonObject c = p.getAsJsonObject("creator");
                    if (c.has("userId") && !c.get("userId").isJsonNull()) {
                        pl.creatorUid = c.get("userId").getAsLong();
                    }
                }
                out.add(pl);
            }
        }
        return out;
    }

    /**
     * Recent-play song list. {@code type=1} = weekly (last 7 days),
     * {@code type=0} = all-time. Netease wraps each entry as
     * {@code {song: {...}, playCount: N, score: M}}; we just unwrap the
     * {@code song} child into a {@link NeteaseSong}. Returns an empty list
     * when the user has hidden their play history (privacy setting).
     */
    public List<NeteaseSong> userRecord(long uid, int type) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        body.put("type", type);
        JsonObject obj = weapiJson("v1/play/record", body);
        List<NeteaseSong> out = new ArrayList<>();
        String key = type == 1 ? "weekData" : "allData";
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray(key)) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                if (!row.has("song") || !row.get("song").isJsonObject()) continue;
                out.add(parseSong(row.getAsJsonObject("song")));
            }
        }
        return out;
    }

    // ---- Lyrics ----

    /**
     * Fetch lyric payloads for a song. Calls Netease's {@code /song/lyric/v1}
     * (the same endpoint SPlayer uses) requesting LRC + YRC + translation +
     * romanisation in one round trip. Returns {@code null} if the API call
     * fails outright; an empty {@link dev.t1m3.qplayer.netease.dto.NeteaseLyric}
     * means "the song has no Netease-side lyrics".
     */
    public dev.t1m3.qplayer.netease.dto.NeteaseLyric lyric(long songId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("id", songId);
        // SPlayer sends each version flag = 0 ("give me whatever you have").
        body.put("lv", 0);
        body.put("kv", 0);
        body.put("tv", 0);
        body.put("rv", 0);
        body.put("yv", 0);
        JsonObject obj = weapiJson("song/lyric/v1", body);
        dev.t1m3.qplayer.netease.dto.NeteaseLyric out =
                new dev.t1m3.qplayer.netease.dto.NeteaseLyric();
        out.lrc     = extractLyricField(obj, "lrc");
        out.yrc     = extractLyricField(obj, "yrc");
        out.tlyric  = extractLyricField(obj, "tlyric");
        out.romalrc = extractLyricField(obj, "romalrc");
        return out;
    }

    /** Each lyric flavour comes back as {@code {"lyric": "...", "version": N}}. */
    private static String extractLyricField(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) return null;
        JsonObject node = root.getAsJsonObject(key);
        if (!node.has("lyric") || node.get("lyric").isJsonNull()) return null;
        String s = node.get("lyric").getAsString();
        return s.isEmpty() ? null : s;
    }

    // ---- Like / unlike, liked-list, recommendations, user detail (N8) ----

    /**
     * Toggle "liked" state for a song (heart icon in mini-player). Persists
     * server-side into the user's "我喜欢的音乐" playlist. Returns true if
     * the call succeeded ({@code code == 200}), false otherwise. The
     * response also carries {@code playlistId} of 我喜欢的, but callers
     * usually only care about the success flag.
     */
    public boolean like(long songId, boolean isLike) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("trackId", songId);
        body.put("like", isLike);
        body.put("alg", "itembased");
        body.put("time", "3");
        JsonObject obj = weapiJson("song/like", body);
        return obj.has("code") && obj.get("code").getAsInt() == 200;
    }

    /**
     * Fetch the set of song IDs the user has marked as "liked". Used to
     * paint the heart icon on track rows + mini-player without one
     * request per row. Empty set when not logged in.
     */
    public java.util.Set<Long> likedSongIds(long uid) throws IOException {
        if (uid == 0L) return java.util.Collections.emptySet();
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        JsonObject obj = weapiJson("song/like/get", body);
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (obj.has("ids") && obj.get("ids").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("ids")) {
                if (el.isJsonPrimitive()) out.add(el.getAsLong());
            }
        }
        return out;
    }

    /**
     * Daily personalised recommendations (the "每日推荐" feed). Up to ~30
     * tracks tied to the user's listening history; refreshes server-side
     * once a day. Returns an empty list when not logged in.
     */
    public List<NeteaseSong> recommendSongs() throws IOException {
        if (!isLoggedIn()) return java.util.Collections.emptyList();
        JsonObject obj = weapiJson("v1/discovery/recommend/songs", new HashMap<String, Object>());
        List<NeteaseSong> out = new ArrayList<>();
        // Newer schema: data.dailySongs[]. Legacy: recommend[].
        JsonArray arr = null;
        if (obj.has("data") && obj.get("data").isJsonObject()) {
            JsonObject data = obj.getAsJsonObject("data");
            if (data.has("dailySongs") && data.get("dailySongs").isJsonArray()) {
                arr = data.getAsJsonArray("dailySongs");
            }
        }
        if (arr == null && obj.has("recommend") && obj.get("recommend").isJsonArray()) {
            arr = obj.getAsJsonArray("recommend");
        }
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) out.add(parseSong(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * Full profile of the given uid — used for the sidebar account
     * display. Returns {@code null} when the API blocks us or uid is 0.
     */
    public dev.t1m3.qplayer.netease.dto.NeteaseUser userDetail(long uid) throws IOException {
        if (uid == 0L) return null;
        Map<String, Object> body = new HashMap<>();
        JsonObject obj = weapiJson("v1/user/detail/" + uid, body);
        if (!obj.has("profile") || !obj.get("profile").isJsonObject()) return null;
        JsonObject p = obj.getAsJsonObject("profile");
        dev.t1m3.qplayer.netease.dto.NeteaseUser out =
                new dev.t1m3.qplayer.netease.dto.NeteaseUser();
        out.uid = uid;
        if (p.has("nickname") && !p.get("nickname").isJsonNull())
            out.nickname = p.get("nickname").getAsString();
        if (p.has("avatarUrl") && !p.get("avatarUrl").isJsonNull())
            out.avatarUrl = p.get("avatarUrl").getAsString();
        if (p.has("vipType")) out.vipType = p.get("vipType").getAsInt();
        if (p.has("signature") && !p.get("signature").isJsonNull())
            out.signature = p.get("signature").getAsString();
        if (obj.has("level")) out.level = obj.get("level").getAsInt();
        return out;
    }

    /** Best-effort server-side logout. Local cookies are wiped regardless. */
    public void logout() {
        try {
            weapiCall("logout", new HashMap<String, Object>());
        } catch (Throwable e) {
            // Server may 301; that's fine, we clear locally anyway.
        }
        clearCookies();
    }

    // ---- QR login (N2) ----

    /**
     * Step 1 of QR login: ask the server for a fresh unikey. The user
     * encodes {@link #qrLoginContent(String)} into a QR image and scans
     * it with the Netease mobile app.
     */
    public String qrLoginKey() throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("type", 1);
        JsonObject obj = weapiJson("login/qrcode/unikey", body);
        if (!obj.has("unikey") || obj.get("unikey").isJsonNull()) {
            throw new IOException("qrLoginKey: no unikey in response: " + obj);
        }
        return obj.get("unikey").getAsString();
    }

    /**
     * The URL/text payload that the QR image must encode. Netease's
     * official client recognises this exact format; SPlayer / Binaryify
     * both use {@code /login/qr/create} to wrap this in a base64 PNG,
     * but it's just the same string under the hood and we can render
     * the QR ourselves later (saves a round-trip).
     */
    public String qrLoginContent(String unikey) {
        return "https://music.163.com/login?codekey=" + unikey;
    }

    /**
     * Render the QR for the given unikey as a square module matrix
     * ({@code true} = dark). Encoded locally via ZXing — netease's own
     * {@code /weapi/login/qrcode/get} now 404s, and SPlayer / Binaryify
     * generate the QR client-side from {@link #qrLoginContent(String)}.
     *
     * <p>Returns a {@code boolean[size][size]} grid the host renders itself
     * (QML draws rects, no platform image encoder needed), or {@code null}
     * if ZXing fails. The matrix is unpadded — callers add quiet-zone margin.
     */
    public boolean[][] qrMatrix(String unikey) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix bits = new QRCodeWriter().encode(
                    qrLoginContent(unikey), BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = bits.getWidth();
            int h = bits.getHeight();
            boolean[][] out = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[y][x] = bits.get(x, y);
                }
            }
            return out;
        } catch (Throwable e) {
            Logger.warn("qrMatrix failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Step 2: poll for QR scan status. Returns the netease status code:
     * <ul>
     *   <li>800 — key expired (need a new {@link #qrLoginKey()})</li>
     *   <li>801 — waiting for user to scan</li>
     *   <li>802 — scanned, waiting for user to confirm</li>
     *   <li>803 — success; Set-Cookie has captured MUSIC_U etc.</li>
     * </ul>
     */
    public int qrLoginCheck(String unikey) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("key", unikey);
        body.put("type", 1);
        JsonObject obj = weapiJson("login/qrcode/client/login", body);
        if (!obj.has("code")) {
            throw new IOException("qrLoginCheck: no code in response: " + obj);
        }
        return obj.get("code").getAsInt();
    }

    // ---- Cookie persistence ----

    public boolean hasCookie(String name) {
        return cookies.containsKey(name);
    }

    public boolean isLoggedIn() {
        return cookies.containsKey("MUSIC_U");
    }

    public void clearCookies() {
        cookies.clear();
        try {
            if (Files.exists(cookieFile)) Files.delete(cookieFile);
        } catch (IOException ignored) {}
    }

    private String cookieHeader() {
        if (cookies.isEmpty()) return "os=pc; appver=8.10.05";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        // Always advertise PC client flags so endpoints return the
        // higher-quality / fuller response variants.
        sb.append("; os=pc; appver=8.10.05");
        return sb.toString();
    }

    private void captureSetCookies(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return;
        boolean changed = false;
        for (String sc : setCookies) {
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';');
            if (eq <= 0) continue;
            String name = sc.substring(0, eq).trim();
            String val = sc.substring(eq + 1, semi > eq ? semi : sc.length()).trim();
            // Skip Set-Cookie attribute lines (Domain, Path, etc).
            if (name.equalsIgnoreCase("domain") || name.equalsIgnoreCase("path")
                    || name.equalsIgnoreCase("expires") || name.equalsIgnoreCase("max-age")
                    || name.equalsIgnoreCase("samesite")) continue;
            String prev = cookies.put(name, val);
            if (prev == null || !prev.equals(val)) changed = true;
        }
        if (changed) saveCookies();
    }

    private void loadCookies() {
        if (!Files.exists(cookieFile)) return;
        try {
            String txt = new String(Files.readAllBytes(cookieFile), StandardCharsets.UTF_8);
            if (txt == null || txt.trim().isEmpty()) return;
            JsonElement el = new JsonParser().parse(txt);
            if (!el.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    cookies.put(e.getKey(), e.getValue().getAsString());
                }
            }
            Logger.info("Netease: loaded {} cookies from disk", cookies.size());
        } catch (Exception e) {
            Logger.warn("Netease: cookie load failed: {}", e.getMessage());
        }
    }

    private void saveCookies() {
        try {
            new File(AppDirs.base()).mkdirs();
            Map<String, Object> out = new LinkedHashMap<>(cookies);
            Files.write(cookieFile, gson.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.warn("Netease: cookie save failed: {}", e.getMessage());
        }
    }

    // ---- HTTP helpers ----

    private static String formEncode(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEnc(e.getKey())).append('=').append(urlEnc(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
