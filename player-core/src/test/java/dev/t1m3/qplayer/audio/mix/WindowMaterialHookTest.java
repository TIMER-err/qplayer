package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>素材钩子的安全默认</b>（2026-10-07，目标①②路径 A）。
 *
 * <p>钩子只有在"真有事可做"时才动手；否则一律返回 {@code null}（= 原样交付）。
 * 这条性质就是"零行为变化"的保证：没开分离、没要响度对齐时，播放路径一个采样都不变。
 */
public class WindowMaterialHookTest {

    @Test
    public void anInertHookDoesNothingAtAll() {
        WindowMaterialHook hook = new WindowMaterialHook(null, 0.0, 0, Double.NaN);
        assertTrue("无事可做 ⇒ doesAnything 为假", !hook.doesAnything());
        assertNull("无事可做 ⇒ 返回 null（原样交付）",
                hook.transform("任何.mp3", null, 0.0, 4800, 48000));
    }

    @Test
    public void aHookWithOnlyLufsOrOnlyDuckIsActive() {
        assertTrue("只要要响度对齐 ⇒ 它是活的",
                new WindowMaterialHook(null, 0.0, 0, -16.0).doesAnything());
        assertTrue("只要有叠唱区间 + 档位 ⇒ 它是活的",
                new WindowMaterialHook(new double[] {1.0, 2.0}, 0.5, 3, Double.NaN).doesAnything());
        assertTrue("档位为 0 时哪怕给了区间也不动手（不去人声）",
                !new WindowMaterialHook(new double[] {1.0, 2.0}, 0.5, 0, Double.NaN).doesAnything());
        assertTrue("空区间不算（长度 < 2）",
                !new WindowMaterialHook(new double[] {1.0}, 0.5, 3, Double.NaN).doesAnything());
    }

    @Test
    public void anActiveHookStillYieldsNullWhenItCannotWork() {
        // 读不到素材（文件不存在/没装解码器）⇒ process 返回 null ⇒ 钩子也返回 null
        WindowMaterialHook hook = new WindowMaterialHook(new double[] {1.0, 2.0}, 0.0, 3, -16.0);
        assertNull("算不出来 ⇒ null ⇒ 原样交付（不猜、不半途而废）",
                hook.transform("不存在.mp3", null, 0.0, 4800, 48000));
    }

    @Test
    public void theDefaultPathNeverSeparatesAnything() {
        // **可计数判据（便宜版）**：没有票 ⇒ onDecoded 一次分轨都不跑。
        // 这是"安全默认"在钩子层面的证据：默认路径既不改响度、也不分离、也不动一个采样。
        WindowMaterialHook hook = new WindowMaterialHook(null, 0.0, 0, Double.NaN);
        dev.t1m3.qplayer.audio.core.AudioBuf buf =
                new dev.t1m3.qplayer.audio.core.AudioBuf(
                        dev.t1m3.qplayer.audio.core.ChannelLayout.STEREO, 4800);
        float[] data = buf.backingArray();
        for (int i = 0; i < data.length; i++) {
            data[i] = (float) Math.sin(i * 0.01);
        }
        float before = data[123];
        hook.onDecoded(buf, 4800, 12.5);
        assertEquals("默认路径：分轨次数为 0", 0L, hook.separations());
        assertEquals("默认路径：采样逐位不变", before, buf.backingArray()[123], 1e-9f);
    }

    @Test
    public void geometryIsCopiedNotAliased() {
        double[] ranges = new double[] {1.0, 2.0};
        WindowMaterialHook hook = new WindowMaterialHook(ranges, 0.5, 3, Double.NaN);
        ranges[0] = 99.0;                    // 外部改数组不该影响钩子
        assertEquals("钩子里存的是拷贝（外部改动进不去）", 1.0, hook.duckRanges()[0], 1e-9);
        // 顺带把 duckGainAt 的区间内/外语义各钉一次（第一次我把"区间内"写成了 1.0 ✗）
        assertEquals("区间之内 ⇒ 压满（0）", 0.0,
                WindowMaterial.duckGainAt(new double[] {1.0, 2.0}, 1.5), 1e-9);
        assertEquals("区间之外 ⇒ 原样（1）", 1.0,
                WindowMaterial.duckGainAt(new double[] {1.0, 2.0}, 0.5), 1e-9);
        assertTrue("钩子仍然是活的", hook.doesAnything());
    }
}
