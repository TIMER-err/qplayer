package dev.t1m3.qplayer.audio.model;

/**
 * ONNX 推理失败（上游 {@code sonalis-model/src/infer.rs} 的 {@code InferError}）。
 *
 * <h2>刻意区分阶段</h2>
 *
 * <p><b>加载失败</b>（文件读不了、图不合法）与<b>运行失败</b>（算子没实现、形状对不上）
 * 是完全不同的问题，修法也不同。所以上游那个 enum 的六个变体在这里一个不少地保留，
 * 由 {@link Kind} 表达，{@link #getMessage()} 的前缀与上游 {@code Display} 逐字对应。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>{@code Result<T, InferError>} → 抛本异常（与 {@code AudioException} 同一处理）。
 *       继承 {@link RuntimeException} 而不是受检异常：这条路径上的每一层调用方
 *       （{@code AiMix.prepare}、宿主工厂）都只会「记下来然后走降级」，
 *       受检异常只会逼出一堆空 catch。</li>
 *   <li>{@code enum InferError { Io(String), Load(String), .. }} → 一个异常类 +
 *       {@link Kind} 枚举 + 一段消息。<b>不</b>做成六个异常子类：上游的变体只是
 *       「给日志分类」用的标签，而分支处的处理完全一致。</li>
 *   <li>{@code impl fmt::Display} → {@link #getMessage()}（前缀在构造时就拼好，
 *       于是调用方拿到的字符串与上游 {@code format!("{e}")} 相同——
 *       {@code AiMixTest} 那几条断言依赖这一点）。</li>
 *   <li>{@code brief()} 是个模块私有 fn，{@code infer.rs} 内部用它截断长错误。
 *       这里做成公开静态方法：宿主实现（ONNX Runtime）的错误信息同样动辄上千字符
 *       （带整条 C++ 调用栈），必须走同一个截断，否则「真正有用的那半句会被淹掉」。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::infer::InferError}。
 */
public class InferException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败阶段。与上游 enum 的六个变体一一对应。 */
    public enum Kind {
        /** 文件不存在或读不了。 */
        IO("模型文件读不了："),
        /** ONNX 图不合法，或用了运行时不支持的算子。 */
        LOAD("ONNX 图无法加载："),
        /** 类型推断 / 图优化失败。 */
        SHAPE("形状推断失败："),
        /** 生成可执行计划失败。 */
        PLAN("无法生成执行计划："),
        /** 运行期失败。 */
        RUN("推理运行失败："),
        /** 输出张量的类型或形状不符合预期。 */
        OUTPUT("输出不符合预期：");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /** 上游 {@code Display} 里那个前缀。 */
        public String prefix() {
            return prefix;
        }
    }

    private final Kind kind;

    public InferException(Kind kind, String message) {
        super(kind.prefix() + message);
        this.kind = kind;
    }

    public InferException(Kind kind, String message, Throwable cause) {
        super(kind.prefix() + message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 文件不存在或读不了。 */
    public static InferException io(String message) {
        return new InferException(Kind.IO, message);
    }

    /**
     * 文件读不了，带底层原因。
     *
     * <p><b>本仓库新增的重载</b>：上游的 {@code InferError::Io(String)} 只有一段文本
     * （Rust 的 {@code io::Error} 在那边也被 {@code to_string()} 拍平了）。
     * Java 侧把 cause 留下来是值得的——{@code FileNotFoundException} /
     * {@code AccessDeniedException} 在日志里能直接指出是「没下」还是「没权限」，
     * 而这两件事的动作完全不同。
     */
    public static InferException io(String message, Throwable cause) {
        return new InferException(Kind.IO, message, cause);
    }

    /** ONNX 图不合法，或用了运行时不支持的算子。 */
    public static InferException load(String message) {
        return new InferException(Kind.LOAD, message);
    }

    /** 类型推断 / 图优化失败。 */
    public static InferException shape(String message) {
        return new InferException(Kind.SHAPE, message);
    }

    /** 生成可执行计划失败。 */
    public static InferException plan(String message) {
        return new InferException(Kind.PLAN, message);
    }

    /** 运行期失败。 */
    public static InferException run(String message) {
        return new InferException(Kind.RUN, message);
    }

    /** 输出张量的类型或形状不符合预期。 */
    public static InferException output(String message) {
        return new InferException(Kind.OUTPUT, message);
    }

    /**
     * 截断长错误。
     *
     * <p>推理运行时的错误带整条上下文链（常常上千字符，ONNX Runtime 更是直接
     * 掺进 C++ 侧的日志），直接透传到日志会把真正有用的那半句淹掉。
     * 只留第一行，再截到 300 个字符。与上游 {@code brief()} 逐条一致。
     */
    public static String brief(Throwable e) {
        return brief(e == null ? "" : String.valueOf(e.getMessage()));
    }

    /** 同 {@link #brief(Throwable)}，直接对一个字符串做截断。 */
    public static String brief(String message) {
        String s = message == null ? "" : message;
        int nl = s.indexOf('\n');
        String first = (nl >= 0 ? s.substring(0, nl) : s).trim();
        if (first.isEmpty()) {
            first = s.trim();
        }
        // 上游按 chars().count() 算长度（不是字节数）：中文错误信息按字节截会把
        // 一个字符劈成两半，输出里出现乱码。
        if (first.codePointCount(0, first.length()) > 300) {
            int end = first.offsetByCodePoints(0, 300);
            return first.substring(0, end) + "…";
        }
        return first;
    }
}
