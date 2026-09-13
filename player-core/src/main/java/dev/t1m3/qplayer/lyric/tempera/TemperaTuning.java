package dev.t1m3.qplayer.lyric.tempera;

/**
 * 凝彩的调参，1:1 对应 folia-major 的 {@code TemperaTuning}（{@code types.ts} / {@code tuning.ts}）。
 *
 * <p>设置页提供整行模式、入场节奏和插图选项；页面通过顶栏按钮单独打开。
 */
public final class TemperaTuning {
    /** 相机幅度倍率。 */
    public float cameraIntensity = 1f;
    /** 逐字运动幅度倍率；0 会把字形钉在排版位置上。 */
    public float glyphMotion = 1f;
    /** 整行歌词作为一个镜头，而不是半个短语一切。 */
    public boolean wholeLineLyrics = false;
    /** 落位窗口相对行时长的拉伸，0.5 表示撑到行的一半。 */
    public float glyphSettleStretch = 0.5f;
    /** duo（主题双色）| mono（纸墨灰阶）| gradient（封面四色坡道）。 */
    public String colorMode = "duo";
    /** 是否绘制色块层。 */
    public boolean showBlocks = true;
    /** 流体色斑底：几团软渐变按互不同步的正弦慢漂，把死板的纸洗底变成流动底色。 */
    public boolean fluidBackdrop = true;
    /** 是否绘制网点装饰母题。 */
    public boolean showDecor = true;
    /** 歌词反色滤镜。 */
    public boolean textInversion = true;
    /** 图层图片（本移植暂不使用，保留字段保持与 folia 的默认一致）。 */
    public String[] layerImages = new String[0];
    public String layerImageDepth = "back";
    public float layerImageFrequency = 0.6f;
    /** qplayer 自己的开关：是否启用从 <数据目录>/tempera/images/ 读入的用户插图。 */
    public boolean layerImagesEnabled = false;
    /** 镜头之间的交接动画。 */
    public boolean enableTransitions = true;
    /** 内部渲染分辨率倍率。 */
    public float textureResolution = 1.5f;
    public boolean postProcessEnabled = true;
    public boolean postProcessTextureCompression = false;
    public float postProcessGrain = 0.2f;
    public float postProcessContrast = 0f;
    public float postProcessRgbShift = 0f;
    public float postProcessVignette = 0.85f;
    public float postProcessLensDistortion = 0.3f;

    public TemperaTuning copy() {
        TemperaTuning out = new TemperaTuning();
        out.cameraIntensity = cameraIntensity;
        out.glyphMotion = glyphMotion;
        out.wholeLineLyrics = wholeLineLyrics;
        out.glyphSettleStretch = glyphSettleStretch;
        out.colorMode = colorMode;
        out.showBlocks = showBlocks;
        out.fluidBackdrop = fluidBackdrop;
        out.showDecor = showDecor;
        out.textInversion = textInversion;
        out.layerImages = layerImages;
        out.layerImageDepth = layerImageDepth;
        out.layerImageFrequency = layerImageFrequency;
        out.layerImagesEnabled = layerImagesEnabled;
        out.enableTransitions = enableTransitions;
        out.textureResolution = textureResolution;
        out.postProcessEnabled = postProcessEnabled;
        out.postProcessTextureCompression = postProcessTextureCompression;
        out.postProcessGrain = postProcessGrain;
        out.postProcessContrast = postProcessContrast;
        out.postProcessRgbShift = postProcessRgbShift;
        out.postProcessVignette = postProcessVignette;
        out.postProcessLensDistortion = postProcessLensDistortion;
        return out;
    }
}
