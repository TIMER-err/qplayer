package dev.t1m3.qplayer.audio.decode;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 一首曲目的解码器：把压缩码流变成平面 f32 帧，填进调用方提供的 {@link AudioBuf}。
 *
 * <p>解码过程不分配内存（除首次遇到超大包时扩容一次内部暂存）。
 *
 * <h2>为什么这个包在 player-core，而实现不在</h2>
 *
 * <p>{@code sonalis-mix} 的 {@code Deck} 直接依赖 {@code sonalis_decode::Decoder}。QPlayer 的
 * 两个宿主解码底座完全不同——桌面端是 {@code jaudiotagger} + {@code jlayer} + {@code jflac}
 * （纯 Java），安卓端是 {@code MediaCodec}/{@code MediaExtractor}——所以这里只放
 * <strong>接口与工厂</strong>，实现由宿主在启动时安装（见 {@link #install(Opener)}）。
 * 这样 player-core 不必依赖任何一个平台的解码库，与上游把 decode 独立成 crate 是同一分层。
 *
 * <h2>从上游搬过来的两条硬约定</h2>
 *
 * <ol>
 *   <li><strong>无缝播放默认开启</strong>（补偿编码器延迟与填充）。这不是可选项——
 *       关了它，MP3/AAC 会带上编码器延迟产生的静音，过渡引擎拿到的位置就是错的</li>
 *   <li><strong>坏包跳过而不是中断</strong>。唱片抓轨的文件常有零星坏帧，
 *       为此停掉整首歌是不可接受的</li>
 * </ol>
 *
 * <p>对应 Sonalis 的 {@code sonalis_decode::Decoder}。差异：上游用 {@code &Path}，
 * 这里用 {@code String} 句柄——QPlayer 的音源可以是 http(s) URL（需要请求头），
 * 而过渡分析与 Deck 必须能打开同一个句柄。
 */
public interface Decoder extends AutoCloseable {

    /** 曲目事实。 */
    TrackSpec spec();

    /** 已解出的总帧数。 */
    long framesDecoded();

    /** 是否已到结尾。 */
    boolean isDrained();

    /**
     * 跳到指定位置（秒）。
     *
     * <p>返回<strong>实际</strong>跳到的位置。容器往往只能停在关键帧或包边界上，所以实际
     * 位置常常略早于请求位置——这个差别要如实回报，否则界面上的进度条会在松手后
     * 自己跳回去，看起来像 bug。
     *
     * <p>实现必须丢掉定位之前解出的暂存数据：留着它，跳转后会先放出一小段旧内容，
     * 听起来像「跳过去又弹回来」。
     */
    double seek(double secs);

    /**
     * 解码填满 {@code out}，返回写入的帧数。返回 0 表示已到结尾。
     *
     * <p>会一直解码到填满容量或流结束，所以调用方不必自己循环。
     *
     * <p>实现要处理「一包放不进调用方缓冲」的情况：包长与缓冲大小无关，
     * 没有暂存就只能在多出的部分丢弃——那正是「3 秒的文件只放出 2.67 秒」的根因。
     * 交付语义因此是：<strong>先清空暂存，再去解码新包</strong>。
     */
    int decodeInto(AudioBuf out);

    @Override
    void close();

    /** 宿主的解码器工厂。 */
    interface Opener {
        /** @param source 本地路径或 http(s) URL */
        Decoder open(String source, java.util.Map<String, String> headers);
    }

    /** 安装宿主的解码器工厂（桌面端 / 安卓端各一份，启动时调用一次）。 */
    static void install(Opener opener) {
        Holder.opener = opener;
    }

    /** 打开一个音源（本地路径或 http(s) URL）。 */
    static Decoder open(String source) {
        return open(source, java.util.Collections.<String, String>emptyMap());
    }

    /** 打开一个音源，带提供商要求的请求头（网易云直链需要）。 */
    static Decoder open(String source, java.util.Map<String, String> headers) {
        Opener opener = Holder.opener;
        if (opener == null) {
            throw new IllegalStateException(
                    "没有安装解码器工厂：宿主必须在启动时调用 Decoder.install(...)");
        }
        return opener.open(source, headers == null
                ? java.util.Collections.<String, String>emptyMap() : headers);
    }

    /** 工厂的持有者：放在单独的类里，避免接口上的静态字段被各实现类初始化顺序影响。 */
    final class Holder {
        static volatile Opener opener;

        private Holder() {
        }
    }
}
