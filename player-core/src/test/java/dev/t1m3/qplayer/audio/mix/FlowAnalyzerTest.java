package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link FlowAnalyzer} 的测试。
 *
 * <h3>为什么注入一个「读法」而不是造 wav 文件</h3>
 *
 * <p>本类要钉的是<strong>窗口怎么切</strong>这一层：整段窗是开头 30 秒、
 * 开头窗是它的前 15 秒、交给下一首的是<strong>结尾</strong>那段而不是平均值。
 * 这些用合成 PCM 就能钉得比真文件更准（BPM 是已知的、按构造可推导），
 * 而且不必依赖宿主的解码器工厂（{@code Decoder.install} 是全局静态状态，
 * 装在测试里会污染同一 JVM 里的其它测试类）。
 *
 * <p>真正走宿主解码器那条路（{@code FlowAnalyzer.DECODER} 调
 * {@code Automix.readMono}）由端到端测试覆盖，见交付报告的「未验证的部分」。
 */
public class FlowAnalyzerTest {

    private static final int RATE = 44_100;

    /**
     * 造一段带拍点的信号：每 {@code bpm} 拍一个衰减脉冲。
     *
     * <p>与 {@code AnalysisTest} 的做法同源（那是本仓库既有的测试手法）：
     * 用脉冲而不是真实音乐，是因为测试要的是<strong>可推导的答案</strong>——
     * BPM 已知，正确的估计就该是它。这里另写一份而不是共享一个 helper，
     * 是为了让两个测试类各自独立成立：改坏一个不会让另一个的结论失去依据。
     */
    private static float[] clicks(float bpm, double secs, float amplitude) {
        int n = (int) ((double) RATE * secs);
        float[] v = new float[n];
        int period = (int) ((double) RATE * 60.0 / (double) bpm);
        int t = 0;
        while (t < n) {
            int len = (int) ((float) RATE * 0.03f);
            for (int k = 0; k < len; k++) {
                if (t + k >= n) {
                    break;
                }
                float env = (float) Math.exp((double) (-((float) k) / ((float) RATE * 0.006f)));
                v[t + k] += env * (float) Math.sin((double) ((float) (t + k) * 12_989.0f)) * 0.5f;
            }
            t += period;
        }
        for (int i = 0; i < n; i++) {
            v[i] *= amplitude;
        }
        return v;
    }

    /** 一条纯正弦（用来钉频谱质心：频率高的质心就该高）。 */
    private static float[] tone(double hz, double secs, float amplitude) {
        int n = (int) ((double) RATE * secs);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = (float) Math.sin(2.0 * Math.PI * hz * (double) i / (double) RATE) * amplitude;
        }
        return v;
    }

    /**
     * 假的读法：开头段固定 120 BPM、结尾段固定 90 BPM，两段响度不同。
     *
     * <p>于是「分析出来的是 120 还是 90」直接说明了它读的是哪一段——
     * 这正是本类要钉的东西。
     */
    private static final class TwoEnds implements FlowAnalyzer.Reader {

        final List<String> calls = new ArrayList<>();
        boolean failTail;

        @Override
        public Automix.Mono read(String source, Map<String, String> headers,
                                 boolean tail, double secs) {
            calls.add((tail ? "tail:" : "head:") + secs);
            if (tail && failTail) {
                throw AudioException.config("文件时长未知，无法定位尾部");
            }
            if (tail) {
                // 结尾 15 秒：90 BPM，明显更轻
                return new Automix.Mono(clicks(90.0f, 15.0, 0.10f), RATE, 45.0);
            }
            // 开头 30 秒：120 BPM
            return new Automix.Mono(clicks(120.0f, 30.0, 0.50f), RATE, 45.0);
        }
    }

    // ---- 窗口 -------------------------------------------------------------

    /** 整段窗读 30 秒、开头窗取它的前 15 秒、结尾窗单独读 15 秒。 */
    @Test
    public void readsThirtySecondsFromTheStartAndFifteenFromTheEnd() {
        TwoEnds reader = new TwoEnds();
        FlowAnalyzer analyzer = new FlowAnalyzer(reader);
        analyzer.analyze("file:/x.mp3", null);

        assertEquals("一共两次读，不是三次", 2, reader.calls.size());
        assertEquals("head:" + FlowAnalyzer.WHOLE_SECS, reader.calls.get(0));
        assertEquals("tail:" + FlowAnalyzer.EDGE_SECS, reader.calls.get(1));
    }

    /**
     * 「交出去」的那一侧取的是<strong>结尾</strong>段，「接进来」的那一侧取的是开头段。
     *
     * <p>这条是整个排序模块的立足点：接缝只发生在开头和结尾，
     * 拿整首歌的平均速度去接任何东西都是错的。
     */
    @Test
    public void outgoingFeaturesComeFromTheTailAndIncomingOnesFromTheHead() {
        FlowAnalyzer analyzer = new FlowAnalyzer(new TwoEnds());
        FlowFeatures f = analyzer.analyze("file:/x.mp3", null);

        assertNotNull(f);
        assertEquals("整段窗是 120 BPM", 120.0f, f.tempo, 4.0f);
        assertEquals("开头窗也应当是 120 BPM（它是整段窗的前缀）", 120.0f, f.headTempo, 4.0f);
        assertEquals("结尾窗是 90 BPM", 90.0f, f.tailTempo, 4.0f);
        assertEquals("接进来用的是开头段", f.headTempo, f.incomingTempo(), 1e-6f);
        assertEquals("交出去用的是结尾段", f.tailTempo, f.outgoingTempo(), 1e-6f);

        // 响度：开头段振幅 0.50、结尾段 0.10，所以结尾必然明显更轻
        assertTrue("开头应当明显比结尾响（" + f.headLevel + " vs " + f.tailLevel + "）",
                f.headLevel > f.tailLevel * 2.0f);
        assertEquals("时长从解码器那边带出来", 45.0, f.seconds, 1e-6);
    }

    /** 请求头一路传到读法那一层（网易云/插件直链需要它）。 */
    @Test
    public void headersReachTheReader() {
        final List<Map<String, String>> seen = new ArrayList<>();
        FlowAnalyzer.Reader spy = new FlowAnalyzer.Reader() {
            @Override
            public Automix.Mono read(String source, Map<String, String> headers,
                                     boolean tail, double secs) {
                seen.add(headers);
                return new Automix.Mono(clicks(120.0f, 2.0, 0.3f), RATE, 2.0);
            }
        };
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", "https://example.invalid/");
        new FlowAnalyzer(spy).analyze("https://cdn.invalid/a.mp3", headers);

        assertEquals(2, seen.size());
        for (Map<String, String> h : seen) {
            assertNotNull("请求头必须传下去，否则这类直链根本打不开", h);
            assertEquals("https://example.invalid/", h.get("Referer"));
        }
    }

    // ---- 「不知道」怎么表示 ------------------------------------------------

    /**
     * 结尾段整个读不到时：这一首<strong>仍然算分析出来了</strong>
     * （开头那一半是有效的），只是「交出去的那一侧」如实记为不知道。
     *
     * <p>两个「不知道」的表示不同，这是刻意的：
     * {@code tailTempo = 0}（测不出速度）、{@code tailLevel} 是 {@code NaN}
     * （这段根本不存在）。响度真的可能为 0（静音结尾），所以 0 不能拿来当「没有」。
     */
    @Test
    public void anUnreadableTailLeavesTheOutgoingSideUnknownWithoutFailingTheTrack() {
        TwoEnds reader = new TwoEnds();
        reader.failTail = true;
        FlowFeatures f = new FlowAnalyzer(reader).analyze("file:/x.mp3", null);

        assertNotNull("开头读得到，这一首就是分析出来了", f);
        assertEquals(120.0f, f.tempo, 4.0f);
        assertEquals(120.0f, f.headTempo, 4.0f);
        assertEquals("结尾速度不知道就记 0，不编一个", 0.0f, f.tailTempo, 1e-6f);
        assertTrue("结尾响度是 NaN（这段不存在），不是 0（很安静）", Float.isNaN(f.tailLevel));
        assertTrue(f.headLevel > 0.0f);
    }

    /** 素材太短就如实失败，而不是返回一份「特征全是 0」的正常结果。 */
    @Test
    public void tooLittleAudioToAnalyzeFailsHonestly() {
        FlowAnalyzer.Reader tiny = new FlowAnalyzer.Reader() {
            @Override
            public Automix.Mono read(String source, Map<String, String> headers,
                                     boolean tail, double secs) {
                return new Automix.Mono(new float[64], RATE, 0.001);
            }
        };
        try {
            new FlowAnalyzer(tiny).analyze("file:/tiny.mp3", null);
            fail("素材太短时应当抛 AudioException，而不是给一份全 0 的特征");
        } catch (AudioException expected) {
            assertTrue(expected.getMessage().contains("太短"));
        }
    }

    /** 整段窗读失败直接往外抛：调用方据此把这一首记成「没分析出来」。 */
    @Test
    public void anUnreadableSourcePropagatesSoTheCallerCanReportIt() {
        FlowAnalyzer.Reader broken = new FlowAnalyzer.Reader() {
            @Override
            public Automix.Mono read(String source, Map<String, String> headers,
                                     boolean tail, double secs) {
                throw AudioException.decode("打不开：" + source);
            }
        };
        try {
            new FlowAnalyzer(broken).analyze("file:/gone.mp3", null);
            fail("读不到就应当抛出去，让上层如实记为「没分析出来」");
        } catch (AudioException expected) {
            assertTrue(expected.getMessage().contains("gone.mp3"));
        }
    }

    /** 纯静音：速度测不出（0），但响度是真的 0——两者不能混为一谈。 */
    @Test
    public void silenceGivesNoTempoButARealZeroLevel() {
        FlowAnalyzer.Reader silent = new FlowAnalyzer.Reader() {
            @Override
            public Automix.Mono read(String source, Map<String, String> headers,
                                     boolean tail, double secs) {
                return new Automix.Mono(new float[RATE * 5], RATE, 5.0);
            }
        };
        FlowFeatures f = new FlowAnalyzer(silent).analyze("file:/silent.mp3", null);
        assertNotNull(f);
        assertEquals("静音没有可辨的节拍，不许编一个 BPM 出来", 0.0f, f.tempo, 1e-6f);
        assertEquals(0.0f, f.headTempo, 1e-6f);
        assertEquals("响度是真的 0", 0.0f, f.headLevel, 1e-6f);
        assertFalse("响度 0 不等于「这段不存在」", Float.isNaN(f.tailLevel));
    }

    // ---- 响度与音色 -------------------------------------------------------

    /** 均方根：振幅减半，RMS 减半。 */
    @Test
    public void rmsHalvesWhenTheAmplitudeHalves() {
        float[] loud = clicks(120.0f, 2.0, 0.40f);
        float[] quiet = clicks(120.0f, 2.0, 0.20f);
        float a = FlowAnalyzer.rms(loud);
        float b = FlowAnalyzer.rms(quiet);
        assertTrue(a > 0.0f);
        assertEquals("振幅减半 → RMS 减半", a / 2.0f, b, a * 0.02f);
        assertEquals(0.0f, FlowAnalyzer.rms(new float[0]), 1e-9f);
    }

    /**
     * 频谱质心：高频正弦的质心显著高于低频正弦。
     *
     * <p>这一项在贴合分里只占 0.2 的权重，但要钉的是<strong>方向</strong>——
     * 名字叫「亮度」，高音就必须比低音更亮。方向反了的话，排序会把
     * 大提琴推到鼓机后面，而界面上没有任何数字能暴露这件事。
     */
    @Test
    public void brightnessRisesWithFrequency() {
        float low = FlowAnalyzer.toneOf(tone(200.0, 3.0, 0.5f), RATE);
        float high = FlowAnalyzer.toneOf(tone(4000.0, 3.0, 0.5f), RATE);
        assertTrue("200 Hz 的质心应当接近 200，实际 " + low, Math.abs(low - 200.0f) < 60.0f);
        assertTrue("4 kHz 的质心应当接近 4000，实际 " + high, Math.abs(high - 4000.0f) < 400.0f);
        assertTrue("高音必须比低音更亮", high > low * 5.0f);
    }

    /** 素材太短给 0（「不知道」），不是抛异常也不是 NaN。 */
    @Test
    public void brightnessOfTooShortAudioIsZero() {
        assertEquals(0.0f, FlowAnalyzer.toneOf(new float[10], RATE), 1e-9f);
        assertEquals(0.0f, FlowAnalyzer.toneOf(new float[RATE], 0), 1e-9f);
    }
}
