package dev.t1m3.qplayer.audio.mix;

/**
 * 过渡中各 deck 的变调安排（半音）。
 *
 * <p>两侧分开给，是因为两条滑轨的方向天然相反：
 *
 * <ul>
 *   <li><strong>出场侧</strong>：从零滑到全量。它在窗口结束时会被退役，所以
 *       <strong>不必滑回来</strong>，也不会把整首歌留在转调后的调上</li>
 *   <li><strong>进场侧</strong>：从全量滑回零。它要接着播下去，结束时必须回到
 *       自己的原调</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::transition::PitchPlan}。
 */
public final class PitchPlan {

    /** 两侧都是零——不需要变调。 */
    public static final PitchPlan ZERO = new PitchPlan(0f, 0f);

    /** 出场 deck 的变调量（半音）。运行时按 {@link Transition#pitchAt} 的曲线滑入。 */
    public final float outro;

    /** 进场 deck 的变调量（半音）。 */
    public final float intro;

    public PitchPlan(float outro, float intro) {
        this.outro = outro;
        this.intro = intro;
    }

    /** 两侧都是零——不需要变调。 */
    public boolean isZero() {
        return Math.abs(outro) < 1e-6f && Math.abs(intro) < 1e-6f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PitchPlan)) return false;
        PitchPlan p = (PitchPlan) o;
        return Float.compare(p.outro, outro) == 0 && Float.compare(p.intro, intro) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Float.floatToIntBits(outro) + Float.floatToIntBits(intro);
    }

    @Override
    public String toString() {
        return "PitchPlan{outro=" + outro + ", intro=" + intro + '}';
    }
}
