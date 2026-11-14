package dev.t1m3.qplayer.lyric.skia;

/**
 * Vertical motion of the lyric column: one closed-form spring per line.
 *
 * <p>The renderer computes a discrete layout every frame (line tops plus the scroll
 * offset the layout should rest at) and this class only moves lines toward it. A
 * layout change is therefore always ONE spring motion per line, never a scroll
 * spring racing a separately timed height ramp, which is what made lines rise and
 * then sink back.
 *
 * <ul>
 * <li>When the focused line advances, lines on the leading side retarget at once
 *     and the trailing side follows with a shrinking per-line delay (Apple Music's
 *     cascade). The delay holds each line's previous target while its spring keeps
 *     running, so a line already in motion never freezes.</li>
 * <li>Discontinuities (explicit seeks, render resumes, jumps over many lines)
 *     re-seed the window exactly as it is drawn and glide it rigidly with a stiff
 *     spring, so lines revealed by the jump travel with the rest.</li>
 * <li>While the user drives the offset, its changes shift every line rigidly
 *     (1:1 with the finger) and layout changes still animate underneath.</li>
 * </ul>
 */
final class LyricColumnMotion {

    /** Line following: k=65 with one gentle overshoot (ζ≈0.68). */
    private static final LyricSpring FLOW = new LyricSpring(65.0, 11.0);
    /** Spring physics off: same speed, no overshoot, no cascade. */
    private static final LyricSpring FIRM = LyricSpring.critical(65.0);
    /** Discontinuities: a slightly overdamped, quick rigid glide. */
    private static final LyricSpring GLIDE = new LyricSpring(180.0, 28.0);
    private static final double LINE_DELAY_S = 0.05;
    private static final double LINE_DELAY_DECAY = 1.05;
    /** The followed line moving further than this is a jump, not a line change. */
    static final int JUMP_LINES = 6;

    private float[] top = new float[0];
    private float[] velocity = new float[0];
    private float[] target = new float[0];
    private double[] delay = new double[0];
    private boolean initialized;
    private int windowStart;
    private int windowEnd;
    private int anchor = Integer.MIN_VALUE;
    private long anchorChangeNs;
    private long lastNs;
    private boolean jumpPending;
    private boolean gliding;
    private float renderedOffset;
    private float lastView;
    private boolean lastUserDriven;

    void reset() {
        initialized = false;
        anchor = Integer.MIN_VALUE;
        jumpPending = false;
        gliding = false;
        lastUserDriven = false;
    }

    /** Glide the whole column rigidly to wherever the next layout puts it. */
    void jumpOnNextStep() {
        jumpPending = true;
    }

    /** Drawn top of line {@code i}; valid for lines in the last stepped window. */
    float top(int i) {
        return top[i];
    }

    /** Scroll offset the column is drawn at, measured on the anchor line. */
    float renderedOffset() {
        return renderedOffset;
    }

    /**
     * @param view       offset the layout rests at this frame: the automatic target,
     *                   or the user's offset while {@code userDriven}
     * @param anchorLine line the view is focused on; a change starts the cascade
     */
    void step(long nowNs, float centerY, float[] lineTops, int n, int start, int end,
              float view, boolean userDriven, int anchorLine, boolean springOn) {
        if (top.length != n) {
            top = new float[n];
            velocity = new float[n];
            target = new float[n];
            delay = new double[n];
            initialized = false;
        }
        if (!initialized) {
            for (int i = start; i < end; i++) {
                top[i] = target[i] = centerY + lineTops[i] - view;
                velocity[i] = 0f;
            }
            initialized = true;
            jumpPending = false;
            gliding = false;
            anchor = anchorLine;
            finish(nowNs, centerY, lineTops, start, end, view, userDriven, anchorLine);
            return;
        }
        double dt = Math.min(0.05, Math.max(0.0, (nowNs - lastNs) / 1_000_000_000.0));

        boolean jumped = Math.abs(anchorLine - anchor) > JUMP_LINES;
        if ((jumpPending || jumped) && !userDriven) {
            for (int i = start; i < end; i++) {
                top[i] = centerY + lineTops[i] - renderedOffset;
                velocity[i] = 0f;
            }
            gliding = true;
            anchor = anchorLine;
        } else if (anchorLine != anchor) {
            scheduleCascade(start, end, anchorLine, anchorLine > anchor);
            anchor = anchorLine;
            anchorChangeNs = nowNs;
        }
        jumpPending = false;

        if (userDriven && lastUserDriven && view != lastView) {
            float shift = view - lastView;
            for (int i = Math.max(start, windowStart); i < Math.min(end, windowEnd); i++) {
                top[i] -= shift;
                target[i] -= shift;
            }
        }

        LyricSpring spring = gliding ? GLIDE : springOn ? FLOW : FIRM;
        boolean cascade = springOn && !gliding && !userDriven;
        double sinceAnchorChange = (nowNs - anchorChangeNs) / 1_000_000_000.0;
        boolean settled = true;
        for (int i = start; i < end; i++) {
            float rest = centerY + lineTops[i] - view;
            if (i < windowStart || i >= windowEnd) {
                // Joins the window at the window's edge, far off screen: place it
                // with the column as drawn so it arrives with its neighbours.
                top[i] = centerY + lineTops[i] - renderedOffset;
                velocity[i] = 0f;
                target[i] = rest;
            } else if (!cascade || sinceAnchorChange >= delay[i]) {
                target[i] = rest;
            }
            spring.step(top, velocity, i, target[i], dt);
            if (Math.abs(top[i] - rest) > 0.5f || Math.abs(velocity[i]) > 5f) settled = false;
        }
        if (gliding && settled) gliding = false;
        finish(nowNs, centerY, lineTops, start, end, view, userDriven, anchorLine);
    }

    /** Leading side (toward travel) moves with the anchor; the trailing side follows. */
    private void scheduleCascade(int start, int end, int anchorLine, boolean advancing) {
        for (int i = start; i < end; i++) delay[i] = 0.0;
        double accumulated = 0.0;
        double step = LINE_DELAY_S;
        if (advancing) {
            for (int i = Math.max(start, anchorLine + 1); i < end; i++) {
                accumulated += step;
                step /= LINE_DELAY_DECAY;
                delay[i] = accumulated;
            }
        } else {
            for (int i = Math.min(end - 1, anchorLine - 1); i >= start; i--) {
                accumulated += step;
                step /= LINE_DELAY_DECAY;
                delay[i] = accumulated;
            }
        }
    }

    private void finish(long nowNs, float centerY, float[] lineTops, int start, int end,
                        float view, boolean userDriven, int anchorLine) {
        lastNs = nowNs;
        windowStart = start;
        windowEnd = end;
        lastView = view;
        lastUserDriven = userDriven;
        int reference = Math.max(start, Math.min(end - 1, anchorLine));
        renderedOffset = end > start ? centerY + lineTops[reference] - top[reference] : view;
    }
}
