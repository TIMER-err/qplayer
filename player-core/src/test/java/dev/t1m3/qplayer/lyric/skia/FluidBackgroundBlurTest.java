package dev.t1m3.qplayer.lyric.skia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 静态缓存那条路的模糊单位换算。
 *
 * <p>这条换算看着只有一行，但错了的后果很具体：手机（uiScale 2.5）上「静态背景」会比
 * 「动态背景」锐一大截——因为实时路径的 sigma 是<strong>逻辑 px</strong>（Skia 按 CTM 放大），
 * 而静态缓存的离屏是<strong>设备分辨率、没有 CTM</strong>。
 *
 * <p>「Skia 会按 CTM 缩放 sigma」这条前提是本机实测出来的：同一份内容按 1x 与 2x 各渲一帧，
 * 把 2x 降采样回 1x 比，平均绝对差 0.13/255（若 sigma 不随 CTM 缩放，2x 那张会明显更锐）。
 */
public class FluidBackgroundBlurTest {

    @Test
    public void staticCacheUsesDevicePixels() {
        assertEquals(80f, FluidBackground.staticBlurSigma(80f, 1f), 1e-4f);
        assertEquals(200f, FluidBackground.staticBlurSigma(80f, 2.5f), 1e-4f);
        assertEquals(160f, FluidBackground.staticBlurSigma(80f, 2f), 1e-4f);
    }

    @Test
    public void degenerateUiScaleIsClampedToUnity() {
        // 缩放到 0 或负数是没有意义的（画布尺寸至少是 1），按 1 处理而不是把模糊抹掉
        assertEquals(80f, FluidBackground.staticBlurSigma(80f, 0f), 1e-4f);
        assertEquals(80f, FluidBackground.staticBlurSigma(80f, -3f), 1e-4f);
    }
}
