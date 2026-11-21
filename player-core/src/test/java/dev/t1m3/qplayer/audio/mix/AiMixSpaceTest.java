package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>空间让位接进 {@link AiMix} 之后的真实行为</b>（P2）。
 *
 * <p>{@code MixerSpaceTest} 只证明中/侧矩阵算得对；这一条钉的是它在融合路径上的三件事，
 * 每一条都对应一个<b>实测踩到过的坑</b>：
 *
 * <ol>
 *   <li><b>不能截断音频。</b>暂存缓冲按 {@code Deck.RENDER_FRAMES} 分配，而生产路径上
 *       {@code from.frames()} 可以到 {@code 2 × RENDER_FRAMES}（{@code Director} 的
 *       {@code curBuf}），于是 {@code copyWithWidth} 会静默截断——<b>出场曲后半块直接消失</b>。
 *       判据：8192 帧进、8192 帧出。</li>
 *   <li><b>默认就是开着的。</b>旋钮在宿主里没有接入点，默认 0 等于这个功能不存在。
 *       判据：默认 {@code MixTuning.defaults()} 下，真实立体声（左右不同）必须被改动。</li>
 *   <li><b>几乎单声道就不动。</b>中/侧展宽靠「压低中间、抬起两侧」，单声道素材两侧为零，
 *       于是退化成对出场曲的<b>纯衰减</b>（白掉电平，不是让位）。
 *       判据：{@code L = R} 时与 {@code width = 0} <b>逐位相同</b>。</li>
 * </ol>
 */
public class AiMixSpaceTest {

    private static final int RATE = 48000;

    /** 左右不同的立体声（两个频率），侧向能量充足。 */
    private static AudioBuf stereo(int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        for (int i = 0; i < frames; i++) {
            buf.channelWriter(0).put(i, (float) (0.4 * Math.sin(2.0 * Math.PI * 220.0 * i / RATE)));
            buf.channelWriter(1).put(i, (float) (0.4 * Math.sin(2.0 * Math.PI * 330.0 * i / RATE)));
        }
        buf.setFrames(frames);
        return buf;
    }

    /** 单声道（左右完全一样）。 */
    private static AudioBuf mono(int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.4 * Math.sin(2.0 * Math.PI * 220.0 * i / RATE));
            buf.channelWriter(0).put(i, v);
            buf.channelWriter(1).put(i, v);
        }
        buf.setFrames(frames);
        return buf;
    }

    private static AudioBuf empty(int frames) {
        return new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
    }

    /** ① 一个 8192 帧的块（生产路径的最大块）必须原样交付，一帧都不能少。 */
    @Test
    public void aFullChunkIsNotTruncatedByTheWideningScratch() {
        int frames = 2 * Deck.RENDER_FRAMES;          // 8192：Director 的 curBuf 就是这个大小
        AudioBuf a = stereo(frames);
        AudioBuf b = stereo(frames);
        AudioBuf out = empty(frames);

        int n = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2).render(out, a, b, 0.5f);
        assertEquals("一个整块必须原样交付", frames, n);
        assertEquals("输出的帧数也要对", frames, out.frames());
        // 末尾那几帧必须真有内容（截断的话它们会是上一块的残留或 0）
        assertTrue("最后几帧不该是空的", Math.abs(out.channel(0).get(frames - 1)) > 1e-6f);
    }

    /**
     * ② <b>默认是关的</b>（用户批准的计划：空间展宽默认关、可调）——与 {@code width = 0}
     * <b>逐位相同</b>；而真的把它调到 0.5 时，真实立体声必须被改动。
     * <p>钉三件事：① 默认（{@value MixTuning#DEFAULT_SPACE_WIDTH}）必须与<b>显式设成同一个值</b>
     * 逐位相同——默认值不是一个偷偷摸摸的另一套行为；② 默认值本身要真的动手（不是个死旋钮）；
     * ③ 设成 0 必须真的把这一手关掉（与显式 0 一样）。
     *
     * <p>默认值从「关」改成「一半」是用户 2026-10-02 听过之后定的：这一手治的正是他报的
     * 「糊」，而 0.5 比拉满更好听（拉满时人声会往后退）。这一条判据跟着改，
     * 但「0 = 与没有这个功能逐位相同」这半条一直有效。
     */
    @Test
    public void theDefaultWidthIsAppliedAndZeroTrulyTurnsItOff() {
        int frames = Deck.RENDER_FRAMES;
        AudioBuf a = stereo(frames);
        AudioBuf b = stereo(frames);

        AudioBuf byDefault = empty(frames);
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2).render(byDefault, a, b, 0.5f);

        MixTuning same = MixTuning.defaults();
        same.spaceWidth = MixTuning.DEFAULT_SPACE_WIDTH;
        AudioBuf explicitSame = empty(frames);
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, same).render(explicitSame, a, b, 0.5f);

        for (int i = 0; i < frames; i++) {
            if (byDefault.channel(0).get(i) != explicitSame.channel(0).get(i)) {
                throw new AssertionError("默认（width=" + MixTuning.DEFAULT_SPACE_WIDTH
                        + "）应当与显式设成同一个值逐位相同（第 " + i + " 帧不同）");
            }
        }

        MixTuning off = MixTuning.defaults();
        off.spaceWidth = 0.0f;
        AudioBuf explicitOff = empty(frames);
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, off).render(explicitOff, a, b, 0.5f);

        int changed = 0;
        for (int i = 0; i < frames; i++) {
            if (explicitOff.channel(0).get(i) != byDefault.channel(0).get(i)) {
                changed++;
            }
        }
        assertTrue("默认值应当真的动手（改动 " + changed + " / " + frames + " 帧）",
                changed > frames / 2);

        MixTuning offTwice = MixTuning.defaults();
        offTwice.spaceWidth = 0.0f;
        AudioBuf secondOff = empty(frames);
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, offTwice).render(secondOff, a, b, 0.5f);
        for (int i = 0; i < frames; i++) {
            if (explicitOff.channel(0).get(i) != secondOff.channel(0).get(i)) {
                throw new AssertionError("width=0 必须是确定的「什么都不做」（第 " + i + " 帧不同）");
            }
        }
        System.out.println("[space] 默认 width=" + MixTuning.DEFAULT_SPACE_WIDTH
                + " 改动 " + changed + " / " + frames + " 帧；width=0 逐位不动 ✔");
    }

    /** ③ 几乎单声道 → 与关掉逐位相同（不白掉电平）。 */
    @Test
    public void essentiallyMonoMaterialIsLeftAlone() {
        int frames = Deck.RENDER_FRAMES;
        AudioBuf a = mono(frames);
        AudioBuf b = mono(frames);
        AudioBuf off = empty(frames);
        AudioBuf on = empty(frames);

        MixTuning flat = MixTuning.defaults();
        flat.spaceWidth = 0.0f;
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2, flat).render(off, a, b, 0.5f);
        new AiMix(MixStyle.PLAIN_BLEND, RATE, 2).render(on, a, b, 0.5f);

        for (int i = 0; i < frames; i++) {
            if (off.channel(0).get(i) != on.channel(0).get(i)) {
                throw new AssertionError("单声道素材不该被展宽（第 " + i + " 帧不同："
                        + off.channel(0).get(i) + " vs " + on.channel(0).get(i) + "）");
            }
        }
    }

    /** ④ 拍点剪切的契约：切点之前出场曲一个比特都不改（展宽也不例外）。 */
    @Test
    public void beatCutStillLeavesTheOutgoingTrackUntouched() {
        int frames = 64;
        AudioBuf a = stereo(frames);
        AudioBuf b = stereo(frames);
        AudioBuf out = empty(frames);
        AudioBuf off = empty(frames);
        MixTuning flat = MixTuning.defaults();
        flat.spaceWidth = 0.0f;

        AiMix cut = new AiMix(MixStyle.BEAT_CUT, RATE, 2);
        cut.cutAt = 0.9f;
        AiMix cutOff = new AiMix(MixStyle.BEAT_CUT, RATE, 2, flat);
        cutOff.cutAt = 0.9f;

        cut.render(out, a, b, 0.2f);
        cutOff.render(off, a, b, 0.2f);
        for (int i = 0; i < frames; i++) {
            if (off.channel(0).get(i) != out.channel(0).get(i)) {
                throw new AssertionError("切点前出场曲被展宽改动了（第 " + i + " 帧）");
            }
        }
    }
}
