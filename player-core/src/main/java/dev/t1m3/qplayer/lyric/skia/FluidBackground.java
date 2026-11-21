package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Random;

/** Three selectable album-driven fluid backdrops, implemented in SkSL for both hosts. */
public final class FluidBackground {

    public static final int STYLE_PIXI_RENDERER = 0;
    public static final int STYLE_MESH_GRADIENT = 1;
    public static final int STYLE_CLASSIC = 2;
    /**
     * 动态背景（网格形变）：网易云动态背景 0x7A9CBB58 的忠实移植
     * （三层旋转封面 + 9×9 控制网格形变 + 大半径模糊），见 {@link MeshWarp}。
     */
    public static final int STYLE_DYNAMIC_MESH = 3;

    /**
     * 这一档的模糊半径（原型 {@code C.backgroundLogicalBlur}，逻辑 px）。
     *
     * <p>原型是在 ≤480px 的离屏上渲染、再按 {@code pixelRatio × offscreenScale} 放大回来的，
     * 折算到屏幕坐标正好是 80 逻辑 px。这里交给 Skia 的 image filter 走同一尺度，
     * 少一层「先降分辨率再放大」的糊化，结构保留更多，观感一致。
     */
    private static final float DYNAMIC_BLUR = 80f;

    /**
     * 静态缓存那条路的模糊 sigma。
     *
     * <p>实测（1x 与 2x 各渲一帧、把 2x 降采样回来比：平均绝对差 0.13/255）：
     * <strong>Skia 会按 CTM 缩放 image filter 的 sigma</strong>，所以实时路径给逻辑 px 就对了。
     * 而静态缓存的离屏画布是<strong>设备分辨率、且没有 CTM</strong>，同一个 sigma 在那里
     * 会变成「80 设备 px」——在 2.5x 的手机上就是 32 逻辑 px，静态背景会比动态背景锐一大截。
     * 所以那条路要自己把逻辑 px 乘成设备 px。
     */
    static float staticBlurSigma(float logicalSigma, float uiScale) {
        return logicalSigma * Math.max(1f, uiScale);
    }

    /** 相位速率（原型 {@code C.backgroundPhaseRate}，单位 1/ms）：phase = 0.13 × dtMs。 */
    private static final float DYNAMIC_PHASE_RATE = 0.13f;

    /** 原型的 {@code C.backgroundShaderTimeScale}：uTime = phase / 60。 */
    private static final float DYNAMIC_TIME_SCALE = 1f / 60f;

    private static final int TEX_SIZE = 32;

    private static final String SHADER_ROOT = "/shaders/fluid/";
    private static final String[][] SHADER_FILES = {
            {"pixi_renderer.sksl", "pixi_renderer_fade.sksl"},
            {"meshgradient.sksl", "meshgradient.sksl"},
            {"classic.sksl", "classic_fade.sksl"},
            // 交叉淡入不用另写一份：这一档在着色器里用 alpha 做预乘叠加
            {"netease_dynamic.sksl", "netease_dynamic.sksl"}
    };
    private static final RuntimeEffect[][] EFFECTS = new RuntimeEffect[SHADER_FILES.length][2];

    private static RuntimeEffect makeEffect(int style, boolean fade) {
        int safeStyle = normalizeStyle(style);
        int variant = fade ? 1 : 0;
        RuntimeEffect cached = EFFECTS[safeStyle][variant];
        if (cached != null) return cached;
        String file = SHADER_FILES[safeStyle][variant];
        cached = compileResource(SHADER_ROOT + file, file);
        EFFECTS[safeStyle][variant] = cached;
        return cached;
    }

    private static RuntimeEffect compileResource(String path, String label) {
        try (InputStream in = FluidBackground.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("resource not found: " + path);
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            StringBuilder source = new StringBuilder(4096);
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) source.append(buffer, 0, read);
            return RuntimeEffect.makeForShader(source.toString());
        } catch (Throwable t) {
            dev.t1m3.qplayer.util.Logger.warn("{} fluid sksl compile failed: {}", label, t.getMessage());
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new IllegalStateException("Failed to load fluid shader " + label, t);
        }
    }

    private static int normalizeStyle(int style) {
        return style >= STYLE_PIXI_RENDERER && style <= STYLE_DYNAMIC_MESH
                ? style : STYLE_PIXI_RENDERER;
    }

    private final long startNs;
    private Image cover;
    // Cover sampled as a shader child every frame. The texture only changes on a
    // track change, so build the child shader once (not per frame) and reuse it;
    // makeShader + close on the cover every frame was needless native churn.
    private Shader coverShader;
    private Image coverAdjusted;
    private Shader coverAdjustedShader;
    // 动态背景（网格形变）那一档的封面：192px、不调色、MIRROR 取样（原型直接镜像拼接封面）。
    // 与上面 32px 的 raw/adjusted 分开：那一档把封面**放大**成三层方块，
    // 32px 放大后只剩色块，原图的结构在 80px 模糊之后会彻底糊掉。
    private Image coverArt;
    private Shader coverArtShader;
    private MeshWarp.Base coverWarp;
    private String coverKey;
    private float[] coverAngles = new float[4];
    private AMLLMeshGradient.Data coverMesh;
    private final float bendDirection;
    // Previous cover, kept alive to cross-fade into the new one on a track switch. Both
    // shader + image are released once the fade completes (or the page is disposed).
    private Shader coverPrevShader;
    private Image coverPrev;
    private Shader coverPrevAdjustedShader;
    private Image coverPrevAdjusted;
    private Image coverPrevArt;
    private Shader coverPrevArtShader;
    private MeshWarp.Base coverPrevWarp;
    private float[] coverPrevAngles;
    private AMLLMeshGradient.Data coverPrevMesh;
    private long fadeStartNs;
    private static final float FADE_DUR = 0.6f; // seconds
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
    private volatile DecodedTextures decoded; // built off-thread, awaiting render thread
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
    private int activeStyle = -1;
    /** 动态背景那一档的模糊滤镜（按 sigma 缓存；绘制中途不能释放它）。 */
    private ImageFilter dynamicBlur;
    private float dynamicBlurSigma = -1f;
    /**
     * 这一帧用的频谱（低/中/高）与「屏幕上有没有歌词」。
     *
     * <p>存成字段是因为静态缓存那一趟（{@code buildStatic}）也要用同一组值：
     * 它由 {@code render} 调用，但走的是另一条绘制路径。
     */
    private final float[] levels = new float[3];
    private boolean hasLyricLines;

    public FluidBackground(long startNs) {
        this.startNs = startNs;
        this.bendDirection = (startNs & 1L) == 0L ? 1f : -1f;
    }

    /**
     * Draw the fluid backdrop into [0,0,w,h] (logical px, under the caller's uiScale
     * matrix). When {@code staticMode}, the fluid is rendered once to an offscreen
     * image and that image is blitted every frame (no per-frame full-screen SkSL);
     * otherwise it animates with {@code nowNs}. {@code ctx}/{@code uiScale} are only
     * used to build the device-resolution static cache.
     */
    public void render(Canvas canvas, DirectContext ctx, float uiScale, float w, float h,
                       byte[] coverBytes, String trackKey, long nowNs, float[] spectrum,
                       boolean hasLyric, boolean staticMode, int style) {
        // 频谱与「有没有歌词」先落到字段：静态缓存那一趟要用同一组值（见字段注释）。
        // 频谱为 null / 长度不足时按全 0 处理，也就是原型里「没在放歌」的那一档。
        for (int i = 0; i < 3; i++) {
            levels[i] = spectrum != null && spectrum.length > i ? spectrum[i] : 0f;
        }
        hasLyricLines = hasLyric;
        int selectedStyle = normalizeStyle(style);
        if (selectedStyle != activeStyle) {
            activeStyle = selectedStyle;
            invalidateStatic();
        }
        boolean keyChanged = !Objects.equals(trackKey, coverKey);
        boolean nullButReady = cover == null && coverBytes != null && coverBytes.length > 0;
        // Kick an off-thread decode for a newly-selected cover, once per key.
        if ((keyChanged || nullButReady) && coverBytes != null && coverBytes.length > 0
                && !Objects.equals(trackKey, decodingKey)) {
            decodingKey = trackKey;
            final byte[] cb = coverBytes;
            final String key = trackKey;
            decoder.submit(() -> {
                DecodedTextures textures = buildTextures(cb, key);
                if (textures != null) decoded = textures;
            });
        }
        // Publish a ready off-thread texture (render thread): swap the shader, drop the
        // old cover. Cheap — the expensive work already happened on the decoder thread.
        DecodedTextures ready = decoded;
        if (ready != null && Objects.equals(ready.key, trackKey)
                && !Objects.equals(coverKey, trackKey)) {
            // Retire the current cover to the "prev" slot and cross-fade to the new one
            // rather than snapping. Both stay alive until the fade ends (releasePrev).
            releasePrev();
            coverPrevShader = coverShader;   // null on the very first cover
            coverPrev = cover;
            coverPrevAdjustedShader = coverAdjustedShader;
            coverPrevAdjusted = coverAdjusted;
            coverPrevArtShader = coverArtShader;
            coverPrevArt = coverArt;
            coverPrevWarp = coverWarp;
            coverPrevAngles = coverPrevShader == null ? null : coverAngles;
            coverPrevMesh = coverPrevShader == null ? null : coverMesh;
            cover = ready.raw;
            coverAdjusted = ready.adjusted;
            coverArt = ready.art;
            decoded = null;
            coverKey = trackKey;
            coverAngles = ready.angles;
            coverMesh = ready.mesh;
            coverShader = cover.makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            coverAdjustedShader = coverAdjusted.makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            coverArtShader = coverArt == null ? null : coverArt.makeShader(
                    // 原型是 MIRRORED_REPEAT：三层方块各自露出封面的一部分，镜像拼接才有那个拼贴感
                    FilterTileMode.MIRROR, FilterTileMode.MIRROR,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            coverWarp = ready.warp;
            fadeStartNs = nowNs;
            invalidateStatic();   // the source changed -> the cached frame is stale
        }
        if (coverShader == null && coverArtShader == null) {
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
        // cover/size change (or when first entering static mode). Battery-saver mode
        // doesn't animate, so it can't play the cross-fade — drop the prev cover and
        // snap to the new one.
        if (staticMode && ctx != null) {
            releasePrev();
            int dw = Math.max(1, Math.round(w * uiScale));
            int dh = Math.max(1, Math.round(h * uiScale));
            if (staticImage == null || staticW != dw || staticH != dh) {
                buildStatic(ctx, dw, dh, time, uiScale);
            }
            if (staticImage != null) {
                canvas.drawImageRect(staticImage, fullRect);
                return;
            }
            // build failed -> fall through to the live shader this frame
        }

        // 实时路径：画布上已经挂了 uiScale 的 CTM，所以 sigma 用逻辑 px（Skia 自己缩放）
        drawFluid(canvas, fullRect, w, h, time, fadeProgress(nowNs), DYNAMIC_BLUR);
    }

    // Fade progress 0..1 since the last cover swap; releases the previous cover once done.
    private float fadeProgress(long nowNs) {
        if (coverPrevShader == null) return 1f;
        float duration = activeStyle == STYLE_MESH_GRADIENT ? 0.5f : FADE_DUR;
        float t = (nowNs - fadeStartNs) / 1_000_000_000f / duration;
        float releaseAt = activeStyle == STYLE_MESH_GRADIENT ? 1.1f : 1f;
        if (t >= releaseAt) { releasePrev(); return 1f; }
        if (t <= 0f) return 0f;
        // AMLL increases the newest mesh alpha linearly for 500 ms and
        // retains the prior mesh until the value reaches 1.1.
        if (activeStyle == STYLE_MESH_GRADIENT) return t;
        float clamped = Math.min(1f, t);
        return 0.5f - 0.5f * (float) Math.cos(Math.PI * clamped);
    }

    private void releasePrev() {
        if (coverPrevShader != null) { coverPrevShader.close(); coverPrevShader = null; }
        if (coverPrev != null) { coverPrev.close(); coverPrev = null; }
        if (coverPrevAdjustedShader != null) {
            coverPrevAdjustedShader.close();
            coverPrevAdjustedShader = null;
        }
        if (coverPrevAdjusted != null) { coverPrevAdjusted.close(); coverPrevAdjusted = null; }
        if (coverPrevArtShader != null) { coverPrevArtShader.close(); coverPrevArtShader = null; }
        if (coverPrevArt != null) { coverPrevArt.close(); coverPrevArt = null; }
        coverPrevWarp = null;
        coverPrevAngles = null;
        coverPrevMesh = null;
    }

    // Render the fluid shader into `dstRect` with the given resolution + time. The
    // runtime shader bakes its uniforms at makeShader time, so an animated `time`
    // rebuilds it each call -- but that just instantiates the already-compiled
    // effect; the cover child shader and the Paint are reused.
    private void drawFluid(Canvas canvas, Rect dstRect, float resW, float resH, float time, float fade,
                           float blurSigma) {
        // 老一档的 `beat` 是「0..1 的响度脉冲」，低频电平是它最接近的等价物
        // （旧音频栈那个钩子没了之后它一直是 0，等于这些着色器的响应项从没生效过）
        float beat = levels[0];
        if (activeStyle == STYLE_DYNAMIC_MESH) {
            drawDynamicMesh(canvas, resW, resH, time, fade, blurSigma);
            return;
        }
        if (activeStyle == STYLE_MESH_GRADIENT) {
            drawMeshGradient(canvas, resW, resH, time, fade, beat);
            return;
        }
        boolean fading = coverPrevShader != null && fade < 1f;
        boolean rawTexture = activeStyle == STYLE_PIXI_RENDERER;
        Shader currentChild = rawTexture ? coverShader : coverAdjustedShader;
        Shader previousChild = rawTexture ? coverPrevShader : coverPrevAdjustedShader;
        Shader shaded = null;
        try {
            try (RuntimeEffectBuilder b = new RuntimeEffectBuilder(makeEffect(activeStyle, fading))) {
                b.setUniform("resolution", resW, resH);
                b.setUniform("time", time);
                b.setUniform("beat", beat);
                b.setChild("cover", currentChild);
                if (activeStyle == STYLE_PIXI_RENDERER) b.setUniform("bend", bendDirection);
                if (activeStyle != STYLE_CLASSIC) b.setUniform("angles", coverAngles);
                if (fading) {
                    b.setChild("coverPrev", previousChild);
                    b.setUniform("fade", fade);
                    if (activeStyle != STYLE_CLASSIC) b.setUniform("anglesPrev", coverPrevAngles);
                }
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

    /**
     * 动态背景（网格形变）：三层旋转封面 + 网格形变 + 大半径模糊。
     *
     * <p>相位与原型一致：{@code phase += 0.13 × dtMs}（这里由秒换算），
     * {@code uTime = phase / 60}，形变插值走 {@link MeshWarp#morphAt(double)}。
     *
     * <p>换曲的交叉淡入用两趟 draw 完成：先把上一张封面<strong>不透明</strong>画出来，
     * 再把这一张按 {@code alpha = fade} 预乘叠上去——几何不同（网格变体不同）没法在一个
     * 着色器里插值，两趟叠加得到的是同一个结果。
     */
    private void drawDynamicMesh(Canvas canvas, float w, float h, float time, float fade,
                                 float blurSigma) {
        MeshWarp.Base base = coverWarp;
        Shader art = coverArtShader;
        if (base == null || art == null) {
            return;
        }
        boolean fading = coverPrevWarp != null && coverPrevArtShader != null && fade < 1f;
        double phase = (double) time * 1000.0 * DYNAMIC_PHASE_RATE;
        float morph = MeshWarp.morphAt(phase);
        float shaderTime = (float) (phase * DYNAMIC_TIME_SCALE);
        if (fading) {
            drawDynamicState(canvas, coverPrevWarp, coverPrevArtShader, w, h, shaderTime, morph, 1f,
                    blurSigma);
        }
        drawDynamicState(canvas, base, art, w, h, shaderTime, morph, fading ? fade : 1f, blurSigma);
    }

    private void drawDynamicState(Canvas canvas, MeshWarp.Base mesh, Shader art,
                                  float w, float h, float time, float morph, float alpha,
                                  float blurSigma) {
        if (mesh == null || art == null || alpha <= 0f) {
            return;
        }
        Shader shaded = null;
        try {
            try (RuntimeEffectBuilder b = new RuntimeEffectBuilder(
                    makeEffect(STYLE_DYNAMIC_MESH, false))) {
                b.setUniform("resolution", w, h);
                b.setUniform("time", time);
                b.setUniform("low", levels[0]);
                b.setUniform("mid", levels[1]);
                b.setUniform("high", levels[2]);
                b.setUniform("hasLyric", hasLyricLines ? 1f : 0f);
                b.setUniform("alpha", alpha);
                b.setChild("cover", art);
                shaded = b.makeShader();
            }
            // 模糊挂在**整块画布那一层**的 paint 上，不能挂在画网格的那支画刷上。
            //
            // 这一条是实测出来的：同一份网格与着色器，把 image filter 挂在画刷上时
            // Skia 只画出中间约一半的像素（覆盖率 51.3%，四周变透明），而同样内容的
            // 矩形绘制完全正常。
            //
            // 两层坑都要绕开（都是本机实测）：
            //   ① filter 必须挂在 saveLayer 的层 paint 上，不能挂在画网格的画刷上；
            //   ② 层边界必须**正好是画面**（Rect.makeWH(w,h)）。把层边界外扩 80 想让
            //      模糊有内容可借，反而又丢掉 43.5% 的覆盖率——所以别「贴心」地加 padding，
            //      边缘的 CLAMP 由 filter 自己处理。
            // 两条都由 desktop-host 的 FluidBackdropRenderTest 钉着。
            try (Paint layerPaint = new Paint()) {
                layerPaint.setImageFilter(dynamicBlurFilter(blurSigma));
                int save = canvas.saveLayer(Rect.makeWH(w, h), layerPaint);
                try {
                    fluidPaint.setShader(shaded);
                    // _nDrawVertices 的形参顺序是 (positions, colors, texCoords, indices, ...)：
                    // colors 传 null（顶点色不走这里，取色由片元着色器按 texCoords 做）
                    Canvas._nDrawVertices(canvas._ptr, 0,
                            mesh.positions(morph, MeshWarp.STRENGTH, MeshWarp.EXPANSION, w, h),
                            null, mesh.uvs, mesh.indices, BlendMode.SRC_OVER.ordinal(), fluidPaint._ptr);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn(
                    "dynamic mesh backdrop render failed: {}", e.getMessage());
            renderFallback(canvas, w, h, 0xFF0A0A0E);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    /**
     * 这一档的模糊滤镜：按 sigma 缓存（原型 backgroundLogicalBlur = 80 逻辑 px）。
     *
     * <p>实时与静态两条路的 sigma 单位不同（见 {@link #staticBlurSigma}），所以缓存要认 sigma：
     * 两种模式来回切时不重建就会拿错尺度。
     */
    private ImageFilter dynamicBlurFilter(float sigma) {
        if (dynamicBlur != null && dynamicBlurSigma == sigma) {
            return dynamicBlur;
        }
        if (dynamicBlur != null) {
            dynamicBlur.close();
        }
        dynamicBlur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
        dynamicBlurSigma = sigma;
        return dynamicBlur;
    }

    private void drawMeshGradient(Canvas canvas, float w, float h, float time, float fade, float beat) {
        boolean fading = coverPrevAdjustedShader != null && coverPrevMesh != null;
        // AMLL keeps previous mesh states fully opaque underneath while the
        // newest state rises linearly from alpha 0, then discards older states at 1.1.
        if (fading) drawMeshGradientState(
                canvas, coverPrevMesh, coverPrevAdjustedShader, w, h, time, 1f, beat);
        drawMeshGradientState(
                canvas, coverMesh, coverAdjustedShader, w, h, time, fading ? fade : 1f, beat);
    }

    private void drawMeshGradientState(Canvas canvas, AMLLMeshGradient.Data mesh,
                                       Shader coverChild, float w, float h,
                                       float time, float alpha, float beat) {
        if (mesh == null || coverChild == null || alpha <= 0f) return;
        Shader shaded = null;
        try {
            try (RuntimeEffectBuilder b = new RuntimeEffectBuilder(
                    makeEffect(STYLE_MESH_GRADIENT, false))) {
                b.setUniform("time", time);
                b.setUniform("alpha", alpha);
                b.setUniform("beat", beat);
                b.setChild("cover", coverChild);
                shaded = b.makeShader();
            }
            fluidPaint.setShader(shaded);
            Canvas._nDrawVertices(canvas._ptr, 0, mesh.points(w, h), null,
                    mesh.textureCoordinates, mesh.indices, BlendMode.SRC_OVER.ordinal(), fluidPaint._ptr);
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn(
                    "AMLL mesh gradient render failed: {}", e.getMessage());
            renderFallback(canvas, w, h, 0xFF0A0A0E);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    // Render the fluid once into an offscreen device-resolution image (frozen at the
    // current `time`) so static mode can blit it each frame.
    private void buildStatic(DirectContext ctx, int dw, int dh, float time, float uiScale) {
        invalidateStatic();
        try (Surface off = Surface.makeRenderTarget(ctx, false, ImageInfo.makeN32Premul(dw, dh))) {
            // 静态缓存冻结在当下这一帧：频谱与歌词状态用的就是 render 里刚写下的那份。
            // 离屏没有 CTM，模糊 sigma 要按设备像素给（见 staticBlurSigma）。
            drawFluid(off.getCanvas(), Rect.makeWH(dw, dh), dw, dh, time, 1f,
                    staticBlurSigma(DYNAMIC_BLUR, uiScale));
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

    /**
     * Release every object backed by the current Skia {@link DirectContext}.
     *
     * <p>The desktop compositor survives minimize-to-tray render-thread respawns,
     * while its DirectContext does not. Call this on the owning render thread
     * before that context is destroyed. Raster cover images/shaders are retained
     * and will be uploaded again when the next context lazily rebuilds caches.
     */
    public void invalidateGpuContext() {
        invalidateStatic();
    }

    public void dispose() {
        decoder.shutdownNow();
        if (decoded != null) { decoded.close(); decoded = null; }
        if (coverShader != null) {
            coverShader.close();
            coverShader = null;
        }
        if (cover != null) {
            cover.close();
            cover = null;
        }
        if (coverAdjustedShader != null) {
            coverAdjustedShader.close();
            coverAdjustedShader = null;
        }
        if (coverAdjusted != null) {
            coverAdjusted.close();
            coverAdjusted = null;
        }
        if (coverArtShader != null) { coverArtShader.close(); coverArtShader = null; }
        if (coverArt != null) { coverArt.close(); coverArt = null; }
        coverWarp = null;
        if (dynamicBlur != null) { dynamicBlur.close(); dynamicBlur = null; dynamicBlurSigma = -1f; }
        releasePrev();
        invalidateStatic();
        fluidPaint.close();
        coverKey = null;
    }

    // PixiRenderer starts every sprite at an independent random rotation. Keep those
    // angles stable for a track so reopening the lyric page does not visibly jump.
    private static float[] generateAngles(String trackKey, byte[] coverBytes) {
        Random random = new Random(seedFor(trackKey, coverBytes));
        float[] angles = new float[4];
        for (int i = 0; i < angles.length; i++) {
            angles[i] = random.nextFloat() * (float) (Math.PI * 2.0);
        }
        return angles;
    }

    private static long seedFor(String trackKey, byte[] coverBytes) {
        long seed = 0x9E3779B97F4A7C15L;
        if (trackKey != null) {
            for (int i = 0; i < trackKey.length(); i++) {
                seed ^= trackKey.charAt(i);
                seed *= 0x100000001B3L;
                seed ^= seed >>> 32;
            }
        } else {
            // A null track key is valid during initial restoration. Sample the bytes
            // sparsely so generating the rotations stays negligible beside decoding.
            int stride = Math.max(1, coverBytes.length / 64);
            for (int i = 0; i < coverBytes.length; i += stride) {
                seed ^= coverBytes[i] & 0xFFL;
                seed *= 0x100000001B3L;
            }
        }
        return seed;
    }

    // ---- Shared cover preprocessing ----------------------------------

    private static final class DecodedTextures {
        final String key;
        final Image raw;
        final Image adjusted;
        /** 动态背景那一档用的 192px 封面（见 {@link MeshWarp#ART}）；构建失败时为 null。 */
        final Image art;
        final float[] angles;
        final AMLLMeshGradient.Data mesh;
        /** 动态背景那一档的形变网格；构建失败时为 null（那一档会退化成不画）。 */
        final MeshWarp.Base warp;

        DecodedTextures(String key, Image raw, Image adjusted, Image art, float[] angles,
                        AMLLMeshGradient.Data mesh, MeshWarp.Base warp) {
            this.key = key;
            this.raw = raw;
            this.adjusted = adjusted;
            this.art = art;
            this.angles = angles;
            this.mesh = mesh;
            this.warp = warp;
        }

        void close() {
            raw.close();
            adjusted.close();
            if (art != null) art.close();
        }
    }

    private static DecodedTextures buildTextures(byte[] coverBytes, String trackKey) {
        if (coverBytes == null || coverBytes.length == 0) return null;
        try {
            // Decode + downscale to TEX_SIZE with Skija (no platform image lib):
            // raster the encoded cover into a 32x32 RGBA bitmap, then read back its
            // pixels for the blur-chain approximation.
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

            float[] rgb = new float[TEX_SIZE * TEX_SIZE * 3];
            int[] argb = new int[TEX_SIZE * TEX_SIZE];
            for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) {
                int r = raw[i * 4] & 0xFF;
                int g = raw[i * 4 + 1] & 0xFF;
                int b = raw[i * 4 + 2] & 0xFF;
                int a = raw[i * 4 + 3] & 0xFF;
                rgb[i * 3] = r;
                rgb[i * 3 + 1] = g;
                rgb[i * 3 + 2] = b;
                argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            // At 32px, four radius-2 passes approximate the combined visual radius
            // of Pixi's 5/10/20/40/80px full-stage blur chain.
            boxBlur(rgb, TEX_SIZE, TEX_SIZE, 2, 4);
            Image rawTexture = imageFromRgb(rgb);

            // AMLL MeshGradientRenderer and QPlayer's classic renderer use the
            // same cover pipeline before the mesh/warp: contrast .4,
            // saturation 3, contrast 1.7, brightness .75, then the same blur.
            float[] adjustedRgb = amllAdjust(argb);
            boxBlur(adjustedRgb, TEX_SIZE, TEX_SIZE, 2, 4);
            Image adjustedTexture = imageFromRgb(adjustedRgb);

            // 动态背景那一档要的 192px 封面（不调色、不预模糊：模糊由那一档的 80px 滤镜统一做）
            Image artTexture = buildArtTexture(coverBytes);

            // 网格形变：变体按曲目种子挑（同一首歌每次打开都是同一个形状，见 MeshWarp.variantFor）。
            // 单独 try/catch：网格数据缺失不该让其它三档一起没背景。
            MeshWarp.Base warp = null;
            try {
                MeshWarp.Grids grids = MeshWarp.grids();
                warp = new MeshWarp.Base(grids,
                        MeshWarp.variantFor(seedFor(trackKey, coverBytes), grids.variants.length));
            } catch (Throwable e) {
                dev.t1m3.qplayer.util.Logger.warn(
                        "dynamic mesh backdrop disabled: {}", e.toString());
            }
            return new DecodedTextures(trackKey, rawTexture, adjustedTexture, artTexture,
                    generateAngles(trackKey, coverBytes), AMLLMeshGradient.create(), warp);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 192px 的封面纹理（动态背景那一档用）：直接从编码字节缩放，不做任何调色。 */
    private static Image buildArtTexture(byte[] coverBytes) {
        try {
            Image src = Image.makeDeferredFromEncodedBytes(coverBytes);
            ImageInfo info = new ImageInfo(MeshWarp.ART, MeshWarp.ART,
                    ColorType.RGBA_8888, ColorAlphaType.OPAQUE);
            Bitmap thumb = new Bitmap();
            thumb.allocPixels(info);
            Canvas canvas = new Canvas(thumb);
            canvas.drawImageRect(src,
                    Rect.makeWH(src.getWidth(), src.getHeight()),
                    Rect.makeWH(MeshWarp.ART, MeshWarp.ART),
                    SamplingMode.LINEAR, null, true);
            src.close();
            byte[] pixels = thumb.readPixels();
            thumb.close();
            if (pixels == null) return null;
            return Image.makeRasterFromBytes(info, pixels, MeshWarp.ART * 4L);
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.warn(
                    "dynamic mesh cover texture failed: {}", e.toString());
            return null;
        }
    }

    private static Image imageFromRgb(float[] rgb) {
        byte[] px = new byte[TEX_SIZE * TEX_SIZE * 4];
        for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) {
            px[i * 4] = (byte) clampU8(rgb[i * 3]);
            px[i * 4 + 1] = (byte) clampU8(rgb[i * 3 + 1]);
            px[i * 4 + 2] = (byte) clampU8(rgb[i * 3 + 2]);
            px[i * 4 + 3] = (byte) 0xFF;
        }
        ImageInfo info = new ImageInfo(TEX_SIZE, TEX_SIZE,
                ColorType.RGBA_8888, ColorAlphaType.OPAQUE);
        return Image.makeRasterFromBytes(info, px, TEX_SIZE * 4L);
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

            r = ((r - 128f) * 1.7f + 128f) * 0.75f;
            g = ((g - 128f) * 1.7f + 128f) * 0.75f;
            b = ((b - 128f) * 1.7f + 128f) * 0.75f;

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
