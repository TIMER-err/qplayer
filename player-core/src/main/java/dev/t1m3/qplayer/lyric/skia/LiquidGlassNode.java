// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Modified Java/Skija port of IosLiquidGlassNavigationBar, Lens, Vibrancy,
// InnerShadow, InteractiveHighlight and miuix-blur (b587aaf2c907c77a8c3c02d8df0aede2937ef8f0).
// Adapted upstream from Kyant0/AndroidLiquidGlass (Apache-2.0).
package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.IRect;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.engine.binding.ObservableList;
import io.github.timer_err.qml4j.render.Painter;
import io.github.timer_err.qml4j.render.Renderer;
import io.github.timer_err.qml4j.render.SkijaItem;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Live, local-GPU implementation of miuix's liquid navigation and floating surfaces.
 * The containing root-child cache boundary must be direct-drawn (qml4j 0.2.33).
 * All distances below are logical dp; blur kernels alone run in texture pixels.
 */
public final class LiquidGlassNode extends SkijaItem {
    private static final float HEIGHT = 64;
    private static final float PADDING = 40;
    private static final float[] BOUNDARIES = {3.5496478f, 9.5f, 20, 44.10215f};
    private static final float[] BOX_VARIANCE = {0, 3.0265f, 7.5625f, 9, 202.696f};
    private final Item marker;
    private final boolean navigation;
    private final Property<?> countProperty;
    private final Property<?> selectedProperty;
    private final Property<?> targetProperty;
    private final Property<?> downProperty;
    private final Property<?> dragProperty;
    private final Property<?> colorProperty;
    private final Property<?> darkProperty;
    private final Property<?> normalProperty;
    private final Property<?> accentProperty;
    private final Property<Object> accentScaleProperty;
    private final LiquidGlassMotion motion;
    private boolean wasActive;
    private float gravityAngle = -(float) Math.PI / 2;
    private float density;
    private float logicalWidth;
    private float logicalHeight;
    private int textureWidth;
    private int textureHeight;
    private Rect textureRect;
    private Rect outerShadowClip;
    private RRect baseGeometry;
    private RRect accentGeometry;
    private RRect indicatorGeometry;
    private Matrix33 logicalImageMatrix;
    private Matrix33 captureMatrix;
    private final float[] capturedTransform = new float[9];
    private int capturedLeft;
    private int capturedTop;
    private Surface raw;
    private Surface combined;
    private Surface shadow;
    private Image outerShadowMask;
    private boolean outerShadowMaskDark;
    private Image outerShadowImage;
    private Surface outerShadowSurface;
    private final float[] outerShadowTransform = new float[9];
    private float outerShadowOffset = Float.NaN;
    private boolean outerShadowDark;
    private int outerShadowLeft;
    private int outerShadowTop;
    private BlurLevel low;
    private BlurLevel high;
    private float blurBlend;
    private Program vibrancy;
    private Program lens;
    private Program bloom;
    private Program interactive;
    private Program downsample;
    private final RuntimeEffect[] blurEffects = new RuntimeEffect[8];
    private Paint paint;
    private Paint overlayPaint;
    private Paint additivePaint;
    private Paint filterPaint;
    private ImageFilter outerShadow;
    private ImageFilter innerShadow;
    private float innerShadowRadius = -1;
    private boolean disposed;
    private final float[] one = new float[1];
    private final float[] two = new float[2];
    private final float[] three = new float[3];
    private float sourceLayoutWidth = -1;
    private long normalStructure = -1;
    private long accentStructure = -1;

    @SuppressWarnings("unchecked")
    public LiquidGlassNode(Item marker, Renderer renderer, boolean navigation) {
        super(renderer);
        this.marker = marker;
        this.navigation = navigation;
        motion = navigation ? new LiquidGlassMotion() : null;
        countProperty = navigation ? required(marker, "itemCount") : null;
        selectedProperty = navigation ? required(marker, "selectedIndex") : null;
        targetProperty = navigation ? required(marker, "targetIndex") : null;
        downProperty = navigation ? required(marker, "pointerDown") : null;
        dragProperty = navigation ? required(marker, "accumulatedDrag") : null;
        colorProperty = required(marker, "surfaceColor");
        darkProperty = required(marker, "darkTheme");
        normalProperty = navigation ? required(marker, "normalContent") : null;
        accentProperty = navigation ? required(marker, "accentContent") : null;
        accentScaleProperty = navigation ? (Property<Object>) required(marker, "accentScale") : null;
    }

    private static Property<?> required(Item item, String name) {
        Property<?> property = MemberAccess.propertyOf(item, name);
        if (property == null) throw new IllegalArgumentException("Liquid glass marker lacks " + name);
        return property;
    }

    /** Call before layout/DirtyQueue.flush so QML accent tab transforms see this frame's scale. */
    public void advance(long nowNs) {
        boolean active = isActive();
        if (!active) {
            if (wasActive) invalidateGpuContext();
            wasActive = false;
            return;
        }
        if (!navigation) {
            wasActive = true;
            return;
        }
        int selected = selectedProperty.peekInt();
        if (!wasActive) motion.reset(selected);
        wasActive = true;
        motion.advance(nowNs, selected, countProperty.peekInt(),
                Boolean.TRUE.equals(downProperty.peek()), targetProperty.peekDouble(), dragProperty.peekDouble());
        double scale = 1 + 0.2 * motion.press.value;
        Object old = accentScaleProperty.peek();
        if (!(old instanceof Number) || ((Number) old).doubleValue() != scale) accentScaleProperty.set(scale);
    }

    /** Does not depend on first-frame layout dimensions. */
    public boolean isActive() {
        if (disposed || (navigation && countProperty.peekInt() <= 0)) return false;
        for (Item item = marker; item != null; item = item.parent.peek()) {
            if (!Boolean.TRUE.equals(item.visible.peek()) || !Boolean.TRUE.equals(item.enabled.peek())) return false;
        }
        return true;
    }

    public boolean isAnimating() {
        return navigation && isActive() && motion.isAnimating();
    }

    public void setGravityAngle(float radians) {
        if (Float.isFinite(radians)) gravityAngle = radians;
    }

    @Override
    protected void paintSkija(Painter painter, Canvas canvas, float width, float height, float alpha) {
        if (!isActive() || width <= 8 || height <= 0 || alpha <= 0) return;
        // A recording canvas has no surface: failing loudly catches a missed live-boundary integration.
        try (Surface owner = canvas.getSurface()) {
            if (owner == null) throw new IllegalStateException("Liquid glass requires a live GPU canvas, not SkPicture recording");
            if (!hasGpuContext()) throw new IllegalStateException("Liquid glass requires a GPU renderer context");
            Matrix33 transform = canvas.getLocalToDeviceAsMatrix33();
            float[] m = transform._mat;
            float rho = (float) Math.hypot(m[0], m[3]);
            if (!(rho > 0)) return;
            ensureResources(owner, width, height, rho);
            int surfaceColor = Renderer.parseColor(String.valueOf(colorProperty.peek()));
            boolean dark = Boolean.TRUE.equals(darkProperty.peek());
            if (!navigation) {
                capture(owner, transform, width, height);
                renderBlur();
                int saved = alpha < 1 ? canvas.saveLayerAlpha(null, Math.round(alpha * 255)) : canvas.save();
                try {
                    drawOuterShadow(canvas, owner, transform, width, height, 0, dark);
                    drawPanel(painter, canvas, width, height, 1, 0, 0,
                            surfaceColor, null, false, 0, 0);
                    drawBloom(canvas, width, height, 0.75f, gravityAngle - (float) Math.PI / 4);
                } finally {
                    canvas.restoreToCount(saved);
                }
                return;
            }
            float p = (float) motion.press.value;
            float tabWidth = (width - 8) / countProperty.peekInt();
            float offset = panelOffset((float) motion.panel.value, width);
            float scale = 1 + 16 / width * p;
            Item normal = source(normalProperty, "normalContent");
            Item accent = source(accentProperty, "accentContent");
            ensureSourceLayout(normal, accent);
            capture(owner, transform, width, height);
            renderBlur();
            // P followed by T. T contains its own processed backdrop, tint, highlight and accent labels.
            Canvas combinedCanvas = combined.getCanvas();
            combinedCanvas.clear(0);
            try (Image original = raw.makeImageSnapshot()) {
                combinedCanvas.drawImage(original, 0, 0);
            }
            int cs = combinedCanvas.save();
            combinedCanvas.scale(density, density).translate(PADDING + offset, PADDING + 4);
            drawPanel(painter, combinedCanvas, width, 56, 1, offset, 4,
                    surfaceColor, accent, true, tabWidth, offset);
            combinedCanvas.restoreToCount(cs);

            int saved = alpha < 1 ? canvas.saveLayerAlpha(null, Math.round(alpha * 255)) : canvas.save();
            try {
                // Shadow is outside the base press-scale graphics layer, as in the source modifier chain.
                drawOuterShadow(canvas, owner, transform, width, HEIGHT, offset, dark);
                int baseSaved = canvas.save();
                canvas.translate(width / 2 + offset, HEIGHT / 2).scale(scale, scale)
                        .translate(-width / 2, -HEIGHT / 2);
                drawPanel(painter, canvas, width, HEIGHT, scale,
                        offset + width / 2 * (1 - scale), HEIGHT / 2 * (1 - scale),
                        surfaceColor, normal, false, tabWidth, offset);
                drawBloom(canvas, width, HEIGHT, 0.75f, gravityAngle - (float) Math.PI / 4);
                canvas.restoreToCount(baseSaved);

                float velocity = (float) motion.velocity.value / 10;
                float sx = (float) motion.scaleX.value / (1 - clamp(velocity * 0.75f, -0.2f, 0.2f));
                float sy = (float) motion.scaleY.value * (1 - clamp(velocity * 0.25f, -0.2f, 0.2f));
                float x = 4 + (float) motion.position.value * tabWidth + offset;
                int indicatorSaved = canvas.save();
                canvas.translate(x + tabWidth / 2, 32).scale(sx, sy).translate(-tabWidth / 2, -28);
                RRect pill = capsule(tabWidth, 56);
                int clipSaved = canvas.save();
                canvas.clipRRect(pill, true);
                try (Image image = combined.makeImageSnapshot(); Shader input = logicalShader(image)) {
                    drawLens(canvas, input, tabWidth, 56, 10 * p, -14 * p, 1, 0.5f,
                            sx, sy, x + tabWidth / 2 * (1 - sx), 4 + 28 * (1 - sy));
                }
                fill(canvas, pill, dark ? 0xFFFFFFFF : 0xFF000000, 0.1f * (1 - p));
                fill(canvas, pill, 0xFF000000, 0.03f * p);
                drawInnerShadow(canvas, tabWidth, p);
                drawBloom(canvas, tabWidth, 56, p, gravityAngle + (float) Math.PI / 2);
                canvas.restoreToCount(clipSaved);
                canvas.restoreToCount(indicatorSaved);
            } finally {
                canvas.restoreToCount(saved);
            }
        }
    }

    private static Item source(Property<?> property, String name) {
        if (property.peek() instanceof Item) return (Item) property.peek();
        throw new IllegalStateException("Liquid glass " + name + " must reference a QML Item");
    }

    private void ensureSourceLayout(Item normal, Item accent) {
        long normalVersion = structureVersion(normal);
        long accentVersion = structureVersion(accent);
        if (sourceLayoutWidth == normal.width.peekFloat()
                && normalStructure == normalVersion && accentStructure == accentVersion) return;
        // The normal scene skips descendants of invisible sources. In particular,
        // Icon's vector-search Loader is not instantiated until this explicit pass.
        layoutSource(normal);
        layoutSource(accent);
        sourceLayoutWidth = normal.width.peekFloat();
        normalStructure = structureVersion(normal);
        accentStructure = structureVersion(accent);
    }

    private static long structureVersion(Item item) {
        return item.children instanceof ObservableList
                ? ((ObservableList<?>) item.children).structuralVersion() : item.children.size();
    }

    private void ensureResources(Surface owner, float width, float height, float rho) {
        if (paint == null) {
            paint = new Paint().setAntiAlias(true);
            overlayPaint = new Paint().setAntiAlias(true);
            additivePaint = new Paint().setAntiAlias(true).setBlendMode(BlendMode.PLUS);
            filterPaint = new Paint().setAntiAlias(true);
            vibrancy = new Program("vibrancy");
            lens = new Program("lens");
            bloom = new Program("bloom");
            if (navigation) interactive = new Program("interactive");
            downsample = new Program("downsample");
        }
        int w = Math.max(1, (int) Math.ceil((width + PADDING * 2) * rho));
        int h = Math.max(1, (int) Math.ceil((height + PADDING * 2) * rho));
        if (raw != null && textureWidth == w && textureHeight == h && density == rho
                && logicalWidth == width && logicalHeight == height) return;
        closeSurfaces();
        density = rho;
        logicalWidth = width;
        logicalHeight = height;
        textureWidth = w;
        textureHeight = h;
        textureRect = Rect.makeWH(w, h);
        outerShadowClip = Rect.makeXYWH(-10, -10, width + 20, height + 20);
        logicalImageMatrix = new Matrix33(1 / rho, 0, -PADDING, 0, 1 / rho, -PADDING, 0, 0, 1);
        captureMatrix = null;
        raw = gpuSurface(owner, w, h);
        if (navigation) combined = gpuSurface(owner, w, h);
        shadow = gpuSurface(owner, w, h);
        if (outerShadow != null) outerShadow.close();
        // Android Compose dropShadow uses BlurMaskFilter(radius), whose legacy radius is
        // converted to sigma by Skia as radius / sqrt(3) + 0.5 device pixels.
        float shadowSigma = 10 * rho * 0.57735f + 0.5f;
        outerShadow = ImageFilter.makeBlur(shadowSigma, shadowSigma, FilterTileMode.DECAL);
        float sigma = 1.8f * rho;
        int exponent = exponent(sigma * sigma);
        int hi = exponent;
        blurBlend = 0;
        for (int b = 0; b < BOUNDARIES.length; b++) {
            float start = BOUNDARIES[b] * 0.88f;
            float end = BOUNDARIES[b] * 1.12f;
            if (sigma > start && sigma < end) {
                exponent = b;
                hi = b + 1;
                float t = (sigma - start) / (end - start);
                blurBlend = t * t * (3 - 2 * t);
                break;
            }
        }
        low = new BlurLevel(owner, exponent, sigma * sigma);
        high = hi == exponent ? null : new BlurLevel(owner, hi, sigma * sigma);
    }

    private static Surface gpuSurface(Surface owner, int w, int h) {
        Surface result = owner.makeSurface(w, h);
        if (result == null) throw new IllegalStateException("Cannot allocate local liquid-glass GPU surface " + w + "x" + h);
        return result;
    }

    private void capture(Surface owner, Matrix33 transform, float width, float height) {
        float[] m = transform._mat;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? -PADDING : width + PADDING;
            float y = (i & 2) == 0 ? -PADDING : height + PADDING;
            float z = m[6] * x + m[7] * y + m[8];
            float dx = (m[0] * x + m[1] * y + m[2]) / z;
            float dy = (m[3] * x + m[4] * y + m[5]) / z;
            minX = Math.min(minX, dx); minY = Math.min(minY, dy);
            maxX = Math.max(maxX, dx); maxY = Math.max(maxY, dy);
        }
        int left = Math.max(0, (int) Math.floor(minX));
        int top = Math.max(0, (int) Math.floor(minY));
        int right = Math.min(owner.getWidth(), (int) Math.ceil(maxX));
        int bottom = Math.min(owner.getHeight(), (int) Math.ceil(maxY));
        if (right <= left || bottom <= top) throw new IllegalStateException("Liquid glass backdrop is outside its GPU surface");
        // Map the cropped device image back through the exact incoming CTM into our local pixel texture.
        if (captureMatrix == null || capturedLeft != left || capturedTop != top || !Arrays.equals(capturedTransform, m)) {
            captureMatrix = Matrix33.makeScale(density)
                    .makeConcat(Matrix33.makeTranslate(PADDING, PADDING))
                    .makeConcat(inverse(transform)).makeConcat(Matrix33.makeTranslate(left, top));
            System.arraycopy(m, 0, capturedTransform, 0, 9);
            capturedLeft = left;
            capturedTop = top;
        }
        try (Image image = owner.makeImageSnapshot(IRect.makeLTRB(left, top, right, bottom));
             Shader input = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, captureMatrix)) {
            Canvas rawCanvas = raw.getCanvas();
            rawCanvas.clear(0);
            paint.setShader(input).setAlphaf(1);
            rawCanvas.drawRect(textureRect, paint);
            paint.setShader(null);
        }
    }

    private void renderBlur() {
        low.render();
        if (high != null) high.render();
    }

    private void drawPanel(Painter painter, Canvas canvas, float w, float h,
                           float sampleScale, float sampleX, float sampleY, int color,
                           Item content, boolean accent, float tabWidth, float panelOffset) {
        RRect shape = capsule(w, h);
        int saved = canvas.save();
        canvas.clipRRect(shape, true);
        // Lens is evaluated at each source blur level BEFORE upscaling/cross-fading.
        low.drawPanelLens(canvas, w, h, sampleScale, sampleX, sampleY, 1);
        if (high != null) high.drawPanelLens(canvas, w, h, sampleScale, sampleX, sampleY, blurBlend);
        fill(canvas, shape, color, 0.4f);
        if (content != null) {
            interactive.vector("center", clamp(((float) motion.position.value + 0.5f) * tabWidth + panelOffset, 0, w), h / 2);
            interactive.scalar("radius", Math.max(1 / density, Math.min(w, h) * 1.2f));
            interactive.scalar("progress", (float) motion.spot.value);
            drawProgram(canvas, shape, interactive, additivePaint);
            int sourceSaved = canvas.save();
            canvas.translate(4, accent ? 0 : 4);
            drawSource(painter, canvas, content, 1);
            canvas.restoreToCount(sourceSaved);
        }
        canvas.restoreToCount(saved);
    }

    private void drawLens(Canvas canvas, Shader input, float w, float h, float refractionHeight,
                          float amount, float depth, float dispersion,
                          float sx, float sy, float dx, float dy) {
        configureLens(input, w, h, refractionHeight, amount, depth, dispersion, sx, sy, dx, dy);
        drawProgram(canvas, capsule(w, h), lens, paint);
    }

    private void configureLens(Shader input, float w, float h, float refractionHeight,
                               float amount, float depth, float dispersion,
                               float sx, float sy, float dx, float dy) {
        lens.builder.setChild("content", input);
        lens.vector("size", w, h);
        lens.scalar("radius", Math.min(w, h) / 2);
        lens.scalar("refractionHeight", refractionHeight);
        lens.scalar("refractionAmount", amount);
        lens.scalar("depthEffect", depth);
        lens.scalar("chromaticAberration", dispersion);
        lens.vector("sampleScale", sx, sy);
        lens.vector("sampleOffset", dx, dy);
    }

    private void drawBloom(Canvas canvas, float w, float h, float alpha, float angle) {
        if (alpha <= 0) return;
        bloom.vector("size", w, h);
        bloom.scalar("density", density);
        bloom.scalar("highlightAlpha", alpha);
        float norm = (float) Math.sqrt(1 + 0.05f * 0.05f);
        bloom.vector3("lightDir1", (float) Math.cos(angle) / norm, (float) Math.sin(angle) / norm, -0.05f / norm);
        // Secondary source position (0.5,0.8,-0.5), relative to the source's (0.5,0.7,0) origin.
        float secondaryNorm = (float) Math.sqrt(0.1f * 0.1f + 0.5f * 0.5f);
        bloom.vector3("lightDir2", 0, 0.1f / secondaryNorm, -0.5f / secondaryNorm);
        drawProgram(canvas, capsule(w, h), bloom, additivePaint);
    }

    private void drawOuterShadow(Canvas canvas, Surface owner, Matrix33 transform,
                                 float w, float h, float offset, boolean dark) {
        float[] m = transform._mat;
        boolean unchanged = outerShadowOffset == offset && outerShadowDark == dark
                && Arrays.equals(outerShadowTransform, m);
        if (!unchanged) {
            if (outerShadowImage != null) { outerShadowImage.close(); outerShadowImage = null; }
            System.arraycopy(m, 0, outerShadowTransform, 0, 9);
            outerShadowOffset = offset;
            outerShadowDark = dark;
        }
        // Cache only a stationary shadow, on the exact device-pixel grid. Moving,
        // rotated or nonuniformly scaled layers keep the original filter path.
        if (!unchanged || m[0] != density || m[4] != density || m[1] != 0 || m[3] != 0
                || m[6] != 0 || m[7] != 0 || m[8] != 1) {
            drawOuterShadowFiltered(canvas, w, h, offset, dark);
            return;
        }
        if (outerShadowImage == null) {
            // Include a guard pixel for fractional CTM/clip rounding.
            outerShadowLeft = (int) Math.floor((offset - 10) * m[0] + m[2]) - 1;
            outerShadowTop = (int) Math.floor(-10 * m[4] + m[5]) - 1;
            int right = (int) Math.ceil((offset + w + 10) * m[0] + m[2]) + 1;
            int bottom = (int) Math.ceil((h + 10) * m[4] + m[5]) + 1;
            int width = Math.max(1, right - outerShadowLeft);
            int height = Math.max(1, bottom - outerShadowTop);
            if (outerShadowSurface == null || outerShadowSurface.getWidth() != width || outerShadowSurface.getHeight() != height) {
                if (outerShadowSurface != null) outerShadowSurface.close();
                outerShadowSurface = gpuSurface(owner, width, height);
            }
            Canvas cached = outerShadowSurface.getCanvas();
            cached.clear(0);
            cached.setMatrix(Matrix33.makeTranslate(-outerShadowLeft, -outerShadowTop).makeConcat(transform));
            drawOuterShadowFiltered(cached, w, h, offset, dark);
            outerShadowImage = outerShadowSurface.makeImageSnapshot();
        }
        int saved = canvas.save();
        canvas.resetMatrix();
        canvas.drawImage(outerShadowImage, outerShadowLeft, outerShadowTop);
        canvas.restoreToCount(saved);
    }

    private void drawOuterShadowFiltered(Canvas canvas, float w, float h, float offset, boolean dark) {
        if (outerShadowMask == null || outerShadowMaskDark != dark) {
            if (outerShadowMask != null) outerShadowMask.close();
            Canvas mask = shadow.getCanvas();
            mask.clear(0);
            int saved = mask.save();
            mask.scale(density, density).translate(PADDING, PADDING);
            overlayPaint.setColor(0xFF000000).setAlphaf(dark ? 0.2f : 0.1f);
            mask.drawRRect(capsule(w, h), overlayPaint);
            mask.restoreToCount(saved);
            outerShadowMask = shadow.makeImageSnapshot();
            outerShadowMaskDark = dark;
        }
        filterPaint.setImageFilter(outerShadow).setAlphaf(1);
        int saved = canvas.save();
        canvas.translate(offset, 0).clipRect(outerShadowClip);
        canvas.translate(-PADDING, -PADDING).scale(1 / density, 1 / density);
        canvas.drawImage(outerShadowMask, 0, 0, filterPaint);
        canvas.restoreToCount(saved);
        filterPaint.setImageFilter(null);
    }

    private void drawInnerShadow(Canvas canvas, float w, float p) {
        if (p <= 0) return;
        float radius = Math.max(0, 8 * p);
        if (innerShadow == null || innerShadowRadius != radius * density) {
            if (innerShadow != null) innerShadow.close();
            innerShadowRadius = radius * density;
            innerShadow = ImageFilter.makeBlur(innerShadowRadius, innerShadowRadius, FilterTileMode.DECAL);
        }
        Canvas mask = shadow.getCanvas();
        mask.clear(0);
        int saved = mask.save();
        mask.scale(density, density).translate(PADDING, PADDING);
        RRect pill = capsule(w, 56);
        mask.clipRRect(pill, true);
        overlayPaint.setColor(0xFF000000).setAlphaf(0.15f).setBlendMode(BlendMode.SRC_OVER);
        mask.drawRRect(pill, overlayPaint);
        mask.translate(0, radius);
        overlayPaint.setBlendMode(BlendMode.CLEAR);
        mask.drawRRect(pill, overlayPaint);
        overlayPaint.setBlendMode(BlendMode.SRC_OVER);
        mask.restoreToCount(saved);
        try (Image image = shadow.makeImageSnapshot()) {
            filterPaint.setImageFilter(innerShadow).setAlphaf(p);
            int targetSaved = canvas.save();
            canvas.translate(-PADDING, -PADDING).scale(1 / density, 1 / density);
            canvas.drawImage(image, 0, 0, filterPaint);
            canvas.restoreToCount(targetSaved);
            filterPaint.setImageFilter(null).setAlphaf(1);
        }
    }

    private void fill(Canvas canvas, RRect shape, int color, float alpha) {
        overlayPaint.setColor((color & 0x00FFFFFF) | 0xFF000000).setAlphaf(clamp(alpha, 0, 1));
        canvas.drawRRect(shape, overlayPaint);
    }

    private RRect capsule(float w, float h) {
        RRect cached = h == logicalHeight ? baseGeometry : w == logicalWidth ? accentGeometry : indicatorGeometry;
        if (cached != null && cached.getWidth() == w && cached.getHeight() == h) return cached;
        RRect shape = RRect.makeXYWH(0, 0, w, h, Math.min(w, h) / 2);
        if (h == logicalHeight) baseGeometry = shape;
        else if (w == logicalWidth) accentGeometry = shape;
        else indicatorGeometry = shape;
        return shape;
    }

    private Shader logicalShader(Image image) {
        return image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR,
                logicalImageMatrix);
    }

    private static Shader pixelShader(Image image) {
        return image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, Matrix33.IDENTITY);
    }

    private static void drawProgram(Canvas canvas, Rect rect, Program program, Paint paint) {
        try (Shader shader = program.builder.makeShader()) {
            paint.setShader(shader);
            canvas.drawRect(rect, paint);
            paint.setShader(null);
        }
        if (program.hasContent) program.builder.setChild("content", null);
    }

    private static int exponent(float sigmaSquared) {
        if (sigmaSquared >= 1945) return 4;
        if (sigmaSquared > 400) return 3;
        if (sigmaSquared >= 90.25f) return 2;
        return sigmaSquared >= 12.6f ? 1 : 0;
    }

    private final class BlurLevel implements AutoCloseable {
        private final Surface[] reductions;
        private final int[] factors;
        private final Surface saturated;
        private final Rect[] reductionRects;
        private final Surface horizontal;
        private final Surface vertical;
        private final Surface refracted;
        private final Matrix33 imageMatrix;
        private final Rect panelLensRect;
        private final Rect accentLensRect;
        private final RuntimeEffectBuilder horizontalBuilder;
        private final RuntimeEffectBuilder verticalBuilder;
        private final int factor;
        private final Rect rect;

        BlurLevel(Surface owner, int exponent, float sigmaSquared) {
            factor = 1 << exponent;
            switch (exponent) {
                case 0: factors = new int[0]; break;
                case 1: factors = new int[]{2}; break;
                case 2: factors = new int[]{2, 2}; break;
                case 3: factors = new int[]{2, 4}; break;
                default: factors = new int[]{2, 4, 2}; break;
            }
            reductions = new Surface[factors.length];
            reductionRects = new Rect[factors.length];
            int w = textureWidth, h = textureHeight;
            for (int i = 0; i < reductions.length; i++) {
                w = Math.max(1, w / factors[i]);
                h = Math.max(1, h / factors[i]);
                reductions[i] = gpuSurface(owner, w, h);
                reductionRects[i] = Rect.makeWH(w, h);
            }
            saturated = gpuSurface(owner, w, h);
            horizontal = gpuSurface(owner, w, h);
            vertical = gpuSurface(owner, w, h);
            refracted = gpuSurface(owner, w, h);
            float pixelScale = density / factor;
            imageMatrix = new Matrix33(1 / pixelScale, 0, -PADDING, 0, 1 / pixelScale, -PADDING, 0, 0, 1);
            // Only the capsule and its linear-sampling footprint consume this output.
            // Keep the full padded INPUT: refraction and blur still sample outside the capsule.
            float guard = 2 / pixelScale;
            panelLensRect = Rect.makeXYWH(-guard, -guard, logicalWidth + 2 * guard, logicalHeight + 2 * guard);
            accentLensRect = navigation ? Rect.makeXYWH(-guard, -guard, logicalWidth + 2 * guard, 56 + 2 * guard) : null;
            rect = Rect.makeWH(w, h);
            float variance = Math.max(0.1f, (sigmaSquared - BOX_VARIANCE[exponent]) / (factor * factor));
            float[] offsets = new float[7];
            float[] weights = new float[7];
            int taps = kernel(variance, offsets, weights);
            RuntimeEffect effect = blurEffects[taps];
            if (effect == null) blurEffects[taps] = effect = compile("blur", taps);
            horizontalBuilder = new RuntimeEffectBuilder(effect);
            verticalBuilder = new RuntimeEffectBuilder(effect);
            weights = Arrays.copyOf(weights, taps);
            float[] vectors = new float[taps * 2];
            for (int i = 0; i < taps; i++) vectors[i * 2] = offsets[i];
            horizontalBuilder.setUniform("offsets", vectors).setUniform("weights", weights);
            Arrays.fill(vectors, 0);
            for (int i = 0; i < taps; i++) vectors[i * 2 + 1] = offsets[i];
            verticalBuilder.setUniform("offsets", vectors).setUniform("weights", weights);
            two[0] = w - 0.5f; two[1] = h - 0.5f;
            horizontalBuilder.setUniform("maxCoord", two);
            verticalBuilder.setUniform("maxCoord", two);
        }

        void render() {
            Surface input = raw;
            for (int i = 0; i < reductions.length; i++) {
                Surface output = reductions[i];
                try (Image image = input.makeImageSnapshot(); Shader child = pixelShader(image)) {
                    downsample.builder.setChild("content", child);
                    downsample.scalar("factor", factors[i]);
                    downsample.scalar("spread", i == 0 ? 0 : factors[i] == 2 ? 0.25f : 0.75f);
                    downsample.vector("maxCoord", input.getWidth() - 0.5f, input.getHeight() - 0.5f);
                    drawProgram(output.getCanvas(), reductionRects[i], downsample, paint);
                }
                input = output;
            }
            try (Image image = input.makeImageSnapshot(); Shader child = pixelShader(image)) {
                vibrancy.builder.setChild("content", child);
                drawProgram(saturated.getCanvas(), rect, vibrancy, paint);
            }
            blurAxis(saturated, horizontal, horizontalBuilder);
            blurAxis(horizontal, vertical, verticalBuilder);
        }

        private void blurAxis(Surface input, Surface target, RuntimeEffectBuilder builder) {
            try (Image image = input.makeImageSnapshot(); Shader child = pixelShader(image)) {
                builder.setChild("content", child);
                try (Shader shader = builder.makeShader()) {
                    paint.setShader(shader);
                    target.getCanvas().drawRect(rect, paint);
                    paint.setShader(null);
                }
                builder.setChild("content", null);
            }
        }

        void drawPanelLens(Canvas target, float w, float h, float scale, float x, float y, float alpha) {
            Canvas result = refracted.getCanvas();
            result.clear(0);
            int saved = result.save();
            result.scale(density / factor, density / factor).translate(PADDING, PADDING);
            try (Image image = vertical.makeImageSnapshot();
                 Shader child = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, imageMatrix)) {
                configureLens(child, w, h, 24, -24, 0, 0, scale, scale, x, y);
                drawProgram(result, h == logicalHeight ? panelLensRect : accentLensRect, lens, paint);
            } finally {
                result.restoreToCount(saved);
            }
            try (Image image = refracted.makeImageSnapshot();
                 Shader shader = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, imageMatrix)) {
                paint.setShader(shader).setAlphaf(alpha);
                target.drawRect(capsule(w, h), paint);
                paint.setShader(null).setAlphaf(1);
            }
        }

        @Override
        public void close() {
            for (Surface surface : reductions) surface.close();
            saturated.close();
            horizontal.close();
            vertical.close();
            refracted.close();
            closeBuilder(horizontalBuilder);
            closeBuilder(verticalBuilder);
        }
    }

    private static int kernel(float variance, float[] offsets, float[] weights) {
        double[] raw = new double[14];
        double total = 0;
        for (int i = 0; i < 14; i++) {
            raw[i] = Math.exp(-0.5 * i * i / variance);
            total += raw[i] * (i == 0 ? 1 : 2);
        }
        for (int i = 0; i < 14; i++) raw[i] /= total;
        offsets[0] = (float) (raw[1] / (raw[0] * 0.5 + raw[1]));
        int n = 1;
        float sum = 0;
        for (int i = 2; i < 14 && n < 7; i += 2) {
            double combined = raw[i] + raw[i + 1];
            if (combined < 0.002) break;
            offsets[n] = (float) ((raw[i] * i + raw[i + 1] * (i + 1)) / combined);
            weights[n] = (float) combined;
            sum += weights[n++];
        }
        weights[0] = Math.max(0, 0.5f - sum);
        return n;
    }

    private final class Program implements AutoCloseable {
        private final RuntimeEffect effect;
        private final RuntimeEffectBuilder builder;
        private final boolean hasContent;

        Program(String resource) {
            effect = compile(resource);
            builder = new RuntimeEffectBuilder(effect);
            hasContent = !resource.equals("bloom") && !resource.equals("interactive");
        }

        void scalar(String name, float value) {
            one[0] = value;
            builder.setUniform(name, one);
        }

        void vector(String name, float x, float y) {
            two[0] = x; two[1] = y;
            builder.setUniform(name, two);
        }

        void vector3(String name, float x, float y, float z) {
            three[0] = x; three[1] = y; three[2] = z;
            builder.setUniform(name, three);
        }

        @Override
        public void close() {
            closeBuilder(builder);
            effect.close();
        }
    }

    private static RuntimeEffect compile(String name) {
        return compile(name, 0);
    }

    private static RuntimeEffect compile(String name, int taps) {
        String resource = "/shaders/liquid-glass/" + name + ".sksl";
        try (InputStream stream = LiquidGlassNode.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            StringBuilder text = new StringBuilder(4096);
            char[] buffer = new char[2048];
            int length;
            while ((length = reader.read(buffer)) >= 0) text.append(buffer, 0, length);
            String source = text.toString();
            if (taps > 0) source = source.replace("TAP_COUNT", Integer.toString(taps));
            return RuntimeEffect.makeForShader(source);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load liquid-glass shader " + resource, error);
        }
    }

    private static void closeBuilder(RuntimeEffectBuilder builder) {
        builder.close();
        builder._uniforms.close();
    }

    private void closeSurfaces() {
        if (outerShadowImage != null) { outerShadowImage.close(); outerShadowImage = null; }
        if (outerShadowSurface != null) { outerShadowSurface.close(); outerShadowSurface = null; }
        if (outerShadowMask != null) { outerShadowMask.close(); outerShadowMask = null; }
        outerShadowOffset = Float.NaN;
        if (low != null) { low.close(); low = null; }
        if (high != null) { high.close(); high = null; }
        if (raw != null) { raw.close(); raw = null; }
        if (combined != null) { combined.close(); combined = null; }
        if (shadow != null) { shadow.close(); shadow = null; }
    }

    /** Invoke before abandoning the old GL context, while it is still current. */
    public void invalidateGpuContext() {
        // Builders retain children. Release them too, not just the Java snapshot handles.
        closeSurfaces();
        if (vibrancy != null) { vibrancy.close(); vibrancy = null; }
        if (lens != null) { lens.close(); lens = null; }
        if (bloom != null) { bloom.close(); bloom = null; }
        if (interactive != null) { interactive.close(); interactive = null; }
        if (downsample != null) { downsample.close(); downsample = null; }
        for (int i = 0; i < blurEffects.length; i++) {
            if (blurEffects[i] != null) { blurEffects[i].close(); blurEffects[i] = null; }
        }
        if (outerShadow != null) { outerShadow.close(); outerShadow = null; }
        if (innerShadow != null) { innerShadow.close(); innerShadow = null; }
        innerShadowRadius = -1;
        if (paint != null) { paint.close(); paint = null; }
        if (overlayPaint != null) { overlayPaint.close(); overlayPaint = null; }
        if (additivePaint != null) { additivePaint.close(); additivePaint = null; }
        if (filterPaint != null) { filterPaint.close(); filterPaint = null; }
    }

    @Override
    protected void releaseResources() {
        if (disposed) return;
        disposed = true;
        invalidateGpuContext();
        super.releaseResources();
    }

    private static float clamp(float x, float low, float high) {
        return Math.max(low, Math.min(high, x));
    }

    /** Compose EaseOut is CubicBezierEasing(0,0,0.58,1). Solve its x parameter. */
    private static float panelOffset(float drag, float width) {
        float f = clamp(drag / Math.max(1, width), -1, 1);
        float x = Math.abs(f), lo = 0, hi = 1;
        for (int i = 0; i < 16; i++) {
            float t = (lo + hi) * 0.5f;
            float bx = 3 * (1 - t) * t * t * 0.58f + t * t * t;
            if (bx < x) lo = t; else hi = t;
        }
        float t = (lo + hi) * 0.5f;
        return Math.signum(f) * 4 * (3 * (1 - t) * t * t + t * t * t);
    }

    private static Matrix33 inverse(Matrix33 matrix) {
        float[] a = matrix._mat;
        float d = a[0] * (a[4] * a[8] - a[5] * a[7])
                - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
        if (Math.abs(d) < 1e-12f) throw new IllegalStateException("Singular liquid-glass canvas transform");
        return new Matrix33((a[4] * a[8] - a[5] * a[7]) / d, (a[2] * a[7] - a[1] * a[8]) / d,
                (a[1] * a[5] - a[2] * a[4]) / d, (a[5] * a[6] - a[3] * a[8]) / d,
                (a[0] * a[8] - a[2] * a[6]) / d, (a[2] * a[3] - a[0] * a[5]) / d,
                (a[3] * a[7] - a[4] * a[6]) / d, (a[1] * a[6] - a[0] * a[7]) / d,
                (a[0] * a[4] - a[1] * a[3]) / d);
    }
}
