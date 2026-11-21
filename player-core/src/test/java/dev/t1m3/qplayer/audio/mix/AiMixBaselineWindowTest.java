package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.model.BeatModels;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>两套方案要在同一把尺子上比：尺子必须是它们各自<u>真正会跑</u>的那个窗口。</b>
 *
 * <h3>用户 2026-09-30 选的这一档</h3>
 *
 * <p>AI 自动档里「现有接法 vs 声波重合」那次比较，基准那一侧的窗口原先取
 * {@code Automix.fallbackWindowWith(style)}——那是各接法<b>在 120 BPM 下</b>的标称秒数
 * （朴素融合 4 s / 低音交接 8 s / 尾骑 12 s / 表现模式 16 s），而它真正执行时用的是
 * {@code beatsFor(style) × 周期} 再对到拍点上。于是除了 120 BPM，两边差一个速度比：
 * 低音交接在 150 BPM 实际跑 6.4~6.8 s，打分却按 8.0 s 算（差 20%）。
 *
 * <p>现在比较挪到窗口算完之后（`AiMix.prepare` 里 `styleWindow` 那一段），基准按
 * <b>它自己会跑的那个窗口</b>打分；声波重合赢了仍然换成它自己那个窗口。
 *
 * <h3>判据</h3>
 *
 * <p>素材是 150 BPM 的节拍轨（拍点能被判出来，note 里写着 149.x BPM），于是：
 *
 * <ol>
 *   <li>note 里「现有接法 …（窗口 X s）」那个 X 必须是 <b>拍数 × 一拍</b> 的量级
 *       （对齐最多多一拍），而且<b>必须小于</b>该接法 120 BPM 的标称值——旧代码在这里会印
 *       标称值；</li>
 *   <li>胜负两边都核一次：<b>谁赢了，装载的窗口就是它被打分的那个窗口</b>
 *       （基线赢了比 note 里基线的窗口，声波重合赢了比它那侧七项后面的窗口）。</li>
 * </ol>
 */
public class AiMixBaselineWindowTest {

    private static final int RATE = 48_000;

    /** 快歌：一拍 0.4 s，和 120 BPM 的标称值拉开 20%。 */
    private static final double BPM = 150.0;

    /** note 里那个「149.4 BPM」之类的读数（判据的前提：拍点真的判出来了）。 */
    private static final Pattern BPM_PATTERN =
            Pattern.compile("[0-9]+\\.[0-9]+ BPM");

    @org.junit.Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Decoder.Opener saved;

    @Before
    public void installSeams() {
        saved = currentOpener();
        Decoder.install(TestWavFile.opener());
        BeatModels.uninstall();
        ModelDownloads.uninstall();
    }

    @After
    public void restoreSeams() {
        BeatModels.uninstall();
        ModelDownloads.uninstall();
        Decoder.install(saved);
    }

    @Test
    public void theBaselineIsScoredAtTheWindowItWillActuallyRun() throws Exception {
        // 两轮：150 BPM（声波重合赢了）与 120 BPM（现有接法赢了，实测）。
        // 两轮都要核的是同一件事：**谁赢了，装载的窗口就得是它被打分的那个窗口**。
        for (double bpm : new double[]{BPM, 120.0}) {
            double beat = 60.0 / bpm;
            Path dir = temporary.newFolder().toPath();
            Path a = TestWavFile.write(dir.resolve("bpm-a-" + bpm + ".wav"),
                    clicks(220.0, 40.0, beat), clicks(220.0, 40.0, beat), RATE);
            Path b = TestWavFile.write(dir.resolve("bpm-b-" + bpm + ".wav"),
                    clicks(246.94, 20.0, beat), clicks(246.94, 20.0, beat), RATE);

            AiMix t = new AiMix(MixStyle.AUTO, RATE, 2, MixTuning.defaults(), () -> 6.0);
            TransitionPlan plan = t.prepare(a.toString(), b.toString(), RATE);
            System.out.println("[baseline-window] " + bpm + " BPM note: " + plan.note);

            assertTrue("这一轮没判出 BPM，判据的前提不成立：" + plan.note,
                    BPM_PATTERN.matcher(plan.note).find());
            MixStyle style = styleOf(plan.note);
            assertNotNull("note 里应当提到选中的接法：" + plan.note, style);
            double baseline = seconds(plan.note, "现有接法 [0-9.]+（窗口 ([0-9.]+)s）");
            double installed = t.windowFrames(RATE) / (double) RATE;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[baseline-window] %.0f BPM：接法 %s（%.0f 拍，120 BPM 标称 %.1f s）"
                            + "→ 基准窗口 %.1f s，实际装载 %.1f s",
                    Double.valueOf(bpm), style.label(), Float.valueOf(style.beats()),
                    Float.valueOf(style.fallbackSecs()), Double.valueOf(baseline),
                    Double.valueOf(installed)));

            if (style.beats() > 0.0f) {
                double ideal = (double) style.beats() * beat;
                assertTrue("基准窗口必须按「拍数 × 实际 BPM」算（拍点对齐最多多一拍）："
                                + "期望 " + ideal + "~" + (ideal + beat) + " s，实测 " + baseline
                                + " s",
                        baseline >= ideal - 0.15 && baseline <= ideal + beat + 0.15);
                // 「基准窗口不许等于 120 BPM 的标称值」这条只在**检测速度明显不是 120** 时
                // 才有意义：素材本来就是 120 BPM 的那一轮，两者**按定义**相等
                // （16 拍 × 0.5 s = 8.0 s = 标称值），拿它当红是判据自己想岔了。
                //
                // 2026-10-01 换掉硬算路（白化谱通量 + DP + 全局回归）之后这一轮才开始撞：
                // 新链路的相位与周期足够准，对拍窗口**正好**落在 8.0 s；旧链路因为拍点有
                // 零点几拍的偏差，窗口会多出一截，于是「不等于标称」这条侥幸成立。
                // 判据的本意是「别照着标称值抄」，所以按 150 BPM 那一轮（6.4 s vs 8.0 s）
                // 来判就够了——那里差 20%，抄标称值必然露馅。
                boolean atReferenceTempo = Math.abs(bpm - 120.0) < 1.2;
                if (!atReferenceTempo) {
                    assertTrue("基准窗口不许是那个**不随速度变**的 120 BPM 标称值 "
                                    + style.fallbackSecs() + " s（实测 " + baseline + " s）——"
                                    + "相等就说明打分还在用旧尺子",
                            Math.abs(baseline - (double) style.fallbackSecs()) > 0.2);
                } else {
                    assertTrue("120 BPM 这一轮：基准窗口按定义就等于标称值，这里核的是"
                                    + "「它确实按拍数 × 实际周期算出来了」（实测 " + baseline
                                    + " s，理想 " + ideal + " s）",
                            Math.abs(baseline - ideal) <= beat + 0.15);
                }
            }

            if (plan.note.contains("选 声波重合")) {
                double harmonic = seconds(plan.note, "声波重合 七项 [0-9.]+（窗口 ([0-9.]+)s）");
                assertEquals("声波重合赢了：装载的必须是它那侧被打分的窗口：" + plan.note,
                        harmonic, installed, 0.06);
            } else {
                assertEquals("现有接法赢了：装载的必须是它被打分的那个窗口：" + plan.note,
                        baseline, installed, 0.06);
            }
        }
    }

    /** 150 BPM 的节拍轨：一条乐音底噪 + 每拍一个短促瞬态（拍点分析要的就是它）。 */
    private static float[] clicks(double hz, double secs, double beat) {
        int n = (int) Math.round(secs * RATE);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = (float) (0.25 * Math.sin(2.0 * Math.PI * hz * (double) i / RATE));
        }
        for (double at = 0.0; at < secs; at += beat) {
            int c = (int) Math.round(at * RATE);
            for (int i = 0; i < RATE / 50 && c + i < n; i++) {
                double env = Math.exp(-i / (RATE * 0.004));
                v[c + i] += (float) (0.6 * env * Math.sin(2.0 * Math.PI * 1200.0 * i / RATE));
            }
        }
        return v;
    }

    /** note 里提到的那个接法（按 label 认；Chooser 的文字里一定带着它）。 */
    private static MixStyle styleOf(String note) {
        for (MixStyle s : MixStyle.values()) {
            if (s != MixStyle.AUTO && note.contains(s.label())) {
                return s;
            }
        }
        return null;
    }

    private static double seconds(String note, String regex) {
        Matcher m = Pattern.compile(regex).matcher(note);
        assertTrue("note 里找不到「" + regex + "」：" + note, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static Decoder.Opener currentOpener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
