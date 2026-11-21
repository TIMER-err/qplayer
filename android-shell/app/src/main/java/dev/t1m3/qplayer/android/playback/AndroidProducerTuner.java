package dev.t1m3.qplayer.android.playback;

import android.os.Process;

import dev.t1m3.qplayer.audio.engine.PlaybackEngine;
import dev.t1m3.qplayer.util.Logger;

/**
 * 把引擎的生产者线程提到「音频级」优先级的宿主注入（{@link PlaybackEngine.ThreadTuner}）。
 *
 * <h3>为什么这件事归宿主</h3>
 *
 * <p>{@code Process.setThreadPriority} 是 Android API，而 player-core 两端共用、
 * 不许出现任何 Android API（见 {@link PlaybackEngine.ThreadTuner} 的注释）。
 * 引擎只留一个 {@code tune(Thread)} 的口子，设成什么值是平台的事：
 * 桌面上什么都不用做，安卓上必须是
 * {@code THREAD_PRIORITY_URGENT_AUDIO}（-19）——生产者线程一旦被屏幕刷新 / GC / 别的
 * App 抢走 CPU，环形缓冲补不上就是欠载（听感是爆音），而它对面的写线程
 * （{@code AndroidOutputStream.pump}）已经是同一个优先级，两条必须对齐。
 *
 * <h3>为什么不在 tune() 里直接调 setThreadPriority</h3>
 *
 * <p>{@code Process.setThreadPriority(int)} 改的是<strong>当前线程</strong>，
 * 而引擎是在<strong>调用方线程</strong>（UI / 渲染线程）、生产者线程 {@code start()}
 * <em>之前</em>回调 {@code tune(producerThread)} 的
 * （见 {@code PlaybackEngine.ensureProducerThread}：先 new Thread、再 tune、最后 start）。
 * 在那个位置直接调它，被提到音频优先级的是<strong>调用 play() 的那条线程</strong>——
 * 比不调优更糟：UI 线程长期占着 -19 会跟音频线程和整个系统抢 CPU。
 *
 * <p>所以分两步：{@link #tune} 只<strong>登记</strong>「这条生产者线程要提优先级」，
 * 真正的提升交给 {@link #applyToCurrentThreadIfProducer()}——它在生产者线程<strong>自己身上</strong>
 * 被调用，一次 {@code setThreadPriority(-19)} 就精确落在目标线程。
 *
 * <p>那个调用点只有一个：{@code AndroidOutputDevice.openStream}（引擎开流那一步就跑在
 * 生产者线程上，而且一条流整场只开一次）。这是「宿主代码第一次在生产者线程上执行」的地方，
 * 位置比优先级本身更要紧——所以 {@code PlaybackEngine} 那边一行都不用改。
 */
public final class AndroidProducerTuner implements PlaybackEngine.ThreadTuner {

    /**
     * {@code Process.THREAD_PRIORITY_URGENT_AUDIO} 的取值（-19）。
     *
     * <p>单独写成一个常量是为了让 JVM 单测能钉住「参数确实是音频级」：
     * {@code Process.setThreadPriority} 在 JVM 上是桩（必定抛 {@code Stub!}），
     * 只有这个值是可断言的。
     */
    public static final int URGENT_AUDIO_PRIORITY = Process.THREAD_PRIORITY_URGENT_AUDIO;

    /** 是否有一条刚登记、还没提过优先级的生产者线程。每进程只有一个引擎，所以是静态的。 */
    private static volatile boolean pending;

    @Override
    public void tune(Thread producerThread) {
        if (producerThread == null) {
            return;
        }
        // 只登记，不动手：见类注释「为什么不在 tune() 里直接调 setThreadPriority」
        pending = true;
    }

    /**
     * 在<strong>生产者线程</strong>上调用（引擎开输出流那一步）。
     *
     * <p>没登记过时只是一次 volatile 读，代价可以忽略；提过一次标志就清了，
     * 不会反复调系统调用。
     *
     * <p>失败只记日志、不往外抛：线程调优是「多一层保障」，不是「能不能播」的前提。
     * 引擎自己也兜了一层（{@code ensureProducerThread} 里的 try/catch），这里再兜一层
     * 是因为 JVM（单测、以及任何非 Android 运行环境）上 {@code android.jar} 的桩方法
     * 必定抛 {@code RuntimeException: Stub!}，那条路径同样不该有事。
     */
    public static void applyToCurrentThreadIfProducer() {
        if (!pending) {
            return;
        }
        pending = false;
        try {
            Process.setThreadPriority(URGENT_AUDIO_PRIORITY);
            Logger.info("生产者线程已提到音频优先级（{}）：{}",
                    Integer.valueOf(URGENT_AUDIO_PRIORITY), Thread.currentThread().getName());
        } catch (Throwable e) {
            Logger.warn("生产者线程优先级注入失败（不影响播放）：{}", String.valueOf(e));
        }
    }

    /** 是否还欠着一次提升（单测用）。 */
    static boolean isPending() {
        return pending;
    }
}
