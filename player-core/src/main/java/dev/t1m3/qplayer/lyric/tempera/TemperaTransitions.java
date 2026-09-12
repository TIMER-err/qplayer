package dev.t1m3.qplayer.lyric.tempera;

import java.util.List;

// src/components/visualizer/tempera/temperaTransitions.ts
// 段落（paragraph）边界的 seek 稳定过渡帧。镜头（shot）边界不需要过渡：在运行时各构图会直接
// 交接给彼此。这里的每一种过渡都由大块图形或镜头主导，并沿镜头的流向角运动，因此出/入段落沿
// 同一方向行进，整段边界读起来像是一次连续的运动。

public final class TemperaTransitions {

    /** 段落过渡帧：描述某一时刻出/入构图叠加在静止帧之上的偏移与效果。 */
    public static class TemperaTransitionFrame {
        public float x;
        public float y;
        public float scale;
        public float rotation;
        public float alpha;
        public float blur;
        /** 运行时覆盖层绘制的擦除块的 0..2 扫过行程；1 表示完整覆盖。 */
        public float wipe;
        /** 擦除块扫过的方向，弧度；与镜头流向一致。 */
        public float wipeAngle;

        public TemperaTransitionFrame() {}

        public TemperaTransitionFrame(float x, float y, float scale, float rotation,
                                      float alpha, float blur, float wipe, float wipeAngle) {
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.rotation = rotation;
            this.alpha = alpha;
            this.blur = blur;
            this.wipe = wipe;
            this.wipeAngle = wipeAngle;
        }
    }

    /** 静止（无过渡）帧：构图原样停在画面里。 */
    public static final TemperaTransitionFrame IDLE_TEMPERA_TRANSITION_FRAME =
            new TemperaTransitionFrame(0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f);

    /** 返回一帧与静止帧等值的全新副本，供各分支覆盖字段，避免改动共享的静止常量。 */
    private static TemperaTransitionFrame idleCopy() {
        return new TemperaTransitionFrame(0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f);
    }

    /**
     * 解析边界的一侧。exit 让出构图沿流向继续前行；enter 让入构图从上游出发并沿同一向量抵达，
     * 因此整段交换过程中屏幕上的运动方向从不改变。
     */
    public static TemperaTransitionFrame resolveTemperaTransitionEffectFrame(
            String kind, String phase, double progress, float flowAngle) {
        float linear = TemperaMotionEasing.clamp01(progress);
        float eased = TemperaMotionEasing.easeInOut(linear);
        float flowX = (float) Math.cos(flowAngle);
        float flowY = (float) Math.sin(flowAngle);
        boolean exit = "exit".equals(phase);

        if ("block-wipe".equals(kind)) {
            // 场景保持完全不透明且原位不动；一块满屏的块沿流向向量滑过，交换在完全覆盖下发生。
            // 扫过是 0..2 的一次连续行程：0..1 把块带进来，1..2 把它带出远端，因此块在边界
            // 中途从不反向。
            TemperaTransitionFrame f = idleCopy();
            f.wipe = exit ? eased : 1f + eased;
            f.wipeAngle = flowAngle;
            return f;
        }

        if ("camera-pan".equals(kind)) {
            float travel = 0.5f;
            // alpha 一直保持到构图几乎离帧，因此滑动中途永远不会跌到空屏。
            float offset = exit ? eased * travel : -(1f - eased) * travel;
            float alpha = exit
                    ? 1f - TemperaMotionEasing.clamp01((linear - 0.72f) / 0.28f)
                    : TemperaMotionEasing.clamp01(linear / 0.3f);
            TemperaTransitionFrame f = idleCopy();
            f.x = flowX * offset;
            f.y = flowY * offset;
            f.scale = 1f + (exit ? eased : 1f - eased) * 0.03f;
            f.alpha = alpha;
            f.wipeAngle = flowAngle;
            return f;
        }

        // shape-carry：构图在流向向量上持续漂移，同时膨胀并柔化，仿佛下一幅图形正把它拉出焦外。
        // 没有硬切，没有故障。
        float drift = (exit ? eased : eased - 1f) * 0.09f;
        float away = exit ? eased : 1f - eased;
        TemperaTransitionFrame f = idleCopy();
        f.x = flowX * drift;
        f.y = flowY * drift;
        f.scale = 1f + away * 0.07f;
        f.alpha = exit
                ? 1f - TemperaMotionEasing.clamp01((linear - 0.55f) / 0.45f)
                : TemperaMotionEasing.clamp01(linear / 0.45f);
        f.blur = away * 6f;
        f.wipeAngle = flowAngle;
        return f;
    }

    public static TemperaTransitionFrame resolveTemperaExitTransitionFrame(
            TemperaTypes.Paragraph paragraph, double time, boolean enabled) {
        TemperaTypes.Transition transition = paragraph.transitionOut;
        if (!enabled || transition == null || time < transition.startTime) {
            return IDLE_TEMPERA_TRANSITION_FRAME;
        }
        double progress = (time - transition.startTime)
                / Math.max(transition.endTime - transition.startTime, 0.001);
        List<TemperaTypes.Shot> shots = paragraph.shots;
        float flowAngle = 0f;
        if (shots != null && !shots.isEmpty()) {
            // 取最后一个镜头的流向角；不存在时回退为 0（与 folia 的 ?.flowAngle ?? 0 一致）。
            flowAngle = shots.get(shots.size() - 1).flowAngle;
        }
        return resolveTemperaTransitionEffectFrame(transition.kind, "exit", progress, flowAngle);
    }

    public static TemperaTransitionFrame resolveTemperaEnterTransitionFrame(
            String kind, double timeSinceStart, double duration, boolean enabled, float flowAngle) {
        if (!enabled || kind == null || timeSinceStart < 0 || timeSinceStart > duration) {
            return IDLE_TEMPERA_TRANSITION_FRAME;
        }
        return resolveTemperaTransitionEffectFrame(
                kind, "enter", timeSinceStart / Math.max(duration, 0.001), flowAngle);
    }

    private TemperaTransitions() {}
}
