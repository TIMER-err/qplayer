package dev.t1m3.qplayer.android.recognize;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import dev.t1m3.qplayer.netease.AudioMatchClient;
import dev.t1m3.qplayer.util.Logger;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 听歌识曲's microphone loop (round 37): listen, fingerprint a 3 s window every second, ask
 * {@link AudioMatchClient}, and <b>stop the moment something matches</b>.
 *
 * <p>The numbers are the working clients' own, read off the official helper and the Android one:
 * <b>8 kHz mono</b> (the fingerprint engine's own rate — recording at 44.1 kHz and decimating gives
 * the same blob for more work and more battery), a <b>3 s</b> window, and a request window every
 * <b>1 s</b> so a match lands within a second of the song being recognisable. Before a window is
 * fingerprinted it is <b>gated on loudness</b> (RMS under {@link #MIN_RMS} means the room is quiet or
 * the phone is too far from the source, and a fingerprint of that is a wasted request) and
 * <b>normalised</b> toward {@link #TARGET_RMS} with the gain capped at {@link #MAX_GAIN}, so quietly
 * captured music still fingerprints while room noise is not amplified into one.
 *
 * <p>All callbacks arrive on this object's own thread — the caller marshals to the UI, which is what
 * the dialog does.
 */
public final class SongRecognizer {

    /** What the loop reports. Every method is called from the recogniser's thread. */
    public interface Listener {
        /** Still listening, {@code note} may carry why (too quiet, an attempt failed…) or be null. */
        void onListening(float seconds, String note);

        /** A window is being fingerprinted and matched right now. */
        void onMatching(float seconds);

        /** The song. The loop has already stopped and released the microphone by the time this is
         *  called. */
        void onFound(AudioMatchClient.Match match);

        /** Nothing matched, or the microphone could not be used. */
        void onFailed(String why);
    }

    /** The fingerprint engine's own rate: what {@code afp.wasm} wants and what the helpers record. */
    public static final int RATE = 8000;
    private static final int WINDOW_SAMPLES = RATE * 3;
    private static final int WINDOW_SECONDS = 3;
    private static final int CHUNK_SAMPLES = RATE / 10;          // 100 ms per read
    private static final long ATTEMPT_EVERY_MS = 1_000L;
    private static final long MAX_LISTEN_MS = 30_000L;
    private static final float MIN_RMS = 0.005f;
    private static final float TARGET_RMS = 0.1f;
    private static final float MAX_GAIN = 50f;

    /** 「一直停在正在听…」的两个兜底门槛（用户 2026-10-03 报的正是这个现象）。 */
    private static final int MAX_CONSECUTIVE_READ_FAILURES = 25;   // 每次失败 sleep 20 ms ≈ 0.5 s
    private static final long NO_WINDOW_TIMEOUT_MS = 15_000L;      // 一个 3 秒窗都凑不满
    private static final long NO_SAMPLES_TIMEOUT_MS = 5_000L;      // 一个采样都没回来

    private final AfpFingerprint fingerprint;
    private final AudioMatchClient client;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Thread thread;
    private volatile AudioRecord record;

    public SongRecognizer(AfpFingerprint fingerprint, Listener listener) {
        this(fingerprint, new AudioMatchClient(8_000), listener);
    }

    public SongRecognizer(AfpFingerprint fingerprint, AudioMatchClient client, Listener listener) {
        this.fingerprint = fingerprint;
        this.client = client;
        this.listener = listener;
    }

    /** Begin listening. Repeat calls are ignored; {@link #cancel} is what stops it. */
    public void start() {
        if (thread != null) return;
        cancelled.set(false);
        thread = new Thread(this::loop, "qplayer-recognize");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stop listening and release the microphone. Idempotent, and callable from any thread — the
     *  dialog's own cancel does exactly this. */
    public void cancel() {
        cancelled.set(true);
        AudioRecord current = record;
        if (current != null) {
            try {
                current.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped or never started; the loop's own finally releases it.
            }
        }
    }

    private void loop() {
        AudioRecord rec;
        try {
            rec = open();
        } catch (Throwable e) {
            // **open() 必须在 try 里**（2026-10-03 修）：`open()` 的兜底那一路是直接
            // `new AudioRecord(...)`，没有内层 try——它在「没有录音权限 / 设备没有麦克风 /
            // 这个 source 不被支持」时**会抛**。而原来 `open()` 是在 try **外面**调的，
            // 于是异常把识别线程直接打死、listener 一声不吭：界面上停在「正在听…」再也不动，
            // 用户报的「找不到麦克风没绷住」就是它。
            listener.onFailed("打不开麦克风：" + brief(e));
            return;
        }
        if (rec == null) {
            listener.onFailed("找不到可用的麦克风：检查系统「录音」权限，或换一台有麦克风的设备");
            return;
        }
        record = rec;
        boolean floatReads = rec.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT;
        float[] rolling = new float[WINDOW_SAMPLES];
        int filled = 0;
        float[] chunk = new float[CHUNK_SAMPLES];
        short[] shorts = floatReads ? null : new short[CHUNK_SAMPLES];
        long started = SystemClock.elapsedRealtime();
        long lastAttempt = 0L;
        long lastSampleAt = started;
        boolean windowFilledOnce = false;
        int readFailures = 0;
        int attempts = 0;
        try {
            rec.startRecording();
            while (!cancelled.get()) {
                int n = floatReads
                        ? rec.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING)
                        : rec.read(shorts, 0, shorts.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) {
                    // **读失败以前是彻底静默的**：只 sleep 一下继续转，既不上报也不放弃，
                    // 界面就永远停在「正在听…」（用户 2026-10-03 报的现象）。
                    // 现在：第一次把错误码写进日志，连续失败够多就如实失败退出。
                    readFailures++;
                    if (readFailures == 1) {
                        Logger.warn("microphone read returned {}（{}）——识别会在连续失败后放弃",
                                Integer.valueOf(n), readErrorName(n));
                    }
                    String stall = stallReason(readFailures, SystemClock.elapsedRealtime() - lastSampleAt,
                            SystemClock.elapsedRealtime() - started, windowFilledOnce);
                    if (stall != null) {
                        listener.onFailed(stall);
                        return;
                    }
                    sleep(20L);
                    continue;
                }
                readFailures = 0;
                lastSampleAt = SystemClock.elapsedRealtime();
                if (!floatReads) {
                    for (int i = 0; i < n; i++) chunk[i] = shorts[i] / 32768f;
                }
                filled = push(rolling, filled, chunk, n);
                long now = SystemClock.elapsedRealtime();
                float seconds = (now - started) / 1000f;
                if (filled >= WINDOW_SAMPLES) {
                    windowFilledOnce = true;
                } else {
                    // 采样是回来了，但一个 3 秒窗都没凑满（采样率对不上、设备只给到零头…）：
                    // 同样不能无限等——见 stallReason。
                    String stall = stallReason(readFailures, now - lastSampleAt, now - started,
                            windowFilledOnce);
                    if (stall != null) {
                        listener.onFailed(stall);
                        return;
                    }
                }
                if (filled < WINDOW_SAMPLES || now - lastAttempt < ATTEMPT_EVERY_MS) {
                    listener.onListening(seconds, null);
                    continue;
                }
                lastAttempt = now;
                float rms = rms(rolling);
                if (rms < MIN_RMS) {
                    listener.onListening(seconds, "太安静了，把手机靠近音源");
                    continue;
                }
                float[] window = Arrays.copyOf(rolling, WINDOW_SAMPLES);
                normalise(window, rms);
                attempts++;
                listener.onMatching(seconds);
                try {
                    String blob = fingerprint.of(window);
                    AudioMatchClient.Match match = client.match(blob, WINDOW_SECONDS);
                    if (match != null) {
                        listener.onFound(match);
                        return;
                    }
                    listener.onListening(seconds, "第 " + attempts + " 次没听出来…");
                } catch (Exception e) {
                    listener.onListening(seconds, "第 " + attempts + " 次失败："
                            + (e.getMessage() == null ? "未知错误" : e.getMessage()));
                }
                if (now - started > MAX_LISTEN_MS) {
                    listener.onFailed("没听出来，换个地方或换个音源再试一次");
                    return;
                }
            }
        } catch (Throwable e) {
            if (!cancelled.get()) {
                listener.onFailed("录音失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        } finally {
            release();
        }
    }

    /** Open the microphone at the fingerprint's rate; null when the device will not. */
    @SuppressLint("MissingPermission")
    private AudioRecord open() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        if (min > 0 && tryOpen(MediaRecorder.AudioSource.UNPROCESSED, min * 2) != null) {
            // The unprocessed source is what the official web demo asks for: no AGC, no noise
            // suppression, no echo cancellation mangling the fingerprint.
            return lastOpened;
        }
        min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        if (min > 0) {
            AudioRecord rec = tryOpen(MediaRecorder.AudioSource.VOICE_RECOGNITION, min * 2);
            if (rec != null) return rec;
            rec = tryOpen(MediaRecorder.AudioSource.MIC, min * 2);
            if (rec != null) return rec;
        }
        // 16-bit fallback for devices that do not offer float capture at 8 kHz.
        int min16 = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min16 <= 0) return null;
        try {
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min16 * 2);
            return rec.getState() == AudioRecord.STATE_INITIALIZED ? rec : null;
        } catch (Throwable e) {
            // 与上面几次 tryOpen 一致：打不开就是打不开，返回 null 交给调用方如实报，
            // 不让 SecurityException / IllegalArgumentException 冒到线程外面去。
            return null;
        }
    }

    /** Scratch for {@link #open}'s probes — AudioRecord cannot be asked "would this work?". */
    private AudioRecord lastOpened;

    @SuppressLint("MissingPermission")
    private AudioRecord tryOpen(int source, int buffer) {
        try {
            AudioRecord rec = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT, buffer);
            if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                lastOpened = rec;
                return rec;
            }
            rec.release();
        } catch (Throwable ignored) {
            // Fall through to the next source.
        }
        return null;
    }

    /**
     * <b>「一直停在正在听…」的兜底判定</b>（纯函数，判据见 android-shell 的
     * {@code SongRecognizerStallTest}）。
     *
     * <p>返回 {@code null} = 还正常，继续听；否则返回**要如实报给用户的那句话**。
     * 三种停住的情形：
     *
     * <ol>
     *   <li>连续读失败太多（麦克风被别的应用占着、被系统限制、录音源被关）——
     *       读回来的永远是负的错误码，以前只是 sleep 一下继续转；</li>
     *   <li>一个采样都没回来（设备/权限的软故障）；</li>
     *   <li>采样在回来、但一个 3 秒窗都凑不满（采样率对不上之类）——
     *       以前的表现同样是永远「正在听…」。</li>
     * </ol>
     */
    static String stallReason(int consecutiveReadFailures, long msWithoutSamples,
                              long msSinceStart, boolean windowFilledOnce) {
        if (consecutiveReadFailures >= MAX_CONSECUTIVE_READ_FAILURES) {
            return "读不到麦克风数据（连续 " + consecutiveReadFailures
                    + " 次读失败）——换个应用独占麦克风的话请先关掉它";
        }
        if (!windowFilledOnce && msWithoutSamples >= NO_SAMPLES_TIMEOUT_MS) {
            return "麦克风没有送回任何采样（" + (msWithoutSamples / 1000)
                    + " 秒）——检查系统「录音」权限，或换一台设备";
        }
        if (!windowFilledOnce && msSinceStart >= NO_WINDOW_TIMEOUT_MS) {
            return "麦克风送来的采样凑不满一个 3 秒窗（已 " + (msSinceStart / 1000)
                    + " 秒）——可能是采样率不被支持";
        }
        return null;
    }

    /** 读失败的错误码名字（日志用；AudioRecord 的负值常量）。 */
    private static String readErrorName(int code) {
        if (code == -3) return "ERROR_INVALID_OPERATION";
        if (code == -2) return "ERROR_BAD_VALUE";
        if (code == -6) return "ERROR_DEAD_OBJECT";
        if (code == -1) return "ERROR";
        if (code == 0) return "0 个采样";
        return "未知";
    }

    /** 异常的一句话说法（状态行只有一行，System 前缀与堆栈都不要）。 */
    private static String brief(Throwable e) {
        String message = e.getMessage();
        String what = message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
        return what.length() > 120 ? what.substring(0, 120) + "…" : what;
    }

    private void release() {
        AudioRecord rec = record;
        record = null;
        if (rec == null) return;
        try {
            if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) rec.stop();
        } catch (IllegalStateException ignored) {
            // Nothing to stop.
        }
        rec.release();
    }

    /** Append {@code n} samples, keeping the newest {@link #WINDOW_SAMPLES} of them. Returns how many
     *  samples the window holds (capped at its size). */
    private static int push(float[] rolling, int filled, float[] chunk, int n) {
        int size = rolling.length;
        if (n >= size) {
            System.arraycopy(chunk, n - size, rolling, 0, size);
            return size;
        }
        if (filled + n > size) {
            int drop = filled + n - size;
            System.arraycopy(rolling, drop, rolling, 0, filled - drop);
            filled -= drop;
        }
        System.arraycopy(chunk, 0, rolling, filled, n);
        return filled + n;
    }

    private static float rms(float[] samples) {
        double sum = 0d;
        for (float sample : samples) sum += (double) sample * sample;
        return (float) Math.sqrt(sum / samples.length);
    }

    /** Scale a quiet window up toward {@link #TARGET_RMS}, never by more than {@link #MAX_GAIN}:
     *  room noise must not be amplified into a fingerprint, and loud music must not be clipped into
     *  one either. */
    private static void normalise(float[] samples, float rms) {
        float gain = Math.min(MAX_GAIN, TARGET_RMS / Math.max(rms, 1e-6f));
        for (int i = 0; i < samples.length; i++) {
            samples[i] = Math.max(-1f, Math.min(1f, samples[i] * gain));
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
