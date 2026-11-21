package dev.t1m3.qplayer.audio.mix;

/**
 * AI 模式的接法。
 *
 * <p>上游把这个 enum 定义在 {@code automix.rs} 里；这里单独成一个文件，是因为
 * {@code tuning} 与 {@code chooser} 都要用它，单独放能避免循环依赖。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::automix::MixStyle}。
 */
public enum MixStyle {

    /** 朴素等功率淡化。没有额外动作，是其余四种的对照基准。 */
    PLAIN_BLEND("朴素融合", "plainBlend", 8f, 4f),

    /** 在拍上硬切，只留 30 ms 斜坡消咔哒。 */
    BEAT_CUT("拍点剪切", "beatCut", 0f, 0.05f),

    /**
     * 高频先换、低频后换。
     *
     * <p>直接整段淡化时，两首歌的低频会叠在一起，听感是「低音糊成一团」。
     * 先把高频让过去、再把低频换掉，接缝会干净得多。
     */
    BASS_SWAP("低音交接", "bassSwap", 16f, 8f),

    /** 让出场曲的尾巴「骑」在进场曲上：出场侧衰减得更慢，进场侧进来得更晚。 */
    TAIL_RIDE("尾音骑行", "tailRide", 24f, 12f),

    /** 表现模式（build-up）：出场曲高通上扫并渐弱，进场曲低通打开并渐强。 */
    SHOW_MODE("表现模式", "buildUp", 32f, 16f),

    /**
     * <b>学习 EQ</b>（2026-10-06 第二阶段）：融合期对进场曲做三段 EQ，出场曲只做等功率淡出。
     *
     * <p>曲线来自 {@link RuleTeacherEq}（规则教师，默认）或 {@link MixEqMlp}（蒸馏出来的 MLP，
     * 权重内嵌、加载失败即退回教师）。三段分频用两级 {@code SplitLowHigh} 串联
     * （200 Hz → 2000 Hz，状态在构造函数里建、{@code reset} 里清），
     * 逐帧增益在 prepare 阶段就算好成线性增益表，render 只查表插值（不做 {@code Math.pow}）。
     *
     * <p>为什么它不是"又一个接法"而是重点：规格第二阶段的目标就是让这个接法在
     * 三选一偏好测试里被选为"最无感"。
     */
    LEARNED_EQ("学习EQ", "learnedEq", 8f, 4f),


    /**
     * <strong>自动</strong>：在 {@code prepare} 阶段按素材关系从上面五个里挑一个。
     *
     * <p>它不是一个「风格」，所以<strong>不在 {@link #ALL} 里</strong>——列进去会让人以为
     * 可以像别的接法那样去调它，而它其实没有自己的形状。判定在 {@link Chooser}，
     * 依据是速度差与调性相似度。
     */
    /**
     * <b>交叉淡化·长</b>：对齐参照模板（2 constant-power long fade-out short fade-in）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    CROSSFADE_LONG("交叉淡化·长", "crossfadeLong", 16f, 8f),
    /**
     * <b>交叉淡化·叠合</b>：对齐参照模板（3 overlap）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    CROSSFADE_OVERLAP("交叉淡化·叠合", "crossfadeOverlap", 8f, 4f),
    /**
     * <b>交叉淡化·尾音</b>：对齐参照模板（4 Ease-in Ring-out）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    CROSSFADE_RINGOUT("交叉淡化·尾音", "crossfadeRingout", 16f, 8f),
    /**
     * <b>交叉淡化·叠合+混响</b>：对齐参照模板（33 overlap + Reverb）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    CROSSFADE_OVERLAP_REVERB("交叉淡化·叠合+混响", "crossfadeOverlapReverbPendingFx", 8f, 4f),
    /**
     * <b>交叉淡化·尾音+混响</b>：对齐参照模板（44 Ease-in Ring-out + Reverb）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    CROSSFADE_RINGOUT_REVERB("交叉淡化·尾音+混响", "crossfadeRingoutReverbPendingFx", 16f, 8f),
    /**
     * <b>滤波·高到低</b>：对齐参照模板（8 BM - Filter high to low）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_HIGH_TO_LOW("滤波·高到低", "filterHighToLow", 16f, 8f),
    /**
     * <b>滤波·长高到低</b>：对齐参照模板（12 BM - Long filter high to low）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_LONG_HIGH_TO_LOW("滤波·长高到低", "filterLongHighToLow", 32f, 16f),
    /**
     * <b>滤波·短高到低</b>：对齐参照模板（5 BM - Short filter high to low）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_SHORT_HIGH_TO_LOW("滤波·短高到低", "filterShortHighToLow", 8f, 4f),
    /**
     * <b>滤波·展开</b>：对齐参照模板（9 BM - Filter expansion（他自动档实际选中的））。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_EXPANSION("滤波·展开", "filterExpansion", 16f, 8f),
    /**
     * <b>滤波·长出短进</b>：对齐参照模板（6 Beat-matched long fade-out short fade-in - Same Bar num）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_LONG_OUT_SHORT_IN("滤波·长出短进", "filterLongOutShortIn", 16f, 8f),
    /**
     * <b>滤波·动态进</b>：对齐参照模板（7 Beat-matched long fade-out dynamic fade-in）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_DYNAMIC_IN("滤波·动态进", "filterDynamicIn", 16f, 8f),
    /**
     * <b>滤波·长出短进+延迟</b>：对齐参照模板（10 … + delay）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_LONG_OUT_SHORT_IN_DELAY("滤波·长出短进+延迟", "filterLongOutShortInDelayPendingFx", 16f, 8f),
    /**
     * <b>滤波·长出短进+混响</b>：对齐参照模板（11 … + Reverb）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_LONG_OUT_SHORT_IN_REVERB("滤波·长出短进+混响", "filterLongOutShortInReverbPendingFx", 16f, 8f),
    /**
     * <b>滤波·柔+混响</b>：对齐参照模板（13 BM - Filter soft + Reverb）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    FILTER_SOFT_REVERB("滤波·柔+混响", "filterSoftReverbPendingFx", 16f, 8f),
    /**
     * <b>人声不撞</b>：对齐参照模板（14 No vocal clash one）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    NO_VOCAL_CLASH("人声不撞", "noVocalClash", 16f, 8f),
    /**
     * <b>嘻哈·延迟出</b>：对齐参照模板（15 Hiphop - Delay out（base 100））。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    HIPHOP_DELAY_OUT("嘻哈·延迟出", "hiphopDelayOutPendingFx", 16f, 8f),
    /**
     * <b>嘻哈·循环</b>：对齐参照模板（18 Hiphop - Looping（base 100））。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    HIPHOP_LOOPING("嘻哈·循环", "hiphopLooping", 8f, 4f),
    /**
     * <b>流行·延迟出</b>：对齐参照模板（16 Pop - Delay out（base 50））。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    POP_DELAY_OUT("流行·延迟出", "popDelayOutPendingFx", 16f, 8f),
    /**
     * <b>流行·混响</b>：对齐参照模板（20 Pop - Reverb（base 50））。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    POP_REVERB("流行·混响", "popReverbPendingFx", 16f, 8f),
    /**
     * <b>舞曲·任意调</b>：对齐参照模板（19 Dance - Any Key）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    DANCE_ANY_KEY("舞曲·任意调", "danceAnyKey", 16f, 8f),
    /**
     * <b>舞曲·重复器</b>：对齐参照模板（17 Dance - Repeater）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    DANCE_REPEATER("舞曲·重复器", "danceRepeater", 8f, 4f),
    /**
     * <b>无缝衔接</b>：对齐参照模板（0 Gapless）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    GAPLESS("无缝衔接", "gapless", 0f, 0.05f),
    /**
     * <b>拼接</b>：对齐参照模板（22 splice）。
     *
     * <p>渲染复用现有渲染器（见 AiMix 分派）—— 本档只声明"拍数/窗口 + 闸门"，
     * 不新写 DSP。做法见 docs/apple-automix-templates.md。
     */
    SPLICE("拼接", "splice", 0f, 0.05f),

    AUTO("自动", "auto", 8f, 4f);

    /** 上游的 {@code Default}（第一个变体）。 */
    public static final MixStyle DEFAULT = PLAIN_BLEND;

    /**
     * {@code AiMix} 能渲染的全部接法（不含 {@link #AUTO}）。
     *
     * <p>（历史：生成式曾经也不在这里，因为它不是一个「怎么叠两首歌」的接法，
     * 而是一层<strong>换掉融合期素材</strong>的包装（`GenerativeBridge`）——
     * <b>2026-10-01 生成式整套删除</b>，那一档与那个包装都不在了，这段留作背景。
     * 它自己不做混音，里面那层骨架是交叉淡化。放进这个数组的话，
     * {@code AiMix} 的 {@code render} 会在遇到它时抛「未知接法」。
     * 界面上照旧要列它：见 {@link #UI_ORDER}。
     */
    public static final MixStyle[] ALL = {
            // 参照那 23 条路由（目标④）：进 ALL ⇒ AiMixTest 的三条"逐档能渲染"
            // 判据会自动把每一条都跑一遍。
            CROSSFADE_LONG, CROSSFADE_OVERLAP, CROSSFADE_RINGOUT, CROSSFADE_OVERLAP_REVERB,
            CROSSFADE_RINGOUT_REVERB, FILTER_HIGH_TO_LOW, FILTER_LONG_HIGH_TO_LOW, FILTER_SHORT_HIGH_TO_LOW,
            FILTER_EXPANSION, FILTER_LONG_OUT_SHORT_IN, FILTER_DYNAMIC_IN, FILTER_LONG_OUT_SHORT_IN_DELAY,
            FILTER_LONG_OUT_SHORT_IN_REVERB, FILTER_SOFT_REVERB, NO_VOCAL_CLASH, HIPHOP_DELAY_OUT,
            HIPHOP_LOOPING, POP_DELAY_OUT, POP_REVERB, DANCE_ANY_KEY,
            DANCE_REPEATER, GAPLESS, SPLICE,
            PLAIN_BLEND, BEAT_CUT, BASS_SWAP, TAIL_RIDE, SHOW_MODE,
            // ⚠️ **参照那 23 条路由暂时不放进这里**（2026-10-07）：
            // 这个数组不只是"逐档能渲染"判据的名单 —— 它还**喂给自动档的方案比较**
            // （aiMix 的 comparePlans 分支）。把 23 条还没配闸门/参数的候选塞进去，
            // 比较就会选出不同的赢家：整链指纹当场从 ecfbc9e9… 变成 0b5685a2…（实测）。
            // 所以顺序应当是：先给每条路由**配齐闸门与参数**（目标第③步），再让它们进比较。
            // 在那之前它们只是"声明好了、能渲染、但没人选"（枚举 + 渲染分派已就位）。
    // **LEARNED_EQ 不在这个数组里**（2026-10-07）：`ALL` 是**自动档挑接法的候选集**
            // （见 {@code Chooser.choose}），把一个新档塞进去等于让它**悄悄接管自动档**——
            // 用户实测「自动 表现模式 学习EQ 全炸了」里，"自动"那一份就是这么来的
            // （判据 AutomixBitExactReferenceTest 的指纹当场变红，钉的就是这件事）。
            // 它仍然在 UI_ORDER 里、可以选择，只是不再参与自动挑档。
    };

    /**
     * 设置界面里的排列顺序（下标 0 = {@link #AUTO}）。
     *
     * <p><strong>存储的是这个顺序的下标，不是 {@link #ordinal()}</strong>——{@code AUTO} 在枚举里
     * 是最后一个（上游把它放在末尾，因为 {@code Default} 必须是 {@code PlainBlend}），
     * 而界面上「自动」要排第一。把两者混为一谈会让用户选了「拍点剪切」却得到别的接法。
     */
    public static final MixStyle[] UI_ORDER = {
            // **新档一律追加在末尾**：设置里存的是这个顺序的下标，插在中间会让老用户
            // 存着的下标指向另一档（判据 AutomixSwitchSettingsTest 守的就是这件事）。
            AUTO, PLAIN_BLEND, BEAT_CUT, BASS_SWAP, TAIL_RIDE, SHOW_MODE, LEARNED_EQ,
            // 参照那 23 条（目标④）：**追加在末尾**（上面的注释就是这么规定的）
            CROSSFADE_LONG, CROSSFADE_OVERLAP, CROSSFADE_RINGOUT, CROSSFADE_OVERLAP_REVERB,
            CROSSFADE_RINGOUT_REVERB, FILTER_HIGH_TO_LOW, FILTER_LONG_HIGH_TO_LOW, FILTER_SHORT_HIGH_TO_LOW,
            FILTER_EXPANSION, FILTER_LONG_OUT_SHORT_IN, FILTER_DYNAMIC_IN, FILTER_LONG_OUT_SHORT_IN_DELAY,
            FILTER_LONG_OUT_SHORT_IN_REVERB, FILTER_SOFT_REVERB, NO_VOCAL_CLASH, HIPHOP_DELAY_OUT,
            HIPHOP_LOOPING, POP_DELAY_OUT, POP_REVERB, DANCE_ANY_KEY,
            DANCE_REPEATER, GAPLESS, SPLICE
    };

    private final String label;
    private final String specName;
    private final float beats;
    private final float fallbackSecs;

    MixStyle(String label, String specName, float beats, float fallbackSecs) {
        this.label = label;
        this.specName = specName;
        this.beats = beats;
        this.fallbackSecs = fallbackSecs;
    }

    /** 界面显示名。 */
    public String label() {
        return label;
    }

    /** 规格名（与设计文档对照用）。 */
    public String specName() {
        return specName;
    }

    /**
     * 理想窗口长度（拍）。
     *
     * <p>只在对拍成功时才用得上。
     */
    public float beats() {
        return beats;
    }

    /** 估不出拍点时的退路（秒）。 */
    public float fallbackSecs() {
        return fallbackSecs;
    }

    /** 五种接法都会改写采样值——它们的存在意义就是改写。 */
    public boolean rewritesSamples() {
        return true;
    }

    /** 是否是需要先挑一次再用的「自动」。 */
    public boolean isAuto() {
        return this == AUTO;
    }

    /** 本接法在 {@link #UI_ORDER} 里的下标（设置里存的就是它）。 */
    public int uiIndex() {
        for (int i = 0; i < UI_ORDER.length; i++) {
            if (UI_ORDER[i] == this) {
                return i;
            }
        }
        return 0;
    }

    /** 由设置里存的下标取回接法；越界或负数一律退化成 {@link #AUTO}。 */
    public static MixStyle fromUiIndex(int index) {
        if (index < 0 || index >= UI_ORDER.length) {
            return AUTO;
        }
        final MixStyle picked = UI_ORDER[index];
        // **「人声不撞」已停用**（2026-10-09 ✓ 用户：「你就不能把这个模式删了 解决一切问题」✓）。
        // 为什么不直接从 UI_ORDER 里删 ✗：那样后面每一档的下标都会**前移一格** ✗
        // ⇒ 老用户设置里存的下标会选到**别的档** ✗（静默改听感 ✓）。
        // 所以这里保持下标不变 ✓，但**任何**来源（设置里存的 / 界面上点的）取到它 ⇒ 一律按 **自动** ✓
        // ⇒ 对用户等于"这个模式没了" ✓；手动也不可达 ✓；其余档的编号一个都没动 ✓。
        return picked == NO_VOCAL_CLASH ? AUTO : picked;
    }
}
