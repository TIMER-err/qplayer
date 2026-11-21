package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 逐条对齐测试：对应 Sonalis {@code sonalis-dsp/src/resample.rs} 的 {@code mod tests}。
 *
 * <p>上游 14 条测试在这里都有同名（小驼峰）对应方法，断言与断言消息一一对应；
 * 另外补了若干 Java 侧必需的额外测试（上游 {@code reset} / 构造前置条件 /
 * Java 独有的 {@code pushAll} 入口 / <strong>上游没有的 {@code drain} 流末尾收尾</strong>等），
 * 都在方法注释里标注了「本地补充」。
 *
 * <p>浮点容差说明：上游比较 f32 时字面量（如 {@code 1e-6}）会被推断成 f32，
 * 这里对应写 {@code 1e-6f}，保持同一量级；上游比较 f64 的地方用 double 字面量。
 * 到目前为止没有为了迁就 Java 超越函数而放宽任何容差 —— 见
 * {@link #sameRateIsSampleExact()} 的说明。
 */
public class ResamplerTest {

    /**
     * 把一段输入全部转换完，返回输出。对应上游测试模块里的 {@code convert}。
     *
     * <p>刻意写成「分批喂 + 每批拉干」：{@code push} 是部分接收语义，
     * 一次性丢进去会超出历史缓冲容量而被静默丢弃。
     * 这个辅助函数本身就是那个踩坑现场，现在它是回归防线。
     */
    private static float[] convert(int inRate, int outRate, float[] input) {
        Resampler rs = new Resampler(inRate, outRate, 1);
        int outFrames = (int) Math.ceil((double) input.length * (double) outRate / (double) inRate) + 32;
        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, Math.max(outFrames, 16));
        FloatList collected = new FloatList();

        // 按历史缓冲容量的一半分批，保证每一批都必被完整接收
        int chunkFrames = Math.max(Resampler.HISTORY_FRAMES / 2, 1);
        int offset = 0;
        while (offset < input.length) {
            int take = Math.min(input.length - offset, chunkFrames);
            AudioBuf chunk = new AudioBuf(ChannelLayout.MONO, take);
            chunk.setFrames(take);
            // 相对批量写（position 从 0 开始）：对应上游的 copy_from_slice。
            // 不用 put(int, float[], int, int) —— 那是 Java 13 才有的绝对批量写，
            // player-core 必须留在 Java 8（Android 直接吃这个 jar）。
            chunk.channelWriter(0).put(input, offset, take);

            int accepted = rs.push(chunk);
            offset += accepted;

            // 拉干，让 reclaim 腾出历史空间再喂下一批
            while (true) {
                int n = rs.pull(dst);
                if (n == 0) {
                    break;
                }
                collected.addAll(dst.channel(0), n);
            }

            if (accepted == 0) {
                // 拉干后仍喂不进：不该发生，防御性跳出，避免死循环。
                break;
            }
        }

        // 收尾：把最后一批还没拉完的取出来
        while (true) {
            int n = rs.pull(dst);
            if (n == 0) {
                break;
            }
            collected.addAll(dst.channel(0), n);
        }

        return collected.toArray();
    }

    /** 造一块全部填 {@code v} 的单声道缓冲（上游测试里 {@code vec![v; n]} + AudioBuf 的等价写法）。 */
    private static AudioBuf mono(int frames, float v) {
        AudioBuf b = new AudioBuf(ChannelLayout.MONO, frames);
        b.setFrames(frames);
        FloatBuffer w = b.channelWriter(0);
        for (int i = 0; i < frames; i++) {
            w.put(i, v);
        }
        return b;
    }

    @Test
    public void kernelRowsSumToOne() {
        float[] k = Resampler.buildKernel(0.95);
        for (int phase = 0; phase < Resampler.OVERSAMPLE; phase++) {
            int row = phase * Resampler.TAPS;
            double sum = 0.0;
            for (int i = 0; i < Resampler.TAPS; i++) {
                sum += (double) k[row + i];
            }
            assertTrue("相位 " + phase + " 的系数和应为 1，实际 " + sum,
                    Math.abs(sum - 1.0) < 1e-4);
        }
    }

    /**
     * 窗函数必须在<strong>中心</strong>取最大值 1、在<strong>边缘</strong>趋于 0，且单调递减。
     *
     * <p>这条锁住一个曾经真实存在、且极难被发现的 bug：{@code blackmanHarris} 的
     * {@code a1} / {@code a3} 符号写反，导致窗完全倒置（中心≈0.00006、边缘才是 1）。
     * 因为核随后做了归一化，直流增益被强行拉回 1，<strong>所有 DC 测试照样全绿</strong>——
     * 只有频响变坏，而肉眼看代码看不出来。
     * 所以这条测试是必需的，不是锦上添花。
     */
    @Test
    public void windowShapeIsNotInverted() {
        double center = Resampler.blackmanHarris(0.0);
        assertTrue("窗中心应为 1，实际 " + center + "（符号写反会得到约 0.00006）",
                Math.abs(center - 1.0) < 1e-6);

        // 从中心往边缘必须单调递减
        double prev = center;
        for (int i = 1; i < 16; i++) {
            double w = Resampler.blackmanHarris((double) i);
            assertTrue("窗在 r=" + i + " 处未继续衰减：" + w + " >= " + prev, w < prev);
            prev = w;
        }
        assertTrue("窗边缘应接近 0，实际 " + Resampler.blackmanHarris(15.99),
                Resampler.blackmanHarris(15.99) < 0.01);
        // 超出定义域直接归零
        assertEquals(0.0, Resampler.blackmanHarris(16.0), 0.0);
        assertEquals(0.0, Resampler.blackmanHarris(-20.0), 0.0);
    }

    /**
     * 同采样率下，相位 0 的核必须<strong>精确等于 delta 函数</strong>。
     *
     * <p>这是位完美的直接前提：中心抽头为 1、其余为 0，采样值才能一个比特
     * 都不变地穿过重采样器。窗函数倒置的症状正是破坏这个性质——
     * 中心抽头变成 0.00006，归一化时 {@code 1/0.00006} 的浮点舍入
     * 会让直通结果偏移约 1 ulp（实测 32.0 → 31.999998）。
     */
    @Test
    public void phaseZeroKernelIsExactDelta() {
        float[] k = Resampler.buildKernel(1.0);
        int center = Resampler.TAPS_PER_SIDE - 1; // d = 0 对应的抽头
        float got = k[center];
        assertTrue("中心抽头应为 1，实际 " + got + "（核不精确会让位完美失效）",
                Math.abs(got - 1.0f) < 1e-6f);
        for (int i = 0; i < Resampler.TAPS; i++) {
            if (i == center) {
                continue;
            }
            assertTrue("第 " + i + " 个抽头应为 0，实际 " + k[i], Math.abs(k[i]) < 1e-6f);
        }
    }

    @Test
    public void dcSignalSurvivesUpsampling() {
        float[] input = new float[512];
        Arrays.fill(input, 1.0f);
        float[] out = convert(44_100, 48_000, input);
        assertTrue(out.length > 500);
        // 跳过两端（边缘没有足够历史，本来就不该参与比较）
        for (int i = 64; i < out.length - 64; i++) {
            assertTrue("第 " + (i - 64) + " 个输出偏离直流：" + out[i]
                            + "（归一化失效会表现为整体缩放）",
                    Math.abs(out[i] - 1.0f) < 1e-3f);
        }
    }

    @Test
    public void dcSignalSurvivesDownsampling() {
        float[] input = new float[1024];
        Arrays.fill(input, 0.5f);
        float[] out = convert(48_000, 44_100, input);
        // 上游这里直接切 out[64..len-64]；Java 越界会抛异常而不是 panic，
        // 但为了「中段可比较」这个前提仍然显式断言一次
        assertTrue("输出太短，中段无从比较：" + out.length, out.length > 128);
        for (int i = 64; i < out.length - 64; i++) {
            assertTrue("降采样后直流电平被改变：" + out[i],
                    Math.abs(out[i] - 0.5f) < 1e-3f);
        }
    }

    @Test
    public void silentInputStaysSilent() {
        float[] input = new float[256];
        float[] out = convert(44_100, 48_000, input);
        for (int i = 0; i < out.length; i++) {
            assertTrue("静音输入不该产生任何声音", Math.abs(out[i]) < 1e-9f);
        }
    }

    @Test
    public void outputLengthTracksRatio() {
        float[] input = new float[4410];
        float[] out = convert(44_100, 48_000, input);
        double expected = 4800.0;
        double got = (double) out.length;
        assertTrue("输出长度应接近 " + expected + "，实际 " + got,
                Math.abs(got - expected) < 40.0);
    }

    @Test
    public void passthroughRateIsReported() {
        Resampler rs = new Resampler(48_000, 48_000, 2);
        assertTrue(rs.isPassthrough());
        Resampler rs2 = new Resampler(44_100, 48_000, 2);
        assertFalse(rs2.isPassthrough());
    }

    /**
     * 历史为空时 {@code pull} 必须安全返回 0。
     *
     * <p>回归防线：曾经 {@code hist_len - 1} 在 {@code hist_len == 0} 时下溢 panic。
     * 触发场景是编排器——它新开一个 deck 后第一次就调 {@code pull}，
     * 而那时历史里一个采样都没有。<strong>调用顺序不该让底层 API 崩掉。</strong>
     */
    @Test
    public void pullOnEmptyHistoryIsSafe() {
        Resampler rs = new Resampler(44_100, 48_000, 1);
        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, 64);
        assertEquals("没有历史时应返回 0，而不是 panic", 0, rs.pull(dst));
        assertEquals(0, dst.frames());

        // 反复调用也不该出问题
        for (int i = 0; i < 8; i++) {
            assertEquals(0, rs.pull(dst));
        }
    }

    @Test
    public void pushAcceptsPartialWhenHistoryIsFull() {
        Resampler rs = new Resampler(44_100, 48_000, 1);
        AudioBuf big = new AudioBuf(ChannelLayout.MONO, Resampler.HISTORY_FRAMES + 1000);
        big.setFrames(Resampler.HISTORY_FRAMES + 1000);
        int took = rs.push(big);
        assertEquals("历史满了只该接收放得下的部分", Resampler.HISTORY_FRAMES, took);
    }

    /**
     * 这条是回归防线，对应一次真实踩坑：
     * 历史缓冲曾设为 4096，而 FLAC/ALAC 的典型包长正好是 4096，
     * 压线且一超就静默丢帧。现在要求「整包必有位置」。
     */
    @Test
    public void aWholeLosslessPacketFits() {
        Resampler rs = new Resampler(44_100, 48_000, 1);
        AudioBuf pkt = new AudioBuf(ChannelLayout.MONO, 4096);
        pkt.setFrames(4096);
        assertEquals("一整包无损数据必须能放下", 4096, rs.push(pkt));
    }

    /** 输入远大于历史缓冲容量时必须走分批路径，且总长度不能被截掉。 */
    @Test
    public void multiBatchInputKeepsFullLength() {
        float[] input = new float[20_000];
        float[] out = convert(44_100, 48_000, input);
        double expected = 20_000.0 * 48_000.0 / 44_100.0; // ≈ 21768.7
        assertTrue("分批喂入的输出长度应接近 " + expected + "，实际 " + out.length,
                Math.abs((double) out.length - expected) < 64.0);
    }

    /** {@code pushFrom} 必须能从任意偏移续喂，且不重复、不遗漏。 */
    @Test
    public void pushFromContinuesWithoutGapOrOverlap() {
        AudioBuf buf = new AudioBuf(ChannelLayout.MONO, 64);
        buf.setFrames(64);
        FloatBuffer w = buf.channelWriter(0);
        for (int i = 0; i < 64; i++) {
            w.put(i, (float) i);
        }

        Resampler rs = new Resampler(48_000, 48_000, 1);
        assertEquals(64, rs.pushFrom(buf, 0));
        assertEquals("越界偏移应安全返回 0", 0, rs.pushFrom(buf, 64));

        // 从中间续喂：应当只接受偏移之后的 32 帧
        Resampler rs2 = new Resampler(48_000, 48_000, 1);
        assertEquals("应只接受偏移之后的 32 帧", 32, rs2.pushFrom(buf, 32));

        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, 64);
        int n = rs2.pull(dst);
        // 历史只有 32 帧，而插值需要每个输出点前后各 TAPS_PER_SIDE 帧，
        // 所以尾部 16 帧无法参与 → 只能产出 16 帧。这是插值器的固有边界效应，
        // 不是 bug；真实播放路径上后续数据到达后会把这段补上。
        assertEquals("尾部 TAPS_PER_SIDE 帧无法参与插值", 16, n);
        // 关键验证：偏移生效，且没有错位——首个输出就是输入的第 32 个样本
        assertTrue("偏移后首样本应为 32，实际 " + dst.channel(0).get(0),
                Math.abs(dst.channel(0).get(0) - 32.0f) < 1e-6f);
    }

    /**
     * 同采样率必须<strong>逐样本精确直通</strong>。
     *
     * <p>这条是位完美的数学前提：相位 0 的核精确等于 delta 函数
     * （{@code sinc} 在所有非零整数点为零，窗函数乘零仍是零），
     * 所以插入一个两端采样率相同的重采样器，不该改变任何一个比特。
     * 谁哪天动了核构造，这条会立刻报警。
     *
     * <p>Java 与 Rust 的 {@code sin} 实现不同（{@code Math.sin} 是 double 版再舍到 float，
     * Rust 是 f32 版），但这条测试的输入是用本机 {@code Math.sin} 现造的，
     * 比较的是「同一个输入样本进 / 出」是否逐位相同，所以超越函数的 ulp 差异
     * 完全不影响结论 —— 这里没有任何放宽容差的必要。
     */
    @Test
    public void sameRateIsSampleExact() {
        float[] input = new float[600];
        for (int i = 0; i < input.length; i++) {
            input[i] = (float) Math.sin((float) i * 0.037f);
        }
        float[] out = convert(48_000, 48_000, input);
        assertTrue("直通不该丢掉大段数据", out.length >= input.length - 40);

        // 跳过开头：历史尚未建立时边缘样本本来就不参与比较
        int skip = 32;
        int n = Math.min(input.length - skip, Math.max(out.length - skip, 0));
        assertTrue("样本对太少（" + n + "），测试失去意义", n > 400);
        for (int i = 0; i < n; i++) {
            float a = input[skip + i];
            float b = out[skip + i];
            assertTrue("第 " + i + " 个样本被改变了：" + a + " → " + b + "（同采样率不该有任何处理）",
                    Math.abs(a - b) < 1e-6f);
        }
    }

    /**
     * 本地补充：上游有 {@code reset} 但没有对应测试。它锁住的性质是「定位之后必须 reset」——
     * 历史里装的是跳转之前的声音，不清掉就会像「跳过去又弹回来」。
     */
    @Test
    public void resetDropsHistorySoOldAudioDoesNotLeak() {
        Resampler rs = new Resampler(48_000, 48_000, 1);
        AudioBuf old = new AudioBuf(ChannelLayout.MONO, 64);
        old.setFrames(64);
        FloatBuffer w = old.channelWriter(0);
        for (int i = 0; i < 64; i++) {
            w.put(i, (float) i);
        }
        assertEquals(64, rs.push(old));

        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, 64);
        assertEquals("同采样率下 64 帧历史可产出 64 - TAPS_PER_SIDE 帧",
                64 - Resampler.TAPS_PER_SIDE, rs.pull(dst));

        rs.reset();
        assertEquals("reset 之后历史为空，pull 必须返回 0", 0, rs.pull(dst));

        // 重新喂一段全新内容：首个输出应该是它的第一个样本，而不是旧历史的残留
        AudioBuf fresh = mono(32, 100f);
        assertEquals(32, rs.push(fresh));
        int m = rs.pull(dst);
        assertEquals(16, m);
        assertEquals("reset 后首个输出必须是新输入的样本", 100f, dst.channel(0).get(0), 0f);
    }

    /**
     * 本地补充（上游没有对应测试，因为<strong>上游根本没有 drain</strong>）：
     * 流末尾收尾时同采样率的尾帧必须<strong>逐位精确</strong>。
     *
     * <p>锁的是一个真实缺陷：{@link Resampler#pull} 算每个输出点都要看到它前后各
     * {@link Resampler#TAPS_PER_SIDE} 帧输入，而流末尾永远凑不齐后面那 16 帧「未来输入」，
     * 于是最后 16 帧被<strong>静默丢掉</strong>——24000 帧的曲子只产出 23984 帧，
     * 播放侧的表现是进度条永远停在倒数第 16 帧、gapless 接缝整体早 0.33 ms。
     * 上游 {@code sonalis-dsp/src/resample.rs} 里没有 drain / flush，所以上游有同一个缺陷。
     *
     * <p>这条一次钉三件事：
     *
     * <ol>
     *   <li><strong>尾帧逐位精确，零容差</strong>：{@code step == 1.0} 且 {@code pos} 自 0 起
     *       整步前进，所以每个输出点都落在相位 0 上，而相位 0 的核精确等于 delta 函数
     *       （见 {@link #phaseZeroKernelIsExactDelta}）。补上的静音只让越界抽头取 0，
     *       一个真实采样都不碰：实测差值是 0.0f，不是「很小」——所以这里不给容差，
     *       也不该给。</li>
     *   <li><strong>重复调用返回 0</strong>：第一次收尾后 {@code pos ≥ histLen}，
     *       没有第二个流末尾。</li>
     *   <li>收尾之后再 {@link Resampler#pull} 同样是 0（两者看的是同一段历史）。</li>
     * </ol>
     */
    @Test
    public void drainFlushesTheTailSampleExactlyAtSameRate() {
        final int n = 100;
        float[] src = new float[n];
        for (int i = 0; i < n; i++) {
            src[i] = (float) Math.sin((float) i * 0.037f);
        }
        AudioBuf in = new AudioBuf(ChannelLayout.MONO, n);
        in.setFrames(n);
        FloatBuffer w = in.channelWriter(0);
        for (int i = 0; i < n; i++) {
            w.put(i, src[i]);
        }

        Resampler rs = new Resampler(48_000, 48_000, 1);
        assertEquals(n, rs.push(in));

        // DeckTest 里那条跨采样率的断言把一个 16 抄成了它自己的本地常量（那边在别的包，
        // 看不见包内可见的 TAPS_PER_SIDE），这里把「16 就是这个值」钉住，免得两边漂移。
        assertEquals("DeckTest.RESAMPLER_TAPS_PER_SIDE 照抄的必须就是这个值",
                16, Resampler.TAPS_PER_SIDE);

        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, 256);
        assertEquals("流没结束前 pull 只能给到 n - TAPS_PER_SIDE 帧（尾部 16 帧凑不齐未来输入）",
                n - Resampler.TAPS_PER_SIDE, rs.pull(dst));
        assertEquals("再 pull 依然返回 0：尾部那 16 帧靠 pull 永远拿不到", 0, rs.pull(dst));

        int tail = rs.drain(dst);
        assertEquals("drain 应当把缺的 TAPS_PER_SIDE 帧一帧不少地补出来",
                Resampler.TAPS_PER_SIDE, tail);
        // 视图要在下一次写之前取：drain 会改 out 的有效帧数
        FloatBuffer out = dst.channel(0);
        for (int i = 0; i < tail; i++) {
            float expected = src[n - Resampler.TAPS_PER_SIDE + i];
            float got = out.get(i);
            assertEquals("第 " + i + " 个尾帧不是原样本的逐位拷贝（零容差）："
                            + expected + " → " + got,
                    expected, got, 0f);
        }

        assertEquals("收尾只能发生一次：pos 已经越过真实输入了", 0, rs.drain(dst));
        assertEquals("收尾之后 pull 也必须是 0", 0, rs.pull(dst));
    }

    /**
     * 本地补充（上游没有对应测试）：跨采样率时 {@link Resampler#drain} 末尾按<strong>零填充</strong>
     * 插值。
     *
     * <p>拿不到的输入不能凭空造，所以末尾缺失的「未来输入」按静音参与乘加：在幅度上等价于
     * 「最后一个采样之后接着静音」，即一段约 {@link Resampler#TAPS_PER_SIDE} 帧
     * （48 kHz 下约 0.33 ms）的淡出，比把波形在末尾截成一个硬台阶（宽频爆音）更接近真实
     * 信号。libsamplerate / SoX / ffmpeg 的 {@code swr} 收尾都是这个做法。<strong>这是有意
     * 的设计</strong>，所以下面断言的是「补出来的点数」，而不是波形逐位。
     *
     * <p>两处边界的来源都写清楚：
     *
     * <ul>
     *   <li><strong>下界 {@code ≥ 理论值} 不是容差，是数学下界</strong>：输入范围
     *       {@code [0, n)} 内的输出点数就是 {@code ceil(n × outRate / inRate)} = 4800。
     *       少一个就说明末尾那几帧又没被交出来——修复前实测 4783 帧，正是这个缺陷。</li>
     *   <li><strong>上界多留一个核（{@code TAPS}）</strong>：输出点的存在性判据是半开区间
     *       {@code pos < histLen}，而 {@code pos} 是一步步 {@code += step} 累加出来的
     *       double，累加舍入会让最后一个点落在界线哪一侧（本机实测比理论值多 1 帧）；
     *       不同 libm 的 ulp 级差异最多再挪一帧。留一个核的宽度只是为了让这条断言与浮点
     *       实现无关——它离「丢帧」的量级（修复前 −17 帧）还差得远，所以没有变弱。</li>
     * </ul>
     */
    @Test
    public void drainZeroPadsTheTailWhenRatesDiffer() {
        final int n = 4410;
        Resampler rs = new Resampler(44_100, 48_000, 1);
        AudioBuf in = new AudioBuf(ChannelLayout.MONO, n);
        in.setFrames(n);
        FloatBuffer w = in.channelWriter(0);
        for (int i = 0; i < n; i++) {
            w.put(i, (float) Math.sin((float) i * 0.01f));
        }
        assertEquals(n, rs.push(in));

        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, 8192);
        int total = 0;
        while (true) {
            int c = rs.pull(dst);
            if (c == 0) {
                break;
            }
            total += c;
        }
        assertTrue("pull 阶段应当已经产出大部分输出点，实际 " + total, total > 4000);

        int tail = rs.drain(dst);
        assertTrue("跨采样率时末尾同样要补出输出点（本机实测 18 帧），实际 " + tail,
                tail > 0 && tail <= Resampler.TAPS_PER_SIDE + 4);

        double ideal = (double) n * 48_000.0 / 44_100.0;
        total += tail;
        assertTrue("总帧数不该少于理论值 " + ideal + "（少了就是末尾没补上），实际 " + total,
                (double) total >= ideal);
        assertTrue("总帧数不该超过理论值 + 一个核 " + (ideal + Resampler.TAPS_PER_SIDE)
                        + "，实际 " + total,
                (double) total <= ideal + (double) Resampler.TAPS_PER_SIDE);
        assertEquals("重复调用必须返回 0", 0, rs.drain(dst));
    }

    /** 本地补充：Java 独有的 {@link Resampler#pushAll} 便捷入口——放得下时必须整块收下。 */
    @Test
    public void pushAllFeedsWholeInputWhenItFits() {
        Resampler rs = new Resampler(44_100, 48_000, 1);
        AudioBuf small = new AudioBuf(ChannelLayout.MONO, 100);
        small.setFrames(100);
        assertEquals(100, rs.pushAll(small));
    }

    /**
     * 本地补充：{@code pushAll} 遇到满历史时也必须如实报出「只收下了一部分」，
     * 绝不假称全收下（这正是上游 {@code #[must_use]} 想表达的意思）。
     */
    @Test
    public void pushAllReportsPartialAcceptanceWhenHistoryIsFull() {
        Resampler rs = new Resampler(44_100, 48_000, 1);
        AudioBuf big = new AudioBuf(ChannelLayout.MONO, 12_000);
        big.setFrames(12_000);
        assertEquals(Resampler.HISTORY_FRAMES, rs.pushAll(big));
    }

    /**
     * 本地补充：{@code HISTORY_FRAMES} 这个常量本身也要被锁住。
     * 4096 会让 FLAC/ALAC 的 4096 帧整包压线，一超就静默丢帧。
     */
    @Test
    public void historyFramesLeavesRoomForAWholeLosslessPacket() {
        assertEquals("HISTORY_FRAMES 必须是 8192，不是 4096", 8192, Resampler.HISTORY_FRAMES);
        assertTrue(Resampler.HISTORY_FRAMES > 4096);
        assertTrue(Resampler.HISTORY_FRAMES >= 2 * 4096);
    }

    /** 本地补充：上游的 {@code assert!} 前置条件 → {@code IllegalArgumentException}。 */
    @Test
    public void constructorRejectsInvalidParameters() {
        try {
            new Resampler(48_000, 48_000, 0);
            fail("声道数为 0 应拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            new Resampler(0, 48_000, 1);
            fail("输入采样率为 0 应拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            new Resampler(48_000, 0, 1);
            fail("输出采样率为 0 应拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /**
     * 本地补充：上游是 {@code debug_assert_eq!}，只在 debug 构建生效；
     * Java 侧总是检查（更严），所以这里显式锁住「声道数不匹配就抛」。
     */
    @Test
    public void pushAndPullRejectChannelMismatch() {
        Resampler rs = new Resampler(48_000, 48_000, 1);
        AudioBuf stereo = new AudioBuf(ChannelLayout.STEREO, 8);
        stereo.setFrames(8);
        try {
            rs.push(stereo);
            fail("声道数不匹配应拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            rs.pull(stereo);
            fail("声道数不匹配应拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /** 本地补充：上游未测的 {@code needed_input_frames}。 */
    @Test
    public void neededInputFramesAddsTapsOnTopOfRatio() {
        Resampler same = new Resampler(48_000, 48_000, 1);
        assertEquals("同采样率下 step=1，折算就是帧数本身再加 TAPS 余量",
                1000 + Resampler.TAPS, same.neededInputFrames(1000));

        Resampler up = new Resampler(44_100, 48_000, 1);
        // 4800 帧输出对应 4800 × 44100/48000 = 4410 帧输入，再加 TAPS。
        // 4410 在二进制里不精确，ceil 可能落在 4410 或 4411，所以给 1 的余量。
        int n = up.neededInputFrames(4800);
        assertTrue("折算至少要覆盖 4410 帧输入，实际 " + n, n >= 4410 + Resampler.TAPS);
        assertTrue("折算不该比 ratio × 帧数 多出 TAPS 以上，实际 " + n,
                n <= 4410 + Resampler.TAPS + 1);
    }

    /** 测试用的可增长 f32 列表，对应上游测试里累积输出的 {@code Vec<f32>}。 */
    private static final class FloatList {

        private float[] a = new float[64];
        private int n;

        void addAll(FloatBuffer src, int count) {
            ensure(n + count);
            // 相对批量读：src 是每次现取的读视图（position=0、limit=有效帧数），
            // 对应上游的 extend_from_slice(&v[..n])。
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
