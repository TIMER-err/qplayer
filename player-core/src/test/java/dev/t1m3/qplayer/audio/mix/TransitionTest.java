package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 过渡骨架的对齐测试：对应 Sonalis {@code sonalis-mix/src/transition.rs} 的 {@code mod tests}。
 */
public class TransitionTest {

    private static AudioBuf filled(int frames, float v) {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
        b.setFrames(frames);
        for (int c = 0; c < 2; c++) {
            FloatBuffer w = b.channelWriter(c);
            for (int i = 0; i < frames; i++) {
                w.put(i, v);
            }
        }
        return b;
    }

    private static AudioBuf empty(int frames) {
        return new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
    }

    @Test
    public void gaplessWindowIsZero() {
        assertEquals(0L, Gapless.INSTANCE.windowFrames(48_000));
    }

    @Test
    public void gaplessDoesNotRewriteSamples() {
        assertFalse("拼接不改写采样值——这正是它与位完美兼容的原因",
                Gapless.INSTANCE.rewritesSamples());
        assertEquals("无缝拼接", Gapless.INSTANCE.name());
    }

    @Test
    public void crossfadeWindowScalesWithRate() {
        Crossfade c = new Crossfade(6f);
        assertEquals(288_000L, c.windowFrames(48_000));
        assertEquals(576_000L, c.windowFrames(96_000));
        // 0 秒等价于硬接
        assertEquals(0L, new Crossfade(0f).windowFrames(48_000));
        // 负值被夹住，不能产生一个「负的窗口」
        assertEquals(0L, new Crossfade(-5f).windowFrames(48_000));
    }

    @Test
    public void crossfadeRewritesSamples() {
        assertTrue("淡化必然改写采样值", new Crossfade().rewritesSamples());
        assertEquals("交叉淡化", new Crossfade().name());
        assertEquals(Crossfade.DEFAULT_SECONDS, new Crossfade().seconds, 0f);
    }

    @Test
    public void gaplessRenderSwitchesAtEndpoints() {
        AudioBuf a = filled(4, 1f);
        AudioBuf b = filled(4, 0f);
        AudioBuf out = empty(4);

        assertEquals(4, Gapless.INSTANCE.render(out, a, b, 0f));
        assertTrue("起点应是出场曲", Math.abs(out.channel(0).get(0) - 1f) < 1e-6f);

        assertEquals(4, Gapless.INSTANCE.render(out, a, b, 1f));
        assertTrue("终点应是进场曲", Math.abs(out.channel(0).get(0)) < 1e-6f);
    }

    /** 交叉淡化在两端必须分别等于纯 A 与纯 B：否则过渡的起止点会有可听的台阶。 */
    @Test
    public void crossfadeEndpointsArePure() {
        AudioBuf a = filled(8, 1f);
        AudioBuf b = filled(8, 0.25f);
        AudioBuf out = empty(8);
        Crossfade cf = new Crossfade(1f);

        cf.render(out, a, b, 0f);
        assertTrue("起点应等于纯 A", Math.abs(out.channel(0).get(0) - 1f) < 1e-6f);

        cf.render(out, a, b, 1f);
        assertTrue("终点应等于纯 B", Math.abs(out.channel(0).get(0) - 0.25f) < 1e-6f);
    }

    /** 中点：等功率下两路各贡献 {@code √2/2 ≈ 0.7071}。 */
    @Test
    public void crossfadeMidpointUsesEqualPower() {
        AudioBuf a = filled(4, 1f);
        AudioBuf b = filled(4, 1f);
        AudioBuf out = empty(4);
        Crossfade cf = new Crossfade(1f);
        cf.render(out, a, b, 0.5f);

        float expected = 2f * (float) (1.0 / Math.sqrt(2.0));
        assertTrue("中点应为 2×0.7071 = " + expected + "，实际 " + out.channel(0).get(0)
                        + "（线性淡化会给出 1.0）",
                Math.abs(out.channel(0).get(0) - expected) < 1e-5f);
    }

    /**
     * 同一个信号淡入淡出时，等功率在中点的幅度必须<strong>高于</strong>原始幅度的一半，
     * 否则听感会「塌陷」。
     */
    @Test
    public void crossfadeDoesNotDipBelowHalf() {
        AudioBuf a = filled(4, 1f);
        AudioBuf out = empty(4);
        Crossfade cf = new Crossfade(1f);
        cf.render(out, a, a, 0.5f);
        assertTrue("中点幅度 " + out.channel(0).get(0) + " 不该低于 0.5",
                out.channel(0).get(0) > 0.5f);
    }

    /** 默认的 {@code prepare} / {@code pitchAt} 必须是「什么都不安排」。 */
    @Test
    public void defaultPrepareAndPitchAreNoops() {
        assertTrue(TransitionPlan.NONE.isNoop());
        assertTrue(Gapless.INSTANCE.prepare(null, null, 48_000).isNoop());
        assertTrue(Gapless.INSTANCE.pitchAt(0.4f).isZero());
    }

    @Test
    public void pitchPlanZeroDetectionIgnoresTinyValues() {
        assertTrue(new PitchPlan(0f, 0f).isZero());
        assertTrue("1e-7 半音属于数值噪声", new PitchPlan(1e-7f, -1e-7f).isZero());
        assertFalse(new PitchPlan(2f, 0f).isZero());
        assertFalse(new PitchPlan(0f, -2f).isZero());
    }
}
