package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedRow;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.ShapedText;
import dev.t1m3.qplayer.lyric.skia.LyricTextShaper.WordSpan;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.impl.Managed;
import io.github.humbleui.skija.impl.Native;
import io.github.humbleui.skija.impl.RefCnt;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** Owns reusable Skia resources and effects used to paint shaped lyric rows. */
final class LyricRowRenderer implements AutoCloseable {

    // 注：逐字上浮的"扫过感"用一条 0.55→1.0 的坡度实现 ✓ 不按毫秒错位 ✓
    // （那个函数里拿不到 spring / 时间 ✓ 重算会和主弹簧不一致 ✗）
    private static final float LIFT_PEAK_PX = 2.0f;
    private static final long LIFT_MIN_DURATION_MS = 1000L;
    private static final float SWEEP_FADE_PX = 25.6f;
    private static final float DARK_MASK_ALPHA = 0.36f;
    private static final double LIFT_OMEGA0 = 3.7416574;
    private static final double LIFT_ZETA = 0.935414;
    // 辉光门控与强度照搬 Melodify（qplayerWordGlow）：GLOW_ALPHA 0.7、词时长 ≥1000ms
    // （无条件，不再依赖 dropShadow）、纯 ASCII 可打印且长度 >7 的词才发光。
    private static final float GLOW_ALPHA = 0.7f;
    private static final long WORD_GLOW_MIN_DURATION_MS = 1000L;
    private static final float WORD_RIBBON_LIFT_PX = 2f;
    /** 逐字放大额外需要的栅格留白（px）：鼓包放大 1.12× 时字会越过自身盒子。 */
    private static final float ZOOM_RASTER_PAD = 8f;
    private static final float MAX_SHADER_LIFT_PX = LIFT_PEAK_PX + WORD_RIBBON_LIFT_PX;
    private static final float TEXT_SHADOW_OFFSET_Y = 2f;
    private static final float TEXT_SHADOW_ALPHA = 0.48f;
    private static final int MAX_LIFT_SEGMENTS = 128;
    private static final int MAX_WORD_LIFT_SEGMENTS = 32;
    private static final float TEXT_SUPERSAMPLE = 2f;
    private static final float TEXT_RASTER_PAD = 8f;
    private static final int MAX_HIGH_RES_ROWS = 12;
    private static final String LIFT_SHADER_RESOURCE = "/shaders/lyric/syllable_lift.sksl";

    private final Paint lyricLayerPaint = new Paint();
    private final Paint sweepPaint = new Paint();
    private final int[] sweepColors = new int[2];
    private final float[] sweepStops = new float[2];
    private final Rect sweepBigRect = Rect.makeLTRB(-100000f, -100000f, 100000f, 100000f);
    private final Paint glowGlyphPaint = newGlowGlyphPaint();
    private final Paint glowLayerPaint = newGlowLayerPaint();
    private final Paint textShadowPaint = newTextShadowPaint();
    private final float[] liftUniforms = new float[MAX_LIFT_SEGMENTS * 4];
    private final float[] wordLiftUniforms = new float[MAX_WORD_LIFT_SEGMENTS * 4];
    /** 逐字放大的附加偏移 (offX, offY, 0, 0)；Melo 的 cOffX / cOffY，与抬升分开。 */
    private final float[] zoomOffsetUniforms = new float[MAX_LIFT_SEGMENTS * 4];
    private final ArrayDeque<ShapedRow> highResolutionRows = new ArrayDeque<>();
    private float[] syllableLefts = new float[0];
    private float[] lifts = new float[0];
    private Shader sweepShader;
    private float sweepShaderDark = Float.NaN;
    private RuntimeEffect liftEffect;
    private RuntimeEffectBuilder liftBuilder;
    private boolean liftEffectUnavailable;

    void drawSubLine(ShapedText text, float leftX, float rightAnchorX, float y,
                     float alpha, boolean alignRight, boolean shadowOn) {
        if (text == null || text.blob == null) return;
        float x = alignRight ? rightAnchorX - text.width : leftX;
        if (shadowOn) drawTextShadow(text.blob, x, y, alpha);
        Paint paint = LyricSkia.scratchPaint();
        paint.setColor(0xFFD5DCF2);
        paint.setAlphaf(alpha);
        paint.setAntiAlias(true);
        LyricSkia.getCanvas().drawTextBlob(text.blob, x, y, paint);
    }

    void drawRow(List<Syllable> syllables, ShapedRow row,
                 float startX, float baselineY, float ascent, float descent, long positionMs,
                 float baseAlpha, float activeK, boolean animatePerToken, boolean spring,
                 boolean glowOn, boolean shadowOn, boolean wordGlowSupported) {
        if (row == null || row.blob == null || row.from >= row.to || row.width <= 0f) return;
        Canvas canvas = LyricSkia.getCanvas();
        if (activeK <= 0.001f || !animatePerToken) {
            drawStaticRow(canvas, row.blob, startX, baselineY, baseAlpha, shadowOn);
            return;
        }

        int count = row.to - row.from;
        if (syllableLefts.length < count + 1) syllableLefts = new float[count + 1];
        for (int i = 0; i <= count; i++) syllableLefts[i] = startX + row.syllableX[i];
        float rowRightX = startX + row.width;
        float sweepX = computeSweepX(syllables, row.from, row.to, syllableLefts, positionMs);

        if (lifts.length < count) lifts = new float[count];
        for (int i = 0; i < count; i++) {
            float k = syllableAnimation(syllables.get(row.from + i), positionMs, spring);
            lifts[i] = -LIFT_PEAK_PX * k * activeK;
        }

        lyricLayerPaint.setAlphaf(baseAlpha);
        canvas.saveLayer(startX - 8f,
                baselineY + ascent - MAX_SHADER_LIFT_PX - 8f,
                rowRightX + 8f, baselineY + descent + 8f, lyricLayerPaint);
        try {
            if (liftEffectUnavailable) {
                drawStaticRow(canvas, row.blob, startX, baselineY, 1f, shadowOn);
                applySweepMask(canvas, sweepX, 1f - (1f - DARK_MASK_ALPHA) * activeK);
                return;
            }
            ensureHighResolutionRaster(row, ascent, descent, shadowOn);
            long liftedShader = makeLiftShader(row, startX, baselineY, rowRightX, count,
                    syllables, positionMs, sweepX, activeK, glowOn, shadowOn,
                    wordGlowSupported);
            if (liftedShader == 0L) {
                drawStaticRow(canvas, row.blob, startX, baselineY, 1f, shadowOn);
                applySweepMask(canvas, sweepX, 1f - (1f - DARK_MASK_ALPHA) * activeK);
                return;
            }
            try {
                // Melodify: bloom 先画(文字底下,additive),再画文字盖在上面。
                drawWordGlows(canvas, syllables, row, startX, baselineY, ascent, descent,
                        positionMs, activeK, glowOn, shadowOn, wordGlowSupported,
                        liftedShader, sweepX);
                Paint textPaint = LyricSkia.scratchPaint();
                setShader(textPaint, liftedShader);
                try {
                    textPaint.setAlphaf(1f);
                    textPaint.setAntiAlias(true);
                    drawRect(canvas, startX + row.rasterLeft,
                            baselineY + row.rasterTop - MAX_SHADER_LIFT_PX,
                            startX + row.rasterLeft + row.rasterWidth,
                            baselineY + row.rasterTop + row.rasterHeight, textPaint);
                } finally {
                    setShader(textPaint, 0L);
                }
                applySweepMask(canvas, sweepX, 1f - (1f - DARK_MASK_ALPHA) * activeK);
            } finally {
                Managed._nInvokeFinalizer(RefCnt._FinalizerHolder.PTR, liftedShader);
            }
        } finally {
            canvas.restore();
        }
    }

    void clearRasterCache() {
        highResolutionRows.clear();
    }

    /** Close every cached glyph raster and glow image. The rows re-raster on demand, so
     *  this is what the page calls when it stops being drawn: the rasters are ~1 MB each
     *  at 2x supersampling and the list used to keep up to {@link #MAX_HIGH_RES_ROWS} of
     *  them alive for the life of the process. */
    void releaseRasterCache() {
        for (ShapedRow row : highResolutionRows) {
            if (row != null) row.closeRaster();
        }
        highResolutionRows.clear();
    }

    private void drawStaticRow(Canvas canvas, TextBlob blob, float x, float baselineY,
                               float alpha, boolean shadowOn) {
        Paint paint = LyricSkia.scratchPaint();
        paint.setColor(0xFFFFFFFF);
        paint.setAlphaf(alpha);
        paint.setAntiAlias(true);
        if (shadowOn) drawTextShadow(blob, x, baselineY, alpha);
        canvas.drawTextBlob(blob, x, baselineY, paint);
    }

    private void ensureHighResolutionRaster(ShapedRow row, float ascent, float descent,
                                            boolean shadowOn) {
        if (row.highResImage != null && row.rasterWithShadow == shadowOn) {
            highResolutionRows.remove(row);
            highResolutionRows.addLast(row);
            return;
        }
        highResolutionRows.remove(row);
        row.closeRaster();
        // 留白要够"逐字放大"往外长的那一点：鼓包把字放大 1+0.1×幅度（≤1.12×）并上抬，
        // 词首/词尾的字因此会越过自身盒子一点点。原来的 TEXT_RASTER_PAD 是按抬升与阴影
        // 定的，不够放大用 —— 现象就是词左右两边偶尔被裁掉一两个像素。
        float pad = TEXT_RASTER_PAD + ZOOM_RASTER_PAD;
        row.rasterLeft = -pad;
        row.rasterTop = ascent - pad;
        row.rasterWidth = Math.max(1f, row.width + pad * 2f);
        row.rasterHeight = Math.max(1f, descent - ascent + pad * 2f);
        int pixelWidth = Math.max(1, (int) Math.ceil(row.rasterWidth * TEXT_SUPERSAMPLE));
        int pixelHeight = Math.max(1, (int) Math.ceil(row.rasterHeight * TEXT_SUPERSAMPLE));
        try (Surface surface = Surface.makeRasterN32Premul(pixelWidth, pixelHeight)) {
            Canvas raster = surface.getCanvas();
            raster.clear(0x00000000);
            raster.scale(TEXT_SUPERSAMPLE, TEXT_SUPERSAMPLE);
            float x = -row.rasterLeft;
            float baseline = -row.rasterTop;
            if (shadowOn) {
                textShadowPaint.setAlphaf(TEXT_SHADOW_ALPHA);
                raster.drawTextBlob(row.blob, x, baseline + TEXT_SHADOW_OFFSET_Y, textShadowPaint);
            }
            Paint glyphPaint = LyricSkia.scratchPaint();
            glyphPaint.setColor(0xFFFFFFFF);
            glyphPaint.setAlphaf(1f);
            glyphPaint.setAntiAlias(true);
            raster.drawTextBlob(row.blob, x, baseline, glyphPaint);
            row.highResImage = surface.makeImageSnapshot();
        }
        row.highResImageShader = row.highResImage.makeShader(
                FilterTileMode.DECAL, FilterTileMode.DECAL,
                SamplingMode.LINEAR, Matrix33.IDENTITY);
        row.rasterWithShadow = shadowOn;
        highResolutionRows.addLast(row);
        while (highResolutionRows.size() > MAX_HIGH_RES_ROWS) {
            ShapedRow evicted = highResolutionRows.removeFirst();
            if (evicted != row) evicted.closeRaster();
        }
    }

    /** 拉丁词判定（Melodify isLatinWord）：全 ASCII 可见字符才算，CJK/泰文等不鼓包。 */
    private static boolean isLatinWord(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private long makeLiftShader(ShapedRow row, float startX, float baselineY,
                                float rowRightX, int count, List<Syllable> syllables,
                                long positionMs, float sweepX, float activeK,
                                boolean glowOn, boolean shadowOn,
                                boolean wordGlowSupported) {
        int segmentCount = Math.min(count, MAX_LIFT_SEGMENTS);
        // 逐字放大（"放大"那一档 = Melodify 的 emphasis 鼓包）：**逐字符**切段。
        // 为什么必须逐字符：拉丁歌词里一个"音节"往往就是一整个词，按音节放大等于把整词
        // 一起拉宽 —— 观感像被拉扯的棉花糖。Melodify 就是每个字符各自鼓起（AMLL 错开）。
        // 字符宽度这里用"段内均分"近似（真实字宽要引入字体度量与逐帧调用，先不做）；
        // 均分只影响每个字符分到哪几列像素，不影响鼓包本身的形状与节奏。
        boolean zoomStyle = Integer.valueOf(1).equals(LyricConfig.instance.liftStyle.getValue());
        float zoomEm = LyricConfig.instance.lyricFontSize.getValue() * 0.5f;
        Arrays.fill(liftUniforms, 0f);
        Arrays.fill(zoomOffsetUniforms, 0f);
        int segCount = 0;
        for (int i = 0; i < segmentCount && segCount < MAX_LIFT_SEGMENTS; i++) {
            float spanX0 = i == 0 ? startX - 8f : startX + row.syllableX[i];
            float spanX1 = i == segmentCount - 1
                    ? rowRightX + 8f : startX + row.syllableX[i + 1];
            // 下标口径：`i` 是**行栅格内**的音节序号，而 `syllables` 是**整行**的列表；
            // 换行成两行的长句 row.from > 0 —— 直接用 syllables.get(i) 会取到别的音节
            // （文字与时间都不对，逐字放大会用错词的时长）。
            int lineIndex = row.from + i;
            Syllable syllable = syllables.get(Math.min(syllables.size() - 1, lineIndex));
            if (!zoomStyle || !isLatinWord(syllable.text)) {
                int offset = segCount * 4;
                liftUniforms[offset] = spanX0;
                liftUniforms[offset + 1] = spanX1;
                liftUniforms[offset + 2] = lifts[i];
                liftUniforms[offset + 3] = 1f;
                segCount++;
                continue;
            }
            int len = Math.max(1, syllable.text.length());
            // Melo 的 emphasizeAmount 用「整行里的词序号 / 整行的词数」，
            // 换行后"最后一个词 ×1.6"仍按整句算。
            float amt = LyricMotion.emphasisAmount(syllable.durationMs,
                    lineIndex, syllables.size());
            // 真实逐字 x（排版期量好）：直接取该音节名下每个字符的 [x_c, x_{c+1})。
            // 拿不到逐字几何时退回"段内均分"（老的行/别的整形路径）。
            int chars = row.glyphX != null ? row.glyphX.length - 1 : 0;
            int ci = 0;
            for (int c = 0; c < chars && segCount < MAX_LIFT_SEGMENTS; c++) {
                if (row.glyphOwner[c] != i) continue;
                float x0 = startX + row.glyphX[c];
                float x1 = startX + row.glyphX[c + 1];
                if (x1 <= x0) { ci++; continue; }
                float ek = LyricMotion.emphasisSwell(positionMs, syllable.startMs,
                        syllable.durationMs, ci, len);
                int offset = segCount * 4;
                // 只有整行的第一段才允许向前扩到那 8px 空白边距；拉丁词的首字若也往前扩，
                // 段里就会含进**前一个音节的笔画**，被一起缩放拖着走 —— 观感是词左边被切、
                // 字有点重（用户实测）。其余情况一律从该字真实左缘开始。
                liftUniforms[offset] = (i == 0 && ci == 0) ? spanX0 : x0;
                liftUniforms[offset + 1] = x1;
                // **上浮也逐字符 + 30 ms 错位**（2026-10-11 ✓ 用户：「触发逐字放大时
                // 那个单词逐字上浮不要再整个词一起上浮」✗ 选 (b) 方案 ✓）：
                // 原来这里给的是 `lifts[i]` ✗ —— 它按 **syllable** 算 ✓
                //   而英文里一个 syllable 常常就是一整个词 ✗ ⇒ 整词同时抬 ✓
                // 现在每个字符用**错开 30 ms 的时间点**重算一次 ✓
                //   ⇒ 同一词内：第一个字先抬 ✓ 后面的依次跟上 ✓ = "扫过去"的手感 ✓
                //   中文一个字一个 syllable ✓ ⇒ 行为与原来完全一致 ✓（不回归 ✗）
                // 逐字递增系数：该 syllable 的 lift × (0.55 → 1.0) ✓
                // ⇒ 同一个词里第一个字只抬 55% ✓ 最后一个字抬满 ✓ = "扫过去"✓
                //   不需要重算时间/弹簧 ✓ 不会引入新的状态 ✓（`spring` 不在这个作用域 ✓）
                //   GLYPH_STAGGER_MS 对应的"观感"就是这条 0.55→1.0 的坡度 ✓
                final float ramp = 0.55f + 0.45f * ((ci + 1f) / Math.max(1, len));
                final float charLift = lifts[i] * ramp;
                liftUniforms[offset + 2] = charLift;
                liftUniforms[offset + 3] = 1f + 0.1f * ek * amt;
                // Melo 的两个附加偏移，逐字一份，严格照搬：
                //   cOffX = -ek * 0.03 * amt * (wlen/2 - ci) * em   （朝词心收拢）
                //   cOffY = -ek * 0.025 * amt * em                   （上抬）
                zoomOffsetUniforms[offset] = -ek * 0.03f * amt * (len / 2f - ci) * zoomEm;
                zoomOffsetUniforms[offset + 1] = -ek * 0.025f * amt * zoomEm;
                segCount++;
                ci++;
            }
            if (ci == 0) {
                // 没拿到逐字几何：整段作为一个字符处理（保底，不至于丢掉这一段的鼓包）。
                float ek = LyricMotion.emphasisSwell(positionMs, syllable.startMs,
                        syllable.durationMs, 0, len);
                int offset = segCount * 4;
                liftUniforms[offset] = spanX0;
                liftUniforms[offset + 1] = spanX1;
                liftUniforms[offset + 2] = lifts[i];
                liftUniforms[offset + 3] = 1f + 0.1f * ek * amt;
                zoomOffsetUniforms[offset] = 0f;
                zoomOffsetUniforms[offset + 1] = -ek * 0.025f * amt * zoomEm;
                segCount++;
            }
        }
        Arrays.fill(wordLiftUniforms, 0f);
        int wordLiftCount = 0;
        if (wordGlowSupported && glowOn) {
            for (WordSpan word : row.words) {
                if (wordLiftCount >= MAX_WORD_LIFT_SEGMENTS) break;
                Syllable first = syllables.get(row.from + word.firstSyllable);
                Syllable last = syllables.get(row.from + word.lastSyllable);
                long end = last.startMs + Math.max(0L, last.durationMs);
                if (end - first.startMs < WORD_GLOW_MIN_DURATION_MS
                        || positionMs < first.startMs || positionMs > end) continue;
                int offset = wordLiftCount * 4;
                float wordX0 = startX + Math.min(word.x0, word.x1);
                float wordX1 = startX + Math.max(word.x0, word.x1);
                float progress = Math.max(0f, Math.min(1f,
                        (sweepX - wordX0) / Math.max(0.001f, wordX1 - wordX0)));
                // 逐词抬升 = "飘带"（跟发光一起出现的那条）。放大模式下整条关掉：
                // 那条路只做行级缩放（当前行 1.14×、其它行 0.97×，锚点行左缘/行中心，
                // 与 Melodify 的 emphasisScale 一致），两种风格互斥。
                boolean ribbonOn = !Integer.valueOf(1)
                        .equals(dev.t1m3.qplayer.lyric.skia.LyricConfig.instance.liftStyle.getValue());
                float amount = ribbonOn
                        ? -WORD_RIBBON_LIFT_PX * (float) Math.sin(Math.PI * progress) * activeK
                        : 0f;
                if (Math.abs(amount) <= 0.001f) continue;
                wordLiftUniforms[offset] = wordX0;
                wordLiftUniforms[offset + 1] = wordX1;
                wordLiftUniforms[offset + 2] = amount;
                wordLiftUniforms[offset + 3] = progress;
                wordLiftCount++;
            }
        }
        RuntimeEffectBuilder builder = liftBuilder();
        if (builder == null) return 0L;
        builder.setUniform("segments", liftUniforms);
        builder.setUniform("segmentCount", segCount);
        builder.setUniform("zoomOffsets", zoomOffsetUniforms);
        builder.setUniform("wordLifts", wordLiftUniforms);
        builder.setUniform("wordLiftCount", wordLiftCount);
        builder.setUniform("sourceOrigin", startX + row.rasterLeft, baselineY + row.rasterTop);
        builder.setUniform("sourceScale", TEXT_SUPERSAMPLE);
        // 等比缩放（逐字放大）的 y 锚点：整行的垂直中心。
        builder.setUniform("zoomCenterY",
                baselineY + row.rasterTop + row.rasterHeight * 0.5f);
        builder.setChild("content", row.highResImageShader);
        return RuntimeEffectBuilder._nMakeShader(Native.getPtr(builder), null);
    }

    private void drawWordGlows(Canvas canvas, List<Syllable> syllables, ShapedRow row,
                               float startX, float baselineY, float ascent, float descent,
                               long positionMs, float activeK, boolean glowOn, boolean shadowOn,
                               boolean wordGlowSupported, long liftedShader, float sweepX) {
        if (!wordGlowSupported || !glowOn || row.words.length == 0) return;
        boolean layerSaved = false;
        try {
            for (WordSpan word : row.words) {
                Syllable first = syllables.get(row.from + word.firstSyllable);
                Syllable last = syllables.get(row.from + word.lastSyllable);
                long wordEnd = last.startMs + Math.max(0L, last.durationMs);
                long wordDuration = wordEnd - first.startMs;
                // 门控（照搬 Melodify qplayerWordGlow）：词时长 ≥1000ms（无条件）+
                // 纯 ASCII 可打印、长度 >7 的词才发光（中文/短词/非可打印 ASCII 不亮）。
                if (wordDuration < WORD_GLOW_MIN_DURATION_MS) continue;
                StringBuilder gw = new StringBuilder();
                for (int k = word.firstSyllable; k <= word.lastSyllable; k++) {
                    gw.append(syllables.get(row.from + k).text);
                }
                String gwt = gw.toString();
                if (gwt.length() <= 7) continue;
                boolean gAscii = true;
                for (int ci = 0; ci < gwt.length(); ci++) {
                    char c = gwt.charAt(ci);
                    boolean ws = c >= 0x09 && c <= 0x0d;
                    if (!ws && (c < 0x20 || c > 0x7e)) { gAscii = false; break; }
                }
                if (!gAscii) continue;
                if (positionMs < first.startMs || positionMs > wordEnd) continue;
                float progress = (positionMs - first.startMs) / (float) Math.max(1L, wordDuration);
                float alpha = activeK * smoothstep(0f, 0.18f, progress)
                        * (1f - smoothstep(0.90f, 1f, progress)) * GLOW_ALPHA;
                if (alpha <= 0.01f) continue;
                if (!layerSaved) {
                    canvas.saveLayer(startX - 8f,
                            baselineY + ascent - MAX_SHADER_LIFT_PX - 8f,
                            startX + row.width + 8f, baselineY + descent + 8f, glowLayerPaint);
                    layerSaved = true;
                }
                float x0 = startX + Math.min(word.x0, word.x1) - 1f;
                float x1 = startX + Math.max(word.x0, word.x1) + 1f;
                // Melodify glowWindow: 正在被扫光头唱到的词发光。窗口半径按该词自身
                // 几何取（词半宽 + 半个平均音节宽），扫光头进入词内即点亮、唱完离开
                // 才熄灭，与词的时间包络同步滑动。旧实现半径固定为半个平均音节宽，
                // 扫光头必须恰好落在词中心附近才亮，导致整句只有零星的瞬间发光、
                // 平时几乎全灭；按词取半径后同一时刻仍只有扫光头下的词被点亮，词与
                // 词之间自然过渡，不会有"整行一起亮"或"平时根本不亮"的两极现象。
                float avgSylW = Math.max(1f, row.width / (float) Math.max(1, row.to - row.from));
                alpha *= glowWindow(sweepX, x0, x1, avgSylW);
                if (alpha <= 0.01f) continue;
                canvas.save();
                try {
                    clipRect(canvas, x0, baselineY + ascent - MAX_SHADER_LIFT_PX,
                            x1, baselineY + descent + 2f);
                    setShader(glowGlyphPaint, liftedShader);
                    glowGlyphPaint.setAlphaf(alpha);
                    drawRect(canvas, startX + row.rasterLeft,
                            baselineY + row.rasterTop - MAX_SHADER_LIFT_PX,
                            startX + row.rasterLeft + row.rasterWidth,
                            baselineY + row.rasterTop + row.rasterHeight, glowGlyphPaint);
                } finally {
                    setShader(glowGlyphPaint, 0L);
                    canvas.restore();
                }
            }
        } finally {
            if (layerSaved) canvas.restore();
        }
    }

    /** Melodify glowWindow: how much of the sweep head's glow falls on one word, from
     *  that word's own geometry (half its width + half an average syllable). The window
     *  slides with the sweep head, so the word lights as the head enters it and dies as
     *  the head leaves — the older fixed half-syllable radius only lit a word while the
     *  head sat on its centre, so a line flickered instead of flowing. */
    static float glowWindow(float sweepX, float x0, float x1, float averageSyllableWidth) {
        float center = (x0 + x1) * 0.5f;
        float radius = Math.max(1f, Math.abs(x1 - x0) * 0.5f
                + Math.max(1f, averageSyllableWidth) * 0.5f);
        float distance = Math.min(1f, Math.abs(center - sweepX) / radius);
        return 1f - distance * distance * (3f - 2f * distance);
    }

    private static float syllableAnimation(Syllable syllable, long positionMs, boolean spring) {
        long start = syllable.startMs;
        if (spring) return liftSpring((positionMs - start) / 1000.0);
        long duration = Math.max(LIFT_MIN_DURATION_MS, Math.max(0L, syllable.durationMs));
        float progress = positionMs <= start ? 0f : positionMs >= start + duration
                ? 1f : (positionMs - start) / (float) duration;
        return 1f - (1f - progress) * (1f - progress) * (1f - progress);
    }

    private static float computeSweepX(List<Syllable> syllables, int from, int to,
                                       float[] syllableLefts, long positionMs) {
        int count = to - from;
        long firstStart = syllables.get(from).startMs;
        Syllable last = syllables.get(to - 1);
        long lastEnd = last.startMs + Math.max(0L, last.durationMs);
        if (positionMs < firstStart) return syllableLefts[0] - SWEEP_FADE_PX * 2f;
        if (positionMs >= lastEnd) return syllableLefts[count] + SWEEP_FADE_PX * 2f;
        for (int i = 0; i < count; i++) {
            Syllable syllable = syllables.get(from + i);
            long start = syllable.startMs;
            long end = syllable.durationMs > 0L
                    ? start + syllable.durationMs
                    : ((i + 1 < count) ? syllables.get(from + i + 1).startMs : start);
            if (end <= start) end = start + 1L;
            if (positionMs < start) return syllableLefts[i];
            if (positionMs < end) {
                float progress = (positionMs - start) / (float) (end - start);
                return syllableLefts[i] + (syllableLefts[i + 1] - syllableLefts[i]) * progress;
            }
        }
        return syllableLefts[count] + SWEEP_FADE_PX * 2f;
    }

    private void applySweepMask(Canvas canvas, float sweepX, float maskDark) {
        if (sweepShader == null || sweepShaderDark != maskDark) {
            if (sweepShader != null) sweepShader.close();
            int dark = ((int) (maskDark * 255f) << 24) | 0x00D3DAF0;
            sweepColors[0] = 0xFFFFFFFF;
            sweepColors[1] = dark;
            sweepStops[0] = 0f;
            sweepStops[1] = 1f;
            sweepShader = Shader.makeLinearGradient(
                    0f, 0f, SWEEP_FADE_PX, 0f, sweepColors, sweepStops);
            sweepShaderDark = maskDark;
        }
        sweepPaint.setShader(sweepShader);
        sweepPaint.setBlendMode(BlendMode.DST_IN);
        canvas.save();
        canvas.translate(sweepX - SWEEP_FADE_PX * 0.5f, 0f);
        canvas.drawRect(sweepBigRect, sweepPaint);
        canvas.restore();
        sweepPaint.setShader(null);
    }

    private RuntimeEffectBuilder liftBuilder() {
        if (liftEffectUnavailable) return null;
        if (liftBuilder == null) {
            RuntimeEffect effect = liftEffect();
            if (effect == null) {
                liftEffectUnavailable = true;
                return null;
            }
            liftBuilder = new RuntimeEffectBuilder(effect);
        }
        return liftBuilder;
    }

    private RuntimeEffect liftEffect() {
        if (liftEffect == null && !liftEffectUnavailable) {
            liftEffect = compileShaderResource();
            if (liftEffect == null) liftEffectUnavailable = true;
        }
        return liftEffect;
    }

    private static RuntimeEffect compileShaderResource() {
        return compileShaderResource(
                () -> LyricRowRenderer.class.getResourceAsStream(LIFT_SHADER_RESOURCE),
                RuntimeEffect::makeForShader);
    }

    static RuntimeEffect compileShaderResource(ShaderSource source,
                                               ShaderCompiler compiler) {
        try (InputStream input = source.open()) {
            if (input == null) throw new IOException("resource not found: " + LIFT_SHADER_RESOURCE);
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            StringBuilder text = new StringBuilder(4096);
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) text.append(buffer, 0, read);
            return compiler.compile(text.toString());
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            dev.t1m3.qplayer.util.Logger.warn(
                    "lyric lift shader unavailable; continuing without lift effect: {}",
                    failure.getMessage());
            return null;
        }
    }

    interface ShaderSource {
        InputStream open() throws Exception;
    }

    interface ShaderCompiler {
        RuntimeEffect compile(String source);
    }

    private void drawTextShadow(TextBlob blob, float x, float baselineY, float alpha) {
        textShadowPaint.setAlphaf(alpha * TEXT_SHADOW_ALPHA);
        LyricSkia.getCanvas().drawTextBlob(
                blob, x, baselineY + TEXT_SHADOW_OFFSET_Y, textShadowPaint);
    }

    private static float liftSpring(double elapsedSeconds) {
        if (elapsedSeconds <= 0.0) return 0f;
        double dampedFrequency = LIFT_ZETA * LIFT_OMEGA0;
        double frequency = LIFT_OMEGA0 * Math.sqrt(1.0 - LIFT_ZETA * LIFT_ZETA);
        double envelope = Math.exp(-dampedFrequency * elapsedSeconds);
        double value = 1.0 - envelope * (Math.cos(frequency * elapsedSeconds)
                + (dampedFrequency / frequency) * Math.sin(frequency * elapsedSeconds));
        return (float) Math.max(0.0, value);
    }

    private static float smoothstep(float minimum, float maximum, float value) {
        if (maximum <= minimum) return value < minimum ? 0f : 1f;
        float progress = (value - minimum) / (maximum - minimum);
        if (progress <= 0f) return 0f;
        if (progress >= 1f) return 1f;
        return progress * progress * (3f - 2f * progress);
    }

    private static void drawRect(Canvas canvas, float left, float top,
                                 float right, float bottom, Paint paint) {
        Canvas._nDrawRect(Native.getPtr(canvas), left, top, right, bottom, Native.getPtr(paint));
    }

    private static void clipRect(Canvas canvas, float left, float top,
                                 float right, float bottom) {
        Canvas._nClipRect(Native.getPtr(canvas), left, top, right, bottom,
                ClipMode.INTERSECT.ordinal(), false);
    }

    private static void setShader(Paint paint, long shader) {
        Paint._nSetShader(Native.getPtr(paint), shader);
    }

    private static Paint newGlowGlyphPaint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        // Melodify: additive(ONE,ONE) bloom 画在文字底下。
        paint.setBlendMode(BlendMode.PLUS);
        return paint;
    }

    private static Paint newGlowLayerPaint() {
        Paint paint = new Paint();
        paint.setImageFilter(ImageFilter.makeBlur(2.5f, 2.5f, FilterTileMode.CLAMP));
        return paint;
    }

    private static Paint newTextShadowPaint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(0xFF000000);
        paint.setMaskFilter(MaskFilter.makeBlur(FilterBlurMode.NORMAL, 2.2f));
        return paint;
    }

    @Override
    public void close() {
        if (sweepShader != null) {
            sweepShader.close();
            sweepShader = null;
        }
        if (liftBuilder != null) {
            liftBuilder.close();
            liftBuilder = null;
        }
        if (liftEffect != null) {
            liftEffect.close();
            liftEffect = null;
        }
        lyricLayerPaint.close();
        sweepPaint.close();
        glowGlyphPaint.close();
        glowLayerPaint.close();
        textShadowPaint.close();
    }
}
