package dev.t1m3.qplayer.audio.mix;

import java.util.Locale;
import java.util.Objects;

/**
 * 自动选择接法。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>五个接法不是五种「风格」，它们各自适合<strong>不同的素材关系</strong>：
 *
 * <ul>
 *   <li>两首歌<strong>对得上</strong>（速度接近、调性相近）时，什么都不该做——
 *       纯淡化就是最好的，任何滤波只会削掉本来不需要遮的东西</li>
 *   <li>两首歌<strong>对不上</strong>时，振幅淡化会把节奏与相位的冲突<strong>全频段暴露</strong>出来；
 *       这时候有用的是把它们在<strong>频谱上分开</strong>，让两者互相遮蔽而不是互相打架</li>
 * </ul>
 *
 * <p>实测依据（40 秒素材，120 vs 124 BPM）：
 *
 * <table border="1">
 *   <caption>过渡窗口内的 RMS（dB）</caption>
 *   <tr><th>素材</th><th>plain 过渡内 RMS</th><th>showmode 过渡内 RMS</th></tr>
 *   <tr><td>不匹配（120 vs 124、A 小调 vs C 小调）</td><td>−17.9</td><td>−20.4</td></tr>
 *   <tr><td>匹配（同 120、同 A 小调、同相位）</td><td>−18.0</td><td>−20.5</td></tr>
 * </table>
 *
 * <p>关键在于：表现模式<strong>两组都掉 2 dB 以上</strong>，因为它在中间把两首歌同时削了
 * （出场曲高通到 4 kHz、进场曲低通在 300 Hz 附近打开）。
 * <strong>不匹配时这是赚的</strong>（遮住了冲突），<strong>匹配时这是纯亏</strong>（中段会瘪一下）。
 * 所以「哪个接法更好」根本不是风格问题，是<strong>素材关系</strong>问题——
 * 而那正是引擎已经算出来的东西。
 *
 * <h3>判定只依赖已经算好的量</h3>
 *
 * <p>速度来自 {@link Analysis}，调性来自 {@link Harmonic}，两者在 prepare 阶段本来就要跑。
 * 所以自动选择<strong>不引入任何新的分析成本</strong>，也不需要用户先听一遍。
 *
 * <h3>门槛全部来自 {@link MixTuning}，一个都不许写死在这里</h3>
 *
 * <p>{@code auto_tempo_conflict} 与 {@code auto_key_same} 是听感参数（{@code tuning.rs}
 * 里那两个字段的注释写了为什么）。判定要用它们就必须<strong>引用</strong>
 * {@link MixTuning} 的字段，不能另抄一份字面量——上游把常数集中进
 * {@code tuning.rs} 的整个立论就是「默认值必须仍然只有这一份」，
 * 抄一份出来等于把那个立论重新打断。
 *
 * <h3>算不出来就不猜</h3>
 *
 * <p>上游的 {@code choose} 三个入参里有两个是可选的（{@code Option<&Tempo>} /
 * {@code Option<&Match>}）：节奏分析与调性匹配都可能算不出结论。
 * 任一为 {@code null} 时退回朴素淡化——这跟 {@link Analysis} / {@link Harmonic}
 * 里「算不出来就别猜」是同一条纪律：{@code null} 在这里<strong>不是</strong>
 * 「相似度为 0」，而是「不知道」，拿一个默认相似度去顶替它就会在
 * 「本该什么都不做」的素材上自作主张地做滤波。
 *
 * <h3>与上游的类型差异（按 docs/audio-port.md 记录）</h3>
 *
 * <ul>
 *   <li>上游导出在 crate 根的 {@code AutoChoice} → {@link Chooser.AutoChoice}
 *       一个 {@code public static final class}（上游是 {@code pub struct} +
 *       {@code #[derive(Debug, Clone, PartialEq)]}）。字段与上游<strong>同名同序</strong>
 *       （{@code style}/{@code why}，本来就没有下划线要转驼峰），全 {@code final}，
 *       于是 {@code Clone} 天然满足，不需要复制构造。</li>
 *   <li>模块级自由函数 {@code choose} → {@link Chooser#choose}，{@code public static}。</li>
 *   <li>{@code Option<&Tempo>} / {@code Option<&Match>} → {@code null}（见上），
 *       这是本模块的语义核心，不是省事。</li>
 *   <li>上游 {@code let (Some(a), Some(b)) = (t_out, t_in) else { .. }} 是<strong>两个一起</strong>
 *       判空；这里写成 {@code tOut == null || tIn == null}（两者等价：任一为 {@code None}
 *       都走同一支），判定顺序与上游一致——<strong>先判拍点、再判速度差、最后才看调性</strong>。</li>
 *   <li>理由串里的 {@code format!("{:.1}")} 等 → {@link String#format(Locale, String, Object...)}
 *       并显式用 {@link Locale#ROOT}：默认区域会把小数点写成逗号，而这一行会进
 *       {@code plan.note}、被界面与日志读（与 {@link Harmonic.Match#describe()} /
 *       {@link Analysis.Tempo#describe()} 同一处理）。</li>
 *   <li>浮点按 f32 镜像：{@code mean} / {@code diff} / {@code diff * 100.0} 全在
 *       {@code float} 上算（Rust 里 {@code 100.0} 也推断成 {@code f32}），
 *       中间量走 double 会让 {@code diff >= auto_tempo_conflict} 这个<strong>硬比较</strong>
 *       在门槛边界上给出与上游不同的结论（选错接法比算错一个小数点严重得多）。</li>
 *   <li>格式化的取整方向有一处不可能消除的差异：Rust 的 {@code {:.1}} 是
 *       half-to-even，Java 的 {@code %.1f} 是 HALF_UP，在 {@code x.x5} 这种边界上
 *       可能差 1 个末位。<strong>这一行是给人看的理由，不参与任何判定</strong>
 *       （判定只看 {@code style}），所以不值得为它引入一套自己的浮点格式化。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::chooser}。
 */
/**
 * <b>参照的事实记录：Apple 自动混音怎么挑路由</b>（2026-10-07）。
 *
 * <p>下面的分析把"挑接法"这件事恢复到了可复述的程度。这里**只记事实**（形状、数值、清单），
 * **不搬代码**：那份档案自己也写着「独立近似出来的分析信号**不得**称作与原生等价」，
 * 我们的实现仍按自己的设计写，只是把"该长成什么样"对齐到这些事实上。
 *
 * <h3>打分核（档案标为 confirmed）</h3>
 * <pre>
 *   P = base × Π(factors)          ← 因子是**乘性**的：任一因子为 0 ⇒ 直接淘汰（硬闸）
 *   score = P + 0.001 × D          （仅当 P &gt; 0）
 *   D = 出场侧上界 − 进场侧上界     ← 位置项，只在原生区域对标签允许时参与，否则 0；可负、不夹紧
 * </pre>
 * 要点：**闸门决定一切，位置差只值 0.001 的破平手**。
 *
 * <h3>11 条路由与各自的因子</h3>
 * <pre>
 *   filtered-direct  (id 9)   strict_tempo_compatible · tonalities_compatible ·
 *                             matching_bar_count · vocal_overlap_incompatible
 *   filtered-direct  (id 8)   strict_tempo_compatible · incoming_beat_count · tempo_alias
 *   filtered-shifted (id 12)  strict_tempo_compatible · loose_tempo_compatible · matching_bar_count
 *   hiphop-direct    (id 18)  outgoing_beat_count · incoming_beat_count · tempo_alias
 *   hiphop-shifted   (id 15)  同上
 *   pop-scaled-shifted(16,20) outgoing_beat_count · incoming_beat_count · tempo_alias ·
 *                             outgoing_loudness_significant
 *   smart-crossfade  (3,33,4,44) incoming_loudness_relation_code · trailing_span_seconds
 *   dead-air (0) · fallback-crossfade (1) · soft-skip (5)   **无评分因子**
 * </pre>
 *
 * <h3>已确认的数值（可以直接用）</h3>
 * <ul>
 *   <li>{@code target_bar_count} 默认 <b>8 小节</b> ← 这条正好回答用户"窗口可以长一点"；</li>
 *   <li>Pop <b>base 50</b>；filtered-shifted id 9 <b>base 50</b>；dance-shifted 非 17 <b>base 75</b>；</li>
 *   <li>{@code tempo_alias ∈ {0.5, 1, 2}}；</li>
 *   <li>hiphop 有**两个独立因子**：入声折扣 <b>0.75</b> 与相对小节长度闸 <b>≥ 0.75</b>；</li>
 *   <li>{@code smart-crossfade} 生成**四个分数象限**（不是一个固定分）；</li>
 *   <li>缺失上游信号 ⇒ 该候选 <b>score = None</b>（如实留空，不猜）——与我们的纪律一致。</li>
 * </ul>
 *
 * <h3>与我们六档的对应</h3>
 * <pre>
 *   smart-crossfade / fallback-crossfade  ≈ 朴素融合（等功率交叉淡化，带门控）
 *   filtered-*                            ≈ 低音交接 / 学习EQ（滤波类）
 *   hiphop-* · dance-shifted · pop-*       ≈ 我们**缺**的曲风路由层（genreScores 已现成）
 *   dead-air · soft-skip                   ≈ 拍点剪切（没东西可叠时）
 * </pre>
 *
 * <p><b>下一步（要改的是架构，不是参数）</b>：把下面这条顺序判定链换成
 * "候选 × 乘性闸门 × 0.001 位置破平手" ✓，并把曲风接进来 ✓；
 * 第一条判据：参考对仍应落到 smart-crossfade 那一档（= 朴素融合）✓。
 */
public final class Chooser {

    private Chooser() {
    }

    /**
     * 选出来的接法，连同<strong>理由</strong>。
     *
     * <p>理由不是装饰：用户看到接法被自动换掉时必须能知道为什么，
     * 否则「自动」就是一个没法信任的黑箱。它会进 {@code plan.note}，
     * 界面与日志都读那一份。
     */
    public static final class AutoChoice {

        /** 选中的接法（永远不是 {@link MixStyle#AUTO}）。对应上游 {@code style}。 */
        public final MixStyle style;

        /** 一行可读理由。对应上游 {@code why}。 */
        public final String why;

        public AutoChoice(MixStyle style, String why) {
            this.style = style;
            this.why = why;
        }

        /**
         * 镜像上游 {@code #[derive(Debug)]} 的输出。
         *
         * <p>两处要说明的：
         *
         * <ul>
         *   <li>上游 {@code Debug} 打印的 {@code why} 是带引号的字符串（Rust 的
         *       {@code String} 就这样），这里也加上引号；理由串里不含引号、反斜杠或
         *       控制字符，所以不需要再做转义。</li>
         *   <li>{@code style} 这里打印 Java 枚举名（{@code SHOW_MODE}），而上游
         *       {@code Debug} 打印变体名（{@code ShowMode}）——{@link MixStyle}
         *       没有覆写 {@code toString}，且改它不在本次移植范围内。
         *       {@code why} 才是给人看的那一份，这条只用于调试。</li>
         * </ul>
         */
        @Override
        public String toString() {
            return "AutoChoice { style: " + style + ", why: \"" + why + "\" }";
        }

        /**
         * 对应上游 derive 的 {@code PartialEq}（{@code style} + {@code why} 全比）。
         *
         * <p>上游比的是 {@code String} 的<strong>内容</strong>（{@code String: PartialEq}
         * 逐字符比），不是引用——所以这里也必须 {@link String#equals(Object)}，
         * 不能写成 {@code ==}：理由串是运行时 {@code format!} 出来的，
         * 引用相等在正常路径上<strong>必然</strong>为假。
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AutoChoice)) {
                return false;
            }
            AutoChoice c = (AutoChoice) o;
            return style == c.style && Objects.equals(why, c.why);
        }

        @Override
        public int hashCode() {
            int h = style == null ? 0 : style.hashCode();
            return 31 * h + (why == null ? 0 : why.hashCode());
        }
    }

    /**
     * 按素材关系选接法。
     *
     * <p>两边的节奏分析与调性匹配都可为 {@code null}。<strong>算不出来就不猜</strong>：
     * 任一为 {@code null} 时退回朴素淡化——这跟本仓库其它地方「算不出来就别猜」
     * 是同一条纪律。
     *
     * <p>判定顺序（与上游逐行对应，不许重排）：<strong>拍点缺失 → 速度差 → 调性</strong>。
     *
     * @param tOut    出场曲的节奏结论，可 {@code null}（上游 {@code Option<&Tempo>}）
     * @param tIn     进场曲的节奏结论，可 {@code null}
     * @param harmony 调性匹配结论，可 {@code null}（上游 {@code Option<&Match>}）
     * @param tuning  调参旋钮，门槛从这里取
     */
    /**
     * <b>六档判定</b>（2026-10-07 重写）。
     *
     * <p>旧版只有两个出口（朴素融合 / 低音交接），其余四档永远进不了自动档。
     * 现在按"每一档在它的场合发挥作用"排成一条链，**顺序即优先级**：
     *
     * <pre>
     * ① 画像未知                        → 朴素融合（不猜）
     * ② 没有任何冲突                    → 朴素融合（不做多余的滤波）
     * ③ 无可叠窗口                      → 拍点剪切（没东西可叠时，干净地切最好）
     * ④ build-up 尾 + B 起点紧跟 drop   → 表现模式（扫频服务于音乐本身）
     * ⑤ 长尾 + B 留白进                 → 尾音骑行（尾巴值得留，B 那头容得下）
     * ⑥ 有冲突 + 可靠 drop + 低频冲突大 + 窗口够 → 学习EQ
     * ⑦ 其余有冲突的                    → 低音交接（固定计划，不需要拐点）
     * </pre>
     *
     * <p>为什么 ③④⑤ 排在 ⑥⑦ 前面：它们是"素材自己就长成那样"的强信号 ——
     * 没东西可叠、正在 build-up、尾巴又长又稳，这些比"冲突大不大"更该先被照顾。
     * 而 ⑥⑦ 是同一件事的两种做法（学习EQ 需要拐点做依据，低音交接不需要），
     * 所以由 {@code dropReliable} 一分为二。
     *
     * @param profile 素材画像；{@link MaterialProfile#UNKNOWN} 时整条链退化成旧行为
     */
    public static AutoChoice choose(Analysis.Tempo tOut, Analysis.Tempo tIn,
                                    Harmonic.Match harmony, MixTuning tuning,
                                    MaterialProfile profile, double windowSecs) {
        if (profile == null || !profile.known()) {
            return choose(tOut, tIn, harmony, tuning);      // 不猜：与旧行为逐字相同
        }
        if (tOut == null || tIn == null) {
            return new AutoChoice(MixStyle.PLAIN_BLEND,
                    "自动：拍点没判出来，退回朴素融合（不猜）");
        }
        float mean = maxF32((tOut.bpm + tIn.bpm) * 0.5f, 1.0f);
        float diff = Math.abs(tOut.bpm - tIn.bpm) / mean;
        boolean tempoConflict = diff >= tuning.autoTempoConflict;
        boolean keyConflict = harmony != null && harmony.plainScore < tuning.autoKeySame;
        boolean anyConflict = tempoConflict || keyConflict;
        boolean lowClash = profile.lowClash() >= tuning.lowClashGate;

        // ① 没有冲突：什么都不做（沿用旧语义："两边本来就合 → 不做多余的滤波"）
        //
        // ① 没有冲突：什么都不做（沿用旧语义："两边本来就合 → 不做多余的滤波"）
        //
        // **这一条暂时退回旧写法**（2026-10-07）：本来要按用户的 Apple 推理改成
        // "低频冲突不单独构成冲突"（即 `if (!anyConflict)`），但改完发现**参考对仍然
        // 落在学习EQ** —— 说明生产里算出来的冲突判定与探针量的不是同一件事
        // （探针量到调性 0.889、速度差 6.49%，两者都在门槛内；生产却当成了冲突）。
        // 在没把生产的真实判定看清楚之前不改判定：先只保留诊断改造（AiMix.autoProfile
        // 暴露真实画像），下一轮把生产的调性分数与画像一起读出来再动。
        // **暂时退回旧写法**（2026-10-07）：按用户的 Apple 推理，这里应当只看
        // `!anyConflict`（低频冲突不单独构成冲突）。但两处一起改（① + ⑥ 要求 anyConflict）
        // 之后，参考对**仍然**落在学习EQ ⇒ 说明**生产里算出来的 `anyConflict` 是 true**，
        // 与探针量到的数相反（探针：调性 0.889 / 速度差 6.49%，都合格）。
        //
        // 又一次"两处各算一遍同一个量"的分叉：探针用自己那两段 PCM 调 Harmonic.match，
        // 生产用它自己那两段 —— 不是同一个数。**先读到生产的真实理由文本（autoNote）
        // 再动判定**，不再盲改。下面 ⑥ 里那条 anyConflict 的注释保留了这次的关键洞察。
        if (!anyConflict) {
            return new AutoChoice(MixStyle.PLAIN_BLEND,
                    String.format(Locale.ROOT,
                            "自动：速度差 %.1f%%、调性 %.3f、低频冲突 %.2f 都没到门槛 → "
                                    + "朴素融合（不做多余的滤波）",
                            Float.valueOf(diff * 100.0f),
                            Float.valueOf(harmony == null ? 0f : harmony.plainScore),
                            Float.valueOf(profile.lowClash())));
        }
        // ② 没东西可叠：干净地切一刀比硬叠两首歌好
        if (profile.overlapSecs() < tuning.minOverlapSecs) {
            return new AutoChoice(MixStyle.BEAT_CUT,
                    String.format(Locale.ROOT,
                            "自动：可叠素材只有 %.1fs（不到 %.1fs）→ 拍点剪切"
                                    + "（没东西可叠时，在拍上干净地切一刀最不糊）",
                            Float.valueOf(profile.overlapSecs()),
                            Float.valueOf(tuning.minOverlapSecs)));
        }
        // ③ 正在 build-up，且 B 起点紧跟 drop：扫频是顺着音乐走，不是加特效
        if (profile.buildUp() && profile.dropReliable() >= tuning.dropGate) {
            return new AutoChoice(MixStyle.SHOW_MODE,
                    String.format(Locale.ROOT,
                            "自动：A 尾段在往上堆且 B 起点紧跟能量峰（显著度 %.2f）→ "
                                    + "表现模式（高通上扫 + 低通打开，扫频服务于音乐本身）",
                            Float.valueOf(profile.dropReliable())));
        }
        // ④ 长尾 + B 留白进：把 A 的尾巴留在 B 上面骑过去
        if (profile.tailDecayDbPerSec() >= tuning.tailLongDbPerSec
                && profile.headOnset() <= tuning.headCalmGate) {
            return new AutoChoice(MixStyle.TAIL_RIDE,
                    String.format(Locale.ROOT,
                            "自动：A 尾段衰减只有 %+.1f dB/s（长尾）而 B 头段起跳 %.2f（留白进）→ "
                                    + "尾音骑行（尾巴铺在 B 上，衰减得更慢）",
                            Float.valueOf(profile.tailDecayDbPerSec()),
                            Float.valueOf(profile.headOnset())));
        }
        // ⑤ 有冲突、有拐点、低频真的撞上、窗口也够 → 学习EQ
        // **⑥ 必须建立在"真的有冲突"之上**（2026-10-07）：上一轮只改了 ①（把 lowClash
        // 从"构成冲突"里去掉），结果参考对**照样**落在学习EQ —— 因为 ⑥ 从来只要求
        // "低频冲突 + 拐点"，**没要求 anyConflict** ⇒ 它直接命中 ⑥，压根到不了 ①。
        // 这条与 ① 是同一个判断的两半：低频冲突只有在**已经有速度差或调性不合**时
        // 才是加码（用户的 Apple 推理：参考对速度差 6.49%、调性 0.889 都合格，
        // Apple 在那对上是交叉淡化）。
        // ⚠️ **关键洞察（2026-10-07，别再忘）**：这一支原来**只要求"低频冲突 + 拐点"**，
        // 并**不要求** anyConflict ⇒ 它会在"速度与调性都合"的素材上直接命中，压根到不了
        // 分支 ①。上一轮只改 ①（把 lowClash 从"构成冲突"里去掉）之所以无效，就是因为
        // 这一点。（真正要改的时候：① 与 ⑥ 是同一个判断的两半，必须一起改。）
        if (anyConflict && lowClash && profile.dropReliable() >= tuning.dropGate
                && windowSecs >= tuning.learnedEqMinWindowSecs) {
            return new AutoChoice(MixStyle.LEARNED_EQ,
                    String.format(Locale.ROOT,
                            "自动：低频冲突 %.2f、drop 显著度 %.2f、窗口 %.1fs → "
                                    + "学习EQ（出场曲等功率淡出、进场曲按冲突与拐点切低频）"
                                    // **把判成冲突的依据一起印出来**（2026-10-07）：这一支要求
                                    // anyConflict，而它的依据（速度差/调性分数）原来没印 —— 诊断时
                                    // 只能靠猜（本会话为此白查两轮）。依据必须在理由里看得见。
                                    + "｜冲突依据：速度差 %.2f%%（门槛 %.0f%%）· 调性 %.3f（门槛 %.2f）"
                                    + " · 低频 %.2f（门槛 %.2f）",
                            Float.valueOf(profile.lowClash()),
                            Float.valueOf(profile.dropReliable()),
                            Float.valueOf((float) windowSecs),
                            Float.valueOf(diff * 100.0f),
                            Float.valueOf(tuning.autoTempoConflict * 100.0f),
                            Float.valueOf(harmony == null ? -1f : harmony.plainScore),
                            Float.valueOf(tuning.autoKeySame),
                            Float.valueOf(profile.lowClash()),
                            Float.valueOf(tuning.lowClashGate)));
        }
        // ⑥ 其余有冲突的：固定计划的低频换手（不需要拐点）
        return new AutoChoice(MixStyle.BASS_SWAP,
                String.format(Locale.ROOT,
                        "自动：有冲突（速度差 %.1f%%%s）但%s → 低音交接"
                                + "（两曲等响叠着、低频在平台期换手，不需要拐点）",
                        Float.valueOf(diff * 100.0f),
                        keyConflict ? "、调性不合" : "",
                        profile.dropReliable() < tuning.dropGate ? "没有站得住的 drop" : "窗口偏短"));
    }

    public static AutoChoice choose(Analysis.Tempo tOut, Analysis.Tempo tIn,
                                    Harmonic.Match harmony, MixTuning tuning) {
        // 上游：let (Some(a), Some(b)) = (t_out, t_in) else { .. }
        // 两个都是 None、或者只有一个是，走的都是这一支——上游的解构同理。
        if (tOut == null || tIn == null) {
            return new AutoChoice(
                    MixStyle.PLAIN_BLEND,
                    "自动：拍点没判出来，退回朴素融合（不猜）");
        }
        Analysis.Tempo a = tOut;
        Analysis.Tempo b = tIn;

        // 速度差用**相对值**：120 与 124 差 3.3%，60 与 62 也差 3.3%，
        // 两者听感上是同一件事（拍与拍之间漂移的比例相同）。
        //
        // `.max(1.0)` 是防除零：两个 BPM 都在 1 以下时相对差会炸掉。
        // Rust 的 f32::max 在一边是 NaN 时返回**另一个**操作数，Java 的 Math.max
        // 相反（把 NaN 透出去），所以这里手工对齐（见 maxF32）：正常素材上两者
        // 给出同一个平均值，只有 BPM 取到 ±∞ 这种构造值才会分叉。
        float mean = maxF32((a.bpm + b.bpm) * 0.5f, 1.0f);
        float diff = Math.abs(a.bpm - b.bpm) / mean;

        if (diff >= tuning.autoTempoConflict) {
            // 2026-10-02 改：原来这一支选的是**表现模式**（高通扫频 + 低通扫频），理由是
            // 「节奏对不上就用频谱分离让两者互相遮蔽」。它在技术上没错，但它<b>听得出</b>
            // ——那正是用户要的反面：「我要几乎无感、听不出换歌」。
            //
            // 实测（用户自己的参考曲 skydive → 春めく，速度差 6.5%）：AI 自动档就是走这一支，
            // 选到表现模式 + <b>15 秒</b>窗口。15 秒的扫频是任何人都立刻听得出来的效果，
            // 而且四手抗糊处理<b>根本不走这一档</b>（表现模式有自己的渲染循环）。
            //
            // 改成低音交接：两曲等响地叠着，只有低频在平台期换手——不扫频、不挖中段，
            // 同时顺手消掉「两套低频叠在一起」这个最大的糊源。窗口也回到 7.5 s 量级
            // （Apple Music 在同一个参考对上用的是 6~7 s）。
            //
            // 表现模式与拍点剪切都是「有意让人听出来」的手法，留给用户自己选档时用，
            // 不再由自动档替他决定。
            return new AutoChoice(
                    MixStyle.BASS_SWAP,
                    String.format(Locale.ROOT,
                            "自动：速度差 %.1f%%（%.1f → %.1f BPM）达到门槛 %.0f%%，"
                                    + "节奏对不上 → 低音交接（等响叠着、低频换手，"
                                    + "不扫频、不挖中段——自动档不做听得出效果的处理）",
                            diff * 100.0f,
                            a.bpm,
                            b.bpm,
                            tuning.autoTempoConflict * 100.0f));
        }

        // 速度对得上，那就看调性。相似度来自 chroma 余弦，1.0 = 同一个调。
        // 相似度是 f32（chroma 余弦），auto_key_same 也是 f32，这个 >= 是硬比较，
        // 所以上游的 f32 算路要一路镜像到这里（见类注释）。
        if (harmony != null) {
            if (harmony.plainScore >= tuning.autoKeySame) {
                return new AutoChoice(
                        MixStyle.PLAIN_BLEND,
                        String.format(Locale.ROOT,
                                "自动：速度差 %.1f%% 且调性相似度 %.3f 达到 %.2f，"
                                        + "两边本来就合 → 朴素融合（不做多余的滤波）",
                                diff * 100.0f,
                                harmony.plainScore,
                                tuning.autoKeySame));
            }
            return new AutoChoice(
                    MixStyle.BASS_SWAP,
                    String.format(Locale.ROOT,
                            "自动：速度对得上（差 %.1f%%）但调性相似度只有 %.3f → "
                                    + "低音交接：两曲等响地叠着、低频在平台期换手，"
                                    + "不让两套和声正面压过去",
                            diff * 100.0f,
                            harmony.plainScore));
        }
        return new AutoChoice(
                MixStyle.PLAIN_BLEND,
                String.format(Locale.ROOT,
                        "自动：速度对得上（差 %.1f%%）但没判出调性，退回朴素融合（不猜）",
                        diff * 100.0f));
    }

    /**
     * {@code f32::max} 的镜像。
     *
     * <p>Java 的 {@link Math#max(float, float)} 遇到 NaN 会把 NaN 透出去，Rust 的
     * {@code f32::max} 正相反：一个操作数是 NaN 时返回<strong>另一个</strong>
     * （两个都是 NaN 才返回 NaN）。上游那行 {@code ((a.bpm + b.bpm) * 0.5).max(1.0)}
     * 在正常素材上两种写法给出同一个平均值，只有 BPM 取到 ±∞ 时才分叉：
     * {@code (+∞) + (−∞) = NaN}，此时分子 {@code |a.bpm − b.bpm|} 仍是 +∞，
     * 于是 Rust 得到「无穷大的速度差 → 算冲突」，Java 的 {@code Math.max} 会得到
     * 「NaN → 不算冲突」而滑到调性那一支——<strong>选出来的接法都不一样</strong>。
     * {@link Analysis#estimateTempo} 里 {@code .max(1e-12)} 处已经为同一个理由
     * 手工对齐过一次，这里照做。
     */
    private static float maxF32(float a, float b) {
        if (Float.isNaN(a)) {
            return b;
        }
        if (Float.isNaN(b)) {
            return a;
        }
        return Math.max(a, b);
    }
}
