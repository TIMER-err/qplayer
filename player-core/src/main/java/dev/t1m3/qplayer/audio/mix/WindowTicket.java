package dev.t1m3.qplayer.audio.mix;

/**
 * <b>窗口处理票</b>（2026-10-07，目标②路径 A 的第二层）。
 *
 * <p>为什么要有"票"这个东西：deck 是**按块**交付素材的（一块几十毫秒），而"去人声"
 * 与"响度对齐"这两件事都必须**按窗口**决策：
 * <ul>
 *   <li>人声增益是窗口时间轴上的一条曲线（叠唱区间内压、区间外不动）；</li>
 *   <li><b>响度只能有一个增益</b> —— 每块各自量、各自补，块与块之间就会跳变，
 *       那正是用户实测的"响度突变"（<code>-lufs+dsp.wav</code>）。</li>
 * </ul>
 *
 * <p>所以上层（几何已知时）把决策**一次性**做成票，deck 拿到票之后只是**照着施加** ——
 * 它自己不做任何测量，也就没有"每块一个答案"的机会 ✓。
 *
 * <p>票是**不可变**的（{@code final} 字段 + 拷贝进出），可以被多块复用、也可以被缓存。
 */
public final class WindowTicket {

    /** 窗口时间轴原点的绝对时间（秒）：把"块起点"换算成窗口时间用。 */
    public final double anchorSecs;

    /** 逐帧人声增益（1 = 原样、0 = 完全去人声）；{@code null} = 不压人声。 */
    private final float[] vocalGain;

    /** **整段共用的**响度增益（1 = 不动）。 */
    public final double loudnessGain;

    private WindowTicket(double anchorSecs, float[] vocalGain, double loudnessGain) {
        this.anchorSecs = anchorSecs;
        this.vocalGain = vocalGain == null ? null : vocalGain.clone();
        this.loudnessGain = loudnessGain;
    }

    /**
     * 做一张"什么都不改"的票（{@link #doesAnything()} 为假 ⇒ 上层可以不装钩子）。
     */
    public static WindowTicket identity(double anchorSecs) {
        return new WindowTicket(anchorSecs, null, 1.0);
    }

    /**
     * 按窗口音频做票：<b>量一次</b>响度（{@link WindowMaterial#alignLoudness} 的口径），
     * 并按叠唱区间摊出逐帧人声增益曲线。
     *
     * @param window      整个窗口的素材（量响度用；{@code null}/空 ⇒ 只做增益曲线）
     * @param rate        采样率
     * @param duckRanges  叠唱区间（窗口时间轴秒数；null/空 ⇒ 不压人声）
     * @param anchorSecs  窗口时间轴原点在这个素材里的秒数
     * @param targetLufs  目标响度；{@link Double#NaN} ⇒ 不做响度对齐
     */
    public static WindowTicket build(float[][] window, int rate, double[] duckRanges,
                                     double anchorSecs, double targetLufs) {
        float[] gain = null;
        boolean duck = duckRanges != null && duckRanges.length >= 2 && rate > 0;
        if (duck) {
            int frames = window == null || window.length == 0 || window[0] == null
                    ? 0 : window[0].length;
            if (frames > 0) {
                gain = new float[frames];
                for (int i = 0; i < frames; i++) {
                    gain[i] = (float) WindowMaterial.duckGainAt(duckRanges, i / (double) rate);
                }
            } else {
                duck = false;
            }
        }
        double loud = 1.0;
        if (!Double.isNaN(targetLufs) && window != null && window.length > 0) {
            // 量一次、补一次 —— 注意这里**在副本上**量，不改调用方的数组
            float[][] copy = new float[window.length][];
            for (int c = 0; c < window.length; c++) {
                copy[c] = window[c] == null ? null : window[c].clone();
            }
            loud = WindowMaterial.alignLoudness(copy, rate, targetLufs);
        }
        return new WindowTicket(anchorSecs, gain, loud);
    }

    /** 这张票会不会真的动手。 */
    public boolean doesAnything() {
        return vocalGain != null || Math.abs(loudnessGain - 1.0) > 1e-9;
    }

    /** 窗口时间轴某点的**目标**人声增益（没有曲线 ⇒ 1）。 */
    public float vocalGainAt(double timelineSecs, int rate) {
        if (vocalGain == null || rate <= 0) {
            return 1.0f;
        }
        int idx = (int) Math.floor(timelineSecs * rate);
        if (idx < 0 || idx >= vocalGain.length) {
            return 1.0f;
        }
        return vocalGain[idx];
    }

    /**
     * <b>把票施加到一块素材上</b>（就地）—— 只做<b>响度</b>那半。
     *
     * <p>为什么人声那半不在这里：人声增益只能作用在**人声轨**上 ✗ ——
     * 乘到整段混音上会把伴奏一起静音（用户实测的"突然静音"）。
     * 所以那半要等"从 PCM 分轨"接好之后，按 {@link #vocalGainAt(double, int)} 施加在人声轨上。
     *
     * <p>响度这半之所以安全，正因为它是**一个**数：无论哪一块、无论块大小，
     * 乘的都是同一个 {@link #loudnessGain} ⇒ 块与块之间**不可能**跳变 ✓。
     *
     * @param chunk  这一块的素材（{@code [声道][帧]}，就地改）
     * @param frames 这一块的有效帧数
     * @return 是否改动过
     */
    public boolean applyLoudness(float[][] chunk, int frames) {
        if (chunk == null || Math.abs(loudnessGain - 1.0) < 1e-9) {
            return false;
        }
        float g = (float) loudnessGain;
        for (float[] ch : chunk) {
            if (ch == null) {
                continue;
            }
            int n = Math.min(frames, ch.length);
            for (int i = 0; i < n; i++) {
                ch[i] = ch[i] * g;
            }
        }
        return true;
    }

    /**
     * <b>把票的"人声那半"施加到一块素材上</b>（就地）：从 PCM 分轨 → 只压人声轨 → 加回伴奏。
     *
     * <p>为什么必须分轨（而不是乘到整段混音上）：那是用户实测的"突然静音" ✗ ——
     * 人声增益一乘到整段，伴奏也跟着没了。这里的重建式与
     * {@code StemMaterial.mixInto} 同源：{@code instr + voc × gain(t)}。
     *
     * <p>分离器**由外部注入**（{@code sep}）：这样判据可以用一个假分离器
     * 精确验证"伴奏必须还在"，而不必依赖真模型 / 真文件 ✓。
     *
     * @param chunk          这一块的素材（{@code [声道][帧]}；至少两声道才能分左右）
     * @param frames         有效帧数
     * @param chunkStartSecs 这一块起点的**绝对时间**（秒）
     * @param sep            分离器（{@code null} ⇒ 原样返回，不动）
     * @return 是否改动过
     */
    public boolean applyVocals(float[][] chunk, int frames, double chunkStartSecs, int rate,
                              dev.t1m3.qplayer.audio.model.StemSeparator sep) {
        if (chunk == null || chunk.length < 2 || sep == null || vocalGain == null || rate <= 0) {
            return false;                    // 没有曲线 / 没有分离器 / 不是立体声 ⇒ 不动（不猜）
        }
        float[] left = chunk[0];
        float[] right = chunk[1];
        if (left == null || right == null) {
            return false;
        }
        dev.t1m3.qplayer.audio.model.StemSeparator.StemSet set;
        try {
            set = sep.separate(left, right, rate);
        } catch (RuntimeException e) {
            return false;                    // 分离失败 ⇒ 原样（不猜、不半途而废）
        }
        if (set == null || set.vocalsLeft == null || set.instrumentalLeft == null) {
            return false;
        }
        int n = Math.min(frames, Math.min(left.length, set.vocalsLeft.length));
        for (int i = 0; i < n; i++) {
            double timeline = (chunkStartSecs - anchorSecs) + i / (double) rate;
            float gain = vocalGainAt(timeline, rate);
            if (gain == 1.0f) {
                continue;                    // 区间之外：**一个采样都不动** ✓
            }
            float vocL = set.vocalsLeft[i];
            float vocR = set.vocalsRight == null ? vocL : set.vocalsRight[i];
            float insL = set.instrumentalLeft[i];
            float insR = set.instrumentalRight == null ? insL : set.instrumentalRight[i];
            left[i] = (float) (insL + vocL * gain);      // 重建：伴奏 + 人声×增益
            right[i] = (float) (insR + vocR * gain);
        }
        return true;
    }

    /** 逐帧曲线的份数（判据用）。 */
    public int vocalGainFrames() {
        return vocalGain == null ? 0 : vocalGain.length;
    }
}
