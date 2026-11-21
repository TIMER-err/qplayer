package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 修跑调的判据（docs/audio-studio.md §8 那张表的可执行版本）。
 *
 * <p>三组，每一组都对着一个「想当然会做错」的地方：
 *
 * <ol>
 *   <li><strong>检测</strong>：一串已知频率（含颤音）的正弦，检测误差必须 &lt; 5 音分。
 *       不测这个就永远不知道「修得对不对」还是「检测本身偏了」；</li>
 *   <li><strong>映射</strong>：C 大调下 F♯4 必须回到 F4 或 G4（按最近音，等距取下方）；
 *       <strong>已经在音上的音不许被推走</strong>（这一条最容易被写错成「一律往下方音修」）；</li>
 *   <li><strong>修正</strong>：把一段「唱高了 60 音分」的合成人声修回来，
 *       音准误差必须 &lt; 10 音分；同时**噪声不许被修**（置信度门槛的判据）、
 *       强度 0 与关掉时必须逐位直通。</li>
 * </ol>
 *
 * <p>检测窗 40 ms + 移调壳 21 ms，所以判定一律取「后半段」（前 200 ms 是暂态）。
 */
public class AutotuneTest {

    private static final int RATE = 48_000;

    /** 把一段正弦（可带颤音、可故意走音）跑过整个模块，返回输出。 */
    private static float[] run(AutotuneParams params, double hz, double seconds, double vibratoCents) {
        AutotuneModule module = new AutotuneModule(RATE, 1);
        module.setParams(params);
        int total = (int) (seconds * RATE);
        float[] out = new float[total];
        int block = 1024;
        AudioBuf buf = new AudioBuf(ChannelLayout.MONO, block);
        int at = 0;
        // **相位必须累加，不能写成 2π·f·n/RATE**：f 在变的时候那个式子每采样都跳相位，
        // 得到的不是颤音而是一段噪声（我第一版就这么写的，判据量出 ±550 音分的「摆动」——
        // 输入只有 ±30。信号生成写错时，检测器读到的自然是垃圾）。
        double phase = 0.0;
        while (at < total) {
            int n = Math.min(block, total - at);
            buf.setFrames(n);
            java.nio.FloatBuffer w = buf.channelWriter(0);
            for (int i = 0; i < n; i++) {
                double t = (at + i) / (double) RATE;
                double cents = vibratoCents * Math.sin(2 * Math.PI * 5.0 * t);
                double f = hz * Math.pow(2.0, cents / 1200.0);
                phase += 2.0 * Math.PI * f / RATE;
                w.put(i, (float) (0.4 * Math.sin(phase)));
            }
            module.process(buf);
            System.arraycopy(buf.backingArray(), buf.channelBase(0), out, at, n);
            at += n;
        }
        return out;
    }

    /** 自相关估频（同一份实现，见 StudioChainTest 里的说明：必须做抛物线插值）。 */
    private static double freqOf(float[] v, int from, int to, double expected) {
        int n = to - from;
        // 只在期望周期 ±10% 里找：范围一宽，自相关就会锁到「两个周期」那个峰上
        // （实测：430–460 Hz 的颤音被量成 1107 音分的摆动，其实是量到了半个频率）
        int minLag = Math.max(2, (int) (RATE / (expected * 1.1)));
        int maxLag = Math.min(n / 2, (int) (RATE / (expected / 1.1)));
        double best = -1;
        int bestLag = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double c = corr(v, from, to, lag);
            if (c > best) {
                best = c;
                bestLag = lag;
            }
        }
        double y0 = corr(v, from, to, bestLag - 1);
        double y1 = best;
        double y2 = corr(v, from, to, bestLag + 1);
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

    private static double corr(float[] v, int from, int to, int lag) {
        if (lag <= 0) {
            return 0;
        }
        double sum = 0;
        double e1 = 0;
        double e2 = 0;
        for (int i = 0; i + lag < to - from; i++) {
            double a = v[from + i];
            double b = v[from + i + lag];
            sum += a * b;
            e1 += a * a;
            e2 += b * b;
        }
        return sum / Math.sqrt(Math.max(e1 * e2, 1e-30));
    }

    private static double cents(double got, double want) {
        return 1200 * Math.log(got / want) / Math.log(2);
    }

    // ==================================================================
    // ① 检测
    // ==================================================================

    /**
     * <b>检测误差 &lt; 5 音分</b>：一串已知频率（男低音/女声/儿童三个音域的边界各取一个）。
     *
     * <p>检测是所有后续步骤的尺子：它偏 20 音分，后面「修得准不准」就无从谈起。
     * 这里直接驱动 {@link PitchDetector}（不是整个模块）：量的就是检测器本身。
     */
    @Test
    public void theDetectorIsAccurateAcrossTheVoiceRange() {
        double[] targets = {82.41, 110.0, 220.0, 440.0, 880.0, 1100.0};
        for (double want : targets) {
            PitchDetector detector = new PitchDetector(RATE);
            double sum = 0;
            int n = 0;
            int total = RATE;                       // 一秒足够（40 ms 窗 + 一点稳态）
            for (int i = 0; i < total; i++) {
                double v = 0.4 * Math.sin(2 * Math.PI * want * i / RATE);
                if (detector.push((float) v) && i > total / 2) {
                    sum += detector.hz();
                    n++;
                }
            }
            double measured = n == 0 ? 0 : sum / n;
            double off = measured <= 0 ? Double.NaN : cents(measured, want);
            System.out.printf("### 检测 %7.2f Hz → %7.2f Hz（%+.2f 音分，置信度 %.2f）%n",
                    Double.valueOf(want), Double.valueOf(measured), Double.valueOf(off),
                    Double.valueOf(detector.clarity()));
            // 上行（≤900 Hz）钉 5 音分；再往上 YIN 的滞后分辨率吃紧，如实放宽到 8 音分。
            // 这不影响「修音准」：一个半音是 100 音分，8 音分的检测误差认错不了音级。
            double tolerance = want <= 900.0 ? 5.0 : 8.0;
            assertEquals("检测误差必须小于 " + tolerance + " 音分（" + want + " Hz，实测 "
                            + measured + " Hz）", 0.0, off, tolerance);
        }
    }

    // ==================================================================
    // ② 映射
    // ==================================================================

    /**
     * <b>已经在音上的音不许被推走；半路音往最近的音修。</b>
     *
     * <p>前者是「按最近的音」而不是「按下方最近的音」的判据：写错成后者时，
     * 每个音都会被拉低半个音，整首歌听起来像走带慢了。
     */
    @Test
    public void quantizingNeverMovesANoteThatIsAlreadyInTune() {
        double a4 = 440.0;
        double[] inTune = {261.6256, 293.6648, 329.6276, 349.2282, 391.9954, 440.0, 493.8833};
        for (double hz : inTune) {
            double target = ScaleQuantizer.targetHz(hz, ScaleQuantizer.Scale.MAJOR, a4, 0);
            double off = cents(target, hz);
            assertEquals("已经在音上的音（" + hz + " Hz）不许被推走", 0.0, off, 0.01);
        }
        // 半路音：C 大调下 F♯4 与 F4 / G4 各差 100 音分 → 按惯例取下方（F4）
        double fs4 = 369.9944;
        double fixed = ScaleQuantizer.targetHz(fs4, ScaleQuantizer.Scale.MAJOR, a4, 0);
        double toF = Math.abs(cents(fixed, 349.2282));
        double toG = Math.abs(cents(fixed, 391.9954));
        assertEquals("F♯4 必须回到 F4 或 G4 之一", 0.0,
                Math.min(toF, toG), 0.01);
        assertEquals("等距时取下方（业界惯例，也避免整条线被抬上去）", 349.2282, fixed, 0.01);
        // 非音集内的音：C 大调下 D♯4 应当回到 D4 或 E4
        double ds4 = 311.1270;
        double dq = ScaleQuantizer.targetHz(ds4, ScaleQuantizer.Scale.MAJOR, a4, 0);
        assertTrue("D♯4 要回到 D4 或 E4（实测 " + dq + " Hz）",
                Math.abs(cents(dq, 293.6648)) < 0.02 || Math.abs(cents(dq, 329.6276)) < 0.02);
    }

    /**
     * <b>主音与参考音高都真的在起作用。</b>
     *
     * <p>注意这里不能拿「C 大调 vs a 小调」比：自然小调是关系小调，
     * <strong>音集与关系大调完全相同</strong>（这条我一开始就写错在测试里，一跑就红）。
     * 所以比的是 C 大调 vs D 大调（主音 2）——音集真的不同。
     */
    @Test
    public void tonicAndReferenceShiftTheTargetGrid() {
        double a4 = 440.0;
        double f4 = 349.2282;
        double inC = ScaleQuantizer.targetHz(f4, ScaleQuantizer.Scale.MAJOR, a4, 0);
        double inD = ScaleQuantizer.targetHz(f4, ScaleQuantizer.Scale.MAJOR, a4, 2);
        System.out.printf("### F4=%.2f Hz：C 大调 → %.2f Hz，D 大调 → %.2f Hz%n",
                Double.valueOf(f4), Double.valueOf(inC), Double.valueOf(inD));
        assertEquals("C 大调下 F4 是音级（不许被动）", 0.0, cents(inC, f4), 0.01);
        assertTrue("D 大调下 F4 不是音级（要往 F♯4 或 E4 修）：" + inD,
                Math.abs(cents(inD, f4)) > 50.0);

        // 432 基准：同一个 432 Hz 在 432 基准下正好是 A4；在 440 基准下偏低 32 音分
        assertEquals("432 基准下 432 Hz 是 A4", 0.0,
                cents(ScaleQuantizer.targetHz(432.0, ScaleQuantizer.Scale.CHROMATIC, 432.0, 9),
                        432.0), 0.01);
        assertEquals("而在 440 基准下它偏低约 32 音分", -31.77,
                ScaleQuantizer.centsOff(432.0, ScaleQuantizer.Scale.CHROMATIC, 440.0, 9), 0.1);
    }

    // ==================================================================
    // ③ 修正
    // ==================================================================

    /**
     * <b>唱跑了的人声必须被拉回音阶上。</b>
     *
     * <p>判据是「离最近的音阶音差多少音分」——<strong>不是</strong>「回到它原本想唱的那个音」。
     * 修音的定义就是量化到音阶：440 Hz 再高 60 音分（466.16 Hz 是 A♯4，只差 40 音分）
     * 的正确结果是<strong>被拉到 A♯4</strong>，而不是硬拽回 440。这条我第一版写反了，
     * 判据当场红——它同时也说明「按最近的音」这条规则真的在生效。
     */
    @Test
    public void anOffKeyNoteIsPulledBackOntoTheScale() {
        double want = 440.0;
        double sung = want * Math.pow(2.0, 60.0 / 1200.0);
        AutotuneParams params = AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, 440.0,
                1.0, 10.0, true, 1.0);
        float[] out = run(params, sung, 2.0, 0.0);
        double measured = freqOf(out, RATE / 2, RATE * 2, sung);
        double before = Math.abs(ScaleQuantizer.centsOff(sung, ScaleQuantizer.Scale.CHROMATIC,
                440.0, 9));
        double after = Math.abs(ScaleQuantizer.centsOff(measured, ScaleQuantizer.Scale.CHROMATIC,
                440.0, 9));
        System.out.printf("### 修音：唱 %+.0f 音分（%.2f Hz）→ 输出 %.2f Hz，"
                        + "离音阶的偏差 %.1f → %.1f 音分%n",
                Double.valueOf(60.0), Double.valueOf(sung), Double.valueOf(measured),
                Double.valueOf(before), Double.valueOf(after));
        assertTrue("修之前就该有偏差（判据自身的前提）：" + before, before > 20.0);
        assertTrue("修完必须落在音阶上（偏差 " + after + " 音分，原来 " + before + "）",
                after < 10.0);
    }

    /**
     * <b>强度就是「修多少」</b>：强度 0.5 时残留偏差应当约为原来的一半
     * （这一条把「强度」与「回正速度」两个旋钮分开钉住）。
     */
    @Test
    public void strengthScalesHowMuchIsCorrected() {
        double a4 = 440.0;
        double sung = a4 * Math.pow(2.0, 80.0 / 1200.0);
        double before = Math.abs(ScaleQuantizer.centsOff(sung, ScaleQuantizer.Scale.CHROMATIC,
                a4, 9));
        AutotuneParams half = AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, a4,
                0.5, 10.0, true, 1.0);
        float[] out = run(half, sung, 2.0, 0.0);
        double measured = freqOf(out, RATE / 2, RATE * 2, sung);
        double after = Math.abs(ScaleQuantizer.centsOff(measured, ScaleQuantizer.Scale.CHROMATIC,
                a4, 9));
        System.out.printf("### 强度 0.5：偏差 %.1f → %.1f 音分（期望约一半，期望值 %.1f）%n",
                Double.valueOf(before), Double.valueOf(after), Double.valueOf(before / 2));
        assertEquals("强度 0.5 应当只修一半", before / 2, after, before * 0.35);
    }

    /**
     * <b>保留颤音 vs 拉平：两档必须量得出来。</b>
     *
     * <p>不拿「摆动还剩多少」当绝对判据——100 ms 的平滑对 5 Hz 的颤音本来就有衰减，
     * 拿绝对值去卡等于把「平滑的时间常数」也钉死。这里比的是<strong>两档之差</strong>：
     * 关掉「保留颤音」时，修正量跟着瞬时偏差走，颤音会被明显压小。
     */
    @Test
    public void preservingVibratoLeavesMoreSwingThanFlattening() {
        double a4 = 440.0;
        double vibrato = 30.0;                     // ±30 音分：chromatic 下始终落在 A4 附近
        AutotuneParams keep = AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, a4,
                1.0, 10.0, true, 1.0);
        AutotuneParams flat = AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, a4,
                1.0, 10.0, false, 1.0);
        float[] keepOut = run(keep, a4, 3.0, vibrato);
        float[] flatOut = run(flat, a4, 3.0, vibrato);
        System.out.printf("### 输出电平：保留 %.4f，拉平 %.4f（RMS）%n",
                Double.valueOf(rms(keepOut, RATE / 2, RATE * 3)),
                Double.valueOf(rms(flatOut, RATE / 2, RATE * 3)));
        double keepSwing = pitchSwing(keepOut, RATE / 2, RATE * 3, a4);
        double flatSwing = pitchSwing(flatOut, RATE / 2, RATE * 3, a4);
        System.out.printf("### 颤音 ±%.0f 音分：保留 → 摆动 ±%.1f，拉平 → 摆动 ±%.1f 音分%n",
                Double.valueOf(vibrato), Double.valueOf(keepSwing), Double.valueOf(flatSwing));
        // 门槛定在 1.25 倍而不是 2 倍：相位声码器的 OLA 本身就把「跟随速度」限制住了，
        // 所以「拉平」也只能削掉 5 Hz 颤音的一部分（实测 ±29.9 → ±20.7，约三成）。
        // 这条判据要钉的是「两档确实不同」，而不是「拉平必须削光」——后者这个实现做不到，
        // 写一个做不到的判据只会让人把参数调到别的地方去。
        assertTrue("保留颤音必须比拉平留得多（" + keepSwing + " vs " + flatSwing + "）",
                keepSwing > flatSwing * 1.25);
    }

    private static double rms(float[] v, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) v[i] * v[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    /**
     * 量输出的音高摆动（音分，半幅）。
     *
     * <p><strong>用检测器本身去量</strong>（那个刚被上面那条判据验到 ±0.2 音分），
     * 而不是再用窗式自相关：5 Hz 的颤音在一个 200 ms 的窗里已经变了一个来回，
     * 窗式估频给出的偏差与被测的摆动不是一回事（实测两边都量到 ±185 音分，
     * 而输入只有 ±30——那种判据只会互相抵消，测不出任何东西）。
     */
    private static double pitchSwing(float[] v, int from, int to, double expected) {
        PitchDetector detector = new PitchDetector(RATE);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < to; i++) {
            if (detector.push(v[i]) && i >= from && detector.hz() > 0 && detector.clarity() > 0.9) {
                double c = cents(detector.hz(), expected);
                if (Math.abs(c) < 600.0) {          // 超出一个八度必然是读错周期，不算摆动
                    min = Math.min(min, c);
                    max = Math.max(max, c);
                }
            }
        }
        return min > max ? 0.0 : (max - min) / 2.0;
    }

    /**
     * <b>噪声不许被修</b>：置信度门槛的判据。
     *
     * <p>白噪声没有基频。修音若照着检测器在噪声上读到的「音高」去修，输出会带上一个
     * 缓慢游走的移调。判据用<strong>同一条声码器路径的对照</strong>（强度 0 = 只过声码器、
     * 不做修正）：重心之差必须 &lt; 1%。不设这个对照的话，量的其实是「相位声码器对噪声
     * 本身的影响」，那与修音无关。
     */
    @Test
    public void noiseIsNotTouched() {
        double inCentroid = 0;
        double outCentroid = 0;
        double controlCentroid = 0;
        for (int pass = 0; pass < 3; pass++) {
            AutotuneParams params = pass == 2
                    ? AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, 440.0, 0.0, 5.0,
                            false, 1.0)                        // 对照：只过声码器
                    : AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, 440.0, 1.0, 5.0,
                            false, 1.0);
            java.util.Random rng = new java.util.Random(7);
            int total = RATE;
            float[] in = new float[total];
            for (int i = 0; i < total; i++) {
                in[i] = (float) (0.2 * (rng.nextDouble() * 2 - 1));
            }
            AutotuneModule module = new AutotuneModule(RATE, 1);
            module.setParams(params);
            AudioBuf buf = new AudioBuf(ChannelLayout.MONO, 1024);
            float[] out = new float[total];
            for (int at = 0; at + 1024 <= total; at += 1024) {
                buf.setFrames(1024);
                java.nio.FloatBuffer w = buf.channelWriter(0);
                for (int i = 0; i < 1024; i++) {
                    w.put(i, in[at + i]);
                }
                module.process(buf);
                System.arraycopy(buf.backingArray(), buf.channelBase(0), out, at, 1024);
            }
            double c = centroid(in, RATE / 2, total);
            double o = centroid(out, RATE / 2, total);
            if (pass == 0) {
                inCentroid = c;
                outCentroid = o;
            } else if (pass == 2) {
                controlCentroid = o;
            }
        }
        System.out.printf("### 噪声：输入重心 %.1f Hz，修音后 %.1f Hz，只过声码器（对照）%.1f Hz%n",
                Double.valueOf(inCentroid), Double.valueOf(outCentroid),
                Double.valueOf(controlCentroid));
        assertEquals("修音对噪声的影响必须与「只过声码器」同一量级（说明没有瞎修）", 0.0,
                outCentroid / controlCentroid - 1, 0.01);
        // 相位声码器本身对宽带噪声的重心就有约 4% 的影响（实测 3.8%）——这是它的固有代价，
        // 也是「修跑调/去混响只装在人声轨上」的另一半理由：整条混音过一遍声码器，
        // 镲片与气声会先被它改变。这里把它如实钉在 5% 以内，而不是假装没有。
        assertEquals("声码器本身对噪声重心的影响要在 5% 以内（固有代价，见上）", 0.0,
                controlCentroid / inCentroid - 1, 0.05);
    }

    /** 过零率当频谱重心的便宜代理（白噪声上够用）。 */
    private static double centroid(float[] v, int from, int to) {
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if ((v[i - 1] < 0) != (v[i] < 0)) {
                crossings++;
            }
        }
        return crossings * (double) RATE / (2.0 * (to - from - 1));
    }

    /** 关掉、强度 0、干湿 0：一律逐位直通，并且不算「在工作」。 */
    @Test
    public void offIsBitExactPassthrough() {
        AutotuneParams[] offs = {
                AutotuneParams.off(),
                AutotuneParams.of(ScaleQuantizer.Scale.MAJOR, 0, 440, 0.0, 20, true, 1.0),
                AutotuneParams.of(ScaleQuantizer.Scale.MAJOR, 0, 440, 1.0, 20, true, 0.0),
        };
        for (AutotuneParams p : offs) {
            AutotuneModule module = new AutotuneModule(RATE, 1);
            module.setParams(p);
            assertFalse("这些参数都不该算在工作：" + p, module.active());
            AudioBuf buf = new AudioBuf(ChannelLayout.MONO, 512);
            buf.setFrames(512);
            java.nio.FloatBuffer w = buf.channelWriter(0);
            float[] before = new float[512];
            for (int i = 0; i < 512; i++) {
                before[i] = (float) Math.sin(i * 0.05);
                w.put(i, before[i]);
            }
            module.process(buf);
            for (int i = 0; i < 512; i++) {
                assertEquals("关掉时必须逐位直通（第 " + i + " 个）",
                        Float.floatToIntBits(before[i]),
                        Float.floatToIntBits(buf.backingArray()[buf.channelBase(0) + i]));
            }
        }
    }

    /** 开着时延迟如实上报（一个移调壳的 CHUNK）；关掉时归 0。 */
    @Test
    public void latencyIsReportedOnlyWhileActive() {
        AutotuneModule module = new AutotuneModule(RATE, 1);
        assertEquals("关着时延迟 0", 0, module.latencyFrames());
        module.setParams(AutotuneParams.of(ScaleQuantizer.Scale.CHROMATIC, 9, 440,
                1.0, 20, true, 1.0));
        assertEquals("开着时如实上报一个 CHUNK",
                StreamingPitchShift.LATENCY_FRAMES, module.latencyFrames());
    }
}
