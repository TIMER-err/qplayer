package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 斜坡增益的对齐测试：对应 Sonalis {@code crates/sonalis-dsp/src/gain.rs} 的 {@code mod tests}。
 *
 * <p>上游 7 条 → 本地 7 条对齐，方法名一一对应（另有 3 条「额外补充」，见文件末尾，上游没有对应条目）：
 * <pre>
 * db_conversion_roundtrip                              → dbConversionRoundtrip
 * unity_gain_does_not_touch_buffer                     → unityGainDoesNotTouchBuffer
 * ramp_reaches_target_without_jump                     → rampReachesTargetWithoutJump
 * ramp_is_linear                                       → rampIsLinear
 * both_channels_get_the_same_gain                      → bothChannelsGetTheSameGain
 * set_target_while_ramping_restarts_from_current        → setTargetWhileRampingRestartsFromCurrent
 * empty_buffer_is_noop                                 → emptyBufferIsNoop
 * </pre>
 *
 * <p>容差说明：上游的 {@code 1e-6} / {@code 1e-5} 容差本身就是给「不同 libm 的超越函数
 * 差几个 ulp」留的，这里的 f32 值都在 0.25 ~ 2.0 量级，1 ulp ≈ 1.2e-7，远小于上游容差，
 * 所以<strong>直接沿用上游容差，没有放宽</strong>。只有上游写明精确相等的断言
 * （{@code assert_eq!}）这里才用 {@code delta = 0f}（放不过任何差异）。
 */
public class GainTest {

    /** 对应上游测试里的 {@code ramp_buf(frames)}：立体声、全 1.0，便于直接读增益序列。 */
    private static AudioBuf rampBuf(int frames) {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, frames);
        b.setFrames(frames);
        for (int c = 0; c < 2; c++) {
            FloatBuffer w = b.channelWriter(c);
            for (int i = 0; i < frames; i++) {
                w.put(i, 1f);
            }
        }
        return b;
    }

    /** 取声道视图的内容（不动原视图的 position）。 */
    private static float[] toArray(FloatBuffer b) {
        float[] out = new float[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    @Test
    public void dbConversionRoundtrip() {
        assertEquals(1.0f, Gain.dbToLinear(0.0f), 1e-6f);
        assertEquals(0.5011872f, Gain.dbToLinear(-6.0f), 1e-5f);
        assertEquals(1.9952623f, Gain.dbToLinear(6.0f), 1e-5f);
        assertEquals(0.0f, Math.abs(Gain.linearToDb(1.0f)), 1e-6f);
        assertEquals("-144 dB 以下视为静音", 0.0f, Gain.dbToLinear(-200.0f), 0f);
        assertEquals(-144.0f, Gain.linearToDb(0.0f), 0f);
    }

    /**
     * 单位增益<strong>不触碰</strong>缓冲。
     *
     * <p>这条断言在上游是不带容差的逐位比较（{@code assert_eq!}），这里同样用
     * {@code delta = 0f}：要求「完全不动」而不是「差不多不动」。理由是它撑着一条数学前提——
     * 位完美路径下增益可以常驻链路：挂着 unity Gain 的链路输出必须与不挂逐位相同。
     *
     * <p>注意这条断言锁的是「提前返回、绝不写缓冲」这个<strong>契约</strong>：即使哪天把快路径
     * 改成仍然走乘法（采样 × 1.0f 数值上依然精确、逐位相同），本测试也不会报错。
     * 真正在代码里实现这条契约的是 {@link Gain#process} 里那条 early return，
     * 动它必须回来重跑本测试。
     */
    @Test
    public void unityGainDoesNotTouchBuffer() {
        Gain g = new Gain(48_000);
        AudioBuf b = rampBuf(64);
        float[] before = toArray(b.channel(0));
        g.process(b);
        assertArrayEquals(before, toArray(b.channel(0)), 0f);
    }

    @Test
    public void rampReachesTargetWithoutJump() {
        Gain g = new Gain(1000); // 1000 Hz → 5 ms = 5 帧
        g.snapTo(1.0f);
        g.setTarget(0.0f);
        assertTrue(g.isRamping());

        AudioBuf b = rampBuf(8);
        g.process(b);

        // 第一帧不该已经到 0——那说明没走斜坡
        assertTrue("增益跳变会产生拉链噪声", b.channel(0).get(0) > 0.0f);
        // 斜坡走完后应精确落在目标
        assertEquals(0.0f, g.current(), 0f);
        assertEquals(0.0f, b.channel(0).get(7), 0f);
    }

    @Test
    public void rampIsLinear() {
        Gain g = Gain.withRampMs(1000, 4.0f); // 4 帧
        g.snapTo(1.0f);
        g.setTarget(0.0f);
        AudioBuf b = rampBuf(4);
        g.process(b);
        assertEquals("第 0 帧应为 0.75，实际 " + b.channel(0).get(0),
                0.75f, b.channel(0).get(0), 1e-6f);
        assertEquals(0.50f, b.channel(0).get(1), 1e-6f);
        assertEquals(0.25f, b.channel(0).get(2), 1e-6f);
        assertEquals(0.0f, Math.abs(b.channel(0).get(3)), 1e-6f);
    }

    @Test
    public void bothChannelsGetTheSameGain() {
        Gain g = Gain.withRampMs(1000, 2.0f);
        g.snapTo(1.0f);
        g.setTarget(0.5f);
        AudioBuf b = rampBuf(2);
        g.process(b);
        // 逐位相等：两个声道走的是同一个 g，不该有任何差别
        assertArrayEquals("左右声道必须同步", toArray(b.channel(0)), toArray(b.channel(1)), 0f);
    }

    @Test
    public void setTargetWhileRampingRestartsFromCurrent() {
        Gain g = Gain.withRampMs(1000, 10.0f);
        g.snapTo(1.0f);
        g.setTarget(0.0f);
        AudioBuf b = rampBuf(3);
        g.process(b);
        float mid = g.current();
        g.setTarget(1.0f);
        AudioBuf b2 = rampBuf(20);
        g.process(b2);
        assertEquals("新的斜坡应从当前位置走到新目标", 1.0f, g.current(), 0f);
        assertTrue(mid > 0.0f && mid < 1.0f);
    }

    @Test
    public void emptyBufferIsNoop() {
        Gain g = new Gain(48_000);
        g.setTarget(0.0f);
        AudioBuf b = new AudioBuf(ChannelLayout.MONO, 64);
        b.setFrames(0);
        g.process(b);
        assertEquals(0, b.frames());
    }

    // ---------------------------------------------------------------
    // 以下 3 条是额外补充（上游 mod tests 里没有对应条目），对应移植约定
    // 「Java 与 Rust 的差异 / 边界要补测试」：上游的 0 / 负值 / 极小值处理是靠
    // max(0.0) 与阈值分支实现的，但没有为这几条边界单独写测试。
    // ---------------------------------------------------------------

    /**
     * 额外补充：负增益被 {@code max(0.0)} 夹到 0——0 增益意味着静音，
     * 负增益会让波形反相（相位翻转），不是「更小的音量」。
     */
    @Test
    public void negativeValuesAreClampedToSilence() {
        Gain g = new Gain(48_000);
        g.setTarget(-1.0f);
        assertEquals(0.0f, g.target(), 0f);
        g.snapTo(-1.0f);
        assertEquals(0.0f, g.current(), 0f);
        assertEquals(0.0f, g.target(), 0f);
        assertFalse(g.isRamping());
    }

    /**
     * 额外补充：{@code -inf} dB 命中 {@code db <= -144.0} 那条分支 → 线性 0，
     * 而不是把 {@code -inf} 喂给 powf 之后的 NaN/0 混用；与 {@link Gain#linearToDb}
     * 的 -144 哨兵正好配对（0 与 -inf 双向都能回到「静音」这个语义）。
     */
    @Test
    public void negativeInfinityDbIsSilence() {
        assertEquals(0.0f, Gain.dbToLinear(Float.NEGATIVE_INFINITY), 0f);
        assertEquals(-144.0f, Gain.linearToDb(0.0f), 0f);
        Gain g = new Gain(48_000);
        g.setTargetDb(Float.NEGATIVE_INFINITY);
        assertEquals(0.0f, g.target(), 0f);
        assertTrue("从单位增益起步应走完整斜坡", g.isRamping());
    }

    /**
     * 额外补充：0 / 负的斜坡时长都退化成 1 帧。上游那两个 {@code .max(1)} 防的是
     * {@code set_target} 里除以 0（得到 inf 步长会把整块缓冲变成 NaN/inf），
     * Java 侧同样要确认这个下限真的在。
     */
    @Test
    public void rampFramesNeverZero() {
        Gain zeroMs = Gain.withRampMs(1000, 0.0f);
        Gain negMs = Gain.withRampMs(1000, -5.0f);
        zeroMs.setTarget(0.0f);
        negMs.setTarget(0.0f);
        AudioBuf b = rampBuf(1);
        zeroMs.process(b);
        assertEquals("1 帧斜坡走完应精确落到目标", 0.0f, zeroMs.current(), 0f);
        assertEquals(0.0f, b.channel(0).get(0), 0f);
        AudioBuf b2 = rampBuf(1);
        negMs.process(b2);
        assertEquals(0.0f, negMs.current(), 0f);
    }
}
