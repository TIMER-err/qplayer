package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 一个能插进机架的处理模块。
 *
 * <h2>契约（每个模块都必须满足，判据也都建在这上面）</h2>
 *
 * <ol>
 *   <li><b>实时安全</b>：{@link #process(AudioBuf)} 跑在生产者线程上，
 *       <strong>逐块零分配</strong>（实测手段见 {@code StudioJsonAndRealtimeTest} 里的
 *       {@code ThreadMXBean} 那一条）、无锁、无 I/O、无日志；</li>
 *   <li><b>空闲即直通</b>：参数是「关」时 {@link #process} 必须<strong>一个采样都不碰</strong>
 *       （{@link #active()} 为 {@code false}），这是位完美承诺的前提；</li>
 *   <li><b>参数整体替换</b>：参数对象不可变，改参数 = 原子换引用（{@code volatile}）；
 *       换参数<strong>保留内部状态</strong>（滤波器/缓存的连续性），否则拖滑杆就是一串咔哒；</li>
 *   <li><b>延迟如实上报</b>：{@link #latencyFrames()} 返回本模块引入的延迟（0 = 无）。
 *       引擎把它记进延迟账（见 docs/audio-studio.md §4）；<strong>谎报等于让 A/B 错位</strong>。</li>
 * </ol>
 *
 * <h2>为什么做成接口</h2>
 *
 * <p>机架（{@code RackProcessor}）只按顺序调它们，不关心里面是什么；于是模块可以各自实现、
 * 各自带判据、各自被替换——这也是「工作站级」能一块一块加上来的原因。
 */
public interface StudioModule {

    /** 就地处理一块。空闲时必须直接返回。 */
    void process(AudioBuf buf);

    /** 现在会不会改动采样。 */
    boolean active();

    /** 清空内部状态（跳转、换曲、重开设备）。 */
    void reset();

    /**
     * 本模块引入的延迟（采样）。
     *
     * <p>没有前视/变换窗的模块返回 0；带窗口的（相位声码器、FFT 分块）必须如实返回，
     * 否则引擎那份延迟账会把两首曲子错开。
     */
    int latencyFrames();
}
