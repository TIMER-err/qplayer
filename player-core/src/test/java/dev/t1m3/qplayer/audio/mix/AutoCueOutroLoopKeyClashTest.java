package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Ignore;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>「调性冲突但节奏兼容」这一条真的接进决策层了，而且"不知道就不拦"</b>
 * ——用户 2026-10-05 规格的第四条触发，也是文档 §8.0.9 里写着「<b>尚未实现</b>」的那一格
 * （包络里没有色度，所以 {@code planCollect} 传进去的 {@code keyClash} 恒为 {@code false}）。
 *
 * <h2>接线方式（与这个包既有的 {@code installLyricSpanSource} 同一种做法）</h2>
 *
 * <p>决策层手上只有两侧的<strong>包络</strong>（三条分频带，没有色度），判不了调性；判调性
 * （{@link Harmonic}）要读 PCM。所以这里只要一个<strong>来源</strong>：
 * {@code AutoCue.installKeyRelationSource(句柄 → 调性关系)}，由 {@code AutoCueTransition}
 * 在准备这一对时按句柄问一次、把答案交给计划层（{@code planWithLyricSpans} 七参版本）。
 * 本判据装的是<strong>假的</strong>来源（不必真跑 {@link Harmonic}），要钉的是接线与口径。
 *
 * <h2>判据与它们各自钉住的东西</h2>
 *
 * <ol>
 *   <li>{@link #aKeyClashWithACompatibleGridProducesAGridPlan}：来源 → 决策层的整条链通着，
 *       而且真的用上了——"调性冲突（0.20 &lt; 0.85）+ 节奏兼容（两侧都 128 BPM）"
 *       在尾奏够长、B 的引子也够长的素材上给出计划，理由里写着调性；</li>
 *   <li>{@link #anUnknownKeyRelationIsBitIdenticalToHavingNoSource}：来源说"不知道"
 *       （没装来源 / 来源答 {@code null}）时，{@code Cue} 的每一个字段与压根没装来源
 *       <strong>逐位相同</strong>（红线：不知道就不拦）；</li>
 *   <li>{@link #aMatchingKeyOrAnIncompatibleGridIsNotAKeyClash}：相似度够高（两边本来就合）
 *       不算冲突；BPM 差 3%~8%（节奏<strong>不</strong>兼容）也不算这一条
 *       ——规格里两条触发因此互斥。</li>
 * </ol>
 *
 * <h2>一个必须说清的边界：这一条目前观察不到"产物"上的差别</h2>
 *
 * <p>它归<strong>"对齐网格"</strong>那一档（规格原文：「调性冲突但节奏兼容（要一段纯节奏
 * 来桥）」，见 {@code planOutroLoop} 的类注释），而 {@code AutoCue.loopIsWired}
 * <strong>有意只接"买时间"那一类</strong>。所以 {@code Cue.outroLoop} 在两种情况下
 * 都是 {@code null}、说明行一个字都不加、渲染一字不动——今天能观察到它的地方是
 * **决策层**（{@code planOutroLoop} 的返回值）与诊断档里那一行日志。
 * 这条判据因此**直接问决策层**（与 {@code AutoCueOutroLoopTest} 里"对齐网格那一类
 * 有意不接线"那条判据同一个做法），而不是指望 {@code Cue} 里出现什么——
 * 那条接线口径是别人钉住的，这次不许动它。
 */
@Ignore("legacy：旧决策链的判据；忠实移植 Apple 决策链期间停用 "
        + "（旧逻辑快照在 git tag automix-legacy-20261121，还原后可恢复）")
public class AutoCueOutroLoopKeyClashTest {

    private static final int RATE = 22050;

    private static final double BPM = 128.0;
    private static final double WINDOW = 7.5;

    private static final String SRC_A = "test://keyclash-a";
    private static final String SRC_B = "test://keyclash-b";

    /** 假的调性关系：只有这一个数参与判断（"冲突"是与 {@code LOOP_KEY_SAME_SCORE} 比）。 */
    private static AutoCue.KeyRelation fake(float plainScore) {
        return new AutoCue.KeyRelation(plainScore,
                "判据用的假读数：chroma 相似度 " + plainScore);
    }

    /** 装一个"永远答这个数"的假来源。 */
    private static void installFake(final float plainScore) {
        AutoCue.installKeyRelationSource(new AutoCue.KeyRelationSource() {
            @Override
            public AutoCue.KeyRelation apply(String outroSource, Map<String, String> outroHeaders,
                                             String introSource, Map<String, String> introHeaders) {
                return fake(plainScore);
            }
        });
    }

    // ==================================================================
    // 判据一：调性冲突 + 节奏兼容 ⇒ 决策层给出计划，理由里写着调性
    // ==================================================================

    /**
     * <b>调性冲突但节奏兼容 ⇒ 决策层给出"对齐网格"那一类的计划</b>。
     *
     * <p>断言五件事：
     *
     * <ol>
     *   <li><b>前提</b>：尾奏够长（&gt; 4 小节）、窗口放得下、B 的引子够长、两侧同一个
     *       BPM——也就是说"买时间"与"BPM 差"两条<b>都不成立</b>，计划只可能来自调性；</li>
     *   <li><b>来源 → 决策层整条链通着</b>：装了来源之后 {@code keyRelationOf} 问得到它；</li>
     *   <li><b>决策层用上了它</b>：{@code planOutroLoop} 给出计划、{@code buysTime == false}
     *       （调性属于"对齐网格"那一档）、理由里写着<b>调性</b>与读数；</li>
     *   <li><b>接线口径照旧</b>：{@code loopIsWired} 为 false、{@code Cue.outroLoop} 仍是
     *       {@code null}、说明行里没有"尾奏循环"——"对齐网格"那一类有意不接
     *       （见 {@code AutoCue.loopIsWired}），这一条不该偷偷改变它；</li>
     *   <li><b>反证</b>：同一套点位把读数换成 {@code null}（"不知道"）就<b>不给</b>计划
     *       ——这正是"来源一断，这一条就整体不参与"。</li>
     * </ol>
     */
    @Test
    public void aKeyClashWithACompatibleGridProducesAGridPlan() {
        Fixture fx = fixture(BPM, 8);
        installFake(0.20f);
        try {
            AutoCue.Envelope a = fx.outroEnv;
            double barA = 4.0 * 60.0 / a.tempo.bpm;
            double barB = 4.0 * 60.0 / fx.introEnv.tempo.bpm;
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位", cue);
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(a);
            assertNotNull("尾奏里应当找得到可循环的 vamp", vamp);

            // ── 前提：另外两条触发都不成立 ──────────────────────────────
            double tail = a.durationSecs - cue.outroCutSecs;
            assertTrue(String.format(java.util.Locale.US,
                            "前提：尾奏要够长（不然是『尾奏太短』那一条触发的），量到 %.2f 小节",
                            Double.valueOf(tail / barA)),
                    tail / barA >= AutoCue.LOOP_SHORT_TAIL_BARS);
            assertTrue("前提：这次窗口要放得下", tail > cue.windowSecs);
            double lead = AutoCue.outroLoopIntroLeadSecs(fx.introEnv);
            assertTrue(String.format(java.util.Locale.US,
                            "前提：B 的引子要够长（不然是『B 的引子太短』那一条），量到 %.2f 小节",
                            Double.valueOf(lead / barB)),
                    lead > AutoCue.LOOP_SHORT_INTRO_BARS * barB);
            assertEquals("前提：两侧同一个 BPM（差 0% ⇒ 不落进『对齐网格』里 BPM 那一条）",
                    a.tempo.bpm, fx.introEnv.tempo.bpm, 0.05);

            // ── 判据①：来源 → 决策层 ──────────────────────────────────
            AutoCue.KeyRelation got = AutoCue.keyRelationOf(SRC_A, null, SRC_B, null);
            assertNotNull("装了来源却问不到（句柄没走到来源那一侧）", got);
            assertEquals("来源给的相似度被改动了", 0.20f, got.plainScore, 1e-6);

            // ── 判据②：决策层真的用上了它 ──────────────────────────────
            AutoCue.OutroLoopPlan decided = AutoCue.planOutroLoop(a, fx.introEnv, vamp,
                    cue.outroCutSecs, cue.windowSecs, got);
            assertNotNull("调性冲突（0.20 < " + AutoCue.LOOP_KEY_SAME_SCORE + "）但节奏兼容"
                    + "（两侧都 " + BPM + " BPM），尾奏够长、B 的引子也够长——决策层却没给计划",
                    decided);
            assertFalse("调性这一条属于『对齐网格』那一档，不是『买时间』：" + decided.reason,
                    decided.buysTime);
            assertTrue("理由里应当写着是调性那一条（用户读日志验收的就是这一句）："
                    + decided.reason, decided.reason.contains("调性"));
            assertTrue("理由里应当写着这次是按什么判的（读数）：" + decided.reason,
                    decided.reason.contains("chroma 相似度"));
            assertTrue("理由里应当写着网格（与『对齐网格』那一档的说法一致）："
                    + decided.reason, decided.reason.contains("网格"));

            // ── 判据③：接线口径一个字都没动 ────────────────────────────
            assertFalse("『对齐网格』那一类有意不接线（见 AutoCue.loopIsWired），"
                    + "调性这一条不该偷偷改变它", AutoCue.loopIsWired(decided));
            assertNull("接线口径不收它 → Cue 里不该有循环计划（渲染路径据此一字不动）",
                    cue.outroLoop);
            assertFalse("说明行里不该出现『尾奏循环』", cue.note.contains("尾奏循环"));

            // ── 判据④：**走 planCollect 那条生产接线**，诊断档里要看得到这一条 ────
            //
            // 这一条才是"接线真的通了"的证据：前三条问的都是决策层（planOutroLoop），
            // 而 planCollect 里那句"问来源 → 交给决策层"是**另一行**，可以被单独改坏
            // （实测：把它换成传 null，前三条照样全绿）。非接线档的计划唯一的痕迹就是
            // 诊断档里那一行（见 planCollect 里那段注释），所以这里打开 verbose、
            // 抓环形缓冲里的日志来断言——它同时钉住另一件事：**没有来源时一个字都不打印**
            // （那一半在下面的判据二里断）。
            java.util.List<String> traced = keyClashLogLines(fx);
            assertFalse("走了 planCollect 却没有任何一行带着调性读数的诊断日志——"
                    + "说明来源根本没被问到：", traced.isEmpty());

            // ── 判据⑤：反证——读数『不知道』时这一条整体不参与 ─────────────
            assertNull("来源答『不知道』时却还是给了计划——那就是在猜",
                    AutoCue.planOutroLoop(a, fx.introEnv, vamp,
                            cue.outroCutSecs, cue.windowSecs, (AutoCue.KeyRelation) null));

            System.out.printf(java.util.Locale.ROOT,
                    "%n[调性冲突] 相似度 0.20 < %.2f、两侧都 %.1f BPM（节奏兼容）；"
                            + "尾奏剩 %.2f 小节、B 的引子 %.2f 小节 → 计划：%s%n"
                            + "          生产接线那一行的痕迹：%s%n",
                    Float.valueOf(AutoCue.LOOP_KEY_SAME_SCORE), Double.valueOf(BPM),
                    Double.valueOf(tail / barA), Double.valueOf(lead / barB), decided.reason,
                    traced);
        } finally {
            AutoCue.installKeyRelationSource(null);
            fx.close();
        }
    }

    /**
     * 走一遍 {@code planCollect}（生产接线），把诊断档里**带调性读数**的那几行捞出来。
     *
     * <p>为什么能用日志当判据：非接线档的计划不进 {@code Cue}、也不进说明行，
     * 唯一说明"决策层想循环、但这一档还没接线"的地方就是那一行 debug 日志
     * （用户排查时打开设置里那一项就能看见）。所以它既是给人看的，也是这条接线
     * 唯一可观测的产物——判据不去看它，就等于没测这一行。
     */
    private static java.util.List<String> keyClashLogLines(Fixture fx) {
        java.util.List<String> hits = new java.util.ArrayList<String>();
        boolean wasVerbose = dev.t1m3.qplayer.util.Logger.verbose();
        dev.t1m3.qplayer.util.Logger.setVerbose(true);
        dev.t1m3.qplayer.util.Logger.clear();
        try {
            cueOfWired(fx);
            for (String line : dev.t1m3.qplayer.util.Logger.snapshot()) {
                if (line.contains("调性冲突") && line.contains("尾奏循环")) {
                    hits.add(line);
                }
            }
        } finally {
            dev.t1m3.qplayer.util.Logger.clear();
            dev.t1m3.qplayer.util.Logger.setVerbose(wasVerbose);
        }
        return hits;
    }

    // ==================================================================
    // 判据二（红线）：来源说"不知道" ⇒ 与没有这条触发逐位相同
    // ==================================================================

    /**
     * <b>"不知道就不拦"逐位成立</b>：同一对素材跑三遍——① 压根没有来源；② 装了来源但它
     * 返回 {@code null}；③ 走了 {@code AutoCueTransition} 那条路（{@code keyRelationOf}
     * 按句柄问）而来源还是返回 {@code null}——三份 {@code Cue} 的<b>每一个字段</b>
     * （double 取原始位、说明行逐字比）必须一模一样，而且两边的循环决策都是"没有计划"。
     *
     * <p>为什么这条是红线：这一条触发的全部价值在于"有证据才拦"。一旦"不知道"能改变结论，
     * 那么没接调性分析的机器（宿主没装来源、插件直链读不出 PCM、素材没有调性）会与接了
     * 的那台机器挑出<b>不同的点位</b>——那是"报告必须描述事实"的反面，也是位指纹判据
     * {@code AutomixBitExactReferenceTest} 会当场红的那一类改动。
     *
     * <p>如实记一句：{@code Cue} 这一层今天**逐位相同是结构性的**
     * （调性这一条落在"对齐网格"档，而那一档本来就不接线，见类注释里那个边界）。
     * 这条判据因此同时钉住**将来**：谁要是放宽了 {@code loopIsWired}，
     * 它会在那一秒红，而不是等到位指纹变了才发现。
     */
    @Test
    public void anUnknownKeyRelationIsBitIdenticalToHavingNoSource() {
        Fixture fx = fixture(BPM, 8);
        try {
            AutoCue.installKeyRelationSource(null);
            AutoCue.Cue none = cueOf(fx);

            AutoCue.installKeyRelationSource(new AutoCue.KeyRelationSource() {
                @Override
                public AutoCue.KeyRelation apply(String outroSource, Map<String, String> oh,
                                                 String introSource, Map<String, String> ih) {
                    return null;                // "这一对我算不出来"
                }
            });
            AutoCue.Cue fromSource = cueOf(fx);
            assertTrue("来源答『不知道』时诊断档里却出现了调性那一条的痕迹（那就是在猜）："
                            + keyClashLogLines(fx),
                    keyClashLogLines(fx).isEmpty());
            AutoCue.installKeyRelationSource(null);
            AutoCue.Cue noSourceAgain = cueOf(fx);

            assertNotNull("前提：这一对素材本来挑得出点位", none);
            assertNull("前提：没有调性这一条时本来不循环", none.outroLoop);
            assertSameCue("来源返回『不知道』时结论变了", none, fromSource);
            assertSameCue("同一份输入跑两遍自己也不一样（判据自身不确定）", none, noSourceAgain);

            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(fx.outroEnv);
            assertNotNull("尾奏里应当找得到可循环的 vamp", vamp);
            assertNull("来源答『不知道』，决策层却给了计划",
                    AutoCue.planOutroLoop(fx.outroEnv, fx.introEnv, vamp,
                            none.outroCutSecs, none.windowSecs,
                            AutoCue.keyRelationOf(SRC_A, null, SRC_B, null)));

            System.out.printf(java.util.Locale.ROOT,
                    "%n[调性：不知道] 三遍（没来源 / 来源答 null / 再跑一遍）逐位相同："
                            + "出场 %.2f s、进场 %.2f s、窗口 %.2f s、说明行 %d 字%n",
                    Double.valueOf(none.outroCutSecs), Double.valueOf(none.introEntrySecs),
                    Double.valueOf(none.windowSecs), Integer.valueOf(none.note.length()));
        } finally {
            AutoCue.installKeyRelationSource(null);
            fx.close();
        }
    }

    // ==================================================================
    // 判据三：相似度够高、或节奏不兼容 ⇒ 都不算这一条
    // ==================================================================

    /**
     * <b>两个边界都要挡得住</b>：
     *
     * <ol>
     *   <li><b>相似度够高 = 两边本来就合</b>（0.95 ≥ {@code LOOP_KEY_SAME_SCORE} 0.85）
     *       → 不是"冲突"，不给计划；</li>
     *   <li><b>节奏不兼容</b>：B 换 133 BPM（差约 3.9%，正落在 3%~8% 那一档）而调性冲突
     *       → <b>不</b>算「调性冲突但节奏兼容」（规格里那一条明确要求"节奏兼容"），
     *       计划来自 BPM 差那一条，理由里<b>不该</b>出现"调性"。</li>
     * </ol>
     */
    @Test
    public void aMatchingKeyOrAnIncompatibleGridIsNotAKeyClash() {
        Fixture same = fixture(BPM, 8);
        installFake(0.95f);
        try {
            AutoCue.Cue cue = cueOf(same);
            assertNotNull("合成素材上应当挑得出点位", cue);
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(same.outroEnv);
            assertNotNull("尾奏里应当找得到可循环的 vamp", vamp);
            AutoCue.OutroLoopPlan decided = AutoCue.planOutroLoop(same.outroEnv, same.introEnv,
                    vamp, cue.outroCutSecs, cue.windowSecs,
                    AutoCue.keyRelationOf(SRC_A, null, SRC_B, null));
            assertNotNull("两段假素材的调性关系应当问得到（否则这条判据什么都没测）",
                    AutoCue.keyRelationOf(SRC_A, null, SRC_B, null));
            assertNull("相似度 0.95 ≥ " + AutoCue.LOOP_KEY_SAME_SCORE
                    + "（『两边本来就合』）→ 不是冲突，不该给计划："
                    + (decided == null ? "" : decided.reason), decided);
        } finally {
            AutoCue.installKeyRelationSource(null);
            same.close();
        }

        Fixture off = fixture(133.0, 8);
        installFake(0.20f);
        try {
            double diff = Math.abs(off.introEnv.tempo.bpm - off.outroEnv.tempo.bpm)
                    / off.outroEnv.tempo.bpm;
            assertTrue(String.format(java.util.Locale.US,
                            "这条判据要的是『BPM 差落在 3%%~8%% 那一档』，量到 %.2f%%",
                            Double.valueOf(100.0 * diff)),
                    diff >= AutoCue.LOOP_BPM_DIFF_MIN && diff <= AutoCue.LOOP_BPM_DIFF_MAX);
            AutoCue.Cue cue = cueOf(off);
            assertNotNull("合成素材上应当挑得出点位", cue);
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(off.outroEnv);
            assertNotNull("尾奏里应当找得到可循环的 vamp", vamp);
            AutoCue.OutroLoopPlan decided = AutoCue.planOutroLoop(off.outroEnv, off.introEnv, vamp,
                    cue.outroCutSecs, cue.windowSecs,
                    AutoCue.keyRelationOf(SRC_A, null, SRC_B, null));
            assertNotNull("BPM 差 3%~8% 那一档本来就给计划", decided);
            assertTrue("理由里应当写着是 BPM 差那一条：" + decided.reason,
                    decided.reason.contains("BPM 差"));
            assertFalse("节奏**不**兼容（差 " + (100.0 * diff) + "%）就算不上"
                            + "『调性冲突但节奏兼容』，理由里不该写调性：" + decided.reason,
                    decided.reason.contains("调性"));
        } finally {
            AutoCue.installKeyRelationSource(null);
            off.close();
        }

        System.out.printf(java.util.Locale.ROOT,
                "%n[调性两条边界] 相似度 0.95（本来就合）→ 不循环；"
                        + "BPM 差 3.9%% + 调性冲突 → 只按 BPM 那一条走%n");
    }

    // ==================================================================
    // 判据四：「没有读数」必须当不知道，不许当成冲突
    // ==================================================================

    /**
     * <b>读数缺失（{@code plainScore} 为 NaN）→ 不知道，不许当成冲突</b>。
     *
     * <p>这条判据是<strong>真曲库探针抓出来的一个真 bug</strong>：早先"老入口交进来的是冲突、
     * 只是没带读数"也编码成 NaN，于是两种含义撞在一起——{@code harmonicKeyRelationSource}
     * 里那条按对缓存把"这一对算不出来"记成 NaN 之后，**第二次问同一对**就会被判成"冲突"，
     * 决策层于是给出一份计划。实测（{@code AutomixOutroLoopTriggerProbe}，本机 8 首）：
     * {@code qplayer-demo-B → qplayer-demo-A} 这一对正是这样混进"调性冲突"计数里的
     * （来源那一列明明写着"问不到"）。现在 NaN 只剩"没有读数"一个含义，
     * 而"不知道就不拦"对**所有**来路的"不知道"一视同仁。
     */
    @Test
    public void aReadingThatIsMissingIsUnknownNotAClash() {
        Fixture fx = fixture(BPM, 8);
        try {
            AutoCue.Cue cue = cueOf(fx);
            assertNotNull("合成素材上应当挑得出点位", cue);
            AutoCue.VampSpan vamp = AutoCue.outroVampSpan(fx.outroEnv);
            assertNotNull("尾奏里应当找得到可循环的 vamp", vamp);

            AutoCue.KeyRelation noReading = new AutoCue.KeyRelation(Float.NaN, "判据：没有读数");
            assertFalse("没有读数（NaN）必须当『不知道』——当成冲突就是在猜",
                    AutoCue.keyClashWithCompatibleGrid(noReading, fx.outroEnv, fx.introEnv));
            assertNull("没有读数却给了计划",
                    AutoCue.planOutroLoop(fx.outroEnv, fx.introEnv, vamp,
                            cue.outroCutSecs, cue.windowSecs, noReading));

            // 真实现那条按对缓存也要守住同一条：同一对问两次，结论必须一样
            // （早先第一次答"算不出来"、第二次答"冲突"，就是这个形状）。
            AutoCue.installKeyRelationSource(AutoCue.harmonicKeyRelationSource());
            try {
                AutoCue.KeyRelation first = AutoCue.keyRelationOf(SRC_A, null, SRC_B, null);
                AutoCue.KeyRelation second = AutoCue.keyRelationOf(SRC_A, null, SRC_B, null);
                assertFalse("同一对问两次给出了不同的『算不算冲突』：第一次 "
                                + describe(first) + "、第二次 " + describe(second),
                        AutoCue.keyClashWithCompatibleGrid(first, fx.outroEnv, fx.introEnv)
                                != AutoCue.keyClashWithCompatibleGrid(second, fx.outroEnv,
                                        fx.introEnv));
            } finally {
                AutoCue.installKeyRelationSource(null);
            }
        } finally {
            AutoCue.installKeyRelationSource(null);
            fx.close();
        }
    }

    private static String describe(AutoCue.KeyRelation key) {
        return key == null ? "没有读数（null）"
                : String.format(java.util.Locale.ROOT, "chroma %.2f", Float.valueOf(key.plainScore));
    }

    // ==================================================================
    // 脚手架：一份 Cue 的逐字段比对 + 夹具
    // ==================================================================

    /** 逐字段比对两份 {@code Cue}（double 取原始位，其余逐个比）。 */
    private static void assertSameCue(String why, AutoCue.Cue expected, AutoCue.Cue got) {
        assertNotNull(why + "：一份是 null", got);
        assertEquals(why + "：出场点", Double.doubleToRawLongBits(expected.outroCutSecs),
                Double.doubleToRawLongBits(got.outroCutSecs));
        assertEquals(why + "：进场点", Double.doubleToRawLongBits(expected.introEntrySecs),
                Double.doubleToRawLongBits(got.introEntrySecs));
        assertEquals(why + "：交叠窗口", Double.doubleToRawLongBits(expected.windowSecs),
                Double.doubleToRawLongBits(got.windowSecs));
        assertEquals(why + "：丢尾", Double.doubleToRawLongBits(expected.tailDropSecs),
                Double.doubleToRawLongBits(got.tailDropSecs));
        assertEquals(why + "：代价", Double.doubleToRawLongBits(expected.cost),
                Double.doubleToRawLongBits(got.cost));
        assertEquals(why + "：强度差", Double.doubleToRawLongBits(expected.intensityGap),
                Double.doubleToRawLongBits(got.intensityGap));
        assertEquals(why + "：强度是否达标", expected.intensityOk, got.intensityOk);
        assertEquals(why + "：循环计划", expected.outroLoop, got.outroLoop);
        assertEquals(why + "：路径", String.valueOf(expected.segments),
                String.valueOf(got.segments));
        assertEquals(why + "：说明行", expected.note, got.note);
    }

    /** 生产口径的一次挑点，但**不**接调性来源（六参入口：与加这条触发之前逐位相同）。 */
    private static AutoCue.Cue cueOf(Fixture fx) {
        return AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv, new double[]{WINDOW},
                AutoCue.vocalPhraseSpans(fx.outroEnv),
                AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE);
    }

    /**
     * 生产口径的一次挑点，**接上**调性来源——七参入口就是 {@code AutoCueTransition.prepare}
     * 走的那一个（它传的供应者就是 {@code keyRelationOfPair} 那三行）。
     */
    private static AutoCue.Cue cueOfWired(Fixture fx) {
        return AutoCue.planWithLyricSpans(fx.outroEnv, fx.introEnv, new double[]{WINDOW},
                AutoCue.vocalPhraseSpans(fx.outroEnv),
                AutoCue.vocalPhraseSpans(fx.introEnv), AutoCue.Bridge.MEASURE,
                () -> AutoCue.keyRelationOf(SRC_A, null, SRC_B, null));
    }

    private static final class Fixture {
        final float[] pcmA;
        final float[] pcmB;
        final AutoCue.Envelope outroEnv;
        final AutoCue.Envelope introEnv;

        Fixture(float[] pcmA, float[] pcmB, AutoCue.Envelope outroEnv, AutoCue.Envelope introEnv) {
            this.pcmA = pcmA;
            this.pcmB = pcmB;
            this.outroEnv = outroEnv;
            this.introEnv = introEnv;
        }

        void close() {
            Decoder.install(null);
        }
    }

    private static Fixture fixture(double bpmB, int introBars) {
        final float[] a = trackA();
        final float[] b = trackB(bpmB, introBars);
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, Map<String, String> headers) {
                return source != null && source.contains("keyclash-b")
                        ? new Fake(b, RATE) : new Fake(a, RATE);
            }
        });
        return new Fixture(a, b, AutoCue.scan(SRC_A, null), AutoCue.scanIntro(SRC_B, null));
    }

    /** 素材 A：128 BPM、**14 小节前段 + 8 小节 vamp + 6 小节稀疏收尾**（尾奏剩 6 小节 &gt; 4）。 */
    private static float[] trackA() {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < 4; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.25f;
        }
        append(kinds, levels, bar, "kkkkhhhh", 0.85f);
        append(kinds, levels, bar += 8, "kk", 0.85f);
        append(kinds, levels, bar += 2, "vvvvvvvv", 1.0f);
        append(kinds, levels, bar + 8, "ffffff", 0.45f);
        return synth(128.0, kinds.toString(), levels, 0.0);
    }

    /**
     * 素材 B：32 小节、{@code bpmB} 那个速度。<b>开头 {@code introBars} 小节安静底鼓
     * 然后进主段</b>（引子够长 ⇒ 不触发"B 的引子太短"那一条）。
     *
     * <p>错开 0.37 拍的理由与 {@code AutoCueOutroLoopShortIntroTest} 里那条一样：
     * 第一记底鼓压在采样 0 上会让第 0 个 hop 的 RMS 变成整段峰值（后面的底鼓都跨在两个
     * hop 之间），而平滑电平在第 0 格没有窗，于是 {@code introStartSecs} 一律返回 0.0
     * ——那是夹具的起振对齐造出来的假象，修夹具而不是修判据。
     */
    private static float[] trackB(double bpmB, int introBars) {
        StringBuilder kinds = new StringBuilder();
        float[] levels = new float[64];
        int bar = 0;
        for (int i = 0; i < introBars && bar < 32; i++, bar++) {
            kinds.append('i');
            levels[bar] = 0.15f;
        }
        for (; bar < 32; bar++) {
            kinds.append('k');
            levels[bar] = 0.85f;
        }
        return synth(bpmB, kinds.toString(), levels, 0.37);
    }

    /** 造一条 4/4 打击乐轨：{@code kinds} 每小节一个字符，{@code levels} 每小节一个电平。 */
    private static float[] synth(double bpm, String kinds, float[] levels, double offsetBeats) {
        double beat = 60.0 / bpm;
        double bar = 4 * beat;
        float[] out = new float[(int) Math.round((kinds.length() + offsetBeats / 4.0) * bar * RATE)];
        for (int b = 0; b < kinds.length(); b++) {
            char kind = kinds.charAt(b);
            float level = levels[Math.min(b, levels.length - 1)];
            for (int k = 0; k < 4; k++) {
                double t = (b * 4 + k + offsetBeats) * beat;
                switch (kind) {
                    case 'i':
                        kick(out, t, level, false);
                        break;
                    case 'h':
                        hat(out, t, level);
                        break;
                    case 'v':
                        kick(out, t, level * (b % 4 == 0 && k == 0 ? 1.15f : 1.0f), false);
                        hat(out, t + beat * 0.5, level * 0.9f);
                        break;
                    default:
                        kick(out, t, level, k % 2 == 1);
                        break;
                }
            }
        }
        return out;
    }

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

    /** 假解码器：把合成 PCM 当一首曲子（单声道，长度已知）。 */
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
