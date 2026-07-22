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
    /** Mobile API host the official apps hit for eapi-encrypted endpoints. */
    private static final String EAPI_BASE = "https://interface.music.163.com";
    /** Newest mobile host: risk-controlled writes (playlist subscribe, ...) the
     *  official Android app encrypts with "xeapi". */
    private static final String XEAPI_BASE = "https://interface3.music.163.com";
    /** Android client UA for the xeapi path — matches the api-enhanced reference
     *  config that's verified to clear risk control (code 200) on these endpoints. */
    private static final String ANDROID_UA =
            "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0"
          + " (Linux; U; Android 14; 23013RK75C Build/UKQ1.230804.001)";
    private static final String XEAPI_APPVER = "9.1.65";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** User-Agent for the mobile (eapi) endpoints — matches osMap.iphone in the reference. */
    private static final String EAPI_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    /** APP_CONF.checkToken from NeteaseCloudMusicApi — the anti-cheat token the subscribe
     *  endpoint requires (sent both in the body and as the X-antiCheatToken header). */
    private static final String CHECK_TOKEN =
            "9ca17ae2e6ffcda170e2e6ee8af14fbabdb988f225b3868eb2c15a879b9a83d274a790ac8ff54a"
          + "97b889d5d42af0feaec3b92af58cff99c470a7eafd88f75e839a9ea7c14e909da883e83fb692a3"
          + "abdb6b92adee9e";
    /** A stable mainland-China client IP sent as X-Real-IP to relax risk control. */
    private static final String REAL_IP = randomChinaIp();

    private final Gson gson = new Gson();
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Path cookieFile;

    /** Registered xeapi session (anti-crawler key exchange). Lazily fetched,
     *  cached for the process: publicKey (base64 X25519), version, sk. */
    private volatile String xeapiPubKey;
    private volatile String xeapiVersion;
    private volatile String xeapiSk;

    /** Sink for netease's own failure reasons (privacy, risk control, ...) so the UI can
     *  surface them as a toast. Set by the controller. */
    public interface ErrorListener {
        void onError(String message);
    }

    private volatile ErrorListener errorListener;
    private volatile String lastErrorMsg;
    private volatile long lastErrorAt;

    public void setErrorListener(ErrorListener l) {
        this.errorListener = l;
    }

    // Forward a server failure reason, collapsing duplicates fired within a short window
    // (one user action often hits playlist/detail twice, so a private playlist would
    // otherwise toast the same line twice).
    private void reportError(String message) {
        if (message == null || message.isEmpty()) return;
        ErrorListener l = errorListener;
        if (l == null) return;
        long now = System.currentTimeMillis();
        if (message.equals(lastErrorMsg) && now - lastErrorAt < 2500) return;
        lastErrorMsg = message;
        lastErrorAt = now;
        l.onError(message);
    }

    private static String neteaseMessage(JsonObject obj) {
        for (String key : new String[]{"message", "msg"}) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String s = obj.get(key).getAsString();
                if (s != null && !s.isEmpty()) return s;
            }
        }
        return null;
    }

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
            // A mainland-China client IP relaxes Netease's risk control (the "当前环境
            // 异常" / 524 rejection on sensitive ops like favoriting).
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
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
        return weapiJson(path, json, true);
    }

    /** {@code reportErrors=false} silences the failure toast — for an attempt inside a
     *  fallback chain (e.g. radio/like tier 2, or the code-512 first try), where a later
     *  tier may still succeed and a toast would be a false alarm ("网络环境有风险" even
     *  though the song got favorited). Cookie-expiry handling still runs. */
    public JsonObject weapiJson(String path, Map<String, Object> json, boolean reportErrors)
            throws IOException {
        String resp = weapiCall(path, json);
        JsonElement el = new JsonParser().parse(resp);
        if (!el.isJsonObject()) throw new IOException("not a JSON object: " + truncate(resp, 200));
        JsonObject obj = el.getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 200;
        if (code == 301 && isLoggedIn() && !"login/qrcode/client/login".equals(path)) {
            Logger.warn("Netease: session expired (code 301) on /weapi/{} — clearing cookies", path);
            clearCookies();
        } else if (code != 200 && !path.startsWith("login/") && reportErrors) {
            // Any failure that carries a server reason (a private playlist, risk control,
            // ...) surfaces as a toast. Skip the login/qrcode flow: its 800-803 codes are
            // poll states ("waiting"/"scanned"), not errors.
            reportError(neteaseMessage(obj));
        }
        return obj;
    }

    /**
     * POST to the mobile {@code /eapi/<path>} host. {@code apiPath} is the
     * {@code /api/...} signing path the body is signed against (the request goes
     * to {@code /eapi/...} with the leading {@code /api} stripped). A device
     * {@code header} object is injected into the body and mirrored into the
     * Cookie header, exactly like the official apps. When {@code checkToken} is
     * set the anti-cheat token rides along in the header — required by
     * {@code playlist/subscribe}. Returns the raw response body as UTF-8.
     */
    public synchronized String eapiCall(String apiPath, Map<String, Object> json, boolean checkToken)
            throws IOException {
        ensureDeviceCookies();
        String csrf = cookies.getOrDefault("__csrf", "");
        String musicU = cookies.getOrDefault("MUSIC_U", "");

        // Mobile-client header: signed into the body AND sent as the Cookie. The
        // device fields mimic the iPhone client (matching EAPI_UA).
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("osver", "16.2");
        header.put("deviceId", cookies.getOrDefault("deviceId", ""));
        header.put("os", "iPhone OS");
        header.put("appver", "9.0.90");
        header.put("versioncode", "140");
        header.put("mobilename", "");
        header.put("buildver", Long.toString(System.currentTimeMillis() / 1000L));
        header.put("resolution", "1920x1080");
        header.put("__csrf", csrf);
        header.put("channel", "distribution");
        header.put("requestId", System.currentTimeMillis() + "_" + String.format(java.util.Locale.ROOT,
                "%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)));
        // api-enhanced puts the anti-cheat token in the header object, which becomes the
        // Cookie (request.js). NOTE: this is a STATIC, shared token — the official client
        // generates a valid device/session-bound one via its anti-cheat SDK, which we
        // can't replicate, so subscribe is inherently rate-limited under repeated use.
        if (checkToken) header.put("X-antiCheatToken", CHECK_TOKEN);
        if (!musicU.isEmpty()) header.put("MUSIC_U", musicU);

        Map<String, Object> body = json == null
                ? new HashMap<String, Object>() : new HashMap<String, Object>(json);
        body.put("header", header);

        String bodyJson = gson.toJson(body);
        Map<String, String> encrypted;
        try {
            encrypted = NeteaseCrypto.eapi(apiPath, bodyJson);
        } catch (Exception e) {
            throw new IOException("eapi crypto failed: " + e.getMessage(), e);
        }

        String tail = apiPath.startsWith("/api/") ? apiPath.substring(5) : apiPath;
        String urlStr = EAPI_BASE + "/eapi/" + tail;
        String form = formEncode(encrypted);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", EAPI_UA);
            conn.setRequestProperty("Referer", BASE);
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            conn.setRequestProperty("Cookie", headerCookie(header));

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
                Logger.warn("Netease HTTP {} on /eapi/{}: {}", code, tail, truncate(resp, 200));
                throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    /** {@link #eapiCall} + parse + the same toast/expiry handling as {@link #weapiJson}. */
    public JsonObject eapiJson(String apiPath, Map<String, Object> json, boolean checkToken)
            throws IOException {
        String resp = eapiCall(apiPath, json, checkToken);
        JsonElement el = new JsonParser().parse(resp);
        if (!el.isJsonObject()) throw new IOException("not a JSON object: " + truncate(resp, 200));
        JsonObject obj = el.getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 200;
        if (code == 301 && isLoggedIn()) {
            Logger.warn("Netease: session expired (code 301) on /eapi/{} — clearing cookies", apiPath);
            clearCookies();
        } else if (code != 200) {
            reportError(neteaseMessage(obj));
        }
        return obj;
    }

    /**
     * POST to the newest mobile {@code /xeapi/<path>} host. {@code apiPath} is the
     * {@code /api/...} path the body is built against (request goes to
     * {@code /xeapi/...} with the leading {@code /api} stripped). {@code formBody} is
     * the URL-encoded request payload (e.g. {@code "id=123456"}). A one-off
     * anti-crawler session key is registered on first use ({@link #ensureXeapiSession})
     * and the request is encrypted into the {@code B}/{@code S}/{@code R} form fields;
     * the response is AES-decrypted back to JSON. This is the path the official Android
     * app uses for risk-controlled writes that eapi can no longer clear (playlist
     * subscribe trips code 405 "操作过于频繁" on eapi). Returns the JSON response text.
     */
    public synchronized String xeapiCall(String apiPath, String formBody) throws IOException {
        ensureDeviceCookies();
        ensureXeapiSession();
        String deviceId = cookies.getOrDefault("deviceId", "");
        String musicU = cookies.getOrDefault("MUSIC_U", "");

        Map<String, String> bsr;
        try {
            bsr = NeteaseCrypto.xeapi(formBody, xeapiPubKey, xeapiVersion, xeapiSk, "android");
        } catch (Exception e) {
            throw new IOException("xeapi crypto failed: " + e.getMessage(), e);
        }

        String tail = apiPath.startsWith("/api/") ? apiPath.substring(5) : apiPath;
        String urlStr = XEAPI_BASE + "/xeapi/" + tail;
        String form = formEncode(bsr);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
            conn.setRequestProperty("User-Agent", ANDROID_UA);
            conn.setRequestProperty("X-Client-Enc-State", "ENCRYPTED");
            conn.setRequestProperty("x-aeapi", "true");
            conn.setRequestProperty("x-deviceid", deviceId);
            conn.setRequestProperty("x-os", "android");
            conn.setRequestProperty("x-osver", "16");
            conn.setRequestProperty("x-appver", XEAPI_APPVER);
            conn.setRequestProperty("x-sdeviceid", deviceId);
            conn.setRequestProperty("x-buildver", Long.toString(System.currentTimeMillis() / 1000L));
            if (!musicU.isEmpty()) conn.setRequestProperty("x-music-u", musicU);
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            conn.setRequestProperty("Cookie", xeapiCookieHeader());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            byte[] raw;
            try {
                raw = readAllBytes(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            captureSetCookies(conn.getHeaderFields().get("Set-Cookie"));

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /xeapi/{}: {} bytes", code, tail, raw.length);
                throw new IOException("HTTP " + code);
            }
            try {
                return NeteaseCrypto.xeapiResDecrypt(raw);
            } catch (Exception e) {
                throw new IOException("xeapi response decrypt failed: " + e.getMessage(), e);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** {@link #xeapiCall} + parse + the same toast/expiry handling as {@link #eapiJson}. */
    public JsonObject xeapiJson(String apiPath, String formBody) throws IOException {
        String resp = xeapiCall(apiPath, formBody);
        JsonElement el = new JsonParser().parse(resp);
        if (!el.isJsonObject()) throw new IOException("not a JSON object: " + truncate(resp, 200));
        JsonObject obj = el.getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 200;
        if (code == 301 && isLoggedIn()) {
            Logger.warn("Netease: session expired (code 301) on /xeapi/{} — clearing cookies", apiPath);
            clearCookies();
        } else if (code != 200) {
            reportError(neteaseMessage(obj));
        }
        return obj;
    }

    /** {@link #xeapiCall} returning just the response {@code code}, without the
     *  toast/expiry side effects of {@link #xeapiJson}. For callers that fall back
     *  to another transport on failure and don't want a premature error toast. */
    private int xeapiCode(String apiPath, String formBody) throws IOException {
        String resp = xeapiCall(apiPath, formBody);
        JsonElement el = new JsonParser().parse(resp);
        if (!el.isJsonObject()) return -1;
        JsonObject obj = el.getAsJsonObject();
        return obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
    }

    /**
     * Register a fresh xeapi anti-crawler session key (X25519 public key + sk +
     * version) via {@code /api/gorilla/anti/crawler/security/key/get}. Anonymous —
     * needs only a stable deviceId, no login. Cached for the process; a failure
     * leaves the cache empty so the next call retries.
     */
    private synchronized void ensureXeapiSession() throws IOException {
        if (xeapiPubKey != null && xeapiSk != null) return;
        String deviceId = cookies.getOrDefault("deviceId", "");
        String nonce = randomDigits(16);
        String ts = Long.toString(System.currentTimeMillis());
        String sig;
        try {
            sig = NeteaseCrypto.xeapiSign(ts, nonce);
        } catch (Exception e) {
            throw new IOException("xeapiSign failed: " + e.getMessage(), e);
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("appVersion", XEAPI_APPVER);
        form.put("currentKeyVersion", "");
        form.put("deviceId", deviceId);
        form.put("nonce", nonce);
        form.put("os", "android");
        form.put("requestType", "active");
        form.put("signature", sig);
        form.put("t1", "");
        form.put("t2", "");
        form.put("timestamp", ts);
        form.put("uid", "");

        String urlStr = EAPI_BASE + "/api/gorilla/anti/crawler/security/key/get";
        String body = formEncode(form);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        String resp;
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", ANDROID_UA);
            conn.setRequestProperty("Cookie", "deviceId=" + urlEnc(deviceId));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            if (code >= 400) throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
        } finally {
            conn.disconnect();
        }

        JsonObject obj = new JsonParser().parse(resp).getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 0;
        if (code != 200 || !obj.has("data") || !obj.get("data").isJsonObject()) {
            throw new IOException("xeapi key registration failed: " + truncate(resp, 200));
        }
        JsonObject data = obj.getAsJsonObject("data");
        if (!data.has("encryptedData") || data.get("encryptedData").isJsonNull()) {
            throw new IOException("xeapi key registration missing encryptedData");
        }
        String pkJson;
        try {
            pkJson = NeteaseCrypto.xeapiDecryptPublicKey(data.get("encryptedData").getAsString());
        } catch (Exception e) {
            throw new IOException("xeapi publicKey decrypt failed: " + e.getMessage(), e);
        }
        JsonObject pk = new JsonParser().parse(pkJson).getAsJsonObject();
        if (!pk.has("publicKey") || !pk.has("sk") || pk.get("publicKey").isJsonNull()
                || pk.get("sk").isJsonNull()) {
            throw new IOException("xeapi publicKey response missing publicKey/sk: " + truncate(pkJson, 200));
        }
        xeapiPubKey = pk.get("publicKey").getAsString();
        xeapiSk = pk.get("sk").getAsString();
        xeapiVersion = pk.has("version") && !pk.get("version").isJsonNull()
                ? pk.get("version").getAsString() : "";
        Logger.info("Netease: registered xeapi session key (version {})", xeapiVersion);
    }

    /** Cookie header for the xeapi path — mirrors {@link #cookieHeader()} but with
     *  the Android device identity that matches the {@code x-os}/{@code x-appver}
     *  headers (a mismatch itself trips risk control). */
    private String xeapiCookieHeader() {
        ensureDeviceCookies();
        Map<String, String> all = new LinkedHashMap<>(cookies);
        all.put("__remember_me", "true");
        all.put("ntes_kaola_ad", "1");
        all.put("_ntes_nnid", all.getOrDefault("_ntes_nuid", "") + "," + System.currentTimeMillis());
        all.put("WNMCID", wnmcid());
        all.put("WEVNSM", "1.0.0");
        all.put("NMTID", randomHex(16));
        all.put("os", "android");
        all.put("osver", "16");
        all.put("appver", XEAPI_APPVER);
        all.put("channel", "xiaomi");
        all.put("deviceId", cookies.getOrDefault("deviceId", ""));
        all.put("sDeviceId", cookies.getOrDefault("deviceId", ""));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String randomDigits(int n) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append((char) ('0' + r.nextInt(10)));
        return s.toString();
    }

    /** Cookie header built from the eapi {@code header} object. Values are written
     *  verbatim (like {@link #cookieHeader()} — MUSIC_U arrives pre-encoded from
     *  Set-Cookie and must not be re-encoded), with only bare spaces escaped so
     *  HttpURLConnection accepts the header (e.g. os "iPhone OS"). */
    private static String headerCookie(Map<String, Object> header) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : header.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            String val = String.valueOf(e.getValue()).replace(" ", "%20");
            sb.append(e.getKey()).append('=').append(val);
        }
        return sb.toString();
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
     * Hot search keywords for the search page (shown when input is empty).
     * Returns a list of keyword strings ordered by popularity.
     */
    public List<String> searchHot() throws IOException {
        // NeteaseCloudMusicApi uses /hotsearchlist/get for the hot-search list; the
        // older search/hot/detail returns nothing now. Both shape data[].searchWord.
        JsonObject obj = weapiJson("hotsearchlist/get", new HashMap<String, Object>());
        List<String> out = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("data")) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                if (item.has("searchWord") && !item.get("searchWord").isJsonNull()) {
                    out.add(item.get("searchWord").getAsString());
                }
            }
        }
        if (out.isEmpty()) {
            Logger.warn("hot search returned empty: {}", truncate(obj.toString(), 200));
        }
        return out;
    }

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
            // Cap to the requested limit: without a logged-in cookie the endpoint
            // ignores `limit` and can return the full pool (hundreds+), which the
            // home grid then instantiates as one PlaylistCard each -> OOM. We asked
            // for `limit`; never build more than that regardless of what comes back.
            for (JsonElement el : obj.getAsJsonArray("result")) {
                if (!el.isJsonObject()) continue;
                if (out.size() >= limit) break;
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
        return filterJunkNumericNames(out, keyword);
    }

    /** The legacy {@code search/get} endpoint occasionally mixes in low-quality UGC
     *  tracks whose {@code name} is literally just a number (e.g. a DJ/sample-pack
     *  index) — real, playable songs server-side, just noise for a text keyword
     *  search. Drop them, unless the user's own keyword is itself numeric (so
     *  searching "67" still finds a song actually named "67"). */
    private static List<NeteaseSong> filterJunkNumericNames(List<NeteaseSong> songs, String keyword) {
        if (keyword != null && keyword.trim().matches("\\d+")) return songs;
        List<NeteaseSong> out = new ArrayList<>(songs.size());
        for (NeteaseSong s : songs) {
            if (s.name != null && s.name.trim().matches("\\d+")) continue;
            out.add(s);
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
        // Match NeteaseCloudMusicApi's playlist/track/all: n=100000 forces the full
        // trackIds[] (a smaller n left another user's red-heart playlist's trackIds
        // empty), s=8 is the collector count the endpoint expects.
        detailBody.put("n", 100000);
        detailBody.put("s", 8);
        JsonObject detail = weapiJson("v6/playlist/detail", detailBody);
        List<NeteaseSong> out = new ArrayList<>();
        // No playlist object => netease refused it (e.g. creator set it private); weapiJson
        // has already surfaced the reason as a toast, so just return empty here.
        if (!detail.has("playlist") || !detail.get("playlist").isJsonObject()) return out;
        JsonObject pl = detail.getAsJsonObject("playlist");
        // Newer responses populate tracks[] directly when n is small enough. Require it
        // to be NON-EMPTY: another user's "喜欢的音乐" (red-heart) playlist comes back with
        // tracks:[] but a full trackIds[], and taking the empty fast path returned an
        // empty list (the playlist opened blank) instead of resolving the ids below.
        if (pl.has("tracks") && pl.get("tracks").isJsonArray()
                && pl.getAsJsonArray("tracks").size() > 0) {
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
        out.coverThumbPath = thumbUrl(out.coverUrl);
        return out;
    }

    /** CDN thumbnail URL (128×128) for a cover, or null. Used as a list-row
     *  Image.source so every list shows album art, not just search results. */
    public static String thumbUrl(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return null;
        return coverUrl + (coverUrl.contains("?") ? "&" : "?") + "param=128y128";
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
            if (c.has("userId") && !c.get("userId").isJsonNull())
                out.creatorUid = c.get("userId").getAsLong();
        }
        if (p.has("subscribed") && !p.get("subscribed").isJsonNull())
            out.subscribed = p.get("subscribed").getAsBoolean();
        return out;
    }

    /**
     * Collect (subscribe) or un-collect (unsubscribe) a playlist for the signed-in user.
     * True when code == 200.
     *
     * <p>The two directions ride different transports because Netease's risk control
     * diverges by direction:
     * <ul>
     *   <li><b>subscribe</b> → {@code /xeapi/playlist/subscribe}. The official Android
     *       app moved collecting onto the newest "xeapi" scheme; the old eapi endpoint
     *       now trips code 405 "操作过于频繁" regardless of the anti-cheat token (the
     *       hard-coded {@code checkToken} is stale server-side). xeapi clears it.</li>
     *   <li><b>unsubscribe</b> → {@code /eapi/playlist/unsubscribe}. Un-collecting is
     *       laxly controlled and keeps working over plain eapi with no token, so we
     *       leave it on the simpler path.</li>
     * </ul>
     */
    public boolean playlistSubscribe(long playlistId, boolean subscribe) throws IOException {
        // Matches api-enhanced's playlist_subscribe.js: both directions go through eapi
        // with the anti-cheat token forced on; subscribe also carries the token value in
        // the body. (An earlier attempt used xeapi for subscribe, which the server now
        // answers with "操作频繁".)
        String path = subscribe ? "/api/playlist/subscribe" : "/api/playlist/unsubscribe";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", playlistId);
        if (subscribe) body.put("checkToken", CHECK_TOKEN);
        JsonObject obj = eapiJson(path, body, true);
        return obj.has("code") && !obj.get("code").isJsonNull() && obj.get("code").getAsInt() == 200;
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
        // Tier 1: xeapi /radio/like — the official app's encrypted mobile path.
        // Single-song favoriting hits the same risk control that pushed playlist
        // subscribe onto xeapi, so try it here first. Quiet (no toast / no
        // exception): a non-200 or a transport error just falls through to weapi.
        try {
            int xc = xeapiCode("/api/radio/like",
                    "trackId=" + songId + "&like=" + isLike + "&alg=itembased&time=3");
            if (xc == 200) return true;
            Logger.warn("xeapi radio/like non-200 for {} (like={}): code {}", songId, isLike, xc);
        } catch (IOException e) {
            Logger.warn("xeapi radio/like failed for {} (like={}): {}", songId, isLike, e.getMessage());
        }
        // Tier 2: legacy weapi /radio/like. Quiet — toggleLike still has the playlist
        // fallback (setFavorite) after this, so a failure here must not toast.
        Map<String, Object> body = new HashMap<>();
        body.put("trackId", songId);
        body.put("like", isLike);
        body.put("alg", "itembased");
        body.put("time", "3");
        JsonObject obj = weapiJson("radio/like", body, false);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("netease radio/like failed for {} (like={}): {}",
                    songId, isLike, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    /** The user's "我喜欢的音乐" playlist id (their first owned playlist), or 0. */
    public long favoritePlaylistId(long uid) throws IOException {
        if (uid == 0L) return 0L;
        for (NeteasePlaylist p : userPlaylists(uid, 1000)) {
            if (p.creatorUid == uid) return p.id;
        }
        return 0L;
    }

    /** Add/remove a track to the "我喜欢的音乐" playlist. The reliable favorite path
     *  when {@link #like} hits risk control (code 524, "当前环境异常"). */
    public boolean setFavorite(long uid, long songId, boolean add) throws IOException {
        long pid = favoritePlaylistId(uid);
        if (pid == 0L) return false;
        return manipulatePlaylistTracks(pid, songId, add);
    }

    /**
     * Add or remove a single track to/from an arbitrary playlist owned by the
     * user via {@code playlist/manipulate/tracks}. Handles the server's code-512
     * quirk: it rejects a trackIds list that (after its own de-dup against the
     * playlist) collapses to one entry, so on 512 we retry with the id doubled —
     * the same workaround api-enhanced uses.
     */
    public boolean manipulatePlaylistTracks(long pid, long songId, boolean add) throws IOException {
        JsonObject obj = manipulateTracksOnce(pid, "[" + songId + "]", add);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code == 512) {
            obj = manipulateTracksOnce(pid, "[" + songId + "," + songId + "]", add);
            code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        }
        if (code != 200) {
            Logger.warn("playlist/manipulate/tracks failed pid={} track={} (add={}): {}",
                    pid, songId, add, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    private JsonObject manipulateTracksOnce(long pid, String trackIdsJson, boolean add) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("op", add ? "add" : "del");
        body.put("pid", pid);
        body.put("trackIds", trackIdsJson);
        body.put("imme", "true");
        // Quiet: manipulatePlaylistTracks retries on code 512, and setFavorite uses this
        // as the fallback tier of toggleLike — an intermediate failure must not toast.
        return weapiJson("playlist/manipulate/tracks", body, false);
    }

    /**
     * Create a new playlist. {@code privacy} true makes it a "隐私歌单" (10),
     * otherwise a normal public playlist (0). Returns the new playlist id, or
     * {@code 0} on failure.
     */
    public long createPlaylist(String name, boolean privacy) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("privacy", privacy ? "10" : "0");
        body.put("type", "NORMAL");
        JsonObject obj = weapiJson("playlist/create", body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) return 0L;
        if (obj.has("id") && !obj.get("id").isJsonNull()) return obj.get("id").getAsLong();
        if (obj.has("playlist") && obj.getAsJsonObject("playlist").has("id")) {
            return obj.getAsJsonObject("playlist").get("id").getAsLong();
        }
        return 0L;
    }

    /** Delete a playlist owned by the user via {@code playlist/remove}. */
    public boolean deletePlaylist(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", "[" + playlistId + "]");
        JsonObject obj = weapiJson("playlist/remove", body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("playlist/remove failed id={}: {}", playlistId, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    /**
     * Upload an image to netease's NOS (object storage) and return its image id,
     * for use with {@link #updatePlaylistCover}. Two steps, mirroring the official
     * web client: {@code nos/token/alloc} (weapi-encrypted, like every other call
     * here) hands back an upload token + object key, then the raw image bytes are
     * POSTed straight to the NOS host with that token — unlike every other write in
     * this client, that second request is neither weapi- nor eapi-encrypted, just
     * an authenticated binary upload. Returns {@code 0} on any failure.
     */
    public long uploadImage(byte[] imageBytes, String filename) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) return 0L;
        Map<String, Object> body = new HashMap<>();
        body.put("bucket", "yyimgs");
        body.put("ext", "jpg");
        body.put("filename", filename == null || filename.isEmpty() ? "cover.jpg" : filename);
        body.put("local", false);
        body.put("nos_product", 0);
        body.put("return_body", "{\"code\":200,\"size\":\"$(ObjectSize)\"}");
        body.put("type", "other");
        JsonObject obj = weapiJson("nos/token/alloc", body);
        if (!obj.has("result") || !obj.get("result").isJsonObject()) {
            Logger.warn("nos/token/alloc failed: {}", truncate(obj.toString(), 300));
            return 0L;
        }
        JsonObject result = obj.getAsJsonObject("result");
        if (!result.has("objectKey") || !result.has("token") || !result.has("docId")) {
            Logger.warn("nos/token/alloc missing fields: {}", truncate(result.toString(), 300));
            return 0L;
        }
        String objectKey = result.get("objectKey").getAsString();
        String token = result.get("token").getAsString();
        long docId = result.get("docId").getAsLong();

        String uploadUrl = "https://nosup-hz1.127.net/yyimgs/" + objectKey
                + "?offset=0&complete=true&version=1.0";
        HttpURLConnection c = (HttpURLConnection) new URL(uploadUrl).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("x-nos-token", token);
            c.setRequestProperty("Content-Type", "image/jpeg");
            try (OutputStream os = c.getOutputStream()) {
                os.write(imageBytes);
            }
            int status = c.getResponseCode();
            if (status / 100 != 2) {
                Logger.warn("NOS image upload failed: HTTP {}", status);
                return 0L;
            }
        } finally {
            c.disconnect();
        }
        return docId;
    }

    /** Bind a previously-uploaded image (see {@link #uploadImage}) as a playlist's cover. */
    public boolean updatePlaylistCover(long playlistId, long coverImgId) throws IOException {
        if (playlistId <= 0 || coverImgId <= 0) return false;
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId);
        body.put("coverImgId", coverImgId);
        JsonObject obj = weapiJson("playlist/cover/update", body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("playlist/cover/update failed id={}: {}", playlistId, truncate(obj.toString(), 300));
        }
        return code == 200;
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
        ensureDeviceCookies();
        Map<String, String> all = new LinkedHashMap<>(cookies);
        // Device-identity flags a real client sends. Without them Netease's risk
        // control rejects sensitive ops (favoriting) with code 524 "当前环境异常".
        all.put("__remember_me", "true");
        all.put("ntes_kaola_ad", "1");
        all.put("_ntes_nnid", all.getOrDefault("_ntes_nuid", "") + "," + System.currentTimeMillis());
        all.put("WNMCID", wnmcid());
        all.put("WEVNSM", "1.0.0");
        all.put("NMTID", randomHex(16));
        all.put("os", "pc");
        all.put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
        all.put("appver", "3.1.17.204416");
        all.put("channel", "netease");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Persist a stable deviceId + _ntes_nuid so the client fingerprint doesn't change
     *  between requests (a moving fingerprint itself trips risk control). */
    private void ensureDeviceCookies() {
        boolean changed = false;
        if (!cookies.containsKey("_ntes_nuid")) { cookies.put("_ntes_nuid", randomHex(32)); changed = true; }
        if (!cookies.containsKey("deviceId")) { cookies.put("deviceId", randomHex(16)); changed = true; }
        if (changed) saveCookies();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(b);
        StringBuilder s = new StringBuilder(bytes * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xf, 16));
            s.append(Character.forDigit(x & 0xf, 16));
        }
        return s.toString();
    }

    private static String wnmcid() {
        StringBuilder s = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) s.append((char) ('a' + r.nextInt(26)));
        return s.append('.').append(System.currentTimeMillis()).append(".01.0").toString();
    }

    /** A random IP in a mainland-China allocation (first octets from CN ranges). */
    private static String randomChinaIp() {
        int[] firsts = {36, 39, 42, 58, 59, 60, 101, 106, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 175, 180, 182, 183, 202, 203, 210, 211, 218, 219, 220, 221, 222, 223};
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        int a = firsts[r.nextInt(firsts.length)];
        return a + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
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
        return new String(readAllBytes(is), StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        if (is == null) return new byte[0];
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
