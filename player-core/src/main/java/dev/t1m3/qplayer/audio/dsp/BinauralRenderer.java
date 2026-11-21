package dev.t1m3.qplayer.audio.dsp;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 双耳渲染器：把任意多声道折叠成耳机里的空间感。
 *
 * <h2>范围（先划清楚，避免过度承诺）</h2>
 *
 * <p>按 ADR-001 的决策，「环绕声」定义为两件事：
 *
 * <ol>
 *   <li><b>多声道直通</b> —— 5.1 / 7.1 原样交给 HDMI 功放。这件事不需要 DSP，
 *       由 {@code sonalis-mix} 的声道映射完成（见 {@link SpatialMode#PASSTHROUGH}）</li>
 *   <li><b>双耳渲染</b> —— 把任意多声道折叠成耳机里的空间感。本类负责这一件</li>
 * </ol>
 *
 * <p><b>不做</b> Dolby Atmos / DTS 硬解：那需要商业授权与认证，不是技术问题。
 *
 * <h2>这里用的是「球头模型」，不是实测 HRTF</h2>
 *
 * <p>实测的人头传递函数（HRTF / HRIR）来自 SOFA 数据库，能同时给出<b>双耳时间差
 * （ITD）、双耳声级差（ILD）与耳廓带来的俯仰/前后线索</b>。本类只用刚性球体近似
 * （见 {@link Source}）：
 *
 * <table>
 *   <tr><th>线索</th><th>是否建模</th><th>说明</th></tr>
 *   <tr><td>ITD</td><td>是</td><td>Woodworth 公式，最大约 0.66 ms</td></tr>
 *   <tr><td>ILD + 高频遮蔽</td><td>是</td><td>远端耳的截止频率随角度下移（一极点低通做头影）</td></tr>
 *   <tr><td>俯仰</td><td>否</td><td>球体左右对称，没有 elevation 线索</td></tr>
 *   <tr><td>前后区分</td><td>否</td><td>前与后对耳朵而言是镜像的</td></tr>
 * </table>
 *
 * <p>所以：<b>水平方向的左右定位是可靠的，前后与高低不保证。</b> 界面上的功能描述
 * 不应暗示能渲染高度。升级路径是换掉 {@link Source#delaySecs(boolean)} 与头影部分、
 * 改为对实测 HRIR 做卷积——接口不需要动。
 *
 * <h2>装备门槛</h2>
 *
 * <p>与 ADR-001 一致：<b>硬件空间音频按硬件置灰</b>（由设备能力探测决定）。本类
 * 是纯软件渲染，耳机就能用——但只有当输出设备是耳机或双声道时才有意义；接到 5.1
 * 功放上时应该走直通，而不是先折叠再送出去。
 *
 * <h2>与上游的对应关系</h2>
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::spatial}。逐项映射：
 *
 * <ul>
 *   <li>模块级常量 {@code HEAD_RADIUS_M} / {@code SPEED_OF_SOUND} / {@code MAX_SHADOW_DB} /
 *       {@code SHADOW_CUTOFF_CLEAR} / {@code SHADOW_CUTOFF_BLOCKED} 收进 {@link Source}
 *       （它们只服务于声源几何）</li>
 *   <li>模块级自由函数 {@code sources_for(layout)} → {@link #sourcesFor(ChannelLayout)}，
 *       按约定写成 {@code public static}（比照 {@code db_to_linear} → {@link Gain#dbToLinear(float)}）</li>
 *   <li>私有类型 {@code DelayLine} / {@code EarState} → 本类里的私有静态嵌套类
 *       （上游没有 {@code pub}，不是模块的公开 API）</li>
 *   <li>{@code BinauralRenderer::new} → 构造器；{@code BinauralRenderer::with_sources}
 *       → {@link #withSources(List, int)}（上游两个都是关联构造函数，名字各有含义，
 *       这里保留同名工厂方法，方便与上游对照）</li>
 * </ul>
 *
 * <p><b>运算一律 float 镜像 f32</b>：所有中间量按 {@code float} 算，{@code Math.sin/cos/sqrt/
 * pow/log} 这类超越函数只在 double 实现里算完<b>立刻</b> {@code (float)} 转回来。头影参数
 * 换算必须走 {@link Gain#dbToLinear(float)}（与 {@code gain} 模块同一份换算，不许在这里
 * 另写一遍）。
 *
 * <p><b>与上游的已知差异</b>：
 *
 * <ul>
 *   <li>{@code render} 是可变方法（上游 {@code &mut self}）；实例不可跨线程共享
 *       （Java 表达不了独占借用，与 {@link OnePole} 同一条约定）。</li>
 *   <li>上游 {@code #[derive(Debug)]} → {@link #toString()}，且只打印标量，不刷采样。</li>
 *   <li>{@link #sources()} 返回不可修改视图：上游返回借用，调用方本来就改不了；
 *       Java 侧用 {@link Collections#unmodifiableList} 把这条约定变成运行期事实。</li>
 * </ul>
 */
public final class BinauralRenderer {

    private final int rate;

    /** 参与渲染的声源表。与上游一样在构造后不再变化。 */
    private final List<Source> sources;

    /**
     * {@code sources.size() * 2}，索引为 {@code 源 * 2 + 耳}（0 左 1 右）。
     *
     * <p>用数组而不是 {@code List}：这是逐帧访问的热路径（每帧每声源每耳一次），
     * 数组的边界检查与偏移计算最省。
     */
    private final EarState[] state;

    /**
     * 输出余量增益，防止多声道求和后削顶。
     *
     * <p>多声道折叠时各路会叠加。<b>下游没有限幅器</b>，所以在这里留出余量，
     * 而不是指望后面的环节去收拾削顶。
     */
    private final float outputGain;

    /**
     * 按声道布局构造。对应上游 {@code BinauralRenderer::new}。
     *
     * @param layout 输入布局，决定声源表（见 {@link #sourcesFor(ChannelLayout)}）
     * @param rate   采样率（Hz），小于 1 时按 1 处理
     */
    public BinauralRenderer(ChannelLayout layout, int rate) {
        this(sourcesFor(layout), rate);
    }

    /**
     * 按显式声源表构造（便于测试与自定义摆位）。对应上游
     * {@code BinauralRenderer::with_sources}；公开入口见 {@link #withSources(List, int)}。
     */
    private BinauralRenderer(List<Source> sources, int rate) {
        if (sources == null) {
            // Rust 的签名要求实参存在；Java 允许 null，所以把这条前置条件显式化
            throw new IllegalArgumentException("声源表不能为 null");
        }
        int r = Math.max(rate, 1);
        // 最大 ITD 约 0.66 ms，再留一倍余量
        int maxDelay = (int) Math.ceil((double) ((float) r * 0.0015f)) + 4;

        List<Source> srcs = new ArrayList<Source>(sources);
        EarState[] st = new EarState[srcs.size() * 2];
        for (int i = 0; i < srcs.size(); i++) {
            Source s = srcs.get(i);
            // 上游是 for ear_right in [false, true]：索引 0 = 左耳，索引 1 = 右耳，
            // 这个顺序决定了 state 的下标公式 i * 2 + e，不能反
            for (int e = 0; e < 2; e++) {
                boolean earRight = e == 1;
                st[i * 2 + e] = new EarState(
                        new DelayLine(maxDelay),
                        // 起始截止频率按初值设定，避免第一帧是全带通的突变
                        OnePole.lowPass(r, s.cutoffHz(earRight)),
                        s.delaySecs(earRight) * (float) r,
                        s.gain(earRight));
            }
        }

        // 上游是 db_to_linear(-3.0 * (1.0 + extra).log2())：Java 没有 Math.log2，
        // 这里用更精确的 Math.log 算完立刻窄化回 float。比特级交叉验证里，
        // 这条在 1/2/4/6/7 路上的输出余量与上游的 log2f 结果逐位相同。
        float extra = Math.max((float) srcs.size() - 2.0f, 0.0f);
        float gain = extra > 0.0f
                ? Gain.dbToLinear(-3.0f * (float) (Math.log((double) (1.0f + extra)) / Math.log(2.0)))
                : 1.0f;

        this.rate = r;
        this.sources = Collections.unmodifiableList(srcs);
        this.state = st;
        this.outputGain = gain;
    }

    /**
     * 按显式声源表构造。对应上游 {@code BinauralRenderer::with_sources}。
     *
     * @param sources 声源表（按声道顺序）
     * @param rate    采样率（Hz），小于 1 时按 1 处理
     */
    public static BinauralRenderer withSources(List<Source> sources, int rate) {
        return new BinauralRenderer(sources, rate);
    }

    /**
     * 给定声道布局的声源位置表。对应上游模块级自由函数 {@code sources_for}。
     *
     * <p>坐标约定取 ITU / SMPTE 的常规摆位。顺序即为声道在缓冲里的顺序——
     * 这个顺序不能凭感觉排，得跟解码器吐出来的顺序一致。
     *
     * <p>上游 match 的是枚举变体（含 {@code ChannelLayout::Other(n)}）；Java 侧的
     * {@link ChannelLayout} 以「声道数相等即相等」定义布局，所以这里按声道数分派，
     * 与上游的 match 一一对应：1/2/6/8 之外一律走 {@code Other(n)} 那条分支。
     */
    public static List<Source> sourcesFor(ChannelLayout layout) {
        switch (layout.count()) {
            case 1:
                return Collections.singletonList(Source.at(0.0f));
            case 2:
                // 立体声按 ±30° 摆（ITU 的标准听音三角）
                return fixed(Source.at(-30.0f), Source.at(30.0f));
            case 6:
                // FL FR C LFE SL SR
                return fixed(
                        Source.at(-30.0f),
                        Source.at(30.0f),
                        Source.at(0.0f),
                        Source.lfe(),
                        Source.at(-110.0f),
                        Source.at(110.0f));
            case 8:
                // FL FR C LFE BL BR SL SR
                return fixed(
                        Source.at(-30.0f),
                        Source.at(30.0f),
                        Source.at(0.0f),
                        Source.lfe(),
                        Source.at(-135.0f),
                        Source.at(135.0f),
                        Source.at(-90.0f),
                        Source.at(90.0f));
            default:
                // 未知布局：按扇面均匀铺开，至少不会把所有内容挤在正前方
                int n = Math.max(layout.count(), 1);
                List<Source> list = new ArrayList<Source>(n);
                for (int i = 0; i < n; i++) {
                    if (n == 1) {
                        list.add(Source.at(0.0f));
                    } else {
                        float t = (float) i / (float) (n - 1); // 0..1
                        list.add(Source.at(-180.0f + 360.0f * t));
                    }
                }
                return Collections.unmodifiableList(list);
        }
    }

    /** 固定声源表的构造辅助：上游是 {@code vec![..]}，这里顺手加上不可修改包装。 */
    private static List<Source> fixed(Source... s) {
        return Collections.unmodifiableList(Arrays.asList(s));
    }

    /** 参与渲染的声源表（不可修改视图）。 */
    public List<Source> sources() {
        return sources;
    }

    /** 输出余量（线性）。 */
    public float outputGain() {
        return outputGain;
    }

    /** 采样率。 */
    public int rate() {
        return rate;
    }

    /**
     * 一行说明，供界面显示。对应上游 {@code BinauralRenderer::describe}。
     *
     * <p>{@code {} 路定位} 只数可定位声源：LFE 不参与定位，算进「路数」会误导。
     */
    public String describe(ChannelLayout input) {
        int locatable = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (!sources.get(i).nonDirectional) {
                locatable++;
            }
        }
        return "双耳渲染（球头模型，" + input + " → 立体声，" + locatable + " 路定位）";
    }

    /**
     * 清空状态。跳转后必须调用，否则旧位置的余振会漏进来
     * （上游 {@code reset_clears_state} 锁着这条）。
     */
    public void reset() {
        for (int i = 0; i < state.length; i++) {
            state[i].delay.reset();
            state[i].shadow.reset();
        }
    }

    /**
     * 渲染一块。
     *
     * <p>{@code src} 的声道数与声源表对应；{@code out} 的前两个声道接收左右耳。
     * 返回写入的帧数。
     *
     * <p>源声道比声源表少时<b>退到第一路</b>而不是补零——补零会让整块输出变安静甚至
     * 静音（{@code fewer_source_channels_than_sources_does_not_silence} 锁着这条）。
     */
    public int render(AudioBuf src, AudioBuf out) {
        int frames = Math.min(src.frames(), out.capacityFrames());
        if (frames == 0 || out.channels() == 0) {
            out.setFrames(0);
            return 0;
        }

        int srcCh = src.channels();
        int ns = sources.size();
        int outCh = out.channels();

        // 逐帧跑的热循环：直接用裸数组 + 声道步长，避免每帧 new 出 FloatBuffer 视图
        // （等价于上游的 src.channel(ch)[n] / out.channel_mut(ch)[n]，
        //  平面布局下第 c 声道第 n 帧就是 c * capacityFrames + n）。
        float[] srcData = src.backingArray();
        int srcStride = src.capacityFrames();
        float[] outData = out.backingArray();
        int outStride = out.capacityFrames();

        for (int n = 0; n < frames; n++) {
            float l = 0.0f;
            float r = 0.0f;

            for (int i = 0; i < ns; i++) {
                // 源声道不足时退到第一路，避免静音
                float x = srcData[(i < srcCh ? i : 0) * srcStride + n];

                for (int e = 0; e < 2; e++) {
                    EarState st = state[i * 2 + e];
                    float y = st.delay.process(x, st.delaySamples);
                    y = st.shadow.process(y) * st.gain;
                    if (e == 1) {
                        r += y;
                    } else {
                        l += y;
                    }
                }
            }

            float lo = l * outputGain;
            float ro = r * outputGain;
            if (outCh == 0) {
                // 上游 match 的 0 分支：上面的提前返回已经排除了这种情况，
                // 留在这里是为了跟上游逐行对应（改了顺序就会漏掉这条出口）
            } else if (outCh == 1) {
                // 单声道输出取双耳平均，不做别的下混
                outData[n] = (lo + ro) * 0.5f;
            } else {
                outData[n] = lo;
                outData[outStride + n] = ro;
            }
        }

        out.setFrames(frames);
        return frames;
    }

    /** 对应上游 {@code #[derive(Debug)]}：只打印几个标量，不刷采样。 */
    @Override
    public String toString() {
        return "BinauralRenderer{rate=" + rate + ", sources=" + sources.size()
                + ", outputGain=" + outputGain + '}';
    }

    /**
     * 分数延迟线。对应上游私有类型 {@code DelayLine}（没有 {@code pub}，所以按约定
     * 不做成独立的公开文件，而是宿主类的私有嵌套类）。
     *
     * <p>上游派生 {@code Clone}，但只有 {@code BinauralRenderer} 自己用它，而
     * {@code BinauralRenderer} 并没有派生 {@code Clone}，所以这里不提供 {@code copy()}。
     */
    private static final class DelayLine {

        private final float[] buf;
        private int pos;

        DelayLine(int capacity) {
            // 至少 4 个槽：process 里的延迟上界是 n - 2，太短会自己撞自己
            this.buf = new float[Math.max(capacity, 4)];
            this.pos = 0;
        }

        void reset() {
            Arrays.fill(buf, 0.0f);
            this.pos = 0;
        }

        /** 写入 {@code x}，读出延迟 {@code d} 个样本的结果（分数延迟，线性插值）。 */
        float process(float x, float d) {
            int n = buf.length;
            buf[pos] = x;

            // 上游 d.clamp(0.0, (n - 2) as f32)：n >= 4 保证上界不小于下界
            float dc = d;
            if (dc < 0.0f) {
                dc = 0.0f;
            } else if (dc > (float) (n - 2)) {
                dc = (float) (n - 2);
            }
            float rf = (float) pos - dc;
            while (rf < 0.0f) {
                rf += (float) n;
            }
            int i0 = (int) Math.floor((double) rf);
            float frac = rf - (float) i0;
            float a = buf[i0 % n];
            float b = buf[(i0 + 1) % n];

            pos += 1;
            if (pos == n) {
                pos = 0;
            }
            return a + (b - a) * frac;
        }
    }

    /**
     * 每（声源，耳）一份的处理状态。对应上游私有类型 {@code EarState}。
     *
     * <p>字段全是 {@code final}：构造后只改{@link #delay}与{@link #shadow}的内部状态，
     * 每样本的延迟量与增益是几何算出来的常量（方位角一旦定了就不变）。
     */
    private static final class EarState {

        final DelayLine delay;
        final OnePole shadow;
        /** 每样本的延迟量（分数）。 */
        final float delaySamples;
        final float gain;

        EarState(DelayLine delay, OnePole shadow, float delaySamples, float gain) {
            this.delay = delay;
            this.shadow = shadow;
            this.delaySamples = delaySamples;
            this.gain = gain;
        }
    }
}
