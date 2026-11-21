package dev.t1m3.qplayer.audio.mix;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.FileOutputStream;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * <b>对拍台自测</b>：把我们这边的决策 dump 成 JSON 写到 {@code /tmp/qplayer-dump-test/}，
 * 供 {@code tools/automix_compare.py} 用参考包重跑比对。
 *
 * <p>这条判据只负责"dump 能写出来且不空"；真正的 diff=0 由 Python 侧比对给出
 * （见提交说明里的命令）。
 */
public class ReferenceDumpTest {

    @Test
    public void dump一份决策() throws Exception {
        dump("decision-test.json", 128.0, 126.0, 9, "minor", 4, "minor", 240.0, 220.0);
    }

    /**
     * <b>矩阵 dump</b>：一批不同特征的曲对，全部写到 {@code /tmp/qplayer-dump-matrix/}，
     * 交给对拍台逐模板比对 —— 单点通过不算数，要一片都过才算 port 正确。
     */
    @Test
    public void dump矩阵() throws Exception {
        double[] bpms = {128.0, 174.0, 100.0, 90.0};
        double[] bpmsB = {126.0, 87.0, 100.0, 180.0};
        int[] tonics = {9, 0, 5, 2};
        String[] modes = {"minor", "major", "minor", "major"};
        double[] vocals = {0.2, 0.9, 0.5, 0.75};
        double[] louds = {-12.0, -18.0, -9.0, -24.0};
        for (int i = 0; i < bpms.length; i++) {
            dump("matrix-" + i + ".json", bpms[i], bpmsB[i], tonics[i], modes[i],
                    (tonics[i] + 7) % 12, modes[i], 240.0 + i * 10, 220.0 + i * 5,
                    vocals[i], louds[i]);
        }
    }

    private static void dump(String name, double bpmA, double bpmB, int tonicA, String modeA,
                             int tonicB, String modeB, double durA, double durB)
            throws Exception {
        dump(name, bpmA, bpmB, tonicA, modeA, tonicB, modeB, durA, durB, 0.5, -12.0);
    }

    private static void dump(String name, double bpmA, double bpmB, int tonicA, String modeA,
                             int tonicB, String modeB, double durA, double durB,
                             double vocal, double loud) throws Exception {
        ReferenceTrack a = new ReferenceTrack("A-" + name, durA);
        a.feature("bpm", bpmA).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", loud).feature("energy", 0.6)
                .feature("vocal_activity", vocal)
                .feature("acousticness", 0.2).feature("danceability", 0.8)
                .feature("melodicness", 0.6).feature("key_confidence", 0.8)
                .feature("phrase_confidence", 0.8).feature("timing_confidence", 0.95)
                .feature("meter", 4.0).key(tonicA, modeA).text("genre", "electronic");
        double beatA = 60.0 / bpmA;
        for (int i = 0; i < 8; i++) {
            double end = durA - i * 4 * beatA;
            double start = Math.max(0.0, end - 4 * beatA);
            if (end - start < 0.5) {
                break;
            }
            a.region(new ReferenceTrack.Region("outro" + i, start, end));
        }
        for (double t = 0.0; t < durA; t += 4 * beatA) {
            a.downbeat(t);
        }
        ReferenceTrack b = new ReferenceTrack("B-" + name, durB);
        b.feature("bpm", bpmB).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", loud).feature("energy", 0.6)
                .feature("vocal_activity", vocal)
                .feature("acousticness", 0.2).feature("danceability", 0.8)
                .feature("melodicness", 0.6).feature("key_confidence", 0.8)
                .feature("phrase_confidence", 0.8).feature("timing_confidence", 0.95)
                .feature("meter", 4.0).key(tonicB, modeB).text("genre", "electronic");
        double beatB = 60.0 / bpmB;
        for (int i = 1; i <= 8; i++) {
            double start = 0.0;
            double end = Math.min(durB, i * 4 * beatB);
            if (end - start < 0.5) {
                break;
            }
            b.region(new ReferenceTrack.Region("intro" + i, start, end));
        }
        for (double t = 0.0; t < durB; t += 4 * beatB) {
            b.downbeat(t);
        }

        long seed = ReferenceDecision.defaultSeed(a.id, b.id);
        ReferenceDecision.Result result = ReferenceDecision.plan(a, b, new ReferenceLimits(), seed, true);
        String json = ReferenceDump.decision(a, b, seed, result);
        assertTrue("dump 不能为空", json.length() > 200);
        File dir = new File("/tmp/qplayer-dump-matrix");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File out = new File(dir, name);
        Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
        assertTrue("dump 文件必须写出来", out.length() > 200);
    }

    /** 边界矩阵：低拍可信度 / 高原声度 / 低舞曲度 / 缺调性 / 短曲 / 大响度差。 */
    @Test
    public void dump边界矩阵() throws Exception {
        edge("edge-low-beat.json", 120.0, 121.0, 0, "major", 0, "major", 200.0, 200.0,
                0.5, -12.0, -12.0, 0.4, 0.9, 0.2, 0.8, true, true);
        edge("edge-acoustic.json", 120.0, 122.0, 2, "major", 2, "major", 200.0, 200.0,
                0.5, -12.0, -12.0, 0.9, 0.8, 0.9, 0.8, true, true);
        edge("edge-low-dance.json", 120.0, 123.0, 4, "minor", 4, "minor", 200.0, 200.0,
                0.5, -12.0, -12.0, 0.9, 0.2, 0.2, 0.8, true, true);
        edge("edge-no-key.json", 110.0, 111.0, 0, "major", 0, "major", 210.0, 205.0,
                0.6, -14.0, -20.0, 0.9, 0.8, 0.2, 0.8, false, true);
        edge("edge-short.json", 130.0, 132.0, 5, "minor", 7, "minor", 60.0, 55.0,
                0.4, -10.0, -10.0, 0.9, 0.8, 0.2, 0.8, true, true);
        edge("edge-loud.json", 140.0, 141.0, 9, "minor", 9, "minor", 200.0, 200.0,
                0.3, -6.0, -30.0, 0.9, 0.8, 0.2, 0.8, true, true);
        edge("edge-low-keyconf.json", 100.0, 150.0, 3, "major", 8, "minor", 200.0, 200.0,
                0.7, -12.0, -16.0, 0.9, 0.8, 0.2, 0.2, true, true);
    }

    private static void edge(String name, double bpmA, double bpmB, int tonicA, String modeA,
                             int tonicB, String modeB, double durA, double durB,
                             double vocal, double loudA, double loudB,
                             double beatConf, double dance, double acoustic,
                             double keyConf, boolean withKey, boolean withDownbeats)
            throws Exception {
        ReferenceTrack a = new ReferenceTrack("A-" + name, durA);
        a.feature("bpm", bpmA).feature("beat_confidence", beatConf)
                .feature("beat_stability", beatConf).feature("loudness_db", loudA)
                .feature("energy", 0.6).feature("vocal_activity", vocal)
                .feature("acousticness", acoustic).feature("danceability", dance)
                .feature("melodicness", 0.6).feature("key_confidence", keyConf)
                .feature("phrase_confidence", 0.8).feature("timing_confidence", 0.95)
                .feature("meter", 4.0).text("genre", "electronic");
        if (withKey) {
            a.key(tonicA, modeA);
        }
        double beatA = 60.0 / bpmA;
        for (int i = 0; i < 8; i++) {
            double end = durA - i * 4 * beatA;
            double start = Math.max(0.0, end - 4 * beatA);
            if (end - start < 0.5) {
                break;
            }
            a.region(new ReferenceTrack.Region("outro" + i, start, end));
        }
        if (withDownbeats) {
            for (double t = 0.0; t < durA; t += 4 * beatA) {
                a.downbeat(t);
            }
        }
        ReferenceTrack b = new ReferenceTrack("B-" + name, durB);
        b.feature("bpm", bpmB).feature("beat_confidence", beatConf)
                .feature("beat_stability", beatConf).feature("loudness_db", loudB)
                .feature("energy", 0.6).feature("vocal_activity", vocal)
                .feature("acousticness", acoustic).feature("danceability", dance)
                .feature("melodicness", 0.6).feature("key_confidence", keyConf)
                .feature("phrase_confidence", 0.8).feature("timing_confidence", 0.95)
                .feature("meter", 4.0).text("genre", "electronic");
        if (withKey) {
            b.key(tonicB, modeB);
        }
        double beatB = 60.0 / bpmB;
        for (int i = 1; i <= 8; i++) {
            double end = Math.min(durB, i * 4 * beatB);
            if (end < 0.5) {
                break;
            }
            b.region(new ReferenceTrack.Region("intro" + i, 0.0, end));
        }
        if (withDownbeats) {
            for (double t = 0.0; t < durB; t += 4 * beatB) {
                b.downbeat(t);
            }
        }
        writeDecision(name, a, b);
    }

    private static void writeDecision(String name, ReferenceTrack a, ReferenceTrack b)
            throws Exception {
        long seed = ReferenceDecision.defaultSeed(a.id, b.id);
        ReferenceDecision.Result result = ReferenceDecision.plan(a, b, new ReferenceLimits(), seed, true);
        String json = ReferenceDump.decision(a, b, seed, result);
        File dir = new File("/tmp/qplayer-dump-matrix");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File out = new File(dir, name);
        Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
        assertTrue("dump 文件必须写出来", out.length() > 200);
    }

    /**
     * <b>区域级特征矩阵</b>：参考包的 region 可以带自己的特征（analysis_part），
     * 而且 region 的键会覆盖曲目级同名键。这里专门造几个"区域与曲目不一致"的样本，
     * 用来验证合并顺序与"调性优先取 region、否则退回曲目"的口径。
     */
    @Test
    public void dump区域特征矩阵() throws Exception {
        // ① 出场区比曲目级更轻（region 覆盖 loudness/energy）
        ReferenceTrack a1 = base("A-region-1", 200.0, 120.0, 0, "major");
        a1.region(new ReferenceTrack.Region("outro", 180.0, 200.0)
                .with("loudness_db", -22.0).with("energy", 0.2));
        ReferenceTrack b1 = base("B-region-1", 200.0, 121.0, 0, "major");
        b1.region(new ReferenceTrack.Region("intro", 0.0, 20.0)
                .with("loudness_db", -8.0).with("energy", 0.9));
        writeDecision("region-1.json", a1, b1);

        // ② region 自带调性（与曲目级不同 ⇒ 应以 region 为准）
        ReferenceTrack a2 = base("A-region-2", 200.0, 120.0, 0, "major");
        a2.region(new ReferenceTrack.Region("outro", 180.0, 200.0)
                .with("key_tonic", 9).with("key_mode_major", 0.0).with("key_confidence", 0.9));
        ReferenceTrack b2 = base("B-region-2", 200.0, 120.0, 9, "minor");
        b2.region(new ReferenceTrack.Region("intro", 0.0, 20.0)
                .with("key_tonic", 9).with("key_mode_major", 0.0).with("key_confidence", 0.9));
        writeDecision("region-2.json", a2, b2);

        // ③ region 没有调性、曲目级有（退回曲目级）
        ReferenceTrack a3 = base("A-region-3", 200.0, 128.0, 4, "minor");
        a3.region(new ReferenceTrack.Region("outro", 180.0, 200.0).with("loudness_db", -14.0));
        ReferenceTrack b3 = base("B-region-3", 200.0, 126.0, 4, "minor");
        b3.region(new ReferenceTrack.Region("intro", 0.0, 20.0).with("loudness_db", -13.0));
        writeDecision("region-3.json", a3, b3);
    }

    private static ReferenceTrack base(String id, double duration, double bpm, int tonic, String mode) {
        ReferenceTrack t = new ReferenceTrack(id, duration);
        t.feature("bpm", bpm).feature("beat_confidence", 0.9).feature("beat_stability", 0.9)
                .feature("loudness_db", -12.0).feature("energy", 0.6).feature("vocal_activity", 0.5)
                .feature("acousticness", 0.2).feature("danceability", 0.8).feature("melodicness", 0.6)
                .feature("key_confidence", 0.8).feature("phrase_confidence", 0.8)
                .feature("timing_confidence", 0.95).feature("meter", 4.0)
                .key(tonic, mode).text("genre", "electronic");
        double beat = 60.0 / bpm;
        for (double at = 0.0; at < duration; at += 4 * beat) {
            t.downbeat(at);
        }
        return t;
    }
}
