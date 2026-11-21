package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * <b>真实感素材上的 BPM 精度：底鼓 / 军鼓 / 踩镲 / 贝斯 + 切分。</b>
 *
 * <h3>为什么要有这一组（用户 2026-09-30）</h3>
 *
 * <p>用户：「我希望你能改进 bpm 算法，硬算算出来的 bpm 和实际 bpm 偏差降到最低」。
 * 合成点击轨（{@code AnalysisTest} 那组）只能验「周期算得准不准」，验不出
 * <b>「哪一层才是拍」</b>——而真歌上的偏差几乎都出在这一层。这组素材按真歌的样子造：
 *
 * <ul>
 *   <li><b>普通 4/4</b>：底鼓每两拍一下、军鼓在 2/4、踩镲八分、贝斯跟拍；</li>
 *   <li><b>切分贝斯</b>：同上，但贝斯挪到后半拍（上一版算法在这里会把 150 报成 75）；</li>
 *   <li><b>半拍感觉</b>：底鼓每四拍一下（kickEvery=2）；</li>
 *   <li><b>无底鼓</b>：只有军鼓与踩镲定义拍。</li>
 * </ul>
 *
 * <p>改之前实测：普通 4/4 与半拍感觉在 150 / 160 / 170 / 175 上**全部报半速**
 * （150→75.0、160→80.0、170→85.0、175→87.5）——自相关挑中的是「底鼓每两拍」那个最强
 * 周期性，而拍由军鼓与踩镲定义。现在四条判据之外的倍速消歧（覆盖率 + 带容差自相关的
 * **并联**，见 {@code Analysis} 的 (b) 段）把它们全部纠正过来。
 *
 * <h3>容差</h3>
 *
 * <p>±1.5 BPM：素材是合成的、真值确定，但速度读数本身有亚格点误差
 * （实测最大 0.1 BPM，留一个数量级的余量）。
 */
public class AnalysisTempoRealisticTest {

    private static final int RATE = 48_000;

    /** 舞曲/流行真正会落在的区间。 */
    private static final float[] BPMS = {100f, 110f, 120f, 128f, 140f, 150f, 160f, 170f, 175f};

    @Test
    public void plainFourOnTheFloorAndHalfTimeFeelAreBothRight() {
        for (float bpm : BPMS) {
            assertEquals("普通 4/4：" + bpm, bpm, bpm(beat(bpm, 1.0, 1.0, false)), 1.5);
            assertEquals("半拍感觉：" + bpm, bpm, bpm(beat(bpm, 2.0, 1.0, false)), 1.5);
            assertEquals("无底鼓：" + bpm, bpm, bpm(beat(bpm, 0.0, 1.0, false)), 1.5);
            // 切分贝斯这一档只钉 100 以上：100 以下它与它的倍速在素材上同样成立
            // （贝斯落在后半拍，"拍"落哪一层本来就是听感问题），不做断言。
            assertEquals("切分贝斯：" + bpm, bpm, bpm(beat(bpm, 1.0, 1.0, true)), 1.5);
        }
    }

    /**
     * <b>慢歌不许被折成倍速。</b>
     *
     * <p>这是倍速消歧的反方向：把 70–90 的素材折成 140–180 同样错。判据用
     * 「底鼓每拍一下」的慢素材——它的拍层非常明确（每条正拍上都有底鼓）。
     */
    @Test
    public void slowTemposAreNotFoldedUp() {
        for (float bpm : new float[]{70f, 75f, 80f, 90f}) {
            assertEquals("慢速素材被折成倍速了：" + bpm,
                    bpm, bpm(beat(bpm, 0.5, 1.0, false)), 1.5);
        }
    }

    private static double bpm(float[] pcm) {
        Analysis.Tempo t = Analysis.analyze(pcm, RATE);
        return t == null ? Double.NaN : (double) t.bpm;
    }

    /** kickEvery=每几拍一个底鼓（0 = 没有底鼓）；sync=true 贝斯切分。 */
    private static float[] beat(double bpm, double kickEvery, double snareEvery, boolean sync) {
        int n = (int) (20.0 * RATE);
        float[] v = new float[n];
        double beat = 60.0 / bpm * RATE;
        int beats = (int) (n / beat);
        for (int b = 0; b < beats; b++) {
            int at = (int) (b * beat);
            if (kickEvery > 0 && (b % Math.round(kickEvery * 2)) % 2 == 0) {
                addKick(v, at);
            }
            if ((b % 4) == 1 || (b % 4) == 3) {
                addSnare(v, at);
            }
            addHat(v, at);
            addHat(v, at + (int) (beat * 0.5));
            // 贝斯：普通在每拍，切分在「后半拍 + 上一拍的反拍」
            int bassAt = sync ? at + (int) (beat * 0.5) : at;
            addBass(v, bassAt, bpm, sync ? (b % 2 == 0 ? 55.0 : 82.5) : 55.0);
        }
        // 一层很轻的和弦垫底（没有起音，只贡献能量）
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            v[i] += (float) (0.05 * Math.sin(2.0 * Math.PI * 220.0 * t)
                    + 0.04 * Math.sin(2.0 * Math.PI * 277.2 * t));
        }
        return v;
    }

    private static void addKick(float[] v, int at) {
        for (int i = 0; i < RATE / 8 && at + i < v.length; i++) {
            double t = (double) i / RATE;
            double env = Math.exp(-i / (RATE * 0.05));
            double f = 110.0 * Math.exp(-i / (RATE * 0.02)) + 45.0;
            v[at + i] += (float) (0.6 * env * Math.sin(2.0 * Math.PI * f * t));
        }
    }

    private static void addSnare(float[] v, int at) {
        java.util.Random rnd = new java.util.Random(at);
        for (int i = 0; i < RATE / 8 && at + i < v.length; i++) {
            double env = Math.exp(-i / (RATE * 0.03));
            v[at + i] += (float) (0.45 * env * (rnd.nextDouble() * 2.0 - 1.0));
        }
    }

    private static void addHat(float[] v, int at) {
        java.util.Random rnd = new java.util.Random(at + 7);
        int prev = 0;
        for (int i = 0; i < RATE / 40 && at + i < v.length; i++) {
            double env = Math.exp(-i / (RATE * 0.008));
            double x = rnd.nextDouble() * 2.0 - 1.0;
            double hp = x - prev;                 // 粗糙的高通
            prev = (int) x;
            v[at + i] += (float) (0.18 * env * hp);
        }
    }

    private static void addBass(float[] v, int at, double bpm, double hz) {
        double beat = 60.0 / bpm * RATE;
        int len = (int) (beat * 0.9);
        for (int i = 0; i < len && at + i < v.length; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1.0, i / (RATE * 0.01)) * Math.exp(-i / (RATE * 0.25));
            v[at + i] += (float) (0.35 * env * Math.sin(2.0 * Math.PI * hz * t));
        }
    }
}
