package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.model.InferException;
import dev.t1m3.qplayer.audio.model.ModelAvailability;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import dev.t1m3.qplayer.audio.model.ModelFetchers;
import dev.t1m3.qplayer.audio.model.ModelManifest;
import dev.t1m3.qplayer.audio.model.StemModels;
import dev.t1m3.qplayer.audio.model.StemSeparator;
import dev.t1m3.qplayer.audio.stem.StemSeparators;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link VocalSeparationTransition} 的测试：包络、委托、<b>诚实降级</b>、边界、两档分离器。
 *
 * <h2>用假的 {@code StemSeparator}，不碰真实现</h2>
 *
 * <p>AI 那一档背后是 108 MB 的 htdemucs + ONNX Runtime（本机既没有也不该在单测里跑，
 * 见 {@code docs/audio-port.md} 的「权重绝不打包进成品」），DSP 那一档是另一条并行实现的
 * 真算法。这一层要验的是<b>「拿到分离结果之后怎么用」「拿不到的时候怎么办」
 * 「选的是哪一档就用哪一档」</b>，与分离器内部无关——替身给常数轨反而让断言精确到浮点。
 *
 * <h2>为什么每条降级测试都要比「没套这一层的参照」</h2>
 *
 * <p>「没去人声」最容易出的错不是崩，而是<b>看起来像去了</b>（拿原始音频当伴奏、
 * 拿静音当人声）。所以降级那几条一律断言：输出的采样值必须与<b>没套这一层</b>
 * 时<b>逐位相同</b>（{@code Float.floatToIntBits} 逐样本比），并额外断言它既不是静音
 * 也不是「只剩伴奏」。这是「不许假装」的可执行形式。
 */
public class VocalSeparationTransitionTest {

    /** 测试用的输出采样率：与素材同率，于是这一层不做重采样（对齐无关紧要，形状才要紧）。 */
    private static final int RATE = 8_000;

    /** 窗口（帧）。1 秒，块长 1000 帧 → 正好 8 块。 */
    private static final int WINDOW = 8_000;

    /** 一次渲染的块长（真实编排器是 4096，这里用小一点让断言好读）。 */
    private static final int BLOCK = 1_000;

    /** 64 位十六进制：清单会校验格式，而这条路径上没人去比哈希。 */
    private static final String DUMMY_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /** 本轮装上去的解码器工厂（只认 16 bit PCM WAV 的替身）。 */
    private WavOpener opener;

    /** 测试开始前的全局工厂，用于还原（同 {@code DirectorTest} / {@code DeckTest} / {@code PlaybackEngineTest}）。 */
    private Decoder.Opener savedOpener;

    @Before
    public void installSeams() {
        // 解码器替身：这一层要读进场曲的那一段，产品路径上它由宿主提供。
        // **先快照再装、@After 还原**：这些接缝是全局静态状态，而 surefire 默认所有测试类
        // 共用同一个 JVM——不还原就会漏到同模块别的测试类里（与 DirectorTest 同一处理）。
        savedOpener = currentOpener();
        opener = new WavOpener();
        Decoder.install(opener);
        StemModels.uninstall();
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    @After
    public void uninstallSeams() {
        Decoder.install(savedOpener);
        StemModels.uninstall();
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    /**
     * 读全局解码器工厂的当前值。
     *
     * <p>{@link Decoder} 只有 {@code install} 没有 getter，所以只能反射快照
     * （与 {@code DirectorTest.currentOpener} 逐字同一处理）。读不到时返回 {@code null}——
     * 那对应「宿主还没安装」这个干净起点，也是 player-core 测试的常态。
     */
    private static Decoder.Opener currentOpener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (RuntimeException e) {
            // InaccessibleObjectException / SecurityException 之类：退回干净起点
            return null;
        }
    }

    // ==================================================================
    // 一、包络：从 0 到 1，单调，两端精确
    // ==================================================================

    /**
     * 包络在整个窗口里从 <b>0</b> 走到 <b>1</b>，而且单调不减。
     *
     * <p>素材取「伴奏 = 0、人声 = 1」，于是输出<b>就是</b>包络本身，断言可以逐点精确：
     *
     * <ul>
     *   <li>窗口第 0 帧 = 0：开头只有背景声（用户要的那件事）</li>
     *   <li>1/4 处 = 0.15625、1/2 处 = 0.5：窗口前半段人声基本不在
     *       （smoothstep 的 t²(3−2t) 在这两点上是精确值，所以用零容差断言）</li>
     *   <li>最后一帧 ≈ 1（差 &lt; 1e-6，也就是 float32 意义上已经满了）：
     *       窗口一结束进场曲就以原音量接着放，差一点点就是接缝上的一下台阶</li>
     * </ul>
     */
    @Test
    public void theVocalEnvelopeRunsFromZeroToOneAndOnlyRises() throws IOException {
        Path intro = writeConstantWav("envelope", WINDOW, 0.5f);
        try {
            FixedWindow inner = new FixedWindow(WINDOW);
            VocalSeparationTransition t = wrap(inner, 2);
            installAiModel(new ConstantSeparatorProvider(0.0f, 1.0f));
            t.prepare("outro.wav", intro.toString(), RATE);
            assertTrue("模型在、文件也在，这次应当真的去人声了", t.separatedVocals());

            float[] envelope = renderWindow(t, 2, WINDOW, BLOCK, 0.0f);

            assertEquals("窗口第 0 帧的人声增益必须是 0（融合阶段只要背景声）",
                    0.0f, envelope[0], 0.0f);
            assertEquals("smoothstep 在 1/4 处的精确值", 0.15625f,
                    envelope[WINDOW / 4], 0.0f);
            assertEquals("smoothstep 在中点处的精确值", 0.5f, envelope[WINDOW / 2], 0.0f);
            assertTrue("窗口最后一帧的人声必须已经回到满（否则接缝上会啪一下）："
                            + envelope[WINDOW - 1],
                    Math.abs(envelope[WINDOW - 1] - 1.0f) < 1e-6f);
            for (int i = 1; i < WINDOW; i++) {
                assertTrue("包络必须单调不减，第 " + i + " 帧退回去了："
                                + envelope[i - 1] + " → " + envelope[i],
                        envelope[i] >= envelope[i - 1] - 1e-7f);
            }
        } finally {
            deleteRecursively(intro);
        }
    }

    @Test
    public void partialRenderMustNotModifyUnconsumedIncomingAudio() throws IOException {
        Path intro=writeConstantWav("input-ownership",WINDOW,.7f);
        try {
            FixedWindow inner=new FixedWindow(WINDOW);
            inner.plan=new TransitionPlan(0,PitchPlan.ZERO,0,"duck edge",0,new double[]{.1,.8});
            VocalSeparationTransition t=wrap(inner,2);
            installAiModel(new ConstantSeparatorProvider(0,1));
            t.prepare("outro.wav",intro.toString(),RATE);
            assertTrue(t.separatedVocals());
            AudioBuf from=new AudioBuf(ChannelLayout.STEREO,BLOCK);
            AudioBuf to=new AudioBuf(ChannelLayout.STEREO,BLOCK);
            Arrays.fill(to.backingArray(),.7f);to.setFrames(BLOCK);from.setFrames(BLOCK);
            AudioBuf out=new AudioBuf(ChannelLayout.STEREO,250);
            assertEquals(250,t.render(out,from,to,0));
            for(float v:to.backingArray())assertEquals("retained deck PCM must remain original, including duck ramps",.7f,v,0);
        } finally {deleteRecursively(intro);}
    }

    /**
     * 进度正好到 1.0 时人声增益<b>逐位等于 1</b>——窗口结束那一刻人声是满的。
     *
     * <p>这是上一条的补刀：smoothstep 在 {@code t = 1} 上多项式逐位给 1，
     * 而线性斜坡之类「差一点」的曲线会在这里留下一个台阶。
     */
    @Test
    public void atProgressOneTheVocalsAreBitExactlyAtFullLevel() throws IOException {
        Path intro = writeConstantWav("envelope-one", WINDOW, 0.5f);
        try {
            VocalSeparationTransition t = wrap(new FixedWindow(WINDOW), 2);
            installAiModel(new ConstantSeparatorProvider(0.0f, 1.0f));
            t.prepare("outro.wav", intro.toString(), RATE);

            AudioBuf from = buf(2, BLOCK, 0.0f);
            AudioBuf to = buf(2, BLOCK, 0.0f);
            AudioBuf out = new AudioBuf(ChannelLayout.STEREO, BLOCK);
            t.render(out, from, to, 1.0f);

            assertEquals("进度 1.0 时人声增益必须是 1.0f（逐位）",
                    Float.floatToIntBits(1.0f),
                    Float.floatToIntBits(out.channel(0).get(BLOCK - 1)));
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * 输出<b>逐样本</b>等于「伴奏 + 人声 × 包络」，而且包络是逐样本算的
     * （不是每块一个值——那会把人声做成 85 ms 一级的阶梯）。
     *
     * <p>预期值在这里按同一条公式本地算一遍：{@code 0.25 + 0.5 × smoothstep(i/window)}，
     * 容差 1e-6 只够吸收单精度乘加的舍入。
     */
    @Test
    public void theResultIsInstrumentalPlusVocalsTimesTheEnvelope() throws IOException {
        Path intro = writeConstantWav("formula", WINDOW, 0.5f);
        try {
            VocalSeparationTransition t = wrap(new FixedWindow(WINDOW), 2);
            installAiModel(new ConstantSeparatorProvider(0.25f, 0.5f));
            t.prepare("outro.wav", intro.toString(), RATE);

            float[] got = renderWindow(t, 2, WINDOW, BLOCK, 0.0f);

            for (int i = 0; i < WINDOW; i++) {
                double x = (double) i / (double) WINDOW;
                double want = 0.25 + 0.5 * (x * x * (3.0 - 2.0 * x));
                assertEquals("第 " + i + " 帧不是「伴奏 + 人声 × 包络」",
                        want, (double) got[i], 1e-6);
            }
            // 块边界上不能有台阶：逐样本算包络的直接证据
            for (int b = 1; b < WINDOW / BLOCK; b++) {
                int i = b * BLOCK;
                assertTrue("块边界 " + i + " 上出现了台阶（包络被量化成每块一个值了）",
                        Math.abs(got[i] - got[i - 1]) < 0.01f);
            }
        } finally {
            deleteRecursively(intro);
        }
    }

    // ==================================================================
    // 二、委托：包装不该改变切歌点判定
    // ==================================================================

    /** {@code windowFrames} / {@code pitchAt} / {@code rewritesSamples} 与内部接法逐值相同。 */
    @Test
    public void everyDelegatedValueIsIdenticalToTheInnerTransition() {
        FixedWindow inner = new FixedWindow(4_321L);
        inner.pitch = new PitchPlan(-1.5f, 2.25f);
        inner.rewrites = false;
        VocalSeparationTransition t = wrap(inner, 2);

        assertEquals("窗口被包装改掉了——那会把切歌点挪走",
                inner.windowFrames(RATE), t.windowFrames(RATE));
        assertEquals(inner.windowFrames(RATE), t.windowFrames(48_000));
        assertFalse("位完美标记被包装改掉了", t.rewritesSamples());
        PitchPlan expected = inner.pitchAt(0.37f);
        PitchPlan actual = t.pitchAt(0.37f);
        assertEquals(Float.floatToIntBits(expected.outro), Float.floatToIntBits(actual.outro));
        assertEquals(Float.floatToIntBits(expected.intro), Float.floatToIntBits(actual.intro));
        assertTrue("名字要让人看出去了人声：" + t.name(), t.name().contains("去人声"));
        assertEquals("档位要跟着构造走", StemSeparators.AI, t.vocalMode());
    }

    /** 位完美标记是原样透传的（会改写采样值的接法照样报告 true）。 */
    @Test
    public void theBitPerfectFlagFollowsTheInnerTransitionBothWays() {
        FixedWindow yes = new FixedWindow(WINDOW);
        yes.rewrites = true;
        assertTrue(wrap(yes, 2).rewritesSamples());
        FixedWindow no = new FixedWindow(WINDOW);
        no.rewrites = false;
        assertFalse(wrap(no, 2).rewritesSamples());
    }

    /** {@code prepare} 只做一件事：在内部接法的结论后面补一句，其余字段逐值不变。 */
    @Test
    public void prepareKeepsEveryFieldOfTheInnerPlan() throws IOException {
        Path intro = writeConstantWav("plan", WINDOW, 0.5f);
        try {
            FixedWindow inner = new FixedWindow(WINDOW);
            inner.plan = new TransitionPlan(0.42, new PitchPlan(1.0f, 0.0f), 0.3f, "假接法 · 128 BPM");
            VocalSeparationTransition t = wrap(inner, 2);
            installAiModel(new ConstantSeparatorProvider(0.0f, 1.0f));

            TransitionPlan plan = t.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("委托必须只调一次 prepare", 1, inner.prepareCalls);
            assertEquals(0.42, plan.skipSecs, 0.0);
            assertEquals(0.3f, plan.introStartAt, 0.0f);
            assertEquals(Float.floatToIntBits(1.0f), Float.floatToIntBits(plan.pitch.outro));
            assertTrue("内部接法的说明不能被盖掉：" + plan.note, plan.note.contains("128 BPM"));
            assertTrue("去了人声这件事要写在 note 里：" + plan.note, plan.note.contains("已去人声"));
            assertFalse("成功了就不该出现「未去人声」的字样：" + plan.note,
                    plan.note.contains("未去人声"));
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * 窗口要在 <b>prepare 之后</b>读。
     *
     * <p>对拍会把窗口拉长最多一拍（见 {@code Transition.windowFrames} 的文档）。读早了，
     * 素材就只够旧窗口那么长，新窗口的尾巴会没有分离结果——<b>而过渡是按新窗口走的</b>。
     * 这里让内部接法在 prepare 里把窗口从 4000 拉长到 8000，断言素材覆盖的是 8000。
     */
    @Test
    public void theWindowIsReadAfterPrepareNotBefore() throws IOException {
        Path intro = writeConstantWav("aligned", WINDOW, 0.5f);
        try {
            FixedWindow inner = new FixedWindow(4_000L);
            inner.windowAfterPrepare = WINDOW; // 模拟对拍把窗口拉长
            VocalSeparationTransition t = wrap(inner, 2);
            installAiModel(new ConstantSeparatorProvider(0.0f, 1.0f));

            t.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("窗口必须按 prepare 之后的值算", WINDOW, t.coveredFrames());
            assertEquals(WINDOW, t.windowFrames(RATE));
            // 拉长出来的那半截也必须真的有分离结果（否则尾巴会退回满人声）
            float[] got = renderWindow(t, 2, WINDOW, BLOCK, 0.0f);
            assertEquals(1.0f, got[WINDOW - 1], 1e-6f);
        } finally {
            deleteRecursively(intro);
        }
    }

    // ==================================================================
    // 三、AI 那一档的诚实降级：照常过渡、不碰人声、逐位与「没套这一层」相同
    // ==================================================================

    /** 模型不在磁盘上的那一种「没有模型」：连实现都不装。 */
    @Test
    public void withoutTheModelTheTransitionIsUntouchedAndTheNoteSaysSo() throws IOException {
        Path intro = writeConstantWav("nomodel", WINDOW, 0.5f);
        try {
            assertDegraded(intro, new FixedWindow(WINDOW), StemSeparators.AI, "htdemucs");
        } finally {
            deleteRecursively(intro);
        }
    }

    /** 模型在磁盘上、但会话建不起来（宿主 {@code open()} 抛 {@link InferException}）。 */
    @Test
    public void aBrokenOpenerDegradesHonestly() throws IOException {
        Path intro = writeConstantWav("broken-open", WINDOW, 0.5f);
        try {
            installAiModel(new StemModels.Opener() {
                @Override
                public StemSeparator open() {
                    throw InferException.load("测试替身故意打不开");
                }
            });
            assertDegraded(intro, new FixedWindow(WINDOW), StemSeparators.AI, "推理失败");
        } finally {
            deleteRecursively(intro);
        }
    }

    /** 推理本身失败（{@code separate()} 抛 {@link InferException}）。 */
    @Test
    public void aFailingSeparateDegradesHonestly() throws IOException {
        Path intro = writeConstantWav("broken-separate", WINDOW, 0.5f);
        try {
            installAiModel(new StemModels.Opener() {
                @Override
                public StemSeparator open() {
                    return new StemSeparator() {
                        @Override
                        public StemSet separate(float[] left, float[] right, int sampleRate) {
                            throw InferException.run("测试替身推理失败");
                        }

                        @Override
                        public void close() {
                        }
                    };
                }
            });
            assertDegraded(intro, new FixedWindow(WINDOW), StemSeparators.AI, "推理失败");
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * 超预算：分离太贵，这次就不去人声。
     *
     * <p>预算走构造器（{@code 0} = 一点都不许花），替身睡 5 ms——比预算大三个数量级，
     * 不是靠运气过的。
     */
    @Test
    public void aSeparationOverBudgetIsDiscardedAndSaysSo() throws IOException {
        Path intro = writeConstantWav("overbudget", WINDOW, 0.5f);
        try {
            installAiModel(new SlowSeparatorProvider());
            VocalSeparationTransition wrapped =
                    new VocalSeparationTransition(new FixedWindow(WINDOW), RATE, 2,
                            StemSeparators.AI, 0.0f);
            TransitionPlan plan = wrapped.prepare("outro.wav", intro.toString(), RATE);

            assertFalse("超预算就不该用那份结果", wrapped.separatedVocals());
            assertTrue("必须说出是超预算：" + plan.note, plan.note.contains("未去人声"));
            assertTrue("必须写出超了预算：" + plan.note, plan.note.contains("超出预算"));
            assertSameOutputAsUnwrapped(new FixedWindow(WINDOW), wrapped);
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * 分离结果的长度与输入不符时<b>整段不用</b>，而不是硬着头皮乘包络。
     *
     * <p>接缝写明「输入与输出的长度必须一致」：不一致就说明对齐已经错了，
     * 拿它去混会让伴奏与人声错位——那比不去人声更糟。这条同时钉住「不许越界」。
     */
    @Test
    public void aWrongLengthStemSetIsRefusedInsteadOfMisaligned() throws IOException {
        Path intro = writeConstantWav("wronglen", WINDOW, 0.5f);
        try {
            installAiModel(new StemModels.Opener() {
                @Override
                public StemSeparator open() {
                    return new StemSeparator() {
                        @Override
                        public StemSet separate(float[] left, float[] right, int sampleRate) {
                            int n = left.length / 2; // 一致地短一半（StemSet 自己要求四条等长）
                            return new StemSet(new float[n], new float[n], new float[n], new float[n]);
                        }

                        @Override
                        public void close() {
                        }
                    };
                }
            });
            assertDegraded(intro, new FixedWindow(WINDOW), StemSeparators.AI, "长度与输入不符");
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * <b>不许把「没去人声」装成「去了人声」。</b>
     *
     * <p>这条测试把那条纪律写成可执行的形式：分离失败时，输出必须
     * ①与没套这一层逐位相同、②不是静音、③不是「只剩伴奏」。
     * 拿原始音频当伴奏（②③都过不了）或拿静音当人声（②过不了）都会在这里红。
     */
    /**
     * <b>「被丢掉的尾巴」必须活着穿过这一层。</b>
     *
     * <p>这一层会重建计划（把说明拼到 note 后面），而 4 参数的那个构造器把
     * {@code outroTailSecs} 默认成 0——它是「被丢掉的尾巴」，编排器靠它把触发点提前
     * （{@code triggerLead = 窗口 + 丢尾}）。丢掉它，自动点位选在曲中的融合点就永远到不了。
     *
     * <p>实测（用户日志，2026-09-29）：自动点位给出「出场 208.7s（丢尾 48.5s）」，
     * 装到编排器手里却成了「窗口 4.0s + 丢尾 0.0s」——触发点从 A 还剩 52.5 秒塌成 4.0 秒，
     * 表现就是「没有按预期跳跃和混音」。这条判据钉住它不再被吃掉。
     */
    @Test
    public void theDroppedTailSurvivesThisLayer() throws IOException {
        Path intro = writeConstantWav("tail-kept", WINDOW, 0.5f);
        try {
            FixedWindow inner = new FixedWindow(WINDOW);
            inner.plan = new TransitionPlan(0.42, PitchPlan.ZERO, 0.0f, "假接法", 48.5);

            TransitionPlan plan = wrap(inner, 2).prepare("outro.wav", intro.toString(), RATE);

            assertEquals("丢尾被这一层吃掉了——触发点会塌成「窗口」，融合点永远到不了",
                    48.5, plan.outroTailSecs, 1e-9);
            assertEquals("进场点也不该被动", 0.42, plan.skipSecs, 1e-9);
        } finally {
            deleteRecursively(intro);
        }
    }

    @Test
    public void aFailedSeparationNeverFallsBackToSilenceOrToTheRawTrackAsInstrumental()
            throws IOException {
        Path intro = writeConstantWav("no-fake", WINDOW, 0.9f);
        try {
            installAiModel(new StemModels.Opener() {
                @Override
                public StemSeparator open() {
                    throw InferException.io("权重被截断了");
                }
            });
            FixedWindow inner = new FixedWindow(WINDOW);
            inner.plan = new TransitionPlan(0.0, PitchPlan.ZERO, 0.0f, "假接法");

            VocalSeparationTransition wrapped = wrap(inner, 2);
            TransitionPlan plan = wrapped.prepare("outro.wav", intro.toString(), RATE);
            assertTrue(plan.note.contains("未去人声"));

            float[] got = renderWindow(wrapped, 2, WINDOW, BLOCK, 0.7f);
            float[] reference = renderWindow(new FixedWindow(WINDOW), 2, WINDOW, BLOCK, 0.7f);
            assertBitsEqual("降级时的输出必须与「没套这一层」逐位相同", reference, got);
            assertTrue("输出是静音——等于拿静音当人声", got[WINDOW / 2] != 0.0f);
            assertEquals("输出被换成了「只剩伴奏」的样子", 0.7f, got[WINDOW / 2], 0.0f);
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * <b>选了 AI，就不许悄悄改用 DSP 顶替。</b>
     *
     * <p>这条是设计上明确要求的一条：AI 不可用时照常过渡但不去人声。
     * 换一种算法就是「换了个东西还不说」——而这两种算法的听感差别很大
     * （DSP 是卡拉OK级的中心声道抑制，AI 是真分轨）。
     *
     * <p>为了让断言有意义，这里把 DSP 那一档也装成<b>可用</b>的（替身能正常工作）：
     * 「DSP 本来能跑，但这次一个字节都没碰它」才是要钉住的东西。
     */
    @Test
    public void theAiModeNeverFallsBackToDsp() throws IOException {
        Path intro = writeConstantWav("no-switch", WINDOW, 0.5f);
        try {
            ConstantSeparatorProvider dsp = new ConstantSeparatorProvider(0.0f, 1.0f);
            installDsp(dsp);
            // AI 那一档：实现装了，但磁盘上没有权重 → available(AI) 为 false
            // （目录与清单都换成测试自己那一份，免得受本机下没下过权重影响）
            CountingAiProvider ai = new CountingAiProvider();
            installAiModelWithoutWeights(ai);

            assertTrue("夹具本身要成立：DSP 这一档是能用的", StemSeparators.available(StemSeparators.DSP));
            assertFalse("夹具本身要成立：AI 这一档不可用",
                    StemSeparators.available(StemSeparators.AI));

            FixedWindow inner = new FixedWindow(WINDOW);
            VocalSeparationTransition wrapped = wrap(inner, 2, StemSeparators.AI);
            TransitionPlan plan = wrapped.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("不许碰 DSP 那一档——碰了就是偷偷换了算法", 0, dsp.opens);
            assertEquals("模型不可用时连打开都不该试", 0, ai.opens);
            assertFalse(wrapped.separatedVocals());
            assertTrue(plan.note.contains("未去人声"));
            assertTrue("原因里要点名是模型的事：" + plan.note, plan.note.contains("htdemucs"));
            assertSameOutputAsUnwrapped(inner, wrapped);
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * 模型在、但推理建不起来时，<b>同样不许改用 DSP</b>。
     *
     * <p>上一条钉的是「模型不在磁盘上」，这一条钉的是更近的那种情况：模型在磁盘上、
     * 会话却建不起来——这时 DSP 那一档<b>完全可用</b>，于是「悄悄换成 DSP」这种实现
     * 一定会在这里露出来（它会去开 DSP）。规则只有一条：用户选了哪一档就用哪一档，
     * 用不了就照常过渡 + 说出来。
     */
    @Test
    public void aFailingAiModelDoesNotGetSwappedForDsp() throws IOException {
        Path intro = writeConstantWav("no-swap", WINDOW, 0.5f);
        try {
            ConstantSeparatorProvider dsp = new ConstantSeparatorProvider(0.0f, 1.0f);
            installDsp(dsp);
            installAiModel(new StemModels.Opener() {
                @Override
                public StemSeparator open() {
                    throw InferException.load("测试替身：会话建不起来");
                }
            });
            assertTrue("夹具本身要成立：AI 这一档看起来是能用的",
                    StemSeparators.available(StemSeparators.AI));

            FixedWindow inner = new FixedWindow(WINDOW);
            VocalSeparationTransition wrapped = wrap(inner, 2, StemSeparators.AI);
            TransitionPlan plan = wrapped.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("AI 建不起来时不许去开 DSP——那就是偷偷换了算法", 0, dsp.opens);
            assertFalse(wrapped.separatedVocals());
            assertTrue(plan.note.contains("未去人声"));
            assertTrue("原因要说清是 AI 那一档失败了：" + plan.note, plan.note.contains("推理失败"));
            assertSameOutputAsUnwrapped(inner, wrapped);
        } finally {
            deleteRecursively(intro);
        }
    }

    // ==================================================================
    // 四、DSP 那一档
    // ==================================================================
    /**
     * DSP 那一档走的是 DSP 实现，<b>一次都不碰模型</b>，而且不需要磁盘上有任何权重。
     */
    @Test
    public void theDspModeUsesTheDspSeparatorAndNeverTouchesTheModel() throws IOException {
        Path intro = writeConstantWav("dsp", WINDOW, 0.5f);
        try {
            ConstantSeparatorProvider dsp = new ConstantSeparatorProvider(0.0f, 1.0f);
            installDsp(dsp);
            CountingAiProvider ai = new CountingAiProvider();
            StemModels.install(ai);

            FixedWindow inner = new FixedWindow(WINDOW);
            VocalSeparationTransition wrapped = wrap(inner, 2, StemSeparators.DSP);
            TransitionPlan plan = wrapped.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("DSP 那一档该被打开一次", 1, dsp.opens);
            assertEquals("DSP 那一档不该碰模型", 0, ai.opens);
            assertEquals("接缝说好了不能话不算数：这个长度必然不是 0",
                    StemSeparators.DSP, wrapped.vocalMode());
            assertTrue(wrapped.separatedVocals());
            assertTrue("note 里要点名用的是哪一档：" + plan.note, plan.note.contains("已去人声（DSP"));

            // 而且素材真的被用上了（不是「看起来成功了但什么都没改」）
            float[] got = renderWindow(wrapped, 2, WINDOW, BLOCK, 0.6f);
            float[] reference = renderWindow(new FixedWindow(WINDOW), 2, WINDOW, BLOCK, 0.6f);
            assertEquals("窗口开头应当只剩背景声（0），而不是原样的 0.6",
                    0.0f, got[0], 0.0f);
            assertFalse("输出与没套这一层一样，说明分离结果根本没被用上",
                    Float.floatToIntBits(got[0]) == Float.floatToIntBits(reference[0]));
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * DSP 那一档真出问题时（实现建不起来 / 算的时候抛异常）<b>同样如实降级</b>。
     *
     * <p>纯 Java 不等于不会失败：内存不够、参数非法、将来换实现都可能。规则与 AI 那一档
     * 一样：照常过渡、不碰人声、note 里说清楚——只是不会有「需要下载」这种原因。
     */
    @Test
    public void aFailingDspSeparatorDegradesHonestly() throws IOException {
        Path intro = writeConstantWav("dsp-broken", WINDOW, 0.5f);
        try {
            installDsp(new StemSeparators.Opener() {
                @Override
                public StemSeparator open() {
                    throw InferException.run("测试替身：DSP 建不起来");
                }
            });
            assertDegraded(intro, new FixedWindow(WINDOW), StemSeparators.DSP, "推理失败");
        } finally {
            deleteRecursively(intro);
        }
    }

    /** DSP 那一档的分离结果长度不对时同样整段不用（与 AI 那一档同一条规矩）。 */
    @Test
    public void aWrongLengthDspStemSetIsRefused() throws IOException {
        Path intro = writeConstantWav("dsp-wronglen", WINDOW, 0.5f);
        try {
            installDsp(new StemSeparators.Opener() {
                @Override
                public StemSeparator open() {
                    return new StemSeparator() {
                        @Override
                        public StemSet separate(float[] left, float[] right, int sampleRate) {
                            int n = left.length + 7; // 长一点点也不行
                            return new StemSet(new float[n], new float[n], new float[n], new float[n]);
                        }

                        @Override
                        public void close() {
                        }
                    };
                }
            });
            assertDegraded(intro, new FixedWindow(WINDOW), StemSeparators.DSP, "长度与输入不符");
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * <b>真 DSP 的端到端</b>：默认实现（{@code new DspStemSeparator()}，不装任何替身）
     * 直接驱动整条包装接法，而且<b>磁盘上一个模型都没有</b>。
     *
     * <p>这条是两件事的兑现：
     *
     * <ol>
     *   <li><b>DSP 那一档不需要模型、不需要门控</b>：测试从头到尾没装 {@code StemModels}
     *       实现、也没放权重（先断言 {@code StemModels.available()} 为假），分离照样成功，
     *       {@code plan.note} 里写的是「已去人声（DSP…）」而不是「需要下载」。</li>
     *   <li><b>包装层与真 DSP 的输出严丝合缝</b>：素材是一段<b>正中间</b>的乐音
     *       （左右声道完全相同），而 DSP 的语义是「把中心声道的人声从<b>伴奏轨</b>里抑制掉」，
     *       于是「伴奏 ≈ 0、人声 ≈ 原信号」，包络一乘就得到这条测试要的两端：
     *       <b>窗口开头只剩接近静音的背景声，窗口结尾回到原音</b>。
     *       这正是当年 automix 那套「人声渐恢复」的听感。</li>
     * </ol>
     *
     * <p>断言用的是<b>区间峰值</b>而不是逐样本相等：DSP 内部有 STFT 分帧，开头那几十毫秒
     * 的边界行为不该由这一层来钉（那是 {@code DspStemSeparatorTest} 的地盘）。
     * 阈值留了足够余量，实测值写在断言消息里，红了能一眼看出差多少。
     */
    @Test
    public void theRealDspSeparatorDrivesTheWrapperWithoutAnyModel() throws IOException {
        int rate = 44_100;
        int window = rate;                       // 1 秒窗口（真 DSP 实测 ≈46 倍实时，这条几十毫秒）
        Path intro = writeCenterToneWav("dsp-real", window, rate);
        try {
            assertFalse("这一段必须不需要模型：StemModels 没装、磁盘上也没有权重",
                    StemModels.available());

            FixedWindow inner = new FixedWindow(window);
            VocalSeparationTransition t = new VocalSeparationTransition(
                    inner, rate, 2, StemSeparators.DSP);
            TransitionPlan plan = t.prepare("outro.wav", intro.toString(), rate);

            assertTrue("真 DSP 应当分得出来：" + plan.note, t.separatedVocals());
            assertTrue("note 要点名用的是 DSP：" + plan.note, plan.note.contains("已去人声（DSP"));
            assertFalse("不许出现「需要下载」那种话：" + plan.note, plan.note.contains("下载"));

            float[] got = renderWindow(t, 2, window, 4096, 0.0f);

            // 开头（包络≈0）= 伴奏轨：正中间的东西已经被拿掉，应当接近静音。
            // 取样区从 5% 开始，躲开 STFT 分帧在 t=0 的边界行为。
            // 本机实测（44.1 kHz、440 Hz + 1 kHz 的中心乐音，素材峰值 0.797）：
            // 开头区间峰值 0.0216（≈ −31 dB）、结尾区间峰值 0.7966（≈ 原样回来）。
            // 阈值留了 7 倍余量，红了能一眼看出差多少。
            float head = peak(got, window / 20, window / 10);
            // 结尾（包络≈1）= 人声轨：原来的中心乐音应该回来了。
            float tail = peak(got, window * 9 / 10, window);

            assertTrue("窗口开头应当只剩背景声（实测峰值 " + head + "），而不是原样的 "
                            + tail, head < 0.15f);
            assertTrue("窗口结尾应当已经回到原音（实测峰值 " + tail + "）", tail > 0.5f);
            assertTrue("人声必须真的回来了（结尾至少是开头的 5 倍）",
                    tail > head * 5f);
        } finally {
            deleteRecursively(intro);
        }
    }

    // ==================================================================
    // 五、边界：不许越界
    // ==================================================================

    /**
     * 进场曲比窗口短时：<b>没覆盖到的那一段原样留着</b>，不许越界、不许当静音。
     *
     * <p>把没数据的地方补成静音等于「悄悄把人声也删了」；原样留着顶多是那一小段
     * 恢复成「没去人声」的样子——两种错法里，只有后者是诚实的。
     */
    @Test
    public void anIntroShorterThanTheWindowLeavesTheTailUntouched() throws IOException {
        int shortFrames = 3_000; // 窗口 8000，素材只够 3000
        Path intro = writeConstantWav("short-intro", shortFrames, 0.5f);
        try {
            FixedWindow inner = new FixedWindow(WINDOW);
            VocalSeparationTransition t = wrap(inner, 2);
            installAiModel(new ConstantSeparatorProvider(0.0f, 1.0f));
            t.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("素材只该有这么长", shortFrames, t.coveredFrames());
            // `to` 里塞一个显眼的值：覆盖到的地方会被换成包络，没覆盖到的地方必须原样
            float[] got = renderWindow(t, 2, WINDOW, BLOCK, 0.75f);

            double x = (double) (shortFrames - 1) / (double) WINDOW;
            assertEquals("覆盖到的最后一帧应当已经是包络值（不是原样 0.75）",
                    x * x * (3.0 - 2.0 * x), (double) got[shortFrames - 1], 1e-4);
            assertEquals("素材之外必须原样留着", 0.75f, got[shortFrames], 0.0f);
            assertEquals("素材之外必须原样留着", 0.75f, got[WINDOW - 1], 0.0f);
        } finally {
            deleteRecursively(intro);
        }
    }

    /** 进场曲在窗口起点之后什么都没有（曲子到此为止）：如实降级，不越界。 */
    @Test
    public void anEmptyIntroWindowDegradesInsteadOfReadingOutOfBounds() throws IOException {
        Path intro = writeConstantWav("empty-intro", 16, 0.5f);
        try {
            FixedWindow inner = new FixedWindow(WINDOW);
            inner.plan = new TransitionPlan(5.0, PitchPlan.ZERO, 0.0f, ""); // 跳过整首
            VocalSeparationTransition t = wrap(inner, 2);
            installAiModel(new ConstantSeparatorProvider(0.0f, 1.0f));

            TransitionPlan plan = t.prepare("outro.wav", intro.toString(), RATE);

            assertFalse(t.separatedVocals());
            assertTrue("必须如实说明这次没去人声：" + plan.note, plan.note.contains("未去人声"));
            assertSameOutputAsUnwrapped(new FixedWindow(WINDOW), t);
        } finally {
            deleteRecursively(intro);
        }
    }

    /** `to` 比窗口短（进场 deck 交不出那么多帧）时按实际长度处理，且游标不错位。 */
    @Test
    public void aShorterBlockIsHandledByItsActualLength() throws IOException {
        Path intro = writeConstantWav("short-block", WINDOW, 0.5f);
        try {
            FixedWindow inner = new FixedWindow(WINDOW);
            VocalSeparationTransition t = wrap(inner, 2);
            installAiModel(new ConstantSeparatorProvider(0.0f, 1.0f));
            t.prepare("outro.wav", intro.toString(), RATE);

            AudioBuf from = buf(2, BLOCK, 0.0f);
            AudioBuf to = buf(2, 250, 0.0f);   // 这一块只有 250 帧
            AudioBuf out = new AudioBuf(ChannelLayout.STEREO, BLOCK);
            t.render(out, from, to, 0.0f);
            assertEquals("输出只能有 to 那么多帧", 250, out.frames());
            assertEquals(0.0f, out.channel(0).get(0), 0.0f);

            // 下一块从 250 处接着走：进度 0 与 250 帧处的人声增益必须对得上
            AudioBuf to2 = buf(2, 250, 0.0f);
            t.render(out, from, to2, 250.0f / (float) WINDOW);
            double x0 = 250.0 / (double) WINDOW;
            assertEquals("游标必须跟着 to.frames() 走", x0 * x0 * (3.0 - 2.0 * x0),
                    (double) out.channel(0).get(0), 1e-6);
        } finally {
            deleteRecursively(intro);
        }
    }

    /** 单声道输出：按两声道平均下混（口径与 Mixer.mapChannels 一致）。 */
    @Test
    public void monoOutputDownmixesTheTwoStemsTheSameWayTheDeckDoes() throws IOException {
        Path intro = writeConstantWav("mono", WINDOW, 0.5f);
        try {
            VocalSeparationTransition t = wrap(new FixedWindow(WINDOW), 1);
            installAiModel(new ConstantSeparatorProvider(0.0f, 2.0f, 0.0f, 4.0f));
            t.prepare("outro.wav", intro.toString(), RATE);

            float[] got = renderWindow(t, 1, WINDOW, BLOCK, 0.0f);
            // 人声 (2+4)/2 = 3，伴奏 0 → 窗口末尾应当到 3
            assertEquals(3.0f, got[WINDOW - 1], 1e-5f);
            assertEquals(0.0f, got[0], 0.0f);
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * 窗口为 0（无缝拼接）时什么都不做：<b>连分离器都不去开</b>。
     *
     * <p>窗口 0 = 硬接，套上这一层只是白读一次文件、白跑一次分离。
     */
    @Test
    public void aZeroWindowSkipsTheModelEntirely() throws IOException {
        Path intro = writeConstantWav("zerowindow", WINDOW, 0.5f);
        try {
            CountingAiProvider counter = new CountingAiProvider();
            installAiModel(counter);
            VocalSeparationTransition t = wrap(new FixedWindow(0L), 2);

            TransitionPlan plan = t.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("窗口为 0 时不该去开模型", 0, counter.opens);
            assertFalse(t.separatedVocals());
            assertTrue(plan.note.contains("未去人声"));
            assertTrue("要说出原因是窗口为 0：" + plan.note, plan.note.contains("窗口为 0"));
        } finally {
            deleteRecursively(intro);
        }
    }

    /** 分离要的是<b>输出侧采样率</b>（接缝约定：实现自己负责重采样到它要的那个率）。 */
    @Test
    public void theSeparatorIsAskedAtTheOutputSampleRate() throws IOException {
        Path intro = writeConstantWav("stems-rate", WINDOW, 0.5f);
        try {
            ConstantSeparatorProvider provider = new ConstantSeparatorProvider(0.0f, 1.0f);
            installAiModel(provider);
            VocalSeparationTransition t = wrap(new FixedWindow(WINDOW), 2);
            t.prepare("outro.wav", intro.toString(), RATE);
            assertEquals(RATE, provider.lastRate);
            assertEquals(WINDOW, provider.lastFrames);
            assertEquals("会话用完必须关（重复调用安全）", 1, provider.closes);
        } finally {
            deleteRecursively(intro);
        }
    }

    /**
     * <b>每个实例只做一次分离。</b>
     *
     * <p>实例本来就只服务一次过渡（{@link Transition} 的生命周期约定），所以分离结果
     * 不需要任何跨过渡的缓存与失效逻辑；但「只做一次」这件事本身要钉住——
     * 真有人把 {@code prepare} 调第二次时，重跑一次秒级的分离是纯浪费，
     * 而在准备线程上浪费的那几秒会直接变成「这次过渡被推迟」。
     */
    @Test
    public void theSeparationRunsOnlyOncePerInstance() throws IOException {
        Path intro = writeConstantWav("once", WINDOW, 0.5f);
        try {
            ConstantSeparatorProvider provider = new ConstantSeparatorProvider(0.0f, 1.0f);
            installAiModel(provider);
            VocalSeparationTransition t = wrap(new FixedWindow(WINDOW), 2);

            TransitionPlan first = t.prepare("outro.wav", intro.toString(), RATE);
            TransitionPlan second = t.prepare("outro.wav", intro.toString(), RATE);

            assertEquals("第二次不该再去开分离器", 1, provider.opens);
            assertEquals("第二次不该再分离", 1, provider.separates);
            assertEquals("两次的结论必须一致", first.note, second.note);
            assertTrue(t.separatedVocals());
        } finally {
            deleteRecursively(intro);
        }
    }

    // ==================================================================
    // 测试夹具
    // ==================================================================

    /** 降级那几条共用的断言：<b>正常过渡 + 如实标注 + 逐位与参照相同</b>。 */
    private static void assertDegraded(Path intro, FixedWindow inner, int vocalMode,
                                       String reasonFragment) {
        VocalSeparationTransition wrapped = wrap(inner, 2, vocalMode);
        TransitionPlan plan = wrapped.prepare("outro.wav", intro.toString(), RATE);

        assertFalse("分离器不可用时不该有分离结果", wrapped.separatedVocals());
        assertTrue("必须如实说出没去人声：" + plan.note, plan.note.contains("未去人声"));
        assertFalse("没去人声却标了「已去人声」：" + plan.note, plan.note.contains("已去人声"));
        assertTrue("要说清是哪一种失败（期望含「" + reasonFragment + "」）：" + plan.note,
                plan.note.contains(reasonFragment));
        assertSameOutputAsUnwrapped(inner, wrapped);
    }

    /** 把整个窗口渲染一遍，断言与「没套这一层」的同一个接法逐位相同。 */
    private static void assertSameOutputAsUnwrapped(FixedWindow inner, Transition wrapped) {
        float[] reference = renderWindow(inner, 2, WINDOW, BLOCK, 0.6f);
        float[] got = renderWindow(wrapped, 2, WINDOW, BLOCK, 0.6f);
        assertBitsEqual("降级时的输出必须与「没套这一层」逐位相同", reference, got);
    }

    private static void assertBitsEqual(String message, float[] expected, float[] actual) {
        assertEquals(message + "（长度）", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (Float.floatToIntBits(expected[i]) != Float.floatToIntBits(actual[i])) {
                assertEquals(message + "（第 " + i + " 个采样）",
                        Float.floatToIntBits(expected[i]), Float.floatToIntBits(actual[i]));
            }
        }
    }

    /** 两参版本走 AI 那一档（本类里大多数测试是照那条路搭的）。 */
    private static VocalSeparationTransition wrap(Transition inner, int channels) {
        return wrap(inner, channels, StemSeparators.AI);
    }

    /** 按档位包一层。 */
    private static VocalSeparationTransition wrap(Transition inner, int channels, int vocalMode) {
        return new VocalSeparationTransition(inner, RATE, channels, vocalMode);
    }

    /**
     * 把整个窗口一块块渲染完，返回声道 0 上那些采样的拼接。
     *
     * <p>进度按 {@code 块起点 / 窗口} 算，与编排器（{@code renderTransition}）逐位一致。
     */
    private static float[] renderWindow(Transition t, int channels, int window, int block, float toValue) {
        AudioBuf from = buf(channels, block, 0.0f);
        AudioBuf to = new AudioBuf(ChannelLayout.of(channels), block);
        AudioBuf out = new AudioBuf(ChannelLayout.of(channels), block);
        List<Float> all = new ArrayList<Float>();
        int blocks = (window + block - 1) / block;
        for (int b = 0; b < blocks; b++) {
            int n = Math.min(block, window - b * block);
            setFrames(from, n, 0.0f);
            setFrames(to, n, toValue);
            out.clear();
            t.render(out, from, to, (float) (b * block) / (float) window);
            for (int i = 0; i < out.frames(); i++) {
                all.add(Float.valueOf(out.channel(0).get(i)));
            }
        }
        float[] flat = new float[all.size()];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = all.get(i).floatValue();
        }
        return flat;
    }

    private static AudioBuf buf(int channels, int frames, float value) {
        AudioBuf b = new AudioBuf(ChannelLayout.of(channels), Math.max(frames, 1));
        setFrames(b, frames, value);
        return b;
    }

    private static void setFrames(AudioBuf b, int frames, float value) {
        b.setFrames(frames);
        for (int c = 0; c < b.channels(); c++) {
            for (int i = 0; i < frames; i++) {
                b.channelWriter(c).put(i, value);
            }
        }
    }

    /**
     * 一个窗口可指定的假接法：{@code render} 把 {@code to} 原样搬到 {@code out}。
     *
     * <p>这样断言读到的就是<b>这一层改过的素材本身</b>（换成真接法之后还要再与出场曲混合，
     * 包络的形状就淹在混音里了）。
     */
    private static final class FixedWindow implements Transition {

        long window;
        long windowAfterPrepare = -1L;
        boolean rewrites = true;
        PitchPlan pitch = PitchPlan.ZERO;
        TransitionPlan plan = TransitionPlan.NONE;
        int prepareCalls;

        FixedWindow(long window) {
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
                FloatBuffer t = to.channel(ch);
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
            return rewrites;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int rate) {
            prepareCalls++;
            if (windowAfterPrepare >= 0L) {
                window = windowAfterPrepare;
            }
            return plan;
        }

        @Override
        public PitchPlan pitchAt(float progress) {
            return pitch;
        }
    }

    // ---- 分离器替身 --------------------------------------------------------

    /**
     * 常数轨的替身：不管输入是什么，都返回四条等长的常数轨。
     *
     * <p>两个 {@code open()} 的签名<b>恰好一样</b>，所以一个类同时实现
     * {@link StemModels.Opener}（AI 那一档）与 {@link StemSeparators.Opener}（DSP 那一档）——
     * 这也是「两档在接缝上完全同形」的一个直接体现。
     */
    private static class ConstantSeparatorProvider
            implements StemModels.Opener, StemSeparators.Opener {

        final float instrumentalLeft;
        final float vocalsLeft;
        final float instrumentalRight;
        final float vocalsRight;
        int opens;
        int closes;
        int separates;
        int lastRate = -1;
        int lastFrames = -1;

        ConstantSeparatorProvider(float instrumental, float vocals) {
            this(instrumental, vocals, instrumental, vocals);
        }

        ConstantSeparatorProvider(float instrumentalLeft, float vocalsLeft,
                                  float instrumentalRight, float vocalsRight) {
            this.instrumentalLeft = instrumentalLeft;
            this.vocalsLeft = vocalsLeft;
            this.instrumentalRight = instrumentalRight;
            this.vocalsRight = vocalsRight;
        }

        @Override
        public StemSeparator open() {
            opens++;
            return new StemSeparator() {
                @Override
                public StemSet separate(float[] left, float[] right, int sampleRate) {
                    separates++;
                    lastRate = sampleRate;
                    lastFrames = left.length;
                    return new StemSet(constant(left.length, vocalsLeft),
                            constant(left.length, vocalsRight),
                            constant(left.length, instrumentalLeft),
                            constant(left.length, instrumentalRight));
                }

                @Override
                public void close() {
                    closes++;
                }
            };
        }
    }

    /** 慢替身：用来把「超预算」那条路钉住。 */
    private static final class SlowSeparatorProvider extends ConstantSeparatorProvider {
        SlowSeparatorProvider() {
            super(0.0f, 1.0f);
        }

        @Override
        public StemSeparator open() {
            final StemSeparator inner = super.open();
            return new StemSeparator() {
                @Override
                public StemSet separate(float[] left, float[] right, int sampleRate) {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return inner.separate(left, right, sampleRate);
                }

                @Override
                public void close() {
                    inner.close();
                }
            };
        }
    }

    /** 只数「AI 那一档被打开过几次」的替身（真被用到时会吼一声，好定位）。 */
    private static final class CountingAiProvider implements StemModels.Opener {

        int opens;

        @Override
        public StemSeparator open() {
            opens++;
            throw InferException.load("测试替身：这一档不该被打开");
        }
    }

    private static float[] constant(int n, float value) {
        float[] out = new float[n];
        Arrays.fill(out, value);
        return out;
    }

    // ---- 接缝与模型可用性 --------------------------------------------------

    /**
     * 让 <b>AI 那一档</b>可用：装上一个 {@link StemModels} 实现，<b>并且</b>在测试自己的
     * 模型目录里放一份「大小对得上」的占位文件（{@code StemModels.available()} 只看这两个条件）。
     *
     * <p>清单与目录都换成测试自己那一份，所以不会因为本机下没下过 108 MB 的权重而行为不同。
     */
    private static void installAiModel(StemModels.Opener opener) throws IOException {
        StemModels.install(opener);
        Path dir = Files.createTempDirectory("qplayer-stem-models");
        byte[] dummy = new byte[]{(byte) 0x53};
        String file = StemModels.MODEL_NAME + ".onnx";
        Files.write(dir.resolve(file), dummy);
        String json = "{\"mirrors\":[\"https://hf-mirror.example.com/{file}\"],\"models\":[{"
                + "\"name\":\"" + StemModels.MODEL_NAME + "\","
                + "\"file\":\"" + file + "\","
                + "\"bytes\":" + dummy.length + ","
                + "\"sha256\":\"" + DUMMY_SHA + "\","
                + "\"enables\":\"stems\",\"license\":\"MIT\","
                + "\"upstream\":\"https://example.invalid\",\"note\":\"测试替身\"}]}";
        ModelDownloads.install(new ModelDownloads(dir.toFile(),
                ModelManifest.parse(json), ModelFetchers.current()));
        ModelAvailability.refresh();
        assertTrue("夹具本身有问题：可用性没起来", StemModels.available());
    }

    /** 换掉 <b>DSP 那一档</b>的实现（默认那份是真的 {@code DspStemSeparator}）。 */
    private static void installDsp(StemSeparators.Opener opener) {
        StemSeparators.installDsp(opener);
    }

    /**
     * 让 <b>AI 那一档</b>「实现装了，但<strong>磁盘上没有权重</strong>」。
     *
     * <p>为什么要显式装这一份清单与目录：{@code StemSeparators.available(AI)} 会去问
     * {@code ModelDownloads.shared().present(...)}，而那默认指向<strong>用户自己的模型目录</strong>
     * （{@code AppDirs.modelsDir()}）。谁在这台机器上下过一次 htdemucs，这条测试的夹具前提
     * 就会翻——2026-09-19 就是这么被顶红的（用户刚下完 103.61 MB 的 htdemucs）。
     * 所以清单与目录都换成测试自己那一份：一个<strong>空</strong>目录 + 一个对不上的大小，
     * 于是它与本机状态无关。与 {@link #installAiModel} 是同一套做法，只差"文件不在"。
     */
    private static void installAiModelWithoutWeights(StemModels.Opener opener) throws IOException {
        StemModels.install(opener);
        Path dir = Files.createTempDirectory("qplayer-stem-models-absent");
        String file = StemModels.MODEL_NAME + ".onnx";
        String json = "{\"mirrors\":[\"https://hf-mirror.example.com/{file}\"],\"models\":[{"
                + "\"name\":\"" + StemModels.MODEL_NAME + "\","
                + "\"file\":\"" + file + "\","
                + "\"bytes\":1,"
                + "\"sha256\":\"" + DUMMY_SHA + "\","
                + "\"enables\":\"stems\",\"license\":\"MIT\","
                + "\"upstream\":\"https://example.invalid\",\"note\":\"测试替身\"}]}";
        ModelDownloads.install(new ModelDownloads(dir.toFile(),
                ModelManifest.parse(json), ModelFetchers.current()));
        ModelAvailability.refresh();
        assertFalse("夹具本身有问题：这一档本该不可用（空目录里没有权重）", StemModels.available());
    }

    // ==================================================================
    // 素材游标：按「交付了多少帧」走，不按「缓冲里有几帧」走
    // ==================================================================

    /**
     * <b>窗口里听到的素材位置，必须等于 deck 交付出去的帧数。</b>
     *
     * <p>用户报的那条（2026-10-02）：「过渡窗口里进度条走得比音频快，到交接处音频才追上，
     * 听起来往回」。根因就在这一层：它把窗口里的进场曲换成**自己读的那份分离素材**，
     * 而那份素材的下标由 {@code cursor} 决定；原先 {@code cursor += to.frames()}
     * ——把「缓冲里有几帧」当成了「这一块交付了几帧」。
     *
     * <p>两者在真实路径上并不相等（编排器的块内探针实测抓到三种形状：
     * {@code to.frames()=6400 ≠ 产出 0}、{@code 4096 ≠ 2560}、{@code 4096 ≠ 1792}）：
     * 缓冲比产出长时游标跑得快，于是**素材读指针跑到音频前面**——听到的是更靠后的那一段，
     * 素材读空后掉回 deck 那条流，听感就是往回跳一下。
     *
     * <h3>怎么量</h3>
     *
     * 素材把位置编码进频率：左声道每 {@code 1000} 帧换一个频率（右声道留空 = 侧声道，
     * 与增益无关），于是从输出里数过零就能反推「这一块听的是素材的第几块」。
     * 分离器用直通替身（伴奏 = 输入、人声 = 0），所以输出<b>就是</b>素材本身，解码没有歧义。
     *
     * <p>接法用「每块只产出 600 / 400 帧」的替身：缓冲里始终有 1000 帧而交付不到一块，
     * 正是缺陷的形状。判据是<b>逐块</b>断言「听到的素材块号 = 已交付帧数 / 素材块长」。
     */
    @Test
    public void theMaterialCursorFollowsWhatWasDeliveredNotWhatTheBufferHeld() throws IOException {
        final int matBlock = 1_000;
        final int matBlocks = 16;
        Path intro = writeStaircaseWav("staircase", matBlock, matBlocks);
        try {
            installAiModel(new PassthroughSeparatorProvider());
            ShortBlockWindow inner = new ShortBlockWindow(WINDOW, new int[] {600, 400});
            VocalSeparationTransition t = wrap(inner, 2);
            t.prepare("outro.wav", intro.toString(), RATE);
            assertTrue("夹具本身有问题：这次没有真的去人声", t.separatedVocals());

            AudioBuf from = buf(2, BLOCK, 0.0f);
            AudioBuf to = new AudioBuf(ChannelLayout.of(2), BLOCK * 2);
            AudioBuf out = new AudioBuf(ChannelLayout.of(2), BLOCK);
            float[] material = staircase(matBlock, matBlocks);

            int delivered = 0;
            for (int call = 0; call < 6; call++) {
                // 编排器：deck 交来一整块（含上一块留下的余量），这一块会交付多少由接法定
                fillLeft(to, material, delivered, BLOCK);
                int written = inner.writtenFor(call);
                out.clear();
                int n = t.render(out, from, to, (float) delivered / (float) WINDOW);
                assertEquals("替身接法这一块应当产出 " + written + " 帧", written, n);

                int heardBlock = decodeBlock(out, Math.min(200, n), matBlock, matBlocks);
                assertEquals("第 " + call + " 块：听到的素材位置必须等于已交付的帧数"
                                + "（听到第 " + heardBlock + " 块，交付 " + delivered + " 帧 → 应为第 "
                                + (delivered / matBlock) + " 块）",
                        delivered / matBlock, heardBlock);
                delivered += n;
            }
        } finally {
            deleteRecursively(intro);
        }
    }

    /** 左声道每 {@code matBlock} 帧换一个频率的素材（右声道留空 = 侧声道）。 */
    private static float[] staircase(int matBlock, int matBlocks) {
        float[] pcm = new float[matBlock * matBlocks];
        for (int k = 0; k < matBlocks; k++) {
            double f = 500.0 + 150.0 * k;
            for (int i = 0; i < matBlock; i++) {
                pcm[k * matBlock + i] = (float) (0.6 * Math.sin(
                        2.0 * Math.PI * f * i / (double) RATE));
            }
        }
        return pcm;
    }

    private static Path writeStaircaseWav(String name, int matBlock, int matBlocks) throws IOException {
        float[] left = staircase(matBlock, matBlocks);
        Path dir = Files.createTempDirectory("qplayer-vocalsep-" + name);
        Path path = dir.resolve(name + ".wav");
        writeWav(path, left, new float[left.length], RATE);
        return path;
    }

    /** 把 {@code material[at, at+frames)} 写进 {@code to} 的左声道（右声道留 0）。 */
    private static void fillLeft(AudioBuf to, float[] material, int at, int frames) {
        to.clear();
        FloatBuffer l = to.channelWriter(0);
        FloatBuffer r = to.channelWriter(1);
        for (int i = 0; i < frames; i++) {
            int idx = at + i;
            l.put(i, idx >= 0 && idx < material.length ? material[idx] : 0.0f);
            r.put(i, 0.0f);
        }
        to.setFrames(frames);
    }

    /** 数前 {@code frames} 帧的过零率，反推素材块号；落在哪一块就用哪一块的中心频率比。 */
    private static int decodeBlock(AudioBuf out, int frames, int matBlock, int matBlocks) {
        FloatBuffer o = out.channel(0);
        int crossings = 0;
        float prev = o.get(0);
        for (int i = 1; i < frames; i++) {
            float cur = o.get(i);
            if ((prev <= 0.0f && cur > 0.0f) || (prev >= 0.0f && cur < 0.0f)) {
                crossings++;
            }
            prev = cur;
        }
        double freq = crossings / 2.0 * ((double) RATE / Math.max(frames, 1));
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int k = 0; k < matBlocks; k++) {
            double diff = Math.abs(freq - (500.0 + 150.0 * k));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = k;
            }
        }
        return best;
    }

    /** 直通分离器：伴奏 = 输入、人声 = 0（于是这一层的输出**就是**素材本身）。 */
    private static final class PassthroughSeparatorProvider
            implements StemModels.Opener, StemSeparators.Opener {

        @Override
        public StemSeparator open() {
            return new StemSeparator() {
                @Override
                public StemSet separate(float[] left, float[] right, int sampleRate) {
                    // 人声轨给输入的 0.2 倍（**不能给 0**）：这一层 2026-10-02 起有
                    // 「人声轨可以忽略时跳过分离」这条分支，给 0 会让它整条跳过，
                    // 而这条用例测的是**游标记账**，不该顺带把跳过也测了。
                    // 同一条素材、同一个频率，所以按频率解位置的方法不受影响。
                    float[] vocalL = new float[left.length];
                    float[] vocalR = new float[right.length];
                    for (int i = 0; i < left.length; i++) {
                        vocalL[i] = left[i] * 0.2f;
                    }
                    for (int i = 0; i < right.length; i++) {
                        vocalR[i] = right[i] * 0.2f;
                    }
                    return new StemSet(vocalL, vocalR, left.clone(), right.clone());
                }

                @Override
                public void close() {
                    // 无会话可放。
                }
            };
        }
    }

    /** 每块只产出一部分的接法替身：制造「缓冲里有 1000 帧、只交付 600 帧」那个形状。 */
    private static final class ShortBlockWindow implements Transition {

        final long window;
        final int[] written;
        int calls;

        ShortBlockWindow(long window, int[] written) {
            this.window = window;
            this.written = written.clone();
        }

        int writtenFor(int call) {
            return Math.min(written[call % written.length], BLOCK);
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            int n = Math.min(writtenFor(calls), Math.min(to.frames(), out.capacityFrames()));
            calls++;
            for (int ch = 0; ch < out.channels(); ch++) {
                FloatBuffer o = out.channelWriter(ch);
                FloatBuffer t = to.channel(ch);
                for (int i = 0; i < n; i++) {
                    o.put(i, t.get(i));
                }
            }
            out.setFrames(n);
            return n;
        }

        @Override
        public String name() {
            return "短块假接法";
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

    // ==================================================================
    // 测试替身：一个只认 16 bit PCM WAV 的解码器
    // ==================================================================

    private static Path writeConstantWav(String name, int frames, float value) throws IOException {
        float[] pcm = new float[frames];
        Arrays.fill(pcm, value);
        Path dir = Files.createTempDirectory("qplayer-vocalsep-" + name);
        Path path = dir.resolve(name + ".wav");
        writeWav(path, pcm, pcm, RATE);
        return path;
    }

    /**
     * 一段<b>正中间</b>的乐音（左右完全相同），用来喂真 DSP。
     *
     * <p>为什么不是常数（DC）：DSP 的抑制区间是<b>人声频带</b>（约 100 Hz–5.2 kHz，
     * 两端各有一段升余弦斜坡），而直流落在「低频保留」那一侧——拿它当素材，
     * 「正中间的东西」根本不会被拿掉，测试就白测了。所以这里用两个都在抑制区间内的
     * 正弦（440 Hz 与 1 kHz）叠加，峰值约 0.8。
     */
    private static Path writeCenterToneWav(String name, int frames, int rate) throws IOException {
        float[] pcm = new float[frames];
        for (int i = 0; i < frames; i++) {
            double t = (double) i / (double) rate;
            pcm[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * t)
                    + 0.3 * Math.sin(2.0 * Math.PI * 1000.0 * t));
        }
        Path dir = Files.createTempDirectory("qplayer-vocalsep-" + name);
        Path path = dir.resolve(name + ".wav");
        writeWav(path, pcm, pcm, rate);   // 左右相同 = 摆在正中间
        return path;
    }

    /** {@code [from, to)} 区间里的峰值绝对值。 */
    private static float peak(float[] samples, int from, int to) {
        float peak = 0.0f;
        for (int i = Math.max(from, 0); i < Math.min(to, samples.length); i++) {
            float a = Math.abs(samples[i]);
            if (a > peak) {
                peak = a;
            }
        }
        return peak;
    }

    /**
     * 造一个 16 bit / 立体声 WAV。
     *
     * <p>与 {@code AiMixTest} 里那份是同一套造法（那边是私有的，这里复制一份）：
     * {@code prepare} 收的是<b>句柄</b>，所以要验「按时间位置读一段」就真的得有文件。
     */
    private static void writeWav(Path path, float[] left, float[] right, int rate) throws IOException {
        int frames = left.length;
        int dataLen = frames * 2 * 2; // 双声道 × 16 bit
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataLen);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);                 // PCM
        buf.putShort((short) 2);                 // 双声道
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

    /**
     * 只认 16 bit PCM WAV 的最小解码器<b>替身</b>（测试用，不是产品代码）。
     *
     * <p>产品路径上 {@code Decoder.open} 打不开音源时会抛 {@link AudioException}，
     * 这里对不存在的文件给出同样的行为。
     */
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
