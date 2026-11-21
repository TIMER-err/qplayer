package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 自动选接法的对齐测试：对应 Sonalis {@code sonalis-mix/src/chooser.rs} 的 {@code mod tests}。
 *
 * <p>上游 5 条测试逐条对应到本类的前 5 个方法（名字按 camelCase 映射），
 * 断言强度保持一致（{@code assert_eq!} → {@code assertEquals}，失败时把 {@code why}
 * 当作消息打出来，与上游的 {@code "{}", c.why} 一样；{@code assert!(x.contains(..))}
 * → {@code assertTrue(...)} 并附上整条理由）。
 *
 * <p>后面 2 条是<strong>补充</strong>测试（上游没有，属于 Java 侧的额外保险），
 * 每条都在自己的注释里写明「上游缺它为什么会成为风险」：
 * {@code AutoChoice} 的值语义（上游是 derive 出来的 {@code PartialEq}），
 * 以及「速度差先于调性判定」这条顺序性质（上游只测了「速度合 + 调性缺失」，
 * 没有测「速度冲突 + 调性缺失」）。
 *
 * <p>注意 {@link MixTuning} 里那两个门槛（{@code auto_tempo_conflict} / {@code auto_key_same}）
 * 在上游是 {@code automix.rs} 的 {@code pub const} 寄放在 {@code tuning.rs} 的默认值，
 * 这里也是同一个寄放关系——测试里一律通过 {@link MixTuning#defaults()} 或
 * {@link MixTuning#parse(String)} 取值，不写字面量。
 */
public class ChooserTest {

    /**
     * 造一个节奏结论。对应上游测试里的 {@code fn tempo(bpm: f32) -> Tempo}。
     *
     * <p>周期按 48 kHz 换算（上游 {@code 48_000.0 * 60.0 / bpm}，三个 f32 字面量相乘，
     * 这里也全用 {@code float} 保持同一条算路）；相位 0、置信度 0.9——
     * {@link Chooser#choose} 只看 {@code bpm}，其余字段是为了让这个入参像一份真实结论。
     */
    private static Analysis.Tempo tempo(float bpm) {
        return new Analysis.Tempo(bpm, 48000.0f * 60.0f / bpm, 0.0f, 0.9f);
    }

    /**
     * 造一个匹配结论。对应上游测试里的 {@code fn scored(score: f32) -> Match}。
     *
     * <p>上游是把同一个 {@code score} 同时填给 {@code plain_score} 与 {@code best_score}、
     * 并且 {@code should_shift: false}——{@link Chooser#choose} 只读
     * {@code plainScore}（不转调时的相似度），这一点与上游一致。
     */
    private static Harmonic.Match scored(float score) {
        return new Harmonic.Match(0, score, score, false);
    }

    /**
     * 速度对不上 → <b>低音交接</b>（等响叠着、只有低频换手）。
     *
     * <p><b>2026-10-02 改的这一条</b>：原来选的是<b>表现模式</b>（高通扫频 + 低通扫频），
     * 理由是「节奏对不上就用频谱分离让两者互相遮蔽」。它在技术上没错，但用户明确要求
     * 「几乎无感、听不出换歌」——扫频是<b>听得出来</b>的处理，正是要避免的东西。
     *
     * <p>实测（用户自己的参考曲 skydive → 春めく，速度差 6.5%）：自动档就是走这一支，
     * 选到表现模式 + <b>15 秒</b>窗口，而抗糊的那几手又不走这一档——「一换歌就听得出来」
     * 就是这么来的。改选低音交接之后：窗口回到 7.5 s、出场点从「半路」（丢尾 45%）
     * 回到曲尾附近（丢尾 2.3 s），进场点 15.0 s 与 Apple Music 一致。
     *
     * <p>表现模式与拍点剪切都是「有意让人听出来」的手法，留给用户自己选档时用，
     * 不再由自动档替他决定。
     */
    @Test
    public void aTempoClashGetsBassSwapNotAnAudibleSweep() {
        MixTuning t = MixTuning.defaults();
        Chooser.AutoChoice c = Chooser.choose(
                // 速度差 3.5% → **10.3%**（2026-10-07）：门槛从 3% 提到 8% 之后，
                // 3.5% 已经不算冲突了，这几条判据测的仍是"速度差要先于调性被看见"，
                // 所以把素材的速度差抬到新门槛之上，判据的**意图**不变。
                tempo(112.0f), tempo(124.2f), scored(0.54f), t);
        assertEquals(c.why, MixStyle.BASS_SWAP, c.style);
        assertTrue("理由要说清是速度的问题：" + c.why, c.why.contains("速度差"));
        assertTrue("理由里也要写清「不做听得出效果的处理」：" + c.why,
                c.why.contains("低音交接"));
    }

    /**
     * 速度与调性都对得上 → <strong>什么都不做</strong>，纯淡化。
     *
     * <p>同样是实测：匹配素材上表现模式会把中段挖掉 2 dB，而那时没有东西需要遮。
     */
    @Test
    public void aMatchedPairGetsAPlainFade() {
        MixTuning t = MixTuning.defaults();
        Chooser.AutoChoice c = Chooser.choose(
                tempo(120.0f), tempo(120.4f), scored(1.0f), t);
        assertEquals(c.why, MixStyle.PLAIN_BLEND, c.style);
        assertTrue(c.why, c.why.contains("本来就合"));
    }

    /**
     * 速度对得上但调性打架 → 低音交接（平台形状）。
     *
     * <p><strong>这一支没有实测依据</strong>，是按机制推的：节奏对得上，问题在音高内容上，
     * 而平台让两首歌等响地叠着而不是互相压过去。要判断它是否真的更好，
     * 得在调性不合、速度相合的素材上盲听。
     */
    @Test
    public void aKeyClashWithAMatchingBeatGetsThePlateau() {
        MixTuning t = MixTuning.defaults();
        Chooser.AutoChoice c = Chooser.choose(
                tempo(120.0f), tempo(120.2f), scored(0.40f), t);
        assertEquals(c.why, MixStyle.BASS_SWAP, c.style);
    }

    /**
     * <strong>算不出来就不猜。</strong>
     *
     * <p>上游两段：一边缺拍点、一边缺调性，都退回朴素融合，而且理由要说清<strong>缺的是哪一样</strong>
     * ——「没判出」与「没判出调性」是给用户看的两个不同解释。
     */
    @Test
    public void nothingIsGuessedFromMissingAnalysis() {
        MixTuning t = MixTuning.defaults();

        Chooser.AutoChoice c = Chooser.choose(null, tempo(120.0f), scored(0.9f), t);
        assertEquals(c.why, MixStyle.PLAIN_BLEND, c.style);
        assertTrue(c.why, c.why.contains("没判出"));

        // 上游这里是 Some(tempo(120.0)) / Some(tempo(120.1)) / None：调性为 None
        c = Chooser.choose(tempo(120.0f), tempo(120.1f), null, t);
        assertEquals(c.why, MixStyle.PLAIN_BLEND, c.style);
        assertTrue(c.why, c.why.contains("没判出调性"));
    }

    /**
     * 门槛本身要可调——它是听感参数，不该写死在代码里。
     */
    @Test
    public void thresholdsAreTunable() {
        MixTuning strict = MixTuning.parse("auto_tempo_conflict=0.01");
        // 差 1.65%（120 vs 122）在默认 3% 门槛下算「合」，在 1% 门槛下就算「冲突」。
        // 注意别拿 1 BPM 去试：120 上的 1 BPM 只有 0.83%，两个门槛都过不去。
        Analysis.Tempo a = tempo(120.0f);
        Analysis.Tempo b = tempo(122.0f);
        assertEquals(MixStyle.PLAIN_BLEND,
                Chooser.choose(a, b, scored(1.0f), MixTuning.defaults()).style);
        assertEquals(MixStyle.BASS_SWAP,
                Chooser.choose(a, b, scored(1.0f), strict).style);
    }

    // ------------------------------------------------------------------
    // 以下为补充测试（上游没有）
    // ------------------------------------------------------------------

    /**
     * 补充：{@code AutoChoice} 的<strong>值语义</strong>。
     *
     * <p>上游 {@code AutoChoice} 有 {@code #[derive(PartialEq)]}，而 {@code why} 是运行时
     * {@code format!} 出来的 {@code String}——所以「两个选择的理由一样」在上游<strong>是相等</strong>。
     * Java 如果不覆写 {@code equals}，同一句话的两个串就会判不等，
     * 「同一个结论」在日志/界面去重和 {@code assertEquals} 里都会表现成两回事。
     * 这条同时钉住 {@code hashCode} 与 {@code style} 参与比较这一点。
     */
    @Test
    public void autoChoiceHasValueSemantics() {
        Chooser.AutoChoice a = Chooser.choose(
                tempo(120.0f), tempo(120.4f), scored(1.0f), MixTuning.defaults());
        Chooser.AutoChoice b = Chooser.choose(
                tempo(120.0f), tempo(120.4f), scored(1.0f), MixTuning.defaults());

        // 不是同一个对象（理由串是各自 format 出来的新串），但接法与理由的内容都一样
        // → 相等。这是上游 PartialEq 的语义，也是「引用相等不够」的证据。
        assertTrue("这是两个不同对象，用来确认下面的相等不是引用相等", a != b);
        assertTrue("同一份输入两次判定必须给出相等的结论", a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());

        // 理由不同（例如另一边缺拍点）就不该相等
        Chooser.AutoChoice other = Chooser.choose(
                null, tempo(120.0f), scored(1.0f), MixTuning.defaults());
        assertNotEquals(a, other);
    }

    /**
     * 补充：<strong>速度差那一关先于调性</strong>（上游 {@code choose} 里的顺序）。
     *
     * <p>上游只测了「速度合 + 调性缺失 → 朴素融合」，没有测「速度冲突 + 调性缺失」。
     * 后者是真正的顺序性质：调性算不出来时也不能把速度冲突一起放过——
     * 否则同一个「没有调性信息」的素材会因为在哪一支判空而与上游选出不同的接法。
     * 这条也是 {@code null} 语义的界：{@code null} 退化的只是「不依赖调性的那一部分判断」。
     */
    @Test
    public void aTempoClashIsDecidedBeforeHarmonyIsConsulted() {
        Chooser.AutoChoice c = Chooser.choose(
                tempo(112.0f), tempo(124.2f), null, MixTuning.defaults());
        assertEquals(c.why, MixStyle.BASS_SWAP, c.style);
        assertTrue(c.why, c.why.contains("速度差"));
    }
}
