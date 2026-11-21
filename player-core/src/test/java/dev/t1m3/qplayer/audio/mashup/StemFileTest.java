package dev.t1m3.qplayer.audio.mashup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link StemFile} / {@link StemWriter}：<b>写进去的能原样读出来，坏文件当场发现</b>。
 *
 * <p>重点是"跨块读"——真实使用就是按 4096 帧随机取，几乎每次都会跨块；块边界算错
 * 的表现是"某一段声音错位 20 秒"，那种错从听感上很难反推到算术上。
 */
public class StemFileTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 100;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void roundTripsAcrossBlockBoundaries() throws IOException {
        Path file = tmp.newFile("stems.stem").toPath();
        int total = 250;                             // 2 整块 + 50 帧尾巴
        float[][] instr = ramp(total, 0.5f);
        float[][] voc = ramp(total, 0.25f);
        try (StemWriter writer = StemWriter.open(file, RATE, BLOCK, StemStore.TAG_DSP)) {
            writer.write(instr, voc, total);
            assertEquals(total, writer.framesWritten());
        }

        try (StemFile stems = StemFile.open(file)) {
            assertEquals(RATE, stems.sampleRate());
            assertEquals(total, stems.frames());
            assertEquals(BLOCK, stems.blockFrames());
            assertEquals(StemStore.TAG_DSP, stems.tag());
            assertEquals(StemFile.HEADER_BYTES + (long) total * StemFile.BYTES_PER_FRAME,
                    Files.size(file));
            assertTrue("crc 应当对得上", stems.verifyCrc());

            // 跨块：90..210 跨了第 1/2/3 块
            int from = 90;
            int n = 120;
            float[][] gotI = new float[2][n];
            float[][] gotV = new float[2][n];
            stems.read(from, n, gotI, gotV);
            for (int i = 0; i < n; i++) {
                assertEquals(instr[0][from + i], gotI[0][i], 1e-4f);
                assertEquals(instr[1][from + i], gotI[1][i], 1e-4f);
                assertEquals(voc[0][from + i], gotV[0][i], 1e-4f);
                assertEquals(voc[1][from + i], gotV[1][i], 1e-4f);
            }

            // 最后一帧与越界
            float[][] oneI = new float[2][1];
            float[][] oneV = new float[2][1];
            stems.read(total - 1, 1, oneI, oneV);
            assertEquals(instr[0][total - 1], oneI[0][0], 1e-4f);
            try {
                stems.read(total - 1, 2, new float[2][2], new float[2][2]);
                fail("越界读应当被拒绝");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("越界"));
            }
        }
    }

    @Test
    public void rejectsTruncatedOrCorruptedFiles() throws IOException {
        Path file = tmp.newFile("a.stem").toPath();
        try (StemWriter writer = StemWriter.open(file, RATE, BLOCK, StemStore.TAG_DSP)) {
            writer.write(ramp(150, 0.5f), ramp(150, 0.25f), 150);
        }
        byte[] good = Files.readAllBytes(file);

        // 截断 8 字节：长度对不上
        Path cut = tmp.newFile("cut.stem").toPath();
        Files.write(cut, java.util.Arrays.copyOf(good, good.length - 8));
        assertOpenFails(cut, "长度不对");

        // 魔数被改
        Path broken = tmp.newFile("broken.stem").toPath();
        byte[] bad = good.clone();
        bad[0] = 'X';
        Files.write(broken, bad);
        assertOpenFails(broken, "魔数");

        // 头里版本被改成未来版本
        Path future = tmp.newFile("future.stem").toPath();
        byte[] v2 = good.clone();
        v2[8] = 2;
        Files.write(future, v2);
        assertOpenFails(future, "版本");

        // 正文里翻转一位：头还能打开（open 不验 crc），但 verifyCrc 必须发现
        Path flipped = tmp.newFile("flipped.stem").toPath();
        byte[] data = good.clone();
        data[StemFile.HEADER_BYTES + 10] ^= 0x01;
        Files.write(flipped, data);
        try (StemFile opened = StemFile.open(flipped)) {
            assertFalse("正文坏了 crc 必须发现", opened.verifyCrc());
        }
    }

    @Test
    public void shortFinalBlockIsAcceptedAndWrittenOnce() throws IOException {
        Path file = tmp.newFile("short.stem").toPath();
        try (StemWriter writer = StemWriter.open(file, RATE, BLOCK, StemStore.TAG_DSP)) {
            writer.write(ramp(70, 0.5f), ramp(70, 0.25f), 70);     // 不足一块
        }
        try (StemFile stems = StemFile.open(file)) {
            assertEquals(70, stems.frames());
            assertEquals(BLOCK, stems.blockFrames());
        }
    }

    @Test
    public void emptyFileIsRefusedInsteadOfBecomingAValidEmptyStem() throws IOException {
        Path file = tmp.newFolder("empty-dir").toPath().resolve("empty.stem");
        StemWriter writer = StemWriter.open(file, RATE, BLOCK, StemStore.TAG_DSP);
        try {
            writer.close();
            fail("一帧都没写就关闭应当失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("空"));
        }
        assertFalse("失败时不该留下正式文件", Files.exists(file));
        try (java.util.stream.Stream<Path> s = Files.list(file.getParent())) {
            assertTrue("也不该留下 .tmp", s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    // ---- 工具 -------------------------------------------------------------

    /** 每帧值可反推帧号：左声道 = f×step，右 = −f×step。 */
    private static float[][] ramp(int frames, float step) {
        float[][] out = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            out[0][i] = i * step / 1000f;
            out[1][i] = -i * step / 1000f;
        }
        return out;
    }

    private static void assertOpenFails(Path file, String fragment) {
        try {
            StemFile.open(file).close();
            fail("应当拒绝打开 " + file);
        } catch (IOException expected) {
            assertTrue("异常里应当提到「" + fragment + "」，实际：" + expected.getMessage(),
                    expected.getMessage().contains(fragment));
        }
    }

    /** 供将来加"就地修补"用：确认 RandomAccessFile 未被本类独占（读模式下可并发打开）。 */
    @Test
    public void canBeOpenedTwiceForReading() throws IOException {
        Path file = tmp.newFile("twice.stem").toPath();
        try (StemWriter writer = StemWriter.open(file, RATE, BLOCK, StemStore.TAG_DSP)) {
            writer.write(ramp(120, 0.5f), ramp(120, 0.25f), 120);
        }
        try (StemFile a = StemFile.open(file); StemFile b = StemFile.open(file);
             RandomAccessFile ignored = new RandomAccessFile(file.toFile(), "r")) {
            assertEquals(a.frames(), b.frames());
        }
    }
}
