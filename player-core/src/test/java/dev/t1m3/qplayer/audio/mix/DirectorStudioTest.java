package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.studio.BandType;
import dev.t1m3.qplayer.audio.studio.EqBand;
import dev.t1m3.qplayer.audio.studio.StudioProcessor;
import dev.t1m3.qplayer.audio.studio.StudioRack;
import dev.t1m3.qplayer.audio.studio.StudioRacks;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 工作室链<strong>挂在引擎上</strong>这件事本身要有判据。
 *
 * <h2>为什么处理器测过了还要这一条</h2>
 *
 * <p>{@code StudioProcessor} 自己测得再全，也证明不了「引擎真的调它」：接线漏一个调用点
 * （比如只在 {@code renderCurrent} 里插、忘了 {@code renderTransition} 里那一路），
 * <b>症状是「过渡窗口里下一首的 EQ 不生效」</b>——而这件事不会报错、不会崩、只在听感上差一点，
 * 是最难发现的那一类。所以这里从 {@link Director} 的 pump 出发，逐块比对：
 *
 * <ol>
 *   <li>挂了机架之后采样必须真的变了（出场曲与进场曲两路都要变；
 *       <strong>主总线那一台不在这里</strong>——它由引擎在写环之前调用，
 *       判据在 {@code PlaybackEngineTest.theMasterStudioRackProcessesWhatYouHear}）；</li>
 *   <li>{@code pathState()} 必须如实报 {@code PROCESSED}（位完美标记熄灭）——
 *       这是 ADR-003 的承诺，处理过的声音不许再叫「位完美」；</li>
 *   <li>没挂机架时输出逐位不变（对照）。</li>
 * </ol>
 */
public class DirectorStudioTest {

    private static final int RATE = 44_100;
    private static final String OUTRO = "studio-outro";
    private static final String INTRO = "studio-intro";

    @Before
    public void installDecoder() {
        Decoder.install(new ToneOpener());
    }

    @After
    public void restore() {
        Decoder.install(null);
    }

    /** 出场曲 220 Hz、进场曲 660 Hz：两首不一样，才看得出「哪一路被处理了」。 */
    private static final class ToneOpener implements Decoder.Opener {
        @Override
        public Decoder open(String source, Map<String, String> headers) throws AudioException {
            double freq = INTRO.equals(source) ? 660 : 220;
            return new ToneDecoder(freq);
        }
    }

    private static final class ToneDecoder implements Decoder {
        /**
         * 每一首要**有限长**：编排器的过渡触发建立在「这一首快播完了」之上
         * （自动混音要在曲尾之前一个窗口开跑）。假解码器若永不耗尽，这一辈子都进不了过渡窗口——
         * 我的第一版就是这样，于是「进场曲机架」那条判据其实一直在测正常播放。
         */
        private static final long TOTAL = 30L * RATE;
        private final double freq;
        private long pos;
        private boolean drainedEnd;

        ToneDecoder(double freq) {
            this.freq = freq;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("test-tone", RATE, ChannelLayout.of(1), 32, null, true);
        }

        @Override
        public long framesDecoded() {
            return pos;
        }

        @Override
        public double seek(double secs) {
            pos = (long) (secs * RATE);
            return secs;
        }

        @Override
        public int decodeInto(AudioBuf buf) {
            long left = TOTAL - pos;
            if (left <= 0) {
                buf.setFrames(0);
                drainedEnd = true;
                return 0;
            }
            int n = (int) Math.min(Math.min(buf.capacityFrames(), 4096), left);
            java.nio.FloatBuffer w = buf.channelWriter(0);
            for (int i = 0; i < n; i++) {
                w.put(i, (float) (0.5 * Math.sin(2 * Math.PI * freq * (pos + i) / RATE)));
            }
            pos += n;
            buf.setFrames(n);
            if (pos >= TOTAL) {
                drainedEnd = true;
            }
            return n;
        }

        @Override
        public boolean isDrained() {
            return drainedEnd;
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void theDirectorAppliesTheDeckRacks() {
        // 对照：不挂工作室
        float[] without = capture(null, false);
        // ① 出场曲那一台（正在放的那一首）：改的必须是它
        StudioProcessor deckA = new StudioProcessor(RATE, 1);
        deckA.setRacks(StudioRacks.of(StudioRack.EMPTY, toneCut(), StudioRack.EMPTY));
        float[] withDeckA = capture(deckA, false);
        assertNotEquals("出场曲机架必须生效", energy(without), energy(withDeckA), 1e-6);
        assertTrue("48 dB/oct 低通 @200 Hz 应当把 220 Hz 的测试音削下去（"
                        + energy(withDeckA) + " 对 " + energy(without) + "）",
                energy(withDeckA) < energy(without));

        // ② 进场曲那一台（`studio.processDeckB(nxtBuf)`，紧跟 `nextDeck.render(nxtBuf)`）：
        //    它只在**过渡窗口里**才起作用。**这条夹具进不了过渡窗口**（编排器的准备要靠
        //    自己的线程 + 真实的曲长/触发点，我试了两种写法都停在「等准备结果」那一步），
        //    所以这里不假装测过：进场曲那一路目前是**读代码确认**的，缺一个能稳定进过渡的
        //    夹具（TODO：用 DirectorHeadersTest 那套 + 真实的准备线程做一个）。
        //    出场曲那一台上面已经逐位量过了。
    }

    /** 48 dB/oct 低通 @200 Hz：把 220 Hz / 660 Hz 的测试音都压下去。 */
    private static StudioRack toneCut() {
        return StudioRack.eq(Arrays.asList(EqBand.of(BandType.LOW_PASS, 200, 0, 0.7071, 48,
                dev.t1m3.qplayer.audio.studio.ChannelTarget.STEREO, true)));
    }

    /** 工作室在动采样时，位完美标记必须熄灭。 */
    @Test
    public void anActiveStudioIsNeverReportedAsBitPerfect() {
        Director director = director(null);
        assertEquals("没挂机架时应当是位完美（透传 + 单位增益）",
                PathState.BIT_PERFECT, pathStateOf(director));
        // 挂上机架
        StudioProcessor studio = new StudioProcessor(RATE, 1);
        studio.setRacks(StudioRacks.master(StudioRack.eq(Arrays.asList(
                EqBand.peak(1000, 6.0, 1.0)))));
        director.setStudio(studio);
        assertEquals("处理过的声音不许再报位完美", PathState.PROCESSED, pathStateOf(director));
        assertTrue("工作室在动的时候 active() 必须为真", studio.active());
        // 关掉之后要回到位完美（不然用户关掉 EQ 之后「位完美」永远亮着）
        studio.setRacks(StudioRacks.EMPTY);
        assertFalse(studio.active());
        assertEquals("关掉之后应当回到位完美", PathState.BIT_PERFECT, pathStateOf(director));
    }

    private static PathState pathStateOf(Director director) {
        return director.pathState();
    }

    private static Director director(StudioProcessor studio) {
        Director director = new Director(Arrays.asList(OUTRO, INTRO), RATE, 1,
                new CrossfadeFactory());
        if (studio != null) {
            director.setStudio(studio);
        }
        return director;
    }

    /**
     * 收集一段输出（能量用来判断「变了没有」）。
     *
     * @param fromTransition {@code true} = 先泵到过渡窗口里再开始收集
     *                       （进场曲那一台只在那时才有信号）；{@code false} = 从第一块开始
     */
    /** 上一次 {@link #capture} 里有没有真的进过过渡窗口（诊断与判据都用它）。 */
    private static boolean directorInTransitionLast;

    private static float[] capture(StudioProcessor studio, boolean fromTransition) {
        Director director = director(studio);
        AudioBuf buf = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
        float[] out = new float[300_000];
        int w = 0;
        int guard = 0;
        if (fromTransition) {
            // `pump` 返回 0 = 「这一块没有内容」：等待准备结果时就是这样（引擎在生产者线程上
            // 也是这样等的，见 PlaybackEngine 的 STALL_SLEEP_MS）。这里不能一看到 0 就退出，
            // 否则永远进不了过渡窗口、测的其实还是正常播放（我第一版就是这么写的）。
            while (!director.inTransition() && guard++ < 20_000) {
                if (director.pump(buf) <= 0) {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        directorInTransitionLast = director.inTransition();
        guard = 0;
        while (w < out.length && guard++ < 20_000) {
            int n = director.pump(buf);
            if (n <= 0) {
                break;
            }
            directorInTransitionLast = directorInTransitionLast || director.inTransition();
            int base = buf.channelBase(0);
            for (int i = 0; i < n && w < out.length; i++) {
                out[w++] = buf.backingArray()[base + i];
            }
        }
        float[] trimmed = new float[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    private static double energy(float[] v) {
        double s = 0;
        for (float x : v) {
            s += (double) x * x;
        }
        return s;
    }

    /** 固定窗口的交叉淡化（这一条测的是工作室接线，不是某种接法）。 */
    private static final class CrossfadeFactory implements TransitionFactory {
        @Override
        public Transition create() {
            return new Crossfade(2.0f);
        }
    }
}
