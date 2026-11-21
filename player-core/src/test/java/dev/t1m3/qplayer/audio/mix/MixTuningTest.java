package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 调参旋钮的对齐测试：对应 Sonalis {@code sonalis-mix/src/tuning.rs} 的 {@code mod tests}。
 *
 * <p>上游 5 条测试逐条对应到本类的前 5 个方法（名字按 camelCase 映射），
 * 断言强度保持一致（{@code assert_eq!} 浮点 → {@code assertEquals(..., 0f)}，
 * 即<strong>不允许任何差异</strong>；{@code assert!(..is_err())} → 见 {@link #assertRejected}）。
 *
 * <p>后面 8 条是<strong>补充</strong>测试（上游没有，属于 Java 侧的额外保险），
 * 每条都在自己的注释里写明「上游缺它为什么会成为风险」：
 * 错误文案（上游 {@code Display}）、默认行的逐字段格式（上游 {@code Display} 与
 * Java {@code toString} 不同）、键的归一化、21 个键的字段映射、浮点语法
 * （Java 比 Rust 宽的那几种写法，两条）、{@code f32 → f64} 的拓宽、
 * 以及复制语义（Rust {@code Copy} 对 Java 引用）。
 */
public class MixTuningTest {

    /**
     * 上游 {@code tuning.rs} 的默认行，按上游 {@code Display} 的格式逐字符写出。
     *
     * <p>Rust 对 {@code 1.0f32} 打印 {@code 1}（不带 {@code .0}），Java 的
     * {@code Float.toString} 打印 {@code 1.0}——所以这串期望值同时也是
     * {@link MixTuning#toLine()} 里那个去掉 {@code .0} 的处理的回归测试。
     */
    private static final String DEFAULT_LINE =
            "window_scale=1,max_window_fraction=0.699999988079071,crossover=200,split=0.5,together=0.5,"
                    + "cut_fade=0.03,cut_lead=0,tail_outro=2,tail_intro=1,loop_cost=0.5,short_in=0.5,delay_send=0.4,delay_wet=0.5,delay_beats=0.75,delay_feedback=0.45,reverb_send=0.35,reverb_wet=0.5,reverb_decay=1.8,reverb_damping=0.35,sweep_from=18000,sweep_to=400,sweep_q=0.707,hp_from=20,hp_to=4000,"
                    + "lp_from=300,lp_to=18000,outro_gain=0.5,intro_gain=0.6,clarity_min=0.28,"
                    + "match_min_score=0.72,min_improvement=0.1,max_shift=3,"
                    + "auto_tempo_conflict=0.1,auto_key_same=0.7,low_clash_gate=0.5,drop_gate=0.25,min_overlap_secs=1.5,learned_eq_min_window_secs=2,tail_long_db_per_sec=-3,head_calm_gate=0.25,"
                    + "width=0.5,level_match=1,low_duck=1,tempo_stretch=1";

    /**
     * 对应上游 {@code assert!(MixTuning::parse(spec).is_err())}。
     *
     * <p>用 try/catch 而不是 {@code @Test(expected = ...)}：一条测试里有多个断言时
     * 后者只能覆盖第一个，而这里的每一条都是独立的规格。
     */
    private static void assertRejected(String spec) {
        try {
            MixTuning t = MixTuning.parse(spec);
            fail("应当报错，却解析成了：" + spec + " → " + t.toLine());
        } catch (TuningException expected) {
            // 正常路径：越界/不认识的项当场报错
        }
    }

    /**
     * <strong>默认值就是当前已实现的听感。</strong>
     *
     * <p>这条是整套旋钮的前提：把常量收进结构体的那次改动<strong>不该改变任何声音</strong>。
     * 逐项与 {@code automix}/{@code harmonic} 里的常量对照，谁再改常量而忘了同步这里，
     * 就会被这条测试拦住。
     */
    @Test
    public void defaultsMatchTheShippedConstants() {
        MixTuning d = MixTuning.defaults();
        assertEquals(MixTuning.BASS_CROSSOVER_HZ, d.crossoverHz, 0f);
        assertEquals(MixTuning.CUT_FADE_SECS, d.cutFadeSecs, 0f);
        assertEquals(MixTuning.MAX_WINDOW_FRACTION, d.maxWindowFraction, 0.0);
        assertEquals(Harmonic.CLARITY_MIN, d.harmonyClarityMin, 0f);
        assertEquals(Harmonic.MATCH_MIN_SCORE, d.harmonyMatchMinScore, 0f);
        assertEquals(Harmonic.MIN_IMPROVEMENT, d.harmonyMinImprovement, 0f);
        assertEquals(Harmonic.MAX_SHIFT, d.harmonyMaxShift);
        assertEquals("缩放默认必须是 1，否则等于悄悄改了规格", 1.0f, d.windowScale, 0f);
        assertEquals("切点默认仍在窗口末尾", 0.0f, d.cutLeadSecs, 0f);
        assertEquals(MixStyle.SHOW_MODE.beats(), d.beatsFor(MixStyle.SHOW_MODE), 0f);
    }

    /** 别名、接法名前缀、以及三种分隔符都要认；没提到的项保持默认。 */
    @Test
    public void parsesAliasesAndStylePrefixes() {
        MixTuning t = MixTuning.parse("bassswap.crossover=250, showmode.hp_to=6000");
        assertEquals(250.0f, t.crossoverHz, 0f);
        assertEquals(6000.0f, t.showHpTo, 0f);
        assertEquals("没提到的项保持默认", MixTuning.defaults().cutFadeSecs, t.cutFadeSecs, 0f);

        // 分隔符：逗号、分号、空白都行
        MixTuning t2 = MixTuning.parse("crossover=300; cut_fade=0.05 split=0.6");
        assertEquals(300.0f, t2.crossoverHz, 0f);
        assertEquals(0.05f, t2.cutFadeSecs, 0f);
        assertEquals(0.6f, t2.bassSwapSplit, 0f);
    }

    /**
     * <strong>认不出的键必须报错。</strong>
     *
     * <p>静默忽略会让用户以为改了一个值、实际没改，而整个盲听循环都建立在这个
     * 假假设上——它不会有任何症状，只是结论全错。
     */
    @Test
    public void unknownKeysAreRejectedLoudly() {
        try {
            MixTuning.parse("crossover=250,bass_swap_splot=0.6");
            fail("认不出的键必须报错");
        } catch (TuningException e) {
            assertEquals("bass_swap_splot", e.key);
            assertTrue("why 应包含「没有这个调参项」，实际：" + e.why,
                    e.why.contains("没有这个调参项"));
        }

        // 值不是数也要报错，而不是悄悄用默认值
        try {
            MixTuning.parse("crossover=低");
            fail("值不是数必须报错");
        } catch (TuningException e) {
            assertEquals("crossover", e.key);
        }
    }

    /** 越界的值当场报错，别让它变成「听起来坏了」的素材。 */
    @Test
    public void outOfRangeValuesAreRejected() {
        assertRejected("crossover=-1");
        assertRejected("window_scale=0");
        assertRejected("cut_fade=0");
        assertRejected("split=1");
        // 终点低于起点应报错
        assertRejected("hp_to=10,hp_from=100");
        assertRejected("max_shift=99");
    }

    /** {@code toLine} 与 {@code parse} 必须互为逆运算——渲染出来的素材要能一行复现。 */
    @Test
    public void toLineRoundTrips() {
        MixTuning t = MixTuning.parse("crossover=250,cut_fade=0.05,tail_intro=0.5,hp_to=6000,"
                + "width=0.5,level_match=0.25,low_duck=0.75");
        MixTuning back = MixTuning.parse(t.toLine());
        assertEquals("自己产出的行必须能被自己解析", t, back);
        assertEquals(MixTuning.defaults(), MixTuning.parse(MixTuning.defaults().toLine()));
    }

    // ------------------------------------------------------------------
    // 以下为补充测试（上游没有对应条目）
    // ------------------------------------------------------------------

    /**
     * 补充：异常消息必须等于上游 {@code TuningError} 的 {@code Display} 文案
     * {@code "调参项 {key} 不合法：{why}"}。
     *
     * <p>上游没有测试盯着这条文案，但它是<strong>调参的人唯一看得到的一行</strong>
     * （命令行报错、日志都直接打它），改一个字都是可见行为变化。
     */
    @Test
    public void errorMessageMatchesUpstreamDisplay() {
        try {
            MixTuning.parse("crossover=250,bass_swap_splot=0.6");
            fail("认不出的键必须报错");
        } catch (TuningException e) {
            assertEquals("调参项 bass_swap_splot 不合法：没有这个调参项", e.getMessage());
            assertEquals("bass_swap_splot", e.key);
            assertEquals("没有这个调参项", e.why);
        }

        try {
            MixTuning.parse("crossover=-1");
            fail("越界必须报错");
        } catch (TuningException e) {
            assertEquals("调参项 crossover 不合法：应当在 (0, 20000) Hz 之间", e.getMessage());
        }

        // 同时越界多项时，报的是 check() 里靠前的那一项（顺序与上游逐条一致）
        try {
            MixTuning.parse("crossover=-1,window_scale=0");
            fail("越界必须报错");
        } catch (TuningException e) {
            assertEquals("window_scale", e.key);
        }
    }

    /**
     * 补充：默认那一行必须与上游逐字符一致，且覆盖全部 21 个字段。
     *
     * <p>上游测试只对了默认值里的 7 项（{@code crossover}/{@code cut_fade}/
     * {@code max_window_fraction}/{@code harmony_*}），其余 14 项没有任何断言；
     * 而这一行同时钉住「每个字段的值」和「{@code Display} 的格式」。
     */
    @Test
    public void defaultLineIsExactAndCoversEveryField() {
        MixTuning d = MixTuning.defaults();
        assertEquals(DEFAULT_LINE, d.toLine());
        assertEquals("44 个字段一个不少（25 + Chooser 六个 + 滤波扫三个 + 混响四个 + 延迟四个 + 长出短进一个 + 确认重复一个）", 44, d.toLine().split(",", -1).length);
        assertEquals("默认行必须能被自己解析回来", d, MixTuning.parse(DEFAULT_LINE));

        // Debug 与 Display 不要混：上游 Debug 打印 1.0，Display 打印 1。
        // **2026-10-06**：上限从 0.5 改成 0.7 之后，Debug 的 double 打印是
        // {@code 0.699999988079071}——0.5 在二进制里精确、0.7 不是，所以这一行必须写
        // 完整形。这不是迁就：调参台能存的数就是 f32，"导出精度"只有一个定义。
        assertTrue("toString 应镜像 derive(Debug)：" + d,
                d.toString().startsWith(
                        "MixTuning { window_scale: 1.0, max_window_fraction: 0.699999988079071,"));
    }

    /**
     * 补充：键的归一化——去空白、ASCII 小写、{@code '-'}→{@code '_'}、
     * 取最后一个 {@code '.'} 之后的部分。
     *
     * <p>上游只测了「小写的前缀形式」，这几步（大写、连字符、最后一个点之后才是键）
     * 都没有测试，而它们决定了别人手写的配置能不能被认出来。
     */
    @Test
    public void keyNormalisationPrefersLastSegment() {
        MixTuning t = MixTuning.parse("BASS-SWAP.Crossover=250\nSHOWMODE.HP_TO=6000\tScale=2");
        assertEquals(250.0f, t.crossoverHz, 0f);
        assertEquals(6000.0f, t.showHpTo, 0f);
        assertEquals("Scale 是 window_scale 的别名", 2.0f, t.windowScale, 0f);

        // 认不出的键报的是**归一化后的短键**（上游也是），不是整段原文
        try {
            MixTuning.parse("bassswap.nope=1");
            fail("应当报错");
        } catch (TuningException e) {
            assertEquals("nope", e.key);
        }

        // '\r' 不是分隔符（上游的字符集合里也没有它），靠值那一步 trim 消化
        assertEquals(250.0f, MixTuning.parse("crossover=250\r\n").crossoverHz, 0f);
    }

    /**
     * 补充：Java 的 {@code Float.parseFloat} 比 Rust 的 {@code FromStr} 宽，
     * 宽出来的写法必须被拒——否则就是「用户以为改了、实际没改」的入口。
     */
    @Test(expected = TuningException.class)
    public void suffixFormIsRejected() {
        // "1f" 在 Java 里是合法的 f32 字面量尾缀，Rust 的 from_str 不认
        MixTuning.parse("crossover=1f");
    }

    /** 补充：与上一条同因，把其余几种「Java 认、Rust 不认」的写法钉住。 */
    @Test
    public void floatGrammarMatchesRust() {
        assertRejected("crossover=0x1p3"); // 十六进制浮点：Java 认、Rust 不认
        assertRejected("crossover=1_000"); // 下划线只在源码字面量里合法
        assertRejected("crossover=");      // 空值也是「不是数」
        assertRejected("crossover=1e");    // 指数缺数字
        assertRejected("crossover=25 0");  // 值里夹空格（上游会把它切成两段并报「应当是 键=值」）

        // 两边都认的写法要照常通过
        assertEquals(200.0f, MixTuning.parse("crossover=2e2").crossoverHz, 0f);
        assertEquals(0.05f, MixTuning.parse("cut_fade=5e-2").cutFadeSecs, 0f);

        // inf/nan：语法两边都认（Java 只认 "Infinity"/"NaN" 这两个写法，
        // 这里按 Rust 的语法实现），但 check() 会拒——无限的分频点没有意义
        assertRejected("crossover=inf");
        assertRejected("cut_lead=nan");
    }

    /**
     * 补充：{@code max_window=0.1} 必须<strong>先按 f32 解析再拓宽成 double</strong>
     * （上游是 {@code f()? as f64}）。
     *
     * <p>直接解析成 double 会得到 0.1，而上游得到 0.10000000149011612——
     * 这一项进的是窗口长度的乘法，属于「默认值只有一份」的那类数字，
     * 1e-8 的差也要按上游来。
     */
    @Test
    public void maxWindowFractionKeepsTheF32Widening() {
        MixTuning t = MixTuning.parse("max_window=0.1");
        assertEquals(0.10000000149011612, t.maxWindowFraction, 0.0);
        assertEquals(0.1f, (float) t.maxWindowFraction, 0f);
        assertTrue("渲染出来的也必须是 f32 拓宽后的值：" + t.toLine(),
                t.toLine().contains("max_window_fraction=0.10000000149011612"));
        assertEquals(t, MixTuning.parse(t.toLine()));
    }

    /**
     * 补充：21 个键<strong>逐一</strong>落在自己的字段上，短别名与长别名指向同一处。
     *
     * <p>上游只对 7 个字段做过「解析结果」的断言，其余 14 个键的映射<strong>没有任何测试</strong>；
     * 而 {@code parse} 里是一大串字符串分支（Rust 的 {@code match}、Java 的
     * {@code switch}），把 {@code match_min_score} 接到隔壁字段上照样能编译、照样能跑，
     * 症状只是「调了这个旋钮没反应」——盲听里最难查的那种。
     * 这条用「每项给一个互不相同的合法值 → 渲染 → 解析回来 → 整体相等」把所有映射钉住。
     */
    @Test
    public void everyKeyMapsToItsOwnField() {
        MixTuning t = MixTuning.defaults();
        // 21 个值互不相同（也是刻意的：若两个键互相接错，值必须不同才看得出来）
        t.windowScale = 2.0f;
        t.maxWindowFraction = 0.25;
        t.crossoverHz = 250.0f;
        t.bassSwapSplit = 0.6f;
        t.bassSwapTogether = 0.4f;
        t.cutFadeSecs = 0.05f;
        t.cutLeadSecs = 0.1f;
        t.tailRideOutroPower = 3.0f;
        t.tailRideIntroPower = 0.5f;
        t.showHpFrom = 30.0f;
        t.showHpTo = 6000.0f;
        t.showLpFrom = 400.0f;
        t.showLpTo = 16000.0f;
        t.showOutroGainPower = 0.7f;
        t.showIntroGainPower = 0.8f;
        t.harmonyClarityMin = 0.3f;
        t.harmonyMatchMinScore = 0.75f;
        t.harmonyMinImprovement = 0.2f;
        t.harmonyMaxShift = 5;
        t.autoTempoConflict = 0.06f;
        t.autoKeySame = 0.9f;
        t.check();

        assertEquals("短别名（to_line 用的那套）必须回到同一批字段", t, MixTuning.parse(t.toLine()));

        MixTuning longNames = MixTuning.parse("window_scale=2,max_window_fraction=0.25,"
                + "crossover_hz=250,bass_swap_split=0.6,bass_swap_together=0.4,cut_fade_secs=0.05,"
                + "cut_lead_secs=0.1,tail_ride_outro_power=3,tail_ride_intro_power=0.5,"
                + "show_hp_from=30,show_hp_to=6000,show_lp_from=400,show_lp_to=16000,"
                + "show_outro_gain_power=0.7,show_intro_gain_power=0.8,harmony_clarity_min=0.3,"
                + "harmony_match_min_score=0.75,harmony_min_improvement=0.2,harmony_max_shift=5,"
                + "auto_tempo_conflict=0.06,auto_key_same=0.9");
        assertEquals("长别名（字段全名）必须与短别名指向同一批字段", t, longNames);
    }

    /**
     * 补充：复制构造是深拷贝（Rust 的 {@code Copy} 在 Java 里没有对应物，
     * 传的是引用——共享一份可变的旋钮是本模块最容易踩的坑）。
     */
    @Test
    public void copyConstructorDoesNotAlias() {
        MixTuning a = MixTuning.defaults();
        MixTuning b = new MixTuning(a);
        assertEquals(a, b);

        b.crossoverHz = 250.0f;
        assertNotEquals("副本改了不能影响原件", a, b);
        assertEquals(MixTuning.BASS_CROSSOVER_HZ, a.crossoverHz, 0f);
        assertEquals("原件仍能渲染出自己的那一行", DEFAULT_LINE, a.toLine());
    }
}
