package dev.t1m3.qplayer.android.lyric;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.util.Objects;

/**
 * Apple Music-style fluid backdrop (ported from Haedus FluidBackground for
 * Android). The cover is downscaled to 32x32 and AMLL-adjusted CPU-side, then a
 * SkSL shader warps + rotates the UV before sampling — all motion comes from
 * the shader. Android port replaces java.awt decode with android.graphics.Bitmap.
 */
public final class FluidBackground {

    private static final int TEX_SIZE = 32;

    // Embedded SkSL (was resources/shaders/fluid_mesh.sksl). Compiled once.
    private static final String SKSL =
            "uniform float2 resolution;\n" +
            "uniform float  time;\n" +
            "uniform shader cover;\n" +
            "const float TEX_SIZE  = 32.0;\n" +
            "const float WARP_A    = 0.10;\n" +
            "const float WARP_B    = 0.06;\n" +
            "const float ROT_SPEED = 0.18;\n" +
            "half4 main(float2 fragCoord) {\n" +
            "    float2 screenUV = fragCoord / resolution;\n" +
            "    // cover-fit the square texture: scale by the long axis, crop the\n" +
            "    // short one so the cover never stretches with the viewport aspect.\n" +
            "    float2 corr = float2(min(1.0, resolution.x / resolution.y),\n" +
            "                         min(1.0, resolution.y / resolution.x));\n" +
            "    float2 uv = (screenUV - 0.5) * corr + 0.5;\n" +
            "    float2 w1 = float2(sin(time * 0.42 + uv.y * 3.7),\n" +
            "                       cos(time * 0.37 + uv.x * 3.1));\n" +
            "    float2 w2 = float2(cos(time * 0.29 + uv.x * 2.3),\n" +
            "                       sin(time * 0.51 + uv.y * 4.4));\n" +
            "    float2 warpedUV = uv + WARP_A * w1 + WARP_B * w2;\n" +
            "    float a  = time * ROT_SPEED;\n" +
            "    float cs = cos(a);\n" +
            "    float sn = sin(a);\n" +
            "    float2 c = warpedUV - 0.5;\n" +
            "    float2 rotUV = float2(c.x * cs - c.y * sn,\n" +
            "                          c.x * sn + c.y * cs) + 0.5;\n" +
            "    rotUV = clamp(rotUV, float2(0.005), float2(0.995));\n" +
            "    half4 col = cover.eval(rotUV * TEX_SIZE);\n" +
            "    float vignette = smoothstep(0.8, 0.3, distance(screenUV, float2(0.5)));\n" +
            "    half3 rgb = col.rgb * half(0.6 + vignette * 0.4);\n" +
            "    float dither = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453);\n" +
            "    rgb += half3(dither - 0.5) / 255.0;\n" +
            "    return half4(rgb, 1.0);\n" +
            "}\n";

    private static RuntimeEffect effect;

    private static RuntimeEffect effect() {
        if (effect == null) {
            try {
                effect = RuntimeEffect.makeForShader(SKSL);
            } catch (Throwable t) {
                dev.t1m3.qplayer.util.Logger.warn("fluid sksl compile failed: {}", t.getMessage());
                throw t;
            }
        }
        return effect;
    }

    private final long startNs;
    private Image cover;
    // Cover sampled as a shader child every frame. The texture only changes on a
    // track change, so build the child shader once (not per frame) and reuse it;
    // makeShader + close on the cover every frame was needless native churn.
    private Shader coverShader;
    private String coverKey;
    // Reused across frames -- the per-frame `new Paint()` was a native alloc/free
    // each frame the lyric page is visible.
    private final Paint fluidPaint = new Paint();
    // Cached full-bleed draw rect -- rebuilt only when the viewport size changes,
    // instead of a fresh Rect.makeXYWH every frame.
    private Rect fullRect;
    private float fullRectW = -1f;
    private float fullRectH = -1f;

    public FluidBackground(long startNs) {
        this.startNs = startNs;
    }

    /**
     * Draw the fluid backdrop into [0,0,w,h]. nowNs drives the animation;
     * coverBytes/trackKey identify the source (rebuilt when the key changes, or
     * when bytes arrive after an initial null for the same key).
     */
    public void render(Canvas canvas, float w, float h, byte[] coverBytes, String trackKey, long nowNs) {
        boolean keyChanged = !Objects.equals(trackKey, coverKey);
        boolean nullButReady = cover == null && coverBytes != null && coverBytes.length > 0;
        if (keyChanged || nullButReady) {
            if (coverShader != null) {
                coverShader.close();
                coverShader = null;
            }
            if (cover != null) {
                cover.close();
                cover = null;
            }
            cover = buildTexture(coverBytes);
            coverKey = trackKey;
            if (cover != null) {
                coverShader = cover.makeShader(
                        FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                        SamplingMode.LINEAR, Matrix33.IDENTITY);
            }
        }
        if (coverShader == null) {
            renderFallback(canvas, w, h, 0xFF0A0A0E);
            return;
        }

        float time = (nowNs - startNs) / 1_000_000_000f;

        if (fullRect == null || fullRectW != w || fullRectH != h) {
            fullRect = Rect.makeXYWH(0, 0, w, h);
            fullRectW = w;
            fullRectH = h;
        }

        // The runtime shader bakes its uniforms at makeShader time, so the animated
        // `time` forces a rebuild every frame -- but that just instantiates the
        // already-compiled effect; the cover child shader and the Paint are reused.
        Shader shaded = null;
        try {
            try (RuntimeEffectBuilder b = new RuntimeEffectBuilder(effect())) {
                b.setUniform("resolution", w, h);
                b.setUniform("time", time);
                b.setChild("cover", coverShader);
                shaded = b.makeShader();
            }
            fluidPaint.setShader(shaded);
            canvas.drawRect(fullRect, fluidPaint);
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn("fluid render failed: {}", e.getMessage());
            renderFallback(canvas, w, h, 0xFF0A0A0E);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    public void dispose() {
        if (coverShader != null) {
            coverShader.close();
            coverShader = null;
        }
        if (cover != null) {
            cover.close();
            cover = null;
        }
        fluidPaint.close();
        coverKey = null;
    }

    // ---- AMLL-mirrored CPU preprocessing -----------------------------

    private static Image buildTexture(byte[] coverBytes) {
        if (coverBytes == null || coverBytes.length == 0) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.length, opts);
            if (src == null) return null;
            Bitmap thumb = Bitmap.createScaledBitmap(src, TEX_SIZE, TEX_SIZE, true);
            int[] argb = new int[TEX_SIZE * TEX_SIZE];
            thumb.getPixels(argb, 0, TEX_SIZE, 0, 0, TEX_SIZE, TEX_SIZE);
            if (thumb != src) thumb.recycle();
            src.recycle();

            float[] rgb = amllAdjust(argb);
            boxBlur(rgb, TEX_SIZE, TEX_SIZE, 2, 4);

            byte[] px = new byte[argb.length * 4];
            for (int i = 0; i < argb.length; i++) {
                px[i * 4] = (byte) clampU8(rgb[i * 3]);
                px[i * 4 + 1] = (byte) clampU8(rgb[i * 3 + 1]);
                px[i * 4 + 2] = (byte) clampU8(rgb[i * 3 + 2]);
                px[i * 4 + 3] = (byte) 0xFF;
            }
            ImageInfo info = new ImageInfo(TEX_SIZE, TEX_SIZE,
                    ColorType.RGBA_8888, ColorAlphaType.OPAQUE);
            return Image.makeRaster(info, px, TEX_SIZE * 4L);
        } catch (Throwable e) {
            return null;
        }
    }

    private static float[] amllAdjust(int[] argb) {
        float[] out = new float[argb.length * 3];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            float r = (p >> 16) & 0xFF;
            float g = (p >> 8) & 0xFF;
            float b = p & 0xFF;

            r = (r - 128f) * 0.4f + 128f;
            g = (g - 128f) * 0.4f + 128f;
            b = (b - 128f) * 0.4f + 128f;

            float gray = r * 0.3f + g * 0.59f + b * 0.11f;
            r = gray * -2f + r * 3f;
            g = gray * -2f + g * 3f;
            b = gray * -2f + b * 3f;

            r = (r - 128f) * 1.7f + 128f;
            g = (g - 128f) * 1.7f + 128f;
            b = (b - 128f) * 1.7f + 128f;

            r *= 0.75f;
            g *= 0.75f;
            b *= 0.75f;

            out[i * 3] = r;
            out[i * 3 + 1] = g;
            out[i * 3 + 2] = b;
        }
        return out;
    }

    private static void boxBlur(float[] rgb, int w, int h, int radius, int passes) {
        float[] tmp = new float[rgb.length];
        for (int p = 0; p < passes; p++) {
            boxBlurH(rgb, tmp, w, h, radius);
            boxBlurV(tmp, rgb, w, h, radius);
        }
    }

    private static void boxBlurH(float[] src, float[] dst, int w, int h, int radius) {
        float div = 2f * radius + 1f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = Math.max(0, Math.min(w - 1, x + k));
                    int idx = (y * w + sx) * 3;
                    r += src[idx];
                    g += src[idx + 1];
                    b += src[idx + 2];
                }
                int o = (y * w + x) * 3;
                dst[o] = r / div;
                dst[o + 1] = g / div;
                dst[o + 2] = b / div;
            }
        }
    }

    private static void boxBlurV(float[] src, float[] dst, int w, int h, int radius) {
        float div = 2f * radius + 1f;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = Math.max(0, Math.min(h - 1, y + k));
                    int idx = (sy * w + x) * 3;
                    r += src[idx];
                    g += src[idx + 1];
                    b += src[idx + 2];
                }
                int o = (y * w + x) * 3;
                dst[o] = r / div;
                dst[o + 1] = g / div;
                dst[o + 2] = b / div;
            }
        }
    }

    private static int clampU8(float v) {
        if (v <= 0f) return 0;
        if (v >= 255f) return 255;
        return (int) (v + 0.5f);
    }

    private void renderFallback(Canvas canvas, float w, float h, int color) {
        try (Paint p = new Paint()) {
            p.setColor(color);
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
    }
}
