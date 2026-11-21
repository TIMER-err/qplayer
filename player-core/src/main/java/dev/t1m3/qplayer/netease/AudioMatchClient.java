package dev.t1m3.qplayer.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 听歌识曲: NetEase's audio-fingerprint matching endpoint, as the official client's own helper uses
 * it (round 37).
 *
 * <p>The contract below was read off the shipped helper and then confirmed by calling the endpoint:
 *
 * <ul>
 *   <li><b>POST {@code https://interface.music.163.com/api/music/audio/match}</b>,
 *   {@code application/x-www-form-urlencoded}. (GET with the same parameters works identically; POST
 *   is what the official extension does.)</li>
 *   <li>{@code sessionId} — any opaque string; it is echoed back as {@code queryId} and is not
 *   validated.</li>
 *   <li>{@code algorithmCode} — <b>{@code shazam_v2} is the only value that actually matches.</b> The
 *   other names that float around ({@code sha256}, {@code pm-delogo-dc}, {@code shazam}, …) are
 *   accepted and then silently answer {@code queryId:null} with no result — they are not errors, which
 *   is exactly why {@link #ALGORITHM} is a constant here rather than a caller's choice.</li>
 *   <li>{@code duration} — seconds the fingerprint covers. The official web demo sends 3, the
 *   extension 6; the endpoint accepts 0–30 and answers {@code {"code":400}} past that.</li>
 *   <li>{@code rawdata} — <b>base64 of the AFP fingerprint blob, not audio.</b> The blob comes from
 *   the shipped fingerprint engine ({@code afp.wasm}, wrapped by {@code afp.js}'s
 *   {@code GenerateFP}), which takes 8 kHz mono float32 samples.</li>
 *   <li>{@code times} — an attempt counter (1 or 2).</li>
 * </ul>
 *
 * <p><b>No cookie, no login and no header is checked</b> — the request works anonymously, which is why
 * this class carries no account state at all.
 *
 * <p>⚠️ <b>A miss is {@code code:200} with {@code data.result == null}</b> (with a non-zero
 * {@code noMatchReason}), so the answer is judged by the result and never by the status code.
 */
public final class AudioMatchClient {

    /** The one endpoint that answers, and the one algorithm that matches. */
    public static final String ENDPOINT = "https://interface.music.163.com/api/music/audio/match";
    public static final String ALGORITHM = "shazam_v2";

    /** What a hit is: the song the fingerprint matched, plus where in it the clip started. */
    public static final class Match {
        public final long id;
        public final String title;
        public final String artist;
        public final String album;
        public final String coverUrl;
        public final long durationMs;
        /** The matched clip's offset inside the track, ms — the server's own reading, and it can be
         *  negative. Reported because it is the only thing that says how confident the match is. */
        public final long startTimeMs;

        Match(long id, String title, String artist, String album, String coverUrl, long durationMs,
              long startTimeMs) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.coverUrl = coverUrl;
            this.durationMs = durationMs;
            this.startTimeMs = startTimeMs;
        }

        @Override
        public String toString() {
            return title + " - " + artist;
        }
    }

    private final int timeoutMs;

    public AudioMatchClient(int timeoutMs) {
        this.timeoutMs = Math.max(5_000, timeoutMs);
    }

    /** One attempt: the fingerprint and the seconds it covers. <b>{@code null} means "not
     *  recognised"</b> — a miss, an unreadable answer and an empty result are the same answer to the
     *  caller, and none of them is an exception. */
    public Match match(String fingerprintBase64, int durationSeconds) throws IOException {
        if (fingerprintBase64 == null || fingerprintBase64.trim().isEmpty()) return null;
        int seconds = Math.max(1, Math.min(30, durationSeconds));
        String body = "sessionId=" + enc(UUID.randomUUID().toString())
                + "&algorithmCode=" + enc(ALGORITHM)
                + "&duration=" + seconds
                + "&times=1"
                + "&decrypt=1"
                + "&rawdata=" + enc(fingerprintBase64);
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            String text = read(code >= 200 && code < 300 ? conn.getInputStream()
                    : conn.getErrorStream());
            if (text == null || text.trim().isEmpty()) return null;
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(text);
            } catch (RuntimeException unreadable) {
                // ⚠️ The endpoint answers `Content-Type: text/plain` even though the body is JSON, and
                // a gateway in front of it can answer HTML. Neither is a match.
                return null;
            }
            if (!parsed.isJsonObject()) return null;
            return parse(parsed.getAsJsonObject());
        } finally {
            conn.disconnect();
        }
    }

    /** The response's own shape: {@code data.result[i].song} — the legacy song shape ({@code artists},
     *  {@code duration}), not the {@code ar}/{@code dt} one the rest of this app parses. */
    static Match parse(JsonObject root) {
        JsonObject data = obj(root, "data");
        if (data == null) return null;
        JsonArray result = data.has("result") && data.get("result").isJsonArray()
                ? data.getAsJsonArray("result") : null;
        if (result == null || result.size() == 0) return null;
        JsonObject first = result.get(0).isJsonObject() ? result.get(0).getAsJsonObject() : null;
        if (first == null) return null;
        JsonObject song = obj(first, "song");
        if (song == null) return null;
        long id = song.has("id") && song.get("id").isJsonPrimitive() ? song.get("id").getAsLong() : 0L;
        if (id == 0L) return null;
        StringBuilder artists = new StringBuilder();
        if (song.has("artists") && song.get("artists").isJsonArray()) {
            for (JsonElement el : song.getAsJsonArray("artists")) {
                if (el.isJsonObject()) {
                    String name = str(el.getAsJsonObject(), "name");
                    if (name != null && !name.isEmpty()) {
                        if (artists.length() > 0) artists.append(" / ");
                        artists.append(name);
                    }
                }
            }
        }
        JsonObject album = obj(song, "album");
        String cover = album == null ? null : str(album, "picUrl");
        // http:// is what the endpoint returns; the app's image loader wants https.
        if (cover != null && cover.startsWith("http://")) cover = "https://" + cover.substring(7);
        long start = first.has("startTime") && first.get("startTime").isJsonPrimitive()
                ? first.get("startTime").getAsLong() : 0L;
        return new Match(id, str(song, "name"), artists.toString(),
                album == null ? "" : str(album, "name"),
                cover == null ? "" : cover,
                song.has("duration") && song.get("duration").isJsonPrimitive()
                        ? song.get("duration").getAsLong() : 0L,
                start);
    }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
    }

    private static String str(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return "";
        try {
            return parent.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) > 0) buffer.write(chunk, 0, n);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
