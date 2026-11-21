package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.util.Logger;

/**
 * 「自动点位」：扫描<strong>整首</strong>出场曲与进场曲，找一个听得出来的最佳融合点。
 *
 * <h2>它解决什么</h2>
 *
 * <p>固定窗口（比如 15 秒）把换曲时机锁死在「当前曲的最后 15 秒」上：不管那 15 秒是
 * 副歌正酣还是已经收尾，都在那里开始交叠。自动点位把这个限制解掉——它在整首歌里
 * 找一处<strong>接得上</strong>的位置：出场曲那一侧最好是「一段刚落下来」的地方
 * （能量在往下走、后面本来就是尾声），进场曲那一侧最好是「一段刚要起来」的地方
 * （能量在往上走，进去正好接住）。
 *
 * <h2>三条硬约束（用户明确要的「不要太极端」）</h2>
 *
 * <ol>
 *   <li>出场点不早于 {@code max(20 秒, 全曲的 20%)}——不能一开头就把这首切了；</li>
 *   <li>出场点不晚于 {@code 全曲时长 − 交叠时长 − 2 秒}——不能等于「完整结尾」
 *       （那就是固定窗口现在的行为，也正是这个功能要解开的限制）；</li>
 *   <li>进场点不晚于 {@code min(20 秒, 进场曲的 25%)}——不能从一首歌的后半段进去。</li>
 * </ol>
 *
 * <p>在这些约束里挑代价最小的位置；如果连一个候选都没有（歌太短），返回 {@code null}，
 * 由调用方<strong>如实退回固定点位</strong>（宁可不动，也不要瞎选一个）。
 *
 * <h2>怎么算（都是包络上的算术，没有模型、没有随机）</h2>
 *
 * <p>先把整首曲子解成一条<strong>能量包络</strong>（每 {@value #HOP_SECS} 秒一个 RMS），
 * 再按下面四项打分；权重写死在 {@link #plan} 里并注明理由：
 *
 * <ul>
 *   <li><b>能量接续</b>（权重 1.0）：出场点与进场点的响度差越小越顺——这是交叉淡化
 *       最朴素也最有效的判据；</li>
 *   <li><b>出场不在往上冲</b>（权重 0.8）：出场点前后各一小节的能量均值在收（不在起）
 *       才给满分——<b>段落尺度</b>，见 {@link #DIRECTION_SPAN_BARS}；</li>
 *   <li><b>进场不在往下掉</b>（权重 0.8）：进场点那一段不在往下掉才给满分；</li>
 *   <li><b>落在段落起拍上</b>（权重 0.5）：该处能量跳变越大越像段落边界。</li>
 * </ul>
 *
 * <p>另外加一项<strong>「少切一点」</strong>的小权重（0.35 × 被丢掉的尾巴占比）：
 * 两个候选的接缝质量差不多时，选那个把歌留得更完整的。这一项很小是有意的——
 * 它不该盖过「接缝听起来顺」这件事，但也不该让一次微不足道的接缝改善就把半首歌切掉。
 *
 * <p>扫描是<strong>流式</strong>的：整首 PCM 不进内存，只按块累加每个 hop 的能量，
 * 所以一首 5 分钟的歌占用的是常数内存（几 KB）。
 */
public final class AutoCue {

    /**
     * 包络的跳距（秒）。
     *
     * <p>20 ms：既够细到能定位「一段刚落下来」的那一刻（50 ms 在快歌上会差半拍），
     * 又足够省（一首 4 分钟的歌 ≈ 12000 个 hop）。
     */
    public static final double HOP_SECS = 0.02;

    /**
     * 拍点分析用的降采样目标（Hz）。
     *
     * <p>为什么要降：谱通量的窗长/跳距是 {@link Analysis} 按 44.1 kHz 量级定的
     * （{@code FRAME=2048} / {@code HOP=512}，即 46 ms / 12 ms）。降到 22.05 kHz 后
     * 变成 93 ms / 23 ms——对「拍点对齐」够用，而且内存与 CPU 都减半。
     * 直接降到 4 kHz 就不行了：窗变 512 ms，起始点全被抹平。
     */
    static final int TARGET_ONSET_RATE = 22_050;

    /** 出场点最早不早于全曲的这个比例。 */
    public static final double MIN_OUTRO_FRACTION = 0.10;   // 工作五：0.20 → 0.10

    /** 出场点最早不早于这么多秒（哪怕歌很短）。 */
    public static final double MIN_OUTRO_SECS = 10.0;       // 工作五：20.0 → 10.0

    /** 出场点之后至少留这么多秒给交叠（保证出场曲在窗口里不会先放完）。 */
    public static final double TAIL_GUARD_SECS = 2.0;

    /**
     * **试过、撤了**：丢尾的硬上界（占全曲比例 15%），想在拿到拍栅格之后防止
     * 「为了两项听不出来的差别丢掉小半首歌」。参考曲上确实有效——出场点从 139.7
     * （丢尾 35.6 s）回到 169.7，但**弄红了 `AutoCueTest`**：
     * 那条判据的素材尾声之后还有一段轻响（210~240 s），硬界把出场点逼进那一段，
     * 整窗响度差变成 0.556（判据要求 < 0.25）。它要的正是「在出场曲还响着的时候切」。
     *
     * <p>结论：这里是**打分口径**的事，不是界的事。软的那一层（{@link #DROP_FREE_FRACTION}
     * 拐点 + 「少切一点」那一项）留着；硬界不做。要压住「丢小半首歌」，得让精细打分
     * 看见「这一段是不是尾声」——与下面那条「进场点总是贴死上界」是同一类毛病。
     */
    /**
     * 「丢尾」开始挨罚的位置（占全曲比例）。
     *
     * <p>「少切一点」原来是一条<strong>线性</strong>项 {@code 1 − 丢尾/时长}：丢掉整首歌的
     * 39% 只罚 {@code 0.39 × 0.35 / 6.25 ≈ 0.022}，管不住——实测（skydive → 春めく）
     * 打分在一处「波形重合略好」的点位上丢掉了 <b>74 s（39%）</b>，而 Apple Music 选的那个
     * 点位只丢 <b>9 s（5%）</b>。线性项分不出这两种「丢尾」的性质差别。
     *
     * <p>所以改成拐点：<b>丢到 {@value} 以内算正常</b>（等尾声淡出、鼓点收干净，DJ 就是这么
     * 放的，不该罚），超过之后才线性罚到 0。Apple 的 5~7%、我们原来那个点的 2.4% 都落在
     * 免费区里，74 s 那种落在罚区。
     */
    public static final double DROP_FREE_FRACTION = 0.12;

    /**
     * 「接近满电平」的门槛（{@code reference} 的比例）——{@link #outroStartSecs} 的首选口径。
     *
     * <p>取值依据（参考对实测）：{@code 0.99} 给 181.16 s，与 Apple 的 181.1 差 0.06 s；
     * 而 {@code 0.97} 给 182.48 s ⇒ 这个口径**很敏感**，所以要贴着满电平取。
     */
    public static final double OUTRO_FULL_RATIO = 0.99;

    /**
     * 「少切一点」这一项的取值（0..1）：丢尾在免费区内给 1，超出去线性降到 0。
     *
     * @param tailDropSecs 被丢掉的尾巴（秒，负数按 0 算）
     * @param durationSecs 全曲时长（秒）
     */
    static double tailTerm(double tailDropSecs, double durationSecs) {
        double fraction = Math.min(1.0,
                Math.max(0.0, tailDropSecs) / Math.max(durationSecs, 1e-6));
        if (fraction <= DROP_FREE_FRACTION) {
            return 1.0;
        }
        return 1.0 - (fraction - DROP_FREE_FRACTION) / (1.0 - DROP_FREE_FRACTION);
    }

    /**
     * 进场点最晚不超过这么多秒。
     *
     * <p><b>2026-10-01 试过改成 15</b>（参考曲 Apple 的入场点就是 15.0 s）：换上去之后
     * 参考判据从 (169.7, 20.0) 变成 <b>(174.8, 15.0)</b>——大幅贴近 Apple，
     * <b>但弄红了 `AutoCueTest#neverCutsAtTheVeryEndAndKeepsTheTwoSidesLevelMatched`</b>
     * （整窗响度差 0.556，那条判据要的是「两边响度接得上」）。也就是说 15 这个数
     * 只是碰巧对上了参考曲，不是普适的——**退回来了**，20 保持不动。
     *
     * <p>真正的毛病在选点的倾向上：`entryScore` 的 {@code 0.8×(1−进场点斜率)} 偏好
     * 「能量正在往上走」的位置，于是进场点**总是贴着上界**（20 s 那次也是 20.0）。
     * 要改的是这个倾向，不是上界。**2026-10-04 这条倾向被"强度闸门 + 两级决策"解掉了**：
     * 上界本身确实放宽了，但放宽只发生在<b>第二级</b>——第一级与今天逐位相同
     * （见 {@link #Bridge#MEASURE} 与 {@code docs/automix-modes.md} §8.0.7）。
     */
    public static final double MAX_INTRO_SECS = 20.0;

    /** 进场点最晚不超过进场曲的这个比例。 */
    public static final double MAX_INTRO_FRACTION = 0.25;

    /** 候选点的上限（拍点栅格再密也够用；同时挡住病态输入把循环撑爆）。 */
    static final int MAX_CANDIDATES = 20_000;

    /** 分频包络的低频分界（Hz）。 */
    static final double BAND_LOW_HZ = 200.0;

    /** 分频包络的高频分界（Hz）。 */
    static final double BAND_HIGH_HZ = 2000.0;

    /** 波形重合用的低采样率（Hz）：见 {@link Envelope#wave}。 */
    static final int WAVE_RATE = 1000;

    /**
     * 精细评分前保留的候选对（先按粗分排序）。
     *
     * <p>粗分只花 O(候选²) 次几项加减，而精细评分要对每一对算整窗的波形相关
     * （几十万次乘加）——不设这一道闸，一次准备会从几十毫秒涨到几秒。
     * 实测：保留 {@value #SHORTLIST} 对时，精细评分约 20–60 ms（采样率 48 kHz、两侧各几百个候选）。
     */
    static final int SHORTLIST = 96;

    /**
     * 粗排里给「强度超标」的点对加的惩罚。
     *
     * <p>取值只要**严格大于粗排五项之和的上界**就够（那五项最多约 5），
     * 所以 {@value} 不是调出来的听感参数，而是「让合规点对严格优先」这件事的下界。
     * 见 {@code planCollect} 里那段注释。
     */
    static final double COARSE_GATE_PENALTY = 10.0;

    /**
     * 粗排里强度贴合分（{@code 1 − 贴合}）的权重。
     *
     * <p>量级要与粗排那五项相当（它们合计通常 0.1~1.0），所以取 {@value}：
     * 一次"掉下去"（贴合 0）在粗排里加 1.0，足够把它挤出前 96；而死区内是 0——
     * 本来就接得上的场合，候选入围名单与加这一项之前**完全一样**（这是参考对指纹
     * 能回到原值的原因之一）。
     */
    static final double COARSE_INTENSITY_WEIGHT = 1.0;

    /**
     * 「可辨节拍」的置信度门槛。
     *
     * <p>要和 {@link Analysis} 报出来的 {@code Tempo.confidence} <b>同一把尺子</b>才谈得上
     * 严不严。硬算路（{@code tempoFromOnsetEnvelope}）2026-10-01 换了尺子：从「主峰 ÷
     * 搜索区间平均 |自相关| ÷ 8」换成<b>归一化自相关</b>（{@code |ac[结论周期]| ÷ 包络总能量}），
     * 因为旧尺子的分母随延迟格数变——同一首 skydive 在 PCM 路是 0.779、扫描路只有 0.439。
     * 新尺子的实测（48 kHz）：点击轨 0.84~0.94、skydive 0.21/0.23/0.26、春めく 0.62~0.80、
     * 白噪声 0.005~0.01、纯正弦 0.12。
     *
     * <p>门槛值<b>仍然留在 0.5</b>，这不是忘了改：<b>把门槛降到 0.15 试过，实测把融合点毁了</b>。
     * skydive 拿到栅格之后（原来它 0.26 卡在 0.5 之下，所以出场点一直落在 20 ms 网格上），
     * 打分把出场点从 170.8 s 挪到 <b>112.1 s、丢尾 74.2 s</b>（Apple Music 选的是 174 s、
     * 丢尾 9 s）——判据 {@code AppleCueReferenceTest} 当场红。逐项查下来，根因不在置信度而在
     * <b>精细打分的结构</b>：七项（波形重合/淡化起伏/频段互补/响度接续/走向/拍漂移/少切一点）
     * 里<b>没有「段落边界」这一项</b>（它只存在于粗排），唯一偏向曲尾的是权重 0.7% 的丢尾项，
     * 于是「栅格一多、可选点位一密」，最先被牺牲的就是「切在尾声开始处」这个性质。
     *
     * <p>所以这条门槛保持 0.5 是<b>有意的现状</b>：先如实留着「skydive 这类歌拿不到拍栅格」，
     * 不拿一个更差的融合点去换。要真正吃到新尺子的好处，得先给精细打分补上「段落边界」那一项
     * （{@code AutoCue.plan} 的七项 → 八项），那是另一件事，判据与实测数据都记在
     * {@code docs/automix-modes.md} §8.1.4 里。
     *
     * <p>门槛对模型路（{@code tempoFromBeats}）也生效，而那条是<b>另一把尺子</b>
     * （{@code 1 − 拍点间隔的平均绝对偏差 / 周期}，实测普遍 0.9 以上），0.5 对它同样说得通。
     * 达不到就如实退回 20 ms 网格（见 {@code alignmentNote}）。
     *
     * <p><b>已知退化（2026-10-02 实测）</b>：这个尺子在<b>平坦</b>包络上会失效到 1.0 附近——
     * 数学上「归一化自相关」把「处处都相关」当成了「很相关」。实测纯 220 Hz 正弦（一个 onset
     * 都没有）拿到 {@code bpm = 150.0、confidence = 0.9994}，比真曲还高。所以<b>不能</b>靠它一个
     * 去判断「这段素材有拍子」：要再要一道「包络里真的有 onset」的判据，实测最干净的是
     * <b>峰均比</b>= {@code max(onset) ÷ mean(onset)}：纯正弦 3.02、白噪声 9.41、140 BPM 点击轨
     * 21.43、skydive 18.92/15.56、春めく 22.73/22.93、Unstoppable 15.83/18.82（门槛取 8.0 左右
     * 就能把「先验峰上的假速度」挡在外面）。这条尺子本身<b>保持现状</b>：改它会连带动到点位
     * 对齐、小节相位与 Apple 参考点位。
     */
    static final float MIN_BEAT_CONFIDENCE = 0.5f;

    /**
     * <b>叠唱的硬约束</b>（P6，秒）：窗口里「两边同时有人在唱」超过这个秒数的候选，
     * <b>不允许</b>成为最终选择——除非所有候选都超标（那时照旧挑最好的，并在 note 里说明）。
     *
     * <p>为什么软打分不够：人声重叠在总分里只占 {@code 0.8 / 7.75 ≈ 10%}，而「响度接续」
     * 占 1.0、「波形重合」占 1.6。只要别的项足够偏爱某一处，10% 是拉不住的——这不是推测：
     * 判据 {@code AutoCueVocalGateTest} 用一个「出场曲前 8 秒电平刚好匹配、之后掉 30 dB」
     * 的夹具把它逼了出来，软打分选中的进场点是 <b>1.88 s</b>，正落在 0~10 s 的叠唱区里
     * （日志：「人声重叠 0.00（叠唱 5.0s）」）。
     *
     * <p>两首歌的人声同时响是「皱在一起很糊」里最不能接受的一种：低频可以靠 P5 让位，
     * 人声没有「让位」可言。所以这一项从「扣分」升级成「否决」。
     *
     * <p>取值 {@value #VOCAL_GATE_SECS} s，与软打分的「不罚区」同一个数：
     * {@link #VOICE_OVERLAP_FREE_SECS} 秒以内那一项就是满分，超过它才开始扣分——
     * 所以「free 以内」本来就是这条尺子自己认为可以接受的范围，硬约束直接用它。
     *
     * <p><b>为什么现在敢用这么紧的数</b>：当年不敢，是因为代理 VAD 会把「中频上同样按
     * 音节起伏的吉他扫弦/琶音」算成人声（见 {@code voiceActive} 的注释），拿它做硬约束
     * 会误伤。现在 VAD 多了第三道判据——<b>中置显著度</b>（{@link #CENTER_SALIENCE_MIN}，
     * 纯 DSP 的 mid/side），把「被摆开的」那一类挡在外面。实测（skydive → 春めく）：
     * 同一处候选的叠唱秒数从 0.6 s 变成 0.5 s（那 0.1 s 就是被摆开却被误判的一段）。
     *
     * <p>而且这条门在真曲目上真的动了手：出场 175.5 / 进场 14.0 的候选虽然总分更高，
     * 但叠唱超过 0.5 s，被否掉；最后取的是进场 <b>15.5</b>（叠唱 0.4 s）——而 Apple Music
     * 在同一个位置选的是 <b>15.0</b>。也就是说这道门把我们<b>推得更靠近</b>那个参考，
     * 不是推远。
     *
     * <p><b>为什么阈值不随窗口缩放</b>（2026-10-02 试过，被判据挡回来）：一开始写成
     * {@code max(0.5, 12% × 窗口)}，语义上更"讲道理"（占窗口的比例才是听感），但它给分析
     * 造了一个<b>反向激励</b>——窗口越长、约束越松，于是它开始靠选更长的窗口来换取通过
     * （实测：{@code AutoCuePlanTest.aTighterTempoPrefersAShorterWindow} 从 4 s 变成 20 s、
     * {@code AutoCueJointTest} 的打平取短窗也失效）。约束不该随被约束的对象缩放，
     * 满足不了的时候由下面的降级阶梯如实说出来。
     */
    static final double VOCAL_GATE_SECS = 0.5;

    /**
     * <b>中置显著度的门槛</b>：低于它就<b>不算人声</b>（见 {@link Envelope#center}）。
     *
     * <p>为什么需要它：代理 VAD（{@link #voiceActive}）只有两条判据——「够响」与「在动」，
     * 而中频上同样按音节起伏的<b>吉他扫弦、琶音、加倍录的合成器</b>会被误判成人声
     * （代码里一直如实写着这一条，也正因如此当年的硬约束没上）。人声几乎总是录在<b>中间</b>的，
     * 而上面那几类通常被摆到两边或左右不相关——中置显著度正好把它们分开。
     *
     * <p>取值 <b>0.62</b>：居中（{@code L = R}）是 1.0；硬摆到一边、或左右不相关
     * （各占一半功率）是 ≈0.5；纯侧（反相）是 0.0。门槛只在 0.5 之上留一点余量，
     * 目的是<b>不误伤</b>「居中但带一点立体声混响」的真人声，同时把摆开的挡掉。
     *
     * <p>没有中置数据（单声道素材、老构造器）时这道门<b>不生效</b>——行为与加它之前逐位相同。
     */
    static final double CENTER_SALIENCE_MIN = 0.62;

    /**
     * <b>出场点必须落在这个比例之内（距曲尾）</b>：{@code duration − cut ≤ 15% × duration}。
     *
     * <p>为什么必须是硬约束而不是靠打分：打分里「少切一点」（{@link #WEIGHTS} 第 7 项）只占
     * {@code 0.35 / 7.75 ≈ 4.5%}——实测在真曲目上（skydive 190.3 s → 春めく，AI 自动档 +
     * 自动点位）选的是<b>出场 90.5 s</b>，也就是<b>把 45% 的歌掐掉</b>，因为别的项
     * （频段互补、低频冲突、响度接续）更偏爱那一处的素材。听感后果是致命的：用户要
     * 「几乎无感、听不出换歌」，而把一首歌掐掉 45% 是任何人都立刻听得出来的「换歌」。
     * Apple Music 在同一个参考对上出场 174 s（距曲尾 8.6%）。
     *
     * <p><b>为什么约束的是「出场点」而不是「没播的尾巴」</b>（2026-10-02 试过，被判据挡回来）：
     * 一开始写的是「丢尾 ≤ 15%」，而 {@code 丢尾 = 全长 − 出场点 − 窗口}——同一个出场点，
     * <b>窗口越长丢尾越小</b>，于是分析学会了靠「把窗口拉长」来通过约束（实测
     * {@code AutoCuePlanTest.aTighterTempoPrefersAShorterWindow} 从 4 s 变成 20 s、
     * {@code AutoCueJointTest} 的打平取短窗也失效）。<b>约束不该随被约束的对象缩放</b>：
     * 这一条只取决于「出场点离曲尾多远」，与窗口长度无关。
     *
     * <p>取值 {@value #MAX_CUT_FROM_END_FRACTION}（15%）：参考对 Apple 的 8.6% 有近两倍余量，
     * 「半路切歌」那种 45% 被干净地挡掉。真遇到「这首歌本来就该早点走」的素材，
     * 全部候选超标时仍会照旧给出一个点并说明原因。
     */
    public static final double MAX_CUT_FROM_END_FRACTION = 0.25;   // 工作五：0.15 → 0.25

    /**
     * <b>窗口长度的最优点</b>（秒）：{@value #WINDOW_OPTIMUM_SECS} s。
     *
     * <p>量级来自用户自己的听感基准：Apple Music 在 skydive → 春めく 上用的是 <b>6~7 秒</b>。
     * 我们曾经选 4.0 s、也曾经塌到 1.0 s（见 {@link #WINDOW_LENGTH_BONUS}）。
     */
    public static final double WINDOW_OPTIMUM_SECS = 6.5;

    /**
     * **"窗口是整小节"的奖励**（第 51 轮 ✓，用户要求"窗口按小节算" ✓）。
     *
     * <p>与既有的 {@code barBonus} 同一个机制 ✓（它奖励"**点**落在小节线上" ✓，这一项奖励
     * "**窗口长度**是整小节" ✓ —— 两者是不同的东西 ✓）。**0 = 关** ✓（默认 ✓ ⇒ 逐位不变 ✓）。
     */
    private static volatile double appleBarWindowWeight = 0.0;

    public static void setAppleBarWindowWeight(double w) {
        appleBarWindowWeight = w > 0.0 ? w : 0.0;
    }

    /**
     * **空安全的加**（2026-10-09 ✓，用户贴的 NPE ✓）：{@code barBonus(...)} 返回的是**可空的
     * {@code Double}**（null = 他那一档没有小节奖励 ✓）—— 我先前写成 `barBonus(...) + 我的奖励`
     * ✗ ⇒ **对 null 自动拆箱 ⇒ NPE** ⇒ 自动点位整条失败、退回固定点位 ✗（用户听到的"不和谐"✓）。
     * 这里保持原语义 ✓：底值为 null 且我没有奖励 ⇒ 仍传 null ✓；有奖励 ⇒ 以 0 为底相加 ✓。
     */
    private static Double appleBarWindowAdd(Double base, double windowSecs) {
        double add = appleBarWindowBonus(windowSecs);
        if (base == null) {
            return add > 0.0 ? Double.valueOf(add) : null;
        }
        return Double.valueOf(base.doubleValue() + add);
    }

    private static double appleBarWindowBonus(double windowSecs) {
        if (appleBarWindowWeight <= 0.0) {
            return 0.0;
        }
        double bar = appleBarTieSecsRef;
        if (!(bar > 0.0)) {
            return 0.0;
        }
        // **在目标小节数处最高**（第 52 轮 ✓）：窗口折成小节数，离目标越远奖励越低 ✓。
        if (appleBarTargetBars <= 0) {
            return 0.0;
        }
        double bars = windowSecs / bar;
        double err = Math.abs(bars - appleBarTargetBars);
        return appleBarWindowWeight * Math.max(0.0, 1.0 - err / appleBarTargetBars);
    }

    private static volatile double appleBarTieSecsRef = 0.0;

    /** 目标小节数（奖励在它处最高 ✓；0 = 关 ✓）。 */
    private static volatile int appleBarTargetBars = 0;

    public static void setAppleBarTargetBars(int bars) {
        appleBarTargetBars = Math.max(0, bars);
    }

    public static void setAppleBarTieSecsRef(double secs) {
        appleBarTieSecsRef = secs > 0.0 ? secs : 0.0;
    }

    /**
     * <b>窗口长度的峰值加成</b>（加到总分上，{@link #windowLengthBonus}）：0.06。
     *
     * <p>为什么必须加这一项：九项打分全是<b>窗口内的均值</b>，而每个窗口取「96 对点位里的
     * 最高分」——窗口越短越容易找到局部完美的位置，于是分数<b>单调偏向更短</b>。实测
     * （真曲目、标称 15 s）：1.0s→0.851、2.0s→0.847、4.0s→0.834、7.5s→0.803、15.0s→0.694。
     * 后果两头都不对：塌到 1~3 秒是硬切（用户「一换歌就听得出来」），拖到 15 秒是糊。
     *
     * <p>取值 0.06 的依据：实测「3 s 与 6 s」之间的平均副产物约 0.02~0.04，
     * 0.06 足以把它翻过来，又远小于真实点位之间的分差（0.1 量级），
     * 所以它只决定「哪个窗口」，不会让人拿一个明显更差的点位换一个更好的窗口。
     * 加成在一个窗口内部是常数，因此<b>不影响窗口内的点位选择</b>。
     */
    public static final double WINDOW_LENGTH_BONUS = 0.06;

    /** 距最优点多远加成归零（秒）。 */
    public static final double WINDOW_LENGTH_FALLOFF_SECS = 8.0;

    /**
     * 窗口长度的加成：最优点处 {@value #WINDOW_LENGTH_BONUS}，向两侧线性递减，
     * 距最优点 {@value #WINDOW_LENGTH_FALLOFF_SECS} 秒以外为 0。
     *
     * <p>它只加在 {@code Score.total} 上（见 {@code Score} 的构造器），而 {@code total}
     * 决定的是「哪个窗口/哪一对点位更好」——同一窗口内它是常数，所以点位的相对优劣不变。
     */
    public static double windowLengthBonus(double windowSecs) {
        double d = Math.abs(windowSecs - WINDOW_OPTIMUM_SECS);
        if (d >= WINDOW_LENGTH_FALLOFF_SECS) {
            return 0.0;
        }
        return WINDOW_LENGTH_BONUS * (1.0 - d / WINDOW_LENGTH_FALLOFF_SECS);
    }

    /**
     * {@link #MAX_CUT_FROM_END_FRACTION} 的<b>方法形式</b>：给别的模块的判据用。
     *
     * <p>为什么要多这一个方法：{@code public static final double} 是<b>编译期内联</b>的——
     * 别的模块编译时会把当时的值嵌进自己的字节码。实测踩过一次：把常量临时改成 1.0 做了一次
     * 「判据能不能抓住」的验证，改回来之后判据<b>仍在拿 1.0 比较</b>，于是"绿"是假的。
     * 方法调用不会被内联，所以跨模块读常量一律走它。
     */
    // ── 三道闸的注入点（2026-10-06 第一阶段工作六）───────────────────────────────
    //
    // 用户要求：「放开限制后，在少数极端情况下过渡会变得很长、变速会很猛、切点会很早。
    // 为了只在旋律匹配足够高时才全放开，保留三道闸：旋律匹配大于 0.9，全放开；0.7 到 0.9，
    // 半放开（原限制和全放开限制的**线性插值**，系数 = (旋律 − 0.7)/0.2）；小于 0.7，保持原限制。」
    //
    // 做法：把这三个限制的**读数**收进这一个出口，闸门按旋律读数写进来（{@link #applyMelodyGate}），
    // 候选生成照旧读这三个方法——于是"闸门"只有一处实现，选点代码一行不用改。
    // 没有开闸（{@code melodyGate == null}）时返回的正是放开后的常量，行为与加这一条之前逐位相同。
    private static volatile double[] melodyGate;

    /** 放开前的那一档（"原限制"）：{@code {cut 距尾比例, 最短尾奏秒, 最短尾奏比例}}。 */
    static final double[] LIMITS_BEFORE_RELAX = {0.15, 20.0, 0.20};

    /** 放开后的那一档（"全放开"）：与三个常量同源，别再写第二遍数字。 */
    static double[] limitsAfterRelax() {
        return new double[] {MAX_CUT_FROM_END_FRACTION, MIN_OUTRO_SECS, MIN_OUTRO_FRACTION};
    }

    /**
     * 按旋律读数把三个限制收到该有的档（{@link #gatedLimit} 逐项插值）。
     *
     * <p>{@code melody} 为 {@code NaN}（没量到）时按原限制——"不假装相似"这条纪律在这里
     * 也成立：量不出来就别放开。
     */
    static void applyMelodyGate(double melody) {
        double[] opened = limitsAfterRelax();
        double[] gated = new double[3];
        for (int i = 0; i < 3; i++) {
            gated[i] = gatedLimit(LIMITS_BEFORE_RELAX[i], opened[i], melody);
        }
        melodyGate = gated;
    }

    /** 关掉闸门（回到"放开后的常量"，也就是这一条接上之前的行为）。 */
    static void clearMelodyGate() {
        melodyGate = null;
    }

    /** 这一次的 cut 距尾比例上限（闸门开着时是插值后的值）。 */
    static double cutFromEndFractionNow() {
        double[] g = melodyGate;
        return g == null ? MAX_CUT_FROM_END_FRACTION : g[0];
    }

    /** 这一次的最短尾奏（秒）。 */
    static double minOutroSecsNow() {
        double[] g = melodyGate;
        return g == null ? MIN_OUTRO_SECS : g[1];
    }

    /** 这一次的最短尾奏占比。 */
    static double minOutroFractionNow() {
        double[] g = melodyGate;
        return g == null ? MIN_OUTRO_FRACTION : g[2];
    }

    public static double maxCutFromEndFraction() {
        // 走闸门出口（2026-10-06 工作六）：常量在别的模块里是**编译期内联**的，
        // 所以跨模块的读数必须从这个方法出去，闸门才真的对外可见。
        return cutFromEndFractionNow();
    }

    private static final double[] EMPTY_TIMES = new double[0];

    /**
     * 可增长的 {@code float} 列表——扫描期按 hop 追加几万个数时的容器。
     *
     * <p>为什么不用 {@code ArrayList<Float>}（2026-10-03，性能）：每个元素一个装箱对象，
     * 一首 5 分钟的歌上光是 rms/低频波形/中置显著度就是几万个对象，手机上全是给 GC 的礼物。
     * 这里只实现这个类真正用到的三个方法，行为与 {@code ArrayList} 的那三个逐条对应。
     */
    private static final class FloatList {
        private float[] data;
        private int size;

        FloatList(int capacity) {
            data = new float[Math.max(16, capacity)];
        }

        void add(float v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        int size() {
            return size;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private AutoCue() {
    }

    /** 一条能量包络（已按 95 分位归一化，所以只反映形状，不受整曲电平影响）。 */
    public static final class Envelope {

        /** 每个 hop 的 RMS（0..1）。 */
        public final float[] rms;

        /** 每个 hop 的正向能量跳变（0..1）：像不像段落边界。 */
        public final float[] onset;

        /** 时长（秒）。 */
        public final double durationSecs;

        /**
         * 色度新颖度给出的<b>尾声起点</b>（秒）；{@code < 0} = 这次没算（手搭的包络、或读不出）。
         *
         * <p>为什么要它：能量类特征看不见「段落结束」——实测（2026-10-01）skydive 的平滑电平
         * 从 160 s 到 185 s 一直是 0.72~0.83，186 s 才断崖，而 Apple Music 选的出场点是 174.0 s。
         * 那是个**音乐段落**边界，只有和声/音色（色度）才看得见。见 {@link #noveltyBoundary}。
         */
        public final float structureOutroStartSecs;

        /** 色度新颖度给出的<b>进场曲真正开始</b>（秒）；{@code < 0} = 没算。 */
        public final float structureIntroStartSecs;

        /**
         * <b>逐帧 chroma 序列</b>（扁平：每帧 {@code SpectralOnsets.PITCH_CLASSES} = 12 个值，
         * 每帧已按总和归一）；{@code null} = 没算（手搭包络、或这次扫描没开 chroma）。
         *
         * <p>为什么要留下来（2026-10-06，第一阶段工作一「旋律永远第一位」）：原来 chroma
         * 只在扫描内部用一下就扔了（只拿它算过 novelty/结构边界），于是**选点根本不知道
         * A 的哪一段和 B 的哪一段旋律最像**——这正是用户说的「过渡明显听出来是第二首歌」
         * 在选点侧的根因。留下之后 {@link AutoCue#melodyMatch} 可以逐帧比。
         *
         * <p>非 final：由 {@code scanInternal} 在全部参数就绪、包络构造出来的那一刻挂上，
         * 这样不必改 {@code Envelope} 的四个构造函数与全部 {@code envelopeOf} 重载
         * （手搭包络的单测因此逐位不变）。
         */
        public float[] chroma;

        /** {@link #chroma} 的帧数（{@code chroma.length / 12}）。 */
        public int chromaFrames;

        /** {@link #chroma} 每帧的秒数（{@code SpectralOnsets.Stream.hop() / workRate}）。 */
        public double chromaHopSecs;

        /**
         * 测出来的速度/相位（帧的单位是 {@link #beatRate}）；没有可辨节拍时 {@code null}。
         *
         * <p>它决定候选点落在<strong>拍</strong>上还是落在 20 ms 网格上——见
         * {@link #beatsIn(double, double)}。
         */
        /**
         * 拍点结论。
         *
         * <p><b>不是 final</b>（2026-10-07）：要允许把"模型给的 BPM"换进来 —— 挑档那一层
         * 用的是模型优先的分析（{@code tempoOf}），而包络里这份来自硬算；两处各算一遍会
         * 差出四倍多（实测参考对 29.32% vs 6.49%）。统一的方向是**让模型那份流进包络**，
         * 而不是反过来（反过来会把模型结果盖掉，三条"模型 BPM 优先"的判据当场变红）。
         *
         * <p>同一类处理在本类里已有先例：{@code chroma*} 三个字段也是构造之后才挂上去的。
         */
        public Analysis.Tempo tempo;

        /** {@link #tempo} 里帧对应的采样率（扫描时降采样之后的那个）。 */
        public final int beatRate;

        /**
         * 三条分频包络（0 = 低频 &lt; {@value #BAND_LOW_HZ} Hz、1 = 中频、2 = 高频 &gt;
         * {@value #BAND_HIGH_HZ} Hz），每个元素与 {@link #rms} 同一时间栅格（每 {@value #HOP_SECS} 秒一格）。
         *
         * <p>为什么要它：过渡期两首歌是<strong>同时响</strong>的，而「叠上去好不好听」很大程度
         * 取决于两边的能量落在<strong>哪些频段</strong>——两边都在低频轰（两个底鼓打架）是糊的，
         * 一边低频重、另一边高频亮（频谱互补）则互相遮蔽。只看总能量看不出这件事。
         *
         * <p>手工构造的包络（单测）可以是 {@code null}，那时这一项按中性 0.5 算。
         */
        public final float[][] band;

        /**
         * <b>中置显著度</b>（每 hop 一个，0..1；{@code null} = 没算）。
         *
         * <p>定义是「中置功率 ÷ 总功率」：{@code mid = (L+R)/2}、{@code side = (L−R)/2}，
         * 于是居中（{@code L = R}）→ 1.0、硬摆到一边或左右不相关 → ≈0.5、
         * 纯侧（{@code L = −R}）→ 0.0。用来给代理 VAD 去掉一类假阳性：中频上同样按
         * 音节起伏的吉他扫弦/琶音（见 {@link #voiceActive}）。
         *
         * <p>只有立体声素材才有；单声道、或只有一路时它是 {@code null}，
         * 那时 VAD 的行为与加这个字段之前<b>逐位相同</b>。
         */
        public final float[] center;

        /**
         * 低采样率单声道波形（{@value #WAVE_RATE} Hz），用于算<strong>波形重合度</strong>。
         *
         * <p>为什么需要真的波形：包络只回答「多响」，而「合不合得上」是相位的事——
         * 两个底鼓错开半个周期时包络看起来完美，叠起来却是一团低频。
         * 波形取到 {@value #WAVE_RATE} Hz 就够：这个带宽里装着底鼓、贝斯与和弦根音，
         * 也就是「重合得好不好」听得出来的那一部分。
         *
         * <p>内存：{@value #WAVE_RATE} Hz × 4 字节 ≈ 4 KB/s，一首 5 分钟的歌约 1.2 MB
         * （出场侧要扫整曲，进场侧只扫前 25 秒）。这是这一层唯一的「随曲长增长」的开销，
         * 如实记在这里。
         */
        public final float[] wave;

        /** {@link #wave} 的采样率（Hz）；{@code wave} 为 {@code null} 时是 0。 */
        public final int waveRate;

        // ---- 只取决于这条包络本身的派生量（惰性缓存，2026-10-03）------------------
        //
        // **为什么必须缓存**：{@code voiceBasic} 每次调用都要「中频那一列」，而它在挑点阶段
        // 是**每个候选 × 每个 hop** 被问一次——参考曲对上是几十万次，每次把整条列复制一遍
        // （190 秒的歌 ≈ 9500 格 ≈ 38 KB）。JFR 实测：一次 prepare 分配 **3.5 GB**、耗时
        // 1.7 s 里的一大块都花在这上面（`AutoCue.midBandColumn` 是分配榜第一名）。
        //
        // 这三样都**只取决于包络**、与「问哪一格」无关，所以缓存它们只是把同一段算术
        // 从「几十万次」变成「一次」，**结果逐位相同**（同一个数组、同样的累加顺序）。
        // 用 volatile + 本地变量：多线程下最坏是两个线程各算一遍，内容相同，行为不变
        // （包络本身是不可变的，这些字段只在第一次访问时被填充）。
        private volatile float[] midCache;
        private volatile float[] voiceBasicCache;
        private volatile float[] voiceActiveCache;

        /** {@link #intensityCurve} 的缓存（惰性、只算一次，见那边的注释）。 */
        private volatile float[] intensityCache;

        /** 中频列（{@code band[1]}），{@code band == null} 时是 {@code null}。 */
        float[] midColumn() {
            float[] c = midCache;
            if (c != null) {
                return c;
            }
            if (band == null) {
                return null;
            }
            float[] out = new float[band.length];
            for (int i = 0; i < band.length; i++) {
                out[i] = band[i][1];
            }
            midCache = out;
            return out;
        }

        /** {@link #voiceBasic}(this, i) 的整条掩码（1 = 有人在唱）。 */
        float[] voiceBasicMask() {
            float[] m = voiceBasicCache;
            if (m != null) {
                return m;
            }
            float[] mid = midColumn();
            if (mid == null) {
                return null;
            }
            float[] mask = new float[mid.length];
            for (int i = 0; i < mid.length; i++) {
                mask[i] = voiceBasicAt(mid, i) ? 1f : 0f;
            }
            voiceBasicCache = mask;
            return mask;
        }

        /**
         * {@link #voiceActive}(this, i) 的整条掩码（在基础判据上再加中置门）。
         *
         * <p>{@code 1 = 这一格有人在唱}；{@code null} = 没有中频那一列（手搭包络）。
         *
         * <p><b>为什么是 public</b>（2026-11-21）：离线 mashup 要按"哪几小节有人声"选段、
         * 判叠唱冲突，读的必须是和这里<b>同一条</b>判据。把同一套判据抄第二份出来，
         * 两边迟早在阈值上分叉——所以开放读取，而不是复制。写入方仍是本类（惰性缓存），
         * 调用方不要修改返回的数组。
         */
        public float[] voiceActiveMask() {
            float[] m = voiceActiveCache;
            if (m != null) {
                return m;
            }
            float[] mid = midColumn();
            if (mid == null) {
                return null;
            }
            float[] mask = new float[mid.length];
            for (int i = 0; i < mid.length; i++) {
                mask[i] = voiceBasicAt(mid, i) && centered(Envelope.this, i) ? 1f : 0f;
            }
            voiceActiveCache = mask;
            return mask;
        }

        Envelope(float[] rms, float[] onset, double durationSecs) {
            this(rms, onset, durationSecs, null, 0, null, null, 0);
        }

        /**
         * **按时间范围切一份包络**（2026-10-09 ✓，"自动点位模式"三档用 ✓）：
         * 把 [{@code fromSecs}, {@code toSecs}] 之外的内容切掉 ✓，新包络的时间原点 = {@code fromSecs} ✓。
         *
         * <p>用途：稳定 / 保守档把"允许挑点的范围"**限制在输入上** ✓（A 取尾段 ✓ B 取头段 ✓）
         * ⇒ 下游的窗口 / 斜坡 / 滤波**一行都不用动** ✓（本会话验证过最稳的做法 ✓）。
         * **激进档不会调用它** ✓ ⇒ 与既有行为逐位相同 ✓。
         *
         * <p>边界规矩（第 6 条纪律 ✓）：空区间 / 退化区间 / 缺数组 ⇒ **原样返回 this** ✓，
         * 绝不造出半截包络 ✗。
         */
        Envelope sliceForCue(double fromSecs, double toSecs) {
            if (rms == null || rms.length == 0 || !(durationSecs > 0.0)) {
                return this;
            }
            double from = Math.max(0.0, Math.min(fromSecs, durationSecs));
            double to = Math.max(from, Math.min(toSecs, durationSecs));
            if (to - from < 0.5) {
                return this;                       // 太短 ⇒ 不值得切 ✓（也避免空数组 ✓）
            }
            double framesPerSec = rms.length / durationSecs;
            int i0 = (int) Math.floor(from * framesPerSec);
            int i1 = (int) Math.ceil(to * framesPerSec);
            i0 = Math.max(0, Math.min(i0, rms.length - 1));
            i1 = Math.max(i0 + 1, Math.min(i1, rms.length));
            float[] newRms = java.util.Arrays.copyOfRange(rms, i0, i1);
            float[] newOnset = (onset != null && onset.length >= i1)
                    ? java.util.Arrays.copyOfRange(onset, i0, i1) : onset;
            float[][] newBand = null;
            if (band != null) {
                newBand = new float[band.length][];
                for (int b = 0; b < band.length; b++) {
                    newBand[b] = (band[b] != null && band[b].length >= i1)
                            ? java.util.Arrays.copyOfRange(band[b], i0, i1) : band[b];
                }
            }
            float[] newWave = wave;
            if (wave != null && waveRate > 0) {
                int w0 = (int) Math.floor(from * waveRate);
                int w1 = (int) Math.ceil(to * waveRate);
                w0 = Math.max(0, Math.min(w0, wave.length - 1));
                w1 = Math.max(w0 + 1, Math.min(w1, wave.length));
                newWave = java.util.Arrays.copyOfRange(wave, w0, w1);
            }
            // 结构点**平移**到新原点 ✓；原本"没算"（<0）的保持 <0 ✓。
            float newOutro = structureOutroStartSecs >= 0f
                    ? (float) Math.max(0.0, structureOutroStartSecs - from) : structureOutroStartSecs;
            float newIntro = structureIntroStartSecs >= 0f
                    ? (float) Math.max(0.0, structureIntroStartSecs - from) : structureIntroStartSecs;
            return new Envelope(newRms, newOnset, to - from, tempo, beatRate,
                    newBand, newWave, waveRate, newOutro, newIntro);
        }

        Envelope(float[] rms, float[] onset, double durationSecs,
                 Analysis.Tempo tempo, int beatRate) {
            this(rms, onset, durationSecs, tempo, beatRate, null, null, 0);
        }

        Envelope(float[] rms, float[] onset, double durationSecs,
                 Analysis.Tempo tempo, int beatRate,
                 float[][] band, float[] wave, int waveRate) {
            this(rms, onset, durationSecs, tempo, beatRate, band, wave, waveRate, -1f, -1f);
        }

        /** 同上的 10 参版本：末两个是色度新颖度给出的结构点（{@code < 0} = 没算）。 */
        Envelope(float[] rms, float[] onset, double durationSecs,
                 Analysis.Tempo tempo, int beatRate,
                 float[][] band, float[] wave, int waveRate,
                 float structureOutroStartSecs, float structureIntroStartSecs) {
            this(rms, onset, durationSecs, tempo, beatRate, band, wave, waveRate,
                    structureOutroStartSecs, structureIntroStartSecs, null);
        }

        /**
         * 11 参版本：末一个参数是<b>中置显著度</b>（每 hop 一个，0..1，{@code null} = 没算）。
         *
         * <p>它回答的是「这一格的能量有多少来自左右声道<b>共同</b>的部分」——也就是
         * 「这段声音在不在中间」。人声几乎总是录在中间的，而被声像摆开的吉他扫弦、
         * 加倍录的合成器、宽混响铺底都不是。见 {@link #CENTER_SALIENCE_MIN}。
         */
        Envelope(float[] rms, float[] onset, double durationSecs,
                 Analysis.Tempo tempo, int beatRate,
                 float[][] band, float[] wave, int waveRate,
                 float structureOutroStartSecs, float structureIntroStartSecs,
                 float[] center) {
            this.center = center;
            this.structureOutroStartSecs = structureOutroStartSecs;
            this.structureIntroStartSecs = structureIntroStartSecs;
            this.rms = rms;
            this.onset = onset;
            this.durationSecs = durationSecs;
            this.tempo = tempo;
            this.beatRate = beatRate;
            this.band = band;
            this.wave = wave;
            this.waveRate = waveRate;
        }

        /**
         * <b>浅拷贝一份、只换 {@code tempo}</b> —— 给"让挑点用模型那一份速度"用，
         * 但**不许就地改原件** ✗。
         *
         * <p>为什么必须有这个方法（2026-10-07，用户报"点位有点偏差"的根因之一）：
         * {@code AutoCueTransition} 原本直接写 {@code outroEnvelope.tempo = model.tempoOut()}
         * ✗，而那个包络是 {@link #cachedScan} 里<b>被缓存共享</b>的对象 ⇒
         * 就地改它会**污染缓存**：实测 A 的读数从当前那份（bpm 128.000 / 置信 0.948）被改成
         * 模型那份（bpm 160.807 / 置信 0.632），置信跌破闸门门槛 ⇒ **此后每一次过渡**都判成
         * "BPM 闸门没过、不用降速" ✗ —— 一个进程里只有**第一次**过渡是对的。
         *
         * <p>拷贝要带上 {@code chroma} 那一串（旋律匹配靠它），以及它的帧数与 hop。
         */
        Envelope withTempo(Analysis.Tempo newTempo) {
            Envelope copy = new Envelope(rms, onset, durationSecs, newTempo, beatRate,
                    band, wave, waveRate, structureOutroStartSecs, structureIntroStartSecs,
                    center);
            copy.chroma = chroma;
            copy.chromaFrames = chromaFrames;
            copy.chromaHopSecs = chromaHopSecs;
            return copy;
        }

        /**
         * 有没有<strong>可信</strong>的拍点栅格（不可信就退回 20 ms 网格）。
         *
         * <p>要求置信度过 {@link #MIN_BEAT_CONFIDENCE}：白噪声也能被测出一个「速度」，
         * 而照着一个假栅格挪点位比不对齐更糟。
         */
        public boolean hasBeatGrid() {
            return tempo != null && beatRate > 0 && tempo.periodFrames > 1.0f
                    && tempo.confidence >= MIN_BEAT_CONFIDENCE;
        }

        /**
         * {@code [fromSecs, toSecs]} 区间里的<strong>拍点</strong>（秒，升序）。
         *
         * <p>没有拍点栅格时返回空数组（调用方据此退回按 hop 取候选）。
         */
        public double[] beatsIn(double fromSecs, double toSecs) {
            if (!hasBeatGrid() || toSecs <= fromSecs) {
                return EMPTY_TIMES;
            }
            double periodSecs = tempo.periodFrames / (double) beatRate;
            if (periodSecs <= 0.01 || periodSecs > 5.0) {
                return EMPTY_TIMES;
            }
            long fromFrame = (long) Math.ceil(fromSecs * beatRate);
            long toFrame = (long) Math.floor(toSecs * beatRate);
            if (toFrame <= fromFrame) {
                return EMPTY_TIMES;
            }
            long beat = tempo.nextBeatAfter(fromFrame);
            // **不装箱**（2026-10-03，性能）：原来用 `ArrayList<Double>` 收，每个拍点一个
            // `Double` 对象；而 `beatsIn` 在打分的内层被反复调用（JFR 分配榜第 3 名）。
            // 现在用可增长的 `double[]`，值还是同一个表达式 `beat / (double) beatRate`。
            double[] out = new double[Math.min(MAX_CANDIDATES, 64)];
            int n = 0;
            while (beat <= toFrame && n < MAX_CANDIDATES) {
                if (n == out.length) {
                    out = java.util.Arrays.copyOf(out,
                            Math.min(MAX_CANDIDATES, out.length * 2));
                }
                out[n++] = beat / (double) beatRate;
                // 「上一拍 +1 帧再问下一拍」而不是自己累加周期：后者会把四舍五入的误差
                // 越滚越大，一首 4 分钟的歌末尾能差出小半拍
                beat = tempo.nextBeatAfter(beat + 1L);
            }
            return n == out.length ? out : java.util.Arrays.copyOf(out, n);
        }

        /** 第 {@code index} 个 hop 的起始时刻（秒）。 */
        public double secs(int index) {
            return index * HOP_SECS;
        }

        /** 时刻 → hop 下标（夹在有效范围内）。 */
        public int indexAt(double secs) {
            int index = (int) Math.round(secs / HOP_SECS);
            if (index < 0) {
                return 0;
            }
            return index >= rms.length ? Math.max(0, rms.length - 1) : index;
        }

        /** hop 数。 */
        public int hops() {
            return rms.length;
        }
    }

    /**
     * <b>过渡路径上的一段素材</b>（用户 2026-10-05：「差距三：不支持"多段拼接"。
     * 你现在一次过渡只能用 A 的尾奏 + B 的 intro 两段素材……在 Cue 里加一个
     * {@code List<Segment>}，每个 Segment 是 (source, startSecs, endSecs, repeat)」）。
     *
     * <p><b>一段就是"一遍"</b>：{@code repeat > 1} 时这一段会被重复播 {@code repeat} 遍，
     * 而渲染层<strong>只读一遍的样本</strong>、靠游标绕回——于是第 n 遍与第一遍
     * <strong>逐位相同</strong>（与尾奏循环那一档同一套做法，见
     * {@code AutoCueTransition.prepareLoopOutgoing}）。
     *
     * <p>渲染路径按 {@code segments} 的<strong>顺序</strong>把这些素材喂给被包的接法
     * （{@code AutoCueTransition} 里的 {@code segmentedOutgoing} 队列）。于是"一次过渡"
     * 从"在时间轴上找最优切点"变成"<strong>在结构图上找最优路径</strong>"：
     * `A 的尾奏 + A 的间奏循环 + B 的主歌`、`A 的 bridge + B 的副歌前奏 + B 的副歌`
     * 这些形状都能表达了。
     *
     * <p><b>边界纪律</b>：{@code startSecs}/{@code endSecs} 是<strong>源文件里</strong>的时刻
     * （秒），{@code source} 是 {@code "outro"}（上一曲）/ {@code "intro"}（下一曲）。
     * 计划层是唯一知道"哪条路径值得走"的地方（它拿着两侧整曲包络），而它是按秒算的
     * （它不知道采样率）——所以这一段里的几何一律是秒；帧几何由渲染层换算
     * （见 {@code AutoCueTransition.passFramesOf}）。
     *
     * <p><b>旋转</b>（{@link #rotateStartSecs}，2026-10-05 加）：一遍默认是
     * {@code [startSecs, endSecs)} <strong>顺序</strong>播完，而"一段循环"真正需要的起点
     * 不一定是段的起点——搜索挑中的那个落点可以落在段<strong>内部</strong>。旋转就是让一遍
     * 从那个点起播：
     *
     * <pre>
     *   不旋转：一遍 = [start, end)
     *   旋转  ：一遍 = [rotate, end) + [start, rotate)
     * </pre>
     *
     * 一遍的<strong>总长不变</strong>（仍是 {@code endSecs − startSecs}），所以小节几何、
     * {@code repeat} 的语义、渲染层的帧几何（{@code passFramesOf}）一个数都不动——
     * 变的只有"这一遍从源里的哪一刻开始响"。
     */
    public static final class Segment {
        /** 素材来自哪一首：{@code "outro"} = 出场曲（上一曲），{@code "intro"} = 进场曲（下一曲）。 */
        public final String source;
        /** 源文件里的起点（秒）。 */
        public final double startSecs;
        /** 源文件里的终点（秒，不含）。 */
        public final double endSecs;
        /** 这一段重复几遍（{@code >= 1}；1 = 不重复）。 */
        public final int repeat;

        /**
         * <b>旋转点</b>（源文件里的秒；{@code null} = 不旋转，一遍就是
         * {@code [startSecs, endSecs)} 顺序播完）。
         *
         * <p>给了它时，一遍从这一秒播到 {@code endSecs}，再<strong>绕回</strong>
         * {@code startSecs} 播到这一秒——"从段落中段起播、绕回段首、再播回起播点"。
         * 它必须<strong>严格落在</strong> {@code (startSecs, endSecs)} 内部：等于端点等于
         * "没旋转"（一遍还是顺序的），落在外面则是说不通的几何——两种情形构造函数都
         * 如实归一化成 {@code null}（渲染层于是不会去绕），不猜。
         *
         * <p><b>谁会填它</b>：{@code planCollect} 里的三段路径——B 的落床段这一段的旋转点
         * 就是<strong>搜索挑中的那个进场点</strong>（它落在落床段内部时）。理由是"同一把尺子"：
         * 搜索那套打分看到的是"B 从那个点进来"，于是落床段也得从那个点起播，
         * 这条路径与搜索挑的那套点位才是<strong>同一套几何</strong>（否则一边是搜索挑的落点、
         * 一边是被硬钉在段首的落床段，打分里比的就不是同一件事）。
         */
        public final Double rotateStartSecs;

        /** 旋转点离端点这么近就当"没旋转"（1 ms：远小于一帧，也远小于任何节拍尺度）。 */
        static final double ROTATE_EPS_SECS = 1e-3;

        public Segment(String source, double startSecs, double endSecs, int repeat) {
            this(source, startSecs, endSecs, repeat, null);
        }

        /**
         * 完整版：带上<strong>旋转点</strong>（见 {@link #rotateStartSecs}）。
         *
         * <p>传 {@code null}（或端点/越界的值）时与四参那一版<strong>逐位相同</strong>：
         * 一遍还是顺序播完，渲染层一个字节都不多碰。
         */
        public Segment(String source, double startSecs, double endSecs, int repeat,
                       Double rotateStartSecs) {
            this.source = source;
            this.startSecs = startSecs;
            this.endSecs = endSecs;
            this.repeat = Math.max(1, repeat);
            this.rotateStartSecs = rotateStartSecs != null
                    && rotateStartSecs.doubleValue() > startSecs + ROTATE_EPS_SECS
                    && rotateStartSecs.doubleValue() < endSecs - ROTATE_EPS_SECS
                    ? rotateStartSecs : null;
        }

        /** 一遍的长度（秒）。<strong>旋转不改它</strong>（旋转只挪起播点）。 */
        public double spanSecs() {
            return endSecs - startSecs;
        }

        /** 这一遍是不是"旋转"的（从 {@link #rotateStartSecs} 起播、绕回段首）。 */
        public boolean rotated() {
            return rotateStartSecs != null;
        }

        /** 这一段要的是不是进场曲的素材（{@code source = "intro"}）。 */
        public boolean fromIntro() {
            return "intro".equals(source);
        }

        @Override
        public String toString() {
            return (fromIntro() ? "B" : "A") + "[" + fmt(startSecs) + "→" + fmt(endSecs)
                    + ")s"
                    + (rotateStartSecs == null ? "" : ("↻" + fmt(rotateStartSecs.doubleValue()) + "s"))
                    + (repeat > 1 ? ("×" + repeat) : "");
        }
    }

    /** 自动点位算出来的结论。 */
    /**
     * **自动点位模式**（2026-10-09 ✓，用户：「我准备推出自动点位模式选择」✓）。
     *
     * <ul>
     *   <li>{@link #AGGRESSIVE}（默认 ✓）= 全曲扫（与既有行为**逐位相同** ✓）；</li>
     *   <li>{@link #STABLE} = A 只许在**曲尾倒推 40 s** 内、B 只许在**开头 1 min** 内挑点；</li>
     *   <li>{@link #CONSERVATIVE} = A 曲尾倒推 **20 s**、B 开头 **20 s**。</li>
     * </ul>
     *
     * <p>实现方式：**只把既有的"许可区间"收窄** ✓（`planCollect` 里的 `cutFloor` / 进场点上界 ✓），
     * **不动包络、不动坐标系** ✓ ⇒ 下游（窗口 / 斜坡 / 滤波）一行都不用改 ✓。
     * 区间收空（范围内放不下窗口）⇒ 退化成**该范围内的一个点**（= 范围内最优点 ✓，日志写明 ✓）。
     */
    public enum PointMode {
        AGGRESSIVE(0.0, 0.0),
        STABLE(40.0, 60.0),
        CONSERVATIVE(20.0, 20.0);

        private final double aKeepSecs;
        private final double bKeepSecs;

        PointMode(double aKeepSecs, double bKeepSecs) {
            this.aKeepSecs = aKeepSecs;
            this.bKeepSecs = bKeepSecs;
        }

        /** A 侧：只许在"曲尾倒推这么多秒"以内挑点（0 = 不限 ✓）。 */
        public double aKeepSecs() {
            return aKeepSecs;
        }

        /** B 侧：只许在"曲子开头这么多秒"以内挑点（0 = 不限 ✓）。 */
        public double bKeepSecs() {
            return bKeepSecs;
        }
    }

    private static volatile PointMode pointMode = PointMode.AGGRESSIVE;

    public static void setPointMode(PointMode mode) {
        pointMode = mode == null ? PointMode.AGGRESSIVE : mode;
    }

    public static PointMode pointMode() {
        return pointMode;
    }

    public static final class Cue {
        /**
         * 用**细化过的点位**复制一份（2026-10-07，目标③：帧级对拍）。
         *
         * <p>吸附建立在 50 Hz 包络栅格（20 ms）上，而 {@code BeatRefine} 能在 ±40 ms 内
         * 把拍点找到 ±1.3 ms；实测参考对进场侧可挪 <b>29.5 ms</b>（偏了一格多）。
         * 挪点位**会改音频** ⇒ 有意改动 ⇒ 由调用方的开关控制、并按纪律重采指纹。
         */
        public Cue withTimes(double outroCut, double introEntry) {
            return new Cue(outroCut, introEntry, windowSecs, tailDropSecs, cost, note);
        }

        /** **换窗口时长**（2026-10-11 ✓ 拍速差过大时降级用 ✓；窗口没有别的写入口 ✗）。 */
        public Cue withWindow(double newWindowSecs) {
            return new Cue(outroCutSecs, introEntrySecs, newWindowSecs, tailDropSecs, cost, note);
        }

        /**
         * **只换 note 文本**（2026-10-09 ✓，goal 第 58 轮 ✓）：AI 模式下窗口由内层 `AiMix` 定 ✓
         * （见 {@code AutoCueTransition} 里那段"AI 模式不许动窗口"的原文 ✓），外层那次规划只用来
         * 出 note ✗ ⇒ 它可能写一个**根本不跑的窗口**（判据实测 3.2 vs 6.0 ✗）。调用方用真正装载的
         * 窗口改写这一句 ✓ ⇒ note 与装载一致 ✓；**音频不变** ✓。
         */
        public Cue withNote(String newNote) {
            return new Cue(outroCutSecs, introEntrySecs, windowSecs, tailDropSecs, cost, newNote);
        }

        /**
         * 同 {@link #withTimes}，但**连丢尾一起**换（2026-10-07：结构对齐要同时改出场点与丢尾 ✓）。
         *
         * <p>为什么必须一起改：对外报的"出场点"是 `全曲 − 窗口 − 丢尾` 算出来的 ✓，
         * 只改 cut 不改丢尾 ⇒ 报出来的落点还停在老地方 ✗（实测就是这么被绕了一圈 ✗）。
         */
        public Cue withLanding(double outroCut, double introEntry, double outroTailDrop) {
            return new Cue(outroCut, introEntry, windowSecs, outroTailDrop, cost, note);
        }

        /**
         * 换一份循环计划（并把说明接在结论后面 ✓）—— 给执行层用：**档位要循环时它自己求一份** ✓
         * （决策层不知道档位 ✓，见 {@link OutroLoopPlan#styleWanted} ✓）。
         */
        public Cue withOutroLoop(OutroLoopPlan loop, String addNote) {
            return new Cue(outroCutSecs, introEntrySecs, windowSecs, tailDropSecs, cost,
                    note + (addNote == null ? "" : addNote), intensityGap, intensityOk, loop,
                    segments);
        }


        /** 出场曲从哪一秒开始交叠（这一秒之后的两首曲子同时响）。 */
        public final double outroCutSecs;

        /** 进场曲从哪一秒进入（对应 {@code TransitionPlan.skipSecs}）。 */
        public final double introEntrySecs;

        /** 交叠时长（秒）。 */
        public final double windowSecs;

        /** 出场曲被丢掉的尾巴（秒）：从「出场点 + 交叠时长」到全曲结束。 */
        public final double tailDropSecs;

        /** 代价（越小越好；只用于日志与比较，没有绝对含义）。 */
        public final double cost;

        /** 给人看的一句实话。 */
        public final String note;

        /**
         * 接缝两侧的强度差（0..1；桥接关着时恒为 {@link #INTENSITY_UNKNOWN_BAND}）。
         *
         * <p>调用方（{@code AutoCueTransition}）拿它判断「要不要再扫深一点重挑」——
         * 见 {@link #Bridge} 与 {@code AutoCueTransition} 里那段两级扫描。
         */
        public final double intensityGap;

        /** 强度闸门满足了吗（桥接关着时恒为 {@code true}）。 */
        public final boolean intensityOk;

        /**
         * <b>尾奏循环计划</b>（{@code null} = 这一对<strong>不需要</strong>循环，
         * 渲染路径一个字节都不碰）。
         *
         * <p>决定「要不要循环」的是 {@link #planOutroLoop}（用户 2026-10-05 的规格：
         * 重复段落是用来<strong>买时间</strong>和<strong>对齐网格</strong>的，不是用来炫技的）；
         * 用它的是 {@code AutoCueTransition}：它把窗口里的 A 素材换成「循环段整段重复
         * {@code repeats} 遍」，淡入点落在循环段内部的小节线上（离线原型验收过的变体 b：
         * B 在第二遍的中段叠进来、淡化结束正好是那一遍的末尾，A 原本的收尾丢弃）。
         *
         * <p><b>为什么由这一层给</b>：选循环段（{@link #outroVampSpan}）与「到底要不要循环」
         * 都要看见<strong>两侧的整曲包络</strong>，而这里是唯一同时拿着两侧包络的地方；
         * 渲染路径只该拿结论（它跑在生产者线程上，一帧都不该做分析）。
         *
         * <p>{@code null} 时点位与渲染<strong>逐位不变</strong>——那正是这条接线的前提，
         * 判据 {@code AutoCueOutroLoopTest} 的第一条钉的也是它。{@link #topCues} 造出来的
         * 候选一律 {@code null}：它们只用于「过渡开始前再选一次」，没有循环信息。
         */
        public final AutoCue.OutroLoopPlan outroLoop;

        /**
         * <b>这一次过渡要走的路径</b>（{@code null} = 没有显式路径，渲染路径按"出场点 + 窗口"
         * 那条老路走，与加这个字段之前<strong>逐位相同</strong>）。
         *
         * <p>每一段是 {@link Segment}（来源、源里的起止、重复几遍），渲染层按<strong>顺序</strong>
         * 把它们喂给被包的接法。{@code null} 与空列表都表示"没有路径"；两者的区别只是
         * 说法，渲染层一视同仁（都走老路）。
         *
         * <p><b>为什么是可空而不是"永远给一条两段的路径"</b>：老路是"窗口里连续播 A"，
         * 把它硬写成路径要先把窗口切段、再逐段读素材，多出来的每一步都是"逐位相同"
         * 这条性质的新风险。可空字段让"没有路径"成为一个<strong>明确的取值</strong>
         * （判据 {@code AutoCueSegmentedPathTest} 的第一条就钉它逐位不变），
         * 而"有路径"时那几条路各自负责证明自己与老路等价。
         *
         * <p><b>现在谁会填它</b>：{@link #planCollect} 在尾奏循环真的接线时（
         * {@link #loopIsWired}）填一条<strong>与决策等价的路径</strong>
         * （A 的尾奏段 × repeats，见 {@link #outroLoopPath}）——把今天隐式的
         * "两段 + 循环"显式化；B 侧找得到落床段时改填**三段路径**
         * （A 的尾奏段 + A 的循环段 ×repeats + B 的落床段 ×repeat，见 {@link #introBedPath}），
         * 那一段的 B 侧素材可以从{@link Segment#rotateStartSecs 搜索挑中的落点}起播。
         */
        public final java.util.List<Segment> segments;

        Cue(double outroCutSecs, double introEntrySecs, double windowSecs, double tailDropSecs,
            double cost, String note) {
            this(outroCutSecs, introEntrySecs, windowSecs, tailDropSecs, cost, note,
                    INTENSITY_UNKNOWN_BAND, true, null, null);
        }

        Cue(double outroCutSecs, double introEntrySecs, double windowSecs, double tailDropSecs,
            double cost, String note, double intensityGap, boolean intensityOk) {
            this(outroCutSecs, introEntrySecs, windowSecs, tailDropSecs, cost, note,
                    intensityGap, intensityOk, null, null);
        }

        /** 完整版：再带上尾奏循环计划（见 {@link #outroLoop}）。 */
        Cue(double outroCutSecs, double introEntrySecs, double windowSecs, double tailDropSecs,
            double cost, String note, double intensityGap, boolean intensityOk,
            AutoCue.OutroLoopPlan outroLoop) {
            this(outroCutSecs, introEntrySecs, windowSecs, tailDropSecs, cost, note,
                    intensityGap, intensityOk, outroLoop, null);
        }

        /** 再完整一点：带上这一次过渡要走的<strong>路径</strong>（见 {@link #segments}）。 */
        Cue(double outroCutSecs, double introEntrySecs, double windowSecs, double tailDropSecs,
            double cost, String note, double intensityGap, boolean intensityOk,
            AutoCue.OutroLoopPlan outroLoop, java.util.List<Segment> segments) {
            this.outroCutSecs = outroCutSecs;
            this.introEntrySecs = introEntrySecs;
            this.windowSecs = windowSecs;
            this.tailDropSecs = tailDropSecs;
            this.cost = cost;
            this.note = note;
            this.intensityGap = intensityGap;
            this.intensityOk = intensityOk;
            this.outroLoop = outroLoop;
            this.segments = segments;
        }

        @Override
        public String toString() {
            return "Cue{出场 " + fmt(outroCutSecs) + "s，进场 " + fmt(introEntrySecs)
                    + "s，交叠 " + fmt(windowSecs) + "s，丢尾 " + fmt(tailDropSecs)
                    + "s，代价 " + String.format(java.util.Locale.US, "%.3f", cost)
                    + (segments == null ? "" : ("，路径 " + describePath(segments))) + "}";
        }
    }

    /** 把一条路径写成给人看的一行（日志与诊断用）。 */
    static String describePath(java.util.List<Segment> segments) {
        if (segments == null) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(s);
        }
        return sb.length() == 0 ? "空" : sb.toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /**
     * 把整首曲子扫成一条能量包络（流式解码，整 PCM 不进内存）。
     *
     * <p>音源句柄与 {@link Decoder#open(String)} 收的是同一种东西；这里的局限与
     * {@link AiMix#prepare} 完全一样：这一层拿不到插件直链需要的请求头
     * （{@code Transition.prepare} 的签名里就没有它），所以需要请求头的音源会扫描失败，
     * 那时如实退回固定点位。
     *
     * @throws AudioException 打不开或解不出来（调用方应当退回固定点位，而不是让过渡失败）
     */
    public static Envelope scan(String source) {
        return scan(source, null);
    }

    /**
     * 同 {@link #scan(String)}，但带上音源需要的<strong>请求头</strong>。
     *
     * <p>插件直链常常必须带 token/Referer；不给它就只能扫本地/已缓存的音源
     * （那正是 2026-09-24 用户报告的限制：「只对本地/已缓存音源有效」）。
     */
    /**
     * 同 {@link #scan(String)}，但带上这条音源需要的请求头。
     *
     * <p>插件直链常常必须带 token/Referer；不给它就只能扫本地/已缓存的音源
     * （那正是 2026-09-24 用户报告的限制：「只对本地/已缓存音源有效」）。
     */
    public static Envelope scan(String source, java.util.Map<String, String> headers) {
        // **走缓存**：这条是 AI 方案比较与自动点位真正调用的入口，
        // 两个消费者要的是同一对包络（见 cachedScan 的注释）
        return cachedScan(source, headers, 0.0, false, false);
    }

    /**
     * 进场侧扫描：按 {@link #introScanSecs} 自动定量（时长取解码器自己报的那个，
     * 所以不必为了知道时长再开一次文件）。
     *
     * <p>为什么进场侧可以只扫一段、出场侧不行，见 {@link #scan(String, java.util.Map, double)}。
     */
    public static Envelope scanIntro(String source, java.util.Map<String, String> headers) {
        return scanIntro(source, headers, Bridge.OFF);
    }

    /**
     * 桥接感知的进场侧扫描：扫到 {@link #introScanSecs(Double, Bridge)} 算出来的长度。
     *
     * <p>与一参版本的区别只在<b>扫多深</b>；包络本身怎么算一个字没变。
     * {@link Bridge#OFF} 时与它逐位相同。
     */
    public static Envelope scanIntro(String source, java.util.Map<String, String> headers,
                                     Bridge bridge) {
        // 仍然走 introBudget 那条路：时长用**解码器自己报的**那个，不为知道时长再开一次文件
        // （与 {@link #scanIntro(String, java.util.Map)} 同一条约定，只是预算按策略算）。
        return cachedScan(source, headers, 0.0, true, bridge != null && bridge.widen);
    }

    /** 旧入口（保留给只关心「整曲扫描」语义的调用方与测试）。 */
    static Envelope scanIntroUncached(String source, java.util.Map<String, String> headers) {
        return scanInternal(source, headers, 0.0, true, false);
    }

    /**
     * 同 {@link #scan(String, java.util.Map)}，但最多只解 {@code maxSecs} 秒。
     *
     * <h3>为什么需要「只扫一段」（2026-09-26 手机端实测）</h3>
     *
     * <p>整曲扫描是这条链上最贵的一步：它要把整首歌<strong>再解一遍</strong>。进场曲的句柄
     * 在很多情况下还是那条<strong>带签名的直链</strong>（预取的音频还没落盘），于是这一次扫描
     * 等于<strong>把整首歌再下一次</strong>——手机上一首歌几十秒，而 {@code Director} 在触发点
     * 没有准备结果时<strong>刻意不开始过渡</strong>（见 {@code beginTransition}），
     * 用户听到的就是「开了自动点位反而完全没有过渡」。
     *
     * <p>而进场侧根本不需要整曲：{@link #plan} 对进场点的硬约束是
     * <code>≤ min(20 秒, 全曲的 25%)</code>（{@link #MAX_INTRO_SECS} /
     * {@link #MAX_INTRO_FRACTION}），候选点全在这个范围内。所以扫到 {@link #introScanSecs}
     * 算出来的长度就够——一首 5 分钟的歌从 300 s 降到 80 s。
     *
     * <p>两处差别如实写在这里：
     *
     * <ul>
     *   <li><strong>时长用解码器报的真实时长</strong>（{@code spec.durationSecs()}），不是
     *       扫出来的长度——否则三条硬约束会按「扫到的那一段」算，进场点上限直接歪掉；</li>
     *   <li><strong>归一化基准</strong>（95 分位）来自扫到的那一段。同一首歌全扫与扫前 30%
     *       可能差几个百分点，于是「能量接续」这一项的分数略有不同——但候选范围、三条硬约束、
     *       拍点栅格都不受影响。</li>
     * </ul>
     *
     * @param maxSecs 最多解多少秒；{@code <= 0} 时解整曲
     */
    public static Envelope scan(String source, java.util.Map<String, String> headers, double maxSecs) {
        return cachedScan(source, headers, maxSecs, false, false);
    }

    // ------------------------------------------------------------------
    // 扫描结果缓存（2026-09-28：AI 自动档 + 自动点位会**同时**要这两条包络）
    // ------------------------------------------------------------------

    /**
     * 同一首曲子的包络缓存。
     *
     * <h3>为什么需要它</h3>
     *
     * <p>「AI 自动档把声波重合当候选方案」与「自动点位挑点」用的是<strong>同一对包络</strong>，
     * 而它们是两个对象（{@code AiMix.prepare} 与 {@code AutoCueTransition.prepare}）、
     * 各扫各的。真机日志（2026-09-28）里一次过渡的准备是这么花的：
     * 模型对拍两次 + **整曲扫描四次**（两首 × 两遍）+ 去人声分离 25.5 s，
     * 结果是<strong>没赶上触发点</strong>——「到了触发点却没有可用的准备结果」。
     * 扫描是这一串里最不值当重复的一步：它是纯函数（同一条音源、同一段预算 → 同一份包络）。
     *
     * <h3>什么时候会失效</h3>
     *
     * <p>键里带上了本地文件的 <strong>长度 + 修改时间</strong>，并且条目有
     * {@link #SCAN_CACHE_TTL_MS} 的存活期、最多留 {@value #SCAN_CACHE_MAX} 条：
     * 文件被换了内容、或者隔得太久（同一路径可能是另一首歌），都不会拿到旧结论。
     * 音频缓存的路径是内容寻址的（{@code v2-<sha>.cache}），本来就一一对应。
     */
    private static final java.util.Map<String, CachedEnvelope> SCAN_CACHE =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, CachedEnvelope>(8, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, CachedEnvelope> eldest) {
                            return size() > SCAN_CACHE_MAX;
                        }
                    });

    // 16（2026-10-09 ✓ 用户：「太慢了 全卡在解码」✓）：
    // 原先只有 **4** 条 ✗ —— 两首歌 ×（深扫/浅扫）= 4 条就满了 ✓，
    // 而编排器一次准备会评估多个窗口方案（还带 `widen` 变体 ✓）⇒
    // **深扫那条被 LRU 顶掉** ✗ ⇒ 下一次又整曲解码 ✓（日志里同一首扫 6 次、每次 2.3 s ✓）。
    static final int SCAN_CACHE_MAX = 16;
    // 12 小时（2026-10-09 ✓ 用户：「太慢了 全卡在解码」✓）：原来只有 10 分钟 ✗
    // ⇒ 同一首隔一会儿再放就得**重新解码整首**（日志里同一帧数出现 4~5 次 ✓）。
    static final long SCAN_CACHE_TTL_MS = 12L * 60L * 60L * 1000L;

    private static final class CachedEnvelope {
        final Envelope envelope;
        final long bornAtMs;

        CachedEnvelope(Envelope envelope) {
            this.envelope = envelope;
            this.bornAtMs = System.currentTimeMillis();
        }
    }

    /** 磁盘缓存版本（格式一变就 +1 ✓ 旧文件自动作废 ✓）。 */
    private static final int ANALYSIS_DISK_VERSION = 1;

    private static java.io.File analysisDiskDir() {
        try {
            java.io.File dir = dev.t1m3.qplayer.store.AppDirs.cacheDir().resolve("analysis").toFile();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            return dir.isDirectory() ? dir : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String analysisDiskName(String key) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", Byte.valueOf(b)));
            }
            return sb.append(".bin").toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 只存自动化真正读的那几样（rms/onset/时长/两个结构点 ✓）；任何异常都静默 ✓。 */
    private static void saveEnvelopeToDisk(String key, Envelope env) {
        java.io.File dir = analysisDiskDir();
        String name = dir == null ? null : analysisDiskName(key);
        if (name == null) {
            return;
        }
        java.io.File out = new java.io.File(dir, name);
        java.io.File tmp = new java.io.File(dir, name + ".tmp");
        try (java.io.DataOutputStream w = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
            w.writeInt(0x5150414E);
            w.writeInt(ANALYSIS_DISK_VERSION);
            w.writeInt(env.rms.length);
            for (float v : env.rms) {
                w.writeFloat(v);
            }
            w.writeInt(env.onset.length);
            for (float v : env.onset) {
                w.writeFloat(v);
            }
            w.writeDouble(env.durationSecs);
            w.writeFloat(env.structureOutroStartSecs);
            w.writeFloat(env.structureIntroStartSecs);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(out);
    }

    private static Envelope loadEnvelopeFromDisk(String key) {
        java.io.File dir = analysisDiskDir();
        String name = dir == null ? null : analysisDiskName(key);
        if (name == null) {
            return null;
        }
        java.io.File in = new java.io.File(dir, name);
        if (!in.isFile() || in.length() <= 0L) {
            return null;
        }
        try (java.io.DataInputStream r = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(in)))) {
            if (r.readInt() != 0x5150414E || r.readInt() != ANALYSIS_DISK_VERSION) {
                return null;
            }
            int n = r.readInt();
            if (n <= 0 || n > 60_000_000) {
                return null;
            }
            float[] rms = new float[n];
            for (int i = 0; i < n; i++) {
                rms[i] = r.readFloat();
            }
            int m = r.readInt();
            if (m <= 0 || m > 60_000_000) {
                return null;
            }
            float[] onset = new float[m];
            for (int i = 0; i < m; i++) {
                onset[i] = r.readFloat();
            }
            double dur = r.readDouble();
            float outro = r.readFloat();
            float intro = r.readFloat();
            // 结构点是 final ✓ 走 10 参构造（末两个 ✓）
            return new Envelope(rms, onset, dur, null, 0, null, null, 0, outro, intro);
        } catch (Exception e) {
            return null;
        }
    }

    private static Envelope cachedScan(String source, java.util.Map<String, String> headers,
                                       double maxSecs, boolean introBudget, boolean bridge) {
        String key = cacheKey(source, headers, maxSecs, introBudget, bridge);
        if (key == null) {
            // 不是本地文件（直链、content://、测试里的假音源）：键里没法带内容指纹，
            // 缓存就可能把上一份素材的包络喂给下一份 → **不缓存**更安全。
            // 真机上这条路的成本由「进场侧只扫前 25 秒」兜着（见 introScanSecs）。
            long t0 = System.currentTimeMillis();
            Envelope raw = scanInternal(source, headers, maxSecs, introBudget, bridge);
            dev.t1m3.qplayer.util.Logger.info("[扫描耗时] {} ms · 预算 {} s · 浅扫={} · 音源 {}",
                    Long.valueOf(System.currentTimeMillis() - t0), Double.valueOf(maxSecs),
                    Boolean.valueOf(introBudget), source);
            return raw;
        }
        CachedEnvelope hit;
        synchronized (SCAN_CACHE) {
            hit = SCAN_CACHE.get(key);
        }
        if (hit != null && System.currentTimeMillis() - hit.bornAtMs <= SCAN_CACHE_TTL_MS) {
            dev.t1m3.qplayer.util.Logger.info("[扫描缓存] 内存命中 ✓（预算 {} s · 浅扫={}）",
                    Double.valueOf(maxSecs), Boolean.valueOf(introBudget));
            return hit.envelope;
        }
        Envelope onDisk = loadEnvelopeFromDisk(key);
        if (onDisk != null) {
            dev.t1m3.qplayer.util.Logger.info("[扫描缓存] 磁盘命中 ✓（预算 {} s · 浅扫={}）",
                    Double.valueOf(maxSecs), Boolean.valueOf(introBudget));
            synchronized (SCAN_CACHE) {
                SCAN_CACHE.put(key, new CachedEnvelope(onDisk));
            }
            return onDisk;
        }
        dev.t1m3.qplayer.util.Logger.info("[扫描缓存] 未命中 ⇒ 解码（预算 {} s · 浅扫={} · 键长度 {}）",
                Double.valueOf(maxSecs), Boolean.valueOf(introBudget),
                Integer.valueOf(key.length()));
        long t0 = System.currentTimeMillis();
        Envelope fresh = scanInternal(source, headers, maxSecs, introBudget, bridge);
        dev.t1m3.qplayer.util.Logger.info("[扫描耗时] {} ms · 预算 {} s · 浅扫={} · 音源 {}",
                Long.valueOf(System.currentTimeMillis() - t0), Double.valueOf(maxSecs),
                Boolean.valueOf(introBudget), source);
        synchronized (SCAN_CACHE) {
            SCAN_CACHE.put(key, new CachedEnvelope(fresh));
        }
        saveEnvelopeToDisk(key, fresh);
        return fresh;
    }

    /** 缓存键：音源 + 请求头 + 预算 + （本地文件的）长度与修改时间。 */
    private static String cacheKey(String source, java.util.Map<String, String> headers,
                                   double maxSecs, boolean introBudget, boolean bridge) {
        StringBuilder sb = new StringBuilder();
        // **本地文件优先按"内容身份"建键**（2026-10-09 ✓）：同一份素材可能以
        // 缓存路径 / 原始直链两种字符串出现 ✗ 把字符串混进键里就必然重复解码 ✓。
        // 非本地音源（直链、content://）仍走下面的"带路径"键 ✓ 保持原语义 ✓。
        boolean localFile = false;
        String localIdentity = null;
        try {
            java.io.File probe = new java.io.File(source);
            if (probe.isFile()) {
                localFile = true;
                localIdentity = probe.length() + "@" + probe.lastModified();
            }
        } catch (RuntimeException ignored) {
            // 保持 localFile = false ✓
        }
        if (localFile) {
            sb.append("file:").append(localIdentity);
        } else {
            // **远端直链：去掉查询串再当键**（2026-10-09 ✓）：
            // 直链每次都带新的时效 token（`?vuutv=…` ✗），把整串放进键里 ⇒ 每次都算新音源 ✗
            // ⇒ 同一首在一次准备里被扫 6 次、每次 2.3~2.7 s ✓（实测日志 ✓）。
            // 去掉 `?...` 之后剩下的是"同一份素材的稳定路径" ✓（音频不变 ✓ 只是签名变 ✓）。
            int q = source.indexOf('?');
            sb.append(q > 0 ? source.substring(0, q) : source);
        }
        sb.append('\u0000').append(introBudget ? 'I' : 'F')
                .append(bridge ? 'B' : 'b')      // 浅扫与深扫是两份不同的包络，键必须分开
                .append('\u0000').append(maxSecs);
        if (headers != null && localFile) {
            java.util.TreeMap<String, String> sorted =
                    new java.util.TreeMap<String, String>(headers);
            for (java.util.Map.Entry<String, String> e : sorted.entrySet()) {
                sb.append('\u0000').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        try {
            java.io.File file = new java.io.File(source);
            if (!file.isFile()) {
                return null;      // 非本地文件：不做缓存（见 cachedScan）
            }
            sb.append('\u0000').append(file.length()).append('@').append(file.lastModified());
        } catch (RuntimeException e) {
            return null;
        }
        return sb.toString();
    }

    /** 清掉扫描缓存（测试用；正常路径不需要）。 */
    static void clearScanCache() {
        synchronized (SCAN_CACHE) {
            SCAN_CACHE.clear();
        }
    }

    private static Envelope scanInternal(String source, java.util.Map<String, String> headers,
                                         double maxSecs, boolean introBudget, boolean bridge) {
        Decoder decoder = headers == null || headers.isEmpty()
                ? Decoder.open(source)
                : Decoder.open(source, headers);
        try {
            TrackSpec spec = decoder.spec();
            int rate = Math.max(spec.sampleRate, 1);
            int channels = Math.max(spec.layout.count(), 1);
            // 一次降采样，两条分析共用：RMS 包络（找接缝）与谱通量（找拍点）
            int decimate = Math.max(1, (int) Math.round((double) rate / TARGET_ONSET_RATE));
            int workRate = Math.max(1, rate / decimate);
            int hopFrames = Math.max(1, (int) Math.round(workRate * HOP_SECS));
            Double trueDuration = spec.durationSecs();
            if (introBudget) {
                // 0（时长未知）表示整曲：那时 plan 本来就会如实放弃
                maxSecs = introScanSecs(trueDuration,
                        bridge ? Bridge.DEFAULT : Bridge.OFF);
            }

            // 扫描上界（源采样率下的帧数）
            long stopFrames = maxSecs > 0.0 ? (long) (maxSecs * rate) : Long.MAX_VALUE;

            PrepareStage.mark(introBudget ? "扫进场曲头段（整曲或前一段）" : "扫出场曲整曲（含解码）");
            OnsetFlux flux = new OnsetFlux();
            // 四个收集容器用**可增长的 float[]** 而不是 ArrayList<Float>（2026-10-03，性能）：
            // 一首 5 分钟的歌上「每 hop 一个 Float」是几万个装箱对象 + 几千个 float[3]，
            // 手机上白送 GC 压力。值还是同一个表达式算出来的。
            FloatList rms = new FloatList(4096);
            FloatList wave = new FloatList(4096);
            FloatList centerList = null;
            AudioBuf block = new AudioBuf(ChannelLayout.of(channels), BLOCK_FRAMES);
            double sumSquares = 0.0;
            int inHop = 0;
            double partial = 0.0;
            int partialCount = 0;
            long decoded = 0L;

            // ---- 额外的两路分析（不改变上面那条主路径）----
            // ① 三条分频包络：低频 / 中频 / 高频，用来判「两首歌叠上去会不会在同一个频段打架」
            dev.t1m3.qplayer.audio.dsp.Biquad bandLow =
                    dev.t1m3.qplayer.audio.dsp.Biquad.lowPass(workRate, (float) BAND_LOW_HZ, 0.7071f);
            dev.t1m3.qplayer.audio.dsp.Biquad bandHigh =
                    dev.t1m3.qplayer.audio.dsp.Biquad.lowPass(workRate, (float) BAND_HIGH_HZ, 0.7071f);
            double[] bandSum = new double[3];
            // 名字带 List 是为了不与上面的两条 biquad（bandLow / bandHigh）撞名
            FloatList bandLowList = new FloatList(4096);
            FloatList bandMidList = new FloatList(4096);
            FloatList bandHighList = new FloatList(4096);
            // ③ 中置显著度（P6）：只对立体声素材算。左右两路各留一个降采样累加器，
            //    每 hop 用「中置功率 ÷ 总功率」得到一个 0..1 的数，交给 VAD 挡掉
            //    「按音节起伏但被摆到两边」的乐器（见 CENTER_SALIENCE_MIN）。
            boolean stereo = channels >= 2;
            if (stereo) {
                centerList = new FloatList(4096);
            }
            final FloatList center = centerList;
            double partialL = 0.0;
            double partialR = 0.0;
            double midPower = 0.0;
            double sidePower = 0.0;
            // ② 低采样率波形：每 WAVE_RATE 个采样取一个（先做一次盒式平均当抗混叠），
            //    用来算波形重合度（相位/拍点对不对得上）。见 Envelope.wave 的注释。
            int waveDecimate = Math.max(1, (int) Math.round((double) workRate / WAVE_RATE));
            int waveRate = Math.max(1, workRate / waveDecimate);
            double wavePartial = 0.0;
            int waveCount = 0;
            // ③ 白化对数谱通量的流式前端：拍栅格走它（见下面 tempo 那一段）。
            SpectralOnsets.Stream spectral = new SpectralOnsets.Stream(workRate);

            while (true) {
                int got = decoder.decodeInto(block);
                if (got <= 0) {
                    break;
                }
                // **裸数组 + 声道起点**（2026-10-03，性能）：原来在逐**采样**的内层循环里
                // 写的是 `block.channel(c).get(frame)`，而 `AudioBuf.channel(int)` 每次都
                // 现包一个 `FloatBuffer`（wrap + slice）——一首 5 分钟的歌上就是几千万次
                // 视图分配，全是垃圾，而且还多一层边界检查。这里一个块取一次数组与起点，
                // 读出来的 float 逐位相同。
                float[] backing = block.backingArray();
                int[] chanBase = new int[channels];
                for (int c = 0; c < channels; c++) {
                    chanBase[c] = block.channelBase(c);
                }
                for (int frame = 0; frame < got; frame++) {
                    // 多声道折叠成单声道（平均，与 BandLevels 同一约定）
                    float mono = 0f;
                    float left = 0f;
                    float right = 0f;
                    for (int c = 0; c < channels; c++) {
                        float v = backing[chanBase[c] + frame];
                        mono += v;
                        if (c == 0) {
                            left = v;
                        } else if (c == 1) {
                            right = v;
                        }
                    }
                    mono /= channels;
                    if (channels >= 2) {
                        partialL += left;
                        partialR += right;
                    }
                    partial += mono;
                    partialCount++;
                    if (partialCount < decimate) {
                        continue;
                    }
                    float sample = (float) (partial / partialCount);
                    if (stereo) {
                        float l = (float) (partialL / partialCount);
                        float r = (float) (partialR / partialCount);
                        double mid = (l + r) * 0.5;
                        double side = (l - r) * 0.5;
                        midPower += mid * mid;
                        sidePower += side * side;
                    }
                    partial = 0.0;
                    partialCount = 0;
                    partialL = 0.0;
                    partialR = 0.0;
                    sumSquares += (double) sample * sample;
                    spectral.push(sample);
                    // 分频：低频取低通、高频取「原信号减去 2 kHz 低通」、中频取两者之差
                    double lowPart = bandLow.process(sample);
                    double highUpTo = bandHigh.process(sample);
                    double midPart = highUpTo - lowPart;
                    double highPart = sample - highUpTo;
                    bandSum[0] += lowPart * lowPart;
                    bandSum[1] += midPart * midPart;
                    bandSum[2] += highPart * highPart;
                    if (++inHop >= hopFrames) {
                        rms.add((float) Math.sqrt(sumSquares / inHop));
                        bandLowList.add((float) Math.sqrt(bandSum[0] / inHop));
                        bandMidList.add((float) Math.sqrt(bandSum[1] / inHop));
                        bandHighList.add((float) Math.sqrt(bandSum[2] / inHop));
                        if (stereo && center != null) {
                            center.add(centerSalience(midPower, sidePower));
                            midPower = 0.0;
                            sidePower = 0.0;
                        }
                        sumSquares = 0.0;
                        bandSum[0] = 0.0;
                        bandSum[1] = 0.0;
                        bandSum[2] = 0.0;
                        inHop = 0;
                    }
                    wavePartial += sample;
                    if (++waveCount >= waveDecimate) {
                        wave.add((float) (wavePartial / waveCount));
                        wavePartial = 0.0;
                        waveCount = 0;
                    }
                    flux.push(sample);
                }
                decoded += got;
                if (decoded >= stopFrames) {
                    // 最多多算不到一块（相对整曲是常数级），到此为止
                    break;
                }
            }
            if (inHop > 0) {
                rms.add((float) Math.sqrt(sumSquares / inHop));
                bandLowList.add((float) Math.sqrt(bandSum[0] / inHop));
                bandMidList.add((float) Math.sqrt(bandSum[1] / inHop));
                bandHighList.add((float) Math.sqrt(bandSum[2] / inHop));
            }
            if (rms.size() == 0) {
                throw AudioException.decode("扫不出能量包络（音源是空的？）：" + source);
            }
            float[] values = rms.toArray();
            // 时长优先用解码器报的真实值（见方法注释）；拿不到就退回扫出来的长度
            double durationSecs = trueDuration != null
                    ? trueDuration.doubleValue() : values.length * HOP_SECS;
            float[] waveValues = wave.toArray();
            // **测速走 Melodify 那套**（用户 2026-09-30 要求硬算照搬 Melodify）：
            // 扫描期本来就在按 {@value #HOP} 个采样算 RMS，正好是它要的能量包络，
            // 于是「挑点用的拍栅格」与「算窗口用的拍点」现在是**同一套估计算法**——
            // 以前一个走频谱通量 + 120 BPM 先验、另一个也走同一套，但那是两处各算一遍；
            // 现在两处都从 RMS 出发、都调 Analysis.tempoFromRmsFlux。
            // **hop 必须是这一路真正用的那个**（{@code hopFrames} = 工作采样率下的
            // 0.02 秒），不是 {@link Analysis#HOP} 那个 512：扫描先按
            // {@code TARGET_ONSET_RATE} 降采样再按 0.02 秒切格，于是 48 kHz 素材上
            // hopFrames = 480 而不是 512——拿 512 去换算相位与周期，拍栅格会整体偏
            // 6.7%（实测拍点偏离真实栅格 0.103 s，就是这一处）。
            // 拍栅格：**新链路优先**（白化对数谱通量 + DP 对拍 + 全局回归：整曲周期误差
            // 0.0006~0.0008%，旧路 0.048~0.051%），拿不到结论再退回旧的 RMS 差分路。
            //
            // 2026-10-01 打开了：先前两次接上又把点带走，根因是**候选集换成了拍点**
            // （见 candidates 的注释）。现在候选集固定用 20 ms 网格、选完再对拍
            // （见 plan 末尾），栅格就只是「锦上添花」的那一层了。
            float[] spectralOnset = spectral.values();
            // ── 拍栅格的**优先级链**（2026-10-06 第一阶段工作二）────────────────────
            //
            // 用户原话：「对拍精度优先级：模型预计算结果优先，缓存读取次之，现有 BeatTracker
            // 再次，自相关加相位精修兜底。」以及「**手机端也得具备硬算程序（DSP 不是模型）**，
            // 有 sidecar 读 sidecar，没有就自己算。」
            //
            // 所以顺序是：① 同目录的 sidecar（电脑端跑模型留下的**缓存**，纯数据、
            // 哈希校验通过才算数）；② 没有 / 坏了 / 哈希对不上 ⇒ **原样走既有的硬算链路**
            // （BeatTracker 自相关 + DP + 稳健回归，后面还有采样级相位精修）。
            // ②这条路与加这一条之前**逐位相同**——缺缓存绝不影响任何行为。
            Analysis.Tempo tempo = tempoFromSidecar(source, workRate);
            if (tempo != null) {
                Logger.debug("自动点位：拍栅格走 sidecar 缓存（{} BPM，置信度 {}）",
                        Float.valueOf(tempo.bpm), Float.valueOf(tempo.confidence));
            } else {
                tempo = spectralOnset.length > 0
                        ? BeatTracker.track(spectralOnset, spectral.hop(), workRate)
                        : null;
            }
            if (tempo == null) {
                Logger.debug("自动点位：新链路没给出拍栅格（{}），退回旧的 RMS 差分路",
                        BeatTracker.lastRejectReason());
                tempo = Analysis.tempoFromRmsFlux(values, hopFrames, workRate);
            } else {
                Logger.debug("自动点位：拍栅格走新链路（{} BPM，残差尺度的置信度 {}）",
                        Float.valueOf(tempo.bpm), Float.valueOf(tempo.confidence));
            }
            // ── 分段（色度新颖度）：尾声起点 / 进场起点 ────────────────────────
            //
            // 能量类特征看不见「段落结束」（实测 skydive 电平平到 185.5 s，而 Apple 选 174.0），
            // 所以这两个结构点改由色度新颖度给：最后一个已知强边界 = 尾声起点，
            // 第一个 = 进场曲真正开始。色度是顺手从同一份 mag 折的（不多做一次 FFT）。
            float[] chroma = spectral.chromaValues();
            double chromaHopSecs = spectral.hop() / (double) Math.max(1, workRate);
            int chromaFrames = spectral.chromaFrameCount();
            float[] novelty = noveltyCurve(chroma, spectral.chromaFrameCount(), chromaHopSecs);
            float[] level = spectral.levelValues();
            float structureOutro = (float) noveltyBoundary(novelty, level, chromaHopSecs, true,
                    durationSecs * 0.5, durationSecs);
            float structureIntro = (float) noveltyBoundary(novelty, level, chromaHopSecs, false,
                    Math.min(5.0, durationSecs * 0.1), Math.min(60.0, durationSecs * 0.5));
            Logger.debug("自动点位：分段（色度新颖度，作为低权加分项 "
                            + NOVELTY_WEIGHT + " 参与选点）→ 尾声起点 {} · 进场起点 {}",
                    structureOutro >= 0f ? fmt(structureOutro) + " s" : "无",
                    structureIntro >= 0f ? fmt(structureIntro) + " s" : "无");
            // 分段结果**曾经**完全不接线（2026-10-01）：实测它比能量法差——
            //   skydive 尾声起点：能量法 179.7（Apple 174.0）· 分段 183.9
            //   春めく 进场起点：能量法 16.3（Apple 15.0）· 分段 5.2
            // 最终点位因此从 175.3 / 15.5 退化到 180.0 / 6.0，参考判据直接红。
            // 原因是「局部谱变化最大」找的是**变化最剧烈**的地方（结尾淡出、前奏第一下），
            // 不是**乐句**边界。链接线要先把分段做对（自相似矩阵 + 多尺度、且要求两侧都是
            // 持续段落），在那之前只打印不参与——见 docs/automix-modes.md §8.0.6-补。
            // **2026-10-02 改**（用户要求）：「不接线」改成「**最低权加分项**」。
            //
            // 为什么当初不接线：把它当**主结构项**用会输给能量法——实测 skydive 尾声起点
            // 183.9（能量法 179.7、Apple 174.0）、春めく 进场起点 5.2（能量法 16.3、Apple 15.0），
            // 最终点位从 175.3/15.5 退化到 180.0/6.0，参考判据当场红。
            //
            // 但那不等于这个信号没用：它讲的是**和声/音色**的变化，与能量法的「电平跨过参考线」
            // 是两件事，只是**精度差一档**。所以现在的用法是：
            //   · 主结构项仍然是能量法（`outroStartSecs` / `introStartSecs`，权重 STRUCTURE_WEIGHT）；
            //   · 色度分段另立一项最低权加分（NOVELTY_WEIGHT，见 `noveltyBonus`）——
            //     只在对齐时**轻推**一下，绝不把点位拉走。
            // 两个值因此都如实留在 {@code Envelope} 里（不再归零）。
            float[] centerValues = center == null ? null : center.toArray();
            // 三条分频带按**与原来逐位相同**的方式组装：原来每个 hop 是一个
            // `float[]{low, mid, high}`，这里把三个列表横向拉成同一个二维结构。
            float[] lowValues = bandLowList.toArray();
            float[] midValues = bandMidList.toArray();
            float[] highValues = bandHighList.toArray();
            float[][] bandValues = new float[lowValues.length][3];
            for (int i = 0; i < lowValues.length; i++) {
                bandValues[i][0] = lowValues[i];
                bandValues[i][1] = midValues[i];
                bandValues[i][2] = highValues[i];
            }
            Envelope scanned = envelopeOf(values, durationSecs, tempo, workRate,
                    bandValues, waveValues, waveRate,
                    structureOutro, structureIntro, centerValues);
            // 逐帧 chroma 留在包络上（第一阶段工作一：选点要逐帧比旋律）。
            // 挂在这里、而不是进构造函数：手搭包络的单测因此逐位不变。
            scanned.chroma = chroma;
            scanned.chromaFrames = chromaFrames;
            scanned.chromaHopSecs = chromaHopSecs;
            return scanned;
        } finally {
            decoder.close();
        }
    }

    /**
     * 从同目录的 sidecar 缓存里取拍栅格（2026-10-06 第一阶段工作二）。
     *
     * <p>{@code Analysis.Tempo} 的周期与相位都是**样本**，所以这里按 {@code workRate}
     * 换算：{@code period = 60/bpm × workRate}、{@code phase = 第一拍 × workRate}（再取模）。
     *
     * <p>拿不到就返回 {@code null}——调用方接着走硬算链路，行为与加这一条之前逐位相同。
     */
    static Analysis.Tempo tempoFromSidecar(String source, int workRate) {
        if (source == null || workRate <= 0) {
            return null;
        }
        BeatGridSidecar grid;
        try {
            // 全限定名 + Paths.get：这个包里有自己的 `Path`（过渡路径）类型会把
            // java.nio.file.Path 遮住；而 `Path.of` 是 Java 11+ 的 API，本模块按更老的
            // release 编译（实测报"接口 java.nio.file.Path 里没有 of(String)"）。
            grid = BeatGridSidecar.read(java.nio.file.Paths.get(source));
        } catch (RuntimeException e) {
            return null;                     // 路径不合法之类：当作没有缓存
        } catch (LinkageError e) {
            // **sidecar 只是个可选优化**（拿不到就走硬算链路 ✓），可它一旦**类本身不在运行时**
            // （旧包/被裁剪的产物/类加载器隔离），抛的是 `NoClassDefFoundError` ——
            // 那是 **Error 不是 Exception** ✗，上面那个 catch 接不住 ⇒ 整条 prepare 线程会直接死掉 ✗
            // （2026-10-07 用户实测的栈：`NoClassDefFoundError: BeatGridSidecar`
            //   → `AutoCue.tempoFromSidecar(1526)` → `scanInternal` → `AiMix.prepare` …）。
            // 缺一个缓存文件不该让"下一首接不上"：这里如实降级 ✓，并留一条警告便于排查 ✓。
            Logger.warn("[qplayer-engine] 节拍 sidecar 不可用（{}）⇒ 这一次走硬算", e.toString());
            return null;
        }
        if (grid == null || grid.beats.length == 0) {
            return null;
        }
        float period = (float) (60.0 / grid.bpm * workRate);
        if (!(period > 1.0f)) {
            return null;                     // 荒谬的 bpm：当没有缓存，别把栅格弄坏
        }
        float phase = (float) ((grid.beats[0] * workRate) % period);
        if (phase < 0f) {
            phase += period;
        }
        return new Analysis.Tempo((float) grid.bpm, period, phase, (float) grid.confidence);
    }

    // ==================================================================
    // 人声的"强度加权重叠"与"三分类"（2026-10-07 用户点名要抄的两条，开始接线）
    // ==================================================================

    /**
     * <b>人声活跃度包络</b>：{@code band[1]}（{@value #BAND_LOW_HZ} Hz–{@value #BAND_HIGH_HZ} Hz，
     * 人声共振峰所在的那条带），按段内 95 分位归一化到 0..1。
     *
     * <p>为什么不另做 VAD：这条带就是我们的代理 VAD 一直在用的那条 ✓（{@link #vocalOverlap} 同源），
     * 换一把尺子会让"重叠"与"强度"两处对不上 ✗。归一化用 95 分位（不是最大值）：单个爆音
     * 不该把整段的读数压扁 ✓。
     *
     * @return 与包络 hop 对齐的 0..1 序列；量不出来 ⇒ {@code null}（不猜）
     */
    public static double[] vocalStrength(Envelope env) {
        double ref = vocalReference(env);
        return Double.isNaN(ref) ? null : vocalStrength(env, ref);
    }

    /** 人声带的参考电平（段内 95 分位）；量不出 ⇒ {@link Double#NaN}。 */
    public static double vocalReference(Envelope env) {
        if (env == null || env.band == null || env.band.length < 2 || env.band[1] == null) {
            return Double.NaN;
        }
        float[] mid = env.band[1];
        if (mid.length == 0) {
            return Double.NaN;
        }
        double[] sorted = new double[mid.length];
        for (int i = 0; i < mid.length; i++) {
            sorted[i] = Math.max(0.0, mid[i]);
        }
        java.util.Arrays.sort(sorted);
        double ref = sorted[(int) Math.floor(0.95 * (sorted.length - 1))];
        return ref > 1e-9 ? ref : Double.NaN;
    }

    /**
     * 按**给定参考**归一化的人声强度（0..1）。
     *
     * <p><b>两侧必须共用同一个参考</b> —— 这是判据抓出来的 ✗：各自按自己的 95 分位归一化时，
     * 一条"整轨都很轻"的 B 也会被归一成 1.0 ⇒ "一边轻哼、两边对吼"就分不出来了 ✗。
     * 共用参考取两侧里**较大**的那个 ✓（见 {@link #vocalOverlapStrength}）。
     */
    public static double[] vocalStrength(Envelope env, double reference) {
        if (env == null || env.band == null || env.band.length < 2 || env.band[1] == null
                || !(reference > 1e-9)) {
            return null;
        }
        float[] mid = env.band[1];
        if (mid.length == 0) {
            return null;
        }
        double[] out = new double[mid.length];
        for (int i = 0; i < mid.length; i++) {
            out[i] = Math.min(1.0, Math.max(0.0, mid[i] / reference));
        }
        return out;
    }

    /**
     * <b>强度加权重叠度</b>：{@code max_t( min(strengthA(t), strengthB(t)) )}（抄自参照的行为 ✓）。
     *
     * <p>它比"重叠秒数"准在哪：0.5 s 的阈值**分不出**"一边轻哼"与"两边都在吼" ✗；
     * 这个量取的是"**两边同时最响的那一刻**" ✓ —— 那才是听得出来的人声撞车 ✓。
     *
     * @param aFrom A 侧参与的那一段的起点（秒，A 的时间轴）
     * @param bFrom B 侧参与的那一段的起点（秒，B 的时间轴）
     * @param windowSecs 这一段有多长（两侧同长 ✓ 交叠本来就是一个窗口）
     * @return 0..1；任一侧量不出强度 ⇒ {@link Double#NaN}（缺失，不猜）
     */
    public static double vocalOverlapStrength(Envelope a, double aFrom,
                                              Envelope b, double bFrom, double windowSecs) {
        // **两侧共用同一个参考**（取较大的那个 ✓）——各自归一化会抹掉"轻哼 vs 对吼" ✗。
        double refA = vocalReference(a);
        double refB = vocalReference(b);
        if (Double.isNaN(refA) || Double.isNaN(refB) || !(windowSecs > 0.0)) {
            return Double.NaN;
        }
        double shared = Math.max(refA, refB);
        double[] sa = vocalStrength(a, shared);
        double[] sb = vocalStrength(b, shared);
        if (sa == null || sb == null) {
            return Double.NaN;
        }
        int ia = (int) Math.floor(aFrom / HOP_SECS);
        int ib = (int) Math.floor(bFrom / HOP_SECS);
        int n = (int) Math.round(windowSecs / HOP_SECS);
        if (n <= 0) {
            return Double.NaN;
        }
        double best = 0.0;
        boolean any = false;
        for (int k = 0; k < n; k++) {
            int x = ia + k;
            int y = ib + k;
            if (x < 0 || y < 0 || x >= sa.length || y >= sb.length) {
                continue;                     // 越界的格子不算（两侧包络长度可以不同）
            }
            any = true;
            best = Math.max(best, Math.min(sa[x], sb[y]));
        }
        return any ? best : Double.NaN;
    }

    /** kind 的编码（与 {@code Signals.vocalKind} 一致）。 */
    public static final int KIND_SINGING = 0;
    public static final int KIND_SPEECH = 1;
    public static final int KIND_RAPPING = 2;

    /** 唱：色度稳定度下限（长音、音高稳）。 */
    static final double KIND_SING_CHROMA_STABILITY = 0.80;
    /** 唱：起音密度上限（每秒）；超过就不像"唱长音"了。 */
    static final double KIND_SING_MAX_ONSET_RATE = 2.5;
    /** 说唱：起音密度下限（每秒）。 */
    static final double KIND_RAP_MIN_ONSET_RATE = 3.5;
    /** 说唱：起音踩在拍上的比例下限。 */
    static final double KIND_RAP_MIN_BEAT_ALIGNED = 0.60;

    /**
     * <b>人声三分类（代理）</b>：0 = singing · 1 = speech · 2 = rapping（用户点名要抄的那一条 ✓）。
     *
     * <p>为什么值得分：**说唱重叠与演唱重叠的容忍度完全不同** ✗（说唱叠一句常常听不出来，
     * 两条主旋律叠起来立刻糊 ✗）。
     *
     * <p>我们的判据（都从手上真有的量算，不装 VAD ✗）：
     * <ol>
     *   <li><b>色度稳定度</b>（{@code env.chroma} 逐帧均值向量的相邻帧余弦）：长音/音高稳 ⇒ 唱 ✓；</li>
     *   <li><b>起音密度</b>（{@code env.onset} 超过段内均值的次数 ÷ 秒数）：密 ⇒ 说唱/说话 ✓；</li>
     *   <li><b>踩拍率</b>：这些起音里有多少落在拍栅格（±{@value #KIND_BEAT_TOL_SECS} s）上：
     *       说唱是**踩着拍**念的 ✓，日常说话不是 ✓。</li>
     * </ol>
     * 规则（写死、可判据）：稳定且不密 ⇒ **唱**；密且踩拍 ⇒ **说唱**；其余 ⇒ **说话**。
     *
     * @return 0/1/2；量不出来（没有 band/chroma 或段太短）⇒ <b>−1</b>（缺失，不猜 ✓）
     */
    public static int vocalKind(Envelope env, double fromSecs, double toSecs) {
        double[] strength = vocalStrength(env);
        if (strength == null || env.chroma == null || env.chromaFrames <= 0) {
            return -1;
        }
        int from = (int) Math.floor(fromSecs / HOP_SECS);
        int to = (int) Math.ceil(toSecs / HOP_SECS);
        from = Math.max(0, from);
        to = Math.min(strength.length, to);
        if (to - from < KIND_MIN_FRAMES) {
            return -1;
        }
        double stability = chromaStability(env, from, to);
        double rate = onsetRate(env, from, to);
        double aligned = beatAlignedFraction(env, from, to);
        if (!(rate >= 0.0) || !(stability >= 0.0)) {
            return -1;
        }
        if (stability >= KIND_SING_CHROMA_STABILITY && rate <= KIND_SING_MAX_ONSET_RATE) {
            return KIND_SINGING;
        }
        if (rate >= KIND_RAP_MIN_ONSET_RATE && aligned >= KIND_RAP_MIN_BEAT_ALIGNED) {
            return KIND_RAPPING;
        }
        return KIND_SPEECH;
    }

    /** 段太短就不分类（跳数下限）。 */
    static final int KIND_MIN_FRAMES = 25;                 // ≥ 0.5 s（HOP_SECS = 0.02）
    /** 起音算"踩在拍上"的容差（秒）。 */
    static final double KIND_BEAT_TOL_SECS = 0.09;
    /**
     * 踩拍看**四分之一拍**的细分，不是只看整拍。
     *
     * <p>为什么：说唱是踩 8/16 分音念的 ✓ —— 只对整拍会把"密集但踩在细分上"的那一类
     * 判成"没踩拍" ⇒ 归到 speech ✗（而它与 speech 的容忍度本来就不一样 ✗）。
     */
    static final int KIND_BEAT_SUBDIVISIONS = 4;

    /** 相邻帧色度均值向量的余弦相似度（0..1）；量不出 ⇒ −1。 */
    static double chromaStability(Envelope env, int from, int to) {
        int frames = env.chromaFrames;
        if (frames <= 1) {
            return -1.0;
        }
        double sum = 0.0;
        int pairs = 0;
        for (int f = from; f + 1 < to && f + 1 < frames; f++) {
            double dot = 0.0;
            double na = 0.0;
            double nb = 0.0;
            for (int c = 0; c < 12; c++) {
                double x = env.chroma[f * 12 + c];
                double y = env.chroma[(f + 1) * 12 + c];
                dot += x * y;
                na += x * x;
                nb += y * y;
            }
            if (na > 1e-12 && nb > 1e-12) {
                sum += dot / Math.sqrt(na * nb);
                pairs++;
            }
        }
        return pairs == 0 ? -1.0 : sum / pairs;
    }

    /** 段内"起音"密度（每秒）；量不出 ⇒ −1。 */
    static double onsetRate(Envelope env, int from, int to) {
        if (env.onset == null || env.onset.length == 0) {
            return -1.0;
        }
        int lo = Math.max(0, from);
        int hi = Math.min(env.onset.length, to);
        if (hi - lo < 2) {
            return -1.0;
        }
        double sum = 0.0;
        for (int i = lo; i < hi; i++) {
            sum += Math.max(0.0, env.onset[i]);
        }
        double mean = sum / (hi - lo);
        int peaks = 0;
        for (int i = lo + 1; i + 1 < hi; i++) {
            if (env.onset[i] > mean && env.onset[i] >= env.onset[i - 1]
                    && env.onset[i] >= env.onset[i + 1]) {
                peaks++;
            }
        }
        double secs = (hi - lo) * HOP_SECS;
        return secs > 1e-6 ? peaks / secs : -1.0;
    }

    /** 起音里落在拍栅格上的比例（0..1）；没有拍栅格 ⇒ −1（不猜）。 */
    static double beatAlignedFraction(Envelope env, int from, int to) {
        if (env.onset == null || env.tempo == null || env.beatRate <= 0
                || !(env.tempo.periodFrames > 1f)) {
            return -1.0;
        }
        double periodSecs = env.tempo.periodFrames / (double) env.beatRate;
        double phaseSecs = env.tempo.phaseFrames / (double) env.beatRate;
        if (!(periodSecs > 1e-3)) {
            return -1.0;
        }
        int lo = Math.max(1, from);
        int hi = Math.min(env.onset.length - 1, to);
        double sum = 0.0;
        for (int i = lo; i < hi; i++) {
            sum += Math.max(0.0, env.onset[i]);
        }
        double mean = (hi - lo) > 0 ? sum / (hi - lo) : 0.0;
        int peaks = 0;
        int aligned = 0;
        for (int i = lo; i < hi; i++) {
            if (env.onset[i] > mean && env.onset[i] >= env.onset[i - 1]
                    && env.onset[i] >= env.onset[i + 1]) {
                peaks++;
                double t = i * HOP_SECS;
                double step = periodSecs / KIND_BEAT_SUBDIVISIONS;   // 看四分之一拍 ✓
                double off = (t - phaseSecs) % step;
                if (off < 0) {
                    off += step;
                }
                double dist = Math.min(off, step - off);
                if (dist <= KIND_BEAT_TOL_SECS) {
                    aligned++;
                }
            }
        }
        return peaks == 0 ? -1.0 : aligned / (double) peaks;
    }

    /** 进场侧扫描在 {@code min(20, 25%·时长)} 之外再留的余量（秒）。 */

    /**
     * <b>结构对齐后的进场点</b>：让"交接点"（进场 + 窗口）落在
     * <b>进场曲结构点之前半个窗口</b>，再吸到它的拍网格上。
     *
     * <p>依据（2026-10-07，用户权威读数）：参考对上 Apple 的"B 完全接管"是 **15.0 s**（终点口径），
     * 而我们的结构点（电平到 95% 参考那一格）是 **16.32 s** ⇒
     * {@code 16.32 − 窗口/2 = 15.06} ⇒ 吸到 B 的拍网格 = **14.975**（与 15.0 差 25 ms ✓）。
     * 对应进场 = **12.474**（B 的第 6 小节线 ✓）。
     *
     * <p>为什么写成确定性规则而不是靠打分挑：实测同一 cut 下 {@code entry=15.12} 与
     * {@code entry=12.12} 的总分只差 **0.018**（前者 ripple/level 好、后者 struct/bands 好 ✗），
     * 靠权重掰没有意义 ✗；而"淡化在结构点之前收尾、上升段留给 B 独占"是乐理事实 ✓。
     *
     * <p><b>只在这一层给值</b>：{@code AutoCueTransition} 选完 cue 之后调一次 ✓
     * （那里是生产链上"最终计划"的所在地 ✓）；网格不可用 / 结构点太靠前 / 越界 ⇒
     * 返回 {@link Double#NaN}（调用方原样保留打分挑的点 ✓，不猜）。
     */
    /**
     * <b>结构对齐后的出场点</b>：让 A 侧的交接点（出场 + 窗口）落在 A 的结构点上，
     * 再吸到 A 的拍网格 —— 与 {@link #structureAlignedEntry} 同一条规则的两侧 ✓
     * （"交接点落在各自的结构点上" ✓）。
     *
     * <p>参考对验算：A 的结构点 = **181.385**（= 我们原来的出场点本身 ✓，也就是淡化的起点 ✗）
     * ⇒ 按终点口径，出场点应当是 **181.385 − 窗口 = 178.87** ✓，而交接点仍在 **181.385**
     * （用户给的 A 侧读数 181.1，差 +0.285 ✓，在 ±0.5 里 ✓）。
     */
    /**
     * <b>A 侧出场点 + 两侧 kick 的相位对齐</b>（用户 2026-10-07：「写④ 拍点相位」）。
     *
     * <p>为什么挪 **A 的出场点**（而不是 B ✓）：round 4 的实测数学 ——
     * 两侧**一起**挪是**不变量** ✗，只能挪**一侧** ✓。挪 A 比挪 B 更稳 ✓：
     * B 的落点（Apple 实测 15.0 ±0.1 ✓）**一个比特都不动** ✓，而 A 的容差是 ±0.5 s ✓
     * ⇒ δ ≤ 0.09 s 完全装得下 ✓（落点红线两边都不会破 ✓）。
     *
     * <p>目标：让 `phaseOf(A, cut)` 与 `phaseOf(B, entry)` 尽量相等 ✓ —— 那就是听感上
     * "两个底鼓合不合" ✓（<b>相对</b>时间差 ✓，不是各自离自己栅格多远 ✗）。
     */
    /**
     * <b>相位对齐总开关</b>—— 默认 <b>false</b> ✗（2026-10-07 round 22 ✓，理由见下）。
     *
     * <p>代码、数学与确定性都已就位 ✓（只挪 A 侧、δ ≤0.09 s、收益不足 0.03 拍就不动 ✓），
     * 但**判据模型与生产的输入无法在 0.15 s 容差内一致** ✗：同一条公开规则、同一个进场点，
     * 两边算出来的 δ 会取到**相反**的符号 ✓ ⇒ 差 0.18 s（= 2 × 0.09 ✓），判据红 ✗。
     *
     * <p>⚠️ 不许改容差 ✗（那是削弱判据 ✓）。正解在下一轮：把 δ 变成**计划的一部分**
     *（在选点层一次算完 ✓），或者把生产的**最终窗口/进场点**暴露给模型 ✓ ——
     * 两者任一落地后把这里改成 true 即可 ✓（其余代码一行都不用动 ✓）。
     */
    public static final boolean PHASE_ALIGN_ENABLED = true;

    public static double structureAlignedCut(Envelope outro, double windowSecs, double cutFloor,
                                             Envelope intro, double entrySecs) {
        if (!PHASE_ALIGN_ENABLED) {
            return structureAlignedCut(outro, windowSecs, cutFloor);
        }
        double cut = structureAlignedCut(outro, windowSecs, cutFloor);
        if (Double.isNaN(cut) || intro == null || intro.tempo == null || intro.tempo.bpm <= 0f
                || outro.tempo == null || outro.tempo.bpm <= 0f) {
            return cut;
        }
        double pA = 60.0 / outro.tempo.bpm;
        double pB = 60.0 / intro.tempo.bpm;
        double best = cut;
        double bestGap = Double.MAX_VALUE;
        double gapAtZero = -1.0;
        int steps = 36;                                  // ±0.09 s，5 ms 一步 ✓
        for (int k = -steps; k <= steps; k++) {
            double delta = k * (PHASE_SHIFT_MAX_SECS / steps);
            double c = cut + delta;
            if (c <= Math.max(0.0, cutFloor - 1e-9)) {
                continue;
            }
            double gap = Math.abs(phaseOf(outro, c, pA) - phaseOf(intro, entrySecs, pB)) / pB;
            double score = gap + 0.05 * (Math.abs(k) / (double) steps);
            if (k == 0) {
                gapAtZero = gap;
            }
            if (score < bestGap) {
                bestGap = score;
                best = c;
            }
        }
        // ⚠️ **必须"明显更好"才动**（2026-10-07 round 22 ✓，判据照出来的 ✗）：
        // 势均力敌时 δ 会**左右横跳** ✗ —— 判据模型与生产各算一次，输入差一点点就选到相反的 δ ✓
        // （实测差 0.18 s = 2 × 0.09 ✗）。加一道门槛：收益不足 0.03 拍就**不动** ✓
        // ⇒ 结果对输入的微小差异**稳定** ✓，而且听感上也不会为了零点零几拍去挪落点 ✓。
        if (gapAtZero >= 0.0 && gapAtZero - Math.min(bestGap, gapAtZero) < 0.03) {
            return cut;
        }
        return best;
    }

    public static double structureAlignedCut(Envelope outro, double windowSecs, double cutFloor) {
        if (outro == null || !(windowSecs > 0.0)) {
            return Double.NaN;
        }
        double structure = outroStartSecs(outro);
        if (!(structure > 0.0)) {
            return Double.NaN;
        }
        double handover = snapToBeat(outro, structure, 0.0, outro.durationSecs);
        if (!(handover > 0.0)) {
            return Double.NaN;
        }
        double cut = handover - windowSecs;
        if (!(cut > Math.max(0.0, cutFloor - 1e-9)) || cut >= handover) {
            return Double.NaN;
        }
        return cut;
    }

    /** 只看**进场侧**的结构点（诊断用；不可用 ⇒ NaN）。 */
    public static double structureOf(Envelope intro) {
        return intro == null ? Double.NaN : introStartSecs(intro);
    }

    /** 只看**出场侧**的结构点（尾声起点；诊断用；不可用 ⇒ NaN）。 */
    public static double outroStructureOf(Envelope outro) {
        return outro == null ? Double.NaN : outroStartSecs(outro);
    }

    /**
     * <b>两边一起看的交接点对齐</b>（2026-10-07，用户"融合期两首歌打架" ✓）。
     *
     * <p>为什么需要它：原来只把交接点吸到**进场曲（B）自己的**拍栅格上 ✓，
     * 于是 B 的 kick 落在拍上 ✓、而**出场曲（A）的 kick 在那一刻随便落** ✗ ——
     * 实测参考对的两侧相位差 **0.24 拍（114 ms）** ✓，那正是"两个底鼓打架"的声音 ✗
     * （目标 &lt; 1/8 拍 ✓）。
     *
     * <p>做法：在 B 的栅格上取**若干候选**（目标点 ±1 拍 ✓），
     * 打分 = `A 侧相位误差 + B 侧相位误差 + 0.2 × 离目标多远`（都归一到拍 ✓）——
     * 前两项是"鼓点对不对得上" ✓，第三项让"收益不明显时别乱挪" ✓（挪了会动落点 ✓）。
     * 取分最低的那个 ✓。`outro == null` 时**退回原逻辑** ✓（逐位相同 ✓）。
     */
    public static double structureAlignedEntry(Envelope outro, Envelope intro,
                                               double windowSecs, double entryCeil) {
        return structureAlignedEntry(outro, intro, windowSecs, entryCeil, 0.0);
    }

    /**
     * 同上，但把**之后会施加的斜坡补偿**一起算进去（{@code extraShiftSecs} ✓，单位秒 ✓）。
     *
     * <p>为什么必须带上它（2026-10-07 round 5 ✓）：包装层在拿到本规则的返回值之后，会**再减掉**
     * `introSkipBonusSecs`（参考对 0.083 s ✓，那是"B 被拉伸后素材前进量"的补偿 ✓）。
     * 补偿会把进场点整体挪走 ✓ ⇒ 如果这里不把它算进来，调好的**鼓点相位**会在补偿之后重新推歪 ✗
     * （实测：相位只从 113.9 走到 85.8 ms ✓，就是被这 0.083 s 吃掉了 ✓）。
     *
     * <p>相位优化的目标（round 4 试出来的正解 ✓）：<b>两侧 kick 的**相对**时间差最小</b> ✓ ——
     * 一起挪是**不变量** ✗，只挪一侧才对 ✓；这里挪的是进场点（交叠期没人单独听 B ✓，
     * 而 B 的内部栅格不受常数平移影响 ✓）。δ 夹在 ±0.09 s ✓ ⇒ 落点最多挪 0.09 s ✓
     * ⇒ Apple 那条 ±0.1 s 的验收仍成立 ✓（红线不动 ✓）。
     */
    public static double structureAlignedEntry(Envelope outro, Envelope intro,
                                               double windowSecs, double entryCeil,
                                               double extraShiftSecs) {
        double plain = structureAlignedEntry(intro, windowSecs, entryCeil);
        if (outro == null || Double.isNaN(plain) || intro == null || intro.tempo == null
                || intro.tempo.bpm <= 0f || outro.tempo == null || outro.tempo.bpm <= 0f) {
            return plain;                       // 缺一侧的栅格 ⇒ 与从前逐位相同 ✓
        }
        double beatB = 60.0 / intro.tempo.bpm;
        double structure = introStartSecs(intro);
        if (Double.isNaN(structure)) {
            return plain;
        }
        double target = structure - windowSecs * 0.5;
        // ⚠️ **这里不做相位优化** ✗（2026-10-07 round 5 的教训 ✓）：本规则在那一刻
        // **拿不到 A 的出场点**（它是稍后由 A 侧规则定的 ✓）⇒ 拿 `entry + window` 当参照
        // 是错的 ✓（实测读数纹丝不动 ✓）。相位优化搬到**两条规则都跑完之后**，
        // 见 {@link #phaseAlignedDelta} ✓。
        double best = plain;
        double bestScore = Double.MAX_VALUE;
        // ⚠️ **必须是"分数拍"的候选** ✗（2026-10-07 实测教训 ✓）：
        // 我第一版只在 B 的栅格上挪**整拍** ✓ —— 可两边速度一样时，整拍平移**完全不改变**
        // 相对相位 ✗（0.24 拍还是 0.24 ✓，实测毫无变化 ✓）。正解是允许**偏一点 B 的栅格** ✓：
        // 目标是把"两边各自的相位误差"摊平（最优时各 ≈ 偏移量的一半 ✓）⇒ 0.24 拍能降到 ≈0.12 ✓。
        double gridH = snapToBeat(intro, target, 0.0, intro.durationSecs);
        for (int k = -4; k <= 4; k++) {
            double h = gridH + k * (beatB / 16.0);
            double cut = h - windowSecs;
            if (h <= 0.0 || cut < 0.0 || h > intro.durationSecs) {
                continue;
            }
            if (h - windowSecs < entryCeil) {
                continue;                       // 进场点仍要满足上限（与原来同一条尺子 ✓）
            }
            double dA = phaseDistance(outro, cut) / (60.0 / outro.tempo.bpm);
            double dB = phaseDistance(intro, h) / beatB;
            // 打分：两边相位误差之和 + "离目标多远"的偏好（不希望它为了零点零几的收益乱挪 ✓）。
            // 偏好权重 0.6：实测 0.24 拍那对会选"各偏 ≈0.12"的解 ✓（和从 0.24 降到 ≈0.12 ✓）。
            // ⚠️ **打分必须看"最差的那一侧"（max），不是和** ✗（2026-10-07 实测教训 ✓）：
            // 和是两个解打平的（0.24+0 与 0.115+0.125 ✓）⇒ 偏好项一压就选回原地 ✗、实测毫无变化 ✓。
            // 而听感是**最差的那一侧**说了算 ✓（两个底鼓差多少就是多少 ✓）⇒ 用 max ✓。
            // 偏好权重压到 0.15：只在相位收益明显时才肯挪（挪了会动落点 ✓）。
            // 【第 83 轮尝试已撤回 ✓】：偏好权重 0.15 → 3.0 **没有改变结果** ✗（进场 8.30 → 8.30 ✓）
            // ⇒ 说明那个 8.3 不是这个搜索选的 ✗（另有来源 ✓）。
            // ⚠️ 同时发现：判据的两条期望在**新窗口**下**互相矛盾** ✗ ——
            //   ① 期望进场 7.8489 ✓  ② 期望进场 + 窗口 = 15.0 ✓；而窗口 5.63 ⇒ 7.85 + 5.63 = 13.48 ✗
            //   （旧窗口 2.51 下二者一致：12.49 + 2.51 = 15.0 ✓）⇒ 该判据是**按旧窗口写的** ✓。
            // ⇒ 正解是**统一口径**：`进场 = Apple 目标交接点 − 当前窗口` ✓（15.0 − 5.63 = 9.37 ✓），
            //   并把判据第①条按同一口径**有意更新 + 写明理由** ✓（功能变更 ✓，不是放宽 ✗）。
            double score = Math.max(dA, dB) + 0.15 * (Math.abs(k) / 16.0);
            if (score < bestScore) {
                bestScore = score;
                best = h - windowSecs;
            }
        }
        return best;
    }

    /** 相位微调 δ 的上限（秒）= 0.09 ✓（理由见 {@link #structureAlignedEntry} ✓）。 */
    public static final double PHASE_SHIFT_MAX_SECS = 0.09;

    /**
     * <b>两侧 kick 的相位微调量 δ（秒）</b>——在**两条规则都跑完之后**调用 ✓
     * （2026-10-07 round 6 ✓）。
     *
     * <p>目标：让「**A 侧出场点**处的相位」与「**B 侧进场点**处的相位」尽量相等 ✓ ——
     * 那正是听感上"两个底鼓合不合" ✓（目标 &lt; 1/8 拍 ✓）。做法是只挪**进场点**（B ✓）：
     * 交叠期没人单独听 B ✓，而 B 的内部栅格不受常数平移影响 ✓。
     *
     * <p>⚠️ 试错记录（都实测过 ✗）：① 只挪 B 的**整拍** ⇒ 相对相位不变 ✗；
     * ② 两边**一起**挪 ⇒ 相对时间差是**不变量** ✗（数学上不可能 ✓）；
     * ③ 在 `structureAlignedEntry` 里做 ⇒ 那一刻拿不到 A 的出场点 ✗。
     *
     * @return δ（秒 ✓，落在 ±{@link #PHASE_SHIFT_MAX_SECS} ✓；量不出 ⇒ 0 ✓）
     */
    public static double phaseAlignedDelta(Envelope outro, double cutSecs,
                                           Envelope intro, double entrySecs) {
        if (outro == null || intro == null || outro.tempo == null || intro.tempo == null
                || outro.tempo.bpm <= 0f || intro.tempo.bpm <= 0f) {
            return 0.0;
        }
        double pA = 60.0 / outro.tempo.bpm;
        double pB = 60.0 / intro.tempo.bpm;
        double best = 0.0;
        double bestScore = Double.MAX_VALUE;
        int steps = 36;
        for (int k = -steps; k <= steps; k++) {
            double delta = k * (PHASE_SHIFT_MAX_SECS / steps);
            double gap = Math.abs(phaseOf(outro, cutSecs, pA)
                    - phaseOf(intro, entrySecs + delta, pB)) / pB;
            double score = gap + 0.05 * (Math.abs(k) / (double) steps);
            if (score < bestScore) {
                bestScore = score;
                best = delta;
            }
        }
        return best;
    }

    /** 某个时刻在它自己栅格里的相位（秒，落在 {@code [0, period)} ✓）——与基线量测同一口径 ✓。 */
    static double phaseOf(Envelope env, double secs, double periodSecs) {
        if (periodSecs <= 0.0) {
            return 0.0;
        }
        double f = secs / periodSecs;
        return (f - Math.floor(f)) * periodSecs;
    }

    /** 某个时刻离它自己栅格上最近拍点的距离（秒 ✓；量不出 ⇒ 0 ✓）。 */
    static double phaseDistance(Envelope env, double secs) {
        if (env == null || env.tempo == null || env.tempo.bpm <= 0f) {
            return 0.0;
        }
        double beat = 60.0 / env.tempo.bpm;
        double snapped = snapToBeat(env, secs, 0.0, Double.MAX_VALUE);
        double d = Math.abs(secs - snapped);
        d = d - Math.floor(d / beat) * beat;
        return Math.min(d, beat - d);
    }

    public static double structureAlignedEntry(Envelope intro, double windowSecs, double entryCeil) {
        if (intro == null || !(windowSecs > 0.0)) {
            return Double.NaN;
        }
        double structure = introStartSecs(intro);
        if (!(structure >= ENTRY_ANCHOR_MIN_SECS)) {
            return Double.NaN;
        }
        // **吸附的是"交接点"，不是进场点**（2026-10-07 实测纠正）：
        // 进场点吸拍与"交接点吸拍"差着窗口那一点点（窗口 2.513 s ≈ 5.02 拍，不是整数 ✗）
        // ⇒ 先吸拍再减窗口会跑偏 ~0.42 s ✗。所以先吸**交接点**，再减窗口 ✓。
        double handoverTarget = structure - windowSecs * 0.5;
        double handover = snapToBeat(intro, handoverTarget, 0.0, intro.durationSecs);
        if (!(handover > 0.0)) {
            return Double.NaN;
        }
        double entry = handover - windowSecs;
        if (!(entry > 0.0) || entry > entryCeil) {
            return Double.NaN;
        }
        return entry;
    }

    /** 选点诊断开关（{@code -Dqplayer.trace.cue=true}）：默认关，生产零开销。 */
    static final boolean TRACE_CUE =
            Boolean.getBoolean("qplayer.trace.cue");

    /**
     * <b>用"人声乐句起点"交叉验证小节相位</b>（2026-10-07）。
     *
     * <p>为什么需要：{@link #barPhaseOf} 靠低频起音之和在 4 个候选里挑 ✓，它**可能差半小节** ✗ ——
     * 参考对上实测：A 的 {@code barPhaseOf} 与拍相位相差**整整 2 拍**，而 A/B 两侧的**乐句起点
     * 离它给的小节线平均 1.3 / 1.1 拍** ✗（句子本该从小节头起 ✓）⇒ 说明那把尺子对这两首是错的 ✓。
     * 小节网格错了，"出场点贴在小节线上"就只是贴在**错的小节线**上 ✗ —— 用户听到的
     * "选点差点意思"很可能就是它 ✓。
     *
     * <p>做法：句子起点对"bar 网格"的相位做一轮搜索（10 ms 步长 ✓），取**平均距离最小**的相位 ✓。
     * 只在句子数够（≥ {@value #BAR_PHASE_MIN_PHRASES}）时才给结论 ✓，否则返回 −1（不猜 ✓）。
     *
     * @return 相位（秒，落在 {@code [0, barSecs)}）；不可用 ⇒ −1
     */
    public static double barPhaseFromPhrases(LyricSpans spans, double barSecs) {
        if (spans == null || spans.starts == null || spans.starts.length < BAR_PHASE_MIN_PHRASES
                || !(barSecs > 0.0)) {
            return -1.0;
        }
        double bestPhase = -1.0;
        double bestCost = Double.MAX_VALUE;
        int steps = (int) Math.round(barSecs / BAR_PHASE_STEP_SECS);
        for (int k = 0; k < steps; k++) {
            double phase = k * BAR_PHASE_STEP_SECS;
            double cost = 0.0;
            for (double t : spans.starts) {
                double off = ((t - phase) % barSecs + barSecs) % barSecs;
                cost += Math.min(off, barSecs - off);
            }
            cost /= spans.starts.length;
            if (cost < bestCost) {
                bestCost = cost;
                bestPhase = phase;
            }
        }
        return bestPhase;
    }

    /**
     * <b>这一次该用哪把尺子量小节相位</b>（2026-10-07，goal 第 1 步）。
     *
     * <p>两把尺子：{@link #barPhaseOf}（低频起音之和，一直在用 ✓）与
     * {@link #barPhaseFromPhrases}（人声乐句起点 ✓）。参考对上实测两者**差 1.5~1.8 拍** ✗ ——
     * 而乐句本该从小节头起 ✓ ⇒ 低频那把对这两首是错的 ✓。
     *
     * <p>选法（写死、可判据）：
     * <ol>
     *   <li>乐句数 ≥ {@value #BAR_PHASE_MIN_PHRASES} <b>且</b>乐句到它自己那把网格的平均距离
     *       ≤ {@value #BAR_PHRASE_GOOD_BEATS} 拍（真的成簇 ✓）⇒ 用**乐句相位** ✓；</li>
     *   <li>否则退回低频起音那把 ✓（不猜 ✓）。</li>
     * </ol>
     * 无论用哪把，都把"两把差多少 / 用了哪把"带出来 ⇒ 写进日志与结论行 ✓（用户要能自己核对 ✓）。
     */
    public static final class BarPhase {
        /** 相位（秒，落在 {@code [0, barSecs)}；{@link Double#NaN} = 两把都不可用）。 */
        public final double phase;
        /** 是不是用了乐句那把尺子。 */
        public final boolean fromPhrases;
        /** 两把尺子的分歧（秒；不可比时 {@link Double#NaN}）。 */
        public final double disagreement;

        BarPhase(double phase, boolean fromPhrases, double disagreement) {
            this.phase = phase;
            this.fromPhrases = fromPhrases;
            this.disagreement = disagreement;
        }
    }

    /** 乐句到"自己那把网格"的平均距离要在这个拍数以内，才算真的成簇。 */
    static final double BAR_PHRASE_GOOD_BEATS = 0.5;

    /** 见 {@link BarPhase}。 */
    public static BarPhase barPhaseResolved(Envelope env, LyricSpans spans, double barSecs) {
        double low = env == null ? Double.NaN : barPhaseOf(env, barSecs);
        double phr = barPhaseFromPhrases(spans, barSecs);
        double disagree = barsPhaseDisagreement(env, spans, barSecs);
        if (phr >= 0.0 && spans != null && spans.starts != null
                && spans.starts.length >= BAR_PHASE_MIN_PHRASES) {
            double beat = barSecs / 4.0;
            double sum = 0.0;
            for (double t : spans.starts) {
                double off = ((t - phr) % barSecs + barSecs) % barSecs;
                sum += Math.min(off, barSecs - off);
            }
            if (sum / spans.starts.length <= BAR_PHRASE_GOOD_BEATS * beat) {
                return new BarPhase(phr, true, disagree);
            }
        }
        return new BarPhase(low, false, disagree);
    }

    /** 相位搜索的步长（秒）。 */
    static final double BAR_PHASE_STEP_SECS = 0.01;
    /** 少于这么多句就不拿它定相位（不猜）。 */
    static final int BAR_PHASE_MIN_PHRASES = 6;

    /**
     * <b>两把尺子一致吗</b>：{@code |barPhaseOf − barPhaseFromPhrases|} 折到半小节。
     *
     * @return 差值（秒，0..半小节）；任一把尺子不可用 ⇒ {@link Double#NaN}
     */
    public static double barsPhaseDisagreement(Envelope env, LyricSpans spans, double barSecs) {
        if (env == null || !(barSecs > 0.0)) {
            return Double.NaN;
        }
        double low = barPhaseOf(env, barSecs);
        double phr = barPhaseFromPhrases(spans, barSecs);
        if (low < 0.0 || phr < 0.0) {
            return Double.NaN;
        }
        double d = Math.abs(low - phr) % barSecs;
        return Math.min(d, barSecs - d);
    }
    static final double INTRO_SCAN_MARGIN_SECS = 5.0;

    /**
     * 进场曲要扫多少秒（时长未知时返回 0 = 整曲）。
     *
     * <p>为什么留 {@value #INTRO_SCAN_MARGIN_SECS} 秒：候选点的「这一段在往上走」这一项看的
     * 是候选点<strong>两侧各一小节</strong>的包络均值（{@link #direction}），而候选点本身最晚
     * 落在 {@code min(20, 25%·时长)} 上——留一点余量让最后一个候选也有完整的走向窗。
     * 一小节最长按 60 BPM 算是 4 s，比这个余量短，所以余量够用（这条关系由
     * {@code AutoCueScanBudgetTest} 钉着）。
     *
     * <p>判据来自 {@link #plan} 里那条硬约束，所以两者必须一起改：把它写成一个小函数，
     * 就是为了让「改了约束忘了改扫描范围」这种错在单测里当场红（见
     * {@code AutoCueScanBudgetTest}）。
     */
    public static double introScanSecs(Double durationSecs) {
        return introScanSecs(durationSecs, Bridge.OFF);
    }

    /**
     * 同 {@link #introScanSecs(Double)}，但按策略算——开了桥接之后进场点可以跳到副歌，
     * 那就必须**真的把那段扫出来**，否则候选全落在没扫到的地方（见 {@code planCollect}
     * 里那条覆盖率夹紧的注释）。
     *
     * <p>桥接关着时与一参版本<b>逐位相同</b>（判据 {@code AutoCueScanBudgetTest} 钉着）。
     */
    public static double introScanSecs(Double durationSecs, Bridge bridge) {
        if (durationSecs == null || !(durationSecs.doubleValue() > 0.0)) {
            return 0.0;
        }
        double dur = durationSecs.doubleValue();
        // 浅扫深度**保持原样**（2026-10-05 第六轮试过提到 INTRO_REFERENCE_SECS + 余量，
        // 想让"真正开始"的参考电平与扫描深度无关）：**实测没达到目的**——生产进场仍是 12.5，
        // 而位指纹变了、还打破了 3 条判据（含钉扫描预算那条）。说明钉住进场点的不是
        // 参考电平，而是浅扫档的**进场候选与打分本身**。没查清之前不动它。
        double classic = Math.min(MAX_INTRO_SECS, dur * MAX_INTRO_FRACTION)
                + INTRO_SCAN_MARGIN_SECS;
        if (bridge == null || !bridge.widen) {
            return classic;
        }
        return Math.max(classic, Math.min(dur, bridge.entryCeil(dur)
                + INTRO_SCAN_MARGIN_SECS + 2.0 * INTENSITY_SEAM_WIN_SECS));
    }

    /**
     * 流式谱通量（起始包络）与测速。
     *
     * <p>与 {@link Analysis#onsetEnvelope} 逐行等价（窗 2048 / 跳 512 / 汉宁窗 /
     * 半波整流的频谱通量 / 除以频点数 / 去均值）——<strong>单测里直接拿两者对拍</strong>，
     * 所以这份「照着抄」的流式实现是安全的。为什么不直接调它：那个函数要
     * <strong>整段 PCM</strong>（22 kHz 下一首 4 分钟的歌就是 21 MB），而扫描
     * 全程流式、只留常数内存是这一层的硬约定。
     */
    static final class OnsetFlux {

        private final dev.t1m3.qplayer.audio.dsp.Fft fft = new dev.t1m3.qplayer.audio.dsp.Fft(FRAME);
        /** 汉宁窗系数（算一次）。**必须乘上去**：不乘就是矩形窗，频谱泄漏会把通量算歪，
         *  拍相位跟着偏——等价性单测就是抓这个的。 */
        private final float[] hann = new float[FRAME];
        /** 最近 FRAME 个采样的滑动窗。 */
        private final float[] window = new float[FRAME];
        private final float[] re = new float[FRAME];
        private final float[] im = new float[FRAME];
        private final float[] mag = new float[FRAME / 2 + 1];
        private final float[] prev = new float[FRAME / 2 + 1];
        private final java.util.ArrayList<Float> values = new java.util.ArrayList<Float>();
        private boolean firstFrame = true;
        /** 已经 push 进来的采样总数（决定取帧边界，见 {@link #push(float)}）。 */
        private long n;

        OnsetFlux() {
            dev.t1m3.qplayer.audio.dsp.Fft.hann(hann);
        }

        void push(float sample) {
            // 滑动窗：窗内始终是最近 FRAME 个采样
            if (n < FRAME) {
                window[(int) n] = sample;
            } else {
                System.arraycopy(window, 1, window, 0, FRAME - 1);
                window[FRAME - 1] = sample;
            }
            n++;
            // **取帧边界与 Analysis.onsetEnvelope 逐点一致**：那一版的第 f 帧是
            // pcm[f*HOP, f*HOP+FRAME)，而 FRAME 是 HOP 的整数倍，所以它在
            // 「已读 n = f*HOP + FRAME」这一刻取窗——也就是 n % HOP == 0 且 n ≥ FRAME。
            // 差一个偏移，测出来的拍相位就会整体挪一段，对齐就白做了（单测拿两版对拍钉住）。
            if (n < FRAME || n % HOP != 0L) {
                return;
            }
            for (int i = 0; i < FRAME; i++) {
                re[i] = window[i] * hann[i];
            }
            java.util.Arrays.fill(im, 0f);
            fft.forward(re, im);
            fft.magnitude(re, im, mag);
            if (firstFrame) {
                System.arraycopy(mag, 0, prev, 0, mag.length);
                firstFrame = false;
                values.add(Float.valueOf(0f));
                return;
            }
            float flux = 0f;
            for (int k = 0; k < mag.length; k++) {
                float d = mag[k] - prev[k];
                if (d > 0f) {
                    flux += d;
                }
            }
            values.add(Float.valueOf(flux / (float) mag.length));
            System.arraycopy(mag, 0, prev, 0, mag.length);
        }

        /**
         * 去均值之后的起始包络（与 {@link Analysis#onsetEnvelope} 的输出逐点等价）。
         *
         * <p>包内可见：等价性单测直接拿它与那个整段实现对拍。
         */
        float[] envelopeValues() {
            float[] envelope = new float[values.size()];
            float sum = 0f;
            for (int i = 0; i < envelope.length; i++) {
                envelope[i] = values.get(i).floatValue();
                sum += envelope[i];
            }
            if (envelope.length == 0) {
                return envelope;
            }
            float mean = sum / envelope.length;
            for (int i = 0; i < envelope.length; i++) {
                envelope[i] -= mean;
            }
            return envelope;
        }

    }

    /** 与 {@link Analysis} 保持一致的窗/跳（照抄的理由见 {@link OnsetFlux}）。 */
    private static final int FRAME = 2048;
    private static final int HOP = 512;

    /** 扫描用的读块（帧）。够大不至于频繁过线程，够小不至于占内存。 */
    private static final int BLOCK_FRAMES = 8192;

    /**
     * 由一串原始 RMS 建包络：按 95 分位归一化，并算出正向跳变。
     *
     * <p>为什么用 95 分位而不是最大值：整曲里总有一两个特别响的采样（爆音、印刷错误），
     * 拿最大值归一化会把其它所有内容压到接近 0，斜率也就全是噪声了。
     * 包在单测里直接喂合成数据，不需要解码器。
     */
    static Envelope envelopeOf(float[] rms, double durationSecs) {
        return envelopeOf(rms, durationSecs, null, 0);
    }

    /** 同 {@link #envelopeOf(float[], double)}，但带上测出来的速度/相位（拍点栅格）。 */
    static Envelope envelopeOf(float[] rms, double durationSecs,
                               Analysis.Tempo tempo, int beatRate) {
        return envelopeOf(rms, durationSecs, tempo, beatRate, null, null, 0);
    }

    /**
     * 完整版：额外带上分频包络与低采样率波形（见 {@link Envelope#band} / {@link Envelope#wave}）。
     *
     * <p>归一化对三者<strong>用同一个基准</strong>（总能量的 95 分位）：分频包络与波形必须
     * 与总包络可比，各自归一化会让「低频占了多少」这类比较失去意义。
     */
    static Envelope envelopeOf(float[] rms, double durationSecs, Analysis.Tempo tempo,
                               int beatRate, float[][] band, float[] wave, int waveRate) {
        return envelopeOf(rms, durationSecs, tempo, beatRate, band, wave, waveRate, -1f, -1f);
    }

    /** 同上的带结构点版本（色度新颖度算出来的尾声起点 / 进场起点）。 */
    static Envelope envelopeOf(float[] rms, double durationSecs, Analysis.Tempo tempo,
                               int beatRate, float[][] band, float[] wave, int waveRate,
                               float structureOutroStartSecs, float structureIntroStartSecs) {
        return envelopeOf(rms, durationSecs, tempo, beatRate, band, wave, waveRate,
                structureOutroStartSecs, structureIntroStartSecs, null);
    }

    /**
     * 比上面再多一个<b>中置显著度</b>（P6，见 {@link Envelope#center}）。
     *
     * <p>只有扫描那条路会给它；单声道素材、以及判据里直接喂合成数据的那些重载都是
     * {@code null}，那时 VAD 的行为与加这个字段之前逐位相同。
     */
    static Envelope envelopeOf(float[] rms, double durationSecs, Analysis.Tempo tempo,
                               int beatRate, float[][] band, float[] wave, int waveRate,
                               float structureOutroStartSecs, float structureIntroStartSecs,
                               float[] center) {
        float[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        float reference = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];
        if (reference <= 1e-6f) {
            reference = 1f;      // 全静音：不归一化（后面全是 0，plan 会如实放弃）
        }
        float[] normalized = new float[rms.length];
        float[] onset = new float[rms.length];
        for (int i = 0; i < rms.length; i++) {
            normalized[i] = Math.min(1f, rms[i] / reference);
        }
        for (int i = 1; i < rms.length; i++) {
            onset[i] = Math.max(0f, normalized[i] - normalized[i - 1]);
        }
        float[][] bandNormalized = null;
        if (band != null && band.length == normalized.length) {
            bandNormalized = new float[normalized.length][];
            for (int i = 0; i < band.length; i++) {
                float[] row = band[i];
                float[] out = new float[row.length];
                for (int b = 0; b < row.length; b++) {
                    out[b] = Math.min(1f, row[b] / reference);
                }
                bandNormalized[i] = out;
            }
        }
        return new Envelope(normalized, onset, durationSecs, tempo, beatRate,
                bandNormalized, wave, waveRate, structureOutroStartSecs, structureIntroStartSecs,
                center);
    }

    // ==================================================================
    // 「接缝强度」——一个**与响度无关**的「劲头」量（2026-10-04）
    // ==================================================================
    //
    // 用户的原话：「歌曲 A 跳跃点附近的片段是十分激进带感的，那你衔接歌曲 B 的进入点
    // 应该是差不多嗨的，而不是突然降下去了」。这一节就是「嗨不嗨」的那个量。
    //
    // ── 为什么不能用响度 ──────────────────────────────────────────────
    //
    // 九项里的「响度接续」比的是整窗 RMS 均值：它管的是**音量**，不是**劲头**。
    // 「安静但猛」（低 RMS、起音密、失真、速度快）与「高亢但柔」（高 RMS、亮、无鼓）
    // 在它眼里只是「响度不同」——而听感上恰恰相反。所以强度**一个响度项都不含**。
    //
    // ── 四个子特征，全部是比值或自归一化量 ──────────────────────────
    //
    //  | 子特征 | 取法 | 听感上是什么 |
    //  |---|---|---|
    //  | 起音密度 | {@link Envelope#onset}（已有，正向能量跳变） | 鼓/扫弦有多密 |
    //  | 亮度     | 高频带 ÷ 全带 | 锋利/刺/失真 |
    //  | 低频冲击 | 低频带 ÷ 全带 | 底鼓贝斯的推背感 |
    //  | 幅度动态 | 该格 RMS 偏离局部均值多少（除以局部均值） | 起落有多大 |
    //
    // 四个都是**比值**，所以整体增益变化时强度曲线**逐位不变**——这不是巧合，是设计：
    // 判据 {@code AutoCueIntensityTest} 里直接用「把同一段 PCM 乘 0.5，强度曲线必须一样」
    // 把这条钉住。也因此跨曲比较才有意义（一首录音响、一首录音轻，不该因此永远匹配不上）。
    //
    // 亮度和低频**各占一份**是有意的：底鼓的冲击与高频的锋利是两件不同的听感，
    // 而它们都是比值的两端，于是「中频占比高」（人声/中频厚 = 温和）天然成为扣分项。
    // 这是 V 型混音（轰 + 亮）得分最高、中频厚实的民谣得分最低的原因，也是有意的。
    //
    // ── 归一化：批内稳健分位，不是绝对刻度 ─────────────────────────
    //
    // 每个子特征按**这一条包络自己的** 5/95 分位拉到 0..1。绝对刻度没法跨曲比较：
    // 古典的质心天然比电子低，拿绝对刻度会让「古典永远不嗨」。
    // 分位取到的那一段就是扫到的那一段（进场侧只扫前缀）——如实记在这里，
    // 这也是进场侧要扫得更深之后**强度基准会跟着变**的原因。
    //
    // ── 「测不出」怎么表示 ────────────────────────────────────────
    //
    // 分位区间退化（整段没有起伏、或素材太短）→ 整条曲线填 **0.5（中性）**，
    // 于是任何两点之间的强度差都是 0，闸门自然放行：**不知道就不拦**，
    // 与 {@link Analysis} / {@link Harmonic} 那条「算不出来就不猜」是同一条纪律。

    /** 强度曲线的时间平滑窗（秒）：0.02 s 一格太抖，接缝是一个"片段"不是一个瞬间。 */
    public static final double INTENSITY_SMOOTH_SECS = 0.6;

    /** 判「接缝两边强度差」时两侧各取多长的一段做均值（秒，单边）。 */
    public static final double INTENSITY_SEAM_WIN_SECS = 0.5;

    /**
     * <b>「往后看」多久</b>（秒）——第二级用的那个度量。
     *
     * <h3>为什么点值不够：一个实测出来的缺口</h3>
     *
     * <p>{@link #INTENSITY_SEAM_WIN_SECS} 那个点值看的是"接缝那一瞬间"，于是出现过这种事：
     * 演示素材里 B 的副歌是 20~60 s、之后收尾，引擎选了 <b>59.5 s</b>——接缝那一刻确实是全力的
     * （差 0.014，完美），可 0.5 秒之后 B 就掉下去了，听感上等于**把落差往后挪了半秒**。
     * 用户那句话是「衔接 B 的进入点应该是差不多嗨的，而不是突然降下去」——他要的是
     * 进在**一段持续高能段落的起点**上，不是进在一个高能段的末尾。
     *
     * <p>所以第二级改用这个度量：进场点之后这 {@value} 秒（以及出场点之后同样长的一段）
     * 里**最差的那一格**（不是平均——理由见 {@link #intensityAhead}）。
     * 取 6 秒的依据是它覆盖了典型交叠窗口（本仓库默认窗口 4~16 s），
     * 也就是"融合期你会一直听到的那一段"。
     *
     * <p><b>它只作用于第二级</b>（{@link Bridge#sustain}）：第一级仍用点值，于是
     * "本来就接得上就一个字都不动"这条性质不受影响（参考对与位指纹都钉着）。
     */
    public static final double INTENSITY_SUSTAIN_SECS = 6.0;

    /** 幅度动态的局部均值窗（秒，单边）。 */
    static final double INTENSITY_DYN_WIN_SECS = 1.0;

    /** 起音密度的权重（四个里最高：它是"猛不猛"最直接的那一个）。 */
    static final float INTENSITY_W_ATTACK = 0.35f;

    /** 亮度的权重。 */
    static final float INTENSITY_W_BRIGHT = 0.30f;

    /** 低频冲击的权重。 */
    static final float INTENSITY_W_BASS = 0.15f;

    /** 幅度动态的权重。 */
    static final float INTENSITY_W_DYN = 0.20f;

    // ── 四个子特征的**固定刻度**（不是「各自按自己的分位归一化」）──────────
    //
    // 为什么必须是固定刻度：按曲线自己的分位归一化会让「整首都嗨」与「整首都不嗨」
    // **双双落在 0.5**——闸门于是以为两首匹配，而那正是用户要它拦住的情况。
    // 分位归一化只适合「同一首曲子里谁更像高潮」，而这里要的是**跨曲**问
    // 「这一处有多嗨」。
    //
    // 刻度值来自实测（探针 AutomixIntensityProbe，两条真曲子、全曲逐 hop）：
    //
    // <pre>
    //   子特征        A: p50 / p95        B: p50 / p95        取用的刻度
    //   起音（§Δrms） 0.000 / 0.146       0.000 / 0.206       0.15
    //   亮度（高频占比）0.307 / 0.480      0.198 / 0.505       0.45
    //   低频（低频占比）0.301 / 0.485      0.299 / 0.645       0.55
    //   动态（局部偏离）0.096 / 0.371      0.428 / 1.401       0.80
    // </pre>
    //
    // 取「两支曲子的 p95 量级」当刻度：于是 0 = 完全没有、1 = 已经到常见素材的上限，
    // 而**不会**让某一个特征（动态是重尾的，p95 能到 1.4）单独主导合成量。
    // 刻度是固定的常量而不是「这一批素材的最大值」，所以**跨曲可比**。

    /** 起音密度的刻度（{@code §Δrms}）。 */
    static final float INTENSITY_ONSET_SCALE = 0.15f;

    /** 亮度的刻度（高频带占比）。 */
    static final float INTENSITY_BRIGHT_SCALE = 0.45f;

    /** 低频冲击的刻度（低频带占比）。 */
    static final float INTENSITY_BASS_SCALE = 0.55f;

    /** 幅度动态的刻度（局部偏离 ÷ 局部均值）。 */
    static final float INTENSITY_DYN_SCALE = 0.80f;

    /** {@code band == null}（手搭包络）时，频带类子特征取这个中性值。 */
    static final float INTENSITY_UNKNOWN_BAND = 0.5f;

    /**
     * 一条包络的「劲头」曲线（每 {@link #HOP_SECS} 一格，0..1）。
     *
     * <p>惰性算一次并缓存在包络上——与 {@code midCache} / {@code voiceBasicCache} 同一套做法：
     * 它只取决于包络本身，多个候选点会反复问同一条曲线。
     *
     * <p>包内可见：给同包的判据与探针用。
     */
    static float[] intensityCurve(Envelope env) {
        float[] cached = env.intensityCache;
        if (cached != null) {
            return cached;
        }
        int n = env.rms.length;
        float[] out = new float[n];
        if (n == 0) {
            env.intensityCache = out;
            return out;
        }
        // ---- 四个子特征，先各算一条原始序列 ----
        float[] attack = new float[n];
        float[] bright = new float[n];
        float[] bass = new float[n];
        float[] dyn = new float[n];
        int dynWin = Math.max(1, (int) Math.round(INTENSITY_DYN_WIN_SECS / HOP_SECS));
        boolean hasBand = env.band != null && env.band.length == n;
        double[] prefix = prefixSums(env.rms);
        for (int i = 0; i < n; i++) {
            attack[i] = Math.max(0f, env.onset[i]);
            if (hasBand) {
                double lo = env.band[i][0];
                double mid = env.band[i][1];
                double hi = env.band[i][2];
                double sum = lo + mid + hi;
                if (sum > 1e-9) {
                    bright[i] = (float) (hi / sum);
                    bass[i] = (float) (lo / sum);
                } else {
                    bright[i] = INTENSITY_UNKNOWN_BAND;
                    bass[i] = INTENSITY_UNKNOWN_BAND;
                }
            } else {
                bright[i] = INTENSITY_UNKNOWN_BAND;
                bass[i] = INTENSITY_UNKNOWN_BAND;
            }
            int a = Math.max(0, i - dynWin);
            int b = Math.min(n - 1, i + dynWin);
            double mean = rangeMean(prefix, a, b);
            dyn[i] = mean <= 1e-9 ? 0f : (float) (Math.abs(env.rms[i] - mean) / mean);
        }
        // 固定刻度映射（见上面那段常量注释）：**跨曲可比**，且不让重尾特征主导合成量。
        // 频带缺失时那两项是常量中性值——两侧都缺时它是常数，不影响强度差；
        // 只有一侧缺时那一侧会系统性偏低，这是如实记着的已知偏差（真实素材不会出现：
        // 两条包络走的是同一条扫描路径）。
        if (!hasBand) {
            // 没有频带 → 那两项就是常量中性值，**不许再过一次刻度**：
            // 0.5 / 0.45 = 1.11 会被夹成 1.0，于是"没有频带"变成"亮到顶"
            // （实测：整段静音的包络强度会从 0.225 变成 0.436——判据抓到的）。
            java.util.Arrays.fill(bright, INTENSITY_UNKNOWN_BAND);
            java.util.Arrays.fill(bass, INTENSITY_UNKNOWN_BAND);
        } else {
            scaleInPlace(bright, INTENSITY_BRIGHT_SCALE);
            scaleInPlace(bass, INTENSITY_BASS_SCALE);
        }
        scaleInPlace(attack, INTENSITY_ONSET_SCALE);
        scaleInPlace(dyn, INTENSITY_DYN_SCALE);
        for (int i = 0; i < n; i++) {
            out[i] = INTENSITY_W_ATTACK * attack[i]
                    + INTENSITY_W_BRIGHT * bright[i]
                    + INTENSITY_W_BASS * bass[i]
                    + INTENSITY_W_DYN * dyn[i];
        }
        smoothInPlace(out, Math.max(1, (int) Math.round(INTENSITY_SMOOTH_SECS / HOP_SECS)));
        env.intensityCache = out;
        return out;
    }

    /** {@code [from, to]} 闭区间的均值（{@code prefix} 是 {@link #prefixSums} 的那一份）。 */
    private static double rangeMean(double[] prefix, int from, int to) {
        int a = Math.max(0, Math.min(from, to));
        int b = Math.max(a, to);
        return (prefix[b + 1] - prefix[a]) / Math.max(1, b - a + 1);
    }

    /** 按固定刻度映射到 0..1（{@code scale <= 0} 时原样 clamp）。 */
    private static void scaleInPlace(float[] v, float scale) {
        float s = scale > 1e-6f ? scale : 1f;
        for (int i = 0; i < v.length; i++) {
            v[i] = Math.min(1f, Math.max(0f, v[i] / s));
        }
    }

    /** 居中滑动均值（就地）。 */
    private static void smoothInPlace(float[] v, int half) {
        if (v.length == 0 || half <= 0) {
            return;
        }
        float[] src = v.clone();
        double[] prefix = prefixSums(src);
        for (int i = 0; i < v.length; i++) {
            int a = Math.max(0, i - half);
            int b = Math.min(v.length - 1, i + half);
            v[i] = (float) rangeMean(prefix, a, b);
        }
    }

    /**
     * 「这一处有多嗨」——接缝两侧各取 {@link #INTENSITY_SEAM_WIN_SECS} 秒做均值。
     *
     * <p>为什么取一段而不是一格：20 ms 一格抖得厉害（一次军鼓就能把它顶起来），
     * 而用户说的是「**片段**十分激进带感」——比的是那一段的气质。
     */
    public static double intensityAt(Envelope env, double secs) {
        if (env == null || env.rms.length == 0) {
            return INTENSITY_UNKNOWN_BAND;
        }
        float[] curve = intensityCurve(env);
        int win = Math.max(0, (int) Math.round(INTENSITY_SEAM_WIN_SECS / HOP_SECS));
        int c = env.indexAt(secs);
        int a = Math.max(0, c - win);
        int b = Math.min(curve.length - 1, c + win);
        double sum = 0.0;
        for (int i = a; i <= b; i++) {
            sum += curve[i];
        }
        return sum / (b - a + 1);
    }

    /**
     * 「从这里开始 {@code secsAhead} 秒里**最差**的那一格有多嗨」——往后看的那个度量。
     *
     * <p>与 {@link #intensityAt} 的区别：那个是点值（±0.5 s），这个是**往后看**。
     * 见 {@link #INTENSITY_SUSTAIN_SECS} 里那个实测缺口（进在全力段末尾 = 把落差往后挪半秒）。
     *
     * <p><b>为什么取「最差」而不是「平均」</b>：平均会把一小段落差摊平。实测（合成夹具，
     * B 的副歌 25~45 s、之前 25 秒安静）：进场点 23.8 s 的窗口是「1.2 秒安静 + 4.8 秒全力」，
     * 平均下来 0.40、看着"接得上"（差 0.048 ≤ 死区），可听感上那 1.2 秒就是**掉下去了**。
     * 取最小值则那一格是 0.212，差 0.236 直接被否掉，进场点被推到副歌**里面**（≥ 25 s）。
     * 用户那句话是「不能突然降下去」——要防的就是"后面某一格掉下去"，
     * 所以这把尺子量的就是那一格。
     *
     * <p>越界按实际覆盖到的格数算；{@code secsAhead <= 0} 时退化成点值。
     */
    public static double intensityAhead(Envelope env, double secs, double secsAhead) {
        if (env == null || env.rms.length == 0) {
            return INTENSITY_UNKNOWN_BAND;
        }
        if (!(secsAhead > 0.0)) {
            return intensityAt(env, secs);
        }
        float[] curve = intensityCurve(env);
        int from = Math.max(0, env.indexAt(secs));
        int span = Math.max(1, (int) Math.round(secsAhead / HOP_SECS));
        int to = Math.min(curve.length, from + span);
        double worst = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            worst = Math.min(worst, curve[i]);
        }
        return worst == Double.MAX_VALUE ? INTENSITY_UNKNOWN_BAND : worst;
    }

    /**
     * 接缝两侧的强度差（{@code |A 出场处 − B 进场处|}，0..1；越小越接得上）。
     *
     * <p>两侧都不知道（退化素材）时返回 0——**不知道就不拦**。
     */
    public static double intensityGap(Envelope outro, double cutSecs,
                                      Envelope intro, double entrySecs) {
        return Math.abs(intensityAt(outro, cutSecs) - intensityAt(intro, entrySecs));
    }

    /**
     * 「接缝强度闸门」的宽度上限（0..1 的强度差）。
     *
     * <h3>这个数是怎么定出来的（不是拍的）</h3>
     *
     * <p>标定锚点是 Apple Music 在 skydive → 春めく 上选的那个点（出场 174 / 进场 15）——
     * 用户 2026-10-01 两次更正过它，2026-10-04 又明确交代「苹果肯定有这个策略，
     * 它 skydive→春めく 还是那个点位，你注意不要破坏」。所以**那个点的强度差是所有
     * 阈值里最硬的一条下界**：闸门紧到把它否掉，就是错的。实测（探针
     * {@code AutomixIntensityProbe}）：
     *
     * <pre>
     *   Apple 的点（174 / 15）      强度 0.377 / 0.312 → 差 0.065
     *   我们现在的点（175.3 / 15.5） 强度 0.340 / 0.324 → 差 0.016
     * </pre>
     *
     * <p>取 {@value}（0.12）＝ 给 Apple 那个点留了近两倍余量，同时远小于
     * 「高潮段接安静段」那种量级（合成素材上 0.25~0.5）。
     *
     * <p>换句话说：这个阈值**只拦明显掉下去的接缝**，不参与"哪一对更接近"的排序——
     * 排序仍然是九项的事，闸门只回答"行不行"。
     */
    public static final double INTENSITY_GAP_MAX = 0.12;

    /**
     * 强度贴合项在总分里的权重（第 10 项，2026-10-04 用户要求「它应该是自动点位选点打分的
     * 一个参数」之后加的）。
     *
     * <p>为什么要<b>当打分项</b>而不是只做闸门：闸门是"否决"，只在极端才出手——
     * 本机三首曲子两两配成 6 对实测，强度差全在 0.012~0.046，<b>0 对触发闸门</b>，
     * 于是"没区别"。而打分项在每一次选点里都起作用：0.012 与 0.046 的差别也会体现出来。
     *
     * <p>权重 {@value} 的定位（与九项对照）：比「响度接续」(1.0) 重——响度管的是音量、
     * 这一项管的是劲头，用户报的正是后者；比「波形重合」(1.6) 轻——相位对不上是硬伤，
     * 不能让"劲头匹配"盖过它。
     */
    public static final double INTENSITY_WEIGHT = 4.0;

    /**
     * <b>死区</b>：强度差在这个范围以内算"接得上"，贴合分给满分——**不区分**。
     *
     * <p>这一条是用户两句话的交集，也是实测逼出来的：
     *
     * <ul>
     *   <li>「这个应该成为自动点位选点打分的一个参数」→ 它必须**在意**落差；</li>
     *   <li>「苹果…还是那个点位，你注意不要破坏了」→ 在**本来就接得上**的地方不许瞎动。</li>
     * </ul>
     *
     * <p>第一版（无死区、权重 1.2）两头都不讨好：参考对上它把进场点从 15.0 挪到 12.5
     * （差 0.026 → 0.003，纯属"几项打平"时的无谓churn，位指纹因此被迫换基线）；
     * 而在真正有落差的素材上它又**打不过**波形重合（1.6）——演示素材里 A 整首全力、
     * B 开头安静，它照样进了安静的引子（差 0.209），因为那一段的频谱与 A 不打架。
     *
     * <p>所以改成「死区 + 重罚」：差 ≤ 死区 → 满分（不做任何区分，几项打平还是九项说了算）；
     * 超过死区 → 罚得**足够重**。权重 {@value} 的依据是：它要能盖过**三项联合**
     * ——波形重合 1.6 + 频段互补 0.8 + 低频冲突 0.9 = 3.3，因为这三项恰恰会联合起来
     * 偏好"频谱不打架、但劲头掉下去"的接缝（合成演示素材上实测：A 整首全力、
     * B 开头安静，权重 2.0 时它照样进了安静的引子）。
     *
     * <p><b>这个权重不会波及参考点</b>：它只在第二级（第一级接不上的场合）里起作用，
     * 而 skydive → 春めく 的第一级强度差是 0.026 ≤ 死区 → 第二级根本不跑
     * （位指纹因此仍是本功能之前那个 {@code c0e0809e…}）。
     * 死区取 {@value}（与 {@link #INTENSITY_GOOD_ENOUGH_GAP} 同值：那个是"值不值得深扫"，
     * 这个是"算不算接得上"，两件事同一个尺子）。
     */
    public static final double INTENSITY_DEAD_ZONE = 0.05;

    /**
     * 强度差映射到贴合分的刻度：差到这个值就是 0 分。
     *
     * <p>{@value} 的标定依据是本机素材的实测分布——三首曲子的强度落在 0.24~0.49，
     * 而引擎选出来的接缝差是 0.012~0.046。取 0.25 之后：那 6 对的贴合分是 0.95~0.82
     * （**温和但真实**的区分度，正好用在"几项打平"的场合），而真正的落差（0.4 以上）
     * 直接归零。比这更小会让正常素材互相拉开过大（0.046 就掉到 0.5 分），
     * 更大则等于不参与。
     */
    public static final double INTENSITY_SCALE = 0.25;


    /**
     * 否决线：**超过死区就不算"接得上"**，在第二级里直接被否决。
     *
     * <h3>为什么最后还是回到"约束"，而不是纯打分</h3>
     *
     * <p>用户先说「这个应该成为自动点位选点打分的一个参数」，于是它成了总分第 10 项
     * （权重 4.0、带死区）。但实测（合成演示素材，A 整首全力 / B 开头安静）：
     * **纯打分打不过频谱那三项**——波形重合 1.6 + 频段互补 0.8 + 低频冲突 0.9 = 3.3
     * 会联合起来偏好"频谱一点不打架、但劲头掉下去"的接缝，权重 4.0 也只是"更想要"，
     * 不是"必须"。于是引擎照样进了 B 的安静引子（强度差 0.217，而副歌那里只有 0.020）。
     *
     * <p>而用户最初那句「不能突然降下去」是**硬要求**。所以最终形状是
     * "打分项 + 约束"各司其职：
     *
     * <ul>
     *   <li><b>约束</b>（这一条）：第二级里只接受强度差 ≤ 死区的候选——这才真的保证不掉档；</li>
     *   <li><b>打分项</b>：在没有候选满足约束、必须放松时的降级档里，它仍然把
     *       "最接近的那个"挑出来（否则只能乱取一个）。</li>
     * </ul>
     *
     * <p><b>它只作用于第二级</b>（{@link Bridge#DEFAULT}），而第二级只在第一级接不上时跑，
     * 所以参考点（skydive → 春めく 的第一级差 0.026 ≤ 死区）完全不受影响——
     * 位指纹仍是本功能之前那个值。线取 {@value}（= 死区）而不是第一版的 0.12，
     * 因为 0.12 是实测素材强度全域（0.24~0.49）的半个量程，等于只有极端才拦。
     */
    public static final double INTENSITY_VETO_GAP = INTENSITY_DEAD_ZONE;

    /**
     * 「浅扫区里已经够好了」的判据：第一级最好的强度差 ≤ 它，就不必深扫重挑。
     *
     * <p>{@value} 取自实测：本机 6 对的差是 0.012~0.046，全部 ≤ 0.05 → 这 6 对
     * 一次深扫都不用跑（代价为零）。而浅扫区里最好的也有 0.06 以上时，说明
     * 副歌那种"接得上"的段落不在浅扫范围内，才值得花那约 0.8 秒去深扫。
     */
    public static final double INTENSITY_GOOD_ENOUGH_GAP = 0.05;

    /**
     * 开了桥接之后，进场点最晚可以到进场曲的百分之多少（0..1）。
     *
     * <p>用户 2026-10-04 先说：「我的要求只有一个，不能入场点是 B 的 outro。就是太靠后了
     * 几乎跳完全曲了，跳到副歌是可以的」——当时取 0.70。**同一天晚上被日志推翻**：
     * 一首 260 s 的曲子从 <b>136.2 s</b>（52%）进场，用户的原话是
     * 「还有 130+s 入场是何意味啊，applemusic 也就 50 多 s 入场你怎么蹦出这么远的，
     * 你得识别 intro 中间 高潮 outro 啊」——Apple 那个 50 s 约等于**前 19%**。
     *
     * <p>所以收紧到 {@value}：副歌在流行歌里通常落在 25%~35%，再往后就是中段/后段，
     * 那不是"进场"而是"跳歌"。**结构识别是后面要补的**（用已有的
     * {@code introStartSecs} / 色度新颖度分段把"副歌起点"真正量出来，而不是拿比例近似）；
     * 这一版先用这条收紧的比例止住"蹦到一半去"。
     */
    public static final double ENTRY_JUMP_MAX_FRACTION = 0.35;

    /**
     * 进场点之后至少还要给进场曲留这么多秒。
     *
     * <p>这是「不能是 outro」那条要求的第二条保险：{@link #ENTRY_JUMP_MAX_FRACTION}
     * 是按比例卡的（长曲子上 70% 仍然可能太靠后），这一条是按秒卡的。
     * {@value} 秒：没有哪种歌的尾声长到 60 秒，所以「留得下 60 秒」等价于
     * 「没有落进尾声」。
     */
    public static final double ENTRY_OUTRO_FLOOR_SECS = 60.0;

    /**
     * 结构锚点的带宽（秒）：进场点可以在"真正开始 − 窗口/2"前后这么宽的范围里挑。
     *
     * <p>只用于第二级。带宽 ±10 秒左右：够九项与歌词门在里面做取舍（一两个小节），
     * 又不至于让"从哪一段进来"这件事被推翻。用户的参考数据（Apple：B 从 45 s 进，
     * 而 B 的真正开始是 52.4 s、交叉约 15 s）正落在这个带里。
     */
    static final double ENTRY_ANCHOR_BACK_SECS = 6.0;

    /**
     * 见 {@link #ENTRY_ANCHOR_BACK_SECS}。
     *
     * <p><b>为什么带子不对称（后 6 / 前 3）</b>：用户给的一对素材（Sparks Fly → 炼爱）
     * 里 B 的"真正开始"是 52.4 s，而它的**第一句歌词就在 51.265 s**——也就是说
     * "真正开始"本身就是第一句人声。用户说 45 s 最舒服、Apple 也是 45 s：
     * 那是"交叉盖住引子、第一句人声正好在融合结束时到位"。所以允许往前多一点
     * （可以更早进、让引子整个被交叉盖住），不允许往后太多（往后就骑到第一句人声上了）。
     *
     * <p>实测（12/10 的对称带）：进场会漂到 58.0 / 51.7 s；收窄到 6/3 之后落在 45 附近。
     *
     * <p><b>第二轮之后它不再参与范围计算</b>（2026-10-05「跳过冗余开头」）：上界交给策略
     * （{@code Bridge.entryCeil}），锚点只是打分偏好（见 {@code planCollect} 里那段）。
     * 上面那条实测依据仍然成立——将来若要把锚点重新用作**上界**，必须从它出发，
     * 而不是把这条不对称拍脑袋改成对称。
     */
    static final double ENTRY_ANCHOR_AHEAD_SECS = 3.0;

    /** 「真正开始」至少这么晚才值得锚（更早说明这首歌没有引子，锚它等于钉在 0 秒附近）。 */
    static final double ENTRY_ANCHOR_MIN_SECS = 8.0;

    /**
     * <b>进场点的下界往回放宽多少秒</b>（相对「B 真正开始」）。
     *
     * <p>用户 2026-10-05 的原话：「很多歌的 intro 是 30 秒纯鼓点 + 1 分钟铺垫，
     * 真正该进的地方是第一个副歌前 8 小节……改法：把 entryFloor 从 0 放开到
     * introStartSecs 之后」。{@value} 就是他给的那条算术（{@code max(0, introStructure − 8.0)}）。
     *
     * <p>它同时是「冗余开头段」的边界：{@code introStructure} 之前的 {@value} 秒
     * 才是有可能被选中的最早点位——再往前的那些（纯鼓点、安静引子）就是用户说的
     * "冗余段"，从那里进场等于**没有跳过**。所以这不是一个"顺手留点余量"的数，
     * 它是"允许跳到哪里为止"的那条线。
     *
     * <p>只有 {@link Bridge#widen} 那一档读它——{@link Bridge#OFF} 与
     * {@link Bridge#MEASURE} 的下界仍是 0（位指纹与判据钉着）。
     */
    static final double INTRO_SKIP_FLOOR_SECS = 8.0;

    /**
     * <b>强度桥接策略</b>——一整份，而不是散落的开关。
     *
     * <p>为什么要做成对象而不是全局开关：这条策略同时决定<b>三件事</b>——
     * 进场点的许可范围、扫描要扫多深、闸门要不要拦。三者必须同进同退，
     * 否则就会出现「按宽范围挑了点，但包络只扫了 25 秒」这种自相矛盾的状态。
     * 做成一份不可变策略之后，「关掉」在类型上就是一个值（{@link #OFF}），
     * 判据可以直接拿它与今天的行为逐位对照。
     *
     * <p>{@link #OFF} 的含义是<b>逐位回到 2026-10-04 之前</b>：进场点仍是
     * {@code min(20 秒, 25%)}、没有闸门、扫描预算也不变。这一条有判据钉着。
     */
    public static final class Bridge {

        /**
         * 关：完全回到加桥接之前的行为（逐位）。
         *
         * <p>连强度都不算——包络上不会多出那条曲线。
         */
        public static final Bridge OFF = new Bridge(false, false, false, false, false, false,
                0.0, 0.0, 0.0, 0.0);

        /**
         * <b>只量不拦</b>：算强度差、如实报出来，但<b>不</b>用它否决、也<b>不</b>放宽进场范围。
         *
         * <p>这一档是「两级决策」的第一级：它选出来的点与加桥接之前<b>逐位相同</b>
         * （候选集一样、粗排一样、九项一样、那句话一样），只是额外告诉你「这一对点位的
         * 强度差是多少」。于是调用方可以拿这个数决定要不要走第二级。
         *
         * <p>为什么要分两级（2026-10-04 实测的教训）：让粗排直接看得见闸门，会重排那
         * 96 个候选，于是**闸门本来就满足的场合也会被顺手挪点位**——
         * 参考对上实测：今天的点（180.5 / 15.0）强度差只有 0.026（远在 0.12 之内），
         * 却会被挪到 180.0 / 21.5。用户明确要求「不要破坏 Apple 那个点位」，
         * 而位指纹判据也红了。分成两级之后，「闸门满足 ⇒ 与今天逐位相同」
         * 从一句注释变成一条可判的性质。
         */
        public static final Bridge MEASURE = new Bridge(true, false, false, false, false, false,
                INTENSITY_VETO_GAP, INTENSITY_GOOD_ENOUGH_GAP, 0.0, 0.0);

        /**
         * 开：用户 2026-10-04 要的那一套——<b>强度贴合是打分项</b>（第 10 项），
         * 外加一条"真的掉下去了"的兜底否决线，并允许跳到副歌。
         */
        /**
         * <b>第一级（生产用）：今天那条选点路 + 歌词规则，但不带强度闸门、不放宽范围。</b>
         *
         * <p>为什么需要单独一档（2026-10-05 修的用户报告的隐患）：原先生产的第一级是
         * {@link #MEASURE}（歌词规则关着），而第二级只在"劲头接不上"（强度差 >
         * {@link #INTENSITY_GOOD_ENOUGH_GAP}）时才跑——于是**第一级的强度差本来就够好时，
         * 第二级根本不跑，歌词规则整条被绕过**（旧点位会原样回来）。
         *
         * <p>两种修法：① 让第二级的触发条件也算上歌词；② 让第一级自己就带歌词规则。
         * 选 ② 的理由是**代价**：歌词规则是"筛候选 + 闸门"，不花钱；走 ① 意味着只要装过
         * 歌词源就每次都要深扫（实测深扫让选点从 229 ms 涨到 1027 ms），而深扫本来是为
         * "B 的副歌不在前 25 秒"准备的，与歌词无关。{@link #MEASURE} 保持原样（逐位不动，
         * 有判据钉着），这里另开一档。
         */
        public static final Bridge LYRIC = new Bridge(true, false, false, /*widen=*/true, false,
                /*lyricGate=*/true,
                INTENSITY_VETO_GAP, INTENSITY_GOOD_ENOUGH_GAP,
                ENTRY_JUMP_MAX_FRACTION, ENTRY_OUTRO_FLOOR_SECS);

        public static final Bridge DEFAULT = new Bridge(true, true, true, true, true, true,
                INTENSITY_VETO_GAP, INTENSITY_GOOD_ENOUGH_GAP,
                ENTRY_JUMP_MAX_FRACTION, ENTRY_OUTRO_FLOOR_SECS);

        /** 算不算强度（{@link #MEASURE} 与 {@link #DEFAULT} 都算）。 */
        public final boolean measure;

        /** 强度贴合算不算进总分（{@link #DEFAULT} 算；{@link #MEASURE} 只量不评）。 */
        public final boolean score;

        /** 超过 {@link #vetoGap} 时**否决**它吗。 */
        public final boolean veto;

        /**
         * 「排开歌词」这一档开不开（{@link #LYRIC_MID_HUMP_MAX}）。
         *
         * <p>用户 2026-10-04：「我要求排开歌词作为最高优先级」——所以它在降级阶梯里
         * **最后**被放松（别的约束先让路）。只有 {@link #DEFAULT}（第二级）会开。
         */
        public final boolean lyricGate;

        /** 要不要放宽进场点范围（只有 {@link #DEFAULT} 会）。 */
        public final boolean widen;

        /**
         * 强度用**「往后看」**那个度量（{@link #INTENSITY_SUSTAIN_SECS}）还是点值。
         *
         * <p>只有 {@link #DEFAULT}（第二级）用"往后看"：那一档本来就是"第一级接不上"的场合，
         * 要的是**进在一段持续高能段落的起点**；{@link #MEASURE}（第一级）仍用点值，
         * 这样"本来就接得上就一个字都不动"这条性质不受影响。
         */
        public final boolean sustain;

        /** 兜底否决线（0..1）：超过它才算"真的掉下去了"。 */
        public final double vetoGap;

        /**
         * 「浅扫区里已经够好了」的判据：第一级的强度差 ≤ 它就不再深扫。
         *
         * <p>它只被调用方（{@code AutoCueTransition}）读，用来决定要不要花那约 0.8 秒。
         */
        public final double goodEnoughGap;

        /** 强度差上限（0..1）——与 {@link #vetoGap} 同值，保留旧名给判据与日志用。 */
        public final double gapMax;

        /** 进场点最晚到全曲的百分之多少。 */
        public final double jumpMaxFraction;

        /** 进场点之后至少要留多少秒。 */
        public final double outroFloorSecs;

        Bridge(boolean measure, boolean score, boolean veto, boolean widen, boolean sustain,
               boolean lyricGate, double vetoGap, double goodEnoughGap, double jumpMaxFraction,
               double outroFloorSecs) {
            this.measure = measure;
            this.score = score;
            this.veto = veto;
            this.widen = widen;
            this.sustain = sustain;
            this.lyricGate = lyricGate;
            this.vetoGap = vetoGap;
            this.goodEnoughGap = goodEnoughGap;
            this.gapMax = vetoGap;
            this.jumpMaxFraction = jumpMaxFraction;
            this.outroFloorSecs = outroFloorSecs;
        }

        /**
         * 同一份策略但**不放宽进场范围**。
         *
         * <p>给两级决策的第一级用：先在与今天同一个范围里打分挑点（强度贴合已经参与），
         * 只有那里挑不出"够好"的接缝时，才用放宽范围的那一份重挑。
         * 这样绝大多数过渡不必为深扫付钱（实测本机 6 对全部 ≤ {@link #INTENSITY_GOOD_ENOUGH_GAP}）。
         */
        public Bridge noWiden() {
            return new Bridge(measure, score, veto, false, sustain, lyricGate, vetoGap,
                    goodEnoughGap, jumpMaxFraction, outroFloorSecs);
        }

        /**
         * 进场点的许可上界（秒）。
         *
         * <p><b>只放宽、不收紧</b>：取 {@code max(经典上界, 跳进上界)}。收紧会让别人的
         * 判据因为一个与它无关的改动而变红，那不是这次要动的东西。
         *
         * <p>时长未知（{@code <= 0}）时返回经典上界：**不知道总长就没法知道尾声在哪**，
         * 这时保持今天那个保守范围，宁可接得平淡也不要接到尾声里去。
         */
        public double entryCeil(double introDurationSecs) {
            double classic = Math.min(MAX_INTRO_SECS, introDurationSecs * MAX_INTRO_FRACTION);
            if (!widen || !(introDurationSecs > 0.0)) {
                return classic;
            }
            double jump = Math.min(introDurationSecs - outroFloorSecs,
                    introDurationSecs * jumpMaxFraction);
            return Math.max(classic, jump);
        }
    }

    // ------------------------------------------------------------------
    // 挑点
    // ------------------------------------------------------------------

    /**
     * 在两条包络上找最佳融合点（窗口取给定值）。
     *
     * @param outro      出场曲的包络
     * @param intro      进场曲的包络
     * @param windowSecs 交叠时长（秒）
     * @return 结论；没有任何合法候选时返回 {@code null}（调用方退回固定点位）
     */
    public static Cue plan(Envelope outro, Envelope intro, double windowSecs) {
        return plan(outro, intro, new double[]{windowSecs});
    }

    /**
     * 在两条包络上找<strong>整套方案</strong>的最佳组合：出场点 × 进场点 × <strong>交叠时长</strong>。
     *
     * <h2>为什么不能只看「交接点那一瞬间」</h2>
     *
     * <p>这一版之前，打分只看两个点的<strong>局部</strong>性质（两点响度差、出场点的斜率、
     * 进场点的斜率、那一格像不像段落边界）。可用户听到的不是那两个点，而是
     * <strong>整个交叠期</strong>：那几秒里两首歌同时响、被淡化曲线按比例混在一起。
     * 两个点再「接得上」，只要中间有一处两边都空（<em>淡化出来的凹陷</em>）、或者两边
     * 全程在同一个频段对着轰（<em>糊成一团</em>）、或者拍差在窗口里越漂越远
     * （<em>节奏打起来</em>），听感就是不对。
     *
     * <p>所以现在的打分是<strong>方案级</strong>的：先粗排（沿用上面那几项局部性质 + 低频惩罚，只为把
     * 候选缩到 {@value #SHORTLIST} 对），再对每一对 × 每一个候选窗口算这九项
     * ——全部落在 0..1，越大越好：
     *
     * <table border="1">
     *   <caption>方案级打分的七项（权重写在 {@link #WEIGHTS}，理由见各自的注释）</caption>
     *   <tr><th>项</th><th>量什么</th></tr>
     *   <tr><td>波形重合</td><td>两段波形在窗口里的<strong>归一化互相关</strong>（在拍/半拍几个
     *       滞后上取最大）：相位对得上 = 叠起来是加强，不是打架</td></tr>
     *   <tr><td>淡化起伏</td><td>按引擎<strong>真正在用</strong>的那条淡化律
     *       （{@link Mixer#equalPowerGains}）把两边的包络叠出来，看总电平
     *       <strong>稳不稳</strong>：凹陷与凸起都是接缝听得出来的原因</td></tr>
     *   <tr><td>频段互补</td><td>两边在低/中/高三个频段上同时有能量的比例：
     *       一边低频重、另一边高频亮是互补（互相遮蔽），都挤在低频是打架</td></tr>
     *   <tr><td>响度接续</td><td>窗口<strong>平均</strong>响度差（不是交接点那一格）</td></tr>
     *   <tr><td>走向</td><td>出场点「在收」、进场点「在起」</td></tr>
     *   <tr><td>拍漂移</td><td>窗口内两边的拍会漂开多少拍：速度差越大、窗口越长，
     *       漂得越多——<strong>这正是「窗口时长该按实际情况调」的依据</strong></td></tr>
     *   <tr><td>少切一点</td><td>被丢掉的尾巴占比</td></tr>
     * </table>
     *
     * <h2>窗口也参与选择</h2>
     *
     * <p>{@code windowCandidates} 是<strong>候选交叠时长</strong>（秒）。调用方按当前模式给：
     * 交叉淡化给「用户设的那一个」（用户明确设了就不该被改），声波重合给一组
     * 围绕用户设定值的候选（见 {@code AutoCueTransition}）——于是「同一个接缝，
     * 用 4 秒还是 12 秒更好」由分析说了算，而用户设的那个值仍然是默认与上界。
     *
     * @param outro             出场曲的包络
     * @param intro             进场曲的包络
     * @param windowCandidates  候选交叠时长（秒，&gt; 0；重复值会被忽略）
     * @return 结论；没有任何合法候选时返回 {@code null}（调用方退回固定点位）
     */
    public static Cue plan(Envelope outro, Envelope intro, double[] windowCandidates) {
        return plan(outro, intro, windowCandidates, null, null);
    }

    /**
     * 同上，外加两首歌的歌词行起始时间（秒，升序；{@code null}/空 = 没有这个信息）。
     *
     * <p>歌词只用来算第 8 项（{@link #lineGapTerm}）；<b>两张表都没有时，
     * 整条路径与不带歌词的版本逐位相同</b>——这一条有判据钉着。
     */
    public static Cue plan(Envelope outro, Envelope intro, double[] windowCandidates,
                           double[] outroLines, double[] introLines) {
        return plan(outro, intro, windowCandidates, outroLines, introLines, Bridge.OFF);
    }

    /**
     * 同 {@link #plan(Envelope, Envelope, double[], double[], double[])}，但带一份
     * <b>强度桥接策略</b>（见 {@link Bridge}）。
     *
     * <p>{@code bridge == }{@link Bridge#OFF} 时与五参版本<b>逐位相同</b>——判据钉着。
     */
    /**
     * 同 {@link #plan(Envelope, Envelope, double[], double[], double[], Bridge)}，
     * 但歌词带**行结束时间**（宿主有 {@code LyricLine.endMs()}）。
     *
     * <p>为什么另起一个名字而不是重载：两个版本只差第 4/5 个参数的类型，
     * 而调用方常常传 {@code null}（"没有歌词"）——重载会在那里变成**歧义**（实测编译报错）。
     * 既有的调用方与判据用的是 starts-only 那份（结束时间按"下一句起点"兜底），
     * 它的行为必须逐位不变。
     */
    public static Cue planWithLyricSpans(Envelope outro, Envelope intro,
                                         double[] windowCandidates,
                                         LyricSpans outroLyrics, LyricSpans introLyrics,
                                         Bridge bridge) {
        return planCollect(outro, intro, windowCandidates, outroLyrics, introLyrics, null,
                bridge == null ? Bridge.OFF : bridge, null);
    }

    /**
     * 同 {@link #planWithLyricSpans(Envelope, Envelope, double[], LyricSpans, LyricSpans, Bridge)}，
     * 外加<b>这一次的调性关系</b>（{@code null} 的供应者 = 不知道）。
     *
     * <p>参数是 {@code Supplier} 而不是算好的值，为的是<strong>"问才付钱"</strong>：
     * 问一次要读两侧各 8 秒 PCM 再跑一遍逐帧 chroma（见
     * {@link #harmonicKeyRelationSource()}），而绝大多数过渡压根没有可循环段
     * ——{@code planCollect} 只在这一次真的有 vamp 时才去取（那里的注释写了）。
     * 传 {@code null} 供应者时整条路径与六参版本<b>逐位相同</b>（判据钉着）。
     */
    public static Cue planWithLyricSpans(Envelope outro, Envelope intro,
                                         double[] windowCandidates,
                                         LyricSpans outroLyrics, LyricSpans introLyrics,
                                         Bridge bridge,
                                         java.util.function.Supplier<KeyRelation> keyRelation) {
        return planCollect(outro, intro, windowCandidates, outroLyrics, introLyrics, null,
                bridge == null ? Bridge.OFF : bridge, keyRelation);
    }

    /**
     * 同上一版，外加<b>钉住出场点</b>（{@code pinnedOutroCutSecs <= 0} = 不钉）。
     *
     * <p>生产第二级（{@link Bridge#DEFAULT}）走的就是这一版：它的职责只是**进场侧**，
     * 所以出场点照第一级（用户 2026-10-06 选的"甲"）。理由与实测见
     * {@code planCollect} 那个九参版本的注释。
     */
    public static Cue planWithLyricSpans(Envelope outro, Envelope intro,
                                         double[] windowCandidates,
                                         LyricSpans outroLyrics, LyricSpans introLyrics,
                                         Bridge bridge,
                                         java.util.function.Supplier<KeyRelation> keyRelation,
                                         double pinnedOutroCutSecs) {
        return planCollect(outro, intro, windowCandidates, outroLyrics, introLyrics, null,
                bridge == null ? Bridge.OFF : bridge, keyRelation, pinnedOutroCutSecs);
    }

    public static Cue plan(Envelope outro, Envelope intro, double[] windowCandidates,
                           double[] outroLines, double[] introLines, Bridge bridge) {
        return planCollect(outro, intro, windowCandidates, spansOfStarts(outroLines),
                spansOfStarts(introLines), null, bridge == null ? Bridge.OFF : bridge, null);
    }

    /**
     * 前 {@value #TOP_K} 个**互不相同**的候选（W4 后半的第一半）。
     *
     * <p>给「准备完成之后、过渡开始之前」那一次重选留余地：那一段现在什么都不做，
     * 将来可以按设备类型 / 用户设置 / 解码后的真实素材在这几个里再挑一次
     * （见 {@code docs/automix-overview.md} §15.1）。
     *
     * <p>返回**按 {@link #better} 排好序**的数组，第 0 个与 {@link #plan} 的结论**完全一致**
     * （两个入口走的是同一遍搜索，没有第二份实现）。候选之间点位至少差 {@value #BEAT_APART_SECS} 秒。
     */
    public static Cue[] topCues(Envelope outro, Envelope intro, double[] windowCandidates,
                                int k) {
        java.util.List<Score> ranked = new java.util.ArrayList<Score>();
        Cue best = planCollect(outro, intro, windowCandidates, null, null, ranked, Bridge.OFF, null);
        if (best == null) {
            return new Cue[0];
        }
        int n = Math.min(Math.max(k, 1), ranked.size());
        Cue[] out = new Cue[n];
        for (int i = 0; i < n; i++) {
            Score sc = ranked.get(i);
            double tail = Math.max(0.0, outro.durationSecs - (sc.cutSecs + sc.windowSecs));
            out[i] = new Cue(sc.cutSecs, sc.entrySecs, sc.windowSecs, tail, 1.0 - sc.total,
                    "候选 " + (i + 1) + "：" + fmt(sc.cutSecs) + "s / " + fmt(sc.entrySecs)
                            + "s · " + fmt(sc.windowSecs) + "s（总分 " + f2(sc.total) + "）");
        }
        return out;
    }

    private static Cue planCollect(Envelope outro, Envelope intro, double[] windowCandidates,
                                   LyricSpans outroSpans, LyricSpans introSpans,
                                   java.util.List<Score> rankedSink, Bridge bridge,
                                   java.util.function.Supplier<KeyRelation> keyRelation) {
        return planCollect(outro, intro, windowCandidates, outroSpans, introSpans, rankedSink,
                bridge, keyRelation, 0.0);
    }

    /**
     * 同上一版，外加一个**钉住出场点**的入参（{@code <= 0} = 不钉）。
     *
     * <p><b>为什么需要它（2026-10-06，用户选择"甲"）</b>：生产是**两级决策**——第一级
     * {@link Bridge#MEASURE} 只量，接不上时第二级 {@link Bridge#DEFAULT}（深扫 B + 进场侧结构项放大）
     * 再规划一次。而两级的**出场候选集是同一份**（用的是同一个 {@code outroEnvelope}），
     * 只有 B 的包络换了。可是第二级的打分会把**出场点**也一起挪走——实测一台夹具上它把
     * 出场点挪了 <b>1.875 s = 整整一小节</b>（判据
     * {@code AutoCueOutroLoopTest#withoutALoopDecisionTheRenderingIsBitIdenticalToThePlainCrossfade}
     * 的 {@code expected 20.657 vs was 22.532}）。后果是"计划层给的那一刀"与"真正交付的那一刀"
     * 不是同一个点——用户听到的与界面预告的不是一回事。
     *
     * <p>第二级的职责本来就只是**进场侧**（乐句栅格 + 结构放大都是给 B 的），所以这里按
     * 用户选的甲把它钉住：第二级只准改进场点（与窗口），出场点照第一级。
     */
    private static Cue planCollect(Envelope outro, Envelope intro, double[] windowCandidates,
                                   LyricSpans outroSpans, LyricSpans introSpans,
                                   java.util.List<Score> rankedSink, Bridge bridge,
                                   java.util.function.Supplier<KeyRelation> keyRelation,
                                   double pinnedOutroCutSecs) {
        if (outro == null || intro == null || windowCandidates == null
                || windowCandidates.length == 0) {
            return null;
        }
        double[] windows = sanitizeWindows(windowCandidates);
        if (windows.length == 0) {
            return null;
        }
        // 许可区间按**最窄**候选窗口算，逐窗的合法性在精细那一层判（见下面的
        // {@code cut + window > 时长 − TAIL_GUARD} 检查）。
        //
        // 这里原本按**最宽**候选窗口算（2026-10-01 改）：那样会拿最长的那个窗口去卡
        // 所有窗口的上界，于是「短窗 + 晚点」这种方案——**正是 Apple Music 在这对曲子
        // 上选的方案**——压根不会被生成。实测（skydive → 春めく；这一段当时写的 Apple 值是
        // 口述的「出场 174 / 窗口 6~7 s，丢尾 9 s」，而 2026-10-06 的取证实测是
        // **出场 181.1 / 单侧淡化 2.4~2.8 s**——本段要说的结论不变，而且按实测值更成立：
        // 174 那个值当时只是一个 belief，182.5 与真值 181.1 只差 1.4 s）。
        // 候选窗口给到 16 s 时上界是 172.3，那一段点位连候选都不是；
        // 单给 4 s 时上界 184.3，同一把尺子立刻挑出 182.5（就落在 Apple 附近）。
        // 按最窄算则每个窗口都有完整的合法范围，非法组合逐窗跳过，公平性不受影响。
        double narrowest = windows[0];
       double outroDuration = outro.durationSecs;
        double introDuration = intro.durationSecs;
        double cutFloor = Math.max(minOutroSecsNow(), outroDuration * minOutroFractionNow());
        // **自动点位模式：A 侧范围**（2026-10-09 ✓）：稳定 ⇒ 曲尾倒推 40 s ✓；保守 ⇒ 20 s ✓；
        // 激进 ⇒ aKeepSecs = 0 ⇒ **这一行不动任何东西** ✓（与既有行为逐位相同 ✓）。
        PointMode modeHere = pointMode;
        if (modeHere.aKeepSecs() > 0.0) {
            cutFloor = Math.max(cutFloor, outroDuration - modeHere.aKeepSecs());
        }
        double cutCeil = outroDuration - narrowest - TAIL_GUARD_SECS;
        if (modeHere.aKeepSecs() > 0.0 && cutFloor > cutCeil) {
            // 范围内放不下这个窗口 ⇒ 退化成范围内**一个点**（用户定的"硬用范围内最优点" ✓），
            // 不退回全曲 ✓；日志里会写明这一档收窄到了哪里 ✓。
            cutCeil = cutFloor;
        }
        if (pinnedOutroCutSecs > 0.0) {
            // **甲**：出场点被上级钉住了（见上面那个重载的注释）。候选集缩成这一个点，
            // 于是"计划的出场点"与"交付的出场点"按构造是同一个数。
            cutFloor = pinnedOutroCutSecs;
            cutCeil = pinnedOutroCutSecs;
        }
        double lastLegalCut = outroDuration - TAIL_GUARD_SECS;
        double widest = windows[windows.length - 1];
        // 进场点的许可上界 = 策略给的上界 ∩ **包络真的覆盖到的范围**。
        //
        // 后半句是这次放宽约束时新加的、也是最容易漏的一条：进场侧只扫了前一段
        // （{@link #introScanSecs}），而 {@code intro.durationSecs} 报的是**解码器给的整曲时长**。
        // 策略把上界放宽到「全曲的 70%」之后，如果包络只扫了 30 秒，那么 30 秒之后的每一个
        // 候选都在读**夹住的下标**（{@code Math.min(i, len-1)} → 全是同一格的值）——
        // 于是一整片「看起来一样」的假候选会涌进榜单，强度闸门还会觉得它们都很匹配。
        // 所以：候选只许落在真正扫到的那一段里（再留出强度那一段的余量）。
        //
        // 这一条对桥接关着的情况**没有任何影响**（策略上界就是今天的上界，取 min 之后不变）。
        double coveredSecs = intro.rms.length * HOP_SECS;
        double coverageCeil = Math.max(0.0,
                coveredSecs - INTENSITY_SEAM_WIN_SECS - 2.0 * HOP_SECS);
        double entryCeil = Math.min(bridge.entryCeil(introDuration),
                Math.max(coverageCeil, Math.min(MAX_INTRO_SECS, introDuration * MAX_INTRO_FRACTION)));
        // **自动点位模式：B 侧范围**（2026-10-09 ✓）：稳定 ⇒ 只许在开头 60 s 内 ✓；保守 ⇒ 20 s ✓；
        // 激进 ⇒ bKeepSecs = 0 ⇒ **这一行不动任何东西** ✓（与既有行为逐位相同 ✓）。
        if (modeHere.bKeepSecs() > 0.0) {
            entryCeil = Math.min(entryCeil, modeHere.bKeepSecs());
        }
        // 结构目标（整段只算一次）：出场曲的尾声起点、进场曲真正开始的位置。
        // 见 {@link #STRUCTURE_WEIGHT}——这是「Apple 挑的是结构点、我们量的是能量点」那个缺口的补丁。
        double outroStructure = outroStartSecs(outro);
        double introStructure = introStartSecs(intro);
        // ── 结构锚点：**下界 + 一项打分偏好，不再给上界封顶**（2026-10-05 第二轮）──
        //
        // 历史（为什么要"限范围"）：实测（用户给的那对素材，Apple 是 B 从 45 s 进）
        // 加权版的锚点会被别的项拖着走；而歌词那道门又会把落在句子里的点否掉——两者互相
        // 拉扯，最后跑到 76.7 s（比不加歌词时的 52.2 s 离"45 s 最舒服"更远）。
        // 所以当时把进场范围**收到锚点附近**，"从哪一段进来"就不可能被推翻。
        //
        // **那一版把上界也一起收了，于是"跳过冗余开头"这件事根本做不到**（用户 2026-10-05
        // 第二轮的原话：「很多歌的 intro 是 30 秒纯鼓点 + 1 分钟铺垫，真正该进的地方是
        // 第一个副歌前 8 小节……允许从 intro 里的任意结构边界进入，跳过前面的冗余段」）：
        // 锚点的带宽只有 −{@link #ENTRY_ANCHOR_BACK_SECS} / +{@link #ENTRY_ANCHOR_AHEAD_SECS} 秒，
        // 而"副歌前 8 小节"离"真正开始"常常差几十秒（30 s 纯鼓点 + 60 s 铺垫的那种歌，
        // 真正开始 ≈ 30 s、副歌在 90 s、该进的地方在 74 s）——那条边界连**候选**都不是。
        //
        // 所以现在拆成两条**不同性质**的界线：
        //   · **下界**：只负责"冗余开头段不许进"——从「真正开始」往回
        //     {@link #INTRO_SKIP_FLOOR_SECS} 秒起（用户给的算术 max(0, introStructure − 8.0)）；
        //   · **上界**：交给策略（{@code entryCeil}），**锚点不再封顶**——它是偏好
        //     （打分项见 {@code STRUCTURE_INTRO_SCALE}），不是天花板。
        // 于是"从哪一段进来"由**打分**在整段 intro 的结构边界里选，锚点只是其中一项加分；
        // 而"不许回到纯鼓点里"仍然是一条硬下界。
        //
        // 锚点之前的范围（歌词规则要放宽锚点时用，见下面的"筛不动就放宽锚点重来"）
        double entryFloorRaw = 0.0;
        double entryCeilRaw = entryCeil;
        double entryFloor = 0.0;
        // 锚点只对"**有引子**"的歌有意义：真正开始不到 ANCHOR_MIN 秒的歌（一上来就是正题）
        // 没有可锚的东西，这时不锚——否则会把进场点硬钉在 0 秒附近（实测：合成夹具里
        // "真正开始"是 0，锚点就把进场点从 45 s 拉到 0.22 s）。
        if (bridge.widen && introStructure >= ENTRY_ANCHOR_MIN_SECS) {
            // **锚点的中点仍然对准「真正开始 + 前置量」**（2026-10-05）：`introStartSecs` 是电平
            // 首次越过 70% 分位的那一格，而听感上的"真正开始"要再晚一点（那时电平还在爬）。
            // 用户给的目标是 Apple 在 skydive → 春めく 上的 **A 181 / B 14~15**：按原来
            // `introStructure − 窗口/2` 我们落在 180.0 / 12.5，进场早 2 s 左右；
            // 加上这个前置量之后是 14.5 上下。
            // 半个窗口的偏移**加上限**（2026-10-05）：让中点对准"真正开始 + 前置量"是对的，
            // 但长窗口会把进场一路拖早——实测生产选中 7.5 s 窗口时进场 12.0（Apple 是 14.5），
            // 而 5 s 窗口时是 14.55 ✓。上限 5 s（即最多让 2.5 s）之后，两种窗口都落在 14~16。
            double target = introStructure + INTRO_ANCHOR_LEAD_SECS
                    - Math.min(widest, ENTRY_ANCHOR_MID_CAP_SECS) * 0.5;
            // 下界取**两条里更早的那一条**（"只放宽、不收紧"——与 Bridge.entryCeil 同一条纪律）：
            //   · 用户给的算术：max(0, 「真正开始」− INTRO_SKIP_FLOOR_SECS)；
            //   · 老锚点带的下沿（target − ENTRY_ANCHOR_BACK_SECS）：留着它，是为了保证
            //     这个下界**只会比从前更早、不会更晚**，别人的判据不会因为这一改动变红。
            double lo = Math.max(0.0, Math.min(introStructure - INTRO_SKIP_FLOOR_SECS,
                    target - ENTRY_ANCHOR_BACK_SECS));
            // 范围还是要有意义（下界必须真的在上界之前），否则退回不锚那一档。
            // 用 2.0 s 与原来同一条门槛：更窄的范围里"挑点"退化成"没有选择"。
            if (entryCeil - lo >= 2.0) {
                entryFloor = lo;
            }
        }

        if (entryCeil <= 0.0) {
            return null;
        }

        // ── 从素材派生一个"乐句规则要求的窗口"（2026-10-05）────────────────────
        //
        // 用户：「交叠时长不应该是自动算吗，为啥成为我要调的了」——对，它就该是自动的。
        //
        // 为什么要由乐句规则定：要满足"A 唱完隔 gA 才是 B 的下一句"，同时"融合期里不许有新
        // 句子起头"，交叠必须**短于 B 的乐句周期减去 gA**：
        //     融合结束要落在某个句头前 gA 处 → 进场 = 句头 − gA − w，
        //     而进场之后到融合结束之间不能夹进句头 → w ≤ 周期 − gA。
        // 实测这对曲子：周期 8.0 s、gA 4.7 s → w ≤ 3.3 s，所以取 **8.0 − 4.7 − 0.3 = 3.0 s**。
        // 用户那组候选里本来有 2.5 s（可行），但样式钉死 5.0 s 的场合就完全没有可行窗口。
        //
        // **只减不增**（clamp 到 [1.0, 最短的那个候选]）：用户设的值永远是上界，
        // 被改的只是这一次过渡，而且会写进说明行。
        double derivedWindowSecs = 0.0;
        if (bridge.lyricGate && introSpans != null && introSpans.starts.length >= 3
                && outroSpans != null) {
            double tB = phraseGapOf(introSpans, intro.durationSecs);
            double tA = phraseGapOf(outroSpans, outro.durationSecs);
            if (!Double.isNaN(tB) && !Double.isNaN(tA) && tB > 0.0 && tA > 0.0 && tB > tA) {
                double derived = Math.max(1.0,
                        Math.min(windows[0], tB - tA - 0.3));
                boolean known = false;
                for (int i = 0; i < windows.length; i++) {
                    if (Math.abs(windows[i] - derived) < 1e-6) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    double[] grown = java.util.Arrays.copyOf(windows, windows.length + 1);
                    grown[windows.length] = derived;
                    java.util.Arrays.sort(grown);
                    windows = grown;
                    derivedWindowSecs = derived;
                }
            }
        }

        // 候选点走**结构边界**（见 candidates 的注释）；名字用常量是因为那一侧的名字
        // 还决定用哪个结构地标（出场侧取尾声起点、进场侧取"真正开始"）。
        double[] cutTimes = pinnedOutroCutSecs > 0.0
                ? new double[]{pinnedOutroCutSecs}
                : candidates(outro, cutFloor, cutCeil, SIDE_OUTRO);
        if (cutTimes.length == 0 && pinnedOutroCutSecs > 0.0) {
            return null;
        }
        double[] entryTimes = candidates(intro, entryFloor, entryCeil, SIDE_INTRO);
        if (cutTimes.length == 0 || entryTimes.length == 0) {
            return null;
        }
        // ── 「在歌词范围内的时间都不让选」：**直接从候选集里筛掉**（用户 2026-10-04）──
        //
        // 为什么必须是筛候选，而不是闸门、也不是粗排惩罚——两种都试过，实测都不成立：
        //
        //   · 只当**闸门**：真素材上 96 对候选被挡掉 96 对（计数器「落句内 96」），于是所有
        //     "看歌词"的档位全空，阶梯一路降级到不看歌词的档位，结论一个字都没变
        //     （进场 49.8 s，正好落在那一句的 **87%** 处——就是用户听到的"卡着歌词进来"）。
        //     **闸门空转是这类故障的默认形态**，所以判据必须钉"最终那个点合法"，
        //     不能只钉"这个函数是对的"。
        //   · 只当**粗排惩罚**：有效，但它本质是"排序"不是"约束"——合法点对少于
        //     {@link #SHORTLIST} 对时非法点照样进决赛圈，那时又回到上一条的困境。
        //
        // 筛掉之后这两件事都不需要了：非法点位从头到尾没被评过分，也不占决赛圈的位置。
        //
        // 合法位置太少时**不筛**：硬筛会让一首"整段都在唱"的歌一个候选都不剩
        // （那不是"排开歌词"，那是没有点位）。此时退回今天的做法，并在说明里如实写明
        // 这条规则没能行使——静默降级在这个包里是不允许的。
        // ── 「融合**结束**那一刻对齐 B 的句头」：从候选集里筛掉做不到的落点 ────────
        //
        // 用户 2026-10-05：「不是你直接入场点前移不就行 我们不是会考虑融合窗口吗
        // 让他**刚好滑完**就是那个点不就行」。见 {@link #ENTRY_HEAD_TOL_SECS}。
        //
        // 这一条**取代**了上一版的三段（依据都留在 git 历史与 docs 里）：
        //   · 「进场点只许落在换气里」——用户现在明确要求进场点**前移**，它必然落在
        //     前一句的尾巴里；真正不许落错的是"融合结束"那一刻；
        //   · 「从歌词栅格派生一个短窗口」——对齐之后窗口不需要被压短，用户设的交叠照用；
        //   · 「句头增益 ≥ 0.5」——等功率下对齐时它恒等于 1.0（w/w），成了同义反复。
        int lyricLegalPoints = -1;
        int lyricScanPoints = entryTimes.length;
        boolean lyricAnchorRelaxed = false;
        // **A 自己的乐句间隔 gA**（末几句间隔的中位数）：B 的第一句要在融合结束之后隔 gA 才起头。
        // 见 headFollowsPhraseGap——它取的是素材属性，与具体出场点无关，所以筛候选时能直接用。
        double lyricPhraseGap = phraseGapOf(outroSpans, outro.durationSecs);
        if (bridge.lyricGate && introSpans != null && introSpans.starts.length > 0) {
            // 两级筛（2026-10-05 判据逼出来的）：
            //   ① 对齐句头 **且** 那个句头是"这一段的起头"（最严）；
            //   ② 只要求对齐句头（退一步）；
            //   ③ 都凑不够 → 不筛，按没有歌词的做法走（说明行如实写明）。
            // 为什么不能只留①：平坦素材（电平没有起伏）上没有任何句头算"起头"，于是整条
            // 规则筛到 0 个落点、直接失效——判据 theAnchorGivesWayWhenItHidesTheAlignedPoint
            // 与 theFirstStageItselfCarriesTheLyricRules 当场红过。
            double[] kept = pickAligned(intro, introSpans, entryTimes, windows,
                    lyricPhraseGap, true);
            if (kept == null) {
                kept = pickAligned(intro, introSpans, entryTimes, windows,
                        lyricPhraseGap, false);
            }
            if (kept != null) {
                entryTimes = kept;
                lyricLegalPoints = kept.length;
            } else {
                // **三条都凑不够 → 不筛**，按没有歌词的做法走（说明行如实写明）。
                //
                // 这里原本还有一条"放宽锚点重来一次"（为旧规则的隐患 2 加的）。2026-10-05
                // 换成"隔 gA"之后它反而有害：限制变成 `交叠 < B 的乐句周期 − gA`
                // （实测 B 每 8 s 一句、gA 4.5 s → 交叠必须短于 3.5 s），固定交叠 5 s 时
                // 范围里**一个合格点都没有**，放宽锚点于是把进场点扔到 B 的前奏里
                // （实测 3.7 s / 1.3 s）。宁可如实降级，也不要跑到没人要的地方去。
                lyricScanPoints = entryTimes.length;
            }
        }

        // ── 出场侧的对称规则：**A 的乐句在"融合结束"那一刻正好唱完** ──────────────
        //
        // 用户 2026-10-05：「现在考虑了 B 的歌词是否唱完，你还得考虑 A 的啊，现在从 A
        // 半途跳出去了」。判定点同样是 {@code cut + window}（A 增益归零、真正消失的那一刻）。
        // 与进场侧合起来：同一个真实时刻，A 刚唱完一句、B 刚起一句。
        int outroLegalPoints = -1;
        if (bridge.lyricGate && outroSpans != null
                && outroSpans.starts.length > 0) {
            int keep = 0;
            for (int i = 0; i < cutTimes.length; i++) {
                if (cutEndsPhrase(outroSpans, cutTimes[i], windows)) {
                    keep++;
                }
            }
            if (keep >= MIN_LYRIC_HEAD_CELLS) {
                double[] kept = new double[keep];
                int n = 0;
                for (int i = 0; i < cutTimes.length; i++) {
                    if (cutEndsPhrase(outroSpans, cutTimes[i], windows)) {
                        kept[n++] = cutTimes[i];
                    }
                }
                cutTimes = kept;
                outroLegalPoints = keep;
            }
        }



        // ── 「买时间」那一档的候选（2026-10-05）：尾奏循环唯一的用武之地 ────────────
        //
        // 这一档要办的事是「尾奏里那段干净 vamp 重复一遍，把这次过渡缺的时间补出来」。
        // 它此前**一次都走不到**，因为候选上界（{@code cutCeil}）与下面逐窗那句
        // {@code cut + window > lastLegalCut → continue} 把唯一需要它的那个点位——
        // **vamp 的终点**——在打分之前就丢掉了：尾奏放得下窗口时永远不需要循环，
        // 而"放不下"恰恰是被丢掉的那个充分条件。判据
        // {@code AutoCueOutroLoopBuyTimeTest} 把这件事钉成事实。
        //
        // 形状（三条边界都是**决定**，不是顺手写的，改之前请先读这三条）：
        //
        //   ① **点只有一个，就是 vamp 的终点**（{@code vamp.to}）。不是"随便挑个靠后的点"：
        //      渲染路径（{@code AutoCueTransition.prepareLoopOutgoing}）讲的顺序是
        //      "A 自然播到 vamp 起点 → 循环段播完第一遍（= vamp 终点）→ 从这里开始与 B
        //      交叉淡化"，于是**计划里那个出场点的含义就是 vamp 终点**。写别的值，
        //      说明行里的「丢尾」与买时间的算术（{@code 全曲 − 出场点}）讲的就不是
        //      真的会发生的事——那是这个包里最不能出的错（报告必须描述事实）。
        //      而且它**天然落在小节线上**（{@code outroVampSpan} 只收小节线对齐、整数
        //      小节的段，{@code to = from + 整数小节 × 小节长}），所以它不需要、也不该
        //      再吸拍——见下面吸拍那一处的注释。
        //
        //   ② **只在"放得进窗口"的区间之外加**（{@code vamp.to > cutCeil}）。{@code cutCeil}
        //      是"最窄的那个窗口也放得下"的上界，所以这个点与**任何**候选窗口配对时
        //      都必然越过 {@code lastLegalCut}——它挤不进"窗口放得进"那一支，
        //      老路径的候选集、名次、结论在句法上就不可能被它动到（"关掉特性 ⇒ 逐位
        //      相同"这条纪律靠的就是这一条，位指纹判据 {@code AutomixBitExactReferenceTest}
        //      钉着）。参考对（skydive → 春めく，实测：全曲 190.3 s、vamp 153.3→168.3 s、
        //      窗口 7.5 s）上 vamp 终点 + 窗口 = 175.8 s ≤ 全曲 − 退出余量 = 188.3 s
        //      ⇒ 它落回"放得进窗口"那一侧，<b>这里连一个点都不会加</b>：改动对它逐位中性
        //      （五部分哈希前后相同，见那条判据的注释）。
        //
        //   ③ **出场点自己的上界仍然是 {@code lastLegalCut}**（{@code 全曲 − 退出余量}）。
        //      这次放宽的**只有**"窗口必须放得进"这一条，不是"出场点可以越过退出余量"。
        //      两件事分开写：将来真要放宽第二条时，得知道动的是哪一行。
        AutoCue.VampSpan vamp = outroVampSpan(outro);
        int buyTimeCutIndex = -1;
        if (vamp != null && vamp.to > cutCeil && vamp.to <= lastLegalCut) {
            cutTimes = java.util.Arrays.copyOf(cutTimes, cutTimes.length + 1);
            cutTimes[cutTimes.length - 1] = vamp.to;
            buyTimeCutIndex = cutTimes.length - 1;
        }

        // ---- ① 粗排：只看与窗口无关的那几项局部性质，缩到 SHORTLIST 对 ----
        // 每一处的强度**先算一次**：内层是「96 对点位 × N 个窗口」，逐对去算整窗均值
        // 会把它变成几万次小循环（和 mode 那次分配榜第一是同一类问题）。
        // 强度只取决于点位，与窗口无关，所以按点位预算一张表就够。
        double[] cutIntensity = null;
        double[] entryIntensity = null;
        // 这一档策略用的度量（0 = 点值，> 0 = 往后看这么多秒）；吸到拍上重算时要用同一个。
        double metricAhead = 0.0;
        if (bridge.measure) {
            // 用哪个度量按策略定：第一级（MEASURE）用点值——它要与"加这一项之前"逐位相同；
            // 第二级（DEFAULT）用"往后看 6 秒"——要的是进在**持续**高能段的起点
            // （见 INTENSITY_SUSTAIN_SECS 里那个实测缺口）。
            double ahead = bridge.sustain ? INTENSITY_SUSTAIN_SECS : 0.0;
            metricAhead = ahead;
            cutIntensity = new double[cutTimes.length];
            for (int c = 0; c < cutTimes.length; c++) {
                cutIntensity[c] = intensityAhead(outro, cutTimes[c], ahead);
            }
            entryIntensity = new double[entryTimes.length];
            for (int e = 0; e < entryTimes.length; e++) {
                entryIntensity[e] = intensityAhead(intro, entryTimes[e], ahead);
            }
        }
        // 粗排的「走向」两项**保持原样**（20 ms 尺度的 {@link #slope} 符号）。
        //
        // 这一处原来与精细九项吃的是同一把 `slope`，这次只把**精细那一处**换成了段落尺度的
        // {@link #direction}——理由是实测出来的、账记在 {@link #slope} 的注释里：
        // 把粗排这一处也换成段落尺度之后，它的零代价带从"一格宽"变成"±一小节宽"，
        // 于是"跨在台阶上"的点也开始拿满分，而粗排的整窗连续性项本来要挡的就是那种点
        // （实测：两条**质量**判据 {@code AutoCueTest} 当场红）。
        // 粗排这一项的角色是**排序/过滤**（挑哪 96 对进决赛圈），"接缝那一刻在起还是在收"
        // 这件事现在由精细九项用段落尺度决定。
        double[] entryScore = new double[entryTimes.length];
        int[] entryIndex = new int[entryTimes.length];
        for (int e = 0; e < entryTimes.length; e++) {
            entryIndex[e] = intro.indexAt(entryTimes[e]);
            double rising = Math.max(0.0, slope(intro, entryIndex[e]));
            entryScore[e] = 0.8 * (1.0 - Math.min(1.0, rising * SLOPE_NORM))
                    + 0.5 * (1.0 - intro.onset[entryIndex[e]]);
        }
        // 前缀和：窗口均值查表即得（粗排要对几百万对做，逐格累加会慢一个量级）
        double[] prefixOutro = prefixSums(outro.rms);
        double[] prefixIntro = prefixSums(intro.rms);
        // 低频那一条带的前缀和：粗排也要看得见「两边的低频都轰」（2026-10-02 补）。
        //
        // 为什么非补不可：粗排只留 96 对进决赛圈，而它原先**完全不看低频**——
        // 判据 {@code AutoCueLowEndTest#planningAvoidsTheMuddyLowEnd} 当场把这件事故照出来了：
        // 引子前 10 秒在轰、之后安静，两边其它性质一模一样时粗排打成平手，
        // 于是「先进堆」的那批（也就是最靠前的、正好在轰的那段）把安静那段挤出了决赛圈，
        // 精细评分再准也没用。这里用**两侧窗口低频均值之积**当惩罚（O(1) 查表），
        // 量级与 连续性/走向 那几个惩罚项相当。
        double[] prefixLowOutro = outro.band == null ? null : prefixSums(lowBandColumn(outro));
        double[] prefixLowIntro = intro.band == null ? null : prefixSums(lowBandColumn(intro));
        float[] voiceOutro = voiceMask(outro);
        float[] voiceIntro = voiceMask(intro);
        double[] prefixVoiceOutro = voiceOutro == null ? null : prefixSums(voiceOutro);
        double[] prefixVoiceIntro = voiceIntro == null ? null : prefixSums(voiceIntro);
        int coarseHops = Math.max(1, (int) Math.round(widest / HOP_SECS));

        // **进场侧的窗口均值只跟「进场点」有关**，与出场点无关（2026-10-03，性能）：
        // 原来三个 `windowMean` 都写在内外双循环的最里面，于是每个出场点都把同一批数
        // 重算一遍。提到循环外只是少算几遍同一个和——每个和自身还是同一段算术、同样的
        // 顺序，所以**结果逐位相同**，只是粗排少做大量重复劳动。
        // ── 粗排也要看见「这一对能不能满足乐句规则」（2026-10-05）────────────────
        //
        // 与强度闸门当初踩的是同一个坑，而且这次更隐蔽：**乐句规则与窗口是耦合的**
        // （实测：B 每 8 s 一句、gA 4.7 s → 交叠必须短于 3.3 s 才可能满足），于是
        // "合格的那一对"往往只用**短窗口**才成立。粗排只留 96 对、又完全不看窗口，
        // 那些合格对会被"不合格但粗排分高"的挤出去 → 精细评分里合格的档位全空 →
        // 阶梯降级 → 又回到"进在 B 正在唱的时候"（用户实测的 51.7 s）。
        final boolean[] cutFeasible;
        final boolean[] entryFeasible;
        if (bridge.lyricGate) {
            final LyricSpans oSpans = outroSpans;
            final LyricSpans iSpans = introSpans;
            final double gap = lyricPhraseGap;
            cutFeasible = new boolean[cutTimes.length];
            for (int c = 0; c < cutTimes.length; c++) {
                cutFeasible[c] = oSpans == null || cutEndsPhrase(oSpans, cutTimes[c], windows);
            }
            entryFeasible = new boolean[entryTimes.length];
            for (int e = 0; e < entryTimes.length; e++) {
                entryFeasible[e] = iSpans == null
                        || headFollowsPhraseGap(iSpans, entryTimes[e], windows, gap,
                                ENTRY_HEAD_TOL_SECS, false);
            }
        } else {
            cutFeasible = null;
            entryFeasible = null;
        }
        final int[] entryIdx = entryIndex;
        final double[] meanB = new double[entryTimes.length];
        final double[] lowB = new double[entryTimes.length];
        final double[] voiceB = new double[entryTimes.length];
        for (int e = 0; e < entryTimes.length; e++) {
            meanB[e] = windowMean(prefixIntro, entryIdx[e], coarseHops, intro.rms.length);
            lowB[e] = prefixLowIntro == null ? 0.0
                    : windowMean(prefixLowIntro, entryIdx[e], coarseHops,
                            prefixLowIntro.length - 1);
            voiceB[e] = prefixVoiceIntro == null ? 0.0
                    : windowMean(prefixVoiceIntro, entryIdx[e], coarseHops,
                            prefixVoiceIntro.length - 1);
        }

        java.util.PriorityQueue<double[]> heap = new java.util.PriorityQueue<double[]>(
                Math.min(SHORTLIST, cutTimes.length * entryTimes.length),
                new java.util.Comparator<double[]>() {
                    @Override
                    public int compare(double[] a, double[] b) {
                        return Double.compare(b[0], a[0]);      // 大的先出去（堆顶 = 最差）
                    }
                });
        // **下界剪枝**（2026-10-03，性能）：进场侧最小的那一项。
        //
        // 粗排分是「五项之和」，其中三项（连续性 |meanA−meanB|、低频惩罚、人声惩罚）**恒非负**，
        // 所以 `coarse ≥ cutScore + minEntryScore` 是这个候选的**真下界**。堆一旦装满
        // （96 个），只要这个下界已经不小于堆顶（= 当前第 96 名），这一整排出场点
        // 就不可能进榜——严格不等号那一条规则（`coarse < peek`）决定了「不小于」时不会替换。
        // 于是可以整排跳过，**结果一个候选都不会变**（不是近似、不是概率剪枝）。
        double minEntryScore = Double.MAX_VALUE;
        for (int e = 0; e < entryScore.length; e++) {
            minEntryScore = Math.min(minEntryScore, entryScore[e]);
        }
        // **「买时间」那一对单独一个堆**（第二队，见下面两段注释）：它与"放得进窗口"的那些
        // 对点位**争的是同一批 96 个名额**，混在一个堆里就会把老成员挤出去——于是老路径的
        // 结论会被"多了一个新点"这件事本身改掉，而这正是位指纹要挡的。
        // 容量与比较器跟老堆一模一样：每个候选出场点都只带 {@value #SHORTLIST} 个进场点
        // 进决赛圈，买时间这个点没有理由特殊（让它带 700 个进场点，成本就比别的点多几倍，
        // 而多出来的那些点只是在跟它自己争同一个出场点）。
        java.util.PriorityQueue<double[]> buyTimeHeap = new java.util.PriorityQueue<double[]>(
                Math.min(SHORTLIST, entryTimes.length), heap.comparator());
        // ── 粗排也要看见「结构 / 小节 / 新颖」（2026-10-06）──────────────────────────
        //
        // 与「强度闸门」「乐句规则」踩的是**同一个坑**（见上面那两段与
        // {@link #COARSE_GATE_PENALTY}）：粗排那五项全是**接缝局部**性质
        // （连续性 / 出场走向 / 进场走向 / 低频 / 人声），一项都不看「这一刀切在不在
        // 音乐上的结构边界上」——而精细评分里那三项是**加分**，
        // {@link #STRUCTURE_OUTRO_SCALE} 放大之后出场侧一项就能顶到 0.15 分。
        // 于是"精细评分的最优解"可能压根进不了前 {@value #SHORTLIST} 对。
        //
        // 实测（参考对 skydive → 春めく、探针复算）：把"走向"改成段落尺度之后，
        // 精细全网格的冠军是 (出场 181.4455 + 进场 15.14)（吸拍后 14.9983 ≈ Apple 的 15.0），
        // 可它被粗排挡在 **233/870**（"不吃粗排"的冠军 17.28 是 278/870）——同一时刻生产落到
        // 的是 15.52 那一对（粗排第 38 名）。精细评分再准也轮不到它们。
        //
        // 做法：**把出场点那一侧「只与点位有关」的加分按与精细评分同一把尺子减进粗排代价**
        // （{@link #coarsePointBonus}，O(1) 每点、进内层循环只是一次加法）。
        // 关键是那个尺度：粗排那几项本来就是**精细权重未归一**的版本（走向 0.8、
        // 少切一点 0.35……它们相加正好是 {@link #WEIGHT_SUM}），而精细里的结构/小节/新颖是
        // **不参与归一**、直接加在总分上的——所以它们进粗排要乘 {@link #COARSE_FINE_SCALE}。
        //
        // ── 为什么**只补出场侧、不补进场侧**（这一条是量出来的，不是省事）──────────
        //
        // 粗排是**窗口无关**的一层（它连整窗均值都只用最宽窗口近似一次）。
        // 出场侧的结构项量的是**出场点自己**（与窗口无关）、小节线与色度新颖也都在点位上；
        // 而进场侧的结构半项量的是**融合中点 = 进场点 + 窗口/2**——它依赖窗口，
        // 粗排这里没有这个量。拿"最宽窗口的中点"去猜它，等于让粗排替精细评分预判一个
        // 它算不准、而且第二级还被放大 {@value #STRUCTURE_INTRO_SCALE} 倍的量。
        // 实测代价很具体（{@code AutoCueIntroSkipTest} 的夹具）：B 的"真正开始"在 30 s，
        // 进场候选 32 s（= 铺垫段起点）与 80 s（= 该进的副歌前 8 小节）的加点差 0.043，
        // 乘完尺度是 0.33——足够把 32 s 那一族顶进决赛圈，而精细评分里那条 5× 的结构项
        // 会让它直接赢，于是"跳过 1 分钟铺垫"这条用户要求被推翻（判据当场红）。
        // 进场侧**只补不依赖窗口的那两项**（小节线、色度新颖）。
        //
        // 还有一条纪律：**不做成"闸门"**（不给不合规的对加 {@link #COARSE_GATE_PENALTY}）。
        // 这三项在精细评分里是**加分**、不是否决，而且粗排只取其中**点位上就算得出来**的那半：
        // 满额 = 结构出场半项 0.06×5×0.5 = 0.15、小节线 0.05、色度新颖 0.02，合计 0.22，
        // 折算后 ≈ 1.7——严格小于那个惩罚（10）。
        // （进场侧那一半结构项**不进粗排**：它量的是融合中点 = 进场点 + 窗口/2，见上。）
        // （实测踩过：真做成闸门时，{@code AutoCueIntensityWiringTest} 那个合成夹具里
        // B 的 {@code structureIntroStartSecs = 0} 让"进场 0 s"那一族拿到 0.02 的加分，
        // 副歌那一族全被 +10 挡在决赛圈外，第二级再也找不到 B 的副歌。）
        //
        // 代价：粗排多 O(候选点数) 次 O(1) 计算（参考对 = 30 次）与内层循环里一次加法，
        // **没有窗口或整窗回归**；粗排仍是百万级循环里最便宜的那一层
        // （{@code AutomixPerfBudgetReferenceTest} 量着）。
        final double[] cutAlign = new double[cutTimes.length];
        final double[] entryAlign = new double[entryTimes.length];
        {
            // 小节线 / 新颖那两项在精细评分里是"两侧取平均"（{@code barBonus} /
            // {@code noveltyBonus}），所以这里的权重也按**可用的侧数**摊——与那边同一把尺子。
            boolean barOutro = barPhase(outro) >= 0;
            boolean barIntro = barPhase(intro) >= 0;
            int barSides = (barOutro ? 1 : 0) + (barIntro ? 1 : 0);
            double barShare = barSides == 0 ? 0.0 : BAR_WEIGHT / barSides;
            boolean novOutro = outro.structureOutroStartSecs >= 0f;
            boolean novIntro = intro.structureIntroStartSecs >= 0f;
            int novSides = (novOutro ? 1 : 0) + (novIntro ? 1 : 0);
            double novShare = novSides == 0 ? 0.0 : NOVELTY_WEIGHT / novSides;
            for (int c = 0; c < cutTimes.length; c++) {
                cutAlign[c] = coarsePointBonus(outro, cutTimes[c], cutTimes[c], outroStructure,
                        STRUCTURE_OUTRO_SCALE,
                        novOutro ? outro.structureOutroStartSecs : -1.0, barShare, novShare);
            }
            // 进场侧：结构半项与窗口耦合（见上），只补点位上就有的那两项。
            for (int e = 0; e < entryTimes.length; e++) {
                entryAlign[e] = coarsePointBonus(intro, entryTimes[e], entryTimes[e], -1.0, 0.0,
                        novIntro ? intro.structureIntroStartSecs : -1.0, barShare, novShare);
            }
        }
        // 「合规门槛」那一档的标尺：本曲**实际能达到**的最大加分，以及它足不足够大
        // （见 {@link #COARSE_ALIGN_MIN}：只有一条色度边界那种 0.01 量级的"加分"不该开门）。
        double coarseMaxAlign = 0.0;
        for (int i = 0; i < cutAlign.length; i++) {
            coarseMaxAlign = Math.max(coarseMaxAlign, cutAlign[i]);
        }
        for (int i = 0; i < entryAlign.length; i++) {
            coarseMaxAlign = Math.max(coarseMaxAlign, entryAlign[i]);
        }
        final boolean coarseGateOn = coarseMaxAlign >= COARSE_ALIGN_MIN;
        for (int c = 0; c < cutTimes.length; c++) {
            // 除了"进哪个堆"，这一行下面的一切都与加这个特性之前**逐字相同**
            // （{@code c != buyTimeCutIndex} 时 {@code into == heap}，同一个对象、同一个
            // 命令序列）——"窗口放得进"那一支的候选集与名次因此逐位不变。
            java.util.PriorityQueue<double[]> into = c == buyTimeCutIndex ? buyTimeHeap : heap;
            int ci = outro.indexAt(cutTimes[c]);
            double tailDrop = Math.max(0.0, outroDuration - (cutTimes[c] + widest));
            double falling = Math.max(0.0, -slope(outro, ci));
            double cutScore = 0.8 * (1.0 - Math.min(1.0, falling * SLOPE_NORM))
                    + 0.5 * (1.0 - outro.onset[ci])
                    + 0.35 * (1.0 - tailTerm(tailDrop, outroDuration));
            if (into.size() >= SHORTLIST
                    && cutScore + minEntryScore >= into.peek()[0]) {
                continue;
            }
            double meanA = windowMean(prefixOutro, ci, coarseHops, outro.rms.length);
            double lowA = prefixLowOutro == null ? 0.0
                    : windowMean(prefixLowOutro, ci, coarseHops, prefixLowOutro.length - 1);
            double voiceA = prefixVoiceOutro == null ? 0.0
                    : windowMean(prefixVoiceOutro, ci, coarseHops, prefixVoiceOutro.length - 1);
            for (int e = 0; e < entryTimes.length; e++) {
                // 粗排用的「连续性」是**整窗均值之差**，不是交接点那一格之差。
                //
                // 这一处原本用的是点值（旧版打分的口径），而精细评分看的是整窗——
                // 两把尺子不一致的后果是实测出来的：粗排按点值把「两边刚好都在各自
                // 响度台阶上」的位置排到最前（那种位置在交接点上完美，整窗却是一路
                // 塌下去的：A 已经掉下去了、B 还没起来），真正的候选根本没进前 96，
                // 精细评分再准也没用（2026-09-26 的 AutoCueTest 就是这么红的）。
                double continuity = Math.abs(meanA - meanB[e]);
                double lowClash = prefixLowIntro == null ? 0.0
                        : LOW_END_COARSE_WEIGHT * lowA * lowB[e];
                double voiceClash = prefixVoiceIntro == null ? 0.0
                        : VOICE_COARSE_WEIGHT * voiceA * voiceB[e];
                // 结构 / 小节 / 新颖这一族的加分：按 {@link #COARSE_FINE_SCALE} 折算进粗排
                // （粗排分是代价，所以是**减**），再按 {@link #COARSE_ALIGN_SHARE}
                // 那一道"合规严格优先"兜底——见上面那一段注释。
                double coarse = continuity + cutScore + entryScore[e] + lowClash + voiceClash;
                double align = cutAlign[c] + entryAlign[e];
                coarse -= COARSE_FINE_SCALE * align;
                if (coarseGateOn && align < COARSE_ALIGN_SHARE * coarseMaxAlign) {
                    coarse += COARSE_GATE_PENALTY;
                }
                // ── 粗排也必须看见强度闸门（2026-10-04，实测踩到的坑）────────────
                //
                // 闸门是"否决"，可它只在**能进前 96 的对点位**上行使否决权。而粗排那五项
                // （连续性 / 出场走向 / 进场走向 / 低频 / 人声）**一项都不看强度**——
                // 于是"两端都嗨"的那些点对可能压根进不了候选集，闸门永远看不到它们，
                // 最后只能如实报「扫到的范围里没有接得上的落点」。
                //
                // 实测（判据里的合成夹具）：合法点对 (出场 80 s, 进场 55 s) 的强度差是
                // **0.000**，而粗排把它们排到了 96 名之外，最终选出来的是差 0.223 的一对。
                //
                // 做法：给超标的那一对加一个**远大于粗排量级**的惩罚（粗排五项的上界
                // 加起来也不到 5），于是"合规的"严格优先、"不合规的"只在合规的凑不满
                // 96 对时进来兜底。桥接关着时这一行不生效 → 逐位不变。
                if (bridge.score) {
                    // 粗排也要连续地看见强度（不只是否决）：分数是 0..1 的贴合分，
                    // 所以惩罚写成 {@code (1 − 贴合) × 权重}。没有这一条，"两端都嗨"的点对
                    // 可能压根进不了前 96（实测：合法点对差 0.000 却被排在 96 名之外）。
                    coarse += COARSE_INTENSITY_WEIGHT
                            * (1.0 - intensityTerm(Math.abs(cutIntensity[c] - entryIntensity[e])));
                }
                if (bridge.veto
                        && Math.abs(cutIntensity[c] - entryIntensity[e]) > bridge.vetoGap) {
                    coarse += COARSE_GATE_PENALTY;
                }
                if (cutFeasible != null
                        && (!cutFeasible[c] || !entryFeasible[e])) {
                    coarse += COARSE_GATE_PENALTY;
                }
                if (into.size() < SHORTLIST) {
                    into.add(new double[]{coarse, c, e});
                } else if (coarse < into.peek()[0]) {
                    into.poll();
                    into.add(new double[]{coarse, c, e});
                }
            }
        }

        // ---- ② 精细：每一对 × 每一个候选窗口，算整窗的七项 ----
        Score best = null;
        // P6：叠唱不超标（{@link #VOCAL_GATE_SECS}）的候选里最好的那个。软打分只给人声
        // 10% 的权重，别的项一偏心就会把「两边都在唱」的点选出来——所以这里单独记一份。
        Score bestClean = null;
        // 丢尾不超标（{@link #MAX_TAIL_DROP_FRACTION}）的候选里最好的那个：这一条治的是
        // 「半路把歌掐掉」——那种候选别的项再漂亮也不能选（见常量的说明）。
        Score bestNearEnd = null;
        // 两个硬约束（丢尾 + 叠唱）**同时**满足的候选里最好的那个。
        Score bestGated = null;
        // 三个硬约束（+ 强度）同时满足的候选里最好的那个。
        Score bestBridgeGated = null;
        // 丢尾 + 强度满足、但叠唱超标的那个（放松叠唱）。
        Score bestRelaxVocal = null;
        // ── 「排开歌词」那一档（用户 2026-10-04：「我要求排开歌词作为最高优先级」）──
        //
        // 它排在最前面：融合中点不许卡在某一句正唱着的时候。放松顺序里它**最后**被放松
        // ——别的约束（叠唱/强度）都可以先让路，歌词不让。
        Score bestLyricTop = null;          // 四个约束全满足
        Score bestLyricNoVocal = null;      // 放松叠唱，但保留歌词 + 强度 + 丢尾
        Score bestLyricNoIntensity = null;  // 放松强度，但保留歌词 + 丢尾
        /** 句头增益达不到（窗口短不下来）、但乐句栅格接得上的那一档。 */
        Score bestLyricLoose = null;
        // 次优候选（2026-10-02 加）：note 要说清「为什么不是别的候选」，
        // 而那个「别的候选」就是它。只留一个，够回答用户会问的那句话。
        Score second = null;
        // 前 k 个**互不相同**的候选（W4 后半的第一半）：给「准备完成之后、过渡开始之前」
        // 那一次重选留出余地（将来按设备类型/用户设置/解码后的真实素材再挑一次）。
        // 去重按点位区分：与已收下的任何一个点位都差不到半拍就不算「另一个候选」——
        // 相邻 20 ms 的点位是同一个点，收进来只会把 k 个名额占满。
        java.util.List<Score> ranked = new java.util.ArrayList<Score>();
        int evaluated = 0;
        // 被粗排选中的**点位对**数（与 {@code evaluated} 分开：上界按最窄窗口生成，
        // 长窗口要逐窗判合法性，所以不是每一对都评了每一个窗口，日志里那个
        // 「N 个窗口 × M 对点位」的 M 用这个真值，别用 evaluated 去除）。
        // 第二队（买时间）那一队也在里面：它与老堆走的是同一套粗排漏斗，
        // 而这一行要回答的是"这一次挑点到底看过多少对"，漏了它就成了谎话。
        int pairs = 0;
        // 每个候选窗口各自的**最好成绩**：只打一行日志，但它回答的是用户会问的那个问题
        // ——「他真的会自己调交叠时长吗，我怎么一直看到 4 秒」。没有这一行，用户只能看到
        // 最后选中的那个数，分不清"分析评估过别的窗口但都更低"和"压根没评估别的窗口"。
        double[] bestPerWindow = new double[windows.length];
        java.util.Arrays.fill(bestPerWindow, Double.NEGATIVE_INFINITY);
        int[] lyricBlocked = new int[4];        // 见下方歌词闸门：谁把候选挡掉的（诊断用）
        // ── 第二队：**放不进窗口、但买得起时间**的那一对（上面 buyTimeHeap）──────────
        //
        // 它排在老堆那些对点位**后面**处理，理由还是"放得进窗口"那一支必须逐位不变：
        // 老堆的成员、弹出顺序、每一对进来的分数与它更新过的每一个 best* 都一个字节
        // 不许动，所以新点不跟它们争那 96 个名额，而是等它们全部评完之后再评。
        // 于是老路径的结论只可能被"买时间这一对真的更好"改写——那正是这次要的功能
        // （它照样走同一套打分、同一套硬约束阶梯，没有任何特权）。
        // 买时间堆为空（没有 vamp、或 vamp 终点仍落在放得进的区间里）时，这一行下面
        // 与加这个特性之前**逐字相同**。
        while (!heap.isEmpty() || !buyTimeHeap.isEmpty()) {
            double[] item = heap.isEmpty() ? buyTimeHeap.poll() : heap.poll();
            int c = (int) item[1];
            int e = (int) item[2];
            pairs++;
            for (int w = 0; w < windows.length; w++) {
                // 这一对点位放到这个窗口里会切到完整结尾（窗口伸到曲子外面）。
                // 候选上界是按**最窄**窗口生成的，所以长的窗口必须在这里逐窗判——判据
                // {@code AutoCueBeatTest} 里那条「出场点不许等于完整结尾」钉的就是这件事。
                //
                // **这里是这次改动唯一动过的那句话**：原来无条件 {@code continue}（丢掉），
                // 现在先问一句"这一对买得起时间吗"——尾奏里量得出可循环的 vamp、并且
                // 决策层在这一对点位上给出的计划正是"买时间"（口径见 {@link #loopIsWired}）
                // 时**照常打分**；其余情形仍然丢掉，与原实现逐位相同（判据
                // {@code AutoCueOutroLoopBuyTimeTest} 钉的是这一档现在真的能走到）。
                //
                // 判定不另写一套算术，而是直接问决策层（见 {@link #buysTimePair}）：
                // "尾奏放不下窗口"这件事的定义就在 {@code planOutroLoop} 里，两处各写一遍
                // 必然分叉——这个包的纪律是"同一件事只有一把尺子"。
                if (cutTimes[c] + windows[w] > lastLegalCut
                        && !buysTimePair(outro, intro, vamp, cutTimes[c], windows[w])) {
                    continue;
                }
                Score score = evaluate(outro, intro, cutTimes[c], entryTimes[e], windows[w])
                        .withLineGap(lineGapTerm(outroSpans, introSpans,
                                cutTimes[c], entryTimes[e], windows[w]));
                score = score
                        .withStructure(structureTerm(cutTimes[c], outroStructure,
                                // **对准"交接点"= 进场点 + 整个窗口**（2026-10-07 用户给了权威口径）：
                                // 「录屏音频里量出来的不一定对，B 的 15 s 是**终点**」✓
                                // —— 也就是"B 完全接管"的那一刻，而它应当落在 B 的**真正开始**上
                                // （实测 春めく 真正开始 15.06 s ≈ 他那个 15.0 ✓ 吻合得非常好）。
                                // 旧写法是 `entry + 窗口 × 0.5`（**中点**对准结构点 + 2.0 s 前置量 ✗）：
                                // 那是把 15.0 当"起点"读出来的口径 ⇒ 交接点整整晚一个窗口
                                // （实测：进场 15.02 ⇒ 交接 17.49，离 B 的小节线 1.98 拍 ✗）。
                                // **交接点 = 进场 + 整个窗口**；它落在
                                // `introStructure − 窗口/2` 上（2026-10-07，goal 第 2 步）：
                                // 也就是**淡化在 B 的结构点之前半个窗口收尾** ✓ ——
                                // 上升段（实测 春めく 16.5 s 起）整个落在 B 独占里 ✓，
                                // 而不是被压在交叉淡化里 ✗（那正是用户听到的"差点意思"）。
                                // 参考对验算：16.32 − 1.26 = 15.06 ⇒ 吸到 B 的拍网格 **14.975** ✓
                                // ⇒ 与用户给的权威读数 15.0 差 **25 ms** ✓（±0.1 内 ✓）。
                                entryTimes[e] + windows[w] * 1.5,
                                introStructure,
                                // **出场侧两侧（两级）都放大**：Apple 的实测（181.1 s）在乐句线上，
                                // 而九项里那个「在收」偏好（当时是 20 ms 尺度）会把出场点拉到
                                // 它前面 1.7 s——见 STRUCTURE_OUTRO_SCALE。
                                // （2026-10-06 补记：九项那一项已经换成**段落尺度**，当初要压的
                                // 那个偏差口径不在了；倍数本身按上一步的标定**原样保留**
                                // ——没有量测证明它现在的幅度不对，就不动它。）
                                STRUCTURE_OUTRO_SCALE,
                                // **进场侧的 5× 只在第二级生效**：第一级（今天那条路）
                                // 的行为必须逐位不变——参考对上它的进场点 15.0 s 本来
                                // 就落在春めく"真正开始"(16.3 s) 附近，用不着锚。
                                bridge.widen ? STRUCTURE_INTRO_SCALE : 1.0))
                        .withBar(appleBarWindowAdd(barBonus(outro, intro, cutTimes[c], entryTimes[e]),
                                        windows[w]))
                        .withNovelty(noveltyBonus(outro, intro, cutTimes[c], entryTimes[e]))
                        // 旋律永远第一位（2026-10-06 第一阶段工作一）：用户原话
                        // 「应当旋律重合度最优先……我找到个极度相似的旋律片段我肯定融它，
                        // 我还考虑别人干什么。」所以这一项以 MELODY_WEIGHT = 8.0
                        // **进分子与分母**（压倒九项之和 7.75），不是加分项。
                        // 量不出来（一侧没有 chroma）时传 null ⇒ 这一对按原来的九项+加分排，
                        // 与加这一项之前**逐位相同**。
                        .withMelody(melodyOrNull(melodyMatch(outro, cutTimes[c], intro,
                                entryTimes[e], windows[w])), null);
                evaluated++;
                // **选点诊断**（默认关；-Dqplayer.trace.cue=true 打开）：把每个候选与逐项分印出来。
                // 为什么要它：用户报"选点差点意思"时，光看最终点不知道**是谁把它拽住的** ✗ ——
                // 逐项分一列就看得见（2026-10-07 加，用于 goal「让交接点落在音乐边界上」）。
                if (TRACE_CUE) {
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "[选点追踪] cut=%.4f entry=%.4f win=%.4f total=%.5f"
                                    + " overlap=%.3f ripple=%.3f bands=%.3f level=%.3f motion=%.3f"
                                    + " beat=%.3f tail=%.3f vocal=%.3f lowEnd=%.3f struct=%s melody=%s",
                            Double.valueOf(score.cutSecs), Double.valueOf(score.entrySecs),
                            Double.valueOf(score.windowSecs), Double.valueOf(score.total),
                            Double.valueOf(score.overlap), Double.valueOf(score.ripple),
                            Double.valueOf(score.bands), Double.valueOf(score.level),
                            Double.valueOf(score.motion), Double.valueOf(score.beat),
                            Double.valueOf(score.tail), Double.valueOf(score.vocal),
                            Double.valueOf(score.lowEnd),
                            score.structure == null ? "-" : String.format(
                                    java.util.Locale.ROOT, "%.3f", score.structure),
                            score.melodyToScore == null ? "-" : String.format(
                                    java.util.Locale.ROOT, "%.3f", score.melodyToScore)));
                }
                if (score.total > bestPerWindow[w]) {
                    bestPerWindow[w] = score.total;
                }
                // 「别掐掉超过 15%」；**但如果窗口本身就比这 15% 长，至少要留得下一个整窗**
                // ——否则短曲子 + 长窗口会变成互斥条件（实测：40 秒的歌配 12 秒窗口，
                // 「出场点在最后 15%」要求 ≥34 s，而「窗口放得进去」要求 ≤28 s，
                // 两条不可能同时成立，判据 AiMixHarmonicCueTest 当场红了）。
                // 对正常长度的曲子这一条等价于「最后 15%」，所以不会重新引入
                // 「靠拉长窗口通过约束」那个反向激励（那一条是 2026-10-02 被判据挡回来的）。
                double fromEnd = outro.durationSecs - score.cutSecs;
                double allowedFromEnd = Math.max(
                        MAX_CUT_FROM_END_FRACTION * outro.durationSecs, score.windowSecs);
                boolean nearEnd = outro.durationSecs <= 0.0 || fromEnd <= allowedFromEnd;
                boolean vocallyClean = score.vocalSecs <= VOCAL_GATE_SECS;
                // ── 强度闸门（2026-10-04）────────────────────────────────────
                //
                // 用户：「A 跳跃点附近十分激进带感，衔接 B 的进入点应该差不多嗨，
                // 而不是突然降下去了」。九项里的「响度接续」管不了这件事（它比的是音量），
                // 而九项**任何一项被别的项压过去都只是分数**——所以这条必须是闸门。
                // 「排开歌词」：融合中点（不是进场点——见 lineGapTerm 的注释）落在某一句
                // 正唱着的时候 → 不合格。只有拿到真实行结束时间才判得出来；判不出来就是
                // 合格（诚实降级：宁可不管，也不要拿一个瞎猜的结论去否决候选）。
                // ── 歌词闸门（2026-10-04 第二次重写，形状由实测定）────────────────
                //
                // 现在的判据只有两条，各自都对着一个**听得到**的事实：
                //
                //   ① **句头增益**（{@link #ENTRY_HEAD_GAIN_MIN}）：B 的第一句句头出现那一刻
                //      B 已经被推到多大声。太小 = 句头被 A 压住，等 B 听得清时这句已经唱了
                //      几个字——用户 2026-10-04 第二次报的原话就是「滑过来的时候都唱了几个字了」。
                //   ② **乐句栅格**：B 的第一句起头要落在"A 本该唱下一句"的时刻。
                //
                // 原先这里还有两条，都被实测否掉了：
                //   · 「融合中点不卡在念到一半」（{@code isMidLyric}）：它其实是①的**粗代理**，
                //     而且在长换气的情况下会判错——换热 4 s、窗口 12 s 时，句头在 B 满音量处
                //     出现（①给满分，正是想要的），但中点已经落在句子里 2 s，②之外的这条会
                //     把它否掉。直接量①之后它就是多余且有害的。
                //   · 「两侧句头不许同时起」（{@code vocalOverlap(LyricSpans…)}）：真素材上
                //     96 对候选被它挡掉 96 对，**一对都没通过过**——结构性不可满足的判据
                //     等于没有判据，而它想防的事①从听感正面量了。
                boolean lyricOk = true;
                if (bridge.lyricGate && !headFollowsPhraseGap(introSpans, entryTimes[e],
                        new double[]{windows[w]}, lyricPhraseGap, ENTRY_HEAD_TOL_SECS, false)) {
                    // 判定点仍与 A 侧同一个瞬间（entry + 窗口）；间距见 headFollowsPhraseGap。
                    lyricOk = false;
                    lyricBlocked[0]++;
                }
                // 出场侧：同一个时刻 A 的那句必须正好唱完（见 endsPhraseNear）。
                if (lyricOk && bridge.lyricGate
                        && !risesAtSettleHead(intro, introSpans, entryTimes[e], windows[w])) {
                    lyricOk = false;
                    lyricBlocked[3]++;
                }
                if (lyricOk && bridge.lyricGate
                        && !endsPhraseNear(outroSpans, cutTimes[c] + windows[w],
                                ENTRY_HEAD_TOL_SECS)) {
                    lyricOk = false;
                    lyricBlocked[1]++;
                }
                // 乐句栅格单独记一份：窗口短不下来时（换气太窄）"句头增益"达不到，
                // 那时至少还要求节奏接得上——见阶梯里的 {@code bestLyricLoose}。
                boolean gridOk = true;
                if (bridge.lyricGate) {
                    double gridErr = phraseGridError(outroSpans, cutTimes[c], windows[w],
                            introSpans, entryTimes[e]);
                    if (!Double.isNaN(gridErr)) {
                        double gA = medianGapBefore(outroSpans.starts, cutTimes[c]);
                        if (!(gridErr <= Math.min(1.5, gA * 0.5))) {
                            // **只记在 gridOk 上，不再否掉 lyricOk**：句头增益是"听得到"的那条，
                            // 节奏是"好不好"的那条——实测这一条会把过了句头门的候选全否掉
                            // （计数器：乐句栅格 576 / 句头太轻 488），于是又回到"档位全空 → 降级"。
                            gridOk = false;
                            lyricBlocked[1]++;
                        }
                    }
                }
                boolean intensityOk = true;
                double gap = INTENSITY_UNKNOWN_BAND;
                if (bridge.measure) {
                    gap = Math.abs(cutIntensity[c] - entryIntensity[e]);
                    intensityOk = gap <= bridge.vetoGap;
                    score = score.withIntensity(Double.valueOf(gap), bridge.score);
                }
                if (vocallyClean && (bestClean == null || better(score, bestClean))) {
                    bestClean = score;
                }
                if (nearEnd && (bestNearEnd == null || better(score, bestNearEnd))) {
                    bestNearEnd = score;
                }
                // **两个硬约束同时满足**的那个。两个门各自为政是不行的：实测出现过
                // 丢尾门挑中了尾声附近的点、紧接着叠唱门把它换成半路切歌的点
                // （出场 90.5 s、丢尾 84.9 s = 全曲 45%），因为两个门是「先后」而不是「同时」。
                if (nearEnd && vocallyClean && (bestGated == null || better(score, bestGated))) {
                    bestGated = score;
                }
                // 三个约束**同时**满足的（桥接关着时它与 bestGated 是同一个条件、
                // 同一批候选 → 选中结果逐位相同，见 {@link Bridge#OFF}）。
                if (bridge.veto && nearEnd && vocallyClean && intensityOk
                        && (bestBridgeGated == null || better(score, bestBridgeGated))) {
                    bestBridgeGated = score;
                }
                if (bridge.lyricGate && nearEnd && vocallyClean && intensityOk
                        && lyricOk && (bestLyricTop == null || better(score, bestLyricTop))) {
                    bestLyricTop = score;
                }
                if (bridge.lyricGate && nearEnd && intensityOk && lyricOk
                        && (bestLyricNoVocal == null || better(score, bestLyricNoVocal))) {
                    bestLyricNoVocal = score;
                }
                // 「句头增益达不到、但节奏接得上」的那一档（窗口短不下来时的兜底）。
                if (bridge.lyricGate && nearEnd && intensityOk && gridOk
                        && (bestLyricLoose == null || better(score, bestLyricLoose))) {
                    bestLyricLoose = score;
                }
                if (bridge.lyricGate && nearEnd && vocallyClean && lyricOk
                        && (bestLyricNoIntensity == null
                                || better(score, bestLyricNoIntensity))) {
                    bestLyricNoIntensity = score;
                }
                // 只放松叠唱的（保留强度闸门）。放松顺序见下面的阶梯：
                // 叠唱最轻、强度居中、丢尾最重。
                if (bridge.veto && nearEnd && intensityOk
                        && (bestRelaxVocal == null || better(score, bestRelaxVocal))) {
                    bestRelaxVocal = score;
                }
                // ① 闸门淘汰计数（用户要的"淘汰了多少候选、按哪一条淘的"）
                if (!vocallyClean) {
                    GATE_DROP_VOCAL++;
                } else if (!intensityOk) {
                    GATE_DROP_INTENSITY++;
                } else if (!gridOk) {
                    GATE_DROP_GRID++;
                }
                if (best == null || better(score, best)) {
                    second = best;
                    best = score;
                } else if (second == null || better(score, second)) {
                    second = score;
                }
                keepRanked(ranked, score);
            }
        }
        if (best == null) {
            return null;
        }
        // ── P6：叠唱硬约束 ────────────────────────────────────────────────
        //
        // 软打分给人声重叠的权重只有 10%，别的项（响度接续 1.0、波形重合 1.6）一偏心就会
        // 把「两边都在唱」的点选出来。实测（`AutoCueVocalGateTest` 的偏心夹具）软打分选中
        // 的进场点是 1.88 s，正落在 0~10 s 的叠唱区里。这里把它升级成否决：
        // 只要还有叠唱不超标的候选，就取其中最好的那个。
        String gateNote = "";
        // 硬约束的**降级阶梯**：两个都满足 → **保住人声（位置让路）** → 只满足丢尾（放松叠唱）
        // → 一个都不满足。
        //
        // **2026-10-06 按用户要求改了顺序**：用户的原话是「人声保护保护不齐全」，
        // 他听到的正是这个顺序的后果——原来第二条是"先放松叠唱（它轻得多）"，
        // 即**位置优先、人声让路**。而"两边同时有人在唱"恰恰是过渡里最容易听出来的那件事，
        // 所以现在优先取"人声干净、位置稍让"的那一类。
        //
        // 为什么必须是一条阶梯而不是两个独立的 if：实测出现过「丢尾门挑中了尾声附近的点、
        // 紧接着叠唱门又把它换成半路切歌的点」（出场 90.5 s、丢尾 85 s = 全曲 45%），
        // 因为两个门各自为政。凡是「约束」就必须一起判定，放松的时候也要按影响的严重程度
        // 排队（把歌掐掉 45% 比叠唱 0.4 s 严重得多），而且必须说出放松了哪一个。
        // 强度闸门那一档排在最前：它比今天的「丢尾 + 叠唱」更严一档，
        // 但比它自己的降级档（放松叠唱）更优先——放松顺序是
        // 「叠唱最轻 → 强度居中 → 丢尾最重」，与上面那段理由同源。
        if (bridge.lyricGate && bestLyricTop != null && bestLyricTop != best) {
            gateNote = lyricGateNote(best, bestLyricTop, "四个约束同时满足");
            second = best;
            best = bestLyricTop;
        } else if (bridge.lyricGate && bestLyricTop == null
                && bestLyricNoVocal != null && bestLyricNoVocal != best) {
            gateNote = lyricGateNote(best, bestLyricNoVocal, "放开了叠唱");
            second = best;
            best = bestLyricNoVocal;
        } else if (bridge.lyricGate && bestLyricTop == null
                && bestLyricNoVocal == null && bestLyricNoIntensity != null
                && bestLyricNoIntensity != best) {
            gateNote = lyricGateNote(best, bestLyricNoIntensity, "放开了强度");
            second = best;
            best = bestLyricNoIntensity;
        } else if (bridge.lyricGate && bestLyricTop == null
                && bestLyricNoVocal == null && bestLyricNoIntensity == null
                && bestLyricLoose != null && bestLyricLoose != best) {
            gateNote = lyricGateNote(best, bestLyricLoose,
                    "句头增益达不到（换气太窄，窗口短不下来），保住了乐句栅格");
            second = best;
            best = bestLyricLoose;
        } else if (bridge.veto && bestBridgeGated != null && bestBridgeGated != best) {
            gateNote = intensityGateNote(best, bestBridgeGated);
            second = best;
            best = bestBridgeGated;
        } else if (bridge.veto && bestBridgeGated == null && bestRelaxVocal != null
                && bestRelaxVocal != best) {
            gateNote = String.format(java.util.Locale.US,
                    "；强度闸门：放开了叠唱（它比强度轻），取强度差 %.3f 的 %s → %s",
                    Double.valueOf(gapOf(bestRelaxVocal)), fmt(bestRelaxVocal.cutSecs),
                    fmt(bestRelaxVocal.entrySecs));
            second = best;
            best = bestRelaxVocal;
        } else if (bridge.veto && bestBridgeGated == null && bestRelaxVocal == null
                && bestGated != null && bestGated != best) {
            gateNote = String.format(java.util.Locale.US,
                    "；强度闸门：扫到的范围里没有强度差 ≤ %.2f 的落点，只能放松强度"
                            + "（差 %.3f 的 %s → %s）",
                    Double.valueOf(bridge.vetoGap), Double.valueOf(gapOf(bestGated)),
                    fmt(bestGated.cutSecs), fmt(bestGated.entrySecs));
            second = best;
            best = bestGated;
        } else if (bestGated != null && bestGated != best) {
            // 桥接关着时走的就是这一条（与加桥接之前逐位相同）。
            gateNote = gateNoteFor(best, secondCuts(bestGated),
                    outro.durationSecs - best.cutSecs, outro.durationSecs);
            second = best;
            best = bestGated;
        } else if (bestGated == null && bestClean != null && bestClean != best) {
            gateNote = String.format(java.util.Locale.US,
                    "；硬约束：所有候选的出场点都不在最后 %.0f%% 里"
                            + "（八成是出场曲本身就短），只能按叠唱挑：取叠唱 %.1fs 的 %s（距曲尾 %.1fs）",
                    Double.valueOf(100.0 * MAX_CUT_FROM_END_FRACTION),
                    Double.valueOf(bestClean.vocalSecs), fmt(bestClean.cutSecs),
                    Double.valueOf(outro.durationSecs - bestClean.cutSecs));
            second = best;
            best = bestClean;
        } else if (bestGated == null && bestNearEnd != null && bestNearEnd != best) {
            gateNote = String.format(java.util.Locale.US,
                    "；硬约束：没有任何候选能同时满足「出场点在最后 %.0f%% 且叠唱 ≤ %.1fs」，"
                            + "先放松叠唱（它轻得多），取尾声附近的 %s（距曲尾 %.1fs）",
                    Double.valueOf(100.0 * MAX_CUT_FROM_END_FRACTION),
                    Double.valueOf(VOCAL_GATE_SECS),
                    fmt(bestNearEnd.cutSecs),
                    Double.valueOf(outro.durationSecs - bestNearEnd.cutSecs));
            second = best;
            best = bestNearEnd;
        } else if (bestGated == null && bestNearEnd == null && bestClean == null) {
            gateNote = String.format(java.util.Locale.US,
                    "；硬约束：没有任何候选满足「出场点在最后 %.0f%%」或「叠唱 ≤ %.1fs」"
                            + "（最好的那个距曲尾 %.1fs、叠唱 %.1fs），只能取它",
                    Double.valueOf(100.0 * MAX_CUT_FROM_END_FRACTION),
                    Double.valueOf(VOCAL_GATE_SECS),
                    Double.valueOf(outro.durationSecs - best.cutSecs),
                    Double.valueOf(best.vocalSecs));
        }
        // **兜底**：分支式阶梯是有缝的——实测漏掉过「最终候选满足出场点、但一个满足叠唱的
        // 候选都没有」这种组合，四个分支一个都不命中，结论被放松了而日志里一个字都没有；
        // 更糟的是「窗口长到最后 15% 里放不下」时（真曲目上 windowScale=2.6 那种），
        // 出场点会跳回半路，说明里同样什么都没写。
        //
        // 与其继续补分支（还会有新的组合漏掉），这里直接**核对最终候选本身**：
        // 它要是违反了哪条约束，而上面又没解释过，就补一句。这样「静默降级」在结构上不可能发生。
        if (gateNote.isEmpty()) {
            double fromEnd = outro.durationSecs - best.cutSecs;
            double allowed = Math.max(MAX_CUT_FROM_END_FRACTION * outro.durationSecs,
                    best.windowSecs);
            boolean tailOk = outro.durationSecs <= 0.0 || fromEnd <= allowed;
            boolean vocalOk = best.vocalSecs <= VOCAL_GATE_SECS;
            if (!tailOk || !vocalOk) {
                StringBuilder why = new StringBuilder();
                if (!tailOk) {
                    why.append(String.format(java.util.Locale.US,
                            "出场点在 %ss（距曲尾 %.1fs，超过允许的 %.1fs）",
                            fmt(best.cutSecs), Double.valueOf(fromEnd), Double.valueOf(allowed)));
                }
                if (!vocalOk) {
                    if (why.length() > 0) {
                        why.append("、");
                    }
                    why.append(String.format(java.util.Locale.US, "叠唱 %.1fs（超过 %.1fs）",
                            Double.valueOf(best.vocalSecs), Double.valueOf(VOCAL_GATE_SECS)));
                }
                gateNote = String.format(java.util.Locale.US,
                        "；硬约束：没有一个候选能同时满足两条，最终这个%1$s——只能取它",
                        why.toString());
            }
        }
        if (GATE_DROP_VOCAL + GATE_DROP_INTENSITY + GATE_DROP_GRID > 0) {
            Logger.debug("[qplayer-engine] 点位闸门淘汰：叠唱 {} · 强度 {} · 乐句栅格 {}（其余 {} 对进入打分）",
                    Long.valueOf(GATE_DROP_VOCAL), Long.valueOf(GATE_DROP_INTENSITY),
                    Long.valueOf(GATE_DROP_GRID), Integer.valueOf(ranked.size()));
            GATE_DROP_VOCAL = 0L;
            GATE_DROP_INTENSITY = 0L;
            GATE_DROP_GRID = 0L;
        }
        if (windows.length > 1) {
            StringBuilder candidates = new StringBuilder();
            for (int w = 0; w < windows.length; w++) {
                if (w > 0) {
                    candidates.append("  ");
                }
                candidates.append(fmt(windows[w])).append("s→")
                        .append(String.format(java.util.Locale.US, "%.3f", bestPerWindow[w]));
            }
            Logger.debug("[qplayer-engine] 交叠窗口候选（各自的最好成绩）：{} → 取 {}s",
                    candidates, fmt(best.windowSecs));
        }
        // ── 选完之后把点位**对到最近的一拍**（只在那一侧有拍栅格时）──────────────
        //
        // 这一步是「候选集用密网格 + 选完对拍」的另一半：搜索在 20 ms 网格上做（保住
        // 选点的结论），落地时吸到最近的一拍（拿到对拍的好处）。挪动幅度不超过半拍，
        // 而且挪出合法区就不挪（宁可不对齐，也不许切到曲子外面）。
        // 挪完**重新评分**一次，note 里的七项与总分才是这套点位自己的，不是旧点位的。
        // **吸拍与否只看一条界**：这一对有没有越过"窗口放得进"的那条界
        // （{@code cut + 窗口 > lastLegalCut}）。
        //
        // 越过的那一档（买时间的点位 = vamp 的终点，见上面那一段）不吸拍，两条理由：
        //   ① 它是 vamp 的终点，而 vamp 段按定义就落在小节线上（{@code outroVampSpan}
        //      只收小节线对齐、整数小节的段）——吸拍只会把它挪到离小节线最多半拍的地方；
        //   ② 吸拍的合法上界正是 {@code lastLegalCut − 窗口}，也就是买时间要越过的那个界，
        //      吸下去就把"买时间"这件事自己抹掉了（计划会退回"放得进窗口"，说明行里写的
        //      和做的就不是一件事）。
        //
        // **放得进窗口的那一档照常吸拍**——"点位落在拍上"是这个包的契约
        // （{@code AutoCueBeatTest} 钉着它），2026-10-05 新加的"尾奏剩得少到会赶"
        // （{@link #LOOP_SHORT_TAIL_BARS}）那一类**也走这一支**：它的点位在放得进的区间里，
        // 吸拍完全合法，没有任何理由把它豁免。于是判定用的就是**吸完拍的那个点**
        // （下面 {@code planOutroLoop} 拿的也是它）——两处说的是同一件事；
        // 万一吸拍把尾巴吸到 4 小节以上，那一档本来就不再"会赶"，不触发才是对的。
        //
        // 这一行的**老路径逐位不变**（同样的实参、同样的顺序）：放得进窗口的候选上
        // {@code beyondFit} 恒为 false，而从前那两条触发（"放不下"）在放得进的候选上
        // 也恒为 false（{@code cut + 窗口 ≤ 全曲 − 2 s} ⇒ 尾巴 ≥ 窗口 + 2 s），
        // 所以老路径永远取 else 那一支、吸拍的上界也一模一样。
        // ── ③ 分层 + 加权取模（Apple 的 weighted modulo 语义）──────────────────
        // 用户 2026-11-20 要求照参考包做：分数相差 ≤2% 的算**同一层**，
        // 同层内按 trunc(score×1000) 加权取模抽一个点 ⇒ 同一对歌结果稳定，
        // 不同对歌不会总撞同一个尴尬点位（Apple 就是靠这个避免"每次都是那一下"）。
        if (TIERED_SELECTION && ranked.size() > 1) {
            final double floor = best.total * (1.0 - TIER_FRACTION);
            java.util.List<Score> layer = new java.util.ArrayList<Score>();
            for (int i = 0; i < ranked.size(); i++) {
                Score sc = ranked.get(i);
                // **同层还要求"同一个模板"**（同一个窗口长度）：Apple 的 tier 是模板族，
                // 不是"分数接近就混在一起"——跨窗口（8 s vs 6 s）随机会让风格选择被颠覆 ✗
                if (sc.total >= floor && Math.abs(sc.windowSecs - best.windowSecs) < 1e-6) {
                    layer.add(sc);
                }
            }
            if (layer.size() > 1) {
                long totalWeight = 0L;
                for (int i = 0; i < layer.size(); i++) {
                    totalWeight += Math.max(1L, Math.round(layer.get(i).total * 1000.0));
                }
                long seed = 1125899906842597L;
                String key = fmt(layer.get(0).cutSecs) + "|" + fmt(layer.get(0).entrySecs)
                        + "|" + fmt(best.windowSecs);
                for (int i = 0; i < key.length(); i++) {
                    seed = 31L * seed + key.charAt(i);
                }
                long draw = Math.floorMod(seed, totalWeight);
                Score chosen = layer.get(layer.size() - 1);
                for (int i = 0; i < layer.size(); i++) {
                    draw -= Math.max(1L, Math.round(layer.get(i).total * 1000.0));
                    if (draw < 0L) {
                        chosen = layer.get(i);
                        break;
                    }
                }
                Logger.info("[qplayer-engine] 分层取模：同层 {} 个（{}~{}）权重和 {} → 抽中 {} → {}"
                                + "（Apple weighted modulo 语义）",
                        Integer.valueOf(layer.size()),
                        fmt(layer.get(layer.size() - 1).total), fmt(layer.get(0).total),
                        Long.valueOf(totalWeight), fmt(chosen.cutSecs), fmt(chosen.entrySecs));
                best = chosen;
            }
        }
        boolean beyondFit = best.cutSecs + best.windowSecs > lastLegalCut;
        double outroCut = beyondFit ? best.cutSecs
                : snapToBeat(outro, best.cutSecs, cutFloor,
                        Math.max(cutFloor, lastLegalCut - best.windowSecs));
        double introEntry = snapToBeat(intro, best.entrySecs, 0.0, entryCeil);
        introEntry = phaseAlignedEntry(outro, outroCut, intro, introEntry, best.windowSecs);
        // ── 歌词内容级锚点：从重复的那一句钻进去（2026-10-06 用户提的玩法）──────────
        // 顺序就是用户说的「先对拍然后直接从重复的地方钻进去」：上面两步已经把出场点吸到拍上、
        // 并用波形修正到采样级；这里再问一次"两边有没有同一句词"，有就按
        // entry = cut + (ta − tb)（量化到整拍）把进场点挪过去。双重门槛在 lyricAnchoredEntry 里。
        double anchorWindowSecs = -1.0;
        if (bridge.lyricGate && LYRIC_ANCHOR_IN_PLAN) {
            // 两个式子一起用：cut 由交接词反推（摆到窗口正中）、entry 由锚点关系跟着定。
            double[] anchor = lyricAnchor(outro, outroCut, intro, introEntry, best.windowSecs,
                    outroSpans, introSpans, cutFloor,
                    Math.max(cutFloor, lastLegalCut - best.windowSecs));
            if (anchor != null) {
                outroCut = anchor[0];
                introEntry = anchor[1];
                // **窗口也跟着锚点走**（第一版漏了这一步，正是"念两遍"的另一半病根）：
                // 锚点按重复段的长度定窗口，这里把它交给重评 ⇒ best.windowSecs 从此就是它，
                // 下游（循环计划、落床、最终 Cue）全部跟着用新窗口。
                anchorWindowSecs = anchor[2];
            }
        }
        // **吸到拍上不许破坏歌词那两条对齐**（2026-10-04 加、2026-10-05 扩到两侧）：
        // 吸拍最多动半拍，而"融合结束那一刻"要同时踩住 A 的句尾与 B 的句头，
        // 差半拍就偏出去（实测单窗口那组被吸拍挪到 0.43 / 0.61 s 之外）。
        // 与强度那条"同一件事只能有一把尺子"同理：决定是按吸拍前的点做的，
        // 就别让吸拍在决定之后把它推翻。破坏了就退回不吸拍的那个点。
        if (bridge.lyricGate) {
            double settleWin = best.windowSecs;
            if (alignsWithHead(introSpans, best.entrySecs, new double[]{settleWin},
                    ENTRY_HEAD_TOL_SECS)
                    && !alignsWithHead(introSpans, introEntry, new double[]{settleWin},
                            ENTRY_HEAD_TOL_SECS)) {
                introEntry = best.entrySecs;
            }
            if (endsPhraseNear(outroSpans, best.cutSecs + settleWin, ENTRY_HEAD_TOL_SECS)
                    && !endsPhraseNear(outroSpans, outroCut + settleWin,
                            ENTRY_HEAD_TOL_SECS)) {
                outroCut = best.cutSecs;
            }
        }
        if (outroCut != best.cutSecs || introEntry != best.entrySecs || anchorWindowSecs > 0.0) {
        // 锚点可能同时改了**窗口**（按重复段长度），所以这一块里的窗口一律读 `win`。
        double win = anchorWindowSecs > 0.0 ? anchorWindowSecs : best.windowSecs;
            // 挪完**重新评分**：note 里那七项与总分必须是这套点位自己的，不是旧点位的。
            //
            // 2026-10-01 一度写了个「分数掉超过 0.01 就不吸」的容差，结果
            // {@code AutoCueBeatTest#planPrefersBeatAlignedPointsOnBothSides} 直接红
            // （出场点 163.64，与栅格拍差 0.04）——那条判据要求的就是「点位落在拍上」，
            // 是**契约**，不是锦上添花。而实测吸到拍上的分数代价很小（≤0.01 量级），
            // 所以不做容差：**能吸就吸**，并把吸完的真实分数报出来。
            best = evaluate(outro, intro, outroCut, introEntry, win)
                    .withLineGap(lineGapTerm(outroSpans, introSpans, outroCut, introEntry,
                            win))
                    .withStructure(structureTerm(outroCut, outroStructure,
                            introEntry + win * 0.5,
                            introStructure + INTRO_ANCHOR_LEAD_SECS,
                            STRUCTURE_OUTRO_SCALE,
                            bridge.widen ? STRUCTURE_INTRO_SCALE : 1.0))
                    // 强度差**按挪完的点重算**：吸到拍上会动点位（最多半拍），
                    // 而强度是 0.6 s 平滑过的量，所以动得极小——但"极小"不是"没有"，
                    // 少了这一步 {@code Cue.intensityGap} 会退回中性值 0.5，
                    // 于是调用方会以为"浅扫区里接不上"而白白去深扫一遍（实测踩到过）。
                    // 度量必须与策略一致：第二级用的是"往后看 6 秒"，这里若还按点值重算，
                    // 就会在吸到拍上之后把 gap 换成另一把尺子的读数，于是 intensityOk 在
                    // 决定已经做完之后**翻面**（实测：第二级明明挑到了满足约束的落点，
                    // 重算出来的却是点值 0.228 > 死区 → 调用方以为"没接上"、不采用，
                    // 最后落在 B 的安静引子里）。同一件事只能有一把尺子。
                    .withIntensity(bridge.measure
                            ? Double.valueOf(Math.abs(
                                    intensityAhead(outro, outroCut, metricAhead)
                                            - intensityAhead(intro, introEntry, metricAhead)))
                            : null, bridge.score)
                    // 旋律永远第一位：**重评也要带上它**，否则吸拍之后
                    // note 里的总分与闸门比较就丢了压倒性那一项（实测踩到：
                    // 说明行显示「旋律重合 没量到」正是这一处漏掉的）。
                    .withMelody(melodyOrNull(melodyMatch(outro, outroCut, intro,
                            introEntry, win)), null);
        }
        // ── 尾奏 vamp：选一段「可以循环的纯器乐段」＋决定「到底要不要循环」──────────
        //
        // 两个结论各有各的用处，**都必须在这一层算**（这里是唯一同时拿着两侧整曲包络的地方，
        // 而渲染路径跑在生产者线程上、一帧都不该做分析）：
        //
        //   * 可循环段（outroVampSpan）：只报，给人看「这对曲子的尾奏里有没有可循环的东西」；
        //   * 循环计划（planOutroLoop）：**接线那一类**才进 Cue，渲染路径据此把窗口里的
        //     A 素材换成「循环段重复 repeats 遍」。
        //
        // **位置讲究（2026-10-05 起）**：这两个结论与"三段路径"那一档都在**说明行拼出来
        // 之前**定下来。原先循环决策在说明行之后算，多段路径那一档又只改 `segments`，
        // 所以说明行可以先拼再补一句；而三段路径会把**进场点**搬到 B 的落床段起点
        // （见下面那段长注释），说明行里的「进场 X s」必须跟着变——先拼再改就成了谎话
        // （"报告必须描述事实"是这个包的老规矩）。
        //
        // 那句「不循环时逐位不变」就靠这里：计划是 null、或者决策层给的这一份**不在接线
        // 口径内**（见 loopIsWired）时，note 一个字都不加、Cue 的那个字段是 null，
        // 渲染路径的分支根本不会进（判据 AutoCueOutroLoopTest 的第一、二条钉着它）。
        // vamp 在上面挑买时间候选那一处已经量过一次，这里复用同一份：它是整条路径里
        // 最贵的几件事之一（要在多个长度 × 每条小节线上量自相似/电平/人声），
        // 而这一层又是唯一同时拿着两侧整曲包络的地方，量两遍没有第二个理由。
        //
        // 「调性冲突但节奏兼容」那一条传**真的问出来的那一份**：这一层拿不到调性
        // ——包络里只有三条分频带（低/中/高），没有色度，而 Harmonic 判调要的就是色度
        // ——所以它只按句柄去问**注入进来的来源**（{@link #installKeyRelationSource}，
        // 现成的实现在 {@link #harmonicKeyRelationSource()}），问不到就是 null，
        // 而 null 一路走下去与"没有这条触发"逐位相同（判据
        // {@code AutoCueOutroLoopKeyClashTest} 钉着）。判据（"冲突"与"节奏兼容"两半）
        // 见 {@link #keyClashWithCompatibleGrid}。
        //
        // **只在真的有可循环段时才问**（2026-10-06）：问一次要读两侧各 8 秒 PCM
        // 再跑一遍逐帧 chroma，而绝大多数过渡连 vamp 都没有（那时循环这件事压根
        // 不在议程上，问了也白问）。`Supplier` 就是为这一条存在的——
        // 决策层决定"什么时候问"，来源那一侧只管"怎么答"。
        KeyRelation key = null;
        if (vamp != null && keyRelation != null) {
            try {
                key = keyRelation.get();
            } catch (RuntimeException e) {
                key = null;                     // 来源出错 = 不知道（不拦）
            }
        }
        // ⚠️ **2026-10-07 试过"结构对齐"（交接点 = B 结构点 − 窗口/2，确定性规则）**：
        // 在**单窗口参照**里它算得正好是目标 —— 参考对 16.32 − 1.26 = 15.06 ⇒ 吸拍 **12.474**
        // ⇒ 交接 **14.987**（用户权威读数 15.0，差 13 ms ✓）。
        // 但**生产链**拿到的是 13.017 ✗（交接 15.53 ✗）⇒ 差别不在这一层：生产走的是
        // 多窗口候选 + 第二级深扫，后面还有一道把它挪了 0.46 s ✗（单窗口参照 12.556 ✓）。
        // 所以这一条**退回** ✓，下一轮把它放到**外层**（过渡包装层对最终计划做对齐 ✓，
        // 而不是在搜索内部改打分/候选），并且要连带把 `AppleCueIntensityBridgeTest`
        // 里"生产 vs 同源参照不许漂移"那条判据一起对上 ✓。

        //
        // **拿最终这套点位问一次**：选中的要是上面第二队那一对（买时间），这一问
        // 得到的就正是 buysTime = true 的计划，于是 loopIsWired 收它、渲染路径
        // 真的去把尾奏那段 vamp 重复一遍——"候选上标了买时间、这里却还按老口径问"
        // 那个断层就是这样消掉的：标记与计划用的是**同一对 (出场点, 窗口)**。
        //
        // 调性那一份是**原始读数**，"调性冲突但节奏兼容"由七参版本自己按
        // {@link #keyClashWithCompatibleGrid} 判（一个合取，不拆两处）。
        AutoCue.OutroLoopPlan decided = vamp == null ? null
                : planOutroLoop(outro, intro, vamp, outroCut, best.windowSecs, key);
        // 循环计划与这一次要走的路径（null = 没有路径：渲染层按老路走"出场点 + 窗口"）。
        AutoCue.OutroLoopPlan outroLoop = null;
        java.util.List<Segment> segments = null;
        // ── 三段路径那一档（2026-10-05，用户：「主动编配是在结构图上找最优路径」）──────
        //
        // 用户给的第三个例子：「A 的尾奏 + B 的纯鼓段循环 + B 的主歌」。渲染层早就支持
        // 多段（判据 {@code AutoCueSegmentedPathTest} 直接喂过一条三段路径），缺的是"选"。
        //
        // **怎么参与选择（用的是字面意义上的同一套口径）**：B 的落床段里那个"起播点"
        // （没有旋转点时 = {@link #introBedSpan} 那一段的起点；落点在段内时 = 搜索挑中的
        // 那个落点，见下面的旋转）在这一层被当成**一个进场点候选**，
        // 与搜索挑中的那个进场点比同一把尺子——同一个出场点、同一个窗口、同一串加分项
        // （见 {@link #seamScore}：评分 + 结构 + 小节线 + 新颖度 + 强度贴合；
        // **只有"歌词节奏"那一项没参与**，理由写在那里的注释里）。
        // 差别因此**只在进场点这一项上**：拿 B 自己的器乐开头当落床，还是拿搜索挑的那个
        // 落点当落点。
        //
        //   · 两边打平（**落床段起播点本来就是搜索的那个点**：要么落床段起点就是它，
        //     要么下面那个旋转点就是它）→ 用三段路径。理由：搜索已经说了
        //     "就从这个落点进"，而今天那条两段路径只会把 B 从这儿**淡进来**（交叉淡化的
        //     另一侧是 B 别处的素材）；三段路径把这件事落成一条有落床的编排——
        //     A 的尾奏段放完 → A 的循环段 ×repeats → B 的落床段 ×repeat，
        //     接缝落在小节线上、B 的主歌从落床段末尾接着播。**"严格更好"在这种情形下
        //     按定义不可能成立**（搜索挑的就是候选集里最好的那个点），所以判据只能是"不更差"；
        //   · 落床段起点**更好** → 用三段路径，并把进场点搬到它（下面那个赋值）。
        //     这条是**严格更优**那一档，也正是这一段路径真正"多选出来"的东西：
        //     落床段起点不一定是候选点（{@code entryFloor} 可能把它排除在外，
        //     而它在 B 的第一条小节线上、与人声段无关），所以它可以比搜索看到的任何一个
        //     落点都好；
        //   · 落床段起播点**更差** → 如实不用它，照旧走那条等价路径（并在 debug 档留一行
        //     说明，免得读日志的人分不清"没接"与"判输了"）。
        //
        // 三条几何前提（任一条不成立就不用它）：① 循环真的接线了（这一档是循环决策之上的
        // 编排，没有循环就没有它）；② B 找得到落床段；③ {@link #introBedPath} 的小节几何、
        // **A 侧 ≤ {@link #INTRO_BED_PATH_MAX_BARS} 小节**、以及**整窗 ≤ A 的全曲时长**成立
        // （B 的落床段不进那个 16 小节的预算，见该常量的注释）。
        AutoCue.VampSpan bed = vamp == null ? null : introBedSpan(intro);
        // ── 旋转点：搜索挑中的那个落点落在落床段**内部**时，让这一段从它起播 ──────────
        //
        // **这是 2026-10-05 那次实测修掉的那件事**：真曲库 56 个有序对里 0 对选三段路径，
        // 原因不是"落床段不成立"，而是"落床段起点"这个候选在打分上**输给了搜索自己挑的
        // 那个落点**——而那个落点就落在落床段**里面**。实测 demo-A→demo-B（落床段
        // 0.05→19.25 s）：第一级（MEASURE）搜索挑的是 2.82 s（在段内），引擎实际采用的
        // 那一级（DEFAULT 深扫）挑的是 25.62 s（在段外）。要紧的是段内那一种：搜索说的与
        // 三段路径说的本来可以是**同一个点**，只是"落床段只能从段首起播"这条硬约束让三段
        // 路径被迫降级成一个更差的候选，于是被如实拒绝。
        //
        // 旋转把这条约束解掉：落床段这一遍从搜索挑的那个点起播（一遍仍是整段落床段，
        // 见 {@link Segment#rotateStartSecs}）。
        //
        // **旋转是"多一个候选"，不是"换一套算法"**——这一档现在有两个候选，各用**同一把
        // 尺子**（{@link #seamScore}，同一个出场点、同一个窗口、同一串加分项）评一次：
        //
        //   · <b>不旋转</b>（今天的形状）：B 从**落床段起点**起播，进场点也搬成它
        //     （下面那个赋值）——分数 = 落床段起点那一个；
        //   · <b>旋转</b>：B 从**搜索挑中的那个落点**起播，进场点不动
        //     （{@code Cue.introEntrySecs} 本来就是它，两边天然一致）——分数 = 搜索挑的
        //     那一个。
        //
        // 谁高用谁；**打平用旋转那一条**（理由：两边几何等价时，搜索挑的那个点还带着
        // {@link #seamScore} 看不见的那些约束——歌词规则、硬约束阶梯，而"段首"只是由 B 的
        // 小节几何派生出来的一个点）。两条都不成立（也就是"落床段起点更好"）时走今天的老路
        // ——旋转没有把任何一支挤掉，它只是让"搜索挑的点更好"这一种情形不再**必然输掉**。
        //
        // 旋转点只取"<b>离段首超过半拍</b>、且严格落在段内"的那一种：
        //   · 半拍以内算**同一个点**（下面那条 sameLanding 的既有口径：进场点还要过一次吸拍，
        //     实测两边相差不到 1 ms 是常态）——为那几十毫秒去旋转一遍等于把素材白白错开一点，
        //     听感上没有任何好处，而"落床段从段首起播"这条老性质（判据
        //     {@code AutoCueIntroBedTest} 钉着它）反而被破坏（实测：合成夹具里搜索挑的点离
        //     段首 32 ms，照旋转处理会让那一段的素材整体挪 704 帧）；
        //   · 等于段首等于"没旋转"（今天的形状）；
        //   · 落在段外则说不通（那种情况下"从落点起播"根本不是这一段的素材）。
        // 三种都如实不旋转，一个字都不猜。
        double beatA = outro.tempo != null && outro.tempo.bpm > 0f ? 60.0 / outro.tempo.bpm : 0.5;
        boolean pickSameAsHead = bed != null
                && Math.abs(introEntry - bed.from) <= beatA * 0.5 + 1e-6;
        double bedRotate = bed != null && !pickSameAsHead
                && introEntry > bed.from + Segment.ROTATE_EPS_SECS
                && introEntry < bed.to - Segment.ROTATE_EPS_SECS ? introEntry : Double.NaN;
        java.util.List<Segment> bedPath = null;
        double bedPlainTotal = Double.NaN;
        double bedAltTotal = Double.NaN;
        if (loopIsWired(decided) && bed != null && bed.from >= 0.0 && bed.from <= entryCeil) {
            double introScale = bridge.widen ? STRUCTURE_INTRO_SCALE : 1.0;
            double structureTarget = introStructure + INTRO_ANCHOR_LEAD_SECS;
            bedPlainTotal = seamScore(outro, intro, outroCut, introEntry, best.windowSecs,
                    outroStructure, structureTarget, introScale, bridge, metricAhead).total;
            bedAltTotal = seamScore(outro, intro, outroCut, bed.from, best.windowSecs,
                    outroStructure, structureTarget, introScale, bridge, metricAhead).total;
            // **搜索挑的那个进场点就是落床段起点**（半拍以内算同一个——进场点还要过一次
            // 吸拍）时两边的几何**是同一套**，分数必然相同：这时"打平"不需要、也不可能用
            // 比分数来表达（实测两边相差不到 1 ms，分数差在千分位、符号是随机的）。
            // 用点的重合来判它，比拿两串浮点数比大小可靠，也是"同一把尺子"那句话的字面意思。
            boolean sameLanding = Math.abs(introEntry - bed.from) <= beatA * 0.5 + 1e-6;
            // 旋转那一条：几何 = "搜索挑的点"，所以它的分就是 bedPlainTotal（不必再算一遍）。
            boolean rotateWins = !Double.isNaN(bedRotate) && bedPlainTotal + 1e-9 >= bedAltTotal;
            boolean headWins = sameLanding || bedAltTotal + 1e-9 >= bedPlainTotal;
            if (rotateWins || headWins) {
                java.util.List<Segment> path = introBedPath(outro, intro, vamp, decided, bed,
                        best.windowSecs, rotateWins ? bedRotate : Double.NaN);
                if (path != null) {
                    bedPath = path;
                    if (!sameLanding && !rotateWins) {
                        // **进场点搬到落床段起点**：这一段路径的 B 侧素材就是它，而"B 从哪儿
                        // 开始响"是渲染层按 `Cue.introEntrySecs` 铺的；两处不一致，交叉淡化
                        // 那一段就会变成"落床段的尾巴 对着 B 别处的素材"。
                        // 搬完**重新评一次分**：说明行里那七项必须是这套点位自己的。
                        // （旋转那一条走不到这里：那时进场点本来就是落床段里的那个落点，
                        // 不用搬。）
                        introEntry = bed.from;
                        best = evaluate(outro, intro, outroCut, introEntry, best.windowSecs)
                                .withLineGap(lineGapTerm(outroSpans, introSpans, outroCut,
                                        introEntry, best.windowSecs))
                                .withStructure(structureTerm(outroCut, outroStructure,
                                        introEntry + best.windowSecs * 0.5, structureTarget,
                                        STRUCTURE_OUTRO_SCALE, introScale))
                                .withIntensity(bridge.measure
                                        ? Double.valueOf(Math.abs(
                                                intensityAhead(outro, outroCut, metricAhead)
                                                        - intensityAhead(intro, introEntry,
                                                                metricAhead)))
                                        : null, bridge.score)
                                .withMelody(melodyOrNull(melodyMatch(outro, outroCut,
                                        intro, introEntry, best.windowSecs)), null);
                    }
                }
            } else {
                Logger.debug("[qplayer-engine] 三段路径：B 的落床段起点在**同一套几何**下分更低"
                                + "（落床 {} / 搜索挑的 {}）→ 这一次不用它",
                        f2(bedAltTotal), f2(bedPlainTotal));
            }
        }
        int cutIndex = outro.indexAt(outroCut);
        int entryIndexBest = intro.indexAt(introEntry);
        double tailDrop = Math.max(0.0, outroDuration - (outroCut + best.windowSecs));
        String note = "自动点位：出场 " + fmt(outroCut) + "s（丢尾 " + fmt(tailDrop) + "s）、进场 "
                + fmt(introEntry) + "s、交叠 " + fmt(best.windowSecs) + "s"
                // 旋律永远第一位（2026-10-06 第一阶段工作一）：**读数要看得见**——
                // 它是压倒性权重（8.0），不打印的话没法判断"这一对到底量到没有"。
                // null = 这一对没量到 chroma（那时这一项不参与总分）。
                + (best.melodyToScore == null ? "（旋律重合 没量到、"
                        : "（旋律重合 " + f2(best.melodyToScore.doubleValue()) + "、")
                + (best.emotionToScore == null ? ""
                        : "情绪 " + f2(best.emotionToScore.doubleValue()) + "、")
                + "波形重合 " + f2(best.overlap) + "、淡化起伏 " + f2(best.ripple)
                + "、频段互补 " + f2(best.bands) + "、低频冲突 " + f2(best.lowEnd)
                + "、人声重叠 " + f2(best.vocal)
                + "（叠唱 " + fmt(vocalOverlapSecs(outro, intro, outro.indexAt(outroCut),
                        intro.indexAt(introEntry),
                        (int) Math.round(best.windowSecs / HOP_SECS))) + "s）"
                + "、响度差 " + f2(best.level)
                + "、" + describe(outro, cutIndex) + "/" + describe(intro, entryIndexBest)
                + "、" + best.beatNote
                + "；候选 " + windows.length + " 个窗口 × " + pairs
                + " 对点位，取总分最高）"
                + "，" + alignmentNote(outro, intro)
                + String.format(java.util.Locale.US, "；结构：尾声起点 %s / 进场起点 %s",
                        outroStructure >= 0.0 ? fmt(outroStructure) + "s" : "无",
                        introStructure >= 0.0 ? fmt(introStructure) + "s" : "无");
        // ── 尾奏 vamp 与循环计划**已经在上面算完了**（见那一段的注释）──────────────
        //
        // 这里只做两件事：把结论写进说明行与 Cue、以及把"这一次到底走哪条路径"定下来
        // （三段路径 / 显式化的单段路径 / 没有路径）。**判断不在这里做**——`decided`、
        // `bed`、`bedPath` 都在说明行拼出来之前就定了，因为三段路径会把 B 侧的起播点
        // 定下来（搬到落床段起点、或者用旋转点从搜索挑的那个落点起播），
        // 说明行必须写最终那一套几何。
        if (vamp != null) {
            note = note + String.format(java.util.Locale.US,
                    "；尾奏 vamp %s→%ss（%.1fs、自相似 %.2f、人声 %.0f%%）可循环",
                    fmt(vamp.from), fmt(vamp.to), Double.valueOf(vamp.length()),
                    Double.valueOf(vamp.selfSim), Double.valueOf(100.0 * vamp.vocalFrac));
            if (loopIsWired(decided)) {
                outroLoop = decided;
                note = note + String.format(java.util.Locale.US,
                        "；尾奏循环：%s→%ss 循环 %d 次（%s）",
                        fmt(decided.span.from), fmt(decided.span.to),
                        Integer.valueOf(decided.repeats), decided.reason);
                if (bedPath != null) {
                    // **三段路径**（用户第三个例子）：A 的尾奏段 + A 的循环段 ×repeats
                    // + B 的落床段 ×repeat。它是**搜索那一套打分挑出来的**（同一个出场点、
                    // 同一个窗口、同一串加分项，见上面那段长注释），所以这里只负责说清
                    // 它长什么样、以及那两项分数差多少。
                    //
                    // **旋转有旋转的说法**：落床段从搜索挑的那个落点起播时，"落床段起点"
                    // 这三个字就不是事实了（真正的起播点是那个落点）——说明行必须描述事实，
                    // 所以这一支单独写一句，并把"落床段起播点"与"搜索挑的"两个数报出来
                    // （它们相等，那正是旋转要做的事）。不旋转时一个字都不变（今天的形状）。
                    segments = bedPath;
                    Segment head = bedPath.get(0);
                    Segment loopSeg = bedPath.get(1);
                    Segment bedSeg = bedPath.get(2);
                    double barA = 4.0 * 60.0 / outro.tempo.bpm;
                    double barB = intro.tempo != null && intro.tempo.bpm > 0f
                            ? 4.0 * 60.0 / intro.tempo.bpm : barA;
                    String bedClause = bedSeg.rotated()
                            ? String.format(java.util.Locale.US,
                                    "B 的落床段 %.1f 小节 ×%d（从搜索挑中的 %.1f s 起播、"
                                            + "绕回段首：一遍 = [落点, 段末) + [段首, 落点)）；"
                                            + "同一套几何下 落床段起播点 %.3f / 搜索挑的 %.3f",
                                    Double.valueOf(bedSeg.spanSecs() / barB),
                                    Integer.valueOf(bedSeg.repeat),
                                    bedSeg.rotateStartSecs, Double.valueOf(bedAltTotal),
                                    Double.valueOf(bedPlainTotal))
                            : String.format(java.util.Locale.US,
                                    "B 的落床段 %.1f 小节 ×%d；同一套几何下 落床段起点 "
                                            + "%.3f / 搜索挑的 %.3f",
                                    Double.valueOf(bedSeg.spanSecs() / barB),
                                    Integer.valueOf(bedSeg.repeat), Double.valueOf(bedAltTotal),
                                    Double.valueOf(bedPlainTotal));
                    note = note + String.format(java.util.Locale.US,
                            "；路径：%s（三段：A 的尾奏段 %.1f 小节 + 循环段 ×%d + %s）"
                                    + "→ B 的主歌从落床段末尾接着播",
                            describePath(bedPath), Double.valueOf(head.spanSecs() / barA),
                            Integer.valueOf(loopSeg.repeat), bedClause);
                } else {
                    // 三段路径没用上（没有落床段 / 几何不成立 / 同一套几何下分更低）：
                    // 照旧把决策显式化成那**一条等价路径**（循环段 × repeats）。
                    // 说不出来就留 null（见 outroLoopPath 的注释）：那不该让这一次过渡
                    // 失去循环——渲染层会按决策自己合成那一段。
                    segments = outroLoopPath(outro, decided);
                    if (segments != null) {
                        note = note + "；路径：" + describePath(segments);
                    }
                }
            } else if (decided != null) {
                // **决策层说要循环，但这一档还没接线**——这件事必须留痕，否则用户读日志时
                // 只能看到"尾奏 vamp …可循环"却没有下文，分不清是"没接"还是"判错了"。
                // 降在 debug 档：它是**没发生的事**，不该占默认日志，更不该进 plan 的说明行
                // （"不循环时逐位不变"那条性质就靠默认路径上这一行不出现）。
                // 措辞修正（2026-11-20）：原文"还没接线"读起来像漏做，实际是**按设计**不接
                // ——4 条判据（AutoCueOutroLoopTest / KeyClash / GridTierRampSuper / AiWindow）
                // 明确钉着"对齐网格这一档有意不接线"，其中 AI 模式那条的理由是
                // "AI 模式没有时间不够这回事"。改成"按设计不接"，日志不再误导。
                Logger.debug("[qplayer-engine] 尾奏循环：决策层给了计划（{}），但这一档**按设计不接**"
                                + "（只接「买时间 / 档位点名循环 / 确认重复」这三类，"
                                + "见 AutoCue.loopIsWired 与那 4 条判据）→ 这一次不循环",
                        decided.reason);
            }
        }
        // **说清「为什么不是别的候选」**（2026-10-02，用户路线图 W5）。
        //
        // 只念七/八/九项分数回答不了用户真正会问的那句「为什么不选另一处」——
        // 那需要把**次优**候选摆出来，并指出两者差在哪一项上：差 0.01 是「几乎打平、
        // 靠加分项定的」，差 0.1 且集中在某一项上才是「那一项否掉了它」。
        // 只在**装了歌词源**时才报"测不出来"——没有歌词源（宿主没接、判据跑的是无歌词路径）
        // 时一个字都不加，于是"没有歌词 ⇒ 逐位相同"这条性质仍然成立（位指纹钉着）。
        if (bridge.lyricGate && lyricSpanSource != null) {
            // 歌词侧测不出来的原因要**看得见**（用户日志里出现过「拿到 1 行」）：
            // 只有一侧够用、或者行数太少时，"排开歌词"这道门根本行使不了，
            // 而用户看到的现象就是"还是卡在歌词中间"——不说明白就只能靠猜。
            int a = outroSpans == null ? -1 : outroSpans.starts.length;
            int b = introSpans == null ? -1 : introSpans.starts.length;
            boolean usable = a >= 2 && b >= 1;
            if (!usable) {
                note = note + String.format(java.util.Locale.US,
                        "；歌词：测不出（出场 %s / 进场 %s，需要真实起止）",
                        a < 0 ? "没有" : a + " 行", b < 0 ? "没有" : b + " 行");
            } else {
                // **每一道歌词条件各挡掉了多少候选**（2026-10-04 补）。
                //
                // 用户报的是「他切的点刚好就是歌词正在念的时候，我测的所有歌曲都这样」——
                // 这类"门明明开着却像没开"的故障，靠读代码是看不出来的（实测原因就是
                // 三道条件的合取在真实素材上不可满足，于是阶梯一路降到不看歌词的档位，
                // 日志里却一个字都没说）。所以把这四个数报出来：**哪个是 0 就说明哪道
                // 条件从来没被行使过**，哪道最大就是它把候选挡光了。
                int blockedTotal = lyricBlocked[0] + lyricBlocked[1] + lyricBlocked[2];
                if (lyricLegalPoints < 0) {
                    // 没有筛（没歌词、或者桥接关着）：一个字都不加，"没有歌词 ⇒ 逐位相同"
                    // 这条性质靠这个分支成立。
                } else if (lyricAnchorRelaxed) {
                    note = note + String.format(java.util.Locale.US,
                            "；歌词：锚点范围里没有能对齐的落点，**放宽锚点**后在 %d 个格子"
                                    + "里挑（结构锚点让位给歌词规则）",
                            Integer.valueOf(lyricLegalPoints));
                } else if (lyricLegalPoints >= MIN_LYRIC_GAP_CELLS) {
                    note = note + String.format(java.util.Locale.US,
                            "；人声段：融合结束对齐句头（可用落点 %d / 共 %d 个格子）",
                            Integer.valueOf(lyricLegalPoints), Integer.valueOf(lyricScanPoints));
                } else {
                    note = note + String.format(java.util.Locale.US,
                            "；歌词：**这条规则没能行使**——进场范围里整段都在唱，"
                                    + "句子之间只剩 %d 个落点（少于 %d）→ 没有筛，按没有歌词的做法挑",
                            Integer.valueOf(lyricLegalPoints),
                            Integer.valueOf(MIN_LYRIC_GAP_CELLS));
                }
                double gain = headGain(introSpans, best.entrySecs, best.windowSecs);
                if (!Double.isNaN(gain)) {
                    double head = Double.NaN;
                    for (int i = 0; i < introSpans.starts.length; i++) {
                        if (introSpans.starts[i] >= best.entrySecs) {
                            head = introSpans.starts[i];
                            break;
                        }
                    }
                    note = note + String.format(java.util.Locale.US,
                            "；第一句句头 %.2fs 出现时 B 已推到 %.0f%%%s",
                            Double.valueOf(head), Double.valueOf(100.0 * gain),
                            gain < ENTRY_HEAD_GAIN_MIN
                                    ? "（**偏低**：句头会被 A 压住，听得清时这句已经唱了一段）" : "");
                }
                if (derivedWindowSecs > 0.0) {
                    note = note + String.format(java.util.Locale.US,
                            "；交叠时长由乐句规则算出（B 的乐句周期 − gA − 余量），"
                                    + "在候选里加了 %.1fs；用户设的值仍是上界",
                            Double.valueOf(derivedWindowSecs));
                }
                if (!Double.isNaN(lyricPhraseGap) && lyricPhraseGap > 0.0) {
                    note = note + String.format(java.util.Locale.US,
                            "；A 唱完隔 %.1fs（A 自己的乐句间隔）才是 B 的下一句",
                            Double.valueOf(lyricPhraseGap));
                }
                note = note + String.format(java.util.Locale.US,
                        "；歌词闸门：挡住 %d 对（融合结束没对齐 B 的句头 %d、没对齐 A 的句尾 %d、乐句栅格 %d）",
                        Integer.valueOf(blockedTotal), Integer.valueOf(lyricBlocked[0]),
                        Integer.valueOf(lyricBlocked[1]), Integer.valueOf(lyricBlocked[2]));
            }
        }
        if (!gateNote.isEmpty()) {
            note = note + gateNote;
        }
        String runnerUp = runnerUpNote(best, second);
        if (!runnerUp.isEmpty()) {
            note = note + "；" + runnerUp;
        }
        Logger.debug("[qplayer-engine] {}", note);
        if (rankedSink != null) {
            // 收尾时把 best 放回队首（note 的「次优」用的是 second，与这个榜无关）：
            // 榜里可能因为去重规则没收录 best（例如它被后来的同点位更高分替换过），
            // 而 `topCues` 的契约是「第 0 个与 plan 的结论完全一致」。
            rankedSink.clear();
            rankedSink.add(best);
            for (int i = 0; i < ranked.size(); i++) {
                Score sc = ranked.get(i);
                if (Math.abs(sc.cutSecs - best.cutSecs) < BEAT_APART_SECS
                        && Math.abs(sc.entrySecs - best.entrySecs) < BEAT_APART_SECS) {
                    continue;
                }
                rankedSink.add(sc);
            }
        }
        // intensityOk 报的是**这件事的真相**（差有没有超标），不是"闸门有没有出手"：
        // 第一级（{@link Bridge#MEASURE}）正是靠它决定要不要走第二级。
        // outroLoop：上面算出来的尾奏循环计划（null = 不需要循环，渲染路径照旧）。
        return new Cue(outroCut, introEntry, best.windowSecs, tailDrop, 1.0 - best.total, note,
                gapOf(best), !bridge.measure || gapOf(best) <= bridge.vetoGap, outroLoop,
                segments);
    }

    /** 「硬约束否掉了谁、理由是什么」的那半句日志（理由取更严重的那一条）。 */
    private static String gateNoteFor(Score vetoed, double chosenCut, double fromEnd,
                                      double outroDuration) {
        boolean cutTooEarly = outroDuration > 0.0
                && fromEnd > MAX_CUT_FROM_END_FRACTION * outroDuration;
        String why = cutTooEarly
                ? String.format(java.util.Locale.US,
                        "出场点在 %ss（距曲尾 %.1fs = 全曲 %.0f%%，超过 %.0f%%）",
                        fmt(vetoed.cutSecs), Double.valueOf(fromEnd),
                        Double.valueOf(100.0 * fromEnd / Math.max(1.0, outroDuration)),
                        Double.valueOf(100.0 * MAX_CUT_FROM_END_FRACTION))
                : String.format(java.util.Locale.US, "叠唱 %.1fs（超过 %.1fs）",
                        Double.valueOf(vetoed.vocalSecs), Double.valueOf(VOCAL_GATE_SECS));
        return String.format(java.util.Locale.US,
                "；硬约束：否掉了%s的更高分候选，改取两个约束都满足的 %ss",
                why, fmt(chosenCut));
    }

    /**
     * 强度差 → 贴合分（0..1，1 = 完全贴合）。
     *
     * <p>线性到 {@link #INTENSITY_SCALE} 为止，之后归零：比它更差的差别在听感上都是
     * "掉下去了"，没必要继续区分（也避免重尾把分数拉出很细的长尾）。
     */
    static double intensityTerm(double gap) {
        if (Double.isNaN(gap)) {
            return 0.0;
        }
        if (gap <= INTENSITY_DEAD_ZONE) {
            return 1.0;
        }
        double span = Math.max(1e-9, INTENSITY_SCALE - INTENSITY_DEAD_ZONE);
        return Math.max(0.0, 1.0 - (gap - INTENSITY_DEAD_ZONE) / span);
    }

    /** 「排开歌词」改选了一句实话。 */
    private static String lyricGateNote(Score relaxed, Score gated, String how) {
        return String.format(java.util.Locale.US,
                "；排开歌词：%s，把 %s → %s 换成 %s → %s（融合中点不落在歌词中间）",
                how, fmt(relaxed.cutSecs), fmt(relaxed.entrySecs),
                fmt(gated.cutSecs), fmt(gated.entrySecs));
    }

    /** 某个候选的强度差（没算过就是中性值）。 */
    private static double gapOf(Score score) {
        return score == null || score.intensityGap == null
                ? INTENSITY_UNKNOWN_BAND : score.intensityGap.doubleValue();
    }

    /**
     * 强度闸门改选了一句实话：**掉了多少 → 补上了多少**。
     *
     * <p>只报两个数，不解释算法：要回答的是「它有没有把掉下去的那一处换掉」，
     * 而日志预算有判据钉着（{@code AutomixLogBudgetReferenceTest}），说多了会红。
     */
    private static String intensityGateNote(Score relaxed, Score gated) {
        return String.format(java.util.Locale.US,
                "；强度闸门：把强度差 %.3f 的 %s → %s 换成差 %.3f 的 %s → %s",
                Double.valueOf(gapOf(relaxed)), fmt(relaxed.cutSecs), fmt(relaxed.entrySecs),
                Double.valueOf(gapOf(gated)), fmt(gated.cutSecs), fmt(gated.entrySecs));
    }

    /** 被硬约束否掉的那个候选的出场点（只用在日志里，说清否掉的是哪一处）。 */
    private static double secondCuts(Score s) {
        return s.cutSecs;
    }

    /**
     * <b>AI 模式的两套候选方案比较</b>：现有接法（用户设的窗口） vs <b>声波重合</b>（窗口可调）。
     *
     * <p>用户要的「AI 自动模式要真正分析两首曲子选最优方案，声波重合也纳入候选」落在这一处。
     * 比较用的是同一把尺子（{@link #plan}）：两个候选各自去<strong>挑自己最好的点位与窗口</strong>，
     * 然后比总分——所以这不是「拿同一个点位换两种接法」，而是<strong>两套完整方案</strong>比高下。
     *
     * <p>两套方案的差别（加成删除后**只剩这一条**，而且它是实测项不是模型项）：
     *
     * <ul>
     *   <li><strong>窗口能不能调</strong>：现有接法用用户设的那个秒数（AI 风格是拍数算出来的），
     *       声波重合可以按分析结果在它附近选（见 {@code AutoCueTransition.windowCandidates}）。
     *       所以声波重合赢，只能是它挑出来的那个窗口在七项上真的更高。</li>
     * </ul>
     *
     * <p>{@code plainScore} 现在只当<b>闸门</b>用（调性差小到不值得移调时不让声波重合进场，
     * 见下面的重载），<b>不再参与打分</b>——原来那个 {@code (1 − plainScore)×0.35} 的
     * 调性加成（一个模型项）已按用户要求整个删除。
     *
     * @param outro           出场曲包络
     * @param intro           进场曲包络
     * @param baselineSecs    现有接法要用的窗口（秒，用户设的）
     * @param harmonicWindows  声波重合的候选窗口（秒；由 {@code AutoCueTransition.windowCandidates} 给，
     *                     与它那一档自己用的候选**同一份**，避免两处各写一份而慢慢分叉）
     * @param plainScore      两边调性相似度（0..1）；{@code <= 0} 表示没判出来（那时不加不减）
     * @return 比较结论；任一候选都算不出来时返回 {@code null}（调用方保留原判定）
     */
    public static PlanChoice comparePlans(Envelope outro, Envelope intro,
                                          double baselineSecs, double[] harmonicWindows,
                                          double plainScore) {
        return comparePlans(outro, intro, baselineSecs, harmonicWindows, plainScore, true);
    }

    /**
     * 同上，外加「这次移调到底值不值得做」。
     *
     * <p>用户 2026-09-29 报的现象：<b>AI 自动档近期几乎每次都选声波重合</b>
     * （「没绷住，不过效果挺好」）。查下来是两处结构性偏置，与素材好坏无关：
     *
     * <ol>
     *   <li><b>调性加成被无条件记上</b>：原先只要 {@code plainScore > 0} 就加
     *       {@code (1−plainScore)×0.35}，哪怕这次的调性差根本小到不值得移调
     *       （{@code Harmonic.match} 自己会拒绝），或者压根不会发生移调——
     *       等于给一份<b>拿不到的好处</b>记了分。
     *       <b>后来这个加成被整个删掉</b>（用户要求），现在两边同一把尺子；</li>
     *   <li><b>候选数量不对等</b>（<b>已撤回此项"修法"</b>）：声波重合那一侧给的是
     *       <b>一组</b>窗口候选，而基准侧只算<b>一个</b>——乍看是偏置，其实两边都是
     *       「按它实际会用的窗口打分」：声波重合会真的按分析结果调窗口，基准侧不会
     *       （AI 风格的窗口是拍数算出来的）。给基准侧展开候选、却仍然按标称窗口执行，
     *       反而是用它不会做的事去给它加分。要让基准侧也能调，得在 AI 风格内部做
     *       （窗口变了，拍点算出来的跳过量与进度映射都要跟着改）——那是另一件事。</li>
     * </ol>
     *
     * <p>所以现在：两侧各自按<b>它自己实际会用的那个窗口</b>打分（用户设的值仍是中心，
     * 与「交叉淡化也按分析调窗口」是同一条纪律）；而在调性本来就合
     * （{@code shiftWorthwhile == false}）时<b>根本不给声波重合候选</b>——
     * 它的全部优势就是那次移调。
     *
     * @param shiftWorthwhile {@code Harmonic.Match.shouldShift}：调性差大到值得移调吗
     */
    public static PlanChoice comparePlans(Envelope outro, Envelope intro,
                                          double baselineSecs, double[] harmonicWindows,
                                          double plainScore, boolean shiftWorthwhile) {
        // **两侧各自按"它实际会用的那个窗口"打分。**
        //
        // 上一轮我给基准侧也展开了一组候选，那是错的：基准那套方案**执行时只会用它自己的
        // 标称窗口**（AI 风格的窗口是拍数算出来的，AutoCue 那一层对它 tune=false），
        // 拿一组它不会执行的更好的窗口给它打分，等于偏袒基准侧——分数与行为两张皮。
        // 声波重合那一侧不同：它**会**按分析结果调窗口（`decided.windowSecs` 真的落到
        // HarmonicBlend 上），所以它按一组候选打分是诚实的。
        Cue baseline = plan(outro, intro, baselineSecs);
        Cue harmonicCue = (!shiftWorthwhile || harmonicWindows == null)
                ? null : plan(outro, intro, harmonicWindows);
        if (baseline == null && harmonicCue == null) {
            return null;
        }
        double baselineScore = baseline == null ? -1.0 : 1.0 - baseline.cost;
        double harmonicSeven = harmonicCue == null ? -1.0 : 1.0 - harmonicCue.cost;
        // **调性加成已整个删除**（用户 2026-09-29 两次要求：先降到 0.07，随后"把加分删掉"）。
        //
        // 删除前它长这样：只要 plainScore > 0 就加 (1−plainScore)×0.35（后来 0.07），
        // 只在一个 0.08 的窗口内生效。用户日志里出现过 七项 0.434 + 加成 0.262 = 0.696 压过
        // 基准 0.695 —— 七项差 0.26，靠一个**模型项**翻盘。那不是"调性收益补上了别的方面的损失"。
        //
        // 现在两个方案就在同一把尺子上比：七项，各自按自己**实际会用的那个窗口**打分
        // （见上面那段）。声波重合唯一剩下的特权是它能按分析结果调窗口——赢就得靠七项真的更高。
        // 判据 `theWinnerIsDecidedByTheSevenTermsAlone` 钉着这条不变量（harmonicScore 必须
        // 等于 1 − cost，且胜负必须与两侧七项的大小一致），谁把加成塞回来当场红。
        double harmonicScore = harmonicSeven;
        boolean harmonic = harmonicCue != null
                && (baseline == null || harmonicScore > baselineScore);
        // 日志两侧都写全：**哪一侧赢了、各自七项多少、各自用了哪个窗口**。
        // 上一版要写"七项 + 加成"两段是因为加成是独立加数；现在只剩一段，别再写"含调性收益"。
        String harmonicPart = harmonicCue == null
                ? (shiftWorthwhile ? "算不出来" : "不参与（调性本来就合，不必移调）")
                : String.format(java.util.Locale.US, "七项 %.3f",
                        Double.valueOf(harmonicSeven));
        String why = String.format(java.util.Locale.US,
                "AI 方案比较：现有接法 %.3f（窗口 %.1fs） vs 声波重合 %s（窗口 %.1fs）→ 选 %s",
                Double.valueOf(baselineScore),
                Double.valueOf(baseline == null ? baselineSecs : baseline.windowSecs),
                harmonicPart,
                Double.valueOf(harmonicCue == null ? 0.0 : harmonicCue.windowSecs),
                harmonic ? "声波重合" : "现有接法");
        // 一条日志把两侧的数字都留出来：这条决策以前只能从 note 里间接看
        Logger.debug("[qplayer-engine] {}", why);
        return new PlanChoice(harmonic, baselineScore, harmonicScore, why,
                harmonicCue == null
                        ? (baseline == null ? baselineSecs : baseline.windowSecs)
                        : harmonicCue.windowSecs,
                harmonic ? harmonicCue.note : (baseline == null ? "" : baseline.note),
                harmonic ? harmonicCue : baseline);
    }

    /** {@link #comparePlans} 的结论。 */
    public static final class PlanChoice {

        /** 是否选声波重合。 */
        public final boolean harmonic;

        /** 现有接法那套方案的总分（0..1；算不出来是 −1）。 */
        public final double baselineScore;

        /** 声波重合那套方案的总分（0..1，就是它的七项分 {@code 1 − cost}）。 */
        public final double harmonicScore;

        /** 给人看的一句理由。 */
        public final String why;

        /** 选中的窗口（秒）。 */
        public final double windowSecs;

        /** 选中那套方案的挑点说明。 */
        public final String note;

        /**
         * <b>选中那一套的完整方案</b>（点位 / 进场点 / 交叠 / 丢尾四个数都在里面）。
         *
         * <p>2026-09-30 加的：调用方（{@code AiMix}）原先只拿得到 {@link #windowSecs}，
         * 于是「AI 比较选了声波重合 @ 12 s」这件事在**执行**时只剩一个窗口——出场点、
         * 进场点、被丢掉的尾巴全丢了，实际跑的还是现有接法那一套点位（用户报的
         * 「AI 比较里选 W1、实际装载 W2」就是这件事的窗口那一面）。
         * 要「决策与执行同一套数」，就得把整套结论交出去。
         */
        public final Cue cue;

        PlanChoice(boolean harmonic, double baselineScore, double harmonicScore,
                   String why, double windowSecs, String note, Cue cue) {
            this.harmonic = harmonic;
            this.baselineScore = baselineScore;
            this.harmonicScore = harmonicScore;
            this.why = why;
            this.windowSecs = windowSecs;
            this.note = note;
            this.cue = cue;
        }
    }

    /** 前缀和（{@code out[i] = sum(rms[0..i-1])}），用于 O(1) 求窗口均值。 */
    private static double[] prefixSums(float[] values) {
        double[] out = new double[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            out[i + 1] = out[i] + values[i];
        }
        return out;
    }

    /** 从 {@code start} 起 {@code hops} 格的均值（越界就夹到末尾；末端不足时按实际格数算）。 */
    private static double windowMean(double[] prefix, int start, int hops, int length) {
        int from = Math.max(0, Math.min(start, length - 1));
        int to = Math.min(length, from + Math.max(1, hops));
        return (prefix[to] - prefix[from]) / Math.max(1, to - from);
    }

    /** 候选窗口：去掉非正数与重复值，升序。 */
    private static double[] sanitizeWindows(double[] candidates) {
        java.util.TreeSet<Double> set = new java.util.TreeSet<Double>();
        for (int i = 0; i < candidates.length; i++) {
            double w = candidates[i];
            if (w > 0.25 && !Double.isNaN(w) && !Double.isInfinite(w)) {
                set.add(Double.valueOf(Math.min(w, MAX_WINDOW_SECS)));
            }
        }
        double[] out = new double[set.size()];
        int i = 0;
        for (Double d : set) {
            out[i++] = d.doubleValue();
        }
        return out;
    }

    /**
     * 第 8 项：<b>跳过去之后第一句歌词来得是不是时候</b>。
     *
     * <p>用户 2026-09-29 要的判据（原话：「选择的点位跳跃后和下一句歌词的间隔尽量和 A 的最后
     * 几个差不多，观感会好一点」）。做法：
     *
     * <ul>
     *   <li><b>A 的节奏</b> {@code gA}：出场点之前最近 4 个「相邻歌词行」间距的<b>中位数</b>
     *       （中位数而不是均值：一句拖长的尾音不该把节奏带跑）；</li>
     *   <li><b>候选的落差</b> {@code gB}：从进场点起，B 的下一句歌词还有多久；</li>
     *   <li><b>得分</b> {@code 1 − min(1, |gB − gA| / max(gA, 0.4))}——
     *       分母兜一个 0.4 秒，免得 {@code gA} 极小时把微小差异放大成 0 分；</li>
     *   <li><b>罚「歌词正落在交叉期里」</b>：那几秒里两句人声会搅在一起，得分乘 0.5
     *       （乘而不是判死：它只是八项里的一项，别让它一句话否决别的七项）。</li>
     * </ul>
     *
     * <p><b>量不出来就返回 {@code null}（这一项不加），而不是给 0 分</b>：
     * 「不知道」和「很差」是两回事，混了就会拿缺数据去惩罚候选。
     */
    /**
     * 色度<b>新颖度</b>曲线：{@code novelty[i] = Σ_p |mean(色度[i−W..i])_p − mean(色度[i..i+W])_p|}。
     *
     * <p>这是「分段」的最小可用形式：某一点左右两侧的和声分布差得越多，它越像一个段落边界。
     * 标准做法（Foote）是自相似矩阵 + 棋盘核，那是 O(帧²) 的内存（4 分钟的歌 = 4 亿项，不可行）；
     * 这里用**局部**均值差，效果等价而代价是 O(帧 × 12)。
     *
     * @param chroma 每帧 12 个值（扁平，已按总和归一），来自 {@code SpectralOnsets.Stream}
     * @param frames 帧数
     * @param hopSecs 每帧多少秒
     * @return 每帧一个值（前后 {@link #NOVELTY_WINDOW_SECS} 内的窗算不出来时为 0）
     */
    static float[] noveltyCurve(float[] chroma, int frames, double hopSecs) {
        float[] out = new float[Math.max(0, frames)];
        if (chroma == null || frames <= 0 || hopSecs <= 0.0) {
            return out;
        }
        // **多尺度**：乐句边界在多个时间尺度上都该有变化，只看一个尺度时最大的变化往往
        // 是淡出/前奏第一下（实测：单尺度 2 s 把 skydive 的尾声起点测成 183.9）。
        // 三个尺度取和，短尺度抓变化点、长尺度抓"这一段和那一段不一样"。
        int[] widths = new int[NOVELTY_SCALES.length];
        for (int s2 = 0; s2 < NOVELTY_SCALES.length; s2++) {
            widths[s2] = Math.max(1, (int) Math.round(NOVELTY_SCALES[s2] / hopSecs));
        }
        // 用**滑动和**：每个尺度维护「左边 w 帧 / 右边 w 帧」两组的逐音高类和，
        // 每前进一帧只加减各 12 个数。原来是每个帧位置都重新累加 2w 帧 × 12 个音高类，
        // 实测 190 s 素材 235 ms（而且这一段当时还没接线，纯浪费）——现在 O(帧 × 尺度 × 12)。
        for (int s2 = 0; s2 < widths.length; s2++) {
            int w = widths[s2];
            if (w <= 0 || 2 * w >= frames) {
                continue;
            }
            double[] left = new double[SpectralOnsets.PITCH_CLASSES];
            double[] right = new double[SpectralOnsets.PITCH_CLASSES];
            for (int k = 0; k < w; k++) {
                for (int pc = 0; pc < SpectralOnsets.PITCH_CLASSES; pc++) {
                    left[pc] += chroma[k * SpectralOnsets.PITCH_CLASSES + pc];
                    right[pc] += chroma[(k + w) * SpectralOnsets.PITCH_CLASSES + pc];
                }
            }
            for (int i = w; i < frames - w; i++) {
                double sum = 0.0;
                for (int pc = 0; pc < SpectralOnsets.PITCH_CLASSES; pc++) {
                    sum += Math.abs(left[pc] / w - right[pc] / w);
                }
                out[i] += (float) sum;
                // 窗口右移一帧：left 去掉第 i−w 帧、加上第 i 帧；right 去掉第 i 帧、加上第 i+w 帧
                for (int pc = 0; pc < SpectralOnsets.PITCH_CLASSES; pc++) {
                    left[pc] += chroma[i * SpectralOnsets.PITCH_CLASSES + pc]
                            - chroma[(i - w) * SpectralOnsets.PITCH_CLASSES + pc];
                    right[pc] += chroma[(i + w) * SpectralOnsets.PITCH_CLASSES + pc]
                            - chroma[i * SpectralOnsets.PITCH_CLASSES + pc];
                }
            }
        }
        return out;
    }

    /**
     * 从新颖度曲线里取<b>最后一个</b>（{@code last=true}）或<b>第一个</b>强边界（秒）。
     *
     * <p>门槛是**相对**的（整曲新颖度的 p90 × {@link #NOVELTY_PEAK_RATIO}）：
     * 绝对门槛在安静的歌与密集的歌之间没法通用。只认「局部极大」（比左右邻居都高），
     * 免得在一条宽而平的新颖度坡上取到坡边。
     *
     * @return 秒数；区间里没有强边界时返回负值
     */
    static double noveltyBoundary(float[] novelty, double hopSecs, boolean last,
                                  double fromSecs, double toSecs) {
        return noveltyBoundary(novelty, null, hopSecs, last, fromSecs, toSecs);
    }

    /**
     * 同上，但带一道常识过滤：**边界右边不能掉进衰减/静音**。
     *
     * <p>为什么必须有（2026-10-01 实测）：只按「局部谱变化最大」找边界，找出来的是**最后的淡出**
     * ——skydive 被测成 188.2 s（Apple 选 174.0 s），而那一下正是电平从 0.7 掉到 0 的地方。
     * 段落变化两边应该**都在响**；一边在响一边没了，那是结尾，不是段落边界。
     *
     * @param level 每帧谱能量（与 {@code novelty} 同时间轴）；{@code null} = 不做这道过滤
     */
    static double noveltyBoundary(float[] novelty, float[] level, double hopSecs, boolean last,
                                  double fromSecs, double toSecs) {
        if (novelty == null || novelty.length < 4 || hopSecs <= 0.0) {
            return -1.0;
        }
        float[] sorted = novelty.clone();
        java.util.Arrays.sort(sorted);
        double p90 = sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * 0.9))];
        if (p90 <= 1e-9) {
            return -1.0;
        }
        double threshold = p90 * NOVELTY_PEAK_RATIO;
        int side = Math.max(1, (int) Math.round(NOVELTY_WINDOW_SECS / hopSecs));
        int first = Math.max(1, (int) Math.round(fromSecs / hopSecs));
        int lastIdx = Math.min(novelty.length - 2, (int) Math.round(toSecs / hopSecs));
        double found = -1.0;
        for (int i = last ? lastIdx : first; last ? i >= first : i <= lastIdx; i += last ? -1 : 1) {
            if (novelty[i] < threshold
                    || novelty[i] < novelty[i - 1] || novelty[i] < novelty[i + 1]) {
                continue;
            }
            if (level != null && i - side >= 0 && i + side < level.length) {
                double before = 0.0;
                double after = 0.0;
                for (int k = i - side; k < i; k++) {
                    before += level[k];
                }
                for (int k = i; k < i + side; k++) {
                    after += level[k];
                }
                // 右边掉到左边的这个比例以下 = 那是结尾的衰减，不是段落边界
                if (after < before * NOVELTY_SIDE_LEVEL_RATIO) {
                    continue;
                }
            }
            found = i * hopSecs;
            break;
        }
        return found;
    }

    /**
     * 出场曲的<b>尾声起点</b>（秒）：平滑电平**最后一次**还在主电平上的位置。
     *
     * <p>这就是 DJ 说的「最后一个副歌结束、开始收」那一点——Apple Music 在 skydive 上选的
     * 174 s 正是那里。判定用「越过参考电平的比例」而不是「斜率」：渐弱的曲子斜率很小、
     * 又有起伏，阈值法稳得多；而「之后不再回到主电平」这一条把它与**间奏**分开
     * （间奏也是低能量，但后面会回来）。
     *
     * @return 秒数；没有可辨的尾声（一开始就淡、或低能量段之后又回到主电平）时返回负值
     */
    static double outroStartSecs(Envelope env) {
        // **不再优先取色度分段值**（2026-10-02）：那个值现在走自己的低权加分项
        // （见 noveltyBonus 与 NOVELTY_WEIGHT）。这里保持「能量法」这一条主判据的纯粹性——
        // 两者混在一起正是 2026-10-01 那次退化的来源（色度把能量法顶掉了）。
        if (env == null || env.rms.length < 8) {
            return -1.0;
        }
        float[] profile = smoothLevel(env);
        double reference = referenceLevel(profile);
        if (reference <= 1e-6) {
            return -1.0;
        }
        // ⚠️ 试过一条新口径（"最后一次接近满电平"≥ ref×0.99 ⇒ 参考对上 181.16 s ≈ Apple 181.1），
        // **已回退**：它挪动切点之后连带把窗口选择也挪了 ⇒ 弄坏了一条刻意立的契约
        //（`AutomixSmoothnessReferenceTest`：「上界不在时窗口该随 window_scale 严格变长」✗）。
        // 结论：**地标口径不能动**；要 A 落点贴 181.1，得走"精细打分"那条路（见 goal 与 docs）。
        // ① 其次找**最后一个「窗内极大」**：电平最后一次冲到局部最高，之后一路在它之下。
        //
        // 为什么不用「最后一次 ≥ 参考电平的某个比例」：渐弱很缓的曲子上那个比例太钝——
        // 实测 skydive 到 182.6 s 都还在参考电平的 95% 以上，于是尾声起点被测成 182.6，
        // 而 Apple Music 选的是 174.0。窗内极大抓的正是「开始往下掉」那一刻：
        // 最后一个副歌的尾部，之后（含结尾的渐弱）始终不超过它。
        int w = Math.max(1, (int) Math.round(STRUCTURE_PEAK_SECS / HOP_SECS));
        int last = -1;
        for (int i = 0; i < profile.length; i++) {
            boolean peak = true;
            int lo = Math.max(0, i - w);
            int hi = Math.min(profile.length - 1, i + w);
            for (int j = lo; j <= hi; j++) {
                if (profile[j] > profile[i]) {
                    peak = false;
                    break;
                }
            }
            if (peak && profile[i] >= reference * OUTRO_KEEP_RATIO) {
                last = i;
            }
        }
        if (last >= 0) {
            double tailMax = 0.0;
            for (int i = last + 1; i < profile.length; i++) {
                tailMax = Math.max(tailMax, profile[i]);
            }
            double secs = last * HOP_SECS;
            // 之后不许再回到同一高度（否则那是间奏之后的又一段副歌），也要留得下一次交接
            if (tailMax <= profile[last] && env.durationSecs - secs >= STRUCTURE_MIN_RUN_SECS) {
                return toPhraseLine(env, secs);
            }
        }
        // ② 退路：电平比例法（没有明显的「最后一座峰」时用）。
        last = -1;
        for (int i = 0; i < profile.length; i++) {
            if (profile[i] >= reference * OUTRO_KEEP_RATIO) {
                last = i;
            }
        }
        if (last < 0) {
            return -1.0;
        }
        double tailMax = 0.0;
        for (int i = last + 1; i < profile.length; i++) {
            tailMax = Math.max(tailMax, profile[i]);
        }
        if (tailMax > reference * (OUTRO_KEEP_RATIO + 0.10)) {
            return -1.0;                 // 后面又回到主电平：那是间奏，不是尾声
        }
        double secs = last * HOP_SECS;
        if (env.durationSecs - secs < STRUCTURE_MIN_RUN_SECS) {
            return -1.0;
        }
        return toPhraseLine(env, secs);
    }

    /**
     * <b>把尾声起点对到它之后的第一条乐句线（8 小节线）上</b>（2026-10-06）。
     *
     * <p>为什么需要这一层：能量法回答的是「电平从哪一格开始往下掉」，而接歌真正要落的是
     * <b>乐句边界</b>——这正是候选集一路上只收 8 小节线的理由（见 {@code candidates} 的注释：
     * 用户要的是「每个 8 小节 / 16 小节乐句边界」，不是「主歌中间切一刀」）。两者在真素材上
     * 会差出一秒多，而<b>差的这一秒多是听得出「在乐句中间切出去了」的那一秒多</b>。
     *
     * <p>取证依据（{@code tools/automix-forensics/}，Apple 在 skydive → 春めく 上的实测）：
     * Apple 的出场点是 <b>181.1 s</b>，而能量法在这首歌上给 <b>179.68 s</b>——那一格是最后一段
     * 副歌里最响的一击（原始 RMS 到 1.0），也就是「最后一个窗内极大」那一支抓到的位置；
     * 而同一个尾声里唯一的 8 小节线是 <b>181.4455 s</b>，与 Apple 差 0.35 s（落在它 ±0.5 s 带里）。
     * 独立旁证：Apple 的 A 淡化在 <b>183.79 s</b> 降到听不见，而 [181.4455, 181.4455+2.5093]
     * 这个窗口的右端正好是 <b>183.955 s</b>。
     *
     * <p>为什么<b>有界</b>（只在 {@link #STRUCTURE_TOLERANCE_SECS} 秒内推）：乐句线间距是
     * 8 小节（120 BPM 上 16 s、128 BPM 上 15 s），若地标离下一条乐句线很远，说明「能量法说
     * 的这一处」与「乐句边界」根本不是同一件事（例如尾奏是一段很长的自由延长），这时<b>以能量法
     * 为准</b>——绝不把出场点平白往后推小半句。这条界用的是打分项自己那条容忍带
     * （{@link #structureTolerance()}），不引入第二个尺度。
     *
     * <p>它只挪动<b>地标</b>，不动包络、不动候选生成规则：地标本来就是候选集里的一格
     * （{@code candidates} 的第二个来源），对到乐句线上之后那一格照旧是候选。
     */
    private static double toPhraseLine(Envelope env, double secs) {
        if (!(secs >= 0.0) || env == null) {
            return secs;
        }
        double[] bars = eightBarLines(env);
        for (int i = 0; i < bars.length; i++) {
            double delta = bars[i] - secs;
            if (delta >= 0.0) {
                return delta <= STRUCTURE_TOLERANCE_SECS ? bars[i] : secs;
            }
        }
        return secs;
    }

    /**
     * 进场曲<b>真正开始</b>的位置（秒）：平滑电平**第一次**升到主电平的位置。
     *
     * <p>Apple Music 在 春めく 上把入场点放在 15 s——那是它的前奏结束、歌起来的时刻。
     * 曲一开头就在主电平的素材返回 0（处处都是「已经开始」）。
     *
     * @return 秒数；读不出来时返回负值
     */
    static double introStartSecs(Envelope env) {
        // 同 `outroStartSecs`：色度分段值现在走自己的低权加分项（noveltyBonus），
        // 这里保持能量法这一条主判据的纯粹性。
        if (env == null || env.rms.length < 8) {
            return -1.0;
        }
        float[] profile = smoothLevel(env);
        // **参考电平只看开头固定的一段**（2026-10-05）：原来取整条剖面的 85 分位，于是
        // "真正开始"会**跟着扫描深度变**——生产的第一级扫得浅（~25 s）、第二级深扫，
        // 同一个"真正开始"能差 2 s 以上（实测：全量包络 16.3 s、浅扫包络 ≈13.8 s），
        // 锚点也就跟着差 2 s，进场点因此在 12.0 / 14.0 之间摇摆。
        // 固定成开头 {@value #INTRO_REFERENCE_SECS} 秒之后两级一致。
        double reference = introReferenceLevel(profile);
        if (reference <= 1e-6) {
            return -1.0;
        }
        for (int i = 0; i < profile.length; i++) {
            if (profile[i] >= reference * INTRO_KEEP_RATIO) {
                return i * HOP_SECS;
            }
        }
        return 0.0;
        //
        // ⚠️ **2026-10-07 试过"再退回上升起点"**（从 cross 往前走，直到掉到 35% 参考以下
        // 或前一格明显更高）：实测在 春めく 上**退过头** —— 它的电平从很早就抬着，
        // 一路退到 **2.56 s** ✗（用户口径要的是 15.0 ✗，而 95% 那一格是 17.5 ✗）。
        // 也就是说"上升起点"这件事在真实素材上没有干净的定义 ⇒ 退回原判据 ✓，
        // 改用**校准门槛**那条路（见下面 INTRO_KEEP_RATIO 的注释）。
    }

    /**
     * {@link #introStartSecs} 用的那个<strong>参考电平</strong>
     * （平滑电平的前 {@value #INTRO_REFERENCE_SECS} 秒的 85 分位）。
     *
     * <p>抽成一个函数是为了让"B 的引子"那条判据（{@link #outroLoopIntroLeadSecs}）
     * 用**同一把尺子**判"这次读数可不可信"：算术与 {@code introStartSecs} 里那几行
     * 逐字相同（移动而不是重写），行为一个字节都不变。
     */
    private static double introReferenceLevel(float[] profile) {
        int horizon = Math.min(profile.length,
                Math.max(8, (int) Math.round(INTRO_REFERENCE_SECS / HOP_SECS)));
        float[] head = new float[horizon];
        System.arraycopy(profile, 0, head, 0, horizon);
        return referenceLevel(head);
    }

    /**
     * 结构奖励（0..1）：出场点落在**尾声起点**附近、进场点落在**进场曲起点**附近。
     *
     * <p>{@code target < 0}（那侧读不出结构）时该项取中性 0.5——因为奖励是**加在总分之上**的，
     * 中性值对同一批候选是同一个常数，排序不受影响（与「没有歌词信息」那条路逐位等价）。
     */
    static double structureTerm(double cutSecs, double outroStart, double heardIntroSecs,
                                double introStart, double outroScale, double introScale) {
        // 进场侧量的是**融合中点**（heardIntroSecs），不是进场点本身——与歌词那一项同一个道理：
        // B 的增益要铺满窗口才到满，中点才是"听感上归 B"的那一刻。
        //
        // 这条规则是用户给的 Apple 参考数据逼出来的（2026-10-04）：
        // 「Sparks Fly → 炼爱 在 Apple Music 上 A 大概剩 25 s 开始、B 是 45 s 入场」。
        // 实测这对素材：B 的**真正开始**（电平首次到参考的 70%）在 **52.4 s**——
        // Apple 的 45 s 加上它那 15 秒左右的交叉，中点正好落在 52 s ✓；
        // 而我们的窗口 8 s 时进场 52.2 s、窗口 5 s 时 76.7 s（都是老规则搜出来的）。
        // 也就是说正确的规则是：**让中点对准"进场曲真正开始"那一刻**，
        // 而不是让进场点去凑音色相似。
        double outro = 0.5 * proximity(cutSecs, outroStart);
        double intro = 0.5 * proximity(heardIntroSecs, introStart);
        return outroScale * outro + introScale * intro;
    }

    /** 点位离结构目标有多近：容忍带内线性给分，带外 0；目标不可用时中性 0.5。 */
    private static double proximity(double secs, double target) {
        if (!(target >= 0.0)) {
            return 0.5;
        }
        return Math.max(0.0, 1.0 - Math.abs(secs - target) / STRUCTURE_TOLERANCE_SECS);
    }

    /** 见 {@link #STRUCTURE_TOLERANCE_SECS}：它是"落在这一段附近"的带宽。 */
    private static double structureTolerance() {
        return STRUCTURE_TOLERANCE_SECS;
    }

    /** 平滑电平剖面（{@link #STRUCTURE_SMOOTH_SECS} 的滑动平均）。 */
    private static float[] smoothLevel(Envelope env) {
        int n = env.rms.length;
        int w = Math.max(1, (int) Math.round(STRUCTURE_SMOOTH_SECS / HOP_SECS));
        float[] out = new float[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += env.rms[i];
            if (i >= w) {
                sum -= env.rms[i - w];
            }
            out[i] = (float) (sum / Math.min(i + 1, w));
        }
        return out;
    }

    /** 参考电平：平滑电平的高分位（0.85）——用分位而不是最大值，免得被一个瞬态带偏。 */
    private static double referenceLevel(float[] profile) {
        float[] copy = profile.clone();
        java.util.Arrays.sort(copy);
        return copy[Math.min(copy.length - 1, (int) Math.floor(copy.length * 0.85))];
    }

    /**
     * 一段歌词的起止（秒，升序）。
     *
     * <p><b>为什么需要结束时间</b>（2026-10-04）：原来只注入行起始时间，于是"入场是不是
     * 卡在念歌词"只能用**槽位**近似（一句从起点唱到下一句起点）。那个近似会和
     * 「节奏」那条规则**自相矛盾**——节奏偏好的是"下一句前 gA 秒"，而那正好落在槽位
     * 后半段、被判成"念到一半"，两项互相抵消，整项得 0（等于没有）。
     * 有了真实结束时间，"两句之间"才有真正的**空隙**：中点落在空隙里同时满足两条规则。
     */
    /**
     * <b>要不要用"重复段落"来桥接，以及怎么用</b>（用户 2026-10-05 的标准）。
     *
     * <p>用户的核心原则：<b>重复段落不是用来炫技的，是用来"买时间"和"对齐网格"的。</b>
     * 所以"有可以循环的段"只是**必要条件**——还要问一句"这次到底需不需要"。
     *
     * <p><b>这道"需要"的门是他那条 Apple 观察逼出来的</b>：他仔细听了 skydive → 春めく，
     * Apple **没有**循环。而按"尾奏剩余 < 8 小节"这条（那对曲子剩 10.3 s = 5.5 小节）
     * 它本该触发——所以那条 8 小节的门是**错的**，它把参考对错误地卷了进来。现在那一条
     * 的门取自"哪一档的音乐上真的会赶"，即 {@link #LOOP_SHORT_TAIL_BARS} =
     * <b>4 小节</b>：5.5 &gt; 4（参考对不循环，与耳朵一致）、2–4 小节（会赶，循环）。
     *
     * <h3>触发（需要"买时间"或"对齐网格"）</h3>
     * <ul>
     *   <li><b>买时间</b>（四条，任一条成立）：① {@code cut + window} 伸到曲子外面；
     *       ② 剩余尾奏短于这次窗口；③ <b>剩余尾奏不足 {@link #LOOP_SHORT_TAIL_BARS}
     *       （4）小节</b>——"剩得少到直接交叉淡化会很赶"本身就是触发条件（用户规格原话：
     *       「两首歌之间只剩 2–4 小节，直接交叉淡化会很赶」），不必等到"窗口放不下"；
     *       ④ <b>下一首的引子也不足 {@link #LOOP_SHORT_INTRO_BARS}（4）小节</b>——
     *       用户规格里「下一首 intro &lt; 8 小节」那一条（门为什么取 4 见那个常量），
     *       量的是 B 的"真正开始"（{@link #outroLoopIntroLeadSecs}）；
     *       ③与④是同一件事的两侧：交了 A 的尾奏，也要交 B 的前奏；</li>
     *   <li><b>对齐网格</b>：BPM 差在 3%~8%（要一段来渐进变速），或调性冲突但节奏兼容
     *       （要一段纯节奏来桥，判据见 {@link #keyClashWithCompatibleGrid}）。</li>
     * </ul>
     *
     * <h3>否决（用户列的"最好别用"）</h3>
     * <ul>
     *   <li>BPM 差 &gt; 8%：硬 loop 变速音质会怪，不如淡出或硬切；</li>
     *   <li>没有可循环的纯器乐段（{@link #outroVampSpan} 为 null，人声密集段已经被它挡掉）；</li>
     *   <li>节拍网格不稳（自由节奏/渐弱结尾/现场版/古典/爵士）：用
     *       {@link Analysis.Tempo#confidence} 挡——**这是用户规格里我们原先完全空白的一条**。</li>
     * </ul>
     *
     * <p>参数按用户规格：长度取 4/8/16 小节里放得下的最大者、重复默认 1 次最多 2 次、
     * 退出在下一首的第一个 downbeat；循环期间建议 EQ 低切/高切 + 音量交叉（渲染层的事）。
     */
    public static final class OutroLoopPlan {
        /** 循环段（小节线对齐、整数小节）。 */
        public final VampSpan span;
        /** 循环几小节（4/8/16）。 */
        public final int bars;
        /** 重复几次（1 或 2）。 */
        public final int repeats;
        /**
         * 这一次循环是<strong>为什么</strong>：{@code true} = <b>买时间</b>
         * （尾奏剩余放不下这次交叠窗口，<b>或者剩得少到音乐上会赶</b>——见
         * {@link #LOOP_SHORT_TAIL_BARS}；<b>或者下一首的引子也太短</b>——见
         * {@link #LOOP_SHORT_INTRO_BARS}；三条的具体理由写在 {@link #reason} 里），
         * {@code false} = <b>对齐网格</b>（BPM 差 3%~8% 或调性冲突但节奏兼容）。
         *
         * <p><b>为什么把它写成字段而不是只留在 reason 里</b>：接线的口径要靠它。
         * 渲染路径（{@code AutoCueTransition}）现在只接"买时间"那一类，判据与日志都要
         * 按这个开关说话——把结论塞在一句人话里，接线那边就只能去解析字符串。
         * 两类的差别与各自的前提见 {@link #loopIsWired}。
         */
        public final boolean buysTime;
        /** 为什么要用（写进日志）。 */
        public final String reason;

        /**
         * <b>这一份循环是"选到的档位本来就要循环"</b>（HIPHOP_LOOPING / DANCE_REPEATER ✓，
         * 2026-10-07「让名不副实的档名副其实」）✓ —— 与"买时间"是两种不同的来由 ✓。
         *
         * <p>为什么单列一个标记而不是复用 {@code buysTime}：那两个字段会被写进日志与结论 ✓，
         * 混在一起就说不清"这次循环是为了什么" ✗（而"买时间"有它自己的口径与判据 ✓）。
         */
        public final boolean styleWanted;

        /**
         * <b>这是一次"确认重复"</b>（用户 2026-10-07：循环是给"融合窗口很短很短的"时候用的 ✓）——
         * 窗口短到音乐上站不住（&lt; 1 小节 ✓）时，用一个小节级、**无人声**、小节对齐的短段
         * 重复一遍，把交接"坐实" ✓；与"买时间"（铺时间 ✓）、"档位要的"（名字 ✓）都不是一回事 ✓。
         */
        public final boolean confirmRepeat;

        OutroLoopPlan(VampSpan span, int bars, int repeats, boolean buysTime, String reason) {
            this(span, bars, repeats, buysTime, reason, false);
        }

        OutroLoopPlan(VampSpan span, int bars, int repeats, boolean buysTime, String reason,
                      boolean styleWanted) {
            this(span, bars, repeats, buysTime, reason, styleWanted, false);
        }

        OutroLoopPlan(VampSpan span, int bars, int repeats, boolean buysTime, String reason,
                      boolean styleWanted, boolean confirmRepeat) {
            this.span = span;
            this.bars = bars;
            this.repeats = repeats;
            this.buysTime = buysTime;
            this.reason = reason;
            this.styleWanted = styleWanted;
            this.confirmRepeat = confirmRepeat;
        }

        /** 同一份计划的副本，但标记成"档位要的" ✓（决策层不知道档位 ✓，由执行层标 ✓）。 */
        public OutroLoopPlan withStyleWanted() {
            return new OutroLoopPlan(span, bars, repeats, buysTime, reason, true, confirmRepeat);
        }
    }

    /** 触发"对齐网格"的 BPM 差下限（用户规格：3%）。 */
    static final double LOOP_BPM_DIFF_MIN = 0.03;
    /** 超过这个 BPM 差就否决（用户规格：8%）。 */
    static final double LOOP_BPM_DIFF_MAX = 0.08;
    /** 拍点置信度低于这个值就当"网格不稳"（自由节奏/现场版那一类），不做循环。 */
    static final double LOOP_MIN_TEMPO_CONFIDENCE = 0.35;

    /**
     * <b>循环段里的人声活动占比上限 = 0（不允许有人声）</b>。
     *
     * <p>用户 2026-10-07 原话：「**不允许有人声**」。起因是实测：`Wake Me Up → After Hours`
     * 触发了"买时间"⇒ 把尾奏里的一段循环了两遍，而那段**带着人声** ⇒ 同一句人声被放了两遍
     * （用户描述「人声叫了两声」）✗。
     *
     * <p>与 {@link #VAMP_MAX_VOCAL_FRACTION}（0.2，用于**诊断/报数**）分开：
     * 那条是"什么样的段落算 vamp"，允许少量人声；而**真去循环**的时候人声一句都不许有 ✓ ——
     * 循环是用来铺时间的，不是用来把唱过的句子再唱一遍。
     */
    static final double LOOP_MAX_VOCAL_FRACTION = 0.0;

    /**
     * <b>尾奏剩多少小节就算"音乐上会赶"</b>——"买时间"的第三条触发（用户规格原话：
     * 「尾奏太短，下一首 intro 也太短：两首歌之间只剩 <b>2–4 小节</b>，直接交叉淡化会很赶。
     * 这时 loop 当前歌尾奏的最后 4/8 小节，争取出 8–16 小节的过渡窗口」）。
     *
     * <p><b>为什么是 4 小节、而不是 §8.0.9 里写的 8 小节</b>：8 会把参考对错误地卷进来。
     * 实测 skydive → 春めく 的 A 尾奏剩 <b>10.3 s = 5.5 小节</b>（128.0 BPM、每小节 1.875 s）
     * ——5.5 &lt; 8 ⇒ 8 小节那道门会给这对曲子循环，而用户仔细听过 Apple 在那对上的过渡
     * <b>没有循环</b>，明确要求保持这个结论。取 <b>4</b> 则两边同时成立：
     * 5.5 &gt; 4 ⇒ 参考对不循环（与耳朵一致）；2–4 小节 ⇒ 循环（与规格一致）。
     *
     * <p>尺寸与 {@link #outroVampSpan} 同一把尺子：{@code barSecs = 4×60/bpm}，与小节相位
     * （{@link #barPhaseOf}）同一个网格——"剩几小节"这件事只有一把尺子。
     */
    static final double LOOP_SHORT_TAIL_BARS = 4.0;

    /**
     * <b>"窗口很短"的门（小节）</b>：融合窗口短于这么多小节 ⇒ 走**确认重复** ✓
     * （用户 2026-10-07：「循环是给融合窗口很短很短的确认重复后真有用才用的」✓）。
     *
     * <p>为什么用"小节"而不是秒：能不能站住是**音乐单位**的问题 ✓ —— 1 小节是"最小的完整乐句单位" ✓
     * （参考对窗口 2.5128 s、128 BPM ⇒ 1.34 小节 ⇒ **不进这一档** ✓，验收过的落点不受影响 ✓）。
     */
    static final double LOOP_CONFIRM_WINDOW_BARS = 1.0;

    /**
     * <b>"窗口很短"的硬门（秒）= 2 s</b>（用户 2026-10-07 拍板 ✓）：
     * 「<b>融合窗口低于 2 s 且直接过渡效果不好才算真缺时间</b>」✓
     *
     * <p>为什么用**秒**而不是小节 ✗：小节长依赖"哪一侧的 tempo 算" ✓（参考对按 A 算是 1.34 小节 ✓、
     * 按代码里那把尺子却进了这一档 ✗，指纹当场变了 ✗）—— 2 s 这把尺子没有这个歧义 ✓，
     * 而且参考对窗口 **2.5128 s ≥ 2 s** ⇒ **不进这一档** ✓（验收过的落点与指纹都不动 ✓）。
     *
     * <p>另一条（"直接过渡效果不好" ✓）在 {@code AutoCueTransition} 用这一次的选点代价判 ✓
     * （见 {@code MixTuning.loopConfirmMinCost} ✓）——两条**同时**成立才算真缺时间 ✓。
     */
    public static final double LOOP_CONFIRM_WINDOW_SECS = 2.0;

    /** 确认重复用的小节数（短 ✓：1~2 小节就够"确认" ✓）。 */
    static final int LOOP_CONFIRM_BARS = 2;

    /**
     * <b>B 的引子（下一首 intro）短于这么多小节就算"太短，来不及把 B 铺进来"</b>
     * ——"买时间"的第四条触发（用户规格原文：「尾奏太短，<b>下一首 intro 也太短</b>：
     * 两首歌之间只剩 <b>2–4 小节</b>，直接交叉淡化会很赶」）。
     *
     * <p><b>量什么</b>：{@link #outroLoopIntroLeadSecs}，也就是 B 的"真正开始"
     * （{@link #introStartSecs}：前奏结束、歌起来的时刻）。选它而不是
     * {@link #vocalPhraseSpans} 的第一段起点，理由是<strong>同一把尺子</strong>：计划层给 B
     * 定的进场锚点（{@code planCollect} 里的 {@code introStructure}）用的就是
     * {@code introStartSecs}，所以"B 的引子有多长"与"引擎手里有多少地方可以铺 B"
     * 讲的**是同一件事**；而"第一段人声"是另一把尺子——器乐素材根本没有它、
     * 代理 VAD 还会被中频扫弦骗（见 {@link #vocalPhraseSpans}），而进场点并不按它定位。
     *
     * <p><b>为什么是 4 而不是规格里写的 8</b>（同一个问题在 A 侧也判过一次，
     * 见 {@link #LOOP_SHORT_TAIL_BARS}，理由同源）：
     *
     * <ol>
     *   <li>用户那句规格的单位本来就是"2–4 小节会很赶"，A 侧取的就是 4——两侧对称；</li>
     *   <li><b>8 会把参考对逼到只剩 0.34 s 余量</b>。实测（2026-10-06，真素材探针）：
     *       参考对的 B = 春めく，<b>浅扫</b>包络量出的引子 16.34 s = <b>8.17 小节</b>
     *       （119.97 BPM、小节 2.000 s）；而同一首歌**全扫**量出 17.42 s = 8.71 小节
     *       ——也就是说这个读数本身就会随扫描深度动 0.5 小节。8 那道门只比它低
     *       0.17 小节，一次扫描深度/拍点估计的正常抖动就能把参考对卷进来
     *       （它的产物必须逐位不变，见 {@code AutomixBitExactReferenceTest}）。</li>
     * </ol>
     *
     * <p>取 4 则参考对那侧余量 4.17 小节（2 倍），本机另外 5 首（引子 9.19~9.26 小节）
     * 同样留一倍以上——"卷进来"这件事在算术上就不可能发生。
     */
    public static final double LOOP_SHORT_INTRO_BARS = 4.0;

    /**
     * <b>"看得见引子 → 主段这一次抬升"的带宽</b>：引子那一段的平均平滑电平要落在参考电平的
     * {@link #LOOP_INTRO_FLOOR_RATIO}~{@value #LOOP_INTRO_RISE_RATIO} 倍之间，才算真的
     * 量到了引子（见 {@link #outroLoopIntroLeadSecs}）。
     *
     * <p>为什么需要它：{@link #introStartSecs} 只回答"电平第一次升到参考电平的 95%
     * 是什么时候"，它<strong>不检查前面是不是真的低</strong>；而参考电平来自
     * <strong>扫到的那一段</strong>的前 {@value #INTRO_REFERENCE_SECS} 秒。真曲库里进场侧
     * 只扫前 ~25 秒（{@link #introScanSecs}），那一段**整段都是安静引子**时，
     * 参考电平会跟着降成"引子自己的电平"，于是它在开头几十毫秒就报"到主电平了"——
     * 读数看着是"引子极短"，其实什么都没量到。这正是"扫到的这一段本身就短"那一类误判，
     * 而且**用浅扫包络在原理上分不开**："引子比扫描还长"与"引子只有几十毫秒、后面一直是
     * 主电平"在截断的那一段里长得一模一样。所以判据只能是"这一次读数有没有证据"，
     * 没有就如实报不知道（与这个包"算不出来就不猜"同一条纪律）。
     *
     * <p>两个边界的取值依据都是实测（2026-10-06，本机 8 首真素材 + 合成夹具，浅扫包络），
     * 真有引子的那些落在 <b>0.38~0.67</b>（春めく 0.67、另外五首真素材 0.61、
     * 合成夹具 0.38~0.41），两边各有一个被挡住的形状：
     *
     * <ul>
     *   <li><b>上界 0.75</b>：从第 0 秒就是主电平的两首（{@code qplayer-demo-A/B}）是
     *       <b>0.82</b>——那种素材里 {@code introStartSecs} 量到的 0.46 s 只是"起振到主电平"
     *       的几十毫秒，不是引子（"B 没有引子"与"B 的引子太短"是两件事，见已知边界）；</li>
     *   <li><b>下界 0.15</b>：引子**本身也是一段素材**，不该是静音。判据三那类夹具
     *       （引子 12 小节、浅扫整段落在引子里）量出来的引子段均只有 <b>0.06</b>×参考电平
     *       ——那是"开头什么都没有、第一记鼓一响就到主电平"的形状，与截断扫描分不开，
     *       一律当不知道。</li>
     * </ul>
     *
     * <p><b>它不是一个听感门槛</b>（不参与"够不够长"的判断），只是"这一次读数可不可信"
     * 的开关。本机真素材上两种取值都判"够长"（9.2 / 8.2 小节 &gt; 4），所以它对生产结论中性。
     */
    static final double LOOP_INTRO_RISE_RATIO = 0.75;

    /** 见 {@link #LOOP_INTRO_RISE_RATIO}：引子段电平的下界（低于它 = 引子那一段是静音）。 */
    static final double LOOP_INTRO_FLOOR_RATIO = 0.15;

    /**
     * <b>"调性冲突"的门</b>：接缝处两段 chroma 的余弦相似度（**不转调**，1.0 = 同一个调）
     * 低于它就算冲突。
     *
     * <p>取值不是新定的：{@link Chooser#choose} 判"两边本来就合"用的就是这座门
     * （{@code tuning.autoKeySame}，{@link MixTuning#autoKeySame} 的默认值
     * {@code 0.85f}）——那条规则原来的原话就是「速度对得上（差 …%）但调性相似度只有
     * … → 低音交接：…不让两套和声正面压过去」，也就是"调性冲突但节奏兼容"的另一面。
     * 这里复用同一个数，免得同一个概念有两把尺子。
     *
     * <p>（{@link Harmonic#MATCH_MIN_SCORE} = 0.72 是另一件事："转调**之后**至少要达到的
     * 相似度"，不是"冲突"的门，别混用。）
     */
    static final float LOOP_KEY_SAME_SCORE = 0.85f;

    /**
     * 按用户的标准决定"这次要不要循环、循环哪一段、几次"。返回 {@code null} 表示不用。
     *
     * @param cutSecs   出场点（A 的切点）
     * @param windowSecs 这次过渡的交叠时长
     * @param keyClash  调性冲突但节奏兼容（由调用方用 {@code Harmonic} 判）
     */
    public static OutroLoopPlan planOutroLoop(Envelope outro, Envelope intro, VampSpan vamp,
                                              double cutSecs, double windowSecs,
                                              boolean keyClash) {
        // 老入口（六参）：调用方只交了一个布尔，所以 reason 里只能写"调性冲突"这四个字
        // ——与加这条触发之前**逐字相同**；而且**不再复核算"节奏兼容"**（那个布尔按契约
        // 已经包含了它，见参数说明），既有的调用方语义一个字不改。
        return planOutroLoopCore(outro, intro, vamp, cutSecs, windowSecs, keyClash,
                keyClash ? KEY_NOTE_CLASH : "");
    }

    /**
     * 同六参版本，但把这一次的调性判读一起带进来（{@code null} = 不知道）。
     *
     * <p>与六参版本的唯一区别在<strong>这一条由谁判</strong>：这里交进来的是**原始读数**
     * （{@code plainScore} 为 NaN 时按"没有读数 = 不知道"处理）
     * （{@link KeyRelation}），"调性冲突"与"节奏兼容"两半都由本方法自己按
     * {@link #keyClashWithCompatibleGrid} 判——"调性冲突**但节奏兼容**"本来就是一个
     * 合取，拆成两处判容易写成两把尺子（判据也钉着这一点：相似度 0.95 或 BPM 差 3.9%
     * 时都不算这一条）。判读还多带一个读数进 {@code reason}，好让用户从日志里看出
     * 这一次是按什么判的。
     */
    static OutroLoopPlan planOutroLoop(Envelope outro, Envelope intro, VampSpan vamp,
                                       double cutSecs, double windowSecs, KeyRelation key) {
        boolean keyClash = keyClashWithCompatibleGrid(key, outro, intro);
        return planOutroLoopCore(outro, intro, vamp, cutSecs, windowSecs, keyClash,
                keyClash ? keyNoteOf(key) : "");
    }

    /** 老入口的措辞：只知道"是冲突"、没有读数（与加这条触发之前逐字相同）。 */
    private static final String KEY_NOTE_CLASH = "、调性冲突";

    /** 带读数那一条的措辞（用户读日志时要能看出这一次是按什么判的）。 */
    private static String keyNoteOf(KeyRelation key) {
        return String.format(java.util.Locale.US,
                "、调性冲突但节奏兼容（chroma 相似度 %.2f < %.2f）",
                Float.valueOf(key.plainScore), Float.valueOf(LOOP_KEY_SAME_SCORE));
    }

    /**
     * 两条入口共用的实现（{@code keyClash} 已经是结论，{@code keyNote} 只用来写理由）。
     *
     * @param keyClash "调性冲突但节奏兼容"成立吗（两条入口各自判过）
     * @param keyNote  写进 {@code reason} 的那一小句（没有读数时是"、调性冲突"）
     */
    private static OutroLoopPlan planOutroLoopCore(Envelope outro, Envelope intro, VampSpan vamp,
                                                   double cutSecs, double windowSecs,
                                                   boolean keyClash, String keyNote) {
        if (outro == null || vamp == null || outro.tempo == null || !(outro.tempo.bpm > 0f)
                || outro.tempo.confidence < LOOP_MIN_TEMPO_CONFIDENCE) {
            return null;                        // 没有可循环段 / 网格不稳 → 不做
        }
        // **循环段里一句人声都不许有**（2026-10-07 用户：「不允许有人声」）。
        // 实测教训：Wake Me Up → After Hours 的"买时间"把一段**带人声**的尾奏循环了两遍
        // ⇒ 那句人声被唱了两遍（用户听到「人声叫了两声」）✗。
        // 宁可不买时间（尾奏短一点、直接接），也不重复人家唱的那一句 ✓。
        if (vamp.vocalFrac > LOOP_MAX_VOCAL_FRACTION) {
            return null;
        }
        double bpmA = outro.tempo.bpm;
        double bpmB = intro != null && intro.tempo != null && intro.tempo.bpm > 0f
                ? intro.tempo.bpm : bpmA;
        double diff = Math.abs(bpmB - bpmA) / bpmA;
        if (diff > LOOP_BPM_DIFF_MAX) {
            return null;                        // 差太大：硬 loop 变速音质会怪
        }
        double barSecs = 4.0 * 60.0 / bpmA;
        double tailRemaining = outro.durationSecs - cutSecs;
        // ── 「买时间」的三条触发（任一条成立就是这一档）────────────────────────
        //
        //   ① 尾奏剩余放不下这次窗口（tailRemaining < windowSecs）；
        //   ② 出场点 + 窗口伸到全曲之外（cutSecs + windowSecs > duration）——
        //      ①与②是同一种"时间不够"的两种说法（放不下 = 伸出去），一起算；
        //   ③ **尾奏剩得少到音乐上会赶**（tailRemaining < LOOP_SHORT_TAIL_BARS 小节）——
        //      这一条 2026-10-05 补上，它才是用户在真实歌曲上真正会碰到的那一档。
        //
        // **为什么必须有③**：计划路径自己的硬约束就是"出场点 + 窗口 ≤ 全曲 − 退出余量"，
        // 于是①与②在生产路径上几乎恒为 false（6 对真素材实测全是 false），这一档等于
        // 一次都走不到。而用户的规格说的本来就是"尾奏剩得少 ⇒ 会赶 ⇒ 循环买时间"：
        // **"剩得少"本身就是触发条件**，不必等到"窗口放不下"。门（几小节）与理由见
        // {@link #LOOP_SHORT_TAIL_BARS}。
        //
        // 注意③与①②**不是互斥**的：①成立时③必然也成立（尾奏 < 窗口 ≤ 曲子长度），
        // 所以下面报理由时先问①②——那是更硬的那条事实（真的放不下），③是"放得下但会赶"。
        //
        //   ④ **B 的引子也太短**（用户规格：「下一首 intro < 8 小节」，2026-10-06 补）——
        //      量的是 B 的"真正开始"落在哪儿（{@link #outroLoopIntroLeadSecs}），
        //      门与"为什么是 4 不是 8"见 {@link #LOOP_SHORT_INTRO_BARS}。
        //      它与③是同一件事的两侧：③说"A 的尾奏太短"、④说"B 的前奏太短"，
        //      用户那句规格本来就是合起来说的（「尾奏太短，下一首 intro 也太短」）。
        //      归**买时间**这一档（不是"对齐网格"）：两边都铺不开的时候，缺的是时间，
        //      而循环 A 的尾奏正是把这段交叠拉长的唯一办法。
        boolean shortTail = tailRemaining < LOOP_SHORT_TAIL_BARS * barSecs;
        boolean windowTooLong = tailRemaining < windowSecs
                || cutSecs + windowSecs > outro.durationSecs;
        double introLead = outroLoopIntroLeadSecs(intro);
        double barSecsB = intro != null && intro.tempo != null && intro.tempo.bpm > 0f
                ? 4.0 * 60.0 / intro.tempo.bpm : barSecs;
        boolean shortIntro = introLead > 0.0 && introLead < LOOP_SHORT_INTRO_BARS * barSecsB;
        boolean needsTime = windowTooLong || shortTail || shortIntro;
        boolean needsGrid = (diff >= LOOP_BPM_DIFF_MIN) || keyClash;
        // ── 第三条触发：**窗口很短 ⇒ 确认重复**（用户 2026-10-07）────────────────────
        //
        // 与上两条并列 ✓：①②③④ 说的是"时间不够" ✓、`needsGrid` 说的是"要跟网格" ✓，
        // 而这一条说的是"**窗口短到音乐上站不住，需要把交接坐实**" ✓ ——
        // 用它的时候要短（1~2 小节 ✓）、要无人声 ✓、要小节对齐 ✓（后两条由下面的闸门保证 ✓），
        // 重复一遍就够 ✓（`repeats = 1` ✓），多一遍就啰嗦了 ✗。
        // ⚠️ 门槛是**秒**（用户拍板：低于 2 s ✓），不是小节 ✗ —— 小节那把尺子有歧义 ✓
        //（按 A 算 vs 按代码里那一把算，结果不同 ✗，实测把参考对的指纹都改了 ✗）。
        boolean wantsConfirm = windowSecs > 0.0 && windowSecs < LOOP_CONFIRM_WINDOW_SECS;
        if (!needsTime && !needsGrid && !wantsConfirm) {
            // **不缺时间、也不用对齐网格 → 不循环**。skydive → 春めく 就是这一档
            // （剩 9.8 s 尾奏放得下 7.5 s 窗口），与用户听到的"Apple 没有循环"一致。
            return null;
        }
        int maxBars = (int) Math.floor(vamp.length() / barSecs + 1e-6);
        int bars = 0;
        // 确认重复：**只用 1~2 小节** ✓（不是买时间那种 4/8/16 小节 ✓）——
        // "重复一遍确认"要的是短促有力 ✓，长段重复就成了另一回事 ✗。
        if (wantsConfirm && !needsTime && !needsGrid) {
            bars = Math.min(maxBars, LOOP_CONFIRM_BARS);
            if (bars <= 0) {
                return null;                    // 连一小节都放不下 ⇒ 不做 ✓
            }
        }
        // ⚠️ **确认重复不能被这一段覆盖** ✗（2026-10-07 判据照出来的 ✓：
        // 我先把 bars 设成 1~2 ✓，紧接着这个候选搜索又把它写成 **8** ✗ ⇒ 计划变成"确认重复 8 小节" ✗）。
        if (bars == 0) {
            for (int candidate : new int[]{16, 8, 4}) {
                if (candidate <= maxBars) {
                    bars = candidate;
                    break;
                }
            }
        }
        if (bars == 0) {
            return null;                        // 连 4 小节都放不下
        }
        // 缺得越多重复越多（用户规格：默认 1、最多 2）
        int repeats = needsTime && tailRemaining < bars * barSecs * 0.5 ? 2 : 1;
        //
        // ⚠️ **2026-10-07 试过改成"按需要算最少遍数"**（`ceil((窗口 − 尾巴)/一遍)`，至少 1 最多 2）——
        // 目标是用户实测的那条：「最终效果还是第二遍循环开始混音，那你循环有毛用」✗。
        // 算术上是对的（那一对 17.4 − 2.7 = 14.7 s ⇒ 一遍就够 ⇒ 混音不再被推到第二遍 ✓），
        // 但它会**连锁**到别的路径：两条判据当场红 ✗ ——
        //   · `AutoCueSegmentedPathTest.thePlanLayerEmitsAnEquivalentPathWhenTheOutroLoopIsWired`
        //     的"等价路径就是一段"变成 3 段 ✗（repeats 喂进了旋转/多段那一路）；
        //   · `AutoCueOutroLoopAiWindowTest` 的「买时间」循环在 AI 模式下没被接住 ✗。
        // 所以**先退回原式** ✓，把"最少遍数"留成独立一轮：要连同那两条判据一起改，
        // 并且必须先验证"重复一遍的素材正好够这次窗口"这条几何仍然成立 ✓。
        // 理由要**写清是哪一条触发的**（用户读日志验收这件事）：
        //   · ①②「放不下」——报"尾奏只剩 X s、窗口 Y s 放不下"，与从前的措辞逐字相同；
        //   · ③「剩得少到会赶」——报剩余小节数与本条的门（LOOP_SHORT_TAIL_BARS），
        //     这才是用户规格里那句"只剩 2–4 小节、直接交叉淡化会很赶"在日志里的样子；
        //   · ④「B 的引子也太短」——报 B 的引子读数与门，并**点明是 B 侧**：
        //     用户读日志时要能一眼分出是"A 的尾奏"还是"B 的引子"触发的。
        //
        // 三条按"哪条事实更硬"排：真的放不下 > 尾奏剩得少 > B 的引子短。
        // 前两条的措辞与加④之前**逐字相同**（A 侧的判据钉着它们）。
        String timeWhy = windowTooLong
                ? String.format(java.util.Locale.US,
                        "尾奏只剩 %.1f s（窗口 %.1f s 放不下）",
                        Double.valueOf(tailRemaining), Double.valueOf(windowSecs))
                : shortTail
                        ? String.format(java.util.Locale.US,
                                "尾奏只剩 %.1f s = %.1f 小节（不足 %.1f 小节，直接交叉淡化会赶）",
                                Double.valueOf(tailRemaining),
                                Double.valueOf(tailRemaining / barSecs),
                                Double.valueOf(LOOP_SHORT_TAIL_BARS))
                        : String.format(java.util.Locale.US,
                                "B 的引子只有 %.1f s = %.1f 小节（不足 %.1f 小节，"
                                        + "来不及把 B 铺进来）",
                                Double.valueOf(introLead),
                                Double.valueOf(introLead / barSecsB),
                                Double.valueOf(LOOP_SHORT_INTRO_BARS));
        // 调性那一条的措辞由两条入口各自给（老入口写"、调性冲突"四个字——与加这条之前
        // **逐字相同**；带读数的那一条多报一个读数，好让用户从日志里看出是按什么判的）。
        // 确认重复是**第三种来由** ✓（用户 2026-10-07）：窗口短到音乐上站不住 ⇒
        // 用小节级、无人声的一小段重复一遍把交接坐实 ✓。它的 reason 必须自己说清楚 ✓
        //（与"买时间""对齐网格"混在一起就说不清这次为什么循环 ✗）。
        final boolean confirm = wantsConfirm && !needsTime && !needsGrid;
        String reason;
        if (confirm) {
            reason = String.format(java.util.Locale.US,
                    "窗口只有 %.2f s（不足 %.1f s，且直接过渡效果不好）→ 确认重复 %d 小节一遍",
                    Double.valueOf(windowSecs), Double.valueOf(LOOP_CONFIRM_WINDOW_SECS),
                    Integer.valueOf(bars));
        } else if (needsTime) {
            reason = timeWhy + String.format(java.util.Locale.US,
                    " → 循环 %d 小节买时间", Integer.valueOf(bars));
        } else {
            reason = String.format(java.util.Locale.US,
                    "BPM 差 %.1f%%%s → 循环 %d 小节对齐网格",
                    Double.valueOf(100.0 * diff), keyNote, Integer.valueOf(bars));
        }
        return new OutroLoopPlan(vamp, bars, repeats, needsTime, reason, false, confirm);
    }

    /**
     * <b>B 的引子有多长</b>（秒；{@code < 0} = 这一次量不出来，调用方必须当"不知道"）。
     *
     * <p>定义就是 {@link #introStartSecs}（B 的"真正开始"）——与计划层给 B 定的进场锚点
     * 同一个量，理由见 {@link #LOOP_SHORT_INTRO_BARS}。三道关都必须过，缺一当不知道：
     *
     * <ol>
     *   <li><b>读数必须是个正数</b>。{@code introStartSecs} 返回 {@code 0.0} 有两个来路：
     *       ① 第一格就在主电平（"B 没有引子"）；② 扫到的这一段里**从来没到过主电平**
     *       （{@code for} 循环走完那条兜底 return）。从外面分不开，而后一种恰恰说明
     *       B 的引子**比扫到的还长**——那是"够长"而不是"太短"。所以一律当不知道，
     *       宁可这一条不触发（与这个包"算不出来就不猜"同一条纪律）。</li>
     *   <li><b>读数必须落在真的扫到的那一段里</b>（{@code lead < rms.length × HOP}）。
     *       这一条是算术上的防御，但它把"扫到的这一段本身就短"这件事写明了：
     *       {@code intro.durationSecs} 是**解码器报的整曲时长**，而
     *       {@code intro.rms.length × HOP_SECS} 才是这次真的扫到的长度，两者在浅扫档
     *       可以差一个数量级（实测 200 s 的歌只扫前 25 s）。拿前者当"扫到的长度"去算，
     *       会把"没扫到的那一截"当成"B 的引子只有这么长"——那正是要防的误判。</li>
     *   <li><b>看得见"引子 → 主段"这一次抬升</b>：引子那一段的平均平滑电平要落在参考电平的
     *       {@link #LOOP_INTRO_FLOOR_RATIO}~{@value #LOOP_INTRO_RISE_RATIO} 倍之间。
     *       看不见抬升（整条剖面一上来就是主电平）= "B 没有引子"或"还没扫到主段"，
     *       两者都不是"引子太短"的证据——尤其防住那一类**误判**：25 秒浅扫整段都是安静引子
     *       时，参考电平会跟着降成"引子自己的电平"，于是读数看着只有几十毫秒
     *       （带宽两端的取值依据都写在 {@link #LOOP_INTRO_RISE_RATIO} 上）。</li>
     * </ol>
     */
    public static double outroLoopIntroLeadSecs(Envelope intro) {
        if (intro == null) {
            return -1.0;
        }
        double lead = introStartSecs(intro);
        if (!(lead > 0.0)) {
            return -1.0;                        // 关①②：0.0 分不开来路，负值是读不出来
        }
        double covered = intro.rms.length * HOP_SECS;
        if (!(lead < covered)) {
            return -1.0;                        // 关②：读数落在扫到的区间之外（防御）
        }
        float[] profile = smoothLevel(intro);
        double reference = introReferenceLevel(profile);
        if (!(reference > 1e-6)) {
            return -1.0;
        }
        int end = Math.min(profile.length, Math.max(1, (int) Math.round(lead / HOP_SECS)));
        double sum = 0.0;
        for (int i = 0; i < end; i++) {
            sum += profile[i];
        }
        double leadLevel = sum / (double) end;
        if (!(leadLevel < reference * LOOP_INTRO_RISE_RATIO)
                || !(leadLevel > reference * LOOP_INTRO_FLOOR_RATIO)) {
            return -1.0;                        // 关③：看不见"引子 → 主段"这一次抬升
        }
        return lead;
    }

    /**
     * <b>这份循环计划渲染路径接没接</b>（接线口径：哪些循环真的会去改 A 的素材）。
     *
     * <p>现在<strong>只接"买时间"那一类</strong>（{@link OutroLoopPlan#buysTime}）。
     * 两条理由，都要能证否：
     *
     * <ol>
     *   <li><b>"对齐网格"那一类要的是渐进变速，而渲染路径没做。</b>用户规格（§8.0.9）里
     *       那一档的原话是「BPM 差在 3%~8%（<b>要一段来渐进变速</b>）」——也就是说这一段
     *       重复段落是拿来<strong>把两边的拍长过渡过去</strong>的。而现在的循环渲染
     *       （{@code AutoCueTransition.prepareLoopOutgoing}）只是把 vamp 按原速重复、
     *       再把 B 淡进来，<strong>不做跨循环的变速</strong>（循环与变速互斥，见那里的注释）。
     *       接下它就是把 A 的收尾换掉、却换不来网格对齐——收益为零、风险非零。</li>
     *   <li><b>参考对正好落在这一类里，而用户要它不循环。</b>实测（2026-10-05，探针
     *       {@code LoopDecisionProbe}，本机 6 对素材）：skydive → 春めく 是 A 128.0 /
     *       B 119.97 BPM，差 <b>6.27%</b>，正好在 3%~8% 之内 → {@link #planOutroLoop}
     *       <strong>会给出一份计划</strong>。而用户仔细听过 Apple Music 在这对曲子上的过渡
     *       「没有循环」，并明确要求这条接线保持这个结论。既然这一类现在没有收益，
     *       就让它保持不循环——这是<strong>有意不接</strong>，不是漏接：{@code planCollect}
     *       会在诊断档里记一行说明"决策层想循环、但这一类还没接线"。</li>
     * </ol>
     *
     * <p><b>同一个探针还量到一件更要紧的事</b>：{@code needsTime}（"买时间"那一类的触发）
     * 在计划路径上曾经<strong>一次都没有成立过</strong>——因为 {@code planCollect} 自己的硬约束
     * 就是「出场点 + 交叠 ≤ 全曲 − {@value #TAIL_GUARD_SECS} s」，于是尾奏永远放得下窗口。
     * 6 对素材上这个值全是 {@code false}，也就是"唯一能被触发的循环就是对齐网格那一类"。
     *
     * <p><b>那个缺口已经补上（2026-10-05）</b>：{@code planCollect} 现在会额外考虑
     * <b>一个</b>点位——vamp 的终点（{@code outroVampSpan} 给的 {@code to}），而且只在它
     * 落在"放得进窗口"的区间之外时才加（见那里的三条边界）。于是"买时间"这一档在生产
     * 路径上真的走得通了，判据 {@code AutoCueOutroLoopBuyTimeTest} 从红转绿。
     * 放宽的**只有**"窗口必须放得进"那一条：出场点本身仍然不许越过
     * {@code 全曲 − 退出余量}；"对齐网格"那一类照旧有意不接（上面两条理由都没变），
     * 而参考对（vamp 终点 168.3 s，仍在放得进的区间内）连一个点都不会多——
     * 位指纹 {@code AutomixBitExactReferenceTest} 上五部分哈希逐位相同。
     *
     * <p><b>而"买时间"这一档真正会在真实歌曲上用起来的触发是第三条</b>
     * （{@link #LOOP_SHORT_TAIL_BARS}，"尾奏剩得少到会赶"，2026-10-05 同日补）：前两条都要求
     * "窗口放不下"，而计划路径的硬约束让它们几乎恒为 false（真素材实测全是 false）——
     * 补上 vamp 终点那个点位只是让它们<strong>能</strong>成立，真正常见的形状是
     * "窗口放得下、但尾奏只剩 2–4 小节"：那一刻引擎能给出的交叠只有 1~2 小节（很赶），
     * 于是第三条成立、循环把窗口延长到"一遍循环段 × 重复次数"。参考对不受影响
     * （它的尾巴 5.5 小节 &gt; 4 小节，且落在"对齐网格"那一档），位指纹仍然逐位相同。
     */
    public static boolean loopIsWired(OutroLoopPlan plan) {
        // **两种来由都算接线**（2026-10-07）：① "买时间"（原有的那一类 ✓）；
        // ② 选到的档位本来就叫"循环/重复器"（HIPHOP_LOOPING / DANCE_REPEATER ✓）——
        // 在那之前这两档跑的是朴素淡化 ✗（名字说循环、实际不循环 ✓，用户报"怎么感觉不对" ✓）。
        return plan != null && (plan.buysTime || plan.styleWanted || plan.confirmRepeat);
    }

    /** 循环最多重复几遍（用户规格：默认 1、最多 2）。计划层、路径、渲染层共用这一把尺子。 */
    static final int LOOP_MAX_REPEATS = 2;

    /**
     * <b>把一份循环计划显式化成一条路径</b>（用户：「主动编配是在结构图上找最优路径」）。
     *
     * <p>渲染路径今天做的是"A 自然播到 vamp 起点 → 循环段播完第一遍 → 从那里开始把
     * 循环段整段重复 {@code repeats} 遍"，也就是说<strong>窗口里的素材</strong>就是
     * "循环段 × repeats"这一段。于是等价路径是<strong>一段</strong>：
     *
     * <pre>
     *   segments = [ Segment("outro", v0, v0 + bars×小节长, repeats) ]
     * </pre>
     *
     * <p><b>为什么不是三段（"A 的前段 + 循环段 × repeats + 与 B 交叉淡化那段"）</b>：
     * 那段"A 的前段"不是窗口素材，而是<strong>出场曲自己自然播过去</strong>的那一段
     * （窗口从 {@code v0 + 一遍} 才开始，见 {@code AutoCueTransition.prepareLoopOutgoing}
     * 里的形状图）；而"与 B 交叉淡化"不是第三种素材，是<strong>窗口末尾那一段的增益</strong>
     * （同一条素材，等功率淡入 B）——把它写成独立的一段，要么重复读同一份样本，
     * 要么让"一遍"变成半遍，两条都会让"与今天逐位相同"这条硬要求当场失效。
     * 这两件事在路径里的位置分别是：起点 {@code v0} 与 {@code Segment} 的
     * {@code endSecs}（渲染层按它反推接缝），以及路径长度与用户窗口的关系
     * （渲染层按 {@code windowSecs} 吸到整数小节得淡化长度）。{@code Segment}
     * 本身只管"放什么素材、放几遍"——这是它唯一该管的事。
     *
     * <p>返回 {@code null} 表示<strong>说不出一条与决策等价的路径</strong>（量不出节拍、
     * 相位不定、vamp 放不进曲子）。那时渲染层照旧按决策合成那一段——
     * "路径说不出来"不该让这一次过渡失去循环（那会把一个表示层的缺口变成听感回归）。
     */
    static java.util.List<Segment> outroLoopPath(Envelope outro, OutroLoopPlan plan) {
        if (outro == null || plan == null || plan.span == null
                || outro.tempo == null || !(outro.tempo.bpm > 0f)) {
            return null;
        }
        double barSecs = 4.0 * 60.0 / outro.tempo.bpm;
        double phase = barPhaseOf(outro, barSecs);
        if (Double.isNaN(phase)) {
            return null;
        }
        int bars = Math.max(1, plan.bars);
        int repeats = Math.max(1, Math.min(plan.repeats, LOOP_MAX_REPEATS));
        double v0 = snapToBar(plan.span.from, phase, barSecs);
        double to = v0 + bars * barSecs;
        if (v0 < 0.0 || to > outro.durationSecs) {
            return null;
        }
        return java.util.Collections.singletonList(new Segment("outro", v0, to, repeats));
    }

    /**
     * <b>三段路径的候选几何</b>（"A 的尾奏段 + A 的循环段 ×repeats + B 的落床段 ×repeat"）。
     *
     * <p>与 {@link #outroLoopPath} 的关系是"多一段、少一段"：那一条把循环决策显式化成
     * <b>一段</b>（A 的循环段 ×repeats），这一条在它前面接上 A 的尾奏段、在后面接上
     * B 的落床段 ×repeat——也就是用户给的第三个例子：
     * 「<b>A 的尾奏 + B 的纯鼓段循环 + B 的主歌</b>」。
     *
     * <p>三条边界各自的来源（都是**已有决定的搬运**，不是这里新拍的数字）：
     *
     * <ol>
     *   <li>{@code A 的尾奏段} = {@code [v0 − m 小节, v0)}，{@code m} 取
     *       <b>用户设的交叠时长吸到整数小节</b>（{@code round(窗口/小节长)}）——渲染层
     *       算"淡化那一段"用的就是这把尺子（{@code AutoCueTransition.prepareFromPath}），
     *       所以这一段正好是"A 本来要拿去交叉淡化的那一截尾巴"，现在把它<b>放完</b>
     *       再进循环；</li>
     *   <li>{@code A 的循环段}：小节数与重复遍数**原样取那份循环决策**
     *       （{@code planOutroLoop} 给的 {@code bars}/{@code repeats}）——三段路径不是
     *       第二个循环决策，它是在同一个决策上换一种素材编排；</li>
     *   <li>{@code B 的落床段} = {@link #introBedSpan} 找到的那一段（起点是 B 的第一条
     *       小节线、整数小节），重复遍数<b>先试 2 遍</b>（用户那一档要的就是"把 B 的器乐
     *       开头循环起来"），总窗放不下就退到 1 遍，1 遍也放不下就<b>整条不成立</b>
     *       （返回 {@code null}）。</li>
     * </ol>
     *
     * <p><b>几何闸</b>（任一条不过就返回 {@code null}——如实不选，不硬塞）：
     * ① A 的尾奏段不许伸到曲子开头之前（{@code v0 − m 小节 ≥ 0}）；
     * ② <b>A 侧</b>（A 的尾奏段 + A 的循环段 × repeats）≤
     * {@link #INTRO_BED_PATH_MAX_BARS} 小节（A 的尺子：接缝与淡化都在 A 的小节线上，
     * 所以这段过渡窗口也用 A 的尺子量）——<b>B 的落床段不算在这 16 小节里</b>
     * （它是"B 的床"，另有 {@link #introBedSpan} 那两条规矩管，见
     * {@link #INTRO_BED_PATH_MAX_BARS} 的注释）；
     * ③ 整窗（A 侧 + B 的落床段 ×n）≤ A 的全曲时长——这是渲染层自己那条"不吞整曲"的闸
     * （{@code prepareLoopOutgoing} 里 {@code windowTotal > duration} 就退回普通交叉淡化），
     * 计划层先按同一句话挡住，免得产出一条渲染层会拒绝的路径。
     *
     * <p><b>已知边界</b>：B 的落床段是按 <b>B 的小节</b> 取的整数小节，而几何闸用的是
     * A 的小节——两者的 BPM 差在 {@code planOutroLoop} 里已经被挡在 8% 以内，
     * 但"B 的落床段在 A 的尺子上也正好是整数小节"这件事<b>不保证</b>（差几个百分点就有
     * 零点几小节的残差）。它不影响路径本身：窗口的终点落在 B 的小节线上（最后一段是
     * B 的素材，所以 B 从那条线接着播），而接缝与淡化在 A 的小节线上。
     *
     * <p><b>进场点由调用方负责</b>：这一段路径的 B 侧素材就是落床段，所以渲染层按
     * {@code Cue.introEntrySecs} 铺的"B 从哪儿开始响"必须与它一致——调用方
     * （{@code planCollect}）在采用这条路径时把进场点设成落床段的起点，
     * <strong>或者</strong>（有旋转点时）设成那个旋转点。
     *
     * <p><b>旋转点（{@code bedRotateSecs}）</b>：落在 {@code (bed.from, bed.to)} 内部时，
     * 第三段带上它——一遍于是 = {@code [旋转点, 段末) + [段首, 旋转点)}，长度<strong>仍是
     * 那一段落床段</strong>（{@code bedPass} 不变，所以上面三条几何闸一个数都不用改），
     * 但 B 是从<strong>搜索挑中的那个落点</strong>开始响的。{@code NaN} = 不旋转（今天的行为）。
     */
    static java.util.List<Segment> introBedPath(Envelope outro, Envelope intro, VampSpan vamp,
                                                OutroLoopPlan plan, VampSpan bed,
                                                double windowSecs, double bedRotateSecs) {
        if (outro == null || intro == null || vamp == null || plan == null || bed == null
                || outro.tempo == null || !(outro.tempo.bpm > 0f)) {
            return null;
        }
        double barA = 4.0 * 60.0 / outro.tempo.bpm;
        double phaseA = barPhaseOf(outro, barA);
        if (Double.isNaN(phaseA)) {
            return null;
        }
        // B 的落床段起点：吸到 B 自己的小节线上（introBedSpan 已经在小节线上，这里再吸
        // 一次是"同一把尺子"那条纪律——段的边界不许由两处各算一遍）。
        double barB = intro.tempo != null && intro.tempo.bpm > 0f
                ? 4.0 * 60.0 / intro.tempo.bpm : barA;
        double phaseB = barPhaseOf(intro, barB);
        double bedFrom = Double.isNaN(phaseB) ? bed.from : snapToBar(bed.from, phaseB, barB);
        double bedPass = bed.length();
        if (!(bedPass > 0.0) || bedFrom + bedPass > intro.durationSecs) {
            return null;
        }
        double v0 = snapToBar(vamp.from, phaseA, barA);
        int loopBars = Math.max(1, plan.bars);
        int loopRepeats = Math.max(1, Math.min(plan.repeats, LOOP_MAX_REPEATS));
        double loopTo = v0 + loopBars * barA;
        int tailBars = Math.max(1, (int) Math.round(windowSecs / barA));
        double tailFrom = v0 - tailBars * barA;
        if (tailFrom < 0.0 || loopTo > outro.durationSecs) {
            return null;
        }
        // ── 闸②：**那 16 小节只封 A 的贡献**（尾奏段 + 循环段 × repeats）──────────
        //
        // 这两个数就是"这条路径为 A 争取出来的过渡窗口"：第一段 A 的尾奏段（那本来是要拿去
        // 交叉淡化的那一截尾巴）与第二段 A 的循环段 ×repeats（{@code planOutroLoop} 的
        // 决策，见 {@link #INTRO_BED_PATH_MAX_BARS} 的注释）。**B 的落床段不在这里**——
        // 它是"B 的床"，由 B 自己的规矩管（{@link #introBedSpan} 的扫描范围
        // {@link #INTRO_BED_HEAD_SECS} 与前伸上限 {@link #INTRO_BED_LEAD_BARS}），
        // 不从那 16 小节里扣（2026-10-06 用户列表第 ④ 项：上限只封 A 的贡献、B 的床单算）。
        double aSideSecs = (tailBars + loopBars * (double) loopRepeats) * barA;
        double budget = INTRO_BED_PATH_MAX_BARS * barA;
        if (aSideSecs > budget) {
            // 放不下就如实不选它——但**要说清是哪一条闸挡的**（否则读日志的人分不清
            // "没有落床段"、"几何不成立"、"A 侧超过上限"与"整窗比 A 全曲还长"，
            // 而这几件事的修法完全不同）。
            // 降在 debug 档：这是**没发生的事**，不该占默认日志（"不循环 ⇒ 逐位不变"那条
            // 性质也要求默认路径上不多出台词）。
            Logger.debug("[qplayer-engine] 三段路径：A 侧超过上限——A 侧 {} 小节（尾奏段 {} + 循环段 "
                            + "{}×{}）= {}s > 上限 {} 小节（{}s）；这条上限只封 A 的贡献，"
                            + "B 的落床段 {} 小节（B 的尺子）不计入它——B 侧另有 {}s 的扫描范围"
                            + "与前伸 {} 小节那两条规矩 → 这一次不选它",
                    Integer.valueOf(tailBars + loopBars * loopRepeats), Integer.valueOf(tailBars),
                    Integer.valueOf(loopBars), Integer.valueOf(loopRepeats), fmt(aSideSecs),
                    fmt(INTRO_BED_PATH_MAX_BARS), fmt(budget), f2(bedPass / barB),
                    fmt(INTRO_BED_HEAD_SECS), fmt(INTRO_BED_LEAD_BARS));
            return null;
        }
        for (int n = LOOP_MAX_REPEATS; n >= 1; n--) {
            // **循环段要乘 repeats**：窗口里放的是它重复 loopRepeats 遍（第一段 A 的尾奏段
            // 与第三段 B 的落床段各自再乘自己的遍数）。
            // **闸③（物理约束，与上面那条各管各的）**：整窗（A 侧 + B 的落床段 ×n）不许比
            // A 的**全曲**还长——A 侧的素材得真的存在，渲染层自己也按这句话退
            // （{@code AutoCueTransition.prepareLoopOutgoing} 里 {@code windowTotal > duration}
            // 就整条交回普通交叉淡化）；计划层先按同一句话挡住，免得产出一条渲染层会拒绝的路径。
            double total = aSideSecs + n * bedPass;
            if (total > outro.durationSecs) {
                continue;
            }
            java.util.List<Segment> path = new java.util.ArrayList<Segment>(3);
            path.add(new Segment("outro", tailFrom, v0, 1));
            path.add(new Segment("outro", v0, loopTo, loopRepeats));
            // 第三段：B 的落床段。旋转点给了就从那个点起播（一遍仍覆盖整段落床段）——
            // 它落在段外时 Segment 的构造函数会如实归一化成"不旋转"。
            path.add(new Segment("intro", bedFrom, bedFrom + bedPass, n,
                    Double.isNaN(bedRotateSecs) ? null : Double.valueOf(bedRotateSecs)));
            return java.util.Collections.unmodifiableList(path);
        }
        // 连一遍都放不进 A 的全曲：报的是**整窗与 A 的全曲**这两个数（不是那 16 小节——
        // 那 16 小节量的是 A 侧，而这里 A 侧自己**是**放行的，见上面那条闸）。
        // 降在 debug 档：这是**没发生的事**，不该占默认日志。
        Logger.debug("[qplayer-engine] 三段路径：总窗超过 A 的全曲时长——A 侧 {} 小节（{}s）+ "
                        + "B 的落床段 {} 小节（B 的尺子）×1（{}s）= {}s > A 全曲 {}s"
                        + "（A 侧的素材得真的存在，渲染层自己也按这条退）→ 这一次不选它",
                Integer.valueOf(tailBars + loopBars * loopRepeats), fmt(aSideSecs),
                f2(bedPass / barB), fmt(bedPass), fmt(aSideSecs + bedPass),
                fmt(outro.durationSecs));
        return null;
    }

    /**
     * <b>同一个接缝窗口、同一条 A 侧素材下的一套点位分</b>——三段路径那一档做比较用的尺子。
     *
     * <p>它重放 {@code planCollect} 里那两处的调用串（评分 + 结构 + 小节线 + 新颖度 +
     * 强度贴合），只把点位换掉：这样两边的差别<b>只在换掉的那一项上</b>，而不是"两把尺子
     * 比出来的差"。改动时两边必须一起改——这个包里"同一件事只有一把尺子"是纪律
     * （吸拍与重评分那两处的注释讲的是同一个教训）。
     *
     * <p><b>唯一没参与的一项是「歌词节奏」（{@code lineGap}），这是有意的、也是这一档
     * 必须说清的一件事</b>：那一项奖励的是"<b>融合结束那一刻正好落在 B 的句头上</b>
     * （隔 gA）"——它的前提是"B 的**唱腔**正在被淡进来"。三段路径的融合窗口整段落在
     * B 的<strong>器乐落床段</strong>里（{@link #introBedSpan} 的四条判据里就有
     * "人声占比 ≤ 20%"），B 的第一句在落床段之后才起——也就是说这一项的前提<b>按定义
     * 不成立</b>，把它算进来等于用一把"你本来就不该在 B 的唱腔上做交叉淡化"的尺子去罚
     * 这条路径（实测：合成素材上它能差 0.078 分，正好把落床段压下去）。
     * 这一档要保证的那条更强、也可查：<b>融合期里 B 一个句子都不起</b>（落床段是器乐段），
     * 而"隔 gA"讲的是"A 唱完隔 gA 才是 B 的下一句"——落床段就是拿来填那个空隙的。
     * 说明行与报告里都写着这一项没参与，不允许静默。
     */
    private static Score seamScore(Envelope outro, Envelope intro,
                                   double cutSecs, double entrySecs,
                                   double windowSecs, double outroStructure,
                                   double introStructure, double introScale, Bridge bridge,
                                   double metricAhead) {
        Score score = evaluate(outro, intro, cutSecs, entrySecs, windowSecs)
                .withStructure(structureTerm(cutSecs, outroStructure,
                        entrySecs + windowSecs * 0.5, introStructure,
                        STRUCTURE_OUTRO_SCALE, introScale))
                .withBar(barBonus(outro, intro, cutSecs, entrySecs))
                .withNovelty(noveltyBonus(outro, intro, cutSecs, entrySecs))
                .withMelody(melodyOrNull(melodyMatch(outro, cutSecs, intro, entrySecs,
                        windowSecs)), null);
        if (bridge.measure) {
            score = score.withIntensity(Double.valueOf(Math.abs(
                    intensityAhead(outro, cutSecs, metricAhead)
                            - intensityAhead(intro, entrySecs, metricAhead))), bridge.score);
        }
        return score;
    }

    /**
     * <b>这一对（出场点 + 窗口）是"买时间"那一对吗</b>——即"尾奏放不下这次窗口
     * （或剩得少到会赶），要靠把尾奏里那段 vamp 重复一遍把时间补出来"。
     *
     * <p>判据不是在这里另写一套算术，而是<b>直接问决策层</b>：{@code planOutroLoop} 里
     * {@code needsTime}（三条触发：{@code 尾奏剩余 < 窗口}、{@code cut + window > 全曲}、
     * {@code 尾奏剩余 < }{@link #LOOP_SHORT_TAIL_BARS} 小节）就是这件事的定义，
     * {@code buysTime} 是它的结论，接线口径再由 {@link #loopIsWired} 定。三处各写一遍
     * 算术必然分叉，而"同一件事只有一把尺子"是这个仓库的纪律（吸拍与重评分那两处的
     * 注释讲的是同一个教训）。
     *
     * <p><b>只有一处调用</b>：{@code planCollect} 的逐窗硬约束——某个候选的
     * {@code cut + window} 越过 {@code 全曲 − 退出余量} 时不立刻丢掉，而是问一句
     * "这一对买得起时间吗"（问的就是"这一对需要循环吗"）。第三条（尾奏剩得少）在这里是
     * **入口**：晚出场、尾巴只剩 2–4 小节的点位正是"该靠循环把时间补出来"的那一类。
     * 选完点位之后落地那一步（吸不吸拍、窗口延长）问的是**另一件事**——它问的是
     * "这一对有没有越过放得进的那条界"，与"需要不需要循环"不是同一个问题（见那里的注释）。
     *
     * <p>{@code vamp == null} 直接 false：没有可循环的段就买不到时间，这也就把
     * "没有 vamp 的素材行为与原实现逐位相同"这条性质收在这一行里——
     * {@code planCollect} 里那句分流只在 vamp 存在且决策层点头时才留候选。
     */
    static boolean buysTimePair(Envelope outro, Envelope intro, VampSpan vamp,
                                double cutSecs, double windowSecs) {
        return vamp != null
                && loopIsWired(planOutroLoop(outro, intro, vamp, cutSecs, windowSecs, false));
    }

    /** 尾奏里一段"可以循环的器乐 vamp"。见 {@link #outroVampSpan}。 */
    public static final class VampSpan {
        public final double from;
        public final double to;
        /** 自相似（前后半三频带轮廓相关）、电平变异、人声占比：诊断用。 */
        public final double selfSim;
        public final double levelCv;
        public final double vocalFrac;

        VampSpan(double from, double to, double selfSim, double levelCv, double vocalFrac) {
            this.from = from;
            this.to = to;
            this.selfSim = selfSim;
            this.levelCv = levelCv;
            this.vocalFrac = vocalFrac;
        }

        public double length() {
            return to - from;
        }
    }

    /** 找 vamp 时只看尾奏最后这么多秒。 */
    static final double VAMP_TAIL_SECS = 45.0;
    /** 循环段至少要比它响（相对全曲 85 分位）。 */
    static final double VAMP_MIN_LEVEL_RATIO = 0.5;
    /** 平滑电平的相对标准差上限。 */
    static final double VAMP_MAX_CV = 0.35;
    /** 人声活动段在循环段里占的比例上限。 */
    static final double VAMP_MAX_VOCAL_FRACTION = 0.2;
    /** 前后半三频带轮廓的相关系数下限。 */
    static final double VAMP_MIN_SELF_SIM = 0.5;

    /**
     * <b>拍栅格上的「下拍」序号</b>（0..3；无拍栅格时 −1）——与 {@link #barPhaseOf}
     * <b>同一把尺子</b>（整带起音之和），只是候选集就是拍位置上的那 4 个。
     *
     * <p>用户 2026-10-06 要求「用尽一切方案严格对齐」。{@link #barPhase} 用<b>低频</b>起音分辨
     * 4 个相位，测不出来时它<b>如实返回 −1</b>（这个纪律是对的），而参考对的 B（春めく）
     * 就是 −1——于是"两侧小节相位必须一致"这条约束**缺一半输入**。这里换一个目标函数补上，
     * 不是放宽猜测；两者天然同栅格，不会再出现"乐句尺子 181.4 / 打分尺子 180.9"那种两把尺子。
     *
     * <h3>实测（2026-10-06，真素材；"用尽一切方案严格对齐"这一轮的全部结果）</h3>
     *
     * <p>为了回答"能不能把 B 的小节相位测出来"，一共量了<b>三个独立特征</b>
     * （探针都在 {@code /tmp/dbprobe/}，只打印、未入库）：
     *
     * <pre>
     *   特征                            skydive（A）                     春めく（B）
     *   低频起音四相位均值（barPhase）     序号 1（现用）                   **−1（如实：测不出）**
     *   整带起音四相位之和（本方法）       序号 2，对比度 2.652 ✓ 可判      序号 2，次大/最大 0.997 ✗
     *   相邻小节鼓型重复度（新量）         最高相位 3，0.1758 vs 0.1734 ⇒  最高相位 0，0.6072 vs 0.5807 ⇒
     *                                   对比度 1.005 ✗                  对比度 1.044 ✗（弱）
     * </pre>
     *
     * <p><b>B 上三个特征给三个不同答案，而且对比度全部接近 1.0</b>：这类 4/4 电子流行里
     * <b>每一拍都有底鼓</b>，四拍在低频上几乎对称——"哪一拍是小节的『一』"这个信息
     * <b>在低频/起音特征里根本不存在</b>（只在和声/旋律/乐句层）。
     * 所以：<b>拍级对齐可以做硬约束</b>（实测 A 的出场点是第 386.00 拍、B 的进场点是第 28.998 拍，
     * 两侧都精确落在拍上）；<b>小节序号对齐不做硬约束</b>——硬做等于拿一次抛硬币去否决一个
     * 26 ms 内贴着 Apple 的点位（实测代价：B 会从 14.9744 挪到 15.4754 或 13.475），
     * 而正确做法是如实写在说明行里"B 的小节相位不可知"。
     *
     * <p>另有一条反证值得记：<b>Apple 自己的 A 出场点 181.1 根本不在拍上</b>
     * （第 385.39 拍），而我们的 181.3853 正好是第 386.00 拍 = 下拍。也就是说 Apple 那一刀
     * 是<b>乐句对乐句</b>贴出来的（离我们的拍差 0.285 s），复刻它该动的是
     * {@code eightBarLines} 的乐句线取值，而不是小节序号。
     */
    public static int downbeatIndex(Envelope env, double barSecs) {
        double[] sums = downbeatSums(env, barSecs);
        if (sums == null) {
            return -1;
        }
        double best = -1.0;
        int bestK = -1;
        for (int k = 0; k < sums.length; k++) {
            if (sums[k] > best) {
                best = sums[k];
                bestK = k;
            }
        }
        return bestK;
    }

    /** 四个拍位置的"整带起音之和"（{@link #downbeatIndex} 与诊断共用）；无拍栅格 null。 */
    public static double[] downbeatSums(Envelope env, double barSecs) {
        if (env == null || !(barSecs > 0.0) || env.onset == null || env.onset.length == 0
                || !env.hasBeatGrid()) {
            return null;
        }
        double period = env.tempo.periodFrames / (double) env.beatRate;
        if (!(period > 0.0) || period > barSecs) {
            return null;
        }
        double[] first = env.beatsIn(0.0, period * 2.0);
        if (first.length == 0) {
            return null;
        }
        int count = Math.max(1, (int) Math.round(barSecs / period));
        double[] sums = new double[count];
        for (int k = 0; k < count; k++) {
            double phase = first[0] + k * period;
            for (double t = phase; t < env.durationSecs; t += barSecs) {
                int i = env.indexAt(t);
                for (int j = Math.max(0, i - 3); j < Math.min(env.onset.length, i + 4); j++) {
                    sums[k] += env.onset[j];
                }
            }
        }
        return sums;
    }

    /**
     * <b>旋律重合度</b>（0..1）：A 在 {@code cutSecs} 附近的 chroma 序列与 B 在
     * {@code entrySecs} 附近的 chroma 序列逐帧比出来的最大相似度。
     *
     * <p><b>为什么要有它（2026-10-06 用户要求，写进 automix 宪法）</b>：
     * 「应当旋律重合度最优先，其次是情绪，然后才是剩下的。<b>旋律永远第一位</b>。
     * 比如我找到个极度相似的旋律片段我肯定融它，我还考虑别人干什么。第一感受就是必须无感。」
     * 原来选点里和"旋律"沾边的只有波形包络相关与频段互补（两者都不是旋律），
     * 而 {@code Harmonic.match} 算的是整首歌一个 chroma 余弦——**它不知道 A 的尾奏与 B 的
     * intro 在时间轴上哪一段最像**。
     *
     * <p>做法：以 {@code hop = }{@link Envelope#chromaHopSecs} 逐帧取两侧窗口，允许 B 相对 A
     * <b>正负 {@value #MELODY_OFFSET_FRAMES} 帧</b>的相位偏移（取相似度最大的那个——两首歌
     * "像"的位置未必从同一个相对点开始），逐帧余弦相似度按权重平均；
     * <b>过渡前半段权重更高</b>（{@link #MELODY_FRONT_WEIGHT}）：融合刚开始那一下最容易听出来，
     * 那一段旋律要是撞上了，后面再好也救不回来。
     *
     * @return 0..1（1 = 完全重合）；任一侧没有 chroma 时返回 {@code NaN}（"不知道"，不假装）
     */
    static double melodyMatch(Envelope a, double cutSecs, Envelope b, double entrySecs,
                              double windowSecs) {
        if (a == null || b == null || a.chroma == null || b.chroma == null
                || !(a.chromaHopSecs > 0.0) || !(b.chromaHopSecs > 0.0) || !(windowSecs > 0.0)) {
            return Double.NaN;
        }
        int pc = SpectralOnsets.PITCH_CLASSES;
        int frames = (int) Math.floor(windowSecs / a.chromaHopSecs);
        if (frames < 2) {
            return Double.NaN;
        }
        int aStart = (int) Math.round(cutSecs / a.chromaHopSecs);
        int bStart = (int) Math.round(entrySecs / b.chromaHopSecs);
        double best = Double.NaN;
        for (int off = -MELODY_OFFSET_FRAMES; off <= MELODY_OFFSET_FRAMES; off++) {
            double sum = 0.0;
            double wsum = 0.0;
            int used = 0;
            for (int i = 0; i < frames; i++) {
                int ai = aStart + i;
                int bi = bStart + i + off;
                if (ai < 0 || ai >= a.chromaFrames || bi < 0 || bi >= b.chromaFrames) {
                    continue;
                }
                double dot = 0.0;
                double na = 0.0;
                double nb = 0.0;
                for (int k = 0; k < pc; k++) {
                    double va = a.chroma[ai * pc + k];
                    double vb = b.chroma[bi * pc + k];
                    dot += va * vb;
                    na += va * va;
                    nb += vb * vb;
                }
                if (na <= 1e-12 || nb <= 1e-12) {
                    continue;
                }
                double cos = dot / Math.sqrt(na * nb);
                double w = i * 2 < frames ? 1.0 : MELODY_FRONT_WEIGHT;
                sum += w * Math.max(0.0, cos);
                wsum += w;
                used++;
            }
            if (wsum > 0.0 && used >= 2) {
                double v = sum / wsum;
                if (Double.isNaN(best) || v > best) {
                    best = v;
                }
            }
        }
        return best;
    }

    /**
     * 旋律重合度的**分辨力**（一档 0.10）：同一对素材在整条候选轴上的读数波动实测
     * 0.79~0.86（skydive→春めく 的进场轴，每 0.5 s 一点），也就是**这个度量的噪声就在
     * ±0.05 量级**。而 MELODY_WEIGHT = 8.0 会把 0.019 的差放大成 0.08 的总分优势，
     * 足以把落点挪 1.5 s——那不是用户说的「**极度相似**的旋律片段」，是噪声。
     * 所以按 {@value} 一档归并：档内不区分（交给其余项），跨档才让旋律说话。
     * 真正的"极度相似"（例如 0.60 vs 0.90）跨 3 档，照样压倒一切。
     */
    static final double MELODY_STEP = 0.10;

    /** 三道闸的低门槛（2026-10-06 第一阶段工作六）：旋律重合低于它就保持原限制。 */
    static final double GATE_MELODY_LOW = 0.7;

    /** 三道闸的高门槛：旋律重合达到它就<b>全放开</b>。 */
    static final double GATE_MELODY_HIGH = 0.9;

    /**
     * <b>三道闸</b>（2026-10-06 第一阶段工作六）：把"放开到什么程度"交给旋律重合度决定。
     *
     * <p>用户原话：「放开限制后，在少数极端情况下过渡会变得很长、变速会很猛、切点会很早。
     * 为了只在旋律匹配足够高时才全放开，保留三道闸：旋律匹配大于 0.9，全放开；
     * 0.7 到 0.9，半放开（原限制和全放开限制的**线性插值**，系数 = (旋律 − 0.7)/0.2）；
     * 小于 0.7，保持原限制。」
     *
     * <p>为什么是插值而不是一个固定的"半开"档：用户在"极度相似的旋律片段我肯定融它"之后
     * 立刻补了这条闸——要的是<b>连续的信任度</b>，不是一个开关。0.8 恰好放开一半，
     * 与 0.89 的"几乎全放"之间没有台阶。
     *
     * @param original 原限制（保守）
     * @param opened   放开后的限制
     * @param melody   旋律重合读数（{@code NaN} = 没量到 ⇒ 按原限制）
     * @return 这一次实际该用的限制
     */
    static double gatedLimit(double original, double opened, double melody) {
        if (Double.isNaN(melody) || melody < GATE_MELODY_LOW) {
            return original;
        }
        if (melody >= GATE_MELODY_HIGH) {
            return opened;
        }
        double t = (melody - GATE_MELODY_LOW) / (GATE_MELODY_HIGH - GATE_MELODY_LOW);
        return original + (opened - original) * t;
    }

    /**
     * <b>歌词内容级锚点要不要参与选点</b>（2026-10-06：用户实测后关掉）。
     *
     * <p>用户原话：「点位计算时不是有问题 **完全对不上**」「你把我整个都改炸了。全部都是短切」。
     * 锚点命中时会把 {@code cut} 挪到"交接词 − 窗口/2"、并把窗口改成重复段的长度
     * （1.2~8 s）——那是**大动作**，在真实素材上直接把点位挪到别处去了。
     *
     * <p>所以：匹配、判定、判据**全部保留**（{@link LyricOverlap} + {@link #lyricAnchor} +
     * {@code AutoCueLyricAnchorTest} 都在），但**生产线默认不接**——先把点位还给原来的打分器。
     * 等把"命中时到底该怎么摆"想清楚（尤其是窗口该不该跟着变）再打开这一个开关。
     */
    static final boolean LYRIC_ANCHOR_IN_PLAN = false;

    /** 交接词在窗口里的目标位置（0.5 = 正中）：前半 A 念、后半 B 念。 */
    static final double LYRIC_ANCHOR_ALPHA = 0.5;

    /** 锚点窗口的下限（秒）：比这还短就不像一个融合段了。 */
    static final double LYRIC_ANCHOR_MIN_WINDOW_SECS = 1.2;

    /** 锚点窗口的上限（秒）：重复段很长时也不许无限拉长融合。 */
    static final double LYRIC_ANCHOR_MAX_WINDOW_SECS = 8.0;

    /** 交接词允许落在窗口里的范围（判据钉这个形状）。 */
    static final double LYRIC_ANCHOR_MIN_POS = 0.35;

    /** 见 {@link #LYRIC_ANCHOR_MIN_POS}。 */
    static final double LYRIC_ANCHOR_MAX_POS = 0.65;

    /**
     * <b>歌词锚点：把"交接词"摆到窗口正中，前半 A 念、后半 B 念</b>（用户实测修正）。
     *
     * <p>用户的原话（第一版做完之后的实测反馈）：「他确实匹配到了歌词相同段 **但是锚点计算有问题**
     * （这句话念完了切到 B 又念一遍【刚好是一整行一致 应当的做法是一半 A 读一半 B 读】）」。
     *
     * <p>病根：第一版**只挪了 `entry`**（`entry = cut + (tB − tA)`），`cut` 还是打分器挑的那个点
     * ——交接词落在窗口的哪里完全没保证。它要是落在窗口之外（比如 A 的那一行整行都在窗口之前），
     * 听到的就是"A 把整行念完 → 换 B → B 从头再念一遍"。
     *
     * <p>正确做法是两个式子一起用：
     * <pre>
     *   cut   = tA_交接 − 窗口 × α        （α = {@value #LYRIC_ANCHOR_ALPHA} ⇒ 交接词落在窗口正中）
     *   entry = cut + (tB_交接 − tA_交接)
     * </pre>
     * 合起来就保证：窗口**前半是 A 在念、后半是 B 在念，中缝正好落在同一个词上**
     * ——用户要的"两个词的话第一个词 A 念、第二个词 B 念"。
     *
     * <p>三重保险：① 新 cut 量化到 **A 的整拍**、新 entry 量化到 **B 的整拍**（钻进去之后两边都还在拍上）；
     * ② 新 cut 必须落在合法区间内、且与原 cut 的距离不超过一个窗口（不许为了锚点把出场点扔到别处去）；
     * ③ 双重门槛照旧（词段达标 **且** 交接处旋律够像）。
     *
     * @return {@code {cut', entry'}}；没命中/不合法/双重门槛不过时返回 {@code null}（调用方别动点位）
     */
    static double[] lyricAnchor(Envelope outro, double cutSecs, Envelope intro, double entrySecs,
                                double windowSecs, LyricSpans outroSpans, LyricSpans introSpans,
                                double cutFloor, double cutCeil) {
        if (outro == null || intro == null || outroSpans == null || introSpans == null
                || !outroSpans.hasTexts() || !introSpans.hasTexts() || !(windowSecs > 0.0)) {
            return null;
        }
        LyricOverlap.Match m = LyricOverlap.best(outroSpans, cutSecs, cutSecs + windowSecs,
                introSpans, entrySecs, entrySecs + windowSecs);
        if (!m.strong()) {
            return null;
        }
        double delta = m.bHandoverSecs - m.aHandoverSecs;      // entry − cut
        // **窗口由重复段反推**（用户实测抓到的设计硬伤）：第一版用的是打分器挑的那个窗口
        // （本机 6 s），而重复的那一行通常只有 3~4 s ⇒ 窗口把**整行**盖住、B 又从头念一遍，
        // 听到的就是"念两遍"。窗口取**重复段本身的长度**（夹进 [1.2, 8] s）之后：
        // 窗口正好盖住这一段，交接词在正中 ⇒ **前半段 A 为主、后半段 B 为主，词是连续的、
        // 换的是嗓子**——这才是"一半 A 读一半 B 读"。
        double anchorWindow = Math.max(LYRIC_ANCHOR_MIN_WINDOW_SECS,
                Math.min(LYRIC_ANCHOR_MAX_WINDOW_SECS, m.aRunEndSecs - m.aRunStartSecs));
        // ① 交接词摆到窗口正中（这就是"前半 A、后半 B"的代数形式）
        double newCut = m.aHandoverSecs - anchorWindow * LYRIC_ANCHOR_ALPHA;
        double periodA = outro.tempo != null && outro.beatRate > 0
                ? outro.tempo.periodFrames / (double) outro.beatRate : 0.0;
        if (periodA > 0.05) {
            newCut = Math.round(newCut / periodA) * periodA;
        }
        // ② 合法区间 + 不许离原来的 cut 太远（一个窗口以内）
        if (newCut < cutFloor || newCut > cutCeil || Math.abs(newCut - cutSecs) > windowSecs) {
            return null;
        }
        double newEntry = newCut + delta;
        double periodB = intro.tempo != null && intro.beatRate > 0
                ? intro.tempo.periodFrames / (double) intro.beatRate : 0.0;
        if (periodB > 0.05) {
            newEntry = Math.round(newEntry / periodB) * periodB;
        }
        if (newEntry < 0.0 || newEntry + anchorWindow > intro.durationSecs) {
            return null;
        }
        // ③ 双重门槛：交接处的旋律也必须像（按**锚点窗口**量）
        double melody = melodyMatch(outro, newCut, intro, newEntry, anchorWindow);
        if (Double.isNaN(melody) || melody < LYRIC_ANCHOR_MIN_MELODY) {
            return null;
        }
        return new double[] {newCut, newEntry, anchorWindow};
    }

    /** 内容级锚点的**旋律侧门槛**（双重门槛的第二条）：光文本像不算数，那一处旋律也要像。 */
    static final double LYRIC_ANCHOR_MIN_MELODY = 0.55;

    /**
     * <b>歌词内容级锚点：直接从重复的那一句钻进去</b>（2026-10-06 用户提的玩法）。
     *
     * <p>用户原话：「我直接看歌词数据有没有歌词内容重叠区域，**我直接先对拍然后直接从重复的
     * 地方钻进去**岂不是完美。」流程正是这个顺序：出场点先吸到拍上 → 在两边窗口里找同一句词
     * → 要让同一句同时唱出来就必须 {@code entry = cut + (ta − tb)}，Δ 再**量化到 B 的整拍**
     * （两句同词在各自歌里本来就都起在拍上，量化保证钻进去之后两边都还在拍上）。
     *
     * <p><b>双重门槛</b>：同一句词 ≠ 同一段旋律（翻唱、采样、同名不同曲，或 "oh baby" 这种
     * 通用句）。所以文本要强（{@link LyricOverlap#STRONG_SIMILARITY}）**且**钻进去那一处的
     * 旋律重合度要够（{@value #LYRIC_ANCHOR_MIN_MELODY}）——只过文本那条不动手。
     */
    static double lyricAnchoredEntry(Envelope outro, double cutSecs, Envelope intro,
                                     double entrySecs, double windowSecs,
                                     LyricSpans outroSpans, LyricSpans introSpans) {
        if (outro == null || intro == null || outroSpans == null || introSpans == null
                || !outroSpans.hasTexts() || !introSpans.hasTexts() || !(windowSecs > 0.0)) {
            return entrySecs;
        }
        LyricOverlap.Match m = LyricOverlap.best(outroSpans, cutSecs, cutSecs + windowSecs,
                introSpans, entrySecs, entrySecs + windowSecs);
        if (!m.strong()) {
            return entrySecs;
        }
        // **符号**（判据抓到的真错误）：A 从 cut 起播、B 从 entry 起播，要让同一句同时唱出来
        //   ta − cut = tb − entry  ⇒  **entry = cut + (tb − ta)**
        // 而 Match.offsetSecs 记的是 ta − tb（A 那句比 B 那句晚多少），所以这里取负。
        double delta = -m.offsetSecs();      // entry = cut + (tB − tA)，交接在重复段中缝上
        double period = intro.tempo != null && intro.beatRate > 0
                ? intro.tempo.periodFrames / (double) intro.beatRate : 0.0;
        if (period > 0.05) {
            delta = Math.round(delta / period) * period;
        }
        double candidate = cutSecs + delta;
        if (candidate < 0.0 || candidate + windowSecs > intro.durationSecs) {
            return entrySecs;
        }
        if (Math.abs(candidate - entrySecs) < 1e-6) {
            return entrySecs;
        }
        double melody = melodyMatch(outro, cutSecs, intro, candidate, windowSecs);
        if (Double.isNaN(melody) || melody < LYRIC_ANCHOR_MIN_MELODY) {
            return entrySecs;
        }
        return candidate;
    }

    /** melodyMatch 的 NaN（量不出来）转成 Score.melodyToScore 要的 null；并把读数按 {@link #MELODY_STEP} 归并。 */
    static Double melodyOrNull(double melody) {
        if (Double.isNaN(melody)) {
            return null;
        }
        double q = Math.floor(melody / MELODY_STEP + 1e-9) * MELODY_STEP;
        return Double.valueOf(Math.min(1.0, q));
    }

    /** {@link #melodyMatch} 允许的相位偏移（帧）：两首歌"像"的位置未必从同一个相对点开始。 */
    static final int MELODY_OFFSET_FRAMES = 8;

    /** {@link #melodyMatch} 里<b>后半段</b>的权重（前半段恒为 1.0）——开头最要命。 */
    static final double MELODY_FRONT_WEIGHT = 0.5;

    /** {@link #barPhaseOf} 均匀扫相位时的份数（一小节 4 拍 ⇒ 每拍 12 份）。 */
    static final int BAR_PHASE_STEPS = 48;

    /**
     * <b>小节网格的相位</b>（秒）：把相位在一个小节内扫一遍，取"落在小节线上的起音之和"最大者。
     *
     * <p>只有 BPM 是没有用的——那只是周期。用户 2026-10-05：「把节拍完全对上就对味了」，
     * 而循环点、淡入点都必须落在**小节线**上（实测小节线对齐之后接缝自己就压到本底）。
     *
     * <h3>已知问题：它与打分那一把小节尺子不是同一张栅格（2026-10-06 实测，<b>尚未修</b>）</h3>
     *
     * <p>本方法在一小节里均匀扫 {@value #BAR_PHASE_STEPS} 份——一小节 4 拍 ⇒ 每拍 12 份，
     * 也就是<b>允许它返回一条"谁都不是"的小节线</b>（不在拍栅格上）。而小节线按定义就是拍：
     * {@link #snapToBar} 的用途是"把循环点/淡入点吸到小节线上"，"在拍上"这件事只有拍栅格
     * 说了算。实测（探针 {@code BarRulerDisagreementProbe}，48 kHz、真素材）：
     *
     * <ul>
     *   <li>skydive：本方法给相位 <b>0.273437 s</b>，而拍栅格的第一拍在 <b>0.447917 s</b>
     *       ——比拍栅格早 <b>0.372 拍</b>；春めく：<b>1.458829 s</b> vs 第一拍 0.4695 s，
     *       早 0.229 拍；</li>
     *   <li>{@link #barPhase}（打分尺子：低频起音、候选只有拍号 mod 4 四个位置，<b>本来就在拍上</b>）
     *       在 skydive 上给 <b>0.916667 s</b>。两把尺子在 A 的第 181 秒附近<b>差 0.643 s =
     *       1.372 拍</b>：乐句尺子的小节线在 180.2734 / 182.1484，打分尺子的在
     *       180.9166 / 182.7916；</li>
     *   <li>代价不是"差一点"：结构项的目标 {@code outroStartSecs} 是经 {@code toPhraseLine}
     *       吸到<b>乐句</b>尺子的线上的，而小节加分项（{@code barTerm}）奖励的是<b>打分</b>
     *       尺子的线——同一次打分里两项在奖励两条互相矛盾的小节线。</li>
     * </ul>
     *
     * <p><b>试过的改法与实测代价（都没有落地，因为都不是零成本）</b>：把候选相位限制在
     * 拍栅格上的那几个位置（目标函数不变），
     * <pre>
     *   A 的尾声结构地标  180.2734 s → 181.3854 s（离 Apple 的 181.1 从 −0.652 变成 +0.285 ✓）
     *   B 的进场点        14.9744 s → 14.4743 s（离 Apple 的 15.0 从 −0.026 变成 −0.526 ✗）
     *   两把尺子的偏差    1.372 拍   → 1.000 拍（整数拍 ⇒ 终于同一张拍栅格）
     *   连锁                AutoCueOutroLoopTest 红 2 条，其中一条是**真不一致**：
     *                      决策层（planWithLyricSpans）与渲染层（prepare）的小节线差整整一小节
     *                      （1.875 s），不是夹具数字变了
     * </pre>
     * 也就是说：这一改修好了 A 侧、破坏了 B 侧，并暴露出"吸小节线"这条路本身在**中点附近
     * 不稳定**（同一件事算两遍可以落到相邻两条线上）。它必须与循环路径/两路对齐那件事
     * <b>一起</b>落地，不能只落一半。判据（合成夹具上红-前实测 0.25 s 不在拍上）与全部实测
     * 数字记在 {@code docs/automix-modes.md} §15。修之前这里保持原样。
     */
    public static double barPhaseOf(Envelope env, double barSecs) {
        if (env == null || !(barSecs > 0.0) || env.onset == null || env.onset.length == 0) {
            return Double.NaN;
        }
        double[] onBeats = beatAlignedBarPhases(env, barSecs);
        if (onBeats != null) {
            return bestBarPhaseOf(env, barSecs, onBeats);
        }
        int steps = BAR_PHASE_STEPS;
        double[] uniform = new double[steps];
        for (int k = 0; k < steps; k++) {
            uniform[k] = barSecs * k / steps;
        }
        return bestBarPhaseOf(env, barSecs, uniform);
    }

    /**
     * 候选相位<b>限制在拍栅格上</b>的那几个位置（{@code 第一拍 + k×拍长}）。
     *
     * <p>没有可信拍栅格、拍长比一小节还长、或量不出第一拍时返回 {@code null}——
     * 那时调用方退回旧的 {@value #BAR_PHASE_STEPS} 份均匀扫描（"没有拍栅格就不许谈小节线"，
     * 与 {@link #barPhase} 同一条纪律）。
     */
    private static double[] beatAlignedBarPhases(Envelope env, double barSecs) {
        if (!env.hasBeatGrid()) {
            return null;
        }
        double period = env.tempo.periodFrames / (double) env.beatRate;
        if (!(period > 0.0) || period > barSecs) {
            return null;
        }
        double[] first = env.beatsIn(0.0, period * 2.0);
        if (first.length == 0) {
            return null;
        }
        int count = (int) Math.round(barSecs / period);
        if (count < 1) {
            return null;
        }
        double[] out = new double[count];
        for (int k = 0; k < count; k++) {
            out[k] = first[0] + k * period;
        }
        return out;
    }

    /**
     * 在给定候选相位里取"落在该相位上的整带起音之和"最大的那个（并列取最靠前者）。
     *
     * <p>目标函数与 {@link #barPhaseOf} 从前那一段<b>逐字相同</b>——这一层只换候选集，
     * 不换"什么叫一条好小节线"。
     */
    private static double bestBarPhaseOf(Envelope env, double barSecs, double[] candidates) {
        // **惊喜度加分**（2026-10-11 ✓ 来自安卓端「智能过渡 → 自动点位」的更新 ✓）：
        //   主项仍是"整首铺下来起音最密"的相位 ✓（不动 ✗）
        //   加成 = (1 − Surprise.score) × **15 %** × 主项最大值 ✓
        //   ⇒ 严格限幅 ✓ 压不过主项 ✗ · 算不出 ⇒ 0 ✓（行为与原来一致 ✓）
        final double[] sums = new double[candidates.length];
        double maxSum = 0;
        for (int c = 0; c < candidates.length; c++) {
            final double phase = candidates[c];
            double sum = 0.0;
            for (double t = phase; t < env.durationSecs; t += barSecs) {
                final int i = env.indexAt(t);
                for (int j = Math.max(0, i - 3); j < Math.min(env.onset.length, i + 4); j++) {
                    sum += env.onset[j];
                }
            }
            sums[c] = sum;
            if (sum > maxSum) maxSum = sum;
        }
        int periodOnsetFrames = 0;
        try {
            final double secPerFrame = env.durationSecs / Math.max(1, env.onset.length);
            if (env.tempo != null && env.tempo.periodFrames > 0 && env.beatRate > 0 && secPerFrame > 0) {
                periodOnsetFrames = (int) Math.round(
                        (env.tempo.periodFrames / (double) env.beatRate) / secPerFrame);
            }
        } catch (RuntimeException ignored) {
            periodOnsetFrames = 0;
        }
        double best = -1.0;
        double bestPhase = Double.NaN;
        for (int c = 0; c < candidates.length; c++) {
            double score = sums[c];
            if (periodOnsetFrames >= 8) {
                final double surprise = dev.t1m3.qplayer.audio.model.Surprise.score(
                        env.onset, 0, env.onset.length, periodOnsetFrames);
                score += (1.0 - surprise) * 0.15 * maxSum;
            }
            if (score > best) {
                best = score;
                bestPhase = candidates[c];
            }
        }
        return bestPhase;
    }

    /**
     * 把时刻吸附到<strong>小节线</strong>上（相位由 {@link #barPhaseOf} 给）。
     *
     * <p>出口只有这一个：计划层（造路径，见 {@link #outroLoopPath}）与渲染层
     * （{@code AutoCueTransition} 的循环素材）吸的是<strong>同一把尺子</strong>——
     * 两边各写一遍 {@code phase + round((t − phase)/bar) × bar} 必然会分叉，
     * 而"循环点落在小节线上"这条性质是靠构造保证的（不是碰上的），分叉就等于它没了。
     */
    public static double snapToBar(double secs, double phase, double barSecs) {
        if (!(barSecs > 0.0)) {
            return secs;
        }
        return phase + Math.round((secs - phase) / barSecs) * barSecs;
    }

    /**
     * <b>在 A 的尾奏里找一段可以循环的器乐 vamp</b>（用户 2026-10-05：iOS 27 的 AutoMix
     * 能"重新编配尾奏、甚至重复段落来桥接"；第 1 步探针已证明这批素材里都有）。
     *
     * <p>四条判据（{@link #VAMP_MIN_LEVEL_RATIO} / {@link #VAMP_MAX_CV} /
     * {@link #VAMP_MAX_VOCAL_FRACTION} / {@link #VAMP_MIN_SELF_SIM}）：够响、电平稳、
     * 没人唱、前后半像同一件事。**"没人唱"是关键**——重复一段人声等于把歌词唱两遍，
     * 而器乐 vamp 循环起来对听感是"背景继续"，也正是可以拿来当 B 的落床的那一段。
     *
     * <p>返回的小节数与相位都对齐（见 {@link #barPhaseOf}）：起点吸附到小节线、长度取整数小节。
     * 量不出（没有节拍/分频数据、或尾奏里没有合格段）时返回 {@code null}。
     */
    public static VampSpan outroVampSpan(Envelope env) {
        if (env == null || env.band == null || env.tempo == null || !(env.tempo.bpm > 0f)) {
            return null;
        }
        double barSecs = 4.0 * 60.0 / env.tempo.bpm;
        double phase = barPhaseOf(env, barSecs);
        if (Double.isNaN(phase)) {
            return null;
        }
        float[] profile = smoothLevel(env);
        double reference = referenceLevel(profile);
        if (reference <= 1e-6) {
            return null;
        }
        AutoCue.LyricSpans voices = vocalPhraseSpans(env);
        VampSpan best = null;
        double bestScore = -1.0;
        for (int bars = 2; bars <= 8; bars += 2) {
            double span = bars * barSecs;
            double from = Math.max(0.0, env.durationSecs - VAMP_TAIL_SECS);
            for (double t = from; t + span <= env.durationSecs - HOP_SECS; t += barSecs) {
                double start = phase + Math.round((t - phase) / barSecs) * barSecs;
                if (start < 0.0 || start + span > env.durationSecs) {
                    continue;
                }
                VampSpan c = measureVamp(env, profile, reference, voices, start, start + span);
                if (c.levelCv > VAMP_MAX_CV || c.vocalFrac > VAMP_MAX_VOCAL_FRACTION
                        || c.selfSim < VAMP_MIN_SELF_SIM
                        || meanLevelBetween(profile, start, start + span) < VAMP_MIN_LEVEL_RATIO
                                * reference) {
                    continue;
                }
                // 偏好更长、更相似的段（长段循环起来更不容易听出重复）
                double score = c.selfSim + 0.02 * bars;
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        return best;
    }

    /**
     * 找落床段时只扫 B 开头这么多秒。
     *
     * <p>与 {@link #VAMP_TAIL_SECS}（尾奏只看最后 45 秒）<b>同一把尺子、只是从头量</b>：
     * 两个函数问的是同一件事（"这一段素材里有没有一段干净的纯器乐"），只是一个在尾、一个在头，
     * 所以"看多长"这一格没有理由取两个不同的数。
     */
    static final double INTRO_BED_HEAD_SECS = 45.0;

    /**
     * 落床段最多伸到 B「真正开始」（{@link #introStartSecs}）之后这么多小节。
     *
     * <p>取值 {@value} 小节的依据是 {@code planCollect} 里那条既有的硬下界
     * （{@link #INTRO_SKIP_FLOOR_SECS} = 8 s，用户 2026-10-05 的规格：「允许从 intro 里的
     * 任意结构边界进入，跳过前面的冗余段」）：那段冗余开头的**宽度**在这个包里的量法就是
     * "「真正开始」往回 8 秒"。而小节是这一路上唯一的长度单位（BPM 只给周期、给不了相位），
     * 所以这一格也写成小节：8 小节在 120 BPM 是 16 s，与 8 s 同一量级，
     * 而且"B 的歌起来之后还能算落床"最多也就多给这么点——再多就不是"落床"，
     * 而是"从中间进场"了（那是另一档，`entryFloor` 管的那件事）。
     */
    static final double INTRO_BED_LEAD_BARS = 8.0;

    /**
     * <b>三段路径的 A 侧上限（小节）</b>——<b>A 的尾奏段 + A 的循环段 ×repeats</b>
     * （也就是这条路径<b>为 A 争取出来的那个过渡窗口</b>）不许比它长。
     *
     * <p><b>它只封 A 的贡献；B 的落床段（"B 的床"）不算在这 {@value} 小节里</b>
     * （2026-10-06 用户列表第 ④ 项的原话：「三段路径的 16 小节上限语义（上限只封 A 的
     * 贡献；B 的床单算）」）。理由是这两段本来就是两件事：A 侧那两段编出来的是"这个过渡
     * 有多长"，而 B 的落床段是"底下铺的是 B 的哪一段器乐"——它的长度由 <b>B 侧自己</b>的
     * 规矩管：扫描范围只到 B 开头 {@link #INTRO_BED_HEAD_SECS}（45 s）、终点不许越过
     * B 的「真正开始」{@link #INTRO_BED_LEAD_BARS} 小节（8 小节），长度也只在那 2/4/6/8
     * 小节里挑（见 {@link #introBedSpan} 的注释）。也就是说它**本来就有上界**，
     * 不必也不该再从 A 的过渡窗口里扣一遍——从前那条闸量"总窗"，于是 A 侧明明只有
     * 16 小节、却会因为"床还要落两遍"而被整条否掉。
     *
     * <p>取 {@value} 的依据还是循环规格自己那一句（{@code planOutroLoop} 的注释里引过）：
     * 「这时 loop 当前歌尾奏的最后 4/8 小节，<b>争取出 8–16 小节的过渡窗口</b>」——
     * 16 就是那套规格给"过渡窗口"的上界，而窗口就是 A 侧那两段。
     *
     * <p>另一条几何闸（整窗 ≤ A 的**全曲**时长）与这一条各管各的，写在
     * {@link #introBedPath} 里：那一条是物理约束（A 侧的素材得真的存在，渲染层自己也按
     * 它退），这一条是"过渡窗口有多长"。
     *
     * <p>放不下就<b>如实不选它</b>（而不是砍掉哪一段）：能砍的都是决定
     * （循环段的小节数与遍数来自 {@code planOutroLoop}，落床段来自 {@link #introBedSpan}），
     * 在这一层偷偷改它们的长度，说明行里写的与真放的就成两件事了。
     *
     * <p><b>已知边界（2026-10-06 在真素材上重新量过一遍）</b>：「买时间」那一档的 A 侧
     * 本身就可能已经贴着上限。把 {@code ~/Music} 缩到那两个演示文件、单独跑
     * {@code AutomixOutroLoopTriggerProbe}（2 个有序对），会走「买时间」的是
     * {@code qplayer-demo-B.mp3 → qplayer-demo-A.mp3}——<b>注意方向</b>：demo-B 当出场曲
     * （demo-A 那一侧尾奏还剩 33.46 小节、压根不循环，所以反方向走不到这条闸）。
     * 该对 100.0 BPM（一小节 2.4 s）、尾奏只剩 4.0 s = 1.7 小节、交叠 3.75 s、
     * 循环段 8 小节 × 2 遍、B 的落床段 6 小节：
     *
     * <pre>
     *   A 侧 = 尾奏段 2 小节（round(3.75 / 2.4)）+ 循环段 16 小节 = 18 小节 = 43.2 s
     *   上限 = 16 小节 × 2.4 s                                = 38.4 s
     *   B 的落床段 = 6 小节                                   = 14.4 s（现在**不计入**上面那条上限）
     * </pre>
     *
     * 也就是说：这个方向的三段路径**在"只封 A"这条新口径下仍然不成立**——挡住它的是 A 侧
     * 自己 18 小节 &gt; 16 小节（B 的床算不算进来都救不了它；A 侧与"两级扫描取了哪一级"
     * 无关，因为窗口与 A 的包络两级都一样）。这与"起点在打分上输掉"是两件不同的事
     * （旋转解决了后者，前者要动这条上限或那次循环决策本身）。
     * 另记一笔：{@code docs/automix-modes.md} 与这条注释 2026-10-05 记的是"demo-A→demo-B、
     * A 侧 17 小节（尾奏段 1）"——那是**另一次读数**（那一对当时的窗口是 2.5 s、落床段 8 小节，
     * 日志里"最少也要 60.0s"与那套数是自洽的）。两个读数都指向同一件事：
     * **A 侧自己就超过 16 小节，抬到 17 也不够**（这次实测是 18）。
     *
     * <p><b>为什么不把 {@value} 抬上去</b>（该不该抬到 17、甚至 18）：不抬，两条理由——
     * ① 那份循环决策（8 小节 × 2 遍 = 16 小节）**已经用满了**规格给的 8–16 小节，
     * A 侧多出来的那 2 小节是交叠窗口本身换算出来的尾奏段（那本来是要拿去交叉淡化的
     * 一截尾巴），把上限抬到 18 等于说"过渡窗口可以到 18 小节 = 43.2 s"，
     * 那就超出规格自己那句"争取出 8–16 小节的过渡窗口"了；
     * ② 要让这一对成立就得动 {@code planOutroLoop} 的循环长度（那一层的注释与判据
     * {@code AutoCueOutroLoop*} 钉着它的每一条触发），那是**另一档取舍**，
     * 不在这条闸这一层偷偷做（用户第 ④ 项要的是口径，不是数值）。
     * 于是这里如实记着：这一对的三段路径按今天的口径**不成立**。
     */
    static final double INTRO_BED_PATH_MAX_BARS = 16.0;

    /**
     * <b>在 B 的开头找一段「可以当落床的器乐段」</b>（用户 2026-10-05 的第三个例子：
     * 「A 的尾奏 + B 的纯鼓段循环 + B 的主歌」）。
     *
     * <p><b>与 {@link #outroVampSpan} 是同一套判据、同一把小节尺子</b>：够响
     * （{@link #VAMP_MIN_LEVEL_RATIO}）、电平稳（{@link #VAMP_MAX_CV}）、
     * <b>人声占比 ≤ {@link #VAMP_MAX_VOCAL_FRACTION}</b>、前后半自相似
     * （{@link #VAMP_MIN_SELF_SIM}），长度取 2/4/6/8 小节里分最高的那个
     * （{@code selfSim + 0.02×小节数}），起点吸附到小节线；量不出来返回 {@code null}。
     * 两条都是"重复一段人声等于把歌词唱两遍"，而<b>器乐</b>段循环起来对听感是"背景继续"——
     * 所以这两处的判据没有理由不一样（各自的**范围**不一样，见下）。
     *
     * <p><b>范围（唯一与尾奏 vamp 不同的那一格，也是唯一的论证）</b>：起点固定在
     * B 的<strong>第一条小节线</strong>（{@link #barPhaseOf} 给的相位），终点不超过
     * {@code min(INTRO_BED_HEAD_SECS, 「真正开始」+ INTRO_BED_LEAD_BARS 小节)}。三条理由：
     *
     * <ol>
     *   <li><b>它必须是 B 的开头</b>。落床段的用处是"过渡期先铺着 B 的器乐，B 的主歌等它铺完
     *       再进"；起点一旦落在后面，B 自己的开头就永远不会播（渲染层的接缝只能落在路径第一段
     *       的终点，进场点就是"B 从哪儿开始响"）——那不是落床，是"从中间进场"，
     *       而那一档在这个包里另有规矩（{@code entryFloor}：不许回到冗余开头段）。</li>
     *   <li><b>起点与终点都要落在小节线上</b>，这样"B 的落床段之后接着播 B 的其余部分"
     *       才是构造出来的性质：落床段是整数小节，它的终点自然在 B 的小节线上，
     *       主歌从那条线起头。</li>
     *   <li><b>终点不许越过"真正开始"太多</b>（{@link #INTRO_BED_LEAD_BARS} 小节）：
     *       越过它就已经进了 B 的正题，"落床"这个说法就不成立了。</li>
     * </ol>
     *
     * <p><b>已知边界（如实记）</b>：起点钉在 B 的第一条小节线上，所以"先来两小节拍手/
     * 一小节静音，然后才是鼓"那种素材找不到落床——这一档宁可找不到（返回 {@code null}，
     * 三段路径于是不成立），也不把范围放宽去猜"哪一段才是他要的开头"。
     *
     * @return 落床段（{@code from}/{@code to} 都在 B 的小节线上）；没有合格段时 {@code null}
     */
    public static VampSpan introBedSpan(Envelope env) {
        if (env == null || env.band == null || env.tempo == null || !(env.tempo.bpm > 0f)) {
            return null;
        }
        double barSecs = 4.0 * 60.0 / env.tempo.bpm;
        double phase = barPhaseOf(env, barSecs);
        if (Double.isNaN(phase)) {
            return null;
        }
        // B 的第一条小节线：相位落在 [0, 小节长) 里，所以它就是起点（负相位不出现）。
        double start = Math.max(0.0, phase);
        if (start >= env.durationSecs - HOP_SECS) {
            return null;
        }
        // 终点上界：只扫 B 开头这一截（见上面第 3 条理由）。
        double mainStart = introStartSecs(env);
        double ceiling = mainStart >= 0.0
                ? Math.min(INTRO_BED_HEAD_SECS, mainStart + INTRO_BED_LEAD_BARS * barSecs)
                : INTRO_BED_HEAD_SECS;
        float[] profile = smoothLevel(env);
        double reference = referenceLevel(profile);
        if (reference <= 1e-6) {
            return null;                        // 整段没电平：没有落床可言
        }
        AutoCue.LyricSpans voices = vocalPhraseSpans(env);
        VampSpan best = null;
        double bestScore = -1.0;
        // 长度与尾奏 vamp 同一把梯子（2/4/6/8 小节），打分同一把尺子（更长、更像同一件事更好）。
        for (int bars = 2; bars <= 8; bars += 2) {
            double to = start + bars * barSecs;
            if (to > env.durationSecs - HOP_SECS || to > ceiling) {
                continue;
            }
            VampSpan c = measureVamp(env, profile, reference, voices, start, to);
            if (c.levelCv > VAMP_MAX_CV || c.vocalFrac > VAMP_MAX_VOCAL_FRACTION
                    || c.selfSim < VAMP_MIN_SELF_SIM
                    || meanLevelBetween(profile, start, to) < VAMP_MIN_LEVEL_RATIO
                            * reference) {
                continue;
            }
            double score = c.selfSim + 0.02 * bars;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static VampSpan measureVamp(Envelope env, float[] profile, double reference,
                                        LyricSpans voices, double from, double to) {
        int i0 = Math.max(0, env.indexAt(from));
        int i1 = Math.min(profile.length - 1, env.indexAt(to));
        double sum = 0.0;
        double sumSq = 0.0;
        int n = Math.max(1, i1 - i0 + 1);
        for (int i = i0; i <= i1; i++) {
            sum += profile[i];
            sumSq += (double) profile[i] * profile[i];
        }
        double mean = sum / n;
        double cv = mean > 1e-6 ? Math.sqrt(Math.max(0.0, sumSq / n - mean * mean)) / mean : 9.9;
        double voiced = 0.0;
        if (voices != null) {
            for (int i = 0; i < voices.starts.length; i++) {
                double a = Math.max(voices.starts[i], from);
                double b = Math.min(voices.ends[i], to);
                if (b > a) {
                    voiced += b - a;
                }
            }
        }
        int mid = (i0 + i1) / 2;
        double sim = bandProfileSim(env, i0, mid, mid + 1, i1);
        return new VampSpan(from, to, sim, cv, voiced / Math.max(1e-6, to - from));
    }

    private static double meanLevelBetween(float[] profile, double from, double to) {
        int i0 = Math.max(0, (int) Math.round(from / HOP_SECS));
        int i1 = Math.min(profile.length - 1, (int) Math.round(to / HOP_SECS));
        double sum = 0.0;
        int n = 0;
        for (int i = i0; i <= i1; i++) {
            sum += profile[i];
            n++;
        }
        return n == 0 ? 0.0 : sum / n;
    }

    /** 两段的三频带平均轮廓的皮尔逊相关（都取不到就返回 0）。 */
    private static double bandProfileSim(Envelope env, int a0, int a1, int b0, int b1) {
        if (env.band == null || a1 < a0 || b1 < b0) {
            return 0.0;
        }
        int k = Math.min(3, env.band[0].length);
        double[] x = new double[k];
        double[] y = new double[k];
        for (int i = a0; i <= a1; i++) {
            for (int b = 0; b < k; b++) {
                x[b] += env.band[i][b];
            }
        }
        for (int i = b0; i <= b1; i++) {
            for (int b = 0; b < k; b++) {
                y[b] += env.band[i][b];
            }
        }
        double mx = 0.0;
        double my = 0.0;
        for (int b = 0; b < k; b++) {
            x[b] /= (a1 - a0 + 1);
            y[b] /= (b1 - b0 + 1);
            mx += x[b];
            my += y[b];
        }
        mx /= k;
        my /= k;
        double num = 0.0;
        double dx = 0.0;
        double dy = 0.0;
        for (int b = 0; b < k; b++) {
            num += (x[b] - mx) * (y[b] - my);
            dx += (x[b] - mx) * (x[b] - mx);
            dy += (y[b] - my) * (y[b] - my);
        }
        return dx <= 1e-12 || dy <= 1e-12 ? 0.0 : num / Math.sqrt(dx * dy);
    }

    public static final class LyricSpans {

        /** 每一句的起始秒（升序）。 */
        public final double[] starts;

        /** 每一句的结束秒（与 {@link #starts} 等长；不可知时用下一句起点兜底）。 */
        public final double[] ends;

        /**
         * <b>每一句的文本</b>（与 {@link #starts} 等长；{@code null} = 这一路没给字）。
         *
         * <p>为什么要带上字（2026-10-06 用户提的玩法）：「我不是有歌词数据吗，我直接看歌词数据
         * 有没有歌词内容重叠区域，我直接先对拍然后直接从重复的地方钻进去岂不是完美」。
         *
         * <p>在此之前这条线上**只有时间戳、没有字**——"哪一句是什么词"在进混音层之前就被丢掉了，
         * 于是歌词只能当"句边界"用（{@code alignsWithHead} / {@code endsPhraseNear}），
         * 用不上"这两句是同一句词"这个**内容级**的锚点。
         *
         * <p>可空是刻意的：老调用方与全部既有判据一位不变（没有字就当没这一路）。
         */
        public final String[] texts;

        public LyricSpans(double[] starts, double[] ends) {
            this(starts, ends, null);
        }

        public LyricSpans(double[] starts, double[] ends, String[] texts) {
            this.starts = starts == null ? new double[0] : starts;
            this.ends = ends;
            this.texts = texts != null && starts != null && texts.length == starts.length
                    ? texts
                    : null;                  // 长度对不上就整条当"没有字"，不做半吊子对齐
        }

        /** 第 {@code i} 句的词；没有字或越界返回 {@code null}。 */
        public String textAt(int i) {
            return texts == null || i < 0 || i >= texts.length ? null : texts[i];
        }

        /** 这一路有没有字（决定内容级锚点能不能用）。 */
        public boolean hasTexts() {
            return texts != null;
        }

        /** 结束时间可用吗（等长且不成片为 0）。 */
        boolean hasEnds() {
            return ends != null && ends.length == starts.length;
        }
    }

    /** 兼容入口：只给行起始时间（结束时间按"下一句起点"兜底，与加结束时间之前逐位相同）。 */
    public static LyricSpans spansOfStarts(double[] starts) {
        if (starts == null) {
            return null;
        }
        // ends 传 null：**"没有结束时间"与"结束时间恰好等于下一句起点"是两件事**。
        // 前者只能退回槽位近似（而且不许用它去行使"排开歌词"那道硬门——实测过：
        // 槽位近似会把"下一句前 gA 秒"这个节奏上最理想的位置判成"念到一半"，
        // 于是门和节奏项互相打架）；后者是宿主给了真实结束时间。
        return new LyricSpans(starts, null);
    }

    static Double lineGapTerm(double[] outroLines, double[] introLines,
                              double cutSecs, double entrySecs, double windowSecs) {
        return lineGapTerm(spansOfStarts(outroLines), spansOfStarts(introLines),
                cutSecs, entrySecs, windowSecs);
    }

    /**
     * <b>「融合期里夹进了新句头」= 扣分，不再是硬筛</b>（2026-10-05 第三轮）。
     *
     * <p>为什么改：它当筛子的时候把进场候选集收窄成"B 的人声段起点之间的那些格子"，
     * 参考对（skydive → 春めく）于是被钉在 175.32 → 10.00，而锚点想要 14.0、Apple 是 14~15
     * ——也就是说 Apple 那种做法恰恰违反它。用户说"全力接近苹果的"，所以它降成**偏好**：
     * 夹了新句头的落点罚到 0.4 倍（歌词项权重 0.10，罚完还压不住更强理由就是设计如此），
     * 但**候选集不再被它收窄**。
     */
    static Double lineGapTerm(LyricSpans outroSpans, LyricSpans introSpans,
                              double cutSecs, double entrySecs, double windowSecs) {
        return penaliseDirtyFade(outroSpans, introSpans, entrySecs, windowSecs,
                lineGapTermRaw(outroSpans, introSpans, cutSecs, entrySecs, windowSecs));
    }

    /** 见 {@link #lineGapTerm}：夹了新句头就把这一项罚到 0.4 倍。 */
    private static Double penaliseDirtyFade(LyricSpans outroSpans, LyricSpans introSpans,
                                            double entrySecs, double windowSecs, Double raw) {
        if (raw == null || introSpans == null) {
            return raw;
        }
        double gap = phraseGapOf(outroSpans, entrySecs + windowSecs);
        if (headFollowsPhraseGap(introSpans, entrySecs, new double[]{windowSecs}, gap,
                ENTRY_HEAD_TOL_SECS, /*requireCleanFade=*/true)) {
            return raw;
        }
        return Double.valueOf(raw.doubleValue() * DIRTY_FADE_PENALTY);
    }

    static Double lineGapTermRaw(LyricSpans outroSpans, LyricSpans introSpans,
                                 double cutSecs, double entrySecs, double windowSecs) {
        double[] outroLines = outroSpans == null ? null : outroSpans.starts;
        double[] introLines = introSpans == null ? null : introSpans.starts;
        if (outroLines == null || outroLines.length < 2 || introLines == null
                || introLines.length == 0) {
            return null;
        }
        // ── 判定点不是"进场点"，而是**融合的等功率中点**（2026-10-04 用户点出来的）──
        //
        // 用户原话：「你有没有想过融合也要时间，你选择 B 入场还是会滑一下，
        // 最后的效果就是 B 听感还是从歌词中间出来的」。
        //
        // 完全正确，而且这正是我前几版量错的地方：`entrySecs` 只是 B **开始出声**的位置，
        // 而 B 的增益要铺满整个窗口才到满。等功率交叉在中点（两边增益都是 0.707），
        // **那里才是听感上"这首歌归 B 了"的那一刻**——用户听到的"B 出来了"就是它。
        // 所以歌词节奏要对着那一刻量：一句歌词应当在**中点**开始（或中点落在两句之间），
        // 而不是在 entry 那一刻。
        //
        // 出场侧对称：`cutSecs` 只是 A 开始退的位置，A 交出主导权同样在中点。
        // ── 这一项真正要量的是什么（用户 2026-10-04 的原话）──
        //
        //   「A 的句子唱完后等多久 B 的句子才起头。这个间距要符合 A 最后几句的平均间隔」
        //
        // 也就是**乐句栅格无缝接上**：B 的第一句应当起在"A 本该唱下一句"的那个时刻。
        // 时间轴统一到**真实时间**（以交接那一刻为 0）：
        //   A 的行起始 s_A 出现在 s_A − cut、B 的行起始 s_B 出现在 s_B − entry。
        //
        // 第一版量的是「从**融合中点**到 B 下一句的间隔 vs A 的间隔」——那是错的：
        // 中点跟歌词句子的落点没有关系（增益曲线不改变时间），而"A 最后唱到哪一句"
        // 也被忽略掉了（实测：B 的第一句落在 A 上一句起头后 7.5 s，而 A 的呼吸是 4 s，
        // 于是听起来是两句人声撞在一起）。
        double gA = medianGapBefore(outroLines, cutSecs);
        if (!(gA > 0.0)) {
            return null;
        }
        // A 最后唱得到的那一句（融合期里最后起头的一句），以及"它之后本该唱下一句"的时刻
        int aLastIdx = -1;
        for (int i = 0; i < outroLines.length; i++) {
            if (outroLines[i] > cutSecs + windowSecs) {
                break;
            }
            aLastIdx = i;
        }
        if (aLastIdx < 0) {
            return null;      // 融合期里 A 一句都没唱：量不出来
        }
        // **A 的声音什么时候真正消失**（用户 2026-10-04：「请不要忘记重叠时间的影响」）：
        // A 的最后一句起头之后，它要么自己唱完，要么被融合本身截断——那时它的增益已经降到 0，
        // 人声就听不见了。所以取两者里**更早**的那个：
        //   endA = min(最后一句唱完的时刻, 融合时长)
        // 少了这一条会把理想落点算早：例如 cut 242.3 / 窗口 5 s 时 A 的最后一句起在真实
        // 5.2 s、本来要唱到 5.5 s，但融合 5.0 s 一到 A 就没了 → 听感上的"静默起点"是 5.0 s。
        //
        // **"唱完的时刻"有真实结束时间就用真实的**（逐字歌词，见 outroRealEnd）。
        double endAReal = Math.min(outroRealEnd(outroSpans, aLastIdx, cutSecs), windowSecs);
        double aNextReal = endAReal + gA;
        // B 在交接之后的第一句
        double bFirst = Double.NaN;
        for (int i = 0; i < introLines.length; i++) {
            if (introLines[i] >= entrySecs) {
                bFirst = introLines[i];
                break;
            }
        }
        if (Double.isNaN(bFirst)) {
            return null;      // 进场点之后没有歌词了：量不出来
        }
        double bFirstReal = bFirst - entrySecs;
        double score = 1.0 - Math.min(1.0, Math.abs(bFirstReal - aNextReal) / Math.max(gA, 0.4));
        for (int i = 0; i < introLines.length; i++) {
            if (introLines[i] > entrySecs && introLines[i] < entrySecs + windowSecs) {
                score *= 0.5;
                break;
            }
        }
        // 「入场时是不是刚好卡在念歌词」（2026-10-04 用户要求）：落进某一句的槽位后半段
        // 就算卡住了——节奏对得上也救不回来，所以这项罚得比上面那条更重。
        // 落在起点上（into ≈ 0）不罚：那是最理想的落点。进场点在第一句之前也不罚
        // （前奏里的人声还没开始）。
        double heardIntro = entrySecs + windowSecs * 0.5;
        if (score > 0.0) {
            int prevIdx = -1;
            for (int i = 0; i < introLines.length; i++) {
                if (introLines[i] <= heardIntro) {
                    prevIdx = i;
                } else {
                    break;
                }
            }
            if (prevIdx >= 0) {
                // 中点这一刻"这一句唱到哪儿了"（0 = 刚开始、1 = 已经唱完）。
                // 有真实结束时间就用它——**这才是用户说的"卡在念歌词"**；
                // 没有就退回槽位近似（一句唱到下一句起点），并在注释里如实标明这是近似。
                if (introSpans.hasEnds() && introSpans.ends[prevIdx] > introLines[prevIdx]) {
                    // **有真实结束时间：罚成驼峰**（2026-10-04）。
                    //
                    // 这一句从 start 唱到 end，中点落在它中间的哪一段才叫"卡在念歌词"？
                    // 听感上两端都是好落点：**刚起头**（0，B 的这句从融合中点开始，正合适）
                    // 与**刚唱完**（1，正好落在两句之间的空隙里）；最差的是**正中间**。
                    // 所以罚则写成 4·s·(1−s)（两端 0、正中 1）——
                    // 第一版用"宽限带 + 线性滑到底"是按槽位近似设计的，在真实结束时间下
                    // 会把"唱到 43%"只罚 18%（实测），而 43% 早就不是句首了。
                    double len = introSpans.ends[prevIdx] - introLines[prevIdx];
                    double sung = Math.min(1.0, Math.max(0.0,
                            (heardIntro - introLines[prevIdx]) / len));
                    double hump = 4.0 * sung * (1.0 - sung);
                    score *= 1.0 - LINE_MID_STRENGTH * hump;
                    return Double.valueOf(score);
                }
                double sungFraction;
                if (prevIdx + 1 < introLines.length) {
                    double slot = introLines[prevIdx + 1] - introLines[prevIdx];
                    sungFraction = slot <= 1e-6 ? 0.0
                            : Math.min(1.0, Math.max(0.0,
                                    (heardIntro - introLines[prevIdx]) / slot));
                } else {
                    return Double.valueOf(score);   // 最后一句、且不知道它唱到哪儿：不猜
                }
                if (sungFraction < 1.0 && sungFraction > LINE_MID_GRACE) {
                    double slide = (sungFraction - LINE_MID_GRACE) / (1.0 - LINE_MID_GRACE);
                    score *= 1.0 - LINE_MID_STRENGTH * slide;
                }
            }
        }
        return Double.valueOf(score);
    }

    /** 出场点之前最近几句的间距中位数（最多取 4 个间距）。 */
    /**
     * <b>A 的乐句间隔 gA</b>（末几句间隔的中位数）：B 的下一句要在融合结束之后隔 gA 才起头。
     *
     * <p>公开出来是给调用方判"这一对点位有没有满足歌词那三条"用的——生产的两级决策里，
     * 第二级的触发条件要算上它（见 {@code AutoCueTransition}）。
     */
    public static double phraseGapOf(LyricSpans outroSpans, double nearSecs) {
        return phraseGapOf(outroSpans, nearSecs, VOCAL_PHRASE_STRONG_SECS);
    }

    /**
     * 同上，但只用**像样长**的乐句来量间隔（{@code minPhraseSecs}）。
     *
     * <p>为什么必须过滤（2026-10-05 实测踩到）：人声活动段会被咬字/气口切碎——
     * Sparks Fly 从 49 行歌词变成 **58 段**人声段，碎片彼此只隔 1 秒上下。
     * 直接取中位数的话 gA 会从 4.5 s 掉到 1 s 出头，于是"隔 gA"这条约束
     * **形同虚设**：实测进场点又跑回 51.7 s（那里下一句只隔 1.06 s），
     * 用户的原话是「又在唱的时候插入」。
     */
    public static double phraseGapOf(LyricSpans spans, double nearSecs, double minPhraseSecs) {
        if (spans == null || spans.starts.length < 3) {
            return Double.NaN;
        }
        double[] starts = spans.starts;
        double[] ends = spans.ends;
        boolean real = ends != null && ends.length == starts.length;
        double[] gaps = new double[6];
        int n = 0;
        int lastStrong = -1;
        for (int i = 0; i < starts.length && starts[i] <= nearSecs; i++) {
            double len = real ? ends[i] - starts[i] : 0.0;
            if (len < minPhraseSecs) {
                continue;                       // 碎片（咬字/气口）不参与
            }
            if (lastStrong >= 0) {
                if (n < gaps.length) {
                    gaps[n++] = starts[i] - starts[lastStrong];
                } else {
                    System.arraycopy(gaps, 1, gaps, 0, gaps.length - 1);
                    gaps[gaps.length - 1] = starts[i] - starts[lastStrong];
                }
            }
            lastStrong = i;
        }
        if (n == 0) {
            // **量不出来返回 NaN，不是 0**（2026-10-05 修）：0 在 headFollowsPhraseGap 里会被当成
            // "间距就是 0"，于是规则退化成"B 的句头必须压在融合结束那一刻"（旧规则），
            // 把合格点挤没。NaN 表示"不知道"，那条判据整体不参与。
            return Double.NaN;
        }
        double[] used = java.util.Arrays.copyOf(gaps, n);
        java.util.Arrays.sort(used);
        return n % 2 == 1 ? used[n / 2] : 0.5 * (used[n / 2 - 1] + used[n / 2]);
    }

    private static double medianGapBefore(double[] lines, double cutSecs) {
        double[] gaps = new double[4];
        int n = 0;
        for (int i = lines.length - 1; i > 0 && n < gaps.length; i--) {
            if (lines[i] <= cutSecs) {
                gaps[n++] = lines[i] - lines[i - 1];
            }
        }
        if (n == 0) {
            // **量不出来返回 NaN，不是 0**（2026-10-05 修）：0 在 headFollowsPhraseGap 里会被当成
            // "间距就是 0"，于是规则退化成"B 的句头必须压在融合结束那一刻"（旧规则），
            // 把合格点挤没。NaN 表示"不知道"，那条判据整体不参与。
            return Double.NaN;
        }
        double[] used = java.util.Arrays.copyOf(gaps, n);
        java.util.Arrays.sort(used);
        return n % 2 == 1 ? used[n / 2] : 0.5 * (used[n / 2 - 1] + used[n / 2]);
    }

    /** 交叠窗口的上限（与设置里那个滑块的上界一致；只是防御，正常候选都远小于它）。 */
    static final double MAX_WINDOW_SECS = 60.0;

    /**
     * 歌词行起始时间的来源（<b>宿主注入</b>）：给一个音源字符串（路径或直链），
     * 返回它每一句歌词的起始秒（升序）；没有歌词信息就返回 {@code null} 或空数组。
     *
     * <p>引擎自己不取歌词（那是控制器的事，见 {@code PlayerController} 的预热路径），
     * 这里只要一个查询口。与 {@code Decoder.install} / {@code StemSeparators.uninstallDsp}
     * 同一个模式：默认没有来源，于是这一项**不加**——「算不出来就不猜」那条纪律。
     */
    private static volatile java.util.function.Function<String, double[]> lineSource;

    /** 装上歌词来源（宿主启动时调一次；传 {@code null} 卸下）。 */
    /**
     * 带上行**结束**时间的来源（宿主有 {@code LyricLine.endMs()}，用它更准）。
     *
     * <p>两个入口同时注入时以本方法为准；只注入 {@link #installLineSource} 的旧调用方
     * 行为逐位不变（结束时间按"下一句起点"兜底）。
     */
    public static void installLyricSpanSource(
            java.util.function.Function<String, LyricSpans> source) {
        lyricSpanSource = source;
    }

    public static void installLineSource(java.util.function.Function<String, double[]> source) {
        lineSource = source;
    }

    /** 某个音源的歌词行时间；没有来源、没有歌词、或者返回空表都算「没有」。 */
    /** {@link #installLyricSpanSource} 注入进来的那一个（没有就用 starts-only 兜底）。 */
    static LyricSpans lyricSpansOf(String source) {
        java.util.function.Function<String, LyricSpans> spans = lyricSpanSource;
        if (spans != null) {
            LyricSpans got = spans.apply(source);
            if (got != null) {
                return got;
            }
        }
        return spansOfStarts(linesOf(source));
    }

    private static volatile java.util.function.Function<String, LyricSpans> lyricSpanSource;

    // ==================================================================
    // 调性关系（A 与 B 的调性距离）——"调性冲突但节奏兼容"那条触发的来源
    // ==================================================================

    /**
     * <b>一对曲子的调性关系</b>（由调性分析算出来、经 {@link KeyRelationSource} 注入）。
     *
     * <p>仓库里调性的表示<strong>没有调名、没有 Camelot</strong>（见 {@code docs/automix-modes.md}
     * §5.1：全仓库 grep {@code camelot|circle of fifths|关系大小调} 零命中，调性就是一个
     * 12 维 chroma 向量）。所以这里也不发明"环距"，用的就是仓库既有的那个量：
     * 两段 chroma 的<strong>余弦相似度</strong>（不转调），1.0 = 同一个调。
     * "关系大小调/相邻调"这些 DJ 手法是它的副产物（共享音级集合 → 相似度天然高），
     * 不需要另立规则。
     */
    public static final class KeyRelation {

        /**
         * 接缝处两侧 chroma 的余弦相似度（**不转调**；{@code NaN} = <b>这一次没有读数</b>，
         * 与"没有来源"一样按<b>不知道</b>处理——见 {@link #keyClashWithCompatibleGrid}）。
         *
         * <p><b>为什么把"没有读数"也写成 NaN 而不是另立一个布尔</b>：这个字段全仓库只有
         * {@link #keyClashWithCompatibleGrid} 一个消费者，而 `float` 的 NaN 天然是"无效值"
         * 的常规表示（{@code AutoCue} 里"量不出来"一路都用负值/NaN，见 {@code outroStartSecs}）。
         * 实测踩过一次坑：早先把"老入口传来的<b>是</b>冲突、只是没带数"也编码成 NaN，
         * 于是"来源算不出来"（NaN）与"调用方说它是冲突"（也是 NaN）分不开——
         * 真曲库探针上当场看出 6 对里混进了 1 对假的（{@code qplayer-demo-B → qplayer-demo-A}：
         * 来源明明答"算不出来"，决策层却给了计划）。现在老入口不再经过这个类型
         * （它直接把措辞交给内部实现），NaN 只剩"没有读数"一个含义。
         */
        public final float plainScore;

        /** 人话（日志用；{@code null} 表示没什么好说的）。 */
        public final String describe;

        public KeyRelation(float plainScore, String describe) {
            this.plainScore = plainScore;
            this.describe = describe;
        }

        @Override
        public String toString() {
            return Float.isNaN(plainScore) ? "调性关系：没有读数"
                    : String.format(java.util.Locale.US, "chroma 相似度 %.3f",
                            Float.valueOf(plainScore));
        }
    }

    /**
     * <b>"A 与 B 的调性距离"的来源</b>（<b>宿主注入</b>）：给两个音源句柄（以及它们各自
     * 需要的请求头——插件直链常常必须带 token/Referer），返回这一对的调性关系；
     * <b>算不出来就返回 {@code null}</b>。
     *
     * <p>与 {@link #installLyricSpanSource} / {@code Decoder.install} 同一个模式：
     * 默认<strong>没有</strong>来源，于是这一条**不参与触发**——「算不出来就不猜」
     * 那条纪律。{@code planCollect} 侧"查到就用、查不到就当不知道"，而"不知道"的
     * 行为与没有这条触发<strong>逐位相同</strong>（判据
     * {@code AutoCueOutroLoopKeyClashTest} 钉着）。
     *
     * <p><b>为什么是"按句柄提供"而不是"在这里读 PCM"</b>：决策层（{@code planCollect} /
     * {@code planOutroLoop}）手上只有两侧的<strong>包络</strong>——包络里只有三条分频带，
     * 没有色度，判不了调性；而判调性（{@link Harmonic}）要的是 PCM。所以这一层只要一个
     * 查询口，PCM 与算法都留在来源那一侧（见 {@link #harmonicKeyRelationSource()}）。
     */
    @FunctionalInterface
    public interface KeyRelationSource {

        /**
         * @param outroSource A 的音源句柄（{@code "outro"} 那一侧）
         * @param introSource B 的音源句柄
         * @return 这一对的调性关系；{@code null} = 不知道（两侧任一侧读不出来、
         *         素材没有调性、或者压根没有调性分析的实现）
         */
        KeyRelation apply(String outroSource, java.util.Map<String, String> outroHeaders,
                          String introSource, java.util.Map<String, String> introHeaders);
    }

    /** {@link #installKeyRelationSource} 注入进来的那一个；默认没有。 */
    private static volatile KeyRelationSource keyRelationSource;

    /**
     * 装上"调性关系"的来源（宿主启动时调一次；传 {@code null} 卸下）。
     *
     * <p>现成的实现见 {@link #harmonicKeyRelationSource()}（用仓库自己的 {@link Harmonic}）。
     * 宿主（例如 {@code PlayerController} 装歌词来源那一处）调一行即可；
     * 不装则这一条触发恒不参与——**这是刻意的**，因为问一次要读两侧各 8 秒 PCM
     * 再跑一遍逐帧 chroma，不该由一个"有没有可循环段都不知道"的时刻替用户决定。
     */
    public static void installKeyRelationSource(KeyRelationSource source) {
        keyRelationSource = source;
    }

    /**
     * 按句柄问一次调性关系；没有来源、参数不全、来源抛异常都算<strong>不知道</strong>。
     *
     * <p>与 {@link #linesOf} 同一条纪律：来源那一侧出问题不许把异常漏进计划路径。
     */
    public static KeyRelation keyRelationOf(String outroSource,
                                            java.util.Map<String, String> outroHeaders,
                                            String introSource,
                                            java.util.Map<String, String> introHeaders) {
        KeyRelationSource src = keyRelationSource;
        if (src == null || outroSource == null || introSource == null) {
            return null;
        }
        try {
            return src.apply(outroSource, outroHeaders, introSource, introHeaders);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * <b>现成的调性来源</b>：用仓库自己的 {@link Harmonic} 判这对曲子的调性
     * （读两侧各 {@link Harmonic#WINDOW_SECS} 秒 PCM → 逐帧 chroma → 余弦）。
     *
     * <h3>代价（先说清，因为它决定了谁该装、什么时候问）</h3>
     *
     * <p>两次小读盘（各 8 秒，且带请求头，见 {@code Automix.readMono}）+ 一次
     * {@code Harmonic.match}（Goertzel 逐帧，内部按帧并行）。与同一次 prepare 里
     * 那两次<strong>整曲/浅扫</strong>包络扫描相比是小头，但绝不是零——所以
     * {@code planCollect} 只在**这一次真的有可循环段**（vamp 非 null）时才去问
     * （见那里的注释），而结果按"这一对"记在下面这个小表里。
     *
     * <h3>有没有现成的结论可以复用</h3>
     *
     * <p><b>没有</b>（这是选"自己算"而不是"接一条现成的线"的原因，如实记在这里）：
     * {@code AiMix.prepare} 确实算过一次 {@code Harmonic.match}（自动档挑接法时），
     * 但它是**局部变量**，算完就丢；{@code AutoCueTransition.base} 的静态类型是
     * {@code Transition}，拿不到它；{@link Harmonic} 自己也没有任何按曲目的缓存
     * （它只缓存 Goertzel 的频点参数表 {@code PLANS}，与"这首歌的调性"无关）。
     * 也就是说：要么在这里重算一遍（多花一次 8 秒读盘 + 一次 chroma），
     * 要么改 {@code Transition} 的接口去把那个局部变量端出来——后者动的是
     * "自动档挑接法"那条已经逐位钉住的路，代价与风险都不成比例。
     */
    public static KeyRelationSource harmonicKeyRelationSource() {
        return new KeyRelationSource() {

            /** 最近几对的结论（同一对会被问不止一次：一次 prepare 问一次、重试也会问到）。 */
            private final java.util.LinkedHashMap<String, KeyRelation> memo =
                    new java.util.LinkedHashMap<String, KeyRelation>(8, 0.75f, true);

            @Override
            public KeyRelation apply(String outroSource, java.util.Map<String, String> outroHeaders,
                                     String introSource, java.util.Map<String, String> introHeaders) {
                String key = outroSource + "\u0000" + introSource;
                synchronized (memo) {
                    KeyRelation hit = memo.get(key);
                    if (hit != null) {
                        // 命中的可能是"知道这一对算不出来"那一格 → 如实交回"不知道"
                        return hit == UNKNOWN ? null : hit;
                    }
                }
                KeyRelation rel = matchOnce(outroSource, outroHeaders, introSource, introHeaders);
                synchronized (memo) {
                    memo.put(key, rel == null ? UNKNOWN : rel);
                    while (memo.size() > KEY_RELATION_MEMO) {
                        java.util.Iterator<String> it = memo.keySet().iterator();
                        it.next();
                        it.remove();
                    }
                }
                return rel;
            }
        };
    }

    /** 见 {@link #harmonicKeyRelationSource()}：一对曲子的调性结论算一次。 */
    private static KeyRelation matchOnce(String outroSource,
                                         java.util.Map<String, String> outroHeaders,
                                         String introSource,
                                         java.util.Map<String, String> introHeaders) {
        try {
            // 与 {@code HarmonicBlend.prepare} 完全同一条取窗：出场曲的**尾段**、进场曲的**首段**
            // （各 Harmonic.WINDOW_SECS 秒）——接缝处的调性才是这一次要判的东西，
            // 拿整曲去判会把中段的转调混进来（见 Harmonic 的类注释）。
            Automix.Mono tail = Automix.readMono(outroSource, outroHeaders, true,
                    Harmonic.WINDOW_SECS);
            Automix.Mono head = Automix.readMono(introSource, introHeaders, false,
                    Harmonic.WINDOW_SECS);
            Harmonic.Match m = Harmonic.match(tail.pcm, tail.rate, head.pcm, head.rate);
            if (m == null) {
                return null;                    // 素材没有可用调性 → 不知道（Harmonic 自己的纪律）
            }
            return new KeyRelation(m.plainScore, String.format(java.util.Locale.US,
                    "chroma 相似度 %.3f（建议移调 %+d 半音）",
                    Float.valueOf(m.plainScore), Integer.valueOf(m.semitones)));
        } catch (RuntimeException e) {
            return null;                        // 打不开/读不出来 → 不知道（不拦）
        }
    }

    /** 见 {@link #harmonicKeyRelationSource()}：缓存表里"知道算不出来"的那一格。 */
    private static final KeyRelation UNKNOWN = new KeyRelation(Float.NaN, null);

    /** 见 {@link #harmonicKeyRelationSource()}：最多记这么多对（够一次会话连续换曲用）。 */
    private static final int KEY_RELATION_MEMO = 8;

    /**
     * <b>「调性冲突但节奏兼容」</b>（用户规格第四条触发）——两半都用仓库既有的尺子：
     *
     * <ul>
     *   <li><b>调性冲突</b>：{@code key.plainScore < }{@link #LOOP_KEY_SAME_SCORE}（0.85）
     *       ——与 {@link Chooser#choose} 判"两边本来就合"同一座门（{@code MixTuning.autoKeySame}）；</li>
     *   <li><b>节奏兼容</b>：两侧都判出了拍点，且速度差 {@code < }{@link #LOOP_BPM_DIFF_MIN}
     *       （3%）。这就是 {@link Chooser#choose} 里"速度对得上"那一支的界
     *       （{@code MixTuning.autoTempoConflict} 默认值同为 0.03，那里用两边的**均值**
     *       作分母，这里按这个包一路的口径用 {@code bpmA} 作分母）。
     *       <b>落在 3%~8% 的那一档不算"节奏兼容"</b>——那是"对齐网格"里 BPM 那一条的事，
     *       两支因此互斥，规格里"或"的关系也就真的成立。</li>
     * </ul>
     *
     * <p>{@code key == null}（没有来源 / 来源说不知道）→ {@code false}：
     * <b>不知道就不拦</b>，行为与没有这条触发逐位相同。
     */
    public static boolean keyClashWithCompatibleGrid(KeyRelation key, Envelope outro,
                                                     Envelope intro) {
        if (key == null || Float.isNaN(key.plainScore)) {
            return false;                       // 不知道（没有来源 / 没有读数）→ 不拦
        }
        if (!(key.plainScore < LOOP_KEY_SAME_SCORE)) {
            return false;                       // 相似度够高：两边本来就合，不是冲突
        }
        if (outro == null || intro == null || outro.tempo == null || intro.tempo == null
                || !(outro.tempo.bpm > 0f) || !(intro.tempo.bpm > 0f)) {
            return false;                       // 哪一侧没判出拍点 → "节奏兼容"无从谈起
        }
        double diff = Math.abs(intro.tempo.bpm - outro.tempo.bpm) / outro.tempo.bpm;
        return diff < LOOP_BPM_DIFF_MIN;
    }

    static double[] linesOf(String source) {
        java.util.function.Function<String, double[]> f = lineSource;
        if (f == null || source == null || source.isEmpty()) {
            return null;
        }
        try {
            double[] lines = f.apply(source);
            return lines == null || lines.length == 0 ? null : lines;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 一次方案打分的七项（都是 0..1，越大越好）。
     *
     * <p>权重集中在 {@link #WEIGHTS}，理由写在字段名旁边——它们是听感参数，
     * 换一个素材就要重新听一遍，所以不许散落在代码里。
     */
    public static final class Score {

        /** 波形重合（归一化互相关的 0..1 映射）。 */
        public final double overlap;
        /** 淡化起伏：等功率淡化的总电平标准差 / 均值（越小越好，这里已取 1−它）。 */
        public final double ripple;
        /** 频段互补：三个频段上「互补」的程度（1 = 完全互补，0 = 全程同一个频段对着轰）。 */
        public final double bands;
        /** 响度接续：窗口平均响度差越小越好（已取 1−差）。 */
        public final double level;
        /** 走向：出场在收、进场在起。 */
        public final double motion;
        /** 拍漂移：窗口内两边拍漂开的程度（1 = 不漂，0 = 漂 4 拍以上）。 */
        public final double beat;
        /** 少切一点：保留的尾巴占比。 */
        public final double tail;

        /**
         * <b>第 9 项：人声重叠</b>（1 = 窗口里两边的人声没有长时间叠唱，0 = 一直在叠）。
         *
         * <p>见 {@link #vocalOverlap}：用 {@code band[1]}（200 Hz–2 kHz，人声共振峰所在）
         * 加**音节速率调制**做代理 VAD，再按「两侧同时有人的时长」给分——
         * 窗口里两边的人声叠在一起唱是最难听的一种（两条人声互相抢、词还撞），
         * 而这一项原先只能靠歌词（{@link #LINE_GAP_WEIGHT}，而且只是加分、没有歌词就没有）。
         */
        public final double vocal;

        /**
         * 窗口里「两边同时有人在唱」的<b>秒数</b>（P6 的硬约束按它判定，note 里也报它）。
         *
         * <p>它与 {@link #vocal} 是同一次扫描的两个产物：分数是这条秒数的斜坡
         * （0.5 s 以内满分、4 s 归零），而硬约束要的是原始秒数——分数会掩盖「到底几秒」。
         */
        public final double vocalSecs;

        /**
         * <b>第 8 项：低频冲突</b>（1 = 从不打架，0 = 全程一起轰）。
         *
         * <p>见 {@link #lowEndClash}：只看「底鼓 + 贝斯」那一条带（{@code band[0]} = &lt; 200 Hz），
         * 由两个成分合成——**同时有低频能量的比例**（糊）与**低频反相程度**（互相抵消）。
         * 频段互补（{@link #bands}）把三个频段平均在一起，低频那一条只占三分之一，
         * 于是「两个底鼓从头对到尾」会被中高频的互补冲淡；这一项专门盯这件事。
         */
        public final double lowEnd;
        /**
         * 第 8 项：歌词节奏（0..1，越大越好）；{@code null} = 这次没有歌词信息，没算这一项。
         *
         * <p>用包装类型而不是「0 表示没有」：0 是一个**真实的坏分**（第一句来得完全不是时候），
         * 和「不知道」不能混为一谈——混了就会把「缺数据」当成「很差」去惩罚候选。
         */
        public final Double lineGap;

        /** 旋律重合度那一项**进总分**时的值（{@code null} = 这一对没量到 chroma，不进）。 */
        public final Double melodyToScore;

        /** 情绪匹配那一项进总分时的值（{@code null} = 同 {@link #melodyToScore}）。 */
        public final Double emotionToScore;

        /**
         * 结构奖励（0..1，越大越好）；{@code null} = 没算这一项（与 {@link #lineGap} 同一口径）。
         *
         * <p>见 {@link #STRUCTURE_WEIGHT}：它加在七项之上、不进分母，上限很小。
         */
        public final Double structure;

        /**
         * 小节线加分（0..1，{@code null} = 这次没算）。
         *
         * <p>与歌词、结构两项<strong>同一个机制</strong>：不参与归一、只往上加，
         * 上限 {@link #BAR_WEIGHT}。它奖励的是「这个点位落在小节线上」——
         * 相位测不出来时（{@link #barPhase} 返回 -1）这里就是 {@code null}，一分不加。
         */
        public final Double bar;

        /**
         * 色度新颖度加分（0..1，{@code null} = 这次没算）。
         *
         * <p>与歌词 / 结构 / 小节线**同一个机制**：不参与归一、只往上加，上限
         * {@link #NOVELTY_WEIGHT}——而且它是全场**最低**的一档：色度分段讲的是和声/音色变化，
         * 精度比能量法差一档（2026-10-01 的实测），所以只让它**轻推**，绝不允许把点位拉走。
         */
        public final Double novelty;

        /** 加权总分（0..1）。 */
        public final double total;

        /**
         * 这一对点位的强度差（0..1）；{@code null} = 这次没算（桥接关着）。
         *
         * <p>见 {@link Score#withIntensity(double)}：它是**旁路信息**，不参与 {@link #total}。
         */
        public final Double intensityGap;

        /**
         * 强度**贴合分**（0..1）；{@code null} = 这一项不进总分（只量不评，见
         * {@link Bridge#measure} 与 {@link Bridge#score} 的分工）。
         */
        final Double intensityToScore;

        final String beatNote;

        final double cutSecs;
        final double entrySecs;
        final double windowSecs;

        Score(double overlap, double ripple, double bands, double level, double motion,
              double beat, double tail, String beatNote,
              double cutSecs, double entrySecs, double windowSecs) {
            this(overlap, ripple, bands, level, motion, beat, tail, 0.5, 0.5, 0.0, beatNote,
                    cutSecs, entrySecs, windowSecs, null, null, null, null);
        }

        /**
         * 带歌词节奏项的那一份。{@code lineGap == null} 时**分母不加、分子不加**，
         * 于是总分与不带这一项的旧算法逐位相同。
         */
        Score(double overlap, double ripple, double bands, double level, double motion,
              double beat, double tail, String beatNote,
              double cutSecs, double entrySecs, double windowSecs, Double lineGap) {
            this(overlap, ripple, bands, level, motion, beat, tail, 0.5, 0.5, 0.0, beatNote,
                    cutSecs, entrySecs, windowSecs, lineGap, null, null, null);
        }

        /** 带歌词与结构两项加分的那一份。两项都可以为 {@code null}（= 不加）。 */
        Score(double overlap, double ripple, double bands, double level, double motion,
              double beat, double tail, double lowEnd, double vocal, double vocalSecs,
              String beatNote,
              double cutSecs, double entrySecs, double windowSecs,
              Double lineGap, Double structure, Double bar, Double novelty) {
            this(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal, vocalSecs,
                    beatNote, cutSecs, entrySecs, windowSecs, lineGap, structure, bar, novelty,
                    null);
        }

        Score(double overlap, double ripple, double bands, double level, double motion,
              double beat, double tail, double lowEnd, double vocal, double vocalSecs,
              String beatNote,
              double cutSecs, double entrySecs, double windowSecs,
              Double lineGap, Double structure, Double bar, Double novelty,
              Double intensityGap) {
            this(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal, vocalSecs,
                    beatNote, cutSecs, entrySecs, windowSecs, lineGap, structure, bar, novelty,
                    intensityGap,
                    intensityGap == null ? null
                            : Double.valueOf(intensityTerm(intensityGap.doubleValue())),
                    null, null);
        }

        /**
         * 最完整的一份：{@code intensityToScore == null} = 这一项**不进总分**（只是报出来），
         * 于是 {@link Bridge#MEASURE}（只量不评）与 {@link Bridge#OFF} 的选点逐位相同。
         */
        Score(double overlap, double ripple, double bands, double level, double motion,
              double beat, double tail, double lowEnd, double vocal, double vocalSecs,
              String beatNote,
              double cutSecs, double entrySecs, double windowSecs,
              Double lineGap, Double structure, Double bar, Double novelty,
              Double intensityGap, Double intensityToScore,
              Double melodyToScore, Double emotionToScore) {
            this.melodyToScore = melodyToScore;
            // 情绪匹配**由已有的三项现算**（劲头 intensityTerm + 频段互补 bands + 低频冲突 lowEnd），
            // 所以调用方一般不必传它：量到了强度差就有情绪分。用户原话「情绪是在旋律相似后面的，
            // 旋律高度相似一般情况下情绪也不会差到哪去」——它不引入新的观测量。
            this.emotionToScore = emotionToScore != null
                    ? emotionToScore
                    : (intensityToScore == null ? null
                            : emotionMatch(intensityToScore, bands, lowEnd));
            this.lineGap = lineGap;
            this.structure = structure;
            this.bar = bar;
            this.novelty = novelty;
            this.intensityGap = intensityGap;
            this.intensityToScore = intensityToScore;
            this.overlap = overlap;
            this.ripple = ripple;
            this.bands = bands;
            this.level = level;
            this.motion = motion;
            this.beat = beat;
            this.tail = tail;
            this.lowEnd = lowEnd;
            this.vocal = vocal;
            this.vocalSecs = vocalSecs;
            this.beatNote = beatNote;
            this.cutSecs = cutSecs;
            this.entrySecs = entrySecs;
            this.windowSecs = windowSecs;
            double sum = WEIGHTS[0] * overlap + WEIGHTS[1] * ripple + WEIGHTS[2] * bands
                    + WEIGHTS[3] * level + WEIGHTS[4] * motion + WEIGHTS[5] * beat
                    + WEIGHTS[6] * tail + WEIGHTS[7] * lowEnd + WEIGHTS[8] * vocal;
            // 第 10 项：**接缝强度贴合**（用户 2026-10-04：「这个应该成为自动点位选点打分的
            // 一个参数」）。它按九项同一套归一方式进分母——不是"加分项"（那样盖不过九项），
            // 也不是"否决"（那样只在极端出手，实测 6 对 0 触发）。
            //
            // intensityGap == null 时**分子分母都不加**，于是关掉桥接就是逐位回到今天
            // （与 lineGap / structure 那两项"只在有值时进"的纪律一致）。
            // ── 旋律 / 情绪进**分子与分母**（2026-10-06 第一阶段工作一）─────────────
            //
            // 用户原话：「应当旋律重合度最优先，其次是情绪，然后才是剩下的。**旋律永远第一位**。」
            // 所以这不是"又一个加分项"（那样盖不过九项），而是**一起参与归一化**的压倒性权重：
            // MELODY_WEIGHT = 8.0 比九项之和 7.75 还大，EMOTION_WEIGHT = 3.0 排第二。
            // 两项都量不到时（手搭包络没有 chroma）分子分母都不加 ⇒ 与加这一项之前**逐位相同**。
            // 九项（+强度）的归一化**与加旋律之前逐位相同**。
            double base;
            if (intensityToScore == null) {
                base = sum / WEIGHT_SUM;
            } else {
                base = (sum + INTENSITY_WEIGHT * intensityToScore.doubleValue())
                        / (WEIGHT_SUM + INTENSITY_WEIGHT);
            }
            // ── 旋律 / 情绪：**在自己的尺度上相加**，不并进九项的分母 ──────────────
            //
            // 用户原话：「应当旋律重合度最优先，其次是情绪，然后才是剩下的。**旋律永远第一位**。」
            // 权重按锁死的 8.0 / 3.0（分别比九项之和 7.75 还大、排第二），所以一项旋律
            // （0..1）能给出最多 8/(7.75+8+3) ≈ 0.51 的分量——**远大于九项任何一项的最大摆动**
            // （最大的一项 1.6/7.75 ≈ 0.21），"极度相似的旋律片段"必然压倒一切。
            //
            // **为什么不并进同一个分母**（实测踩到，记在这里）：一进分母就把九项整体稀释
            // 2.4 倍，而"加在归一化之外"的那几个加分项（歌词/结构/小节/新颖）没被稀释 ⇒
            // 它们的相对强度翻倍，参考对的结构锚点（进场起点 + 2.0 s = 18.32）立刻赢了，
            // 进场点从 Apple 的 15.0 被拉到 **17.4753**（差 +2.48 s，出带）。
            // 这样写：旋律照样压倒，其余各项的比例**一个数都没动**。
            if (melodyToScore != null) {
                base += MELODY_WEIGHT * melodyToScore.doubleValue()
                        / (WEIGHT_SUM + MELODY_WEIGHT + EMOTION_WEIGHT);
            }
            if (emotionToScore != null) {
                base += EMOTION_WEIGHT * emotionToScore.doubleValue()
                        / (WEIGHT_SUM + MELODY_WEIGHT + EMOTION_WEIGHT);
            }
            // **歌词节奏是加分项**（用户 2026-09-29 改的）：不参与归一，只往上加，最高
            // {@link #LINE_GAP_WEIGHT}。理由：它是"锦上添花"——一句歌词来得是不是时候，
            // 不该能像七项那样**把一个候选压下去**（原先它并进分母，一个烂的歌词节奏能把
            // 波形其实很合的候选拉到基准之下）。它是全场优先级最低的一项。
            // 结构奖励同理：不参与归一、只往上加，上限 {@link #STRUCTURE_WEIGHT}。
            if (lineGap != null) {
                base += LINE_GAP_WEIGHT * lineGap.doubleValue();
            }
            if (structure != null) {
                base += STRUCTURE_WEIGHT * structure.doubleValue();
            }
            if (bar != null) {
                base += BAR_WEIGHT * bar.doubleValue();
            }
            if (novelty != null) {
                base += NOVELTY_WEIGHT * novelty.doubleValue();
            }
            this.total = base;
        }

        /**
         * 换一个歌词节奏分（{@code null} = 不加这一项，原样返回 {@code this}）。
         *
         * <p>**另一项（结构）原样保留**：两个 {@code with*} 谁先谁后都不该把对方抹掉。
         */
        Score withLineGap(Double term) {
            if (term == null) {
                return this;
            }
            return new Score(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal, vocalSecs,
                    beatNote, cutSecs, entrySecs, windowSecs, term, structure, bar, novelty);
        }

        /** 换一个色度新颖度加分（{@code null} = 不加，原样返回 {@code this}）。 */
        /**
         * <b>带上旋律重合度与情绪匹配</b>（2026-10-06 第一阶段工作一）。
         *
         * <p>两项都是 {@code null} 时原样返回 {@code this}（与加这一项之前逐位相同）。
         * 两项**一起进分子与分母**——见最全那个构造里的注释：旋律是**压倒性**权重。
         */
        Score withMelody(Double melody, Double emotion) {
            if (melody == null && emotion == null) {
                return this;
            }
            return new Score(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal, vocalSecs,
                    beatNote, cutSecs, entrySecs, windowSecs, lineGap, structure, bar, novelty,
                    intensityGap, intensityToScore, melody, emotion);
        }

        Score withNovelty(Double term) {
            if (term == null) {
                return this;
            }
            return new Score(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal, vocalSecs,
                    beatNote, cutSecs, entrySecs, windowSecs, lineGap, structure, bar, term);
        }

        /** 换一个小节线加分（{@code null} = 不加，原样返回 {@code this}）。 */
        Score withBar(Double term) {
            if (term == null) {
                return this;
            }
            return new Score(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal, vocalSecs,
                    beatNote, cutSecs, entrySecs, windowSecs, lineGap, structure, term, novelty);
        }

        /** 换一个结构奖励分（{@code null} = 不加这一项，原样返回 {@code this}）。 */
        /**
         * 带上这一对点位的强度差（见 {@link #intensityGap}）。
         *
         * <p>它**不进总分**——闸门只回答「行不行」，不参与「哪对更接近」的排序。
         * 之所以还挂在 Score 上：决定用哪个候选之后，要把它如实报进 {@link Cue}。
         */
        Score withIntensity(Double gap, boolean scored) {
            return new Score(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal,
                    vocalSecs, beatNote, cutSecs, entrySecs, windowSecs, lineGap, structure,
                    bar, novelty, gap,
                    scored && gap != null ? Double.valueOf(intensityTerm(gap.doubleValue())) : null,
                    // **必须把已经设好的两项带过去**：这个 with* 在链尾被调用（强度差是最后
                    // 才算出来的），传 null 会把前面刚设上的旋律/情绪**抹掉**——实测踩到：
                    // 桥接关着时旋律生效（进场 16.4749）、开着时被抹掉（14.9744），
                    // 同一份代码两条路给出不同落点。
                    melodyToScore, emotionToScore);
        }

        Score withStructure(Double term) {
            if (term == null) {
                return this;
            }
            return new Score(overlap, ripple, bands, level, motion, beat, tail, lowEnd, vocal, vocalSecs,
                    beatNote, cutSecs, entrySecs, windowSecs, lineGap, term, bar, novelty);
        }
    }

    /**
     * 七项权重（与 {@link Score} 的字段顺序一一对应）。
     *
     * <p>排序理由（用户能听出来的程度）：<strong>波形重合最重</strong>——「叠上去是加强还是打架」
     * 是这件事的本体；其次是<strong>淡化起伏</strong>（凹陷/凸起是接缝最直接的可听证据）；
     * 响度接续与走向是原来那套判据里最有效的两项，降到 1.0 / 0.8；
     * 频段互补给 0.8（它只在两边频谱分布明显不同时才起作用）；
     * <b>拍漂移权重 2026-10-01 改成 0</b>（读数仍然打印在 note 里，只是不参与打分）。
     * 理由：这一项算的是「两边速度差在窗口里会漂开多少拍」——**它默认播放不伸缩**。
     * 而本函数里「窗口可以动」这件事只有 {@code AiMix}（AI 方案比较）与
     * 声波重合那档会传（{@code windowCandidates(nominal, tune=true)}），那两档引擎
     * **是会伸缩的**（B 跟着 A 的速度走），漂移根本不发生；朴素交叉淡化传的是用户的
     * 单个窗口（{@code tune=false}），这一项也就无从影响结论。实测代价很具体：
     * 它对 4~8 秒的短窗给 0.87~0.93、对 12 秒给 0.60，于是把窗口从 12 s 推到 4 s，
     * 出场点跟着从 <b>174.4</b>（参考曲 Apple 的点）挪到 182.3。
     * 「少切一点」仍旧最小（0.35）——不该为了一点接缝改善就把半首歌切掉。
     */
    static final double[] WEIGHTS = {1.6, 1.2, 0.8, 1.0, 0.8, 0.5, 0.35, 0.9, 0.6};

    static final double WEIGHT_SUM = 7.75;

    /**
     * <b>旋律重合度的权重</b>（2026-10-06 第一阶段工作一，用户要求写进 automix 宪法）。
     *
     * <p>原判据是「旋律永远第一位」⇒ 取 8.0（比九项之和 7.75 还大，压倒性）。
     *
     * <p>**2026-11-16 用户改判**：「这个自动点位你把旋律重合度的权重降低吧，
     * 比其他的略高一点就行」——原话里那一档"压倒性"会让旋律一项单独决定落点，
     * 而旋律读数本身只有 ±0.05 的分辨力（见 {@link #MELODY_STEP}），等于让噪声当裁判 ✗。
     * ⇒ 降到 {@value}：在单项里仍然最高（{@link #WEIGHTS} 里最大是 1.6），
     * 但不再能盖过"情绪 + 频段 + 响度 + 拍漂移"这一组真正决定听感是否丝滑的项 ✓
     */
    static final double MELODY_WEIGHT = 2.0;

    /**
     * 情绪匹配的权重（次于旋律，仍高于九项里的任何单项）。
     *
     * <p>2026-11-16 随旋律一起下调（原来 3.0 会反过来压过旋律 ✗，与"旋律略高于其他"矛盾）。
     */
    static final double EMOTION_WEIGHT = 1.5;

    /**
     * <b>情绪匹配</b>（0..1）：接缝的"劲头"（{@code intensityTerm}）、频段互补（{@code bands}）
     * 与低频冲突（{@code lowEnd}）三者的平均。
     *
     * <p>用户原话：「情绪是在旋律相似后面的，**旋律高度相似一般情况下情绪也不会差到哪去**。」
     * 所以它排第二，而且只是这三项的合成，不引入新的观测量。
     */
    static Double emotionMatch(Double intensityTerm, double bands, double lowEnd) {
        if (intensityTerm == null) {
            return null;
        }
        return Double.valueOf((intensityTerm.doubleValue() + bands + lowEnd) / 3.0);
    }

    /**
     * 「拍漂移」这一项**不罚**的漂移量（拍）：窗口里漂到这么多拍以内算正常。
     *
     * <p>为什么加这条死区（2026-10-01 实测）：原来是 {@code 1 − min(1, 漂移/4)}——对 4~8 秒的
     * 短窗给 0.87~0.93、对 12 秒给 0.60，于是它把窗口从 12 s 推到 4 s，出场点跟着从
     * <b>174.4</b>（参考曲 Apple 的点，落在拍上）挪到 <b>182.3</b>。
     *
     * <p>而这一项算的是「两边速度差在窗口里会漂开多少拍」——<b>它默认播放不伸缩</b>。可在本函数里
     * 「窗口可以动」这件事只有 AI 方案比较与声波重合那两档会传（{@code tune=true}），
     * 而那两档引擎**是会伸缩的**（B 跟着 A 的速度走），一两拍的漂移在实际播放里并不存在；
     * 朴素交叉淡化传的是用户的单个窗口（{@code tune=false}），这一项也影响不到结论。
     * 真正要避免的是「长窗口里漂到三四拍以上」——那才是对不上的那种。
     */
    static final double DRIFT_FREE_BEATS = 0.25;

    /** 死区之后线性罚到 0 的额外漂移量（拍）：{@code 1.5 + 3 = 4.5} 拍时归零。 */
    static final double DRIFT_FALLOFF_BEATS = 1.25;

    /**
     * 「歌词节奏」的**加分上限**（用户 2026-09-29 改：从"参与归一的一项"改成"加分项"）。
     *
     * <p>它衡量的是「跳过去之后第一句歌词来得是不是时候」：A 最后几句的间距是这首歌的呼吸，
     * 进场曲第一句来得太快会像抢拍、太慢会像空档。
     *
     * <p><b>加分项</b>（不参与归一：只往上加，{@code total = 九项 + 这个量 × 歌词节奏}），
     * 所以再差的歌词节奏也不会把候选压到"没有歌词"之下——那是九项才有的权力。
     *
     * <p><b>量级 0.10 的来历</b>（用户 2026-10-04 两句话：
     * <b>「尽量预判一下入场时是否刚好卡在念歌词的时候……权重加大一丢丢」</b>，
     * 随后<b>「权重从 0.07 加到 0.1」</b>）。三次的处境不一样，记下来：
     *
     * <ul>
     *   <li><b>0.05（原值）</b>：低于九项里最小那项（「少切一点」0.35/7.75 ≈ 0.045）
     *       的归一上限——**只在别的完全一样时**才决定选谁；</li>
     *   <li><b>0.07（第一次加大）</b>：相当于「拍漂移」(0.5/7.75 ≈ 0.065) 那一档，
     *       能决定那种差 0.01~0.02 的平局（实测参考对上头部候选的差距正是这个量级：
     *       「次优 102.3s/30.3s，差在『响度接续』0.61 vs 0.61」）；</li>
     *   <li><b>0.10（用户指定的终值）</b>：与「频段互补」(0.8/7.75 ≈ 0.103) **同一量级**
     *       ——它现在是一个**正式的打分项**，不再是"锦上添花"。</li>
     * </ul>
     *
     * <p>上界仍然守着，而且这是它唯一不能越的地方：**必须严格小于「响度接续」
     * (1.0/7.75 ≈ 0.129)**——那是保证"两边响度接得上"的那一项，也是
     * {@code AutoCueTest#neverCutsAtTheVeryEndAndKeepsTheTwoSidesLevelMatched} 盯着的性质。
     * 换句话说：歌词节奏现在能左右同量级的取舍，但不能推翻"接缝本身难听"。
     * 判据 {@code AutoCueLineGapTest#theLyricTermOnlyEverAddsToTheScore} 钉的就是这条上界。
     *
     * <p><b>没有歌词信息时这一项不加</b>，于是「没有歌词」那条路径与加这一项之前**逐位相同**。
     */
    static final double LINE_GAP_WEIGHT = 0.10;

    /**
     * 「入场时是不是刚好卡在念歌词」的判据：落在某一句的**槽位后半段**就算卡住了。
     *
     * <p>用户 2026-10-04：「尽量预判一下入场时是否刚好卡在念歌词的时候」。
     *
     * <p><b>判定点是融合的中点</b>（见 {@code lineGapTerm} 里那段注释）：B 的增益要铺满整个
     * 窗口才到满，等功率交叉的中点才是"听感上归 B 了"的那一刻。所以这一项量的是
     * 「中点是不是句首」，而不是「进场点是不是句首」——用户 2026-10-04 指出的正是这一点
     * （「融合也要时间……最后的效果就是 B 听感还是从歌词中间出来的」）。
     *
     * <p>怎么做：宿主只给**行起始时间**（{@link #installLineSource} 的契约），没有行结束时间，
     * 所以用槽位近似——一句歌词从它的起点开始唱，唱到下一句起点之前；**越靠后越像
     * "念到一半"**，于是乘数从 1.0 一路滑到 {@link #LINE_MID_PENALTY}（见
     * {@link #LINE_MID_STRENGTH} 里为什么不用阈值一刀切）。正好落在起点上
     * （{@code into ≈ 0}）不罚：那是最理想的落点，一句话刚好从融合期开始。
     *
     * <p>为什么值得单独罚：原来的 {@code |gB − gA|} 只比**节奏**——它甚至可能**奖励**
     * "卡在念到一半"（下一句来得快、恰好和 A 的呼吸一致）。而听感上那是两件事：
     * 节奏接得上但切断了一句正在唱的人声，比节奏差半拍更刺耳。
     *
     * <p>已知的粗糙处（如实记）：没有真实的行结束时间，于是「一句歌词唱多久」是用
     * 槽位近似出来的。宿主侧其实有 {@code LyricLine.endMs()}，将来把它一起注入会更准。
     */
    static final double LINE_MID_FRACTION = 0.25;

    /**
     * 「念到一半」罚多重：{@value}（越接近下一句，乘数一路降到 {@code 1 − 0.75 = 0.25}）。
     *
     * <p><b>为什么是渐变而不是某个阈值上一刀切</b>：第一版写的是「超过槽位 50% 就乘 0.25」，
     * 结果实测（计划层夹具）挑中的落点恰好在 **47%**——刚好在线内，罚则不触发，
     * 于是歌词那一项对结论毫无影响。而"已经唱到 47%"听感上早就不是句首了。
     * 渐变没有这种悬崖：句首乘 1.0、槽位正中乘约 0.62、快到下一句乘 0.25，
     * 既不会在阈值附近抖，也更贴近"越晚越差"这件事。
     *
     * <p>底 0.25 比"交叉期夹进一句"那个 ×0.5 重：**切断一句正在唱的人声**比
     * 两句撞在交叉期里更刺耳。
     */
    static final double LINE_MID_STRENGTH = 0.75;

    /**
     * 「句首宽限」：槽位过了 {@value} 以内算**还是句首**，不罚。
     *
     * <p>为什么需要宽限带：渐变罚则如果从 0 就开始滑，那么**只有正好落在行起点上**才拿得到
     * 满分——而"落在起点上"在 20 ms 的候选栅格上本来就是稀有的，结果整项对绝大多数候选
     * 都在打折扣，等于把节奏那一项（原设计的主信号）整体压低。宽限 25% 的含义是
     * "一行歌词刚起头的那一小段"（4 秒一句时 ≈ 1 秒），落在这里就是理想的落点。
     */
    static final double LINE_MID_GRACE = 0.25;

    /**
     * <b>「排开歌词」的门槛（用户 2026-10-04：「我要求排开歌词作为最高优先级」）</b>。
     *
     * <p>驼峰值超过它就算"卡在念歌词"，**直接否决**——不是加分项。理由是他实测的那句话：
     * 「你这个现在就是卡着歌词进来的」。之前它只是一个加分项（权重 0.10），
     * 而九项之间的差常常大于它，所以它压不住（日志里 B 从 136.2 s 进场正是这样）。
     *
     * <p>取 {@value}（驼峰 0.5 ↔ 唱到 15%~85% 之间都算"卡住"）：
     * 两端各留 15% 的余量给"刚起头 / 刚唱完"——那两种才是好落点。
     */
    static final double LYRIC_MID_HUMP_MAX = 0.5;

    /**
     * <b>「卡在念歌词」的判定带（按用户的耳朵标定）</b>：槽位已过 {@value} 到
     * {@link #LINE_MID_TO} 之间才算卡住。
     *
     * <h3>三个数据点怎么把它标出来的（2026-10-04，用真实歌词）</h3>
     *
     * <p>用户给了一对实测：Sparks Fly → 炼爱，Apple 是「A 剩 25 s 开始、B 从 45 s 进」，
     * 并明确「45 s 是最舒服的」。我把炼爱的歌词（netease 1406043587）真拉下来比：
     *
     * <pre>
     *   行起始： 43.246 → 51.265 → 59.415 → 67.592 → 70.544 → 74.305 …
     *
     *   落点      槽位已过   驼峰   用户的判词
     *   45.0 s    21.9%     0.68   「最舒服」→ 必须放过
     *   52.2 s    11.5%     0.41   我们的结构锚点 → 放过
     *   72.0 s    38.7%     0.95   「飙到 72 去了」→ 必须否掉
     * </pre>
     *
     * <p>第一版按"驼峰 &gt; 0.5 就否决"写，结果**把 45 s 也否了**（0.68 &gt; 0.5）——
     * 用户的耳朵说"句首起头后一两秒"是舒服的，只有**唱到中间**才难受。
     * 带子取 [0.30, 0.70]：两端各留 30%（4 秒一句时 ≈ 1.2 秒）给"刚起头/快唱完"。
     */
    static final double LINE_MID_FROM = 0.30;

    /** 见 {@link #LINE_MID_FROM}。 */
    static final double LINE_MID_TO = 0.70;

    /**
     * 融合中点落在某一句的什么位置 → 驼峰值（0 = 两端/不在任何一句里，1 = 正中间）。
     *
     * <p>只有拿到**真实行结束时间**才算得出来；算不出来返回 0（= 不拦，诚实降级）。
     */
    public static double midLineHump(LyricSpans spans, double heardSecs) {
        double sung = sungFractionAt(spans, heardSecs);
        return Double.isNaN(sung) ? 0.0 : 4.0 * sung * (1.0 - sung);
    }

    /**
     * 融合中点这一刻，"这一句唱到哪儿了"（0 = 刚起头、1 = 唱完、NaN = 判不出来）。
     *
     * <p><b>没有真实结束时间时退回槽位近似</b>（下一句起点 = 这句的结束）——
     * 这一条是**真的踩过**的：宿主拿到的歌词是 LRC（只有行起始时间），
     * `LyricLine.endMs()` 于是等于 `startMs()`，而第一版的写法是"ends 数组长度对得上就用它"，
     * 结果 `end <= start` 直接返回 0 → **整道门在用户手机上完全是哑的**，
     * 于是点位照样落在歌词正中间（他那句「你这个现在就是卡着歌词进来的」就是这么来的）。
     */
    public static double sungFractionAt(LyricSpans spans, double heardSecs) {
        if (spans == null || spans.starts.length == 0) {
            return Double.NaN;
        }
        int idx = -1;
        for (int i = 0; i < spans.starts.length; i++) {
            if (spans.starts[i] <= heardSecs) {
                idx = i;
            } else {
                break;
            }
        }
        if (idx < 0) {
            return Double.NaN;                // 第一句之前：前奏里，谈不上"念到一半"
        }
        double start = spans.starts[idx];
        // 真实结束时间优先；没有（或不可信）就用下一句起点当槽位末尾
        double end = (spans.ends != null && spans.ends.length == spans.starts.length
                && spans.ends[idx] > start) ? spans.ends[idx]
                : (idx + 1 < spans.starts.length ? spans.starts[idx + 1] : Double.NaN);
        if (Double.isNaN(end) || !(end > start)) {
            return Double.NaN;                // 最后一句、又没有结束时间：不猜
        }
        if (heardSecs >= end) {
            return 1.0;                       // 这句已经唱完：落在两句之间
        }
        return Math.min(1.0, Math.max(0.0, (heardSecs - start) / (end - start)));
    }

    /**
     * <b>「两句人声不许叠在一起」</b>用的估计：一句歌词至少唱这么久（秒）。
     *
     * <p>用户 2026-10-04 选的方向：「(a) 别让人声叠在一起（A 的句子唱完 → B 的句子才起头）」。
     *
     * <p>为什么需要这个估计：两边从宿主拿到的都是 LRC（**只有行起始、没有行结束**，
     * 词级的 TTML 这两首都没有），要判"唱完没有"只能估一句的时长。取 {@value}（2.5 秒）：
     * 中文流行一句 8~12 个字大约就是这个量级，偏保守（宁可多留一点空档，
     * 也不要让两边的人声真的撞上）。
     */
    static final double VOCAL_LINE_MIN_SECS = 2.5;

    /**
     * <b>「进场点不许落在歌词范围内」筛候选时，合法位置至少要留多少个格子</b>
     * （{@value} 个 20 ms 格子 ≈ 0.5 秒）。
     *
     * <p>为什么要有个下限：这条规则是**筛候选**（见 {@code planCollect} 里那段），
     * 而"整段都在唱"的歌（说唱、连唱副歌）合法位置可能是 0 个——硬筛会让它一个候选都不剩。
     * 那时宁可退回今天的做法（不筛）并在说明里写明规则没能行使，也不能凭空造点位。
     *
     * <p>0.5 秒这个量级是**照实测定的**：用户那对素材（Sparks Fly → 炼爱）里，
     * B 一句唱 6.6~7.6 秒、句间换气 0.03~1.11 秒，进场范围 [44.1, 53.1] 里的合法位置
     * 只有那段 1.09 秒的换气（约 51 个格子）。下限再高就会把这种正常素材也挡在门外。
     */
    static final int MIN_LYRIC_GAP_CELLS = 25;

    /**
     * <b>B 的第一句歌词「句头」出现时，B 的音量至少要推到多大</b>（用户 2026-10-04 第二次报）。
     *
     * <p>用户的原话：「AI 自动模式 + 自动点位 + DSP 去人声，最终入场点为 51.2 s，卡在歌词中央；
     * 滑过来的时候都唱了几个字了」。
     *
     * <p>把这句话量化出来（融合是等功率的 {@code Mixer.equalPowerGains = sin(πt/2)}）：
     * B 的下一句句头在 51.460，进场点 51.2 → 句头只比进场晚 0.26 s，窗口 5 s 时句头那一刻
     * B 的增益只有 <b>sin(π/2 × 0.052) = 0.08</b>——**句头是以 8% 的音量冒出来的**，
     * 被 A 压得根本听不见；等 B 真的听得清（融合中点 53.7 s），这句话已经唱了 2.24 s
     * （"火线正蔓"四个字）。用户听到的就是这 2.24 s。
     *
     * <p>实测生产允许的**每一个**窗口都栽在这上面（设定值 6 s 的 0.5/0.75/1/1.5/2 倍）：
     * 句头增益 0.11 / 0.07 / 0.06 / 0.20 / 0.15。要让它到 0.5 以上，唯一的办法是
     * **进场点落在换气的最前头 + 窗口短**（Δ = 换气长度 1.09 s、窗口 3 s → sin(32.7°) = 0.54）。
     *
     * <p>所以这一条同时管两件事：**进场点要贴着换气的开头**，**窗口不许长到把句头压没**。
     * 这也正是"结构锚点"和歌词规则打架的地方——锚点想把进场点往晚处推（中点对准 B 的"真正开始"
     * 54.1 s），而它推得越晚，句头增益越低。用户说了歌词是最高优先级，所以锚点让位。
     */
    static final double ENTRY_HEAD_GAIN_MIN = 0.5;

    /** 「句头听得清」那道筛之后至少留几个格子（20 ms 一格）。见 {@code ENTRY_HEAD_GAIN_MIN}。 */
    static final int MIN_LYRIC_HEAD_CELLS = 4;

    /**
     * <b>「融合结束」那一刻允许离 B 的句头差多少秒</b>（用户 2026-10-05 的规则）。
     *
     * <p>用户原话：「不是你直接入场点前移不就行 我们不是会考虑融合窗口吗 让他**刚好滑完**
     * 就是那个点不就行」。
     *
     * <p>这句把判定点彻底改对了：要对的**不是进场那一刻**，而是**融合结束、B 到满音量**
     * 那一刻（{@code entry + window}）。所以进场点要**前移 w 秒**，落在前一句的尾巴里——
     * 那时 B 的增益还很小（等功率），听感上是"慢慢推上来"，而句头一到就已经是满音量。
     */
    static final double ENTRY_HEAD_TOL_SECS = 0.35;

    /**
     * <b>「融合结束」踩住的那个句头，还得是"这一段的起头"</b>（用户 2026-10-05 第三次报：
     * 「B 怎么又成 54.4s 了」）。
     *
     * <p>为什么光对齐句头不够：实测同一次分析里有两个句头都能被对齐——
     * <pre>
     *   48.4 + 5.0 = 51.4 ≈ 句头 51.460（副歌**第一句**，前 1 秒电平 10%、之后冲到 100%）
     *   54.4 + 5.0 = 59.4 ≈ 句头 59.710（副歌**第三句**，副歌早就开始了）
     * </pre>
     * 两者都"滑完正好踩在句头上"，但前者是"进在副歌起头"，后者是"进在副歌中段"——
     * 听感完全是两回事。用户说的"短暂安静了一下然后开始高潮"正是**前者**。
     *
     * <p>所以加一条：句头之后 2 秒的电平必须显著高于句头之前 1 秒（{@value} 倍）。
     * 51.460 处是 10%→100%（3 倍以上）✓；59.710 处前后都是满的（约 1 倍）✗。
     */
    static final double ENTRY_HEAD_RISE_RATIO = 1.5;

    /** 融合期里夹进了新句头时，歌词那一项罚到几倍（见 {@link #lineGapTerm}）。 */
    static final double DIRTY_FADE_PENALTY = 0.4;

    /**
     * 结构锚点的**前置量**（秒）：锚点对准的是「真正开始 + 这个量」，不是"真正开始"本身。
     * 见 {@code planCollect} 里锚点那一段与 {@code AppleCueReferenceTest} 的目标（181 / 14~15）。
     */
    static final double INTRO_ANCHOR_LEAD_SECS = 2.0;

    /**
     * 算"进场曲真正开始"时，参考电平只取开头的这么多秒（见 {@code introStartSecs}）。
     * 固定下来是为了让**浅扫与深扫给出同一个"真正开始"**——否则锚点会跟着扫描深度漂。
     */
    static final double INTRO_REFERENCE_SECS = 60.0;

    /** 锚点的"半个窗口"偏移最多算到这么长的窗口（秒）；更长的窗口不再继续拖早进场点。 */
    static final double ENTRY_ANCHOR_MID_CAP_SECS = 5.0;

    /** 造"人声乐句"时，多短的静默不算断句（秒）。 */
    static final double VOCAL_HOLE_MERGE_SECS = 0.18;

    /** 造"人声乐句"时，短于这个时长的段不算一句（秒）：避免咬字、气口被当成句子。 */
    static final double VOCAL_PHRASE_MIN_SECS = 0.45;

    /** 量 gA 时，多长的段才算"像样的乐句"（秒）。见 {@link #phraseGapOf}。 */
    static final double VOCAL_PHRASE_STRONG_SECS = 1.5;

    /**
     * 融合期里<b>两边的人声有没有叠在一起</b>（真实时间轴）。
     *
     * <p>真实时间怎么对齐：融合期第 τ 秒时，A 放的是它的 {@code cut+τ}、B 放的是
     * {@code entry+τ}——两边同时响，所以「A 的某一句」与「B 的某一句」在真实时间上的
     * 起点分别是 {@code s_A − cut} 与 {@code s_B − entry}。两者相差不到
     * {@link #VOCAL_LINE_MIN_SECS} 就算**叠上了**。
     *
     * <p>只算**融合期听得到**的那些句子：起点落在 {@code [cut, cut+window]}（A 侧）
     * 与 {@code [entry, entry+window]}（B 侧），再加上各自"交接那一刻已经在唱"的那一句。
     * 两侧任一侧没有歌词就判为不叠（**不知道就不拦**，与这个包一贯的纪律一致）。
     */
    static boolean vocalOverlap(LyricSpans outroSpans, double cutSecs, double windowSecs,
                                LyricSpans introSpans, double entrySecs) {
        if (outroSpans == null || introSpans == null
                || outroSpans.starts.length == 0 || introSpans.starts.length == 0) {
            return false;
        }
        for (double sa : audibleStarts(outroSpans, cutSecs, windowSecs)) {
            for (double sb : audibleStarts(introSpans, entrySecs, windowSecs)) {
                if (Math.abs((sa - cutSecs) - (sb - entrySecs)) < VOCAL_LINE_MIN_SECS) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 融合期听得到的行起始（秒，升序）：{@code [from, from+window]} 之内的每一句，
     * 再加上**交接那一刻正在唱**的那一句（交接前最近的一个起点）。
     *
     * <p>为什么要把"正在唱的那一句"也算进来：它才是最容易和对面撞上的那一句——
     * 交接时 B 若是从某句中间进来，那一句会一直唱到融合期里。
     */
    private static double[] audibleStarts(LyricSpans spans, double from, double windowSecs) {
        double before = Double.NaN;
        double[] hit = new double[spans.starts.length];
        int n = 0;
        for (int i = 0; i < spans.starts.length; i++) {
            double t = spans.starts[i];
            if (t < from) {
                before = t;                       // 交接前最近的一句（升序遍历，后面的会覆盖）
                continue;
            }
            if (t > from + windowSecs) {
                break;
            }
            hit[n++] = t;
        }
        if (!Double.isNaN(before)) {
            double[] out = new double[n + 1];
            out[0] = before;
            System.arraycopy(hit, 0, out, 1, n);
            return out;
        }
        return java.util.Arrays.copyOf(hit, n);
    }

    /**
     * <b>乐句栅格误差</b>（秒）：B 的第一句起头，离"A 本该唱下一句"的时刻差多远。
     *
     * <p>用户 2026-10-04 的原话把这一项的本意讲清楚了：
     * 「A 的句子唱完后等多久 B 的句子才起头。这个间距要符合 A 最后几句的平均间隔」，
     * 并补了一句「请不要忘记重叠时间的影响」。
     *
     * <p>时间轴统一到真实时间（交接那一刻为 0）：
     * <pre>
     *   A 的最后一句起头      aLastReal = s_A_last − cut
     *   A 的声音消失          endA      = min(aLastReal + 一句时长, **融合时长**)
     *   B 的第一句起头        bFirstReal = s_B_first − entry
     *   理想：                bFirstReal − endA ≈ A 的平均间隔 gA
     * </pre>
     *
     * <p>「融合时长」那一项就是用户提醒的那个影响：A 的最后一句若被融合截断，
     * 人声是在**融合结束**那一刻才消失的，间距要从那里算起。
     *
     * @return 误差（秒）；量不出来时返回 {@link Double#NaN}
     */
    public static double phraseGridError(LyricSpans outroSpans, double cutSecs, double windowSecs,
                                         LyricSpans introSpans, double entrySecs) {
        if (outroSpans == null || introSpans == null
                || outroSpans.starts.length < 2 || introSpans.starts.length == 0) {
            return Double.NaN;
        }
        double[] a = outroSpans.starts;
        double gA = medianGapBefore(a, cutSecs);
        if (!(gA > 0.0)) {
            return Double.NaN;
        }
        int aLastIdx = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i] > cutSecs + windowSecs) {
                break;
            }
            aLastIdx = i;
        }
        if (aLastIdx < 0) {
            return Double.NaN;
        }
        double aLast = a[aLastIdx];
        double bFirst = Double.NaN;
        for (int i = 0; i < introSpans.starts.length; i++) {
            if (introSpans.starts[i] >= entrySecs) {
                bFirst = introSpans.starts[i];
                break;
            }
        }
        if (Double.isNaN(bFirst)) {
            return Double.NaN;
        }
        double endA = Math.min(outroRealEnd(outroSpans, aLastIdx, cutSecs), windowSecs);
        double ideal = endA + gA;
        return Math.abs((bFirst - entrySecs) - ideal);
    }

    /**
     * 从候选里挑出"融合结束能踩住 B 的句头"的那些；要求太高、一个都挑不出时返回 {@code null}
     * （调用方会退一步，见 {@code planCollect} 里那段两级筛）。
     *
     * @param requireRise 是否还要求那个句头是"这一段的起头"（见 {@link #ENTRY_HEAD_RISE_RATIO}）
     */
    private static double[] pickAligned(dev.t1m3.qplayer.audio.mix.AutoCue.Envelope intro,
                                        LyricSpans introSpans, double[] times, double[] windows,
                                        double gapSecs, boolean requireRise) {
        // requireRise = false 是第二级：连"融合期里不许起新句"也放宽（见六参版判据的注释）
        boolean cleanFade = false;   // 见 lineGapTerm 的注释：这一条改成打分偏好，不再筛
        int keep = 0;
        for (int i = 0; i < times.length; i++) {
            if (headFollowsPhraseGap(introSpans, times[i], windows, gapSecs,
                    ENTRY_HEAD_TOL_SECS, cleanFade)
                    && (!requireRise
                            || anyWindowRises(intro, introSpans, times[i], windows))) {
                keep++;
            }
        }
        if (keep < MIN_LYRIC_HEAD_CELLS) {
            return null;
        }
        double[] kept = new double[keep];
        int n = 0;
        for (int i = 0; i < times.length; i++) {
            if (headFollowsPhraseGap(introSpans, times[i], windows, gapSecs,
                    ENTRY_HEAD_TOL_SECS, cleanFade)
                    && (!requireRise
                            || anyWindowRises(intro, introSpans, times[i], windows))) {
                kept[n++] = times[i];
            }
        }
        return kept;
    }

    /** {@link #risesAtSettleHead} 的"任意一个候选窗口都行"版本（筛候选集时用）。 */
    private static boolean anyWindowRises(dev.t1m3.qplayer.audio.mix.AutoCue.Envelope intro,
                                          LyricSpans introSpans, double entrySecs,
                                          double[] windows) {
        if (windows == null) {
            return true;
        }
        for (int k = 0; k < windows.length; k++) {
            if (risesAtSettleHead(intro, introSpans, entrySecs, windows[k])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「融合结束」踩住的句头是不是**这一段的起头**：句头后 2 s 的电平 ≥ 句头前 1 s 的
     * {@link #ENTRY_HEAD_RISE_RATIO} 倍。见那个常量的说明。
     *
     * <p>没踩住句头（或没有歌词）时返回 {@code true}——那条判据由 {@link #alignsWithHead} 管。
     */
    static boolean risesAtSettleHead(dev.t1m3.qplayer.audio.mix.AutoCue.Envelope intro,
                                     LyricSpans introSpans, double entrySecs, double windowSecs) {
        if (intro == null || introSpans == null || introSpans.starts.length == 0) {
            return true;
        }
        double settle = entrySecs + windowSecs;
        double head = Double.NaN;
        for (int i = 0; i < introSpans.starts.length; i++) {
            if (introSpans.starts[i] >= settle) {     // 融合结束之后的第一句（见 headFollowsPhraseGap）
                head = introSpans.starts[i];
                break;
            }
        }
        if (Double.isNaN(head)) {
            return true;
        }
        double before = meanLevel(intro, head - 1.0, head - 0.2);
        double after = meanLevel(intro, head + 0.5, head + 2.0);
        if (!(before > 0.0)) {
            return after > 0.0;                 // 前面是静音：那正是"起头"
        }
        return after >= before * ENTRY_HEAD_RISE_RATIO;
    }

    /** 包络在 [from, to] 这一段的平均电平。 */
    private static double meanLevel(dev.t1m3.qplayer.audio.mix.AutoCue.Envelope env,
                                    double from, double to) {
        int i0 = Math.max(0, env.indexAt(Math.max(0.0, from)));
        int i1 = Math.min(env.rms.length - 1, env.indexAt(to));
        if (i1 < i0) {
            return env.rms[Math.max(0, Math.min(env.rms.length - 1, i0))];
        }
        double sum = 0.0;
        for (int i = i0; i <= i1; i++) {
            sum += env.rms[i];
        }
        return sum / (i1 - i0 + 1);
    }

    /** {@link #endsPhraseNear} 的"任意一个候选窗口都行"版本（筛候选集时用）。 */
    static boolean cutEndsPhrase(LyricSpans outroSpans, double cutSecs, double[] windows) {
        if (outroSpans == null || windows == null) {
            return true;
        }
        for (int k = 0; k < windows.length; k++) {
            if (endsPhraseNear(outroSpans, cutSecs + windows[k], ENTRY_HEAD_TOL_SECS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>A 的某一乐句是不是正好在 {@code secs} 那一刻唱完</b>（出场侧的对称规则）。
     *
     * <p>用户 2026-10-05：「现在考虑了 B 的歌词是否唱完，你还得考虑 A 的啊，现在从 A 半途
     * 跳出去了」。
     *
     * <p>A 的判定点是**融合结束**（{@code cut + window}）——那一刻 A 的增益降到 0，也就是
     * "A 真正消失"的时刻。它必须正好是 A 某个乐句的**结束**，否则听感就是"A 唱着唱着被掐掉"。
     *
     * <p>与进场侧合起来才完整：**同一个真实时刻**，A 刚唱完一句、B 刚起一句
     * （A 侧看的是 {@code cut + w}、B 侧看的是 {@code entry + w}，而融合只有一条时间轴）。
     *
     * <p>没有真实结束时间时用"下一句起点"兜底（连唱的歌里它就等于上一句的结束）。
     * 最后一句且没有真实结束时间 → 不猜、跳过。
     */
    public static boolean endsPhraseNear(LyricSpans outroSpans, double secs, double tolSecs) {
        if (outroSpans == null || outroSpans.starts.length == 0) {
            return true;                        // 没有歌词：不知道就不拦
        }
        // **A 此刻根本没人在唱 → 这条自动满足**（2026-10-05 修，参考对的根因）。
        //
        // 规则的本意是"A 的乐句别被切断"，而器乐段（尾奏 vamp、间奏）里**不存在**被切断的
        // 乐句——可旧写法只认"正好落在某句的句尾上"，于是器乐尾奏上这条**永远不满足**，
        // 出场点被拖走：实测 skydive（尾奏 145~184 s 是 0% 人声的 vamp）本该 180.0，
        // 却因为 187.5 处找不到"句尾"而落到 167.8。
        if (!insideLyricSpan(outroSpans, secs)) {
            return true;
        }
        double[] starts = outroSpans.starts;
        boolean real = outroSpans.hasEnds();
        for (int i = 0; i < starts.length; i++) {
            double end;
            if (real && outroSpans.ends[i] > starts[i]) {
                end = outroSpans.ends[i];
            } else if (i + 1 < starts.length) {
                end = starts[i + 1];
            } else {
                continue;
            }
            if (Math.abs(end - secs) <= tolSecs) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>直接从音频的「人声活动段」造乐句 spans</b>——不再依赖歌词。
     *
     * <p>用户 2026-10-05：「你就直接用人声活动段，把扫歌词舍弃掉，这样能加速加载，
     * 而且有人填词时间全是错位的，很恶心」。
     *
     * <p>为什么这比歌词可靠：歌词只是"应该什么时候唱"，而宿主拿到的歌词可能是**别的时间轴**
     * （错位的手填 LRC、现场版/remaster 对不上、翻译行混进来），一旦错位，整套对齐规则
     * 就全落在错的地方。人声活动段是**从音频本身量出来的**：有人在唱就是一句，唱完了就是句尾，
     * 换气就是句间——它天然带着我们最需要的两样东西（句头 = 段的起点、句尾 = 段的终点，
     * 都是**真实**的而不是估的）。
     *
     * <p>做法：{@link Envelope#voiceActiveMask()}（中频带能量 + 在动 + 中置门）的连续活跃段，
     * 合并 {@link #VOCAL_HOLE_MERGE_SECS} 以内的短洞（咬字之间的停顿不算断句），
     * 丢掉短于 {@link #VOCAL_PHRASE_MIN_SECS} 的段。没有分频数据时返回 {@code null}
     * （不知道就不拦，退回"没有乐句信息"的行为）。
     *
     * <p>模型：{@code audio/model/StemSeparator} 那条路（onnxruntime）产出的人声曲线更适合
     * 干这件事，但它要跑一次推理；先用 DSP 掩码（零成本、已经在算了），
     * 有模型的机器将来把曲线接进来即可——两者的产物都是这一份 spans。
     */
    public static LyricSpans vocalPhraseSpans(Envelope env) {
        float[] mask = env == null ? null : env.voiceActiveMask();
        if (mask == null || mask.length == 0) {
            return null;
        }
        int mergeHops = Math.max(1, (int) Math.round(VOCAL_HOLE_MERGE_SECS / HOP_SECS));
        int minHops = Math.max(1, (int) Math.round(VOCAL_PHRASE_MIN_SECS / HOP_SECS));
        java.util.ArrayList<double[]> runs = new java.util.ArrayList<>();
        int start = -1;
        int lastActive = -1;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] == 0f) {
                continue;
            }
            if (start < 0) {
                start = i;
            } else if (i - lastActive > mergeHops) {
                runs.add(new double[]{start, lastActive});   // 洞太长 → 断句
                start = i;
            }
            lastActive = i;
        }
        if (start >= 0) {
            runs.add(new double[]{start, lastActive});
        }
        int keep = 0;
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i)[1] - runs.get(i)[0] + 1 >= minHops) {
                keep++;
            }
        }
        if (keep == 0) {
            return null;
        }
        double[] starts = new double[keep];
        double[] ends = new double[keep];
        int n = 0;
        for (int i = 0; i < runs.size(); i++) {
            double[] r = runs.get(i);
            if (r[1] - r[0] + 1 < minHops) {
                continue;
            }
            starts[n] = env.secs((int) r[0]);
            // 句尾 = 最后一个活跃格**之后**那一格：那一格已经没人唱了，
            // 于是"句尾"落在空隙的起点上（与歌词的 end 同一个含义）。
            ends[n] = Math.min(env.durationSecs, env.secs((int) r[1] + 1));
            n++;
        }
        return new LyricSpans(starts, ends);
    }

    /**
     * <b>「融合结束」之后 B 的第一句，要隔 A 自己的乐句间隔才起头</b>（用户 2026-10-05）。
     *
     * <p>用户原话：「现在是 A 句子唱完 B 马上开始。这里的对齐要考虑**人声节奏对齐**啊，
     * 不然你隔着唱 rap 呢」。
     *
     * <p>这一句点破了上一版的毛病：我上一版要求 B 的句头**正好压在**融合结束那一刻，
     * 于是 A 一唱完 B 就张嘴——两句人声首尾相接、你来我往，听感是"对唱/接力"，不是过渡。
     * 正确的形状是他在 2026-10-04 最早说的那句：**A 的句子唱完，隔它自己惯常的间隔 gA，
     * B 的下一句才起头**。gA 取 A 末几句间隔的中位数（{@code medianGapBefore}，
     * 实测 Sparks Fly 末四段是 4.38 / 4.65 / 12.09 / 3.66 → 中位数 <b>4.52 s</b>）。
     *
     * <p>注意 gA 这一段里 B 并不是不响——它可能正在唱自己上一句的尾巴（进场点按新规则
     * 会落在更早的位置）。要对齐的是**两侧的"句子节拍"**：A 一句唱完，下一句人声要等一个
     * 正常的乐句间隔才出现。
     */
    public static boolean headFollowsPhraseGap(LyricSpans introSpans, double entrySecs,
                                               double[] windows, double gapSecs,
                                               double tolSecs) {
        return headFollowsPhraseGap(introSpans, entrySecs, windows, gapSecs, tolSecs, true);
    }

    /**
     * 同上，但可以**只要"隔 gA"那一条、放弃"融合期里不许起新句"**。
     *
     * <p>为什么需要这个开关（2026-10-05 实测）：第二条实际上把"B 的人声段起点"当成了
     * 量化格点——参考对（skydive → 春めく）上它把进场从锚点想要的 14.0 拉到 **10.0**，
     * 而 Apple 的 14~15 **恰恰违反**它。用户说"全力接近苹果的"，所以把它降成**偏好**：
     * 有满足严格版的落点就用严格的，一个都没有才退到只保证"隔 gA"。
     */
    public static boolean headFollowsPhraseGap(LyricSpans introSpans, double entrySecs,
                                               double[] windows, double gapSecs,
                                               double tolSecs, boolean requireCleanFade) {
        if (introSpans == null || introSpans.starts.length == 0 || windows == null) {
            return true;
        }
        boolean gapKnown = !Double.isNaN(gapSecs);
        for (int k = 0; k < windows.length; k++) {
            double settle = entrySecs + windows[k];
            double head = Double.NaN;
            double firstFrom = Double.NaN;
            for (int i = 0; i < introSpans.starts.length; i++) {
                if (Double.isNaN(firstFrom) && introSpans.starts[i] >= entrySecs) {
                    firstFrom = introSpans.starts[i];
                }
                if (introSpans.starts[i] >= settle) {
                    head = introSpans.starts[i];
                    break;
                }
            }
            if (Double.isNaN(head)) {
                continue;                       // 后面没有歌词了：这个窗口量不出来
            }
            // **融合期里不许有新句子起头**：B 这期间该在唱上一句的尾巴，不该张嘴开新句。
            // 少了这一条会挑出"副歌正好被埋在淡入里"的落点——实测（真素材、窗口 5 s）
            // 进场 49.8 / 融合结束 54.8：副歌句头 51.46 落在融合的 32% 处，B 到满音量时
            // 副歌已经唱了 3.3 s，就是用户最早抱怨的"滑过来的时候都唱了几个字了"。
            if (requireCleanFade && !Double.isNaN(firstFrom) && firstFrom < settle - tolSecs) {
                continue;
            }
            if (!gapKnown || Math.abs((head - settle) - gapSecs) <= tolSecs) {
                return true;                    // 量不出间隔时，只要求"融合期里没起新句"
            }
        }
        return false;
    }

    /**
     * <b>这个进场点配上某个候选窗口之后，「融合结束」那一刻是不是正好落在 B 的句头。</b>
     *
     * <p>见 {@link #ENTRY_HEAD_TOL_SECS}。没有歌词时返回 {@code true}——不知道就不拦。
     */
    public static boolean alignsWithHead(LyricSpans introSpans, double entrySecs,
                                         double[] windows, double tolSecs) {
        if (introSpans == null || introSpans.starts.length == 0 || windows == null) {
            return true;
        }
        for (int k = 0; k < windows.length; k++) {
            double settle = entrySecs + windows[k];
            for (int i = 0; i < introSpans.starts.length; i++) {
                if (Math.abs(introSpans.starts[i] - settle) <= tolSecs) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * B 的第一句歌词**句头**出现的那一刻，B 的声音已经推到多大了（0~1；等功率融合）。
     *
     * <p>见 {@link #ENTRY_HEAD_GAIN_MIN}：这个数太小就意味着"句头被压在人声底下"，
     * 等 B 听得清时句子早唱了几个字——用户听到的正是那个。
     *
     * @return 量不出来（进场点之后 B 没有歌词了 / 没有歌词）时返回 {@link Double#NaN}
     */
    public static double headGain(LyricSpans introSpans, double entrySecs, double windowSecs) {
        if (introSpans == null || introSpans.starts.length == 0 || !(windowSecs > 0.0)) {
            return Double.NaN;
        }
        double head = Double.NaN;
        for (int i = 0; i < introSpans.starts.length; i++) {
            if (introSpans.starts[i] >= entrySecs) {
                head = introSpans.starts[i];
                break;
            }
        }
        if (Double.isNaN(head)) {
            return Double.NaN;
        }
        double progress = Math.min(1.0, Math.max(0.0, (head - entrySecs) / windowSecs));
        return Math.sin(Math.PI / 2.0 * progress);
    }

    /**
     * A 的第 {@code idx} 句在**真实时间轴**上唱到什么时候（融合期内的秒数，交接那一刻为 0）。
     *
     * <p>有真实结束时间（逐字歌词）就直接用；没有才退回"一句按 {@link #VOCAL_LINE_MIN_SECS} 估"。
     * 2026-10-04 之前这里一律用估计值，而用户手机上拿到的是逐字数据——实测这对曲子的差别很大：
     * 炼爱的句子长 <b>6.6~7.6 s</b>，估计值 2.5 s 只有它的三分之一，于是"A 唱完的时刻"被算早了
     * 四秒多，理想进场点跟着整体前移。
     */
    private static double outroRealEnd(LyricSpans outroSpans, int idx, double cutSecs) {
        double start = outroSpans.starts[idx];
        if (outroSpans.hasEnds() && outroSpans.ends[idx] > start) {
            return outroSpans.ends[idx] - cutSecs;
        }
        return (start - cutSecs) + VOCAL_LINE_MIN_SECS;
    }

    /** 这一处算不算"卡在念歌词"：落在 {@link #LINE_MID_FROM}~{@link #LINE_MID_TO} 之间。 */
    public static boolean isMidLyric(LyricSpans spans, double heardSecs) {
        double sung = sungFractionAt(spans, heardSecs);
        return !Double.isNaN(sung) && sung > LINE_MID_FROM && sung < LINE_MID_TO;
    }

    /**
     * <b>这一处是不是落在某一句歌词"正在唱"的范围里</b>（用户 2026-10-04 的硬规则：
     * 「在歌词范围内的时间都不让选」）。
     *
     * <p>这是"排开歌词"的最终形态，比 {@link #isMidLyric} 严得多也简单得多：
     * 后者只拦一句的**中段**（0.30~0.70），句头和句尾都放过——而实测用户手机上选中的
     * 那个点（炼爱 49.8 s）落在那一句的 <b>87%</b> 处，正是"句尾"，于是它一路绿灯。
     * 现在整句的范围（start → end）都不许落，只剩两种合法位置：
     * <b>第一句之前</b>，以及<b>两句之间的换气</b>。
     *
     * <p>没有真实结束时间（宿主只给了 LRC 行起始）时，一句的范围按
     * {@link #VOCAL_LINE_MIN_SECS} 估，且不超过到下一句的距离——退化成"句头之后
     * 短短一段不许进"，是这套数据下唯一诚实的读法。两侧都没有歌词就返回 {@code false}
     * （不知道就不拦，与这个包一贯的纪律一致）。
     */
    public static boolean insideLyricSpan(LyricSpans spans, double secs) {
        if (spans == null || spans.starts.length == 0) {
            return false;
        }
        double[] starts = spans.starts;
        if (secs < starts[0]) {
            return false;                       // 第一句之前：还没张嘴
        }
        boolean real = spans.hasEnds();
        for (int i = 0; i < starts.length; i++) {
            if (secs < starts[i]) {
                return false;                   // 落在上一句结束与这一句之间（换气）
            }
            double len;
            if (real && spans.ends[i] > starts[i]) {
                len = spans.ends[i] - starts[i];
            } else {
                double slot = i + 1 < starts.length
                        ? starts[i + 1] - starts[i] : Double.MAX_VALUE;
                len = Math.min(VOCAL_LINE_MIN_SECS, slot);
            }
            if (secs < starts[i] + len) {
                return true;                    // 正在唱
            }
        }
        return false;                           // 最后一句之后
    }

    /** 见 {@link #LINE_MID_STRENGTH}：罚到底时的乘数。 */
    static final double LINE_MID_PENALTY = 1.0 - LINE_MID_STRENGTH;

    /**
     * 「结构奖励」的上限（用户 2026-10-01：自动点位要像 Apple Music）。
     *
     * <p>为什么需要它：七项量的全是**能量/频谱**的贴合度，没有任何一项知道「这首歌在哪儿
     * 结束、那首歌从哪儿开始」。实测（参考曲 skydive → 春めく，固定出场 174.4 s）：
     * 进场点 15.0 s 的总分 0.7168、18.5 s 是 0.7299、19.5 s 是 0.7343——分数**单调地往晚处走**，
     * 把它压下去的是「淡化起伏 0.761→0.807」「响度 0.983→1.000」「频段互补 0.242→0.253」
     * 三项合起来的 0.0175 分。而正好在 15.0 s 处，进场曲的电平刚过 1 dB 台阶
     * （15.5 s 起就满分），于是「能量更贴」的那一侧赢了。
     * Apple 挑的是**结构点**（歌真正开始的地方），我们量的是能量点。
     *
     * <p>量级取 {@value}：与 {@link #LINE_GAP_WEIGHT}（歌词那项）同量级，都是"锦上添花"，
     * 加在七项之上、不进分母——**它不该有能力把七项里很差的一侧硬拉上来**
     * （例如为了凑尾声起点而切到一段安静处：那种候选在「响度接续」上要掉 0.1 分以上，
     * 远超这里的上限，所以压不动它；`AutoCueTest#neverCutsAtTheVeryEndAndKeepsTheTwoSidesLevelMatched`
     * 那条判据盯的就是这件事）。
     */
    static final double STRUCTURE_WEIGHT = 0.06;

    /**
     * <b>进场侧那条结构奖励单独的倍数</b>（用户 2026-10-04：「你得识别 intro 中间 高潮 outro 啊」）。
     *
     * <p>为什么进场侧要单独放大：{@link #STRUCTURE_WEIGHT}（0.06）是给两侧<em>对称</em>的
     * "锦上添花"用的，它压不动九项——而用户拿 Apple 的实测数据（A 剩 25 s / B 从 45 s 进）
     * 说明"**从哪一段进来**"本身就该是决定性的：B 的真正开始是 52.4 s，Apple 的 45 s
     * 正好让中点落在那里。所以进场侧按这个倍数放大（{@value}×0.06 = 0.30），
     * 量级与九项里最重的「波形重合」(0.206) 同档甚至更高——
     * 也就是"**音色再合，也不许从半路进来**"。
     *
     * <p>出场侧**不动**：A 的出场点本来就由"少切一点 + 尾声起点"管着，
     * 实测它已经和 Apple 一致（238.1 s 距曲尾 23.1 s vs Apple 的 25 s）。
     */
    static final double STRUCTURE_INTRO_SCALE = 5.0;

    /**
     * <b>出场侧那条结构奖励单独的倍数</b>（2026-10-06，与 {@link #STRUCTURE_INTRO_SCALE}
     * 对称的那一半）。
     *
     * <p>为什么出场侧也要放大：{@link #STRUCTURE_WEIGHT}（0.06，出场半项上限 0.03）
     * 压不动九项。而九项里唯一"知道 A 该从哪儿走"的是「走向」（{@code WEIGHTS[4]} = 0.8），
     * 它量的是 <b>±1 格 = 20 ms</b> 的原始 RMS 差分的符号——一声鼓就能翻符号。
     * 取证（{@code tools/automix-forensics/}）把这件事量得很清楚：Apple 在
     * skydive → 春めく 上的出场点是 <b>181.1 s</b>（= 尾声里那条 8 小节线 181.4455 的近邻，
     * 也是「平滑电平最后一次还在主电平上」的那一格 181.2），而「走向」在那一格给
     * <b>0.519</b>、在它前面 1.77 s 的那一格给 <b>1.000</b>——于是那一项在总分里
     * 赢 0.0497，把出场点拉到 <b>179.68</b>（= 能量地标本身），比 Apple 早 1.4 s。
     * 也就是说：**"切的时候 A 必须正在往下掉"这条前提，与 Apple 的实测相反**
     * ——Apple 恰恰是在最后一段副歌还没落下来时开始撤的，是<b>淡化本身</b>让它落下去的。
     *
     * <p>量级取 {@value}（与进场侧同一个数）：{@code 5 × 0.06 × 0.5 = 0.15}，与九项里
     * 最重的「波形重合」(1.6 / 7.75 ≈ 0.206) 同档——也就是"<b>接缝再合，也不许在乐句
     * 中间切出去</b>"。这正是进场侧那条 {@link #STRUCTURE_INTRO_SCALE} 的理由在出场侧的
     * 对偶（"音色再合，也不许从半路进来"）。
     *
     * <p>两侧倍数<b>都只在目标测得出时才起作用</b>（{@link #proximity} 的目标为负时给中性
     * 0.5，对同一批候选是同一个常数）——测不出尾声的素材上这一改一个字都不动。
     *
     * <p><b>2026-10-06 补记</b>：上面那段「20 ms 尺度的走向会把出场点拉走 0.0497」的处境
     * 已经被这次改动**去掉**了（九项那一项换成段落尺度之后，181.4455 那一格的走向是
     * 1.000 而不是 0.038）。这个倍数按纪律**原样保留**：它是上一步用真素材标定出来的，
     * 而当前没有量测证明它的幅度现在不对（改成 1.0 会让 181.4455 在精细评分里失去
     * 那 0.15 分的领先——那正是 Apple 的落点，所以更不能顺手改小）。
     */
    static final double STRUCTURE_OUTRO_SCALE = 5.0;

    /** 结构奖励的容忍带（秒）：落在目标 ±这么多秒内线性给分，之外为 0。 */
    static final double STRUCTURE_TOLERANCE_SECS = 6.0;

    /**
     * 「还在主电平」的判定比例：平滑电平 ≥ 该比例 × 参考电平。
     *
     * <p>取 {@value}（很紧）——**量的是「电平开始往下掉的那一刻」，不是「已经掉下去了」**。
     * 2026-10-01 实测：取 0.85 时 skydive 的尾声起点测成 185.0 s，而 Apple Music 选的是 174.0 s；
     * 原因是渐弱很缓，到 185 s 都还在参考电平的 85% 以上。抬到 0.95 之后，
     * 最后一次「还在满电平」的位置落在最后一个副歌的尾部，就是 174 那一带。
     * 进场侧同一个口径：0.85 时把 春めく 的前奏起点测成 11.3 s，Apple 用 15.0 s。
     */
    static final double OUTRO_KEEP_RATIO = 0.95;

    /** 进场曲「已经开始」的判定比例（与出场侧同一个口径，理由见上）。 */
    static final double INTRO_KEEP_RATIO = 0.95;

    /** 往回到上升起点时，电平不许掉到这个参考比例以下（否则算走到引子的静段里了）。 */
    static final double INTRO_RISE_FLOOR_RATIO = 0.35;
    /** 往回走时允许的"前一格比后一格高"的倍数（抖动容忍；超过就说明那儿是另一个峰）。 */
    static final double INTRO_RISE_BACK_TOLERANCE = 1.15;

    /** 平滑电平用的滑动窗（秒）：短于此的起伏不该被当成结构。 */
    static final double STRUCTURE_SMOOTH_SECS = 2.0;

    /** 色度新颖度的比较窗（秒）：前后各取这么长求均值再比。 */
    static final double NOVELTY_WINDOW_SECS = 2.0;

    /** 新颖度的多个时间尺度（秒）：三个尺度取和（乐句边界在长短两个尺度上都该有变化）。 */
    static final double[] NOVELTY_SCALES = {1.0, 2.0, 4.0};

    /** 边界门槛：新颖度相对整曲 p90 的比例（相对量，免得被整体音量/活跃度带偏）。 */
    static final double NOVELTY_PEAK_RATIO = 0.6;

    /**
     * 边界两侧的电平比：右边低于左边的这个比例就不算段落边界（那是结尾的衰减）。
     *
     * <p>见 {@link #noveltyBoundary}——没有这道过滤时 skydive 的尾声起点被测成 188.2 s
     * （最后那段淡出），而 Apple 选的是 174.0 s。
     */
    static final double NOVELTY_SIDE_LEVEL_RATIO = 0.5;

    /** 「窗内极大」的窗半宽（秒）：电平在这段时间里都是最高，才算「最后一座峰」。 */
    static final double STRUCTURE_PEAK_SECS = 3.0;

    /** 尾声至少要这么长（秒）才认；更短就当作没有尾声。 */
    static final double STRUCTURE_MIN_RUN_SECS = 4.0;

    /**
     * 对一套方案（出场点、进场点、窗口）算整窗的七项。
     *
     * <p>窗口部分按 {@link #HOP_SECS} 的格子在两条包络上走一遍；波形重合只算窗口
     * <strong>开头 {@value #OVERLAP_SPAN_SECS} 秒</strong>——再往后只会把差异平均掉，
     * 而成本随窗口线性增长（准备阶段要跑几十对 × 几个窗口）。
     */
    static Score evaluate(Envelope outro, Envelope intro,
                          double cutSecs, double entrySecs, double windowSecs) {
        int c0 = outro.indexAt(cutSecs);
        int e0 = intro.indexAt(entrySecs);
        int hops = Math.max(1, (int) Math.round(windowSecs / HOP_SECS));

        double sum = 0.0;
        double sumSq = 0.0;
        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < hops; i++) {
            float p = hops <= 1 ? 0.5f : (float) i / (float) (hops - 1);
            Mixer.Gains g = Mixer.equalPowerGains(p);
            double a = outro.rms[Math.min(c0 + i, outro.rms.length - 1)];
            double b = intro.rms[Math.min(e0 + i, intro.rms.length - 1)];
            double mixed = a * g.from + b * g.to;
            sum += mixed;
            sumSq += mixed * mixed;
            meanA += a;
            meanB += b;
        }
        double mean = sum / hops;
        double variance = Math.max(0.0, sumSq / hops - mean * mean);
        double ripple = mean <= 1e-6 ? 1.0 : Math.min(1.0, Math.sqrt(variance) / mean);
        meanA /= hops;
        meanB /= hops;

        double overlap = waveOverlap(outro, intro, cutSecs, entrySecs, windowSecs);
        double bands = bandComplement(outro, intro, c0, e0, hops);
        // 响度接续：**带死区**——差在 {LEVEL_DEAD_ZONE} 以内算「接得上」，不给额外奖励。
        //
        // 2026-10-01 量出来的毛病：原来是线性的 `1 − |Δ|`，于是它会把「0.6 dB 的响度差」
        // 也当成信号去奖励（参考曲上 0.998 vs 0.932 差 0.066 分），并且正是这一项
        // （加上频段互补）把出场点从 Apple 的 174 s 带到了 139.7 s——那种量级的差别
        // **根本听不出来**。听感上真正要避免的是「一边比另一边明显轻/响」，也就是超过
        // 1 dB 量级的那种；死了这一段之后，`AutoCueTest` 那条「整窗响度必须接得上」
        // 仍然有效（0.45 的差照样重罚）。
        double levelDelta = Math.abs(meanA - meanB);
        double level = levelDelta <= LEVEL_DEAD_ZONE ? 1.0
                : 1.0 - Math.min(1.0, (levelDelta - LEVEL_DEAD_ZONE)
                        / (1.0 - LEVEL_DEAD_ZONE));
        // 走向：出场**不在往上冲**、进场**不在往下掉**——两项都在**段落尺度**上判
        // （{@link #rising} / {@link #falling}，左右各一小节的均值的相对差）。
        // 这里与粗排那两个代价项用的是同一个量，见 {@link #DIRECTION_SPAN_BARS}。
        double motion = 0.5 * (1.0 - rising(outro, c0))
                + 0.5 * (1.0 - falling(intro, e0));
        double tailDrop = Math.max(0.0, outro.durationSecs - (cutSecs + windowSecs));
        double tail = tailTerm(tailDrop, outro.durationSecs);

        double beat = 0.5;
        String beatNote = "没测出拍点";
        if (outro.hasBeatGrid() && intro.hasBeatGrid()) {
            double driftBeats = Math.abs(outro.tempo.bpm - intro.tempo.bpm) / 60.0 * windowSecs;
            double over = Math.max(0.0, driftBeats - DRIFT_FREE_BEATS);
            beat = 1.0 - Math.min(1.0, over / DRIFT_FALLOFF_BEATS);
            beatNote = String.format(java.util.Locale.US, "拍漂移 %.1f 拍/窗口", Double.valueOf(driftBeats));
        }
        double lowEnd = lowEndClash(outro, intro, c0, e0, hops);
        // 人声重叠：**只扫一遍**——分数与秒数（P6 硬约束按秒数判定）来自同一次扫描。
        double vocalSecs = vocalOverlapSecs(outro, intro, c0, e0, hops);
        double vocal = vocalOverlapTerm(vocalSecs);
        return new Score(overlap, 1.0 - ripple, bands, level, motion, beat, tail, lowEnd, vocal,
                vocalSecs,
                beatNote, cutSecs, entrySecs, windowSecs, null, null, null, null);
    }

    /**
     * 「响度接续」的死区：整窗均值差在这个比例以内算接得上，给满分。
     *
     * <p>取 {@value}（约 1 dB）。理由见 {@code evaluate} 里那段：线性口径会把 0.6 dB 的差别
     * 当信号，实测把出场点从 174 s 带偏到 139.7 s。超过死区的部分照样线性罚到 0。
     */
    static final double LEVEL_DEAD_ZONE = 0.10;

    /** 波形重合的相关跨距（秒）。 */
    static final double OVERLAP_SPAN_SECS = 4.0;

    /**
     * 波形重合度：两段波形在窗口里的归一化互相关，在几个<strong>有意义的滞后</strong>上取最大。
     *
     * <p>滞后候选是「0、±半拍、±一拍」（用两边共同的节拍周期；没测出节拍就用 ±50 ms / ±100 ms）：
     * 拍点栅格只把点位对齐到各自的<strong>拍</strong>上，而两边的相位还差着未知量——
     * 只差半个周期时听起来是「底鼓错开」，差一整个周期则完全无所谓。
     * 在 ±1 拍里找最好的一处，正是 DJ 对拍时手上做的事。
     *
     * @return 0..1（1 = 完全同相、0.5 = 无关、0 = 反相）；没有波形数据时返回中性的 0.5
     */
    static double waveOverlap(Envelope a, Envelope b,
                              double cutSecs, double entrySecs, double windowSecs) {
        if (a.wave == null || b.wave == null || a.waveRate <= 0 || b.waveRate <= 0) {
            return 0.5;
        }
        int rate = Math.min(a.waveRate, b.waveRate);
        int span = (int) Math.max(1L, Math.round(Math.min(windowSecs, OVERLAP_SPAN_SECS) * rate));
        int a0 = (int) Math.round(cutSecs * rate);
        int b0 = (int) Math.round(entrySecs * rate);
        if (a0 + span >= a.wave.length || b0 + span >= b.wave.length || a0 < 0 || b0 < 0) {
            // 波形没覆盖到这一段（进场侧只扫了前一段）：不猜，给中性值
            return 0.5;
        }
        double beatSecs = beatSecsFor(a, b);
        // **只搜 ±半拍，不搜 ±一拍**（2026-10-01 改，实测依据）。
        //
        // 原来搜到 ±一拍：两个栅格都在时 beatSecs ≈ 0.47 s，±1 拍就是 ±0.47 s 的容差——
        // 在 4~8 秒的窗口上那几乎等于「随便挪」，位置分辨力被抹平：参考曲上**每个窗口**
        // 都挑到 180~182 s（丢尾只 2~4 s），而 Apple Music 选的是 174（丢尾 9 s）。
        // 收到 ±半拍之后「底鼓错开」那种情况仍然覆盖（这正是这条搜索存在的理由），
        // 但「差一整个周期」不再被当成可以凑分的手段——本方法自己的注释里就写着
        // 「差一整个周期则完全无所谓」，既然是「无所谓」，就不该进候选。
        double[] lags = {0.0, 0.5 * beatSecs, -0.5 * beatSecs};
        double best = -1.0;
        for (int i = 0; i < lags.length; i++) {
            int lag = (int) Math.round(lags[i] * rate);
            int bStart = b0 + lag;
            if (bStart < 0 || bStart + span >= b.wave.length) {
                continue;
            }
            double c = correlation(a.wave, a0, b.wave, bStart, span);
            if (c > best) {
                best = c;
            }
        }
        if (best < -1.0) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, (best + 1.0) * 0.5));
    }

    /** 两边共同的最小拍长（秒）；任一侧没有拍点时用 0.5 s（≈120 BPM）当兜底。 */
    private static double beatSecsFor(Envelope a, Envelope b) {
        double out = 0.5;
        if (a.hasBeatGrid()) {
            out = a.tempo.periodFrames / (double) a.beatRate;
        }
        if (b.hasBeatGrid()) {
            double other = b.tempo.periodFrames / (double) b.beatRate;
            out = Math.min(out > 0 ? out : other, other);
        }
        if (!(out > 0.05) || out > 5.0) {
            return 0.5;
        }
        return out;
    }

    /** 两段等长波形的零均值归一化互相关（−1..1）。 */
    private static double correlation(float[] a, int aStart, float[] b, int bStart, int n) {
        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < n; i++) {
            meanA += a[aStart + i];
            meanB += b[bStart + i];
        }
        meanA /= n;
        meanB /= n;
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < n; i++) {
            double x = a[aStart + i] - meanA;
            double y = b[bStart + i] - meanB;
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        double denom = Math.sqrt(normA * normB);
        if (denom <= 1e-12) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, dot / denom));
    }

    /**
     * 频段互补：三个频段上「两边同时有能量」的比例（1 = 完全互补，0 = 全程重叠）。
     *
     * <p>{@code 1 − Σ min(a_b, b_b) / Σ max(a_b, b_b)}：一边低频重、另一边高频亮时
     * 每一个频段上总有一边是小的，于是 min/max 很小 → 互补度高。
     * 两边频谱分布相近（都在低频轰）时 min ≈ max → 互补度低。
     */
    static double bandComplement(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (a.band == null || b.band == null) {
            return 0.5;
        }
        double clash = 0.0;
        double energy = 0.0;
        for (int band = 0; band < 3; band++) {
            double ma = 0.0;
            double mb = 0.0;
            for (int i = 0; i < hops; i++) {
                int ai = Math.min(aStart + i, a.band.length - 1);
                int bi = Math.min(bStart + i, b.band.length - 1);
                ma += a.band[ai][band];
                mb += b.band[bi][band];
            }
            ma /= hops;
            mb /= hops;
            clash += Math.min(ma, mb);
            energy += Math.max(ma, mb);
        }
        if (energy <= 1e-6) {
            return 1.0;      // 两边都静音：谈不上打架
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - clash / energy));
    }

    /**
     * <b>低频冲突</b>（1 = 从不打架，0 = 全程一起轰）。
     *
     * <p>为什么要单独一项：{@link #bandComplement} 把三个频段<strong>平均</strong>在一起，
     * 低频那一条只占三分之一——两个底鼓从头对到尾时，只要中高频一边亮一边暗，
     * 平均下来仍然能拿到不错的互补分。而「低频同时全开」是接缝最糊的一种：
     * 两个底鼓 + 两条贝斯叠在一起，中高频再怎么互补也盖不住。
     *
     * <p>两个成分（都在窗口内、逐 hop）：
     *
     * <ol>
     *   <li><b>低频重叠</b>：{@code 1 − Σ min(a₀, b₀) / Σ max(a₀, b₀)}，只看 {@code band[0]}
     *       （&lt; 200 Hz，装着底鼓与贝斯）。{@code band} 与 {@code rms} 归一化到**同一基准**
     *       （总能量的 95 分位，见 {@code envelopeOf}），所以两侧的值可以直接比：
     *       安静那首的低频本来就小，min 自然小——这正是要的语义（不响就不糊）。</li>
     *   <li><b>低频反相</b>：把两侧的 {@code wave} 都过一遍 120 Hz 低通再算归一化互相关。
     *       两个低频信号**反相**时它们互相抵消，听感是「低频一鼓一鼓地塌下去」——
     *       包络类特征看不见这件事（包络只回答「多响」），只有真的波形能看见。
     *       相关 +1 = 同相加强（不罚），0 = 无关，负得越多罚得越重。</li>
     * </ol>
     *
     * <p>两项按 {@value #LOW_END_MUD_SHARE} / {@value #LOW_END_PHASE_SHARE} 合成。
     * 任何一侧缺数据（手工造的包络、没有波形）时该成分按中性 0.5 算，
     * 与 {@link #bandComplement} 同一条纪律——**不猜，也不假装好或坏**。
     */
    static double lowEndClash(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (hops <= 0) {
            return 0.5;
        }
        // **缺数据的那一半不参与**，而不是按 0.5 混进去（2026-10-02，判据当场纠正的）：
        // 按 0.5 混的话，「两边低频都安静」（重叠分 1.0）会被没有波形这件事拖到 0.85，
        // 而「没有波形」并不代表「反相」——那就是拿缺数据当成了一个中等的坏消息。
        // 现在的口径与 {@link #bandComplement} 一致：没有就中性，有一半就用那一半。
        boolean hasMud = a.band != null && b.band != null;
        boolean hasPhase = a.wave != null && b.wave != null
                && a.waveRate > 0 && a.waveRate == b.waveRate;
        if (!hasMud && !hasPhase) {
            return 0.5;
        }
        if (!hasPhase) {
            return clip01(lowEndOverlap(a, b, aStart, bStart, hops));
        }
        if (!hasMud) {
            return clip01(lowEndPhase(a, b, aStart, bStart, hops));
        }
        return clip01(LOW_END_MUD_SHARE * lowEndOverlap(a, b, aStart, bStart, hops)
                + LOW_END_PHASE_SHARE * lowEndPhase(a, b, aStart, bStart, hops));
    }

    /**
     * 粗排里「两边低频都轰」的惩罚权重。
     *
     * <p>粗排的代价是若干惩罚项之和（连续性差、斜率不对、丢尾……），量级都在 0~1.6；
     * 这一项取 {@value} 让「两边低频都开满」的代价与「响度接不上」同一量级——
     * 它只负责**别把候选筛掉**，真正的判断在精细评分那一层（{@link #WEIGHTS} 里的低频冲突项）。
     */
    static final double LOW_END_COARSE_WEIGHT = 0.7;

    /** 把 {@code band} 的第 0 列（&lt; 200 Hz）抄成一条数组，供前缀和用。 */
    /**
     * <b>人声重叠</b>（1 = 窗口里两边的人声没有长时间叠唱，0 = 一直在叠）。
     *
     * <p>人声是怎么判的：{@code band[1]}（{@value #BAND_LOW_HZ} Hz–{@value #BAND_HIGH_HZ} Hz，
     * 人声共振峰所在的那条带）上要同时满足两件事——
     *
     * <ol>
     *   <li><b>够响</b>：超过 {@value #VOICE_FLOOR}（分频包络与总包络归一化到同一基准，
     *       所以这是一个可比的绝对量）；</li>
     *   <li><b>在动</b>：0.4 秒窗内的相对起伏（标准差 ÷ 均值）超过 {@value #VOICE_MODULATION}。
     *       人声按音节在动，而持续的和声垫/长音合成器在时间上几乎是平的——这是最省、
     *       又不需要模型的区分方式。</li>
     * </ol>
     *
     * <p><b>这是代理，不是 VAD 模型</b>（如实写在 {@code docs/automix-modes.md} §8.0.7）：
     * 中频上同样会按音节起伏的吉他扫弦、琶音会被误判成人声。<b>第三道判据就是为此加上的</b>
     * ——「在不在中间」（{@link #CENTER_SALIENCE_MIN}，纯 DSP 的 mid/side）：人声几乎总是
     * 录在中间，而被摆开/加倍录/宽混响的那几类不是。加上它之后，「窗口内人声重叠 ≤ 0.5 s」
     * 那条硬约束（{@link #VOCAL_GATE_SECS}）才敢上；在那之前它只能是软惩罚
     * （权重 {@code 0.6}，比频段互补 0.8 还低），因为误判会直接毁掉点位。
     *
     * <p>仍然不是模型：单声道素材拿不到中置数据（那道门不生效），纯中置的假人声
     * （居中的合成器铺底、居中的人声采样）也照样会被算成人声。
     *
     * <p>判分方式与用户提的口径一致：<b>{@value #VOICE_OVERLAP_FREE_SECS} 秒以内不罚</b>，
     * 之后线性加重，到 {@value #VOICE_OVERLAP_WORST_SECS} 秒归零。
     * 两侧都判定「没人声」时重叠为 0 → 满分（没人声就没有叠唱问题）；
     * 拿不到分频包络时按中性 0.5 算，不猜。
     */
    static double vocalOverlap(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (hops <= 0 || a.band == null || b.band == null) {
            return 0.5;
        }
        return vocalOverlapTerm(vocalOverlapSecs(a, b, aStart, bStart, hops));
    }

    /**
     * <b>叠唱秒数 → 这一项的分数</b>（0.5 s 以内满分，之后线性到 {@value
     * #VOICE_OVERLAP_WORST_SECS} s 归零）。
     *
     * <p>单独拆出来是为了让 {@code evaluate} <b>只扫一遍</b>：它既要这个分数，
     * 又要那一份秒数（P6 的硬约束按秒数判定）。扫两遍在 fine 阶段是几百次窗口扫描，
     * 而这一层最贵的恰好就是「逐 hop 扫窗口」。
     */
    static double vocalOverlapTerm(double overlapSecs) {
        if (overlapSecs <= VOICE_OVERLAP_FREE_SECS) {
            return 1.0;
        }
        double over = overlapSecs - VOICE_OVERLAP_FREE_SECS;
        return clip01(1.0 - over / (VOICE_OVERLAP_WORST_SECS - VOICE_OVERLAP_FREE_SECS));
    }

    /**
     * <b>「人声让多少」的连续权重曲线</b>：逐 hop 的 0..1，把「两边同时在唱」的 0/1 活动
     * <b>平滑</b>成一条连续曲线（见 {@code TransitionPlan.vocalDuckCurve} 的说明）。
     *
     * <p>与 {@link #vocalOverlapRanges} 的关系：区间版是「哪里要动手」的开关清单，曲线版是
     * 「每一格让多少」的连续量。两条都从同一份 {@link #voicePresent} 判据来，所以
     * 「宁可多动也不漏」这条不对称纪律两边一致。
     *
     * <p>{@code null} = 拿不到分频包络（人声层按「不确定」处理，照旧整窗分离）。
     */
    static float[] vocalDuckCurve(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (hops <= 0 || a.band == null || b.band == null) {
            return null;      // 拿不到分频包络 = 不确定（人声层照旧整窗分离）
        }
        float[] active = new float[hops];
        for (int i = 0; i < hops; i++) {
            int ai = aStart + i;
            int bi = bStart + i;
            boolean both = ai >= 0 && bi >= 0
                    && ai < a.band.length && bi < b.band.length
                    && voicePresent(a, ai) && voicePresent(b, bi);
            active[i] = both ? 1f : 0f;
        }
        // 居中盒式平滑（只取窗口内的格子，边界不借窗口外的数据）：
        // 逐格最大变化 = 1 / 窗长。窗长 15 格 = 0.3 秒 → 0.067，正好把「30 ms 的开关」
        // 摊成零点几秒的渐变（判据的上限是 0.125）。
        float[] curve = new float[hops];
        int half = DUCK_CURVE_SMOOTH_HOPS / 2;
        for (int i = 0; i < hops; i++) {
            int from = Math.max(0, i - half);
            int to = Math.min(hops, i + half + 1);
            float sum = 0f;
            for (int j = from; j < to; j++) {
                sum += active[j];
            }
            // **让位强度上限**（2026-10-09 ✓，用户：「还是有 A 瞬间消失问题」✓）：
            // 这一对两边**整窗都有唱** ⇒ 原曲线全程 = 1.0 ✗ ⇒ A 的人声从第 0 秒被整段拿走 ✓
            // ⇒ 听感就是"A 瞬间消失" ✗。压到 0.75 ⇒ 人声降下来但**仍在场** ✓，
            // 撞人声的目的一样达到 ✓；可用 {@link #setVocalDuckCap(float)} 调（1 = 逐字照旧 ✗）。
            curve[i] = (sum / (to - from)) * vocalDuckCap;
        }
        return curve;
    }

    /**
     * 人声权重曲线的平滑窗长（格）：{@value #DUCK_CURVE_SMOOTH_HOPS} 格 = 0.3 秒。
     *
     * <p>取值理由：逐格最大变化 = {@code 1 / 窗长}，所以窗长直接决定「最陡能有多陡」。
     * 开关式的区间两端只有 30 ms 坡（一格就能从 0 跳到 1 ✗），而 0.3 秒的渐变量级上
     * 听不出「人声被拿掉又放回」，同时又不至于把叠唱位置糊成一大片。
     */
    static final int DUCK_CURVE_SMOOTH_HOPS = 15;

    /** **让位强度上限**（默认 0.75 ✓；1.0 = 逐字照旧 ✓）。见 {@link #vocalDuckCurve} ✓。 */
    private static volatile float vocalDuckCap = 0.75f;

    public static void setVocalDuckCap(float cap) {
        vocalDuckCap = Math.max(0f, Math.min(1f, cap));
    }

    public static float vocalDuckCap() {
        return vocalDuckCap;
    }

    /** 这一对点位在窗口里「两边同时有人在唱」的秒数（日志与 note 里要报的就是它）。
     *
     * <p>单拿出来是为了让 note 能写清「这次叠唱了几秒」——用户验证 W3 时最想看的数就是它，
     * 而光看那一项 0..1 的分数是看不出「到底几秒」的。
     */
    static double vocalOverlapSecs(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (a.band == null || b.band == null || hops <= 0) {
            return 0.0;
        }
        double overlapSecs = 0.0;
        for (int i = 0; i < hops; i++) {
            int ai = aStart + i;
            int bi = bStart + i;
            if (ai < 0 || bi < 0 || ai >= a.band.length || bi >= b.band.length) {
                continue;
            }
            if (voiceActive(a, ai) && voiceActive(b, bi)) {
                overlapSecs += HOP_SECS;
            }
        }
        return overlapSecs;
    }

    /**
     * <b>窗口里「两边同时有人在唱」的时间段</b>（秒，相对窗口起点，平成对给：{@code from0,to0,from1,to1,…}）。
     *
     * <p>用户要的是「**只在重叠处去人声**」：没有重叠的那几秒不该动人家的素材——
     * 那既省一次分离（AI 档是秒级），也不让素材白白过一遍分离器（那会改音色）。
     * 这一支就是给人声层用的输入。
     *
     * <p>三条约束写在这里，免得调用方各自再猜：
     * <ul>
     *   <li>返回的是**相对窗口起点**的秒数（人声层渲染的就是这个窗口）；</li>
     *   <li>短于 {@value #VOICE_RANGE_MIN_SECS} 秒的段**并进前一段**——VAD 代理在音节之间
     *       本来就会闪断，逐格开关会把素材切成一堆碎条；</li>
     *   <li>两侧都判定「没人声」时返回**空数组**（不是 {@code null}）——「没有重叠」
     *       是一个明确的结论，人声层据此整段不分离。</li>
     * </ul>
     *
     * @return 空数组 = 没有重叠；{@code null} = 拿不到数据（人声层按「不确定」处理，照旧全窗分离）
     */
    static double[] vocalOverlapRanges(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (hops <= 0) {
            return new double[0];
        }
        if (a.band == null || b.band == null) {
            return null;
        }
        double[] out = new double[hops * 2];
        int n = 0;
        boolean inRange = false;
        double from = 0.0;
        double gap = 0.0;
        for (int i = 0; i <= hops; i++) {
            boolean active = false;
            if (i < hops) {
                int ai = aStart + i;
                int bi = bStart + i;
                active = ai >= 0 && bi >= 0 && ai < a.band.length && bi < b.band.length
                        && voicePresent(a, ai) && voicePresent(b, bi);
            }
            if (active) {
                if (!inRange) {
                    inRange = true;
                    from = i * HOP_SECS;
                }
                gap = 0.0;
            } else if (inRange) {
                gap += HOP_SECS;
                if (gap >= VOICE_RANGE_MIN_SECS || i >= hops) {
                    double to = i * HOP_SECS - gap;
                    // 这里**不再**要求「整段不短于 VOICE_RANGE_MIN_SECS」。
                    //
                    // 原来有一道「短于 0.4 秒就丢掉」的过滤，注释写的是「并进前一段」，
                    // 但实现里只有换气缝会被并段，长度不够的整段是直接丢弃——于是
                    // 「两边唱了 0.2~0.3 秒」这种**听得见的撞车**会产生空数组，而空数组的语义
                    // 是「整段不去人声」（见 TransitionPlan.vocalDuckRanges 的三值契约）。
                    // 用户的报障就是这么来的：开了 DSP 仍然听到两个声音叠在一起。
                    // 换气缝的并段由上面那个 gap 判定负责，所以放宽之后素材也不会被切碎
                    // （判据 AutoCueVocalRangeTest 第三条钉着这一条）。
                    if (n + 2 > out.length) {
                        out = java.util.Arrays.copyOf(out, out.length * 2);
                    }
                    out[n++] = from;
                    out[n++] = to;
                    inRange = false;
                }
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * 重叠段短于这个长度就并进相邻段（秒）。
     *
     * <p>VAD 代理按音节开关，中间必然有几十毫秒的「换气缝」；逐格开关会把素材切成碎条，
     * 听感反而更碎。并段的长度取一个音节量级（0.4 秒）。
     */
    static final double VOICE_RANGE_MIN_SECS = 0.4;

    /**
     * 这一格算不算「有人在唱」：中频够响 + 0.4 秒窗内相对起伏够大。
     *
     * <p>起伏用 {@code ±}{@value #VOICE_WINDOW_HOPS} 格（0.4 秒）的滑窗均值和标准差现算——
     * 每格 O(窗长) 的常数很小（窗长 20），而扫描阶段完全不增加成本（数据本来就有）。
     */
    static boolean voiceActive(Envelope e, int idx) {
        float[] mask = e.voiceActiveMask();
        return mask != null && idx >= 0 && idx < mask.length && mask[idx] != 0f;
    }

    /**
     * 「这一格有人在唱」的<b>基础</b>判据：够响 + 在动（不含中置门）。
     *
     * <p>它与 {@link #voiceActive} 的分工是这一轮（2026-10-02）划出来的，两边的容错方向
     * <b>相反</b>，这是有意的：
     *
     * <ul>
     *   <li><b>打分与硬约束</b>（{@link #voiceActive}）：宁可漏判也不能误判——误判会把点位
     *       带到错的地方，代价是「整次过渡都选错了」；</li>
     *   <li><b>「该去哪里去人声」</b>（{@link #voicePresent}）：宁可多动也不能漏——漏判的代价是
     *       用户<b>直接听到两首歌的人声叠在一起</b>（实测就是这样报回来的），而多动一点的代价
     *       只是多压几十毫秒的人声，有 30 ms 交叉淡化兜着。</li>
     * </ul>
     */
    private static boolean voiceBasic(Envelope e, int idx) {
        float[] mask = e.voiceBasicMask();
        return mask != null && idx >= 0 && idx < mask.length && mask[idx] != 0f;
    }

    /**
     * {@link #voiceBasic} 的那段算术，改吃「已经拿到的那一列」。
     *
     * <p>拆出来只有一个原因：掩码构建要用<b>同一份</b>算术（逐格问一次），
     * 而逐格查询与掩码构建必须是同一个函数，否则「缓存」就成了「换了算法」。
     */
    static boolean voiceBasicAt(float[] mid, int idx) {
        if (mid == null || idx < 0 || idx >= mid.length) {
            return false;
        }
        if (mid[idx] < VOICE_FLOOR) {
            return false;
        }
        int from = Math.max(0, idx - VOICE_WINDOW_HOPS);
        int to = Math.min(mid.length, idx + VOICE_WINDOW_HOPS + 1);
        double sum = 0.0;
        double sumSq = 0.0;
        for (int i = from; i < to; i++) {
            sum += mid[i];
            sumSq += (double) mid[i] * mid[i];
        }
        int n = Math.max(1, to - from);
        double mean = sum / n;
        if (mean <= 1e-6) {
            return false;
        }
        double var = Math.max(0.0, sumSq / n - mean * mean);
        return Math.sqrt(var) / mean >= VOICE_MODULATION;
    }

    /** 「在不在中间」：中置显著度够低就说明这一格主要来自被摆开的两侧（吉他/合成器）。 */
    private static boolean centered(Envelope e, int idx) {
        return e.center == null || idx >= e.center.length
                || e.center[idx] >= CENTER_SALIENCE_MIN;
    }

    /**
     * 「该去哪里去人声」用的判据：只有 {@link #voiceBasic}，<b>不要中置门</b>。
     *
     * <p>为什么这里不能用中置门：人声不一定居中——宽混响、加倍录、刻意的声像处理都会把它
     * 推到两边。实测（判据 {@code AutoCueVocalRangeTest}）：中置显著度 0.50 的人声会被中置门
     * 判成「不是人声」，于是「需要去人声的时段」变成空数组，而空数组的语义是
     * <b>整段不去人声</b>——用户就是这么听到两个声音叠在一起的。
     */
    static boolean voicePresent(Envelope e, int idx) {
        return voiceBasic(e, idx);
    }

    /**
     * <b>中置显著度</b>：{@code 中置功率 ÷ (中置功率 + 侧向功率)}，0..1。
     *
     * <p>抽成纯函数是为了让判据直接钉它（不必造一整个扫描）：居中 1.0 / 摆到一边 ≈0.5 /
     * 纯侧 0.0。扫描每 hop 用<b>已经降采样</b>的左右两路功率喂进来（见 {@code scanInternal}）。
     */
    static float centerSalience(double midPower, double sidePower) {
        double total = midPower + sidePower;
        if (total <= 0.0) {
            return 1.0f;      // 静音：没有「摆开」可言，按中性（不否决）算
        }
        return (float) (midPower / total);
    }

    /** 中频那一列的 0/1 活动掩码（粗排要用它的前缀和）。 */
    static float[] voiceMask(Envelope e) {
        // 直接把缓存那一份交出去：调用方（前缀和）只读它。
        return e.voiceActiveMask();
    }

    /** 判「有人在唱」的中频响度门槛（分频包络与总包络同一基准）。 */
    static final double VOICE_FLOOR = 0.08;

    /** 判「有人在唱」的相对起伏门槛（标准差 ÷ 均值，0.4 秒窗）。 */
    static final double VOICE_MODULATION = 0.25;

    /** 起伏滑窗的半宽（格）：20 格 = 0.4 秒。 */
    static final int VOICE_WINDOW_HOPS = 20;

    /** 窗口内人声重叠到这个时长以内不罚（用户提的口径）。 */
    static final double VOICE_OVERLAP_FREE_SECS = 0.5;

    /** 窗口内人声重叠到这个时长时这一项归零。 */
    static final double VOICE_OVERLAP_WORST_SECS = 4.0;

    /**
     * 粗排里「两边都在唱」的惩罚权重：粗排只留 {@value #SHORTLIST} 对进决赛圈，
     * 看不见这一项就会把「人声不撞」的位置挤出去（与低频那一项同一个教训，
     * 见 {@link #LOW_END_COARSE_WEIGHT}）。用两侧活动掩码的前缀和，O(1) 查表。
     */
    static final double VOICE_COARSE_WEIGHT = 0.7;

    /**
     * <b>小节相位</b>（0..3 = 这一拍里的第几拍是「1」，{@code -1} = 没测出来）。
     *
     * <h3>为什么需要它</h3>
     *
     * <p>拍栅格只回答「哪里是拍」，而 DJ 接歌与乐句对齐讲的是<strong>小节</strong>：
     * 在「第 1 拍」上接、在第 8/16 小节线上接，听感与「随便挑一拍」完全不同。
     * 之前的结论（{@code docs/automix-modes.md} §8.0.6-补）是：想把窗口压到 Apple 那种
     * 6–8 秒，缺的正是这一层——纯频谱信号里 174 s 处的新颖度是整段<strong>最低</strong>的，
     * 它不是一个「谱边界」，而是一个小节/乐句地标。
     *
     * <h3>怎么测（不需要模型）</h3>
     *
     * <p>用**低频起音**：底鼓在小节里的分布从来不是均匀的——4/4 的流行/电子里，
     * 第 1 拍（与第 3 拍）上落鼓的概率明显更高。于是对 4 个相位各算一次
     * 「落在该相位上的拍，低频起音的平均强度」，取最大的那个；相位之间的对比度
     * 就是置信度（{@link #BAR_CONFIDENCE} 之下算「没测出来」，如实返回 -1，不猜）。
     *
     * <p>局限如实写在这里：<b>只假设 4/4</b>（3/4、6/8、自由拍会测错），而且它测的是
     * 「鼓落在哪一拍」，不是真正的下拍模型。所以它给的是**有上限的加分**
     * （{@link #BAR_WEIGHT}），不参与归一、也不做硬约束——与歌词、结构两项同一个机制。
     */
    static int barPhase(Envelope e) {
        if (!e.hasBeatGrid()) {
            return -1;
        }
        double periodSecs = e.tempo.periodFrames / (double) e.beatRate;
        if (periodSecs <= 0.0) {
            return -1;
        }
        // 从第一拍起把整首歌切成拍序，按序号 mod 4 归相位
        double[] beats = e.beatsIn(0.0, e.durationSecs);
        if (beats.length < 8) {
            return -1;      // 少于两小节：谈不上相位
        }
        double[] sum = new double[4];
        int[] count = new int[4];
        for (int i = 0; i < beats.length; i++) {
            int idx = e.indexAt(beats[i]);
            if (idx <= 0 || idx >= e.onset.length) {
                continue;
            }
            int phase = i % 4;
            sum[phase] += lowOnsetAt(e, idx);
            count[phase]++;
        }
        int best = -1;
        double bestMean = -1.0;
        double others = 0.0;
        int othersCount = 0;
        for (int p = 0; p < 4; p++) {
            if (count[p] == 0) {
                return -1;
            }
            double mean = sum[p] / count[p];
            if (mean > bestMean) {
                bestMean = mean;
                best = p;
            }
            others += sum[p];
            othersCount += count[p];
        }
        if (best < 0 || othersCount == 0) {
            return -1;
        }
        double rest = (others - sum[best]) / Math.max(1, othersCount - count[best]);
        double contrast = bestMean <= 1e-9 ? 0.0 : (bestMean - rest) / bestMean;
        return contrast >= BAR_CONFIDENCE ? best : -1;
    }

    /** 相位之间的相对对比度门槛：低于它就如实说「没测出来」，不猜。 */
    static final double BAR_CONFIDENCE = 0.12;

    /** 这一格附近的低频起音强度（取 ±1 格内的最大，容忍包络栅格与真实起音的错位）。 */
    private static double lowOnsetAt(Envelope e, int idx) {
        double best = 0.0;
        for (int i = Math.max(1, idx - 1); i <= Math.min(e.onset.length - 1, idx + 1); i++) {
            double v = e.onset[i];
            if (e.band != null && i < e.band.length) {
                v = Math.max(v, e.band[i][0]);
            }
            best = Math.max(best, v);
        }
        return best;
    }

    /**
     * 某个时刻是不是落在<strong>小节线</strong>上（±半拍内算）。
     *
     * <p>相位未知（{@link #barPhase} 返回 -1）时一律返回 0——「不知道」不给奖励，
     * 也绝不假装它对齐了。
     */
    static double barTerm(Envelope e, double secs) {
        int phase = barPhase(e);
        if (phase < 0) {
            return 0.0;
        }
        double periodSecs = e.tempo.periodFrames / (double) e.beatRate;
        if (periodSecs <= 0.0) {
            return 0.0;
        }
        // 拍序与 barPhase 用**同一个起点**（第一拍），所以这里的序号可以直接参与 mod 4
        double[] first = e.beatsIn(0.0, periodSecs * 2.0);
        if (first.length == 0) {
            return 0.0;
        }
        double beatsFromFirst = (secs - first[0]) / periodSecs;
        long index = Math.round(beatsFromFirst);
        if (Math.abs(beatsFromFirst - index) > 0.5) {
            return 0.0;      // 不在拍上：谈不上小节线
        }
        return Math.floorMod(index - phase, 4) == 0 ? 1.0 : 0.0;
    }

    /** 小节线的加分上限（与歌词项、结构项同一个机制：不参与归一，只往上加）。 */
    static final double BAR_WEIGHT = 0.05;

    /**
     * <b>色度新颖度</b>的加分上限——<b>全场最低的一档</b>（2026-10-02，用户要求）。
     *
     * <p>用户原话：「色度新颖度没参与打分吗？让它参与，作为比较的加分项，权值很低」。
     *
     * <p>取 {@value}（比结构项 0.06、小节线 0.05、歌词项 0.05 都低）是按实测定的：
     * 色度分段当**主结构项**用时输给能量法——skydive 尾声起点 183.9（能量法 179.7、
     * Apple 174.0）、春めく 进场起点 5.2（能量法 16.3、Apple 15.0），
     * 最终点位从 175.3/15.5 退到 180.0/6.0（参考判据当场红）。
     * 但它讲的是**和声/音色**的变化，与「电平跨过参考线」是两件事，所以留着有用：
     * 对齐时轻推一下，点位不会被它拉走。
     */
    static final double NOVELTY_WEIGHT = 0.02;

    /**
     * 色度新颖度的加分：出场点 / 进场点各自「离色度分段给的边界有多近」，取平均。
     *
     * <p>容差比主结构项宽（{@value #NOVELTY_TOLERANCE_SECS} 秒）：色度分段的边界本身
     * 就比能量法糊一档（那是它当主项用会输的原因），拿它去要求同一个精度就会满盘不给分。
     * 两侧都没有这个值（{@code < 0}，例如手搭的包络、或那次分段没算出来）时返回
     * {@code null}——**一分不加，也不假装对齐**。
     */
    static Double noveltyBonus(Envelope outro, Envelope intro, double cutSecs, double entrySecs) {
        double outBoundary = outro == null ? -1.0 : outro.structureOutroStartSecs;
        double inBoundary = intro == null ? -1.0 : intro.structureIntroStartSecs;
        if (outBoundary < 0.0 && inBoundary < 0.0) {
            return null;
        }
        double sum = 0.0;
        int n = 0;
        if (outBoundary >= 0.0) {
            sum += nearBoundary(cutSecs, outBoundary);
            n++;
        }
        if (inBoundary >= 0.0) {
            sum += nearBoundary(entrySecs, inBoundary);
            n++;
        }
        return Double.valueOf(n == 0 ? 0.0 : sum / n);
    }

    /** 点位离边界的接近程度：容差内 1.0，之外线性归零。 */
    private static double nearBoundary(double secs, double boundary) {
        double d = Math.abs(secs - boundary);
        return d >= NOVELTY_TOLERANCE_SECS ? 0.0 : 1.0 - d / NOVELTY_TOLERANCE_SECS;
    }

    /** 色度分段边界的容差（秒）：比主结构项的 6 秒更宽，因为它本来就糊一档。 */
    static final double NOVELTY_TOLERANCE_SECS = 8.0;

    /**
     * 两侧的小节线加分：出场点与进场点各自「是不是落在小节线上」，取平均。
     *
     * <p>两侧都测不出相位时返回 {@code null}（一分不加，也不假装）。
     */
    static Double barBonus(Envelope outro, Envelope intro, double cutSecs, double entrySecs) {
        int phaseOut = barPhase(outro);
        int phaseIn = barPhase(intro);
        if (phaseOut < 0 && phaseIn < 0) {
            return null;
        }
        double sum = 0.0;
        int n = 0;
        if (phaseOut >= 0) {
            sum += barTerm(outro, cutSecs);
            n++;
        }
        if (phaseIn >= 0) {
            sum += barTerm(intro, entrySecs);
            n++;
        }
        return Double.valueOf(n == 0 ? 0.0 : sum / n);
    }

    private static float[] lowBandColumn(Envelope e) {
        float[] out = new float[e.band.length];
        for (int i = 0; i < e.band.length; i++) {
            out[i] = e.band[i][0];
        }
        return out;
    }

    private static double clip01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 低频重叠分（1 = 从不重叠，0 = 全程一起轰）；缺频段数据 → 中性 0.5。 */
    private static double lowEndOverlap(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (a.band == null || b.band == null) {
            return 0.5;
        }
        double clash = 0.0;
        double energy = 0.0;
        for (int i = 0; i < hops; i++) {
            int ai = Math.min(aStart + i, a.band.length - 1);
            int bi = Math.min(bStart + i, b.band.length - 1);
            if (ai < 0 || bi < 0) {
                continue;
            }
            double x = a.band[ai][0];
            double y = b.band[bi][0];
            clash += Math.min(x, y);
            energy += Math.max(x, y);
        }
        if (energy <= 1e-6) {
            return 1.0;      // 两边都没有低频：谈不上打架
        }
        double overlap = clash / energy;      // 0 = 从不重叠，1 = 全程一起轰
        // **死区**（2026-10-02，与响度项 0.10、拍漂移 1.5 拍同一族做法）：
        // 实测依据——这一项一上线，参考曲的进场点就从 15.5 s（Apple 15.0）挪到 18.5 s，
        // 而**把权重从 0.9 降到 0.5、把粗排惩罚降到 0.25 都拉不回来**：说明问题不是量级，
        // 而是「0.3 与 0.5 的重叠」这种**听不太出来的差别**在参与决策、把点位带偏。
        // 真正要避免的是「两边低频几乎全程对着轰」（那样一定糊）；轻度的同时低频
        // （总有一边在让路）不该决定点位落在哪。
        if (overlap <= LOW_END_FREE_OVERLAP) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0,
                1.0 - (overlap - LOW_END_FREE_OVERLAP) / (1.0 - LOW_END_FREE_OVERLAP)));
    }

    /**
     * 低频反相分（1 = 同相或无关，0 = 全程反相抵消）；缺波形数据 → 中性 0.5。
     *
     * <p>只看 120 Hz 以下：那是底鼓与贝斯的基频所在，也是「反相会塌」看得见的地方。
     * 低通用两次一阶（级联）就够——判据只需要符号与量级，不需要陡峭的截止。
     */
    private static double lowEndPhase(Envelope a, Envelope b, int aStart, int bStart, int hops) {
        if (a.wave == null || b.wave == null || a.waveRate <= 0 || b.waveRate <= 0
                || a.waveRate != b.waveRate) {
            return 0.5;
        }
        int rate = a.waveRate;
        // 窗口在两侧波形里的起点（波形与包络同一时间轴：hop 秒一格）
        int aFrom = (int) Math.round(aStart * HOP_SECS * rate);
        int bFrom = (int) Math.round(bStart * HOP_SECS * rate);
        int span = (int) Math.round(hops * HOP_SECS * rate);
        if (aFrom < 0 || bFrom < 0 || span <= rate / 4
                || aFrom + span >= a.wave.length || bFrom + span >= b.wave.length) {
            return 0.5;
        }
        double alpha = 1.0 - Math.exp(-2.0 * Math.PI * LOW_END_PHASE_HZ / rate);
        double ax = 0.0;
        double ay = 0.0;
        double bx = 0.0;
        double by = 0.0;
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < span; i++) {
            ax += alpha * (a.wave[aFrom + i] - ax);
            ay += alpha * (ax - ay);
            bx += alpha * (b.wave[bFrom + i] - bx);
            by += alpha * (bx - by);
            dot += ay * by;
            na += ay * ay;
            nb += by * by;
        }
        double denom = Math.sqrt(na * nb);
        if (denom <= 1e-12) {
            return 0.5;      // 至少一侧低频是静音：谈不上相位
        }
        double r = dot / denom;
        // r ≥ 0（同相/无关）→ 满分；r ≤ −0.5（明显反相）→ 0 分；中间线性。
        return Math.max(0.0, Math.min(1.0, (r + LOW_END_PHASE_FLOOR) / LOW_END_PHASE_FLOOR));
    }

    /**
     * 低频重叠的<strong>死区</strong>：重叠比例在这个值以内算「听不出来」，给满分。
     *
     * <p>取值 0.45 是量出来的：不设死区时参考曲的进场点被这一项从 15.5 s 带到 18.5 s
     * （而参考点 Apple 是 15.0），设了之后回到 15.5 s；而判据里那两个极端
     * （两边都轰 ≈ 1.0 → 0 分、一边安静 ≈ 0.05 → 满分）两头都不受影响。
     */
    static final double LOW_END_FREE_OVERLAP = 0.45;

    /** 低频冲突里「重叠」那一半的占比，另一半是「反相」。 */
    static final double LOW_END_MUD_SHARE = 0.7;

    /** 低频冲突里「反相」那一半的占比。 */
    static final double LOW_END_PHASE_SHARE = 0.3;

    /** 低频反相的判定频率（Hz）：底鼓与贝斯的基频区。 */
    static final double LOW_END_PHASE_HZ = 120.0;

    /** 相关系数低于 −这个值时反相分归零（−0.5 已经是很明显的抵消了）。 */
    static final double LOW_END_PHASE_FLOOR = 0.5;

    /**
     * 「次优候选和它差在哪」这一句（{@code 没有次优} 时返回空串）。
     *
     * <p>输出两件事：次优的总分与点位，以及**差距最大的那一项**（weight × Δ项）。
     * 后者是回答「为什么不是它」的关键——只报总分差，用户仍然不知道是哪一项把它否掉的。
     */
    static String runnerUpNote(Score best, Score second) {
        if (best == null || second == null) {
            return "";
        }
        String[] names = {"波形重合", "淡化起伏", "频段互补", "响度接续", "走向", "拍漂移", "少切一点",
                "低频冲突", "人声重叠"};
        double[] mine = {best.overlap, best.ripple, best.bands, best.level, best.motion, best.beat,
                best.tail, best.lowEnd, best.vocal};
        double[] theirs = {second.overlap, second.ripple, second.bands, second.level,
                second.motion, second.beat, second.tail, second.lowEnd, second.vocal};
        int worst = -1;
        double worstGap = 0.0;
        for (int i = 0; i < names.length; i++) {
            double gap = WEIGHTS[i] / WEIGHT_SUM * (mine[i] - theirs[i]);
            if (gap > worstGap) {
                worstGap = gap;
                worst = i;
            }
        }
        boolean samePoint = Math.abs(best.cutSecs - second.cutSecs) < HOP_SECS
                && Math.abs(best.entrySecs - second.entrySecs) < HOP_SECS;
        boolean tied = best.total - second.total < 1e-4;
        if (samePoint && tied) {
            // 打分很平时会出现「次优就是同一个点、同分」——这时如实说出来，
            // 比编一句「差在哪一项」有用：它告诉用户这一次没有别的点位被否掉，
            // 结论是**平的**（这正是调参时最该知道的一件事）。
            return String.format(java.util.Locale.ROOT,
                    "次优与它同点位同分（%s，总分 %s）——这一次打分很平，没有别的点位被否掉",
                    fmt(best.cutSecs) + "s/" + fmt(best.entrySecs) + "s", f2(best.total));
        }
        String why = worst < 0 ? "各项都略差"
                : String.format(java.util.Locale.ROOT, "差在「%s」（%s vs %s）",
                        names[worst], f2(theirs[worst]), f2(mine[worst]));
        return String.format(java.util.Locale.ROOT,
                "次优 %s/%s（总分 %s），%s",
                fmt(second.cutSecs) + "s", fmt(second.entrySecs) + "s", f2(second.total), why);
    }

    /** 前 k 个候选的名额（{@link #planTop} 与 note 的次优那一句都靠它）。 */
    static final int TOP_K = 4;

    /**
     * 把 {@code score} 收进「前 {@value #TOP_K} 个互不相同的候选」里。
     *
     * <p>两条规则：① 与已收下的任何候选**点位差不到半拍**就视为同一个点、直接丢掉
     * （相邻 20 ms 的候选是同一个点，收进来只会把名额占满）；② 按 {@link #better} 排序，
     * 满了就顶掉最差的那个。
     */
    private static void keepRanked(java.util.List<Score> ranked, Score score) {
        for (int i = 0; i < ranked.size(); i++) {
            Score other = ranked.get(i);
            if (Math.abs(other.cutSecs - score.cutSecs) < BEAT_APART_SECS
                    && Math.abs(other.entrySecs - score.entrySecs) < BEAT_APART_SECS) {
                if (better(score, other)) {
                    ranked.set(i, score);
                    ranked.sort(new java.util.Comparator<Score>() {
                        @Override
                        public int compare(Score a, Score b) {
                            return better(a, b) ? -1 : (better(b, a) ? 1 : 0);
                        }
                    });
                }
                return;
            }
        }
        ranked.add(score);
        ranked.sort(new java.util.Comparator<Score>() {
            @Override
            public int compare(Score a, Score b) {
                return better(a, b) ? -1 : (better(b, a) ? 1 : 0);
            }
        });
        while (ranked.size() > TOP_K) {
            ranked.remove(ranked.size() - 1);
        }
    }

    /**
     * 一对点位的**累积拍漂移**（秒）：窗口里两边拍栅格相对滑开多少。
     *
     * <p>{@code W × |1/T_A − 1/T_B|}——两边速度差在窗口里「攒」出来的错位量，
     * 也就是窗口结束那一刻两个底鼓之间最大能差多少。这就是用户说的「皱在一起」的
     * 节奏成因（140 vs 145 BPM 在 4 秒窗口里攒出 ≈140 ms，12 秒窗口 ≈420 ms）。
     *
     * <p>无拍栅格、或速度未知时返回 0（「不知道」不参与判断）。
     */
    static double driftSecs(double bpmA, double bpmB, double windowSecs) {
        if (bpmA <= 0.0 || bpmB <= 0.0 || windowSecs <= 0.0) {
            return 0.0;
        }
        // **单位是秒**：`W × |1 − T_B/T_A|`。第一版写成 `W × |1/T_A − 1/T_B|` —— 那得到的是
        // **拍**（1/T 的单位是拍/秒），140/145 配 4 秒会算出 333 ms 而不是 140 ms。
        // 判据 `AutoCueDriftTest#theDriftFormulaMatchesHandCalculation` 当场把它照出来了。
        double periodA = 60.0 / bpmA;
        double periodB = 60.0 / bpmB;
        return windowSecs * Math.abs(1.0 - periodB / periodA);
    }

    /** 「算两个不同的候选」的最小点位间隔（秒）：半拍。 */
    static final double BEAT_APART_SECS = 0.25;

    /**
     * 「谁更好」的判定：**总分优先，打平时取更短的窗口**（2026-10-02 加）。
     *
     * <h3>为什么要这一条（量出来的）</h3>
     *
     * <p>九项打分**没有任何一项内在地偏好短窗口**：长窗口同样可以自由选进场点去避开起伏，
     * 所以 4 s 与 8 s 常常打出**完全相同**的分数（判据 {@code AutoCueJointTest} 打印的两行
     * 就是证据）。既然分数打平，胜者就取决于**枚举顺序**——而窗口候选是按升序给的，
     * 于是长的先被算、先占住 {@code best}，结论就偏长。
     *
     * <p>这与响度 0.10、拍漂移 1.5 拍、低频重叠 0.45 是同一族做法：**在一个听不出差别的
     * 区间里，用一个明确的、可解释的偏向代替「谁先来谁赢」**。
     *
     * <p><b>偏向从「更短」改成了「离最优点更近」</b>（2026-10-02）。原来偏向更短，理由是
     * 「短窗暴露更少、Apple 也落在 6~7 s」——但那是 P3 那一轮唯一的杠杆，它同时把窗口往
     * <b>下</b>推：真曲目上曾经塌到 1.0 s，而 1~3 秒不是融合、是带斜坡的硬切，用户的原话是
     * 「我可以很明了地听出来换歌曲了」。现在偏向 {@link #WINDOW_OPTIMUM_SECS}（6.5 s，
     * 取自 Apple 在同一对参考曲子上的 6~7 s）：<b>同一个意图（窗口落在 Apple 那个量级）
     * 做得更准，而且不会把窗口推向下限</b>。
     *
     * <p>注意它**只在死区内生效**：差 {@value #WINDOW_TIE_EPS} 以上仍然按总分走，
     * 绝不为了窗口好看而牺牲真正的接缝质量。
     */
    private static boolean better(Score a, Score b) {
        if (a.total > b.total + WINDOW_TIE_EPS) {
            return true;
        }
        if (b.total > a.total + WINDOW_TIE_EPS) {
            return false;
        }
        // 死区内：谁离最优点更近谁赢；一样近（或同一个窗口）时保持稳定——先到的赢。
        double da = Math.abs(a.windowSecs - WINDOW_OPTIMUM_SECS);
        double db = Math.abs(b.windowSecs - WINDOW_OPTIMUM_SECS);
        if (da != db) {
            return da < db;
        }
        // 同一个窗口长度（da == db）时，**按总分精确判**——原来这里直接 `a.windowSecs < b.windowSecs`
        // 对同一窗口恒为 false，于是"平局"退化成**枚举顺序**决定（先被算到的占住 best）。
        // 2026-10-06 实测踩到：参考对上两个候选的窗口完全相同、总分只差一点点，
        // 结果谁赢取决于漏斗顺序。死区是为了"不同窗口长度之间别被平均的副产物带偏"，
        // 它对同一个窗口没有意义，所以那里必须按 merit 判。
        if (a.total != b.total) {
            return a.total > b.total;
        }
        return a.windowSecs < b.windowSecs;
    }

    /**
     * 「窗口长度」比较时的死区：总分差在这个量级以内算打平，改看谁的窗口更接近最优点。
     *
     * <p>取值 0.01 是量出来的：**完全平的素材**下各档成绩是
     * {@code 4.0s→0.668  8.0s→0.672  12.0s→0.675}——**越长越高**，
     * 因为窗口越长，各分项的均值越接近全局均值、差异被平均掉（纯属平均的副产物，
     * 不是听感上的偏好）。0.01 正好盖住 4→12 s 这段 0.007 的抬升，
     * 而点位之间的真实分差通常是 0.02 上下，所以它**不会**让窗口长度去压过点位质量。
     */
    /**
     * ③ 分层取模开关（Apple 的 weighted modulo）。
     *
     * <p><b>默认关</b>（2026-11-20 实测后的结论）：我们的总分是七项连续加权求和，
     * 小数点后第三位就开始有意义；Apple 是"模板 tier + base_score"的离散分。
     * 把取模打开会动到已经钉住的行为——`AutoCuePlanTest` 里「调性对不上 ⇒ 倾向声波重合」
     * 那条直接被抽成了另一种接法（0.823 vs 0.822 被判成同层）。
     * ⇒ 机器保留（下面那段代码 + 日志），需要时把这个常量打开即可；
     *   默认仍然"取最大"，保证落点可复现、与既有测试一致。
     */
    /** ① 点位层闸门淘汰计数（每次准备清零并打一行日志）。 */
    private static long GATE_DROP_VOCAL = 0L;
    private static long GATE_DROP_INTENSITY = 0L;
    private static long GATE_DROP_GRID = 0L;

    private static volatile boolean TIERED_SELECTION = false;

    /** 宿主/设置页打开「分层取模」时调用（默认关，见上面那段说明）。 */
    public static void setTieredSelectionEnabled(boolean on) {
        TIERED_SELECTION = on;
    }

    public static boolean tieredSelectionEnabled() {
        return TIERED_SELECTION;
    }

    /**
     * ③ 同层判定：分数在最优值的这个比例以内算同一层。
     *
     * <p><b>为什么是 1e-6 而不是参考包的 2%</b>（2026-11-20 实测定下来的）：
     * 我们的总分是**连续实数**（七项加权，精确到 1e-3），而 Apple 的总分是
     * **模板 tier**（离散的 base_score + 因子），他们的"同层"是模板族的同层。
     * 把 2% 直接搬过来会吃掉我们刻意保留的**小差距判断**：
     * `AutoCuePlanTest` 钉着「调性对不上 ⇒ 应当倾向声波重合」（0.823 vs 0.822），
     * 2% 的容差会让 0.823 的"现有接法"被随机抽中 ⇒ 调性策略被颠覆 ✗。
     * ⇒ 这里只在**真平局**（差 &lt; 1e-6，且同一窗口）里做加权取模：
     *   语义与 Apple 一致（同层内取模），只是我们的"同层"更窄——因为分数更细。
     */
    static final double TIER_FRACTION = 0.02;

    static final double WINDOW_TIE_EPS = 0.01;

    private static String f2(double v) {
        return String.format(java.util.Locale.US, "%.2f", Double.valueOf(v));
    }

    // ==================================================================
    // 结构边界候选（nextgen）
    // ==================================================================
    //
    // 用户的要求（原话）：「候选点应该是**结构边界**——每个 8 小节 / 16 小节乐句边界、
    // 有人开始唱 / 唱完的位置，而不是几百上千个『主歌中间切一刀』的点」。
    //
    // 为什么必须换：候选集原来是一条**均匀 20 ms 网格**，一对 30 秒的范围里就有一千多个点，
    // 而打分在真素材上本来就很平（参考曲上七项读数挤在 0.5~1.0、候选之间的分差只有 0.02
    // 上下，见 docs/automix-modes.md）。**候选越密，"选哪个"就越由噪声决定**，选出来的点
    // 自然也常常落在乐句中间——那正是用户听到的"切得很随意"。结构边界把这个选择面砍到
    // "音乐上真的有事情发生"的那几处，每一处都是一个听得出理由的地方。

    /** 两个边界之间算"同一个点"的容差（秒）：这是一格 hop，听感上完全是同一处。 */
    public static final double STRUCTURE_SNAP_TOL_SECS = 0.02;

    /** 只收 **8 的倍数**的**小节**线（16 的倍数天然是 8 的倍数，所以这一条同时收了两级乐句）。 */
    static final int STRUCTURE_BAR_EVERY = 8;

    /** 能量骤变的判定窗（秒，单边）：相邻两窗的均值差。 */
    static final double STRUCTURE_JUMP_WIN_SECS = 0.5;

    /**
     * 能量骤变用的电平曲线：RMS 的<b>居中</b>滑动均值（半窗，秒）。
     *
     * <p>总窗 2 s 与结构地标那条 {@link #STRUCTURE_SMOOTH_SECS} 一样（同一把尺子），
     * 但这里必须是<b>居中</b>的（{@code smoothInPlace}），不能直接用 {@code smoothLevel}——
     * 那条是<b>尾随</b>平均，会把一个台阶的落点整体推后半个窗：2 s 窗下就是 <b>+1.0 s</b>，
     * 而 1 秒在 120 BPM 上正好是<b>两拍</b>，接缝会因此落在错的拍上。
     * 居中之后台阶的对称中心落在真值上（判据 {@code AutoCueStructureBoundaryTest} 里
     * RMS 0.2→0.9（真值 20.0 s）的夹具给的就是 20.00）。
     */
    static final double STRUCTURE_LEVEL_HALF_SECS = 1.0;

    /**
     * 能量骤变的阈值（相邻 {@value #STRUCTURE_JUMP_WIN_SECS} 秒窗的电平均值差）。
     *
     * <p>取值 {@value} 是<b>量出来的</b>，不是按 0~1 的直觉拍的（2026-10-05，一次性探针，
     * 用真的解码器扫本机两个参考素材 {@code ~/Music/skydive.mp3} 190 s 与
     * {@code ~/Music/春めく.mp3} 185 s，逐 hop 打窗差分布；探针只为定这个数、没有入库）：
     *
     * <pre>
     *   素材        p50      p90      p95      p99      max      过阈的簇（= 边界数）
     *   skydive   0.0089   0.0308   0.0441   0.1252   0.1841   0.05→6 个   0.08→2 个
     *   春めく     0.0075   0.0372   0.0566   0.0996   0.1496   0.05→16 个  0.08→7 个
     * </pre>
     *
     * <p>0.05 正好落在两首曲子的 p95（0.044 / 0.057）上，也就是「**全曲最陡的 5% 量级**
     * 的位置」：skydive 出 6 处（1.1 / 76.3 / 110.6 / 116.7 / 124.2 / 185.1 s）、
     * 春めく出 16 处（16.3 / 17.3 / 36.6 / 38.6 / 46.3 / 86.7 / 102.8 / 119.2 …）。
     * 加上 8 小节线与人声段起止之后，一个 30 秒范围里的候选是十几个（均匀网格是上千个）。
     * 不取 0.08 是因为两条曲子各只剩 2 / 7 处（skydive 那两处是 76 s 的落差与 185 s 的
     * 收尾淡出，等于这一路只剩收尾）；不取 0.03 是因为两首各出 16 / 26 处、落到 p90 之下，
     * 普通乐句起伏也会被当成段落边界收进来。
     *
     * <p><b>为什么不用 {@link #intensityCurve} 做这件事</b>（试过，实测更差）：
     * "劲头"曲线在真素材上只有 0.33~0.52 的跨度（p05 0.33 / p95 0.42~0.45 / max 0.50），
     * 全曲最陡的一处窗差也只有 0.066，阈值挤在 0.035~0.05 这么窄的一段里——
     * 0.04 时 skydive 只剩 2 处（还都在收尾）。更关键的是它对"段落电平变化"的响应形状是
     * 一个<b>鼓包</b>（动态那一项在骤变点两侧都变大），而窗差统计量在对称鼓包的<b>顶点处恰好是 0</b>、
     * 极大值落在<b>两翼</b>：夹具里 RMS 0.2→0.9（真值 20.0 s）会被它报成 <b>19.20 与 20.56</b>
     * 两条，离真值 0.8 s——比半拍还远。所以这一路改用"电平本身"（RMS）来量，
     * {@link #intensityCurve} 仍然在打分那边照原样用（那里的"嗨不嗨"是另一回事）。
     */
    static final float STRUCTURE_JUMP_DIFF = 0.05f;

    /** 结构边界少于这个数就退回 20 ms 均匀网格（见 {@link #candidates}）。 */
    static final int MIN_STRUCTURE_CANDIDATES = 4;

    /**
     * 结构边界之间允许的<b>最大空档</b>（秒，含范围两端到最近一条边界的距离）；
     * 超过它这段范围就算"没有结构信息"，{@link #candidates} 同样退回 20 ms 网格。
     *
     * <p>为什么在"少于 {@value #MIN_STRUCTURE_CANDIDATES} 个"之外还要这一条：光数个数
     * 挡不住一种实测出来的坏情况——<b>没有拍栅格</b>、只有零星几个地标的素材，
     * 个数够（≥ 4）却铺不开。判据
     * {@code AutoCueIntensityBridgeTest#theGateReplacesTheSeamThatDropped} 的夹具正是这种：
     * 全程响度一模一样、只有音色在 45 / 70 s 变过，VAD 于是在音色台阶上误报出两段 0.8 s 的
     * "人声"（起止 44.66 / 45.40 / 69.60 / 70.34），加上结构地标一共 5 个点，
     * 而范围是 [0, 140] s——<b>头一个空档就是 44.66 s</b>。挑点因此退化成"没有选择"：
     * 开了桥接之后引擎从"进在 B 的副歌起点（45 s）"退到"进在副歌末尾（69.6 s）"，
     * 点值强度差 0.43（刻度 {@code INTENSITY_SCALE} = 0.25），判据当场红。
     * 而这种素材本来就该走兜底那条路（用户的原话："没有节拍网格、没有歌词也没测出结构点"）。
     *
     * <p>取 {@value} s 的依据：只要拍栅格在，8 小节线就是每 8 小节一条，而 8 小节要到
     * <b>48 BPM</b> 才是 40 s（120 BPM 是 16 s、70 BPM 是 27 s）——48 BPM 已经低于
     * "还谈得上可信节拍"的素材范围，所以这条对<b>有拍栅格</b>的素材永不触发，
     * 只有"没有拍栅格"时才可能碰到。真素材上复核过：skydive 出场范围 [38.1, 184.3]
     * 的空档是 8.4 / 15.0 … / 2.9 s、春めく 进场范围 [0, 46.4] 是 1.5 / 16.0 / 16.0 / 12.9 s，
     * 都不触发（结构边界照旧当候选）。
     */
    static final double STRUCTURE_MAX_GAP_SECS = 40.0;

    /** 候选点侧名（{@link #candidates} 的 {@code side} 参数，也是日志里的名字）。 */
    static final String SIDE_OUTRO = "出场";
    static final String SIDE_INTRO = "进场";

    /**
     * 某一侧在 {@code [fromSecs, toSecs]} 里的<b>结构边界</b>（秒，升序、去重）。
     *
     * <p>四路来源，全部用仓库里已有的量（没有新算法、没有新模型）：
     *
     * <ol>
     *   <li><b>8 小节线</b>（{@link #STRUCTURE_BAR_EVERY}）：用 {@code env.tempo} 的
     *       周期与 {@link #barPhaseOf} 的相位，一小节按 <b>4/4</b> 算（= 4 拍）。相位
     *       优先走 {@code barPhaseOf}（它按"落在小节线上的起音之和最大"扫，是现成的求法），
     *       因为"BPM 只给周期、给不了相位"，而接歌必须落在小节线上。</li>
     *   <li><b>结构地标</b>：出场侧用 {@link #outroStartSecs}（A 的尾声起点）、进场侧用
     *       {@link #introStartSecs}（B 真正开始的位置）——与 {@code planCollect} 里那两个
     *       结构目标<b>是同一个量</b>（见 {@code STRUCTURE_WEIGHT}），所以结构项想奖励的那个点
     *       必然在候选集里。负数（测不出）就不加。另一侧的地标不加：它在乐理上属于另一侧的角色，
     *       加进来只会往候选集里塞无关点。</li>
     *   <li><b>人声活动段的起止</b>（{@link #vocalPhraseSpans}）：每一段的 start 与 end 都收。
     *       这是"有人开始唱 / 唱完"的位置，是这四个来源里<b>最硬的</b>一个边界——
     *       它不依赖节拍、不依赖能量剖面，纯粹是"人声在不在"。</li>
     *   <li><b>能量骤变点</b>：RMS 的<b>居中</b>滑动均值上（见 {@link #STRUCTURE_LEVEL_HALF_SECS}），
     *       "相邻 {@value #STRUCTURE_JUMP_WIN_SECS} 秒窗的均值差"连续超过
     *       {@link #STRUCTURE_JUMP_DIFF} 的每一段出一个点，位置取那一段的<b>窗差加权质心</b>
     *       （见 {@link #collectJumps}）。用"电平本身"而不是 {@link #intensityCurve}、
     *       以及"一段一个质心"而不是"局部极大"，两条都是实测逼出来的，
     *       依据写在 {@link #STRUCTURE_JUMP_DIFF} 的注释里。</li>
     * </ol>
     *
     * <p>容差 {@value #STRUCTURE_SNAP_TOL_SECS} 秒的用法有两处：非小节线来的点若落在
     * 某条小节线 ±容差 内就<b>吸附</b>到那条线上（同一条边界不该出现两个相差一格 hop 的副本）；
     * 排完序之后同一容差内的点只留最早的一个。
     *
     * <p>为什么不把 {@code env.structureOutroStartSecs} / {@code structureIntroStartSecs}
     * （色度新颖度那两个结构点）也收进来：它们已经在打分里有一份自己的低权加分
     * （见 {@code NOVELTY_WEIGHT}），再让它们占一个候选格等于同一件事算两遍；
     * 而且那两个值是"和声/音色变了"，与这里四条"听得出段落"的边界不是一把尺子。
     *
     * @param side 「出场」/「进场」，决定用哪一侧的结构地标
     * @return 升序去重的秒数；没有可信来源时返回<strong>空数组</strong>（由调用方兜底）
     */
    public static double[] structureBoundaries(Envelope env, double fromSecs, double toSecs,
                                               String side) {
        if (env == null || env.rms == null || env.rms.length == 0 || !(toSecs > fromSecs)) {
            return EMPTY_TIMES;
        }
        double lo = Math.max(0.0, fromSecs);
        double hi = Math.min(toSecs, env.durationSecs);
        if (!(hi > lo)) {
            return EMPTY_TIMES;
        }
        // 小节线先单独算出来：它既是候选，也是别的点的**吸附目标**。
        double[] bars = eightBarLines(env);
        java.util.ArrayList<Double> all = new java.util.ArrayList<Double>();
        for (int i = 0; i < bars.length; i++) {
            if (bars[i] >= lo && bars[i] <= hi) {
                all.add(Double.valueOf(bars[i]));
            }
        }
        // ② 结构地标
        double anchor = SIDE_INTRO.equals(side) ? introStartSecs(env) : outroStartSecs(env);
        addBoundary(all, anchor, bars, lo, hi);
        // ③ 人声活动段的起止
        LyricSpans spans = vocalPhraseSpans(env);
        if (spans != null) {
            for (int i = 0; i < spans.starts.length; i++) {
                addBoundary(all, spans.starts[i], bars, lo, hi);
            }
            if (spans.ends != null && spans.ends.length == spans.starts.length) {
                for (int i = 0; i < spans.ends.length; i++) {
                    addBoundary(all, spans.ends[i], bars, lo, hi);
                }
            }
        }
        // ④ 能量骤变点
        collectJumps(env, all, bars, lo, hi);
        if (all.isEmpty()) {
            return EMPTY_TIMES;
        }
        java.util.Collections.sort(all);
        double[] out = new double[Math.min(all.size(), MAX_CANDIDATES)];
        int n = 0;
        double last = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < all.size() && n < out.length; i++) {
            double v = all.get(i).doubleValue();
            if (n > 0 && v - last <= STRUCTURE_SNAP_TOL_SECS) {
                continue;                       // 同一个点：只留最早的那一个
            }
            out[n++] = v;
            last = v;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * 一条包络上的 <b>8 小节线</b>（秒，升序）；没有可信拍栅格、或测不出相位时返回空数组。
     *
     * <p>一小节按 <b>4/4</b> 算：{@code 一小节 = 4 拍 = 4 × tempo.periodFrames / beatRate}。
     * 用 {@code periodFrames / beatRate} 而不是 {@code 60 / bpm} 是为了跟
     * {@code snapToBeat} / {@code beatsIn} 用<b>同一把尺子</b>（同一个周期，不引入第二个
     * 舍入来源）。相位用 {@link #barPhaseOf}——它扫的是"起音之和最大"的相位，
     * 与 {@code outroVampSpan} 找循环段用的是同一套（那里也要求起点落在小节线上）。
     *
     * <p>返回的是<b>第 0、8、16……小节</b>的线（相位 + k×小节长）：16 小节的乐句边界天然
     * 在 8 的倍数里，所以一条规则同时满足"每个 8 小节 / 16 小节乐句边界"。
     */
    private static double[] eightBarLines(Envelope env) {
        if (!env.hasBeatGrid()) {
            return EMPTY_TIMES;                 // 白噪声也能被测出一个"速度"，见 hasBeatGrid
        }
        double beatSecs = env.tempo.periodFrames / (double) env.beatRate;
        if (!(beatSecs > 0.0)) {
            return EMPTY_TIMES;
        }
        double barSecs = beatSecs * 4.0;        // 4/4：一小节 4 拍
        double span = barSecs * STRUCTURE_BAR_EVERY;
        if (!(span > 0.0) || span > env.durationSecs) {
            return EMPTY_TIMES;                 // 整曲还不到 8 小节：没有乐句边界可言
        }
        // **用哪把尺子**：乐句够多且成簇 ⇒ 乐句相位 ✓（见 barPhaseResolved）。
        // 这一处是"结构候选点"的出生地 —— 网格错了，后面挑出来的点就都贴在错的小节线上 ✗。
        BarPhase ruler = barPhaseResolved(env, vocalPhraseSpans(env), barSecs);
        double phase = ruler.phase;
        if (Double.isNaN(phase)) {
            return EMPTY_TIMES;
        }
        // barPhaseOf 是"从 phase 起每 barSecs 一格"扫的，所以 phase 就是第 0 小节线
        int bars = (int) Math.floor((env.durationSecs - phase) / barSecs + 1e-6);
        if (bars < 0) {
            return EMPTY_TIMES;
        }
        int count = bars / STRUCTURE_BAR_EVERY + 1;
        double[] out = new double[Math.min(count, MAX_CANDIDATES)];
        int n = 0;
        for (int k = 0; k * STRUCTURE_BAR_EVERY <= bars && n < out.length; k++) {
            out[n++] = phase + (double) k * span;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * 收一个边界点：越界（{@code [lo, hi]} 之外）丢掉；落在某条小节线 ±{@value
     * #STRUCTURE_SNAP_TOL_SECS} 秒内就吸附到那条线上。
     *
     * <p>吸附的意义不只是"好看"：小节线是**精确**值（相位 + 整数倍小节长），
     * 而人声段的句尾、能量骤变的峰都带一格 hop 的量化误差。吸附之后同一条边界不会
     * 因为一格 hop 的差别而变成两个候选。
     */
    private static void addBoundary(java.util.List<Double> out, double secs, double[] bars,
                                    double lo, double hi) {
        if (Double.isNaN(secs) || secs < lo || secs > hi) {
            return;
        }
        double snapped = secs;
        for (int i = 0; i < bars.length; i++) {
            if (Math.abs(bars[i] - secs) <= STRUCTURE_SNAP_TOL_SECS) {
                snapped = bars[i];
                break;
            }
        }
        out.add(Double.valueOf(snapped));
    }

    /**
     * 能量骤变点（秒）：{@link #STRUCTURE_LEVEL_HALF_SECS} 秒居中平滑过的 RMS 上，
     * "相邻 {@value #STRUCTURE_JUMP_WIN_SECS} 秒窗的均值差"连续超过
     * {@link #STRUCTURE_JUMP_DIFF} 的每一段，出<b>一个</b>边界，位置取那一段的
     * <b>窗差加权质心</b>。
     *
     * <p>为什么用"电平本身"而不是 {@link #intensityCurve}：见 {@link #STRUCTURE_JUMP_DIFF}
     * 的长注释（一句话是："劲头"曲线对段落电平变化的响应是个对称鼓包，而窗差统计量在鼓包
     * 顶点处恰好是 0、极大落在两翼，夹具上离真值 0.8 s）。
     *
     * <p>为什么窗差取 0.5 秒：这一层要"骤变"不要"渐变"。窗太短（比如一格 20 ms）
     * 一次军鼓就过阈值；窗太长（2 s 以上）会把一段渐强平均掉、测不出台阶。
     * 0.5 秒 = {@code INTENSITY_SEAM_WIN_SECS}，与"接缝两侧各取多长算强度"同一把尺子。
     *
     * <p>为什么是"一段出一个质心"而不是"找局部极大"：窗差曲线上的局部极大落在<b>台阶的两翼</b>，
     * 不是台阶本身（两级台阶的窗差在中心是对称的、反而回落）。质心对这一类对称过渡给中心点，
     * 而且一段连续过阈的地方只产生一个边界（不会一处段落变化吐出两条相隔 1.3 s 的候选）。
     */
    private static void collectJumps(Envelope env, java.util.List<Double> out, double[] bars,
                                     double lo, double hi) {
        float[] level = env.rms.clone();
        smoothInPlace(level, Math.max(1, (int) Math.round(STRUCTURE_LEVEL_HALF_SECS / HOP_SECS)));
        int n = level.length;
        int half = Math.max(1, (int) Math.round(STRUCTURE_JUMP_WIN_SECS / HOP_SECS));
        if (n < 2 * half + 1) {
            return;
        }
        double[] prefix = prefixSums(level);
        for (int i = half; i + half <= n && out.size() < MAX_CANDIDATES; ) {
            if (diffAt(prefix, i, half) < STRUCTURE_JUMP_DIFF) {
                i++;
                continue;
            }
            // 一段连续过阈：整段只出一个边界。质心用窗差加权（不是格的算术平均），
            // 这样"陡的地方"把位置拽过去——一段里既有缓坡又有陡坎时，落点跟陡坎走。
            double weight = 0.0;
            double weighted = 0.0;
            while (i + half <= n) {
                double d = diffAt(prefix, i, half);
                if (d < STRUCTURE_JUMP_DIFF) {
                    break;
                }
                weight += d;
                weighted += d * i;
                i++;
            }
            if (weight <= 0.0) {
                continue;
            }
            addBoundary(out, env.secs((int) Math.round(weighted / weight)), bars, lo, hi);
        }
    }

    /** 第 {@code i} 格处「后 {@code half} 格均值 − 前 {@code half} 格均值」的绝对值。 */
    private static double diffAt(double[] prefix, int i, int half) {
        double before = rangeMean(prefix, i - half, i - 1);
        double after = rangeMean(prefix, i, i + half - 1);
        return Math.abs(after - before);
    }

    /**
     * 某一侧的候选点（秒，升序）：<b>默认走结构边界</b>，结构点不够时退回 20 ms 均匀网格。
     *
     * <p><b>为什么默认换成结构边界</b>（nextgen，用户要求）：见上面那一节的长注释。
     * 一句话是"从几百上千个乐句中间的点，换成几处真的有段落事件的地方"。
     *
     * <p><b>为什么必须留 20 ms 网格这条兜底</b>：结构边界要靠素材给出信息——没有可信拍栅格
     * （{@code MIN_BEAT_CONFIDENCE} 拦掉的那一类）、没有分频数据（{@code vocalPhraseSpans}
     * 返回 null）、能量又没有台阶时，四路来源可能一个点都吐不出来，或者只吐出一两个。
     * 那时候退回今天的均匀网格，行为与改这一行之前<b>逐位相同</b>（同一串
     * {@code env.secs(first + i)}、同一个累加顺序）——仓库那条"关掉特性 ⇒ 逐位相同"的纪律
     * 就是钉这个的（判据 {@code AutoCueStructureBoundaryTest} 里另有一条直接对拍）。
     *
     * <p>门槛有<b>两条</b>，都要过：个数 ≥ {@value #MIN_STRUCTURE_CANDIDATES} 个，
     * 而且最大空档 ≤ {@value #STRUCTURE_MAX_GAP_SECS} s。一二个点的候选集会让"挑点"退化成
     * "没有选择"（歌词门、强度闸门这些本来靠"换个点"来满足的规则会整片空转，
     * 而空转是这个包里最贵的故障形态，见 {@code planCollect} 里那段注释）；
     * 而"个数够、铺不开"是同一个毛病的另一种形状（实测：5 个点铺在 140 秒里，
     * 引擎从"进在副歌起点"退到"进在副歌末尾"），所以空档那一条单独钉着，
     * 依据与实测数据写在 {@link #STRUCTURE_MAX_GAP_SECS} 的注释里。
     *
     * <p><b>为什么不用拍点当候选</b>（2026-10-01 的实测，留作依据）：换一套格点等于换一套
     * 选择面，而打分本来就平——当年换成拍点直接让结论翻面（出场点从 174.5 s 变成 169.7 s）。
     * 所以这次换的是"候选从哪来"这件事本身（乐句边界），不是"格点粗细"；
     * "点位落在拍上"仍然由**先选后对拍**（{@code snapToBeat}）保证。
     *
     * @param side 「出场」/「进场」（见 {@link #SIDE_OUTRO} / {@link #SIDE_INTRO}）
     */
    static double[] candidates(Envelope env, double fromSecs, double toSecs, String side) {
        double[] structure = structureBoundaries(env, fromSecs, toSecs, side);
        if (structure.length >= MIN_STRUCTURE_CANDIDATES
                && maxGapSecs(structure, env, fromSecs, toSecs) <= STRUCTURE_MAX_GAP_SECS) {
            return structure;
        }
        // 兜底：今天的 20 ms 均匀网格（**逐位相同**，见上面那段注释）。
        int first = env.indexAt(fromSecs);
        int last = env.indexAt(toSecs);
        if (last < first) {
            return EMPTY_TIMES;
        }
        int count = Math.min(last - first + 1, MAX_CANDIDATES);
        double[] times = new double[count];
        for (int i = 0; i < count; i++) {
            times[i] = env.secs(first + i);
        }
        return times;
    }

    /**
     * 一串候选点里最大的空档（秒）：相邻两点之间、以及范围两端到最近一条候选的距离。
     *
     * <p>范围先按包络真的覆盖到的那一段夹住（{@code candidates} 拿到的上界可能超过
     * 实际扫到的长度，见 {@code planCollect} 里 coverageCeil 那段）：否则"没扫到的那一截"
     * 会被算成一个巨大的空档，把所有素材都推回网格。
     */
    private static double maxGapSecs(double[] times, Envelope env, double fromSecs, double toSecs) {
        double lo = Math.max(0.0, fromSecs);
        double hi = Math.min(toSecs, env.durationSecs);
        if (!(hi > lo) || times.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double best = 0.0;
        double prev = lo;
        for (int i = 0; i < times.length; i++) {
            best = Math.max(best, times[i] - prev);
            prev = times[i];
        }
        return Math.max(best, hi - prev);
    }

    /**
     * 把点位挪到**最近的拍**上（没有栅格、或挪出合法区、或半拍内没有拍就原样返回）。
     *
     * @param env     那一侧的包络
     * @param secs    选出来的点位（秒）
     * @param low     合法下界（秒）
     * @param high    合法上界（秒）
     */
    /** 采样级相位对齐的搜索半宽（秒）：见 {@link #phaseAlignedEntry}。 */
    static final double PHASE_ALIGN_MAX_SECS = 0.050;

    /** 采样级相位对齐启用的最小窗口（秒）：更短的窗口样本不够，靠低频换手对齐就够。 */
    static final double PHASE_ALIGN_MIN_WINDOW_SECS = 4.0;

    /**
     * <b>采样级相位对齐</b>（2026-10-06 第一阶段工作二）：把 B 的进场点在
     * <b>±{@value #PHASE_ALIGN_MAX_SECS} s</b> 内平移，使它与 A 在出场点处的
     * <b>互相关最大</b>。
     *
     * <p>为什么需要：{@link #snapToBeat} 只把它放到**最近的拍**上，至于那一拍上两个鼓点
     * 还差多少毫秒，它不管。而用户报的正是「还是会出现对不上拍的问题」——那点差距在听感上
     * 就是"没对齐"。这里用扫描期就已经算好的低采样率波形（{@link Envelope#wave}，1000 Hz）
     * 做一次直接搜索：这是**硬算能做到的最高精度**（1 ms 一格），不需要任何模型。
     *
     * <p>归一化互相关（除以两段能量），所以它比的是"像不像"而不是"谁更响"。
     * 只在窗口 &gt; {@value #PHASE_ALIGN_MIN_WINDOW_SECS} s 时启用；任一侧没有波形、
     * 或算出来还不如不动时，**原样返回**入参（宁可不动，也不乱动）。
     */
    static double phaseAlignedEntry(Envelope outro, double cutSecs, Envelope intro,
                                    double entrySecs, double windowSecs) {
        if (outro == null || intro == null || outro.wave == null || intro.wave == null
                || outro.waveRate <= 0 || intro.waveRate <= 0) {
            return entrySecs;
        }
        if (!(windowSecs > PHASE_ALIGN_MIN_WINDOW_SECS)) {
            return entrySecs;
        }
        int rate = Math.min(outro.waveRate, intro.waveRate);
        int n = (int) Math.round(windowSecs * rate);
        if (n < rate / 4) {
            return entrySecs;
        }
        int aStart = (int) Math.round(cutSecs * outro.waveRate);
        int bStart = (int) Math.round(entrySecs * intro.waveRate);
        int maxLag = (int) Math.round(PHASE_ALIGN_MAX_SECS * intro.waveRate);
        double best = Double.NEGATIVE_INFINITY;
        int bestLag = 0;
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double dot = 0.0;
            double na = 0.0;
            double nb = 0.0;
            int used = 0;
            for (int i = 0; i < n; i++) {
                // A 那一侧按它自己的采样率取（两侧 rate 一般相同，这里仍各自换算，避免假定）
                int ai = aStart + i * outro.waveRate / rate;
                int bi = bStart + lag + i * intro.waveRate / rate;
                if (ai < 0 || ai >= outro.wave.length || bi < 0 || bi >= intro.wave.length) {
                    continue;
                }
                double va = outro.wave[ai];
                double vb = intro.wave[bi];
                dot += va * vb;
                na += va * va;
                nb += vb * vb;
                used++;
            }
            if (used < n / 2 || na <= 1e-12 || nb <= 1e-12) {
                continue;
            }
            double corr = dot / Math.sqrt(na * nb);
            if (corr > best) {
                best = corr;
                bestLag = lag;
            }
        }
        if (best == Double.NEGATIVE_INFINITY || bestLag == 0) {
            return entrySecs;
        }
        return entrySecs + bestLag / (double) intro.waveRate;
    }

    private static double snapToBeat(Envelope env, double secs, double low, double high) {
        if (env == null || !env.hasBeatGrid()) {
            return secs;
        }
        // ⚠️ **这一层的精度上限就是包络的时间栅格**（`env.beatRate` = 50 Hz ⇒ 20 ms）——
        // 吸附结果只能落在包络帧的边界上，不是音频帧上。用户 2026-10-07 的规格要求
        // 「每个乐句和节拍**帧级**对应」，实测参考对（skydive→春めく）接缝处仍有
        // **633.9 帧 ≈ 13.2 ms** 的拍相位差（探针 BeatAlignProbe），这一层再怎么调也到不了 0 ✗。
        //
        // 要真的到帧级，得在**音频上细化拍点**：以这里给出的拍时刻为中心，在 ±半个栅格
        // （±20 ms）内找该拍的起音峰（onset peak），把拍点定到样本级；然后再吸附。
        // 那件事没做之前，"帧级对应"这句话在这条路径上是**达不到**的——如实记在这里，
        // 免得下次又把它当成 bug 去调参数。
        double period = env.tempo.periodFrames / (double) Math.max(1, env.beatRate);
        if (!(period > 0.0)) {
            return secs;
        }
        // 直接走栅格，不再先物化一个数组（2026-10-03，性能）：语义与
        // 「`beatsIn(secs−period, secs+period)` 之后逐个筛」逐条相同——
        // 同一个候选集合、同一个升序、同样的越界筛除、同样的「严格小于才替换」。
        double bestSecs = secs;
        double bestDiff = Double.MAX_VALUE;
        long fromFrame = (long) Math.ceil((secs - period) * env.beatRate);
        long toFrame = (long) Math.floor((secs + period) * env.beatRate);
        if (toFrame > fromFrame) {
            long beat = env.tempo.nextBeatAfter(fromFrame);
            int guard = 0;
            while (beat <= toFrame && guard++ < MAX_CANDIDATES) {
                double candidate = beat / (double) env.beatRate;
                if (candidate >= low && candidate <= high) {
                    double diff = Math.abs(candidate - secs);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestSecs = candidate;
                    }
                }
                beat = env.tempo.nextBeatAfter(beat + 1L);
            }
        }
        return bestSecs;
    }

    /** 日志里那句「拍点对齐 / 退回网格」。 */
    private static String alignmentNote(Envelope outro, Envelope intro) {
        boolean o = outro.hasBeatGrid();
        boolean i = intro.hasBeatGrid();
        if (o && i) {
            return String.format(java.util.Locale.US, "拍点对齐 %.1f/%.1f BPM",
                    Float.valueOf(outro.tempo.bpm), Float.valueOf(intro.tempo.bpm));
        }
        if (i) {
            return String.format(java.util.Locale.US, "进场按 %.1f BPM 的拍点对齐（出场没测出节拍）",
                    Float.valueOf(intro.tempo.bpm));
        }
        if (o) {
            return String.format(java.util.Locale.US, "出场按 %.1f BPM 的拍点对齐（进场没测出节拍）",
                    Float.valueOf(outro.tempo.bpm));
        }
        return "两侧都没测出节拍 → 退回 20 ms 网格";
    }

    /** 粗排「走向」那一项的归一化尺度（每 hop 变化多少算明显）——只给 {@link #slope} 用。 */
    private static final double SLOPE_NORM = 6.0;

    /**
     * 包络在 {@code index} 处的斜率（左右各一个 hop 的中心差分，单位：每 hop 的比例变化）。
     *
     * <h3>为什么只有粗排这一项还留着 20 ms 这一格</h3>
     *
     * <p>精细九项里那一项已经换成段落尺度的 {@link #direction}（那才是"接缝那一刻 A 是在起
     * 还是在收"该用的尺度）。粗排这一处**换过、又退回来了**，理由是实测出来的：
     *
     * <p>20 ms 那一格的<strong>零代价带只有一格宽</strong>——它正好落在台阶的下一格；
     * 而段落尺度的零代价带有 ±一小节那么宽，于是"<b>跨在台阶上</b>"的点（窗口一半在响、
     * 一半在轻）也拿满分。而粗排的整窗连续性项（{@code |meanA − meanB|}）本来要挡的正是
     * 这种点——现在它被那两个 0.8 的局部分差（合计 1.6）盖过去了。实测代价（都是**质量**
     * 判据，不是点位判据，所以既不能改判据也不能装看不见）：
     *
     * <ul>
     *   <li>{@code AutoCueTest#neverCutsAtTheVeryEndAndKeepsTheTwoSidesLevelMatched}：
     *       出场 163.14 s / 进场 15.0 s（整窗均值差 0.05，两边各自在自己那一段里）
     *       → <b>179.76 s / 13.32 s</b>（两边都跨在自己那一段的台阶上，均值差 0.73）；</li>
     *   <li>{@code AutoCueTest#energyContinuityBeatsBeingLater}：出场 100.0 s →
     *       <b>99.5 s</b>（从"台阶之后"挪到"台阶之前"）。</li>
     * </ul>
     *
     * <p>所以：粗排那一项保持原样，**决定权交给精细九项**——参考对上的实测正是这样落地的
     * （见 {@link #COARSE_FINE_SCALE} 与 {@link #DIRECTION_SPAN_BARS}：进场 15.14 s
     * ＝Apple 的 15.0 由精细评分选中，而它原来被粗排挡在 553/870）。
     * 这也是这次改动里唯一一处"该一起改却没一起改"的地方，账记在这里。
     */
    private static double slope(Envelope env, int index) {
        int left = Math.max(0, index - 1);
        int right = Math.min(env.rms.length - 1, index + 1);
        if (right == left) {
            return 0.0;
        }
        return (env.rms[right] - env.rms[left]) / (right - left);
    }

    /**
     * <b>「走向」的判定尺度：左右各一小节</b>（2026-10-06 改，原来是一格 = 20 ms）。
     *
     * <h3>为什么必须换尺度（实测）</h3>
     *
     * <p>这一项回答的是「接缝那一刻 A 是在起还是在收」——A 还在往上冲的时候切出去，
     * 听感上就是「硬断在一句还没唱完的地方」。可它原来量的是
     * {@code rms[i+1] − rms[i−1]}（<b>±1 格 = 20 ms 的中心差分</b>），而那正好是**打击点**的
     * 尺度：一个底鼓落在候选点上，20 ms 差分立刻变成「在起」。
     *
     * <p>参考对 skydive → 春めく 上这件事量得很清楚（{@code AppleOutroChoiceProbe}）：
     * 音乐上正确的乐句线 {@code 181.4455 s}（8 小节线 + 尾声起点，Apple 取 181.1）**自己就是
     * 一个打击点**，20 ms 口径读 {@code +0.16/格} ⇒ 走向项拿 {@code 0.038}（满分 1），
     * 而它旁边 0.35 s 处的 {@code 181.10} 读 {@code +0.0087} ⇒ 拿 {@code 1.000}。
     * 总分差里这一项占了 <b>79%</b>（0.0497 / 0.0627）。B 侧同一个病灶：候选
     * {@code 15.14 s}（吸拍后 14.9983，与 Apple 的 15.0 差 1.7 ms）在 20 ms 口径下被读成
     * 「在收」（{@code −0.019 vs 旁边 0.519}），于是一个**逐点正确**的候选被同一项压掉。
     *
     * <p>换成一小节尺度之后，同一个点读 {@code −0.010}（在收）→ 满分；而「整段真的在往上冲」
     * 的地方照样被抓住（见 {@link #rising}）。span 取一小节而不是半小节，是因为被误判的
     * 那个点在 0.5 小节尺度上仍然读到 {@code +0.016}（部分保留打击点的偏置），
     * 一小节才把它抹平——而乐句本身就是小节级的单位。
     */
    static final double DIRECTION_SPAN_BARS = 1.0;

    /** 没有拍栅格时假定的一小节长度（秒）：120 BPM。 */
    static final double DIRECTION_FALLBACK_BAR_SECS = 2.0;

    /**
     * <b>「明显在起/在收」的相对差门槛。</b>
     *
     * <p>走向量是一个<strong>相对</strong>量——{@code (右均值 − 左均值) / (右均值 + 左均值)}——
     * 所以这里的常数与整曲电平无关。取 {@value}（= 窗内电平<strong>翻一倍</strong>，约 +6 dB）
     * 记满分：真素材上 |走向| 的中位数只有 0.02（p90 = 0.08~0.13、p95 = 0.12~0.23，
     * 见 {@code AutoCueDirectionTest} 的分位断言），也就是说「≈6 dB 的渐强/渐弱」
     * 大约就是这一段里最强的那 5%，拿它当满分刻度既不会让所有点都被重罚、
     * 也不会让「真的在冲」的点漏网。
     */
    static final double DIRECTION_NORM = 0.20;

    /** 日志那句「在起/在收/平稳」的死区（归一化走向的单位）：罚分不到这个比例就叫平稳。 */
    static final double DIRECTION_DESCRIBE_DEAD_ZONE = 0.15;

    /** 走向比较窗的半宽（秒）：见 {@link #DIRECTION_SPAN_BARS}。 */
    private static double directionSpanSecs(Envelope env) {
        double bar = env != null && env.tempo != null
                && env.tempo.bpm > 40f && env.tempo.bpm < 250f
                ? 4.0 * 60.0 / env.tempo.bpm
                : DIRECTION_FALLBACK_BAR_SECS;
        return DIRECTION_SPAN_BARS * bar;
    }

    /**
     * <b>段落尺度的走向</b>：候选点左右各 {@link #DIRECTION_SPAN_BARS} 小节的包络均值之相对差
     * （{@code (右 − 左) / (右 + 左)}，范围 −1..1；窗被曲首/曲尾截断时按实际格数取均值）。
     *
     * <p>用<strong>两段均值</strong>而不是拟合斜率：均值本身就是一次低通，一个落点上的
     * 打击点（20 ms 尺度）在一小节的均值里只占 1/94 的权重，抹得掉；而「整段在往上走」
     * 这种持续趋势会完整保留下来——这正是这一项要防的事（用户原话：
     * 「A 跳跃点附近十分激进带感」，不许从那种地方切出去）。
     */
    private static double direction(Envelope env, int index) {
        int n = Math.max(1, (int) Math.round(directionSpanSecs(env) / HOP_SECS));
        int lo = Math.max(0, index - n);
        int hi = Math.min(env.rms.length - 1, index + n);
        if (hi <= lo) {
            return 0.0;
        }
        double left = 0.0;
        double right = 0.0;
        int ln = 0;
        int rn = 0;
        for (int i = lo; i <= hi; i++) {
            if (i < index) {
                left += env.rms[i];
                ln++;
            } else if (i > index) {
                right += env.rms[i];
                rn++;
            }
        }
        if (ln == 0 || rn == 0) {
            return 0.0;
        }
        left /= ln;
        right /= rn;
        double sum = left + right;
        return sum <= 1e-9 ? 0.0 : (right - left) / sum;
    }

    /**
     * 「在起」的程度（0..1）：{@link #direction} 到 {@link #DIRECTION_NORM} 记满分。
     *
     * <p><b>粗排与精细评分共用这一个量</b>（粗排那一项写成 {@code 0.8 × rising}、
     * 精细那一项写成 {@code 1 − rising}）——两阶段两把尺子是这个包最忌讳的事：
     * 上一版粗排用「每格跌落速率」、精细用「每格上升速率」，量出来的候选集与打分口径
     * 本来就不一致。
     */
    private static double rising(Envelope env, int index) {
        return clip01(direction(env, index) / DIRECTION_NORM);
    }

    /** 「在收」的程度（0..1）：与 {@link #rising} 对称。 */
    private static double falling(Envelope env, int index) {
        return clip01(-direction(env, index) / DIRECTION_NORM);
    }

    /**
     * <b>把「只与点位有关」的那些加分折算进粗排代价时的尺度。</b>
     *
     * <p>粗排那几项本来就是**精细权重未归一**的版本：{@link #evaluate} 里走向是
     * {@code 0.8 × 走向 / 7.75}，而粗排的 {@code cutScore} 就是 {@code 0.8 × 在起程度}；
     * 「少切一点」在两处同是 0.35。也就是说同一个量在两边的比例恰好是 {@link #WEIGHT_SUM}。
     *
     * <p>而结构 / 小节 / 新颖这三项在精细评分里**不参与归一**（直接加在总分上，
     * 见 {@code Score} 的构造），所以它们要在粗排里表达同一个相对分量，就得乘上这个比例。
     * 取 {@value}（= {@code WEIGHT_SUM}）不是调出来的：它由两边各自的算式决定。
     *
     * <p><b>为什么不做成闸门</b>：这三项是**加分**不是否决，而且粗排只取"点位上就算得出来"
     * 的那半（进场侧的结构半项与窗口耦合，不进粗排），折算后的满额约
     * {@code (0.06×5×0.5 + 0.05 + 0.02) × 7.75 ≈ 1.7}，严格小于
     * {@link #COARSE_GATE_PENALTY}（10）——所以"强度拒不拒 / 歌词合不合格"那两道**硬**闸门
     * 仍然严格优先。
     */
    static final double COARSE_FINE_SCALE = WEIGHT_SUM;

    /**
     * 粗排里「结构 / 小节 / 新颖」的合规门槛：本曲能达到的最大加分的这个比例。
     *
     * <p>与 {@link #COARSE_GATE_PENALTY} 那两处同一个形状（"合规的严格优先、不合规的只在
     * 凑不满 {@value #SHORTLIST} 对时兜底"）。取**相对量**是因为这三项能拿到的上限随素材
     * 差很多（有尾声起点 + 有拍相位 + 有色度边界时出场半项能到 0.15；一个都读不出来时是 0）。
     */
    static final double COARSE_ALIGN_SHARE = 0.6;

    /**
     * 这一档"合规优先"生效所需的**最小**加分（即：本曲最好的那一对至少要拿到这么多）。
     *
     * <p>取 {@value}（= {@link #STRUCTURE_WEIGHT}，一整项结构奖励的量级）：小节线那一半项
     * 满额只有 0.025、色度新颖只有 0.01——**单独一条色度边界**就开门是危险的，
     * 实测踩过：{@code AutoCueIntensityWiringTest} 的合成夹具里 B 的"真正开始"是 0 s，
     * 于是"进场 0 s"那一族拿到 0.01 的加分（本曲最大加分也就是 0.01、门槛随之降到 0.006），
     * 副歌那一族全被 {@link #COARSE_GATE_PENALTY} 挡在决赛圈外——第二级再也找不到 B 的副歌。
     * 也就是说：**"这一档加分足不足够大到值得为它开门"本身就是一条判据**，
     * 而 0.06（一整项结构奖励）是"真的有一个结构目标"与"顺手蹭到一条边界"之间的分界。
     */
    static final double COARSE_ALIGN_MIN = STRUCTURE_WEIGHT;

    /**
     * <b>只与点位有关</b>的那部分「结构 / 小节 / 新颖」加分（粗排用）。
     *
     * <p>精细评分里这三项是 {@code STRUCTURE_WEIGHT × structureTerm + BAR_WEIGHT × barBonus
     * + NOVELTY_WEIGHT × noveltyBonus}，其中每一项都由"出场 / 进场"两半组成——这里只取
     * <b>点位上就能算出来的那半</b>：
     *
     * <ul>
     *   <li><b>结构</b>：{@code scale × 0.5 × proximity(结构点)}（进场侧传的是融合中点）；</li>
     *   <li><b>小节线</b>：{@code barShare × barTerm(点位)}；</li>
     *   <li><b>色度新颖</b>：{@code novShare × 离边界的接近度}。</li>
     * </ul>
     *
     * <p><b>「读不出来」的那一项按 0 计，不给中性的 0.5</b>：中性值对全部候选是同一个常数、
     * 对排序无影响，但它会把"不知道"混进"对齐得好"——那样门槛就失去了意义。
     *
     * @param pointSecs     小节线 / 新颖两项用的点位（精细评分量的就是点位本身）
     * @param structureSecs 结构那一项用的时刻（出场侧 = 出场点，进场侧 = 融合中点）
     */
    private static double coarsePointBonus(Envelope env, double pointSecs, double structureSecs,
                                           double structureTarget, double structureScale,
                                           double noveltyBoundary,
                                           double barShare, double noveltyShare) {
        double bonus = 0.0;
        if (structureTarget >= 0.0 && structureScale > 0.0) {
            bonus += STRUCTURE_WEIGHT * structureScale * 0.5
                    * proximity(structureSecs, structureTarget);
        }
        bonus += barShare * barTerm(env, pointSecs);
        if (noveltyBoundary >= 0.0) {
            bonus += noveltyShare * nearBoundary(pointSecs, noveltyBoundary);
        }
        return bonus;
    }

    /** 这一处是「在往下走」还是「在往上走」（日志用的一句实话）——与打分同一个量。 */
    private static String describe(Envelope env, int index) {
        double d = direction(env, index);
        if (d > DIRECTION_DESCRIBE_DEAD_ZONE * DIRECTION_NORM) {
            return "在起";
        }
        if (d < -DIRECTION_DESCRIBE_DEAD_ZONE * DIRECTION_NORM) {
            return "在收";
        }
        return "平稳";
    }

    /**
     * **拍速差过大 ⇒ 本次过渡应当降级**（2026-10-11 ✓ 标准 L1.2 ✓ 用户：「找根源修」✓）。
     *
     * <p>实测日志：拍点对齐 124.0 / 150.6 BPM ⇒ 差 **21%** ✗ 仍硬叠放 ✗
     * ⇒ 那种叠放必然糊 ✓ 不如**短交叉/硬切**✓
     * （降级也不劣于硬切 ✓ 正是标准里写明的回退条件 ✓）
     *
     * @param bpmA     出场曲拍速（未知传 ≤ 0 ✓）
     * @param bpmB     进场曲拍速（未知传 ≤ 0 ✓）
     * @param limitPct 允许的相对差（默认 6% = 0.06 ✓；标准 L1.2 合格线 4% ✓）
     * @return 差距超过限度 ⇒ true ⇒ 调用方走降级 ✓；任一拍速未知 ⇒ **false**（不误杀 ✗）
     */
    public static boolean shouldDowngradeForTempo(double bpmA, double bpmB, double limitPct) {
        if (!(bpmA > 0) || !(bpmB > 0)) return false;
        final double limit = limitPct > 0 ? limitPct : 0.06;
        final double gap = Math.abs(bpmA - bpmB) / Math.max((bpmA + bpmB) * 0.5, 1e-9);
        return gap > limit;
    }
}
