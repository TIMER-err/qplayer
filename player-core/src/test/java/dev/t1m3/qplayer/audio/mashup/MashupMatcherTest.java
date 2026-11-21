package dev.t1m3.qplayer.audio.mashup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MashupMatcher}：<b>该拒的拒、该降权的降权、结论要说得清</b>。
 *
 * <p>判据只钉闸门与分项，不钉总分的小数点——总分是排序用的，钉死它等于把"权重"变成
 * 不可改的东西，而那正是后面调听感时要动的地方。
 */
public class MashupMatcherTest {

    @Test
    public void acceptsCloseTempoAndSameKey() {
        TrackProfile a = Profiles.of("a", 120, 0, "major", 200, 0.2);
        TrackProfile b = Profiles.of("b", 124, 2, "major", 200, 0.2);
        MashupMatcher.Result r = MashupMatcher.match(a, b);
        assertTrue("速度差 3.3%、调性只差两个半音，应当通过：" + r, r.accepted);
        assertEquals(124.0 / 120.0, r.tempoRatio, 1e-9);
        assertEquals(1, r.aStride);
        assertEquals(1, r.bStride);
        assertEquals("主音给出的结论是 +2 半音", 2, r.semitones);
        assertTrue("同调式应当拿到很高的调性分：" + r.keyFit, r.keyFit > 0.95);
        assertTrue("总分应当明显偏正面：" + r.score, r.score > 0.6);
        assertTrue(r.renderableWholeTrack());
    }

    @Test
    public void halfTimePairIsTreatedAsTheSameTempo() {
        // 87 vs 174：直接相除是 2.0 倍，但音乐上是同一速度
        TrackProfile slow = Profiles.of("slow", 87, 0, "major", 200, 0.2);
        TrackProfile fast = Profiles.of("fast", 174, 0, "major", 200, 0.2);
        MashupMatcher.Result r = MashupMatcher.match(slow, fast);
        assertTrue("half-time 必须能过：" + r, r.accepted);
        assertEquals(1.0, r.tempoRatio, 1e-9);
        assertEquals("A 不变速", 1, r.aStride);
        assertEquals("B 用每两拍当锚点", 2, r.bStride);
        assertTrue(r.renderableWholeTrack());
    }

    @Test
    public void rejectsTempoGapBeyondTheGate() {
        TrackProfile a = Profiles.of("a", 120, 0, "major", 200, 0.2);
        TrackProfile b = Profiles.of("b", 150, 0, "major", 200, 0.2);
        MashupMatcher.Result r = MashupMatcher.match(a, b);
        assertFalse("25% 的速度差必须拒绝（half-time 也救不了）", r.accepted);
        assertTrue("原因要说得清：" + r.verdict, r.verdict.contains("速度差"));
    }

    @Test
    public void middleTempoGapIsAcceptedButOnlyForShortOutput() {
        TrackProfile a = Profiles.of("a", 120, 0, "major", 200, 0.2);
        TrackProfile b = Profiles.of("b", 132, 0, "major", 200, 0.2);   // 10%
        MashupMatcher.Result r = MashupMatcher.match(a, b);
        assertTrue("10% 在闸门内：" + r, r.accepted);
        assertFalse("但超过 ±8%，只能做短版本", r.renderableWholeTrack());
    }

    @Test
    public void rejectsIncompatibleKeys() {
        TrackProfile a = withChroma("a", 120, 0, Profiles.singlePitchChroma(0));
        TrackProfile b = withChroma("b", 120, 6, Profiles.singlePitchChroma(6));
        MashupMatcher.Result r = MashupMatcher.match(a, b);
        assertFalse("三全音关系的两个单音，怎么转都对不上", r.accepted);
        assertTrue("原因要说得清：" + r.verdict, r.verdict.contains("调性"));
    }

    @Test
    public void unknownKeyIsNeutralNotAGuess() {
        TrackProfile a = Profiles.withoutKey("a", 120, 200);
        TrackProfile b = Profiles.withoutKey("b", 122, 200);
        MashupMatcher.Result r = MashupMatcher.match(a, b);
        assertTrue(r.accepted);
        assertEquals("未知调性不猜移调量", 0, r.semitones);
        assertEquals("未知调性按中性 0.5 记", 0.5, r.keyFit, 1e-9);
        assertTrue("要把这件事写进备注", hasNote(r, "调性未知"));
    }

    @Test
    public void rejectsTooShortTracks() {
        TrackProfile a = Profiles.of("a", 120, 0, "major", 60, 0.2);
        TrackProfile b = Profiles.of("b", 120, 0, "major", 200, 0.2);
        MashupMatcher.Result r = MashupMatcher.match(a, b);
        assertFalse(r.accepted);
        assertTrue(r.verdict.contains("太短"));
    }

    @Test
    public void loudnessGapDownweightsButStillAccepts() {
        TrackProfile a = Profiles.of("a", 120, 0, "major", 200, 0.2);
        TrackProfile equal = Profiles.of("b", 124, 2, "major", 200, 0.2);
        TrackProfile quiet = TrackProfile.builder("b2").durationSecs(200)
                .tempo(124, 0.8, 22_050, 22_050f * 60f / 124f, 0f)
                .loudness(-22.0)
                .key(2, "major", 0.7, Profiles.chromaOf(2, "major"))
                .structure(-1, -1)
                .bars(240.0 / 124, new float[]{0.5f}, new float[]{0.5f}, new float[]{0.2f}, 0.2)
                .build();

        MashupMatcher.Result loudMatch = MashupMatcher.match(a, equal);
        MashupMatcher.Result quietMatch = MashupMatcher.match(a, quiet);
        assertTrue(quietMatch.accepted);
        assertEquals("响度差要如实报出来（A − B，正 = A 更响）", 12.0,
                quietMatch.loudnessDeltaDb, 1e-9);
        assertTrue("12 dB 的差应当降权：" + quietMatch.score + " vs " + loudMatch.score,
                quietMatch.score < loudMatch.score);
        assertTrue(hasNote(quietMatch, "响度差"));
    }

    @Test
    public void closerTempoScoresHigher() {
        TrackProfile a = Profiles.of("a", 120, 0, "major", 200, 0.2);
        MashupMatcher.Result near = MashupMatcher.match(a, Profiles.of("b1", 122, 0, "major", 200, 0.2));
        MashupMatcher.Result far = MashupMatcher.match(a, Profiles.of("b2", 127, 0, "major", 200, 0.2));
        assertTrue(near.accepted);
        assertTrue(far.accepted);
        assertTrue("越接近 1.0 分越高：" + near.score + " vs " + far.score,
                near.score > far.score);
    }

    @Test
    public void relativeSemitonesTakesTheShorterDirection() {
        assertEquals(0, MashupMatcher.relativeSemitones(0, 0));
        assertEquals(1, MashupMatcher.relativeSemitones(0, 1));
        assertEquals(5, MashupMatcher.relativeSemitones(0, 5));
        assertEquals(-1, MashupMatcher.relativeSemitones(0, 11));
        assertEquals(-5, MashupMatcher.relativeSemitones(5, 0));
        assertEquals(2, MashupMatcher.relativeSemitones(7, 9));
    }

    @Test
    public void missingTempoIsRejectedWithAReason() {
        TrackProfile noTempo = TrackProfile.builder("x").durationSecs(200).build();
        MashupMatcher.Result r = MashupMatcher.match(noTempo, Profiles.of("b", 120, 0, "major", 200, 0.2));
        assertFalse(r.accepted);
        assertTrue(r.verdict.contains("A 没有"));
    }

    private static TrackProfile withChroma(String key, double bpm, int tonic, float[] chroma) {
        return TrackProfile.builder(key)
                .durationSecs(200)
                .tempo(bpm, 0.8, 22_050, (float) (22_050.0 * 60.0 / bpm), 0f)
                .loudness(-10)
                .key(tonic, "major", 0.7, chroma)
                .structure(-1, -1)
                .bars(240.0 / bpm, new float[]{0.5f}, new float[]{0.5f}, new float[]{0.1f}, 0.1)
                .build();
    }

    private static boolean hasNote(MashupMatcher.Result r, String fragment) {
        for (String note : r.notes) {
            if (note.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
