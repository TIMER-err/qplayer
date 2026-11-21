package dev.t1m3.qplayer.audio.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型清单：有哪些权重、从哪些镜像取、怎么知道手上这份是对的（形状照 folia 的
 * {@code shared/modelManifest.json}）。
 *
 * <h2>为什么清单是数据、而且两端共用一份</h2>
 *
 * <p>上游地址会变（仓库改名、改发布方式、镜像挂掉），校验值在发行方重新发布时也会变。
 * 把它们写死在代码里意味着<b>修好一个失效的地址要发一个新版本</b>。所以「哪份文件才是对的」
 * 只有一个描述：这份 JSON。构建机取权重与 App 里用户按需下载读的是同一份——
 * 两份描述迟早会不一致，而它们不一致时的表现方式是一个「自信地给出错误答案」的拍点。
 *
 * <h2>只留一份（本轮收敛掉的就是「两份」）</h2>
 *
 * <p>本轮之前这个仓库里有<b>两份</b>描述同一批模型的清单：Sonalis 形状的
 * {@code audio/model/models.json}（{@code id}/{@code urls}/{@code approx_bytes}/{@code risk}）
 * 与这份 folia 形状的 {@code /models/model-manifest.json}，两条路线各读一份。
 * <b>那份已经删掉</b>，拍点那条路现在也读这一份——理由是 folia 那句话：
 * <b>两份描述迟早会不一致，而它们不一致时的表现方式是一个「自信但错误的拍点」</b>。
 * 同理，下载只有 {@link ModelDownloads} 一份、目录只有 {@code AppDirs.modelsDir()} 一处。
 *
 * <p>为此补进来的字段：{@code enables}（它打开什么能力）与
 * {@code input}/{@code output}（<b>实测出来的</b>张量名与形状——同名文件的不同导出连输入名
 * 都不一样，所以实现必须从会话元数据取输入名、不能写死）。{@code reference} 数组用的是
 * 同一套字段与同一个解析器，只是它装的不是「要按需下载给用户用的权重」，
 * 而是像 {@code mel_spectrogram} 那样做参数标定对照的东西：它不进 {@link #models()}
 * （设置页那一列就是它），也不参与 {@code ModelAvailability}。
 *
 * <h2>解析即校验</h2>
 *
 * <p>不合法的清单在<b>读取时</b>就抛错，而不是等到下载完才发现没法校验：
 * 缺 {@code file}/{@code sha256}、sha256 不是 64 位十六进制、{@code bytes} 不是正数、
 * 名字重复（{@code models} 与 {@code reference} 一起查）、镜像模板里没有 {@code {file}}、
 * 自带的 {@code urls} 不是 https——每一条都会让后面那句「下载成功≠能用」变成空话。
 *
 * <p>{@link #embedded()} 每次调用都重新解析（几十 KB 的 JSON 在毫秒级完成）。
 * <b>不</b>做静态缓存：清单将来要支持从文件或远程覆盖，缓存会让「换了一份清单却没生效」
 * 变成排查黑洞。
 *
 * <p>对应 folia 的 {@code shared/modelManifest.json}；QPlayer 的落点是
 * {@code player-core/src/main/resources/models/model-manifest.json}。
 */
public final class ModelManifest {

    /** 内嵌清单的资源路径（<b>权重本身绝不在这里</b>，见 {@code ModelPackagingTest}）。 */
    public static final String RESOURCE = "/models/model-manifest.json";

    /** 镜像 URL 模板里的占位符，会被条目的 {@code file} 替换。 */
    public static final String FILE_PLACEHOLDER = "{file}";

    private final List<String> mirrors;
    private final List<ManualLink> manualLinks;
    private final String manualNote;
    private final List<ModelManifestEntry> models;
    private final List<ModelManifestEntry> references;

    private ModelManifest(List<String> mirrors, List<ManualLink> manualLinks, String manualNote,
                          List<ModelManifestEntry> models, List<ModelManifestEntry> references) {
        this.mirrors = Collections.unmodifiableList(mirrors);
        this.manualLinks = Collections.unmodifiableList(manualLinks);
        this.manualNote = manualNote;
        this.models = Collections.unmodifiableList(models);
        this.references = Collections.unmodifiableList(references);
    }

    /**
     * 内嵌清单（{@link #RESOURCE}）。
     *
     * @throws ModelException 资源缺失或 JSON 不合法
     */
    public static ModelManifest embedded() {
        InputStream in = ModelManifest.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw ModelException.catalog("内嵌清单资源缺失：" + RESOURCE);
        }
        Reader reader = null;
        try {
            reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(16 * 1024);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return parse(sb.toString());
        } catch (IOException e) {
            throw ModelException.catalog("内嵌清单读不了：" + e.getMessage());
        } finally {
            closeQuietly(reader);
            closeQuietly(in);
        }
    }

    /** 从 JSON 文本解析。 */
    public static ModelManifest parse(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) {
                throw ModelException.catalog("清单的顶层不是一个 JSON 对象");
            }
            root = parsed.getAsJsonObject();
        } catch (ModelException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ModelException.catalog(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        List<String> mirrors = new ArrayList<String>();
        JsonElement m = root.get("mirrors");
        if (m != null && !m.isJsonNull()) {
            if (!m.isJsonArray()) {
                throw ModelException.catalog("mirrors 不是数组");
            }
            JsonArray arr = m.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement item = arr.get(i);
                if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    throw ModelException.catalog("mirrors 里有非字符串项（第 " + i + " 个）");
                }
                String url = item.getAsString().trim();
                if (!url.startsWith("https://")) {
                    // 明文 http 下载上百兆的可执行内容没有可接受的理由
                    // （与「条目自带的 urls 也要过这一关」同一条）
                    throw ModelException.catalog("镜像不是 https：" + url);
                }
                if (url.indexOf(FILE_PLACEHOLDER) < 0) {
                    // 没有占位符的模板会把同一个文件当成每个模型发过来。
                    // 哈希那一关当然会挡住它，但那是下载完 108MB 之后的事了——
                    // 这种错在读取时就该报出来。
                    throw ModelException.catalog("镜像模板里没有 " + FILE_PLACEHOLDER + " ：" + url);
                }
                mirrors.add(url);
            }
        }

        JsonElement ms = root.get("models");
        if (ms == null || !ms.isJsonArray()) {
            throw ModelException.catalog("缺少 models 数组");
        }
        JsonArray modelsArr = ms.getAsJsonArray();
        List<ModelManifestEntry> models = new ArrayList<ModelManifestEntry>();
        for (int i = 0; i < modelsArr.size(); i++) {
            models.add(parseEntry(modelsArr.get(i), i, "models"));
        }
        if (models.isEmpty()) {
            throw ModelException.catalog("models 数组是空的");
        }

        // 对照物：同一套字段、同一套解析，但不进 models[]（设置页那一列就是 models[]），
        // 也不参与「谁能不能跑」。它在这里的理由是「哪份文件才是对的」只该有一个描述。
        List<ModelManifestEntry> references = new ArrayList<ModelManifestEntry>();
        JsonElement rs = root.get("reference");
        if (rs != null && !rs.isJsonNull()) {
            if (!rs.isJsonArray()) {
                throw ModelException.catalog("reference 不是数组");
            }
            JsonArray refArr = rs.getAsJsonArray();
            for (int i = 0; i < refArr.size(); i++) {
                references.add(parseEntry(refArr.get(i), i, "reference"));
            }
        }

        // 重名会让「按名字取」的结果取决于顺序，这种不确定性必须在加载时挡住。
        // models 与 reference 一起查：两个数组里各有一个同名条目，取到哪一个就说不清了。
        List<ModelManifestEntry> all = new ArrayList<ModelManifestEntry>(models);
        all.addAll(references);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                String a = all.get(i).name;
                String b = all.get(j).name;
                if (a.equals(b)) {
                    throw ModelException.catalog("模型名 `" + a + "` 重复了");
                }
                if (all.get(i).file.equals(all.get(j).file)) {
                    throw ModelException.catalog("两个条目指向同一个文件名 `"
                            + all.get(i).file + "`");
                }
            }
        }

        List<ManualLink> links = new ArrayList<ManualLink>();
        String note = "";
        JsonElement manual = root.get("manual");
        if (manual != null && manual.isJsonObject()) {
            JsonObject mo = manual.getAsJsonObject();
            note = optString(mo, "note");
            JsonElement ls = mo.get("links");
            if (ls != null && ls.isJsonArray()) {
                JsonArray arr = ls.getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    JsonElement item = arr.get(i);
                    if (item == null || !item.isJsonObject()) continue;
                    JsonObject o = item.getAsJsonObject();
                    String label = optString(o, "label");
                    String url = optString(o, "url");
                    // 空链接比没有链接更糟：界面上会出现一个点了没反应的入口
                    if (label.isEmpty() || url.isEmpty()) continue;
                    links.add(new ManualLink(label, url, optString(o, "code")));
                }
            }
        }

        return new ModelManifest(mirrors, links, note, models, references);
    }

    private static ModelManifestEntry parseEntry(JsonElement el, int idx, String where) {
        if (el == null || !el.isJsonObject()) {
            throw ModelException.catalog(where + " 的第 " + idx + " 个条目不是一个 JSON 对象");
        }
        JsonObject o = el.getAsJsonObject();
        String name = need(o, idx, "name");
        String file = need(o, idx, "file");
        String sha256 = need(o, idx, "sha256").trim().toLowerCase(java.util.Locale.ROOT);
        if (sha256.length() != 64 || !isHex(sha256)) {
            throw ModelException.catalog("第 " + idx + " 个条目（" + name
                    + "）的 sha256 不是 64 位十六进制：" + sha256);
        }
        if (file.indexOf('/') >= 0 || file.indexOf('\\') >= 0 || file.startsWith(".")) {
            throw ModelException.catalog("第 " + idx + " 个条目（" + name
                    + "）的 file 不是一个纯文件名：" + file);
        }
        JsonElement b = o.get("bytes");
        if (b == null || !b.isJsonPrimitive() || !b.getAsJsonPrimitive().isNumber()) {
            throw ModelException.catalog("第 " + idx + " 个条目（" + name + "）缺少数字字段 bytes");
        }
        long bytes = b.getAsLong();
        if (bytes <= 0L) {
            throw ModelException.catalog("第 " + idx + " 个条目（" + name + "）的 bytes 不是正数");
        }
        return new ModelManifestEntry(name, file, bytes, sha256,
                need(o, idx, "license"), need(o, idx, "upstream"),
                optString(o, "enables"), optString(o, "input"), optString(o, "output"),
                urls(o, idx, name), optString(o, "note"), optString(o, "bundle"));
    }

    /**
     * 条目自带的下载地址（可选）。
     *
     * <p>只有「不在顶层镜像上」的条目才写它——镜像模板里只有 {@code {file}} 一处占位，
     * 而那几条路托管的是 folia 那两个权重。所以一条自带的地址也要过同一关：
     * 必须是 https（明文下载上百兆的可执行内容没有可接受的理由），
     * 而且不能是空串（一个点了没反应的地址比没有地址更糟）。
     */
    private static List<String> urls(JsonObject o, int idx, String name) {
        List<String> out = new ArrayList<String>();
        JsonElement u = o.get("urls");
        if (u == null || u.isJsonNull()) {
            return out;
        }
        if (!u.isJsonArray()) {
            throw ModelException.catalog("第 " + idx + " 个条目（" + name + "）的 urls 不是数组");
        }
        JsonArray arr = u.getAsJsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw ModelException.catalog("第 " + idx + " 个条目（" + name
                        + "）的 urls 里有非字符串项");
            }
            String url = item.getAsString().trim();
            if (!url.startsWith("https://")) {
                throw ModelException.catalog("第 " + idx + " 个条目（" + name
                        + "）的 url 不是 https：" + url);
            }
            out.add(url);
        }
        return out;
    }

    private static String need(JsonObject o, int idx, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()
                || v.getAsString().trim().isEmpty()) {
            throw ModelException.catalog("第 " + idx + " 个条目缺少非空字符串字段 `" + key + "`");
        }
        return v.getAsString().trim();
    }

    private static String optString(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
            return "";
        }
        return v.getAsString();
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 镜像模板，按顺序试（第一个通常是国内唯一可达的那条）。 */
    public List<String> mirrors() {
        return mirrors;
    }

    /** 手动兜底链接。空表示界面根本不提供手动那条路。 */
    public List<ManualLink> manualLinks() {
        return manualLinks;
    }

    /** 手动兜底那一块的补充说明（可为空串）。 */
    public String manualNote() {
        return manualNote;
    }

    /**
     * 要按需下载给用户用的权重，顺序就是设置页上的顺序。
     *
     * <p><b>不含对照物</b>（{@link #references()}）：那一类不 gate 任何能力，
     * 也不该在「模型」那一列里多出一行让用户下 270 KB 的对照文件。
     */
    public List<ModelManifestEntry> models() {
        return models;
    }

    /**
     * 对照物：与 {@link #models()} 同一套字段，但不进设置页那一列、也不参与可用性。
     *
     * <p>目前只有 {@code mel_spectrogram}（Beat This! 官方 log-mel 前端，
     * 用来标定纯 Java 的 {@code MelFrontend} / {@code MelParams.beatThis()}）。
     * 它不在 folia 的清单里，所以自带 {@code urls}。
     */
    public List<ModelManifestEntry> references() {
        return references;
    }

    /** 清单里的全部条目（{@link #models()} 在前，对照物在后）。 */
    public List<ModelManifestEntry> allEntries() {
        List<ModelManifestEntry> out = new ArrayList<ModelManifestEntry>(models);
        out.addAll(references);
        return Collections.unmodifiableList(out);
    }

    /**
     * 按名字查，查不到返回 {@code null}。
     *
     * <p><b>对照物也查得到</b>：拍点那条路要能按名字取到 {@code mel_spectrogram}
     * （参数标定与核对要用它），而这与「设置页上显示哪几行」是两件事。
     */
    public ModelManifestEntry get(String name) {
        if (name == null) {
            return null;
        }
        for (ModelManifestEntry e : models) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        for (ModelManifestEntry e : references) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** 按名字查，查不到抛 {@link ModelException}。 */
    public ModelManifestEntry require(String name) {
        ModelManifestEntry e = get(name);
        if (e == null) {
            throw ModelException.unknown(name);
        }
        return e;
    }

    /**
     * 把镜像模板展开成一个具体 URL。
     *
     * <p>只做 {@code {file}} 一处替换，什么都不做 URL 编码：文件名来自我们自己的清单，
     * 按上面的校验它只能是纯文件名，不会带需要转义的字符。
     */
    public static String expand(String template, ModelManifestEntry entry) {
        return template.replace(FILE_PLACEHOLDER, entry.file);
    }

    /** 这条 URL 的主机名（错误信息与「用了哪个镜像」都用它，不把整条长 URL 塞进界面）。 */
    public static String hostOf(String url) {
        int idx = url.indexOf("//");
        String rest = idx >= 0 ? url.substring(idx + 2) : url;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 关一个只读资源失败没有任何补救动作，也不该盖掉真正的错误
        }
    }

    @Override
    public String toString() {
        return "ModelManifest { models: " + models.size() + " 个, mirrors: " + mirrors.size()
                + " 条, manual: " + manualLinks.size() + " 条 }";
    }

    /** 手动兜底的一条链接。提取码是链接自己的字段（不同网盘的码不共用）。 */
    public static final class ManualLink {

        public final String label;
        public final String url;
        /** 提取码；没有就是空串。 */
        public final String code;

        ManualLink(String label, String url, String code) {
            this.label = label;
            this.url = url;
            this.code = code;
        }

        @Override
        public String toString() {
            return label + " " + url + (code.isEmpty() ? "" : "（提取码 " + code + "）");
        }
    }
}
