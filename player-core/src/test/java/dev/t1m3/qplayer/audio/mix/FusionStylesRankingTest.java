package dev.t1m3.qplayer.audio.mix;

import java.io.File;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.t1m3.qplayer.audio.decode.Decoder;

public class FusionStylesRankingTest {
    @BeforeClass
    public static void install() {
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, Map<String, String> headers) {
                return TestWavDecoder.open(source);
            }
        });
    }

    @Test
    public void 合成对拍曲目能看到表现模式() {
        ReferenceTrack a = base("A", 240.0, 128.0, 9, "minor");
        ReferenceTrack b = base("B", 220.0, 126.0, 4, "minor");
        ReferenceDecision.Result r = ReferenceDecision.plan(a, b, new ReferenceLimits(),
                ReferenceDecision.defaultSeed("A", "B"), true, FusionStyles.PROFILES, true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.ranking.size() && i < 10; i++) {
            ReferenceDecision.Candidate c = r.ranking.get(i);
            sb.append(c.templateId).append('=').append(String.format(java.util.Locale.ROOT, "%.2f", c.score));
            if (!c.eligible) {
                sb.append('✗');
            }
            sb.append(' ');
        }
        System.out.println("probe synthetic 128/126 → " + sb);
        for (ReferenceDecision.Candidate c : r.ranking) {
            if (c.templateId >= 900) {
                org.junit.Assert.assertTrue("我们的接法必须能通过参考包闸门："
                                + c.templateId + " " + c.rejectedReasons,
                        c.eligible);
            }
        }
        for (ReferenceDecision.Candidate c : r.ranking) {
            if (c.templateId >= 900) {
                System.out.println("probe our " + c.templateId + " eligible=" + c.eligible
                        + " score=" + String.format(java.util.Locale.ROOT, "%.2f", c.score)
                        + " reasons=" + c.rejectedReasons);
            }
        }
        org.junit.Assert.assertNotNull("必须选出候选", r.chosen);
        System.out.println("probe chosen = " + (r.chosen == null ? "null"
                : r.chosen.templateId + " 「" + r.chosen.templateName + "」 tier " + r.chosen.tier));
    }

    private static ReferenceTrack base(String id, double duration, double bpm, int tonic, String mode) {
        ReferenceTrack t = new ReferenceTrack(id, duration);
        t.feature("bpm", bpm).feature("beat_confidence", 0.95).feature("beat_stability", 0.95)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.3)
                .feature("acousticness", 0.2).feature("danceability", 0.85).feature("melodicness", 0.6)
                .feature("key_confidence", 0.85).feature("phrase_confidence", 0.85)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(tonic, mode).text("genre", "electronic");
        double beat = 60.0 / bpm;
        for (double at = 0.0; at < duration; at += 4 * beat) {
            t.downbeat(at);
        }
        t.region(new ReferenceTrack.Region("outro", duration - 32.0, duration));
        t.region(new ReferenceTrack.Region("intro", 0.0, 32.0));
        return t;
    }

    @Test
    public void probe() {
        int[][] pairs = {{2, 3}, {3, 4}, {5, 6}};
        for (int[] pair : pairs) {
            String a = "/tmp/qplayer-real/wav/song-" + pair[0] + ".wav";
            String b = "/tmp/qplayer-real/wav/song-" + pair[1] + ".wav";
            if (!new File(a).isFile() || !new File(b).isFile()) {
                continue;
            }
            AutoCue.Envelope ea = AutoCue.scan(a, null);
            AutoCue.Envelope eb = AutoCue.scan(b, null);
            ReferenceTrack ta = ReferenceBridge.track("A", ea,
                    ea.tempo == null ? 0.0 : ea.tempo.confidence, 0.5, true);
            ReferenceTrack tb = ReferenceBridge.track("B", eb,
                    eb.tempo == null ? 0.0 : eb.tempo.confidence, 0.5, false);
            ReferenceDecision.Result r = ReferenceDecision.plan(ta, tb,
                    new ReferenceLimits(), ReferenceDecision.defaultSeed("A", "B"), true,
                    FusionStyles.PROFILES, true);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.ranking.size() && i < 8; i++) {
                sb.append(r.ranking.get(i).templateId).append('=')
                        .append(String.format(java.util.Locale.ROOT, "%.2f", r.ranking.get(i).score))
                        .append(' ');
            }
            System.out.println("probe " + pair[0] + "-" + pair[1] + " bpm="
                    + (ea.tempo == null ? "null" : ea.tempo.bpm) + " → " + sb);
        }
    }
}
