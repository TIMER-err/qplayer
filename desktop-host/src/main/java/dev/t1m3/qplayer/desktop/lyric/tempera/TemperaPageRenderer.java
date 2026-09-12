package dev.t1m3.qplayer.desktop.lyric.tempera;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「凝彩」全屏页的运行时，1:1 移植自 folia-major {@code tempera/createTemperaPixiRuntime.ts}
 * 的 {@code renderFrame}（去掉 Pixi 生命周期与外部贴图）。
 *
 * <p>它拥有段落场景缓存，并按<b>绝对播放时间</b>直接改写有界的场景视图——所以一次 seek 会画出
 * 与连续播放完全相同的帧。缓存按「当前段 ± 1」保留，且每帧最多做一件昂贵的事（先回收上一轮
 * 交出的场景，再预构建邻居），这正是段落切换不掉帧的原因。
 *
 * <p>与 folia 的已知差异（其余逐字一致）：没有歌曲交接的两帧切歌动画（切歌直接清缓存重建）、
 * 没有 Pixi 的 GPU 后处理链（镜头畸变/颗粒/对比度/RGB 偏移需要采样背景的着色器）。暗角
 * （vignette）用一层径向渐变近似，这是后处理里唯一对「凝彩」观感贡献最大的一项。
 */
public final class TemperaPageRenderer {

    private TemperaTypes.Program program;
    private TemperaPalette.Theme theme;
    private TemperaTuning tuning = new TemperaTuning();
    private String[] coverColors = new String[0];
    private float lyricsFontScale = 1f;
    private boolean staticMode;

    private float width;
    private float height;
    private TemperaPalette.Palette palette;

    private final TreeMap<Integer, TemperaScene> sceneCache = new TreeMap<>();
    private final List<TemperaScene> retiredScenes = new ArrayList<>();
    private int activeParagraphIndex = -1;
    /** 用户插图素材池（<数据目录>/tempera/images/）；空池时凝彩照常跑，只是没插图。 */
    private final TemperaImagePool imagePool = new TemperaImagePool();

    private final Paint scratch = new Paint().setAntiAlias(true);
    private final Paint backdrop = new Paint().setAntiAlias(true);
    /** 当前帧的绝对播放秒数；暗角等随时间缓动的后处理读它。 */
    private double frameTime;

    /** 换歌：seed/歌词程序变了，整首歌的场景都要重建。 */
    public void setProgram(TemperaTypes.Program program, TemperaPalette.Theme theme,
                           String[] coverColors) {
        if (this.program == program && this.theme == theme
                && java.util.Arrays.equals(this.coverColors, coverColors)) {
            return;
        }
        this.program = program;
        this.theme = theme;
        this.coverColors = coverColors == null ? new String[0] : coverColors;
        this.palette = theme == null ? null
                : TemperaPalette.resolve(theme, tuning.colorMode, this.coverColors);
        clearScenes();
    }

    public void setTuning(TemperaTuning tuning) {
        if (tuning == null || tuning == this.tuning) return;
        boolean rebuild = (this.tuning.colorMode != null
                    && !this.tuning.colorMode.equals(tuning.colorMode))
                || this.tuning.textureResolution != tuning.textureResolution
                || this.tuning.glyphSettleStretch != tuning.glyphSettleStretch
                || this.tuning.showBlocks != tuning.showBlocks
                || this.tuning.showDecor != tuning.showDecor
                || this.tuning.textInversion != tuning.textInversion
                || this.tuning.enableTransitions != tuning.enableTransitions
                || this.tuning.wholeLineLyrics != tuning.wholeLineLyrics
                || this.tuning.layerImagesEnabled != tuning.layerImagesEnabled;
        this.tuning = tuning;
        if (theme != null) {
            this.palette = TemperaPalette.resolve(theme, tuning.colorMode, coverColors);
        }
        if (rebuild) clearScenes();
    }

    public void setLyricsFontScale(float scale) {
        if (scale > 0f && Math.abs(scale - lyricsFontScale) > 0.001f) {
            lyricsFontScale = scale;
            clearScenes();
        }
    }

    public void setStaticMode(boolean staticMode) {
        if (this.staticMode != staticMode) {
            this.staticMode = staticMode;
            clearScenes();
        }
    }

    /** 释放全部缓存的场景与原生绘制资源。 */
    public void dispose() {
        clearScenes();
        retiredScenes.forEach(TemperaScene::dispose);
        retiredScenes.clear();
        scratch.close();
        backdrop.close();
        TemperaTextView.dispose();
        TemperaMeasure.dispose();
        TemperaFonts.dispose();
        imagePool.dispose();
    }

    private void clearScenes() {
        for (TemperaScene scene : sceneCache.values()) {
            scene.dispose();
        }
        sceneCache.clear();
        activeParagraphIndex = -1;
    }

    private void drainRetiredScene() {
        if (retiredScenes.isEmpty()) return;
        TemperaScene scene = retiredScenes.remove(0);
        scene.dispose();
    }

    private void retireScenes() {
        for (TemperaScene scene : sceneCache.values()) retiredScenes.add(scene);
        sceneCache.clear();
        activeParagraphIndex = -1;
    }

    private void pruneScenes(int index) {
        List<Integer> drop = new ArrayList<>();
        for (int key : sceneCache.keySet()) {
            if (Math.abs(key - index) > 1) drop.add(key);
        }
        for (int key : drop) {
            TemperaScene scene = sceneCache.remove(key);
            if (scene != null) {
                scene.dispose();
            }
        }
    }

    private TemperaScene ensureScene(int index) {
        if (program == null || index < 0 || index >= program.paragraphs.size()) return null;
        TemperaScene cached = sceneCache.get(index);
        if (cached != null) return cached;
        TemperaScene scene = TemperaScene.build(program.seed, program.paragraphs.get(index),
                palette, tuning, lyricsFontScale, staticMode, width, height, imagePool);
        sceneCache.put(index, scene);
        return scene;
    }

    private float animationScale() {
        // qplayer 的主题没有 animationIntensity 三档，默认取中间的 1（与 folia 的 'normal' 一致）。
        return 1f;
    }

    /**
     * 画一帧。{@code time} 是相对本曲起点的绝对秒数；{@code w}/{@code h} 是逻辑像素尺寸，
     * {@code uiScale} 由画布尺寸换算。
     *
     * <p>纸色底<b>永远</b>先铺一层：没有歌词、还没唱到、已经唱完都还在画面里，绝不留黑屏。
     * 在那三段空档里再垫一层会缓慢流动的标题卡（见 {@link TemperaIdle}）；标题卡画在场景
     * <b>下面</b>，开场与第一镜交叉淡入淡出，结尾最后一镜淡出时又把它露出来。
     *
     * @param title  待机卡上的曲名
     * @param artist 待机卡上的歌手
     */
    public void render(Canvas canvas, double time, float uiScale, float w, float h,
                       String title, String artist) {
        if (palette == null || w < 1f || h < 1f) return;
        frameTime = time;
        if (Math.abs(w - width) > 0.5f || Math.abs(h - height) > 0.5f) {
            width = w;
            height = h;
            clearScenes();
        }

        int save = canvas.save();
        canvas.scale(uiScale, uiScale);
        // 满屏纸色底：构图自带纸色洗底，但关掉色块层或还没进场时也要有一层不透明底，
        // 否则会透出下面的 QML 场景（甚至是一块黑板）。
        backdrop.setColor(TemperaColor.withAlpha(palette.paper, 1f));
        backdrop.setAlphaf(1f);
        backdrop.setShader(null);
        canvas.drawRect(Rect.makeWH(w, h), backdrop);
        if (tuning.fluidBackdrop) {
            drawFluidBackdrop(canvas, w, h, time);
        }

        boolean hasProgram = program != null && !program.paragraphs.isEmpty();
        if (!hasProgram) {
            TemperaIdle.paint(canvas, palette, w, h, time, title, artist, false,
                    tuning.textInversion);
            canvas.restoreToCount(save);
            return;
        }

        TemperaTypes.Shot first = firstShot();
        float introAlpha = first == null ? 1f : TemperaMotionEasing.easeInOut(
                TemperaMotionEasing.clamp01((time - first.startTime) / shotHandoff(first)));
        if (showIdleCard(time)) {
            float cardAlpha = first != null && time < first.startTime + shotHandoff(first)
                    ? 1f - introAlpha : 1f;
            // Sparse scenes do not cover the whole title card. Fade it out too,
            // so its text cannot disappear abruptly at the end of the handoff.
            try (Paint fade = cardAlpha < 0.999f ? new Paint().setAlphaf(cardAlpha) : null) {
                int cardSave = fade == null ? canvas.save() : canvas.saveLayer(Rect.makeWH(w, h), fade);
                TemperaIdle.paint(canvas, palette, w, h, time, title, artist, true,
                        tuning.textInversion);
                canvas.restoreToCount(cardSave);
            }
        }

        int paragraphIndex = TemperaProgram.findTemperaParagraphIndexAtTime(program, time);
        if (paragraphIndex != activeParagraphIndex) {
            activeParagraphIndex = paragraphIndex;
            ensureScene(paragraphIndex);
            pruneScenes(paragraphIndex);
        } else {
            // 每帧只做一件昂贵的事，按优先级：先回收上一轮交出的场景，再预构建邻居。
            if (!retiredScenes.isEmpty()) {
                drainRetiredScene();
            } else {
                int next = paragraphIndex + 1;
                int previous = paragraphIndex - 1;
                if (next < program.paragraphs.size() && !sceneCache.containsKey(next)) {
                    ensureScene(next);
                } else if (previous >= 0 && !sceneCache.containsKey(previous)) {
                    ensureScene(previous);
                }
            }
        }

        boolean transitionsEnabled = tuning.enableTransitions && !staticMode;
        TemperaTypes.Transition outgoing = program.paragraphs.get(paragraphIndex).transitionOut;
        boolean preRoll = transitionsEnabled && outgoing != null
                && !"block-wipe".equals(outgoing.kind) && time >= outgoing.startTime;
        float motion = tuning.glyphMotion * animationScale();
        float wipeDrawn = 0f;

        for (Map.Entry<Integer, TemperaScene> entry : new ArrayList<>(sceneCache.entrySet())) {
            int index = entry.getKey();
            TemperaScene scene = entry.getValue();
            boolean isActive = index == paragraphIndex;
            boolean isIncoming = preRoll && index == paragraphIndex + 1;
            // The paragraph lookup returns index 0 even before the song's first
            // lyric. Keep its frozen opening pose off the animated title card,
            // then fade the whole scene in during the first shot's handoff.
            boolean visible = (isActive || isIncoming) && introAlpha > 0f;
            scene.visible = visible;
            // 到达的场景必须叠在它替换的那个之上；缓存的插入顺序说明不了段落顺序。
            scene.zIndex = index;
            if (!visible) {
                for (TemperaScene.ShotView shot : scene.shots) shot.visible = false;
                scene.activeShotIndex = -1;
                continue;
            }

            TemperaTypes.Transition previousTransition = index > 0
                    ? program.paragraphs.get(index - 1).transitionOut : null;
            double enterDuration = previousTransition != null
                    ? Math.max(0.35, Math.min(1,
                            previousTransition.endTime - previousTransition.startTime))
                    : 0;
            // 只有擦除仍在边界之后入场；其余在边界前就已经抵达，因为它们是穿过出场的窗口预滚的。
            boolean entering = transitionsEnabled && previousTransition != null
                    && "block-wipe".equals(previousTransition.kind)
                    && time >= scene.paragraph.startTime
                    && time <= scene.paragraph.startTime + enterDuration;
            TemperaTransitions.TemperaTransitionFrame transition;
            if (isIncoming && outgoing != null) {
                transition = TemperaTransitions.resolveTemperaEnterTransitionFrame(
                        outgoing.kind, time - outgoing.startTime,
                        Math.max(0.001, outgoing.endTime - outgoing.startTime), true, flowAngle(scene));
            } else if (entering && previousTransition != null) {
                transition = TemperaTransitions.resolveTemperaEnterTransitionFrame(
                        previousTransition.kind, time - scene.paragraph.startTime,
                        enterDuration, true, flowAngle(scene));
            } else {
                transition = TemperaTransitions.resolveTemperaExitTransitionFrame(
                        scene.paragraph, time, transitionsEnabled);
            }

            // 严格确定这个场景里唯一的活动镜头，避免场景内的残留。
            int activeShotIndex = 0;
            for (int i = scene.shots.size() - 1; i >= 0; i--) {
                if (time >= scene.shots.get(i).shot.startTime) {
                    activeShotIndex = i;
                    break;
                }
            }
            for (int i = 0; i < scene.shots.size(); i++) {
                TemperaScene.ShotView shot = scene.shots.get(i);
                // 离场的镜头在它的交接窗口里留在画面上，于是两幅构图恰好在一次推挤中重叠。
                boolean shotActive = i == activeShotIndex;
                boolean handingOff = i < activeShotIndex && resolveShotExit(shot, time) < 1f;
                shot.visible = shotActive || handingOff;
                // 字 / 水印 / 边角批注只属于当前正在唱的那个镜头；交接时退场镜头只让色块
                // 淡出，否则反色滤镜读到「上一句色块 + 这一句色块」的复合底色，字会算成中灰。
                shot.paintText = shotActive;
            }
            scene.updateShots(time, tuning, animationScale());
            scene.activeShotIndex = activeShotIndex;

            scene.scenePivotX = w / 2f;
            scene.scenePivotY = h / 2f;
            scene.sceneX = w / 2f + transition.x * w;
            scene.sceneY = h / 2f + transition.y * h;
            scene.sceneScale = transition.scale;
            scene.sceneRotation = transition.rotation;
            scene.sceneAlpha = transition.alpha * introAlpha;
            scene.paint(canvas, time, tuning, tuning.textInversion);

            if (transition.wipe > 0.001f && transition.wipe < 1.999f) {
                drawWipe(canvas, transition.wipe, transition.wipeAngle, w, h, palette.tone3);
                wipeDrawn = 1f;
            }
        }
        if (wipeDrawn == 0f) wipeCleanup();

        if (tuning.postProcessEnabled && tuning.postProcessVignette > 0.001f) {
            drawVignette(canvas, w, h, tuning.postProcessVignette);
        }
        canvas.restoreToCount(save);
    }

    private void wipeCleanup() {
        // 无状态：擦除块每帧从几何重建，所以「清掉」在这里就是什么都不做。
    }

    /**
     * 现在该不该垫待机卡：开场第一镜进场之前，以及最后一镜退场之后。
     *
     * <p>两端都用第一/最后一镜自己的 handoff 时长（与 {@link TemperaScene} 同一公式），于是
     * 「卡还在」与「场景已经盖住画面」的交接正好落在镜头滑动／淡出的那段时间里，两头都不会
     * 出现空帧。
     */
    private boolean showIdleCard(double time) {
        TemperaTypes.Shot first = firstShot();
        if (first != null && time < first.startTime + shotHandoff(first)) return true;
        TemperaTypes.Shot last = lastShot();
        return last != null && time > last.endTime;
    }

    private TemperaTypes.Shot firstShot() {
        for (TemperaTypes.Paragraph paragraph : program.paragraphs) {
            if (paragraph.shots != null && !paragraph.shots.isEmpty()) {
                return paragraph.shots.get(0);
            }
        }
        return null;
    }

    private TemperaTypes.Shot lastShot() {
        for (int index = program.paragraphs.size() - 1; index >= 0; index -= 1) {
            TemperaTypes.Paragraph paragraph = program.paragraphs.get(index);
            if (paragraph.shots != null && !paragraph.shots.isEmpty()) {
                return paragraph.shots.get(paragraph.shots.size() - 1);
            }
        }
        return null;
    }

    /** 一个镜头的交接时长；与 {@link TemperaScene#updateShots} 用同一条公式。 */
    private static float shotHandoff(TemperaTypes.Shot shot) {
        return (float) TemperaMotionEasing.resolveShotPacedDuration(
                shot.endTime - shot.startTime, 0.3, 0.4, 1.1);
    }

    private float flowAngle(TemperaScene scene) {
        if (scene.paragraph.shots == null || scene.paragraph.shots.isEmpty()) return 0f;
        return scene.paragraph.shots.get(0).flowAngle;
    }

    private float resolveShotHandoff(TemperaScene.ShotView view) {
        return (float) TemperaMotionEasing.resolveShotPacedDuration(
                view.shot.endTime - view.shot.startTime, 0.3, 0.4, 1.1);
    }

    private float resolveShotExit(TemperaScene.ShotView view, double time) {
        return TemperaMotionEasing.clamp01(
                (float) ((time - view.shot.endTime) / resolveShotHandoff(view)));
    }

    /** 沿 {@code angle} 滑过一块满屏的块；{@code travel} 跑 0..2，1 时恰好完全覆盖。 */
    private void drawWipe(Canvas canvas, float travel, float angle, float w, float h, String color) {
        if (travel <= 0.001f || travel >= 1.999f) return;
        // 在一个按屏幕对角线放大的旋转局部系里绘制，于是任何角度都满幅。
        float span = (float) Math.hypot(w, h);
        float notch = span * 0.08f;
        float length = span + notch * 2f;
        float start = -span / 2f - notch + (travel - 1f) * length;
        float end = start + length;
        float half = span / 2f;
        float[] polygon = {
                start, -half,
                end, -half,
                end + notch, 0f,
                end, half,
                start, half,
                start + notch, 0f,
        };
        int save = canvas.save();
        canvas.translate(w / 2f, h / 2f);
        canvas.rotate((float) Math.toDegrees(angle));
        PathBuilder builder = new PathBuilder();
        builder.addPolygon(polygon, true);
        Path path = builder.detach();
        builder.close();
        scratch.setShader(null);
        scratch.setColor(TemperaColor.withAlpha(color, 1f));
        scratch.setAlphaf(1f);
        canvas.drawPath(path, scratch);
        path.close();
        canvas.restoreToCount(save);
    }

    /** 暗角：中心透明、边缘压暗的一层径向渐变，是后处理里对观感贡献最大的一项。
     *  半径随时间做一次极缓的正弦呼吸（±3.5%），让画面像一直在「吸气」而不是钉死。 */
    private void drawVignette(Canvas canvas, float w, float h, float amount) {
        float strength = Math.min(1f, Math.max(0f, amount));
        float breath = 1f + 0.035f * (float) Math.sin(frameTime * 0.5);
        float radius = (float) Math.hypot(w, h) * 0.62f * breath;
        int edge = TemperaColor.withAlpha("#000000", strength);
        int[] colors = {0x00000000, 0x00000000, edge};
        float[] positions = {0f, 0.52f, 1f};
        Shader shader = null;
        try {
            shader = Shader.makeRadialGradient(w / 2f, h / 2f, radius, colors, positions);
            scratch.setShader(shader);
            scratch.setAlphaf(1f);
            canvas.drawRect(Rect.makeWH(w, h), scratch);
        } catch (Throwable ignored) {
            // 渐变构造失败时静默跳过暗角：它是装饰，不该让整帧失败。
        } finally {
            scratch.setShader(null);
            if (shader != null) shader.close();
        }
    }

    /**
     * 流体色斑底：三团大半径软渐变（取自构图的 blockA/B 与重音色），圆心沿互不同步的
     * 正弦慢漂。它替代了「一张死白纸 + 一堆格点」的观感——整张底始终在缓流，但 alpha 极
     * 低，绝不抢过构图与反色文字。绝对时间求值，所以 seek 与连续播放逐帧一致。
     */
    private void drawFluidBackdrop(Canvas canvas, float w, float h, double time) {
        if (palette == null) return;
        drawBlob(canvas, w, h, time, 0.013, 0.30, 0.34, palette.blockA, 0.22f, 0);
        drawBlob(canvas, w, h, time, 0.011, 0.72, 0.66, palette.blockB, 0.18f, 1);
        drawBlob(canvas, w, h, time, 0.009, 0.50, 0.50, palette.accent, 0.14f, 2);
    }

    /** 一团软色斑：圆心漂移 + 半径呼吸，从纯色渐隐到透明。 */
    private void drawBlob(Canvas canvas, float w, float h, double time,
                          double freq, double cx0, double cy0, String color,
                          float alpha, int phase) {
        double tau = time * Math.PI * 2.0 * freq;
        float cx = (float) (w * (cx0 + Math.sin(tau + phase) * 0.20));
        float cy = (float) (h * (cy0 + Math.cos(tau * 1.3 + phase * 1.7) * 0.18));
        float r = (float) (Math.max(w, h) * (0.44 + Math.sin(tau * 0.7 + phase) * 0.07));
        if (r < 1f) return;
        int c = TemperaColor.withAlpha(color, 1f);
        int[] colors = {c, 0x00000000};
        float[] positions = {0f, 1f};
        Shader shader = null;
        try {
            shader = Shader.makeRadialGradient(cx, cy, r, colors, positions);
            scratch.setShader(shader);
            scratch.setAlphaf(alpha);
            canvas.drawRect(Rect.makeWH(w, h), scratch);
        } catch (Throwable ignored) {
            // 渐变构造失败时静默跳过：它是底色装饰，不该让整帧失败。
        } finally {
            scratch.setShader(null);
            if (shader != null) shader.close();
        }
    }
}
