package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 声波重合的对齐测试：对应 Sonalis {@code sonalis-mix/src/automix.rs} 的
 * {@code mod tests} 里<b>被测对象是 {@link HarmonicBlend} 或它用的那两条曲线</b>
 * 的那 7 条（其余 24 条在 {@link AiMixTest}）。上游 31 条因此一条不少。
 *
 * <p>方法名按 camelCase 逐条对应上游，断言强度与容差一致。
 *
 * <h2>为什么这里装一个「只会失败」的解码器</h2>
 *
 * <p>本类的三条 {@code prepare} 测试用的都是**不存在的文件**（上游也是
 * {@code Path::new("nope-a.wav")}），要的就是「分析失败」这条路径。QPlayer 的
 * player-core 里 {@link Decoder} 只有接口与工厂、实现由宿主提供，所以这里装一个
 * 一律抛 {@link AudioException} 的替身，把「打不开音源」这件事如实表达出来。
 * 需要真实文件的那条回归（对拍）在 {@link AiMixTest} 里，那边装的是能读
 * 16 bit WAV 的替身。
 */
public class HarmonicBlendTest {

    private static final int RATE = 48_000;

    /**
     * 装上「一律打不开」的解码器替身。
     *
     * <p>用 {@code @Before} 而不是 {@code @BeforeClass}：{@link Decoder#install} 是
     * 全局静态状态，每一条测试都自己装一遍，就不怕被别的测试类换掉。
     */
    @Before
    public void installFailingDecoder() {
        Decoder.install(new MissingFileOpener());
    }

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
     * 一次失败的分析<b>不能留下上一首歌的转调量</b>。
     *
     * <p>{@code pitchAt()} 读的是 {@code self.shift}，而编排器是按 {@code plan.pitch}
     * 决定要不要提前挂变调器的。两者一旦不一致，后果是双重错误：
     * 歌被转了一个与它无关的半音数，而且变调器还是在过渡中途挂上的。
     */
    @Test
    public void aFailedPrepareDoesNotKeepThePreviousTransposition() {
        HarmonicBlend t = new HarmonicBlend(RATE, 2, 6.0f);
        // 假装上一首歌判出来要转 3 个半音
        t.shift = 3.0f;
        t.score = 0.91f;

        TransitionPlan plan = t.prepare("nope-a.wav", "nope-b.wav", RATE);

        assertTrue("没算出来就不该有转调安排", plan.pitch.isZero());
        assertEquals("上一次的转调量必须被清掉", 0.0f, t.shift(), 0.0f);
        assertEquals(0.0f, t.score(), 0.0f);
        assertTrue("否则这一首会被上一首的半音数处理：" + t.pitchAt(0.5f),
                t.pitchAt(0.5f).isZero());
    }

    /** 分析失败（文件不存在）时必须退回「本次不转调」，不能抛出去。 */
    @Test
    public void harmonicPrepareSurvivesMissingFiles() {
        HarmonicBlend t = new HarmonicBlend(RATE, 2, 6.0f);
        TransitionPlan plan = t.prepare("nope-a.wav", "nope-b.wav", RATE);
        assertEquals(0.0f, t.shift(), 0.0f);
        assertTrue(plan.pitch.isZero());
        assertTrue("应当说明原因：" + plan.note, plan.note.contains("失败"));
    }

    /** 声波重合：渲染必须是等功率淡化（变调交给 deck）。 */
    @Test
    public void harmonicBlendIsAnEqualPowerCrossfade() {
        HarmonicBlend t = new HarmonicBlend(RATE, 2, 6.0f);
        AudioBuf a = buf(4, 1.0f);
        AudioBuf b = buf(4, 1.0f);
        AudioBuf out = empty(4);
        t.render(out, a, b, 0.5f);
        float expected = (1.0f / (float) Math.sqrt(2.0)) * 2.0f;
        assertTrue("中点应为 2×0.7071 = " + expected + "，实际 " + out.channel(0).get(0),
                Math.abs(out.channel(0).get(0) - expected) < 1e-5f);
        assertEquals("声波重合", t.name());
    }

    /**
     * 这份曲线是<strong>给「只移伴奏」那一层消费的意图</strong>，形状照上游：
     * 前段滑入（突然变调会听出「跳」）、出场侧不滑回零。
     *
     * <p><b>它不会落到 deck 上</b>：这一档永远被 {@code VocalAwarePitchTransition} 包住，
     * 那一层的 {@code pitchAt} 恒为零、自己按这条曲线移两条伴奏。
     * 「出场曲与进场曲的人声都不移」那条纪律由 {@code AutomixVocalModeGatingTest}
     * 全表扫（两侧都要求零）。
     */
    /**
     * <b>2026-10-06 按证据重述</b>（第一阶段工作四：双方都变调）。原来这一条钉的是
     * "滑入终点达到全量 2.0、且不滑回零"——那是"<b>A 独担全部移调</b>"时代的形状。
     * 用户要求改成：<b>双方各走一半</b>（中间调 = keyA + d/2），曲线
     * <b>前 25% 靠拢 → 保持到 0.70 → 之后各自划回原调</b>，并且明确说过
     * "后 25% 的划回从 0.70 开始而不是 0.75，不要改成 0.75"。
     *
     * <p>所以现在钉的是：① 靠拢终点 A 拿到<b>一半</b>（1.0），B 拿到<b>相反的一半</b>（−1.0）；
     * ② 0.70 之前保持；③ 1.0 处两边都回到 0（划回原调，谁都不跑调）。
     */
    @Test
    public void theCurveIsTheIntentConsumedByTheAccompanimentLayer() {
        // 窗口 0.6 秒：上游的 25% = 0.15 s，比绝对上限（0.2 s）短，所以按上游走
        HarmonicBlend t = new HarmonicBlend(RATE, 2, 0.6f);
        t.shift = 2.0f;
        float glideEnd = t.glideEnd();
        assertEquals("短窗口时滑入段仍按上游的 25%", 0.25f, glideEnd, 1e-6f);

        assertTrue("起点不该已经变调", Math.abs(t.pitchAt(0.0f).outro) < 1e-6f);
        assertEquals("靠拢终点 A 拿到**一半**（双方各半）",
                1.0f, t.pitchAt(HarmonicBlend.PITCH_CONVERGE_END).outro, 1e-5f);
        assertEquals("靠拢终点 B 拿到**相反的一半**（两首歌在中间调会合）",
                -1.0f, t.pitchAt(HarmonicBlend.PITCH_CONVERGE_END).intro, 1e-5f);
        assertEquals("0.70 之前一直保持（中间 50% 不动）",
                1.0f, t.pitchAt(0.5f).outro, 1e-5f);
        assertEquals("0.70 处仍然保持", 1.0f, t.pitchAt(HarmonicBlend.PITCH_RETURN_START).outro, 1e-5f);
        // **0.75 那一点是这条判据的关键**：从 0.70 划回的话，0.75 处已经走掉 1/6。
        // 0.75 处：从 0.70 起划回，走掉的比例 (1−0.75)/0.30 = 1/6，再经**缓和曲线**
        // （3x²−2x³，治滑音：两端斜率为 0）⇒ 剩余 1 − smooth(1/6) ≈ 0.9259。
        float x = (1.0f - 0.75f) / (1.0f - HarmonicBlend.PITCH_RETURN_START);
        assertEquals("0.75 处已经开始划回（0.70 起划，不是 0.75）",
                x * x * (3.0f - 2.0f * x), t.pitchAt(0.75f).outro, 1e-5f);
        // **治滑音的硬性质**：两端斜率必须为 0——"开始滑"那一下不许有边界感。
        assertTrue("起点附近不许出现匀速滑动（斜率必须≈0）："
                        + Math.abs(t.pitchAt(0.005f).outro),
                Math.abs(t.pitchAt(0.005f).outro) < 5e-3f);
        assertTrue("终点附近同理：" + Math.abs(t.pitchAt(0.995f).outro),
                Math.abs(t.pitchAt(0.995f).outro) < 5e-3f);
        assertEquals("终点 A 回到原调", 0.0f, t.pitchAt(1.0f).outro, 1e-5f);
        assertEquals("终点 B 也回到原调", 0.0f, t.pitchAt(1.0f).intro, 1e-5f);
        for (float p = 0.0f; p <= 1.0f; p += 0.05f) {
            assertEquals("两侧必须始终互为相反数（靠拢/划回同步，p=" + p + "）",
                    t.pitchAt(p).outro, -t.pitchAt(p).intro, 1e-5f);
        }
    }

    /**
     * <strong>滑入段按绝对时长封顶（与上游的已记录偏差）。</strong>
     *
     * <p>上游把滑入段写成窗口的前 25%。窗口是用户可调的（0–20 s），于是 10 秒的窗口
     * 意味着出场曲<strong>连续滑音 2.5 秒</strong>：正在放的那首歌当场变调、一路弯过去，
     * 而滑音期间两首歌始终不在一调上（最多差一个全音以上），进场曲到滑音结束时
     * 已经响了 38%——那两秒半里两路一直在打架。用户听到的就是「又糊又诡异」。
     *
     * <p>滑入段本来只有一个用处：把「忽然变调」这一下藏起来。200 ms 足够，而且那时
     * 进场曲才刚出声（10 s 窗口下约 −30 dB）。
     */
    @Test
    public void longWindowStillKeepsTheGlideShort() {
        HarmonicBlend t = new HarmonicBlend(RATE, 2, 10.0f);
        t.shift = 2.0f;
        float glideEnd = t.glideEnd();
        double glideSecs = (double) glideEnd * 10.0;
        assertTrue("10 秒窗口的滑入段是 " + glideSecs + " 秒（比例 " + glideEnd
                        + "）——出场曲要连续滑音这么久，听感就是诡异音高",
                glideSecs <= 0.25);

        // 滑入段结束时，进场曲还应当几乎听不见（等功率曲线下它的增益）
        Mixer.Gains g = Mixer.equalPowerGains(glideEnd);
        assertTrue("滑入结束时进场增益已经 " + g.to + "，两路在不在一调上的那段时间太长了",
                g.to < 0.12f);

        // 20 秒窗口（设置上限）也不能变长
        HarmonicBlend w = new HarmonicBlend(RATE, 2, 20.0f);
        w.shift = 2.0f;
        assertTrue("20 秒窗口的滑入段被拉长到了 " + ((double) w.glideEnd() * 20.0) + " 秒",
                (double) w.glideEnd() * 20.0 <= 0.25);
    }

    /**
     * 算出的调性差要如实报在 {@code plan.note} 里，并且说清「加在进场曲上」。
     *
     * <p>这条判据直接打 {@code intervalNote} 这个包私有出口：为了造一份真有调性差的
     * 音频文件去驱动 {@code prepare} 不值当，而这句话是「决策依据」唯一的对外说法。
     */
    @Test
    public void theNoteSaysWhereTheShiftGoes() {
        Harmonic.Match needsShift = new Harmonic.Match(2, 0.42f, 0.88f, true);
        String note = HarmonicBlend.intervalNote(needsShift);
        assertTrue("要报出量到的差值：" + note, note.contains("+2"));
        assertTrue("要说清移调只加在伴奏上：" + note, note.contains("两条伴奏"));
        assertTrue("要说清两条人声都不动：" + note, note.contains("人声原样"));

        String noShift = HarmonicBlend.intervalNote(new Harmonic.Match(0, 0.91f, 0.91f, false));
        assertTrue("不需要移调时就别多话：" + noShift, noShift.contains("不转调"));
        assertFalse("不需要移调时不该出现移调安排：" + noShift, noShift.contains("伴奏"));
    }

    /** 不变调时 {@code pitchAt} 必须干脆返回零，免得白挂一个变调器。 */
    @Test
    public void noShiftMeansNoPitchPlan() {
        HarmonicBlend t = new HarmonicBlend(RATE, 2, 6.0f);
        assertTrue(t.pitchAt(0.5f).isZero());
    }

    /** 进场侧滑出曲线：前 70% 全量，末尾归零。 */
    @Test
    public void introGlideReturnsToZero() {
        assertTrue(Math.abs(Automix.introPitchGlide(0.0f) - 1.0f) < 1e-6f);
        assertTrue(Math.abs(Automix.introPitchGlide(Automix.INTRO_PITCH_GLIDE_START) - 1.0f) < 1e-6f);
        assertTrue(Math.abs(Automix.introPitchGlide(1.0f)) < 1e-6f);
        // 单调下降（上游的 f32 累加：p += 0.05，这里照做）
        float prev = 2.0f;
        float p = 0.0f;
        while (p <= 1.0f) {
            float v = Automix.introPitchGlide(p);
            assertTrue("在 " + p + " 处回升了", v <= prev + 1e-6f);
            prev = v;
            p += 0.05f;
        }
    }

    @Test
    public void smoothstepIsFlatAtTheEnds() {
        assertTrue(Math.abs(Automix.smoothstep(0.0f, 1.0f, -1.0f)) < 1e-9f);
        assertTrue(Math.abs(Automix.smoothstep(0.0f, 1.0f, 1.0f) - 1.0f) < 1e-9f);
        assertTrue(Math.abs(Automix.smoothstep(0.0f, 1.0f, 0.5f) - 0.5f) < 1e-6f);
        // 退化区间不该除零
        assertTrue(Math.abs(Automix.smoothstep(1.0f, 1.0f, 2.0f) - 1.0f) < 1e-9f);
    }

    /**
     * 一律失败的解码器替身：{@code Decoder.open} 拿到的是打不开的音源时，
     * 产品实现会抛 {@link AudioException}，这里如实照做。
     */
    private static final class MissingFileOpener implements Decoder.Opener {
        @Override
        public Decoder open(String source, Map<String, String> headers) {
            throw AudioException.config("音源不存在或不可读：" + source);
        }
    }
}
