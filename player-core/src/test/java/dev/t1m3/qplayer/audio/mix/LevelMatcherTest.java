package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>逐帧响度匹配</b>（P4）：融合期让进场的 B 在响度上贴着出场的 A 走。
 *
 * <p>治的是「两首歌皱在一起很糊」里最直白的那一半：母带差几个 dB 的两首歌叠在一起，
 * 等功率淡化对「各自多响」一无所知，听感就是一首把另一首埋了（埋掉的那首只剩糊底）。
 *
 * <p>六条判据钉三件事：<b>关掉逐位不动</b>、<b>匹配的方向与上限</b>、<b>慢到没有台阶</b>。
 */
public class LevelMatcherTest {

    private static final int RATE = 48000;

    /** 一个 220 Hz 正弦（两侧同相），幅度给定。 */
    private static AudioBuf tone(double amp, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2.0 * Math.PI * 220.0 * i / RATE));
            buf.channelWriter(0).put(i, v);
            buf.channelWriter(1).put(i, v);
        }
        buf.setFrames(frames);
        return buf;
    }

    private static AudioBuf silent(int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        return buf;
    }

    private static float[] snapshot(AudioBuf buf) {
        float[] out = new float[buf.frames() * 2];
        for (int i = 0; i < buf.frames(); i++) {
            out[i * 2] = buf.channel(0).get(i);
            out[i * 2 + 1] = buf.channel(1).get(i);
        }
        return out;
    }

    /** 逐帧渲染，拿到这一路的增益序列（判据用：状态是同一个 matcher 的）。 */
    private static float[] gainSeries(LevelMatcher matcher, AudioBuf a, AudioBuf b,
                                      int frames, float strength) {
        float[] gains = new float[frames];
        for (int i = 0; i < frames; i++) {
            float pa = 0.0f;
            float pb = 0.0f;
            for (int c = 0; c < 2; c++) {
                float va = a.channel(c).get(i);
                float vb = b.channel(c).get(i);
                pa += va * va;
                pb += vb * vb;
            }
            gains[i] = matcher.gainForFrame(pa * 0.5f, pb * 0.5f, strength);
        }
        return gains;
    }

    // ==================================================================
    // ① 关掉 = 没有这个功能
    // ==================================================================

    /** {@code strength = 0} → 输出与 {@code mixTwo} **逐位相同**（不是「乘 1」）。 */
    @Test
    public void turningItOffIsBitIdenticalToNotHavingIt() {
        int frames = 4096;
        AudioBuf a = tone(0.4, frames);
        AudioBuf b = tone(0.05, frames);

        AudioBuf plain = new AudioBuf(ChannelLayout.STEREO, frames);
        int nPlain = Mixer.mixTwo(plain, a, 0.7f, b, 0.7f);

        AudioBuf matched = new AudioBuf(ChannelLayout.STEREO, frames);
        int nMatched = Mixer.mixTwoMatched(matched, a, 0.7f, b, 0.7f,
                new LevelMatcher(RATE), 0.0f);

        assertEquals("帧数也要一致", nPlain, nMatched);
        float[] want = snapshot(plain);
        float[] got = snapshot(matched);
        for (int i = 0; i < want.length; i++) {
            if (want[i] != got[i]) {
                throw new AssertionError("关掉时不该动任何一个采样（第 " + i + " 个不同）");
            }
        }
    }

    // ==================================================================
    // ② 方向与上限
    // ==================================================================

    /**
     * <b>B 比 A 轻 6 dB（在上限之内）→ 抬到一样响。</b>
     *
     * <p>幅度 0.4 与 0.2 是 2:1，也就是 6.02 dB——正好是上限那一档。稳定之后增益应当是
     * 2.0（B 被抬到与 A 同响度），而不是 1.0（不匹配）或 10（AGC 那种拉飞）。
     */
    @Test
    public void aQuietEntrantIsLiftedToTheOutgoingLevel() {
        int frames = RATE * 2;
        LevelMatcher matcher = new LevelMatcher(RATE);
        float[] gains = gainSeries(matcher, tone(0.4, frames), tone(0.2, frames), frames, 1.0f);

        float settled = gains[frames - 1];
        assertEquals("B 轻 6 dB，应当被抬到 2.0 倍", 2.0f, settled, 0.05f);
        assertTrue("起步时不该立刻跳（平滑）", Math.abs(gains[0] - 1.0f) < 0.01f);
        System.out.println("[level] 0.4 vs 0.2（6 dB）→ 稳定增益 " + settled
                + "（第 1 帧 " + gains[0] + "）");
    }

    /** B 更响 → 压下去（同样 6 dB 档）。 */
    @Test
    public void aLouderEntrantIsTamed() {
        int frames = RATE * 2;
        LevelMatcher matcher = new LevelMatcher(RATE);
        float[] gains = gainSeries(matcher, tone(0.15, frames), tone(0.6, frames), frames, 1.0f);
        float settled = gains[frames - 1];
        assertEquals("B 响 12 dB，压到上限 −6 dB（0.5012）", LevelMatcher.MIN_GAIN, settled, 0.02f);
    }

    /** 差得再多也不越过 ±6 dB（安静的前奏不该被拉飞）。 */
    @Test
    public void theGainNeverExceedsSixDecibelsEitherWay() {
        int frames = RATE * 2;
        float[] lifted = gainSeries(new LevelMatcher(RATE), tone(0.5, frames), tone(0.01, frames),
                frames, 1.0f);
        float[] tamed = gainSeries(new LevelMatcher(RATE), tone(0.01, frames), tone(0.5, frames),
                frames, 1.0f);

        assertEquals("上限 +6 dB", LevelMatcher.MAX_GAIN, lifted[frames - 1], 0.02f);
        assertEquals("上限 −6 dB", LevelMatcher.MIN_GAIN, tamed[frames - 1], 0.02f);
        System.out.println("[level] 上限实测 " + lifted[frames - 1] + " / " + tamed[frames - 1]
                + "（MAX_GAIN " + LevelMatcher.MAX_GAIN + "）");
    }

    /** 半开：增益正好落在「不动」与「全匹配」的中点（旋钮是可用的，不是开关）。 */
    @Test
    public void strengthBlendsBetweenOffAndFullMatch() {
        int frames = RATE * 2;
        float full = gainSeries(new LevelMatcher(RATE), tone(0.4, frames), tone(0.2, frames),
                frames, 1.0f)[frames - 1];
        float half = gainSeries(new LevelMatcher(RATE), tone(0.4, frames), tone(0.2, frames),
                frames, 0.5f)[frames - 1];
        assertEquals("半开应当落在 1.0 与全匹配之间", 1.0f + (full - 1.0f) * 0.5f, half, 0.02f);
    }

    // ==================================================================
    // ③ 没有台阶、不会炸
    // ==================================================================

    /**
     * <b>B 的档位突然变了，增益也不能跟着跳。</b>
     *
     * <p>判据直接量增益序列的逐帧变化：一阶跟随的时间常数 {@value LevelMatcher#GAIN_TAU_SECS} s
     * 决定了每帧最多走 {@code alpha ≈ 1/(τ·rate)} 的比例，实测远小于千分之一。
     */
    @Test
    public void theGainMovesWithoutSteps() {
        int frames = RATE * 2;
        int step = RATE / 2;
        AudioBuf a = tone(0.4, frames);
        AudioBuf b = tone(0.05, frames);
        for (int i = step; i < frames; i++) {          // 后半段 B 突然响了 10 倍
            float v = (float) (0.5 * Math.sin(2.0 * Math.PI * 220.0 * i / RATE));
            b.channelWriter(0).put(i, v);
            b.channelWriter(1).put(i, v);
        }
        float[] gains = gainSeries(new LevelMatcher(RATE), a, b, frames, 1.0f);

        float worst = 0.0f;
        for (int i = 1; i < gains.length; i++) {
            worst = Math.max(worst, Math.abs(gains[i] - gains[i - 1]));
        }
        float bound = 2.0f * (1.0f - (float) Math.exp(-1.0 / (LevelMatcher.GAIN_TAU_SECS * RATE)));
        assertTrue("单帧增益变化 " + worst + " 应当不超过 " + bound, worst <= bound);
        System.out.println("[level] 档位突变 10 倍：单帧增益最大变化 " + worst + "（上限 " + bound + "）");
    }

    /** 两路都静音、只有一路静音、静音起手——都不能出 NaN / 无穷，增益也不乱动。 */
    @Test
    public void silenceNeverBlowsUp() {
        int frames = RATE;
        float[] bSilent = gainSeries(new LevelMatcher(RATE), tone(0.5, frames), silent(frames),
                frames, 1.0f);
        float[] aSilent = gainSeries(new LevelMatcher(RATE), silent(frames), tone(0.5, frames),
                frames, 1.0f);
        float[] bothSilent = gainSeries(new LevelMatcher(RATE), silent(frames), silent(frames),
                frames, 1.0f);

        for (int i = 0; i < frames; i++) {
            assertFinite(bSilent[i], "B 静音");
            assertFinite(aSilent[i], "A 静音");
            assertFinite(bothSilent[i], "两侧都静音");
        }
        assertEquals("B 静音时不该抬（抬静音只会放大底噪）", 1.0f, bSilent[frames - 1], 1e-6f);
        assertEquals("两侧都静音时也不动", 1.0f, bothSilent[frames - 1], 1e-6f);
    }

    private static void assertFinite(float v, String what) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            throw new AssertionError(what + "时增益变成了 " + v);
        }
    }
}
