package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import dev.t1m3.qplayer.audio.model.BeatDetector;
import dev.t1m3.qplayer.audio.model.BeatModels;
import dev.t1m3.qplayer.audio.model.InferException;
import dev.t1m3.qplayer.audio.model.MelParams;
import dev.t1m3.qplayer.audio.model.ModelAvailability;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import dev.t1m3.qplayer.audio.model.ModelFetchers;
import dev.t1m3.qplayer.audio.model.ModelManifest;
import dev.t1m3.qplayer.audio.model.PeakParams;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * AI 模式的对齐测试：对应 Sonalis {@code sonalis-mix/src/automix.rs} 里
 * {@code mod window_limit_tests}（4 条）与 {@code mod tests} 中<b>被测对象是 {@link AiMix}
 * 或 {@link Automix} 的自由函数</b>的那 20 条。
 *
 * <p>加上 {@code HarmonicBlendTest} 里的 7 条，正好覆盖上游那 31 条，
 * 一条不少。方法名按 camelCase 逐条对应上游，断言强度与容差一致
 * （{@code assert_eq!} → {@code assertEquals}，{@code assert!(x, "{}", y)} →
 * {@code assertTrue(msg, x)} 并把上游那段解释原样带上）。
 *
 * <h2>为什么这个类里有一套 WAV 解码器</h2>
 *
 * <p>上游 {@code a_long_window_still_gets_beat_aligned} 是<b>唯一</b>需要真实文件的
 * 回归：{@code prepare} 收的是句柄，而它要读的是文件<b>尾部</b>——这一点没法用内存里的
 * 缓冲替代（见那条测试自己的注释）。上游直接用了 {@code symphonia} 的 WAV 支持；
 * QPlayer 的 player-core <b>故意没有解码器实现</b>（桌面是 jaudiotagger+jlayer+jflac，
 * 安卓是 MediaCodec，两边不能共用，见 {@link Decoder} 的类注释），所以这里由测试
 * 自己装一个只认 16 bit PCM WAV 的最小实现：<b>它是测试替身，不是产品代码</b>。
 * 另一个作用是「文件不存在」那几条：产品路径上 {@code Decoder.open} 会抛
 * {@link AudioException}，测试替身必须提供同样的行为。
 */
public class AiMixTest {

    private static final int RATE = 48_000;

    /** {@code core::f32::consts::PI} 的 f32 镜像（造素材的相位链要按 f32 走）。 */
    private static final float PI_F32 = (float) Math.PI;

    /** 上游 {@code FRAC_PI_4}：平台中心就是它（45°）。 */
    private static final float FRAC_PI_4 = (float) (Math.PI / 4.0);

    /**
     * 装上测试用的解码器替身。
     *
     * <p>用 {@code @Before} 而不是 {@code @BeforeClass}：{@link Decoder#install} 是
     * 全局静态状态，每一条测试都自己装一遍，就不怕被别的测试类换掉。
     *
     * <p>{@code BeatModels} 同理——它是「宿主的推理底座」这同一类全局状态。
     * <b>默认擦干净</b>：没接线时 {@code AiMix.prepare} 走的正是硬算那条路，
     * 于是本类里那些对齐上游的测试不受模型路线影响（结果与移植前逐位相同）。
     */
    @Before
    public void installTestDecoder() {
        Decoder.install(new WavOpener());
        BeatModels.uninstall();
        // 清单 / 目录 / 可用性都是全局静态状态，逐条测试自己装一遍再擦掉
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    // ------------------------------------------------------------------
    // 造素材的辅助（对应上游 tests 模块里的 buf / empty / write_wav / click_track）
    // ------------------------------------------------------------------

    /** 上游 {@code fn buf(frames, v)}：双声道、每个采样都是 {@code v}。 */
    private static AudioBuf buf(int frames, float v) {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
        b.setFrames(frames);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < frames; i++) {
                b.channelWriter(c).put(i, v);
            }
        }
        return b;
    }

    /** 上游 {@code fn empty(frames)}：有效帧数为 0 的双声道缓冲。 */
    private static AudioBuf empty(int frames) {
        return new AudioBuf(ChannelLayout.STEREO, Math.max(frames, 1));
    }

    /**
     * {@code f32::is_finite} 的镜像。
     *
     * <p><b>不能用 {@code Float.isFinite(float)}</b>：那是安卓 API 24+，
     * 低版本运行期会 {@code NoSuchMethodError}（见 docs/audio-port.md 的坑表）。
     */
    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ==================================================================
    // mod window_limit_tests（上游 4 条）
    // ==================================================================

    /** 上界必须真的生效，而且是「一半」。 */
    /**
     * 这条判据量的是**我们自己的机制**（电平匹配 / 朴素融合的终点电平 / 非对称淡化形状 /
     * 学习EQ 的逐位回退 ✓）。这些判据都拿 **`PLAIN_BLEND`（朴素融合 ✓）当基准** ✗ ——
     * 而 `PLAIN_BLEND` 正是**我们抄他的档 1**（"constant-power cross-fade" ✓）⇒ 它的增益
     * **本来就该**换成他的 `out_gain` ✓（用户 2026-10-08「完全照搬他的写法」✓）⇒ 基准**合理地**变了 ✓。
     * ⚠️ 第 62 轮我曾**一刀切关掉六路** ✗ ⇒ 反而多出 1 条红 ✓（别的路与这些判据无关 ✓）；
     * 现在**只关"增益"这一路** ✓ ⇒ 判据量的对象回到"我们自己的增益" ✓，**一个字都没动断言** ✗。
     */
    @org.junit.BeforeClass
    public static void useOurOwnMixingOnly() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(false);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleHarmonyDevicesPort(false);
    }

    @org.junit.AfterClass
    public static void restoreApplePorts() {
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleGainPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleHarmonyDevicesPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleFilterPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleReverbParamsPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleDelayParamsPort(true);
        dev.t1m3.qplayer.audio.mix.AiMix.setAppleRemixPort(true);
    }

    @Test
    public void windowIsCappedToTheFractionOfTheSong() {
        // **2026-10-06 按证据重述**（第一阶段工作五）：上限从"半首歌"放到
        // {@code MixTuning.MAX_WINDOW_FRACTION} = 0.7（用户：「只保留技术上不可能的限制，
        // 去掉审美上保守的限制」）。8 秒的曲子 ⇒ 上限 5.6 秒。
        // 判据本身（"超过上限就削到上限、之内不动、恰好等于不削"）一条没改，只换了那个数，
        // 并且**从常量算**而不是写死——上限以后再动时它会跟着走。
        long cap = Math.round(8.0 * dev.t1m3.qplayer.audio.mix.MixTuning.MAX_WINDOW_FRACTION * RATE);
        assertEquals("16 秒的窗口落在 8 秒的曲子上 → 削到上限", cap,
                Automix.limitWindow(16L * RATE, 8.0, RATE));
        // 本来就在上界之内的窗口不该被动
        assertEquals(2L * RATE, Automix.limitWindow(2L * RATE, 8.0, RATE));
        // 长曲子不受影响
        assertEquals(16L * RATE, Automix.limitWindow(16L * RATE, 180.0, RATE));
        // 恰好等于上界时不该被削
        assertEquals(cap, Automix.limitWindow(cap, 8.0, RATE));
    }

    /** 拿不到时长时<b>不猜</b>。 */
    @Test
    public void unknownDurationIsNotGuessed() {
        assertEquals(16L * RATE, Automix.limitWindow(16L * RATE, 0.0, RATE));
        assertEquals(16L * RATE, Automix.limitWindow(16L * RATE, -1.0, RATE));
        assertEquals(16L * RATE, Automix.limitWindow(16L * RATE, 8.0, 0));
    }

    /**
     * 窗口<b>永远不能变成 0</b>：那会让过渡彻底不触发，
     * 用户以为开了自动混音，实际听到的是硬接。
     */
    @Test
    public void windowIsNeverZero() {
        assertTrue(Automix.limitWindow(0L, 8.0, RATE) >= 1L);
        assertTrue(Automix.limitWindow(100L, 0.01, RATE) >= 1L);
        assertTrue(Automix.limitWindow(1L, 1e-9, RATE) >= 1L);
    }

    /** 夹取只该让窗口变小，绝不能变大。 */
    @Test
    public void limitOnlyShrinks() {
        double[] songs = {0.5, 3.0, 8.0, 60.0, 600.0};
        double[] wantSecs = {0.05, 1.0, 4.0, 16.0, 64.0};
        for (double song : songs) {
            for (double want : wantSecs) {
                long ideal = (long) (want * RATE);
                long got = Automix.limitWindow(ideal, song, RATE);
                assertTrue("曲长 " + song + "s 想要 " + want + "s，结果被放大到 " + got + " 帧",
                        got <= Math.max(ideal, 1L));
            }
        }
    }

    // ==================================================================
    // 接法与窗口（上游 5 条）
    // ==================================================================

    @Test
    public void styleLabelsAreStable() {
        for (MixStyle s : MixStyle.ALL) {
            assertFalse(s.label().isEmpty());
            assertFalse(s.specName().isEmpty());
            assertTrue(s.rewritesSamples());
            assertTrue(s.fallbackSecs() > 0.0f);
        }
        assertEquals(MixStyle.PLAIN_BLEND, MixStyle.DEFAULT);
        assertEquals("beatCut", MixStyle.BEAT_CUT.specName());
        assertEquals("buildUp", MixStyle.SHOW_MODE.specName());
    }

    /** 五种接法的窗口都必须是正的，否则过渡根本不会触发。 */
    @Test
    public void everyStyleHasAPositiveWindow() {
        for (MixStyle s : MixStyle.ALL) {
            AiMix t = new AiMix(s, RATE, 2);
            assertTrue(s.specName() + " 的窗口为 0", t.window() > 0L);
            assertTrue(t.windowFrames(RATE) > 0L);
        }
    }

    /** 分析失败（文件不存在）时必须退回固定窗口，不能抛出去、不能把窗口清零。 */
    @Test
    public void prepareSurvivesAMissingFile() {
        AiMix t = new AiMix(MixStyle.BASS_SWAP, RATE, 2);
        long before = t.window();
        TransitionPlan plan = t.prepare("nope-a.wav", "nope-b.wav", RATE);
        assertTrue("窗口不该被清零", t.window() > 0L);
        assertEquals("分析失败时窗口应保持原样", before, t.window());
        assertTrue("没有分析就不该有跳过量", plan.skipSecs <= 0.0);
        assertTrue(plan.pitch.isZero());
        assertTrue("应当说明原因：" + plan.note, plan.note.contains("失败"));
    }

    /**
     * 一次失败的分析<b>不能留下上一首歌的窗口</b>。
     *
     * <p>报错文案写着「退回固定窗口」，那这句话就必须是真的：引擎对象是
     * 跨曲目复用的，而窗口、拍点、时长都是「算出来才写」。
     * 不先清基线的话，这里会继续用上一首曲子对拍拉长后的窗口——
     * 过渡会在错误的位置触发，而且窗口长度也是错的。
     */
    @Test
    public void aFailedPrepareRestoresTheFixedWindow() {
        AiMix t = new AiMix(MixStyle.BASS_SWAP, RATE, 2);
        // 假装上一首歌对拍之后把窗口拉长到了 20 秒
        t.window = 20L * RATE;
        t.cutAt = 0.42f;

        TransitionPlan plan = t.prepare("nope-a.wav", "nope-b.wav", RATE);

        assertEquals("分析失败应退回固定窗口，而不是留着上一首的窗口",
                Automix.fallbackWindow(MixStyle.BASS_SWAP, RATE), t.window());
        assertTrue("应当如实说明：" + plan.note, plan.note.contains("固定窗口"));
        assertEquals("上一首的切点不该留下", 0.0f, t.cutAt, 0.0f);
        assertNull("上一首的速度不该留下", t.outroBpm());
        assertNull(t.introBpm());
    }

    /** 固定窗口<b>永远不能是 0</b>：那是硬接，而界面还写着「自动混音」。 */
    @Test
    public void theFixedWindowIsNeverZero() {
        for (MixStyle s : MixStyle.ALL) {
            assertTrue(s.specName() + " 的退路窗口为 0", Automix.fallbackWindow(s, RATE) > 0L);
            // 采样率低到 1 Hz 也不该退化成 0
            assertTrue(s.specName() + " 在 1 Hz 下退化成 0", Automix.fallbackWindow(s, 1) > 0L);
        }
    }

    // ==================================================================
    // 五种接法的渲染（上游 7 条）
    // ==================================================================

    /** 朴素融合在两端必须分别等于纯 A 与纯 B。 */
    @Test
    public void plainBlendEndpointsArePure() {
        AiMix t = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2);
        AudioBuf a = buf(8, 1.0f);
        AudioBuf b = buf(8, 0.25f);
        AudioBuf out = empty(8);

        t.render(out, a, b, 0.0f);
        assertTrue("起点应是纯 A", Math.abs(out.channel(0).get(0) - 1.0f) < 1e-6f);
        t.render(out, a, b, 1.0f);
        assertTrue("终点应是纯 B", Math.abs(out.channel(0).get(0) - 0.25f) < 1e-6f);
    }

    /** 拍点剪切：切点之前出场曲必须<b>一个比特都不改</b>。 */
    @Test
    public void beatCutKeepsOutgoingUntouchedBeforeTheCut() {
        AiMix t = new AiMix(MixStyle.BEAT_CUT, RATE, 2);
        t.cutAt = 0.8f;
        AudioBuf a = buf(8, 0.7f);
        AudioBuf b = buf(8, 0.1f);
        AudioBuf out = empty(8);

        t.render(out, a, b, 0.3f);
        for (int i = 0; i < 8; i++) {
            assertTrue("切点前第 " + i + " 帧被改动了：" + out.channel(0).get(i),
                    Math.abs(out.channel(0).get(i) - 0.7f) < 1e-9f);
        }

        // 切点之后必须已经在往 B 走
        t.render(out, a, b, 1.0f);
        assertTrue("窗口末尾应是纯 B，实际 " + out.channel(0).get(0),
                Math.abs(out.channel(0).get(0) - 0.1f) < 1e-6f);
    }

    /**
     * 低音交接：<b>低频只在平台期换手</b>。
     *
     * <p>判据用<b>与出场素材的相关性</b>，而不是某个采样点的符号：
     * 60 Hz 在 2400 点处正好过零，那一点判断不出任何东西——
     * 这个测试最初就是按「取第 2400 点的符号」写的，于是把一个正确的实现
     * 判成了失败。<b>测量方式本身出错，比实现出错更难发现。</b>
     *
     * <p>默认形状（{@code together = 0.5}）：平台占 [0.25, 0.75]，低频在平台开始前
     * 完全还是出场曲、平台结束时已经完全交给进场曲。
     */
    @Test
    public void bassSwapHoldsTheBassUntilThePlateau() {
        AiMix t = new AiMix(MixStyle.BASS_SWAP, RATE, 2);
        int n = 4_800;

        // 用低频正弦，落到低频路里
        AudioBuf a = new AudioBuf(ChannelLayout.STEREO, n);
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, n);
        a.setFrames(n);
        b.setFrames(n);
        for (int i = 0; i < n; i++) {
            float s = (float) Math.sin((double) (2.0f * PI_F32 * 60.0f * (float) i / (float) RATE))
                    * 0.5f;
            a.channelWriter(0).put(i, s);
            a.channelWriter(1).put(i, s);
            // 进场曲相位相反，便于用相关性判断归属
            b.channelWriter(0).put(i, -s);
            b.channelWriter(1).put(i, -s);
        }
        AudioBuf out = empty(n);

        // p = 0.25：还在前半段，低频应当仍与出场曲正相关
        t.render(out, a, b, 0.25f);
        float c1 = correlation(out, a, n);
        assertTrue("前半段低频应与出场曲正相关，实际 " + c1, c1 > 0.0f);

        // p = 0.9：低频已经换过去了，相关性应当翻过来
        t.reset();
        t.render(out, a, b, 0.9f);
        float c2 = correlation(out, a, n);
        assertTrue("后半段低频应已换到进场曲，实际 " + c2, c2 < 0.0f);
    }

    /** 上游测试里的 {@code corr}：逐样本乘积累加，全程 f32。 */
    private static float correlation(AudioBuf o, AudioBuf r, int n) {
        float sum = 0.0f;
        for (int i = 0; i < n; i++) {
            sum += o.channel(0).get(i) * r.channel(0).get(i);
        }
        return sum;
    }

    /**
     * <b>出场曲提前放完时，拍点剪切也必须把斜坡走完。</b>
     *
     * <p>编排器按块推进（一块 4096 帧 ≈ 48 kHz 下 85 ms），出场曲在触发时可能只剩
     * 不到一个块；而拍点剪切的窗口<b>比一个块还短</b>（0.05~0.11 秒），于是切点
     * 永远到不了。老代码在这种情况下两路都没内容、{@code render} 返回 0，调用方走
     * 兜底硬接——<b>30 ms 消咔哒斜坡一次都没跑</b>。
     *
     * <p>实测证据（调参台渲染两段素材）：接缝处的最大跳变 0.4888，
     * 而其余四种接法都是 0.0906（素材自己的瞬态基线）。
     * 修好之后同样是 0.0906——接缝不再贡献任何台阶。
     */
    @Test
    public void beatCutStillFadesWhenTheOutgoingTrackEndsEarly() {
        AiMix t = new AiMix(MixStyle.BEAT_CUT, RATE, 2);
        t.cutAt = 0.7f;
        AudioBuf gone = empty(8);        // 出场曲已经放完
        AudioBuf to = buf(8, 1.0f);      // 进场曲满幅
        AudioBuf out = empty(8);

        // 第一次调用：交接从这一刻开始（此时增益为 0，输出静音是正常的）
        t.render(out, gone, to, 0.2f);

        // 窗口 2400 帧、斜坡 1440 帧 → 斜坡折算成 0.6 个进度。
        // 走完一个斜坡之后应当**已经基本换到进场曲**；
        // 若还是「等切点」的老行为，这里只会到 0.5（等功率在 0.7 处的一半）。
        t.render(out, gone, to, 0.8f);
        assertTrue("斜坡走完后应当接近纯进场曲，实际 " + out.channel(0).get(0)
                        + "（~0.5 说明又退回没有斜坡的硬接了）",
                out.channel(0).get(0) > 0.9f);
    }

    /**
     * <b>曲线必须在块内逐样本走</b>，不能每块只算一次。
     *
     * <p>编排器一块给一个 {@code progress}，而一块是 4096 帧（48 kHz 下 85 ms）。
     * 拍点剪切的窗口（0.05~0.11 秒）比一块还短，整块套同一个增益的结果是：
     * 那条 30 ms 斜坡<b>根本不存在</b>，渲染出来是一次干净的硬切——
     * 而消咔哒正是它存在的唯一理由。
     *
     * <p>长素材（40 秒）上实测到的症状：过渡窗口内 RMS 是 −inf dB（整段静音），
     * 接缝上带一个 0.16 的台阶。
     */
    @Test
    public void beatCutRampsWithinABlockNotOncePerBlock() {
        AiMix t = new AiMix(MixStyle.BEAT_CUT, RATE, 2);
        // 窗口 2400 帧（0.05 秒），切点放在正中
        t.cutAt = 0.5f;
        AudioBuf from = buf(512, 1.0f);
        // 进场曲留静音，这样输出就等于出场侧那条增益曲线本身
        AudioBuf silentTo = buf(512, 0.0f);
        AudioBuf out = empty(512);

        // 从切点之前 8 帧开始渲染**一整块**：曲线应当在这一块之内就开始下降
        float p = 0.5f - 8.0f / 2400.0f;
        t.render(out, from, silentTo, p);

        float first = out.channel(0).get(0);
        float last = out.channel(0).get(511);
        assertTrue("块内必须已经在走下坡，实际首 " + first + " 末 " + last
                        + "（相等 = 又变成每块算一次了）",
                first > last + 0.1f);
        // 而且是**连续**地下坡，不是一步跳下去
        for (int i = 1; i < 512; i++) {
            float step = Math.abs(out.channel(0).get(i) - out.channel(0).get(i - 1));
            assertTrue("第 " + i + " 帧跳了 " + step + "，曲线不连续", step < 0.05f);
        }
    }

    /** 尾音骑行：中点的出场侧必须比等功率淡化时<b>更响</b>（尾巴被骑住了）。 */
    @Test
    public void tailRideHoldsTheOutgoingLongerThanPlainBlend() {
        AudioBuf a = buf(4, 1.0f);
        AudioBuf b = buf(4, 1.0f);

        AiMix plain = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2);
        AiMix ride = new AiMix(MixStyle.TAIL_RIDE, RATE, 2);
        AudioBuf o1 = empty(4);
        AudioBuf o2 = empty(4);
        plain.render(o1, a, b, 0.5f);
        ride.render(o2, a, b, 0.5f);

        // 两者都是等功率（两路全 1 时功率和相同），但骑行把能量重心后移：
        // 出场侧权重 √0.75 ≈ 0.866 对 0.707
        assertTrue("骑行时两路贡献更接近，总输出应更低：" + o2.channel(0).get(0)
                        + " vs " + o1.channel(0).get(0),
                o2.channel(0).get(0) < o1.channel(0).get(0));
        // 关键是功率仍守着等功率关系
        float gf = (float) Math.sqrt((double) (1.0f - 0.25f));
        float gt = 0.5f;
        float expect = gf + gt;
        assertTrue("骑行中点应为 " + expect + "，实际 " + o2.channel(0).get(0),
                Math.abs(o2.channel(0).get(0) - expect) < 1e-5f);
    }

    /** 表现模式：输出必须有限，两端必须收敛到纯 A / 纯 B。 */
    @Test
    public void showModeIsSaneAtBothEnds() {
        AiMix t = new AiMix(MixStyle.SHOW_MODE, RATE, 2);
        int n = 4_800;
        AudioBuf a = new AudioBuf(ChannelLayout.STEREO, n);
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, n);
        a.setFrames(n);
        b.setFrames(n);
        for (int i = 0; i < n; i++) {
            float s = (float) Math.sin((double) ((float) i * 0.01f)) * 0.4f;
            a.channelWriter(0).put(i, s);
            a.channelWriter(1).put(i, s);
            b.channelWriter(0).put(i, s * 0.5f);
            b.channelWriter(1).put(i, s * 0.5f);
        }
        AudioBuf out = empty(n);

        // 起点：出场曲全量、进场曲全静音
        t.render(out, a, b, 0.0f);
        for (int i = n / 2; i < n; i++) {
            assertTrue("出现了非有限值", isFinite(out.channel(0).get(i)));
        }

        // 终点：出场曲归零、进场曲全量
        t.render(out, a, b, 1.0f);
        float peakFrom = Math.abs(a.channel(0).get(n - 1));
        float got = Math.abs(out.channel(0).get(n - 1));
        assertTrue("终点应约等于进场曲的 0.5 倍振幅，实际 " + got,
                Math.abs(got - peakFrom * 0.5f) < 0.05f);
    }

    /** 缓冲长度不一致时不该抛异常（一首比另一首先结束）。 */
    @Test
    public void mismatchedLengthsDoNotPanic() {
        AiMix t = new AiMix(MixStyle.BASS_SWAP, RATE, 2);
        AudioBuf shortBuf = buf(64, 0.5f);
        AudioBuf longBuf = buf(512, 0.5f);
        AudioBuf out = empty(4096);
        int n = t.render(out, shortBuf, longBuf, 0.5f);
        assertEquals("应按较长的一路产出", 512, n);

        AiMix t2 = new AiMix(MixStyle.SHOW_MODE, RATE, 2);
        int n2 = t2.render(out, shortBuf, longBuf, 0.5f);
        assertEquals(512, n2);
    }

    // ==================================================================
    // 平台曲线（上游 3 条）
    // ==================================================================

    /**
     * <b>平台不消耗任何余量</b>——{@code cos²θ + sin²θ = 1} 对任何 θ 都成立，
     * 所以无论 {@code together} 取多少，两路功率和都逐点等于 1。
     *
     * <p>这正是它敢在中段停住的原因：平台既不会让中段塌下去（线性淡化会掉 3 dB），
     * 也不会顶上去。<b>用长度换不来这个性质，只有形状能。</b>
     */
    @Test
    public void thePlateauDoesNotWasteAnyHeadroom() {
        float[] togethers = {0.0f, 0.2f, 0.5f, 0.9f};
        for (float together : togethers) {
            float prev = -1.0f;
            for (int i = 0; i <= 100; i++) {
                float p = (float) i / 100.0f;
                float theta = Automix.togetherAngle(p, together);
                float gf = (float) Math.cos((double) theta);
                float gt = (float) Math.sin((double) theta);
                float power = gf * gf + gt * gt;
                assertTrue("together=" + together + " p=" + p + " 处功率和 " + power
                        + "，应当恒为 1", Math.abs(power - 1.0f) < 1e-5f);
                assertTrue("角度必须单调不减（不能回头）", theta >= prev - 1e-6f);
                prev = theta;
            }
        }

        // 两端：起点全部给出场曲、终点全部给进场曲
        float[] ends = {0.0f, 0.5f};
        for (float together : ends) {
            float t0 = Automix.togetherAngle(0.0f, together);
            float c0 = (float) Math.cos((double) t0);
            float s0 = (float) Math.sin((double) t0);
            assertTrue(Math.abs(c0 - 1.0f) < 1e-6f && Math.abs(s0) < 1e-6f);
            float t1 = Automix.togetherAngle(1.0f, together);
            float c1 = (float) Math.cos((double) t1);
            float s1 = (float) Math.sin((double) t1);
            assertTrue(Math.abs(c1) < 1e-6f && Math.abs(s1 - 1.0f) < 1e-6f);
        }
    }

    /** 平台期两曲<b>等响</b>（各 0.7071）——这正是它听上去「叠在一起」的原因。 */
    @Test
    public void thePlateauSitsAtEqualLoudness() {
        float centre = Automix.togetherAngle(0.5f, 0.5f);
        assertTrue("平台中心应停在 45°", Math.abs(centre - FRAC_PI_4) < 1e-6f);
        float gf = (float) Math.cos((double) centre);
        float gt = (float) Math.sin((double) centre);
        assertTrue("两曲增益必须相等", Math.abs(gf - gt) < 1e-6f);
        assertTrue("等响点是 cos(π/4)，实际 " + gf, Math.abs(gf - 0.7071068f) < 1e-5f);

        // 平台是**一段**而不是一个点
        float[] inPlateau = {0.3f, 0.4f, 0.5f, 0.6f, 0.7f};
        for (float p : inPlateau) {
            float t = Automix.togetherAngle(p, 0.5f);
            assertTrue("p=" + p + " 应当仍在平台内", Math.abs(t - centre) < 1e-6f);
        }
        // 两端之外必须已经在动
        assertTrue(Automix.togetherAngle(0.1f, 0.5f) < centre);
        assertTrue(Automix.togetherAngle(0.9f, 0.5f) > centre);
    }

    /**
     * {@code together = 0} 必须<b>逐点等于</b>原来那条纯等功率淡化曲线。
     *
     * <p>这条守住「新形状是可选的」：调参的人随时能退回老听感做 A/B。
     */
    @Test
    public void zeroTogetherReproducesAPlainEqualPowerFade() {
        for (int i = 0; i <= 100; i++) {
            float p = (float) i / 100.0f;
            Mixer.Gains g = Mixer.equalPowerGains(p);
            float t = Automix.togetherAngle(p, 0.0f);
            float c = (float) Math.cos((double) t);
            float s = (float) Math.sin((double) t);
            assertTrue("p=" + p + " 应当与等功率曲线一致", Math.abs(c - g.from) < 1e-6f);
            assertTrue(Math.abs(s - g.to) < 1e-6f);
        }
    }

    // ==================================================================
    // 真实文件上的对拍回归（上游 1 条）
    // ==================================================================

    /**
     * <b>理想窗口装不进分析窗时，对拍会静默失效——这一条守住它。</b>
     *
     * <p>{@code ANALYSIS_SECS} 是 12 秒，而 ShowMode 要 32 拍：120 BPM 下就是 16 秒，
     * 比分析窗还长。老代码里 {@code tail_dur > w0} 判为假，整段对拍被跳过——
     * 窗口停在理想值、起点不落在拍上，而 {@code note} 照样写着 BPM，谁也不会发现。
     * 曲风稍微慢一点，TailRide（24 拍 = 12 秒）会一起失效。
     *
     * <p>曲子刻意取 <b>40.13 秒</b>：它不是拍长（0.5 秒）的整数倍，
     * 所以「对齐了」与「没对齐」的结果必然不同，断言才有区分力。
     */
    @Test
    public void aLongWindowStillGetsBeatAligned() throws IOException {
        float[] outro = clickTrack(120.0f, 40.13, RATE);
        float[] intro = clickTrack(120.0f, 20.0, RATE);

        Path dir = Files.createTempDirectory("qplayer-align");
        Path a = dir.resolve("outro.wav");
        Path b = dir.resolve("intro.wav");
        writeWav(a, outro, RATE);
        writeWav(b, intro, RATE);

        AiMix t = new AiMix(MixStyle.SHOW_MODE, RATE, 2);
        TransitionPlan plan = t.prepare(a.toString(), b.toString(), RATE);
        long got = t.window();

        // 理想窗口 = 拍数 × 拍长。拍长从**引擎自己估出来的** BPM 来算，
        // 免得把测试绑死在「估计值恰好等于 120.0」上。
        Float bpmOpt = t.outroBpm();
        assertNotNull("脉冲轨应当能估出速度", bpmOpt);
        double bpm = bpmOpt.doubleValue();
        double period = 60.0 / bpm;
        long ideal = Math.round((double) MixStyle.SHOW_MODE.beats() * period * (double) RATE);

        assertTrue("对齐只该拉长窗口，实际 " + got + " < 理想 " + ideal + "（" + plan.note + "）",
                got >= ideal);
        assertTrue("对齐最多多一拍，实际多出 " + (got - ideal) + " 帧（" + plan.note + "）",
                got <= ideal + (long) (period * (double) RATE) + 1L);
        assertTrue("窗口正好等于理想值，说明**根本没做对齐**：曲长不是拍长的整数倍，"
                        + "对齐后必然落在别处（" + plan.note + "）",
                got > ideal);

        Files.deleteIfExists(b);
        Files.deleteIfExists(a);
        Files.deleteIfExists(dir);
    }

    // ==================================================================
    // 过渡的默认行为与变调器工厂（上游 3 条）
    // ==================================================================

    /** 全部过渡的 {@code prepare} 默认实现都不能抛异常（含 gapless/crossfade）。 */
    @Test
    public void defaultPrepareIsANoop() {
        TransitionPlan plan = Gapless.INSTANCE.prepare("x", "y", RATE);
        assertTrue(plan.isNoop());

        Crossfade c = new Crossfade(3.0f);
        TransitionPlan plan2 = c.prepare("x", "y", RATE);
        assertTrue(plan2.isNoop());
        assertTrue(c.pitchAt(0.5f).isZero());
    }

    /** 变调器工厂必须把变调量真的用上——这里曾经漏过。 */
    @Test
    public void pitchShifterFactoryAppliesSemitones() {
        PitchShifter ps = Automix.newPitchShifter(2, RATE, 12.0f, 4096);
        assertTrue("12 个半音应当翻倍，实际比例 " + ps.ratio(),
                Math.abs(ps.ratio() - 2.0f) < 1e-3f);
        PitchShifter flat = Automix.newPitchShifter(2, RATE, 0.0f, 4096);
        assertTrue(flat.isUnity());
    }

    /** {@code introStartAt} 默认必须是 0：只有拍点剪切才需要推迟进场。 */
    @Test
    public void planDefaultsToStartingTheIntroImmediately() {
        TransitionPlan p = TransitionPlan.NONE;
        assertEquals(0.0f, p.introStartAt, 0.0f);
        assertTrue(p.isNoop());
    }

    // ==================================================================
    // 模型路线：优先用模型，失败如实退回硬算（本仓库新增，上游没有对应测试）
    // ==================================================================

    /**
     * 装了模型底座时，{@code prepare} <b>用模型的拍点</b>，并在 note 里标注来源。
     *
     * <p>假检测器给一条固定的 120 BPM 栅格（首拍 0.02 秒），所以断言可以精确：
     * {@code outroBpm()} 必须是 120，note 里必须出现「（模型）」。
     *
     * <p>这条测试的另一半意义是「模型的那份 Tempo 真的被用上了」：
     * 素材本身是 100 BPM 的脉冲轨（硬算会给出 ~100），模型给 120，
     * 于是「用的是谁」从 {@code outroBpm()} 就能看出来。
     */
    @Test
    public void modelBeatsArePreferredAndTheNoteSaysSo() throws IOException {
        float[] outro = clickTrack(100.0f, 40.13, RATE);
        float[] intro = clickTrack(100.0f, 20.0, RATE);

        Path dir = Files.createTempDirectory("qplayer-model-route");
        Path a = dir.resolve("outro.wav");
        Path b = dir.resolve("intro.wav");
        writeWav(a, outro, RATE);
        writeWav(b, intro, RATE);

        FixedGridOpener opener = new FixedGridOpener(120.0f);
        BeatModels.install(opener);
        Path models = fakeModelDir();
        try {
            AiMix t = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2);
            TransitionPlan plan = t.prepare(a.toString(), b.toString(), RATE);

            assertNotNull("模型给了拍点，速度就该判出来", t.outroBpm());
            assertEquals("应当用模型给的 120 BPM（素材本身是 100）",
                    120.0f, t.outroBpm().floatValue(), 0.05f);
            assertTrue("note 里必须标出用的是模型：" + plan.note,
                    plan.note.contains("（模型）"));
            assertFalse("模型成功了就不该标成硬算：" + plan.note,
                    plan.note.contains("（硬算）"));
        } finally {
            BeatModels.uninstall();
            deleteRecursively(models);
            Files.deleteIfExists(b);
            Files.deleteIfExists(a);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * 模型坏掉时<b>如实退回硬算</b>，而且 note 必须说「硬算」——
     * <b>不许在模型失败时假装「模型版」</b>。
     *
     * <p>这是本轮最重要的一条纪律：用户看到「128.0 BPM」时要能知道它从哪来，
     * 否则「这一版 BPM 到底变准了没有」永远无法判断。
     */
    @Test
    public void aBrokenModelFallsBackToHardComputationAndSaysSo() throws IOException {
        float[] outro = clickTrack(120.0f, 40.13, RATE);
        float[] intro = clickTrack(120.0f, 20.0, RATE);

        Path dir = Files.createTempDirectory("qplayer-model-broken");
        Path a = dir.resolve("outro.wav");
        Path b = dir.resolve("intro.wav");
        writeWav(a, outro, RATE);
        writeWav(b, intro, RATE);

        // 参照实现：同一条素材在**没有模型**时硬算出来的结论
        AiMix reference = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2);
        TransitionPlan referencePlan = reference.prepare(a.toString(), b.toString(), RATE);

        BeatModels.install(new BrokenOpener());
        Path models = fakeModelDir();
        try {
            AiMix t = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2);
            TransitionPlan plan = t.prepare(a.toString(), b.toString(), RATE);

            assertTrue("note 里必须标出用的是硬算：" + plan.note,
                    plan.note.contains("（硬算）"));
            assertFalse("模型失败了就不许标成模型：" + plan.note,
                    plan.note.contains("（模型）"));
            assertEquals("退回硬算的结论必须与「没有模型」时逐位相同",
                    Float.floatToIntBits(reference.outroBpm().floatValue()),
                    Float.floatToIntBits(t.outroBpm().floatValue()));
            assertEquals(reference.window(), t.window());
            assertTrue(referencePlan.note.length() > 0);
        } finally {
            BeatModels.uninstall();
            deleteRecursively(models);
            Files.deleteIfExists(b);
            Files.deleteIfExists(a);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * 喂给模型的必须是 <b>22050 Hz 单声道</b>（模型前端的采样率），
     * 而不是素材自己的 48 kHz。
     *
     * <p>上游的约定是「采样率不符时由调用方先重采样，模型里不暗中重采样」，
     * 所以这件事只能在这一层保证。断言的是<b>长度</b>：12 秒的 48 kHz 素材
     * 重采样之后应当是 22050 × 12 上下，而不是 48000 × 12。
     */
    @Test
    public void theModelSeesPcmAtItsOwnSampleRate() throws IOException {
        float[] outro = clickTrack(120.0f, 40.13, RATE);
        float[] intro = clickTrack(120.0f, 20.0, RATE);

        Path dir = Files.createTempDirectory("qplayer-model-rate");
        Path a = dir.resolve("outro.wav");
        Path b = dir.resolve("intro.wav");
        writeWav(a, outro, RATE);
        writeWav(b, intro, RATE);

        FixedGridOpener opener = new FixedGridOpener(120.0f);
        BeatModels.install(opener);
        Path models = fakeModelDir();
        try {
            AiMix t = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2);
            t.prepare(a.toString(), b.toString(), RATE);

            assertTrue("模型应当被问过至少一次", opener.calls > 0);
            assertEquals("模型要的是 22050 Hz", 22_050, opener.params.sampleRate);
            long want = 22_050L * 12L;
            assertTrue("喂给模型的应当是约 12 秒的 22050 Hz PCM，实际 " + opener.lastPcmLength
                            + " 帧", Math.abs((long) opener.lastPcmLength - want) < 22_050L / 2L);
            assertEquals("每次用完都要 close（宿主可以把它做成 no-op）",
                    opener.calls, opener.closes);
        } finally {
            BeatModels.uninstall();
            deleteRecursively(models);
            Files.deleteIfExists(b);
            Files.deleteIfExists(a);
            Files.deleteIfExists(dir);
        }
    }

    /** 递归删掉一个目录（测试自己造的临时目录，删不掉也不影响断言）。 */
    private static void deleteRecursively(Path path) {
        File[] kids = path.toFile().listFiles();
        if (kids != null) {
            for (File k : kids) {
                k.delete();
            }
        }
        path.toFile().delete();
    }

    /** 每一条测试跑完都把全局的推理底座与模型清单/目录擦掉。 */
    @After
    public void uninstallBeatModels() {
        BeatModels.uninstall();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    /**
     * 造一个「已经下载好首选模型」的模型目录（内容是一个字节的占位文件）。
     *
     * <p>清单与目录都换成测试自己那一份：{@code BeatModels.open} 会先问
     * {@code ModelDownloads.present}（磁盘上有没有、大小对不对），
     * 所以测试必须真的把文件造出来，而不是让底座假装有。替身底座不会去读它的内容。
     *
     * <p>这里是「一份清单、一处目录」带来的直接好处：不必去碰真实的模型目录，
     * 也不会因为本机下没下过 83 MB 的权重而让这条测试行为不同。
     */
    private static Path fakeModelDir() throws IOException {
        Path dir = Files.createTempDirectory("qplayer-fake-models");
        byte[] dummy = new byte[]{(byte) 0x4f};
        Files.write(dir.resolve(BeatModels.PREFERRED_ID + ".onnx"), dummy);
        String json = "{\"mirrors\":[\"https://hf-mirror.example.com/{file}\"],\"models\":[{"
                + "\"name\":\"" + BeatModels.PREFERRED_ID + "\","
                + "\"file\":\"" + BeatModels.PREFERRED_ID + ".onnx\","
                + "\"bytes\":" + dummy.length + ","
                + "\"sha256\":\"" + REPEATED_A + "\","
                + "\"enables\":\"beatGrid\",\"license\":\"MIT\","
                + "\"upstream\":\"https://example.invalid\",\"note\":\"测试替身\"}]}";
        ModelDownloads.install(new ModelDownloads(dir.toFile(),
                ModelManifest.parse(json), ModelFetchers.current()));
        return dir;
    }

    /** 64 位十六进制：清单会校验格式，而这条路径上没人去比哈希。 */
    private static final String REPEATED_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /**
     * 会给出固定 120 BPM 拍点栅格的测试替身。
     *
     * <p><b>它不是模型。</b> 真的推理在 {@code desktop-host} 的
     * {@code OnnxBeatDetectorTest} 里跑（那边用本地模型文件做端到端）；
     * 这里要被验的是「{@code AiMix.prepare} 会不会优先问模型、问的时候喂了什么、
     * 失败了怎么标注」——这三件事与模型内部无关，用一个固定的栅格更可推导。
     */
    private static final class FixedGridOpener implements BeatModels.Opener {

        private final float bpm;
        int calls;
        int closes;
        int lastPcmLength;
        MelParams params;

        FixedGridOpener(float bpm) {
            this.bpm = bpm;
        }

        @Override
        public BeatDetector open(String label, java.io.File path, MelParams params,
                                 PeakParams peaks) {
            this.params = params;
            return new GridDetector();
        }

        private final class GridDetector implements BeatDetector {

            @Override
            public String label() {
                return "synthetic-grid";
            }

            @Override
            public MelParams melParams() {
                return params;
            }

            @Override
            public PeakParams peakParams() {
                return PeakParams.defaults();
            }

            @Override
            public float[] beats(float[] pcm) {
                lastPcmLength = pcm.length;
                calls++;
                float period = 60.0f / bpm;
                float[] out = new float[25];
                for (int i = 0; i < out.length; i++) {
                    out[i] = 0.02f + (float) i * period;
                }
                return out;
            }

            @Override
            public void close() {
                closes++;
            }
        }
    }

    /** 永远打不开的测试替身。 */
    private static final class BrokenOpener implements BeatModels.Opener {

        @Override
        public BeatDetector open(String label, java.io.File path, MelParams params,
                                 PeakParams peaks) {
            throw InferException.load("测试替身故意打不开：" + label);
        }
    }

    // ==================================================================
    // 测试替身：一个只认 16 bit PCM WAV 的解码器
    // ==================================================================

    /**
     * 造一个 16 bit / 立体声 WAV（两个声道相同）。
     *
     * <p>对应上游测试里的 {@code fn write_wav}。上游对拍那条回归测试需要<b>真实文件</b>：
     * {@code prepare} 收的是句柄，而它要读的是文件尾部——这一点没法用内存里的缓冲替代。
     */
    private static void writeWav(Path path, float[] pcm, int rate) throws IOException {
        int frames = pcm.length;
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
        for (float s : pcm) {
            // Rust 的 `as i16` 是向零截断，Java 的 (short) 转换同样是截断（不是舍入）。
            short v = (short) (Math.max(-1.0f, Math.min(1.0f, s)) * 32767.0f);
            buf.putShort(v);
            buf.putShort(v);
        }
        Files.write(path, buf.array());
    }

    /**
     * 造一段带拍点的信号：每拍一个衰减脉冲 + 很轻的底噪。
     *
     * <p>与 {@code Analysis} 测试里的是同一套造法（那边是私有的，这里复制一份）。
     * 用脉冲而不是真实音乐，是因为测试要的是<b>可推导的答案</b>：BPM 已知，
     * 拍长就是 60/BPM 秒，于是「对齐后窗口该多长」可以精确算出来。
     *
     * <p>那个 LCG 的状态在 Rust 里是 {@code u32}，所以右移必须是<b>逻辑</b>右移
     * （Java 的 {@code int >>} 是算术右移，会把 1 带进来）；Java 的 {@code int}
     * 乘法溢出天然回绕，与 Rust 的 {@code wrapping_mul} 逐位一致。
     */
    private static float[] clickTrack(float bpm, double secs, int rate) {
        int n = (int) ((double) rate * secs);
        float[] v = new float[n];
        int period = (int) ((double) rate * 60.0 / (double) bpm);

        int state = 0x2545F491;
        for (int i = 0; i < n; i++) {
            state = state * 1_664_525 + 1_013_904_223;
            v[i] = (float) (state >>> 16) / 32768.0f * 0.002f - 0.001f;
        }

        int t = 0;
        int len = (int) ((float) rate * 0.03f);
        while (t < n) {
            for (int k = 0; k < len; k++) {
                if (t + k >= n) {
                    break;
                }
                float env = (float) Math.exp((double) (-((float) k) / ((float) rate * 0.006f)));
                float noise = (float) Math.sin((double) ((float) (t + k) * 12989.0f));
                v[t + k] += env * noise * 0.5f;
            }
            t += period;
        }
        return v;
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
     * 这里对不存在的文件给出同样的行为——{@code prepare} 的「分析失败就退回固定窗口」
     * 正是靠这一点才测得到。
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
                // 对应上游 `Decoder::open` 对不存在的文件返回的那条 Err。
                throw AudioException.config("音源不存在或不可读：" + source);
            }
            byte[] all;
            try {
                all = Files.readAllBytes(path);
            } catch (IOException e) {
                throw AudioException.io("读不出音源：" + source, e);
            }
            if (all.length < 44
                    || !tag(all, 0, "RIFF")
                    || !tag(all, 8, "WAVE")) {
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
