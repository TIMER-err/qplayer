package dev.t1m3.qplayer.desktop.lyric.tempera;

import java.util.ArrayList;
import java.util.List;

/**
 * 渲染器无关的公开契约，1:1 移植自 folia-major {@code tempera/types.ts}。
 *
 * <p>整条 Tempera 管线是「编译期确定性」的：所有随机性在编译阶段就被种子哈希烧进
 * {@link Shot}/{@link DecorSpec} 里，运行时只做插值与绘制，所以任意一次 seek 都会
 * 重绘出逐像素相同的帧。这里的全部时间字段与 folia 一致，单位是<b>秒</b>（不是毫秒），
 * 只在宿主边界处做一次 ms→s 换算，保证所有算法常量与 folia 逐字一致。
 */
public final class TemperaTypes {
    private TemperaTypes() {
    }

    /** 段落分类，只影响文案节奏与装饰密度。 */
    public static final String[] PARAGRAPH_KINDS = {
            "breath", "verse", "lift", "chorus", "break", "outro"
    };

    /** 段落切分依据，调试用。 */
    public static final String[] PARAGRAPH_BOUNDARIES = {
            "song-start", "time-gap", "metadata", "duration-cap", "line-cap"
    };

    /**
     * 凝彩能切到的全部镜头。镜头默认是「半句」级别的，所以这个列表必须足够长，
     * 长到一个段落里很少重复同一种；{@code temperaShotProfiles} 负责每种镜头的
     * 布局区域/相机/情绪，{@code temperaCompositions} 负责构图绘制。
     */
    public static final String[] SHOT_KINDS = {
            // 分割与网格
            "duo-split",
            "quad-split",
            "tri-column",
            "thirds-stack",
            "checker-quad",
            "corner-wedge",
            "diagonal-halves",
            "cross-axis",
            "offset-halves",
            "stair-blocks",
            "pillar-gap",
            "corner-quad",
            "sliver-stack",
            // 横带
            "band-strip",
            "horizon-band",
            "deep-dive",
            "tone-ramp",
            "double-band",
            "tilt-band",
            "edge-rails",
            "gradient-wall",
            "terrace",
            // 框与窗
            "frame-window",
            "double-frame",
            "circle-window",
            "ladder-frame",
            "corner-brackets",
            "inset-box",
            "bracket-pair",
            "arch-window",
            "grid-cells",
            "keyhole",
            // 海报与形状
            "poster-panel",
            "diamond-stack",
            "slash-poster",
            "arrow-wedge",
            "edge-bleed",
            "triangle-mass",
            "ribbon-cross",
            "half-disc",
            "stacked-slabs",
            "wedge-pair",
            // 稀疏场
            "quiet-line",
            "starfield-dots",
            "ripple-lines",
            "hair-grid",
            "margin-rule",
            "dot-drift",
            "arc-sweep",
            "blank-page",
            // 影院遮幅：一整块实心画框，中间按某个画幅挖出一个窗口
            "cinema-scope",
            "cinema-wide",
            "cinema-academy",
            "cinema-square",
            "cinema-portrait",
            "cinema-tall",
            "cinema-twin",
            // 圆润形状铺在平整场上，取视觉小说宣传 PV 的语汇
            "bubble-drift",
            "cloud-window",
            "heart-burst",
            "sparkle-field",
            "petal-arc",
            "scallop-band",
            "ribbon-loop",
            "round-plate",
            "halo-burst",
            // 冲孔板：开口是穿透色调的，所以壳体里活的背景会在孔里透出来
            "iris-hole",
            "slot-rail",
            "punch-row",
            "film-gate",
            "cross-vent",
            "louvre-slats",
            "ring-eye",
            "notch-stack",
            "wedge-gap",
            "dot-sieve",
            // 仪表盘：围绕一个沉重焦点做讲求测量的几何装饰
            "sight-mark",
            "dial-scale",
            "chevron-run",
            "tally-column",
            "grid-focus",
            "axis-caps",
            "strobe-slats",
            "offset-plate",
            "radial-comb",
            "bracket-target",
            // 走廊：开口沿流动矢量切出，两个镜头交接时同一个开口继续移动
            "flow-channel",
            "twin-channel",
            "reed-run",
            "taper-channel",
            "chain-ports",
            "dash-channel",
            "window-run",
            "bridge-span",
            "braid-channel",
            "port-ladder",
            // 粗野主义巨块：一块巨大的实心遮体被画框裁切，字压在它的边上
            "apex-mass",
            "ziggurat",
            "slab-wall",
            "cantilever",
            "pylon-pair",
            "bunker-slit",
            "plinth-stack",
            "buttress-run",
            "void-core",
            "shear-block",
            // 同一套语汇读作地形与结构，而不是读作物件
            "ridge-line",
            "chasm",
            "overhang",
            "step-well",
            "pier-row",
            "revetment",
            "tower-crop",
            "lintel",
            "rubble-fan",
            "gnomon",
            // 物语风格过场：一整片平场，字就是整张画面
            "monogatari-card",
            "monogatari-rule",
            "monogatari-edge",
            "monogatari-stack",
            "monogatari-flash",
    };

    /**
     * 每一次转场都由大图形或相机领走；没有溶解、没有硬切——溶解读起来像剪辑，
     * 而凝彩的构图应该是交接。
     */
    public static final String[] TRANSITION_KINDS = {
            "block-wipe", "camera-pan", "shape-carry",
    };

    /** 每种镜头在编译期就被选定的网点装饰母题。 */
    public static final String[] DECOR_MOTIFS = {
            "diamonds", "hatch-twin", "band-cross", "poster-diamond", "doodle",
    };

    // ------------------------------------------------------------------
    // 歌词编译产物
    // ------------------------------------------------------------------

    /**
     * 解析器给出的一个音节（最细粒度时间单元）。qplayer 的 LYS/YRC 解析器每个音节一条，
     * 正好对应 folia 里 {@code Word.syllables} 的角色。
     */
    public static final class SyllableTime {
        public final String text;
        public final double startTime;
        public final double endTime;

        public SyllableTime(String text, double startTime, double endTime) {
            this.text = text;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    /**
     * 解析器给出的一个词。folia 的词可以带若干音节；qplayer 侧通常一词一音节，
     * 结构保留是为了让 graphemeTiming 的算法逐字对应。
     */
    public static final class Word {
        public final String text;
        public final double startTime;
        public final double endTime;
        public final List<SyllableTime> syllables = new ArrayList<>();

        public Word(String text, double startTime, double endTime) {
            this.text = text;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public Word addSyllable(String text, double startTime, double endTime) {
            syllables.add(new SyllableTime(text, startTime, endTime));
            return this;
        }
    }

    /** 一行原始歌词，编译器的输入。单位是秒。 */
    public static final class SourceLine {
        public final String fullText;
        public final double startTime;
        public final double endTime;
        public final List<Word> words = new ArrayList<>();

        public SourceLine(String fullText, double startTime, double endTime) {
            this.fullText = fullText;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public SourceLine addWord(Word word) {
            words.add(word);
            return this;
        }
    }

    /** 一个可渲染的最小字素单元（含逐字时间）。 */
    public static final class GraphemeTiming {
        public final String charText;
        public final double startTime;
        public final double endTime;

        public GraphemeTiming(String charText, double startTime, double endTime) {
            this.charText = charText;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    /** 编译后的一个词/标点片段：字素按整形宽度铺开，逐字对齐演唱时间。 */
    public static final class Segment {
        public String text;
        public int startOffset;
        public int endOffset;
        public double startTime;
        public double endTime;
        public List<GraphemeTiming> graphemes = new ArrayList<>();
        public boolean isWordLike;

        public Segment copy() {
            Segment out = new Segment();
            out.text = text;
            out.startOffset = startOffset;
            out.endOffset = endOffset;
            out.startTime = startTime;
            out.endTime = endTime;
            out.graphemes = new ArrayList<>(graphemes);
            out.isWordLike = isWordLike;
            return out;
        }
    }

    /**
     * 编译后的一行歌词。{@code sourceIndex} 指向原始歌词行，{@code renderEndTime}
     * 是渲染提示算出的「这一行最晚可以留到什么时候」。
     */
    public static final class CompiledLine {
        public final int sourceIndex;
        /** 原始文本（未切分），用于字素时间轴对齐。 */
        public String fullText = "";
        public double startTime;
        public double endTime;
        public double renderEndTime;
        public List<Segment> segments = new ArrayList<>();

        public CompiledLine(int sourceIndex) {
            this.sourceIndex = sourceIndex;
        }
    }

    // ------------------------------------------------------------------
    // 镜头与装饰
    // ------------------------------------------------------------------

    /** 相机的关键帧（视口偏移为视口比例的小数）。 */
    public static final class CameraKey {
        public final float x;
        public final float y;
        public final float zoom;
        public final float rotation;

        public CameraKey(float x, float y, float zoom, float rotation) {
            this.x = x;
            this.y = y;
            this.zoom = zoom;
            this.rotation = rotation;
        }
    }

    /** 停在稀疏构图边角上的一个孤立字形。 */
    public static final class DecorFragment {
        public final String charText;
        /** 视口比例坐标；场景构建阶段再换算成像素。 */
        public final float x;
        public final float y;
        public final float rotation;
        public final float scale;

        public DecorFragment(String charText, float x, float y, float rotation, float scale) {
            this.charText = charText;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.scale = scale;
        }
    }

    /** 构图背后超大的装饰词，海报水印式。 */
    public static final class DecorWatermark {
        public final String text;
        public final float x;
        public final float y;
        public final float rotation;
        /** 镜头基准字号的倍数。 */
        public final float scale;

        public DecorWatermark(String text, float x, float y, float rotation, float scale) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.scale = scale;
        }
    }

    /** 单个镜头的网点装饰，编译期就完全解算好，运行时不再碰随机数。 */
    public static final class DecorSpec {
        public String motif = "doodle";
        public float hatchAngle;
        public int crossCount;
        public int scribbleSeed;
        public List<DecorFragment> fragments = new ArrayList<>();
        public DecorWatermark watermark;
    }

    /** 镜头展示的是按词边界切开的半句（或整行，开了 {@code wholeLineLyrics} 时）。 */
    public static final class ShotSlice {
        /** 所属编译行的 {@code sourceIndex}。 */
        public final int lineIndex;
        /** 该行 {@code segments} 的半开区间。 */
        public final int segmentStart;
        public final int segmentEnd;

        public ShotSlice(int lineIndex, int segmentStart, int segmentEnd) {
            this.lineIndex = lineIndex;
            this.segmentStart = segmentStart;
            this.segmentEnd = segmentEnd;
        }
    }

    /** 一个镜头。 */
    public static final class Shot {
        public String id;
        public String kind;
        public double startTime;
        public double endTime;
        /**
         * 这个镜头最后一个字素停止演唱的时刻。{@code endTime} 会被平铺到下一个镜头的
         * 起点（收尾镜头则撑到段落末尾），所以它可能落在歌词之后好几秒；任何跟着词走
         * 的节奏（色块与图形的入场错峰）都必须用这个字段。
         */
        public double lyricEndTime;
        public List<ShotSlice> slices = new ArrayList<>();
        /** 桥段镜头承载段落之间的纯器乐空档：没有歌词切片，只有构图。 */
        public boolean isBridge;
        public CameraKey camera;
        public CameraKey cameraEnd;
        /** 该镜头图形整体行进的方向（弧度）。 */
        public float flowAngle;
        public DecorSpec decor = new DecorSpec();
    }

    public static final class Transition {
        public String kind;
        public double startTime;
        public double endTime;
    }

    public static final class Paragraph {
        public String id;
        public String kind;
        public String boundary;
        public double startTime;
        public double endTime;
        public List<CompiledLine> lines = new ArrayList<>();
        public List<Shot> shots = new ArrayList<>();
        public Transition transitionOut;
    }

    /** 编译产物：一次性算出，之后整首歌都只是查表 + 插值。 */
    public static final class Program {
        public int version = 1;
        public String seed = "";
        public double paragraphGapThreshold;
        public List<Paragraph> paragraphs = new ArrayList<>();
    }

    /**
     * 一张用户插图（移植自 folia 的 {@code TemperaLayerImage}）。qplayer 没有放置器 UI，
     * {@code align}/{@code verticalAlign} 走 folia 的默认 {@code free}，由九宫格带状摆放 +
     * 种子决定每镜落点。
     */
    public static final class LayerImage {
        public String id;
        public String path;
        public String align = "free";
        public String verticalAlign = "free";
        public float scale = 1f;
        public float opacity = 0.92f;
    }
}
