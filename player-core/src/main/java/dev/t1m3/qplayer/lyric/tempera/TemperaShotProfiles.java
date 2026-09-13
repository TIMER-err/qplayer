package dev.t1m3.qplayer.lyric.tempera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐种构图的纯数据：字排在哪里、字形往哪个方向飞入、相机怎么走、构图有多吵。
 * 1:1 移植自 folia-major {@code tempera/temperaShotProfiles.ts}（由脚本从 TS 源生成）。
 *
 * <p>刻意不依赖任何绘制代码，于是排版器与程序编译器都能依赖它，而不用拖进绘制层。
 */
public final class TemperaShotProfiles {
    private TemperaShotProfiles() {
    }

    /** 歌词的布局盒，单位是视口的比例。 */
    public static final class Region {
        public final float cx;
        public final float cy;
        public final float w;
        public final float h;
        public final String align;
        public final float rotation;
        /** 镜头基准字号的倍数。 */
        public final float fontScale;

        Region(float cx, float cy, float w, float h, String align, float rotation, float fontScale) {
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.align = align;
            this.rotation = rotation;
            this.fontScale = fontScale;
        }
    }

    /** 基础字形入场矢量，单位是解算后字号的倍数。 */
    public static final class Enter {
        public final float x;
        public final float y;

        Enter(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    /** 相机沿镜头流动方向的位移，以及它的变焦坡道。 */
    public static final class CameraProfile {
        public final float travel;
        public final float zoomStart;
        public final float zoomEnd;

        CameraProfile(float travel, float zoomStart, float zoomEnd) {
            this.travel = travel;
            this.zoomStart = zoomStart;
            this.zoomEnd = zoomEnd;
        }
    }

    public static final class Profile {
        public final Region region;
        public final Enter enter;
        public final CameraProfile camera;
        /** 构图有多吵：安静的段落避开 loud，副歌永远不会掉到 quiet。 */
        public final String mood;
        /** 共用的交叉线与母题叠加层是否绘制。整张卡的要点就是净场的会关掉。 */
        public final boolean sharedDecor;

        Profile(Region region, Enter enter, CameraProfile camera, String mood, boolean sharedDecor) {
            this.region = region;
            this.enter = enter;
            this.camera = camera;
            this.mood = mood;
            this.sharedDecor = sharedDecor;
        }
    }

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();

    private static void put(String kind, Region region, float enterX, float enterY,
                            float travel, float zoomStart, float zoomEnd,
                            String mood, boolean sharedDecor) {
        PROFILES.put(kind, new Profile(region, new Enter(enterX, enterY),
                new CameraProfile(travel, zoomStart, zoomEnd), mood, sharedDecor));
    }

    static {
        put("duo-split", new Region(0.5f, 0.52f, 0.86f, 0.46f, "center", 0f, 1f), 0f, 1.3f, 0.11f, 1.06f, 1.13f, "neutral", true);
        put("quad-split", new Region(0.5f, 0.5f, 0.82f, 0.4f, "center", 0f, 1f), 0.9f, 0.9f, 0.09f, 1.08f, 1.16f, "loud", true);
        put("tri-column", new Region(0.5f, 0.5f, 0.7f, 0.5f, "center", 0f, 0.95f), -1.2f, 0f, 0.12f, 1.04f, 1.12f, "neutral", true);
        put("thirds-stack", new Region(0.5f, 0.5f, 0.8f, 0.28f, "center", 0f, 1f), 0f, 1.1f, 0.13f, 1.03f, 1.11f, "neutral", true);
        put("checker-quad", new Region(0.5f, 0.5f, 0.76f, 0.36f, "center", 0f, 1f), 0.8f, -0.8f, 0.1f, 1.1f, 1.02f, "loud", true);
        put("corner-wedge", new Region(0.44f, 0.56f, 0.68f, 0.4f, "left", -0.03f, 1f), -1.4f, 0.5f, 0.1f, 1.05f, 1.14f, "loud", true);
        put("diagonal-halves", new Region(0.5f, 0.5f, 0.78f, 0.4f, "center", -0.075f, 1f), 1.1f, 1.1f, 0.12f, 1.06f, 1.14f, "neutral", true);
        put("cross-axis", new Region(0.5f, 0.5f, 0.66f, 0.3f, "center", 0f, 1f), 0f, 1.2f, 0.08f, 1.12f, 1.03f, "loud", true);
        put("offset-halves", new Region(0.5f, 0.5f, 0.8f, 0.4f, "center", 0f, 1f), 0.9f, 0.6f, 0.11f, 1.05f, 1.13f, "neutral", true);
        put("stair-blocks", new Region(0.5f, 0.5f, 0.72f, 0.38f, "center", -0.03f, 1f), -1f, 0.8f, 0.12f, 1.06f, 1.14f, "loud", true);
        put("pillar-gap", new Region(0.5f, 0.5f, 0.34f, 0.62f, "center", 0f, 0.8f), 0f, 1.2f, 0.07f, 1.1f, 1.02f, "loud", true);
        put("corner-quad", new Region(0.46f, 0.46f, 0.68f, 0.36f, "center", 0f, 1f), -0.9f, -0.9f, 0.1f, 1.07f, 1.15f, "neutral", true);
        put("sliver-stack", new Region(0.5f, 0.5f, 0.76f, 0.3f, "center", 0f, 1f), 1.2f, 0f, 0.13f, 1.04f, 1.12f, "neutral", true);
        put("band-strip", new Region(0.5f, 0.52f, 0.78f, 0.26f, "center", 0f, 0.92f), 0.5f, 1.05f, 0.12f, 1.03f, 1.1f, "neutral", true);
        put("horizon-band", new Region(0.5f, 0.36f, 0.8f, 0.3f, "center", 0f, 1f), 0f, -1.1f, 0.14f, 1.02f, 1.1f, "neutral", true);
        put("deep-dive", new Region(0.5f, 0.58f, 0.76f, 0.34f, "center", 0f, 1f), 0f, 1.6f, 0.16f, 1.04f, 1.14f, "neutral", true);
        put("tone-ramp", new Region(0.5f, 0.5f, 0.82f, 0.38f, "center", 0f, 1f), 1.2f, 0.4f, 0.11f, 1.05f, 1.12f, "neutral", true);
        put("double-band", new Region(0.5f, 0.5f, 0.78f, 0.22f, "center", 0f, 0.88f), 0.8f, 0f, 0.12f, 1.03f, 1.11f, "neutral", true);
        put("tilt-band", new Region(0.5f, 0.5f, 0.8f, 0.26f, "center", -0.1f, 1f), -1.1f, 0.5f, 0.13f, 1.05f, 1.13f, "neutral", true);
        put("edge-rails", new Region(0.5f, 0.5f, 0.74f, 0.4f, "center", 0f, 1f), 0f, 1f, 0.09f, 1.02f, 1.09f, "quiet", true);
        put("gradient-wall", new Region(0.5f, 0.44f, 0.82f, 0.36f, "center", 0f, 1f), 0.5f, -0.9f, 0.15f, 1.04f, 1.13f, "neutral", true);
        put("terrace", new Region(0.46f, 0.52f, 0.72f, 0.34f, "left", 0f, 1f), -1.2f, 0.4f, 0.14f, 1.05f, 1.12f, "neutral", true);
        put("frame-window", new Region(0.5f, 0.5f, 0.64f, 0.5f, "center", 0f, 0.95f), 0f, 0.95f, 0.05f, 1.14f, 1.03f, "neutral", true);
        put("double-frame", new Region(0.5f, 0.5f, 0.58f, 0.4f, "center", 0f, 0.9f), 0.6f, 0.6f, 0.06f, 1.12f, 1.02f, "neutral", true);
        put("circle-window", new Region(0.5f, 0.5f, 0.5f, 0.34f, "center", 0f, 0.88f), 0f, 0.8f, 0.05f, 1.16f, 1.04f, "quiet", true);
        put("ladder-frame", new Region(0.52f, 0.5f, 0.6f, 0.4f, "left", 0f, 0.9f), -0.9f, 0.6f, 0.07f, 1.1f, 1.02f, "quiet", true);
        put("corner-brackets", new Region(0.5f, 0.5f, 0.56f, 0.3f, "center", 0f, 0.85f), 0f, 0.6f, 0.04f, 1.06f, 1.01f, "quiet", true);
        put("inset-box", new Region(0.5f, 0.5f, 0.6f, 0.42f, "center", 0f, 0.92f), 0f, 0.8f, 0.06f, 1.1f, 1.02f, "quiet", true);
        put("bracket-pair", new Region(0.5f, 0.5f, 0.54f, 0.34f, "center", 0f, 0.88f), 1f, 0f, 0.05f, 1.08f, 1.01f, "quiet", true);
        put("arch-window", new Region(0.5f, 0.54f, 0.5f, 0.34f, "center", 0f, 0.86f), 0f, 0.9f, 0.06f, 1.14f, 1.03f, "quiet", true);
        put("grid-cells", new Region(0.5f, 0.5f, 0.72f, 0.22f, "center", 0f, 0.8f), 0.7f, 0.7f, 0.07f, 1.06f, 1.13f, "neutral", true);
        put("keyhole", new Region(0.5f, 0.6f, 0.42f, 0.3f, "center", 0f, 0.78f), 0f, 1f, 0.05f, 1.12f, 1.02f, "quiet", true);
        put("poster-panel", new Region(0.4f, 0.5f, 0.58f, 0.62f, "left", -0.045f, 1f), -1.5f, 0.35f, 0.08f, 1.05f, 1.12f, "loud", true);
        put("diamond-stack", new Region(0.54f, 0.5f, 0.6f, 0.42f, "right", 0.035f, 1f), 1.3f, -0.5f, 0.09f, 1.07f, 1.15f, "loud", true);
        put("slash-poster", new Region(0.46f, 0.5f, 0.66f, 0.44f, "left", -0.09f, 1f), -1.2f, 1f, 0.11f, 1.06f, 1.14f, "loud", true);
        put("arrow-wedge", new Region(0.5f, 0.34f, 0.72f, 0.26f, "center", 0f, 1f), 0f, -1.2f, 0.13f, 1.04f, 1.13f, "loud", true);
        put("edge-bleed", new Region(0.56f, 0.5f, 0.6f, 0.44f, "left", 0f, 1f), 1.4f, 0.3f, 0.1f, 1.05f, 1.13f, "neutral", true);
        put("triangle-mass", new Region(0.5f, 0.42f, 0.68f, 0.3f, "center", 0f, 1f), 0.8f, -1f, 0.12f, 1.05f, 1.14f, "loud", true);
        put("ribbon-cross", new Region(0.5f, 0.5f, 0.62f, 0.3f, "center", 0.05f, 1f), -1f, -0.8f, 0.11f, 1.08f, 1.16f, "loud", true);
        put("half-disc", new Region(0.44f, 0.44f, 0.6f, 0.34f, "left", 0f, 1f), -1.3f, 0.4f, 0.1f, 1.06f, 1.14f, "loud", true);
        put("stacked-slabs", new Region(0.52f, 0.5f, 0.62f, 0.4f, "center", -0.05f, 1f), 1.1f, 0.6f, 0.1f, 1.07f, 1.15f, "loud", true);
        put("wedge-pair", new Region(0.5f, 0.5f, 0.44f, 0.44f, "center", 0f, 0.82f), 0f, 1.1f, 0.09f, 1.1f, 1.02f, "loud", true);
        put("quiet-line", new Region(0.5f, 0.5f, 0.6f, 0.28f, "center", 0f, 0.58f), 0f, 0.7f, 0.03f, 1f, 1.04f, "quiet", true);
        put("starfield-dots", new Region(0.5f, 0.5f, 0.62f, 0.26f, "center", 0f, 0.66f), 0.4f, 0.5f, 0.04f, 1.02f, 1.08f, "quiet", true);
        put("ripple-lines", new Region(0.5f, 0.46f, 0.66f, 0.28f, "center", 0f, 0.72f), 0f, 0.9f, 0.06f, 1.03f, 1.1f, "quiet", true);
        put("hair-grid", new Region(0.5f, 0.5f, 0.64f, 0.26f, "center", 0f, 0.62f), 0.4f, 0.6f, 0.04f, 1.01f, 1.06f, "quiet", true);
        put("margin-rule", new Region(0.54f, 0.5f, 0.62f, 0.26f, "left", 0f, 0.66f), -0.8f, 0.3f, 0.05f, 1.02f, 1.07f, "quiet", true);
        put("dot-drift", new Region(0.5f, 0.48f, 0.6f, 0.26f, "center", 0f, 0.68f), 0.6f, 0.6f, 0.05f, 1.03f, 1.09f, "quiet", true);
        put("arc-sweep", new Region(0.5f, 0.5f, 0.6f, 0.26f, "center", 0f, 0.7f), 0f, 0.8f, 0.06f, 1.04f, 1.1f, "quiet", true);
        put("blank-page", new Region(0.5f, 0.5f, 0.56f, 0.24f, "center", 0f, 0.6f), 0f, 0.5f, 0.03f, 1f, 1.03f, "quiet", true);
        put("cinema-scope", new Region(0.5f, 0.5f, 0.74f, 0.22f, "center", 0f, 0.86f), 0.9f, 0f, 0.09f, 1.04f, 1.11f, "neutral", true);
        put("cinema-wide", new Region(0.5f, 0.5f, 0.7f, 0.3f, "center", 0f, 0.9f), 0f, 0.9f, 0.08f, 1.06f, 1.13f, "neutral", true);
        put("cinema-academy", new Region(0.5f, 0.5f, 0.5f, 0.4f, "center", 0f, 0.88f), 0f, 0.8f, 0.06f, 1.1f, 1.02f, "quiet", true);
        put("cinema-square", new Region(0.5f, 0.5f, 0.42f, 0.4f, "center", 0f, 0.84f), 0.7f, 0.7f, 0.05f, 1.12f, 1.03f, "quiet", true);
        put("cinema-portrait", new Region(0.5f, 0.5f, 0.32f, 0.46f, "center", 0f, 0.8f), 0f, 1f, 0.06f, 1.08f, 1.16f, "neutral", true);
        put("cinema-tall", new Region(0.5f, 0.5f, 0.24f, 0.5f, "center", 0f, 0.72f), 0.8f, 0.4f, 0.07f, 1.05f, 1.14f, "neutral", true);
        put("cinema-twin", new Region(0.33f, 0.5f, 0.3f, 0.32f, "center", 0f, 0.76f), -0.9f, 0.3f, 0.08f, 1.06f, 1.13f, "neutral", true);
        put("bubble-drift", new Region(0.5f, 0.5f, 0.72f, 0.36f, "center", 0f, 0.92f), 0.5f, 1f, 0.09f, 1.05f, 1.12f, "neutral", true);
        put("cloud-window", new Region(0.5f, 0.52f, 0.46f, 0.3f, "center", 0f, 0.84f), 0f, 0.8f, 0.05f, 1.14f, 1.03f, "quiet", true);
        put("heart-burst", new Region(0.5f, 0.52f, 0.5f, 0.3f, "center", 0f, 0.95f), 0.8f, 0.8f, 0.1f, 1.08f, 1.16f, "loud", true);
        put("sparkle-field", new Region(0.5f, 0.48f, 0.64f, 0.26f, "center", 0f, 0.7f), 0.4f, 0.6f, 0.04f, 1.02f, 1.08f, "quiet", true);
        put("petal-arc", new Region(0.5f, 0.6f, 0.7f, 0.3f, "center", 0f, 0.9f), 0f, 1.1f, 0.1f, 1.04f, 1.12f, "neutral", true);
        put("scallop-band", new Region(0.5f, 0.5f, 0.76f, 0.24f, "center", 0f, 0.88f), 0.7f, 0f, 0.12f, 1.03f, 1.1f, "neutral", true);
        put("ribbon-loop", new Region(0.5f, 0.5f, 0.68f, 0.22f, "center", 0.04f, 0.86f), -1.1f, 0.6f, 0.11f, 1.07f, 1.15f, "loud", true);
        put("round-plate", new Region(0.5f, 0.5f, 0.56f, 0.36f, "center", 0f, 0.86f), 0f, 0.7f, 0.05f, 1.1f, 1.02f, "quiet", true);
        put("halo-burst", new Region(0.5f, 0.5f, 0.46f, 0.3f, "center", 0f, 0.95f), 0f, 0.9f, 0.08f, 1.12f, 1.02f, "loud", true);
        put("iris-hole", new Region(0.28f, 0.68f, 0.44f, 0.28f, "center", 0f, 0.78f), -1f, 0.6f, 0.09f, 1.06f, 1.14f, "loud", true);
        put("slot-rail", new Region(0.5f, 0.66f, 0.72f, 0.3f, "center", 0f, 0.9f), 0.6f, 0.9f, 0.12f, 1.04f, 1.11f, "neutral", true);
        put("punch-row", new Region(0.5f, 0.5f, 0.76f, 0.34f, "center", 0f, 0.92f), 1.1f, 0f, 0.13f, 1.03f, 1.1f, "neutral", true);
        put("film-gate", new Region(0.5f, 0.82f, 0.66f, 0.2f, "center", 0f, 0.8f), 0f, 1.1f, 0.08f, 1.08f, 1.02f, "loud", true);
        put("cross-vent", new Region(0.5f, 0.82f, 0.66f, 0.2f, "center", 0f, 0.8f), 0f, 1f, 0.07f, 1.1f, 1.02f, "loud", true);
        put("louvre-slats", new Region(0.5f, 0.8f, 0.7f, 0.22f, "center", 0f, 0.82f), 0.8f, 0.7f, 0.11f, 1.05f, 1.13f, "neutral", true);
        put("ring-eye", new Region(0.5f, 0.5f, 0.26f, 0.18f, "center", 0f, 0.62f), 0f, 0.7f, 0.05f, 1.14f, 1.03f, "quiet", true);
        put("notch-stack", new Region(0.32f, 0.5f, 0.5f, 0.34f, "center", 0f, 0.84f), -1.1f, 0.5f, 0.1f, 1.05f, 1.13f, "neutral", true);
        put("wedge-gap", new Region(0.5f, 0.78f, 0.7f, 0.24f, "center", 0f, 0.82f), 0f, 1.2f, 0.12f, 1.06f, 1.14f, "loud", true);
        put("dot-sieve", new Region(0.75f, 0.5f, 0.38f, 0.3f, "center", 0f, 0.78f), 0.9f, 0.4f, 0.06f, 1.03f, 1.09f, "quiet", true);
        put("sight-mark", new Region(0.5f, 0.5f, 0.46f, 0.2f, "center", 0f, 0.85f), 0f, 0.8f, 0.06f, 1.12f, 1.02f, "loud", true);
        put("dial-scale", new Region(0.5f, 0.5f, 0.36f, 0.2f, "center", 0f, 0.8f), 0.7f, 0.5f, 0.05f, 1.1f, 1.02f, "neutral", true);
        put("chevron-run", new Region(0.5f, 0.52f, 0.72f, 0.3f, "center", 0f, 0.9f), 0f, -1.1f, 0.14f, 1.04f, 1.12f, "neutral", true);
        put("tally-column", new Region(0.38f, 0.5f, 0.6f, 0.32f, "left", 0f, 0.76f), -0.9f, 0.3f, 0.05f, 1.02f, 1.08f, "quiet", true);
        put("grid-focus", new Region(0.5f, 0.5f, 0.62f, 0.22f, "center", 0f, 0.85f), 0.8f, 0.6f, 0.09f, 1.05f, 1.13f, "neutral", true);
        put("axis-caps", new Region(0.5f, 0.72f, 0.66f, 0.24f, "center", 0f, 0.82f), 0f, 1.2f, 0.13f, 1.06f, 1.15f, "loud", true);
        put("strobe-slats", new Region(0.5f, 0.5f, 0.3f, 0.36f, "center", 0f, 0.8f), 1f, 0f, 0.1f, 1.08f, 1.16f, "loud", true);
        put("offset-plate", new Region(0.48f, 0.62f, 0.4f, 0.2f, "center", 0f, 0.85f), -0.9f, -0.6f, 0.09f, 1.07f, 1.15f, "loud", true);
        put("radial-comb", new Region(0.42f, 0.4f, 0.62f, 0.3f, "center", 0f, 0.88f), -1.2f, -0.4f, 0.11f, 1.05f, 1.13f, "neutral", true);
        put("bracket-target", new Region(0.5f, 0.28f, 0.66f, 0.24f, "center", 0f, 0.85f), 0f, -0.9f, 0.06f, 1.04f, 1.1f, "quiet", true);
        put("flow-channel", new Region(0.32f, 0.5f, 0.5f, 0.3f, "center", 0f, 0.85f), -1f, 0.5f, 0.13f, 1.04f, 1.12f, "neutral", true);
        put("twin-channel", new Region(0.5f, 0.5f, 0.4f, 0.3f, "center", 0f, 0.85f), 0f, 1.2f, 0.14f, 1.03f, 1.11f, "neutral", true);
        put("reed-run", new Region(0.5f, 0.5f, 0.3f, 0.3f, "center", 0f, 0.72f), 0f, 0.9f, 0.1f, 1.02f, 1.09f, "quiet", true);
        put("taper-channel", new Region(0.28f, 0.5f, 0.42f, 0.3f, "center", 0f, 0.82f), -1.1f, 0.6f, 0.12f, 1.05f, 1.14f, "neutral", true);
        put("chain-ports", new Region(0.32f, 0.5f, 0.5f, 0.32f, "center", 0f, 0.8f), -0.8f, 0.7f, 0.11f, 1.03f, 1.1f, "quiet", true);
        put("dash-channel", new Region(0.3f, 0.5f, 0.46f, 0.3f, "center", 0f, 0.84f), 0f, 1.3f, 0.15f, 1.04f, 1.13f, "neutral", true);
        put("window-run", new Region(0.28f, 0.5f, 0.42f, 0.3f, "center", 0f, 0.82f), 0f, 1.2f, 0.14f, 1.04f, 1.12f, "neutral", true);
        put("bridge-span", new Region(0.5f, 0.5f, 0.72f, 0.16f, "center", 0f, 0.85f), 1.2f, 0f, 0.12f, 1.08f, 1.16f, "loud", true);
        put("braid-channel", new Region(0.5f, 0.5f, 0.34f, 0.3f, "center", 0f, 0.8f), 0f, 1.1f, 0.13f, 1.06f, 1.15f, "loud", true);
        put("port-ladder", new Region(0.3f, 0.5f, 0.46f, 0.3f, "center", 0f, 0.78f), -0.9f, 0.4f, 0.09f, 1.02f, 1.09f, "quiet", true);
        put("apex-mass", new Region(0.5f, 0.3f, 0.62f, 0.2f, "center", 0f, 0.9f), 0f, -1f, 0.12f, 1.04f, 1.13f, "loud", true);
        put("ziggurat", new Region(0.5f, 0.32f, 0.5f, 0.18f, "center", 0f, 0.85f), 0f, -0.9f, 0.1f, 1.06f, 1.15f, "loud", true);
        put("slab-wall", new Region(0.36f, 0.5f, 0.5f, 0.24f, "center", 0f, 0.88f), 1.2f, 0f, 0.11f, 1.05f, 1.12f, "neutral", true);
        put("cantilever", new Region(0.42f, 0.42f, 0.6f, 0.14f, "center", 0f, 0.8f), -1.3f, 0f, 0.13f, 1.04f, 1.12f, "loud", true);
        put("pylon-pair", new Region(0.5f, 0.22f, 0.7f, 0.12f, "center", 0f, 0.75f), 0f, -0.8f, 0.09f, 1.08f, 1.02f, "loud", true);
        put("bunker-slit", new Region(0.5f, 0.48f, 0.72f, 0.2f, "center", 0f, 0.86f), 0.9f, 0.4f, 0.08f, 1.06f, 1.13f, "neutral", true);
        put("plinth-stack", new Region(0.5f, 0.32f, 0.44f, 0.16f, "center", 0f, 0.82f), 0f, -0.8f, 0.1f, 1.05f, 1.13f, "neutral", true);
        put("buttress-run", new Region(0.5f, 0.26f, 0.7f, 0.2f, "center", 0f, 0.86f), 0.8f, -0.8f, 0.12f, 1.03f, 1.11f, "loud", true);
        put("void-core", new Region(0.5f, 0.2f, 0.66f, 0.18f, "center", 0f, 0.84f), 0f, -1.1f, 0.11f, 1.07f, 1.15f, "loud", true);
        put("shear-block", new Region(0.5f, 0.5f, 0.56f, 0.2f, "center", 0f, 0.88f), 1f, 0.5f, 0.12f, 1.06f, 1.14f, "neutral", true);
        put("ridge-line", new Region(0.5f, 0.42f, 0.72f, 0.2f, "center", 0f, 0.9f), 0f, -1f, 0.14f, 1.03f, 1.12f, "neutral", true);
        put("chasm", new Region(0.25f, 0.5f, 0.38f, 0.24f, "center", 0f, 0.82f), -1.2f, 0.4f, 0.1f, 1.06f, 1.14f, "loud", true);
        put("overhang", new Region(0.44f, 0.58f, 0.6f, 0.22f, "center", 0f, 0.88f), 0f, 1.1f, 0.11f, 1.08f, 1.02f, "loud", true);
        put("step-well", new Region(0.5f, 0.26f, 0.66f, 0.2f, "center", 0f, 0.85f), 0f, -0.9f, 0.09f, 1.04f, 1.12f, "neutral", true);
        put("pier-row", new Region(0.5f, 0.4f, 0.76f, 0.14f, "center", 0f, 0.82f), 1.1f, 0f, 0.13f, 1.03f, 1.1f, "neutral", true);
        put("revetment", new Region(0.46f, 0.3f, 0.6f, 0.2f, "center", 0f, 0.86f), -1f, -0.6f, 0.12f, 1.05f, 1.13f, "neutral", true);
        put("tower-crop", new Region(0.28f, 0.5f, 0.44f, 0.24f, "left", 0f, 0.74f), -0.8f, 0.3f, 0.06f, 1.02f, 1.09f, "quiet", true);
        put("lintel", new Region(0.5f, 0.5f, 0.76f, 0.16f, "center", 0f, 0.9f), -1.1f, 0f, 0.1f, 1.02f, 1.08f, "quiet", true);
        put("rubble-fan", new Region(0.56f, 0.4f, 0.5f, 0.22f, "center", 0f, 0.84f), 1.2f, -0.5f, 0.13f, 1.07f, 1.16f, "loud", true);
        put("gnomon", new Region(0.66f, 0.34f, 0.46f, 0.2f, "center", 0f, 0.78f), 0.9f, -0.4f, 0.07f, 1.03f, 1.1f, "quiet", true);
        put("monogatari-card", new Region(0.5f, 0.5f, 0.78f, 0.5f, "center", 0f, 1.15f), 0f, 0.7f, 0.03f, 1.02f, 1.07f, "quiet", false);
        put("monogatari-rule", new Region(0.5f, 0.46f, 0.76f, 0.4f, "center", 0f, 1.05f), 0.6f, 0f, 0.04f, 1.03f, 1.09f, "quiet", false);
        put("monogatari-edge", new Region(0.52f, 0.5f, 0.72f, 0.46f, "left", 0f, 1.05f), -0.8f, 0f, 0.05f, 1.02f, 1.08f, "neutral", false);
        put("monogatari-stack", new Region(0.5f, 0.5f, 0.44f, 0.62f, "center", 0f, 1f), 0f, 0.9f, 0.04f, 1.05f, 1.12f, "quiet", false);
        put("monogatari-flash", new Region(0.5f, 0.5f, 0.82f, 0.44f, "center", 0f, 1.3f), 0f, 0.5f, 0.02f, 1.08f, 1.01f, "loud", false);
    }

    /** 缺省回落到 {@code duo-split}，与 folia 一致。 */
    public static Profile resolve(String kind) {
        Profile profile = PROFILES.get(kind);
        return profile != null ? profile : PROFILES.get("duo-split");
    }

    /** 某个性格的段落允许切到的构图。 */
    public static List<String> resolveCandidates(List<String> moods) {
        List<String> candidates = new ArrayList<>();
        for (String kind : TemperaTypes.SHOT_KINDS) {
            if (moods.contains(resolve(kind).mood)) candidates.add(kind);
        }
        return candidates.isEmpty()
                ? new ArrayList<>(java.util.Arrays.asList(TemperaTypes.SHOT_KINDS))
                : candidates;
    }

    /** 全部档案（调试/自检用）。 */
    public static Map<String, Profile> all() {
        return PROFILES;
    }
}
