package dev.t1m3.qplayer.audio.mix;

/**
 * 一次调参解析失败：哪一个键不认识，或者值不合法。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::tuning::TuningError}——那边是
 * {@code struct TuningError { key, why }} + {@code impl Display}/{@code Error}，
 * 这边按 {@code docs/audio-port.md} 的类型映射做成异常
 * （{@code Result<T, TuningError>} → 抛异常，写法参考
 * {@code dev.t1m3.qplayer.audio.core.AudioException}）。
 *
 * <p>上游 {@code Display} 的文案原样保留：
 * {@code write!(f, "调参项 {} 不合法：{}", self.key, self.why)}——
 * 它同时也是 {@link #getMessage()}，因为调参的人看到的就只有这一行。
 *
 * <p>两个字段保持上游的 {@code pub} 语义（公开的只读字段），调用方要判断
 * 「错在哪个键」时直接读 {@link #key} 即可，不必去解析消息文本。
 *
 * <p>上游的 {@code #[derive(PartialEq, Eq)]} 在 Java 里不合成：异常按引用比较是惯例，
 * 而且上游的测试也没有拿两个 {@code TuningError} 做相等比较（只读 {@code key}/{@code why}）。
 */
public class TuningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 出错的键（或整段）。对应上游的 {@code key}。 */
    public final String key;

    /** 为什么不行。对应上游的 {@code why}。 */
    public final String why;

    public TuningException(String key, String why) {
        super("调参项 " + key + " 不合法：" + why);
        this.key = key;
        this.why = why;
    }
}
