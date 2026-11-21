package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 增益与渐变。
 *
 * <p>直接改增益会听到「拉链噪声」——因为增益是逐块生效的，
 * 两次块边界之间跳变会在波形上留下阶跃。所以增益一律走线性斜坡：
 * 在 {@code rampFrames} 帧内从当前值走到目标值。
 *
 * <p>默认斜坡 5 ms。这个长度在 48 kHz 下是 240 帧，足够消掉阶跃，
 * 又短到不让人感知成「音量慢慢变」。
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::gain} 模块（{@code Gain} + 模块级自由函数
 * {@code db_to_linear} / {@code linear_to_db}）。按移植约定，自由函数变成本类上的
 * {@link #dbToLinear(float)} / {@link #linearToDb(float)}。
 *
 * <p><strong>与上游的已知差异</strong>（都记在这里，方便以后 diff 上游）：
 * <ul>
 *   <li>{@code f32::EPSILON}：Java 没有 {@code Float.EPSILON}，用 {@link #F32_EPSILON}
 *       （{@code 0x1p-23f}）顶，它就是 Rust 的 {@code f32::EPSILON} = 2<sup>-23</sup>
 *       = {@code Math.ulp(1.0f)}。这个常量决定「算不算动了增益」的阈值，不许改。</li>
 *   <li>超越函数：Rust 走平台 libm 的 {@code powf} / {@code log10f}，Java 走 {@code Math.pow}
 *       / {@code Math.log10}（double 实现）再<strong>立刻</strong>窄化回 {@code float}。
 *       结果可能与上游差 &le; 1 ulp，测试里对这类断言给了刚好覆盖它的容差。</li>
 *   <li>{@code f32::max} 遇 NaN 返回另一个操作数，Java 的 {@code Math.max} 遇 NaN 返回 NaN。
 *       上游测试没有覆盖 NaN 输入，这里不改写上游算法，只记下这个差别。</li>
 *   <li>{@code #[derive(Clone)]} → 复制构造 {@link #Gain(Gain)}；
 *       {@code #[derive(Debug)]} → {@link #toString()}。</li>
 *   <li>{@link #process(AudioBuf)} 里用 {@link AudioBuf#backingArray()} + 声道步长索引，
 *       而不是逐声道取 {@link AudioBuf#channelWriter(int)}：后者每次都会 new 出视图对象，
 *       而这是逐帧跑的热循环（约定允许热循环用裸数组，前提是写清理由）。</li>
 * </ul>
 */
public final class Gain {

    /** 默认斜坡时长（毫秒）。上游是模块级 {@code pub const DEFAULT_RAMP_MS}。 */
    public static final float DEFAULT_RAMP_MS = 5.0f;

    /**
     * Rust 的 {@code f32::EPSILON} = 2<sup>-23</sup> &asymp; 1.1920929e-7。
     *
     * <p>Java 没有对应的 {@code Float.EPSILON}，用十六进制浮点字面量写成精确值
     * （{@code Math.ulp(1.0f)} 也是它）。注意这<strong>不是</strong>
     * {@code Float.MIN_VALUE}（那是最小次正规数 1.4e-45），两者差着天量级。
     */
    private static final float F32_EPSILON = 0x1p-23f;

    private float current;
    private float target;
    /** 每帧的增量。 */
    private float step;
    /** 斜坡剩余帧数。上游是 {@code usize}，按约定映射成 {@code int}。 */
    private int remaining;
    /** 斜坡总长（帧）。 */
    private int rampFrames;

    /**
     * 以单位增益创建。对应上游 {@code Gain::new(sample_rate)}。
     *
     * <p>斜坡帧数 = {@code round(sample_rate × 5ms / 1000)}，至少 1 帧
     * （0 帧会让 {@link #setTarget} 除以零）。
     */
    public Gain(int sampleRate) {
        // 对齐上游表达式：((sample_rate as f32 * DEFAULT_RAMP_MS / 1000.0).round() as usize).max(1)
        // 除法在 float 里做完再 round，不让 double 混进来走全程。
        this.rampFrames = Math.max(1,
                Math.round((float) sampleRate * DEFAULT_RAMP_MS / 1000.0f));
        this.current = 1.0f;
        this.target = 1.0f;
        this.step = 0.0f;
        this.remaining = 0;
    }

    /**
     * 复制构造，对应上游的 {@code #[derive(Clone)]}：五个字段逐一复制，
     * 包括斜坡进度——所以复制出来的节点与原节点此刻状态完全一致。
     */
    public Gain(Gain other) {
        this.current = other.current;
        this.target = other.target;
        this.step = other.step;
        this.remaining = other.remaining;
        this.rampFrames = other.rampFrames;
    }

    /** 自定义斜坡时长。对应上游 {@code Gain::with_ramp_ms}；负值按 0 处理（于是斜坡只有 1 帧）。 */
    public static Gain withRampMs(int sampleRate, float rampMs) {
        Gain g = new Gain(sampleRate);
        // 上游：(sample_rate as f32 * ramp_ms.max(0.0) / 1000.0).round() as usize，再 .max(1)
        g.rampFrames = Math.max(1,
                Math.round((float) sampleRate * Math.max(0.0f, rampMs) / 1000.0f));
        return g;
    }

    /** 当前增益（线性）。 */
    public float current() {
        return current;
    }

    /** 目标增益（线性）。 */
    public float target() {
        return target;
    }

    /** 斜坡是否还在进行。 */
    public boolean isRamping() {
        return remaining > 0;
    }

    /** 设置线性目标值。 */
    public void setTarget(float value) {
        float v = Math.max(0.0f, value);
        this.target = v;
        if (Math.abs(v - this.current) < F32_EPSILON) {
            // 目标与当前值「按 f32 尺度看」相同：立刻停掉斜坡，别白走一遍。
            this.step = 0.0f;
            this.remaining = 0;
            return;
        }
        // 从当前值重新起一条完整斜坡——正在斜坡中途改目标时也走这里，
        // 所以不会出现「目标改了但还在按旧步长走」的错位。
        this.step = (v - this.current) / (float) this.rampFrames;
        this.remaining = this.rampFrames;
    }

    /** 用分贝设置目标值。0 dB = 单位增益。 */
    public void setTargetDb(float db) {
        setTarget(dbToLinear(db));
    }

    /** 立刻跳到某个值，不做渐变。用于初始化或重置。 */
    public void snapTo(float value) {
        this.current = Math.max(0.0f, value);
        this.target = this.current;
        this.step = 0.0f;
        this.remaining = 0;
    }

    /**
     * 应用增益，按帧走斜坡。
     *
     * <p>单位增益且无斜坡时<strong>直接返回、一个采样都不写</strong>：这不只是省一轮乘加，
     * 更是「位完美路径下增益可以常驻链路」的前提——留着它，链路里挂一个 unity 的
     * {@code Gain} 对输出逐位无影响（{@code gain.rs} 的
     * {@code unity_gain_does_not_touch_buffer} 锁着这条性质）。
     */
    public void process(AudioBuf buf) {
        int frames = buf.frames();
        int channels = buf.channels();
        if (frames == 0) {
            return;
        }

        // 单位增益且没有斜坡时走快路径，省掉整轮乘加。
        if (!isRamping() && Math.abs(this.current - 1.0f) < F32_EPSILON) {
            return;
        }

        // 对应上游的 buf.channel_mut(c)[i] *= g：平面布局下第 c 声道第 i 帧的下标
        // 就是 c * capacityFrames + i（等于 AudioBuf.channelBase(c) + i）。
        float[] data = buf.backingArray();
        int stride = buf.capacityFrames();

        for (int i = 0; i < frames; i++) {
            if (this.remaining > 0) {
                this.current += this.step;
                this.remaining -= 1;
                if (this.remaining == 0) {
                    // 斜坡结束时精确落到目标值：浮点累加会有残差，
                    // 不这样兜一下就可能永远差一点点到不了目标。
                    this.current = this.target;
                }
            }
            float g = this.current;
            for (int c = 0; c < channels; c++) {
                int idx = c * stride + i;
                data[idx] *= g;
            }
        }
    }

    /**
     * 分贝转线性。{@code -inf} dB 视作静音。
     *
     * <p>阈值 −144 dB 是个哨兵：再低的增益在 f32 里没有意义（−144 dB 已接近
     * f32 的表示下限量级），直接返回 0，免得 {@code powf} 吐次正规数。
     */
    public static float dbToLinear(float db) {
        if (db <= -144.0f) {
            return 0.0f;
        }
        // 上游是 10f32.powf(db / 20.0)：除法在 f32 里做，幂在平台的 powf 里做。
        // 这里同样先做 f32 除法，Math.pow 的结果立刻窄化回 float。
        float e = db / 20.0f;
        return (float) Math.pow(10.0, e);
    }

    /**
     * 线性转分贝。0（或负）返回 {@code -144.0} 作为「静音」哨兵。
     *
     * <p>哨兵值必须与 {@link #dbToLinear(float)} 的阈值配对：这样
     * {@code dbToLinear(linearToDb(0))} 回到 0 而不是 −inf 或 NaN。
     */
    public static float linearToDb(float v) {
        if (v <= 1e-7f) {
            return -144.0f;
        }
        // 上游 20.0 * v.log10() 是 f32 乘法；这里 log10 走 double 实现再立刻窄化。
        return 20.0f * (float) Math.log10(v);
    }

    /** 只打印几个标量字段，不会把采样刷进日志。 */
    @Override
    public String toString() {
        return "Gain{current=" + current + ", target=" + target
                + ", remaining=" + remaining + "/" + rampFrames + '}';
    }
}
