package dev.t1m3.qplayer.lyric.tempera;

/**
 * 每个构图族共享的绘制契约，1:1 移植自 folia-major
 * {@code tempera/temperaCompositionContext.ts}。
 *
 * <p>构图只负责往 {@link TemperaBlocks} 里塞「已完成、静态」的图形；所有时间与运动状态
 * 都留在 {@link TemperaBlocks} 里。
 */
public abstract class TemperaCompositionContext {
    public String kind;
    public TemperaPalette.Palette palette;
    public TemperaTypes.DecorSpec decor;
    public float width;
    public float height;
    public int seed;
    public boolean showDecor;
    /**
     * 每个铺满画面的形状都必须多伸出视口这么多余量。构图在整镜头期间沿流动矢量移动，
     * 一个正好画到画框边缘的形状在滑动时会把背景露出来。
     */
    public float bleed;
    /**
     * 整个镜头行进的方向，与相机和交接共用。冲孔族会把它们的通道对齐到这个方向：
     * 一条平行于流动方向的槽在镜头滑动时仍然是槽，于是相邻两个镜头读起来是一条连续的
     * 走廊，而不是一次剪辑。
     */
    public float flowAngle;
    /** 只在 gradient 色彩模式下非空；调子填充会变成四色坡道。 */
    public TemperaDraw.Gradient gradient;

    /** 往当前镜头里加一个图形节点；{@code parent} 非空时挂进倾斜子组。 */
    public abstract void add(TemperaDraw.Graphic node,
                             TemperaBlocks.BlockOptions options,
                             TemperaDraw.Graphic parent);

    /** 建一个倾斜子组（海报类构图用），子节点保持在局部坐标里。 */
    public abstract TemperaDraw.Graphic createGroup(float rotation, float x, float y);

    public final void add(TemperaDraw.Graphic node) {
        add(node, null, null);
    }

    public final void add(TemperaDraw.Graphic node, TemperaBlocks.BlockOptions options) {
        add(node, options, null);
    }
}
