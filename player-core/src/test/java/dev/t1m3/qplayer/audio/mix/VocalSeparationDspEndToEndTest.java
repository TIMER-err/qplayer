package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.model.InferException;
import dev.t1m3.qplayer.audio.model.ModelAvailability;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import dev.t1m3.qplayer.audio.model.StemModels;
import dev.t1m3.qplayer.audio.model.StemSeparator;
import dev.t1m3.qplayer.audio.stem.DspStemSeparator;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>真 DSP 的端到端</b>：{@link VocalSeparationTransition} + 真的 {@link DspStemSeparator}，
 * 素材是合成出来的（正中中频人声 + 两侧低频/高频伴奏），走的是一条完整的链——
 * 写 WAV → {@code prepare} 里真读盘、真分离 → 渲染时逐样本乘包络。
 *
 * <h2>为什么需要这一条（{@code VocalSeparationTransitionTest} 不够）</h2>
 *
 * <p>那一份全程用假的 {@code StemSeparator}（常数轨），它钉的是「拿到分离结果之后怎么用」
 * 与「拿不到的时候怎么办」。唯一碰到真 {@code DspStemSeparator} 的地方只有
 * 「{@code forMode(DSP)} 会构造出它」这一条断言——<b>它的 {@code separate()} 从来没在
 * 完整链上跑过</b>。于是下面这些事谁都还没验过：
 *
 * <ul>
 *   <li>真分离结果里的<b>伴奏轨</b>到底有没有把那路正中中频人声压掉（替身给的常数轨
 *       只能证明「乘了包络」，证明不了「压掉了」）；</li>
 *   <li>「伴奏 + 人声」这两条真轨的<b>重建精度</b>——窗口结尾人声回到满时，
 *       输出与「没套这一层」差多少。差多少是这一档能不能用的核心数字，
 *       而它与替身无关；</li>
 *   <li>真 DSP 在<b>输出采样率、输出声道数、窗口长度都对</b>的条件下能不能跑
 *       （频带边界是按 {@code sr} 现算的，链上给错率不会有异常，只会安静地做错事）。</li>
 * </ul>
 *
 * <h2>素材为什么长这样</h2>
 *
 * <p>DSP 做的是「逐 bin 拆 center/side，只衰减人声带内的 center」。要让判据有意义，
 * 素材必须把这三件事分开：
 *
 * <ul>
 *   <li><b>正中中频人声</b>：左右完全相同的 1012 Hz 正弦（bin 47）。1012 Hz 落在
 *       完全抑制带（280 Hz–4 kHz）里，所以「该被压掉」是有定义的；</li>
 *   <li><b>低频伴奏</b>：只放左声道 86 Hz（bin 4）。它落在<b>保留带</b>（&lt; 100 Hz），
 *       所以「伴奏必须留着」也是有定义的——拿它一起被压掉的话，用户听到的伴奏就没了；</li>
 *   <li><b>高频伴奏</b>：只放右声道 8613 Hz（bin 400），落在另一侧保留带（&gt; 5.2 kHz）。</li>
 * </ul>
 *
 * <p><b>三个频率都取成 2048 点 FFT 的整 bin</b>（{@code 44100 / 2048 = 21.533 Hz} 的整数倍），
 * 因为 DSP 的增益是<b>逐 bin</b> 的：整 bin 的纯音能量只落在它自己那一两个 bin 上，
 * 于是「这个音该被压到 0 / 该原样保留」是精确的，判据不必迁就频谱泄漏。
 * 非整 bin 的音会把能量漏到带边斜坡上，压掉的量与泄漏形状有关，判据就变成一笔糊涂账。
 *
 * <h2>真 DSP 为什么可以在单测里真跑（耗时实测）</h2>
 *
 * <p>它是纯 Java 的频域改写：本类里 {@code realDspIsCheapEnoughToRunForReal} 实测
 * <b>44.1 kHz 立体声 1 秒素材约 18 ms、10 秒素材 184 ms（约 54 倍实时）</b>，
 * 与 {@link StemSeparators#DSP} 注释里那份「10 秒 215 ms」是同一个量级。
 * 所以这一条能真跑而不是再搭一个替身；它同时也是「过渡窗口那几秒只有几十毫秒」的活证据
 * ——换成 AI 那一档（秒级 + 上百兆权重）这条测试就必须是替身。
 *
 * <h2>数值判据一览（本机实测，方便后人一眼看出「过没过、离边缘多远」）</h2>
 *
 * <table border="1">
 *   <caption>实测值 vs 判据</caption>
 *   <tr><th>量</th><th>实测</th><th>判据</th><th>判据的来源</th></tr>
 *   <tr><td>前半段（0–0.1）正中 1012 Hz 幅度</td><td>0.00538（参照 0.39997）</td>
 *       <td>{@code ≤ 0.4 × g(0.1) + 1e-3 = 0.0122}，且压掉 ≥ 20 倍</td>
 *       <td>包络在 [0, 0.1) 的解析上界 g(0.1) = 0.028</td></tr>
 *   <tr><td>同段低频伴奏（左）/ 高频伴奏（右）保留率</td><td>99.963% / 99.99998%</td>
 *       <td>≥ 99%</td>
 *       <td>两个音是整 bin，增益精确为 1；只有 Hann 窗边缘 bin 5（g = 0.9955）漏 ~0.1%</td></tr>
 *   <tr><td>窗口结尾（progress = 1）与「伴奏 + 人声」</td><td>逐位相同</td>
 *       <td>0 个样点不等</td><td>渲染路径只做一次 float 乘加，包络在 t = 1 处逐位是 1.0f</td></tr>
 *   <tr><td>窗口结尾与「不套这一层」的最大偏差</td><td>1.49e-8</td><td>{@code ≤ 3e-7}</td>
 *       <td>分离器自身的重建残差（float 舍入上界 1.8e-7）；见 {@code RECON_TOL}</td></tr>
 * </table>
 */
public class VocalSeparationDspEndToEndTest {

    /** 输出采样率。取 44.1 kHz 是为了让频带边界（100 Hz–5.2 kHz）两侧都有 bin 可用。 */
    private static final int RATE = 44_100;

    /** 窗口 = 1 秒。够长（远大于 STFT 的 2048 帧），又短到这条测试跑得快。 */
    private static final int WINDOW = 44_100;

    /** 一块 0.1 秒（编排器真实块长是 4096，这里取窗口的十分之一，让「前半段」好切）。 */
    private static final int BLOCK = 4_410;

    /** DSP 的 STFT 长度（见 {@link DspStemSeparator}）：整 bin 的间距就是 RATE / 2048。 */
    private static final int STFT = 2_048;

    /** 正中中频人声：bin 47 = 1012.06 Hz，在完全抑制带里。 */
    private static final int VOCAL_BIN = 47;

    /** 低频伴奏：bin 4 = 86.13 Hz，在保留带（< 100 Hz）里。 */
    private static final int LOW_BIN = 4;

    /** 高频伴奏：bin 400 = 8613.28 Hz，在保留带（> 5.2 kHz）里。 */
    private static final int HIGH_BIN = 400;

    private static final float VOCAL_AMP = 0.4f;
    private static final float LOW_AMP = 0.25f;
    private static final float HIGH_AMP = 0.25f;

    /**
     * 「伴奏 + 人声」与「原信号」允许差多少。
     *
     * <p><b>容差从哪来</b>（不是试出来的，是算出来的）：DSP 的两条轨各自是
     * {@code clampUnit(double)} 的结果，{@code |v| ≤ 1} 时 float 的 ulp 是
     * {@code 2^-24 ≈ 5.96e-8}，两条轨各舍入一次、再在 float 域相加舍入一次，
     * 上界约 {@code 3 × 5.96e-8 ≈ 1.8e-7}；双精度域里「伴奏谱 + 人声谱 = 原谱」
     * 的残差按 {@code DspStemSeparator} 类注释是 {@code ~1e-7} 以下。
     * 取 {@code 3e-7} 是把这个上界留一倍余量——而测试<b>先把实际残差量出来</b>
     * （见 {@code reconstructionError}），再拿同一个数去比，所以它不是拍脑袋的松紧带。
     *
     * <p><b>本机实测（这份合成素材、44.1 kHz、1 秒窗口）</b>：重建残差
     * {@code 1.49e-8}，而「窗口结尾套包装 vs 不套包装」的最大偏差是同一个数
     * {@code 1.49e-8}——也就是结尾那一处除了分离器自身的残差之外<b>一个比特都没多差</b>。
     * 这比上面那个 1.8e-7 的上界还好一个数量级（上界是按最坏情况的舍入累积算的，
     * 实际这份素材的幅度只有 0.65，够不到满刻度），所以 3e-7 现在有 20 倍余量。
     */
    private static final double RECON_TOL = 3e-7;

    /**
     * 测量用的容差：单频幅度是用 Hann 窗在<b>精确音高</b>上做单点 DFT 量出来的，
     * 窗的主瓣中心正落在音上，误差只剩 {@code 2f} 处的镜像项（实测 &lt; 1e-6 相对）。
     */
    private static final double MEASURE_TOL = 1e-3;

    private Path intro;

    @Before
    public void installSeams() {
        // 解码器替身：这一层要按时间位置读进场曲那一段，产品路径上它由宿主提供
        Decoder.install(new WavOpener());
        StemModels.uninstall();
        // 关键：**回到真 DSP**（默认就是它）。这条测试要的就是真实现，装替身等于白写。
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    @After
    public void uninstallSeams() throws IOException {
        StemModels.uninstall();
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
        if (intro != null) {
            deleteRecursively(intro);
            intro = null;
        }
    }

    // ==================================================================
    // 一、端到端：真 DSP 走进整条链
    // ==================================================================

    /**
     * 一条测试把三件事一起钉住：<b>前半段人声被压掉、伴奏留着、note 如实写着 DSP</b>。
     *
     * <p>判据全部是「与不套包装的参照比」——参照就是同一个接法、同一份素材、不套这一层，
     * 于是输出**逐样本等于素材本身**，任何绝对值都不用假设。
     */
    @Test
    public void theRealDspSeparatesTheCenterVocalOutOfTheWindow() throws IOException {
        float[] left = new float[WINDOW];
        float[] right = new float[WINDOW];
        synthesize(left, right);
        // 写进文件的是原始信号（16 bit 量化由写侧做），而 `to` 用的是「写进去再读出来
        // 会得到的那一份」——两边必须逐样本一致，否则量到的是量化差而不是分离残差。
        intro = writeMaterialWav("dsp-e2e", left, right);
        float[] matL = quantize(left);
        float[] matR = quantize(right);

        CountingAiProvider ai = new CountingAiProvider();
        StemModels.install(ai);

        FixedWindow inner = new FixedWindow(WINDOW);
        VocalSeparationTransition t = new VocalSeparationTransition(inner, RATE, 2, StemSeparators.DSP);
        TransitionPlan plan = t.prepare("outro.wav", intro.toString(), RATE);

        // ---- ③ note 里如实写着用的是 DSP（不是模型、也不是「分不开」） ----
        assertTrue("真 DSP 在链上必须分离成功", t.separatedVocals());
        assertEquals("这一档给人看的名字", "DSP", StemSeparators.label(StemSeparators.DSP));
        assertTrue("note 要点名用的是哪一档：" + plan.note,
                plan.note.contains("已去人声（" + StemSeparators.label(StemSeparators.DSP)));
        assertFalse("note 里不该出现模型的名字——那等于把档说错了：" + plan.note,
                plan.note.contains(StemModels.MODEL_NAME));
        assertFalse("DSP 那一档不许碰模型：" + plan.note, plan.note.contains("未去人声"));
        assertEquals("DSP 那一档一次都不该去开模型", 0, ai.opens);
        assertEquals("素材要覆盖整个窗口", WINDOW, t.coveredFrames());

        // ---- 渲染：套包装 vs 不套包装（同一个接法、同一份素材） ----
        float[][] wrapped = renderWindow(t, matL, matR, -1f);
        float[][] reference = renderWindow(new FixedWindow(WINDOW), matL, matR, -1f);

        // 夹具自检：不套包装时输出就是素材本身（参照物本身要成立）
        for (int i = 0; i < WINDOW; i += 997) {
            assertEquals("参照物必须就是素材本身", Float.floatToIntBits(matL[i]),
                    Float.floatToIntBits(reference[0][i]));
        }

        // ---- ① 窗口前半段人声确实被压掉 ----
        //
        // 判据的容差来源：包络是 smoothstep，g(0.25) = 0.15625，所以「前半段」必须收紧到
        // 包络本身可以忽略的那一小段——取第一个块 [0, 0.1)，那里 g ≤ g(0.1) = 0.028。
        // 于是输出的中频人声幅度有一个**解析上界**：
        //     0.4 × g(0.1) + 测量误差 + DSP 自己的残差 = 0.0112 + 0.001 + ~0
        // 拿它当判据，而不是「比参照低」——后者在压掉 1% 时也成立。
        //
        // 本机实测：参照 0.39997（= 素材给的 0.4 减去 16 bit 量化那 3e-5），
        // 套上包装后 0.00538 → **压掉 74 倍（约 37 dB）**，离上面那条上界还有 2.3 倍余量。
        int seg = BLOCK;
        double vocalRef = amplitudeAt(center(reference), 0, seg, hz(VOCAL_BIN));
        double vocalGot = amplitudeAt(center(wrapped), 0, seg, hz(VOCAL_BIN));
        double envelopeBound = VOCAL_AMP * smoothstep01(0.1);

        assertEquals("夹具自检：参考段的正中人声幅度应当就是素材给的 " + VOCAL_AMP,
                VOCAL_AMP, vocalRef, MEASURE_TOL);
        assertTrue("前半段人声没有被压掉：测到 " + vocalGot + "，解析上界是 "
                        + (envelopeBound + MEASURE_TOL) + "（= 0.4×g(0.1) + 测量余量）",
                vocalGot <= envelopeBound + MEASURE_TOL);
        assertTrue("压掉的量太少（要求至少 20 倍 = 26 dB）：参照 " + vocalRef + "，实际 " + vocalGot,
                vocalGot * 20.0 <= vocalRef);

        // 「接近只有伴奏」的另一半：两条伴奏都必须在，而且几乎原样
        double lowRef = amplitudeAt(reference[0], 0, seg, hz(LOW_BIN));
        double lowGot = amplitudeAt(wrapped[0], 0, seg, hz(LOW_BIN));
        double highRef = amplitudeAt(reference[1], 0, seg, hz(HIGH_BIN));
        double highGot = amplitudeAt(wrapped[1], 0, seg, hz(HIGH_BIN));
        assertTrue("夹具自检：低频伴奏在参照里要有 " + LOW_AMP, lowRef > LOW_AMP * 0.99);
        assertTrue("夹具自检：高频伴奏在参照里要有 " + HIGH_AMP, highRef > HIGH_AMP * 0.99);
        // 容差来源：这两个音是整 bin，增益在它们落的那两三个 bin 上是精确的 1；
        // 唯一会漏一点的是 Hann 窗边缘那一两个 bin（bin 5 = 107.7 Hz 处 g = 0.9955），
        // 算下来损失 ~0.1%。留 1%（10 倍余量），既不是「不许动」也不是松到看不出问题。
        // 本机实测：低频伴奏保留 99.963%、高频伴奏保留 99.99998%。
        assertTrue("低频伴奏被削了：" + lowGot + " / " + lowRef, lowGot >= lowRef * 0.99);
        assertTrue("高频伴奏被削了：" + highGot + " / " + highRef, highGot >= highRef * 0.99);
        assertTrue("前半段整段都安静下来了——那不是「只剩伴奏」，是出事了",
                peak(wrapped[0], 0, seg) > 0.2f);

        // ---- ② 窗口结尾人声回来了 ----
        //
        // 逐位相同在这里**不可能**成立，原因不是实现有偏差，而是 DSP 这一档本身有损：
        // 输出在结尾是「伴奏 + 人声 × 1」，而「伴奏 + 人声」与「原信号」之间本来就差一个
        // 重建残差（频域改写的浮点残差）。所以这一条落成两步：
        //   (a) 逐位断言输出 == 本地用同一个真 DSP 算出来的「伴奏 + 人声」（渲染路径没有额外偏差）；
        //   (b) 与「不套包装的同一接法」比，容差就是那个残差本身（先量再比）。
        VocalSeparationTransition tail =
                new VocalSeparationTransition(new FixedWindow(WINDOW), RATE, 2, StemSeparators.DSP);
        tail.prepare("outro.wav", intro.toString(), RATE);
        float[][] atOne = renderWindow(tail, matL, matR, 1.0f);

        StemSeparator.StemSet stems = new DspStemSeparator().separate(matL, matR, RATE);
        int bitMismatches = 0;
        float worstRender = 0.0f;
        for (int i = 0; i < WINDOW; i++) {
            float want = stems.instrumentalLeft[i] + stems.vocalsLeft[i];
            if (Float.floatToIntBits(want) != Float.floatToIntBits(atOne[0][i])) {
                bitMismatches++;
                worstRender = Math.max(worstRender, Math.abs(want - atOne[0][i]));
            }
        }
        assertEquals("进度 1.0 时输出必须逐位等于「伴奏 + 人声 × 1」（不等的样点有 "
                        + bitMismatches + " 个，最大一处差 " + worstRender
                        + "）——渲染路径上不该有任何额外偏差",
                0, bitMismatches);

        double recon = reconstructionError(stems, matL, matR);
        assertTrue("真 DSP 在这一份素材上的重建残差 " + recon + " 超过了推导出的上界 "
                + RECON_TOL + "（容差来源见 RECON_TOL 注释）", recon <= RECON_TOL);

        double worstRef = 0.0;
        for (int i = 0; i < WINDOW; i++) {
            worstRef = Math.max(worstRef, Math.abs((double) atOne[0][i] - (double) matL[i]));
        }
        assertTrue("窗口结尾与「不套这一层」的差 " + worstRef + " 超过了重建残差上界 "
                + RECON_TOL + "：结尾不是「近似恢复」，而是走了别的路", worstRef <= RECON_TOL);

        // 而且「结尾恢复」在编排器的真实节奏下也是可见的：最后一块的人声幅度回到满
        double vocalTail = amplitudeAt(center(wrapped), WINDOW - BLOCK, BLOCK, hz(VOCAL_BIN));
        // 最后一块里 g 从 g(0.9) = 0.972 升到 1，平均值 = ∫₀.₉¹ t²(3−2t)dt / 0.1 = 0.9905
        // → 期望幅度 0.4 × 0.9905 ≈ 0.396；取 0.39 为界（比平均值的下界还低一点）。
        assertTrue("窗口最后一块的人声没回来：" + vocalTail, vocalTail >= VOCAL_AMP * 0.975);
    }

    /**
     * 真 DSP 便宜到什么程度——<b>这就是这条测试能拿真实现跑的理由</b>。
     *
     * <p>耗时不写死（机器有别），只钉住量级：10 秒素材必须远快于实时，否则
     * 「窗口那几秒交给 {@code prepare}」这条设计就不成立。上界取得很松（5 倍实时），
     * 因为它的用途是「别把秒级的模型那一档混进来」，不是性能回归门。
     *
     * <p>本机实测（JDK 21、44.1 kHz 立体声）：<b>1 秒素材 ~18 ms、10 秒素材 184 ms
     * （约 54 倍实时）</b>，与 {@link StemSeparators#DSP} 注释里那份「10 秒 215 ms」同一量级。
     * 对照 AI 那一档的实测（3.9 s 窗口约 5.0 s、峰值内存 1.2 GB），差三个数量级——
     * 「真 DSP 可以在单测里真跑、真模型必须用替身」这句话就是这么来的。
     */
    @Test
    public void realDspIsCheapEnoughToRunForReal() {
        float[] left = new float[10 * RATE];
        float[] right = new float[10 * RATE];
        synthesize(left, right);
        DspStemSeparator real = new DspStemSeparator();

        // 先跑一段把 JIT 预热掉，否则第一个数里有一半是解释器的时间
        float[] warmL = new float[RATE];
        float[] warmR = new float[RATE];
        synthesize(warmL, warmR);
        real.separate(warmL, warmR, RATE);

        long t1 = System.nanoTime();
        StemSeparator.StemSet one = real.separate(warmL, warmR, RATE);
        double secs1 = (System.nanoTime() - t1) / 1e9;
        assertNotNull(one);
        assertEquals("分离结果长度必须等于输入", warmL.length, one.frames());
        assertTrue("1 秒素材花了 " + secs1 + " s——真 DSP 不该是这个量级", secs1 < 0.5);

        long t0 = System.nanoTime();
        StemSeparator.StemSet set = real.separate(left, right, RATE);
        double secs = (System.nanoTime() - t0) / 1e9;
        assertNotNull(set);
        assertEquals("分离结果长度必须等于输入", left.length, set.frames());
        assertTrue("10 秒素材花了 " + secs + " s——真 DSP 不该是这个量级",
                secs < 2.0);
    }

    // ==================================================================
    // 合成素材与测量
    // ==================================================================

    /** {@code bin} 对应的频率（整 bin ⇒ 频率精确落在 FFT 的格点上）。 */
    private static double hz(int bin) {
        return (double) bin * (double) RATE / (double) STFT;
    }

    /**
     * 合成素材：左 = 正中中频人声 + 低频伴奏，右 = 正中中频人声 + 高频伴奏。
     *
     * <p>人声在左右<b>完全相同</b>（摆在正中间），两条伴奏各只占一边——所以既满足
     * 「正中的人声」，也满足「左右可区分」（左右两个声道拿到的伴奏频率不同，
     * 判定「伴奏还在不在」时能分别量）。
     */
    private static void synthesize(float[] left, float[] right) {
        double fv = hz(VOCAL_BIN);
        double fl = hz(LOW_BIN);
        double fh = hz(HIGH_BIN);
        for (int i = 0; i < left.length; i++) {
            double t = (double) i / (double) RATE;
            double vocal = VOCAL_AMP * Math.sin(2.0 * Math.PI * fv * t);
            left[i] = (float) (vocal + LOW_AMP * Math.sin(2.0 * Math.PI * fl * t));
            right[i] = (float) (vocal + HIGH_AMP * Math.sin(2.0 * Math.PI * fh * t));
        }
    }

    /**
     * 把一条信号变成「写进 WAV 再读回来会得到的样子」。
     *
     * <p>为什么必须做这一步：{@code prepare} 读的是<b>文件</b>，而 {@code to}
     * 是编排器从 deck 拿的。这条测试要让两边<b>逐样本一致</b>（否则「与不套包装比」
     * 量到的会是量化差而不是分离残差），所以渲染时喂进去的那份素材也要按同一套 16 bit
     * 量化规则算一遍——{@code (short)(v × 32767)} 落在写侧，{@code / 32768} 落在读侧，
     * 两处都与下面的解码器替身逐字一致。
     *
     * <p><b>只许量化一次</b>：传进来的必须是<b>原始</b>信号（写侧自己会量化），
     * 已经量化过的数组再走一遍写侧就会掉一个 LSB（{@code (short)((s/32768)×32767) = s−1}）——
     * 1 LSB 在 16 bit 下是 3.05e-5，比这里要量的重建残差大三个数量级，
     * 会把这个测试变成在量量化误差。
     */
    private static float[] quantize(float[] x) {
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            float v = Math.max(-1.0f, Math.min(1.0f, x[i]));
            out[i] = (float) (short) (v * 32767.0f) / 32768.0f;
        }
        return out;
    }

    /** 「伴奏 + 人声」与原信号在整段上的最大偏差（见 {@link #RECON_TOL}）。 */
    private static double reconstructionError(StemSeparator.StemSet set, float[] left, float[] right) {
        double worst = 0.0;
        for (int i = 0; i < left.length; i++) {
            worst = Math.max(worst, Math.abs((double) (set.instrumentalLeft[i] + set.vocalsLeft[i])
                    - (double) left[i]));
            worst = Math.max(worst, Math.abs((double) (set.instrumentalRight[i] + set.vocalsRight[i])
                    - (double) right[i]));
        }
        return worst;
    }

    /** 两声道的中点（正中分量的口径：{@code (L+R)/2}）。 */
    private static float[] center(float[][] stereo) {
        float[] out = new float[stereo[0].length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (stereo[0][i] + stereo[1][i]) * 0.5f;
        }
        return out;
    }

    /**
     * {@code x[from, from+len)} 里 {@code hz} 那个分量的<b>幅度</b>。
     *
     * <p>Hann 窗 + 在精确音高上做单点 DFT，再按窗和归一化：因为音高正好落在分析频率上，
     * 窗的主瓣中心就在那儿，估计值等于真幅度（只剩 {@code 2f} 处那个镜像项，
     * 在这几个频率上实测 &lt; 1e-6 相对）。用不了「数整周期」那招——这几个频率在
     * 4410 帧的段里都不是整周期数。
     */
    private static double amplitudeAt(float[] x, int from, int len, double hz) {
        double wsum = 0.0;
        double re = 0.0;
        double im = 0.0;
        for (int i = 0; i < len; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (len - 1));
            double a = 2.0 * Math.PI * hz * (double) (from + i) / (double) RATE;
            double v = x[from + i];
            wsum += w;
            re += w * v * Math.cos(a);
            im -= w * v * Math.sin(a);
        }
        return 2.0 * Math.sqrt(re * re + im * im) / wsum;
    }

    /** 与 {@code Automix.smoothstep} 同一条曲线（这里自己算一遍，判据不依赖被测代码）。 */
    private static double smoothstep01(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }

    private static float peak(float[] x, int from, int to) {
        float peak = 0.0f;
        for (int i = from; i < to; i++) {
            peak = Math.max(peak, Math.abs(x[i]));
        }
        return peak;
    }

    // ==================================================================
    // 渲染（按编排器的节奏）
    // ==================================================================

    /**
     * 把整个窗口渲染一遍，返回 {@code {左, 右}}。
     *
     * @param fixedProgress {@code < 0} = 按编排器的节奏（每块 {@code progress = 块起点/窗口}）；
     *                      否则每一块都用这个进度调 {@code render}
     */
    private static float[][] renderWindow(Transition t, float[] matL, float[] matR,
                                          float fixedProgress) {
        int block = fixedProgress < 0f ? BLOCK : WINDOW;
        AudioBuf from = new AudioBuf(ChannelLayout.of(2), block);
        AudioBuf to = new AudioBuf(ChannelLayout.of(2), block);
        AudioBuf out = new AudioBuf(ChannelLayout.of(2), block);
        float[] outL = new float[WINDOW];
        float[] outR = new float[WINDOW];
        for (int at = 0; at < WINDOW; at += block) {
            int n = Math.min(block, WINDOW - at);
            from.setFrames(n);
            to.setFrames(n);
            FloatBuffer fl = from.channelWriter(0);
            FloatBuffer fr = from.channelWriter(1);
            FloatBuffer tl = to.channelWriter(0);
            FloatBuffer tr = to.channelWriter(1);
            for (int i = 0; i < n; i++) {
                // `from` 在这个假接法里没人读，但仍然填零——不填的话读到的是未初始化的缓冲
                fl.put(i, 0.0f);
                fr.put(i, 0.0f);
                tl.put(i, matL[at + i]);
                tr.put(i, matR[at + i]);
            }
            out.clear();
            float progress = fixedProgress < 0f ? (float) at / (float) WINDOW : fixedProgress;
            t.render(out, from, to, progress);
            assertEquals("这一块该交出 " + n + " 帧", n, out.frames());
            for (int i = 0; i < n; i++) {
                outL[at + i] = out.channel(0).get(i);
                outR[at + i] = out.channel(1).get(i);
            }
        }
        return new float[][]{outL, outR};
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 一个窗口可指定的假接法（与 {@code VocalSeparationTransitionTest} 里那份同形）。 */
    private static final class FixedWindow implements Transition {

        private final long window;

        private FixedWindow(long window) {
            this.window = window;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            int n = Math.min(to.frames(), out.capacityFrames());
            for (int ch = 0; ch < out.channels(); ch++) {
                FloatBuffer o = out.channelWriter(ch);
                FloatBuffer t = to.channel(Math.min(ch, to.channels() - 1));
                for (int i = 0; i < n; i++) {
                    o.put(i, t.get(i));
                }
            }
            out.setFrames(n);
            return n;
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
        public TransitionPlan prepare(String outro, String intro, int rate) {
            return TransitionPlan.NONE;
        }

        @Override
        public PitchPlan pitchAt(float progress) {
            return PitchPlan.ZERO;
        }
    }

    /** 只数「模型那一档被打开过几次」的替身：真被用到就会吼一声，好定位。 */
    private static final class CountingAiProvider implements StemModels.Opener {

        private int opens;

        @Override
        public StemSeparator open() {
            opens++;
            throw InferException.load("测试替身：DSP 那一档不该去开模型");
        }
    }

    // ==================================================================
    // 素材落盘：只认 16 bit PCM WAV 的解码器替身
    // （与 VocalSeparationTransitionTest / AiMixTest 里那份同一套造法；
    //   那边是私有的，按本仓库既有惯例在这里复制一份，好让这条测试自成一档。）
    // ==================================================================

    private static Path writeMaterialWav(String name, float[] left, float[] right)
            throws IOException {
        Path dir = Files.createTempDirectory("qplayer-dsp-e2e-" + name);
        Path path = dir.resolve(name + ".wav");
        writeWav(path, left, right, RATE);
        return path;
    }

    private static void writeWav(Path path, float[] left, float[] right, int rate)
            throws IOException {
        int frames = left.length;
        int dataLen = frames * 2 * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataLen);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 2);
        buf.putInt(rate);
        buf.putInt(rate * 2 * 2);
        buf.putShort((short) 4);
        buf.putShort((short) 16);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataLen);
        for (int i = 0; i < frames; i++) {
            buf.putShort((short) (Math.max(-1.0f, Math.min(1.0f, left[i])) * 32767.0f));
            buf.putShort((short) (Math.max(-1.0f, Math.min(1.0f, right[i])) * 32767.0f));
        }
        Files.write(path, buf.array());
    }

    private static void deleteRecursively(Path path) {
        File parent = path.toFile().getParentFile();
        if (parent != null) {
            File[] kids = parent.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    k.delete();
                }
            }
            parent.delete();
        }
    }

    /** 测试用的工厂：永远返回 WAV 替身（文件不存在时抛 {@link AudioException}）。 */
    private static final class WavOpener implements Decoder.Opener {
        @Override
        public Decoder open(String source, Map<String, String> headers) {
            return WavDecoder.open(source);
        }
    }

    /** 只认 16 bit PCM WAV 的最小解码器替身（测试用，不是产品代码）。 */
    private static final class WavDecoder implements Decoder {

        private final byte[] file;
        private final int dataOff;
        private final int dataLen;
        private final int rate;
        private final int channels;
        private final int bytesPerSample;
        private final int frames;
        private int pos;

        private WavDecoder(byte[] file, int dataOff, int dataLen, int rate, int channels,
                           int bytesPerSample) {
            this.file = file;
            this.dataOff = dataOff;
            this.dataLen = dataLen;
            this.rate = rate;
            this.channels = channels;
            this.bytesPerSample = bytesPerSample;
            this.frames = dataLen / (channels * bytesPerSample);
        }

        static WavDecoder open(String source) {
            Path path;
            try {
                path = Paths.get(source);
            } catch (InvalidPathException e) {
                throw AudioException.config("打不开音源：" + source);
            }
            if (!Files.isReadable(path)) {
                throw AudioException.config("音源不存在或不可读：" + source);
            }
            byte[] all;
            try {
                all = Files.readAllBytes(path);
            } catch (IOException e) {
                throw AudioException.io("读不出音源：" + source, e);
            }
            if (all.length < 44 || !tag(all, 0, "RIFF") || !tag(all, 8, "WAVE")) {
                throw AudioException.unsupportedFormat("测试替身只认 RIFF/WAVE：" + source);
            }
            int rate = 0;
            int channels = 0;
            int bits = 0;
            int dataOff = -1;
            int dataLen = 0;
            int p = 12;
            while (p + 8 <= all.length) {
                int size = le32(all, p + 4);
                int body = p + 8;
                if (tag(all, p, "fmt ")) {
                    channels = le16(all, body + 2);
                    rate = le32(all, body + 4);
                    bits = le16(all, body + 14);
                } else if (tag(all, p, "data")) {
                    dataOff = body;
                    dataLen = Math.min(size, all.length - body);
                }
                p = body + size + (size & 1);
            }
            if (dataOff < 0 || channels <= 0 || bits != 16) {
                throw AudioException.unsupportedFormat("测试替身只认 16 bit PCM：" + source);
            }
            return new WavDecoder(all, dataOff, dataLen, rate, channels, bits / 8);
        }

        private static boolean tag(byte[] b, int off, String s) {
            for (int i = 0; i < s.length(); i++) {
                if (b[off + i] != (byte) s.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private static int le16(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        }

        private static int le32(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                    | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
        }

        private float sample(int frame, int ch) {
            int c = Math.min(ch, channels - 1);
            int off = dataOff + (frame * channels + c) * bytesPerSample;
            short raw = (short) (((file[off] & 0xFF)) | (file[off + 1] << 8));
            return (float) raw / 32768.0f;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("wav", rate, ChannelLayout.of(channels),
                    Integer.valueOf(16), Long.valueOf(frames), true);
        }

        @Override
        public long framesDecoded() {
            return pos;
        }

        @Override
        public boolean isDrained() {
            return pos >= frames;
        }

        @Override
        public double seek(double secs) {
            long want = (long) (secs * (double) rate);
            pos = (int) Math.max(0L, Math.min(want, frames));
            return (double) pos / (double) rate;
        }

        @Override
        public int decodeInto(AudioBuf out) {
            int n = Math.min(out.capacityFrames(), frames - pos);
            if (n <= 0) {
                out.setFrames(0);
                return 0;
            }
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < out.channels(); c++) {
                    out.channelWriter(c).put(i, sample(pos + i, c));
                }
            }
            out.setFrames(n);
            pos += n;
            return n;
        }

        @Override
        public void close() {
            // 数据已经在内存里，没有句柄要放。
        }
    }
}
