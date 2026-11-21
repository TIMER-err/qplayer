package dev.t1m3.qplayer.audio.mix;

import java.util.Locale;

/**
 * 一首曲目的「接缝特征」——一键排列的输入。
 *
 * <h3>它是什么</h3>
 *
 * <p>把一首歌压成七个数字，描述它<strong>开头接得上什么、结尾交给什么</strong>：
 *
 * <ul>
 *   <li>{@link #tempo}：整段（开头 {@link FlowAnalyzer#WHOLE_SECS} 秒）的速度</li>
 *   <li>{@link #headTempo} / {@link #tailTempo}：<strong>开头</strong>与<strong>结尾</strong>的速度。
 *       排歌关心的是接缝，不是「整首歌平均多快」——一首从 90 渐快到 140 的歌，
 *       拿平均值去接谁都接不准。</li>
 *   <li>{@link #level} / {@link #headLevel} / {@link #tailLevel}：均方根响度</li>
 *   <li>{@link #tone}：频谱质心（Hz），音色明暗</li>
 * </ul>
 *
 * <h3>「测不出」怎么表示——不许猜</h3>
 *
 * <p>与 {@link Analysis} / {@link Harmonic} 同一条纪律：
 *
 * <ul>
 *   <li>速度测不出（静音、自由速度、素材太短）→ <strong>{@code 0}</strong>。
 *       不编一个 120 出来：{@link FlowPlanner} 见到 0 就把该接缝的速度项记 0
 *       （中性），而不是照着一个假数字去惩罚或奖励。</li>
 *   <li>整首歌都读不到（解码失败、没有音源句柄）→ <strong>根本不给
 *       {@code FlowFeatures}</strong>，排队的那一位传 {@code null}
 *       （见 {@link FlowPlanner#plan}）。「没特征」与「特征恰好是 0」是两件事，
 *       混成一个会让「这首歌没分析出来」在结果里彻底看不见。</li>
 *   <li>结尾那一段整个读不到（文件没有总长头部，定位不到尾部）→
 *       {@link #tailLevel} 是 {@link Float#NaN}，{@link #tailTempo} 是 0。
 *       0 是「测不出速度」，NaN 是「这段根本不存在」——前者仍然算响度，
 *       后者连响度都没有。</li>
 * </ul>
 *
 * <h3>洁净室说明</h3>
 *
 * <p>本类与 {@link FlowPlanner} / {@link FlowAnalyzer} / {@link FlowSorter} 一起，
 * 依据 LiveSort（{@code https://github.com/LuN3cy/LiveSort}）<strong>公开的算法说明</strong>
 * 独立实现：那篇说明写明了「情绪贴合度（最高权重）+ BPM 衔接度（1:1 与 1:2 都算顺）
 * + 能量平滑度」三因子代价函数、以及「贪心逐位取代价最小」的算法类别。
 * <strong>未复制其源码、标识符、文件结构或测试数据</strong>——LiveSort 的仓库里
 * 没有 LICENSE 文件，按「保留所有权利」处理，因此这里做的是洁净室重实现，
 * 不是逐行移植（对照这件事实另见 {@code docs/audio-port.md} 里 Sonalis automix
 * 那条先例）。字段命名与公式写法均为本仓库自有；数值参数的来源逐条写在各常量上。
 */
public final class FlowFeatures {

    /** 整段速度（BPM）。{@code 0} 表示测不出。 */
    public final float tempo;

    /** 开头一段的速度（BPM）。{@code 0} 表示测不出。 */
    public final float headTempo;

    /** 结尾一段的速度（BPM）。{@code 0} 表示测不出（含「结尾段整个没读到」）。 */
    public final float tailTempo;

    /** 整段均方根响度（0..1 量级）。 */
    public final float level;

    /** 开头一段的均方根响度。 */
    public final float headLevel;

    /** 结尾一段的均方根响度；结尾段没读到时为 {@link Float#NaN}。 */
    public final float tailLevel;

    /** 频谱质心（Hz）。{@code 0} 表示素材太短、算不出来。 */
    public final float tone;

    /** 时长（秒）；未知时为 {@code 0}。只用于展示与日志。 */
    public final double seconds;

    public FlowFeatures(float tempo, float headTempo, float tailTempo,
                        float level, float headLevel, float tailLevel,
                        float tone, double seconds) {
        this.tempo = tempo;
        this.headTempo = headTempo;
        this.tailTempo = tailTempo;
        this.level = level;
        this.headLevel = headLevel;
        this.tailLevel = tailLevel;
        this.tone = tone;
        this.seconds = seconds;
    }

    /** 整段速度可用吗（不是「速度是多少」这个问题的答案）。 */
    public boolean hasTempo() {
        return tempo > 0.0f;
    }

    /**
     * 这首歌<strong>交出去</strong>的速度：下一首接在它后面时该对齐的那个数。
     *
     * <p>结尾段没读到、或速度测不出，都给 {@code 0}（= 不知道），
     * 让 {@link FlowPlanner#transitionCost} 把速度项记 0。
     */
    public float outgoingTempo() {
        return tailTempo > 0.0f ? tailTempo : 0.0f;
    }

    /** 这首歌<strong>接进来</strong>的速度：上一首的结尾要接到这个数上。 */
    public float incomingTempo() {
        return headTempo > 0.0f ? headTempo : 0.0f;
    }

    /**
     * 这首歌交出去的响度；结尾段没读到时为 {@link Float#NaN}。
     *
     * <p>响度是真的可能为 0（纯静音的结尾），所以「没有」只能用 NaN 表示。
     */
    public float outgoingLevel() {
        return tailLevel;
    }

    /** 这首歌接进来的响度。开头段一定读到了（否则不会有这个对象）。 */
    public float incomingLevel() {
        return headLevel;
    }

    /**
     * 一行可读说明。日志/排查用，不参与任何判定。
     *
     * <p>{@link Locale#ROOT}：默认区域会把小数点写成逗号（与
     * {@link Analysis.Tempo#describe} 同一处理）。
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "FlowFeatures { 速度 %.1f（头 %.1f / 尾 %.1f）, 响度 %.4f（头 %.4f / 尾 %s）, "
                        + "质心 %.0f Hz, 时长 %.1fs }",
                tempo, headTempo, tailTempo, level, headLevel,
                Float.isNaN(tailLevel) ? "无" : String.format(Locale.ROOT, "%.4f", tailLevel),
                tone, seconds);
    }
}
