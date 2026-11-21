package dev.t1m3.qplayer.android.audio.decode;

/**
 * 编码器延迟 / 填充（gapless）的记账（<strong>纯逻辑，不引用 {@code android.*}</strong>）。
 *
 * <p>对应上游 {@code Decoder} 的「<strong>无缝播放默认开启</strong>」那条硬约定：
 * 补偿编码器延迟与填充。这不是可选项——关掉它，MP3/AAC 会带上编码器延迟产生的静音，
 * 过渡引擎拿到的位置就是错的。
 *
 * <h3>数据从哪来、算不出来的地方怎么办</h3>
 *
 * <p>安卓的路子是 media3 的 {@code Format.encoderDelay} / {@code Format.encoderPadding}
 * （单位<strong>帧</strong>，即每声道的采样数，不是字节也不是跨声道采样数）：
 * media3 的提取器从 LAME/Xing 头（MP3）、iTunSMPB（AAC/M4A）、Opus pre-skip 这些地方读出来，
 * 认不出时字段是 {@code Format.NO_VALUE}——那就如实记成「不知道」，
 * {@link #note()} 会明说「首尾可能各带一小段静音」，
 * <strong>而不是假装已经补偿了</strong>（拿不到却报「已无缝」是这类功能最糟的失败模式）。
 *
 * <h3>为什么我们自己裁，而不是让解码器裁</h3>
 *
 * <p>平台<em>也</em>支持这件事：把 {@code MediaFormat} 的 {@code encoder-delay} /
 * {@code encoder-padding} 留在交给 {@code MediaCodec.configure} 的格式里，
 * AOSP 的软解码器（C2SoftAacDec / C2SoftMp3Dec 等）会自己把首尾裁掉。但那条路有两个问题：
 *
 * <ol>
 *   <li>「有没有生效」在 Java 侧看不出来——解码器裁了还是没裁，输出上很难分辨，
 *       于是我们无法如实报告，也无法把它算进 {@code durationSecs()} 与 {@code seek} 的账上；</li>
 *   <li>不同设备/不同解码器实现是否照做并不保证。</li>
 * </ol>
 *
 * <p>所以 {@code AndroidDecoder} <strong>根本不把这两个键写进 configure 的格式</strong>，
 * 由这一层按提取器给的值自己裁：一次且仅一次，账目清楚（{@code AndroidDecoder} 的类注释里
 * 也记着「绝不能双重裁剪」这件事）。media3 自己的播放链路也是这么分工的：解码器不裁，
 * 由消费端按帧数裁（{@code TrimmingAudioProcessor}）。
 *
 * <p>{@code containerFrames}（容器时间轴上的总帧数）的假设：容器的时长<strong>包含</strong>
 * 首部延迟与尾部填充，这符合 MP3（Xing 帧数含延迟）与 MP4（sample 时长总和含填充）的实际情况。
 * 所以可播放帧数 = 容器总帧数 − 延迟 − 填充。
 */
public final class AndroidGapless {

    /** 什么都没拿到、也不裁：容器既没给延迟也没给填充，且总帧数未知。 */
    public static final AndroidGapless UNKNOWN = new AndroidGapless(0L, 0L, null, false, false);

    private final long delayFrames;
    private final long paddingFrames;
    private final Long playableFrames;
    private final boolean delayKnown;
    private final boolean paddingKnown;

    private AndroidGapless(long delayFrames, long paddingFrames, Long playableFrames,
                           boolean delayKnown, boolean paddingKnown) {
        this.delayFrames = delayFrames;
        this.paddingFrames = paddingFrames;
        this.playableFrames = playableFrames;
        this.delayKnown = delayKnown;
        this.paddingKnown = paddingKnown;
    }

    /**
     * 从容器读到的三个值算出裁剪方案。
     *
     * @param encoderDelay   {@code KEY_ENCODER_DELAY}（帧），没有则 {@code null}
     * @param encoderPadding {@code KEY_ENCODER_PADDING}（帧），没有则 {@code null}
     * @param containerFrames 容器时间轴总帧数，未知则 {@code null}
     */
    public static AndroidGapless of(Integer encoderDelay, Integer encoderPadding, Long containerFrames) {
        boolean delayKnown = encoderDelay != null;
        boolean paddingKnown = encoderPadding != null;
        // 负数/垃圾值按 0 处理（当成「没给」）：宁可少裁也不要多裁——
        // 多裁就是凭空吃掉音乐的开头或结尾
        long delay = encoderDelay == null || encoderDelay.intValue() < 0 ? 0L : encoderDelay.longValue();
        long padding = encoderPadding == null || encoderPadding.intValue() < 0 ? 0L : encoderPadding.longValue();

        Long playable = null;
        if (containerFrames != null) {
            long total = containerFrames.longValue();
            if (total > delay + padding) {
                playable = Long.valueOf(total - delay - padding);
            }
            // else：元数据自相矛盾（总长比要裁掉的还短）。这时**不裁尾**：
            // 宁可多放一点填充，也不能因为一个坏字段把整首歌裁没
        }
        return new AndroidGapless(delay, padding, playable, delayKnown, paddingKnown);
    }

    /** 首部要丢掉的帧数（编码器延迟）。 */
    public long delayFrames() {
        return delayFrames;
    }

    /** 尾部要丢掉的帧数（编码器填充）。只在 {@link #playableFrames()} 可用时才有意义。 */
    public long paddingFrames() {
        return paddingFrames;
    }

    /**
     * 可播放帧数（裁剪后，从音乐第一帧算起）；未知时 {@code null}。
     *
     * <p>它同时是「已交付帧数」的上界：到这儿就停，剩下的都是编码器填充。
     */
    public Long playableFrames() {
        return playableFrames;
    }

    /** 两端的补偿信息是不是都拿到了（拿到才算「真补偿」）。 */
    public boolean isTrimKnown() {
        return delayKnown && paddingKnown;
    }

    /**
     * 定位到容器时间轴的某一帧之后，首部还要丢多少帧。
     *
     * <p>为什么需要它：跳到 <strong>0</strong> 的时候落点依然在编码器延迟区间里
     * （容器时间轴的 0 就是延迟的第一帧），那段延迟<strong>必须重新丢掉</strong>；
     * 而跳到 10 秒时早过了延迟区，一帧都不用丢。写死「已经裁过就不再裁」会让
     * 「拖回开头」多放一小段静音，这是同一类账目问题的另一个面。
     *
     * @param containerFrame 定位<strong>实际</strong>落点（容器时间轴）
     */
    public long skipAfterSeek(long containerFrame) {
        long remain = delayFrames - containerFrame;
        return remain > 0L ? remain : 0L;
    }

    /**
     * 定位落点对应的「已交付帧数」。
     *
     * <p>{@code decodeInto} 的尾部裁剪是靠「已交付帧数 vs {@link #playableFrames()}」比较的，
     * 所以 seek 之后这个计数必须换算到<strong>同一条轴</strong>上（裁剪后、从音乐第一帧算起），
     * 否则跳到后半段的歌会多放出一段填充（听感上像是尾巴上多了一截静音）。
     */
    public long framesDecodedAtSeek(long containerFrame) {
        long delivered = containerFrame - delayFrames;
        return delivered > 0L ? delivered : 0L;
    }

    /**
     * 给人看的一句实话（写进日志，也用于真机核对）。
     *
     * <p>拿不到就说拿不到，不要输出「已无缝」这种听起来很好但没做到的话。
     */
    public String note() {
        if (isTrimKnown()) {
            return "编码器延迟 " + delayFrames + " 帧 / 填充 " + paddingFrames + " 帧（已按容器给的值补偿）";
        }
        if (delayKnown || paddingKnown) {
            return "容器只给了部分无缝信息（延迟 " + (delayKnown ? Long.toString(delayFrames) : "未知")
                    + " 帧 / 填充 " + (paddingKnown ? Long.toString(paddingFrames) : "未知")
                    + " 帧）：未给的那一端无法补偿（已知缺口）";
        }
        return "容器没有提供编码器延迟/填充：首尾可能各带一小段静音（已知缺口，不假装已补偿）";
    }

    @Override
    public String toString() {
        return "AndroidGapless{delay=" + delayFrames + ", padding=" + paddingFrames
                + ", playable=" + playableFrames + ", known=" + isTrimKnown() + '}';
    }
}
