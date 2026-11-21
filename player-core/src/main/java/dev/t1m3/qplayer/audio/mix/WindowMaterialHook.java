package dev.t1m3.qplayer.audio.mix;

import java.util.Map;

/**
 * {@link MaterialHook} 的实现：把过渡几何（叠唱区间 + 分离档位 + 窗口锚点 + 目标响度）
 * 固定下来，交给 {@link WindowMaterial#process} 干活（2026-10-07，目标①②路径 A）。
 *
 * <p>三处"如实不做"的守卫（都有判据在后面）：
 * <ul>
 *   <li>没装几何（叠唱区间为空且不做响度对齐）⇒ 直接 {@code null} ⇒ 原样交付；</li>
 *   <li>档位为 0（不去人声）且目标响度为 NaN ⇒ 同上（**一个采样都不动**）；</li>
 *   <li>底下算不出来 ⇒ {@code null} ⇒ 原样交付。</li>
 * </ul>
 */
public final class WindowMaterialHook implements MaterialHook {

    /** 票（上层一次性决策好的：人声曲线 + **一个**整段响度增益）；null ⇒ 不动。 */
    private final WindowTicket ticket;

    /** 按**块起点**缓存的分离器结果（"一次且只有一次"：同一块只分离一次）。 */
    private final java.util.Map<Long, dev.t1m3.qplayer.audio.model.StemSeparator.StemSet>
            stemCache = new java.util.LinkedHashMap<Long,
                    dev.t1m3.qplayer.audio.model.StemSeparator.StemSet>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<Long,
                                dev.t1m3.qplayer.audio.model.StemSeparator.StemSet> eldest) {
                    return size() > 8;
                }
            };

    private final double[] duckRanges;
    private final int vocalMode;
    private final double anchorSecs;
    private final double targetLufs;

    /**
     * @param duckRanges  叠唱区间（窗口时间轴秒数；null/空 ⇒ 不压人声）
     * @param anchorSecs  窗口时间轴原点在这个素材里的秒数（帧号↔窗口时间换算用）
     * @param vocalMode   分离档位（0 = 不去人声）
     * @param targetLufs  目标响度；{@link Double#NaN} ⇒ 不做响度对齐
     */
    public WindowMaterialHook(double[] duckRanges, double anchorSecs, int vocalMode,
                              double targetLufs) {
        this.duckRanges = duckRanges == null ? null : duckRanges.clone();
        this.anchorSecs = anchorSecs;
        this.vocalMode = vocalMode;
        this.targetLufs = targetLufs;
        this.ticket = null;
        this.rate = 48000;                   // 旧构造不接收采样率：用保守默认（票那条路会传真的）
    }

    /** 采样率（**由上层传入** —— 它构造票时本来就知道；deck 端不必猜）。 */
    private final int rate;

    /** 用**猜**的采样率构造（判据用；产品路径请用下面那个三参构造）。 */
    public WindowMaterialHook(WindowTicket ticket, int vocalMode) {
        this(ticket, vocalMode, 48000);
    }

    /** 用一个"票"构造（路径 A 推荐用法：上层一次性决策、deck 端只照着施加）。 */
    public WindowMaterialHook(WindowTicket ticket, int vocalMode, int rate) {
        this.rate = rate > 0 ? rate : 48000;
        this.ticket = ticket;
        this.vocalMode = vocalMode;
        this.duckRanges = null;
        this.anchorSecs = ticket == null ? 0.0 : ticket.anchorSecs;
        this.targetLufs = Double.NaN;
    }

    /**
     * <b>就地处理"刚解码出来的这一块"</b>（路径 A 在 Deck 里的落点）：
     * 响度那半（整段**一个**增益 ⇒ 块间不可能跳变）+ 人声那半（从 PCM 分轨 ⇒ 只压人声轨）。
     *
     * <p>{@code AudioBuf} 是**平面布局**（{@code base = 声道号 × capacityFrames}）——
     * 与 {@code AutoCueTransition.fillScratchInto} 里的读法同一套；这里顺着它就地改。
     */
    @Override
    public void onDecoded(dev.t1m3.qplayer.audio.core.AudioBuf buf, int frames,
                          double startSecs) {
        WindowTicket t = ticket;
        if (t == null || buf == null || frames <= 0 || !t.doesAnything()) {
            return;                          // 没有票 / 没事可做 ⇒ 一个采样都不动
        }
        int chans = buf.channels();
        int cap = buf.capacityFrames();
        float[] data = buf.backingArray();
        if (chans <= 0 || cap <= 0 || data == null || data.length < chans * cap) {
            return;
        }
        float[][] planar = new float[chans][];
        for (int c = 0; c < chans; c++) {
            planar[c] = java.util.Arrays.copyOfRange(data, c * cap, c * cap + frames);
        }
        // ① 响度：整段一个增益（块间结构上不可能跳变）
        t.applyLoudness(planar, frames);
        // ② 人声：从 PCM 分轨（按块起点缓存"一次"）⇒ 只压人声轨
        if (vocalMode != 0 && chans >= 2) {
            dev.t1m3.qplayer.audio.model.StemSeparator sep = separatorFor(vocalMode);
            if (sep != null) {
                // **按块起点缓存分轨结果**：同一块被问第二次 ⇒ 复用 ⇒ 不再分离
                // （"一次且只有一次"；separations() 是可计数的证据）
                long key = Math.round(startSecs * 1000.0);
                dev.t1m3.qplayer.audio.model.StemSeparator.StemSet cached;
                synchronized (stemCache) {
                    cached = stemCache.get(Long.valueOf(key));
                }
                if (cached == null) {
                    try {
                        cached = sep.separate(planar[0], planar[1], rate);
                    } catch (RuntimeException e) {
                        cached = null;
                    }
                    if (cached != null) {
                        separations++;
                        synchronized (stemCache) {
                            stemCache.put(Long.valueOf(key), cached);
                        }
                    }
                }
                if (cached != null) {
                    applyVocalsWith(t, planar, frames, startSecs, cached);
                }
            }
        }
        for (int c = 0; c < chans; c++) {
            System.arraycopy(planar[c], 0, data, c * cap, frames);
        }
    }

    /** 用**已经分好的**两轨施加人声那半（与 WindowTicket.applyVocals 同一套重建式）。 */
    private void applyVocalsWith(WindowTicket t, float[][] planar, int frames, double startSecs,
                                 dev.t1m3.qplayer.audio.model.StemSeparator.StemSet set) {
        if (set == null || set.vocalsLeft == null || set.instrumentalLeft == null) {
            return;
        }
        float[] left = planar[0];
        float[] right = planar.length > 1 ? planar[1] : null;
        int n = Math.min(frames, Math.min(left.length, set.vocalsLeft.length));
        for (int i = 0; i < n; i++) {
            double timeline = (startSecs - t.anchorSecs) + i / (double) rate;
            float gain = t.vocalGainAt(timeline, rate);
            if (gain == 1.0f) {
                continue;                    // 区间之外：一个采样都不动
            }
            float vocL = set.vocalsLeft[i];
            float vocR = set.vocalsRight == null ? vocL : set.vocalsRight[i];
            float insL = set.instrumentalLeft[i];
            float insR = set.instrumentalRight == null ? insL : set.instrumentalRight[i];
            left[i] = (float) (insL + vocL * gain);
            if (right != null) {
                right[i] = (float) (insR + vocR * gain);
            }
        }
    }

    /** 分离档位对应的分离器（拿不到就 null ⇒ 那半不动）。 */
    private static dev.t1m3.qplayer.audio.model.StemSeparator separatorFor(int mode) {
        try {
            return dev.t1m3.qplayer.audio.stem.StemSeparators.forMode(mode);
        } catch (RuntimeException e) {
            return null;                     // 档位不支持 ⇒ 不动（不猜）
        }
    }

    /** 真正跑过多少次分轨（判据用 —— "同一块只分离一次"的可计数形式）。 */
    private long separations;

    /** 见 {@link #separations()}。 */
    long separations() {
        return separations;
    }

    /** 区间的一份拷贝（判据用；产品里不需要）。 */
    double[] duckRanges() {
        return duckRanges == null ? null : duckRanges.clone();
    }

    /** 这个钩子会不会真的动手（不会动手时上层可以不装它）。 */
    public boolean doesAnything() {
        boolean duck = vocalMode != 0 && duckRanges != null && duckRanges.length >= 2;
        return duck || !Double.isNaN(targetLufs);
    }

    @Override
    public float[][] transform(String source, Map<String, String> headers,
                               double startSecs, int frames, int rate) {
        if (!doesAnything()) {
            return null;                     // 什么都不用做 ⇒ 原样（零变化）
        }
        return WindowMaterial.process(source, headers, startSecs, frames, rate, vocalMode,
                duckRanges, anchorSecs, targetLufs);
    }
}
