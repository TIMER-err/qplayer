package dev.t1m3.qplayer.audio.model;

/**
 * 清单里的一个模型条目（形状照 folia 的 {@code shared/modelManifest.json}）。
 *
 * <p>字段全部 {@code public final}：QML 桥是按<b>公开字段</b>反射读取的（没有 getter），
 * 而这份数据由清单解析产出、不对外修改，所以只能由 {@link ModelManifest} 构造。
 *
 * <h2>为什么 {@code sha256} 与 {@code bytes} 是并列的硬门槛</h2>
 *
 * <p>两个都要对：{@code bytes} 一次 stat 就能比，用来挡掉绝大多数不对的文件
 * （被截断的副本必然改大小）；{@code sha256} 才是「这一份就是那一份」的证明。
 * 只比大小的镜像替换、只比哈希的读整份文件都不划算，所以两道都留着。
 *
 * <p>{@link #license} 不是装饰：模型能不能由我们托管、能不能打包进成品，取决于它。
 * 两个条目都是 MIT——这正是「自己托管一份」的法律前提。
 */
public final class ModelManifestEntry {

    /** 模型名（也是本地目录里的键、界面上那一行的 id）。 */
    public final String name;

    /** 落地的文件名。**用清单里的名字而不是 {@code name + ".onnx"}**：名字是发行方定的，
     *  我们照抄一份，两边就不会各有一套命名规则。 */
    public final String file;

    /** 确切字节数。 */
    public final long bytes;

    /** 小写十六进制的 sha256。 */
    public final String sha256;

    /** 许可证（SPDX 标识或完整名字）。 */
    public final String license;

    /** 上游仓库或主页。 */
    public final String upstream;

    /** 这个模型打开了什么能力（例如 {@code beatGrid}、{@code stems}）。
     *  界面上「哪个功能依赖哪个模型」就靠它对上。对照物为空串（它不 gate 任何能力）。 */
    public final String enables;

    /**
     * 实测出来的<b>输入张量名与形状</b>（文字描述，给人看）。
     *
     * <p>这不是装饰：同一个模型的两次导出连输入名都不一样（folia 那份叫
     * {@code input_spectrogram}、{@code [1, -1, 128]}，Sonalis 那份叫 {@code spectrogram}、
     * {@code [-1, -1, 128]}），所以宿主实现必须<b>从会话元数据取输入名</b>。
     * 写死输入名的实现会在其中一份上直接失败——比这更糟的是有人把名字「猜对一半」，
     * 那时它会跑起来而结果是错的。所以这条数据是给实现与排错看的判据。
     */
    public final String input;

    /** 实测出来的输出张量名与形状（文字描述）。取输出<b>按名字</b>，不按位置。 */
    public final String output;

    /**
     * 这个条目自己的下载地址，按顺序试。空 = 用清单顶层的 {@code mirrors} 模板展开。
     *
     * <p>只有「不在那几条镜像上」的条目才自带地址（{@code reference} 里的
     * {@code mel_spectrogram} 就是这种）：镜像模板里只有 {@code {file}} 一处占位，
     * 而那三条路托管的是 folia 那两个权重。两种写法都遵守同一条规矩——
     * 按顺序试、第一个哈希匹配的胜出。
     */
    public final java.util.List<String> urls;

    /**
     * 这一份属于哪个<strong>模型组</strong>（同一组的文件要一起下才有意义）。
     *
     * <p>空串表示「自己就是一组」，也就是绝大多数条目（一个模型一个文件）。
     * 有分组的是那种「一个模型拆成好几个文件」的导出：ONNX 带外部权重的
     * {@code .onnx} + {@code .onnx.data} 是一对，少一个都跑不起来；
     * ACE-Step 那一套更是十几个文件（条件编码器、DiT、VAE、FSQ 表……）。
     *
     * <p>界面上按组渲染成<strong>一行</strong>：一个「下载」按钮、一条进度、
     * 一个「已就绪」状态。让用户自己点十几次、还得知道该点哪几个，是把实现细节
     * 推给了用户——而且少下一个的表现是「模型在磁盘上但加载失败」，最难查。
     */
    public final String bundle;

    /** 关于这一份权重的说明（版本、取舍、为什么是这个导出）。给人看。 */
    public final String note;

    ModelManifestEntry(String name, String file, long bytes, String sha256,
                       String license, String upstream, String enables, String input,
                       String output, java.util.List<String> urls, String note) {
        this(name, file, bytes, sha256, license, upstream, enables, input, output, urls, note, "");
    }

    ModelManifestEntry(String name, String file, long bytes, String sha256,
                       String license, String upstream, String enables, String input,
                       String output, java.util.List<String> urls, String note, String bundle) {
        this.name = name;
        this.file = file;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.license = license;
        this.upstream = upstream;
        this.enables = enables;
        this.input = input;
        this.output = output;
        this.urls = java.util.Collections.unmodifiableList(urls);
        this.note = note;
        this.bundle = bundle == null ? "" : bundle;
    }

    /** 这一份所属的组（没分组时就是它自己）。 */
    public String bundleId() {
        return bundle.isEmpty() ? name : bundle;
    }

    /** 人类可读的体积（界面显示用）。按 1024 进制，保留一位小数。 */
    public String sizeText() {
        double mb = bytes / 1048576.0;
        if (mb >= 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
    }

    /** 把一个字节数写成人类可读体积（与 {@link #sizeText()} 同一套进制）。 */
    public static String humanBytes(long bytes) {
        double mb = bytes / 1048576.0;
        if (mb >= 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
    }

    /** 与清单里其它条目是否指同一份文件（哈希一致）。 */
    public boolean sameBytesAs(ModelManifestEntry other) {
        return other != null && bytes == other.bytes && sha256.equals(other.sha256);
    }

    @Override
    public String toString() {
        return "ModelManifestEntry { name: \"" + name + "\", file: \"" + file + "\", bytes: "
                + bytes + ", sha256: \"" + sha256 + "\", license: \"" + license
                + "\", enables: \"" + enables + "\" }";
    }
}
