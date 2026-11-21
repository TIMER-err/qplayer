package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.mix.PathState;

/**
 * 「控制室」要显示的那一份快照（P4 的<b>看</b>那一半）。
 *
 * <h2>为什么是快照而不是让界面去问引擎</h2>
 *
 * <p>过渡的每一个读数（接法名、窗口、融合点在哪、走到哪儿了）都住在生产者线程独占的对象里
 * （{@code Director} 的字段不是 volatile）。界面在另一条线程上按帧读它们就是数据竞争——
 * 这与 {@code SpectrumTap}（电平表）面对的是同一个问题，解法也一样：
 * <strong>生产者每块发布一份不可变快照，界面读那一份</strong>。
 *
 * <p>它同时是「读数与引擎一致」这条判据的落点：判据不是「界面显示得像」，而是
 * <strong>快照里的每个字段都能在引擎自己的 getter 上找到同一个值</strong>
 * （见 {@code TransitionSnapshotTest}）。
 *
 * <h2>字段都是「界面此刻要说的那句话」</h2>
 *
 * <p>与 {@code PlaybackEngine.publishSnapshot} 的分工一致：位置/时长报「界面现在说的那一首」，
 * 而 {@link #currentPath} 报「编排器手上那个 current deck 是谁」。两条在过渡窗口里
 * 会指向不同的曲子，这是有意的（那里两首都在出声）。
 */
public final class TransitionSnapshot {

    /** 什么都不在过渡时的空快照（界面拿到的初值）。 */
    public static final TransitionSnapshot IDLE = new TransitionSnapshot(
            false, "", "", 0.0, 0.0f, PathState.PROCESSED, "", "", 0L, 0L, "", "",
            Double.NaN);

    /** 现在在不在过渡窗口里。 */
    public final boolean inTransition;

    /** 接法的名字（含「· 自动点位 / · 手动点位」这些后缀）。 */
    public final String transitionName;

    /** 这一档的结论（就是界面/日志里那句实话）。 */
    public final String note;

    /** 这个接法的窗口长度（秒）。 */
    public final double windowSecs;

    /** 过渡走到了哪儿（0..1；不在窗口里时是 0）。 */
    public final float progress;

    /** 信号路径状态（直通/过渡中/经过处理）。 */
    public final PathState pathState;

    /** 编排器手上那个 current deck 的句柄。 */
    public final String currentPath;

    /** 正在进场的那个 deck 的句柄（没有时是空串）。 */
    public final String nextPath;

    /** 窗口内的位置（帧）。 */
    public final long currentFrames;

    /** 进场 deck 已经产出的帧数（没在过渡时是 0）。 */
    public final long nextFrames;

    /** 主总线的增益衰减读数（dB，正数 = 压了多少）——P5 的表头。 */
    public final String rackSummary;

    /** 一条给人看的总结（界面直接显示它就行，不必自己拼）。 */
    public final String summary;

    /**
     * 离这次过渡开始还有多少秒（{@code NaN} = 没有已装载的过渡，界面不许预告）。
     *
     * <p>经典歌词页的「过渡提示」（进度条已完成段发光）就是靠它提前三秒亮起来的：
     * {@link #inTransition} 只在过渡<b>开始那一刻</b>才翻，用那一位做提示等于没有提示
     * （用户 2026-09-30：「让进度条发光在 automix 开始前三秒就有」）。
     * 正数 = 还有几秒开始；{@code 0} 或负数 = 已经在过渡里；{@code NaN} = 不知道该不该预告。
     * 语义与来源见 {@code Director.transitionLeadInSecs()}。
     */
    public final double leadInSecs;

    public TransitionSnapshot(boolean inTransition, String transitionName, String note,
                              double windowSecs, float progress, PathState pathState,
                              String currentPath, String nextPath, long currentFrames,
                              long nextFrames, String rackSummary, String summary) {
        this(inTransition, transitionName, note, windowSecs, progress, pathState, currentPath,
                nextPath, currentFrames, nextFrames, rackSummary, summary, Double.NaN);
    }

    /** 全字段构造（{@code leadInSecs} 见它的字段注释；{@code NaN} = 不预告）。 */
    public TransitionSnapshot(boolean inTransition, String transitionName, String note,
                              double windowSecs, float progress, PathState pathState,
                              String currentPath, String nextPath, long currentFrames,
                              long nextFrames, String rackSummary, String summary,
                              double leadInSecs) {
        this.inTransition = inTransition;
        this.transitionName = transitionName == null ? "" : transitionName;
        this.note = note == null ? "" : note;
        this.windowSecs = windowSecs;
        this.progress = progress;
        this.pathState = pathState == null ? PathState.PROCESSED : pathState;
        this.currentPath = currentPath == null ? "" : currentPath;
        this.nextPath = nextPath == null ? "" : nextPath;
        this.currentFrames = currentFrames;
        this.nextFrames = nextFrames;
        this.rackSummary = rackSummary == null ? "" : rackSummary;
        this.summary = summary == null ? "" : summary;
        this.leadInSecs = leadInSecs;
    }

    @Override
    public String toString() {
        return "TransitionSnapshot{" + summary + "}";
    }
}
