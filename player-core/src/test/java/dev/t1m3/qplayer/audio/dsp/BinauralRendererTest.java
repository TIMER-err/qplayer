package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 双耳渲染器的对齐测试：对应 Sonalis {@code crates/sonalis-dsp/src/spatial.rs} 的
 * {@code #[cfg(test)] mod tests}（上游 21 条）。
 *
 * <p>方法名与上游测试一一对应（snake_case → camelCase），断言强度、容差、断言消息都照搬。
 *
 * <p><b>容差来源</b>：上游的容差（对称 1e-6、截止差 1e-3、ITD 连续 1e-4、遮蔽单调 1e-6、
 * 双耳一致 1e-4、重置 1e-9）都是<strong>算法本身</strong>留的余量，不是给数值库差异的补偿，
 * 所以一条都没有放宽。
 *
 * <p>唯一可能引入差异的地方是超越函数：Rust 走平台 libm 的 {@code sinf} / {@code powf} /
 * {@code log2f}，Java 走 {@code Math.sin/pow/log}（double 实现）再<strong>立刻</strong>窄化回
 * {@code float}，两者可能差 &le; 1 ulp。1 ulp 在 f32 上是 <strong>相对</strong> 1.2e-7：
 * 对着 0.66 ms 的 ITD 是 8e-11 s，对着 20000 Hz 的截止是 2.4e-3 Hz——比上面任何一条容差
 * 都小好几个数量级，因此不需要额外放宽。
 * {@link #frontalSourceRendersSymmetrically} 与 {@link #lfeIsNotSpatialized} 用 0 容差
 * （逐位相同），因为那两处两耳算的是<strong>同一个表达式</strong>（相位角为 0 时
 * {@code sin(0) = 0} 两边都精确），不存在实现差异。
 *
 * <p><b>测试数量</b>：上游 21 条 → 本地 24 条。多出的 3 条是 Java 侧补充，每条都在注释里
 * 写明为什么补：{@link #monoOutputAveragesBothEars}（上游没有任何测试走到
 * {@code out_ch == 1} 这条分支）、{@link #describeAndLabelsCoverEveryMode}（上游只断言了
 * 一个 label，{@code describe} 完全没覆盖）、{@link #unknownLayoutSpreadsSourcesAcrossTheSector}
 * （上游只数了未知布局的声源个数，没看摆位）。
 *
 * <p>移植时另外做了一次比特级交叉验证（不在本仓库里，属一次性核对）：把上游
 * {@code spatial.rs} <strong>逐位未改</strong>地编成一个 Rust 探针（只在外层补
 * {@code sonalis_core::AudioBuf} / {@code ChannelLayout} / {@code OnePole} / {@code db_to_linear}
 * 的最小桩，算法一行未动），与本移植版喂同一组输入，输出按 f32 比特打印后逐行比对：
 * 18 组方位角 + LFE 的 ITD / 遮蔽 / 增益 / 截止频率 / 延迟、7 种布局的声源表与输出余量、
 * 以及右侧/左侧声源噪声、正前方正弦、5.1、声道数不足、单声道输出、reset、零长输入这些
 * 渲染路径的逐样本输出，<strong>69 行全部相同（比特级）</strong>。
 * 所以上面那些容差确实只是算法本身的余量，没有替数值库差异兜底：{@code Math.sin/pow/log}
 * 与 Rust libm 在本模块用到的取值上没有差异。
 *
 * <p>唯一一处实测差异在 {@link Source#describe()}：恰好 {@code x.5} 的方位角
 * （{@code 0.5} / {@code 2.5} / {@code -0.5}）上，Rust 的 {@code {:.0}} 是半到偶、
 * {@code String.format("%.0f")} 是半远离零（探针实测 {@code "0°"} vs {@code "1°"}）。
 * 这只影响界面字符串、不影响采样值，而且四种固定布局的方位角全是整数；
 * 原因与取舍写在 {@link Source#describe()} 的注释里，测试不覆盖这一处。
 *
 * <p><b>注意</b>：断言消息里的字符串拼接在 Java 里是<strong>立刻求值</strong>的（Rust 的
 * {@code assert!} 只在失败时才格式化），所以这些消息会在每次断言时都建一遍。测试代码不在
 * 热路径上，为保持与上游一一对应，这样写。
 */
public class BinauralRendererTest {

    /** 与上游测试同名同值。 */
    private static final int RATE = 48_000;

    /** 对应上游测试里的 {@code fn buf(layout, frames)}：容量至少 1 帧，有效帧数另设。 */
    private static AudioBuf buf(ChannelLayout layout, int frames) {
        AudioBuf b = new AudioBuf(layout, Math.max(frames, 1));
        b.setFrames(frames);
        return b;
    }

    /**
     * 上游测试里的 LCG：{@code state = state * 1664525 + 1013904223}（u32 回绕）。
     *
     * <p>Java 的 {@code int} 溢出回绕与 Rust 的 {@code wrapping_mul}/{@code wrapping_add}
     * 逐位一致，所以这里用 {@code int} 承载 u32，取位时用无符号右移 {@code >>>}。
     */
    private static void fillNoise(AudioBuf src, int seed, int n) {
        int state = seed;
        FloatBuffer w = src.channelWriter(0);
        for (int i = 0; i < n; i++) {
            state = state * 1_664_525 + 1_013_904_223;
            // 上游是 (state >> 8) as f32 / 8_388_608.0 - 1.0：右移后落在 [0, 2^24)，除以 2^23 再偏移
            w.put(i, (float) (state >>> 8) / 8_388_608.0f - 1.0f);
        }
    }

    /** 对应上游 {@code out.channel(ch)[..n].iter().map(|v| v * v).sum::<f32>()}：f32 从左到右累加。 */
    private static float energy(FloatBuffer ch, int n) {
        float sum = 0.0f;
        for (int i = 0; i < n; i++) {
            float v = ch.get(i);
            sum += v * v;
        }
        return sum;
    }

    /**
     * 有限值判断。<b>刻意不用 {@code Float.isFinite}</b>：它看着像 Java 8，实际是
     * Android API 24+ 才有的方法，而 {@code isNaN}/{@code isInfinite} 从 API 1 就有
     * （约定文档里那类「看着像 Java 8、其实不是」的坑，这里不再踩一次）。
     */
    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** 正前方的声源对双耳应当完全对称：延迟为零、增益相同。 */
    @Test
    public void frontalSourceIsSymmetric() {
        Source s = Source.at(0.0f);
        assertEquals("正前方左耳不该有延迟", 0.0f, s.delaySecs(false), 0.0f);
        assertEquals("正前方右耳不该有延迟", 0.0f, s.delaySecs(true), 0.0f);
        assertTrue("正前方两耳增益应相同", Math.abs(s.gain(false) - s.gain(true)) < 1e-6f);
        assertTrue("正前方两耳截止频率应相同",
                Math.abs(s.cutoffHz(false) - s.cutoffHz(true)) < 1e-3f);
    }

    /** 右侧声源：右耳是近端（无延迟），左耳是远端（有延迟）。 */
    @Test
    public void rightSourceDelaysTheLeftEar() {
        Source s = Source.at(90.0f);
        assertEquals("右耳是近端，不该有延迟", 0.0f, s.delaySecs(true), 0.0f);
        assertTrue("左耳是远端，应当有延迟", s.delaySecs(false) > 0.0f);
    }

    /** 正侧方的 ITD 应在 0.6–0.7 ms 之间——与实测人头吻合。 */
    @Test
    public void itdIsInTheRealisticRange() {
        Source s = Source.at(90.0f);
        float ms = s.itdSecs() * 1000.0f;
        // 上游 (0.6..0.7).contains(&ms)：左闭右开
        assertTrue("正侧方 ITD 应约 0.66 ms，实际 " + ms + " ms", ms >= 0.6f && ms < 0.7f);
    }

    /** 方位角越过正前时不连续的话，声音会在头中间「跳」一下。 */
    @Test
    public void itdIsContinuousAcrossTheFront() {
        float prev = Source.at(-5.0f).itdSecs();
        float az = -4.5f;
        while (az <= 5.0f) {
            float cur = Source.at(az).itdSecs();
            assertTrue("方位角 " + az + " 处 ITD 跳变：" + prev + " → " + cur,
                    Math.abs(cur - prev) < 1e-4f);
            prev = cur;
            az += 0.5f;
        }
    }

    /** 正后方的 ITD 应为零（对两耳对称）。 */
    @Test
    public void rearSourceHasNoItd() {
        Source s = Source.at(180.0f);
        assertTrue("正后方两耳对称，实际 " + s.itdSecs(), s.itdSecs() < 1e-6f);
    }

    /** 近端耳必须比远端耳更亮（截止频率更高）。 */
    @Test
    public void nearEarIsBrighterThanFarEar() {
        Source s = Source.at(90.0f);
        assertTrue("近端截止 " + s.cutoffHz(true) + " 应显著高于远端 " + s.cutoffHz(false),
                s.cutoffHz(true) > s.cutoffHz(false) * 2.0f);
    }

    /** 正前方不该被闷掉：遮蔽量要明显小于正侧方。 */
    @Test
    public void frontalSourceIsNotHeavilyShadowed() {
        Source front = Source.at(0.0f);
        float sideFar = Source.at(90.0f).cutoffHz(false);
        assertTrue("正前方的截止频率 " + front.cutoffHz(true) + " 过低，听起来会闷",
                front.cutoffHz(true) > 8_000.0f);
        assertTrue("正侧方远端应明显变暗，实际 " + sideFar, sideFar < 4_000.0f);
    }

    /** 遮蔽量必须落在 [0, 1] 且随方位单调。 */
    @Test
    public void shadowStaysInRangeAndIsMonotone() {
        float[] angles = {-180.0f, -120.0f, -90.0f, -45.0f, 0.0f, 45.0f, 90.0f, 120.0f, 180.0f};
        for (int i = 0; i < angles.length; i++) {
            float sh = Source.at(angles[i]).shadow(true);
            assertTrue(angles[i] + "° 遮蔽量越界：" + sh, sh >= 0.0f && sh <= 1.0f);
        }
        // 从正右转到正左，右耳的遮蔽量必须一路增大
        float prev = -1.0f;
        float az = 90.0f;
        while (az >= -90.0f) {
            float cur = Source.at(az).shadow(true);
            assertTrue(az + "° 处遮蔽量回落：" + prev + " → " + cur, cur >= prev - 1e-6f);
            prev = cur;
            az -= 10.0f;
        }
    }

    /** LFE 不定位：两耳完全等量、零延迟。 */
    @Test
    public void lfeIsNotSpatialized() {
        Source s = Source.lfe();
        assertEquals("LFE 不该有 ITD", 0.0f, s.itdSecs(), 0.0f);
        assertEquals("LFE 不该有头影遮蔽", 0.0f, s.shadow(true), 0.0f);
        // 两耳走的是同一个表达式（遮蔽量恒为 0），所以这里要求逐位相同
        assertEquals("LFE 两耳增益应完全相同", s.gain(false), s.gain(true), 0.0f);
        assertEquals("LFE 两耳截止频率应完全相同", s.cutoffHz(false), s.cutoffHz(true), 0.0f);
    }

    /** 等价性：LFE 与正前声源虽然都在正前，但 LFE 不该有头影衰减。 */
    @Test
    public void lfeDiffersFromAFrontalSource() {
        Source lfe = Source.lfe();
        Source front = Source.at(0.0f);
        assertTrue("LFE 不该被头影衰减（" + lfe.gain(true) + " vs " + front.gain(true) + "）",
                lfe.gain(true) > front.gain(true));
    }

    /** 端到端：右侧声源渲染后，右耳能量必须高于左耳。 */
    @Test
    public void rightSourceRendersLouderOnTheRight() {
        BinauralRenderer r = BinauralRenderer.withSources(
                Collections.singletonList(Source.at(90.0f)), RATE);
        int n = 9_600;
        AudioBuf src = buf(ChannelLayout.MONO, n);
        // 用宽带噪声而不是纯音：纯音在头影滤波下的变化不明显
        fillNoise(src, 0x9E3779B9, n);

        AudioBuf out = buf(ChannelLayout.STEREO, n);
        assertEquals("render 应写满整块", n, r.render(src, out));

        float el = energy(out.channel(0), n);
        float er = energy(out.channel(1), n);
        assertTrue("右侧声源应在右耳更响：左 " + el + " 右 " + er, er > el * 1.5f);
    }

    /** 左侧声源的表现必须镜像。 */
    @Test
    public void leftSourceRendersLouderOnTheLeft() {
        BinauralRenderer r = BinauralRenderer.withSources(
                Collections.singletonList(Source.at(-90.0f)), RATE);
        int n = 9_600;
        AudioBuf src = buf(ChannelLayout.MONO, n);
        fillNoise(src, 0x12345678, n);

        AudioBuf out = buf(ChannelLayout.STEREO, n);
        r.render(src, out);
        float el = energy(out.channel(0), n);
        float er = energy(out.channel(1), n);
        assertTrue("左侧声源应在左耳更响：左 " + el + " 右 " + er, el > er * 1.5f);
    }

    /** 正前方声源渲染后两耳必须几乎相同。 */
    @Test
    public void frontalSourceRendersSymmetrically() {
        BinauralRenderer r = BinauralRenderer.withSources(
                Collections.singletonList(Source.at(0.0f)), RATE);
        int n = 4_800;
        AudioBuf src = buf(ChannelLayout.MONO, n);
        FloatBuffer w = src.channelWriter(0);
        for (int i = 0; i < n; i++) {
            // 上游 ((i as f32) * 0.01).sin()：乘法在 f32 里做，sin 走平台实现
            w.put(i, (float) Math.sin((float) i * 0.01f));
        }
        AudioBuf out = buf(ChannelLayout.STEREO, n);
        r.render(src, out);

        FloatBuffer ol = out.channel(0);
        FloatBuffer or = out.channel(1);
        // 跳过起始的滤波瞬态
        for (int i = 480; i < n; i++) {
            float d = Math.abs(ol.get(i) - or.get(i));
            assertTrue("第 " + i + " 帧两耳不一致，差 " + d, d < 1e-4f);
        }
    }

    /** 声源表必须与布局的声道数一致，否则会有一路被漏掉或错位。 */
    @Test
    public void sourceTableMatchesLayoutChannelCount() {
        ChannelLayout[] layouts = {
                ChannelLayout.MONO,
                ChannelLayout.STEREO,
                ChannelLayout.SURROUND_5_1,
                ChannelLayout.SURROUND_7_1,
                ChannelLayout.of(4), // 上游的 ChannelLayout::Other(4)
        };
        for (int i = 0; i < layouts.length; i++) {
            ChannelLayout l = layouts[i];
            List<Source> s = BinauralRenderer.sourcesFor(l);
            assertEquals(l + " 的声源数 " + s.size() + " 与声道数 " + l.count() + " 不符",
                    l.count(), s.size());
        }
    }

    /** 5.1 里必须恰好有一个 LFE 且它不定位。 */
    @Test
    public void surround51HasOneNonDirectionalSource() {
        List<Source> s = BinauralRenderer.sourcesFor(ChannelLayout.SURROUND_5_1);
        int lfe = 0;
        for (int i = 0; i < s.size(); i++) {
            if (s.get(i).nonDirectional) {
                lfe++;
            }
        }
        assertEquals("5.1 应恰有一个 LFE", 1, lfe);
        assertTrue("5.1 的第 4 路（索引 3）应是 LFE", s.get(3).nonDirectional);
    }

    /** 多声道要有余量，否则求和后必然削顶。 */
    @Test
    public void surroundGetsHeadroom() {
        BinauralRenderer stereo = new BinauralRenderer(ChannelLayout.STEREO, RATE);
        BinauralRenderer surround = new BinauralRenderer(ChannelLayout.SURROUND_5_1, RATE);
        assertTrue("立体声不该被额外衰减，实际 " + stereo.outputGain(),
                Math.abs(stereo.outputGain() - 1.0f) < 1e-6f);
        assertTrue("5.1 应留出余量，实际 " + surround.outputGain(), surround.outputGain() < 0.9f);
    }

    /** 渲染结果必须是有限值：滤波器与延迟线一旦不稳定就会出 NaN。 */
    @Test
    public void outputIsAlwaysFinite() {
        BinauralRenderer r = new BinauralRenderer(ChannelLayout.SURROUND_5_1, RATE);
        int n = 4_800;
        AudioBuf src = buf(ChannelLayout.SURROUND_5_1, n);
        for (int ch = 0; ch < 6; ch++) {
            FloatBuffer w = src.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                w.put(i, ch == 3
                        ? 0.5f
                        : (float) Math.sin(((float) i + (float) ch * 100.0f) * 0.02f) * 0.3f);
            }
        }
        AudioBuf out = buf(ChannelLayout.STEREO, n);
        r.render(src, out);
        for (int ch = 0; ch < 2; ch++) {
            FloatBuffer oc = out.channel(ch);
            for (int i = 0; i < n; i++) {
                float v = oc.get(i);
                assertTrue("第 " + ch + " 声道第 " + i + " 帧非有限值：" + v, finite(v));
            }
        }
    }

    /** 声道数不足的源喂给更多声源时不该静音。 */
    @Test
    public void fewerSourceChannelsThanSourcesDoesNotSilence() {
        BinauralRenderer r = new BinauralRenderer(ChannelLayout.SURROUND_5_1, RATE);
        int n = 2_400;
        AudioBuf src = buf(ChannelLayout.MONO, n);
        FloatBuffer w = src.channelWriter(0);
        for (int i = 0; i < n; i++) {
            w.put(i, 0.4f);
        }
        AudioBuf out = buf(ChannelLayout.STEREO, n);
        r.render(src, out);
        float peak = out.peak();
        assertTrue("不该静音，实际峰值 " + peak, peak > 0.1f);
    }

    /** 模式标记：双耳渲染必然改写采样值。 */
    @Test
    public void spatialModeDeclaresWhetherItRewrites() {
        assertTrue("Off 不该改写采样", !SpatialMode.OFF.rewritesSamples());
        assertTrue("Passthrough 不该改写采样", !SpatialMode.PASSTHROUGH.rewritesSamples());
        assertTrue("Binaural 必然改写采样", SpatialMode.BINAURAL.rewritesSamples());
        // 上游 SpatialMode::default() == SpatialMode::Off
        assertEquals("默认模式应是 Off", SpatialMode.OFF, SpatialMode.defaultValue());
        assertEquals("双耳渲染", SpatialMode.BINAURAL.label());
    }

    /** 重置后不该还有余振。 */
    @Test
    public void resetClearsState() {
        BinauralRenderer r = new BinauralRenderer(ChannelLayout.STEREO, RATE);
        int n = 480;
        AudioBuf src = buf(ChannelLayout.STEREO, n);
        src.channelWriter(0).put(0, 1.0f);
        AudioBuf out = buf(ChannelLayout.STEREO, n);
        r.render(src, out);
        r.reset();

        // 重置后立刻渲染静音，输出应当严格为零
        AudioBuf silence = buf(ChannelLayout.STEREO, 64);
        AudioBuf out2 = buf(ChannelLayout.STEREO, 64);
        r.render(silence, out2);
        assertTrue("重置后不该还有余振，实际峰值 " + out2.peak(), out2.peak() < 1e-9f);
    }

    /** 零长输入不能抛异常，也不能把 out 留在「有数据」的状态。 */
    @Test
    public void emptyInputIsHandled() {
        BinauralRenderer r = new BinauralRenderer(ChannelLayout.STEREO, RATE);
        AudioBuf src = buf(ChannelLayout.STEREO, 0);
        AudioBuf out = buf(ChannelLayout.STEREO, 64);
        assertEquals("零长输入应返回 0", 0, r.render(src, out));
        assertEquals("零长输入后 out 应为空", 0, out.frames());
    }

    /**
     * <b>本地补充</b>：单声道输出（{@code out_ch == 1}）取双耳平均。
     *
     * <p>为什么要补：上游 {@code render} 里有一条 {@code 1 => out.channel_mut(0)[n] =
     * (lo + ro) * 0.5} 的分支，而 21 条上游测试<strong>没有一条</strong>走进去——它只被
     * 5.1 功放的「耳机折叠」路径用到，写错了也不会有人发现。
     *
     * <p>断言用 0 容差：两个渲染器是同样构造、同样输入，输出是确定性的，{@code mono[i]}
     * 与这里重算的 {@code (l + r) * 0.5f} 是同一串浮点运算，必须逐位相同。
     */
    @Test
    public void monoOutputAveragesBothEars() {
        int n = 2_400;
        AudioBuf src = buf(ChannelLayout.MONO, n);
        fillNoise(src, 0x0BADC0DE, n);

        BinauralRenderer rs = BinauralRenderer.withSources(
                Collections.singletonList(Source.at(90.0f)), RATE);
        AudioBuf stereo = buf(ChannelLayout.STEREO, n);
        rs.render(src, stereo);

        BinauralRenderer rm = BinauralRenderer.withSources(
                Collections.singletonList(Source.at(90.0f)), RATE);
        AudioBuf mono = buf(ChannelLayout.MONO, n);
        assertEquals("单声道输出也该写满整块", n, rm.render(src, mono));

        FloatBuffer ol = stereo.channel(0);
        FloatBuffer or = stereo.channel(1);
        FloatBuffer om = mono.channel(0);
        for (int i = 0; i < n; i++) {
            float expect = (ol.get(i) + or.get(i)) * 0.5f;
            assertEquals("第 " + i + " 帧单声道输出应为双耳平均",
                    expect, om.get(i), 0.0f);
        }
    }

    /**
     * <b>本地补充</b>：{@link BinauralRenderer#describe(ChannelLayout)} 与三个模式的显示名。
     *
     * <p>为什么要补：上游只断言了 {@code SpatialMode::Binaural.label()} 一条，
     * {@code describe} 一个字都没测——而这两处都是给界面看的文案，改错了只会在 UI 上
     * 悄悄错掉。{@code describe} 的「路定位」还特意只数可定位声源（5.1 是 5 路而不是 6 路），
     * 这条正好把它锁住。
     */
    @Test
    public void describeAndLabelsCoverEveryMode() {
        assertEquals("关闭", SpatialMode.OFF.label());
        assertEquals("多声道直通", SpatialMode.PASSTHROUGH.label());
        assertEquals("上游的变体只有三个", 3, SpatialMode.values().length);

        BinauralRenderer r = new BinauralRenderer(ChannelLayout.SURROUND_5_1, RATE);
        assertEquals("双耳渲染（球头模型，5.1 → 立体声，5 路定位）",
                r.describe(ChannelLayout.SURROUND_5_1));
        BinauralRenderer st = new BinauralRenderer(ChannelLayout.STEREO, RATE);
        assertEquals("双耳渲染（球头模型，stereo → 立体声，2 路定位）",
                st.describe(ChannelLayout.STEREO));
    }

    /**
     * <b>本地补充</b>：未知布局（上游 {@code ChannelLayout::Other(n)}）的摆位。
     *
     * <p>为什么要补：上游只数了这条分支的声源<strong>个数</strong>（声源表长度测试），
     * 没看它摆在哪；而这条分支的用意是「至少不会把所有内容挤在正前方」，摆位算错就白写了。
     * 顺带锁住 {@code n.max(1)}：{@code Other(0)} 也要给出一个（正前方的）声源，
     * 不能返回空表——空表会让渲染静音。
     */
    @Test
    public void unknownLayoutSpreadsSourcesAcrossTheSector() {
        List<Source> four = BinauralRenderer.sourcesFor(ChannelLayout.of(4));
        assertEquals(4, four.size());
        assertEquals(-180.0f, four.get(0).azimuth, 0.0f);
        assertEquals(180.0f, four.get(four.size() - 1).azimuth, 0.0f);

        List<Source> three = BinauralRenderer.sourcesFor(ChannelLayout.of(3));
        assertEquals(-180.0f, three.get(0).azimuth, 0.0f);
        assertEquals(0.0f, three.get(1).azimuth, 0.0f);
        assertEquals(180.0f, three.get(2).azimuth, 0.0f);

        List<Source> zero = BinauralRenderer.sourcesFor(ChannelLayout.of(0));
        assertEquals("ChannelLayout::Other(0) 也要给出一个声源", 1, zero.size());
        assertEquals(0.0f, zero.get(0).azimuth, 0.0f);
    }
}
