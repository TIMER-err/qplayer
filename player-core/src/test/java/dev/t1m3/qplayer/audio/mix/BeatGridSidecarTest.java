package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * <b>节拍 sidecar 的契约</b>（2026-10-06 第一阶段工作二）。
 *
 * <p>用户要求：「电脑端用模型预计算……手机端直接读缓存，不需要跑模型」，
 * 以及「手机端也得具备硬算程序（DSP 不是模型），**有 sidecar 读 sidecar，没有就自己算**」。
 *
 * <p>所以这一组判据最重要的一条不是"能读能写"，而是<b>读不到时必须是 {@code null}</b>
 * ——那是"手机端自己算"这条兜底路径的入口条件。坏缓存、版本不认识、哈希对不上，
 * 三种情况都必须和"根本没有缓存"走同一条路。
 */
public class BeatGridSidecarTest {

    private static BeatGridSidecar sample() {
        return new BeatGridSidecar("abc123", 128.0,
                new double[] {0.0, 0.46875, 0.9375, 1.40625},
                new double[] {0.0, 1.875}, 0.93);
    }

    @Test
    public void roundTripsThroughText() {
        BeatGridSidecar back = BeatGridSidecar.parse(sample().toText());
        assertNotNull("自己产出的文本必须能自己解析", back);
        assertEquals(128.0, back.bpm, 1e-9);
        assertEquals(0.93, back.confidence, 1e-9);
        assertEquals("abc123", back.fileHash);
        assertArrayEquals(sample().beats, back.beats, 1e-9);
        assertArrayEquals(sample().downbeats, back.downbeats, 1e-9);
    }

    @Test
    public void unknownKeysAreIgnoredSoNewFieldsDoNotBreakOldReaders() {
        String text = sample().toText() + "genre techno\nfutureField 1 2 3\n";
        assertNotNull("将来的新字段不该让旧版读不了", BeatGridSidecar.parse(text));
    }

    @Test
    public void everyKindOfDamageMeansNoCache() {
        assertNull("null 文本", BeatGridSidecar.parse(null));
        assertNull("空文本", BeatGridSidecar.parse(""));
        assertNull("不是我们的文件", BeatGridSidecar.parse("hello 1\nbpm 128\nbeats 0 1\n"));
        assertNull("版本不认识", BeatGridSidecar.parse("qplayer-beatgrid 99\nfileHash a\nbpm 128\n"
                + "confidence 0.9\nbeats 0 1\n"));
        assertNull("缺 bpm", BeatGridSidecar.parse("qplayer-beatgrid 1\nfileHash a\n"
                + "confidence 0.9\nbeats 0 1\n"));
        assertNull("拍点不够", BeatGridSidecar.parse("qplayer-beatgrid 1\nfileHash a\nbpm 128\n"
                + "confidence 0.9\nbeats 0\n"));
        assertNull("拍点乱序", BeatGridSidecar.parse("qplayer-beatgrid 1\nfileHash a\nbpm 128\n"
                + "confidence 0.9\nbeats 1 0 2\n"));
        assertNull("数字坏了", BeatGridSidecar.parse("qplayer-beatgrid 1\nfileHash a\nbpm x\n"
                + "confidence 0.9\nbeats 0 1\n"));
        assertNull("bpm 非正", BeatGridSidecar.parse("qplayer-beatgrid 1\nfileHash a\nbpm 0\n"
                + "confidence 0.9\nbeats 0 1\n"));
    }

    @Test
    public void missingOrMismatchedCacheFallsBackToTheHardComputation() throws Exception {
        Path dir = Files.createTempDirectory("beatgrid-sidecar");
        Path audio = dir.resolve("song.mp3");
        Files.write(audio, "not really audio, but a hash is a hash".getBytes(StandardCharsets.UTF_8));

        assertNull("**没有 sidecar 时必须返回 null**（手机端接着自己算）",
                BeatGridSidecar.read(audio));

        String hash = BeatGridSidecar.hashOf(audio);
        assertNotNull(hash);
        BeatGridSidecar grid = new BeatGridSidecar(hash, 120.0, new double[] {0.0, 0.5}, null, 0.8);
        assertTrue("写缓存", BeatGridSidecar.write(audio, grid));
        BeatGridSidecar read = BeatGridSidecar.read(audio);
        assertNotNull("哈希对得上就能读到", read);
        assertEquals(120.0, read.bpm, 1e-9);

        // 音频变了（重新压制/换码率）⇒ 哈希对不上 ⇒ **当没有缓存**
        Files.write(audio, "different bytes now".getBytes(StandardCharsets.UTF_8));
        assertNull("哈希对不上时必须返回 null（旧网格会静静配错拍）",
                BeatGridSidecar.read(audio));

        // 内容坏了（截断）⇒ 同样走硬算
        Path audio2 = dir.resolve("song2.mp3");
        Files.write(audio2, "another file".getBytes(StandardCharsets.UTF_8));
        BeatGridSidecar grid2 = new BeatGridSidecar(BeatGridSidecar.hashOf(audio2), 120.0,
                new double[] {0.0, 0.5}, null, 0.8);
        assertTrue(BeatGridSidecar.write(audio2, grid2));
        Files.write(BeatGridSidecar.sidecarFor(audio2), "qplayer-beatgrid 1\nfileHa"
                .getBytes(StandardCharsets.UTF_8));
        assertNull("坏缓存 = 没有缓存", BeatGridSidecar.read(audio2));
        assertFalse("不存在的文件写不出去，但也不许抛", BeatGridSidecar.write(
                dir.resolve("nope").resolve("x.mp3"), grid2));
    }
}
