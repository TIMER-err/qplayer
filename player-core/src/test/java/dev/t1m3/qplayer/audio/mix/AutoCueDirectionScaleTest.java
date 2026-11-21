package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>「走向」那一项的尺度：段落级，不是 20 ms 的打击点</b>（2026-10-06 新增）。
 *
 * <h3>这一条钉的是什么</h3>
 *
 * <p>九项里的「走向」（{@code WEIGHTS[4]} = 0.8）回答的是「接缝那一刻 A 是在起还是在收」。
 * 它原来量的是 {@code rms[i+1] − rms[i−1]}——<b>±1 格 = 20 ms 的中心差分</b>，而那正好是
 * <b>打击点</b>的尺度：一声鼓落在候选点上，「走向」立刻读成"在起"。
 *
 * <p>参考对 skydive → 春めく 上这件事是要命的（{@code tools/automix-forensics/} 的取证）：
 * 音乐上正确的乐句线 {@code 181.4455 s}（8 小节线 + 尾声起点，Apple 取 181.1）**自己就是
 * 一个打击点**，20 ms 口径在那一格给 {@code 0.038}（满分 1）、在它旁边 0.35 s 处给
 * {@code 1.000}，于是那一项在总分里赢 0.0497，把出场点拉走 1.7 s；B 侧同理：
 * 候选 {@code 15.14 s}（吸拍后 14.9983，与 Apple 的 15.0 差 1.7 ms）被同一项读成「在收」
 * 而输给旁边那一格。也就是说：**"这一段整体在起还是在收"这件事，不该由 20 ms 的一格决定。**
 *
 * <p>所以这一条判据把三件事分开钉住：
 *
 * <ol>
 *   <li><b>单个打击点不再翻符号</b>：一段整段在收的尾声上叠一个 20 ms 的上冲，
 *       「走向」必须仍然是满分（{@link #aSingleHitOnAParagraphScaleDeclineDoesNotReadAsRising}）；</li>
 *   <li><b>真的一段在冲照样罚</b>：整段在升的地方必须仍然拿不到满分——这是这一项**本来
 *       要防的事**（用户原话「A 跳跃点附近十分激进带感」，不许从那种地方切出去），
 *       见 {@link #aGenuineParagraphScaleRiseIsStillPenalised}；</li>
 *   <li><b>读数对一格之差不敏感</b>：同一个包络、只在候选点那一格差一个 20 ms 上冲，
 *       「走向」的读数必须**逐位相同**（见 {@link #theReadingIsBitIdenticalWithoutThatOneHop}
 *       ——段落窗取的是候选点**两侧**的均值，候选点自己不在窗里）。</li>
 * </ol>
 *
 * <p><b>红-前（实测，2026-10-06 复跑，{@code -Dtest=AutoCueDirectionScaleTest}）</b>：
 * 把 {@code AutoCue.evaluate} 里的 {@code rising}/{@code falling} 退回 20 ms 的
 * {@code max(0, ±(rms[i+1]−rms[i−1])/2) × SLOPE_NORM}（也就是这次改动之前的
 * {@code slope(env, i)}），<b>4 条里当场红 2 条</b>，实测失败信息逐字是
 * <pre>
 *   aSingleHitOnAParagraphScaleDeclineDoesNotReadAsRising:
 *       「出场侧那半项必须给满分（这一段整段在收）」
 *       expected:&lt;1.0&gt; but was:&lt;0.6539052724838257&gt;
 *   aGenuineParagraphScaleRiseIsStillPenalised:
 *       「整段在往上冲的地方必须罚（实测走向 0.9921052753925323）」
 * </pre>
 * 注意红的是<b>第 1、2 两条</b>（不是第 3 条）——第 3 条在旧口径下反而是绿的：
 * 旧读数是 {@code (rms[i+1] − rms[i−1])}，候选点**自己**那一格本来就不在它里面，
 * 所以"一格之差不影响读数"在旧口径下偶然也成立。真正被这次改动救回来的是第 1 条
 * （打击点不再把"整段在收"读成在起）与第 2 条（旧口径**看不见 4 秒的渐强**：
 * 读数 0.9921 ≈ 满分，一个真在往上冲的地方拿到了奖励）。
 */
public class AutoCueDirectionScaleTest {

    private static final double HOP = AutoCue.HOP_SECS;
    private static final double WINDOW = 5.0;

    /** 判据要看的那个时刻（场景中间，两侧都有一小节以上的余量）。 */
    private static final double CANDIDATE = 30.0;

    /** 段落窗的半宽（秒）：{@code DIRECTION_SPAN_BARS × 无拍栅格时的默认小节} = 2.0 s。 */
    private static final double SPAN = AutoCue.DIRECTION_FALLBACK_BAR_SECS;

    /** 整段在收的尾声：60 s 里从 0.90 线性降到 0.45（整段都在收）。 */
    static AutoCue.Envelope decliningOutro() {
        int hops = (int) Math.round(60.0 / HOP);
        float[] rms = new float[hops];
        for (int i = 0; i < hops; i++) {
            rms[i] = (float) (0.90 - 0.45 * (i * HOP / 60.0));
        }
        return AutoCue.envelopeOf(rms, 60.0);
    }

    /**
     * 在候选点那一刻叠一个 <b>60 ms 的上冲</b>（真实素材里的样子：底鼓的起振沿）。
     *
     * <p>要点是「候选点<b>两侧</b>不一样」——{@code rms[i−1]} 还是低电平、
     * {@code rms[i+1]} 已经冲到高电平，于是 20 ms 的中心差分读出"在起"。
     */
    static AutoCue.Envelope withAnAttackAtTheCandidate(AutoCue.Envelope base) {
        float[] rms = base.rms.clone();
        int i = (int) Math.round(CANDIDATE / HOP);
        for (int k = 0; k <= 3; k++) {
            rms[i + k] = (float) (rms[i + k] + 0.40);
        }
        return AutoCue.envelopeOf(rms, base.durationSecs);
    }

    /** 只改候选点**自己**那一格（一个孤立的 20 ms 采样点）。 */
    static AutoCue.Envelope withASingleHopAtTheCandidate(AutoCue.Envelope base) {
        float[] rms = base.rms.clone();
        int i = (int) Math.round(CANDIDATE / HOP);
        rms[i] = (float) (rms[i] + 0.30);
        return AutoCue.envelopeOf(rms, base.durationSecs);
    }

    /** 整段在**起**的尾声：30 s 从 0.45 升到 0.95（真实的 4 秒渐强量级）。 */
    static AutoCue.Envelope risingOutro() {
        int hops = (int) Math.round(60.0 / HOP);
        float[] rms = new float[hops];
        for (int i = 0; i < hops; i++) {
            double t = i * HOP;
            // 0~28 s 平坦 0.45，28~32 s 线性升到 0.95（4 秒渐强），之后平台；
            // 候选点 30 s 正好落在这段渐强的**中间**
            double up = t <= 28.0 ? 0.0 : Math.min(1.0, (t - 28.0) / 4.0);
            rms[i] = (float) (0.45 + 0.50 * up);
        }
        return AutoCue.envelopeOf(rms, 60.0);
    }

    /** 平稳的进场曲（进场侧那半项固定给满分，好让读数只反映出场侧）。 */
    static AutoCue.Envelope steadyIntro() {
        int hops = (int) Math.round(60.0 / HOP);
        float[] rms = new float[hops];
        java.util.Arrays.fill(rms, 0.6f);
        return AutoCue.envelopeOf(rms, 60.0);
    }

    /**
     * <b>一段整段在收的尾声，候选点上有一个打击点 ⇒「走向」仍然是满分。</b>
     *
     * <p>夹具保证那一格在 20 ms 口径下真的是"在起"（{@code rms[i+1] − rms[i−1]} = 0.30，
     * 远超旧口径的 1/6 饱和线），所以这条判据在旧口径下必红。
     */
    @Test
    public void aSingleHitOnAParagraphScaleDeclineDoesNotReadAsRising() {
        AutoCue.Envelope outro = withAnAttackAtTheCandidate(decliningOutro());
        AutoCue.Envelope intro = steadyIntro();
        int i = outro.indexAt(CANDIDATE);
        double hopDerivative = outro.rms[i + 1] - outro.rms[i - 1];
        assertTrue("夹具要保证这一格在 20 ms 口径下真的是「在起」（实测 " + hopDerivative + "）",
                hopDerivative > 1.0 / 6.0);

        AutoCue.Score score = AutoCue.evaluate(outro, intro, CANDIDATE, 1.0, WINDOW);
        assertEquals("出场侧那半项必须给满分（这一段整段在收）", 1.0, score.motion, 1e-9);
    }

    /**
     * <b>整段在往上冲的地方照样罚</b>——「不许从一段正在往上冲的地方切出去」这条防守还在。
     *
     * <p>夹具里 60 s 涨了 0.45（相对差约 0.33 > {@link AutoCue#DIRECTION_NORM}），
     * 所以出场侧那半项应当归零（{@code motion} = 0.5：进场侧那半项仍满分）。
     */
    @Test
    public void aGenuineParagraphScaleRiseIsStillPenalised() {
        AutoCue.Score score = AutoCue.evaluate(risingOutro(), steadyIntro(),
                CANDIDATE, 1.0, WINDOW);
        assertTrue("整段在往上冲的地方必须罚（实测走向 " + score.motion + "）", score.motion <= 0.6);
        assertTrue("但也不该把进场侧那半项一起罚掉（实测 " + score.motion + "）", score.motion >= 0.5);
    }

    /**
     * <b>读数对"候选点那一格"逐位不敏感</b>：段落窗取的是候选点<strong>两侧</strong>的均值，
     * 候选点自己不在窗里——所以多一个 20 ms 的上冲，读数必须一模一样。
     */
    @Test
    public void theReadingIsBitIdenticalWithoutThatOneHop() {
        double withHit = AutoCue.evaluate(withASingleHopAtTheCandidate(decliningOutro()),
                steadyIntro(), CANDIDATE, 1.0, WINDOW).motion;
        double without = AutoCue.evaluate(decliningOutro(), steadyIntro(),
                CANDIDATE, 1.0, WINDOW).motion;
        assertTrue("夹具没搭好：整段在收时读数应当满分（" + without + "）", without >= 1.0 - 1e-9);
        assertEquals("20 ms 的单点不该改变段落尺度的读数", without, withHit, 1e-12);
    }

    /**
     * <b>尺度常数本身被钉住</b>：换一个值就等于换一把尺子，必须有意识地改这一条。
     */
    @Test
    public void theScaleConstantsArePinned() {
        assertEquals("判定的尺度是一小节", 1.0, AutoCue.DIRECTION_SPAN_BARS, 1e-9);
        assertEquals("无拍栅格时按 120 BPM 算小节", 2.0, AutoCue.DIRECTION_FALLBACK_BAR_SECS, 1e-9);
        assertEquals("「明显在起/在收」的相对差门槛（约等于窗内电平翻一倍）",
                0.20, AutoCue.DIRECTION_NORM, 1e-9);
        assertEquals("20 ms 只有一小节的 1/100——这就是被换掉的那把尺子", 0.02, HOP, 1e-9);
        assertTrue("段落的窗必须比一格宽得多", SPAN >= 50 * HOP);
    }
}
