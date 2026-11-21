package dev.t1m3.qplayer.audio.dsp;

import java.util.Locale;

/**
 * 一路可定位声源。
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::spatial::Source}（上游是 {@code pub struct}，
 * 所以按移植约定单独一个文件放同包，与 {@link SplitLowHigh} 的处理一致）。
 *
 * <p>对象不可变：两个字段都是 {@code final}，没有 setter。上游派生
 * {@code Clone, Copy, PartialEq}：
 *
 * <ul>
 *   <li>{@code Copy}/{@code Clone} 不需要移植——Java 里持有引用的语义已经等价于「不可变数据」；</li>
 *   <li>{@code PartialEq} <b>不</b>移植成 {@code equals}：{@code azimuth} 是 f32，
 *       {@code NaN == NaN} 为 false 会违反 {@link Object#equals} 的自反契约
 *       （理由与 {@link OnePole}/{@link Biquad} 里同一条约定一致）；</li>
 *   <li>{@code #[derive(Debug)]} → {@link #toString()}。</li>
 * </ul>
 *
 * <p><b>球头模型，不是实测 HRTF</b>：实测的人头传递函数（HRTF/HRIR）来自 SOFA 数据库，
 * 能同时给出双耳时间差（ITD）、双耳声级差（ILD）与耳廓带来的俯仰/前后线索。这里只用
 * 刚性球体近似：ITD 用 Woodworth 公式（最大约 0.66 ms），远端耳靠一极点低通做头影
 * （ILD + 高频遮蔽）。<b>俯仰与前后区分都没有建模</b>——球体左右对称，前与后对耳朵
 * 而言是镜像的。所以水平方向的左右定位可靠，前后与高低不保证；界面上的功能描述不应
 * 暗示能渲染高度。升级路径是换掉 {@link #delaySecs} 与头影部分、改为对实测 HRIR 做
 * 卷积——接口不需要动。
 */
public final class Source {

    /** 头半径（米）。取成年人均值。上游是模块级 {@code const HEAD_RADIUS_M}。 */
    private static final float HEAD_RADIUS_M = 0.0875f;

    /** 声速（米/秒）。取 20 °C 干燥空气。 */
    private static final float SPEED_OF_SOUND = 343.0f;

    /** 远端耳最大衰减（分贝）。实测在 4–6 dB 量级。 */
    private static final float MAX_SHADOW_DB = 6.0f;

    /**
     * 无遮蔽时的高频上限（Hz）。
     *
     * <p>取 20 kHz 而不是奈奎斯特，是为了让「无遮蔽」在低采样率下也确实是「不滤波」。
     */
    private static final float SHADOW_CUTOFF_CLEAR = 20_000.0f;

    /** 完全遮蔽时的截止频率（Hz）。实测数据在 1–3 kHz 区间。 */
    private static final float SHADOW_CUTOFF_BLOCKED = 3_000.0f;

    /** 上游的 {@code core::f32::consts::PI}（0x1.921FB6p+1）。 */
    private static final float PI = (float) Math.PI;

    /**
     * 度 → 弧度。
     *
     * <p>Rust 的 {@code f32::to_radians()} 就是 {@code self * (consts::PI / 180.0)}，
     * 而且那个商是在 <b>f32</b> 里算出来的常量。这里也必须先 f32 除法再乘，不能直接用
     * {@code Math.toRadians}（那是在 double 里算 PI/180，结果可能与上游差 1 ulp）。
     */
    private static final float RADS_PER_DEG = PI / 180.0f;

    /**
     * 方位角（度）。{@code 0} 正前、{@code +90} 正右、{@code -90} 正左、{@code ±180} 正后。
     *
     * <p>公开 {@code final} 字段（不是 getter）：与上游的公开字段一一对应，读起来也一样。
     */
    public final float azimuth;

    /**
     * 是否为非定位声道（LFE）。
     *
     * <p>低频效果声道按规范<b>不参与定位</b>：它没有方向，直接等量送进双耳。
     * 硬把它当环绕声道定位，会让低音在头上左右乱飘。
     */
    public final boolean nonDirectional;

    private Source(float azimuth, boolean nonDirectional) {
        this.azimuth = azimuth;
        this.nonDirectional = nonDirectional;
    }

    /** 定位声源。对应上游 {@code Source::at}。 */
    public static Source at(float azimuth) {
        return new Source(azimuth, false);
    }

    /** 非定位声源（LFE）。对应上游 {@code Source::lfe}。 */
    public static Source lfe() {
        return new Source(0.0f, true);
    }

    /**
     * 双耳时间差（秒）。
     *
     * <p>Woodworth 刚性球体公式：{@code Δ = (a/c)·(θ + sin θ)}，{@code θ} 为声源偏离
     * 正中面的角度（折叠到 0..90°）。
     *
     * <p>声源越靠侧方，两耳路程差越大；到正侧方时约 0.66 ms，
     * 与实测人头的最大值（约 0.7 ms）吻合。
     *
     * <p>注意<b>正后方与正前方对耳朵等价</b>：这条公式把方位角折叠到 {@code [0, 180]}
     * 再折叠到 {@code [0, 90]}，所以 180° 的 ITD 恰好是 0（被 {@code rear_source_has_no_itd}
     * 锁住）——上游刻意如此，不是漏了。反过来，本模块不建模前后区分，也正因为这条折叠。
     */
    public float itdSecs() {
        if (nonDirectional) {
            return 0.0f;
        }
        float t = Math.abs(azimuth) % 360.0f;
        // 折叠到 [0, 180]，再折叠到 [0, 90]（正后方与正前方对耳朵等价）
        t = t > 180.0f ? 360.0f - t : t;
        float deg = t > 90.0f ? 180.0f - t : t;
        float theta = deg * RADS_PER_DEG;
        return (HEAD_RADIUS_M / SPEED_OF_SOUND) * (theta + (float) Math.sin(theta));
    }

    /**
     * 某只耳相对声源是近端还是远端。
     *
     * <p>上游是 {@code (self.azimuth >= 0.0) == ear_is_right}；Java 的 {@code >=} 对
     * {@code -0.0} 同样为 true，与 Rust 一致。
     */
    private boolean earIsNear(boolean earIsRight) {
        if (nonDirectional) {
            return true;
        }
        return (azimuth >= 0.0f) == earIsRight;
    }

    /** 该耳的延迟（秒）。近端为零，远端为完整 ITD。 */
    public float delaySecs(boolean earIsRight) {
        if (earIsNear(earIsRight)) {
            return 0.0f;
        }
        return itdSecs();
    }

    /**
     * 该耳的头影遮蔽量（0 = 无遮蔽，1 = 完全遮蔽）。
     *
     * <p>用声源在该耳轴线上的投影：投影为正表示声源就在这只耳朵那侧（无遮蔽），
     * 为 1 的反方向表示声源在正对面（最大遮蔽）。取平方是为了让<b>正前与正后
     * 的遮蔽足够轻</b>——否则正前方的声音会被不必要地闷掉，而实际不是这样：
     * 前方来的声波是绕过脸颊到达耳廓的，衰减很小。
     */
    public float shadow(boolean earIsRight) {
        if (nonDirectional) {
            return 0.0f;
        }
        float theta = azimuth * RADS_PER_DEG;
        float s = (float) Math.sin(theta);
        float p = earIsRight ? s : -s;
        float q = (1.0f - p) / 2.0f;
        // Rust f32::clamp 的镜像：NaN 时两个比较都不成立、原样返回 NaN
        // （这里 p ∈ [-1, 1]，取不到 NaN；也不需要 clamp 的 min > max 断言）
        if (q < 0.0f) {
            q = 0.0f;
        } else if (q > 1.0f) {
            q = 1.0f;
        }
        // 上游是 powi(2)——那在 Rust 里就是一次乘法，不是 powf
        return q * q;
    }

    /**
     * 该耳的增益（线性）。
     *
     * <p>头影的衰减量必须走 {@link Gain#dbToLinear(float)} 换算：这是本模块与 {@code gain}
     * 模块共享的唯一约定，也是 {@code lfe_differs_from_a_frontal_source} 与
     * {@code near_ear_is_brighter_than_far_ear} 这些性质能对齐的前提。
     */
    public float gain(boolean earIsRight) {
        return Gain.dbToLinear(-MAX_SHADOW_DB * shadow(earIsRight));
    }

    /**
     * 该耳头影滤波的截止频率（Hz）。
     *
     * <p>在对数域线性插值：遮蔽量从 0 到 1，截止频率从 20 kHz 几何下降到 3 kHz。
     * 用几何（对数）插值而不是算术，是因为频率的听感尺度是对数的——
     * 算术插值会让「一半遮蔽」听起来已经闷掉了。
     *
     * <p>两次 {@code powf} 各自算完<b>立刻</b>窄化回 {@code float} 再相乘：不能让整个
     * 表达式在 double 里算完再转，那样与上游的 f32 乘法不是同一个数。
     */
    public float cutoffHz(boolean earIsRight) {
        float s = shadow(earIsRight);
        float clear = (float) Math.pow(SHADOW_CUTOFF_CLEAR, (double) (1.0f - s));
        float blocked = (float) Math.pow(SHADOW_CUTOFF_BLOCKED, (double) s);
        return clear * blocked;
    }

    /**
     * 一行可读说明，给人看。
     *
     * <p><b>与上游的已知差异（唯一一处）</b>：恰好落在 {@code x.5} 上时的舍入方向不同。
     * 实测（把上游 {@code spatial.rs} 逐位未改地编成 Rust 探针，与本移植版对位比较）：
     * Rust 的 {@code {:.0}} 是<b>半到偶</b>，{@code String.format("%.0f")} 是<b>半远离零</b>——
     * {@code 0.5} 上游给 {@code "0°"} 而这里给 {@code "1°"}，{@code 2.5} 是 {@code "2°"}/{@code "3°"}，
     * {@code -0.5} 是 {@code "-0°"}/{"-1°"}；非半整数的值两边一致（{@code 1.5}→{@code "2°"}、
     * {@code 179.5}→{@code "180°"}、{@code 63.7}→{@code "64°"}、{@code -0.4}→{@code "-0°"}）。
     *
     * <p>为什么不改：这里保持与上游同一句 {@code format!("{:.0}°")} 的字面翻译，不去手写一套
     * 半到偶舍入（那要连 {@code -0}、NaN、inf 的打印一起复刻）。四种固定布局
     * （1/2/6/8 声道）的方位角全是整数，取不到半整数；只有 {@code Other(n)} 在 n-1 整除 720
     * 之类的极端声道数下才可能落到 {@code x.5}，而这只影响界面字符串、不影响任何采样值。
     * locale 固定 {@code ROOT}，免得某些区域设置把数字写成别的样子。
     */
    public String describe() {
        if (nonDirectional) {
            return "LFE（不定位）";
        }
        return String.format(Locale.ROOT, "%.0f°", Float.valueOf(azimuth));
    }

    /** 对应上游 {@code #[derive(Debug)]}：只打印两个字段。 */
    @Override
    public String toString() {
        return "Source{azimuth=" + azimuth + ", nonDirectional=" + nonDirectional + '}';
    }
}
