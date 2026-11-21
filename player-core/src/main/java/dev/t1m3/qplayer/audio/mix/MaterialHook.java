package dev.t1m3.qplayer.audio.mix;

import java.util.Map;

/**
 * <b>素材钩子</b>（2026-10-07，目标①②路径 A 的接口）。
 *
 * <p>为什么需要它：朴素融合（以及他真正用的 `FILTER_EXPANSION`）那一对的音频，
 * 是渲染直接拿**播放引擎交出来的素材**做的 —— 素材漏斗、`SegmentFeed`、`readFeed`
 * 三条路都到不了它（已逐条证伪）。所以唯一能让**所有接法**都受益的落点，
 * 是播放引擎交付素材的那一处（`Deck` 的解码缓冲）。
 *
 * <p>但 `Deck` 不该知道"过渡""叠唱区间"这些概念（它是播放引擎的零件）⇒ 用钩子：
 * <b>策略留在上层（transition 装钩子）、取素材留下层（Deck 问钩子）</b>。
 *
 * <p>实现方（见 {@link WindowMaterial#process}）只做三件事：分轨 + 只在叠唱区间压人声
 * + 整段一次响度对齐；<b>算不出来就返回 {@code null} ⇒ Deck 原样交付</b>
 * （安全默认：与没有这个功能逐位相同）。
 */
public interface MaterialHook {

    /**
     * 把"刚解码出来的一段素材"变成"该交给渲染的那一份"。
     *
     * @param source    音源（与 {@code Decoder.open} 拿到的同一个）
     * @param headers   请求头（可为 null）
     * @param startSecs 这一段在源里的起点（秒）
     * @param frames    帧数
     * @param rate      采样率
     * @return 处理后的素材；{@code null} ⇒ 原样（**不猜、也不半途而废**）
     */
    float[][] transform(String source, Map<String, String> headers,
                        double startSecs, int frames, int rate);

    /**
     * <b>就地处理"刚解码出来的这一块"</b>（路径 A 在 {@code Deck} 里的落点）。
     *
     * <p>为什么是这个契约而不是"重读文件"：{@code Deck} 填缓冲的那一处
     * （{@code Deck.java} 里 {@code decoder.decodeInto(decBuf)} 之后）**未必**拿得到
     * source/headers，而且再解一次码既浪费又可能得到不同的字节 ✗。
     * 所以钩子直接改手上这一块 —— 也是"一次且只有一次"的最自然形式 ✓。
     *
     * <p>{@code buf} 是**平面布局**（{@code base = 声道号 × capacityFrames}）。
     * 实现方**必须**只改前 {@code frames} 帧、且改不动时**原样留着**（不猜、不半途而废）。
     *
     * @param startSecs 这一块在源里的起点（秒；未知传 {@link Double#NaN}）
     */
    default void onDecoded(dev.t1m3.qplayer.audio.core.AudioBuf buf, int frames,
                           double startSecs) {
        // 默认不动（既有实现不受影响）
    }
}
