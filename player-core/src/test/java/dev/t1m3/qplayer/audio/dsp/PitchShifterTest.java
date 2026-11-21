package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 逐条对齐测试：对应 Sonalis {@code sonalis-dsp/src/pitch.rs} 的 {@code mod tests}。
 *
 * <p>上游 14 条测试在这里都有同名（小驼峰）对应方法，断言与断言消息一一对应；
 * 另外补了 3 条 Java 侧必需的额外测试（延迟公式、{@code pushFrom} 的 offset 语义、
 * 超越函数换算的已知精确点），方法注释里都标了「本地补充」。
 *
 * <p><strong>浮点容差说明</strong>：上游比较 f32 时字面量（如 {@code 1e-5}）会被推断成 f32，
 * 这里对应写 {@code 1e-5f}，保持同一量级。<strong>没有为了迁就 Java 的超越函数放宽任何容差</strong>：
 * {@code Math.pow} / {@code Math.sin}（double 实现后立刻窄化）与 Rust 的 {@code powf} / {@code sinf}
 * 差异在 1 ulp 量级（相对 1e-7），而本文件里最小的容差是 {@code unityRatioIsTransparent}
 * 的 1e-4——留了三个数量级的余量。其余断言（过零率比 0.15、往返 1e-3）用的都是上游自己的
 * 容差，本就远大于 1 ulp。{@code 2^±1} 在二进制里精确，所以
 * {@code octaveUpDoublesFrequency} / {@code octaveDownHalvesFrequency} /
 * {@code latencyFramesMatchesDocumentedLookahead} 不依赖 pow 的实现精度。
 *
 * <p>上游测试里的造波函数 {@code sine} 用 f32 算术算相位（{@code 2π·f·i/rate}），
 * 这里也全程用 {@code float} 算，只有最外层 {@code Math.sin} 走 double 再窄化——
 * 输入与输出用的是同一个 {@code float[]}，所以即使与上游 libm 差 1 ulp，
 * 也不影响「输出 vs 输入」「过零率比」这类<em>相对</em>断言。
 */
public class PitchShifterTest {

    private static final int RATE = 48_000;

    /** {@code core::f32::consts::PI} 的 f32 镜像（与 {@link Fft#hann} 里的 PI 同源）。 */
    private static final float PI = (float) Math.PI;

    /** 对应上游测试里的 {@code mono(x)}。 */
    private static AudioBuf mono(float[] x) {
        AudioBuf b = new AudioBuf(ChannelLayout.MONO, Math.max(x.length, 1));
        b.setFrames(x.length);
        // 相对批量写（position 从 0 开始）：对应上游的 copy_from_slice。
        // 不用 put(int, float[], int, int) —— 那是 Java 13 才有的绝对批量写，
        // player-core 必须留在 Java 8（Android 直接吃这个 jar）。
        b.channelWriter(0).put(x, 0, x.length);
        return b;
    }

    /**
     * 对应上游测试里的 {@code sine(freq, n)}。
     *
     * <p>相位在 f32 上算，与上游 {@code (2.0 * PI * freq * i as f32 / RATE as f32)} 同序同类型。
     */
    private static float[] sine(float freq, int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float t = 2.0f * PI * freq * (float) i / (float) RATE;
            out[i] = (float) Math.sin((double) t);
        }
        return out;
    }

    /** 把整段输入推过变调器，返回全部输出。对应上游测试里的 {@code run}。 */
    private static float[] run(float semitones, float[] input) {
        PitchShifter ps = new PitchShifter(1, RATE, semitones, 4096);
        AudioBuf src = mono(input);
        AudioBuf out = new AudioBuf(ChannelLayout.MONO, 1024);
        FloatList got = new FloatList();
        int fed = 0;

        while (true) {
            while (fed < input.length) {
                int n = ps.pushFrom(src, fed);
                if (n == 0) {
                    break;
                }
                fed += n;
            }
            int n = ps.pull(out);
            if (n == 0) {
                if (fed >= input.length) {
                    break;
                }
                continue;
            }
            got.addAll(out.channel(0), n);
        }
        return got.toArray();
    }

    /** 过零次数——估基频用的简易指标。对应上游测试里的 {@code zero_crossings}。 */
    private static float zeroCrossings(float[] x, int rate) {
        int n = 0;
        for (int i = 0; i + 1 < x.length; i++) {
            if ((x[i] < 0.0f) != (x[i + 1] < 0.0f)) {
                n += 1;
            }
        }
        // 每次正弦周期过零两次。上游是 n/2 * rate / len，保持同一运算顺序。
        return (float) n / 2.0f * (float) rate / (float) Math.max(x.length, 1);
    }

    /** 把整个变调器拉干（喂不进就拉、拉拉再喂），返回全部输出。 */
    private static float[] drain(PitchShifter ps, AudioBuf src, int n) {
        AudioBuf out = new AudioBuf(ChannelLayout.MONO, 1024);
        FloatList collected = new FloatList();
        int fed = 0;
        while (true) {
            while (fed < n) {
                int t = ps.pushFrom(src, fed);
                if (t == 0) {
                    break;
                }
                fed += t;
            }
            int got = ps.pull(out);
            if (got == 0) {
                if (fed >= n) {
                    break;
                }
                continue;
            }
            collected.addAll(out.channel(0), got);
        }
        return collected.toArray();
    }

    /**
     * 重叠相加必须归一到 1——这是「ratio=1 透明」的全部依据。
     *
     * <p>归一化的对象是<strong>窗平方</strong>的重叠和：分析乘一次窗、合成再乘一次，
     * 所以逐点要除的是 {@code Σ w²(n − kH)}（周期 Hann、4 倍重叠下恒为 1.5），
     * 而不是 {@code Σ w(n − kH)}（那个恒为 2）。
     *
     * <p><strong>这条测试原来是个空跑</strong>：循环写成 {@code n = 4*h; n < g; n += h}，
     * 而 {@code g == 4*h}，于是循环体一次都没进过、断言一条都没执行——它一直是绿的，
     * 但什么都没测。改成从 0 扫到 {@code g}，并把断言换成换算法之后真正成立的那条
     * （窗平方 × 归一化因子 = 1）。
     */
    @Test
    public void windowSumIsUnity() {
        PitchShifter ps = new PitchShifter(1, RATE, 0.0f, 1024);
        int g = ps.grain();
        int h = ps.hop();
        float scale = ps.olaNormalization();
        int checked = 0;
        // 稳态区（前 3 个帧移的覆盖还不满，那是开头的淡入）逐采样检查：
        // 覆盖它的那些帧的窗平方之和 × 归一化因子必须恒等于 1
        for (int n = 3 * h; n < g; n++) {
            float sum = 0.0f;
            int k = 0;
            while (k * h <= n) {
                int off = n - k * h;
                if (off < g) {
                    float w = ps.windowAt(off);
                    sum += w * w;
                }
                k += 1;
            }
            assertTrue("第 " + n + " 帧的窗平方和为 " + sum + " × " + scale
                            + " = " + (sum * scale) + "，应为 1（重叠归一化有误）",
                    Math.abs(sum * scale - 1.0f) < 1e-4f);
            checked++;
        }
        assertTrue("一个位置都没检查到，这条测试等于没跑", checked >= h);
    }

    /**
     * 不变调时必须是（近似）透明的：这是本模块最重要的回归防线。
     * 一旦哪天窗归一化被改坏，音高测试仍会通过，只有这条会响。
     */
    @Test
    public void unityRatioIsTransparent() {
        float[] input = sine(440.0f, 16_000);
        float[] out = run(0.0f, input);
        assertTrue("输出过短：" + out.length, out.length > 12_000);

        // 跳过开头的淡入段（前 3 个粒的覆盖还不满）
        int skip = 3 * new PitchShifter(1, RATE, 0.0f, 1024).hop();
        for (int i = skip; i < Math.min(out.length, input.length); i++) {
            assertTrue("第 " + i + " 帧不透明：" + out[i] + " vs " + input[i],
                    Math.abs(out[i] - input[i]) < 1e-4f);
        }
    }

    /** 升高一个八度：基频应当翻倍。 */
    @Test
    public void octaveUpDoublesFrequency() {
        float[] input = sine(400.0f, 24_000);
        float[] out = run(12.0f, input);

        int skip = 4_000;
        float fIn = zeroCrossings(Arrays.copyOfRange(input, skip, skip + 12_000), RATE);
        float fOut = zeroCrossings(Arrays.copyOfRange(out, skip, skip + 12_000), RATE);
        float ratio = fOut / fIn;
        assertTrue("升高一个八度后频率比应为 2，实际 " + ratio + "（" + fIn + " → " + fOut + "）",
                Math.abs(ratio - 2.0f) < 0.15f);
    }

    /** 降低一个八度：基频应当减半。 */
    @Test
    public void octaveDownHalvesFrequency() {
        float[] input = sine(800.0f, 24_000);
        float[] out = run(-12.0f, input);

        int skip = 10_000;
        float fIn = zeroCrossings(Arrays.copyOfRange(input, skip, skip + 12_000), RATE);
        float fOut = zeroCrossings(Arrays.copyOfRange(out, skip, skip + 12_000), RATE);
        float ratio = fOut / fIn;
        assertTrue("降低一个八度后频率比应为 0.5，实际 " + ratio + "（" + fIn + " → " + fOut + "）",
                Math.abs(ratio - 0.5f) < 0.1f);
    }

    /**
     * 时长必须保持：产出帧数与喂入帧数之差，就等于内部延迟。
     *
     * <p>容差取<strong>一个合成帧移</strong>：末尾会被粒栅格量化——最后一个粒必须在帧移的
     * 整数倍处起跳，所以「还能不能继续产出」这件事本身有一个 hop 的粒度。
     * 这是栅格固有的，不是丢帧。
     */
    @Test
    public void durationIsPreserved() {
        int n = 20_000;
        float[] input = sine(300.0f, n);
        float[] out = run(3.0f, input);

        int lat = new PitchShifter(1, RATE, 3.0f, 4096).latencyFrames();
        int hop = PitchShifter.grainForRate(RATE) / 4;
        long expected = (long) n - (long) lat;
        long diff = Math.abs((long) out.length - expected);
        assertTrue("产出 " + out.length + " 帧，理论 " + expected + " 帧（输入 " + n
                + " − 延迟 " + lat + "），容差 " + hop, diff <= (long) hop);
    }

    /**
     * <b>一个瞬态进来、一个瞬态出去——不许被拆成好几次。</b>
     *
     * <p>为什么要单钉这一条：脉冲串是「鼓点」的替身，而变调器在这一档上最容易出的两种毛病
     * 都会让<b>节奏听起来变快</b>——① 把一次瞬态拆成「预响 + 主响」两次（听感是双击/赶拍），
     * ② 整体时间轴被压缩（那是重采样变速，另有判据）。用户 2026-09-30 报「声波重合时加速了」，
     * 实测这两条都不是（真正的原因在 `VocalAwarePitchTransition`：它当时把进场曲的伴奏也移了，
     * 见 docs/automix-modes.md §5.4.4）——这条判据就是把「不是」这个结论固定下来，
     * 以后谁把算法改回颗粒法（用梳状滤波冒充移调）这里会一起红。
     *
     * <p>实测（44.1 kHz、0.3 s 处一个脉冲）：各档移调下能量都落在 290~315 ms 这一小段里，
     * 单个脉冲被抹开约 ±10 ms，别处没有第二个包。
     */
    @Test
    public void aTransientComesOutAsASingleBurst() {
        int n = 20_000;
        int at = 6_000;
        float[] input = new float[n];
        for (int i = -3; i <= 3; i++) {
            input[at + i] = 0.6f * (1.0f - Math.abs(i) / 4.0f);
        }
        float[] shifts = {1.0f, 3.0f, -1.0f, -3.0f};
        for (float st : shifts) {
            float[] out = run(st, input);
            double total = 0.0;
            double near = 0.0;
            int half = (int) (0.03 * RATE);
            for (int i = 0; i < out.length; i++) {
                double e = (double) out[i] * out[i];
                total += e;
                if (Math.abs(i - at) <= half) {
                    near += e;
                }
            }
            assertTrue("整段输出是静音，测不出瞬态（" + st + " 半音）", total > 0.0);
            double frac = near / total;
            assertTrue(String.format(java.util.Locale.ROOT,
                            "%+.0f 半音：瞬态附近 ±30 ms 里只有 %.3f 的能量，"
                                    + "别处还有 %.3f——一次瞬态被拆成了好几次（听感是双击/赶拍）",
                            Float.valueOf(st), Double.valueOf(frac), Double.valueOf(1.0 - frac)),
                    frac > 0.98);
        }
    }

    /**
     * 变调不改变时长——这是与「重采样变调」的分水岭。
     *
     * <p>断言分两层：长度差必须落在粒栅格的量化误差内，<strong>而且</strong>相对变化必须
     * 远小于「若退化成重采样会产生的变化」。第二层才是真正有判别力的那条：
     * 只看第一层的话，一个缓慢的重采样错误也可能蒙混过关。
     */
    @Test
    public void pitchShiftDoesNotShortenMaterial() {
        int n = 20_000;
        float[] input = sine(300.0f, n);
        long flat = run(0.0f, input).length;
        long shifted = run(5.0f, input).length;

        long hop = PitchShifter.grainForRate(RATE) / 4;
        assertTrue("变调前后长度应一致：" + flat + " vs " + shifted + "（容差 " + (3 * hop) + "）",
                Math.abs(flat - shifted) <= 3L * hop);

        // 5 个半音对应比例约 1.335；真退化成重采样会让长度缩到 75%
        float resampleDelta = PitchShifter.semitonesToRatio(5.0f) - 1.0f;
        float observed = Math.abs((float) flat / (float) shifted - 1.0f);
        assertTrue("长度比变化 " + observed + " 已接近重采样的 " + resampleDelta
                + "——变调器退化成了变速", observed < resampleDelta * 0.3f);
    }

    /** 缓冲满时必须如实报告「只接收了一部分」，不能悄悄丢。 */
    @Test
    public void partialPushIsReported() {
        PitchShifter ps = new PitchShifter(1, RATE, 0.0f, 2048);
        float[] big = new float[200_000];
        Arrays.fill(big, 0.1f);
        AudioBuf src = mono(big);
        int took = ps.pushFrom(src, 0);
        assertTrue("首次喂入不该一个都收不下", took > 0);
        assertTrue("缓冲容量有限却报告收了 " + took + " 帧，说明容量算错或直接丢了数据",
                took < 200_000);
    }

    /** 立体声：两个声道各自独立处理，不能串。 */
    @Test
    public void stereoChannelsStayIndependent() {
        PitchShifter ps = new PitchShifter(2, RATE, 0.0f, 4096);
        int n = 8_000;
        AudioBuf src = new AudioBuf(ChannelLayout.STEREO, n);
        src.setFrames(n);
        // 对应上游逐帧 src.channel_mut(c)[i] = ±0.5：整段填充等价。
        // 同样是相对批量写，避开 Java 13+ 的带下标重载。
        float[] leftIn = new float[n];
        float[] rightIn = new float[n];
        Arrays.fill(leftIn, 0.5f);
        Arrays.fill(rightIn, -0.5f);
        src.channelWriter(0).put(leftIn, 0, n);
        src.channelWriter(1).put(rightIn, 0, n);

        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, 1024);
        FloatList left = new FloatList();
        FloatList right = new FloatList();
        int fed = 0;
        while (true) {
            while (fed < n) {
                int t = ps.pushFrom(src, fed);
                if (t == 0) {
                    break;
                }
                fed += t;
            }
            int got = ps.pull(out);
            if (got == 0) {
                if (fed >= n) {
                    break;
                }
                continue;
            }
            left.addAll(out.channel(0), got);
            right.addAll(out.channel(1), got);
        }

        float[] l = left.toArray();
        float[] r = right.toArray();
        int skip = 3 * ps.hop();
        for (int i = skip; i < Math.min(l.length, 4_000); i++) {
            assertTrue("左声道第 " + i + " 帧：" + l[i], Math.abs(l[i] - 0.5f) < 1e-3f);
            assertTrue("右声道第 " + i + " 帧：" + r[i], Math.abs(r[i] + 0.5f) < 1e-3f);
        }
    }

    /** 声道数不足的源喂给更多声道的目标时，不该留下静音的半边。 */
    @Test
    public void monoSourceFillsAllTargetChannels() {
        PitchShifter ps = new PitchShifter(2, RATE, 0.0f, 4096);
        int n = 4_000;
        float[] data = new float[n];
        Arrays.fill(data, 0.3f);
        AudioBuf src = mono(data);
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, 1024);

        int fed = 0;
        float rightMax = 0.0f;
        while (true) {
            while (fed < n) {
                int t = ps.pushFrom(src, fed);
                if (t == 0) {
                    break;
                }
                fed += t;
            }
            int got = ps.pull(out);
            if (got == 0) {
                if (fed >= n) {
                    break;
                }
                continue;
            }
            FloatBuffer rv = out.channel(1);
            for (int i = 0; i < got; i++) {
                rightMax = Math.max(rightMax, Math.abs(rv.get(i)));
            }
        }
        assertTrue("单声道源喂入后右声道全静音，实际峰值 " + rightMax, rightMax > 0.1f);
    }

    @Test
    public void resetClearsState() {
        PitchShifter ps = new PitchShifter(1, RATE, 0.0f, 1024);
        float[] data = new float[4_000];
        Arrays.fill(data, 0.5f);
        AudioBuf src = mono(data);
        ps.pushFrom(src, 0);
        AudioBuf out = new AudioBuf(ChannelLayout.MONO, 64);
        ps.pull(out);

        ps.reset();
        assertEquals("reset 后 pushed 应为 0", 0L, ps.pushed());
        assertEquals("reset 后 base 应为 0", 0L, ps.base());
        // 重置后立刻 pull 应当因为没有输入而返回 0
        assertEquals("重置后立刻 pull 应当因为没有输入而返回 0", 0, ps.pull(out));
    }

    @Test
    public void semitonesRoundtrip() {
        float[] cases = {-3.0f, -1.0f, 0.0f, 1.0f, 3.0f};
        for (float s : cases) {
            float r = PitchShifter.semitonesToRatio(s);
            float back = PitchShifter.ratioToSemitones(r);
            assertTrue(s + " 半音往返后变成 " + back, Math.abs(back - s) < 1e-3f);
        }
    }

    /**
     * 比例必须被夹住：超出环形容量的前视会让 push 与 pull 互相锁死。
     */
    @Test
    public void ratioIsClamped() {
        PitchShifter ps = new PitchShifter(1, RATE, 0.0f, 1024);
        ps.setSemitones(60.0f); // 5 个八度
        assertTrue("比例未被夹住：" + ps.ratio(), ps.ratio() <= PitchShifter.MAX_RATIO);
        ps.setSemitones(-60.0f);
        assertTrue("比例未被夹住（下界）：" + ps.ratio(),
                ps.ratio() >= 1.0f / PitchShifter.MAX_RATIO);
    }

    @Test
    public void unityDetection() {
        PitchShifter ps = new PitchShifter(1, RATE, 0.0f, 1024);
        assertTrue("0 半音时应当报告处于不变调状态（此时调用方要整段旁路，见类注释）",
                ps.isUnity());
        ps.setSemitones(0.5f);
        assertFalse("0.5 半音时不该报告不变调", ps.isUnity());
    }

    /** 粒长随采样率放大，且始终是 2 的幂。 */
    @Test
    public void grainScalesWithRate() {
        assertEquals(2048, PitchShifter.grainForRate(48_000));
        assertEquals(4096, PitchShifter.grainForRate(96_000));
        int g = PitchShifter.grainForRate(44_100);
        assertTrue("44.1 kHz 的粒长应当是 2 的幂，实际 " + g, isPowerOfTwo(g));
    }

    // ------------------------------------------------------------------
    // 本地补充（上游没有对应测试；Java 侧要么没法覆盖、要么想额外钉住的性质）
    // ------------------------------------------------------------------

    /**
     * 本地补充：流式契约下的延迟必须等于 {@code N − H}（{@code N = 帧长、H = 帧移}）。
     *
     * <p><strong>换算法之后这条公式变了</strong>（原来是颗粒法的
     * {@code ceil((G−1)·ratio)+1}）：相位声码器要交付输出帧 {@code t}，必须先做出
     * 覆盖它的最后一帧（起点 {@code floor(t/H)·H}），而那一帧要读到输入
     * {@code floor(t/H)·H + N}。于是「不调用 drain 时能产出多少」正好比喂入量少
     * {@code N − H} 帧；{@code deck.rs} 用它决定还要喂多少帧，报小了开头几帧会读到
     * 尚未喂入的输入、报大了白等。
     *
     * <p>它与变调量<strong>无关</strong>——这正是相位声码器与颗粒法的另一处差别：
     * 颗粒法的前视随 ratio 线性增长（±2 个八度差 4 倍），声码器恒为一帧的长度。
     * 48 kHz 下 {@code N = 2048}、{@code H = 512}，所以三个比例都报 1536。
     */
    @Test
    public void latencyFramesMatchesDocumentedLookahead() {
        assertEquals("ratio=1：N − H = 2048 − 512",
                1536, new PitchShifter(1, RATE, 0.0f, 1024).latencyFrames());
        assertEquals("升高一个八度也还是 N − H（与 ratio 无关）",
                1536, new PitchShifter(1, RATE, 12.0f, 1024).latencyFrames());
        assertEquals("降低一个八度同理",
                1536, new PitchShifter(1, RATE, -12.0f, 1024).latencyFrames());
    }

    /**
     * 本地补充：{@code pushFrom} 的 {@code offset} 必须真的被尊重。
     *
     * <p>上游的 {@code run()} 经由 {@code push_from(&src, fed)} 间接用到 offset，但没有一条
     * 测试直接断言「喂进去的是哪一段」。这里把前 2048 帧填 0.9、其余填 0.1，
     * 只从第 2048 帧开始喂：若 offset 被忽略（当成 0），输出会全是 0.9，断言立刻响。
     */
    @Test
    public void pushFromRespectsOffset() {
        PitchShifter ps = new PitchShifter(1, RATE, 0.0f, 4096);
        float[] data = new float[6_000];
        Arrays.fill(data, 0, 2048, 0.9f);
        Arrays.fill(data, 2048, data.length, 0.1f);
        AudioBuf src = mono(data);

        int took = ps.pushFrom(src, 2048);
        assertEquals("剩余的 6000-2048=3952 帧应当全部收下（环形容量足够）", 3952, took);

        float[] out = drain(ps, src, 0); // 已经把 3952 帧喂完，这里只拉干
        int skip = 3 * ps.hop();
        assertTrue("输出过短：" + out.length, out.length > skip);
        for (int i = skip; i < out.length; i++) {
            assertTrue("第 " + i + " 帧应当读到 offset 之后的 0.1，实际 " + out[i]
                    + "（若为 0.9 说明 offset 被忽略）", Math.abs(out[i] - 0.1f) < 1e-3f);
        }
    }

    /**
     * 本地补充：超越函数换算在「二进制精确点」上必须逐位精确。
     *
     * <p>{@link PitchShifter#semitonesToRatio} 走 {@code Math.pow}（double），上游走
     * {@code powf}（平台 libm）。{@code 2^±1}、{@code 2^0}、{@code 2^(6/12)=√2} 里前三个
     * 都是精确值，两边都必须给出同一个 bit；{@code √2} 那边才可能出现 1 ulp 差，
     * 所以按 {@code 1e-7f}（相对 1 ulp 稍微留一点余量）断言，并在
     * {@link PitchShifterTest} 的类注释里写明这个容差只覆盖超越函数实现差异。
     */
    @Test
    public void transcendentalHelpersMatchKnownIntervals() {
        assertEquals("2^0 必须精确为 1", 1.0f, PitchShifter.semitonesToRatio(0.0f), 0.0f);
        assertEquals("2^1 必须精确为 2", 2.0f, PitchShifter.semitonesToRatio(12.0f), 0.0f);
        assertEquals("2^-1 必须精确为 0.5", 0.5f, PitchShifter.semitonesToRatio(-12.0f), 0.0f);
        // 6 个半音 = √2：这里才可能出现 1 ulp 级的实现差异
        assertEquals("升 6 个半音应为 √2", (float) Math.sqrt(2.0),
                PitchShifter.semitonesToRatio(6.0f), 1e-7f);
        // ratioToSemitones 的 log2 走 log/log2 换算，同样只给 1 ulp 级容差
        assertEquals("log2 换算：ratio=2 应当回到 12 个半音",
                12.0f, PitchShifter.ratioToSemitones(2.0f), 1e-4f);
        assertEquals("log2 换算：ratio=1 应当回到 0 个半音",
                0.0f, PitchShifter.ratioToSemitones(1.0f), 1e-4f);
    }

    /** 2 的幂判定，对应 Rust 的 {@code usize::is_power_of_two}。 */
    private static boolean isPowerOfTwo(int v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    /** 测试用的可增长 f32 列表，对应上游测试里累积输出的 {@code Vec<f32>}。 */
    private static final class FloatList {

        private float[] a = new float[64];
        private int n;

        void addAll(FloatBuffer src, int count) {
            ensure(n + count);
            // 相对批量读：src 是每次现取的读视图（position=0、limit=有效帧数），
            // 对应上游的 extend_from_slice(&out[..n])。
            // 同样避开 Java 13 的 get(int, float[], int, int)。
            src.get(a, n, count);
            n += count;
        }

        private void ensure(int cap) {
            if (cap <= a.length) {
                return;
            }
            int len = a.length;
            while (len < cap) {
                len *= 2;
            }
            a = Arrays.copyOf(a, len);
        }

        float[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}
