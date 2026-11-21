package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>"强度加权重叠"与"人声三分类"</b>（用户 2026-10-07 点名要抄的两条，本类钉它们的语义）。
 *
 * <p>都用**手搭包络**（同包 ⇒ 直接用包络的构造器 ✓）：不依赖任何素材 ✓，跑起来是毫秒级 ✓。
 */
public class VocalKindAndStrengthTest {

    private static final int RATE = 48_000;
    private static final double HOP = AutoCue.HOP_SECS;

    /** 手搭一条包络：中频带 + 起音 + 色度都由调用方给。 */
    private static AutoCue.Envelope envelope(float[] midBand, float[] onset,
                                             float[] chromaFramesFlat, int chromaFrames,
                                             float periodSecs, float phaseSecs) {
        float[][] band = {new float[midBand.length], midBand, new float[midBand.length]};
        Analysis.Tempo tempo = periodSecs > 0f
                ? new Analysis.Tempo(60f / periodSecs, periodSecs * RATE, phaseSecs * RATE, 0.9f)
                : null;
        AutoCue.Envelope env = new AutoCue.Envelope(new float[midBand.length], onset,
                midBand.length * HOP, tempo, RATE, band, new float[midBand.length], RATE,
                -1f, -1f, null);
        env.chroma = chromaFramesFlat;
        env.chromaFrames = chromaFrames;
        env.chromaHopSecs = HOP;
        return env;
    }

    /** 常数色度（音高很稳 ⇒ 唱）。 */
    private static float[] steadyChroma(int frames) {
        float[] out = new float[frames * 12];
        for (int f = 0; f < frames; f++) {
            out[f * 12] = 1f;                 // 每帧都是同一个音级 ⇒ 相邻帧余弦 = 1
        }
        return out;
    }

    /** 乱跳色度（音高不稳 ⇒ 不像唱）。 */
    private static float[] jitterChroma(int frames) {
        float[] out = new float[frames * 12];
        for (int f = 0; f < frames; f++) {
            out[f * 12 + (f % 12)] = 1f;      // 每帧换一个音级 ⇒ 余弦 ≈ 0
        }
        return out;
    }

    @Test
    public void strengthOverlapIsMaxOfMinNotSeconds() {
        int frames = 200;                     // 4 秒
        float[] loud = new float[frames];
        float[] quiet = new float[frames];
        for (int i = 0; i < frames; i++) {
            loud[i] = 0.8f;
            quiet[i] = 0.02f;
        }
        AutoCue.Envelope aLoud = envelope(loud, new float[frames], steadyChroma(frames), frames, 0.5f, 0f);
        AutoCue.Envelope bQuiet = envelope(quiet, new float[frames], steadyChroma(frames), frames, 0.5f, 0f);
        assertEquals("一边轻哼 ⇒ 强度加权重叠接近 0",
                0.0, AutoCue.vocalOverlapStrength(aLoud, 0.0, bQuiet, 0.0, 3.0), 0.06);
        assertEquals("两边都吼 ⇒ 接近 1",
                1.0, AutoCue.vocalOverlapStrength(aLoud, 0.0, aLoud, 0.0, 3.0), 0.06);

        // **关键性质**：A 只在前半响、B 只在后半响 ⇒ 虽然"重叠 4 秒"，但两边从不**同时**响
        float[] aHalf = new float[frames];
        float[] bHalf = new float[frames];
        for (int i = 0; i < frames; i++) {
            aHalf[i] = i < frames / 2 ? 0.8f : 0.0f;
            bHalf[i] = i < frames / 2 ? 0.0f : 0.8f;
        }
        AutoCue.Envelope a = envelope(aHalf, new float[frames], steadyChroma(frames), frames, 0.5f, 0f);
        AutoCue.Envelope b = envelope(bHalf, new float[frames], steadyChroma(frames), frames, 0.5f, 0f);
        assertEquals("错开的 4 秒 ≠ 撞车：加权重叠必须接近 0（秒数阈值分不出这个 ✗）",
                0.0, AutoCue.vocalOverlapStrength(a, 0.0, b, 0.0, 4.0), 0.06);
    }

    @Test
    public void kindSeparatesSingingFromRappingAndSpeech() {
        int frames = 300;                     // 6 秒
        float[] mid = new float[frames];
        for (int i = 0; i < frames; i++) {
            mid[i] = 0.5f;
        }
        // 唱：起音稀疏（每 1 秒一次）+ 色度稳
        float[] fewOnsets = new float[frames];
        for (int i = 20; i < frames; i += (int) Math.round(1.0 / HOP)) {
            fewOnsets[i] = 1f;
        }
        AutoCue.Envelope sing = envelope(mid, fewOnsets, steadyChroma(frames), frames, 0.5f, 0f);
        assertEquals("稀疏起音 + 音高稳 ⇒ 唱",
                AutoCue.KIND_SINGING, AutoCue.vocalKind(sing, 0.0, frames * HOP));

        // 说唱：起音密（每 0.25 s 一次）+ 踩在四分之一拍上 + 色度不稳
        float[] denseOnsets = new float[frames];
        for (int i = 10; i < frames; i += (int) Math.round(0.25 / HOP)) {
            denseOnsets[i] = 1f;
        }
        AutoCue.Envelope rap = envelope(mid, denseOnsets, jitterChroma(frames), frames, 0.5f, 0f);
        assertEquals("密集起音 + 踩在细分拍上 + 音高不稳 ⇒ 说唱",
                AutoCue.KIND_RAPPING, AutoCue.vocalKind(rap, 0.0, frames * HOP));

        // 说话：起音密，但**不踩拍**（每 0.37 s 一次，与 0.25 s 的细分格错开）
        float[] offGrid = new float[frames];
        for (int i = 10; i < frames; i += (int) Math.round(0.37 / HOP)) {
            offGrid[i] = 1f;
        }
        AutoCue.Envelope speech = envelope(mid, offGrid, jitterChroma(frames), frames, 0.5f, 0f);
        assertEquals("密集但错开拍栅格 ⇒ 说话",
                AutoCue.KIND_SPEECH, AutoCue.vocalKind(speech, 0.0, frames * HOP));
    }

    @Test
    public void missingSignalsGiveUnknownNotAGuess() {
        int frames = 300;
        float[] mid = new float[frames];
        AutoCue.Envelope noChroma = envelope(mid, new float[frames], null, 0, 0.5f, 0f);
        assertEquals("没有色度 ⇒ 未知（−1），不猜", -1,
                AutoCue.vocalKind(noChroma, 0.0, frames * HOP));
        AutoCue.Envelope shortSeg = envelope(mid, new float[frames], steadyChroma(frames),
                frames, 0.5f, 0f);
        assertEquals("段太短 ⇒ 未知（−1）", -1, AutoCue.vocalKind(shortSeg, 0.0, 0.2));
        assertTrue("没有中频带 ⇒ 强度量不出来（NaN）",
                Double.isNaN(AutoCue.vocalOverlapStrength(
                        envelope(mid, new float[frames], steadyChroma(frames), frames, 0.5f, 0f),
                        0.0, null, 0.0, 2.0)));
    }
}
