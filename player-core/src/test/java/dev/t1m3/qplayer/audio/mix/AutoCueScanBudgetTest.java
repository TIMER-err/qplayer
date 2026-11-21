package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>自动点位的扫描预算</b>：进场侧只扫「候选点可能落在的那一段」。
 *
 * <h2>为什么这条必须有测试（2026-09-26 手机端实测）</h2>
 *
 * <p>整曲扫描是自动点位最贵的一步：它要把整首歌<strong>再解一遍</strong>。进场曲的句柄在
 * 真机上很多情况下还是那条<strong>带签名的直链</strong>（预取的音频还没落盘），于是这一次
 * 扫描等于<strong>把整首歌再下一次</strong>——手机上一首歌几十秒，而 {@code Director} 在
 * 触发点没有准备结果时<strong>刻意不开始过渡</strong>（见 {@code beginTransition}），
 * 用户听到的就是「开了自动点位反而完全没有过渡」。
 *
 * <p>而 {@link AutoCue#plan} 对进场点的硬约束是 {@code ≤ min(20 s, 25%·时长)}，
 * 所以进场侧根本不需要整曲。这条测试钉住三件事：
 *
 * <ol>
 *   <li>扫的<strong>真的只有那么多</strong>（hop 数按预算截断，不是「读了但没用」）；</li>
 *   <li><strong>时长仍报真实时长</strong>——否则三条硬约束会按「扫到的那一段」算，
 *       进场点上限直接歪掉（这是最容易写错的一处：包络长度与时长不再是同一个数）；</li>
 *   <li>预算本身与 {@link AutoCue#plan} 的约束<strong>同步</strong>：预算必须覆盖
 *       进场点上限，不能被改小到约束之外。</li>
 * </ol>
 */
public class AutoCueScanBudgetTest {

    private static final int RATE = 44_100;

    /**
     * 一分钟的「曲子」：进场侧预算 = min(20, 25%·60) + 5 = 20 s，所以只有约 20 秒的 hop。
     *
     * <p>断言用「扫到的秒数」而不是 hop 数：hop 是 20 ms，而 {@code Decoder.decodeInto}
     * 按块交付，末尾可能多算不到一块——直接比秒数会随块长抖动。
     */
    @Test
    public void theIntroScanStopsAtTheBudgetWhileStillReportingTheRealDuration() {
        int seconds = 60;
        float[] pcm = tone(seconds, 220.0);
        AutoCue.Envelope intro = scanIntro(pcm);
        double budget = AutoCue.introScanSecs(Double.valueOf(seconds));
        assertEquals("预算按约束算：min(20, 25%·60) + 5", 20.0, budget, 1e-9);

        double scanned = intro.hops() * AutoCue.HOP_SECS;
        assertTrue("进场侧必须只扫预算之内（扫了 " + scanned + " s，预算 " + budget + " s）",
                scanned <= budget + 1.0);
        assertTrue("也不能少扫太多（否则候选点会落空）：扫了 " + scanned + " s",
                scanned >= budget - 1.0);
        assertEquals("**时长必须仍是真实时长**（约束按它算，不是按扫到的那一段）",
                60.0, intro.durationSecs, 1e-6);
    }

    /**
     * 整曲扫描（不传预算）仍然扫满，而且时长与包络长度一致——
     * 这条是上一条的对照：差别只该来自「预算」，不该来自别的改动。
     */
    @Test
    public void aFullScanStillCoversTheWholeTrack() {
        int seconds = 60;
        float[] pcm = tone(seconds, 220.0);
        AutoCue.Envelope full = AutoCueTestSupport.scan(pcm, RATE);
        double scanned = full.hops() * AutoCue.HOP_SECS;
        assertEquals("整曲扫描要把整首扫完", 60.0, scanned, 0.2);
        assertEquals("时长 = 扫出来的长度（这一条与进场侧不同，见上一条）",
                60.0, full.durationSecs, 0.2);
    }

    /**
     * 预算与约束同步：进场点上限 {@code min(20, 25%·时长)} 必须落在预算之内，
     * 而且要留出斜率窗口（{@link AutoCue#INTRO_SCAN_MARGIN_SECS}）。
     *
     * <p>三个时长各测一次（长曲、刚好 80 s 的曲、很短的曲）：改任何一边的常量，
     * 这里就会红。
     */
    @Test
    public void theBudgetAlwaysCoversTheEntryCeilingPlusTheSlopeWindow() {
        double[] durations = {300.0, 80.0, 30.0};
        for (double dur : durations) {
            double ceiling = Math.min(AutoCue.MAX_INTRO_SECS, dur * AutoCue.MAX_INTRO_FRACTION);
            double budget = AutoCue.introScanSecs(Double.valueOf(dur));
            assertTrue("时长 " + dur + " s：预算 " + budget + " 必须覆盖进场点上限 " + ceiling,
                    budget >= ceiling);
            assertEquals("余量要正好是斜率窗口：预算 = 上限 + " + AutoCue.INTRO_SCAN_MARGIN_SECS,
                    ceiling + AutoCue.INTRO_SCAN_MARGIN_SECS, budget, 1e-9);
        }
        assertEquals("时长未知（流式）时退回整曲扫描：0", 0.0,
                AutoCue.introScanSecs(null), 1e-9);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private static AutoCue.Envelope scanIntro(final float[] pcm) {
        dev.t1m3.qplayer.audio.decode.Decoder.install(
                new dev.t1m3.qplayer.audio.decode.Decoder.Opener() {
                    @Override
                    public dev.t1m3.qplayer.audio.decode.Decoder open(
                            String source, java.util.Map<String, String> headers) {
                        return fake(pcm);
                    }
                });
        try {
            return AutoCue.scanIntro("test://intro", null);
        } finally {
            dev.t1m3.qplayer.audio.decode.Decoder.install(null);
        }
    }

    /** 一段单音（220 Hz 正弦，幅度 0.5）。 */
    private static float[] tone(int seconds, double hz) {
        float[] pcm = new float[seconds * RATE];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * hz * i / RATE));
        }
        return pcm;
    }

    /** 与 {@code AutoCueTestSupport} 里那个假解码器同一形状（那个是包私有的私有类）。 */
    private static dev.t1m3.qplayer.audio.decode.Decoder fake(final float[] pcm) {
        return new dev.t1m3.qplayer.audio.decode.Decoder() {

            private int position;

            @Override
            public dev.t1m3.qplayer.audio.decode.TrackSpec spec() {
                return new dev.t1m3.qplayer.audio.decode.TrackSpec("pcm", RATE,
                        dev.t1m3.qplayer.audio.core.ChannelLayout.of(1), Integer.valueOf(16),
                        Long.valueOf(pcm.length), true);
            }

            @Override
            public long framesDecoded() {
                return position;
            }

            @Override
            public boolean isDrained() {
                return position >= pcm.length;
            }

            @Override
            public double seek(double secs) {
                position = Math.max(0, Math.min(pcm.length, (int) (secs * RATE)));
                return position / (double) RATE;
            }

            @Override
            public int decodeInto(dev.t1m3.qplayer.audio.core.AudioBuf out) {
                int n = Math.min(out.capacityFrames(), pcm.length - position);
                if (n <= 0) {
                    out.setFrames(0);
                    return 0;
                }
                java.nio.FloatBuffer dst = out.channelWriter(0);
                for (int i = 0; i < n; i++) {
                    dst.put(i, pcm[position + i]);
                }
                out.setFrames(n);
                position += n;
                return n;
            }

            @Override
            public void close() {
            }
        };
    }
}
