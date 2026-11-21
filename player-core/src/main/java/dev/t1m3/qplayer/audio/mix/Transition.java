package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 过渡引擎。
 *
 * <p><strong>这里是「按模板移植过渡效果」的接入点。</strong> 每个模板实现这个接口，
 * 引擎主体不需要任何改动。三种模式（交叉淡化 / 声波重合 / AI 模式）与 AI 模式的
 * 五种接法都只是它的实现。
 *
 * <h2>两种性质截然不同的「无缝」</h2>
 *
 * <table>
 *   <tr><th>类型</th><th>做什么</th><th>位完美</th></tr>
 *   <tr><td>{@link Gapless}</td><td>只决定「在哪一帧拼接」</td><td>兼容（不动采样值）</td></tr>
 *   <tr><td>{@link Crossfade}</td><td>加权混合</td><td>必须放弃</td></tr>
 * </table>
 *
 * <p>引擎靠 {@link #rewritesSamples()} 决定当前时段的位完美标记，<strong>不另开一套
 * 判断</strong>——判定逻辑只允许有一份。位完美因此是<strong>逐段</strong>生效的：
 * 只有过渡窗口那几秒让步，歌曲主体照常直通。
 *
 * <p><strong>线程约定</strong>：上游的 {@code Transition: Send} 让实例可以在准备线程与
 * 渲染线程之间移交（每个接法实例只服务一次过渡，准备时另起线程用工厂造新实例，
 * 过渡真正开始时才换上来——这样渲染路径上不加锁）。Java 无法在类型上表达这一点，
 * 实现必须遵守：<strong>渲染中不得被另一线程调用，且实例用完即弃</strong>。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::transition::Transition}。
 */
public interface Transition {

    /**
     * 过渡窗口长度（帧）。
     *
     * <p>返回 0 表示无重叠（硬接 / 纯无缝拼接）。窗口越长，两首歌交叠越久，
     * 对素材调性差异也越敏感。
     *
     * <p><strong>这个值可以在 {@link #prepare} 之后改变</strong>——对拍会把窗口拉长到
     * 整数拍。编排器会在准备之后重新读一次；忘了重读，编排器会一直用旧窗口
     * 判断触发点。
     */
    long windowFrames(int sampleRate);

    /**
     * 渲染过渡。
     *
     * @param out      输出缓冲（容量即本次要产出的帧数上限）
     * @param from     出场曲在窗口内的片段
     * @param to       进场曲在窗口内的片段
     * @param progress 0.0 → 1.0，由引擎按窗口位置算好传入
     * @return 写入 {@code out} 的帧数
     */
    int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress);

    /** 模式名称，用于界面显示。 */
    String name();

    /**
     * 这个过渡是否会<strong>改写采样值</strong>。
     *
     * <p>返回 {@code false} 的过渡不会破坏位完美。引擎据此决定位完美标记——
     * 这是它与 ADR-003 §2.3 那张表的唯一连接点。
     */
    boolean rewritesSamples();

    /**
     * 过渡开始前的准备。默认什么都不做。
     *
     * <p>实现会在这里做离线分析（读文件、算拍点、判调性），因此<strong>耗时可达数秒</strong>
     * （上游实测最坏 4.6 秒），编排器必须在另一条线程上提前调用它。
     *
     * <p>两个句柄与 {@link dev.t1m3.qplayer.audio.decode.Decoder#open(String)} 收的是同一种
     * 东西：本地路径或 http(s) URL。上游用 {@code &Path}（只有本地文件），QPlayer 的音源
     * 可以是网络直链，所以统一成 {@code String}。
     */
    default TransitionPlan prepare(String outro, String intro, int rate) {
        return TransitionPlan.NONE;
    }

    /**
     * 同 {@link #prepare(String, String, int)}，但带上两条音源各自需要的<strong>请求头</strong>。
     *
     * <p>为什么需要它：这一层要<strong>自己开解码器读文件</strong>做分析（对拍、判调性、
     * 自动点位扫整曲），而插件直链常常必须带 token/Referer。没有这条通路，
     * 那些音源只能等落到本地缓存之后才分析得了。
     *
     * <p>默认实现丢掉请求头转调老签名：只实现了老方法的接法行为完全不变。
     *
     * @param outroHeaders 出场曲的请求头（可为 {@code null} / 空）
     * @param introHeaders 进场曲的请求头（可为 {@code null} / 空）
     */
    default TransitionPlan prepare(String outro, String intro, int rate,
                                   java.util.Map<String, String> outroHeaders,
                                   java.util.Map<String, String> introHeaders) {
        return prepare(outro, intro, rate);
    }

    /**
     * 某个进度处实际生效的变调量。
     *
     * <p>默认返回零——不需要变调的过渡不用管它。需要变调的实现（如声波重合）
     * 在这里给出自己的滑入/滑出曲线。
     */
    /**
     * 这一次过渡有没有「**只有出场曲在响**」的前导（秒；{@code 0} = 没有）。
     *
     * <p>给引擎的报口径用（2026-10-07 用户拍板）：有前导时，界面要**继续显示出场曲** ✓——
     * 通知、位置、时长三样一起等进场曲真的出声再切 ✓；没有前导的实现（绝大多数）保持原样 ✓。
     * 买时间那一档由 {@code AutoCueTransition} 覆写成真实读数 ✓。
     */
    default double outroLoopLeadInSecs() {
        return 0.0;
    }

    default PitchPlan pitchAt(float progress) {
        return PitchPlan.ZERO;
    }

    /**
     * **输出帧 → 内容帧**（变速档用 ✓ 用户 2026-10-09：「让 B 整体都变，末尾逐步还原回正常，实时同步速度」✓）：
     * 变速时 deck 的帧数跑得比听到的内容快 ✗ ⇒ 界面/歌词必须按**内容**走 ✓。
     * 不变速的档直接返回原值 ✓。
     */
    default long contentFramesFor(long outFrames) {
        return outFrames;
    }
}
