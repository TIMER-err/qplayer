package dev.t1m3.qplayer.audio.mashup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>一首歌的 mashup 分析档案</b>：配对与选段需要的全部特征，可落盘、可复现。
 *
 * <h2>为什么不直接每次问 {@code AutoCue.Envelope}</h2>
 *
 * <p>整曲扫描是"一首歌把整段再解一遍"的操作（桌面 1~3 s/首），而配对一次要看两首、
 * 可能反复调整候选。所以：<b>算一次、存下来、按内容 hash 失效</b>（{@link ProfileStore}）。
 *
 * <h2>字段的取舍（都是被"能不能用"逼出来的）</h2>
 *
 * <ul>
 *   <li><b>响度用 {@link #lufs} 而不是 {@code Envelope.rms}</b>：后者的 95 分位归一化
 *       让两首歌的响度<b>不可比</b>（安静的歌会被当成响的歌）。{@code lufs} 是额外解码
 *       一段中段素材算出来的近似值（见 {@link TrackProfiles}），{@link Double#NaN} = 没算成；</li>
 *   <li><b>拍点只存结论，不存拍列表</b>：拍位置可以由 {@code 周期/相位} 在任何时刻推出来，
 *       存一份几千个 double 的列表既占空间又会和周期分叉；真正的逐拍锚点要过
 *       {@code BeatRefine}，那是第三阶段在规划器里做的事；</li>
 *   <li><b>每小节特征只存归一化形状</b>：选段要的是"哪几小节更响/更密"（段内相对量），
 *       绝对响度由 {@link #lufs} 单独负责。</li>
 * </ul>
 */
public final class TrackProfile {

    /** 档案格式版本。字段语义一变就必须 +1（旧文件照旧会当成"没有缓存"）。 */
    public static final int VERSION = 1;

    /** 魔数（第一行）。 */
    static final String MAGIC = "qplayer-mashup-profile";

    /** 缓存键：本地文件是内容 sha256，远端是去 query 的 URL。 */
    public final String key;

    /** 时长（秒）。 */
    public final double durationSecs;

    /** BPM（0 = 没测出来）。 */
    public final double bpm;

    /** 拍点置信度（<b>不同来源的尺子不同</b>，见设计文档 G8；只用它做降权，不做硬闸门）。 */
    public final double beatConfidence;

    /** 拍点结论所在的采样率（{@code periodFrames/phaseFrames} 的坐标系）。 */
    public final int beatRate;

    /** 拍周期（帧）。 */
    public final float periodFrames;

    /** 拍相位（帧）。 */
    public final float phaseFrames;

    /** 积分响度（LUFS）；{@link Double#NaN} = 未知。 */
    public final double lufs;

    /** 调性主音（0=C..11=B）；{@code -1} = 未知。 */
    public final int keyTonic;

    /** {@code "major"} / {@code "minor"}；{@code ""} = 未知。 */
    public final String keyMode;

    /** 调性置信度（{@code ReferenceKeyEstimate} 的 (best−second)/best）。 */
    public final double keyConfidence;

    /** 整曲平均色度（12 维，已归一化）；{@code null} = 没算（配对时按"未知"处理）。 */
    public final float[] meanChroma;

    /** 尾声结构点（秒）；{@code < 0} = 未知。 */
    public final double structureOutroSecs;

    /** 进场结构点（秒）；{@code < 0} = 未知。 */
    public final double structureIntroSecs;

    /** 一小节的秒数（按 4/4 与 {@link #bpm} 算）。 */
    public final double barSecs;

    /** 每小节归一化 RMS（0..1）。 */
    public final float[] barRms;

    /** 每小节归一化起音密度（0..1）。 */
    public final float[] barOnset;

    /** 每小节人声活动占比（0..1）。 */
    public final float[] barVocal;

    /** 整曲人声活动占比（0..1）：B 这个数越高，分轨后残留人声越多。 */
    public final double vocalShare;

    private TrackProfile(Builder b) {
        this.key = b.key;
        this.durationSecs = b.durationSecs;
        this.bpm = b.bpm;
        this.beatConfidence = b.beatConfidence;
        this.beatRate = b.beatRate;
        this.periodFrames = b.periodFrames;
        this.phaseFrames = b.phaseFrames;
        this.lufs = b.lufs;
        this.keyTonic = b.keyTonic;
        this.keyMode = b.keyMode == null ? "" : b.keyMode;
        this.keyConfidence = b.keyConfidence;
        this.meanChroma = b.meanChroma == null ? null : b.meanChroma.clone();
        this.structureOutroSecs = b.structureOutroSecs;
        this.structureIntroSecs = b.structureIntroSecs;
        this.barSecs = b.barSecs;
        this.barRms = b.barRms == null ? new float[0] : b.barRms.clone();
        this.barOnset = b.barOnset == null ? new float[0] : b.barOnset.clone();
        this.barVocal = b.barVocal == null ? new float[0] : b.barVocal.clone();
        this.vocalShare = b.vocalShare;
    }

    /** 建一份档案。 */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    /** 小节数（三组数组里最短的那个——防止手搭的档案里长度不一致）。 */
    public int bars() {
        return Math.min(barRms.length, Math.min(barOnset.length, barVocal.length));
    }

    /** 有没有可用的调性结论。 */
    public boolean hasKey() {
        return keyTonic >= 0 && meanChroma != null;
    }

    /** 有没有可用的拍点结论。 */
    public boolean hasTempo() {
        return bpm > 0 && periodFrames > 0 && beatRate > 0;
    }

    // ---- 序列化 -----------------------------------------------------------

    /**
     * 落盘用的文本。
     *
     * <p>行式 {@code 名字 值}，数组用空格分隔、{@code %.5f}、{@link Locale#ROOT}
     * （与 {@code BeatGridSidecar} 同一套写法规矩：换个 locale 就解析不出来是最冤的一种错）。
     */
    public String toText() {
        StringBuilder sb = new StringBuilder(512);
        sb.append(MAGIC).append(' ').append(VERSION).append('\n');
        line(sb, "key", key);
        line(sb, "durationSecs", fmt(durationSecs));
        line(sb, "bpm", fmt(bpm));
        line(sb, "beatConfidence", fmt(beatConfidence));
        line(sb, "beatRate", Integer.toString(beatRate));
        line(sb, "periodFrames", fmt(periodFrames));
        line(sb, "phaseFrames", fmt(phaseFrames));
        line(sb, "lufs", fmt(lufs));
        line(sb, "keyTonic", Integer.toString(keyTonic));
        line(sb, "keyMode", keyMode);
        line(sb, "keyConfidence", fmt(keyConfidence));
        line(sb, "structureOutroSecs", fmt(structureOutroSecs));
        line(sb, "structureIntroSecs", fmt(structureIntroSecs));
        line(sb, "barSecs", fmt(barSecs));
        line(sb, "vocalShare", fmt(vocalShare));
        line(sb, "meanChroma", fmtArray(meanChroma));
        line(sb, "barRms", fmtArray(barRms));
        line(sb, "barOnset", fmtArray(barOnset));
        line(sb, "barVocal", fmtArray(barVocal));
        return sb.toString();
    }

    /**
     * 解析。
     *
     * <p>与 {@code BeatGridSidecar} 同一条纪律：<b>任何不认识的东西都不抛异常</b>——
     * 版本不认、缺必填项、数字解析不了、数组长度不一致，一律返回 {@code null}
     * （= 没有缓存），让调用方重新分析。未知键<b>忽略</b>（前向兼容）。
     */
    public static TrackProfile parse(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 2) {
            return null;
        }
        String[] head = splitFirst(lines[0].trim());
        if (head == null || !MAGIC.equals(head[0])) {
            return null;
        }
        int version;
        try {
            version = Integer.parseInt(head[1].trim());
        } catch (RuntimeException e) {
            return null;
        }
        if (version != VERSION) {
            return null;
        }
        String key = null;
        double durationSecs = -1;
        double bpm = 0;
        double beatConfidence = 0;
        int beatRate = 0;
        float periodFrames = 0;
        float phaseFrames = 0;
        double lufs = Double.NaN;
        int keyTonic = -1;
        String keyMode = "";
        double keyConfidence = 0;
        float[] meanChroma = null;
        double outroSecs = -1;
        double introSecs = -1;
        double barSecs = 0;
        float[] barRms = new float[0];
        float[] barOnset = new float[0];
        float[] barVocal = new float[0];
        double vocalShare = 0;
        try {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    continue;
                }
                String[] kv = splitFirst(line);
                String name = kv[0];
                String value = kv[1];
                switch (name) {
                    case "key":
                        key = value;
                        break;
                    case "durationSecs":
                        durationSecs = Double.parseDouble(value);
                        break;
                    case "bpm":
                        bpm = Double.parseDouble(value);
                        break;
                    case "beatConfidence":
                        beatConfidence = Double.parseDouble(value);
                        break;
                    case "beatRate":
                        beatRate = Integer.parseInt(value);
                        break;
                    case "periodFrames":
                        periodFrames = (float) Double.parseDouble(value);
                        break;
                    case "phaseFrames":
                        phaseFrames = (float) Double.parseDouble(value);
                        break;
                    case "lufs":
                        lufs = Double.parseDouble(value);
                        break;
                    case "keyTonic":
                        keyTonic = Integer.parseInt(value);
                        break;
                    case "keyMode":
                        keyMode = value;
                        break;
                    case "keyConfidence":
                        keyConfidence = Double.parseDouble(value);
                        break;
                    case "structureOutroSecs":
                        outroSecs = Double.parseDouble(value);
                        break;
                    case "structureIntroSecs":
                        introSecs = Double.parseDouble(value);
                        break;
                    case "barSecs":
                        barSecs = Double.parseDouble(value);
                        break;
                    case "vocalShare":
                        vocalShare = Double.parseDouble(value);
                        break;
                    case "meanChroma":
                        meanChroma = parseFloats(value);
                        break;
                    case "barRms":
                        barRms = parseFloats(value);
                        break;
                    case "barOnset":
                        barOnset = parseFloats(value);
                        break;
                    case "barVocal":
                        barVocal = parseFloats(value);
                        break;
                    default:
                        break;                  // 未知键忽略（前向兼容）
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (key == null || key.isEmpty() || !(durationSecs > 0)) {
            return null;                        // 必填项缺失 ⇒ 当没有缓存
        }
        try {
            return builder(key)
                    .durationSecs(durationSecs)
                    .tempo(bpm, beatConfidence, beatRate, periodFrames, phaseFrames)
                    .loudness(lufs)
                    .key(keyTonic, keyMode, keyConfidence, meanChroma)
                    .structure(introSecs, outroSecs)
                    .bars(barSecs, barRms, barOnset, barVocal, vocalShare)
                    .build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String[] splitFirst(String line) {
        int sp = line.indexOf(' ');
        if (sp < 0) {
            return new String[]{line, ""};
        }
        return new String[]{line.substring(0, sp), line.substring(sp + 1)};
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String fmtArray(float[] a) {
        if (a == null || a.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(a.length * 8);
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "%.5f", a[i]));
        }
        return sb.toString();
    }

    private static float[] parseFloats(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return new float[0];
        }
        String[] parts = v.split("\\s+");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i]);
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "TrackProfile{%.1fs, %.1f BPM, 调 %d/%s, %.0f LUFS, %d 小节}",
                durationSecs, bpm, keyTonic, keyMode.isEmpty() ? "?" : keyMode,
                lufs, bars());
    }

    /** {@link TrackProfile} 的构造器（字段多，按名字给比按位置安全）。 */
    public static final class Builder {

        private final String key;
        private double durationSecs;
        private double bpm;
        private double beatConfidence;
        private int beatRate;
        private float periodFrames;
        private float phaseFrames;
        private double lufs = Double.NaN;
        private int keyTonic = -1;
        private String keyMode = "";
        private double keyConfidence;
        private float[] meanChroma;
        private double structureOutroSecs = -1.0;
        private double structureIntroSecs = -1.0;
        private double barSecs;
        private float[] barRms = new float[0];
        private float[] barOnset = new float[0];
        private float[] barVocal = new float[0];
        private double vocalShare;

        private Builder(String key) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("档案必须有缓存键");
            }
            this.key = key;
        }

        /** 时长（秒，必须 > 0）。 */
        public Builder durationSecs(double secs) {
            this.durationSecs = secs;
            return this;
        }

        /** 拍点结论（{@code beatRate} 是 {@code periodFrames/phaseFrames} 的采样率）。 */
        public Builder tempo(double bpm, double confidence, int beatRate,
                             float periodFrames, float phaseFrames) {
            this.bpm = bpm;
            this.beatConfidence = confidence;
            this.beatRate = beatRate;
            this.periodFrames = periodFrames;
            this.phaseFrames = phaseFrames;
            return this;
        }

        /** 积分响度（LUFS）；未知就别调，默认 NaN。 */
        public Builder loudness(double lufs) {
            this.lufs = lufs;
            return this;
        }

        /** 调性：主音 0..11、{@code "major"/"minor"}、置信度、整曲平均色度（可为 null）。 */
        public Builder key(int tonic, String mode, double confidence, float[] meanChroma) {
            this.keyTonic = tonic;
            this.keyMode = mode;
            this.keyConfidence = confidence;
            this.meanChroma = meanChroma;
            return this;
        }

        /** 两个结构点（秒；未知传负数）。 */
        public Builder structure(double introSecs, double outroSecs) {
            this.structureIntroSecs = introSecs;
            this.structureOutroSecs = outroSecs;
            return this;
        }

        /** 每小节特征（三组长度必须一致）。 */
        public Builder bars(double barSecs, float[] rms, float[] onset, float[] vocal,
                            double vocalShare) {
            this.barSecs = barSecs;
            this.barRms = rms;
            this.barOnset = onset;
            this.barVocal = vocal;
            this.vocalShare = vocalShare;
            return this;
        }

        /** 校验并冻结。 */
        public TrackProfile build() {
            if (!(durationSecs > 0)) {
                throw new IllegalArgumentException("时长必须为正：" + durationSecs);
            }
            if (bpm < 0 || Double.isNaN(bpm)) {
                throw new IllegalArgumentException("BPM 不能为负或 NaN：" + bpm);
            }
            if (barRms != null && barOnset != null && barVocal != null
                    && !(barRms.length == barOnset.length && barRms.length == barVocal.length)) {
                throw new IllegalArgumentException("每小节三组特征长度不一致："
                        + barRms.length + "/" + barOnset.length + "/" + barVocal.length);
            }
            if (meanChroma != null && meanChroma.length != 12) {
                throw new IllegalArgumentException("平均色度必须是 12 维：" + meanChroma.length);
            }
            List<String> problems = new ArrayList<>();
            if (keyTonic < -1 || keyTonic > 11) {
                problems.add("主音越界 " + keyTonic);
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("；", problems));
            }
            return new TrackProfile(this);
        }
    }

    private static void line(StringBuilder sb, String name, String value) {
        sb.append(name).append(' ').append(value == null ? "" : value).append('\n');
    }
}
