package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.model.ModelAvailability;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import dev.t1m3.qplayer.audio.model.StemModels;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>用户唯一那条要求：A 和 B 的人声音调都不变——伴奏可以移。</b>
 *
 * <p>判据分两层：
 *
 * <ol>
 *   <li><b>指令层</b>：deck 永远拿不到移调指令（{@code pitchAt} 与 {@code plan.pitch}
 *       两处都为零）。变调是 deck 对整个混音施加的，只要指令为零，人声就不可能被移；</li>
 *   <li><b>样本层（本类的主判据）</b>：真 DSP 分轨 + 真变调器，量输出里各条频率的幅度——
 *       人声那条<b>还在原来的频率上</b>，伴奏那条确实移了。</li>
 * </ol>
 *
 * <h3>素材为什么这么造</h3>
 *
 * <p>{@code DspStemSeparator} 只衰减 280 Hz–4 kHz 的<i>正中</i>分量，所以：
 *
 * <ul>
 *   <li><b>人声</b>：左右完全相同的 1012 Hz（bin 47），落在完全抑制带里 →
 *       它必须出现在「人声轨」而不是「伴奏轨」；</li>
 *   <li><b>伴奏</b>：只放左声道的 86 Hz（bin 4，保留带 &lt;100 Hz）与
 *       只放右声道的 8613 Hz（bin 400，保留带 &gt;5.2 kHz）→ 它们必须留在伴奏轨。</li>
 * </ul>
 *
 * <p>于是「人声没被移、伴奏被移了」这两件事各自都有确定的频率可以量，
 * 而不是靠「听着像」。
 */
public class VocalAwarePitchTransitionTest {

    /** 输出采样率。44.1 kHz 让频带边界两侧都有整 bin 可用。 */
    private static final int RATE = 44_100;

    /** DSP 的 STFT 长度（见 {@code DspStemSeparator}）：整 bin 间距 = RATE / 2048。 */
    private static final int STFT = 2_048;

    /** 窗口 = 1 秒。 */
    private static final int WINDOW = 44_100;

    /** 渲染块 = 0.25 秒（够长到单点 DFT 有分辨率，又短到跑得快）。 */
    private static final int BLOCK = 11_025;

    private static final int VOCAL_BIN_A = 47;      // 1012.06 Hz，正中中频
    private static final int LOW_BIN_A = 4;         // 86.13 Hz，左声道，保留带
    private static final int HIGH_BIN_A = 400;      // 8613.28 Hz，右声道，保留带

    private static final int VOCAL_BIN_B = 52;      // 1119.7 Hz（另一路素材，避免和 A 混起来）
    /**
     * B 的低频伴奏取与 A 同一个 bin。
     *
     * <p>不用 bin 3（64.6 Hz）的原因量出来过：颗粒式变调器的粒长是 2048 帧
     * （≈21.5 Hz 一个 bin），64.6 Hz 的乐音在一个粒里只有约 3 个周期，
     * 移调后能量摊到邻近好几个 bin 上，单点 DFT 在那个频率上只剩 0.09 ——
     * 那不是「没移调」，而是这条测量方法在那个频点分辨率不够。
     * 判据不该建立在测不准的地方，所以两侧用同一个 bin。
     */
    private static final int LOW_BIN_B = 4;         // 86.13 Hz
    private static final int HIGH_BIN_B = 350;      // 7537.2 Hz

    private static final float VOCAL_AMP = 0.4f;
    private static final float LOW_AMP = 0.25f;
    private static final float HIGH_AMP = 0.25f;

    /** 移调量：+2 个半音（≈ +12.2% 频率）。 */
    private static final float SHIFT = 2.0f;

    /** 幅度测量的容差：Hann 窗在精确音高上做单点 DFT，误差只剩镜像项。 */
    private static final double TOL = 0.02;

    /** 「不该有能量」的判定门限：比该处应有的幅度低 20 倍。 */
    private static final double ABSENT = 0.02;

    @org.junit.Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Decoder.Opener savedOpener;
    private Path dir;

    @Before
    public void installSeams() throws IOException {
        savedOpener = currentOpener();
        Decoder.install(TestWavFile.opener());
        StemModels.uninstall();
        // 要的**就是**真 DSP：装替身这条测试就白写了。
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
        dir = temporary.newFolder().toPath();
    }

    @After
    public void restoreSeams() {
        StemModels.uninstall();
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
        Decoder.install(savedOpener);
    }

    // ==================================================================
    // 指令层
    // ==================================================================

    /** 内层哪怕报「要移 +2 个半音」，deck 拿到的也必须是零。 */
    @Test
    public void theDeckIsNeverAskedToShiftNoMatterWhatTheInnerPlanWants() {
        VocalAwarePitchTransition t = wrap(new StubInner(new PitchPlan(SHIFT, 0.0f)),
                StemSeparators.DSP, false);
        for (float p : new float[]{0.0f, 0.1f, 0.5f, 0.9f, 1.0f}) {
            assertTrue("进度 " + p + " 处不该要求 deck 移调：" + t.pitchAt(p),
                    t.pitchAt(p).isZero());
        }
    }

    /**
     * 拿不到分轨时**什么都不移**（不是退回「整段混音移调」）。
     *
     * <p>这是那条「人声保护优先于调性收益」的兜底：用户选了 AI 分轨但模型没下载、
     * 或者分轨当场失败时，宁可这一次不转调。
     */
    @Test
    public void withoutASeparatorNothingIsShifted() throws Exception {
        TransitionPlan plan = wrap(new StubInner(new PitchPlan(SHIFT, 0.0f)),
                StemSeparators.OFF, false).prepare("x.wav", "y.wav", RATE);

        assertTrue("拿不到分轨却把移调意图交给了 deck：" + plan.pitch, plan.pitch.isZero());
        assertNotNull(plan.note);
        assertTrue("说明里要写清为什么没移调：" + plan.note,
                plan.note.contains("不移调") || plan.note.contains("拿不到分轨"));
        System.out.println("[vocal-aware] 无分轨 → " + plan.note);
    }

    // ==================================================================
    // 样本层（主判据）
    // ==================================================================

    /**
     * <b>只移伴奏，两条人声都待在原频率上。</b>
     *
     * <p>量的是输出里的幅度谱：人声频率处应当仍然是原来的幅度、且移调后的那个频率处
     * <b>没有能量</b>；伴奏频率处反过来。
     */
    @Test
    public void onlyTheAccompanimentMovesWhileBothVocalsStayPut() throws Exception {
        Path a = TestWavFile.write(dir.resolve("a.wav"),
                left(VOCAL_BIN_A, LOW_BIN_A), right(VOCAL_BIN_A, HIGH_BIN_A), RATE);
        Path b = TestWavFile.write(dir.resolve("b.wav"),
                left(VOCAL_BIN_B, LOW_BIN_B), right(VOCAL_BIN_B, HIGH_BIN_B), RATE);

        VocalAwarePitchTransition t = wrap(new StubInner(new PitchPlan(SHIFT, 0.0f)),
                StemSeparators.DSP, true);
        TransitionPlan plan = t.prepare(a.toString(), b.toString(), RATE);

        assertTrue("计划里不许带移调（deck 绝不移调）：" + plan.pitch, plan.pitch.isZero());
        assertTrue("要说清只对出场曲的伴奏移调：" + plan.note,
                plan.note.contains("只对出场曲的伴奏移调"));
        assertTrue("还要说清进场曲原样：" + plan.note, plan.note.contains("进场曲原样"));
        assertTrue("自动补的 DSP 必须写在说明里：" + plan.note, plan.note.contains("自动用 DSP"));

        // ---- A 侧：进度 0，出场曲满音量（inGain = 0） ----
        float[][] outA = renderOnce(t, 0.0f);
        double aVocalFreq = freq(VOCAL_BIN_A);
        double aVocalShifted = shiftedFreq(VOCAL_BIN_A);
        double aLowFreq = freq(LOW_BIN_A);
        double aLowShifted = shiftedFreq(LOW_BIN_A);
        double aHighFreq = freq(HIGH_BIN_A);
        double aHighShifted = shiftedFreq(HIGH_BIN_A);

        report("A 人声", aVocalFreq, aVocalShifted,
                amplitudeAt(outA[0], aVocalFreq), amplitudeAt(outA[0], aVocalShifted), VOCAL_AMP);
        report("A 低频伴奏", aLowFreq, aLowShifted,
                amplitudeAt(outA[0], aLowFreq), amplitudeAt(outA[0], aLowShifted), LOW_AMP);
        report("A 高频伴奏", aHighFreq, aHighShifted,
                amplitudeAt(outA[1], aHighFreq), amplitudeAt(outA[1], aHighShifted), HIGH_AMP);

        // 人声：原频率还在，移调后的频率上没有东西
        assertEquals("A 的人声幅度变了（人声不许被动）",
                VOCAL_AMP, amplitudeAt(outA[0], aVocalFreq), TOL);
        assertTrue("A 的人声被移调了：移调后频率 " + aVocalShifted + " Hz 处出现了 "
                        + amplitudeAt(outA[0], aVocalShifted) + " 的幅度",
                amplitudeAt(outA[0], aVocalShifted) < ABSENT);
        // 伴奏：能量整个搬到移调后的频率上，原频率基本空了。
        //
        // 两个数都是量出来的（44.1 kHz、1 秒窗口、真 DSP、真变调器 +2 半音）：
        // 低频 0.000 → 0.244（应有 0.25），高频 0.000 → 0.244。
        // 判据留了很宽的余量（≥0.6× 应有、原频率 ≤0.25× 应有）——这条要抓的是
        // 「根本没移」或「移调后没声音」这类整体性错误，不是几个百分点的精度。
        assertTrue("A 的低频伴奏没出现在移调后的频率上：" + aLowShifted + " Hz 处只有 "
                        + amplitudeAt(outA[0], aLowShifted),
                amplitudeAt(outA[0], aLowShifted) > LOW_AMP * 0.6);
        assertTrue("A 的低频伴奏还留在原频率上：" + amplitudeAt(outA[0], aLowFreq),
                amplitudeAt(outA[0], aLowFreq) < LOW_AMP * 0.25);
        assertTrue("A 的高频伴奏没出现在移调后的频率上",
                amplitudeAt(outA[1], aHighShifted) > HIGH_AMP * 0.6);
        assertTrue("A 的高频伴奏还留在原频率上",
                amplitudeAt(outA[1], aHighFreq) < HIGH_AMP * 0.25);

        // ---- B 侧：进度 1，进场曲满音量（outGain = 0） ----
        float[][] outB = renderOnce(t, 1.0f);
        report("B 人声", freq(VOCAL_BIN_B), shiftedFreq(VOCAL_BIN_B),
                amplitudeAt(outB[0], freq(VOCAL_BIN_B)),
                amplitudeAt(outB[0], shiftedFreq(VOCAL_BIN_B)), VOCAL_AMP);
        assertEquals("B 的人声幅度变了（人声不许被动）",
                VOCAL_AMP, amplitudeAt(outB[0], freq(VOCAL_BIN_B)), TOL);
        assertTrue("B 的人声被移调了：移调后频率处出现了 "
                        + amplitudeAt(outB[0], shiftedFreq(VOCAL_BIN_B)) + " 的幅度",
                amplitudeAt(outB[0], shiftedFreq(VOCAL_BIN_B)) < ABSENT);
        report("B 低频伴奏", freq(LOW_BIN_B), shiftedFreq(LOW_BIN_B),
                amplitudeAt(outB[0], freq(LOW_BIN_B)),
                amplitudeAt(outB[0], shiftedFreq(LOW_BIN_B)), LOW_AMP);
        // **进场曲的伴奏留在它自己的频率上**（用户 2026-09-30 报「加速了」之后改的）：
        // 原来这里钉的是「B 的伴奏也要移到移调后的频率」，那正是病根——B 被移了、B 的人声
        // 没移，所以窗口里 B 自己就不协和，而窗口一结束 deck 按原调继续放 B，那一下就是
        // 用户听到的跳变。现在 B 整条都不动。
        assertTrue("B 的伴奏被移调了（它本来就不该动）：原频率 " + freq(LOW_BIN_B)
                        + " Hz 处只剩 " + amplitudeAt(outB[0], freq(LOW_BIN_B)),
                amplitudeAt(outB[0], freq(LOW_BIN_B)) > LOW_AMP * 0.6);
        assertTrue("B 的伴奏出现在了移调后的频率上："
                        + amplitudeAt(outB[0], shiftedFreq(LOW_BIN_B)),
                amplitudeAt(outB[0], shiftedFreq(LOW_BIN_B)) < ABSENT);
    }

    /**
     * <b>进场曲在融合期里逐样本就是它自己——一个采样都不许被处理。</b>
     *
     * <p>这是「加速了」那条的正面判据，也是最强的一条：进度 1 时等功率增益里
     * {@code outGain = cos(π/2) = 0}、{@code inGain·g = 1}，所以包装层的输出**全部**来自
     * {@code bInst + bVoc} 两条轨；只要这两条轨是 B 原始窗口素材过一遍分离再原样叠回来
     * （真 DSP 的重建误差是 3e-7 量级），输出就该与原始素材<b>逐样本相等</b>。
     *
     * <p>移了 B 的伴奏时这条会立刻红：低频那条整体掉 {@code shift} 个半音，逐样本差直接到
     * 0.2 量级。所以它同时钉住「和声上白移」和「交接处弹回原调」这两件事的根源。
     */
    @Test
    public void theIntroWindowIsUntouchedSampleForSample() throws Exception {
        float[] bLeft = left(VOCAL_BIN_B, LOW_BIN_B);
        float[] bRight = right(VOCAL_BIN_B, HIGH_BIN_B);
        Path a = TestWavFile.write(dir.resolve("untouched-a.wav"),
                left(VOCAL_BIN_A, LOW_BIN_A), right(VOCAL_BIN_A, HIGH_BIN_A), RATE);
        Path b = TestWavFile.write(dir.resolve("untouched-b.wav"), bLeft, bRight, RATE);

        VocalAwarePitchTransition t = wrap(stub(0.0), StemSeparators.DSP, true);
        t.prepare(a.toString(), b.toString(), RATE);

        // 进度 1：输出 = B 的那两条轨（A 侧增益为 0）
        float[][] out = renderWholeWindow(t, 1.0f);
        assertEquals("渲染帧数应当就是窗口", WINDOW, out[0].length);
        double worst = 0.0;
        for (int ch = 0; ch < 2; ch++) {
            float[] want = ch == 0 ? bLeft : bRight;
            for (int i = 0; i < WINDOW; i++) {
                worst = Math.max(worst, Math.abs((double) out[ch][i] - (double) want[i]));
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vocal-aware] 进度 1 时输出与 B 原始素材的最大逐样本差 %.3e（真 DSP 重建误差量级）",
                Double.valueOf(worst)));
        assertTrue("B 的窗口素材被动过了（最大逐样本差 " + worst + "）——"
                        + "它在窗口里必须与 deck 接下来放的完全是同一份东西",
                worst < 1e-3);
    }

    /**
     * <b>出场曲那一段从「总长 − 被丢掉的尾巴 − 窗口」读，不是从文件第 0 帧读。</b>
     *
     * <p>这条钉的是用户报的那个「阴间」bug：原先把 {@code plan.outroTailSecs}
     * （<b>被丢掉的尾巴长度</b>）直接当偏移用，固定点位时它恒为 0 → 读到的是出场曲的
     * <b>开头</b>，融合期里听到的是这首歌的前奏而不是它的尾段。
     *
     * <p>素材刻意做成「只有最后那一窗口有声音」：读对了就有声音，读错了就是一片静音，
     * 判据因此是决定性的（不是靠听）。
     */
    @Test
    public void theOutroWindowIsReadFromItsTailNotFromTheStart() throws Exception {
        // A 长 3 秒：前 2 秒静音、最后一秒是「尾段」；窗口 1 秒、尾巴 0
        Path a = TestWavFile.write(dir.resolve("tail-only.wav"),
                withSilentLead(2.0, left(VOCAL_BIN_A, LOW_BIN_A)),
                withSilentLead(2.0, right(VOCAL_BIN_A, HIGH_BIN_A)), RATE);
        Path b = TestWavFile.write(dir.resolve("b.wav"),
                left(VOCAL_BIN_B, LOW_BIN_B), right(VOCAL_BIN_B, HIGH_BIN_B), RATE);

        VocalAwarePitchTransition t = wrap(stub(0.0), StemSeparators.DSP, true);
        t.prepare(a.toString(), b.toString(), RATE);
        float[][] out = renderOnce(t, 0.0f);

        double at = amplitudeAt(out[0], freq(VOCAL_BIN_A));
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vocal-aware] 尾段偏移：A 长 3.0s、窗口 1.0s、尾巴 0 → 应当从 2.0s 读；"
                        + "输出里 1012.1 Hz 幅度 %.4f（读对了是 0.40，读到开头是 0.00）", at));
        assertTrue("读到的是出场曲的开头（或别处），不是它最后那一窗口：" + at, at > 0.3);
    }

    /** 有「被丢掉的尾巴」时，起点还要再往前挪 {@code 尾巴} 秒。 */
    @Test
    public void aDroppedOutroTailMovesTheWindowEarlier() throws Exception {
        // A 长 3 秒：只有 [1.5, 2.5) 有声音；尾巴 0.5 + 窗口 1.0 → 应当从 1.5 s 读
        Path a = TestWavFile.write(dir.resolve("tail-window.wav"),
                contentBetween(1.5, 3.0, left(VOCAL_BIN_A, LOW_BIN_A)),
                contentBetween(1.5, 3.0, right(VOCAL_BIN_A, HIGH_BIN_A)), RATE);
        Path b = TestWavFile.write(dir.resolve("b2.wav"),
                left(VOCAL_BIN_B, LOW_BIN_B), right(VOCAL_BIN_B, HIGH_BIN_B), RATE);

        VocalAwarePitchTransition t = wrap(stub(0.5), StemSeparators.DSP, true);
        t.prepare(a.toString(), b.toString(), RATE);
        float[][] out = renderOnce(t, 0.0f);

        double at = amplitudeAt(out[0], freq(VOCAL_BIN_A));
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vocal-aware] 尾段偏移：A 长 3.0s、窗口 1.0s、尾巴 0.5 → 应当从 1.5s 读；"
                        + "输出里 1012.1 Hz 幅度 %.4f", at));
        assertTrue("没有按「总长 − 尾巴 − 窗口」算起点：" + at, at > 0.3);
    }

    /** 在素材前面补静音（模拟「只有尾段有声音」的曲子）。 */
    private static float[] withSilentLead(double leadSecs, float[] body) {
        int lead = (int) Math.round(leadSecs * RATE);
        float[] out = new float[lead + body.length];
        System.arraycopy(body, 0, out, lead, body.length);
        return out;
    }

    /**
     * 全长 {@code totalSecs} 的文件，只有 {@code [fromSecs, fromSecs + body)} 有声音。
     *
     * <p>注意全长必须**显式给**：判据算的是「总长 − 尾巴 − 窗口」，
     * 文件多长直接决定窗口落在哪（第一版忘了补尾部的静音，文件只有 2.5 秒，
     * 窗口就落到了别处，量出来是 1.3e-7——是素材的错，不是实现的错）。
     */
    private static float[] contentBetween(double fromSecs, double totalSecs, float[] body) {
        int from = (int) Math.round(fromSecs * RATE);
        int total = (int) Math.round(totalSecs * RATE);
        float[] out = new float[total];
        System.arraycopy(body, 0, out, from, Math.min(body.length, total - from));
        return out;
    }

    /** 一个窗口 1 秒、可指定「被丢掉的尾巴」的假接法。 */
    private static Transition stub(double outroTailSecs) {
        return new StubInner(new PitchPlan(SHIFT, 0.0f), outroTailSecs);
    }

    /**
     * <b>移调后的伴奏必须和原素材<u>对齐</u>（不许整体晚一截）。</b>
     *
     * <p>用户报「Cruel Summer → Symphony 几乎每次都炸，别的歌没事」：杂音 + 卡顿。
     * 根因是变调器**有延迟**（{@code PitchShifter.latencyFrames()}：输出比输入落后
     * grain − hop，44.1 kHz 下约 1536 帧 ≈ 35 ms）而 {@code shifted()} 没有补它——
     * 于是移调后的伴奏比<b>同一首歌</b>的人声晚 35 ms 混出去，一首歌被拆成两半错开叠回来：
     * 听感是梳状滤波（发闷、毛刺），鼓点这类瞬态素材最明显（那两首正好都是强瞬态流行歌）。
     *
     * <p>量法：素材做成「左声道每隔 0.2 秒一个脉冲」的伴奏（人声那一条留空），
     * 融合进度 0 处输出就是移调后的伴奏本身。逐个取脉冲的能量重心，与原始位置比——
     * 读对了应当只差几毫秒（颗粒法本身会把脉冲摊开一点），差 35 ms 就是没补延迟。
     */
    @Test
    public void theShiftedAccompanimentStaysAlignedWithTheOriginal() throws Exception {
        float[] impulses = new float[WINDOW];
        int[] expected = new int[4];
        for (int k = 0; k < expected.length; k++) {
            int at = (int) Math.round((0.2 + 0.2 * k) * RATE);
            expected[k] = at;
            for (int i = -3; i <= 3; i++) {
                if (at + i >= 0 && at + i < impulses.length) {
                    impulses[at + i] += 0.6f * (1.0f - Math.abs(i) / 4.0f);
                }
            }
        }
        float[] silence = new float[WINDOW];
        Path a = TestWavFile.write(dir.resolve("aligned.wav"), impulses, silence, RATE);
        Path b = TestWavFile.write(dir.resolve("aligned-b.wav"), impulses, silence, RATE);

        VocalAwarePitchTransition t = wrap(stub(0.0), StemSeparators.DSP, true);
        t.prepare(a.toString(), b.toString(), RATE);
        // **整窗渲染**：脉冲铺满 1 秒，只渲染一块（0.25s）会把后三个漏掉
        float[][] out = renderWholeWindow(t, 0.0f);

        System.out.println("[vocal-aware] 对齐检查（应差几毫秒，差 35ms = 没补变调器延迟）：");
        for (int k = 0; k < expected.length; k++) {
            double centroid = energyCentroid(out[0], expected[k], (int) (0.05 * RATE));
            double lagMs = (centroid - expected[k]) / (double) RATE * 1000.0;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "           第 %d 个脉冲：原始 %.1f ms，输出重心 %.1f ms（差 %+.1f ms）",
                    Integer.valueOf(k + 1), Double.valueOf(expected[k] / (double) RATE * 1000.0),
                    Double.valueOf(centroid / (double) RATE * 1000.0), Double.valueOf(lagMs)));
            assertTrue("第 " + (k + 1) + " 个脉冲错位 " + lagMs + " ms——移调后的伴奏没有对齐",
                    Math.abs(lagMs) < 12.0);
            assertTrue("输出的末尾没有内容（移调只产出了一段）：第 " + (k + 1) + " 个脉冲缺失",
                    centroid > 0.0);
        }
        // 尾巴也要有内容：只产出开头那一段的 bug 在这里会露出来
        double tail = 0.0;
        for (int i = WINDOW - (int) (0.2 * RATE); i < WINDOW; i++) {
            tail += Math.abs(out[0][i]);
        }
        assertTrue("输出尾段是静音——移调只产出了开头一段", tail > 0.0);
    }

    /**
     * <b>过渡窗口里不许「加速」：脉冲间隔必须和原素材一模一样。</b>
     *
     * <p>用户 2026-09-30 报：「只在过渡那几秒显得快，过去就恢复正常」。所以这里量的是
     * <b>融合期的时长本身</b>，不是频响、也不是幅度——把素材做成脉冲串，看融合期里
     * 相邻脉冲的间隔有没有变。
     *
     * <p>素材与判据：A 是 4 秒的脉冲串，脉冲落在「窗口起点 + 0.2/0.4/0.6/0.8 秒」
     * （窗口起点 = 总长 − 丢尾 − 窗口 = 4.0 − 0.0 − 1.0 = 3.0 s，所以绝对位置是
     * 3.2/3.4/3.6/3.8 s）；B 的脉冲落在它自己的 0.2/0.4/0.6/0.8 s（B 从 skipSecs = 0 进）。
     * 于是<b>不管进度走到哪儿</b>（第一块全是 A、最后一块全是 B），输出里的脉冲都该落在
     * 窗口内的这四个点上：
     *
     * <ul>
     *   <li>间隔变短 = 融合期被压缩 = 听见的「快」；变长 = 拖慢；</li>
     *   <li>整排位置挪了 = 融合期播的是这首歌的<b>另一段</b>（跳跃）；</li>
     *   <li>缺一个 = 素材比窗口短，中途掉回内层（那是一次听得见的换声）。</li>
     * </ul>
     *
     * <p>为什么按进度分四块渲染：增益曲线要真的走一遍，否则量不到「最后一块换成 B」之后
     * 的位置——B 的窗口读错位置（比如没按 {@code skipSecs} 读）只有在那一段才露出来。
     */
    @Test
    public void theTransitionBodyKeepsTheOriginalTempo() throws Exception {
        float[] aLeft = new float[4 * RATE];
        float[] bLeft = new float[WINDOW];
        int[] expected = new int[4];
        for (int k = 0; k < expected.length; k++) {
            int rel = (int) Math.round((0.2 + 0.2 * k) * RATE);
            expected[k] = rel;
            click(aLeft, 3 * RATE + rel);
            click(bLeft, rel);
        }
        Path a = TestWavFile.write(dir.resolve("tempo-a.wav"),
                aLeft, new float[aLeft.length], RATE);
        Path b = TestWavFile.write(dir.resolve("tempo-b.wav"),
                bLeft, new float[bLeft.length], RATE);

        VocalAwarePitchTransition t = wrap(stub(0.0), StemSeparators.DSP, true);
        t.prepare(a.toString(), b.toString(), RATE);

        float[] out = new float[WINDOW];
        int written = 0;
        for (int block = 0; block < 4; block++) {
            AudioBuf from = new AudioBuf(ChannelLayout.STEREO, BLOCK);
            AudioBuf to = new AudioBuf(ChannelLayout.STEREO, BLOCK);
            AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, BLOCK);
            from.setFrames(BLOCK);
            to.setFrames(BLOCK);
            int n = t.render(buf, from, to, block / 4.0f);
            assertTrue("第 " + (block + 1) + " 块没渲染出样本（掉回内层了？）", n > 0);
            for (int i = 0; i < n && written < out.length; i++) {
                out[written++] = buf.channel(0).get(i);
            }
        }
        assertEquals("过渡窗口的帧数应当正好是窗口本身", WINDOW, written);

        System.out.println("[vocal-aware] 融合期节奏检查（期望间隔 "
                + Math.round(0.2 * RATE) + " 帧 = 200.0 ms）：");
        double[] at = new double[expected.length];
        for (int k = 0; k < expected.length; k++) {
            at[k] = energyCentroid(out, expected[k], (int) (0.05 * RATE));
            double ms = at[k] / (double) RATE * 1000.0;
            assertTrue("第 " + (k + 1) + " 个脉冲在融合期里不见了（位置 " + ms + " ms）",
                    at[k] > 0.0);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "           第 %d 个脉冲：应在 %.1f ms，实测重心 %.1f ms（差 %+.1f ms）",
                    Integer.valueOf(k + 1), Double.valueOf(expected[k] / (double) RATE * 1000.0),
                    Double.valueOf(ms), Double.valueOf(ms - expected[k] / (double) RATE * 1000.0)));
            assertTrue("第 " + (k + 1) + " 个脉冲整体挪了 "
                            + (ms - expected[k] / (double) RATE * 1000.0)
                            + " ms——融合期播的不是这首歌这一段",
                    Math.abs(ms - expected[k] / (double) RATE * 1000.0) < 12.0);
        }
        for (int k = 0; k + 1 < at.length; k++) {
            double gapMs = (at[k + 1] - at[k]) / (double) RATE * 1000.0;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "           第 %d→%d 个脉冲间隔 %.1f ms（原素材 200.0 ms）",
                    Integer.valueOf(k + 1), Integer.valueOf(k + 2), Double.valueOf(gapMs)));
            assertTrue("融合期里脉冲间隔变成了 " + gapMs + " ms（原素材 200.0 ms）"
                            + "——这一段被加速/减速了",
                    Math.abs(gapMs - 200.0) < 12.0);
        }
    }

    /** 在 {@code at} 处加一个三角脉冲（与对齐判据同一种形状）。 */
    private static void click(float[] x, int at) {
        for (int i = -3; i <= 3; i++) {
            if (at + i >= 0 && at + i < x.length) {
                x[at + i] += 0.6f * (1.0f - Math.abs(i) / 4.0f);
            }
        }
    }

    /**
     * 把**整个窗口**渲染出来（只给对齐那条判据用）。
     *
     * <p>别的判据用 {@link #renderOnce}——它们要的是窗口**最开头**那一瞬的混合比例；
     * 整窗渲染会让后面的帧按更晚的进度混合，读数就不再是"开头"的那个数了。
     */
    private static float[][] renderWholeWindow(VocalAwarePitchTransition t, float progress) {
        AudioBuf from = new AudioBuf(ChannelLayout.STEREO, WINDOW);
        AudioBuf to = new AudioBuf(ChannelLayout.STEREO, WINDOW);
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, WINDOW);
        from.setFrames(WINDOW);
        to.setFrames(WINDOW);
        int n = t.render(out, from, to, progress);
        assertTrue("包装层没渲染出样本：" + n, n > 0);
        float[][] pcm = new float[2][n];
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < n; i++) {
                pcm[ch][i] = out.channel(ch).get(i);
            }
        }
        return pcm;
    }

    /** 以 {@code center} 为中心、±{@code half} 帧范围内的能量重心（帧下标）。 */
    private static double energyCentroid(float[] x, int center, int half) {
        int from = Math.max(0, center - half);
        int to = Math.min(x.length, center + half);
        double num = 0.0;
        double den = 0.0;
        for (int i = from; i < to; i++) {
            double e = (double) x[i] * x[i];
            num += e * i;
            den += e;
        }
        return den > 0.0 ? num / den : -1.0;
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 装上去之前的解码器工厂（{@code install} 返回 void，只能从 Holder 里读）。 */
    private static Decoder.Opener currentOpener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到当前解码器工厂", e);
        }
    }

    private static VocalAwarePitchTransition wrap(Transition inner, int vocalMode, boolean auto) {
        return new VocalAwarePitchTransition(inner, RATE, 2, vocalMode, auto);
    }

    /** 渲染一块，返回 {左声道, 右声道}。 */
    private static float[][] renderOnce(VocalAwarePitchTransition t, float progress) {
        AudioBuf from = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf to = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        // 内层素材不会被用到（本类量的是包装层自己的四条轨），给静音即可。
        from.setFrames(BLOCK);
        to.setFrames(BLOCK);
        int n = t.render(out, from, to, progress);
        assertTrue("包装层没渲染出样本：" + n, n > 0);
        float[][] pcm = new float[2][n];
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < n; i++) {
                pcm[ch][i] = out.channel(ch).get(i);
            }
        }
        return pcm;
    }

    /** 第 k 个整 bin 的频率。 */
    private static double freq(int bin) {
        return (double) bin * (double) RATE / (double) STFT;
    }

    /** 移调 {@link #SHIFT} 个半音之后的频率。 */
    private static double shiftedFreq(int bin) {
        return freq(bin) * Math.pow(2.0, (double) SHIFT / 12.0);
    }

    /** Hann 窗、精确频率上的单点 DFT 幅度。 */
    private static double amplitudeAt(float[] x, double f) {
        int n = x.length;
        double re = 0.0;
        double im = 0.0;
        double wsum = 0.0;
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
            double ph = 2.0 * Math.PI * f * (double) i / (double) RATE;
            re += w * x[i] * Math.cos(ph);
            im -= w * x[i] * Math.sin(ph);
            wsum += w;
        }
        return 2.0 * Math.hypot(re, im) / wsum;
    }

    /**
     * <b>进场曲的人声必须与出场曲的人声走同一条等功率曲线</b>（2026-10-03 修的公式错）。
     *
     * <p>原先 {@code render} 里 B 的人声多乘了一个进度 {@code g}：
     * {@code bvc*inGain*g}，而 A 的人声只有 {@code avc*outGain}。于是 B 的人声在整个窗口里
     * 比她该有的电平低 g 倍（窗口中点 −6 dB），听感是「新歌的人声慢慢鼓出来」。
     * 这与类注释三条明写的契约相反（第 10 行「人声原样交叉」、第 27 行「按等功率交叉把
     * 四条轨混起来」、第 41 行「进场曲一个采样都不动」），所以它是公式错而不是设计。
     *
     * <p>为什么原来的判据没抓到：{@code onlyTheAccompanimentMovesWhileBothVocalsStayPut}
     * 只在 <b>p=0 与 p=1</b> 两个端点上量，而 {@code g} 恰好在这两端等于 0 与 1——
     * 端点看不出来。这一条**在窗口内部取三点**，并把「该是多少」按解析式算出来比对。
     *
     * <p>量法：两条人声在不同的 bin（1012 Hz / 1120 Hz，互不串扰），各自测幅度后取比值，
     * 于是「幅度常数」与「窗和」都被约掉，比的只是**包络**。渲染的 {code p} 在块内随采样推进，
     * 所以预测值按同一个 Hann 窗做加权平均（与 {@code amplitudeAt} 同一套权重）。
     */
    @Test
    public void theIncomingVocalFollowsTheSameEqualPowerCrossfadeAsTheOutgoingOne()
            throws Exception {
        Path a = TestWavFile.write(dir.resolve("a.wav"),
                left(VOCAL_BIN_A, LOW_BIN_A), right(VOCAL_BIN_A, HIGH_BIN_A), RATE);
        Path b = TestWavFile.write(dir.resolve("b.wav"),
                left(VOCAL_BIN_B, LOW_BIN_B), right(VOCAL_BIN_B, HIGH_BIN_B), RATE);
        VocalAwarePitchTransition t = wrap(new StubInner(new PitchPlan(SHIFT, 0.0f)),
                StemSeparators.DSP, true);
        t.prepare(a.toString(), b.toString(), RATE);

        float[] progresses = {0.25f, 0.5f, 0.75f};
        for (float progress : progresses) {
            float[][] out = renderOnce(t, progress);
            double aAmp = amplitudeAt(out[0], freq(VOCAL_BIN_A));
            double bAmp = amplitudeAt(out[0], freq(VOCAL_BIN_B));
            double measured = bAmp / aAmp;
            double symmetric = envelopeMean(progress, out[0].length, true, false)
                    / envelopeMean(progress, out[0].length, false, false);
            double withProgressFactor = envelopeMean(progress, out[0].length, true, true)
                    / envelopeMean(progress, out[0].length, false, false);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[vocal-aware] p=%.2f 人声幅度比 B/A 实测 %.3f｜应为 %.3f（旧的错法会得到 %.3f）",
                    Float.valueOf(progress), Double.valueOf(measured),
                    Double.valueOf(symmetric), Double.valueOf(withProgressFactor)));
            assertEquals("p=" + progress + " 处 B/A 的人声幅度比不等于等功率交叉（实测 "
                            + measured + "，应为 " + symmetric + "）",
                    symmetric, measured, symmetric * 0.15);
            // 与「正确的那个」更近、而不是与「旧的错法」更近——这样在**任何**进度上都成立
            // （越靠窗口末尾 g→1，两种预测越接近，所以不能用一个固定的倍数当门槛）。
            assertTrue("p=" + progress + " 处的实测更接近旧的错法（实测 " + measured
                            + "｜应为 " + symmetric + "｜错法会得到 " + withProgressFactor + "）",
                    Math.abs(measured - symmetric) < Math.abs(measured - withProgressFactor));
        }
    }

    /**
     * <b>四条轨的包络整表</b>：A 伴奏 / A 人声 / B 伴奏 / B 人声，全都走同一条等功率曲线
     * （2026-10-03，用户点头补的）。
     *
     * <p>为什么要有这一条：前面那两条判据各自只钉了一处——一条量「A 的人声没被动」，
     * 另一条量「B 的人声和 A 的人声同曲线」。而这一小段混音（{@code render} 里那四行相加）
     * 已经出过两次错：**两条伴奏都移调**（用户报「加速了」）、**B 的人声多乘一个进度**
     * （用户报「新歌的人声慢慢鼓出来」）。两次都是「四条轨里有一条的系数写错了」——
     * 所以这里一次性把四条都钉住，哪一条的包络偏了都会红。
     *
     * <p>量法（不依赖任何绝对幅度）：以 p=0.75 那一块为基准，量每个信号在若干进度上的
     * <b>幅度比</b>，与解析的等功率包络之比比对。这样移调器自身的增益、素材幅度常数、
     * 窗和都被约掉，比的只是**包络形状**。
     *
     * <p>四条轨各自的频率：A 人声 1012 Hz（居中）、A 低频伴奏移调后 ≈ 109 Hz（左）、
     * B 人声 1120 Hz（居中）、B 低频伴奏 86 Hz（左，B 整条不动）。
     */
    @Test
    public void allFourTracksFollowTheSameEqualPowerEnvelope() throws Exception {
        Path a = TestWavFile.write(dir.resolve("a.wav"),
                left(VOCAL_BIN_A, LOW_BIN_A), right(VOCAL_BIN_A, HIGH_BIN_A), RATE);
        Path b = TestWavFile.write(dir.resolve("b.wav"),
                left(VOCAL_BIN_B, LOW_BIN_B), right(VOCAL_BIN_B, HIGH_BIN_B), RATE);
        final float reference = 0.75f;
        float[] progresses = {0.2f, 0.35f, 0.5f, 0.65f, reference};
        // 每个信号：用哪个声道、哪个频率、走哪条包络（true = 进场侧 sin）
        String[] names = {"A 伴奏(低频,已移调)", "A 人声", "B 伴奏(低频,不动)", "B 人声"};
        boolean[] incoming = {false, false, true, true};
        double[][] measured = new double[names.length][progresses.length];
        int n = 0;
        for (int pi = 0; pi < progresses.length; pi++) {
            // **每个进度都要一份全新的实例**：`render` 从自己的 cursor 往后推进素材，
            // 同一个实例上连着量好几个块会把 1 秒素材用完（用完就退回内层 = 静音），
            // 量出来的就不是包络了。第一版就是这么错的，量到的 B 侧全是残料。
            VocalAwarePitchTransition fresh = wrap(new StubInner(new PitchPlan(SHIFT, 0.0f)),
                    StemSeparators.DSP, true);
            fresh.prepare(a.toString(), b.toString(), RATE);
            float[][] out = renderOnce(fresh, progresses[pi]);
            n = out[0].length;
            double[] amp = {
                // A 的伴奏量**高频那一条**（bin 400 → 移调后 ≈9.4 kHz）：低频 bin 4 在
                // 颗粒式变调器里一个粒只有约 3 个周期，移调后能量摊到邻近 bin 上、
                // 幅度随粒相位起伏（这个测试类开头就记过这条测量限制）。
                // 用高频那条只是为了**量得稳**，不是因为它更该被钉。
                amplitudeAt(out[1], shiftedFreq(HIGH_BIN_A)),
                amplitudeAt(out[0], freq(VOCAL_BIN_A)),
                amplitudeAt(out[0], freq(LOW_BIN_B)),
                amplitudeAt(out[0], freq(VOCAL_BIN_B)),
            };
            for (int k = 0; k < names.length; k++) {
                measured[k][pi] = amp[k];
            }
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[vocal-aware] p=%.2f 幅度 A伴奏 %.4f｜A人声 %.4f｜B伴奏 %.4f｜B人声 %.4f",
                    Float.valueOf(progresses[pi]), Double.valueOf(amp[0]), Double.valueOf(amp[1]),
                    Double.valueOf(amp[2]), Double.valueOf(amp[3])));
            if (n <= 0) {
                throw new AssertionError("渲染没产出样本");
            }
        }
        for (int k = 0; k < names.length; k++) {
            double refMeasured = measured[k][progresses.length - 1];
            assertTrue(names[k] + " 在 p=" + reference + " 处几乎没有能量（" + refMeasured + "）",
                    refMeasured > 0.01);
            for (int pi = 0; pi < progresses.length - 1; pi++) {
                double expected = envelopeMean(progresses[pi], n, incoming[k], false)
                        / envelopeMean(reference, n, incoming[k], false);
                double actual = measured[k][pi] / refMeasured;
                assertEquals(names[k] + " 在 p=" + progresses[pi] + " 的包络偏离等功率曲线"
                                + "（实测比值 " + actual + "，应为 " + expected + "）",
                        expected, actual, expected * 0.2);
            }
        }
    }

    /**
     * 块内包络的 Hann 加权平均（与 {@link #amplitudeAt} 的权重一致）。
     *
     * @param incoming true = 进场侧（{@code sin}），false = 出场侧（{@code cos}）
     * @param extraG   true = 乘上那个**错的**进度因子（复现旧行为用）
     */
    private static double envelopeMean(float progress, int n, boolean incoming, boolean extraG) {
        double perFrame = 1.0 / (double) WINDOW;
        double sum = 0.0;
        double wsum = 0.0;
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
            double p = Math.min(1.0, (double) progress + (double) i * perFrame);
            double g = p * p * (3.0 - 2.0 * p);          // Automix.smoothstep(0,1,p)
            double env = incoming ? Math.sin(g * Math.PI / 2.0) : Math.cos(g * Math.PI / 2.0);
            if (extraG && incoming) {
                env *= g;
            }
            sum += w * env;
            wsum += w;
        }
        return sum / wsum;
    }

    private static void report(String what, double f, double fShifted,
                               double atOriginal, double atShifted, double expected) {
        System.out.println(String.format(java.util.Locale.ROOT,
                "[vocal-aware] %-12s 原频率 %.1f Hz 幅度 %.4f（应有 %.4f）｜移调后 %.1f Hz 幅度 %.4f",
                what, f, atOriginal, expected, fShifted, atShifted));
    }

    private static float[] left(int vocalBin, int lowBin) {
        return stereo(vocalBin, lowBin, null);
    }

    private static float[] right(int vocalBin, int highBin) {
        return stereo(vocalBin, null, highBin);
    }

    /** 正中（左右相同）的人声 + 单侧伴奏。 */
    private static float[] stereo(int vocalBin, Integer lowBin, Integer highBin) {
        float[] v = new float[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            double t = (double) i / (double) RATE;
            double x = VOCAL_AMP * Math.sin(2.0 * Math.PI * freq(vocalBin) * t);
            if (lowBin != null) {
                x += LOW_AMP * Math.sin(2.0 * Math.PI * freq(lowBin) * t);
            }
            if (highBin != null) {
                x += HIGH_AMP * Math.sin(2.0 * Math.PI * freq(highBin) * t);
            }
            v[i] = (float) x;
        }
        return v;
    }

    /** 一个只提供「计划」的假接法：窗口、点位与移调意图都由测试指定。 */
    private static final class StubInner implements Transition {

        private final PitchPlan wanted;
        private final double outroTailSecs;

        StubInner(PitchPlan wanted) {
            this(wanted, 0.0);
        }

        StubInner(PitchPlan wanted, double outroTailSecs) {
            this.wanted = wanted;
            this.outroTailSecs = outroTailSecs;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return WINDOW;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            // 包装层有素材时不会走到这里；走到了说明降级，返回静音便于发现。
            out.setFrames(0);
            return 0;
        }

        @Override
        public String name() {
            return "测试用假接法";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int sampleRate) {
            return new TransitionPlan(0.0, wanted, 0.0f, "测试用计划", outroTailSecs);
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int sampleRate,
                                      Map<String, String> outroHeaders,
                                      Map<String, String> introHeaders) {
            return prepare(outro, intro, sampleRate);
        }

        @Override
        public PitchPlan pitchAt(float progress) {
            return wanted;
        }
    }

}
