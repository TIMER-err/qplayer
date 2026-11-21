package dev.t1m3.qplayer.audio.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 环形缓冲的对齐测试：对应 Sonalis {@code sonalis-core/src/ring.rs} 的 {@code mod tests}。
 */
public class RingBufferTest {

    @Test
    public void capacityRoundsUpToPowerOfTwo() {
        assertEquals(1024, RingBuffer.create(1000).producer.capacity());
        assertEquals("下限 256", 256, RingBuffer.create(1).producer.capacity());
        assertEquals(4096, RingBuffer.create(4096).producer.capacity());
    }

    @Test
    public void emptyRingReportsNothingReadable() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        assertEquals(0, ring.consumer.available());
        float[] out = new float[] {7f, 7f, 7f, 7f};
        assertEquals(0, ring.consumer.pop(out));
    }

    @Test
    public void roundtripPreservesSamples() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        float[] data = new float[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = i * 0.5f;
        }
        assertEquals(100, ring.producer.push(data));
        assertEquals(100, ring.consumer.available());

        float[] out = new float[100];
        assertEquals(100, ring.consumer.pop(out));
        org.junit.Assert.assertArrayEquals(data, out, 0f);
        assertEquals(0, ring.consumer.available());
    }

    @Test
    public void pushIsTruncatedAtCapacityNotWrappedOver() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        float[] big = new float[300];
        java.util.Arrays.fill(big, 1f);
        assertEquals("只能写满容量", 256, ring.producer.push(big));
        assertEquals(0, ring.producer.free());
        assertEquals(256, ring.consumer.available());
        // 满的时候再写应该被拒绝而不是覆盖未读数据
        assertEquals(0, ring.producer.push(new float[] {9f}));

        float[] out = new float[256];
        assertEquals(256, ring.consumer.pop(out));
        for (float s : out) {
            assertEquals("未读数据不能被覆盖", 1f, s, 0f);
        }
    }

    @Test
    public void wrapsAroundCorrectly() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        // 先把游标推到接近回绕点
        ring.producer.push(new float[250]);
        ring.consumer.pop(new float[250]);
        // 这次写入会跨越物理边界
        float[] data = new float[50];
        for (int i = 0; i < data.length; i++) {
            data[i] = i + 1;
        }
        assertEquals(50, ring.producer.push(data));
        float[] out = new float[50];
        assertEquals(50, ring.consumer.pop(out));
        org.junit.Assert.assertArrayEquals("跨边界回绕后数据必须仍然连续", data, out, 0f);
    }

    @Test
    public void popOrSilenceFillsUnderrun() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        ring.producer.push(new float[] {1f, 2f, 3f});
        float[] out = new float[] {9f, 9f, 9f, 9f, 9f, 9f, 9f, 9f};
        int got = ring.consumer.popOrSilence(out);
        assertEquals(3, got);
        assertEquals(1f, out[0], 0f);
        assertEquals(2f, out[1], 0f);
        assertEquals(3f, out[2], 0f);
        for (int i = 3; i < out.length; i++) {
            assertEquals("欠载部分必须是静音", 0f, out[i], 0f);
        }
    }

    @Test
    public void freeReportsRemainingRoom() {
        RingBuffer.Ring ring = RingBuffer.create(256);
        assertEquals(256, ring.producer.free());
        ring.producer.push(new float[100]);
        assertEquals(156, ring.producer.free());
    }

    /**
     * 音频线程与生产者线程真并发时的完整性：不能出现撕裂或丢样本。
     * 上游靠类型系统保证单生产者单消费者，这里用一条线程实测一遍。
     */
    @Test
    public void concurrentProducerConsumerDeliversEverySampleInOrder() throws Exception {
        final int total = 200_000;
        final RingBuffer.Ring ring = RingBuffer.create(1024);
        final java.util.List<String> failures =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                int written = 0;
                float[] block = new float[64];
                while (written < total) {
                    int n = Math.min(block.length, total - written);
                    for (int i = 0; i < n; i++) {
                        block[i] = written + i;
                    }
                    int off = 0;
                    while (off < n) {
                        off += ring.producer.push(block, off, n - off);
                    }
                    written += n;
                }
            }
        });

        final java.util.concurrent.atomic.AtomicInteger consumed =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                float[] out = new float[64];
                int expected = 0;
                while (expected < total) {
                    int got = ring.consumer.pop(out);
                    for (int i = 0; i < got; i++) {
                        if (out[i] != expected + i) {
                            failures.add("下标 " + (expected + i) + " 期望值 " + (expected + i)
                                    + "，实际 " + out[i]);
                            return;
                        }
                    }
                    expected += got;
                    consumed.addAndGet(got);
                }
            }
        });

        producer.start();
        consumer.start();
        producer.join(30_000);
        consumer.join(30_000);

        assertTrue("消费者仍在等待数据，可能丢了唤醒：" + failures, failures.isEmpty());
        assertEquals(total, consumed.get());
    }
}
