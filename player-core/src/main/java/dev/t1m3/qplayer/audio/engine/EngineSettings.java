package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.mix.Director;
import dev.t1m3.qplayer.audio.mix.Gapless;
import dev.t1m3.qplayer.audio.mix.Transition;
import dev.t1m3.qplayer.audio.mix.TransitionFactory;
import dev.t1m3.qplayer.audio.stem.StemSeparators;

/**
 * 播放引擎需要从设置里知道的那几件事，以及「这些设置对应哪个过渡引擎」。
 *
 * <p>为什么要抽成接口、而不是让引擎直接读 {@code SettingsCore}：
 *
 * <ul>
 *   <li>引擎的单元测试<strong>不必造一个真的 {@code SettingsCore}</strong>
 *       （它挂在 qml4j 的 {@code QObject} 上、要靠 {@code load(store, platform)}
 *       才把值播种好）。测试实现这个接口就够，见 {@code PlaybackEngineTest}。</li>
 *   <li>安卓宿主以后要换别的设置存储时，不必动引擎。</li>
 * </ul>
 *
 * <p>真源仍然是 {@code SettingsCatalog} 里那几个键，宿主用 {@link AutomixSettings}
 * 把 {@code SettingsCore} 桥过来（含越界值夹紧与缺省值）。
 */
public interface EngineSettings {

    /** {@code SettingsCatalog.AUTOMIX_MODE_KEY} 的取值（0 无缝 / 1 交叉淡化 / 2 声波重合 / 3 AI）。 */
    int automixMode();

    /** {@code SettingsCatalog.AUTOMIX_STYLE_KEY} 的取值（{@code MixStyle.UI_ORDER} 的下标，0 = 自动）。 */
    int automixStyle();

    /** 交叉淡化窗口时长（秒）。0 等价于硬接。 */
    float crossfadeSecs();

    /** 声波重合窗口时长（秒）。 */
    float harmonicSecs();

    /**
     * HiFi L1「位完美直通」是否开启。
     *
     * <p>它决定<strong>开流请求</strong>：开启时按源采样率（并带上源位深）请求输出，
     * 让设备跟随源——那是位完美的前提。同时按 ADR-003 的取舍，主增益保持单位值
     * （见 {@code PlaybackEngine.applyGain} 里那段注释）。
     */
    /**
     * 工作室处理链（三台机架）。
     *
     * <p>默认 {@link StudioRacks#EMPTY}（全关 = 直通，位完美不受影响）。
     * 实现方请<strong>缓存解析结果</strong>：引擎每块都要问一次，它靠对象引用是否变化
     * 判断「参数换没换」（见 {@code StudioProcessor}），每次都 new 一份会让它每块都重建滤波器。
     */
    default dev.t1m3.qplayer.audio.studio.StudioRacks audioStudio() {
        return dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY;
    }

    boolean bitPerfect();

    /**
     * HiFi L3「随曲切换采样率」是否开启。
     *
     * <p><strong>本版没有实现</strong>：按 docs/audio-engine.md §6，默认是「无缝优先」——
     * 整场播放只开一次流，跨采样率的另一首走我们自己的重采样器。这条设置只是读出来
     * 记在诊断里，换流要等后续按 ADR-003 §2.3 的取舍单独做（过渡窗口内重开流）。
     */
    boolean rateFollow();

    /**
     * 融合阶段的人声怎么处理：{@link StemSeparators#OFF 不分离} /
     * {@link StemSeparators#DSP DSP} / {@link StemSeparators#AI AI（模型）}。
     *
     * <p>默认 {@code OFF}（不分离），而且只有读得到设置时才可能不是它：读不到设置时
     * 不许承诺一个「要下载上百兆模型、要跑秒级推理」的能力——与
     * {@code ModelAvailability} 从 {@code false} 起步是同一条纪律。
     *
     * <p><strong>选了哪一档就用哪一档</strong>：AI 不可用时照常过渡但不去人声，
     * <b>不</b>自动改用 DSP 顶替（那是「换了个东西还不说」）。
     * 真正的门控在 {@code VocalSeparationTransition.prepare}。
     */
    default int automixVocalMode() {
        return StemSeparators.DEFAULT_MODE;
    }

    /**
     * 自动混音<strong>总开关</strong>（{@code SettingsCatalog.AUTOMIX_ENABLED_KEY}）。
     *
     * <p>关掉时：{@link #transitionFactory(int, int)} 一律造出窗口 0 的无缝拼接，
     * 于是 {@code Director} 既不 {@code prepare}（不烧 CPU）也不进过渡——
     * 「这个功能不存在」的等价物。<strong>关着的时候 {@link #automixMode()} 仍然如实
     * 报出用户存的那一档</strong>（那一档没被改掉，只是这次不生效），
     * 「读到的值」与「实际用的接法」因此可以分开看，不会互相撒谎。
     *
     * <p>默认 {@code true}：这条接口是后加的，只实现了取值方法的老实现类
     * （测试夹具、将来别的宿主）不该因为少写一个方法就<strong>静默失去</strong>它原来
     * 会做的过渡——那正是{@link #transitionFactory(int, int)} 的注释里记过的那次教训。
     * 「默认关」是 {@link AutomixSettings} 那一层的事（它读的是设置里的键，
     * 读不到设置时返回 false）。
     */
    default boolean automixEnabled() {
        return true;
    }

    /**
     * 「自动点位」开关（{@code SettingsCatalog.AUTOMIX_AUTO_CUE_KEY}）。
     *
     * <p>打开时，融合点不再是「当前曲的最后 N 秒」，而是<strong>扫两首整曲</strong>之后
     * 挑出来的位置（见 {@code AutoCue}）：出场曲可以提前收、进场曲可以从中段进。
     * 关着时一切与从前一样（固定窗口）。
     *
     * <p>默认 {@code false}：这是个要<strong>把两首歌整曲解一遍</strong>的功能
     * （多花几秒 CPU、多几百毫秒的准备时间），必须由用户明确打开。
     * 「读不到设置」同样是 false。
     */
    default boolean automixAutoCue() {
        return false;
    }

    /**
     * 造一个过渡工厂（采样率与声道数是<strong>协商到的输出配置</strong>）。
     *
     * <p><strong>默认实现就是 docs/audio-engine.md §4 那张表</strong>：接法完全由
     * {@link #automixEnabled()} 与 {@link #automixMode()}（配合 {@link #automixStyle()}
     * 与两个窗口时长）决定，与 {@link AutomixSettings#transitionFactory(int, int)} 共用
     * {@link AutomixSettings#factoryFor}，所以「模式 → 接法」在这一层只有一处真源。
     *
     * <p>从前这里的默认实现写死返回 {@link Gapless#INSTANCE}，后果不是「保守一点」而是
     * <strong>静默丢掉用户选的接法</strong>：只实现了取值方法、没实现工厂的实现类
     * （测试夹具、将来别的宿主都会这样）拿到的永远是无缝拼接——
     * {@code automixMode()} 报 1（交叉淡化）、实际按窗口 0 硬接，
     * 表现是「设置里的自动混音怎么调都没反应」，而且没有任何报错。
     * 默认值该覆盖的是「取值不合法」，不是无视取值本身：读不到设置时
     * {@code automixMode()} 就是 0，越界值在 {@link AutomixSettings#factoryFor} 里落到
     * {@code default} 分支，两者照样是 Gapless（窗口 0、不改写采样值，唯一与位完美兼容的那档）。
     *
     * <p>返回的工厂<strong>每次 {@code create()} 都必须造新实例</strong>——
     * 上游的生命周期约定（实例只服务一次过渡，准备线程上造新的，渲染路径因此不加锁）。
     */
    default TransitionFactory transitionFactory(final int rate, final int channels) {
        return AutomixSettings.factoryFor(automixEnabled(), automixMode(), automixStyle(),
                crossfadeSecs(), harmonicSecs(), automixVocalMode(), automixAutoCue(),
                rate, channels);
    }

    /**
     * 准备阶段的提前量（秒）：离曲终还有这么多时就开始后台准备。
     *
     * <p>默认就是 {@link Director#PREPARE_MARGIN_SECS}——对拍、判调性、分离人声都只要几秒，
     * 30 秒留了一个数量级的余量。<strong>要在准备阶段跑模型推理的接法必须把它调大</strong>
     * （原先最贵的一档是「生成式」，2026-10-01 整套删除；现在最贵的是自动点位与
     * AI 自动档的整曲扫描）：余量不够的后果不是卡顿
     * （准备是异步的，音频照常产出），而是<strong>这次过渡用不上生成段</strong>——
     * 结果还在算，触发点已经过去了，用户看到的就是「开了它却还是普通融合」。
     *
     * <p>调大<strong>没有</strong>同步时代的那个代价：准备一直是异步的，提前量只决定起跑时间。
     */
    default long prepareMarginSecs() {
        return Director.PREPARE_MARGIN_SECS;
    }
}

