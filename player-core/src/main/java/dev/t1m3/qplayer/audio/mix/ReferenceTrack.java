package dev.t1m3.qplayer.audio.mix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>参考包里的"曲目 + 候选区"模型</b>（scoring.py 的 track dict / region dict 的 Java 版）。
 *
 * <p>特征全部用 {@code Map<String,Double>}：参考包也是"缺就是缺"（{@code None}）
 * 语义 —— 缺项会走 {@link ReferenceDecision} 里的 {@code unknown_factor} 分支，
 * 所以不能用 0 或 NaN 冒充"没有这个特征"。
 */
public final class ReferenceTrack {

    /** 一个候选区（参考包的 region dict）。 */
    public static final class Region {
        public final String id;
        public final double start;
        public final double end;
        /** 该区覆盖曲目级特征（同名键优先）。 */
        public final Map<String, Double> features = new LinkedHashMap<String, Double>();
    /** 字符串特征（genre 之类；参考包的 features 是混合 dict）。 */
    public final Map<String, String> textFeatures = new LinkedHashMap<String, String>();

        public Region(String id, double start, double end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public Region with(String key, double value) {
            features.put(key, Double.valueOf(value));
            return this;
        }
    }

    public final String id;
    public final double duration;
    public double leadingSilence;
    public double trailingSilence;
    public final Map<String, Double> features = new LinkedHashMap<String, Double>();
    /** 字符串特征（genre 之类；参考包的 features 是混合 dict）。 */
    public final Map<String, String> textFeatures = new LinkedHashMap<String, String>();
    /** 调性单独用两个字段（参考包是 {"tonic":..,"mode":..} 的嵌套 dict）。 */
    public Integer keyTonic;
    public String keyMode;
    public final List<Double> downbeats = new ArrayList<Double>();
    public final List<Region> regions = new ArrayList<Region>();

    public ReferenceTrack(String id, double duration) {
        this.id = id;
        this.duration = duration;
    }

    public ReferenceTrack text(String key, String value) {
        textFeatures.put(key, value);
        return this;
    }

    public ReferenceTrack feature(String key, double value) {
        features.put(key, Double.valueOf(value));
        return this;
    }

    public ReferenceTrack key(int tonic, String mode) {
        this.keyTonic = Integer.valueOf(tonic);
        this.keyMode = mode;
        return this;
    }

    public ReferenceTrack silence(double leading, double trailing) {
        this.leadingSilence = leading;
        this.trailingSilence = trailing;
        return this;
    }

    public ReferenceTrack downbeat(double at) {
        downbeats.add(Double.valueOf(at));
        return this;
    }

    public ReferenceTrack region(Region region) {
        regions.add(region);
        return this;
    }

    /** 参考包 {@code regions(track, side)}：给不出区就合成"尾 40 s / 头 40 s"两个。 */
    public List<Region> regionsFor(String side) {
        List<Region> provided = new ArrayList<Region>();
        for (int i = 0; i < regions.size(); i++) {
            provided.add(regions.get(i));
        }
        if (!provided.isEmpty()) {
            // 参考包按 (outgoing: end 降序 / incoming: start 升序) 排序后取前 8 个
            java.util.Collections.sort(provided, new java.util.Comparator<Region>() {
                @Override
                public int compare(Region a, Region b) {
                    return "outgoing".equals(side)
                            ? Double.compare(b.end, a.end)
                            : Double.compare(a.start, b.start);
                }
            });
            return provided.size() > 8 ? new ArrayList<Region>(provided.subList(0, 8)) : provided;
        }
        List<Region> out = new ArrayList<Region>();
        double ending = duration - trailingSilence;
        if ("outgoing".equals(side)) {
            out.add(new Region("synthetic-outro", Math.max(0.0, ending - 40.0), ending));
        } else {
            out.add(new Region("synthetic-intro", leadingSilence,
                    Math.min(duration, leadingSilence + 40.0)));
        }
        return out;
    }
}
