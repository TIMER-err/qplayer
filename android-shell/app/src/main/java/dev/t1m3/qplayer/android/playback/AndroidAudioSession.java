package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import dev.t1m3.qplayer.util.Logger;

/**
 * 安卓的<strong>平台音频会话</strong>：音频焦点（{@link AudioFocusRequest}）与
 * 部分唤醒锁（{@link PowerManager.WakeLock}）。
 *
 * <h3>为什么它必须独立于「用哪个后端」</h3>
 *
 * <p>这两件事原来长在 {@code AndroidAudioBackend}（{@code MediaPlayer}）里：
 * 焦点是那段代码自己申请的，唤醒锁是 {@code MediaPlayer.setWakeMode} 顺带拿的。
 * 换到共用的 {@code PlaybackEngine} 之后，引擎是 player-core 的代码、<strong>不许出现
 * 任何 Android API</strong>，所以「焦点与唤醒锁」必须由宿主继续持有——
 * <strong>不能因为换引擎就把它们丢了</strong>：丢了的表现是真机上「来电时音乐继续放」、
 * 「锁屏后网络流播到一半断了」、「别的 App 放歌时两首都响」这些用户一眼能看出来的毛病。
 *
 * <p>所以旧的焦点那套实现（{@code requestAudioFocus} / {@code abandonAudioFocusRequest}
 * / 四种变化的处理）原样搬到这里，策略部分抽到纯逻辑的 {@link AudioFocusPolicy}，
 * 动作落到 {@link Sink}（由 {@code AndroidPlaybackBackend} 实现，转发给引擎）。
 *
 * <h3>唤醒锁为什么自己拿</h3>
 *
 * <p>旧实现靠 {@code MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)}：网络流的解码/缓冲
 * 需要 CPU 在屏幕熄灭后继续跑，否则部分 OEM ROM 会把它挂起，报一个播放错误，
 * 让控制器以为「这首放完了」跳到下一首。{@code AudioTrack} + 我们自己那条生产者线程
 * 没有这个开关，所以这里显式拿一把 {@code PARTIAL_WAKE_LOCK}。
 * 持有区间是<strong>「正在出声」</strong>：第一次 {@code play()} 拿到，暂停/被焦点暂停/
 * 释放时放掉——暂停还占着 CPU 只会白耗电。
 *
 * <h3>焦点在暂停时不还</h3>
 *
 * <p>与 MediaPlayer 时代一致：焦点从第一次播放持有到 {@link #release()}，
 * 暂停只放唤醒锁。这样「暂停 → 恢复」不会在中间把焦点让给别人再抢回来
 * （那一来一回在某些 ROM 上会让另一个 App 闪一下播放状态）。
 */
public final class AndroidAudioSession {

    /**
     * 焦点变化要落到谁身上。实现方（{@code AndroidPlaybackBackend}）只做转发，
     * 不含任何策略——策略在 {@link AudioFocusPolicy} 里。
     */
    public interface Sink {

        /** 后端此刻是否真的在出声。焦点策略唯一的外部输入。 */
        boolean isPlaying();

        /** 被焦点打断：暂停（<strong>不要</strong>在这里做「是否要恢复」的判断，那是策略的事）。 */
        void pauseForFocus();

        /** 焦点回来了：恢复播放。 */
        void resumeForFocus();

        /** 压低/恢复输出电平（不改变用户设定的音量）。 */
        void setDucked(boolean ducked);

        /** 告诉控制器「这次暂停是焦点导致的」，界面与媒体会话要跟着变。 */
        void notifyFocusPause();

        /** 告诉控制器「这次恢复是焦点导致的」。 */
        void notifyFocusResume();
    }

    private final AudioManager audioManager;
    private final PowerManager powerManager;
    private final Sink sink;
    private final AudioFocusPolicy policy = new AudioFocusPolicy();

    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private PowerManager.WakeLock wakeLock;
    private boolean wakeLockHeld;

    public AndroidAudioSession(Context context, Sink sink) {
        if (context == null) {
            throw new IllegalArgumentException("AndroidAudioSession 需要 Context");
        }
        if (sink == null) {
            throw new IllegalArgumentException("AndroidAudioSession 需要 Sink");
        }
        // 只留 application context：这个对象活得比 Activity 久（引擎是进程级的），
        // 抓着 Activity 不放就是一处泄漏
        Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        this.sink = sink;
        this.audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.powerManager = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
    }

    /**
     * 开始出声之前调：要焦点 + 拿唤醒锁。可重复调（幂等）。
     *
     * <p>用「要开始放」而不是「真的响了」作为时机是有意的：焦点申请是异步的，
     * 而扬声器不能等到焦点回来了才开流——开流本身要几百毫秒。
     */
    public synchronized void beginPlayback() {
        requestFocus();
        acquireWakeLock();
    }

    /** 暂停：只放唤醒锁，<strong>保留</strong>焦点（见类注释）。 */
    public synchronized void endPlayback() {
        releaseWakeLock();
    }

    /** 整场会话结束：还焦点、放唤醒锁、把策略状态复位。 */
    public synchronized void release() {
        abandonFocus();
        releaseWakeLock();
        policy.reset();
    }

    // --- 音频焦点 ----------------------------------------------------------

    private void requestFocus() {
        if (hasFocus || audioManager == null) {
            return;
        }
        try {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    // 显式指定主线程 Handler：焦点回调落在哪条线程取决于申请时那条线程
                    // 有没有 Looper（渲染线程就没有），而回调里要做的是暂停/恢复播放，
                    // 这种「看运气」的不确定性没有理由留着。
                    .setOnAudioFocusChangeListener(this::onFocusChange,
                            new Handler(Looper.getMainLooper()))
                    // 可以 duck 时我们要自己压低电平，不要让系统直接暂停（那会切掉歌声）
                    .setWillPauseWhenDucked(false)
                    .build();
            focusRequest = req;
            hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            Logger.info("音频焦点：{}", hasFocus ? "已获得" : "被拒绝");
        } catch (Throwable e) {
            // 拿不到焦点不该让播放起不来（顶多是被别的 App 抢的时候不礼让），如实记日志
            hasFocus = false;
            Logger.warn("申请音频焦点失败：{}", String.valueOf(e));
        }
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Throwable e) {
            Logger.warn("放弃音频焦点失败：{}", String.valueOf(e));
        }
        focusRequest = null;
        hasFocus = false;
    }

    /**
     * 焦点变化。动作由 {@link AudioFocusPolicy} 决定，这里只负责按顺序执行。
     *
     * <p>顺序与旧实现一致：先暂停/恢复，再通知控制器——反过来的话控制器会先改状态，
     * 中间那一刻它以为在放、实际没放（媒体会话那一帧的状态就是错的）。
     */
    private void onFocusChange(int change) {
        int actions;
        synchronized (this) {
            actions = policy.onFocusChange(change, sink.isPlaying());
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                // 永久丢失之后要允许重新申请：Android 的契约是「焦点不会再回来，
                // 想要就再 request 一次」。旧的自持有 hasFocus=true 会让用户点播放时
                // 不再申请焦点，于是和另一个还在放歌的 App 同时出声。
                hasFocus = false;
            }
        }
        Logger.info("音频焦点变化：change={} → 动作=0x{}", Integer.valueOf(change),
                Integer.toHexString(actions));
        if ((actions & AudioFocusPolicy.UNDUCK) != 0) {
            sink.setDucked(false);
        }
        if ((actions & AudioFocusPolicy.DUCK) != 0) {
            sink.setDucked(true);
        }
        if ((actions & AudioFocusPolicy.PAUSE) != 0) {
            sink.pauseForFocus();
            releaseWakeLock();
            sink.notifyFocusPause();
        }
        if ((actions & AudioFocusPolicy.RESUME) != 0) {
            acquireWakeLock();
            sink.resumeForFocus();
            sink.notifyFocusResume();
        }
    }

    // --- 唤醒锁 ------------------------------------------------------------

    private synchronized void acquireWakeLock() {
        if (wakeLockHeld || powerManager == null) {
            return;
        }
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "qplayer:playback");
                // 不计数：拿/放是一对一写的，计数模式下一处漏放就会让锁永远留着
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire();
            wakeLockHeld = true;
        } catch (Throwable e) {
            wakeLockHeld = false;
            Logger.warn("申请唤醒锁失败：{}", String.valueOf(e));
        }
    }

    private synchronized void releaseWakeLock() {
        if (!wakeLockHeld || wakeLock == null) {
            return;
        }
        wakeLockHeld = false;
        try {
            wakeLock.release();
        } catch (Throwable e) {
            Logger.warn("释放唤醒锁失败：{}", String.valueOf(e));
        }
    }

    /** 当前是否持有焦点（诊断用）。 */
    public synchronized boolean hasFocus() {
        return hasFocus;
    }

    /** 当前是否持有唤醒锁（诊断用）。 */
    public synchronized boolean isWakeLockHeld() {
        return wakeLockHeld;
    }
}
