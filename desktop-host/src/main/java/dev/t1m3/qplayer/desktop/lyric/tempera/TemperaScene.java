package dev.t1m3.qplayer.desktop.lyric.tempera;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个有界的段落场景，1:1 移植自 folia-major {@code tempera/temperaSceneBuilder.ts} 的
 * {@code buildTemperaScene} 部分（播放期只改字段，不再重建）。
 *
 * <p>这里也是「凝彩」反色语汇的核心：{@code textLayer} 只包含字形与重影，且用 DIFFERENCE
 * 混合绘制——任何它下面的东西（纸面、网目、构图、水印）都会成为它翻转的对象。水印刻意放在
 * 文字层<b>之下</b>，于是歌词横穿它的笔画时翻色：装饰成了文字所反应的画面的一部分，而不是
 * 第二行歌词。
 */
public final class TemperaScene {
    /** 段落场景里一个镜头：构图 + 文字 + 装饰，外加它自己的容器变换。 */
    public static final class ShotView {
        public TemperaTypes.Shot shot;
        public TemperaBlocks.View blocks;
        public List<TemperaTextView.View> glyphs = new ArrayList<>();
        public float baseX;
        public float baseY;
        /** 这个镜头布局用的基准字号；水印与散字的字号是它的倍数。 */
        public float baseFontSize;
        /** 逐字亮相结束的时刻；相机呼吸在此之后才渐入。 */
        public double revealDoneTime;
        public boolean visible;
        // 容器变换（每帧由 update 写入）。
        public float x;
        public float y;
        public float scale = 1f;
        public float rotation;
        public float alpha = 1f;
        /** 只有当前正在唱的那个镜头才画字/水印/边角批注；交接时退场的镜头只保留色块淡出。
         *  否则反色滤镜读到的是「上一句的色块 + 这一句的色块」的复合底色，字色会算成中灰。 */
        public boolean paintText;
        /** 本镜选中的用户插图（无则为 null）；移植自 folia 的图层图片。 */
        public ShotImage image;
    }

    /**
     * 一镜的用户插图状态。摆放参数在编译期（场景构建）按种子一次性算定，逐帧只更新
     * 入场透明度与沿流动矢量的爬行；原生贴图懒加载到绘制时才解码。
     */
    public static final class ShotImage {
        public String imageId;
        // 摆放（0..1 分数）。
        public float placementX;
        public float placementY;
        public float placementScale;
        public float placementRotation;
        public float placementOpacity;
        public boolean flip;
        public boolean front; // depth=="front" 画在文字之上，否则画在文字之下（被反色读）
        // 逐帧。
        public float x;
        public float y;
        public float alpha;
        public boolean visible;
        final float creepScale;
        ShotImage(float creepScale) { this.creepScale = creepScale; }
    }

    // 九宫格带状摆放：移植自 folia 的 ALIGN_BANDS / VERTICAL_ALIGN_BANDS。
    private static final float[] ALIGN_FROM = {0.14f, 0.40f, 0.68f, 0.12f}; // left/center/right/free
    private static final float[] ALIGN_TO   = {0.32f, 0.60f, 0.86f, 0.88f};
    private static final float[] VALIGN_FROM = {0.14f, 0.40f, 0.68f, 0.12f};
    private static final float[] VALIGN_TO   = {0.32f, 0.60f, 0.86f, 0.88f};

    private static int bandIndex(String align) {
        if (align == null) return 3;
        switch (align) {
            case "left": case "top": return 0;
            case "center": return 1;
            case "right": case "bottom": return 2;
            default: return 3; // free
        }
    }

    public void dispose() {
        for (ShotView shot : shots) {
            if (shot.blocks != null) shot.blocks.container.close();
        }
        shots.clear();
        baseGraphics.forEach(TemperaDraw.Graphic::close);
        baseGraphics.clear();
    }

    public TemperaTypes.Paragraph paragraph;
    public TemperaPalette.Palette palette;
    public final List<ShotView> shots = new ArrayList<>();
    /** 每段构建一次、播放期不再触碰的静态层（纸面 + 网目点阵）。 */
    public final List<TemperaDraw.Graphic> baseGraphics = new ArrayList<>();
    public int activeShotIndex = -1;
    /** 这一帧是否上屏（由运行时按段落时间决定）。 */
    public boolean visible;
    /** 段落顺序作为层叠序；缓存插入顺序说明不了段落顺序。 */
    public int zIndex;
    public final float width;
    public final float height;
    /** 每个字形拖几个残影；静态模式或关闭装饰时为 0。 */
    public int echoCount;
    /** 段落场景自身（整段）的容器变换，由运行时写入。 */
    public float sceneX;
    public float sceneY;
    public float sceneScale = 1f;
    public float sceneRotation;
    public float sceneAlpha = 1f;
    public float scenePivotX;
    public float scenePivotY;
    /** 段落过渡模糊强度（0 = 不挂）。 */
    public float transitionBlur;
    /** 本段场景用的插图池（绘制时按 id 解码原生贴图）；由 build 写入。 */
    private TemperaImagePool imagePool;

    private TemperaScene(float width, float height) {
        this.width = width;
        this.height = height;
    }

    private static final int FONT_WEIGHT = 600;
    private static final Paint LAYER_PAINT = new Paint();

    /**
     * 组装一个段落场景。这是整条运行时里最贵的一次调用：它对每个字素跑一遍排版适配循环，
     * 再为每个字形建立视图。每帧最多跑一次。
     *
     * <p>与 folia 的已知差异：本移植没有「用户放置的图层图片」（{@code layerImages}，需要一套
     * 放置器 UI）与主题关键字着色（{@code theme.wordColors}，qplayer 的主题没有这项配置），
     * 故 {@code segmentColors} 传 null、imageLayer 整体省略。其余算法与常量逐字一致。
     */
    public static TemperaScene build(String programSeed, TemperaTypes.Paragraph paragraph,
                                     TemperaPalette.Palette palette,
                                     TemperaTuning tuning, float lyricsFontScale,
                                     boolean staticMode, float width, float height,
                                     TemperaImagePool imagePool) {
        TemperaScene scene = new TemperaScene(width, height);
        scene.paragraph = paragraph;
        scene.palette = palette;
        scene.imagePool = imagePool;
        scene.echoCount = tuning.showDecor && !staticMode ? 2 : 0;
        int sceneSeed = TemperaRandom.hashSeed(programSeed + ":" + paragraph.id);

        // 半透明的纸色洗底把色块与壳体背景统一，上面那层点阵给整幅画面印刷纸的颗粒。
        // 两者每段构建一次，播放期不再触碰。
        if (tuning.showBlocks) {
            scene.baseGraphics.add(TemperaShapes.drawPolygonFill(
                    TemperaHatch.rectPolygon(0f, 0f, width, height), palette.paper, 0.35f));
            // 间距随视口增长，于是任何显示上点阵都维持在 3k 点左右。
            float toneSpacing = Math.max(26f, (float) Math.sqrt((width * height) / 6000.0));
            scene.baseGraphics.add(TemperaShapes.drawSquareMarks(
                    TemperaHatch.buildDotGrid(width, height, toneSpacing, 1.6f), palette.tone4, 0.05f));
        }

        // Tempera 刻意没有辉光层：叠加的光晕会把字洗向白色，并且无论它落在哪都会成为反色
        // 滤镜必须读的背景。
        //
        // 反色不是后处理 pass：它是这个模式给字上色的方式，所以它有自己独立的 textInversion
        // 开关（默认开），而不是搭在 postProcessEnabled 上。

        int shotCount = paragraph.shots.size();
        List<TemperaTypes.LayerImage> imagePoolList =
                (imagePool != null && tuning.layerImagesEnabled) ? imagePool.list() : new ArrayList<>();
        String previousImageId = null;
        for (int shotIndex = 0; shotIndex < shotCount; shotIndex++) {
            TemperaTypes.Shot shot = paragraph.shots.get(shotIndex);
            ShotView view = new ShotView();
            view.shot = shot;
            int shotSeed = sceneSeed + shotIndex * 97;

            // 一个镜头通常显示一个半句切片；整行模式则刻意用一部分字级来换取整行合唱。
            List<List<TemperaTypes.Segment>> linesSegments = new ArrayList<>();
            for (TemperaTypes.ShotSlice slice : shot.slices) {
                TemperaTypes.CompiledLine line = null;
                for (TemperaTypes.CompiledLine candidate : paragraph.lines) {
                    if (candidate.sourceIndex == slice.lineIndex) {
                        line = candidate;
                        break;
                    }
                }
                if (line == null || line.segments == null) continue;
                int from = Math.max(0, Math.min(slice.segmentStart, line.segments.size()));
                int to = Math.max(from, Math.min(slice.segmentEnd, line.segments.size()));
                List<TemperaTypes.Segment> segments = new ArrayList<>();
                for (int index = from; index < to; index++) {
                    TemperaTypes.Segment segment = line.segments.get(index);
                    if (TemperaLayout.isLayoutSegment(segment)) segments.add(segment);
                }
                if (!segments.isEmpty()) linesSegments.add(segments);
            }
            if (linesSegments.isEmpty()) {
                // 桥段镜头没有切片；仍然建一个空场景，让它的构图能显示。
                view.blocks = buildBlocks(shot, shotIndex, sceneSeed, palette, tuning, width, height);
                if (!imagePoolList.isEmpty()) {
                    TemperaTypes.LayerImage chosen = pickLayerImage(
                            imagePoolList, tuning.layerImageFrequency, shotSeed, previousImageId);
                    if (chosen != null) {
                        view.image = buildShotImage(chosen, shotSeed, tuning.layerImageDepth);
                        previousImageId = chosen.id;
                    }
                }
                view.baseX = width / 2f;
                view.baseY = height / 2f;
                view.revealDoneTime = shot.startTime;
                view.visible = false;
                scene.shots.add(view);
                continue;
            }

            int maxGraphemes = 3;
            for (List<TemperaTypes.Segment> segments : linesSegments) {
                int total = 0;
                for (TemperaTypes.Segment segment : segments) {
                    total += segment.graphemes == null ? 0 : segment.graphemes.size();
                }
                maxGraphemes = Math.max(maxGraphemes, total);
            }
            float baseFontSize = (float) (Math.max(34f, Math.min(150f,
                    (width / Math.max(5f, maxGraphemes * 1.05f)) * 1.5f)) * lyricsFontScale);

            view.blocks = buildBlocks(shot, shotIndex, sceneSeed, palette, tuning, width, height);

            // 用户插图：开了开关且池里有图时，每镜按种子挑一张、避开上一张，按九宫格带状
            // 摆放。没有歌词的桥段镜头也允许插图——它本来就是纯构图，加张图更活。
            if (!imagePoolList.isEmpty()) {
                TemperaTypes.LayerImage chosen = pickLayerImage(
                        imagePoolList, tuning.layerImageFrequency, shotSeed, previousImageId);
                if (chosen != null) {
                    view.image = buildShotImage(chosen, shotSeed, tuning.layerImageDepth);
                    previousImageId = chosen.id;
                }
            }

            TemperaLayout.Options options = new TemperaLayout.Options();
            options.lines = linesSegments;
            options.shotKind = shot.kind;
            options.width = width;
            options.height = height;
            options.baseFontSize = baseFontSize;
            options.fontWeight = FONT_WEIGHT;
            options.seed = shotSeed;
            options.segmentColors = null; // qplayer 主题没有 wordColors，关键字色整体省略。
            options.settleStretch = tuning.glyphSettleStretch;
            List<TemperaLayout.GlyphPlacement> placements = TemperaLayout.resolve(options);
            view.glyphs = TemperaTextView.build(placements, palette, FONT_WEIGHT,
                    tuning.showDecor, tuning.showDecor && !staticMode ? 2 : 0);

            // 桥段镜头没有字要亮相，所以相机呼吸可以立刻开始——一段器乐空档不该撑着一个僵硬的画面。
            double revealDone = shot.startTime;
            for (TemperaTextView.View glyph : view.glyphs) {
                revealDone = Math.max(revealDone, glyph.motion.settleTime);
            }
            view.revealDoneTime = revealDone;

            view.baseX = width / 2f;
            view.baseY = height / 2f;
            view.baseFontSize = baseFontSize;
            view.x = view.baseX;
            view.y = view.baseY;
            view.visible = false;
            scene.shots.add(view);
        }
        return scene;
    }

    private static TemperaBlocks.View buildBlocks(TemperaTypes.Shot shot, int shotIndex, int sceneSeed,
                                                  TemperaPalette.Palette palette, TemperaTuning tuning,
                                                  float width, float height) {
        TemperaBlocks.Options options = new TemperaBlocks.Options();
        options.kind = shot.kind;
        options.decor = shot.decor;
        options.palette = palette;
        options.width = width;
        options.height = height;
        options.seed = sceneSeed + shotIndex * 97;
        options.showDecor = tuning.showDecor;
        options.flowAngle = shot.flowAngle;
        return TemperaBlocks.build(options);
    }

    /** 按种子挑一张插图，避开上一张；频率闸：命中 < frequency 才上画。移植自 folia。 */
    private static TemperaTypes.LayerImage pickLayerImage(
            List<TemperaTypes.LayerImage> pool, float frequency, int seed, String previousId) {
        if (pool.isEmpty()) return null;
        if (TemperaRandom.hash01(seed, 6, 277) >= Math.max(0.0, Math.min(1.0, frequency))) return null;
        int start = (int) (TemperaRandom.hash01(seed, 7, 281) * pool.size()) % pool.size();
        for (int offset = 0; offset < pool.size(); offset++) {
            TemperaTypes.LayerImage candidate = pool.get((start + offset) % pool.size());
            if (!candidate.id.equals(previousId)) return candidate;
        }
        return pool.get(start);
    }

    /** 把一张插图按种子摆进九宫格带状位置；移植自 folia 的 resolveTemperaImagePlacement。 */
    private static ShotImage buildShotImage(TemperaTypes.LayerImage image, int seed, String depth) {
        int h = bandIndex(image.align);
        int v = bandIndex(image.verticalAlign);
        float px = ALIGN_FROM[h] + (ALIGN_TO[h] - ALIGN_FROM[h])
                * (float) TemperaRandom.hash01(seed, 1, 251);
        float py = VALIGN_FROM[v] + (VALIGN_TO[v] - VALIGN_FROM[v])
                * (float) TemperaRandom.hash01(seed, 2, 257);
        float scale = image.scale * (0.9f + (float) TemperaRandom.hash01(seed, 3, 263) * 0.2f);
        float rotation = (float) (TemperaRandom.hash01(seed, 4, 269) - 0.5) * 0.08f;
        boolean flip = TemperaRandom.hash01(seed, 5, 271) > 0.5;
        float creepScale = 0.6f + (float) TemperaRandom.hash01(seed, 8, 283) * 0.8f;
        ShotImage out = new ShotImage(creepScale);
        out.imageId = image.id;
        out.placementX = px;
        out.placementY = py;
        out.placementScale = scale;
        out.placementRotation = rotation;
        out.placementOpacity = image.opacity;
        out.flip = flip;
        out.front = "front".equals(depth);
        return out;
    }

    /**
     * 推进这个段落里全部可见镜头的运动。时间与镜头自身的时间线对齐：{@code time} 是绝对播放
     * 秒数，{@code tuning} 决定相机与逐字幅度。
     */
    public void updateShots(double time, TemperaTuning tuning, float animationScale) {
        float camera = tuning.cameraIntensity * animationScale;
        float motion = tuning.glyphMotion * animationScale;
        for (ShotView view : shots) {
            if (!view.visible) continue;
            double duration = Math.max(view.shot.endTime - view.shot.startTime, 0.001);
            float rawProgress = (float) ((time - view.shot.startTime) / duration);
            TemperaCamera.Frame frame = TemperaCamera.resolve(
                    new TemperaCamera.Key(view.shot.camera.x, view.shot.camera.y,
                            view.shot.camera.zoom, view.shot.camera.rotation),
                    new TemperaCamera.Key(view.shot.cameraEnd.x, view.shot.cameraEnd.y,
                            view.shot.cameraEnd.zoom, view.shot.cameraEnd.rotation),
                    rawProgress);

            float breathWeight = TemperaCamera.breathWeight(
                    (float) time, (float) view.revealDoneTime, 1.2f);
            if (breathWeight > 0f) {
                float breathPhase = (float) (Integer.remainderUnsigned(
                        TemperaRandom.hashSeed(view.shot.id), 1024) / 1024.0 * Math.PI * 2);
                TemperaCamera.Frame breath = TemperaCamera.breath((float) time, breathPhase);
                frame.x += breath.x * breathWeight;
                frame.y += breath.y * breathWeight;
                frame.scale += breath.scale * breathWeight;
                frame.rotation += breath.rotation * breathWeight;
            }

            // 交接：镜头沿自己的流动矢量从上游抵达，结束后继续向下游出画。重叠期间两个镜头
            // 同时跑这一套，于是离场的构图是被 visibly 推走，而不是被切掉。
            float handoff = (float) TemperaMotionEasing.resolveShotPacedDuration(
                    view.shot.endTime - view.shot.startTime, 0.3, 0.4, 1.1);
            float span = Math.max(width, height);
            // 抵达刻意前置：字形在镜头自己的时间线上开始亮相，所以慢入场会露出画面外的字。
            float enter = TemperaMotionEasing.easeEnter(TemperaMotionEasing.clamp01(
                    (time - view.shot.startTime) / (handoff * 0.8)));
            float exit = TemperaMotionEasing.easeInOut(TemperaMotionEasing.clamp01(
                    (time - view.shot.endTime) / handoff));
            float travel = exit * span * 0.55f - (1f - enter) * span * 0.32f;
            view.x = (float) (view.baseX + frame.x * width * camera
                    + Math.cos(view.shot.flowAngle) * travel);
            view.y = (float) (view.baseY + frame.y * height * camera
                    + Math.sin(view.shot.flowAngle) * travel);
            // 进来时是不透明的：这是一次推入，不是溶解。只有退场才淡出。
            view.alpha = 1f - exit;
            view.scale = (1f + (frame.scale - 1f) * camera) * (1f - exit * 0.08f);
            view.rotation = frame.rotation * camera;

            // 逐行脉冲：歌词推进期间镜头随句势做一次极轻的推-拉，像呼吸。只在字还在唱时
            // 生效（lyricEndTime 之前），过了就让画面稳住，避免和退场淡出叠出抖动。幅度刻意
            // 压到 1.8%：看得见是「这帧在动」，看不出是「镜头在算数」。
            float lyricSpan = (float) Math.max(0.1, view.shot.lyricEndTime - view.shot.startTime);
            float lyricProgress = TemperaMotionEasing.clamp01(
                    (float) ((time - view.shot.startTime) / lyricSpan));
            if (lyricProgress > 0f && lyricProgress < 1f) {
                float pulse = (float) Math.sin(lyricProgress * Math.PI);
                view.scale *= 1f + 0.018f * pulse * tuning.cameraIntensity;
            }

            // 两个端点是有意的：图形的入场错峰对着镜头承载的歌词配速；而匀速爬行跑满整个
            // 可见寿命——它被平铺到下一个镜头的起点，可能长好几秒。
            view.blocks.updateTime(time, view.shot.startTime, view.shot.endTime, view.shot.lyricEndTime);

            // 插图骑同一条流动轴、同一种错峰入场，于是它属于这个镜头而不是浮在画面上。
            if (view.image != null) {
                ShotImage img = view.image;
                double imgDuration = Math.max(view.shot.endTime - view.shot.startTime, 0.2);
                double paceDuration = Math.max(view.shot.lyricEndTime - view.shot.startTime, 0.2);
                float progress = TemperaMotionEasing.clamp01(
                        (float) ((time - view.shot.startTime) / imgDuration));
                float carry = Math.max(width, height) * 0.09f;
                // 比色块慢一点，读成深度而不是整帧一齐滑。
                float creep = TemperaMotionEasing.easeInOut(progress) * carry * 0.35f * img.creepScale;
                float imgEnter = TemperaMotionEasing.easeEnter(
                        (float) ((time - view.shot.startTime - paceDuration * 0.1) / (paceDuration * 0.5)));
                img.alpha = img.placementOpacity * imgEnter;
                img.visible = imgEnter > 0.001f;
                float flowX = (float) Math.cos(view.shot.flowAngle);
                float flowY = (float) Math.sin(view.shot.flowAngle);
                img.x = img.placementX * width + flowX * creep;
                img.y = img.placementY * height + flowY * creep;
            }
        }
    }

    /** 把整个场景画到画布上；调用方负责已应用（或不应用）外层变换。 */
    public void paint(Canvas canvas, double time, TemperaTuning tuning, boolean inversion) {
        int save = canvas.save();
        canvas.translate(sceneX, sceneY);
        if (sceneRotation != 0f) canvas.rotate((float) Math.toDegrees(sceneRotation));
        if (sceneScale != 1f) canvas.scale(sceneScale, sceneScale);
        canvas.translate(-scenePivotX, -scenePivotY);
        int layer = -1;
        if (sceneAlpha < 0.999f) {
            LAYER_PAINT.setAlphaf(Math.max(0f, sceneAlpha));
            layer = canvas.saveLayer(Rect.makeLTRB(-width, -height, width * 2f, height * 2f), LAYER_PAINT);
        }

        for (TemperaDraw.Graphic graphic : baseGraphics) graphic.paint(canvas);
        float motion = tuning.glyphMotion;
        for (ShotView view : shots) {
            if (!view.visible || view.alpha <= 0.002f) continue;
            paintShot(canvas, view, time, tuning, motion, inversion);
        }

        if (layer >= 0) canvas.restoreToCount(layer);
        canvas.restoreToCount(save);
    }

    private void paintShot(Canvas canvas, ShotView view, double time, TemperaTuning tuning,
                           float motion, boolean inversion) {
        int save = canvas.save();
        canvas.translate(view.x, view.y);
        if (view.rotation != 0f) canvas.rotate((float) Math.toDegrees(view.rotation));
        if (view.scale != 1f) canvas.scale(view.scale, view.scale);
        canvas.translate(-view.baseX, -view.baseY);
        int layer = -1;
        if (view.alpha < 0.999f) {
            LAYER_PAINT.setAlphaf(view.alpha);
            layer = canvas.saveLayer(Rect.makeLTRB(-width, -height, width * 2f, height * 2f), LAYER_PAINT);
        }

        // 顺序即语义：所有反色滤镜应当读到的内容都必须先于文字层渲染——包括装饰水印，
        // 这正是它的意义：歌词横穿那些笔画时翻色。但任何字形形状的东西都不能放进去，否则
        // 每个字都会相对自己的重影反色、碎成斑块。
        if (tuning.showBlocks) view.blocks.container.paint(canvas);
        // back 插图在文字之下：反色滤镜会读它，字横穿图片时翻色——这正是 folia 的做法。
        // 但只在当前正在唱的镜头里画它；交接时退场镜头的插图也不要画，否则反色底会叠错。
        if (view.paintText) drawShotImage(canvas, view.image, false);
        // 字 / 水印 / 边角批注只属于当前正在唱的那个镜头：交接时退场镜头正在淡出，它的
        // 水印和字会让反色滤镜读到一份「上一句色块 + 这一句色块」的复合底色，字色会算成
        // 中灰——也就是你看到的「worldwide」读不清的根因。
        if (view.paintText && tuning.showDecor && view.shot.decor != null
                && view.shot.decor.watermark != null) {
            TemperaTextView.drawWatermark(canvas, view.shot.decor.watermark, palette,
                    FONT_WEIGHT, view.baseFontSize, width, height);
        }
        // 边角散字属于 textLayer，所以它们与普通字形一样参与反色——同样只在当前镜头画。
        if (view.paintText && tuning.showDecor && view.shot.decor != null
                && view.shot.decor.fragments != null) {
            for (TemperaTypes.DecorFragment fragment : view.shot.decor.fragments) {
                TemperaTextView.drawFragment(canvas, fragment, palette,
                        FONT_WEIGHT, view.baseFontSize, width, height, inversion);
            }
        }
        // 文字（影子 + 普通字形走反色；残影与关键字在其上、不反色）。
        if (view.paintText) {
            TemperaTextView.paint(canvas, view.glyphs, time, motion, echoCount,
                    tuning.showDecor, inversion, palette.paper);
        }
        // front 插图在文字之上：不参与反色，像贴在玻璃上的照片。退场时也不画，避免抢戏。
        if (view.paintText) drawShotImage(canvas, view.image, true);

        if (layer >= 0) canvas.restoreToCount(layer);
        canvas.restoreToCount(save);
    }

    /** 画一张插图；{@code frontPass} 为 true 时只画 depth=front 的，false 时只画 depth=back 的。 */
    private void drawShotImage(Canvas canvas, ShotImage img, boolean frontPass) {
        if (img == null || !img.visible || img.alpha <= 0.002f) return;
        if (img.front != frontPass) return;
        if (imagePool == null) return;
        io.github.humbleui.skija.Image texture = imagePool.imageFor(img.imageId);
        if (texture == null) return;
        int iw = texture.getWidth();
        int ih = texture.getHeight();
        if (iw <= 0 || ih <= 0) return;
        // 高度驱动缩放，保持比例：与视口高度成比例，所以任何显示上图都不变形。
        float uniform = height * img.placementScale / ih;
        int save = canvas.save();
        canvas.translate(img.x, img.y);
        if (img.placementRotation != 0f) canvas.rotate((float) Math.toDegrees(img.placementRotation));
        canvas.scale(img.flip ? -uniform : uniform, uniform);
        LAYER_PAINT.setAlphaf(Math.max(0f, Math.min(1f, img.alpha)));
        canvas.drawImage(texture, -iw / 2f, -ih / 2f, LAYER_PAINT);
        canvas.restoreToCount(save);
    }
}
