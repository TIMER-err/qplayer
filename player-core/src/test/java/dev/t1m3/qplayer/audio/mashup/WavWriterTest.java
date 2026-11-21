package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link WavWriter}：头字段逐字节钉死，量化规则逐档钉死。
 *
 * <p>为什么要"逐字节"：WAV 头错一个字段，播放器不会报错，只会把时长算错或把声音放成噪声
 * ——那是最难从用户描述里反推的一类坏法。
 */
public class WavWriterTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesAStandardPcmHeaderAndPatchesSizesOnClose() throws IOException {
        Path file = tmp.newFile("out.wav").toPath();
        try (WavWriter writer = WavWriter.open(file, 48_000, 2)) {
            AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, 4);
            buf.setFrames(4);
            for (int i = 0; i < 4; i++) {
                buf.channelWriter(0).put(i, 0.5f);
                buf.channelWriter(1).put(i, -0.5f);
            }
            writer.write(buf);
            assertEquals(4, writer.framesWritten());
        }

        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(44 + 4 * 2 * 2, bytes.length);
        assertEquals("RIFF", ascii(bytes, 0, 4));
        assertEquals(36 + 16, b.getInt(4));          // RIFF 块长度
        assertEquals("WAVE", ascii(bytes, 8, 4));
        assertEquals("fmt ", ascii(bytes, 12, 4));
        assertEquals(16, b.getInt(16));
        assertEquals(1, b.getShort(20));             // PCM
        assertEquals(2, b.getShort(22));             // 声道数
        assertEquals(48_000, b.getInt(24));          // 采样率
        assertEquals(48_000 * 2 * 2, b.getInt(28));  // 每秒字节数
        assertEquals(2 * 2, b.getShort(32));         // 块对齐
        assertEquals(16, b.getShort(34));            // 位深
        assertEquals("data", ascii(bytes, 36, 4));
        assertEquals(16, b.getInt(40));              // data 块长度

        // 左右声道交错：0.5 → 16384、−0.5 → −16384
        assertEquals(16_384, b.getShort(44));
        assertEquals(-16_384, b.getShort(46));
    }

    @Test
    public void quantisesTheFullScaleWithoutLosingTheNegativeEnd() throws IOException {
        Path file = tmp.newFile("levels.wav").toPath();
        float[] values = {0f, 0.25f, 0.5f, 1f, -1f, -0.5f, Float.NaN};
        try (WavWriter writer = WavWriter.open(file, 44_100, 1)) {
            AudioBuf buf = new AudioBuf(ChannelLayout.MONO, values.length);
            buf.setFrames(values.length);
            for (int i = 0; i < values.length; i++) {
                buf.channelWriter(0).put(i, values[i]);
            }
            writer.write(buf);
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, b.getShort(44));
        assertEquals(8_192, b.getShort(46));
        assertEquals(16_384, b.getShort(48));
        assertEquals(Short.MAX_VALUE, b.getShort(50));
        assertEquals(Short.MIN_VALUE, b.getShort(52));
        assertEquals(-16_384, b.getShort(54));
        assertEquals(0, b.getShort(56));            // NaN 当静音，不写出一堆随机值
    }

    @Test
    public void emptyFileIsAValidZeroLengthWav() throws IOException {
        Path file = tmp.newFile("empty.wav").toPath();
        try (WavWriter writer = WavWriter.open(file, 48_000, 2)) {
            assertEquals(0, writer.framesWritten());
        }
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(44, bytes.length);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(36, b.getInt(4));
        assertEquals(0, b.getInt(40));
    }

    @Test
    public void rejectsWrongChannelCountAndOversizedBlocks() throws IOException {
        Path file = tmp.newFile("bad.wav").toPath();
        try (WavWriter writer = WavWriter.open(file, 48_000, 2)) {
            AudioBuf mono = new AudioBuf(ChannelLayout.MONO, 4);
            mono.setFrames(4);
            try {
                writer.write(mono);
                fail("声道数不一致应当被拒绝");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("声道数"));
            }
            AudioBuf stereo = new AudioBuf(ChannelLayout.STEREO, 4);
            stereo.setFrames(4);
            try {
                writer.write(stereo, 5);
                fail("超过容量应当被拒绝");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("越界"));
            }
        }
    }

    @Test
    public void writeAfterCloseFailsLoudly() throws IOException {
        Path file = tmp.newFile("closed.wav").toPath();
        WavWriter writer = WavWriter.open(file, 48_000, 2);
        writer.close();
        writer.close();                             // 重复关闭是允许的（幂等）
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, 1);
        buf.setFrames(1);
        try {
            writer.write(buf);
            fail("关闭后写应当抛异常");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("已关闭"));
        }
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
