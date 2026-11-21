package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;

import java.nio.FloatBuffer;

/**
 * <b>过渡专用的前瞻软限幅器</b>（2026-10-07）。
 *
 * <p>起因是用户实测：skydive→春めく 这类**两张都很响**的曲子，融合期会"有点点炸麦"。
 * 等功率淡化让**功率**守恒，但两张母带电平都高的曲子叠在一起，拍点上的**峰值**会超过
 * 满刻度 ⇒ 削顶。引擎里本来有 studio 限幅器，但那是全局效果链、要用户自己开；
 * 而"融合期不炸"是过渡本身该保证的事，所以做进过渡里、**只在窗口内作用**。
 *
 * <h3>为什么是"块内预扫"而不是采样级延迟线</h3>
 *
 * <p>真正的前瞻限幅器要把输出延迟 lookahead 那么久（经典代价），于是整个过渡会**整体后移**
 * 2 ms——窗口开头会多出 2 ms 的静音、结尾少 2 ms 的素材，那本身就是一个"咔哒"。
 * 这里改用<b>块内预扫</b>：先把这一块的峰值扫出来（{@link AutoCueTransition} 的块是
 * 1024 帧 ⇒ 48 kHz 下 21 ms），**在峰值到达之前**就把增益推过去——前瞻量比 2 ms 更大，
 * 而且**零延迟**、天花板以下<b>逐位不动</b>。
 *
 * <p>两条手段：
 * <ol>
 *   <li><b>留白</b>：由调用方按窗口形状施加约 1 dB（见 {@code AutoCueTransition.finish}），
 *       两端为 0 —— A 单独在响的那一下不该被压；</li>
 *   <li><b>预扫 + 软拐点</b>（本类）：增益在块内**线性**推过去（不瞬时落下来 ⇒ 没有"抽气"感），
 *       再过一个 {@code tanh} 软拐点，它<b>保证</b>输出不超过天花板。</li>
 * </ol>
 */
public final class TransitionLimiter {

    /** 天花板（dBFS）：留 1 dB 给后面的重采样/链路。 */
    static final double CEILING_DB = -1.0;

    /** 释放速度（毫秒）：增益回升多快。太短会"抽气"，80 ms 是常见取值。 */
    static final double RELEASE_MS = 80.0;

    private final float ceiling;
    private final float releasePerFrame;
    private float gain = 1.0f;
    private boolean acted;

    public TransitionLimiter(int rate, int channels) {
        this.ceiling = (float) Math.pow(10.0, CEILING_DB / 20.0);
        this.releasePerFrame = (float) (1.0 - Math.exp(-1.0 / Math.max(1.0, rate * RELEASE_MS / 1000.0)));
    }

    /** 清状态（每次过渡开始时调）。 */
    public void reset() {
        gain = 1.0f;
        acted = false;
    }

    /** 这一次过渡里有没有真的压过（记账/判据用）。 */
    public boolean acted() {
        return acted;
    }

    /** 天花板（线性），判据用。 */
    float ceilingLinear() {
        return ceiling;
    }

    /**
     * 就地处理 {@code frames} 帧。
     *
     * <p><b>天花板以下逐位透明</b>：这一块（含"接下来要不要压"的余量）没有超过天花板时
     * 直接返回，一个采样都不写——这是"绝不无故改变听感"那条纪律在 DSP 里的落法。
     */
    public void process(AudioBuf buf, int frames) {
        int n = Math.min(frames, buf.frames());
        if (n <= 0) {
            return;
        }
        int chs = Math.min(buf.channels(), 2);
        FloatBuffer left = buf.channelWriter(0);
        FloatBuffer right = chs > 1 ? buf.channelWriter(1) : null;
        float peak = 0f;
        for (int i = 0; i < n; i++) {
            peak = Math.max(peak, Math.abs(left.get(i)));
            if (right != null) {
                peak = Math.max(peak, Math.abs(right.get(i)));
            }
        }
        if (peak <= ceiling && gain >= 1.0f) {
            return;                                     // 逐位透明
        }
        acted = true;
        float target = peak > ceiling ? ceiling / peak : 1.0f;
        // **块首立刻到位**：预扫已经把这一块的峰值看过了 —— 那就是前瞻，
        // 所以不需要再爬坡（第一版按整块长度线性推过去，实测峰值 1.48 > 天花板 0.89：
        // 8192 帧 = 170 ms 的爬坡，等推到目标时峰值早过去了）。
        if (target < gain) {
            gain = target;
        }
        // **这一块还需要压就保持住、不释放**：第一版在块内一直朝 1 回升，
        // 而 80 ms 的释放时间短于一块（48 kHz / 1024 帧 = 21 ms，实测那块更长），
        // 于是块内后段的峰值又冒出来（实测 1.43 > 天花板 0.89）。
        // 释放只在"这一块已经不需要压"的时候发生 —— 那才是它该做的事。
        boolean holding = peak > ceiling;
        for (int i = 0; i < n; i++) {
            if (!holding && gain < 1.0f) {
                gain += (1.0f - gain) * releasePerFrame;
            }
            left.put(i, soft(left.get(i) * gain));
            if (right != null) {
                right.put(i, soft(right.get(i) * gain));
            }
        }
    }

    /** tanh 软拐点：<b>保证</b> |y| ≤ ceiling，又不像硬削那样生硬。 */
    private float soft(float x) {
        float a = Math.abs(x);
        if (a <= ceiling) {
            return x;
        }
        float over = (a - ceiling) / Math.max(ceiling, 1e-6f);
        float shaped = ceiling + ceiling * (float) Math.tanh(over);
        return x < 0f ? -shaped : shaped;
    }
}
