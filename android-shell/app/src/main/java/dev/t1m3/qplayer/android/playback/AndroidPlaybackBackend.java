package dev.t1m3.qplayer.android.playback;

import android.content.Context;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.engine.PlaybackEngine;
import dev.t1m3.qplayer.util.Logger;

import java.util.Collections;
import java.util.Map;

/**
 * 安卓宿主的 {@link AudioBackend}：里面就是共用播放引擎
 * {@link PlaybackEngine}（{@code Director} + {@code Deck} 跑混音与 automix），
 * 外面套一层安卓的平台会话——音频焦点与唤醒锁（{@link AndroidAudioSession}）。
 *
 * <h3>这个类为什么存在（而不是直接把 PlaybackEngine 交给控制器）</h3>
 *
 * <p>三件事 player-core 做不了，必须由宿主在中间接住：
 *
 * <ol>
 *   <li><strong>焦点驱动的暂停/恢复</strong>：引擎完全不知道「焦点」这回事
 *       （那是平台概念），它只会 {@code pause()} / {@code resume()}。而控制器要的
 *       {@link #setOnPaused} / {@link #setOnResumed} 是「不是用户按的暂停」这类事件——
 *       引擎自己从不触发它们（它的两个字段留给了宿主）。所以由这一层保存控制器注册的
 *       回调，在焦点事件里发出去。</li>
 *   <li><strong>duck（压低电平）</strong>：引擎只有一条音量通路（deck 的增益），
 *       没有「临时压低」的概念。这里把「用户音量」和「被 duck」分开存，
 *       实际施加的是两者的乘积——用户的音量值本身不会被 duck 改掉
 *       （否则接完电话音量就永久变了）。</li>
 *   <li><strong>平台会话</strong>：焦点申请与唤醒锁（见 {@link AndroidAudioSession}）。</li>
 * </ol>
 *
 * <p>旧实现（{@code AndroidAudioBackend}）把这三件事和 {@code MediaPlayer} 揉在一个类里，
 * 所以删 MediaPlayer 时很容易连焦点一起删掉。现在边界是清楚的：
 * <strong>播放逻辑在 player-core，平台生命周期在这里。</strong>
 *
 * <h3>已知缺口（照实记）</h3>
 *
 * <p>{@link AudioBackend#play(String, Map, long)} 带的 {@code headers}
 * （插件直链需要）在引擎的混音层<strong>没有通路</strong>：{@code Deck}/{@code Director}
 * 只收音源句柄，引擎只会记一条警告。这是 player-core 的现状（见
 * {@code PlaybackEngine} 的类注释），不是这里漏传——所以这里照样把 headers 递给引擎，
 * 让那条警告出现在日志里，而不是在宿主这边悄悄吞掉。
 */
public final class AndroidPlaybackBackend implements AudioBackend, AndroidAudioSession.Sink {

    /**
     * 被 duck 时施加到输出上的比例。
     *
     * <p>与 MediaPlayer 时代同一个值（0.3）：只压低、不静音——系统给的语义是
     * 「CAN_DUCK：让一让」，静音就成了暂停。
     */
    static final float DUCK_FACTOR = 0.3f;

    private final PlaybackEngine engine;
    private final AndroidAudioSession session;

    /** 用户设定的音量（0..1）。duck 只影响实际施加的值，不改它。 */
    private volatile float volume = 1.0f;

    /** 当前是否被 duck。焦点回调线程写、音量线程读，所以是 volatile。 */
    private volatile boolean ducked;

    /** 控制器注册的「焦点导致的暂停/恢复」回调（引擎不发这两个，见类注释）。 */
    private volatile Runnable onPaused;
    private volatile Runnable onResumed;

    public AndroidPlaybackBackend(Context context) {
        engine = new PlaybackEngine();
        // 线程优先级是平台的事：引擎留口子、宿主注入（见 AndroidProducerTuner）
        engine.setThreadTuner(new AndroidProducerTuner());
        session = new AndroidAudioSession(context, this);
    }

    /**
     * 引擎本体：设置注入（{@code setSettings(new AutomixSettings(settings))}）与
     * 诊断（{@code pathState()} / {@code transitionName()} / {@code outputConfig()}）
     * 都走它。
     *
     * <p>不要在它上面注册回调——那些要经过这一层（见类注释）。
     */
    public PlaybackEngine engine() {
        return engine;
    }

    /** 平台会话（焦点 / 唤醒锁）的状态，诊断用。 */
    public AndroidAudioSession session() {
        return session;
    }

    // --- AudioBackend ------------------------------------------------------

    @Override
    public void play(String source, long startMs) {
        play(source, Collections.<String, String>emptyMap(), startMs);
    }

    @Override
    public void play(String source, Map<String, String> headers, long startMs) {
        if (source == null || source.isEmpty()) {
            return;
        }
        // 先要焦点、再开流：开流要几百毫秒，等焦点回来才开只会让起始更慢
        session.beginPlayback();
        engine.play(source, headers, startMs);
    }

    @Override
    public void pause() {
        engine.pause();
        // 暂停放掉唤醒锁，但保留焦点（见 AndroidAudioSession 的类注释）
        session.endPlayback();
    }

    @Override
    public void resume() {
        session.beginPlayback();
        engine.resume();
    }

    @Override
    public boolean isPlaying() {
        return engine.isPlaying();
    }

    @Override
    public void seek(long ms) {
        engine.seek(ms);
    }

    @Override
    public long position() {
        return engine.position();
    }

    @Override
    public long duration() {
        return engine.duration();
    }

    @Override
    public void setVolume(float v) {
        volume = clamp(v);
        applyVolume();
    }

    @Override
    public void setOnComplete(Runnable callback) {
        engine.setOnComplete(callback);
    }

    @Override
    public void setOnStarted(Runnable callback) {
        engine.setOnStarted(callback);
    }

    @Override
    public void setOnError(Runnable callback) {
        engine.setOnError(callback);
    }

    /** 焦点导致的暂停由本层发（引擎不发，见类注释）。 */
    @Override
    public void setOnPaused(Runnable callback) {
        this.onPaused = callback;
    }

    @Override
    public void setOnResumed(Runnable callback) {
        this.onResumed = callback;
    }

    /**
     * 过渡开始（进场曲已经开始出声）这一类事件同样要转发给引擎。
     *
     * <p>漏了这一条不会有任何报错：{@code AudioBackend} 上它是 {@code default} 空实现，
     * 于是安卓端界面就永远等曲终才推进——手机上「过渡一开始界面就跳下一首」那条
     * 等于没生效，而桌面端是好的（那台宿主转发了）。这类「接口加了默认实现、某个宿主
     * 忘了转发」的缺口只能靠真机/对读发现。
     */
    @Override
    public void setOnTransitionStart(java.util.function.Consumer<String> callback) {
        engine.setOnTransitionStart(callback);
    }

    /**
     * 引擎现在放的是哪一份句柄——<strong>这条必须转发</strong>。
     *
     * <p>控制器用它核对「这次曲终是不是过渡开始时已经推进过的那一首的交接」。
     * 漏了它的后果不是「少个诊断」，而是**每遇到一次自动交接就多推一首队列**：
     * 刚交接过去的进场曲会被当成已播完而跳掉（听感「放了几秒又跳」）。
     * 2026-09-20 安卓真机实测就是这个原因（接口上它当时是个 {@code default} 返回 null）。
     */
    @Override
    public String currentSource() {
        return engine.currentSource();
    }

    /**
     * 预告下一首，<strong>连同它需要的请求头</strong>。
     *
     * <h3>为什么这一条必须显式转发（2026-09-26 真机报告）</h3>
     *
     * <p>{@code AudioBackend} 上带 headers 的那条重载是 {@code default}——它的默认实现
     * <strong>丢掉请求头</strong>转调老签名。这里以前只实现了老签名，于是安卓上这条通路
     * 整个是断的：控制器明明把 token/Referer 交了下来，引擎拿到的是一张空表。
     *
     * <p>后果正好落在用户报的那两个症状上（插件的直链必须带 token/Referer）：
     *
     * <ul>
     *   <li><strong>自动点位（要扫整曲）</strong>：扫不动就退回固定点位，静默；
     *       更糟的是扫一条带签名的直链会**把整首歌再下一遍**，几十秒里准备线程收不了工，
     *       而 {@code Director} 在触发点没有准备结果时**刻意不开始过渡**——
     *       于是「开了自动点位就完全没过渡」；</li>
     *   <li><strong>开进场流</strong>：{@code PrepareWorker} 打开队首时用的是同一份头，
     *       打不开就是「跳过了 xxx」→ 这一次过渡没有进场曲可用。</li>
     * </ul>
     *
     * <p>与 {@link #currentSource()} / {@code setOnTransitionStart} 同一类缺口：
     * 「接口加了默认实现、某个宿主忘了转发」。区别是那两条当时直接改成了抽象方法让编译器
     * 拦截，而这条重载有默认实现（别的后端可以真的不需要头），所以只能靠这一条注释与
     * {@code AndroidPlaybackBackendTest} 里的断言守住。
     */
    @Override
    public void setNextSource(String source, Map<String, String> headers, long startMs) {
        engine.setNextSource(source, headers, startMs);
    }

    @Override
    public void setNextSource(String source, long startMs) {
        engine.setNextSource(source, startMs);
    }

    /**
     * 确认一次自动交接（引擎只认账、不重开放）。
     *
     * <p>必须转发：不转发的话引擎那枚一次性令牌一直挂着，用户下一次点这一首会被静默吃掉
     * （点了没反应）。{@code AudioBackend} 上它是抽象方法，漏了编译器就报错——这一条
     * 是那次「抽象化」决定的收益。
     */
    @Override
    public void confirmHandoff(String source) {
        engine.confirmHandoff(source);
    }

    /**
     * 工作室页的实时数据（频谱 / 表头 / 控制室）也必须转发。
     *
     * <p>这四条在 {@code AudioBackend} 上都有默认实现（空频谱、静音表头、IDLE 快照），
     * 所以漏转发<strong>不会报任何错</strong>：桌面宿主直接用 {@code PlaybackEngine}，
     * 一切正常；安卓这边套了一层，工作室页的「实时频谱」就永远是空的、表头永远是 -120、
     * 控制室永远显示「现在没有在过渡」——用户看到的是「这页是不是没做完」。
     *
     * <p>与 {@link #setNextSource(String, Map, long)} 同一类缺口（接口有默认实现 + 宿主忘转发），
     * 由 {@code AndroidAutomixWiringTest} 做源码级检查守住。
     */
    @Override
    public int studioSpectrum(float[] dst) {
        return engine.studioSpectrum(dst);
    }

    @Override
    public dev.t1m3.qplayer.audio.studio.StudioMeters.Snapshot studioMeters() {
        return engine.studioMeters();
    }

    @Override
    public float studioGainReductionDb() {
        return engine.studioGainReductionDb();
    }

    @Override
    public dev.t1m3.qplayer.audio.engine.TransitionSnapshot transitionSnapshot() {
        return engine.transitionSnapshot();
    }

    /**
     * 界面在不在看工作室页：这一条也要转发，否则安卓端的表头/频谱永远不算
     * （页面上的两块数据会一直停着不动）。
     */
    @Override
    public void setStudioMonitoring(boolean on) {
        engine.setStudioMonitoring(on);
    }

    @Override
    public void clearNextSource() {
        engine.clearNextSource();
    }

    @Override
    public void release() {
        engine.release();
        session.release();
    }

    // --- AndroidAudioSession.Sink -----------------------------------------

    @Override
    public void pauseForFocus() {
        engine.pause();
    }

    @Override
    public void resumeForFocus() {
        engine.resume();
    }

    @Override
    public void setDucked(boolean value) {
        ducked = value;
        // duck 必须自己把值施加下去：控制器的恢复路径只改它自己的状态，
        // 不会顺手再调一次 setVolume
        applyVolume();
    }

    @Override
    public void notifyFocusPause() {
        Logger.info("焦点丢失：已暂停播放");
        fire(onPaused);
    }

    @Override
    public void notifyFocusResume() {
        Logger.info("焦点恢复：继续播放");
        fire(onResumed);
    }

    // --- 内部 --------------------------------------------------------------

    /**
     * 实际施加到输出上的音量 = 用户音量 × duck 系数。
     *
     * <p>它落到引擎的 deck 增益上（不是改采样值）：增益一旦不是单位值，
     * {@code PlaybackEngine.pathState()} 会如实报「经过处理」——位完美是算出来的，
     * 开了 duck 就不是位完美，这是对的。
     *
     * <p>HiFi「位完美直通」开着时引擎<strong>有意</strong>不施加用户音量
     * （ADR-003：HiFi 路径旁路一切电平处理，音量交给系统/硬件），
     * 所以那时 duck 也不起作用——这是同一条取舍，不是这里漏做。
     */
    private void applyVolume() {
        float v = volume;
        engine.setVolume(ducked ? v * DUCK_FACTOR : v);
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) {
            return 1.0f;
        }
        return Math.max(0f, Math.min(1f, v));
    }

    private static void fire(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (Throwable e) {
            // 回调抛异常绝不能影响焦点处理本身（那些动作已经做完了）
            Logger.error("播放回调抛异常：{}", String.valueOf(e));
        }
    }

    @Override
    public String toString() {
        return "AndroidPlaybackBackend{" + engine + ", ducked=" + ducked + '}';
    }
}
