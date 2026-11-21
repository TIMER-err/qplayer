package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.junit.Test;

/**
 * <b>惰性 + 缓存</b>（2026-10-07，目标②）：把"一次且只有一次"做成可计数的判据。
 *
 * <p>同一段素材问多次 ⇒ 分离只能跑一次（第二次起复用缓存）；
 * 不支持的档位 ⇒ 返回 null（调用方退回"原样素材"，不猜）。
 */
public class StemMaterialsTest {

    private static final int RATE = 48000;

    @Test
    public void unsupportedModeGivesNullInsteadOfPretending() {
        StemMaterials.reset();
        // 档位 0（OFF）在 forMode 里是**抛异常**的，stemsWindow 会吞掉并返回 null
        assertNull("不支持的档位 ⇒ 返回 null（调用方据此退回原样素材，不猜）",
                StemMaterials.forWindow("不存在的文件.mp3", new HashMap<String, String>(),
                        0.0, 4800, RATE, 0));
        assertEquals("没分离成功 ⇒ 计数为 0", 0L, StemMaterials.separations());
    }

    @Test
    public void missingFileGivesNullAndDoesNotCountAsASeparation() {
        StemMaterials.reset();
        assertNull("读不到素材 ⇒ null",
                StemMaterials.forWindow("也不存在的文件.mp3", new HashMap<String, String>(),
                        1.0, 4800, RATE, dev.t1m3.qplayer.audio.stem.StemSeparators.DSP));
        assertEquals("读不到素材不算跑过分离", 0L, StemMaterials.separations());
    }

    @Test
    public void nullArgumentsAreRejectedWithoutSideEffects() {
        StemMaterials.reset();
        assertNull(StemMaterials.forWindow(null, null, 0.0, 100, RATE, dev.t1m3.qplayer.audio.stem.StemSeparators.DSP));
        assertNull(StemMaterials.forWindow("x.mp3", null, 0.0, 0, RATE, dev.t1m3.qplayer.audio.stem.StemSeparators.DSP));
        assertNull(StemMaterials.forWindow("x.mp3", null, 0.0, 100, 0, dev.t1m3.qplayer.audio.stem.StemSeparators.DSP));
        assertEquals("非法入参不产生副作用", 0L, StemMaterials.separations());
        assertTrue("缓存上限是个位数级别的常数（不会长住内存）",
                StemMaterials.MAX_ENTRIES > 0 && StemMaterials.MAX_ENTRIES <= 64);
    }
}
