package dev.t1m3.qplayer.audio.mix;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.InvalidPathException;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.core.AudioException;

/**
 * <b>真实歌曲对拍</b>：把用户 Music 目录里的真实曲目（先用 ffmpeg 转 16 bit WAV）
 * 走一遍我们的分析层 → 参考包决策链 → dump，交给 {@code tools/automix_compare.py} 复核。
 *
 * <p>这一步验证的是：**真实数据**（真 BPM、真响度、真调性、真实候选区）下，
 * 我们的决策链与参考包是否仍逐位一致。合成样本过不代表真实样本过。
 */
public class RealSongParityTest {

    private static final String WAV_DIR = "/tmp/qplayer-real/wav";
    private static final String DUMP_DIR = "/tmp/qplayer-dump-real";

    @BeforeClass
    public static void installDecoder() {
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, Map<String, String> headers) {
                return TestWavDecoder.open(source);
            }
        });
    }

    @Test
    public void dump真实歌曲对() throws Exception {
        File dir = new File(DUMP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        int pairs = 0;
        // 6 首两两组合（不含自身）= 30 对
        for (int i = 1; i <= 6; i++) {
            for (int j = 1; j <= 6; j++) {
                if (i == j) {
                    continue;
                }
                String a = WAV_DIR + "/song-" + i + ".wav";
                String b = WAV_DIR + "/song-" + j + ".wav";
                if (!new File(a).isFile() || !new File(b).isFile()) {
                    continue;
                }
                AutoCue.Envelope envA = AutoCue.scan(a, null);
                AutoCue.Envelope envB = AutoCue.scan(b, null);
                dumpPair("real-" + i + "-" + j + ".json", "song-" + i, envA, "song-" + j, envB,
                        true, true);
                pairs++;
            }
        }
        // 真实变体：去掉 downbeats（模拟"没有拍栅格"）/ 去掉 chroma（模拟"估不出调性"）
        for (int i = 1; i <= 3; i++) {
            int j = i + 3;
            String a = WAV_DIR + "/song-" + i + ".wav";
            String b = WAV_DIR + "/song-" + j + ".wav";
            AutoCue.Envelope envA = AutoCue.scan(a, null);
            AutoCue.Envelope envB = AutoCue.scan(b, null);
            dumpPair("real-nobeat-" + i + "-" + j + ".json", "song-" + i, envA,
                    "song-" + j, envB, false, true);
            dumpPair("real-nokey-" + i + "-" + j + ".json", "song-" + i, envA,
                    "song-" + j, envB, true, false);
            pairs += 2;
        }
        System.out.println("real-dump: 写了 " + pairs + " 份到 " + DUMP_DIR);
    }

    private static void dumpPair(String name, String idA, AutoCue.Envelope envA,
                                 String idB, AutoCue.Envelope envB,
                                 boolean withDownbeats, boolean withKey) throws Exception {
        ReferenceTrack ta = ReferenceBridge.track(idA, envA,
                envA.tempo == null ? 0.0 : envA.tempo.confidence, 0.5, true);
        ReferenceTrack tb = ReferenceBridge.track(idB, envB,
                envB.tempo == null ? 0.0 : envB.tempo.confidence, 0.5, false);
        if (!withDownbeats) {
            ta.downbeats.clear();
            tb.downbeats.clear();
        }
        if (!withKey) {
            ta.keyTonic = null;
            ta.keyMode = null;
            tb.keyTonic = null;
            tb.keyMode = null;
            for (ReferenceTrack.Region r : ta.regions) {
                r.features.remove("key_tonic");
                r.features.remove("key_mode_major");
            }
            for (ReferenceTrack.Region r : tb.regions) {
                r.features.remove("key_tonic");
                r.features.remove("key_mode_major");
            }
            ta.features.remove("key_confidence");
            tb.features.remove("key_confidence");
        }
        long seed = ReferenceDecision.defaultSeed(ta.id, tb.id);
        ReferenceDecision.Result result = ReferenceDecision.plan(ta, tb,
                new ReferenceLimits(), seed, true);
        String json = ReferenceDump.decision(ta, tb, seed, result);
        File out = new File(DUMP_DIR, name);
        Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
    }

}
