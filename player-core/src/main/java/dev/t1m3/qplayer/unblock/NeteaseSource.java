package dev.t1m3.qplayer.unblock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

import dev.t1m3.qplayer.util.Logger;

/**
 * "netease" unblock source: resolves the official song id straight to a CDN url
 * through GD音乐台 (music.gdstudio.xyz), which proxies the netease cloud-disk
 * endpoint. No search/match needed — the id is authoritative — so this is the
 * cheapest source and runs first.
 *
 * <p>Credit: SPlayer / @939163156 / GD音乐台. Learning use only.
 */
final class NeteaseSource {

    private NeteaseSource() {}

    private static final String API = "https://music-api.gdstudio.xyz/api.php";

    /** Returns a playable url for the netease song id, or null. */
    static String resolve(long songId) {
        if (songId <= 0) return null;
        String url = API + "?types=url&id=" + songId;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "qplayer/1.0");
        String body = UnblockHttp.get(url, headers);
        if (body == null) return null;
        try {
            JsonElement el = new JsonParser().parse(body);
            if (!el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            JsonElement u = obj.get("url");
            if (u == null || u.isJsonNull()) return null;
            String result = u.getAsString();
            if (result.isEmpty()) return null;
            Logger.info("unblock[netease]: {}", result);
            return result;
        } catch (RuntimeException e) {
            Logger.warn("unblock[netease] parse failed: {}", e.getMessage());
            return null;
        }
    }
}
