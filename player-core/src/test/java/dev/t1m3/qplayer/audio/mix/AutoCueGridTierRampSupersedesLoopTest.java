package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Ignore;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「对齐网格」那一档的定性判据：网格对齐现在由 ramp 交付，重复素材换不来额外收益</b>
 * （2026-10-06）。
 *
 * <h2>它钉的是哪一句结论</h2>
 *
 * <p>用户 2026-10-05 的规格核原话是「<b>重复段落不是用来炫技的，是用来'买时间'和'对齐网格'的</b>」，
 * 于是 {@code AutoCue.planOutroLoop} 把循环分成两档（见 {@code docs/automix-modes.md}
 * §8.0.8~§8.0.10）：{@code buysTime = true} 的「买时间」接进了渲染路径，
 * {@code buysTime = false} 的「对齐网格」（BPM 差 3%~8%，或调性冲突但节奏兼容）
 * <strong>有意不接线</strong>（{@code AutoCue.loopIsWired}）。
 *
 * <p><b>「不接线」这个结论不变，但它今天的两条理由里有一条已经过时</b>：原文写着
 * 「'对齐网格'那一档要<b>跨循环渐进变速</b>才算真的对齐（<b>循环路径与变速目前互斥</b>）」——
 * 而 2026-10-06 落了保音高的<b>渐变降速斜坡</b>（{@link TempoRamp} +
 * {@link TempoBridge#stretchRamp}，接线在 {@code AutoCueTransition.prepareRampedOutgoing}），
 * <b>变速已经在生产路径上跑着</b>，循环与变速不再互斥。所以这一档的定性要改成：
 * <b>对齐网格由 ramp 直接完成——它在不重复素材的前提下把两侧拍长连续拉近
 * （{@link TempoRamp#absorbedFraction} 量它吸收了多少），而循环式对齐只能对齐每一遍循环的
 * 「起点」、还要跨循环继续变速。重复素材在这里换不来额外收益。</b>
 *
 * <h2>三条判据，每条都对着上面那个说法的可证伪形式</h2>
 *
 * <ol>
 *   <li>{@link #theGridTierIsStillADecisionThePlannerMakes}：<b>决策层确实认得这一档</b>
 *       （同一对素材上 {@code planOutroLoop} 报得出 {@code buysTime = false} 的计划、
 *       理由里写着「网格」，且实测 BPM 差落在 3%~8% 里）；</li>
 *   <li>{@link #andThatDecisionIsStillNotWiredAndThatIsDeliberate}：
 *       <b>它确实不接线</b>（{@code AutoCue.loopIsWired(decided) == false}、
 *       {@code Cue.outroLoop == null}、说明行里没有「尾奏循环」）——与既有的
 *       {@code AutoCueOutroLoopTest#theGridClassDecisionIsDeliberatelyNotWired} 同源，
 *       本条要多的是<strong>同一对素材上「决策层认得」与「接线口径不收」同时成立</strong>；
 *       </li>
 *   <li>{@link #theRampCoversTheSamePairSoRepeatingMaterialBuysNothing}：
 *       <b>同一对素材上 ramp 是生效的</b>——用这一对实测的 BPM 比调
 *       {@link TempoRamp#derive}，断言 {@code active} 为 true、
 *       {@code endSpeedFor} 严格小于 1、{@code absorbedFraction} 大于 0。
 *       这才是「对齐网格由 ramp 交付」的可证伪形式：把它换成一个不降速的实现
 *       （{@code derive} 直接返回 {@code off(...)}，或把 {@link TempoRamp#SLEW_MAX}
 *       压成 0），这一条当场红。</li>
 * </ol>
 *
 * <p><b>这三条是「加」上去的，不是替换既有的判据</b>：{@code AutoCueOutroLoopTest} 一个字
 * 都没动（它那一条 {@code theGridClassDecisionIsDeliberatelyNotWired} 原样保留），本类
 * 只把「为什么可以不接线」从"我们还没有这个能力"改成"我们已经有一个更好的机制"，并给出
 * 那个机制的判据。
 *
 * <h2>素材</h2>
 *
 * <p>合成素材与 {@code AutoCueOutroLoopTest} <strong>同一份</strong>（A 128 BPM、105 s，
 * 尾奏最后 45 s 里有一段 8 小节的自相似 vamp；B 换 BPM 即可）——所以「同一对素材」
 * 这句话在这里是字面意义上的同一对：决策层、接线口径、ramp 三条读的是同一组包络。
 * 写成自带的私有夹具（而不是去复用那个类的私有成员）是为了让本判据不依赖别的测试类
 * 的内部形状；两份材料的生成代码相同，读数因此可比。
 *
 * <h2>本类<strong>不</strong>记录的那件事</h2>
 *
 * <p>ramp 今天只覆盖「B 比 A 慢」那一半（{@code ratio > 1} 时 {@code derive} 恒不出手，
 * 见 {@link TempoRamp#derive} 的门与 {@code GridTierRampProbe} 的实测表）——
 * 那是<strong>已知缺口</strong>，写在 {@code docs/automix-modes.md} 里，
 * <b>本次没有改行为</b>（改 {@link TempoRamp} 牵动渲染路径的源读取长度，要单独一轮）。
 * 判据不把它写成断言：把一条"今天不成立的行为"钉成判据，等于给未来的修复设路障。
 */
@Ignore("legacy：旧决策链的判据；忠实移植 Apple 决策链期间停用 "
        + "（旧逻辑快照在 git tag automix-legacy-20261121，还原后可恢复）")
public class AutoCueGridTierRampSupersedesLoopTest {

    private static final int RATE = 22050;

    /** 用户设的交叠时长（秒）：与参考对实测的 7.5 s 同一个量级，在合成素材上正好 4 小节。 */
    private static final double NOMINAL_WINDOW = 7.5;

    private static final String SRC_A = "test://loop-a";
    private static final String SRC_B = "test://loop-b";

    /** B 的 BPM：128 → 124 是 <b>3.1%</b>，正落在 3%~8% 那一档里（与 {@code AutoCueOutroLoopTest} 同一个数）。 */
    private static final double BPM_B = 124.0;

    // ==================================================================
    // 判据一：决策层认得这一档
    // ==================================================================

    /**
     * <b>「对齐网格」仍然是决策层报得出来的一档</b>。
     *
     * <p>这一条是下面两条的前提：如果决策层已经不认这一档了（比如把
     * {@code LOOP_BPM_DIFF_MIN} 抬到 6.3% 以上），那么"它不接线"就退化成"根本没认出来"，
     * 结论会被悄悄改写。所以先钉住事实：同一对素材上 {@code planOutroLoop} 报得出计划、
     * 归的是<b>网格</b>那一档。
     */
    @Test
    public void theGridTierIsStillADecisionThePlannerMakes() {
        LoopFixture fx = fixture(BPM_B);
        try {
            double bpmA = fx.outroEnv.tempo.bpm;
            double bpmB = fx.introEnv.tempo.bpm;
            double diff = Math.abs(bpmB - bpmA) / bpmA;
            System.out.println(String.format(Locale.US,
                    "[grid-tier] 实测 A %.4f BPM / B %.4f BPM = 差 %.4f%%（档次 3%%~8%%）",
                    Double.valueOf(bpmA), Double.valueOf(bpmB), Double.valueOf(100.0 * diff)));
            assertTrue("夹具必须真的落在「对齐网格」档（3%~8%）里，实测差 "
                            + String.format(Locale.US, "%.4f%%", Double.valueOf(100.0 * diff)),
                    diff >= 0.03 && diff <= 0.08);

            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(fx.outroEnv);
            assertNotNull("合成素材上应当找得到可循环的 vamp（否则这条判据什么都没测）", vamp);
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位", cue);

            AutoCue.OutroLoopPlan decided = AutoCue.planOutroLoop(fx.outroEnv, fx.introEnv, vamp,
                    cue.outroCutSecs, cue.windowSecs, false);
            assertNotNull("BPM 差落在 3%~8% 里 → 决策层必须给出计划（这一条是事实，不是期望）",
                    decided);
            assertFalse("BPM 差这一档是「对齐网格」，不是「买时间」", decided.buysTime);
            assertTrue("这一类的理由里应当写着「网格」，实际理由：" + decided.reason,
                    decided.reason.contains("网格"));
            System.out.println(String.format(Locale.US,
                    "[grid-tier] 决策层报出：buysTime=%s bars=%d repeats=%d 理由=「%s」",
                    Boolean.valueOf(decided.buysTime), Integer.valueOf(decided.bars),
                    Integer.valueOf(decided.repeats), decided.reason));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据二：决策层认得，但接线口径不收（两句同时成立）
    // ==================================================================

    /**
     * <b>同一对素材上「决策层认得」与「接线口径不收」同时成立</b>。
     *
     * <p>与既有的 {@code AutoCueOutroLoopTest#theGridClassDecisionIsDeliberatelyNotWired}
     * 同一个结论（那条判据一个字没动），但这里要的是<strong>在同一对素材、同一次运行里</strong>
     * 把两句话一起钉住：{@code planOutroLoop(...) != null} 与
     * {@code loopIsWired(...) == false}——于是"不接线"不可能被误读成"判不出来"，
     * 也不可能被一次放宽口径的改动悄悄改掉（放宽了这条就红）。
     *
     * <p>反过来的对照也在：{@code buysTime = true} 的计划<strong>是</strong>接的，
     * 否则"有意不接"就退化成"根本没接"。
     */
    @Test
    public void andThatDecisionIsStillNotWiredAndThatIsDeliberate() {
        LoopFixture fx = fixture(BPM_B);
        try {
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(fx.outroEnv);
            assertNotNull("合成素材上应当找得到可循环的 vamp", vamp);
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位", cue);
            AutoCue.OutroLoopPlan decided = AutoCue.planOutroLoop(fx.outroEnv, fx.introEnv, vamp,
                    cue.outroCutSecs, cue.windowSecs, false);

            assertNotNull("决策层必须认得这一档（否则下面那句「不接线」测的是「没认出来」）",
                    decided);
            assertFalse("「对齐网格」那一类有意不接线（见 AutoCue.loopIsWired）；"
                            + "接线口径今天只收「买时间」那一类",
                    AutoCue.loopIsWired(decided));
            assertNull("接线口径不收它 → Cue 里不该有循环计划（渲染路径据此一字不动）",
                    cue.outroLoop);
            assertFalse("说明行里不该出现「尾奏循环」（它进 plan 与日志，是产物的一部分）",
                    cue.note.contains("尾奏循环"));
            assertTrue("但决策层<b>确实</b>看到了这一对有个可循环段（说明行里应当写着 vamp），"
                            + "否则「不接线」这条结论没有对象；实际说明行：" + cue.note,
                    cue.note.contains("尾奏 vamp"));

            AutoCue.OutroLoopPlan timeClass = new AutoCue.OutroLoopPlan(vamp, decided.bars, 1, true,
                    "对照：买时间那一类");
            assertTrue("「买时间」那一类必须接（否则就是根本没接）",
                    AutoCue.loopIsWired(timeClass));
            System.out.println(String.format(Locale.US,
                    "[grid-tier] 接线口径：loopIsWired(网格计划)=%s，loopIsWired(买时间计划)=%s，"
                            + "Cue.outroLoop=%s",
                    Boolean.valueOf(AutoCue.loopIsWired(decided)),
                    Boolean.valueOf(AutoCue.loopIsWired(timeClass)),
                    cue.outroLoop == null ? "null" : "有"));
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 判据三：同一对素材上 ramp 是生效的（这才是「对齐网格」的交付方式）
    // ==================================================================

    /**
     * <b>同一对 BPM 上 {@link TempoRamp} 真的出手</b>——「对齐网格由 ramp 交付」的可证伪形式。
     *
     * <p>比值取的就是这一对的 BPM 比（{@code 进场/出场}，与 {@code TempoRamp} 的门径一致），
     * 窗口取这一对在计划层实际拿到的那一个。断言四件事：
     *
     * <ol>
     *   <li>{@code ramp.active} 为 true——它真的出手了；</li>
     *   <li>{@code endSpeedFor(窗口)} 严格小于 1——出场曲真的被放慢了；</li>
     *   <li>{@code absorbedFraction(窗口) > 0}——它真的<b>吸收掉</b>了一部分 BPM 差
     *       （这是"对齐网格"这四个字的量）；</li>
     *   <li>速率曲线两端对得上（起点 1.000 = 与自然播放同速 ⇒ 接缝没有速率阶跃，
     *       末端 = {@code endSpeedFor}），且生产入口
     *       {@link AutoCueTransition#rampFor}（= 闸门 + 本规则）在同一对包络上给出同一个斜坡
     *       ——否则这条只是"纯函数碰巧算得出来"，不是生产路径上生效。</li>
     * </ol>
     *
     * <p><b>红-前</b>：把 {@code TempoRamp.derive} 改成直接 {@code off(...)}（或把
     * {@code SLEW_MAX} 改成 0），第 1、4 条当场红；把比值换成反方向（{@code bpmA/bpmB > 1}）
     * 也会红——那正是下面「已知缺口」记的那一半（本类不把它写成断言）。
     */
    @Test
    public void theRampCoversTheSamePairSoRepeatingMaterialBuysNothing() {
        LoopFixture fx = fixture(BPM_B);
        try {
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位（窗口要从计划层拿，别自己编一个）", cue);
            double bpmA = fx.outroEnv.tempo.bpm;
            double bpmB = fx.introEnv.tempo.bpm;
            double ratio = bpmB / bpmA;                 // 进场 BPM / 出场 BPM
            double window = cue.windowSecs;
            assertTrue("这一对的方向必须落在 ramp 覆盖的那一半（B 比 A 慢 ⇒ ratio < 1），"
                            + "实测 ratio = " + ratio, ratio > 0.0 && ratio < 1.0);

            TempoRamp ramp = TempoRamp.derive(ratio, window);
            double end = ramp.endSpeedFor(window);
            double absorbed = ramp.absorbedFraction(window);
            System.out.println(String.format(Locale.US,
                    "[grid-tier] ramp：ratio %.4f、窗口 %.4f s → active=%s、1.0000→%.4f×"
                            + "（平均 %.4f×、吸收 %.2f%% 的差）注：%s",
                    Double.valueOf(ratio), Double.valueOf(window), Boolean.valueOf(ramp.active),
                    Double.valueOf(end), Double.valueOf(ramp.meanSpeedFor(window)),
                    Double.valueOf(100.0 * absorbed), ramp.note));

            assertTrue("同一对 BPM 上 ramp 必须出手（差 "
                            + String.format(Locale.US, "%.2f%%", Double.valueOf(100.0 * (1.0 - ratio)))
                            + " 远大于听阈 " + TempoRamp.TEMPO_JND + "）", ramp.active);
            assertTrue("出场的拍长必须被真的拉慢：endSpeedFor 要严格小于 1，实测 " + end, end < 1.0);
            assertTrue("它必须真的吸收掉一部分 BPM 差（否则「对齐网格」没有交付），实测 "
                    + absorbed, absorbed > 0.0);
            assertEquals("窗口起点必须与自然播放同速（否则接缝处是个速率阶跃）",
                    1.0, ramp.speedAt(window, 0.0), 1e-12);
            assertEquals("速率曲线末端就是 endSpeedFor 那个数（两处是同一条曲线）",
                    end, ramp.speedAt(window, window), 1e-12);
            assertTrue("源消费必须短于窗口（降速 ⇒ 少消耗素材），实测 "
                            + ramp.sourceSecsAt(window, window),
                    ramp.sourceSecsAt(window, window) < window);

            TempoRamp production = AutoCueTransition.rampFor(fx.outroEnv, fx.introEnv, window, false);
            assertTrue("生产入口（闸门 + 本规则）在同一对包络上也必须出手——否则这条判据只是"
                            + "「纯函数碰巧算得出来」。注：" + production.note, production.active);
            // **2026-10-06 按证据重述**（第一阶段工作三）：生产入口现在走
            // {@code TempoRamp.deriveHalved}（双方各承担一半、几何平均），不再走
            // {@code TempoRamp.derive}（A 独担全部）——所以比对对象换成对半那一条，
            // 并额外钉住"对半"这件事本身：A 侧终点必须是 **sqrt(BPM 比)** 那一半。
            double prodRatio = production.ratio;
            assertEquals("生产入口必须与 deriveHalved 同源（对半那一档）",
                    TempoRamp.deriveHalved(prodRatio, window).endSpeedFor(window),
                    production.endSpeedFor(window), 1e-12);
            assertTrue("生产入口必须是对半那一档（halved）", production.halved);
            assertEquals("A 侧终点速率 = sqrt(BPM 比)（几何平均、双方各半）",
                    Math.sqrt(prodRatio), production.endSpeedFor(window), 1e-9);
        } finally {
            fx.close();
        }
    }

    // ==================================================================
    // 夹具：与 AutoCueOutroLoopTest 同一份合成素材
    // ==================================================================

    /** 一对合成素材（A 固定，B 的 BPM 可换）与它扫出来的包络。 */
    private static final class LoopFixture {
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;

        LoopFixture(AutoCue.Envelope outroEnv, AutoCue.Envelope introEnv) {
            this.outroEnv = outroEnv;
            this.introEnv = introEnv;
        }

        void close() {
            Decoder.install(null);
        }
    }

    private static LoopFixture fixture(double bpmB) {
        final float[] a = trackA();
        final float[] b = trackB(bpmB);
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return source != null && source.contains("loop-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new LoopFixture(AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /** 走生产那条计划路径（与 {@code AutoCueTransition.prepare} 里的调用完全一致）。 */
    private static AutoCue.Cue cueOf(LoopFixture fx) {
        return AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv,
                new double[]{NOMINAL_WINDOW}, AutoCue.vocalPhraseSpans(fx.outroEnv),
                AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
    }

    /** 造一条 4/4 打击乐轨：{@code kinds} 每小节一个字符，{@code levels} 每小节一个电平。 */
    private static float[] synth(double bpm, String kinds, float[] levels) {
        double beat = 60.0 / bpm;
        double bar = 4 * beat;
        float[] out = new float[(int) Math.round(kinds.length() * bar * RATE)];
        for (int b = 0; b < kinds.length(); b++) {
            char kind = kinds.charAt(b);
            float level = levels[Math.min(b, levels.length - 1)];
            for (int k = 0; k < 4; k++) {
                double t = (b * 4 + k) * beat;
                switch (kind) {
                    case 'i':
                        kick(out, t, level, false);
                        break;
                    case 'k':
                        kick(out, t, level, k % 2 == 1);
                        break;
                    case 'h':
                        hat(out, t, level);
                        break;
                    case 'v':
                        // vamp：每拍底鼓 + 反拍 hat；每 4 小节的第一拍加一记重音当"地标"。
                        // 周期因此是 4 小节而循环段是 8 小节——于是"倒回起点重播"与
                        // "接着往下播"能用样本逐位比出来。
                        kick(out, t, level * (b % 4 == 0 && k == 0 ? 1.15f : 1.0f), false);
                        hat(out, t + beat * 0.5, level * 0.9f);
                        break;
                    case 'o':
                        if (k == 0) {
                            kick(out, t, level, false);
                        }
                        break;
                    default:
                        kick(out, t, level, false);
                        break;
                }
            }
        }
        return out;
    }

    /** 底鼓：60 Hz（重音拍 120 Hz）正弦、指数衰减。 */
    private static void kick(float[] out, double t, float level, boolean high) {
        double freq = high ? 120.0 : 60.0;
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(0.18 * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double env = Math.exp(-i / (0.05 * RATE));
            out[idx] += (float) (level * 0.9 * env * Math.sin(2 * Math.PI * freq * i / RATE));
        }
    }

    /** hi-hat：8 kHz 正弦、很短的衰减（能量整段落在高频带里，不进代理 VAD 的中频带）。 */
    private static void hat(float[] out, double t, float level) {
        int start = (int) Math.round(t * RATE);
        int n = (int) Math.round(0.03 * RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= out.length) {
                continue;
            }
            double env = Math.exp(-i / (0.008 * RATE));
            out[idx] += (float) (level * 0.5 * env * Math.sin(2 * Math.PI * 8000 * i / RATE));
        }
    }

    private static void append(StringBuilder sb, float[] levels, int bar, String kinds,
                               float level) {
        sb.append(kinds);
        for (int i = 0; i < kinds.length(); i++) {
            levels[bar + i] = level;
        }
    }

    /** 素材 A：105 s、128 BPM，可循环的 vamp 落在 [60, 75)（8 小节 = 15.00 s）。 */
    private static float[] trackA() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < 8; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.25f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.8f);       // [15, 30)
        append(kinds, levels, bar += 8, "hhhhkkkk", 0.8f);  // [30, 45)
        append(kinds, levels, bar += 8, "kkkkhhhh", 0.8f);  // [45, 60)
        append(kinds, levels, bar += 8, "vvvvvvvv", 1.0f);  // [60, 75) ← 可循环段
        append(kinds, levels, bar += 8, "oooooooo", 0.35f); // [75, 90)
        append(kinds, levels, bar + 8, "ffffffff", 0.5f);   // [90, 105)
        return synth(128.0, kinds.toString(), levels);
    }

    /** 素材 B：40 小节，BPM 由调用方给（计划层用它算"BPM 差"）。 */
    private static float[] trackB(double bpm) {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < 8; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.3f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.75f);
        append(kinds, levels, bar += 8, "hhhhkkkk", 0.8f);
        append(kinds, levels, bar + 8, "kkkkvvvv", 0.9f);
        return synth(bpm, kinds.toString(), levels);
    }

    /** 假解码器：把合成 PCM 当一首曲子（单声道，长度已知——计划层要真实时长）。 */
    private static final class Fake implements Decoder {

        private final float[] pcm;
        private final int rate;
        private int position;

        Fake(float[] pcm, int rate) {
            this.pcm = pcm;
            this.rate = rate;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("pcm", rate, ChannelLayout.of(1), Integer.valueOf(16),
                    Long.valueOf(pcm.length), true);
        }

        @Override
        public long framesDecoded() {
            return position;
        }

        @Override
        public boolean isDrained() {
            return position >= pcm.length;
        }

        @Override
        public double seek(double secs) {
            position = Math.max(0, Math.min(pcm.length, (int) (secs * rate)));
            return position / (double) rate;
        }

        @Override
        public int decodeInto(AudioBuf out) {
            int n = Math.min(out.capacityFrames(), pcm.length - position);
            if (n <= 0) {
                out.setFrames(0);
                return 0;
            }
            java.nio.FloatBuffer dst = out.channelWriter(0);
            for (int i = 0; i < n; i++) {
                dst.put(i, pcm[position + i]);
            }
            out.setFrames(n);
            position += n;
            return n;
        }

        @Override
        public void close() {
        }
    }
}
