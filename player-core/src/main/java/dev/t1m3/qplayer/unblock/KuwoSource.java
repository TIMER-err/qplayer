package dev.t1m3.qplayer.unblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.t1m3.qplayer.util.Logger;

/**
 * "kuwo" unblock source: search {@code search.kuwo.cn} for the track, validate
 * the hit against the wanted title/artist, then sign a {@code mobi.kuwo.cn}
 * query with {@link KuwoDES} to pull the playable url. Ported from SPlayer's
 * {@code electron/server/unblock/kuwo.ts}.
 */
final class KuwoSource {

    private KuwoSource() {}

    private static final String PACKAGE_NAME = "kwplayer_ar_5.1.0.0_B_jiakong_vh.apk";
    private static final Pattern URL_RE = Pattern.compile("http[^\\s$\"]+");

    /** Returns a playable url, or null when not found / not matched. */
    static String resolve(String keyword, String songName, String artist) {
        if (keyword == null || keyword.isEmpty()) return null;
        String rid = searchRid(keyword, songName, artist);
        if (rid == null) return null;
        try {
            String query = "corp=kuwo&source=" + PACKAGE_NAME
                    + "&p2p=1&type=convert_url2&sig=0&format=mp3&rid=" + rid;
            // mobi.kuwo.cn expects the raw base64 signature as the q value.
            String url = "http://mobi.kuwo.cn/mobi.s?f=kuwo&q=" + KuwoDES.encryptQuery(query);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "okhttp/3.10.0");
            String body = UnblockHttp.get(url, headers);
            if (body == null) return null;
            Matcher m = URL_RE.matcher(body);
            if (!m.find()) return null;
            String result = m.group();
            Logger.info("unblock[kuwo]: {}", result);
            return result;
        } catch (RuntimeException e) {
            Logger.warn("unblock[kuwo] url failed: {}", e.getMessage());
            return null;
        }
    }

    /** Search and return the matching kuwo rid (MUSICRID minus the "MUSIC_" prefix). */
    private static String searchRid(String keyword, String songName, String artist) {
        String url = "http://search.kuwo.cn/r.s?&correct=1&stype=comprehensive&encoding=utf8"
                + "&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=" + enc(keyword);
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
                if (musicRid == null || musicRid.isEmpty()) continue;
                if (MatchUtil.isMatch(str(item, "SONGNAME"), str(item, "ARTIST"), songName, artist)) {
                    return musicRid.substring("MUSIC_".length());
                }
            }
            Logger.warn("unblock[kuwo]: no result matched \"{}\"", songName);
            return null;
        } catch (RuntimeException e) {
            Logger.warn("unblock[kuwo] search parse failed: {}", e.getMessage());
            return null;
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
