package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>note 要说清「为什么不是别的候选」</b>（用户路线图 W5）。
 *
 * <p>只念九项分数回答不了用户真正会问的那一句「为什么不选另一处」：那需要把
 * <b>次优</b>候选摆出来，并指出两者差在哪一项上——差 0.01 是「几乎打平、靠加分项定的」，
 * 差 0.1 且集中在某一项上才是「那一项否掉了它」。
 */
public class AutoCueExplainTest {

    /** note 里必须有次优候选的点位与总分。 */
    @Test
    public void theNoteNamesTheRunnerUp() {
        AutoCue.Cue cue = AutoCue.plan(outro(), intro(), 5.0);
        assertNotNull(cue);
        System.out.println("[explain] " + cue.note);
        assertTrue("note 里应当有「次优」那一句：" + cue.note, cue.note.contains("次优"));
        assertTrue("次优那一句要给出点位（x s/y s）：" + cue.note, cue.note.contains("s/"));
    }

    /**
     * 差在哪一项必须指得准：手工拿两个候选的分数喂给 {@code runnerUpNote}，
     * 唯一差别是低频 → 那一句必须点名「低频冲突」，而且不能只说「总分低一点」。
     */
    @Test
    public void theRunnerUpSentenceBlamesTheDecidingTerm() {
        AutoCue.Score quiet = AutoCue.evaluate(outro(), intro(), 20.0, 14.0, 5.0);
        AutoCue.Score muddy = AutoCue.evaluate(outro(), intro(), 20.0, 1.0, 5.0);
        String sentence = AutoCue.runnerUpNote(quiet, muddy);
        System.out.println("[explain] 次优那一句：" + sentence);
        assertTrue("应当点名「低频冲突」：" + sentence, sentence.contains("低频冲突"));
        assertTrue("应当给出次优点位与总分：" + sentence,
                sentence.contains("s/") && sentence.contains("总分"));
    }

    /**
     * <b>打分很平时不许编故事</b>：次优与最优同点位同分时，那一句必须如实说「打分很平」，
     * 而不是硬指一项「差在这里」。第一版就是这么写的，判据的输出当场把它照出来：
     * 夹具里大量候选同分，于是日志里出现「次优 47.8s/11.9s vs 47.8s/11.9s，总分 0.80 vs 0.80」。
     */
    @Test
    public void aTieIsReportedAsATieNotAsAReason() {
        AutoCue.Score one = AutoCue.evaluate(outro(), intro(), 20.0, 14.0, 5.0);
        AutoCue.Score same = AutoCue.evaluate(outro(), intro(), 20.0, 14.0, 5.0);
        String sentence = AutoCue.runnerUpNote(one, same);
        System.out.println("[explain] 同分那一句：" + sentence);
        assertTrue("同分时应当如实说「打分很平」：" + sentence, sentence.contains("打分很平"));
        assertTrue("同分时不该硬指某一项：" + sentence, !sentence.contains("差在「"));
    }

    /** 打分本身也仍然正确：安静段胜出。 */
    @Test
    public void theQuietLowEndStillWins() {
        AutoCue.Cue cue = AutoCue.plan(outro(), intro(), 5.0);
        assertTrue("应当选安静段（≥9 s），实际 " + cue.introEntrySecs, cue.introEntrySecs >= 9.0);
    }

    // ------------------------------------------------------------------
    // 夹具：唯一变量是进场侧前 10 秒的低频
    // ------------------------------------------------------------------

    private static AutoCue.Envelope outro() {
        int hops = 60 * 50;
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.8f, 0.4f, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 60.0, null, 0, band, null, 0);
    }

    private static AutoCue.Envelope intro() {
        int hops = 80 * 50;
        int hot = 10 * 50;
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {i < hot ? 0.85f : 0.04f, 0.4f, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[hops], 80.0, null, 0, band, null, 0);
    }
}
