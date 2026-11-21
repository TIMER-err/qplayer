package dev.t1m3.qplayer.audio.mix;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>惰性 + 缓存的分轨素材</b>（2026-10-07，目标②）。
 *
 * <p>为什么要缓存：分离（DSP / ONNX）很贵，而同一段素材在一次准备里会被问好几次
 * （出场侧、进场侧、变速窗口、渲染侧各问一遍）。**"一次且只有一次"**在音质上的含义是
 * "同一段素材只分离一次、结果复用" —— 否则既慢又可能得到不一致的两份结果 ✗。
 *
 * <p>本类把这条纪律做成**可计数**的：{@link #separations()} 数的是真正跑过分离的次数，
 * 判据直接断言"同一段问 5 次 ⇒ 分离只跑了 1 次" ✓。
 *
 * <p>缓存按 (路径, 起点, 帧数, 采样率, 档位) 作键；上限 {@value #MAX_ENTRIES} 条
 * （一次过渡涉及的窗口数量是个位数，够用且不会长住内存）。
 */
public final class StemMaterials {

    /** 缓存条数上限（超出按最久未用淘汰）。 */
    static final int MAX_ENTRIES = 32;

    private static final Map<String, StemMaterial> CACHE =
            new LinkedHashMap<String, StemMaterial>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StemMaterial> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private static final AtomicLong SEPARATIONS = new AtomicLong();

    private StemMaterials() {
    }

    /** 真正跑过多少次分离（判据用 —— "一次且只有一次"的可计数形式）。 */
    public static long separations() {
        return SEPARATIONS.get();
    }

    /** 清空缓存与计数（判据用；产品里不需要）。 */
    public static void reset() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        SEPARATIONS.set(0L);
    }

    /**
     * 取一段分轨素材（**首次被问时才分离**，之后复用）。
     *
     * @param mode 分离档位；不支持的档位（例如 OFF）⇒ 返回 {@code null}，
     *             调用方据此退回"未分离素材"（不猜、也不假装有分轨）
     * @return 分轨素材；分离不出来时返回 {@code null}
     */
    public static StemMaterial forWindow(String path, Map<String, String> headers,
                                        double startSecs, int frames, int rate, int mode) {
        if (path == null || frames <= 0 || rate <= 0) {
            return null;
        }
        String key = path + '\u0000' + startSecs + '\u0000' + frames + '\u0000' + rate
                + '\u0000' + mode;
        synchronized (CACHE) {
            StemMaterial cached = CACHE.get(key);
            if (cached != null) {
                return cached;                 // 复用 ⇒ **不再分离**（这就是"一次"）
            }
        }
        float[][] stems = VocalSeparationTransition.stemsWindow(
                path, headers, startSecs, frames, rate, mode);
        if (stems == null || stems.length < 4 || stems[0] == null) {
            return null;                       // 算不出来 ⇒ 交给调用方按"原样"处理
        }
        SEPARATIONS.incrementAndGet();
        float[] vocals = new float[stems[2].length];
        for (int i = 0; i < vocals.length; i++) {
            vocals[i] = 0.5f * (stems[2][i] + stems[3][i]);      // 人声取左右平均（单声道）
        }
        float[][] instrumental = new float[][] {stems[0], stems[1]};
        StemMaterial material = new StemMaterial(instrumental, vocals);
        synchronized (CACHE) {
            CACHE.put(key, material);
        }
        return material;
    }
}
