package dev.t1m3.qplayer.audio.mix;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>dsp_parameters.py 的端口</b>：69 条原生 DSP 参数描述（不可变数组 0x27ad959c0）。
 *
 * <p>为什么需要它：参考包选完路径之后，渲染用的湿声/滤波斜率/riding 等参数都来自
 * 这张表；只抄了"选哪条路径"而没抄"用什么参数"，听感上仍然是两套东西。
 *
 * <p>数据从 {@code automix/dsp-parameters.json} 读（参考包 assets 的原样拷贝），
 * 用最小 JSON 解析（这份文件结构固定，不引第三方库）。
 *
 * <p>{@code default_value} 的规则（参考包 {@code default_value()}）：
 * <b>{@code out_gain} 一律返回 fallback</b>（渲染器显式置 1.0），
 * 以及 <b>id 不是四字符的参数也返回 fallback</b>；其余返回原生默认值。
 */
public final class ReferenceDsp {

    /** 一条参数描述。 */
    public static final class Descriptor {
        public final String nativeAddress;
        public final String id;
        public final String styleParameterId;
        public final double rangeLow;
        public final double rangeHigh;
        public final double defaultValue;

        Descriptor(String nativeAddress, String id, String styleParameterId,
                   double rangeLow, double rangeHigh, double defaultValue) {
            this.nativeAddress = nativeAddress;
            this.id = id;
            this.styleParameterId = styleParameterId;
            this.rangeLow = rangeLow;
            this.rangeHigh = rangeHigh;
            this.defaultValue = defaultValue;
        }
    }

    private static final List<Descriptor> DESCRIPTORS = load();

    private ReferenceDsp() {
    }

    /** 全部 69 条。 */
    public static List<Descriptor> descriptors() {
        return DESCRIPTORS;
    }

    /** 按 style_parameter_id 找唯一一条（参考包找不到/重复都抛错）。 */
    public static Descriptor descriptor(String styleParameterId) {
        Descriptor found = null;
        for (int i = 0; i < DESCRIPTORS.size(); i++) {
            Descriptor d = DESCRIPTORS.get(i);
            if (styleParameterId.equals(d.styleParameterId)) {
                if (found != null) {
                    throw new IllegalArgumentException("Unknown native style parameter: " + styleParameterId);
                }
                found = d;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Unknown native style parameter: " + styleParameterId);
        }
        return found;
    }

    /**
     * 参考包 {@code default_value(id, fallback)}：
     * {@code out_gain} 或 id 长度不是 4 ⇒ fallback；其余 ⇒ 原生默认值。
     */
    public static Double defaultValue(String styleParameterId, Double fallback) {
        Descriptor d = descriptor(styleParameterId);
        if ("out_gain".equals(d.id) || d.id.length() != 4) {
            return fallback;
        }
        return Double.valueOf(d.defaultValue);
    }

    /**
     * 把某个参数夹到原生描述的 {@code value_range} 里（返回夹取后的值）。
     *
     * <p>用途：我们渲染链里那些"我填的近似值"（湿声 / 滤波截止 / feedback）
     * 在应用前先按参考包的原生区间校一遍，越界就夹住并可在日志里看到 ——
     * 这样至少不会出现"参数超出原生范围"这种一眼假的声音。
     */
    public static double clampToRange(String styleParameterId, double value) {
        Descriptor d = descriptor(styleParameterId);
        if (Double.isNaN(d.rangeLow) || Double.isNaN(d.rangeHigh)) {
            return value;
        }
        return Math.min(d.rangeHigh, Math.max(d.rangeLow, value));
    }

    /** 参考包 {@code available_vocoder_state}：有限值校验 + 区间夹取。 */
    public static final class VocoderState {
        public final double rate;
        public final double pitchCents;
        public final double pitchRatio;
        public final double smoothness;
        public final boolean spectralCoherence;
        public final boolean transientPreservation;

        VocoderState(double rate, double pitchCents, double pitchRatio, double smoothness,
                     boolean spectralCoherence, boolean transientPreservation) {
            this.rate = rate;
            this.pitchCents = pitchCents;
            this.pitchRatio = pitchRatio;
            this.smoothness = smoothness;
            this.spectralCoherence = spectralCoherence;
            this.transientPreservation = transientPreservation;
        }
    }

    public static VocoderState vocoderState(double rate, double pitchCents, double smoothness,
                                            boolean spectralCoherence, boolean transientPreservation) {
        if (Double.isNaN(rate) || Double.isInfinite(rate)
                || Double.isNaN(pitchCents) || Double.isInfinite(pitchCents)
                || Double.isNaN(smoothness) || Double.isInfinite(smoothness)) {
            throw new IllegalArgumentException("Finite vocoder parameters required");
        }
        double clampedRate = Math.min(32.0, Math.max(0.03125, rate));
        double clampedPitch = Math.min(2400.0, Math.max(-2400.0, pitchCents));
        double clampedSmoothness = Math.min(32.0, Math.max(3.0, smoothness));
        return new VocoderState(clampedRate, clampedPitch,
                Math.pow(2.0, clampedPitch / 1200.0), clampedSmoothness,
                spectralCoherence, spectralCoherence && transientPreservation);
    }

    // ---- 最小 JSON 读取（只认这份文件的结构：数组 + 对象 + 字符串/数字/布尔） ----

    private static List<Descriptor> load() {
        String text;
        try {
            InputStream in = ReferenceDsp.class.getResourceAsStream("/automix/dsp-parameters.json");
            if (in == null) {
                throw new IllegalStateException("缺少 automix/dsp-parameters.json");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            text = sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<Descriptor> out = new ArrayList<Descriptor>();
        // 每个参数对象都是一行 "native_address / id / style_parameter_id / value_range / default_value"
        Pattern objectPattern = Pattern.compile("\\{[^{}]*?\\}", Pattern.DOTALL);
        Matcher m = objectPattern.matcher(text.substring(text.indexOf("\"parameters\"")));
        while (m.find()) {
            String obj = m.group();
            if (!obj.contains("style_parameter_id")) {
                continue;
            }
            String address = stringField(obj, "native_address");
            String id = stringField(obj, "id");
            String styleId = stringField(obj, "style_parameter_id");
            double[] range = numberArray(obj, "value_range");
            double def = numberField(obj, "default_value");
            out.add(new Descriptor(address, id, styleId, range[0], range[1], def));
        }
        return out;
    }

    private static String stringField(String obj, String name) {
        String key = "\"" + name + "\"";
        int at = obj.indexOf(key);
        if (at < 0) {
            return "";
        }
        int colon = obj.indexOf(':', at + key.length());
        int open = obj.indexOf('"', colon + 1);
        int close = obj.indexOf('"', open + 1);
        return (colon < 0 || open < 0 || close < 0) ? "" : obj.substring(open + 1, close);
    }

    private static double numberField(String obj, String name) {
        String key = "\"" + name + "\"";
        int at = obj.indexOf(key);
        if (at < 0) {
            return Double.NaN;
        }
        int colon = obj.indexOf(':', at + key.length());
        return parseNumber(obj, colon + 1);
    }

    private static double[] numberArray(String obj, String name) {
        String key = "\"" + name + "\"";
        int at = obj.indexOf(key);
        if (at < 0) {
            return new double[] {Double.NaN, Double.NaN};
        }
        int open = obj.indexOf('[', at + key.length());
        int close = obj.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            return new double[] {Double.NaN, Double.NaN};
        }
        String body = obj.substring(open + 1, close);
        String[] parts = body.split(",");
        double[] out = new double[] {Double.NaN, Double.NaN};
        for (int i = 0; i < 2 && i < parts.length; i++) {
            out[i] = parseNumber(parts[i], 0);
        }
        return out;
    }

    private static double parseNumber(String text, int from) {
        int i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == ':')) {
            i++;
        }
        int start = i;
        while (i < text.length()) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }
        if (start == i) {
            return Double.NaN;
        }
        return Double.parseDouble(text.substring(start, i));
    }

}
