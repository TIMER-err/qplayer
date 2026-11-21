package dev.t1m3.qplayer.audio.model;

/**
 * 模型清单 / 下载 / 本地缓存的失败（上游 {@code sonalis-model/src/model.rs} 的
 * {@code ModelError}）。
 *
 * <h2>为什么校验值不匹配要单独成一类</h2>
 *
 * <p>这是整条链上唯一真正重要的一步。文件被截断或被镜像替换时，前面每一步都会
 * 「成功」——只有校验能发现。所以它有自己的 {@link Kind#CHECKSUM_MISMATCH}
 * 与两个字段（{@link #expectedSha256()} / {@link #actualSha256()}），
 * 消息里也必须说清「<b>不会使用</b>」。
 *
 * <p>这条规矩不是洁癖：这台机器上已经实测过一次「从镜像下到的可执行文件体积正确、
 * 安装过程也成功，但数字签名是 NotSigned」。一个被替换过的二进制不会报错，
 * 它会<b>静默产出错误结果</b>——对模型来说就是「拍点悄悄变差」，几乎不可能被发现。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>{@code Result<T, ModelError>} → 抛本异常，理由同 {@link InferException}。</li>
 *   <li>上游 {@code NotDownloadable { id, reason }} / {@code ChecksumMismatch { expected,
 *       actual }} 是带数据的变体；Java 侧用 {@link Kind} 分类 + 额外字段承载数据
 *       （字段只在对应的 kind 上非 {@code null}）。</li>
 *   <li>上游 {@code TooLarge { got, limit }} 的两个数直接拼进消息（这一处不需要
 *       给调用方做判断，只需要人看得见）。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::model::ModelError}。
 */
public class ModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败类别。与上游 enum 的七个变体一一对应。 */
    public enum Kind {
        /** 清单本身有问题。 */
        CATALOG("模型清单有问题："),
        /** 清单里没有这个 id。 */
        UNKNOWN(""),
        /** 条目存在但不允许下载（缺地址或缺校验值）。 */
        NOT_DOWNLOADABLE(""),
        /** 传输失败。 */
        HTTP("下载失败："),
        /** 校验值不匹配。这是最要紧的一类错误：文件被截断或被替换了。 */
        CHECKSUM_MISMATCH(""),
        /** 响应过大，超出内存保护上限。 */
        TOO_LARGE(""),
        /** 本地文件操作失败。 */
        IO("本地文件操作失败："),
        /**
         * 一个可用的模型都没有（首选与第二选择都打不开）。
         *
         * <p><b>本仓库新增的变体</b>：上游的模型路线还没接进 automix
         * （{@code TransitionSpec::AiStyle} 的 {@code use_model} 在它那边只是一句注释），
         * 所以「两个都打不开」这件事在它那里还没有落点。理由与上游
         * {@code infer.rs} 退回分支那条一致：<b>两阶段的错误都要报</b>——
         * 只报后一个会掩盖真正的问题。消息里两个原因都在。
         */
        NOT_AVAILABLE("没有可用的拍点模型：");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /** 上游 {@code Display} 里那个前缀（有的变体自带完整句子，所以是空串）。 */
        public String prefix() {
            return prefix;
        }
    }

    private final Kind kind;

    /** {@link Kind#NOT_DOWNLOADABLE} 时的模型 id，其余为 {@code null}。 */
    private final String modelId;

    /** {@link Kind#CHECKSUM_MISMATCH} 时的期望值（小写十六进制），其余为 {@code null}。 */
    private final String expectedSha256;

    /** {@link Kind#CHECKSUM_MISMATCH} 时的实际值（小写十六进制），其余为 {@code null}。 */
    private final String actualSha256;

    private ModelException(Kind kind, String message, Throwable cause,
                           String modelId, String expectedSha256, String actualSha256) {
        super(message, cause);
        this.kind = kind;
        this.modelId = modelId;
        this.expectedSha256 = expectedSha256;
        this.actualSha256 = actualSha256;
    }

    public Kind kind() {
        return kind;
    }

    /** {@link Kind#NOT_DOWNLOADABLE} 时的模型 id，其余为 {@code null}。 */
    public String modelId() {
        return modelId;
    }

    /** 期望的 sha256，仅在 {@link Kind#CHECKSUM_MISMATCH} 时非 {@code null}。 */
    public String expectedSha256() {
        return expectedSha256;
    }

    /** 实际的 sha256，仅在 {@link Kind#CHECKSUM_MISMATCH} 时非 {@code null}。 */
    public String actualSha256() {
        return actualSha256;
    }

    /** 清单本身有问题。 */
    public static ModelException catalog(String message) {
        return new ModelException(Kind.CATALOG, Kind.CATALOG.prefix() + message, null,
                null, null, null);
    }

    /** 清单里没有这个 id。 */
    public static ModelException unknown(String id) {
        return new ModelException(Kind.UNKNOWN, "清单里没有模型 `" + id + "`", null,
                null, null, null);
    }

    /**
     * 条目存在但不允许下载。
     *
     * <p>缺地址与缺校验值必须<b>分开报</b>：缺地址是「我们还没找到」，
     * 缺校验值是「找到了但不敢用」。混成一句「不可用」会让排查时无从下手。
     */
    public static ModelException notDownloadable(String id, String reason) {
        return new ModelException(Kind.NOT_DOWNLOADABLE,
                "模型 `" + id + "` 暂时不能下载：" + reason, null, id, null, null);
    }

    /** 传输失败。 */
    public static ModelException http(String message) {
        return new ModelException(Kind.HTTP, Kind.HTTP.prefix() + message, null,
                null, null, null);
    }

    /** 传输失败，带底层原因。 */
    public static ModelException http(String message, Throwable cause) {
        return new ModelException(Kind.HTTP, Kind.HTTP.prefix() + message, cause,
                null, null, null);
    }

    /**
     * 校验值不匹配。消息必须说清「不会使用」。
     *
     * <p>上游用续行拼接（{@code \} 把多行接成一行），Java 这里同样是一段多行文本，
     * 断言 {@code contains("不会使用")} 与 {@code contains(expected)} 都能过。
     */
    public static ModelException checksumMismatch(String expected, String actual) {
        return new ModelException(Kind.CHECKSUM_MISMATCH,
                "校验值不匹配——文件已被截断或被替换，**不会使用**。\n"
                        + "期望 " + expected + "\n实际 " + actual,
                null, null, expected, actual);
    }

    /** 响应过大，超出内存保护上限。 */
    public static ModelException tooLarge(long got, long limit) {
        return new ModelException(Kind.TOO_LARGE,
                "响应 " + got + " 字节，超过上限 " + limit, null, null, null, null);
    }

    /** 本地文件操作失败。 */
    public static ModelException io(String message) {
        return new ModelException(Kind.IO, Kind.IO.prefix() + message, null,
                null, null, null);
    }

    /** 本地文件操作失败，带底层原因。 */
    public static ModelException io(String message, Throwable cause) {
        return new ModelException(Kind.IO, Kind.IO.prefix() + message, cause,
                null, null, null);
    }

    /** 一个可用的拍点模型都没有，两个原因都要在消息里（见 {@link Kind#NOT_AVAILABLE}）。 */
    public static ModelException notAvailable(String message) {
        return new ModelException(Kind.NOT_AVAILABLE, Kind.NOT_AVAILABLE.prefix() + message, null,
                null, null, null);
    }
}
