package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.RingBuffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 音频回调填充逻辑的对齐测试。
 *
 * <p>上游 {@code fill::<T>(data, consumer, scratch)} 是 cpal 回调里的一次泛型 dispatch，
 * 没有单测（要真声卡）。移植时设备 I/O 归宿主，但回调体被抽成纯函数放在
 * {@link OutputFiller}，于是上游文档里写明的两条性质<strong>终于可以被测到</strong>：
 *
 * <ul>
 *   <li>欠载时吐静音而不是少写采样（少写会让设备播放位置与写入进度错位，
 *       听感上表现为越来越明显的爆音）</li>
 *   <li>必须显式写满——{@code data} 是设备给的缓冲，里面可能残留上一轮的内容或
 *       未初始化的垃圾，不能「读空了就跳出循环」</li>
 * </ul>
 *
 * <p>上游没有对应条目，这一整个文件都是移植时补充的测试。
 */
public class OutputFillerTest {

    private static final OutputSampleFormat FMT = OutputSampleFormat.I16;
    private static final int BPS = 2;

    /** 环里的采样要按交错顺序、按格式逐个写进设备缓冲。 */
    @Test
    public void fillConvertsAndWritesInOrder() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        // 立体声交错：L0 R0 L1 R1
        float[] input = {0f, 0.5f, -0.5f, 1.0f, -1.0f, 0f};
        assertEquals(input.length, ring.producer.push(input));

        byte[] out = new byte[input.length * BPS];
        float[] scratch = new float[OutputFiller.SCRATCH_SAMPLES];
        OutputFiller.fill(ring.consumer, scratch, FMT, out);

        for (int i = 0; i < input.length; i++) {
            float back = FMT.readSample(out, i * BPS);
            assertEquals("第 " + i + " 个采样", input[i], back, 2.0f / 32_767.0f);
        }
        assertEquals("环应被读空", 0, ring.consumer.available());
    }

    /**
     * 欠载：读空了也要把整段缓冲写满（吐静音）。
     *
     * <p>先把 out 填成垃圾值，再验证它被完全覆盖——这正是上游那句
     * 「里面可能残留上一轮的内容或者未初始化的垃圾，必须显式写满」要守的。
     */
    @Test
    public void underrunSilencesInsteadOfLeavingGarbage() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        byte[] out = new byte[64 * BPS];
        java.util.Arrays.fill(out, (byte) 0x7F); // 上一轮的残留

        float[] scratch = new float[OutputFiller.SCRATCH_SAMPLES];
        OutputFiller.fill(ring.consumer, scratch, FMT, out);

        for (int i = 0; i < out.length; i++) {
            assertEquals("欠载必须补静音，第 " + i + " 字节", 0, out[i]);
        }
    }

    /** 部分欠载：前半是真数据，后半是静音，位置不能错。 */
    @Test
    public void partialUnderrunKeepsPhase() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        float[] input = {1.0f, 1.0f, 1.0f};
        ring.producer.push(input);

        byte[] out = new byte[8 * BPS];
        java.util.Arrays.fill(out, (byte) 0x55);
        float[] scratch = new float[OutputFiller.SCRATCH_SAMPLES];
        OutputFiller.fill(ring.consumer, scratch, FMT, out);

        for (int i = 0; i < 3; i++) {
            assertEquals("前 3 个采样应是环里的数据", 1.0f, FMT.readSample(out, i * BPS), 0f);
        }
        for (int i = 3; i < 8; i++) {
            assertEquals("第 " + i + " 个采样应是静音", 0.0f, FMT.readSample(out, i * BPS), 0f);
        }
    }

    /** 一次回调需要的采样数超过暂存缓冲时，要分多轮读——暂存不增长。 */
    @Test
    public void fillLoopsWhenBufferExceedsScratch() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        int total = 10;
        float[] input = new float[total];
        for (int i = 0; i < total; i++) {
            input[i] = (i % 2 == 0) ? 1.0f : -1.0f;
        }
        ring.producer.push(input);

        byte[] out = new byte[total * BPS];
        float[] scratch = new float[4]; // 故意比一次回调需要的少
        OutputFiller.fill(ring.consumer, scratch, FMT, out);

        for (int i = 0; i < total; i++) {
            assertEquals("第 " + i + " 个采样", input[i], FMT.readSample(out, i * BPS), 0f);
        }
    }

    /** 暂存缓冲的大小不影响结果，只影响轮数——同一份数据要填出同样的字节。 */
    @Test
    public void scratchSizeDoesNotChangeOutput() {
        float[] input = {0.125f, -0.25f, 0.5f, -0.75f, 1.0f, -1.0f, 0f, 0.375f};

        byte[] a = new byte[input.length * BPS];
        byte[] b = new byte[input.length * BPS];

        RingBuffer.Ring r1 = RingBuffer.create(256);
        r1.producer.push(input);
        OutputFiller.fill(r1.consumer, new float[OutputFiller.SCRATCH_SAMPLES], FMT, a);

        RingBuffer.Ring r2 = RingBuffer.create(256);
        r2.producer.push(input);
        OutputFiller.fill(r2.consumer, new float[3], FMT, b);

        assertTrue("暂存大小不该改变输出字节", java.util.Arrays.equals(a, b));
    }

    /** 24 位（4 字节存储）下缓冲长度按存储宽度折算。 */
    @Test
    public void fillUsesStorageWidthOfFormat() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        ring.producer.push(new float[]{1.0f, -1.0f});

        byte[] out = new byte[2 * OutputSampleFormat.I24.bytes()];
        OutputFiller.fill(ring.consumer, new float[16], OutputSampleFormat.I24, out);

        // 8388607 = 0x7FFFFF 与 -8388607 = 0xFF800001，小端
        assertEquals(0xFF, out[0] & 0xFF);
        assertEquals(0xFF, out[1] & 0xFF);
        assertEquals(0x7F, out[2] & 0xFF);
        assertEquals(0x00, out[3] & 0xFF);
        assertEquals(0x01, out[4] & 0xFF);
        assertEquals(0x00, out[5] & 0xFF);
        assertEquals(0x80, out[6] & 0xFF);
        assertEquals(0xFF, out[7] & 0xFF);
    }
}
