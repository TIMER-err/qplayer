package dev.t1m3.qplayer.audio.core;

import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 平面缓冲的对齐测试：对应 Sonalis {@code sonalis-core/src/buf.rs} 的 {@code mod tests}。
 */
public class AudioBufTest {

    @Test
    public void newBufferIsEmptyButAllocated() {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, 1024);
        assertEquals(2, b.channels());
        assertEquals(1024, b.capacityFrames());
        assertEquals(0, b.frames());
        assertTrue(b.isEmpty());
    }

    @Test
    public void setFramesIsClamped() {
        AudioBuf b = new AudioBuf(ChannelLayout.MONO, 16);
        b.setFrames(999);
        assertEquals(16, b.frames());
    }

    @Test
    public void channelsAreIndependent() {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, 4);
        b.setFrames(4);
        b.channelWriter(0).put(0, 1f);
        b.channelWriter(1).put(0, -1f);
        assertEquals(1f, b.channel(0).get(0), 0f);
        assertEquals(-1f, b.channel(1).get(0), 0f);
        // 写左声道不应该污染右声道
        assertEquals(0f, b.channel(1).get(1), 0f);
    }

    /** 读视图的长度是有效帧数，写视图的长度是整块容量。 */
    @Test
    public void readViewIsTrimmedToFramesWriterIsNot() {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, 8);
        b.setFrames(3);
        assertEquals(3, b.channel(0).remaining());
        assertEquals(8, b.channelWriter(0).remaining());
    }

    @Test
    public void interleaveLaysOutRowMajor() {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, 2);
        b.setFrames(2);
        FloatBuffer left = b.channelWriter(0);
        left.put(0, 1f);
        left.put(1, 3f);
        FloatBuffer right = b.channelWriter(1);
        right.put(0, 2f);
        right.put(1, 4f);

        float[] out = new float[4];
        int n = b.interleaveInto(out);
        assertEquals(4, n);
        assertEquals(1f, out[0], 0f);
        assertEquals(2f, out[1], 0f);
        assertEquals(3f, out[2], 0f);
        assertEquals(4f, out[3], 0f);
    }

    @Test
    public void interleaveStopsAtOutputLimit() {
        AudioBuf b = new AudioBuf(ChannelLayout.STEREO, 8);
        b.setFrames(8);
        float[] out = new float[4];
        assertEquals("输出只放得下 2 帧", 4, b.interleaveInto(out));
    }

    @Test
    public void appendRespectsRoomAndChannelMatch() {
        AudioBuf dst = new AudioBuf(ChannelLayout.STEREO, 4);
        AudioBuf src = new AudioBuf(ChannelLayout.STEREO, 4);
        src.setFrames(4);
        assertEquals(4, dst.append(src));
        assertEquals(4, dst.frames());
        // 已满，再追加应该是 0 而不是抛异常
        assertEquals(0, dst.append(src));

        AudioBuf mono = new AudioBuf(ChannelLayout.MONO, 4);
        assertEquals("声道数不一致应拒绝", 0, dst.append(mono));
    }

    @Test
    public void peakAndClipping() {
        AudioBuf b = new AudioBuf(ChannelLayout.MONO, 4);
        b.setFrames(4);
        FloatBuffer w = b.channelWriter(0);
        w.put(0, 0.1f);
        w.put(1, -0.9f);
        w.put(2, 0.2f);
        w.put(3, 0f);
        assertEquals(0.9f, b.peak(), 1e-6f);
        assertFalse(b.isClipping());
        w.put(0, 1.5f);
        assertTrue(b.isClipping());
    }

    @Test
    public void zeroClearsSamplesButKeepsFrames() {
        AudioBuf b = new AudioBuf(ChannelLayout.MONO, 4);
        b.setFrames(4);
        b.channelWriter(0).put(0, 0.5f);
        b.zero();
        assertEquals(0f, b.peak(), 0f);
        assertEquals(4, b.frames());
    }
}
