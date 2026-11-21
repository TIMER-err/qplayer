package dev.t1m3.qplayer.audio.mix;

import java.util.Map;

/**
 * <b>"处理过的窗口素材"</b>（2026-10-07，目标②路径 B 的第一步）。
 *
 * <p>朴素融合（交叉淡化）那一对的音频是 {@code AiMix.render} 直接拿两台 deck 的缓冲做的，
 * 所以去人声与响度对齐**必须在 render 拿到的素材上做** —— 这个类负责把"原始窗口素材"
 * 变成"该给 render 的那一份"。三件事，顺序有讲究：
 *
 * <ol>
 *   <li><b>分轨</b>：按叠唱区间用 {@link StemMaterial#mixWith} 把**人声轨**按
 *       {@code vocalGainAt} 的曲线压下去（绝不动伴奏 —— 那是"突然静音"的教训）；</li>
 *   <li><b>响度对齐</b>：整段量一次、整段补一次（<b>不是</b>逐块 —— 逐块会让段边界跳变，
 *       那是用户实测的"响度突变"）；</li>
 *   <li><b>算不出来就原样返回</b>：没分轨 / 静音 / 档位不支持 ⇒ 返回**输入本身** ⇒
 *       与今天逐位相同（这条是"安全默认"的性质，判据在验它）。</li>
 * </ol>
 *
 * <p>本类不碰渲染、不碰播放引擎；{@code AiMix.render} 开头把它们换进来是下一步。
 */
public final class WindowMaterial {

    private WindowMaterial() {
    }

    /**
     * 处理一段窗口素材。
     *
     * @param path        素材文件
     * @param headers     头信息（可为 null）
     * @param startSecs   窗口在源里的起点（秒）
     * @param frames      帧数
     * @param rate        采样率
     * @param vocalMode   分离档位（0 = 不去人声 ⇒ 不分离）
     * @param duckRanges  叠唱区间（窗口时间轴秒数，{@code {s0,e0,…}}；null/空 ⇒ 不压人声）
     * @param anchorSecs  窗口时间轴的原点在这个素材里的秒数（用来把帧号换成窗口时间）
     * @param targetLufs  目标响度；{@link Double#NaN} ⇒ 不做响度对齐
     * @return 处理后的素材；任何一步算不出来 ⇒ **原样返回读到的素材**（不猜、不半途而废）
     */
    public static float[][] process(String path, Map<String, String> headers, double startSecs,
                                    int frames, int rate, int vocalMode, double[] duckRanges,
                                    double anchorSecs, double targetLufs) {
        float[][] material;
        try {
            material = Automix.readStereoWindow(path, headers, startSecs, frames, rate);
        } catch (RuntimeException e) {
            // 没装解码器 / 文件读不了 —— 与仓库别处一样：**如实返回 null**，
            // 调用方据此走原路（不猜、也不抛到渲染里）
            return null;
        }
        if (material == null || material.length == 0 || frames <= 0 || rate <= 0) {
            return material;
        }
        float[][] result = material;
        // ① 去人声（只在叠唱区间、只压人声轨）
        if (vocalMode != 0 && duckRanges != null && duckRanges.length >= 2) {
            StemMaterial stems = StemMaterials.forWindow(path, headers, startSecs, frames, rate,
                    vocalMode);
            if (stems != null && stems.hasStems() && stems.frames() >= frames) {
                float[] gain = new float[frames];
                for (int i = 0; i < frames; i++) {
                    double timeline = (startSecs - anchorSecs) + i / (double) rate;
                    gain[i] = (float) duckGainAt(duckRanges, timeline);
                }
                result = stems.mixWith(gain);
            }
        }
        // ② 响度对齐：整段一次（不逐块）
        if (!Double.isNaN(targetLufs)) {
            alignLoudness(result, rate, targetLufs);
        }
        return result;
    }

    /**
     * 窗口时间轴上某点的人声增益（与 {@code AutoCueTransition.vocalGainAt} 同一套语义：
     * 区间外 1、区间内 0、两端各留 50 ms 余弦渐变）。
     *
     * <p>这里重写一份而不是复用，是因为那个方法是实例方法、依赖渲染侧的字段；
     * 语义由 {@code VocalWindowReconstructionTest} 那套量尺守着（区间外逐位不动）。
     */
    static double duckGainAt(double[] ranges, double timelineSecs) {
        if (ranges == null || ranges.length < 2) {
            return 1.0;              // 没有叠唱区间 ⇒ 原样（判据在钉这条契约）
        }
        double gain = 1.0;
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            double a = ranges[i];
            double b = ranges[i + 1];
            if (b <= a || timelineSecs <= a || timelineSecs >= b) {
                continue;
            }
            double edge = 0.05;
            double in = Math.min(1.0, (timelineSecs - a) / edge);
            double out = Math.min(1.0, (b - timelineSecs) / edge);
            double depth = 0.5 - 0.5 * Math.cos(Math.PI * Math.min(in, out));
            gain = Math.min(gain, 1.0 - depth);
        }
        return Math.max(0.0, Math.min(1.0, gain));
    }

    /**
     * 整段响度对齐（就地）。量不出来（静音）⇒ 不动；增益仍受 ±12 dB 夹紧。
     *
     * @return 实际施加的增益（1.0 = 没动）
     */
    static double alignLoudness(float[][] material, int rate, double targetLufs) {
        if (Double.isNaN(targetLufs)) {
            return 1.0;          // **先挡 NaN**：不然 Math.min(12, NaN) = NaN ⇒ 整段被乘成 NaN ✗
        }
        double sum = 0.0;
        int n = 0;
        for (float[] ch : material) {
            if (ch == null || ch.length == 0) {
                continue;
            }
            double lufs = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(ch, null, rate);
            if (!Double.isInfinite(lufs) && !Double.isNaN(lufs)) {
                sum += lufs;
                n++;
            }
        }
        if (n == 0) {
            return 1.0;
        }
        double current = sum / n;
        double db = Math.max(-dev.t1m3.qplayer.audio.dsp.Lufs.MAX_NORMALIZE_DB,
                Math.min(dev.t1m3.qplayer.audio.dsp.Lufs.MAX_NORMALIZE_DB, targetLufs - current));
        double gain = Math.pow(10.0, db / 20.0);
        if (Math.abs(gain - 1.0) < 1e-9) {
            return 1.0;
        }
        for (float[] ch : material) {
            if (ch == null) {
                continue;
            }
            for (int i = 0; i < ch.length; i++) {
                ch[i] = (float) (ch[i] * gain);
            }
        }
        return gain;
    }
}
