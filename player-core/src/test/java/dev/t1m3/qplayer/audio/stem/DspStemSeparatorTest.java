package dev.t1m3.qplayer.audio.stem;

import dev.t1m3.qplayer.audio.model.StemSeparator;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DSP 模式（{@link DspStemSeparator}）的可执行判据。
 *
 * <p>素材是<strong>合成</strong>的，不是真实音乐——因为要断言的每一件事都需要"我知道它本来在哪"：
 *
 * <ul>
 *   <li><b>正中人声</b>：1000 Hz 正弦，左右<strong>相同</strong>（= 纯 center），幅度 0.25。
 *       1000 Hz 落在 {@code LO_INNER = 280} 与 {@code HI_INNER = 4000} Hz 之间，
 *       也就是完全抑制带的正中，离两端斜坡都够远。</li>
 *   <li><b>正中低频</b>：60 Hz，左右相同，0.20 —— 在 {@code LO_OUTER = 100} Hz 以下，
 *       中心能量必须保留（"伴奏还有厚度"的可执行形式）。</li>
 *   <li><b>正中高频</b>：9000 Hz，左右相同，0.10 —— 在 {@code HI_OUTER = 5200} Hz 以上，
 *       中心能量必须保留（镲片/齿音的那一头）。</li>
 *   <li><b>两侧乐器</b>：800 Hz，左 +0.15 / 右 −0.15（= 纯 side），必须<strong>一点不动</strong>
 *       地留在伴奏轨里 —— 这是"只衰减 center"的可执行形式。</li>
 * </ul>
 *
 * <p>峰值 = 0.25 + 0.20 + 0.10 + 0.15 = 0.70 &lt; 1，全程不会碰到那一步 ±1 钳位，
 * 所以"两轨相加 = 原信号"测到的是纯浮点误差，不是被钳位吃掉的部分。
 *
 * <h2>怎么量</h2>
 *
 * <p>量"某个频率上的幅度"用的是<strong>加 Hann 窗的单点投影</strong>（{@code amplitudeAt}），
 * 不是裸的 FFT 峰值：Hann 窗的旁瓣衰减极快，别的分量在本音上的泄漏远在 −100 dB 之下
 * （实测：输入 0.25 的 1000 Hz 量出来是 0.2499999936），所以量出来的数就是那一路分量自己的幅度。
 * 投影区间<strong>掐掉首尾各 4096 帧</strong>：素材首尾那一段的窗覆盖本来就不足
 * （实现里会按覆盖率退回原信号，见差异一的说明），与频带判据无关。
 * 实测这一步之后量出来的偏差在 1e-4 dB 以下（输入 0.25 量到的就是 0.2499999936）。
 *
 * <h2>容差来源（逐条写清，都是实测过的量级）</h2>
 *
 * <ul>
 *   <li><b>"人声在中频被去掉"用 40 dB 门槛，不是实测值。</b> 实测残留在 −152 dB（44.1 kHz）
 *       / −153 dB（48 kHz）：1000 Hz 落在完全抑制带正中，它的加窗主瓣（约 ±2 个 bin ≈ ±43 Hz）
 *       整个都在带内，于是连泄漏都被一起乘了 0，剩下的只有别的分量的旁瓣与浮点误差。
 *       门槛取 −40 dB 是因为它已经足以区分三种状态：「去掉了」（实测 −150 dB 量级）、
 *       「根本没去」（0 dB）、「共轭半没重建」（整段 −6 dB）；同时给真实音乐
 *       （频谱比合成正弦复杂得多）留出比合成素材大得多的泄漏余量。</li>
 *   <li><b>低频/高频"被保留"用 0.1 dB 容差。</b> 实测 60 Hz 是 −4.7e-5 dB、9 kHz 是 +7e-10 dB
 *       （基本逐位不动）。不按实测值卡死，是因为频带边界按 {@code f = b·sr/N} 判、
 *       60 Hz 那一路的主瓣上沿会<strong>擦到</strong> 100 Hz 的斜坡起点，
 *       这条边界效应的具体大小随采样率与频率对齐而变，所以要留 0.1 dB（2000 倍余量）。</li>
 *   <li><b>"两轨相加 = 原信号"用 1e-5（逐样本绝对值）。</b> 实测 <b>2.98e-8</b>
 *       （44.1 kHz 与 48 kHz 都是这个量级，L/R 一样）：重叠相加在 double 里是精确重建，
 *       误差只剩"两条轨各自转 float"那一步（相对 6e-8）。1e-5 留了 300 倍余量。</li>
 * </ul>
 *
 * <p>采样率跑 44.1 kHz 与 48 kHz 两遍：判据<strong>同一套</strong>（同一条
 * {@code assertKaraokeGrade} 辅助断言），因为实现里频带边界是按 {@code f = b·sr/N}
 * 现算的，没有任何一处写死 44.1 kHz。
 */
public class DspStemSeparatorTest {

    /** 正中"人声"（纯 center）。 */
    private static final double VOCAL_HZ = 1000.0;
    private static final double VOCAL_AMP = 0.25;

    /** 正中低频：必须在伴奏里保留。 */
    private static final double BASS_HZ = 60.0;
    private static final double BASS_AMP = 0.20;

    /** 正中高频：必须在伴奏里保留。 */
    private static final double TREBLE_HZ = 9000.0;
    private static final double TREBLE_AMP = 0.10;

    /** 两侧乐器（纯 side）：必须原样通过。 */
    private static final double SIDE_HZ = 800.0;
    private static final double SIDE_AMP = 0.15;

    /** 量幅度时首尾各掐掉的帧数（那几帧没有窗覆盖，见类注释）。 */
    private static final int GUARD_FRAMES = 4096;

    // ==================================================================
    // 合成素材与量测辅助
    // ==================================================================

    /** 合成素材，返回 {@code {左, 右}}。 */
    private static float[][] material(int frames, int sr) {
        float[] l = new float[frames];
        float[] r = new float[frames];
        for (int i = 0; i < frames; i++) {
            double t = (double) i / (double) sr;
            double center = VOCAL_AMP * Math.sin(2 * Math.PI * VOCAL_HZ * t)
                    + BASS_AMP * Math.sin(2 * Math.PI * BASS_HZ * t)
                    + TREBLE_AMP * Math.sin(2 * Math.PI * TREBLE_HZ * t);
            double side = SIDE_AMP * Math.sin(2 * Math.PI * SIDE_HZ * t);
            l[i] = (float) (center + side);
            r[i] = (float) (center - side);
        }
        return new float[][]{l, r};
    }

    /**
     * {@code x[from..from+len)} 上频率 {@code hz} 的幅度：加 Hann 窗的单点投影
     * {@code 2·|Σ x·w·e^{-jωi}| / Σw}。单频分量下这个数就是它的幅度。
     */
    private static double amplitudeAt(float[] x, int from, int len, double hz, int sr) {
        double re = 0;
        double im = 0;
        double wsum = 0;
        for (int i = 0; i < len; i++) {
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (len - 1));
            double ph = 2 * Math.PI * hz * (from + i) / sr;
            double s = x[from + i] * w;
            re += s * Math.cos(ph);
            im -= s * Math.sin(ph);
            wsum += w;
        }
        return 2.0 * Math.sqrt(re * re + im * im) / wsum;
    }

    /** 相对电平（dB）。 */
    private static double db(double amp, double ref) {
        return 20.0 * Math.log10(Math.max(amp, 1e-12) / Math.max(ref, 1e-12));
    }

    /** 两轨相加与原信号的最大逐样本偏差。 */
    private static double maxSumError(float[] a, float[] b, float[] original) {
        double worst = 0;
        for (int i = 0; i < original.length; i++) {
            double d = Math.abs((double) a[i] + (double) b[i] - (double) original[i]);
            if (d > worst) {
                worst = d;
            }
        }
        return worst;
    }

    private static void assertRetained(String what, double got, double want, double tolDb) {
        double d = Math.abs(db(got, want));
        assertTrue(what + "：应保留（偏差 <= " + tolDb + " dB），实测 " + got
                + " vs " + want + " = " + String.format("%.3f", Double.valueOf(d)) + " dB", d <= tolDb);
    }

    private static void assertSuppressed(String what, double got, double ref, double minDb) {
        double d = db(got, ref);
        assertTrue(what + "：应至少衰减 " + minDb + " dB，实测 " + d + " dB（"
                + got + " vs " + ref + "）", d <= -minDb);
    }

    // ==================================================================
    // ① ② ③ ⑤：同一套判据在两个采样率上各跑一遍
    // ==================================================================

    /**
     * ① 中频人声：伴奏轨里基本消失、人声轨里留着；② 低频/高频中心能量在伴奏轨里保留；
     * ③ 两轨相加 = 原信号；再顺带钉住"只有 center 被动过"（侧向分量原样通过）与
     * "人声轨左右相同"（固有性质）。
     */
    private void assertKaraokeGrade(int sr) {
        int frames = 40000 + 137;           // 故意不是 hop 的整数倍（差异一那条尾巴）
        float[][] in = material(frames, sr);
        float[] left = in[0];
        float[] right = in[1];

        StemSeparator sep = new DspStemSeparator();     // 按接缝类型持有，编译期就证明实现了接缝
        StemSeparator.StemSet set = sep.separate(left, right, sr);

        assertEquals("四条轨长度必须等于输入", frames, set.frames());
        assertEquals(frames, set.vocalsLeft.length);
        assertEquals(frames, set.vocalsRight.length);
        assertEquals(frames, set.instrumentalLeft.length);
        assertEquals(frames, set.instrumentalRight.length);

        int from = GUARD_FRAMES;
        int len = frames - 2 * GUARD_FRAMES;

        double inVocal = amplitudeAt(left, from, len, VOCAL_HZ, sr);
        double insVocal = amplitudeAt(set.instrumentalLeft, from, len, VOCAL_HZ, sr);
        double vocVocal = amplitudeAt(set.vocalsLeft, from, len, VOCAL_HZ, sr);

        double inBass = amplitudeAt(left, from, len, BASS_HZ, sr);
        double insBass = amplitudeAt(set.instrumentalLeft, from, len, BASS_HZ, sr);
        double vocBass = amplitudeAt(set.vocalsLeft, from, len, BASS_HZ, sr);

        double inTreble = amplitudeAt(left, from, len, TREBLE_HZ, sr);
        double insTreble = amplitudeAt(set.instrumentalLeft, from, len, TREBLE_HZ, sr);
        double vocTreble = amplitudeAt(set.vocalsLeft, from, len, TREBLE_HZ, sr);

        double insSide = amplitudeAt(set.instrumentalLeft, from, len, SIDE_HZ, sr);
        double vocSide = amplitudeAt(set.vocalsLeft, from, len, SIDE_HZ, sr);

        System.out.println("[DSP 实测 sr=" + sr + "] 1000Hz(正中): 输入 " + inVocal
                + " → 伴奏 " + insVocal + " (" + String.format("%.1f", Double.valueOf(db(insVocal, inVocal)))
                + " dB), 人声 " + vocVocal + " (" + String.format("%.1f", Double.valueOf(db(vocVocal, inVocal))) + " dB)");
        System.out.println("[DSP 实测 sr=" + sr + "] 60Hz(正中): 输入 " + inBass
                + " → 伴奏 " + insBass + " (" + String.format("%.3f", Double.valueOf(db(insBass, inBass)))
                + " dB), 人声 " + vocBass + " (" + String.format("%.1f", Double.valueOf(db(vocBass, inBass))) + " dB)");
        System.out.println("[DSP 实测 sr=" + sr + "] 9kHz(正中): 输入 " + inTreble
                + " → 伴奏 " + insTreble + " (" + String.format("%.3f", Double.valueOf(db(insTreble, inTreble)))
                + " dB), 人声 " + vocTreble + " (" + String.format("%.1f", Double.valueOf(db(vocTreble, inTreble))) + " dB)");
        System.out.println("[DSP 实测 sr=" + sr + "] 800Hz(侧向): 伴奏 " + insSide
                + " (" + String.format("%.3f", Double.valueOf(db(insSide, SIDE_AMP)))
                + " dB), 人声 " + vocSide + " (" + String.format("%.1f", Double.valueOf(db(vocSide, SIDE_AMP))) + " dB)");

        // 量测本身的自证：输入里 1000 Hz 就该是 0.25
        assertRetained("合成素材的 1000 Hz 幅度", inVocal, VOCAL_AMP, 0.1);

        // ① 正中中频：伴奏轨里基本消失（这是"人声消除"本体），人声轨里留着
        assertSuppressed("伴奏轨的 1000 Hz 人声", insVocal, inVocal, 40.0);
        assertRetained("人声轨的 1000 Hz 人声", vocVocal, inVocal, 0.1);
        assertSuppressed("人声轨不该留下 1000 Hz 之外的中频（这里量的是侧向 800 Hz）",
                vocSide, SIDE_AMP, 40.0);

        // ② 低频与高频的中心能量被保留 —— "伴奏还有厚度"
        assertRetained("伴奏轨的 60 Hz 中心能量（kick/bass）", insBass, inBass, 0.1);
        assertRetained("伴奏轨的 9 kHz 中心能量（镲片）", insTreble, inTreble, 0.1);
        // 反过来说：这两段既然留在伴奏里，人声轨里就不该有它们
        assertSuppressed("人声轨不该有 60 Hz 中心能量", vocBass, inBass, 40.0);
        assertSuppressed("人声轨不该有 9 kHz 中心能量", vocTreble, inTreble, 40.0);

        // 只有 center 被动过：纯侧向的 800 Hz 原样留在伴奏轨里
        assertRetained("伴奏轨的 800 Hz 侧向乐器（只该动 center）", insSide, SIDE_AMP, 0.1);

        // ③ 两轨相加 = 原信号（逐样本）
        double errL = maxSumError(set.instrumentalLeft, set.vocalsLeft, left);
        double errR = maxSumError(set.instrumentalRight, set.vocalsRight, right);
        System.out.println("[DSP 实测 sr=" + sr + "] 人声+伴奏-原信号 的最大偏差: L " + errL + ", R " + errR);
        assertTrue("左声道两轨相加应还原原信号，实测最大偏差 " + errL, errL < 1e-5);
        assertTrue("右声道两轨相加应还原原信号，实测最大偏差 " + errR, errR < 1e-5);

        // 固有性质：人声只取 center，所以人声轨左右完全相同
        for (int i = 0; i < frames; i++) {
            assertEquals("人声轨左右应完全相同（人声只取 center），第 " + i + " 点",
                    set.vocalsLeft[i], set.vocalsRight[i], 0.0f);
        }
    }

    /** ① ② ③ ⑤ @ 44.1 kHz。 */
    @Test
    public void karaokeGradeAt44100() {
        assertKaraokeGrade(44100);
    }

    /** ⑤ 同一套判据 @ 48 kHz（频带边界按 sr 现算，所以行为必须一致）。 */
    @Test
    public void karaokeGradeAt48000() {
        assertKaraokeGrade(48000);
    }

    // ==================================================================
    // ③ 单独一条：k 变了也照样拼得回原信号
    // ==================================================================

    /** 人声保留量 k=0.5：中频人声在两轨里各留一半，相加仍是原信号。 */
    @Test
    public void halfRetentionSplitsTheVocalEnergy() {
        int sr = 44100;
        int frames = 30000 + 11;
        float[][] in = material(frames, sr);
        StemSeparator sep = new DspStemSeparator(0.5);
        StemSeparator.StemSet set = sep.separate(in[0], in[1], sr);

        int from = GUARD_FRAMES;
        int len = frames - 2 * GUARD_FRAMES;
        double inVocal = amplitudeAt(in[0], from, len, VOCAL_HZ, sr);
        double insVocal = amplitudeAt(set.instrumentalLeft, from, len, VOCAL_HZ, sr);
        double vocVocal = amplitudeAt(set.vocalsLeft, from, len, VOCAL_HZ, sr);
        System.out.println("[DSP 实测 k=0.5] 1000Hz: 输入 " + inVocal + " → 伴奏 " + insVocal
                + ", 人声 " + vocVocal + "（各半）");

        // g = 0.5：两边各拿一半的 center（实测 0.12499999 / 0.12500000）
        assertRetained("k=0.5 时伴奏轨该拿到一半人声", insVocal, inVocal * 0.5, 0.1);
        assertRetained("k=0.5 时人声轨该拿到一半人声", vocVocal, inVocal * 0.5, 0.1);

        double errL = maxSumError(set.instrumentalLeft, set.vocalsLeft, in[0]);
        double errR = maxSumError(set.instrumentalRight, set.vocalsRight, in[1]);
        assertTrue("k=0.5 时左声道相加应还原原信号，实测 " + errL, errL < 1e-5);
        assertTrue("k=0.5 时右声道相加应还原原信号，实测 " + errR, errR < 1e-5);
    }

    /**
     * {@code vocalK = 1} 是"原样透传"——老实现里那条 {@code vocalK >= 1} 直接返回入参的早返回，
     * 在接缝上就是「伴奏 = 原信号、人声 = 0」。
     */
    @Test
    public void fullRetentionIsPassThrough() {
        int sr = 48000;
        int frames = 12345 + 7;
        float[][] in = material(frames, sr);
        StemSeparator.StemSet set = new DspStemSeparator(1.0).separate(in[0], in[1], sr);

        double errL = maxSumError(set.instrumentalLeft, new float[frames], in[0]);
        double errR = maxSumError(set.instrumentalRight, new float[frames], in[1]);
        assertTrue("vocalK=1 时伴奏轨就是原信号，实测最大偏差 " + errL, errL < 1e-5);
        assertTrue("vocalK=1 时伴奏轨就是原信号（右），实测最大偏差 " + errR, errR < 1e-5);
        for (int i = 0; i < frames; i++) {
            assertEquals("vocalK=1 时人声轨应精确为 0，第 " + i + " 点", 0.0f, set.vocalsLeft[i], 0.0f);
            assertEquals("vocalK=1 时人声轨应精确为 0（右），第 " + i + " 点", 0.0f, set.vocalsRight[i], 0.0f);
        }
    }

    // ==================================================================
    // ④ 短于一个 STFT 窗：原样返回
    // ==================================================================

    /**
     * 输入短于一个窗（{@code N = 2048} 帧）时不做任何处理。
     *
     * <p>接缝上没有"失败"这个出口，所以"没做分离"必须落成一个诚实的组合：
     * <strong>伴奏 = 原信号、人声 = 0</strong>（等价于 {@code g ≡ 1}）。选这一边而不是
     * "人声 = 原信号、伴奏 = 0"，是因为后者等于凭空声称"找到了人声"，
     * 而这里根本没有做任何分析；前者既不丢音频，也保住"两轨相加 = 原信号"。
     */
    @Test
    public void inputShorterThanOneWindowIsPassedThrough() {
        int sr = 44100;
        int[] lengths = {2047, 1000, 1, 0};
        for (int k = 0; k < lengths.length; k++) {
            int frames = lengths[k];
            float[][] in = material(frames, sr);
            float[] leftCopy = in[0].clone();
            float[] rightCopy = in[1].clone();

            StemSeparator.StemSet set = new DspStemSeparator().separate(in[0], in[1], sr);

            assertEquals("长度=" + frames + " 时帧数应保持一致", frames, set.frames());
            assertArrayEquals("长度=" + frames + " 时伴奏轨左应原样返回", leftCopy, set.instrumentalLeft, 0.0f);
            assertArrayEquals("长度=" + frames + " 时伴奏轨右应原样返回", rightCopy, set.instrumentalRight, 0.0f);
            for (int i = 0; i < frames; i++) {
                assertEquals("长度=" + frames + " 时人声轨应为 0，第 " + i + " 点",
                        0.0f, set.vocalsLeft[i], 0.0f);
                assertEquals("长度=" + frames + " 时人声轨应为 0（右），第 " + i + " 点",
                        0.0f, set.vocalsRight[i], 0.0f);
            }
        }
    }

    /**
     * 长度不是 hop 整数倍时，末尾那几个没被任何窗覆盖的样点也必须<b>原样留下</b>。
     *
     * <p>这是与老实现的一处有意差异（见实现类注释的差异一）：老实现把它们除以 1.0，
     * 也就是<strong>清零</strong>——最多 511 帧 ≈ 11.6 ms 的静音豁口，而且会让
     * "两轨相加 = 原信号"在那里不成立。这里改成原样透传到伴奏轨。
     */
    @Test
    public void tailNotCoveredByAnyWindowIsNotSilenced() {
        int sr = 44100;
        int frames = 2048 + 511;        // 只够一帧 + 511 帧尾巴（hop - 1）
        float[][] in = material(frames, sr);
        StemSeparator.StemSet set = new DspStemSeparator().separate(in[0], in[1], sr);

        double errL = maxSumError(set.instrumentalLeft, set.vocalsLeft, in[0]);
        assertTrue("尾巴上相加也应还原原信号，实测 " + errL, errL < 1e-5);

        // 尾巴（最后一窗之后）不该被动过：逐位等于原信号
        double tailEnergy = 0;
        for (int i = 2048; i < frames; i++) {
            assertEquals("尾巴第 " + i + " 点应原样留下", in[0][i], set.instrumentalLeft[i], 0.0f);
            assertEquals("尾巴第 " + i + " 点人声应为 0", 0.0f, set.vocalsLeft[i], 0.0f);
            tailEnergy += Math.abs(in[0][i]);
        }
        assertTrue("这条测法的前提是尾巴上本来有信号", tailEnergy > 0.0);
    }

    /**
     * 覆盖不足的那一段（素材开头、末尾没被覆盖的尾巴）<b>不许变成爆音</b>，
     * 而是平滑退回原信号——这是与老实现差异一的钉子。
     *
     * <p>素材是 0.5 幅度的纯正中 1000 Hz：它的<strong>理想伴奏是 0</strong>。
     * 老实现在 {@code i = 4..7} 处会给出 ±1（除以几乎为零的窗值把增益改写的残差放大了
     * 几万倍，再撞上钳位），这里必须看到"退回原信号"而不是那个爆音。
     */
    @Test
    public void weakCoverageAtTheEdgesFallsBackInsteadOfBlowingUp() {
        int sr = 44100;
        int frames = 3000;
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.5 * Math.sin(2 * Math.PI * VOCAL_HZ * i / sr));
            left[i] = v;
            right[i] = v;
        }
        StemSeparator.StemSet set = new DspStemSeparator().separate(left, right, sr);

        // ① 一个样点都不许被钳（老实现在开头会连撞好几次）
        double peakIns = 0;
        double peakVoc = 0;
        for (int i = 0; i < frames; i++) {
            peakIns = Math.max(peakIns, Math.abs(set.instrumentalLeft[i]));
            peakVoc = Math.max(peakVoc, Math.abs(set.vocalsLeft[i]));
        }
        System.out.println("[DSP 实测 纯正中音] 伴奏峰值 " + peakIns + "，人声峰值 " + peakVoc
                + "（老实现在开头是 ±1）");
        assertTrue("伴奏轨不应出现爆音，实测峰值 " + peakIns, peakIns <= 0.5 + 1e-3);
        assertTrue("人声轨不应出现爆音，实测峰值 " + peakVoc, peakVoc <= 0.5 + 1e-3);

        // ② 开头（覆盖极弱）退回原信号：偏差只该是"覆盖率 × 处理结果与原信号的差"
        double worstHead = 0;
        for (int i = 1; i <= 100; i++) {
            worstHead = Math.max(worstHead, Math.abs((double) set.instrumentalLeft[i] - (double) left[i]));
        }
        assertTrue("开头覆盖不足处该退回原信号，实测最大偏差 " + worstHead, worstHead < 0.01);
        // 人声轨在那里也按覆盖率缩过：cover ≤ 1，量级是 (w²/0.25)×0.5。
        // 实测 i=99 处是 1.05e-3，取 5e-3 留 5 倍余量（它是随 i 平滑增长的，不是跳变）。
        for (int i = 1; i <= 100; i++) {
            assertEquals("开头覆盖不足处人声轨该接近 0，第 " + i + " 点",
                    0.0f, set.vocalsLeft[i], 5e-3f);
        }

        // ③ 覆盖充足的区段照常处理：纯正中音在那里应该已经被去干净
        double worstSteady = 0;
        for (int i = 1024; i < 2048; i++) {
            worstSteady = Math.max(worstSteady, Math.abs(set.instrumentalLeft[i]));
        }
        assertTrue("覆盖充足处伴奏轨该接近 0，实测最大 " + worstSteady, worstSteady < 0.05);
    }

    // ==================================================================
    // 入参纪律
    // ==================================================================

    /** 改写输入数组是不允许的（接缝没有说可以就地处理，调用方还会用原信号）。 */
    @Test
    public void doesNotMutateInputsAndDoesNotAliasThem() {
        int sr = 44100;
        int frames = 8192 + 3;
        float[][] in = material(frames, sr);
        float[] left = in[0];
        float[] right = in[1];
        float[] leftCopy = left.clone();
        float[] rightCopy = right.clone();

        StemSeparator.StemSet set = new DspStemSeparator().separate(left, right, sr);

        assertArrayEquals("输入左声道不该被改写", leftCopy, left, 0.0f);
        assertArrayEquals("输入右声道不该被改写", rightCopy, right, 0.0f);
        assertNotSame("返回的轨不该是入参本身", left, set.instrumentalLeft);
        assertNotSame("返回的轨不该是入参本身", right, set.instrumentalRight);
    }

    /** 参数不合法要说出来，不能静默算错。 */
    @Test
    public void rejectsBadArguments() {
        float[] two = new float[100];
        try {
            new DspStemSeparator().separate(null, two, 44100);
            fail("左声道为 null 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue("消息该说清是声道的问题，实际：" + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("null"));
        }
        try {
            new DspStemSeparator().separate(two, new float[99], 44100);
            fail("左右长度不一致应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            new DspStemSeparator().separate(two, two, 0);
            fail("采样率非正应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        double[] bad = {Double.NaN, -0.01, 1.01, Double.POSITIVE_INFINITY};
        for (int i = 0; i < bad.length; i++) {
            try {
                new DspStemSeparator(bad[i]);
                fail("vocalK=" + bad[i] + " 越界应抛 IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // 预期
            }
        }
    }

    /** {@code close()} 无状态、可重复调用，关掉之后照样能用（DSP 这一档本来就是"永远可用"）。 */
    @Test
    public void closeIsIdempotentAndKeepsTheInstanceUsable() {
        int sr = 44100;
        int frames = 10000;
        float[][] in = material(frames, sr);
        DspStemSeparator sep = new DspStemSeparator();
        sep.close();
        sep.close();
        StemSeparator.StemSet set = sep.separate(in[0], in[1], sr);
        assertEquals(frames, set.frames());
        // 关掉之后结果照旧正确：DSP 这一档没有模型可缺失，也没有会话可释放
        double err = maxSumError(set.instrumentalLeft, set.vocalsLeft, in[0]);
        assertTrue("close 之后分离结果照样精确，实测 " + err, err < 1e-5);
        assertEquals("vocalK 只是构造参数，close 不该改动它", 0.0, sep.vocalK(), 0.0);
    }

    /**
     * 钳位（差异二）：产物必须留在 ±1 内，代价是<strong>超满刻度</strong>素材上
     * "两轨相加 = 原信号"会在被钳的样点上不成立——偏差上界就是超出部分的幅度。
     * 这条把代价也钉住，免得日后有人以为它处处精确。
     */
    @Test
    public void clampingPastFullScaleIsTheOnlyPlaceTheSumIsNotExact() {
        int sr = 44100;
        int frames = 12000 + 5;
        float[] left = new float[frames];
        float[] right = new float[frames];
        double amp = 1.6;                       // 远超满刻度的正中"人声"
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2 * Math.PI * VOCAL_HZ * i / sr));
            left[i] = v;
            right[i] = v;
        }
        StemSeparator.StemSet set = new DspStemSeparator().separate(left, right, sr);

        int clipped = 0;
        double worstUnclipped = 0;
        for (int i = 0; i < frames; i++) {
            assertTrue("伴奏轨必须在 ±1 内，第 " + i + " 点 = " + set.instrumentalLeft[i],
                    Math.abs(set.instrumentalLeft[i]) <= 1.0f);
            assertTrue("人声轨必须在 ±1 内，第 " + i + " 点 = " + set.vocalsLeft[i],
                    Math.abs(set.vocalsLeft[i]) <= 1.0f);
            if (Math.abs(left[i]) > 1.0f) {
                clipped++;
            } else {
                double d = Math.abs((double) set.instrumentalLeft[i] + (double) set.vocalsLeft[i]
                        - (double) left[i]);
                if (d > worstUnclipped) {
                    worstUnclipped = d;
                }
            }
        }
        System.out.println("[DSP 实测] 超满刻度素材：被钳样点 " + clipped + " 个，未钳处最大偏差 " + worstUnclipped);
        assertTrue("这条测法的前提是素材真的超过了满刻度并触发钳位", clipped > 0);
        assertTrue("没被钳位的地方相加仍然精确，实测 " + worstUnclipped, worstUnclipped < 1e-5);
    }
}
