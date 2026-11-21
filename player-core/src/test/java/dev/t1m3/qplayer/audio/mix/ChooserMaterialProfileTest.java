package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>六档各在它的场合被挑中</b>（2026-10-07 Chooser 重写）。
 *
 * <p>重写前的自动档只有两个出口（朴素融合 / 低音交接），其余四档永远进不来。
 * 这条判据用**合成素材**给每一档各造一个"就该落到它"的例子，并且
 * <b>先断言画像信号本身</b>——信号没造对时，失败信息直接指出是哪个信号，
 * 而不是只报"档不对"。
 *
 * <p>判定顺序即优先级（见 {@link Chooser#choose}）：没冲突 → 朴素融合；
 * 没东西可叠 → 拍点剪切；在 build-up 且 B 紧跟能量峰 → 表现模式；
 * 长尾+留白进 → 尾音骑行；低频冲突大且 drop 站得住 → 学习EQ；其余 → 低音交接。
 */
public class ChooserMaterialProfileTest {

    private static final int RATE = 48000;
    private static final double SECS = 8.0;

    private static Analysis.Tempo tempo(float bpm) {
        return new Analysis.Tempo(bpm, RATE * 60.0f / bpm, 0.0f, 0.9f);
    }

    private static Harmonic.Match key(float score) {
        return new Harmonic.Match(0, score, score, false);
    }

    private static MixTuning tuning() {
        return MixTuning.defaults();
    }

    // ---- 合成素材 ------------------------------------------------------------------

    /** 低频（100 Hz）+ 中频（1 kHz）的稳定信号：低频冲突大。 */
    private static float[] sustained(double secs, double lowAmp, double midAmp) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        java.util.Random rng = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double v = lowAmp * Math.sin(2 * Math.PI * 100 * t)
                    + midAmp * Math.sin(2 * Math.PI * 1000 * t)
                    + 0.02 * (rng.nextDouble() - 0.5);
            out[i] = (float) v;
        }
        return out;
    }

    /** 只有中高频、没有低频：低频冲突≈0。 */
    private static float[] noLow(double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            out[i] = (float) (0.3 * Math.sin(2 * Math.PI * 3000 * t));
        }
        return out;
    }

    /** 能量往上堆的尾巴（build-up），末尾一个强峰（"drop"）。 */
    private static float[] risingWithDrop(double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double ramp = 0.05 + 0.25 * (i / (double) n);
            double v = ramp * Math.sin(2 * Math.PI * 100 * t);
            if (i > n * 0.85) {
                v *= 5.0;                       // 末尾的"drop"
            }
            out[i] = (float) v;
        }
        return out;
    }

    /** 缓慢衰减的长尾（每 8 秒只掉 ~3 dB）。 */
    private static float[] longTail(double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.pow(10, -3.0 * (i / (double) n) / 20.0);
            out[i] = (float) (env * (0.4 * Math.sin(2 * Math.PI * 100 * t)
                    + 0.2 * Math.sin(2 * Math.PI * 1000 * t)));
        }
        return out;
    }

    /** 冒头很软的进场（留白进）：前 2 秒几乎没有上升沿。 */
    private static float[] calmHead(double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            out[i] = (float) (0.3 * Math.sin(2 * Math.PI * 200 * t)
                    + 0.2 * Math.sin(2 * Math.PI * 1000 * t));
        }
        return out;
    }

    /** 满拍进：头部立刻有强起跳。 */
    private static float[] punchyHead(double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        java.util.Random rng = new java.util.Random(11);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double beat = (i % (RATE / 2)) < 600 ? 0.6 : 0.0;      // 每 0.5 s 一下
            out[i] = (float) (0.35 * Math.sin(2 * Math.PI * 100 * t) + beat
                    + 0.02 * (rng.nextDouble() - 0.5));
        }
        return out;
    }

    /**
     * 中间塌一下再回来的尾巴：**有**能量峰（dropReliable 高 ✓）但**不是在往上堆**
     * （后半段均值没比前半段高 ⇒ buildUp 为假 ✓）。
     *
     * <p>为什么学习EQ 那一档必须用这种素材：表现模式的分支排在它前面，
     * 而"一路上升 + 末尾一个 drop"那种素材会先被表现模式接走 —— 那是**优先级在起作用**，
     * 不是判定错（第一次跑就是这么红的）。
     */
    private static float[] dippedTail(double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double at = i / (double) n;
            double env = (at > 0.4 && at < 0.6) ? 0.15 : 1.0;      // 中段塌下去
            if (at > 0.2 && at < 0.26) {
                env *= 1.8;                     // 前半段一个短促强拍：抬高峰值 ⇒ 显著度够
            }                                   // （放在前半段 ⇒ 后半段均值不更高 ⇒ 仍非 build-up）
            out[i] = (float) (env * (0.5 * Math.sin(2 * Math.PI * 100 * t)
                    + 0.25 * Math.sin(2 * Math.PI * 1000 * t)));
        }
        return out;
    }

    /** 衰减很快的尾巴（拍点剪切/学习EQ 那两档要"不是长尾"）。 */
    private static float[] fastDecay(double secs) {
        int n = (int) Math.round(secs * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.pow(10, -30.0 * (i / (double) n) / 20.0);
            out[i] = (float) (env * (0.5 * Math.sin(2 * Math.PI * 100 * t)
                    + 0.3 * Math.sin(2 * Math.PI * 1000 * t)));
        }
        return out;
    }

    private static MaterialProfile profileOf(float[] tail, float[] head,
                                             double tailSecs, double headSecs) {
        return MaterialProfile.of(tail, head, tailSecs, headSecs, RATE);
    }

    private static MixStyle pick(MaterialProfile p, float bpmA, float bpmB, float keyScore,
                                 double windowSecs) {
        assertTrue("画像必须是已知的（算不出来就该走不猜那一支）", p.known());
        return Chooser.choose(tempo(bpmA), tempo(bpmB), key(keyScore), tuning(), p,
                windowSecs).style;
    }

    @Test
    public void noConflictGoesToPlainBlend() {
        // 速度一样、调性一样、两边都没有低频 ⇒ 什么都别做
        MaterialProfile p = profileOf(noLow(SECS), noLow(SECS), SECS, SECS);
        assertTrue("低频不重叠时 lowClash 必须很低（实测 " + p.lowClash() + "）",
                p.lowClash() < tuning().lowClashGate);
        assertEquals("没有冲突就该朴素融合", MixStyle.PLAIN_BLEND, pick(p, 128f, 128f, 0.95f, 8.0));
    }

    @Test
    public void tooLittleMaterialGoesToBeatCut() {
        // 有冲突（速度差 6%），但可叠素材只有 0.5 秒 ⇒ 没东西可叠
        MaterialProfile p = profileOf(sustained(0.5, 0.5, 0.3), punchyHead(SECS), 0.5, SECS);
        assertTrue("可叠秒数必须真的短（实测 " + p.overlapSecs() + "）",
                p.overlapSecs() < tuning().minOverlapSecs);
        assertEquals("没东西可叠就该在拍上干净地切一刀", MixStyle.BEAT_CUT,
                pick(p, 112f, 136f, 0.9f, 8.0));
    }

    @Test
    public void buildUpIntoADropGoesToShowMode() {
        MaterialProfile p = profileOf(risingWithDrop(SECS), punchyHead(SECS), SECS, SECS);
        assertTrue("尾巴必须被判成在往上堆（尾斜率 " + p.tailDecayDbPerSec() + " dB/s）",
                p.buildUp());
        assertTrue("必须有站得住的能量峰（显著度 " + p.dropReliable() + "）",
                p.dropReliable() >= tuning().dropGate);
        assertEquals("build-up 尾 + B 紧跟能量峰 ⇒ 扫频服务于音乐", MixStyle.SHOW_MODE,
                pick(p, 112f, 136f, 0.9f, 8.0));
    }

    @Test
    public void longTailWithACalmHeadGoesToTailRide() {
        MaterialProfile p = profileOf(longTail(SECS), calmHead(SECS), SECS, SECS);
        assertTrue("尾巴必须被判成长尾（" + p.tailDecayDbPerSec() + " dB/s ≥ "
                + tuning().tailLongDbPerSec + "）",
                p.tailDecayDbPerSec() >= tuning().tailLongDbPerSec);
        assertTrue("B 必须被判成留白进（起跳 " + p.headOnset() + "）",
                p.headOnset() <= tuning().headCalmGate);
        assertEquals("长尾 + 留白进 ⇒ 尾音骑行", MixStyle.TAIL_RIDE,
                pick(p, 112f, 136f, 0.9f, 8.0));
    }

    @Test
    public void conflictWithoutADropGoesToBassSwap() {
        // **平稳**素材（不衰减、无峰值）+ 满拍进的头：既没有 drop、也不会被误判成
        // "长尾+留白进"（那一支要求 B 留白进，而这个头是满拍进）。
        MaterialProfile p = profileOf(sustained(SECS, 0.5, 0.3), punchyHead(SECS), SECS, SECS);
        assertTrue("平稳素材不该有站得住的 drop（显著度 " + p.dropReliable() + "）",
                p.dropReliable() < tuning().dropGate);
        assertEquals("有冲突但没有拐点 ⇒ 低音交接（固定计划，不需要拐点）", MixStyle.BASS_SWAP,
                pick(p, 112f, 136f, 0.9f, 8.0));
    }

    @Test
    public void lowClashWithAReliableDropGoesToLearnedEq() {
        MaterialProfile p = profileOf(dippedTail(SECS), punchyHead(SECS), SECS, SECS);
        assertTrue("低频必须真的撞上（" + p.lowClash() + " ≥ " + tuning().lowClashGate + "）",
                p.lowClash() >= tuning().lowClashGate);
        assertTrue("这一档要求**不是** build-up（否则会被表现模式先接走）：尾斜率 "
                        + p.tailDecayDbPerSec() + " dB/s、build-up " + p.buildUp(),
                !p.buildUp());
        // **要有真实冲突**（新契约：低频冲突只在已有速度差或调性不合时才是加码）——
        // 所以这里给一个 >8% 的速度差；素材形状（低频大 + 有拐点）才是决定"用哪一档处理"的那一半。
        assertEquals("有冲突 + 低频冲突大 + drop 稳 + 窗口够 ⇒ 学习EQ", MixStyle.LEARNED_EQ,
                pick(p, 112f, 136f, 0.95f, 8.0));
    }

    @Test
    public void unknownProfileNeverGuesses() {
        // 契约是"逐字退回旧行为"，不是"一定是朴素融合" —— 速度差 6.25% 在旧逻辑里
        // 本来就该给低音交接。所以拿**旧 4 参入口的结果**当期望，这才叫"逐字退回"。
        MixStyle old = Chooser.choose(tempo(128f), tempo(136f), key(0.5f), tuning()).style;
        assertEquals("画像未知 ⇒ 逐字退回旧行为（旧逻辑给的是 " + old + "）", old,
                Chooser.choose(tempo(128f), tempo(136f), key(0.5f), tuning(),
                        MaterialProfile.UNKNOWN, 8.0).style);
    }
}
