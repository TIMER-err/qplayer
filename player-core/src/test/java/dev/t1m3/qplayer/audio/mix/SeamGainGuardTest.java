package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.*;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.core.AudioBuf;
import org.junit.Test;

/**
 * ① 接缝 25 dB 台阶的判据（2026-10-11 ✓ 根因修复的可测部分 ✓）。
 *
 * <p>场景：A 已放干（`outroGone`）而 `p` 还很小 ⇒ 等功率交叉会输出近乎静音 ✗
 * ⇒ `liftWhenOutgoingDrained` 必须把 B 按 `max(p, sin)` 补上 ✓
 */
public class SeamGainGuardTest {

    private static AudioBuf tone(int frames, float amp) {
        AudioBuf b = new AudioBuf(ChannelLayout.of(2), frames);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < frames; i++) b.backingArray()[b.channelBase(c) + i] = amp;
        }
        b.setFrames(frames);
        return b;
    }

    private static double rms(AudioBuf b, int frames) {
        double acc = 0;
        int cnt = 0;
        for (int c = 0; c < b.channels(); c++) {
            for (int i = 0; i < frames; i++) {
                double v = b.backingArray()[b.channelBase(c) + i];
                acc += v * v;
                cnt++;
            }
        }
        return cnt == 0 ? 0 : Math.sqrt(acc / cnt);
    }

    @Test public void theIncomingSideIsLiftedWhenTheOutgoingOneIsGone() {
        final int n = 512;
        AudioBuf out = tone(n, 0.0f);          // A 放干 ⇒ 等功率交叉给出 0 ✗
        AudioBuf inc = tone(n, 0.5f);          // B 满素材 ✓
        final float p = 0.10f;                 // p 很小 ✓ 旧行为：sin(0.1·π/2)≈0.156 ⇒ 0.078 ✗
        Director.liftWhenOutgoingDrained(out, inc, p, n);
        // **量交叉之后的部分**（2026-10-11 ✓ 行为有意变化：前 5 ms 是交叉 ✓
        // 整块均值必然被拉低 ✗ ⇒ 断言应当量"稳定段"✓ 而不是"含交叉的整块"✗）
        double r = rms(out, n) ;     // 整块（仅用于上面的"不是静音"粗判）
        double settled = rmsRange(out, 441, n);
        assertTrue("补上之后不应再是静音（RMS=" + r + "）", r > 0.02);
        assertEquals("交叉之后的稳定段应当就是 B 的满增益 × g", 0.5 * Math.max(p, Math.sin(p * Math.PI / 2)),
                settled, 0.01);
        // 增益必须 ≥ p（更早更满 ✓）且 ≤ 1（不越界 ✓）
        assertTrue("增益应 ≥ p 且 ≤ 1（实测 RMS=" + r + "）", r >= 0.5 * p - 1e-4 && r <= 0.5 + 1e-4);
    }

    private static double rmsRange(AudioBuf b, int from, int to) {
        double acc = 0;
        int cnt = 0;
        for (int c = 0; c < b.channels(); c++) {
            for (int i = Math.max(0, from); i < Math.min(to, b.frames()); i++) {
                final double v = b.backingArray()[b.channelBase(c) + i];
                acc += v * v;
                cnt++;
            }
        }
        return cnt == 0 ? 0 : Math.sqrt(acc / cnt);
    }

    @Test public void nearTheEndTheIncomingSideIsAtFullLevel() {
        // 块要比 5 ms 的交叉**长得多** ✓ 否则整块几乎全是交叉段 ✗（256 帧 = 5.8 ms ✗ 不够 ✓）
        final int n = 4410;                    // 100 ms ✓
        AudioBuf out = tone(n, 0.0f);
        AudioBuf inc = tone(n, 0.5f);
        Director.liftWhenOutgoingDrained(out, inc, 0.99f, n);
        assertEquals("p→1 时应当就是 B 的满电平（不再有 -25 dB 的坑）", 0.5,
                rmsRange(out, 441, n), 0.02);
    }

    @Test public void anEmptyIncomingBufferIsLeftAlone() {
        final int n = 128;
        AudioBuf out = tone(n, 0.25f);
        AudioBuf inc = new AudioBuf(ChannelLayout.of(2), n);
        inc.setFrames(0);                       // B 也没素材 ⇒ 不动 ✓ 不制造噪声 ✓
        Director.liftWhenOutgoingDrained(out, inc, 0.2f, n);
        assertEquals("没有 B 就不该改写", 0.25, rms(out, n), 1e-6);
    }
}
