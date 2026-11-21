package dev.t1m3.qplayer.audio.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作室参数的 JSON 读写（设置里存的就是一个字符串）。
 *
 * <h2>为什么是 JSON 而不是一堆设置键</h2>
 *
 * <p>一条链有「三台机架 × 十几段 × 每段五个参数」这样的量级，摊成设置键就是几百个键，
 * 而且每次加一个段类型都要迁一次 {@code SettingsCatalog}。JSON 把它收成一个原子值：
 * <strong>换参数 = 换这一个字符串</strong>，泵线程那边整体替换，不会看到半套参数。
 *
 * <h2>容错的三条约定</h2>
 *
 * <ol>
 *   <li><b>认不出来的一律退回默认</b>（段类型、声道、缺字段、超范围），绝不抛异常——
 *       用户手改过的文件、旧版本存的预设都不该让播放器打不开；</li>
 *   <li><b>整数版本号</b>{@code version} 只在语义真的变了时才升（升的时候在这里写迁移）；</li>
 *   <li><b>最大值夹住</b>（{@link EqBand} 的构造里已经夹了）。</li>
 * </ol>
 */
public final class StudioJson {

    /** 当前格式版本。 */
    public static final int VERSION = 1;

    private StudioJson() {
    }

    /** 序列化（永远是当前版本）。 */
    public static String toJson(StudioRacks racks) {
        StudioRacks r = racks == null ? StudioRacks.EMPTY : racks;
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("master", rackToJson(r.master));
        root.add("deckA", rackToJson(r.deckA));
        root.add("deckB", rackToJson(r.deckB));
        return root.toString();
    }

    /**
     * 反序列化：任何解析不了的东西都退回 {@link StudioRacks#EMPTY}（全关=直通），
     * 而不是让播放器起不来。
     */
    public static StudioRacks fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return StudioRacks.EMPTY;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return StudioRacks.EMPTY;
            }
            JsonObject root = parsed.getAsJsonObject();
            int version = intOf(root, "version", VERSION);
            if (version > VERSION) {
                // 来自更新的版本：能读多少读多少（认不出的段退回默认），但**不**当作错误
                // ——用户可能刚从新版本回退，把整份参数丢掉太粗暴。
                version = VERSION;
            }
            return StudioRacks.of(rackFromJson(root.get("master")), rackFromJson(root.get("deckA")),
                    rackFromJson(root.get("deckB")));
        } catch (RuntimeException e) {
            return StudioRacks.EMPTY;
        }
    }

    private static JsonObject rackToJson(StudioRack rack) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", rack.enabled);
        o.addProperty("preampDb", rack.preampDb);
        o.addProperty("balance", rack.balance);
        o.addProperty("polarityInvert", rack.polarityInvert);
        o.addProperty("loudness", rack.loudness);
        o.addProperty("loudnessAmount", rack.loudnessAmount);
        JsonArray bands = new JsonArray();
        for (EqBand b : rack.bands) {
            JsonObject bo = new JsonObject();
            bo.addProperty("type", b.type.json());
            bo.addProperty("freq", b.freqHz);
            bo.addProperty("gain", b.gainDb);
            bo.addProperty("q", b.q);
            bo.addProperty("slope", b.slopeDbPerOct);
            bo.addProperty("channel", b.channel.json());
            bo.addProperty("enabled", b.enabled);
            bands.add(bo);
        }
        o.add("bands", bands);
        // 音高：写成一个子对象。**永远写出来**（哪怕关着）——省掉它会让「同一份参数
        // 序列化两次得到两个字符串」，而界面上「有没有改过」正是按字符串比的。
        JsonObject p = new JsonObject();
        p.addProperty("enabled", rack.pitch.enabled);
        p.addProperty("semitone", rack.pitch.semitone);
        p.addProperty("cents", rack.pitch.cents);
        p.addProperty("mix", rack.pitch.mix);
        o.add("pitch", p);
        // 动态（P5）
        JsonObject dyn = new JsonObject();
        dyn.addProperty("gateOn", rack.dynamics.gateOn);
        dyn.addProperty("gateThresholdDb", rack.dynamics.gateThresholdDb);
        dyn.addProperty("gateRangeDb", rack.dynamics.gateRangeDb);
        dyn.addProperty("gateHoldMs", rack.dynamics.gateHoldMs);
        dyn.addProperty("compOn", rack.dynamics.compOn);
        dyn.addProperty("compThresholdDb", rack.dynamics.compThresholdDb);
        dyn.addProperty("compRatio", rack.dynamics.compRatio);
        dyn.addProperty("compKneeDb", rack.dynamics.compKneeDb);
        dyn.addProperty("compAttackMs", rack.dynamics.compAttackMs);
        dyn.addProperty("compReleaseMs", rack.dynamics.compReleaseMs);
        dyn.addProperty("compMakeupDb", rack.dynamics.compMakeupDb);
        dyn.addProperty("compRms", rack.dynamics.compRms);
        dyn.addProperty("compRmsWindowMs", rack.dynamics.compRmsWindowMs);
        dyn.addProperty("limiterOn", rack.dynamics.limiterOn);
        dyn.addProperty("limiterCeilingDb", rack.dynamics.limiterCeilingDb);
        dyn.addProperty("limiterLookaheadMs", rack.dynamics.limiterLookaheadMs);
        dyn.addProperty("limiterReleaseMs", rack.dynamics.limiterReleaseMs);
        o.add("dynamics", dyn);
        // 立体声（P6）
        JsonObject st = new JsonObject();
        st.addProperty("enabled", rack.stereo.enabled);
        st.addProperty("width", rack.stereo.width);
        st.addProperty("bassMonoHz", rack.stereo.bassMonoHz);
        st.addProperty("sideDelayMs", rack.stereo.sideDelayMs);
        st.addProperty("channelSwap", rack.stereo.channelSwap);
        st.addProperty("crossfeed", rack.stereo.crossfeed);
        o.add("stereo", st);
        // 染色（P7）
        JsonObject sat = new JsonObject();
        sat.addProperty("enabled", rack.saturation.enabled);
        sat.addProperty("curve", rack.saturation.curve.name());
        sat.addProperty("driveDb", rack.saturation.driveDb);
        sat.addProperty("makeupDb", rack.saturation.makeupDb);
        sat.addProperty("mix", rack.saturation.mix);
        sat.addProperty("oversample", rack.saturation.oversample);
        o.add("saturation", sat);
        return o;
    }

    private static StudioRack rackFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return StudioRack.EMPTY;
        }
        JsonObject o = element.getAsJsonObject();
        List<EqBand> bands = new ArrayList<EqBand>();
        JsonElement arr = o.get("bands");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement e : arr.getAsJsonArray()) {
                if (e.isJsonObject() && bands.size() < StudioRack.MAX_BANDS) {
                    bands.add(bandFromJson(e.getAsJsonObject()));
                }
            }
        }
        return StudioRack.full(
                boolOf(o, "enabled", false),
                doubleOf(o, "preampDb", 0.0),
                doubleOf(o, "balance", 0.0),
                boolOf(o, "polarityInvert", false),
                boolOf(o, "loudness", false),
                doubleOf(o, "loudnessAmount", 0.5),
                bands,
                pitchFromJson(o.get("pitch")),
                dynamicsFromJson(o.get("dynamics")),
                stereoFromJson(o.get("stereo")),
                saturationFromJson(o.get("saturation")));
    }

    /** 动态那一段（缺字段/类型不对一律退回默认；旧 JSON 没有这一段 → 全关）。 */
    private static DynamicsParams dynamicsFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return DynamicsParams.off();
        }
        JsonObject o = element.getAsJsonObject();
        return DynamicsParams.builder()
                .gate(boolOf(o, "gateOn", false), doubleOf(o, "gateThresholdDb", -40.0),
                        doubleOf(o, "gateRangeDb", -40.0), doubleOf(o, "gateHoldMs", 50.0))
                .compressor(boolOf(o, "compOn", false), doubleOf(o, "compThresholdDb", -18.0),
                        doubleOf(o, "compRatio", 3.0), doubleOf(o, "compKneeDb", 6.0),
                        doubleOf(o, "compAttackMs", 10.0), doubleOf(o, "compReleaseMs", 120.0),
                        doubleOf(o, "compMakeupDb", 0.0))
                .rmsDetector(boolOf(o, "compRms", false), doubleOf(o, "compRmsWindowMs", 20.0))
                .limiter(boolOf(o, "limiterOn", false), doubleOf(o, "limiterCeilingDb", -1.0),
                        doubleOf(o, "limiterLookaheadMs", 2.0),
                        doubleOf(o, "limiterReleaseMs", 80.0))
                .build();
    }

    /** 立体声那一段（缺字段 → 中性 = 直通）。 */
    private static StereoParams stereoFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return StereoParams.neutral();
        }
        JsonObject o = element.getAsJsonObject();
        return new StereoParams(boolOf(o, "enabled", false), doubleOf(o, "width", 1.0),
                doubleOf(o, "bassMonoHz", 0.0), doubleOf(o, "sideDelayMs", 0.0),
                boolOf(o, "channelSwap", false), doubleOf(o, "crossfeed", 0.0));
    }

    /** 染色那一段（缺字段 → 关；曲线名认不出一律退回 tanh）。 */
    private static SaturationParams saturationFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return SaturationParams.off();
        }
        JsonObject o = element.getAsJsonObject();
        SaturationParams.Curve curve;
        try {
            curve = SaturationParams.Curve.valueOf(stringOf(o, "curve", "TANH"));
        } catch (RuntimeException ignored) {
            curve = SaturationParams.Curve.TANH;
        }
        return SaturationParams.of(curve, doubleOf(o, "driveDb", 6.0),
                doubleOf(o, "makeupDb", -6.0), doubleOf(o, "mix", 1.0),
                intOf(o, "oversample", 2)).withEnabled(boolOf(o, "enabled", false));
    }

    /**
     * 音高那一段。缺字段、类型不对、超范围一律按 {@link PitchParams} 自己的夹紧规则走
     * （它的构造函数里已经夹了半音/音分/干湿）。
     *
     * <p>默认是<strong>关</strong>：旧版本的 JSON 里没有这一段，读出来必须是「关」——
     * 否则用户升级一次播放器，所有歌就都被移调了（那种 bug 极难联想到 JSON 上）。
     */
    private static PitchParams pitchFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return PitchParams.off();
        }
        JsonObject o = element.getAsJsonObject();
        if (!boolOf(o, "enabled", false)) {
            return PitchParams.off();
        }
        return PitchParams.of(intOf(o, "semitone", 0), doubleOf(o, "cents", 0.0),
                doubleOf(o, "mix", 1.0));
    }

    private static EqBand bandFromJson(JsonObject o) {
        return EqBand.of(
                BandType.fromJson(stringOf(o, "type", "peak")),
                doubleOf(o, "freq", 1000.0),
                doubleOf(o, "gain", 0.0),
                doubleOf(o, "q", 0.7071),
                intOf(o, "slope", 12),
                ChannelTarget.fromJson(stringOf(o, "channel", "stereo")),
                boolOf(o, "enabled", true));
    }

    private static String stringOf(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return e.getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double doubleOf(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            double v = e.getAsDouble();
            return Double.isNaN(v) ? fallback : v;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int intOf(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean boolOf(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
