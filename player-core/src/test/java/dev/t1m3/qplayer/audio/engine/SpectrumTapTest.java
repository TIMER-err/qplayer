package dev.t1m3.qplayer.audio.engine;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SpectrumTap} 的测试：快照必须<strong>整份</strong>可见（不能撕裂），
 * 这是这个类存在的唯一理由。
 */
public class SpectrumTapTest {

    @Test
    public void publishAndCopyRoundTrip() {
        SpectrumTap.publish(0.25f, 0.5f, 0.75f);
        float[] out = new float[SpectrumTap.BANDS];
        SpectrumTap.copyInto(out, 0);
        // 打包是 10 位定点，容差取 1/1023
        assertEquals(0.25f, out[0], 0.001f);
        assertEquals(0.5f, out[1], 0.001f);
        assertEquals(0.75f, out[2], 0.001f);
    }

    @Test
    public void outOfRangeValuesAreClampedAndNanIsZero() {
        SpectrumTap.publish(-1f, 2f, Float.NaN);
        float[] out = new float[SpectrumTap.BANDS];
        SpectrumTap.copyInto(out, 0);
        assertEquals(0f, out[0], 0f);
        assertEquals(1f, out[1], 0f);
        assertEquals(0f, out[2], 0f);
    }

    @Test
    public void resetGoesBackToSilence() {
        SpectrumTap.publish(1f, 1f, 1f);
        SpectrumTap.reset();
        float[] out = new float[SpectrumTap.BANDS];
        SpectrumTap.copyInto(out, 0);
        assertEquals(0f, out[0], 0f);
        assertEquals(0f, out[1], 0f);
        assertEquals(0f, out[2], 0f);
    }

    @Test
    public void shortDestinationIsLeftUntouched() {
        SpectrumTap.publish(0.5f, 0.5f, 0.5f);
        float[] tooShort = new float[2];
        SpectrumTap.copyInto(tooShort, 0);
        assertEquals(0f, tooShort[0], 0f);
        assertEquals(0f, tooShort[1], 0f);
        float[] offsetOverflow = new float[3];
        SpectrumTap.copyInto(offsetOverflow, 1);   // 1 + 3 > 3
        assertEquals(0f, offsetOverflow[0], 0f);
    }

    @Test
    public void offsetWriteLandsWhereAsked() {
        SpectrumTap.publish(0.1f, 0.6f, 0.9f);
        float[] out = new float[5];
        SpectrumTap.copyInto(out, 2);
        assertEquals(0.1f, out[2], 0.001f);
        assertEquals(0.6f, out[3], 0.001f);
        assertEquals(0.9f, out[4], 0.001f);
    }

    /**
     * 撕裂读的最后一道闸门：一个线程在两套值之间来回发布，读者拿到的三元组必须是
     * <strong>A 或 B 其中之一</strong>。三个独立的 volatile 字段会在这里偶发混合值。
     */
    @Test
    public void snapshotsAreNeverTornBetweenPublishes() throws Exception {
        final float[] a = {0.10f, 0.20f, 0.30f};
        final float[] b = {0.90f, 0.80f, 0.70f};
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<String> bad = new AtomicReference<String>();
        // 先把初值放到 A：否则读者可能在写者第一次发布之前读到初始的 0（那不是撕裂）
        SpectrumTap.publish(a[0], a[1], a[2]);

        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stop.get()) {
                    SpectrumTap.publish(a[0], a[1], a[2]);
                    SpectrumTap.publish(b[0], b[1], b[2]);
                }
            }
        }, "spectrum-writer");
        writer.setDaemon(true);
        writer.start();

        float[] out = new float[SpectrumTap.BANDS];
        long deadline = System.nanoTime() + 200_000_000L;   // 200 ms 够了
        int reads = 0;
        while (System.nanoTime() < deadline) {
            SpectrumTap.copyInto(out, 0);
            reads++;
            boolean isA = near(out, a);
            boolean isB = near(out, b);
            if (!isA && !isB) {
                bad.compareAndSet(null, out[0] + "/" + out[1] + "/" + out[2]);
                break;
            }
        }
        stop.set(true);
        writer.join(1000);
        assertTrue("至少要有一些读数", reads > 0);
        assertEquals("读到了撕裂的快照：" + bad.get(), null, bad.get());
    }

    private static boolean near(float[] got, float[] expected) {
        for (int i = 0; i < SpectrumTap.BANDS; i++) {
            if (Math.abs(got[i] - expected[i]) > 0.005f) {
                return false;
            }
        }
        return true;
    }
}
