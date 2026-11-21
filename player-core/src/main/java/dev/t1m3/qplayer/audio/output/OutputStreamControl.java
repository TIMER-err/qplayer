package dev.t1m3.qplayer.audio.output;

/**
 * 一条已打开、由宿主实现的底层音频流。
 *
 * <p>这是 Java 侧的<strong>平台接缝</strong>：上游的 {@code cpal::Stream} 在 player-core 里
 * 没有等价物（{@code javax.sound.sampled} 在安卓上根本不存在），所以这里只留下
 * 输出层真正需要的三个动作，实现由宿主提供——桌面端是 {@code JavaSoundOutputStream}
 * （{@code SourceDataLine} + 自己的写线程），安卓端将是 {@code AudioTrack}。
 *
 * <p>生命周期与上游一致：{@link #play()} 在开流成功后调用一次，
 * {@link #close()} 由 {@link OutputHandle#close()} 负责，<strong>不关就是持续出声</strong>
 * （上游靠 Rust 的 {@code Drop} 自动停止；Java 没有析构，必须显式关）。
 */
public interface OutputStreamControl {

    /**
     * 启动播放。
     *
     * <p>上游在 {@code build} 里对 {@code stream.play()} 的失败直接报错
     * （{@code "启动音频流失败：…"}），不吞掉——一个开不响的流比没有流更误导人。
     *
     * @throws dev.t1m3.qplayer.audio.core.AudioException 启动失败
     */
    void play();

    /** 停止并释放底层资源。可重复调用。 */
    void close();

    /** 是否正在播放（给界面/诊断用，不参与音频逻辑）。 */
    boolean isRunning();
}
