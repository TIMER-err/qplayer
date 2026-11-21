package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>歌词内容级锚点：从重复的那一句钻进去</b>（2026-10-06 用户提的玩法）。
 *
 * <p>用户原话：「我直接看歌词数据有没有歌词内容重叠区域，我直接先对拍然后直接从重复的地方
 * 钻进去岂不是完美。」
 *
 * <p>这条判据钉四件事：① 有同句时进场点必须落到 {@code cut + Δ}（量化到整拍）；
 * ② **双重门槛**——文本像但那一处旋律不像时**不许**动点位（防"同一句词≠同一段旋律"）；
 * ③ 没有歌词数据时一位不变；④ 锚点会钻出曲子外面时不许动。
 */
public class AutoCueLyricAnchorTest {

    private static final int RATE = 22050;

    /** 造一个 4 秒周期（60 BPM 的整拍）、带 chroma 的包络。 */
    private static AutoCue.Envelope envelope(double secs, float chromaValue) {
        int frames = (int) (secs * RATE / 512.0);
        float[] rms = new float[frames];
        for (int i = 0; i < frames; i++) {
            rms[i] = 0.5f;
        }
        Analysis.Tempo tempo = new Analysis.Tempo(120.0f, 0.5f * RATE, 0.0f, 0.9f);
        AutoCue.Envelope env = AutoCue.envelopeOf(rms, secs, tempo, RATE);
        // 逐帧 chroma：全部帧给同一个值 ⇒ 任意两帧都"同调"（用于把旋律侧门槛调到可过）
        int pc = SpectralOnsets.PITCH_CLASSES;
        int chromaFrames = (int) (secs / 0.01);
        float[] chroma = new float[chromaFrames * pc];
        for (int i = 0; i < chromaFrames; i++) {
            chroma[i * pc] = chromaValue;
        }
        env.chroma = chroma;
        env.chromaFrames = chromaFrames;
        env.chromaHopSecs = 0.01;
        return env;
    }

    @Test
    public void aRepeatedLineMovesTheEntryOntoTheAlignment() {
        AutoCue.Envelope outro = envelope(200.0, 1.0f);
        AutoCue.Envelope intro = envelope(200.0, 1.0f);
        // A 行 181.0→184.0（3 s、4 个词）⇒ 第 2 个词在 181 + 3×(2/4) = 182.5
        // B 行 14.8→18.8（4 s、5 个词）⇒ 第 2 个词在 14.8 + 4×(2/5) = 16.4
        // 对齐关系 entry = cut + (tB − tA) = 181.4 + (16.4 − 182.5) = 15.3，
        // 再量化到 B 的整拍（0.5 s）：−166.1 → −166.0 ⇒ entry = 15.4。
        // 英文行（中文行不参与匹配）：共同段 "hold on to"（3 个词，从 A 的第 0 个 / B 的第 0 个起）
        // 交接在中缝 ⇒ 过词数 ceil(3/2) = 2：A 第 2 个词、B 第 2 个词
        AutoCue.LyricSpans a = new AutoCue.LyricSpans(new double[] {181.0},
                new double[] {184.0}, new String[] {"hold on to me"});
        AutoCue.LyricSpans b = new AutoCue.LyricSpans(new double[] {14.8},
                new double[] {18.8}, new String[] {"hold on to the night"});
        double cut = 181.4;
        double period = intro.tempo.periodFrames / (double) intro.beatRate;
        double got = AutoCue.lyricAnchoredEntry(outro, cut, intro, 15.0, 2.5, a, b);
        assertEquals("必须钻到 cut + (tb − ta) 量化到整拍后的位置", 15.4, got, 1e-9);
        double delta = got - cut;
        assertEquals("钻进去之后必须落在整拍上", 0.0,
                Math.abs(delta / period - Math.round(delta / period)), 1e-9);
        // 对齐质量：两句在窗口里的**相对位置**必须只差不到一拍
        double aLineInWindow = 182.5 - cut;
        double bLineInWindow = 16.4 - got;
        assertTrue("两句必须在同一进度上（差 ≤ 一拍）：A " + aLineInWindow + " vs B " + bLineInWindow,
                Math.abs(aLineInWindow - bLineInWindow) <= period + 1e-9);
    }

    @Test
    public void withoutMelodyAgreementTheTextAloneDoesNotMoveAnything() {
        AutoCue.Envelope outro = envelope(200.0, 1.0f);
        AutoCue.Envelope intro = envelope(200.0, 1.0f);
        // 把 B 的 chroma 造成"完全不同调"：只放第 7 个音级 ⇒ 与 A 的余弦为 0
        int pc = SpectralOnsets.PITCH_CLASSES;
        for (int i = 0; i < intro.chromaFrames; i++) {
            java.util.Arrays.fill(intro.chroma, i * pc, (i + 1) * pc, 0f);
            intro.chroma[i * pc + 6] = 1.0f;
        }
        AutoCue.LyricSpans a = new AutoCue.LyricSpans(new double[] {181.0},
                new double[] {184.0}, new String[] {"hold on to me"});
        AutoCue.LyricSpans b = new AutoCue.LyricSpans(new double[] {15.2},
                new double[] {19.2}, new String[] {"hold on to the night"});
        double got = AutoCue.lyricAnchoredEntry(outro, 181.4, intro, 15.0, 2.5, a, b);
        assertEquals("**双重门槛**：文本像但那处旋律不像 ⇒ 不许动点位", 15.0, got, 1e-12);
    }

    @Test
    public void withoutLyricsNothingChanges() {
        AutoCue.Envelope outro = envelope(200.0, 1.0f);
        AutoCue.Envelope intro = envelope(200.0, 1.0f);
        AutoCue.LyricSpans noText = new AutoCue.LyricSpans(new double[] {181.0},
                new double[] {183.0});
        assertEquals("这一路没有字 ⇒ 一位不变", 15.0,
                AutoCue.lyricAnchoredEntry(outro, 181.4, intro, 15.0, 2.5, noText, noText), 1e-12);
        assertEquals("连 spans 都没有 ⇒ 一位不变", 15.0,
                AutoCue.lyricAnchoredEntry(outro, 181.4, intro, 15.0, 2.5, null, null), 1e-12);
    }

    @Test
    public void anAnchorThatWouldDrillOutsideTheTrackIsRefused() {
        AutoCue.Envelope outro = envelope(200.0, 1.0f);
        AutoCue.Envelope intro = envelope(200.0, 1.0f);
        // Δ 很大 ⇒ entry 会超过曲子长度 ⇒ 不许动
        AutoCue.LyricSpans a = new AutoCue.LyricSpans(new double[] {181.0},
                new double[] {184.0}, new String[] {"hold on to me"});
        AutoCue.LyricSpans b = new AutoCue.LyricSpans(new double[] {5.0},
                new double[] {9.0}, new String[] {"hold on to the night"});
        assertEquals("钻出曲子外面 ⇒ 不动", 15.0,
                AutoCue.lyricAnchoredEntry(outro, 181.4, intro, 15.0, 2.5, a, b), 1e-12);
    }

    /**
     * <b>交接词必须落在窗口正中</b>（用户实测修正的核心）。
     *
     * <p>用户原话：「他确实匹配到了歌词相同段 **但是锚点计算有问题**（这句话念完了切到 B
     * 又念一遍【刚好是一整行一致 应当的做法是**一半 A 读一半 B 读**】）」。
     *
     * <p>病根是第一版只挪了 {@code entry}、没管 {@code cut}，于是交接词可能落在窗口之外
     * ——听到的就是"A 把整行念完 → 换 B → B 从头再念一遍"。这一条钉的就是那个形状：
     * <b>交接词在窗口里的相对位置必须落在 [{@value AutoCue#LYRIC_ANCHOR_MIN_POS},
     * {@value AutoCue#LYRIC_ANCHOR_MAX_POS}]</b>，也就是前半 A、后半 B。
     */
    @Test
    public void theHandoverWordLandsInTheMiddleOfTheWindow() {
        AutoCue.Envelope outro = envelope(200.0, 1.0f);
        AutoCue.Envelope intro = envelope(200.0, 1.0f);
        // A 行 150.0→154.0（4 s、4 个词）⇒ 交接词（第 2 个）在 152.0
        AutoCue.LyricSpans a = new AutoCue.LyricSpans(new double[] {150.0},
                new double[] {154.0}, new String[] {"hold on to me"});
        // B 行 30.0→34.0（4 s、5 个词）⇒ 交接词（第 2 个）在 31.6
        AutoCue.LyricSpans b = new AutoCue.LyricSpans(new double[] {30.0},
                new double[] {34.0}, new String[] {"hold on to the night"});
        double window = 3.0;
        double cut = 151.0;                 // 打分器挑的点（交接词 152.0 在它之后 1.0 s）
        double entry = 31.0;
        double[] got = AutoCue.lyricAnchor(outro, cut, intro, entry, window, a, b, 60.0, 190.0);
        org.junit.Assert.assertNotNull("这一对必须命中锚点", got);
        double newCut = got[0];
        double newEntry = got[1];
        // **窗口必须由重复段反推**（用户实测抓到的设计硬伤：第一版用打分器的 6 s 窗口，
        // 而重复的那一行只有 3~4 s ⇒ 窗口把整行盖住、B 又从头念一遍，听到的是"念两遍"）。
        // 本夹具：A 行 150→154（4 s、4 词），共同段 "hold on to" 占前 3 个词
        // ⇒ 段跨 150.0→153.0 = 3.0 s ⇒ 锚点窗口必须正好是 3.0 s。
        org.junit.Assert.assertEquals("锚点窗口必须等于重复段的长度", 3.0, got[2], 1e-9);
        org.junit.Assert.assertTrue("窗口必须夹在合理区间内："
                        + AutoCue.LYRIC_ANCHOR_MIN_WINDOW_SECS + "~"
                        + AutoCue.LYRIC_ANCHOR_MAX_WINDOW_SECS,
                got[2] >= AutoCue.LYRIC_ANCHOR_MIN_WINDOW_SECS
                        && got[2] <= AutoCue.LYRIC_ANCHOR_MAX_WINDOW_SECS);
        double pos = (152.0 - newCut) / got[2];      // 交接词在窗口里的相对位置
        System.out.println(String.format(java.util.Locale.ROOT,
                "[lyric-anchor] cut %.3f→%.3f · entry %.3f→%.3f · 交接词在窗口 %.2f 处"
                        + "（窗口 %.2f s）",
                java.lang.Double.valueOf(cut), java.lang.Double.valueOf(newCut),
                java.lang.Double.valueOf(entry), java.lang.Double.valueOf(newEntry),
                java.lang.Double.valueOf(pos), java.lang.Double.valueOf(got[2])));
        org.junit.Assert.assertTrue("**交接词必须落在窗口中间那一带**（前半 A、后半 B），实际 "
                        + pos + "；区间 [" + AutoCue.LYRIC_ANCHOR_MIN_POS + ", "
                        + AutoCue.LYRIC_ANCHOR_MAX_POS + "]",
                pos >= AutoCue.LYRIC_ANCHOR_MIN_POS && pos <= AutoCue.LYRIC_ANCHOR_MAX_POS);
        // 对齐关系仍然成立（允许各侧一个整拍的量化误差）
        double periodA = outro.tempo.periodFrames / (double) outro.beatRate;
        double periodB = intro.tempo.periodFrames / (double) intro.beatRate;
        org.junit.Assert.assertEquals("entry − cut 必须等于两个交接词之差（±各一个整拍）",
                31.6 - 152.0, newEntry - newCut, periodA + periodB + 1e-9);
    }
}
