package dev.t1m3.qplayer.audio.mix;

/**
 * <b>规则教师（Java 侧）</b>：确定性的 EQ 曲线生成器，也是 MLP 的<b>兜底</b>。
 *
 * <p>规格（第二阶段·训练流程第四步）：「不收敛时<b>退回用规则教师直接生成 EQ 曲线</b>，
 * 不经过 MLP」。蒸馏的结果是——本机 874 对真实样本上最佳验证 MSE <b>0.048</b>（阈值 0.01），
 * 判定不收敛，所以**生产路径默认走这里**；MLP 那条路保留（{@code MixEqMlp}），
 * 等数据规模上来（规格目标 2000 对）再打开。
 *
 * <p>与 Python {@code rule_teacher.py} 必须同一条曲线（判据 {@code RuleTeacherVectorTest}
 * 用固定向量钉 1e-4）。三条锁死的性质：
 * <ul>
 *   <li><b>考虑窗口时长</b>：bass cut 用**绝对秒数**（{@value #CUT_DURATION_SECS} s），
 *       {@code cut_duration_p = 2.0 / max(window_secs, 1e-3)}；</li>
 *   <li><b>用 drop 位置锚定拐点</b>：{@code drop_p} 是窗口内 (A+B) 能量最大帧的位置；</li>
 *   <li><b>连续可微、不用硬阈值</b>：门控是 sigmoid（k = {@value #GATE_K}，不超过 50）。</li>
 * </ul>
 */
public final class RuleTeacherEq {

    /** bass cut 的**绝对**时长（秒）——4 秒窗口占 50%、16 秒窗口占 12.5%。 */
    public static final double CUT_DURATION_SECS = 2.0;

    /** sigmoid 门控斜率（规格：20~40，不超过 50 避免梯度消失）。 */
    static final double GATE_K = 30.0;

    private static final double EPS = 1e-6;

    private RuleTeacherEq() {
    }

    /** 曲风连续分数 → {threshold, cut_target}。不硬分类，直接加权。 */
    static double[] thresholds(double[] genres) {
        double edm = genres[0];
        double hiphop = genres[1];
        double dnb = genres[2];
        double rock = genres[3];
        double def = genres[4];
        double threshold = edm * 0.85 + hiphop * 0.75 + dnb * 0.8 + rock * 0.6 + def * 0.7;
        double cutTarget = edm * 0.30 + hiphop * 0.35 + dnb * 0.30 + rock * 0.40 + def * 0.40;
        return new double[] {threshold, cutTarget};
    }

    /**
     * 逐帧目标曲线：{@code [frames][3]}（低/中/高）。
     *
     * <p>中高频默认 1.0（0 dB），只在频谱冲突大时压低；低频按拐点分段（规格第三十五条）：
     * <pre>
     *   p &lt; cut_start_p          → 1.0
     *   cut_start_p ≤ p &lt; drop_p → 1.0 − progress · gate · (1 − cut_target)
     *   p ≥ drop_p               → cut_target + (1 − cut_target) · min(1, (p − drop_p) · 4)
     * </pre>
     *
     * @param features 逐帧 36 维特征（只用第 1/4 维的低频能量、第 9 维进度、第 30 维 drop 位置、
     *                 第 32–36 维曲风分数——与 Python 侧同一批维度）
     * @param windowSecs 这一次的实际窗口（秒）
     */
    public static void curves(float[][] features, int frames, double windowSecs, float[][] out) {
        double dropP = clamp(features[0][29], 0.0, 1.0);
        double[] genres = new double[5];
        for (int k = 0; k < 5; k++) {
            genres[k] = features[0][31 + k];
        }
        double[] th = thresholds(genres);
        double cutDurationP = CUT_DURATION_SECS / Math.max(windowSecs, 1e-3);
        double cutStartP = Math.max(0.0, dropP - cutDurationP);

        for (int f = 0; f < frames; f++) {
            double conflict = features[f][0] * features[f][3];        // lowA × lowB
            double gate = sigmoid((conflict - th[0]) * GATE_K);
            double p = clamp(features[f][8], 0.0, 1.0);
            double progress = (p - cutStartP) / Math.max(EPS, dropP - cutStartP);
            double low;
            if (p < cutStartP) {
                low = 1.0;
            } else if (p < dropP) {
                low = 1.0 - progress * gate * (1.0 - th[1]);
            } else {
                low = th[1] + (1.0 - th[1]) * Math.min(1.0, (p - dropP) * 4.0);
            }
            out[f][0] = (float) clamp(low, th[1], 1.0);
            out[f][1] = 1.0f;
            out[f][2] = 1.0f;
        }
    }

    /**
     * 教师曲线 → <b>12 个控制点</b>（给 {@link EqCurve} 用）。
     *
     * <p>控制点取 p = 0 / 0.33 / 0.66 / 1.0 四处的曲线值（与 Python 侧同一个位置）；
     * 中高频恒 1.0 ⇒ 控制点也是 1.0（0 dB，不动它们）。
     */
    public static double[] controlsFrom(float[][] features, int frames, double windowSecs) {
        float[][] curves = new float[frames][3];
        curves(features, frames, windowSecs, curves);
        double[] controls = new double[EqCurve.CONTROLS];
        double[] controlP = EqCurve.CONTROL_P;
        for (int band = 0; band < EqCurve.BANDS; band++) {
            for (int i = 0; i < EqCurve.PER_BAND; i++) {
                int frame = (int) Math.round(controlP[i] * Math.max(0, frames - 1));
                controls[band * EqCurve.PER_BAND + i] = curves[frame][band];
            }
            // **中高频一律 1.0（0 dB）**：规格里教师的中高频**恒为 1.0**——「中高频目标曲线默认
            // 1.0，对应 0 dB。低频目标曲线默认 1.0，对应 0 dB。**只在频谱冲突大时压低**」。
            // 而蒸馏出来的 MLP 会把它们预测成 0.95~0.99（拟合噪声，−0.1~−0.5 dB），
            // 某一帧甚至到 0.769（**−2.28 dB**）——那点染色不是设计意图，是噪声，
            // 所以运行时把这两个频段钉死回 0 dB（低频才是有话要说的那一段）。
            if (band > 0) {
                for (int i = 0; i < EqCurve.PER_BAND; i++) {
                    controls[band * EqCurve.PER_BAND + i] = 1.0;
                }
                continue;
            }
            // **最后一个控制点强制回到 1.0**（0 dB）：规格的时间轴是"窗口结束时 A 停、B 继续"
            // ——也就是说窗口收尾那一刻 B 必须是**干净**的。
            //
            // 教师曲线本身不保证这一点：`p ≥ drop_p` 那一段只按 `min(1, (p−drop_p)·4)` 恢复，
            // drop 落在窗口后段时（真实素材上很常见）到 p=1 只回到一半 ⇒ 过渡之后 B 一直被染色。
            // 实测：渲染出来的学习EQ 每秒 RMS 从 0.09 掉到 0.027 起不来（低 11 dB），
            // 而另两档全程 0.09。这不是"更安静"，是**收尾没还回去**。
            controls[band * EqCurve.PER_BAND + EqCurve.PER_BAND - 1] = 1.0;
        }
        return controls;
    }

    static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-60.0, Math.min(60.0, x))));
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
