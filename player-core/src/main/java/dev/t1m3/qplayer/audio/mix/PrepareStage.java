package dev.t1m3.qplayer.audio.mix;

/**
 * 准备线程<strong>此刻走到哪一步</strong>——只为「为什么这次过渡没发生」这一句日志。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>到了触发点却没有准备结果时，引擎会如实记一条
 * 「准备线程还在跑（分析/开流没完成）→ 结果没赶上触发点」。可在手机上（用户 2026-09-30：
 * 「有的歌曲过渡压根没有任何 automix，安卓那边我复制不出来 log」）这一句没有用：
 * 它说不清**卡在哪一步**——是整曲扫描（两百秒的 MP3 或一条直链）、是模型推理
 * （对拍 / 分轨，手机上最慢的两样）、还是分轨之后的移调。三种情况的修法完全不同
 * （等更久 / 换更省的路 / 干脆不用那一档）。
 *
 * <p>所以准备链上几个「可能很慢」的阶段各自在这里留一个记号，把读到的那一行
 * 「卡在：去人声（进场曲窗口）」写进日志——用户只要把那一句念出来就够了。
 *
 * <h3>为什么是静态的</h3>
 *
 * <p>准备链是一串包装接法（{@code VocalAware → VocalSeparation → AutoCue → AiMix}），
 * 每一层都只知道被自己包住的那一层；要给每一层都加一个「报进度」的参数，等于改
 * {@code Transition} 接口和所有实现。这里的用途只有诊断，静态记号最省事——
 * 与 {@code AutoCue.installLineSource}、{@code Decoder.install} 是同一类做法。
 *
 * <p><b>只写不读</b>：渲染路径一个字节都不读它（读的只有准备失败时那一条日志）。
 * 写的是 {@code volatile String}，写进去的是常量字符串，没有分配、没有锁。
 */
public final class PrepareStage {

    /** 没有准备在跑时的值（界面/日志看到它就知道「现在没有准备」）。 */
    public static final String IDLE = "（没有准备在跑）";

    private static volatile String stage = IDLE;

    private PrepareStage() {
    }

    /** 记下「现在走到哪一步了」（准备线程调；字符串请用常量，别在热路径拼）。 */
    public static void mark(String what) {
        stage = what == null ? IDLE : what;
    }

    /** 准备收工（成功、失败、被作废都算）时清掉。 */
    public static void clear() {
        stage = IDLE;
    }

    /** 现在走到哪一步了（给日志用）。 */
    public static String current() {
        return stage;
    }
}
