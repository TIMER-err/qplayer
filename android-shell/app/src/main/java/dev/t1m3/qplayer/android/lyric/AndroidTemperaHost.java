package dev.t1m3.qplayer.android.lyric;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.tempera.TemperaHostPage;
import dev.t1m3.qplayer.lyric.tempera.TemperaTuning;
import dev.t1m3.qplayer.settings.SettingsCore;

import io.github.humbleui.skija.Canvas;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;
import io.github.timer_err.qml4j.render.items.core.Item;

/**
 * 安卓端的「凝彩」宿主：把桌面 {@code TemperaHostPage} 的接入逻辑搬过来，挂在
 * {@code QmlGLSurfaceView} 的渲染循环上。
 *
 * <p>凝彩不是独立页面，它和标准歌词共用歌词页这一个入口（设置里 {@code temperaEnabled}
 * 决定歌词页由哪套渲染器出画）。本宿主在每一帧判断「凝彩」是否该出画：该出画时整屏由
 * 凝彩绘制（流体底色 + 构图 + 逐字歌词），滑入动画期间在下方露出标准 QML 场景；否则照旧走
 * 共享的 {@code LyricCompositor} 出标准歌词页。
 *
 * <p>QML 侧的控件子树（右下角胶囊）已经由 {@code TemperaLayer.qml} 以
 * {@code objectName="temperaChrome"} 提供，与桌面共用同一份 shared-qml，所以这里按名字查它
 * 后单独叠上去即可。
 */
public final class AndroidTemperaHost {

    private final TemperaHostPage page = new TemperaHostPage();

    /** 这一帧该不该由凝彩出画：开了凝彩，且歌词页正开着（或仍在关闭的滑出动画里）。 */
    public boolean wantsFrame(PlayerController controller, SettingsCore settings) {
        if (controller == null) return false;
        boolean enabled = settings != null && settings.bool("temperaEnabled");
        return page.wantsFrame(controller, enabled);
    }

    /** 从设置构造凝彩调参，1:1 对应桌面 {@code DesktopWindow.temperaTuning()}。 */
    private static TemperaTuning tuning(SettingsCore settings) {
        TemperaTuning next = new TemperaTuning();
        int stretch = settings == null ? 50 : settings.intOf("temperaGlyphSettleStretch");
        boolean wholeLine = settings != null && settings.bool("temperaWholeLine");
        boolean images = settings != null && settings.bool("temperaImages");
        boolean fluid = settings == null || settings.bool("temperaFluidBackdrop");
        int effects = settings == null ? 60 : settings.intOf("temperaEffects");
        next.glyphSettleStretch = Math.max(0f, Math.min(1f, stretch / 100f));
        next.wholeLineLyrics = wholeLine;
        next.layerImagesEnabled = images;
        next.fluidBackdrop = fluid;
        next.effectsIntensity = Math.max(0f, Math.min(1f, effects / 100f));
        return next;
    }

    /**
     * 画一帧「凝彩」。{@code width}/{@code height} 是设备像素，内部换算成逻辑像素。
     * 逻辑与桌面 {@code RenderThread.drawTemperaFrame} 一致。
     */
    public void drawFrame(Canvas canvas, Renderer renderer, QmlView view,
                          PlayerController controller, SettingsCore settings,
                          float uiScale, int width, int height) {
        if (controller == null) return;
        float lw = width / uiScale;
        float lh = height / uiScale;

        float fontScale = settings == null ? 1f : settings.intOf("lyricFontSize") / 28f;
        boolean staticMode = settings != null && settings.lyricBgStatic();
        page.configure(tuning(settings), fontScale, staticMode);

        float ease = page.slideEase();
        // 滑入动画期间，在下方露出标准 QML 场景（与桌面一致：只画 QML 主树，不画宿主歌词叠层）。
        if (ease < 0.999f) {
            int under = canvas.save();
            canvas.scale(uiScale, uiScale);
            renderer.render(canvas, view.root(), false);
            canvas.restoreToCount(under);
        }

        int save = canvas.save();
        canvas.translate(0f, (1f - ease) * lh * uiScale);
        boolean dark = settings == null || settings.resolvedDarkValue();
        page.render(canvas, controller, uiScale, lw, lh, System.nanoTime(), dark);
        canvas.restoreToCount(save);

        // 把凝彩页自己的 QML 控件子树（右下角胶囊）叠上去，按 objectName 查。
        Item chrome = view.findByObjectName("temperaChrome");
        if (chrome != null) {
            renderer.layoutOnly(chrome);
            int chromeSave = canvas.save();
            canvas.scale(uiScale, uiScale);
            renderer.renderSubtree(canvas, chrome, lw, lh);
            canvas.restoreToCount(chromeSave);
        }
    }

    /** 释放凝彩运行时持有的原生绘制资源（paint / 插图缓存）。 */
    public void dispose() {
        page.dispose();
    }
}
