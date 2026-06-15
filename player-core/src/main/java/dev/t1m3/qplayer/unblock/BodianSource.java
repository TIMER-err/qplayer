package dev.t1m3.qplayer.unblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import dev.t1m3.qplayer.util.Logger;

/**
 * "bodian" (波点音乐) unblock source: reuses the kuwo search to find the rid,
 * then hits the bodian app api {@code bd-api.kuwo.cn} with an MD5-signed query.
 * Ported from SPlayer's {@code electron/server/unblock/bodian.ts}.
 */
final class BodianSource {

    private BodianSource() {}

    private static final String DEVICE_ID =
            Long.toString(Math.abs(new Random(System.currentTimeMillis()).nextLong()) % 100000000000L);
    private static final String AUDIO_PATH = "/api/play/music/v2/audioUrl";

    /** Returns a playable url, or null. */
    static String resolve(String keyword, String songName, String artist) {
        if (keyword == null || keyword.isEmpty()) return null;
        String rid = searchRid(keyword, songName, artist);
        if (rid == null) return null;
        try {
            sendAdFreeRequest(); // best-effort, ignore result
            String audioUrl = "http://bd-api.kuwo.cn/api/play/music/v2/audioUrl?&br=320kmp3&musicId=" + rid;
            audioUrl = generateSign(audioUrl);
            Map<String, String> headers = new HashMap<>();
            headers.put("user-agent", "Dart/2.19 (dart:io)");
            headers.put("plat", "ar");
            headers.put("channel", "aliopen");
            headers.put("devid", DEVICE_ID);
            headers.put("ver", "3.9.0");
            headers.put("host", "bd-api.kuwo.cn");
            headers.put("X-Forwarded-For", "1.0.1.114");
            String body = UnblockHttp.get(audioUrl, headers);
            if (body == null) return null;
            JsonObject obj = new JsonParser().parse(body).getAsJsonObject();
            JsonObject data = obj.getAsJsonObject("data");
            if (data == null) return null;
            JsonElement u = data.get("audioUrl");
            if (u == null || u.isJsonNull()) return null;
            String result = u.getAsString();
            if (result.isEmpty()) return null;
            Logger.info("unblock[bodian]: {}", result);
            return result;
        } catch (RuntimeException e) {
            Logger.warn("unblock[bodian] url failed: {}", e.getMessage());
            return null;
        }
    }

    private static String searchRid(String keyword, String songName, String artist) {
        String kw = keyword.replace(" - ", " ");
        String url = "http://search.kuwo.cn/r.s?&correct=1&vipver=1&stype=comprehensive&encoding=utf8"
                + "&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=" + enc(kw);
        String body = UnblockHttp.get(url, null);
        if (body == null) return null;
        try {
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonArray content = root.getAsJsonArray("content");
            if (content == null || content.size() < 2) return null;
            JsonObject page = content.get(1).getAsJsonObject().getAsJsonObject("musicpage");
            if (page == null) return null;
            JsonArray list = page.getAsJsonArray("abslist");
            if (list == null || list.size() < 1) return null;
            for (JsonElement el : list) {
                JsonObject item = el.getAsJsonObject();
                String musicRid = str(item, "MUSICRID");
                if (musicRid.isEmpty()) continue;
                if (MatchUtil.isMatch(str(item, "SONGNAME"), str(item, "ARTIST"), songName, artist)) {
                    int us = musicRid.lastIndexOf('_');
                    return us >= 0 ? musicRid.substring(us + 1) : musicRid;
                }
            }
            Logger.warn("unblock[bodian]: no result matched \"{}\"", songName);
            return null;
        } catch (RuntimeException e) {
            Logger.warn("unblock[bodian] search parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** Append {@code &timestamp=...&sign=md5(...)} to the request, matching bodian's scheme. */
    private static String generateSign(String str) {
        long now = System.currentTimeMillis();
        str += "&timestamp=" + now;
        String query = str.substring(str.indexOf('?') + 1).replaceAll("[^a-zA-Z0-9]", "");
        char[] chars = query.toCharArray();
        Arrays.sort(chars);
        String data = "kuwotest" + new String(chars) + AUDIO_PATH;
        return str + "&sign=" + md5(data);
    }

    private static void sendAdFreeRequest() {
        try {
            String adUrl = "http://bd-api.kuwo.cn/api/service/advert/watch"
                    + "?uid=-1&token=&timestamp=1724306124436&sign=15a676d66285117ad714e8c8371691da";
            Map<String, String> headers = new HashMap<>();
            headers.put("user-agent", "Dart/2.19 (dart:io)");
            headers.put("plat", "ar");
            headers.put("channel", "aliopen");
            headers.put("devid", DEVICE_ID);
            headers.put("ver", "3.9.0");
            headers.put("host", "bd-api.kuwo.cn");
            headers.put("qimei36", "1e9970cbcdc20a031dee9f37100017e1840e");
            String data = "{\"type\":5,\"subType\":5,\"musicId\":0,\"adToken\":\"\"}";
            UnblockHttp.post(adUrl, data, "application/json; charset=utf-8", headers);
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
