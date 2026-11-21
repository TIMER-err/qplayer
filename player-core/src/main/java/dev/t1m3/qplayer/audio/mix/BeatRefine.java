package dev.t1m3.qplayer.audio.mix;

/**
 * <b>把拍点从"包络栅格"细化到"样本级"</b>（2026-10-07）。
 *
 * <p>为什么必须做：用户规格要求「每个乐句和节拍**帧级**对应」，而拍点是节拍跟踪器在
 * <b>包络</b>上给的 —— 栅格是 {@code AutoCue.HOP_SECS}（50 Hz ⇒ **20 ms**）。
 * 实测参考对 skydive→春めく 的接缝拍相位差还有 <b>633.9 帧 ≈ 13.2 ms</b>
 * （探针 {@code BeatAlignProbe}）—— 那不是参数没调好，是**栅格的精度上限**：
 * 在这一层再怎么吸附都到不了 0 ✗。
 *
 * <p>做法：把包络给的拍时刻当作"近似值"，在它两侧 ±{@value #SEARCH_SECS} s 内
 * 找该拍的**起音峰**（短窗能量的正向差分的最大值），把拍点定到样本级。
 * 找不到明显起音时**原样返回**（不猜 —— 沿用本仓库那条纪律）。
 *
 * <p>它只负责"细化"：不改变谁跟谁对齐、也不改点位选择，那些仍由 {@code AutoCue} 决定。
 */
public final class BeatRefine {

    /** 在近似拍点两侧各搜多少秒（要盖住半个包络栅格 20 ms，留一倍余量）。 */
    public static final double SEARCH_SECS = 0.04;

    /** 起音检测的能量窗（约 2 ms，够短以定位起音沿，又够长以抗噪）。 */
    static final double WIN_SECS = 0.002;

    /** 明显程度门槛：峰值得比搜索窗内的均值高这么多倍，否则算"没找到"。 */
    static final double PEAK_RATIO = 1.5;

    /**
     * <b>孤立性</b>门槛：把主峰两侧各一个窗挖掉之后，剩下的最大值必须低于主峰的这个比例。
     *
     * <p>为什么需要它：纯噪声里"最大的一处能量增长"天然就是均值的几倍 ✗，
     * 光看"峰/均"比值会把噪声认成起音（实测：平坦噪声上被判成 19283 ✗）。
     * 真起音是**孤立**的 —— 把主峰附近挖掉之后，别处不该有差不多的峰。
     */
    static final double ISOLATION_RATIO = 0.6;

    private BeatRefine() {
    }

    /**
     * 细化一个拍点。
     *
     * @param mono       单声道 PCM（窗口内即可，但下标要与 {@code approxFrame} 同一坐标系）
     * @param rate       采样率
     * @param approxFrame 包络给出的近似拍点（样本下标）
     * @return 细化后的样本下标；没有明显起音时<b>原样返回</b> {@code approxFrame}
     */
    public static int refineFrame(float[] mono, int rate, int approxFrame) {
        if (mono == null || rate <= 0 || mono.length == 0) {
            return approxFrame;
        }
        int win = Math.max(1, (int) Math.round(rate * WIN_SECS));
        int half = Math.max(win, (int) Math.round(rate * SEARCH_SECS));
        int from = Math.max(win, approxFrame - half);
        int to = Math.min(mono.length - 1, approxFrame + half);
        if (to - from < win * 2) {
            return approxFrame;
        }
        // 起音强度：当前短窗能量相对上一短窗的**正向**增长
        int best = -1;
        double bestVal = 0.0;
        double sum = 0.0;
        int n = 0;
        for (int at = from; at <= to; at++) {
            double now = energy(mono, at - win, at);
            double prev = energy(mono, at - 2 * win, at - win);
            double onset = Math.max(0.0, now - prev);
            if (onset > bestVal) {
                bestVal = onset;
                best = at;
            }
            sum += onset;
            n++;
        }
        if (best < 0 || n == 0) {
            return approxFrame;
        }
        double mean = sum / n;
        if (mean <= 0.0 || bestVal < mean * PEAK_RATIO) {
            return approxFrame;                    // 不明显 ⇒ 不猜
        }
        // 孤立性：主峰两侧各挖一个窗，剩下的峰必须明显更低（噪声不满足这一条）
        double runnerUp = 0.0;
        for (int at = from; at <= to; at++) {
            if (Math.abs(at - best) <= win) {
                continue;
            }
            double now = energy(mono, at - win, at);
            double prev = energy(mono, at - 2 * win, at - win);
            runnerUp = Math.max(runnerUp, Math.max(0.0, now - prev));
        }
        if (runnerUp > bestVal * ISOLATION_RATIO) {
            return approxFrame;                    // 不孤立 ⇒ 不猜
        }
        // **返回起音沿**：能量窗覆盖到起音的那一刻是 `best`，窗宽是 `win`，
        // 所以起音本身在 `best − win`。第一版直接返回 `best` ⇒ 偏约 96 帧（实测踩到）。
        //
        // 再往前走到"增长刚起来"的那一点：脉冲有自己的起音沿形状，直接取
        // `best − win` 实测还偏 14 帧（0.29 ms）。从峰值回退到 `onset ≥ 25%·峰值`
        // 的第一处，落点才贴着真正的起音沿。
        int leading = best;
        for (int at = best; at >= from; at--) {
            double now = energy(mono, at - win, at);
            double prev = energy(mono, at - 2 * win, at - win);
            if (Math.max(0.0, now - prev) < bestVal * 0.25) {
                break;
            }
            leading = at;
        }
        return leading - win;
    }

    /** {@code [from, to)} 的均方能量（越界按 0 处理）。 */
    private static double energy(float[] pcm, int from, int to) {
        int a = Math.max(0, from);
        int b = Math.min(pcm.length, to);
        if (b <= a) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = a; i < b; i++) {
            sum += (double) pcm[i] * pcm[i];
        }
        return sum / (b - a);
    }
}
