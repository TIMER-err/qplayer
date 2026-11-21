package dev.t1m3.qplayer.audio.mix;

/**
 * <b>36 维逐帧特征提取</b>（第二阶段）：从两侧窗口的包络数据拼出 MLP 的输入。
 *
 * <p>规格锁死（第二阶段·实现细节 第二至五、二十七、二十八条）：
 * <ul>
 *   <li><b>复用 {@code AutoCue.Envelope} 的 band 数组</b>（HOP_SECS = 0.02 ⇒ 50 Hz），
 *       <b>不要新建提取器</b>；</li>
 *   <li>频谱质心优先复用 Envelope 的 STFT 结果；没有复数谱时用 ZCR 兜底；</li>
 *   <li>节拍强度用 onset 包络的<b>峰值因子</b>，不新增 FFT；</li>
 *   <li>全局特征（维度 30–36）对每一帧都是<b>同一个值</b>；</li>
 *   <li>所有除法都加 epsilon。</li>
 * </ul>
 *
 * <p>逐帧布局（与 Python 侧 {@code eq_features.build_features} 一一对应，判据
 * {@code EqFeatureVectorTest} 用固定向量钉着）：<pre>
 *   1–3   A 低/中/高 · 4–6 B 低/中/高 · 7–8 A/B 质心 · 9 进度 · 10 窗口长/8
 *   11    BPM 差 · 12 调性相似度 · 13 节拍强度 · 14 BPM/180 · 15–16 A/B 低频占比 · 17 A 通量
 *   18–23 A/B 前 5 帧变化量（低中高）· 24–29 A/B 后 5 帧变化量 · 30–36 全局
 * </pre>
 */
public final class EqFeatureExtractor {

    /** 输入维。 */
    public static final int DIM = 36;

    /** 前/后各看几帧（规格：前 5 帧统计 6 维、后 5 帧统计 6 维）。 */
    static final int LOOK = 5;

    private static final double EPS = 1e-6;

    private EqFeatureExtractor() {
    }

    /** 一侧窗口的特征源（来自 {@code AutoCue.Envelope} 的切片）。 */
    public static final class Side {

        /** {@code [frame][3]}：低/中/高能量。 */
        public final float[][] band;

        /** 逐帧包络（去均值后的 RMS）。 */
        public final float[] rms;

        /** 频谱质心（0..1，可空）；没有就用 {@link #zcr}，再没有就当 0。 */
        public final float[] centroid;

        /** 过零率的替身（0..1，可空）。 */
        public final float[] zcr;

        public Side(float[][] band, float[] rms, float[] centroid, float[] zcr) {
            this.band = band;
            this.rms = rms;
            this.centroid = centroid;
            this.zcr = zcr;
        }

        int frames() {
            return band == null ? 0 : band.length;
        }

        float bandAt(int frame, int k) {
            float[] row = band[frame];
            return k < row.length ? row[k] : 0f;
        }

        float centroidAt(int frame) {
            if (centroid != null && frame < centroid.length) {
                return clamp01(centroid[frame]);
            }
            if (zcr != null && frame < zcr.length) {
                return clamp01(zcr[frame]);
            }
            return 0f;                                   // 都没有：不做假设，给 0
        }

        float lowRatio(int frame) {
            float low = bandAt(frame, 0);
            float sum = low + bandAt(frame, 1) + bandAt(frame, 2);
            return (float) (low / Math.max(sum, EPS));
        }
    }

    /** 节拍强度：onset 包络的峰值因子 {@code (peak − mean) / (peak + mean)}。 */
    static double onsetStrength(Side side, int frames) {
        if (frames < 2) {
            return 0.0;
        }
        double peak = 0.0;
        double sum = 0.0;
        for (int f = 1; f < frames; f++) {
            double d = 0.0;
            for (int k = 0; k < 3; k++) {
                d += Math.max(0.0, side.bandAt(f, k) - side.bandAt(f - 1, k));
            }
            peak = Math.max(peak, d);
            sum += d;
        }
        double mean = sum / Math.max(1, frames - 1);
        return (peak - mean) / (peak + mean + EPS);
    }

    /** 曲风连续分数（规格：不用硬分类），五个数之和为 1。 */
    static double[] genreScores(double bpm, double onset, double lowRatio, double midRatio) {
        double edm = sigmoid((bpm - 115.0) / 5.0) * onset;
        double hiphop = sigmoid((90.0 - bpm) / 5.0) * onset * lowRatio;
        double dnb = sigmoid((bpm - 160.0) / 5.0) * onset;
        double rock = sigmoid((bpm - 105.0) / 8.0) * (1.0 - onset) * midRatio;
        double def = 1.0 - Math.max(Math.max(edm, hiphop), Math.max(dnb, rock));
        double[] s = {Math.max(0.0, edm), Math.max(0.0, hiphop), Math.max(0.0, dnb),
                Math.max(0.0, rock), Math.max(0.0, def)};
        double sum = s[0] + s[1] + s[2] + s[3] + s[4];
        for (int i = 0; i < 5; i++) {
            s[i] = s[i] / Math.max(sum, EPS);
        }
        return s;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-60.0, Math.min(60.0, x))));
    }

    /**
     * 拼出逐帧特征。
     *
     * @param a           A 的窗口切片
     * @param b           B 的窗口切片
     * @param windowSecs  这一次融合窗口（秒）——<b>来自上游选的窗口</b>，不要自己算
     * @param bpmA        A 的原始 BPM（**变速前**）
     * @param bpmB        B 的原始 BPM
     * @param keyMatch    调性相似度（0..1，未知给 0）
     * @param out         形状 {@code [frames][36]} 的输出缓冲（会被写满）
     * @return 实际写了几帧（两侧较短者）
     */
    public static int extract(Side a, Side b, double windowSecs, double bpmA, double bpmB,
                              double keyMatch, float[][] out) {
        int frames = Math.min(Math.min(a.frames(), b.frames()), out.length);
        if (frames <= 0) {
            return 0;
        }
        double bpmGap = Math.abs(bpmA - bpmB) / Math.max((bpmA + bpmB) / 2.0, 1e-3);
        double winNorm = windowSecs / 8.0;
        double bpmAbs = bpmA / 180.0;
        double onset = onsetStrength(a, frames);

        double lowSum = 0.0;
        double midSum = 0.0;
        double energyPeak = 0.0;
        double energySum = 0.0;
        int peakFrame = 0;
        for (int f = 0; f < frames; f++) {
            lowSum += a.lowRatio(f);
            midSum += a.bandAt(f, 1) / Math.max(a.bandAt(f, 0) + a.bandAt(f, 1) + a.bandAt(f, 2), EPS);
            double energy = a.bandAt(f, 0) + a.bandAt(f, 1) + a.bandAt(f, 2)
                    + b.bandAt(f, 0) + b.bandAt(f, 1) + b.bandAt(f, 2);
            energySum += energy;
            if (energy > energyPeak) {
                energyPeak = energy;
                peakFrame = f;
            }
        }
        double lowRatioMean = lowSum / frames;
        double midRatioMean = midSum / frames;
        double[] genres = genreScores(bpmA, onset, lowRatioMean, midRatioMean);
        double peakPos = peakFrame / (double) Math.max(1, frames);
        double peakAmp = Math.min(5.0, energyPeak / Math.max(energySum / frames, 1e-3));

        for (int f = 0; f < frames; f++) {
            float[] row = out[f];
            int past = Math.max(0, f - LOOK);
            int future = Math.min(frames - 1, f + LOOK);
            row[0] = a.bandAt(f, 0);
            row[1] = a.bandAt(f, 1);
            row[2] = a.bandAt(f, 2);
            row[3] = b.bandAt(f, 0);
            row[4] = b.bandAt(f, 1);
            row[5] = b.bandAt(f, 2);
            row[6] = a.centroidAt(f);
            row[7] = b.centroidAt(f);
            row[8] = (float) (f / (double) Math.max(1, frames));   // 进度 p
            row[9] = (float) winNorm;
            row[10] = (float) bpmGap;
            row[11] = (float) keyMatch;
            row[12] = (float) onset;
            row[13] = (float) bpmAbs;
            row[14] = a.lowRatio(f);
            row[15] = b.lowRatio(f);
            row[16] = flux(a, f);
            for (int k = 0; k < 3; k++) {
                row[17 + k] = a.bandAt(f, k) - a.bandAt(past, k);
                row[20 + k] = b.bandAt(f, k) - b.bandAt(past, k);
                row[23 + k] = a.bandAt(future, k) - a.bandAt(f, k);
                row[26 + k] = b.bandAt(future, k) - b.bandAt(f, k);
            }
            row[29] = (float) peakPos;                  // 全局：对每一帧都是同一个值
            row[30] = (float) peakAmp;
            for (int k = 0; k < 5; k++) {
                row[31 + k] = (float) genres[k];
            }
        }
        return frames;
    }

    /**
     * <b>频谱质心的替身</b>（规格第四条：Envelope 没存复数谱时改用 ZCR）。
     *
     * <p>这一层拿不到 {@code AutoCue.Envelope} 的 STFT 复数谱，所以在"包络的逐帧差分"
     * 上取一个单调映射当替身：{@code clamp(0.5 + 4·(rms[f] − rms[f−1]), 0, 1)}。
     *
     * <p><b>训练数据必须用同一把尺子</b>：数据导出器（{@code MixEqDatasetExporter}）与运行时
     * 都调这个函数——两边各写一份的话，训练分布与推理分布就对不上了（那是这一类模型最常见、
     * 也最难查的失效）。
     */
    public static float[] centroidStandIn(float[] rms, int from, int frames) {
        float[] out = new float[Math.max(0, frames)];
        for (int f = 0; f < out.length; f++) {
            float cur = rms[from + f];
            float prev = rms[Math.max(0, from + f - 1)];
            out[f] = Math.max(0f, Math.min(1f, (cur - prev) * 4f + 0.5f));
        }
        return out;
    }

    /** 频谱通量：当前帧 band 减前一帧的正部分之和。 */
    private static float flux(Side side, int frame) {
        if (frame == 0) {
            return 0f;
        }
        float sum = 0f;
        for (int k = 0; k < 3; k++) {
            sum += Math.max(0f, side.bandAt(frame, k) - side.bandAt(frame - 1, k));
        }
        return sum;
    }

    /** 见 {@link #extract} 的第 17 维（单独提出来只为了让那一行的类型一目了然）。 */

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
