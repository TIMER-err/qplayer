package dev.t1m3.qplayer.lyric.tempera;

/**
 * 逐字入场方式，1:1 移植自 folia-major {@code tempera/temperaEnterStyles.ts}。
 *
 * <p>全是方向变体：长距离飞入与单轴拉伸被刻意去掉了（会读成花招），每种样式都只走排版
 * 已经为这个镜头算好的那段位移，并且等比缩放。样式按词挑选，所以一个词是整体落位，
 * 相邻词各自不同——这种差异正是拼贴不会读成统一滑入的原因。
 */
public final class TemperaEnterStyles {
    private TemperaEnterStyles() {
    }

    public static final String[] STYLES = {
            "slide", "from-left", "from-right", "from-above", "from-below", "swing", "stamp",
    };

    public static String styleAt(int index) {
        return STYLES[Math.floorMod(index, STYLES.length)];
    }

    /** 入场求解的输入：排版算出的位移、旋转、缩放。 */
    public static final class Input {
        public float enterX;
        public float enterY;
        public float enterRotation;
        public float enterScale;

        public Input(float enterX, float enterY, float enterRotation, float enterScale) {
            this.enterX = enterX;
            this.enterY = enterY;
            this.enterRotation = enterRotation;
            this.enterScale = enterScale;
        }
    }

    /** 某一时刻该样式的 x/y/旋转/缩放与残影强度。 */
    public static final class Frame {
        public float x;
        public float y;
        public float rotation;
        public float scaleX;
        public float scaleY;
        /** 0 表示这个样式没有值得拖出残影的位移（stamp 无处可拖）。 */
        public float echo;
    }

    private static Frame directional(Input input, float travel, float uniform,
                                     float dirX, float dirY, float rotationScale) {
        float magnitude = (float) Math.hypot(input.enterX, input.enterY);
        float length = (float) Math.hypot(dirX, dirY);
        if (length == 0f) length = 1f;
        Frame frame = new Frame();
        frame.x = (dirX / length) * magnitude * travel;
        frame.y = (dirY / length) * magnitude * travel;
        frame.rotation = input.enterRotation * rotationScale * travel;
        frame.scaleX = uniform;
        frame.scaleY = uniform;
        frame.echo = travel;
        return frame;
    }

    /**
     * 求解某个样式在入场途中某点的偏移/旋转/缩放。
     * {@code travel} 在窗口内从 1 走到 0，{@code linear} 是原始 0→1 进度。
     */
    public static Frame resolve(String style, Input input, float travel, float linear) {
        float settle = TemperaMotionEasing.easeSoftBack(linear);
        float uniform = input.enterScale + (1 - input.enterScale) * settle;
        Frame frame = new Frame();
        switch (style == null ? "slide" : style) {
            case "from-left":
                return directional(input, travel, uniform, -1, 0.12f, 0.4f);
            case "from-right":
                return directional(input, travel, uniform, 1, -0.12f, 0.4f);
            case "from-above":
                return directional(input, travel, uniform, 0.12f, -1, 0.4f);
            case "from-below":
                return directional(input, travel, uniform, -0.12f, 1, 0.4f);
            case "swing":
                // 走镜头自己的接近向量，但字绕自身中心转进来。
                frame.x = input.enterX * 0.6f * travel;
                frame.y = input.enterY * 0.6f * travel;
                float sign = input.enterRotation == 0f ? 1f : Math.signum(input.enterRotation);
                frame.rotation = (input.enterRotation + sign * 0.95f) * travel;
                frame.scaleX = uniform;
                frame.scaleY = uniform;
                frame.echo = travel * 0.8f;
                return frame;
            case "stamp":
                // 唯一的原地样式：从超尺寸砸到页面上。没有位移，也就没有残影。
                frame.x = 0;
                frame.y = 0;
                frame.rotation = input.enterRotation * 0.6f * travel;
                frame.scaleX = 1 + travel * 0.7f;
                frame.scaleY = 1 + travel * 0.7f;
                frame.echo = 0;
                return frame;
            case "slide":
            default:
                frame.x = input.enterX * travel;
                frame.y = input.enterY * travel;
                frame.rotation = input.enterRotation * travel;
                frame.scaleX = uniform;
                frame.scaleY = uniform;
                frame.echo = travel;
                return frame;
        }
    }
}
