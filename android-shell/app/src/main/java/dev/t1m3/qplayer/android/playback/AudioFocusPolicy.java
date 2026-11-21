package dev.t1m3.qplayer.android.playback;

import android.media.AudioManager;

/**
 * 音频焦点的<strong>状态机</strong>（纯逻辑，不碰任何平台对象）。
 *
 * <p>这一层是从旧的 {@code AndroidAudioBackend}（{@code MediaPlayer} 那条链路）里
 * 原样抽出来的语义：{@code requestAudioFocus / abandonAudioFocusRequest} 那些调用是
 * 平台的事，但「哪种焦点变化该暂停、哪种该 duck、恢复要不要自动做」是<strong>策略</strong>，
 * 与后端换不换引擎无关。换引擎时最容易丢的就是这套策略（丢了的表现是：来电时音乐继续放，
 * 或者答完电话不回来），所以把它单独钉在这里，并用 JVM 单测锁住。
 *
 * <p>它只用到 {@link AudioManager} 的常量（编译期常量，会被内联），所以单测不需要真机、
 * 也不需要 AudioManager 实例。
 *
 * <h3>四种变化对应的取舍（与 MediaPlayer 时代一致）</h3>
 *
 * <table border="1">
 *   <caption>焦点变化 → 动作</caption>
 *   <tr><th>变化</th><th>动作</th><th>理由</th></tr>
 *   <tr><td>{@code AUDIOFOCUS_LOSS}</td><td>暂停，<strong>不</strong>自动恢复</td>
 *       <td>别人长期接管了（用户去开了另一个播放器）。自动抢回来是抢用户的操作。</td></tr>
 *   <tr><td>{@code AUDIOFOCUS_LOSS_TRANSIENT}</td><td>暂停，记住要恢复</td>
 *       <td>来电/导航播报这类短暂打断，结束后应当接着放。</td></tr>
 *   <tr><td>{@code AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}</td><td>压低电平（不暂停）</td>
 *       <td>通知音/导航提示：音乐继续，只是让一让。旧实现的比例是 0.3，见
 *       {@code AndroidPlaybackBackend.DUCK_FACTOR}。</td></tr>
 *   <tr><td>{@code AUDIOFOCUS_GAIN}</td><td>取消 duck；若之前是短暂丢失则恢复播放</td>
 *       <td>两件事可能同时要发生，所以动作用位标志返回，不是「一个动作」。</td></tr>
 * </table>
 *
 * <p>「正在出声」是唯一的输入条件：后端没在响的时候收到焦点变化，什么都不做
 * （旧实现也是这个判断——不这么判的话，用户在暂停状态下接个电话，回来音乐自己响了）。
 */
public final class AudioFocusPolicy {

    /** 没有动作。 */
    public static final int NONE = 0;

    /** 暂停播放（并告诉控制器：这不是用户按的暂停）。 */
    public static final int PAUSE = 1;

    /** 压低输出电平。 */
    public static final int DUCK = 2;

    /** 恢复被压低的输出电平。 */
    public static final int UNDUCK = 4;

    /** 恢复播放（并告诉控制器：这不是用户按的恢复）。 */
    public static final int RESUME = 8;

    /** 当前是否处于 duck 状态（音量被压低）。 */
    private boolean ducked;

    /** 是否欠着一次「焦点回来就恢复播放」。 */
    private boolean resumePending;

    /**
     * 处理一次焦点变化，返回要执行的动作（{@link #PAUSE} / {@link #DUCK} /
     * {@link #UNDUCK} / {@link #RESUME} 的按位或，可能是 {@link #NONE}）。
     *
     * @param change  {@code AudioManager.AUDIOFOCUS_*} 之一
     * @param playing 收到这次变化时后端是否真的在出声
     */
    public int onFocusChange(int change, boolean playing) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // 永久丢失：即便之前在等「短暂丢失」的恢复，也一笔勾销
                resumePending = false;
                int onLoss = NONE;
                if (ducked) {
                    // 旧的 MediaPlayer 实现在这里不清 duck，于是「duck 期间又被永久抢走焦点」
                    // 之后，用户手动恢复播放会一直卡在 30% 音量——因为 duck 只在 GAIN 里清，
                    // 而永久丢失之后 hasFocus 还是 true、不会重新申请焦点，也就再收不到 GAIN。
                    // 这里补掉：永久丢失本身就把 duck 撤掉。
                    ducked = false;
                    onLoss |= UNDUCK;
                }
                // 没在出声就什么都不做：暂停是幂等的，但没有必要通知控制器
                // （那会让界面把「用户自己暂停」翻成「被焦点暂停」）
                return playing ? (onLoss | PAUSE) : onLoss;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (!playing) {
                    return NONE;
                }
                // 只有真的暂停过才欠恢复，否则「用户暂停时来电」会让音乐在通话结束后自己响起来
                resumePending = true;
                return PAUSE;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (!playing || ducked) {
                    return NONE;
                }
                ducked = true;
                return DUCK;
            case AudioManager.AUDIOFOCUS_GAIN:
                int onGain = NONE;
                if (ducked) {
                    ducked = false;
                    onGain |= UNDUCK;
                }
                if (resumePending) {
                    resumePending = false;
                    onGain |= RESUME;
                }
                return onGain;
            default:
                // 未知变化一律不动：宁可少做，也不要凭猜测停掉用户的播放
                return NONE;
        }
    }

    /** 当前是否被 duck。 */
    public boolean isDucked() {
        return ducked;
    }

    /** 是否欠着一次「焦点回来就恢复播放」。 */
    public boolean isResumePending() {
        return resumePending;
    }

    /**
     * 复位（放弃焦点时调）。
     *
     * <p>必须复位：焦点已经还回去了，下一次 GAIN 之前不许再自动恢复播放，
     * 也不许留着「音量被压低」的状态跨过一整场播放。
     */
    public void reset() {
        ducked = false;
        resumePending = false;
    }
}
