package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 混合与声道映射的对齐测试：对应 Sonalis {@code sonalis-mix/src/mixer.rs} 的 {@code mod tests}。
 */
public class MixerTest {

    private static AudioBuf buf(ChannelLayout ch, int frames) {
        AudioBuf b = new AudioBuf(ch, Math.max(frames, 1));
        b.setFrames(frames);
        return b;
    }

    private static AudioBuf filled(ChannelLayout ch, float... values) {
        AudioBuf b = buf(ch, values.length);
        for (int c = 0; c < ch.count(); c++) {
            FloatBuffer w = b.channelWriter(c);
            for (int i = 0; i < values.length; i++) {
                w.put(i, values[i]);
            }
        }
        return b;
    }

    private static void assertFrames(float[] expected, FloatBuffer actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals("下标 " + i, expected[i], actual.get(i), 1e-6f);
        }
    }

    /** 这条是等功率存在的理由：中点总功率必须为 1。 */
    @Test
    public void equalPowerHoldsPowerConstant() {
        for (int step = 0; step <= 10; step++) {
            float t = step / 10f;
            Mixer.Gains g = Mixer.equalPowerGains(t);
            float power = g.from * g.from + g.to * g.to;
            assertTrue("进度 " + t + " 处功率应为 1，实际 " + power,
                    Math.abs(power - 1f) < 1e-5f);
        }
    }

    /**
     * 对照组：线性淡化在中点会掉 3 dB。这条测试是为了把「为什么不用线性」
     * 写成可执行的证据，而不是留在注释里。
     */
    @Test
    public void linearCrossfadeWouldDrop3db() {
        float a = 0.5f;
        float b = 0.5f;
        float power = a * a + b * b;
        assertTrue("线性淡化中点功率只有 0.5（-3 dB）", Math.abs(power - 0.5f) < 1e-6f);
        Mixer.Gains eq = Mixer.equalPowerGains(0.5f);
        assertTrue("等功率在中点的功率必须高于线性",
                eq.from * eq.from + eq.to * eq.to > power);
    }

    @Test
    public void equalPowerEndpoints() {
        Mixer.Gains atZero = Mixer.equalPowerGains(0f);
        assertTrue("起点应完全是出场曲", Math.abs(atZero.from - 1f) < 1e-6f);
        assertTrue(Math.abs(atZero.to) < 1e-6f);

        Mixer.Gains atOne = Mixer.equalPowerGains(1f);
        assertTrue(Math.abs(atOne.from) < 1e-6f);
        assertTrue("终点应完全是进场曲", Math.abs(atOne.to - 1f) < 1e-6f);
    }

    @Test
    public void equalPowerClampsOutOfRange() {
        Mixer.Gains low = Mixer.equalPowerGains(-3f);
        assertTrue(Math.abs(low.from - 1f) < 1e-6f);
        assertTrue(Math.abs(low.to) < 1e-6f);
        Mixer.Gains high = Mixer.equalPowerGains(5f);
        assertTrue(Math.abs(high.from) < 1e-6f);
        assertTrue(Math.abs(high.to - 1f) < 1e-6f);
    }

    @Test
    public void mapPassthroughWhenSameGeometry() {
        AudioBuf src = filled(ChannelLayout.STEREO, 1f, 2f, 3f);
        AudioBuf dst = buf(ChannelLayout.STEREO, 8);
        Mixer.mapChannels(src, dst);
        assertEquals(3, dst.frames());
        assertFrames(new float[] {1f, 2f, 3f}, dst.channel(0));
        assertFrames(new float[] {1f, 2f, 3f}, dst.channel(1));
    }

    @Test
    public void mapMonoSpreadsToAllChannels() {
        AudioBuf src = filled(ChannelLayout.MONO, 0.5f, -0.5f);
        AudioBuf dst = buf(ChannelLayout.SURROUND_5_1, 8);
        Mixer.mapChannels(src, dst);
        assertEquals(2, dst.frames());
        for (int c = 0; c < 6; c++) {
            assertFrames(new float[] {0.5f, -0.5f}, dst.channel(c));
        }
    }

    @Test
    public void mapDownmixAveragesInsteadOfSumming() {
        // 6 个声道全是 1.0：相加会得到 6.0（严重溢出），取平均才是 1.0
        AudioBuf src = new AudioBuf(ChannelLayout.SURROUND_5_1, 4);
        src.setFrames(4);
        for (int c = 0; c < 6; c++) {
            FloatBuffer w = src.channelWriter(c);
            for (int i = 0; i < 4; i++) {
                w.put(i, 1f);
            }
        }
        AudioBuf dst = buf(ChannelLayout.MONO, 4);
        Mixer.mapChannels(src, dst);
        assertEquals("下混应取平均", 1f, dst.channel(0).get(0), 1e-6f);
    }

    @Test
    public void mapTruncatesAndZeroFillsWithoutPretending() {
        // 4 声道源 → 5.1 目标：走「取前 N 个 + 其余补零」分支
        AudioBuf src = new AudioBuf(ChannelLayout.of(4), 2);
        src.setFrames(2);
        for (int c = 0; c < 4; c++) {
            FloatBuffer w = src.channelWriter(c);
            w.put(0, c + 1);
            w.put(1, c + 1);
        }
        AudioBuf dst = buf(ChannelLayout.SURROUND_5_1, 2);
        Mixer.mapChannels(src, dst);
        // 前 4 个声道原样搬运
        for (int c = 0; c < 4; c++) {
            assertEquals(c + 1f, dst.channel(c).get(0), 0f);
        }
        // 多出来的两个声道补零，而不是复制或留垃圾
        assertEquals("未映射的声道应补零", 0f, dst.channel(4).get(0), 0f);
        assertEquals(0f, dst.channel(5).get(0), 0f);
    }

    @Test
    public void mapRespectsDestinationCapacity() {
        AudioBuf src = filled(ChannelLayout.MONO, 1f, 2f, 3f, 4f, 5f);
        AudioBuf dst = buf(ChannelLayout.MONO, 2);
        Mixer.mapChannels(src, dst);
        assertEquals("容量不足时应截断", 2, dst.frames());
    }

    @Test
    public void mixTwoSumsWeighted() {
        AudioBuf a = filled(ChannelLayout.STEREO, 1f, 1f, 1f);
        AudioBuf b = filled(ChannelLayout.STEREO, 0.5f, 0.5f, 0.5f);
        AudioBuf out = buf(ChannelLayout.STEREO, 8);
        int n = Mixer.mixTwo(out, a, 1f, b, 1f);
        assertEquals(3, n);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 3; i++) {
                assertEquals(1.5f, out.channel(c).get(i), 1e-6f);
            }
        }
    }

    @Test
    public void mixTwoHandlesUnequalLengths() {
        // a 长、b 短：尾部应只剩 a，而不是读到 b 的越界数据
        AudioBuf a = filled(ChannelLayout.MONO, 1f, 1f, 1f, 1f);
        AudioBuf b = filled(ChannelLayout.MONO, 0.5f, 0.5f);
        AudioBuf out = buf(ChannelLayout.MONO, 8);
        int n = Mixer.mixTwo(out, a, 1f, b, 1f);
        assertEquals(4, n);
        assertEquals(1.5f, out.channel(0).get(0), 1e-6f);
        assertEquals(1.5f, out.channel(0).get(1), 1e-6f);
        assertEquals("b 结束后应只剩 a 的贡献", 1f, out.channel(0).get(2), 1e-6f);
        assertEquals(1f, out.channel(0).get(3), 1e-6f);
    }

    @Test
    public void mixTwoWithEmptyBIsPureA() {
        AudioBuf a = filled(ChannelLayout.MONO, 0.25f, 0.5f);
        AudioBuf b = buf(ChannelLayout.MONO, 0);
        AudioBuf out = buf(ChannelLayout.MONO, 4);
        int n = Mixer.mixTwo(out, a, 1f, b, 1f);
        assertEquals(2, n);
        assertFrames(new float[] {0.25f, 0.5f}, out.channel(0));
    }

    /**
     * 过渡的起点与终点必须分别等于纯粹的 A 和纯粹的 B，
     * 否则过渡点会有可听的台阶。
     */
    @Test
    public void crossfadeEndpointsMatchPureSources() {
        AudioBuf a = filled(ChannelLayout.STEREO, 1f, 1f, 1f, 1f);
        AudioBuf b = filled(ChannelLayout.STEREO, 0f, 0f, 0f, 0f);
        AudioBuf out = buf(ChannelLayout.STEREO, 8);

        Mixer.Gains start = Mixer.equalPowerGains(0f);
        Mixer.mixTwo(out, a, start.from, b, start.to);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 4; i++) {
                assertEquals("起点应等于纯 A", 1f, out.channel(c).get(i), 1e-6f);
            }
        }

        Mixer.Gains end = Mixer.equalPowerGains(1f);
        Mixer.mixTwo(out, a, end.from, b, end.to);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 4; i++) {
                assertEquals("终点应等于纯 B", 0f, out.channel(c).get(i), 1e-6f);
            }
        }
    }

    @Test
    public void gainIntoUnityIsExactCopy() {
        AudioBuf src = filled(ChannelLayout.MONO, 0.1f, 0.2f, 0.3f);
        AudioBuf out = buf(ChannelLayout.MONO, 8);
        int n = Mixer.gainInto(out, src, 1f);
        assertEquals(3, n);
        assertFrames(new float[] {0.1f, 0.2f, 0.3f}, out.channel(0));
    }

    @Test
    public void gainIntoAppliesScalar() {
        AudioBuf src = filled(ChannelLayout.MONO, 1f, 2f);
        AudioBuf out = buf(ChannelLayout.MONO, 8);
        Mixer.gainInto(out, src, 0.5f);
        assertFrames(new float[] {0.5f, 1f}, out.channel(0));
    }
}
