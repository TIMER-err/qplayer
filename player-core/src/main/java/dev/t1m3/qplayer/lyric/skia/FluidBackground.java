package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.util.Objects;

/**
 * Apple Music-style fluid backdrop (ported from Haedus FluidBackground for
 * Android). The cover is downscaled to 32x32 and AMLL-adjusted CPU-side, then a
 * SkSL shader warps + rotates the UV before sampling — all motion comes from
 * the shader. The cover decode is done with Skija itself (Image.makeFromEncoded
 * + a 32x32 raster Bitmap), so this class is fully platform-neutral and shared
 * verbatim by the Android shell and the desktop host.
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
    // Off-thread cover decode. The heavy decode + 32x32 downscale + CPU blur must NOT
    // run on the render thread: doing it on the frame a track switches stalls that
    // frame, and the time-based cover-morph animation then jumps to catch up. A single
    // background thread builds the raster texture; the render thread only swaps it in
    // (a cheap makeShader) once ready, keeping the previous cover shading until then.
    private final java.util.concurrent.ExecutorService decoder =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fluid-cover-decode");
                t.setDaemon(true);
                return t;
            });
    private volatile Image decodedCover;  // built off-thread, awaiting the render thread
    private volatile String decodedKey;   // the track key decodedCover belongs to
    private volatile String decodingKey;  // the track key currently queued/decoding
    // Reused across frames -- the per-frame `new Paint()` was a native alloc/free
    // each frame the lyric page is visible.
    private final Paint fluidPaint = new Paint();
    // Cached full-bleed draw rect -- rebuilt only when the viewport size changes,
    // instead of a fresh Rect.makeXYWH every frame.
    private Rect fullRect;
    private float fullRectW = -1f;
    private float fullRectH = -1f;
    // Static-mode cache: the fluid rendered ONCE to an offscreen GPU image (at
    // device resolution) and then blitted each frame, so static mode pays a single
    // full-screen SkSL pass per track/size instead of one every frame. Rebuilt when
    // the cover or the device size changes.
    private Image staticImage;
    private int staticW = -1;
    private int staticH = -1;

    public FluidBackground(long startNs) {
        this.startNs = startNs;
    }

    /**
     * Draw the fluid backdrop into [0,0,w,h] (logical px, under the caller's uiScale
     * matrix). When {@code staticMode}, the fluid is rendered once to an offscreen
     * image and that image is blitted every frame (no per-frame full-screen SkSL);
     * otherwise it animates with {@code nowNs}. {@code ctx}/{@code uiScale} are only
     * used to build the device-resolution static cache.
     */
    public void render(Canvas canvas, DirectContext ctx, float uiScale, float w, float h,
                       byte[] coverBytes, String trackKey, long nowNs, boolean staticMode) {
        boolean keyChanged = !Objects.equals(trackKey, coverKey);
        boolean nullButReady = cover == null && coverBytes != null && coverBytes.length > 0;
        // Kick an off-thread decode for a newly-selected cover, once per key.
        if ((keyChanged || nullButReady) && coverBytes != null && coverBytes.length > 0
                && !Objects.equals(trackKey, decodingKey)) {
            decodingKey = trackKey;
            final byte[] cb = coverBytes;
            final String key = trackKey;
            decoder.submit(() -> {
                Image tex = buildTexture(cb);
                if (tex != null) { decodedKey = key; decodedCover = tex; }
            });
        }
        // Publish a ready off-thread texture (render thread): swap the shader, drop the
        // old cover. Cheap — the expensive work already happened on the decoder thread.
        if (decodedCover != null && Objects.equals(decodedKey, trackKey)
                && !Objects.equals(coverKey, trackKey)) {
            if (coverShader != null) {
                coverShader.close();
                coverShader = null;
            }
            if (cover != null) cover.close();
            cover = decodedCover;
            decodedCover = null;
            coverKey = trackKey;
            coverShader = cover.makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            invalidateStatic();   // the source changed -> the cached frame is stale
        }
        if (coverShader == null) {
            renderFallback(canvas, w, h, 0xFF0A0A0E);
            return;
        }

        float time = (nowNs - startNs) / 1_000_000_000f;

        // Full-bleed dst rect, cached across frames (rebuilt only on a size change)
        // -- shared by the static blit and the live draw so neither allocates a Rect
        // per frame.
        if (fullRect == null || fullRectW != w || fullRectH != h) {
            fullRect = Rect.makeXYWH(0, 0, w, h);
            fullRectW = w;
            fullRectH = h;
        }

        // Static: blit a once-rendered, device-resolution image. Rebuild it on a
        // cover/size change (or when first entering static mode).
        if (staticMode && ctx != null) {
            int dw = Math.max(1, Math.round(w * uiScale));
            int dh = Math.max(1, Math.round(h * uiScale));
            if (staticImage == null || staticW != dw || staticH != dh) {
                buildStatic(ctx, dw, dh, time);
            }
            if (staticImage != null) {
                canvas.drawImageRect(staticImage, fullRect);
                return;
            }
            // build failed -> fall through to the live shader this frame
        }

        drawFluid(canvas, fullRect, w, h, time);
    }

    // Render the fluid shader into `dstRect` with the given resolution + time. The
    // runtime shader bakes its uniforms at makeShader time, so an animated `time`
    // rebuilds it each call -- but that just instantiates the already-compiled
    // effect; the cover child shader and the Paint are reused.
    private void drawFluid(Canvas canvas, Rect dstRect, float resW, float resH, float time) {
        Shader shaded = null;
        try {
            try (RuntimeEffectBuilder b = new RuntimeEffectBuilder(effect())) {
                b.setUniform("resolution", resW, resH);
                b.setUniform("time", time);
                b.setChild("cover", coverShader);
                shaded = b.makeShader();
            }
            fluidPaint.setShader(shaded);
            canvas.drawRect(dstRect, fluidPaint);
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn("fluid render failed: {}", e.getMessage());
            renderFallback(canvas, resW, resH, 0xFF0A0A0E);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    // Render the fluid once into an offscreen device-resolution image (frozen at the
    // current `time`) so static mode can blit it each frame.
    private void buildStatic(DirectContext ctx, int dw, int dh, float time) {
        invalidateStatic();
        try (Surface off = Surface.makeRenderTarget(ctx, false, ImageInfo.makeN32Premul(dw, dh))) {
            drawFluid(off.getCanvas(), Rect.makeWH(dw, dh), dw, dh, time);
            staticImage = off.makeImageSnapshot();
            staticW = dw;
            staticH = dh;
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn("fluid static cache failed: {}", e.getMessage());
            invalidateStatic();
        }
    }

    private void invalidateStatic() {
        if (staticImage != null) {
            staticImage.close();
            staticImage = null;
        }
        staticW = -1;
        staticH = -1;
    }

    public void dispose() {
        decoder.shutdownNow();
        if (decodedCover != null) {
            decodedCover.close();
            decodedCover = null;
        }
        if (coverShader != null) {
            coverShader.close();
            coverShader = null;
        }
        if (cover != null) {
            cover.close();
            cover = null;
        }
        invalidateStatic();
        fluidPaint.close();
        coverKey = null;
    }

    // ---- AMLL-mirrored CPU preprocessing -----------------------------

    private static Image buildTexture(byte[] coverBytes) {
        if (coverBytes == null || coverBytes.length == 0) return null;
        try {
            // Decode + downscale to TEX_SIZE with Skija (no platform image lib):
            // raster the encoded cover into a 32x32 RGBA bitmap, then read back the
            // pixels as ARGB ints for the AMLL CPU adjust.
            Image src = Image.makeDeferredFromEncodedBytes(coverBytes);
            ImageInfo thumbInfo = new ImageInfo(TEX_SIZE, TEX_SIZE,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            Bitmap thumb = new Bitmap();
            thumb.allocPixels(thumbInfo);
            Canvas tc = new Canvas(thumb);
            tc.drawImageRect(src,
                    Rect.makeWH(src.getWidth(), src.getHeight()),
                    Rect.makeWH(TEX_SIZE, TEX_SIZE),
                    SamplingMode.LINEAR, null, true);
            src.close();
            byte[] raw = thumb.readPixels();
            thumb.close();
            if (raw == null) return null;

            int[] argb = new int[TEX_SIZE * TEX_SIZE];
            for (int i = 0; i < argb.length; i++) {
                int r = raw[i * 4] & 0xFF;
                int g = raw[i * 4 + 1] & 0xFF;
                int b = raw[i * 4 + 2] & 0xFF;
                int a = raw[i * 4 + 3] & 0xFF;
                argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

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
            return Image.makeRasterFromBytes(info, px, TEX_SIZE * 4L);
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
