package dev.t1m3.qplayer.audio.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 照搬 goal 第 49 轮：flanger 的行为契约 ✓（关着逐位直通 ✓ / 开了有梳状 ✓ / mix 控制深浅 ✓）。 */
public class AppleFlangerTest {

    private static float[] run(float mix, float[] in) {
        AppleFlanger f = new AppleFlanger(48000, 1);
        f.setMix(mix);
        f.setRateHz(2f);
        f.setDepth(0.32f);
        f.setCenter(0.51f);
        f.setFeedback(0.52f);
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = f.process(0, in[i]);
        }
        return out;
    }

    private static float[] impulse(int n) {
        float[] x = new float[n];
        x[0] = 1f;
        return x;
    }

    @Test
    public void whenMixIsZeroItIsBitIdenticalPassthrough() {
        float[] in = impulse(64);
        float[] out = run(0f, in);
        for (int i = 0; i < in.length; i++) {
            assertEquals(in[i], out[i], 0f);
        }
    }

    @Test
    public void aFlangedImpulseComesBackAsSeveralEchoes() {
        float[] out = run(1f, impulse(2048));
        int echoes = 0;
        for (int i = 8; i < out.length; i++) {
            if (Math.abs(out[i]) > 1e-4f) {
                echoes++;
            }
        }
        assertTrue("开了 flanger 之后应当出现多个回声（实测 " + echoes + " 个）✓", echoes >= 3);
    }

    @Test
    public void moreMixMeansMoreDeviationFromTheDrySignal() {
        float[] in = new float[512];
        for (int i = 0; i < in.length; i++) {
            in[i] = (float) Math.sin(i * 0.05);
        }
        float[] light = run(0.1f, in);
        float[] heavy = run(0.45f, in);
        double dl = 0.0;
        double dh = 0.0;
        for (int i = 0; i < in.length; i++) {
            dl += Math.abs(light[i] - in[i]);
            dh += Math.abs(heavy[i] - in[i]);
        }
        assertTrue("mix 越大、偏离干声越多 ✓（" + dl + " vs " + dh + "）", dh > dl * 2.0);
    }

    @Test
    public void itIsDeterministic() {
        float[] in = new float[256];
        for (int i = 0; i < in.length; i++) {
            in[i] = (i % 7) - 3f;
        }
        float[] a = run(0.4f, in);
        float[] b = run(0.4f, in);
        for (int i = 0; i < in.length; i++) {
            assertEquals(a[i], b[i], 0f);
        }
    }
}
