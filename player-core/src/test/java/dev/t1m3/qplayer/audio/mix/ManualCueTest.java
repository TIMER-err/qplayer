package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P4 控制室的「调」：手动点位覆盖（{@link ManualCue}）。
 *
 * <p>判据三条，全是「用户按下那个数之后应当发生什么」：
 *
 * <ol>
 *   <li>只给切入点 → 进场 deck 从那一秒进（{@code plan.skipSecs} 逐位等于它）；</li>
 *   <li>只给出场点 → 融合点落在曲中，而且<strong>被丢掉的尾巴算进触发提前量</strong>
 *       （编排器靠它把过渡触发点提前——这一条是「真的落在曲中」而不是「提前结束」的分界）；</li>
 *   <li>两个数都没给（都是 0）→ <strong>一个字都不改</strong>（照旧用被包接法的点位）。</li>
 * </ol>
 *
 * <p>它同时钉住「手动说了算」这件事在工厂里的落点：套在自动点位外面
 * （见 {@code AutomixSettings.factoryFor}）。
 */
public class ManualCueTest {

    private static final int RATE = 48_000;

    /** 一个假的被包接法：固定窗口 + 固定点位，好让「手动覆盖」看得出来。 */
    private static final class Fixed implements Transition {

        private final double windowSecs;
        private final double skipSecs;
        private final double tailSecs;

        Fixed(double windowSecs, double skipSecs, double tailSecs) {
            this.windowSecs = windowSecs;
            this.skipSecs = skipSecs;
            this.tailSecs = tailSecs;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return (long) (windowSecs * sampleRate);
        }

        @Override
        public int render(dev.t1m3.qplayer.audio.core.AudioBuf out,
                          dev.t1m3.qplayer.audio.core.AudioBuf from,
                          dev.t1m3.qplayer.audio.core.AudioBuf to, float progress) {
            return 0;
        }

        @Override
        public String name() {
            return "假接法";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int sampleRate) {
            return new TransitionPlan(skipSecs, PitchPlan.ZERO, 0f, "假接法", tailSecs);
        }
    }

    /** 一个能报时长的假解码器（手动点位要靠它把数夹进曲子范围）。 */
    private static void installDecoder(final double durationSecs) {
        dev.t1m3.qplayer.audio.decode.Decoder.install(
                new dev.t1m3.qplayer.audio.decode.Decoder.Opener() {
                    @Override
                    public dev.t1m3.qplayer.audio.decode.Decoder open(
                            String source, java.util.Map<String, String> headers) {
                        return new dev.t1m3.qplayer.audio.decode.Decoder() {
                            @Override
                            public dev.t1m3.qplayer.audio.decode.TrackSpec spec() {
                                return new dev.t1m3.qplayer.audio.decode.TrackSpec("pcm", RATE,
                                        dev.t1m3.qplayer.audio.core.ChannelLayout.MONO,
                                        Integer.valueOf(16),
                                        Long.valueOf((long) (durationSecs * RATE)), true);
                            }

                            @Override
                            public long framesDecoded() {
                                return 0;
                            }

                            @Override
                            public boolean isDrained() {
                                return true;
                            }

                            @Override
                            public double seek(double secs) {
                                return secs;
                            }

                            @Override
                            public int decodeInto(dev.t1m3.qplayer.audio.core.AudioBuf out) {
                                return 0;
                            }

                            @Override
                            public void close() {
                            }
                        };
                    }
                });
    }

    @Test
    public void theIntroPointIsExactlyWhatTheUserAskedFor() {
        installDecoder(200.0);
        try {
            ManualCue cue = new ManualCue(new Fixed(6.0, 2.8, 0.0), 0.0, 12.5);
            TransitionPlan plan = cue.prepare("outro.wav", "intro.wav", RATE);
            assertEquals("切入点必须逐位等于用户给的那个数", 12.5, plan.skipSecs, 1e-9);
            assertTrue("说明里要写清这是手动点位：" + plan.note, plan.note.contains("手动点位"));
            assertEquals("窗口照旧由被包接法决定", 6.0,
                    (double) cue.windowFrames(RATE) / RATE, 1e-9);
            assertTrue("名字要能看出这一档是手动点位", cue.name().contains("手动点位"));
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    @Test
    public void theOutroPointMovesTheBlendIntoTheSongAndDropsTheTail() {
        installDecoder(200.0);
        try {
            ManualCue cue = new ManualCue(new Fixed(6.0, 2.8, 0.0), 120.0, 0.0);
            TransitionPlan plan = cue.prepare("outro.wav", "intro.wav", RATE);
            // 全曲 200 s、融合点 120 s、窗口 6 s → 丢尾 = 200 − 126 = 74 s
            assertEquals("丢尾必须算出来（编排器靠它把触发点提前）", 74.0, plan.outroTailSecs, 1e-9);
            assertEquals("切入点没给，照旧用被包接法的", 2.8, plan.skipSecs, 1e-9);
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    /** 越界的数被夹住：出场点不能把整首曲子切掉（至少要留窗口 + 2 秒）。 */
    @Test
    public void outOfRangePointsAreClamped() {
        installDecoder(60.0);
        try {
            ManualCue cue = new ManualCue(new Fixed(6.0, 0.0, 0.0), 999.0, 999.0);
            TransitionPlan plan = cue.prepare("outro.wav", "intro.wav", RATE);
            assertTrue("出场点要夹到「全曲 − 窗口 − 2 秒」（60 − 6 − 2 = 52）以内："
                            + plan.outroTailSecs,
                    plan.outroTailSecs >= 2.0 - 1e-9);
            assertTrue("切入点要夹到「全曲 − 窗口」以内：" + plan.skipSecs,
                    plan.skipSecs <= 60.0 - 6.0 + 1e-9);
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    /** 两个数都是 0：一个字都不改（照旧是被包接法的点位与说明）。 */
    @Test
    public void nothingGivenChangesNothing() {
        ManualCue cue = new ManualCue(new Fixed(6.0, 2.8, 4.5), 0.0, 0.0);
        TransitionPlan plan = cue.prepare("outro.wav", "intro.wav", RATE);
        assertEquals("切入点照旧", 2.8, plan.skipSecs, 1e-9);
        assertEquals("丢尾照旧", 4.5, plan.outroTailSecs, 1e-9);
        assertEquals("说明照旧（不许自己加一句「手动点位」）", "假接法", plan.note);
        assertFalse("这一段完全没有手动点位参与", plan.note.contains("手动点位"));
    }

    /** 套在自动点位外面时，手动说了算（两层都是「只换点位」的包装）。 */
    @Test
    public void manualWinsOverAutoCue() {
        installDecoder(200.0);
        try {
            // 被包的是「自动点位」那一层（这里用一个会的假接法代替：它的点位是 30 s）
            ManualCue cue = new ManualCue(new Fixed(6.0, 30.0, 0.0), 0.0, 7.0);
            TransitionPlan plan = cue.prepare("outro.wav", "intro.wav", RATE);
            assertEquals("手动给的切入点必须压过内层算出来的 30 s", 7.0, plan.skipSecs, 1e-9);
            assertTrue(plan.note.contains("手动点位"));
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    /** 时长未知（流式）时不夹紧，但仍按用户给的数走。 */
    @Test
    public void unknownDurationStillHonoursTheManualValue() {
        dev.t1m3.qplayer.audio.decode.Decoder.install(
                new dev.t1m3.qplayer.audio.decode.Decoder.Opener() {
                    @Override
                    public dev.t1m3.qplayer.audio.decode.Decoder open(
                            String source, java.util.Map<String, String> headers) {
                        throw new IllegalStateException("打不开");
                    }
                });
        try {
            ManualCue cue = new ManualCue(new Fixed(6.0, 1.0, 0.0), 90.0, 9.0);
            TransitionPlan plan = cue.prepare("outro.wav", "intro.wav", RATE);
            assertEquals("时长未知时照用户给的数走（不猜时长、也不丢弃他的意图）",
                    9.0, plan.skipSecs, 1e-9);
            assertEquals("丢尾保持被包接法的值（算不出来就不改）", 0.0, plan.outroTailSecs, 1e-9);
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }
}
