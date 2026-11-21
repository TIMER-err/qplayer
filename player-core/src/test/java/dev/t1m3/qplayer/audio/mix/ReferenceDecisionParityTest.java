package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * <b>ReferenceParityTest（选点全链路）</b>：{@link ReferenceDecision} 的 chosen 必须与参考包
 * {@code scoring.plan_transition(selection="weighted")} 逐位一致。
 *
 * <p>每条期望值都是 Python 侧真实跑出来的（模板 id / tier / 分数 / 出场与进场区间），
 * 容差 1e-9；只要闸门、因子顺序、时长规则、tier、随机序列有一处不同，这条就会红。
 */
public class ReferenceDecisionParityTest {

    private static final ReferenceLimits LIMITS = new ReferenceLimits();

    @Test
    public void 选点与参考包逐位一致() {
        // ── ① 两首舞曲：拍与调都合，带候选区与 downbeats ──
        ReferenceTrack a1 = new ReferenceTrack("A", 240.0);
        a1.feature("bpm", 128.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.5)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.6)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(9, "minor").text("genre", "electronic")
                .region(new ReferenceTrack.Region("outro", 200.0, 240.0));
        for (int i = 0; i < 22; i++) {
            a1.downbeat(200.0 + 1.875 * i);
        }
        ReferenceTrack b1 = new ReferenceTrack("B", 220.0);
        b1.feature("bpm", 126.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.5)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.6)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(4, "minor").text("genre", "electronic")
                .region(new ReferenceTrack.Region("intro", 0.0, 40.0));
        for (int i = 0; i < 22; i++) {
            b1.downbeat(1.9 * i);
        }

        // ── ② 拍速差太大（174 vs 100） ──
        ReferenceTrack a2 = new ReferenceTrack("A2", 200.0);
        a2.feature("bpm", 174.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.5)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.6)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(0, "major").text("genre", "pop");
        ReferenceTrack b2 = new ReferenceTrack("B2", 200.0);
        b2.feature("bpm", 100.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.5)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.6)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(0, "major").text("genre", "pop");

        // ── ③ 调性不合但旋律弱（允许通融） ──
        ReferenceTrack a3 = new ReferenceTrack("A3", 180.0);
        a3.feature("bpm", 120.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.5)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.1)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(0, "major").text("genre", "electronic");
        ReferenceTrack b3 = new ReferenceTrack("B3", 180.0);
        b3.feature("bpm", 121.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.5)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.1)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(3, "major").text("genre", "electronic");

        // ── ④ 人声冲突拉满 ──
        ReferenceTrack a4 = new ReferenceTrack("A4", 210.0);
        a4.feature("bpm", 140.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 1.0)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.6)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(5, "minor").text("genre", "electronic");
        ReferenceTrack b4 = new ReferenceTrack("B4", 210.0);
        b4.feature("bpm", 141.0).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 1.0)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.6)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(5, "minor").text("genre", "electronic");

        check(a1, b1, 1L, 9, 18.551574700831846,
                235.625, 239.375, 0.0, 3.8095238095238098);   // dance-match seed=1
        check(a1, b1, 42L, 5, 3.9006720830235118,
                237.5, 239.375, 0.0, 1.9047619047619049);   // dance-match seed=42
        check(a1, b1, 20261121L, 12, 7.252892821826811,
                231.875, 239.375, 0.0, 7.6190476190476195);   // dance-match seed=20261121
        check(a2, b2, 1L, 3, 6.496,
                196.0, 200.0, 0.0, 4.0);   // tempo-mismatch seed=1
        check(a2, b2, 42L, 44, 6.244,
                196.0, 200.0, 0.0, 4.0);   // tempo-mismatch seed=42
        check(a2, b2, 20261121L, 33, 6.244,
                196.0, 200.0, 0.0, 4.0);   // tempo-mismatch seed=20261121
        check(a3, b3, 1L, 3, 6.476000000000001,
                176.0, 180.0, 0.0, 4.0);   // weak-melody seed=1
        check(a3, b3, 42L, 44, 6.224,
                176.0, 180.0, 0.0, 4.0);   // weak-melody seed=42
        check(a3, b3, 20261121L, 33, 6.224,
                176.0, 180.0, 0.0, 4.0);   // weak-melody seed=20261121
        check(a4, b4, 1L, 3, 1.4060000000000001,
                206.0, 210.0, 0.0, 4.0);   // vocal-clash seed=1
        check(a4, b4, 42L, 44, 1.358,
                206.0, 210.0, 0.0, 4.0);   // vocal-clash seed=42
        check(a4, b4, 20261121L, 44, 1.358,
                206.0, 210.0, 0.0, 4.0);   // vocal-clash seed=20261121
    }

    private static void check(ReferenceTrack a, ReferenceTrack b, long seed, int templateId,
                              double score, double outStart, double outEnd,
                              double inStart, double inEnd) {
        ReferenceDecision.Result r = ReferenceDecision.plan(a, b, LIMITS, seed, true);
        assertNotNull("没有选出候选（参考包有）", r.chosen);
        assertEquals("模板 id", templateId, r.chosen.templateId);
        assertEquals("分数", score, r.chosen.score, 1e-9);
        assertEquals("出场起点", outStart, r.chosen.outStart, 1e-9);
        assertEquals("出场终点", outEnd, r.chosen.outEnd, 1e-9);
        assertEquals("进场起点", inStart, r.chosen.inStart, 1e-9);
        assertEquals("进场终点", inEnd, r.chosen.inEnd, 1e-9);
    }
}
