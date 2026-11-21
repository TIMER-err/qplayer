package dev.t1m3.qplayer.audio.mix;

import java.util.List;
import java.util.Map;

/**
 * <b>把一次决策的输入/输出 dump 成 JSON</b>（阶段 5 的"对拍台"）。
 *
 * <p>用途：真机上跑一次过渡，把喂给参考包的**特征 + 区域 + downbeats** 以及
 * 我们这边的**选中结果**一起写下来，然后交给参考包的 Python 实现
 * （{@code tools/automix_compare.py}）在同一份输入上重跑，逐字段比对。
 *
 * <p>为什么必须 dump 真实特征：数学核心已经 diff=0 了，剩下的差异只可能来自
 * "特征怎么算"；把真实特征固化下来，才能证明"同一份输入 ⇒ 同一份输出"。
 */
public final class ReferenceDump {

    private ReferenceDump() {
    }

    /** 一首曲目 → JSON（字段名与参考包的 track dict 对齐）。 */
    public static String track(ReferenceTrack t) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        field(sb, "id", t.id).append(',');
        raw(sb, "duration", Double.toString(t.duration)).append(',');
        raw(sb, "leading_silence", Double.toString(t.leadingSilence)).append(',');
        raw(sb, "trailing_silence", Double.toString(t.trailingSilence));
        sb.append(",\"features\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : t.features.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            key(sb, e.getKey());
            // 参考包要求 meter 是 [正整数, 正整数]（validate_features 会校验）：
            // 我们内部存的是 4.0 这种标量，dump 时按 [4,4] 写出去。
            if ("meter".equals(e.getKey())) {
                int beats = (int) Math.max(1, Math.round(e.getValue().doubleValue()));
                sb.append(":[").append(beats).append(',').append(beats).append(']');
            } else {
                sb.append(':').append(num(e.getValue().doubleValue()));
            }
        }
        for (Map.Entry<String, String> e : t.textFeatures.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            key(sb, e.getKey());
            sb.append(":\"").append(escape(e.getValue())).append('"');
        }
        if (t.keyTonic != null && t.keyMode != null) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"key\":{\"tonic\":").append(t.keyTonic.intValue())
                    .append(",\"mode\":\"").append(escape(t.keyMode)).append("\"}");
        }
        sb.append('}');
        sb.append(",\"downbeats\":[");
        for (int i = 0; i < t.downbeats.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(num(t.downbeats.get(i).doubleValue()));
        }
        sb.append(']');
        sb.append(",\"regions\":[");
        List<ReferenceTrack.Region> regions = t.regions;
        for (int i = 0; i < regions.size(); i++) {
            ReferenceTrack.Region r = regions.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(escape(r.id)).append("\",\"start\":")
                    .append(num(r.start)).append(",\"end\":").append(num(r.end))
                    .append(",\"features\":{");
            boolean f2 = true;
            Double regionTonic = r.features.get("key_tonic");
            Double regionMajor = r.features.get("key_mode_major");
            for (Map.Entry<String, Double> e : r.features.entrySet()) {
                // 调性在参考包里是嵌套对象 {"tonic","mode"}：region 级也要按这个形状写出，
                // 否则 Python 只看得到曲目级 key ⇒ 调性闸门判定与 Java 不一致 ✗
                if ("key_tonic".equals(e.getKey()) || "key_mode_major".equals(e.getKey())) {
                    continue;
                }
                if (!f2) {
                    sb.append(',');
                }
                f2 = false;
                key(sb, e.getKey());
                sb.append(':').append(num(e.getValue().doubleValue()));
            }
            if (regionTonic != null && regionMajor != null) {
                if (!f2) {
                    sb.append(',');
                }
                sb.append("\"key\":{\"tonic\":")
                        .append((int) Math.round(regionTonic.doubleValue()))
                        .append(",\"mode\":\"")
                        .append(regionMajor.doubleValue() >= 0.5 ? "major" : "minor")
                        .append("\"}");
            }
            sb.append("}}");
        }
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    /** 一次完整决策（输入两首 + 我们这边的选中结果）。 */
    public static String decision(ReferenceTrack outgoing, ReferenceTrack incoming, long seed,
                                  ReferenceDecision.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"seed\":").append(Long.toString(seed));
        sb.append(",\"outgoing\":").append(track(outgoing));
        sb.append(",\"incoming\":").append(track(incoming));
        sb.append(",\"java\":{");
        if (result != null && result.chosen != null) {
            ReferenceDecision.Candidate c = result.chosen;
            sb.append("\"template_id\":").append(c.templateId)
                    .append(",\"tier\":").append(c.tier)
                    .append(",\"score\":").append(num(c.score))
                    .append(",\"out_start\":").append(num(c.outStart))
                    .append(",\"out_end\":").append(num(c.outEnd))
                    .append(",\"in_start\":").append(num(c.inStart))
                    .append(",\"in_end\":").append(num(c.inEnd));
        }
        sb.append("},\"ranking\":[");
        if (result != null) {
            for (int i = 0; i < result.ranking.size(); i++) {
                ReferenceDecision.Candidate c = result.ranking.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"template_id\":").append(c.templateId)
                        .append(",\"tier\":").append(c.tier)
                        .append(",\"score\":").append(num(c.score))
                        .append(",\"eligible\":").append(c.eligible)
                        .append(",\"reasons\":\"")
                        .append(escape(String.join(";", c.rejectedReasons))).append("\"")
                        .append(",\"factors\":{");
                boolean firstFactor = true;
                for (Map.Entry<String, Double> fe : c.factors.entrySet()) {
                    if (!firstFactor) {
                        sb.append(',');
                    }
                    firstFactor = false;
                    key(sb, fe.getKey());
                    sb.append(':').append(num(fe.getValue().doubleValue()));
                }
                sb.append("}}");
            }
        }
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        key(sb, name);
        return sb.append(":\"").append(escape(value)).append('"');
    }

    private static void key(StringBuilder sb, String name) {
        sb.append('"').append(escape(name)).append('"');
    }

    private static StringBuilder raw(StringBuilder sb, String name, String literal) {
        key(sb, name);
        return sb.append(':').append(literal);
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "null";
        }
        return Double.toString(v);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else if (ch == '\n') {
                sb.append("\\n");
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
