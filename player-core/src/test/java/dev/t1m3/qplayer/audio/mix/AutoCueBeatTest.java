package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 「自动点位」的<strong>拍点对齐</strong>：候选点落在拍上，两侧的拍在融合处重合。
 *
 * <p>素材是合成的点击轨（每 0.5 秒一个宽带脉冲 = 120 BPM），所以拍的周期与相位是可算的，
 * 断言能写死到几十毫秒。另有一条把「流式谱通量」与 {@link Analysis#onsetEnvelope}
 * <strong>逐点对拍</strong>——流式那份是为常数内存照抄的，抄错一个取帧偏移，
 * 拍相位就会整体挪一段，而对齐正是靠相位。
 */
public class AutoCueBeatTest {

    private static final int RATE = 22_050;

    /** 造一条点击轨：每 {@code periodSecs} 秒一个衰减的宽带脉冲（模拟鼓点），底下垫一点噪声。 */
    private static float[] clickTrack(double seconds, double periodSecs, double firstClickSecs) {
        int n = (int) Math.round(seconds * RATE);
        float[] pcm = new float[n];
        java.util.Random random = new java.util.Random(7);
        for (double t = firstClickSecs; t < seconds; t += periodSecs) {
            int start = (int) Math.round(t * RATE);
            for (int i = 0; i < 700 && start + i < n; i++) {
                float decay = (float) Math.exp(-i / 120.0);
                pcm[start + i] += (random.nextFloat() * 2f - 1f) * decay;
            }
        }
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] += (random.nextFloat() * 2f - 1f) * 0.002f;
        }
        return pcm;
    }

    // ------------------------------------------------------------------
    // 流式谱通量 vs Analysis 的整段实现
    // ------------------------------------------------------------------

    @Test
    public void streamingOnsetEnvelopeMatchesTheBatchOne() {
        float[] pcm = clickTrack(12.0, 0.5, 0.1);
        Analysis.Onsets batch = Analysis.onsetEnvelope(pcm, RATE);

        AutoCue.OnsetFlux flux = AutoCueTestSupport.stream();
        for (float sample : pcm) {
            flux.push(sample);
        }
        float[] streamed = flux.envelopeValues();

        assertEquals("帧数必须一致", batch.envelope.length, streamed.length);
        float maxDiff = 0f;
        for (int i = 0; i < batch.envelope.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(batch.envelope[i] - streamed[i]));
        }
        assertTrue("流式与整段的起始包络必须逐点一致（最大差 " + maxDiff + "）", maxDiff < 1e-5f);
    }

    @Test
    public void tempoOfAClickTrackIsTheClickRate() {
        float[] pcm = clickTrack(30.0, 0.5, 0.1);
        Analysis.Tempo tempo = Analysis.estimateTempo(Analysis.onsetEnvelope(pcm, RATE), RATE);
        assertNotNull("点击轨必须能测出速度", tempo);
        assertEquals("每 0.5 秒一拍 = 120 BPM", 120.0f, tempo.bpm, 3.0f);
    }

    // ------------------------------------------------------------------
    // 候选落在拍上（走真正的 scan）
    // ------------------------------------------------------------------

    @Test
    public void scanFindsTheClickGrid() {
        AutoCue.Envelope env = AutoCueTestSupport.scan(clickTrack(30.0, 0.5, 0.1), RATE);
        assertTrue("扫描应当测出拍点栅格", env.hasBeatGrid());
        double[] beats = env.beatsIn(4.0, 6.0);
        assertTrue("4..6 秒里应当有几拍，实际 " + beats.length, beats.length >= 3);
        for (double beat : beats) {
            assertTrue("拍点要落在区间里：" + beat, beat >= 4.0 && beat <= 6.0);
            double phase = (beat - 0.1) % 0.5;
            double error = Math.min(Math.abs(phase), 0.5 - Math.abs(phase));
            assertTrue("拍点偏离真实栅格太多：" + beat + "（误差 " + error + " s）", error < 0.04);
        }
        for (int i = 1; i < beats.length; i++) {
            assertEquals("相邻拍之间应当是一个周期", 0.5, beats[i] - beats[i - 1], 0.03);
        }
    }

    @Test
    public void planPrefersBeatAlignedPointsOnBothSides() {
        // 出场：240 秒的点击轨，180 秒之后变轻（尾声）；进场：200 秒点击轨
        float[] outroPcm = clickTrack(240.0, 0.5, 0.1);
        for (int i = (int) (180 * RATE); i < outroPcm.length; i++) {
            outroPcm[i] *= 0.15f;
        }
        AutoCue.Envelope outro = AutoCueTestSupport.scan(outroPcm, RATE);
        AutoCue.Envelope intro = AutoCueTestSupport.scan(clickTrack(200.0, 0.5, 0.1), RATE);
        assertTrue("出场曲应当测出拍点", outro.hasBeatGrid());
        assertTrue("进场曲应当测出拍点", intro.hasBeatGrid());

        AutoCue.Cue cue = AutoCue.plan(outro, intro, 15.0);
        assertNotNull(cue);
        assertTrue("出场点必须落在拍上：" + cue.outroCutSecs, onBeat(outro, cue.outroCutSecs));
        // **2026-10-06 按证据重述**（第一阶段工作二）：进场点在吸拍之后还会做一次
        // **采样级相位对齐**（±PHASE_ALIGN_MAX_SECS s 的互相关，窗口 > 4 s 时启用）——
        // 用户原话「我要求你使用现有硬算中能用到的最高精度方式给我对拍」。
        // 所以"落在拍上"的容差从 40 ms 扩到 **相位精修半径 + 一拍内的判定**：
        // 精修是有界的，而且它只朝"波形真的对上"的方向动。
        assertTrue("进场点必须落在拍上（允许采样级相位精修的 ±"
                        + AutoCue.PHASE_ALIGN_MAX_SECS + " s）：" + cue.introEntrySecs,
                onBeatWithin(intro, cue.introEntrySecs,
                        40 + (int) Math.round(AutoCue.PHASE_ALIGN_MAX_SECS * 1000.0)));
        assertTrue("说明里要写清拍点对齐：" + cue.note, cue.note.contains("拍点对齐"));
        // 硬边界照旧：不许选到开头或完整结尾
        assertTrue("出场点不许太靠前：" + cue.outroCutSecs, cue.outroCutSecs >= 48.0 - 0.1);
        assertTrue("出场点不许等于完整结尾：" + cue.outroCutSecs, cue.outroCutSecs <= 240.0 - 15.0 - 2.0 + 0.1);
    }

    /** 同 {@link #onBeat}，但容差可调（见上面那条判据的重述理由）。 */
    private static boolean onBeatWithin(AutoCue.Envelope env, double secs, int toleranceMs) {
        double p = env.tempo.periodFrames / (double) env.beatRate;
        double first = env.beatsIn(0.0, p * 2.0)[0];
        double d = Math.abs(secs - first) % p;
        double off = Math.min(d, p - d);
        return off <= toleranceMs / 1000.0;
    }

    /** 这个时刻是不是贴着某一拍（40 ms 以内）。 */
    private static boolean onBeat(AutoCue.Envelope env, double secs) {
        for (double beat : env.beatsIn(secs - 0.2, secs + 0.2)) {
            if (Math.abs(beat - secs) < 0.04) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void withoutTempoItFallsBackToTheHopGrid() {
        // 纯噪声：没有可辨节拍 → 必须如实退回 20 ms 网格，而不是编一个 BPM 出来
        java.util.Random random = new java.util.Random(3);
        float[] noise = new float[(int) (120 * RATE)];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (random.nextFloat() * 2f - 1f) * 0.3f;
        }
        AutoCue.Envelope env = AutoCueTestSupport.scan(noise, RATE);
        assertFalse("噪声不该测出拍点栅格", env.hasBeatGrid());
        assertEquals("没有栅格时 beatsIn 返回空", 0, env.beatsIn(0.0, 10.0).length);

        AutoCue.Envelope other = AutoCueTestSupport.scan(noise, RATE);
        AutoCue.Cue cue = AutoCue.plan(env, other, 15.0);
        assertNotNull("仍然要能挑出点位（退回网格）", cue);
        assertTrue("说明里要如实写明退回网格：" + cue.note, cue.note.contains("20 ms 网格"));
    }
}
