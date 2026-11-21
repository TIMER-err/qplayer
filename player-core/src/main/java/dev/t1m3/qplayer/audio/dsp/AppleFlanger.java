package dev.t1m3.qplayer.audio.dsp;

/**
 * <b>Flanger（梳状 + LFO）</b>（2026-10-08 ✓，照搬 goal 第 49 轮 ✓）。
 *
 * <p>材料里档 17 的 `remixfx_flanger_*`（逐字读出来的 ✓）：`rate = 2` ✓、`depth = 0.32` ✓、
 * `center = 0.51` ✓、`feedback = 0.52` ✓、`mix` 由 0 升到 **0.45** ✓（于 `[0.6722, 0.8333]` ✓）。
 *
 * <p>实现：一条短延迟线（上限 {@link #MAX_DELAY_SECS} ✓），读取位置在
 * {@code center} 附近由 LFO 以 {@code rate} Hz 摆动 ✓、摆幅 = {@code depth} ✓（都是"占上限的比例"✓，
 * 这是我按参数名与常见语义的解释 ✓ —— 材料没给单位 ⇒ **在此说明** ✓，不假装知道别的 ✗）；
 * 读出值带 {@code feedback} 回灌 ✓；输出 = 干 ×(1−mix) + 湿 ×mix ✓。
 * **关着（mix = 0）⇒ 一个采样都不碰** ✓（逐位直通 ✓）；**零分配** ✓（缓冲构造时建好 ✓）、RT 安全 ✓
 *（分数延迟读用线性插值 ✓，不重采样、不 new ✓）。
 */
public final class AppleFlanger {

    /** 延迟线上限（秒 ✓）。10 ms 足够做 flanger 的梳状 ✓。 */
    public static final float MAX_DELAY_SECS = 0.01f;

    private final float[][] buf;
    private final int mask;
    private final int size;
    private final int rate;
    private int writePos;
    private float phase;
    private float rateHz = 2f;
    private float depth = 0.32f;
    private float center = 0.51f;
    private float feedback = 0.52f;
    private float mix;

    public AppleFlanger(int sampleRate, int channels) {
        this.rate = Math.max(1, sampleRate);
        int n = 1;
        while (n < (int) (MAX_DELAY_SECS * this.rate) + 4) {
            n <<= 1;
        }
        this.size = n;
        this.mask = n - 1;
        this.buf = new float[Math.max(1, channels)][n];
    }

    public void reset() {
        for (float[] ch : buf) {
            java.util.Arrays.fill(ch, 0f);
        }
        writePos = 0;
        phase = 0f;
    }

    public void setMix(float m) {
        mix = m < 0f ? 0f : (m > 1f ? 1f : m);
    }

    public void setRateHz(float hz) {
        rateHz = hz > 0f ? hz : 0f;
    }

    public void setDepth(float d) {
        depth = d < 0f ? 0f : (d > 1f ? 1f : d);
    }

    public void setCenter(float c) {
        center = c < 0f ? 0f : (c > 1f ? 1f : c);
    }

    public void setFeedback(float f) {
        feedback = f < -0.95f ? -0.95f : (f > 0.95f ? 0.95f : f);
    }

    /** 处理一个采样 ✓（mix = 0 ⇒ 逐位直通 ✓）。 */
    public float process(int channel, float in) {
        if (mix <= 0f) {
            return in;                       // 关着 ⇒ 不碰 ✓（也不推进缓冲 ✓，免得留残响 ✓）
        }
        int c = Math.max(0, Math.min(buf.length - 1, channel));
        float[] b = buf[c];
        int maxFrames = size - 2;
        float lfo = (float) Math.sin(2.0 * Math.PI * (double) phase);
        float delayFrames = (center + depth * lfo) * (MAX_DELAY_SECS * rate);
        if (delayFrames < 1f) {
            delayFrames = 1f;
        }
        if (delayFrames > maxFrames) {
            delayFrames = maxFrames;
        }
        int i0 = writePos - (int) delayFrames;
        float frac = delayFrames - (int) delayFrames;
        float a = b[i0 & mask];
        float d = b[(i0 - 1) & mask];
        float delayed = a + frac * (d - a);              // 线性插值 ✓（分数延迟 ✓）
        b[writePos & mask] = in + feedback * delayed;     // 回灌 ✓
        writePos++;
        phase += rateHz / rate;
        if (phase >= 1f) {
            phase -= 1f;
        }
        return in + mix * (delayed - in);
    }
}
