package dev.t1m3.qplayer.audio.model;

/**
 * 分轨模型：把一段音乐拆成「人声」与「伴奏（其余全部）」。
 *
 * <p>用途见 automix 的融合阶段：只合并背景声，人声在过渡结尾渐恢复——所以这里只关心
 * <strong>两条轨</strong>，不暴露上游模型内部那四轨（drums / bass / other / vocals）。
 * 伴奏 = 除人声之外的全部，由实现自己相加得到；调用方不该知道模型的轨数。
 *
 * <h2>为什么这个包在 player-core、而实现不在</h2>
 *
 * <p>与 {@link BeatDetector} 同一条理由：推理运行时（ONNX Runtime）是**宿主的依赖**，
 * 桌面是 {@code onnxruntime} jar、安卓是 AAR，而 player-core 必须保持 Java 8 且不背原生依赖。
 * 所以这里只有接缝，实现由宿主安装（见 {@link StemModels#install}）。
 *
 * <h2>代价必须先说清</h2>
 *
 * <p>这不是一个便宜的操作：模型本身上百兆，一次分离的峰值内存按上游实测可达数百 MB
 * （folia 用 onnxruntime 的 Python 专属开关 {@code enable_mem_reuse=False} 把一次分离从
 * 2543MB 压到 500MB 以下；Java 侧有没有等价手段必须实测，不能假设）。因此：
 *
 * <ul>
 *   <li>调用它的是 <strong>异步的 {@code prepare}</strong>，绝不在渲染路径上；</li>
 *   <li>实现要能被取消，且失败要如实抛 {@link InferException}，<strong>不许</strong>用一段静音
 *       或原始音频冒充"分离结果"——那样接缝会静默变成"没有人声去除"，而用户以为开了；</li>
 *   <li>只对**过渡窗口那几秒**做，不对整首歌做。</li>
 * </ul>
 */
public interface StemSeparator extends AutoCloseable {

    /**
     * 分离一段立体声素材。
     *
     * <p>输入与输出的<strong>长度必须一致</strong>（按采样点计），采样率也保持一致：调用方要按
     * 时间对齐把两条轨混回去，任何长度差都会让接缝处错位。实现内部要做的重采样
     * （模型通常固定在 44.1 kHz 立体声）由实现自己负责，调用方不该知道模型要什么采样率。
     *
     * @param left          左声道（长度 = 帧数）
     * @param right         右声道（长度 = 帧数；单声道素材请传同一份）
     * @param sampleRate    输入采样率
     * @return 人声与伴奏两条轨，长度与输入相同
     * @throws InferException 模型缺失、会话建不起来、推理失败或超时
     */
    StemSet separate(float[] left, float[] right, int sampleRate);

    /** 释放会话与它占用的原生内存。可重复调用。 */
    @Override
    void close();

    /** 分离结果：人声与伴奏，都是平面立体声。 */
    final class StemSet {

        /** 人声，左。 */
        public final float[] vocalsLeft;
        /** 人声，右。 */
        public final float[] vocalsRight;
        /** 伴奏（除人声之外的全部），左。 */
        public final float[] instrumentalLeft;
        /** 伴奏（除人声之外的全部），右。 */
        public final float[] instrumentalRight;

        public StemSet(float[] vocalsLeft, float[] vocalsRight,
                       float[] instrumentalLeft, float[] instrumentalRight) {
            int n = vocalsLeft == null ? 0 : vocalsLeft.length;
            if (vocalsRight == null || vocalsRight.length != n
                    || instrumentalLeft == null || instrumentalLeft.length != n
                    || instrumentalRight == null || instrumentalRight.length != n) {
                throw new IllegalArgumentException(
                        "四条轨长度必须一致（人声/伴奏 × 左右），任何长度差都会让接缝处错位");
            }
            this.vocalsLeft = vocalsLeft;
            this.vocalsRight = vocalsRight;
            this.instrumentalLeft = instrumentalLeft;
            this.instrumentalRight = instrumentalRight;
        }

        /** 帧数。 */
        public int frames() {
            return vocalsLeft.length;
        }
    }
}
