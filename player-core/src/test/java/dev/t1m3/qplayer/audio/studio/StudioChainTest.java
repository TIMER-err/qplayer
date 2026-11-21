package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P1 的另外两组判据：<b>全关必须逐位直通</b>、<b>声道路由只能动该动的那一路</b>。
 *
 * <p>第一条是这个播放器的老承诺（ADR-003 的位完美）：工作室一条链都没开时，
 * 采样必须一个比特都不变——不是「听不出来」，而是<strong>逐个浮点比较相等</strong>。
 * 第二条是「声道」这个功能的全部意义：给左声道加的补偿如果漏到右声道，那就是 bug。
 */
public class StudioChainTest {

    private static final int RATE = 48_000;

    /** 一段有内容的立体声：左右不同（否则声道测试没有意义）。 */
    private static AudioBuf stereo(int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            l.put(i, (float) (0.4 * Math.sin(2 * Math.PI * 220 * i / RATE)
                    + 0.2 * Math.sin(2 * Math.PI * 3300 * i / RATE)));
            r.put(i, (float) (0.35 * Math.sin(2 * Math.PI * 277 * i / RATE)
                    - 0.15 * Math.sin(2 * Math.PI * 5100 * i / RATE)));
        }
        return buf;
    }

    private static float[] snapshot(AudioBuf buf) {
        float[] out = new float[buf.frames() * buf.channels()];
        int k = 0;
        for (int ch = 0; ch < buf.channels(); ch++) {
            int base = buf.channelBase(ch);
            for (int i = 0; i < buf.frames(); i++) {
                out[k++] = buf.backingArray()[base + i];
            }
        }
        return out;
    }

    private static void assertSame(String what, float[] before, float[] after) {
        for (int i = 0; i < before.length; i++) {
            if (Float.floatToIntBits(before[i]) != Float.floatToIntBits(after[i])) {
                throw new AssertionError(what + "：第 " + i + " 个采样变了（"
                        + before[i] + " → " + after[i] + "）");
            }
        }
    }

    /** 全关 = 逐位直通；而且「关着」不能被算成「在处理」。 */
    @Test
    public void everythingOffIsBitIdentical() {
        int frames = 4096;
        // ① 完全没配（EMPTY）
        AudioBuf a = stereo(frames);
        float[] before = snapshot(a);
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.EMPTY);
        p.processDeckA(a);
        p.processMaster(a);
        p.processDeckB(a);
        assertSame("EMPTY 链", before, snapshot(a));
        assertFalse("EMPTY 链不该被算作在处理", p.active());

        // ② 配了但全是「透明」的：0 dB 前级、居中平衡、0 dB 的段、关掉的段
        StudioRack transparent = StudioRack.eq(Arrays.asList(
                        EqBand.peak(1000, 0.0, 1.0),
                        EqBand.of(BandType.HIGH_PASS, 500, 0, 0.7071).withEnabled(false)))
                .withPreamp(0.0).withBalance(0.0);
        AudioBuf b2 = stereo(frames);
        float[] before2 = snapshot(b2);
        StudioProcessor p2 = new StudioProcessor(RATE, 2);
        p2.setRacks(StudioRacks.of(transparent, transparent, transparent));
        p2.processDeckA(b2);
        p2.processMaster(b2);
        assertSame("全透明的链", before2, snapshot(b2));
        assertFalse("全透明的链不该被算作在处理", p2.active());

        // ③ 响度补偿开着但量是 0：同样是透明
        StudioRack zeroLoudness = StudioRack.EMPTY.withEnabled(true).withLoudness(true, 0.0);
        assertFalse("强度 0 的响度补偿不该算在处理", zeroLoudness.active());
    }

    /** 只改左声道时，右声道必须**一个比特都不变**。 */
    @Test
    public void leftOnlyBandLeavesTheRightChannelAlone() {
        int frames = 4096;
        AudioBuf buf = stereo(frames);
        float[] before = snapshot(buf);
        EqBand left = EqBand.of(BandType.PEAK, 300, 12.0, 1.2, 12, ChannelTarget.LEFT, true);
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.eq(Arrays.asList(left))));
        p.processMaster(buf);
        float[] after = snapshot(buf);
        boolean leftChanged = false;
        for (int i = 0; i < frames; i++) {
            if (before[i] != after[i]) {
                leftChanged = true;
            }
            assertEquals("右声道第 " + i + " 个采样必须逐位不变",
                    Float.floatToIntBits(before[frames + i]),
                    Float.floatToIntBits(after[frames + i]));
        }
        assertTrue("左声道应当被改了", leftChanged);
    }

    /**
     * 只改<strong>右</strong>声道时，左声道必须一个比特都不变。
     *
     * <p>为什么要跟上面那条 LEFT 分开写：{@code Plan.compile} 里「几路滤波器」原来只给
     * {@code STEREO} 开两路，而 {@code process()} 传进去的是**物理声道号**（右 = 1）——
     * 于是「把一段设在右声道」在第一个采样就 {@code filters[s][1]} 越界
     * （{@code ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1}）。
     * 判据只写了 LEFT，所以这条缺口活到了用户手上（2026-09-26 报的「生产者线程异常」）。
     * <strong>每一条分支都要有自己的判据</strong>——镜像的那一条不会因为对称就免费得到。
     */
    @Test
    public void rightOnlyBandLeavesTheLeftChannelAlone() {
        int frames = 4096;
        AudioBuf buf = stereo(frames);
        float[] before = snapshot(buf);
        EqBand right = EqBand.of(BandType.PEAK, 300, 12.0, 1.2, 12, ChannelTarget.RIGHT, true);
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.eq(Arrays.asList(right))));
        p.processMaster(buf);
        float[] after = snapshot(buf);
        boolean rightChanged = false;
        for (int i = 0; i < frames; i++) {
            assertEquals("左声道第 " + i + " 个采样必须逐位不变",
                    Float.floatToIntBits(before[i]), Float.floatToIntBits(after[i]));
            if (before[frames + i] != after[frames + i]) {
                rightChanged = true;
            }
        }
        assertTrue("右声道应当被改了", rightChanged);
    }

    /** 中置段：侧信号必须基本不动，中信号被改（这正是「修人声不动声场」的意思）。 */
    @Test
    public void midBandChangesMidButNotSide() {
        int frames = 8192;
        AudioBuf buf = stereo(frames);
        double sideBefore = 0;
        double[] l0 = new double[frames];
        double[] r0 = new double[frames];
        float[] backing = buf.backingArray();
        int bl = buf.channelBase(0);
        int br = buf.channelBase(1);
        for (int i = 0; i < frames; i++) {
            l0[i] = backing[bl + i];
            r0[i] = backing[br + i];
            sideBefore += Math.pow((l0[i] - r0[i]) / 2, 2);
        }
        EqBand mid = EqBand.of(BandType.LOW_SHELF, 400, 9.0, 0.7071, 12, ChannelTarget.MID, true);
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.eq(Arrays.asList(mid))));
        p.processMaster(buf);
        double sideAfter = 0;
        double midChange = 0;
        double midBefore = 0;
        for (int i = 0; i < frames; i++) {
            double l = backing[bl + i];
            double r = backing[br + i];
            sideAfter += Math.pow((l - r) / 2, 2);
            midChange += Math.pow(((l + r) / 2) - ((l0[i] + r0[i]) / 2), 2);
            midBefore += Math.pow((l0[i] + r0[i]) / 2, 2);
        }
        double sideDelta = Math.abs(sideAfter - sideBefore) / Math.max(sideBefore, 1e-30);
        double midDelta = midChange / Math.max(midBefore, 1e-30);
        System.out.printf("### 中置低架 +9 dB：侧能量变化 %.3e，中能量变化 %.3e%n",
                Double.valueOf(sideDelta), Double.valueOf(midDelta));
        assertTrue("侧信号应当基本不动（实测相对变化 " + sideDelta + "）", sideDelta < 1e-3);
        assertTrue("中信号应当被改了（实测相对变化 " + midDelta + "）", midDelta > 0.05);
    }

    /** 前级增益：±6 dB 必须精确（这是「调音台上写的数字」）。 */
    @Test
    public void preampIsAccurate() {
        int frames = 1024;
        for (double db : new double[]{-12, -6, 6, 12}) {
            AudioBuf buf = stereo(frames);
            float[] before = snapshot(buf);
            StudioProcessor p = new StudioProcessor(RATE, 2);
            p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true).withPreamp(db)));
            p.processMaster(buf);
            float[] after = snapshot(buf);
            // 斜坡：取块尾（已经走到目标值）
            double want = Math.pow(10, db / 20.0);
            double got = after[frames - 1] / before[frames - 1];
            assertEquals("前级 " + db + " dB", want, got, Math.abs(want) * 1e-3);
        }
    }

    /** 平衡：−1 全左、+1 全右、0 居中（且不改变左右的比例关系）。 */
    @Test
    public void balanceMovesTheImage() {
        int frames = 1024;
        double[] balances = {-1, -0.5, 0, 0.5, 1};
        for (double bal : balances) {
            AudioBuf buf = stereo(frames);
            float[] before = snapshot(buf);
            StudioProcessor p = new StudioProcessor(RATE, 2);
            p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true).withBalance(bal)));
            p.processMaster(buf);
            float[] after = snapshot(buf);
            double l = after[frames - 1] / before[frames - 1];
            double r = after[frames + frames - 1] / before[frames + frames - 1];
            System.out.printf("### 平衡 %+.1f：左 ×%.2f 右 ×%.2f%n", Double.valueOf(bal),
                    Double.valueOf(l), Double.valueOf(r));
            if (bal < 0) {
                assertEquals("往左时右声道应当变小", 1.0 + bal, r, 1e-3);
                assertEquals("左声道不变", 1.0, l, 1e-3);
            } else if (bal > 0) {
                assertEquals("往右时左声道应当变小", 1.0 - bal, l, 1e-3);
                assertEquals("右声道不变", 1.0, r, 1e-3);
            } else {
                assertEquals("居中：左", 1.0, l, 1e-3);
                assertEquals("居中：右", 1.0, r, 1e-3);
            }
        }
    }

    /** 极性反相：左右都变成负的（绝对值不变）。 */
    @Test
    public void polarityInvertFlipsBothChannels() {
        int frames = 512;
        AudioBuf buf = stereo(frames);
        float[] before = snapshot(buf);
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true).withPolarityInvert(true)));
        // **第一块是斜坡块**：增益从上一块的值（初始 1.0）线性走到新目标（−1），
        // 一块约 20 ms——没有这个斜坡，切换极性会是一下咔哒。所以判据查的是第二块：
        // 那时 from == to，逐样本就是精确的相反数。
        p.processMaster(buf);
        AudioBuf next = stereo(frames);
        float[] beforeNext = snapshot(next);
        p.processMaster(next);
        float[] after = snapshot(next);
        for (int i = 0; i < beforeNext.length; i++) {
            assertEquals("反相后（稳定块）应当是相反数", -beforeNext[i], after[i], 1e-6);
        }
    }

    /** 等响度补偿：音量越小，低频补得越多；音量满时一点不补。 */
    @Test
    public void loudnessOnlyLiftsAtLowVolume() {
        int frames = 16384;
        double atFull = lowBandDb(1.0f, frames);
        double atHalf = lowBandDb(0.5f, frames);
        double atQuarter = lowBandDb(0.25f, frames);
        System.out.printf("### 等响度补偿（低频 100 Hz 处）：音量 1.0 → %+.2f dB，0.5 → %+.2f dB，"
                        + "0.25 → %+.2f dB%n",
                Double.valueOf(atFull), Double.valueOf(atHalf), Double.valueOf(atQuarter));
        assertEquals("音量满时不补", 0.0, atFull, 0.05);
        assertTrue("半音量应当补一点：" + atHalf, atHalf > 1.0);
        assertTrue("四分之一音量应当补得更多：" + atQuarter, atQuarter > atHalf + 1.0);
        assertTrue("补偿要有上限（不该补到离谱）", atQuarter < 12.0);
    }

    /** 量「低频处被补偿了多少 dB」：喂 100 Hz 正弦，量与不补时的 RMS 比。 */
    private static double lowBandDb(float volume, int frames) {
        AudioBuf withLoudness = mono(100.0, frames);
        AudioBuf without = mono(100.0, frames);
        StudioRack rack = StudioRack.EMPTY.withEnabled(true).withLoudness(true, 1.0);
        StudioProcessor a = new StudioProcessor(RATE, 2);
        a.setRacks(StudioRacks.master(rack));
        a.setVolume(volume);
        a.processMaster(withLoudness);
        StudioProcessor b = new StudioProcessor(RATE, 2);
        b.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)));
        b.setVolume(volume);
        b.processMaster(without);
        double rmsA = rms(withLoudness, frames);
        double rmsB = rms(without, frames);
        return 20 * Math.log10(rmsA / Math.max(rmsB, 1e-30));
    }

    private static AudioBuf mono(double freq, int frames) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.5 * Math.sin(2 * Math.PI * freq * i / RATE));
            l.put(i, v);
            r.put(i, v);
        }
        return buf;
    }

    /** 只量后半段（前面那段是滤波器的暂态）。 */
    private static double rms(AudioBuf buf, int frames) {
        int base = buf.channelBase(0);
        float[] backing = buf.backingArray();
        double sum = 0;
        int n = 0;
        for (int i = frames / 2; i < frames; i++) {
            sum += (double) backing[base + i] * backing[base + i];
            n++;
        }
        return Math.sqrt(sum / Math.max(n, 1));
    }

    // ==================================================================
    // P2：音高（改调）接进机架之后
    // ==================================================================

    /**
     * <b>只开音高（其它全透明）时，机架必须报「在处理」，而且真的移调。</b>
     *
     * <p>两条都要：{@code active()} 报 false 的话位完美标记会亮着撒谎；而不真的移调就是
     * 「界面能调、声音不变」。判据用频率——喂 440 Hz，量输出的主频是不是 440·2^(n/12)。
     */
    @Test
    public void pitchAloneIsActiveAndActuallyTransposes() {
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)
                .withPitch(PitchParams.semitones(5))));
        assertTrue("只开音高也算在处理（位完美必须如实熄灭）", p.active());
        assertTrue("延迟要如实上报", p.latencyFrames() > 0);

        double measured = transposedFreq(p, 440.0, 5);
        double expect = 440.0 * Math.pow(2.0, 5.0 / 12.0);
        double cents = 1200.0 * Math.log(measured / expect) / Math.log(2.0);
        System.out.printf("### 机架移调 +5 半音：440 Hz → 实测 %.2f Hz（解析 %.2f Hz，差 %+.1f 音分）%n",
                Double.valueOf(measured), Double.valueOf(expect), Double.valueOf(cents));
        assertTrue("机架里必须真的移了调（实测 " + measured + " Hz）：" + cents + " 音分",
                Math.abs(cents) < 12.0);
    }

    /**
     * 音高参数「开着但正好 0 半音 0 音分」= 不改采样：机架必须报空闲（逐位直通）。
     *
     * <p>这正是「参数在工作」与「参数开着」的区别——判据错一边就会要么撒谎（报在处理
     * 而实际直通）、要么白烧 CPU。
     */
    @Test
    public void pitchAtZeroIsIdleAndBitIdentical() {
        int frames = 2048;
        AudioBuf buf = stereo(frames);
        float[] before = snapshot(buf);
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)
                .withPitch(PitchParams.of(0, 0.0, 1.0))));
        assertFalse("0 半音 0 音分不改采样，不算在工作", p.active());
        assertEquals("空闲时延迟如实报 0", 0, p.latencyFrames());
        p.processMaster(buf);
        assertSame("0 音高必须逐位直通", before, snapshot(buf));

        // 干湿 0 也一样（那是「原样」而不是「移调」）
        StudioProcessor dry = new StudioProcessor(RATE, 2);
        dry.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)
                .withPitch(PitchParams.of(7, 0.0, 0.0))));
        assertFalse("干湿 0 = 原样，不该算在工作", dry.active());
    }

    /**
     * <b>关掉音高之后不许留断口，也不许把同一段放两遍。</b>
     *
     * <p>那一个 CHUNK（21 ms）的前视在关掉之后要<strong>交叉淡化</strong>到干声上：
     * 直接返回会让输出内容向前跳 21 ms（台阶），原样倒出来又会让同一段时间响两遍。
     * 判据用三条，全是可执行的：
     *
     * <ol>
     *   <li>收尾那一块不是空的（那 21 ms 得放出来）；</li>
     *   <li>收尾块的<strong>最后一个采样等于本块自己的干声</strong>（淡到头了，之后就是干声）；</li>
     *   <li>收尾走完之后 {@code active()} 回到 false，而且之后的块<strong>逐位直通</strong>
     *       （位完美要能回来）。</li>
     * </ol>
     *
     * <p>块长取 512（小于一个 CHUNK）：一次收尾要跨两块才走完，
     * 「跨块的收尾状态」这条路径因此真的被走到。
     */
    @Test
    public void turningPitchOffFlushesTheLookAheadInsteadOfCuttingIt() {
        int frames = 512;
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)
                .withPitch(PitchParams.semitones(3))));
        // 先跑几块把声码器喂起来（开头那几块还在攒前视，hold 里是零）
        for (int i = 0; i < 6; i++) {
            p.processMaster(monoOf(440.0, frames, i));
        }
        assertTrue("开着的时候必须报在处理", p.active());

        p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)));

        // 收尾第一块：内容必须还在
        int block = 6;
        AudioBuf flush1 = monoOf(440.0, frames, block++);
        p.processMaster(flush1);
        assertTrue("收尾那一块必须还有内容（那 21 ms 是被淡出去的，不是被切掉的）",
                rms(flush1, frames) > 0.05);
        assertTrue("收尾期间要如实报「还在写采样」（位完美这时候还不许亮）", p.active());

        // 收尾第二块：淡到头 —— 最后一个采样必须等于本块自己的干声
        AudioBuf flush2 = monoOf(440.0, frames, block++);
        float dryLast = flush2.backingArray()[flush2.channelBase(0) + frames - 1];
        p.processMaster(flush2);
        float afterLast = flush2.backingArray()[flush2.channelBase(0) + frames - 1];
        assertEquals("收尾块的末尾必须已经淡到干声上（不然接缝上还有一步台阶）",
                dryLast, afterLast, 1e-4);
        assertFalse("收尾走完必须真的旁路（位完美要回得来）", p.active());
        assertEquals("旁路之后延迟如实报 0", 0, p.latencyFrames());

        // 之后的块逐位直通
        AudioBuf passthrough = monoOf(440.0, frames, block);
        float[] before = snapshot(passthrough);
        p.processMaster(passthrough);
        assertSame("收尾之后的块必须逐位直通", before, snapshot(passthrough));
    }

    /** 第 {@code block} 块的正弦（块与块之间相位连续——不连续的话「淡到干声」那条判据会假红）。 */
    private static AudioBuf monoOf(double freq, int frames, int block) {
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, frames);
        buf.setFrames(frames);
        java.nio.FloatBuffer l = buf.channelWriter(0);
        java.nio.FloatBuffer r = buf.channelWriter(1);
        long start = (long) block * frames;
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.5 * Math.sin(2 * Math.PI * freq * (start + i) / RATE));
            l.put(i, v);
            r.put(i, v);
        }
        return buf;
    }

    /** 跑几块之后量输出的频率（用自相关 + 抛物线插值：过零率在 4k 窗上只有 ±10 音分精度）。 */
    private static double transposedFreq(StudioProcessor p, double freq, int semitones) {
        double want = freq * Math.pow(2.0, semitones / 12.0);
        int frames = 16384;
        AudioBuf out = null;
        for (int block = 0; block < 8; block++) {
            out = mono(freq, frames);
            p.processMaster(out);
        }
        int base = out.channelBase(0);
        float[] v = new float[frames];
        System.arraycopy(out.backingArray(), base, v, 0, frames);
        return freqByAutocorrelation(v, frames / 2, frames, want);
    }

    /**
     * 自相关估频（只看稳态段的第二块）。
     *
     * <p>与 {@code PitchModuleTest} 里那一份同一算法：**必须做抛物线插值**，
     * 不然采样点量化在 580 Hz 上就有 ±1.5 音分级误差，判据会变成掷骰子。
     */
    private static double freqByAutocorrelation(float[] v, int from, int to, double expected) {
        int n = to - from;
        // 只在期望周期 ±10% 里找（范围放宽会锁到倍周期上，见 AutotuneTest 里的同一条说明）
        int minLag = Math.max(2, (int) (RATE / (expected * 1.1)));
        int maxLag = Math.min(n / 2, (int) (RATE / (expected / 1.1)));
        double best = -1;
        int bestLag = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double norm = corrAt(v, from, to, lag);
            if (norm > best) {
                best = norm;
                bestLag = lag;
            }
        }
        double y0 = corrAt(v, from, to, bestLag - 1);
        double y1 = best;
        double y2 = corrAt(v, from, to, bestLag + 1);
        double denom = y0 - 2 * y1 + y2;
        double delta = Math.abs(denom) < 1e-12 ? 0 : 0.5 * (y0 - y2) / denom;
        // 抛物线插值超出 ±1 个采样就没有意义了（denom 极小时会飞到天上去 → NaN/Inf）
        if (delta > 1.0) {
            delta = 1.0;
        } else if (delta < -1.0) {
            delta = -1.0;
        }
        return RATE / (bestLag + delta);
    }

    private static double corrAt(float[] v, int from, int to, int lag) {
        if (lag <= 0) {
            return 0;
        }
        int n = to - from;
        double sum = 0;
        double e1 = 0;
        double e2 = 0;
        for (int i = 0; i < n - lag; i++) {
            double a = v[from + i];
            double b = v[from + i + lag];
            sum += a * b;
            e1 += a * a;
            e2 += b * b;
        }
        return sum / Math.sqrt(Math.max(e1 * e2, 1e-30));
    }

    // ==================================================================
    // P5–P7：接进机架之后的旁路与延迟
    // ==================================================================

    /**
     * <b>三组新参数全关（或全中性）时，机架必须逐位直通、并如实报 0 延迟。</b>
     *
     * <p>这一条是位完美承诺在「链变长之后」的复验：每加一个模块都要重新过一遍，
     * 否则某一个默认值是「中性但不等于直通」（例如压缩阈值 0 dB 会让一切都被压一点），
     * 而那种默认值会悄悄改掉所有人的声音。
     */
    @Test
    public void theNewModulesAreBitExactWhenOff() {
        int frames = 4096;
        AudioBuf buf = stereo(frames);
        float[] before = snapshot(buf);
        StudioProcessor p = new StudioProcessor(RATE, 2);
        // 「开着但全中性」：动态三档全关、立体声宽度 100%、染色关闭
        StudioRack rack = StudioRack.EMPTY.withEnabled(true)
                .withDynamics(DynamicsParams.off())
                .withStereo(StereoParams.neutral())
                .withSaturation(SaturationParams.off());
        p.setRacks(StudioRacks.master(rack));
        assertFalse("全中性不该被算作在处理", p.active());
        assertEquals("延迟必须报 0", 0, p.latencyFrames());
        p.processMaster(buf);
        assertSame("全中性必须逐位直通", before, snapshot(buf));
    }

    /**
     * <b>延迟是几笔加起来的，而且每笔都如实。</b>
     *
     * <p>引擎那本账只有一个数，所以「漏报一笔」的表现是两首曲子错开一点点——
     * 听起来只是「有点糊」，是最难查的一类。这条把三笔分别钉住：
     * 音高 1024、限制器前视 96（2 ms @48 kHz）、立体声侧向延迟 960（20 ms 里的 2 ms 是上限，
     * 这里用 1 ms = 48 帧）。
     */
    @Test
    public void theRackReportsEveryLatencyItIntroduces() {
        StudioRack pitchOnly = StudioRack.EMPTY.withEnabled(true).withPitch(PitchParams.semitones(1));
        assertEquals("音高那一笔", StreamingPitchShift.LATENCY_FRAMES,
                pitchOnly.latencyFrames(RATE));

        StudioRack limiterOnly = StudioRack.EMPTY.withEnabled(true)
                .withDynamics(DynamicsParams.builder()
                        .limiter(true, -1.0, 2.0, 80.0).build());
        assertEquals("限制器前视那一笔", 96, limiterOnly.latencyFrames(RATE));

        StudioRack stereoOnly = StudioRack.EMPTY.withEnabled(true)
                .withStereo(new StereoParams(true, 1.0, 0.0, 1.0, false, 0.0));
        assertEquals("立体声侧向延迟那一笔", 48, stereoOnly.latencyFrames(RATE));

        StudioRack all = StudioRack.EMPTY.withEnabled(true)
                .withPitch(PitchParams.semitones(1))
                .withDynamics(DynamicsParams.builder().limiter(true, -1.0, 2.0, 80.0).build())
                .withStereo(new StereoParams(true, 1.0, 0.0, 1.0, false, 0.0));
        assertEquals("三笔必须相加", StreamingPitchShift.LATENCY_FRAMES + 96 + 48,
                all.latencyFrames(RATE));
    }

    /** 动态开着时机架报「在处理」，而且三档各自的开关都真的落地。 */
    @Test
    public void dynamicsGatesAndCompressorsLandOnTheRack() {
        StudioProcessor p = new StudioProcessor(RATE, 2);
        p.setRacks(StudioRacks.master(StudioRack.EMPTY.withEnabled(true)
                .withDynamics(DynamicsParams.builder()
                        .compressor(true, -24.0, 4.0, 0.0, 1.0, 200.0, 0.0)
                        .build())));
        assertTrue("压缩开着就算在处理", p.active());
        AudioBuf loud = mono(997.0, 4096);
        for (int i = 0; i < 4096; i++) {
            loud.channelWriter(0).put(i, (float) (0.9 * Math.sin(2 * Math.PI * 997.0 * i / RATE)));
            loud.channelWriter(1).put(i, (float) (0.9 * Math.sin(2 * Math.PI * 997.0 * i / RATE)));
        }
        p.processMaster(loud);
        assertTrue("满幅信号过 4:1 压缩之后应当有增益衰减读数", p.gainReductionDb() > 5.0);
    }
}
