package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.List;

/**
 * 每个镜头的网点 MG 层，1:1 移植自 folia-major {@code tempera/temperaBlocks.ts}。
 *
 * <p>它拥有入场/退场运动状态，把全部几何交给 {@link TemperaCompositions}。时间都是
 * 「本镜头自身时长的比例」，于是图形跟着歌词走，而不是在一个固定的几分之一秒里做完。
 * 这里没有任何东西响应音频：一次 seek 会重绘出完全相同的帧。
 */
public final class TemperaBlocks {
    private TemperaBlocks() {
    }

    /** 单个图块的入场参数。{@code delay}/{@code span} 是镜头时长的比例。 */
    public static final class BlockOptions {
        public float alpha = 1f;
        public float enterDX;
        public float enterDY;
        public float delayFraction;
        public float spanFraction = 0.45f;
        /** 落位后的慢速确定性浮动，取代了老的音频脉冲。 */
        public boolean drift;
        /** 从枢轴横向张开，用于网点密度的揭示。 */
        public boolean grow;

        public static BlockOptions of() {
            return new BlockOptions();
        }

        public BlockOptions alpha(float value) {
            this.alpha = value;
            return this;
        }

        public BlockOptions enterDX(float value) {
            this.enterDX = value;
            return this;
        }

        public BlockOptions enterDY(float value) {
            this.enterDY = value;
            return this;
        }

        public BlockOptions delay(float value) {
            this.delayFraction = value;
            return this;
        }

        public BlockOptions span(float value) {
            this.spanFraction = value;
            return this;
        }

        public BlockOptions drift() {
            this.drift = true;
            return this;
        }

        public BlockOptions grow() {
            this.grow = true;
            return this;
        }
    }

    public static final class Options {
        public String kind;
        public TemperaTypes.DecorSpec decor;
        public TemperaPalette.Palette palette;
        public float width;
        public float height;
        public int seed;
        public boolean showDecor;
        /** 整个构图行进的方向；与相机和转场共用。 */
        public float flowAngle;
    }

    /** 构建结果：根容器 + 逐帧推进函数。 */
    public static final class View {
        public final TemperaDraw.Graphic container = new TemperaDraw.Graphic();
        final List<Item> items = new ArrayList<>();
        float flowX;
        float flowY;
        float carry;

        /**
         * {@code shotEnd} 是这个镜头不再显示的时刻；{@code lyricEnd} 是它最后一个字素
         * 停止演唱的时刻。两者不同，因为镜头末尾会被平铺到下一个镜头的起点。错峰按歌词
         * 配速（图形与词一起落位），而爬行则跑满整个可见寿命（带长器乐尾巴的镜头不会冻住）。
         */
        public void updateTime(double time, double shotStart, double shotEnd, double lyricEnd) {
            double duration = Math.max(shotEnd - shotStart, 0.2);
            double paceDuration = Math.max(lyricEnd - shotStart, 0.2);
            float progress = TemperaMotionEasing.clamp01((time - shotStart) / duration);
            // 整个镜头期间沿流动矢量匀速前爬；相机骑在同一条轴上，于是下一个构图到来时
            // 画面已经在动了。
            float creep = TemperaMotionEasing.easeInOut(progress) * carry * 0.35f;
            double budget = Math.max(0.5, paceDuration);

            for (Item item : items) {
                double rawDelay = TemperaMotionEasing.resolveShotPacedDuration(
                        paceDuration, item.delayFraction, 0, 1.4);
                double rawSpan = TemperaMotionEasing.resolveShotPacedDuration(
                        paceDuration, item.spanFraction, 0.7, 2.6);
                // 短镜头压缩整段错峰，而不是丢掉迟到的元素。
                double compress = Math.min(1, budget / (rawDelay + rawSpan));
                float enter = TemperaMotionEasing.easeEnter(
                        (time - shotStart - rawDelay * compress) / (rawSpan * compress));
                item.node.alpha = item.baseAlpha * enter;
                item.node.visible = enter > 0.001f;
                float behind = (1 - enter) * carry - creep;
                item.node.x = (float) (item.baseX + item.enterDX * (1 - enter) - flowX * behind);
                item.node.y = (float) (item.baseY + item.enterDY * (1 - enter) - flowY * behind);
                if (item.drift || item.grow) {
                    // 慢速浮动取代了老的音频脉冲：确定性、seek 安全，并且让装饰在镜头落位后
                    // 不至于僵住。
                    float driftFloat = item.drift
                            ? (float) (1 + Math.sin(time * 0.5 + item.driftPhase) * 0.02) : 1f;
                    float scale = item.grow ? Math.max(0.0001f, enter) * driftFloat : driftFloat;
                    item.node.scaleX = scale;
                    item.node.scaleY = driftFloat;
                    if (item.drift) {
                        item.node.rotation = (float) (Math.sin(time * 0.33 + item.driftPhase) * 0.012);
                    }
                }
            }
        }
    }

    private static final class Item {
        TemperaDraw.Graphic node;
        float baseX;
        float baseY;
        float baseAlpha;
        float enterDX;
        float enterDY;
        float delayFraction;
        float spanFraction;
        boolean drift;
        float driftPhase;
        boolean grow;
    }

    public static View build(Options options) {
        final View view = new View();
        view.flowX = (float) Math.cos(options.flowAngle);
        view.flowY = (float) Math.sin(options.flowAngle);
        // 逐元素的错峰距离只占一部分；交接位移的主体属于镜头容器，它带着字和图形一起走。
        view.carry = Math.max(options.width, options.height) * 0.09f;

        final TemperaCompositionContext context = new TemperaCompositionContext() {
            @Override
            public void add(TemperaDraw.Graphic node, BlockOptions blockOptions,
                            TemperaDraw.Graphic parent) {
                BlockOptions effective = blockOptions == null ? new BlockOptions() : blockOptions;
                Item item = new Item();
                item.node = node;
                item.baseX = node.x;
                item.baseY = node.y;
                item.baseAlpha = effective.alpha;
                item.enterDX = effective.enterDX;
                item.enterDY = effective.enterDY;
                item.delayFraction = effective.delayFraction;
                item.spanFraction = effective.spanFraction;
                item.drift = effective.drift;
                item.driftPhase = (float) (TemperaRandom.hash01(options.seed, view.items.size(), 173)
                        * Math.PI * 2);
                item.grow = effective.grow;
                view.items.add(item);
                TemperaDraw.Graphic target = parent != null ? parent : view.container;
                target.addGraphic(node);
            }

            @Override
            public TemperaDraw.Graphic createGroup(float rotation, float x, float y) {
                TemperaDraw.Graphic group = new TemperaDraw.Graphic();
                group.rotation = rotation;
                group.x = x;
                group.y = y;
                group.parent = view.container;
                view.container.addGraphic(group);
                return group;
            }
        };

        context.kind = options.kind;
        context.palette = options.palette;
        context.decor = options.decor;
        context.width = options.width;
        context.height = options.height;
        context.seed = options.seed;
        context.showDecor = options.showDecor;
        context.flowAngle = options.flowAngle;
        context.bleed = view.carry + Math.max(options.width, options.height) * 0.08f;
        // 只在 gradient 模式下：每个镜头一条自己的轴，相邻构图不会用同一种方式拉封面颜色。
        context.gradient = options.palette.gradient != null
                ? new TemperaDraw.Gradient(options.palette.gradient,
                        (float) (TemperaRandom.hash01(options.seed, 3, 197) * Math.PI * 2))
                : null;
        TemperaCompositions.drawTemperaComposition(context);

        return view;
    }
}
