package dev.t1m3.qplayer.lyric.tempera;

/**
 * 镜头级运动，1:1 移植自 folia-major {@code tempera/temperaCamera.ts}。
 * 只插值编译期烘好的起止关键帧，再叠一层确定性的呼吸浮动；从不追踪单个字。
 */
public final class TemperaCamera {
    private TemperaCamera() {
    }

    public static final float BREATH_MAX_OFFSET = 0.006f;
    public static final float BREATH_MAX_SCALE = 0.002f;
    public static final float BREATH_MAX_ROTATION = 0.0015f;

    public static final class Key {
        public float x;
        public float y;
        public float zoom;
        public float rotation;

        public Key() {
        }

        public Key(float x, float y, float zoom, float rotation) {
            this.x = x;
            this.y = y;
            this.zoom = zoom;
            this.rotation = rotation;
        }
    }

    public static final class Frame {
        public float x;
        public float y;
        public float scale;
        public float rotation;
    }

    private static float clamp01(float value) {
        return Math.min(1f, Math.max(0f, value));
    }

    private static float easeInOut(float value) {
        float t = clamp01(value);
        return t < 0.5f ? 4 * t * t * t : (float) (1 - Math.pow(-2 * t + 2, 3) / 2);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    /**
     * 求某进度下的镜头路径。进度可以略微超过 1（行间空隙），这样画面会继续漂移而不是冻住。
     */
    public static Frame resolve(Key start, Key end, float progress) {
        float clamped = clamp01(progress);
        // 把匀速混进缓动，镜头中段就不会停住。
        float eased = clamped * 0.5f + easeInOut(clamped) * 0.5f;
        float overshoot = Math.max(0f, progress - 1f) * 0.35f;
        float amount = eased + overshoot;
        Frame frame = new Frame();
        frame.x = lerp(start.x, end.x, amount);
        frame.y = lerp(start.y, end.y, amount);
        frame.scale = lerp(start.zoom, end.zoom, amount);
        frame.rotation = lerp(start.rotation, end.rotation, amount);
        return frame;
    }

    /** 确定性的手持呼吸浮动：几条互不整齐的正弦叠加，绝对时间求值所以 seek 与播放一致。 */
    public static Frame breath(float time, float phase) {
        float tau = (float) (time * Math.PI * 2);
        Frame frame = new Frame();
        frame.x = (float) ((Math.sin(tau * 0.13 + phase) * 0.65
                + Math.sin(tau * 0.31 + phase * 1.7) * 0.35) * BREATH_MAX_OFFSET);
        frame.y = (float) ((Math.cos(tau * 0.11 + phase * 2.3) * 0.65
                + Math.sin(tau * 0.29 + phase * 0.9) * 0.35) * BREATH_MAX_OFFSET);
        frame.scale = (float) (Math.sin(tau * 0.09 + phase * 1.3) * BREATH_MAX_SCALE);
        frame.rotation = (float) (Math.sin(tau * 0.07 + phase * 2.9) * BREATH_MAX_ROTATION);
        return frame;
    }

    /** 呼吸在逐字亮相结束后才渐入，避免在一句中间突然出现。 */
    public static float breathWeight(float time, float revealDoneTime, float rampDuration) {
        if (rampDuration <= 0) return time >= revealDoneTime ? 1f : 0f;
        return easeInOut(clamp01((time - revealDoneTime) / rampDuration));
    }
}
