package dev.t1m3.qplayer.lyric.tempera;

/**
 * 缓动原语，1:1 移植自 folia-major {@code tempera/temperaMotionEasing.ts}。
 * 独立出来是为了让入场样式与逐字求解器共用而不互相依赖。
 */
public final class TemperaMotionEasing {
    private TemperaMotionEasing() {
    }

    public static float clamp01(double value) {
        return (float) Math.min(1.0, Math.max(0.0, value));
    }

    private static double cubicCoordinate(double point1, double point2, double time) {
        double inverse = 1 - time;
        return 3 * inverse * inverse * time * point1
                + 3 * inverse * time * time * point2
                + time * time * time;
    }

    /** CSS 式 cubic-bezier：先解 x 曲线，再采样 y。 */
    public static float resolveCubicBezier(double x1, double y1, double x2, double y2, double value) {
        double target = Math.min(1.0, Math.max(0.0, value));
        if (target == 0 || target == 1) return (float) target;
        double low = 0;
        double high = 1;
        double parameter = target;
        for (int iteration = 0; iteration < 12; iteration++) {
            double x = cubicCoordinate(x1, x2, parameter);
            if (x < target) low = parameter;
            else high = parameter;
            parameter = (low + high) / 2;
        }
        return (float) cubicCoordinate(y1, y2, parameter);
    }

    /** 长而软的前重减速：开头果断，然后一段很长的爬行尾巴。 */
    public static float easeEnter(double value) {
        return resolveCubicBezier(0.22, 1, 0.36, 1, value);
    }

    public static float easeInOut(double value) {
        return resolveCubicBezier(0.62, 0, 0.32, 1, value);
    }

    /** 出画时轻微的过冲；用于缩放，让字落位时有一点回弹。 */
    public static float easeSoftBack(double value) {
        double t = Math.min(1.0, Math.max(0.0, value));
        double c = 1.42;
        return (float) (1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2));
    }

    /**
     * 把一个「镜头内比例」映射成秒，并夹住上下限，于是特别短或特别长的镜头仍然以可看的
     * 速度动。这就是把色块运动系在行节奏而不是固定墙钟时长上的那一环。
     *
     * <p>folia 把它放在 {@code temperaMotion.ts}；这里放在缓动原语模块里，
     * {@link TemperaMotion} 再原样转发一次，保持两边的调用点一致。
     */
    public static double resolveShotPacedDuration(double shotDuration, double fraction,
                                                  double minSeconds, double maxSeconds) {
        return Math.min(maxSeconds, Math.max(minSeconds, shotDuration * fraction));
    }
}
