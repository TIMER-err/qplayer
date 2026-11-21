package dev.t1m3.qplayer.audio.stem;

import dev.t1m3.qplayer.audio.model.InferException;
import dev.t1m3.qplayer.audio.model.StemModels;
import dev.t1m3.qplayer.audio.model.StemSeparator;

/**
 * 「这一档的分离器从哪来」的<b>唯一一处</b>选择入口。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>人声消除有三档（见 {@link #OFF} / {@link #DSP} / {@link #AI}），而它们的实现来源
 * 完全不同：DSP 那一档是 player-core 自己的纯 Java 实现，AI 那一档是宿主提供的
 * ONNX 推理（{@link StemModels}）。<b>用哪一档</b>是设置的事，<b>怎么拿到那个实现</b>
 * 是这里的事——包在过渡外面的那层（{@code VocalSeparationTransition}）因此
 * <strong>只认这个入口，不认识任何具体实现</strong>：
 *
 * <ul>
 *   <li>它不 {@code import} {@link DspStemSeparator}，也不判断「手上这个是哪个类」
 *       ——那种判断会让两条并行的实现互相绑死，而「加了第四档却忘了改判断」
 *       的表现是静默用错算法；</li>
 *   <li>它要的只有三件事：这一档现在能不能用（{@link #available}）、
 *       打不开时给人看的原因（{@link #unavailableReason}）、以及拿一个分离器
 *       （{@link #forMode}）；档名走 {@link #label}。</li>
 * </ul>
 *
 * <h2>三档的「能不能用」是三件不同的事</h2>
 *
 * <table border="1">
 *   <caption>可用性</caption>
 *   <tr><th>档</th><th>能不能用</th><th>打不开时怎么办</th></tr>
 *   <tr><td>{@link #OFF}</td><td>不适用（<b>调用方在套包装之前就把它挡掉</b>：
 *       不套包装接法，照常过渡）</td><td>—</td></tr>
 *   <tr><td>{@link #DSP}</td><td>几乎永远是「能」：纯 Java、没有权重、没有会话、
 *       不占常驻内存，所以设置里<b>不需要任何下载门控</b></td>
 *       <td>真出问题（装不上、算的时候抛异常）时照常过渡 + 如实标注</td></tr>
 *   <tr><td>{@link #AI}</td><td><b>只在桌面</b>（安卓不装 {@code StemModels.Opener}，
 *       所以移动端恒为不可用）；可用性看磁盘上有没有那份 htdemucs 权重
 *       （{@link StemModels#available()}，从 false 起步、每次现问）</td>
 *       <td>照常过渡但<b>不去人声</b> + 如实标注。
 *       <b>绝不用 DSP 顶替</b>：用户明确选了 AI，悄悄换一种算法就是
 *       「换了个东西还不说」——而这两种算法的听感差别很大</td></tr>
 * </table>
 *
 * <h2>取值就是设置里那个下拉的下标</h2>
 *
 * <p>{@link #OFF} / {@link #DSP} / {@link #AI} 的 0 / 1 / 2 <b>就是</b>
 * {@code SettingsCatalog.AUTOMIX_VOCAL_MODE_*} 的值（那三个常量直接引用这里，
 * 不另抄一份）。于是「下拉选中的下标」与「实际用的档」不可能分叉——
 * 分叉的表现是「选了 DSP 实际用模型」，而那是查不出来的那种错。
 */
public final class StemSeparators {

    /** 不分离：<b>不套包装接法</b>，照常过渡（默认档）。 */
    public static final int OFF = 0;

    /**
     * DSP：纯 Java 的中心声道（人声）抑制，{@link DspStemSeparator}。
     *
     * <p>没有权重、没有会话、没有推理运行时，所以它永远可用、也不占常驻内存——
     * 这一档在设置里不该被任何「请先下载模型」挡住。
     *
     * <p><b>耗时要说准</b>：它是 {@code O(帧数 · log N)} 的频域改写，本机实测 44.1 kHz
     * 立体声 <b>10 秒素材 215 ms、60 秒素材 1.2 s</b>（约 46 倍实时）——
     * 也就是过渡窗口那几秒只有几十毫秒，交给 {@code prepare} 毫无压力；
     * 但<b>「整首歌毫秒级」是不成立的</b>，它的用法从一开始就是「只对过渡窗口那几秒做」。
     */
    public static final int DSP = 1;

    /**
     * AI：htdemucs 分轨模型（{@link StemModels}），宿主提供推理实现、权重按需下载。
     *
     * <p><b>这一档只在桌面出现</b>（见下），因为安卓不安装 {@code StemModels.Opener}——
     * 那份推理（ONNX Runtime）在移动端没有底座，于是 {@link #available} 在手机上恒为 false。
     *
     * <p><b>实测代价（Java 进程内，当前那份焊死 3.9 s 窗口的模型）</b>，也就是用户应该预期的：
     * 峰值内存约 <b>1.2 GB</b>；分离耗时约等于窗口时长（3.9 s 窗口约 5.0 s、8 秒窗口约 9.1 s，
     * 都在默认 15 秒预算内）；<b>16 秒窗口 15.7 s + 建会话 1.6 s ≈ 17.3 s → 超预算</b>，
     * 那一次会被丢弃并如实降级成「不去人声」。所以<b>表现模式（32 拍，慢歌下窗口 16 s+）
     * 在 AI 模式下会退回不去人声</b>，其它接法正常。
     * 这组数字也写在设置页那一行的说明里（{@code settings.automixVocalMode.desc}），
     * 用户要在选之前看得到——藏起来比说出来糟得多。
     */
    public static final int AI = 2;

    /** 读不到设置时用的档：不分离（不承诺任何需要额外东西的能力）。 */
    public static final int DEFAULT_MODE = OFF;

    /**
     * DSP 那一档的实现工厂。
     *
     * <p>做成可换的接缝有两个理由：<b>可测</b>（测试要能把「DSP 算不出来」那条路
     * 钉住，而真的 DSP 实现是纯函数、几乎不会失败），以及<b>不绑死</b>
     * （宿主将来要换一份 DSP 实现时不必动这一层的调用方）。
     */
    public interface Opener {
        /** @return 一个 DSP 分离器；建不起来时抛 {@link InferException} */
        StemSeparator open();
    }

    /** 默认就是 player-core 自己那份纯 Java 实现。 */
    private static final Opener DEFAULT_DSP = new Opener() {
        @Override
        public StemSeparator open() {
            return new DspStemSeparator();
        }
    };

    /** <b>绝不为 null</b>：DSP 那一档的可用性不来自外部文件，所以没有「卸掉就不可用」这回事。 */
    private static volatile Opener dsp = DEFAULT_DSP;

    private StemSeparators() {
    }

    /** 换掉 DSP 那一档的实现（宿主/测试；传 {@code null} 回到默认实现）。 */
    public static void installDsp(Opener value) {
        dsp = value == null ? DEFAULT_DSP : value;
    }

    /** 回到默认的 DSP 实现（测试用；生产路径不需要）。 */
    public static void uninstallDsp() {
        dsp = DEFAULT_DSP;
    }

    /** 三个档里有没有这一档（越界的设置值要能被认出来，而不是当成某一档用）。 */
    public static boolean isKnownMode(int mode) {
        return mode == OFF || mode == DSP || mode == AI;
    }

    /**
     * 这一档给人看的名字（写进 {@code plan.note} 与日志）。
     *
     * <p>用档名而不是类名：用户看到的是「DSP」与「htdemucs」，
     * 而那是他在设置里选的那两个词。
     */
    public static String label(int mode) {
        switch (mode) {
            case DSP:
                return "DSP";
            case AI:
                return StemModels.MODEL_NAME;
            default:
                return "不分离";
        }
    }

    /**
     * 这一档现在能不能分出人声。
     *
     * <p>{@link #DSP} <b>恒为 true</b>：这一档不依赖任何外部文件（没有权重、没有会话、
     * 没有推理运行时），所以设置里不该有任何下载门控，这里也没有「缺东西」这种状态。
     * 真出问题只可能在<b>算的时候</b>（{@link #open} 或 {@code separate} 抛异常），
     * 那时由调用方照常过渡 + 如实标注。
     *
     * <p>{@link #AI} 现问磁盘（{@link StemModels#available()}：从 false 起步、
     * 下载落地后自愈）。{@link #OFF} 与越界值一律 false——「不分离」不是一个
     * 「能用但打不开」的分离器，它是「压根不套这一层」。
     */
    public static boolean available(int mode) {
        switch (mode) {
            case DSP:
                return true;
            case AI:
                return StemModels.available();
            default:
                return false;
        }
    }

    /**
     * {@link #available} 为假时，给人看的原因（会进 {@code plan.note}）。
     *
     * <p>放在这里而不是让调用方自己拼：原因与档位是一对一的，写两处迟早对不上，
     * 而对不上的表现是「用户照着提示去下载了一个用不上的东西」。
     */
    public static String unavailableReason(int mode) {
        switch (mode) {
            case DSP:
                // available(DSP) 恒为真，所以这句只在「将来有人给 DSP 加上可用性判断」
                // 时才取得到；留着是为了那一刻不必再想「该说什么」。
                return "DSP 分离器不可用";
            case AI:
                return StemModels.MODEL_NAME + " 模型不在磁盘上（需要在「模型」里下载）";
            default:
                return "模式 " + mode + " 不是分离器档（" + label(mode) + "），这一档不该被套上包装接法";
        }
    }

    /**
     * 打开这一档的分离器（<b>唯一的取分离器入口</b>）。
     *
     * <p>名字是「按档取一个」而不是 {@code open}：调用方手上拿的是一个<b>档位</b>
     * （设置里选的那个），它要问的是「这一档给我哪个分离器」——
     * 而 {@link Opener#open} 是<b>注入进来的那个实现</b>自己的工厂方法，两者不是一回事。
     *
     * @throws InferException 这一档不可用、实现建不起来，或 {@code mode} 不是分离器档。
     *                        <b>调用方（{@code prepare}）接住它、如实降级</b>，
     *                        绝不改去打开别的档。
     */
    public static StemSeparator forMode(int mode) {
        switch (mode) {
            case DSP:
                return dsp.open();
            case AI:
                return StemModels.open();
            default:
                // 越界值走这里。**不退回任何一档**：那会把「设置里写了个奇怪的值」
                // 变成「悄悄用了另一种算法」。
                throw InferException.plan("模式 " + mode + " 不是一个分离器档（"
                        + label(mode) + "）");
        }
    }
}
