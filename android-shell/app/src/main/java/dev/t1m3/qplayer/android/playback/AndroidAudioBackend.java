package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.PowerManager;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * {@link AudioBackend} over {@code android.media.MediaPlayer}. MediaPlayer
 * decodes local files and {@code http(s)} streams natively (mp3/flac/m4a/ogg),
 * so netease CDN urls and local tracks share one path — no SPI decoders, no
 * {@code javax.sound} (which Android lacks).
 *
 * <p>Prepared asynchronously: {@link #play} kicks off {@code prepareAsync} and
 * starts on the prepared callback, honoring the requested start offset.
 */
public final class AndroidAudioBackend implements AudioBackend {

    private MediaPlayer player;
    private String source;
    private long pendingSeekMs;
    private boolean wantPlay;
    private float volume = 0.8f;
    private boolean prepared;
    private Runnable onComplete;
    private Runnable onStarted;
    private Runnable onPaused;
    private Runnable onResumed;
    private Runnable onError;

    // Audio focus: pause on loss (call / other player), duck on transient-can-duck,
    // resume on regain when the loss was transient.
    private final AudioManager audioManager;
    private final Context appContext;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean resumeOnGain;
    private boolean ducked;

    public AndroidAudioBackend(Context ctx) {
        appContext = ctx.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public synchronized void play(String src, long startMs) {
        play(src, Collections.emptyMap(), startMs);
    }

    @Override
    public synchronized void play(String src, Map<String, String> headers, long startMs) {
        if (src == null || src.isEmpty()) return;
        releasePlayer();
        source = src;
        pendingSeekMs = Math.max(0L, startMs);
        wantPlay = true;
        prepared = false;
        requestFocus();

        MediaPlayer mp = new MediaPlayer();
        // Network-backed tracks still need the CPU while the screen is off. Without
        // MediaPlayer's partial wake lock some OEMs suspend the streaming/decoder
        // path, report a MEDIA_ERROR_* and make PlayerController skip to the next
        // track even though the current song has not actually reached its end.
        mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(this::onPrepared);
        mp.setOnCompletionListener(p -> onCompleted(p));
        mp.setOnErrorListener(this::onPlayerError);
        player = mp;
        try {
            Logger.info("MediaPlayer: setDataSource + prepareAsync");
            setDataSource(mp, src, headers);
            mp.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            Logger.error("MediaPlayer setDataSource failed: {}", e.getMessage());
            releasePlayer();
        }
    }

    /** A {@code content://} URI must be opened via the {@code (Context, Uri)}
     *  overload — the {@code String} overload has no calling context to resolve it
     *  and prepareAsync then fails async with extra=MEDIA_ERROR_SYSTEM (0x80000000).
     *  http(s) urls and plain file paths take the string overload. */
    private void setDataSource(MediaPlayer mp, String src, Map<String, String> headers) throws IOException {
        if (src.startsWith("content://")) {
            mp.setDataSource(appContext, Uri.parse(src));
        } else if ((src.startsWith("http://") || src.startsWith("https://"))
                && headers != null && !headers.isEmpty()) {
            mp.setDataSource(appContext, Uri.parse(src), headers);
        } else {
            mp.setDataSource(src);
        }
    }

    private synchronized void onPrepared(MediaPlayer preparedPlayer) {
        // releasePlayer() cannot prevent a callback that was already queued on the
        // media thread. Never let an old source start, publish onStarted, or apply its
        // state to the replacement MediaPlayer after a rapid track switch.
        if (player != preparedPlayer) return;
        prepared = true;
        Logger.info("MediaPlayer: prepared, duration={}ms", preparedPlayer.getDuration());
        applyVolume();
        if (pendingSeekMs > 0L) {
            preparedPlayer.seekTo(pendingSeekMs, MediaPlayer.SEEK_CLOSEST);
        }
        if (wantPlay) {
            preparedPlayer.start();
            Runnable cb = onStarted;
            if (cb != null) cb.run();
        }
    }

    private synchronized void onCompleted(MediaPlayer completedPlayer) {
        if (player != completedPlayer) return;
        Logger.info("MediaPlayer: completed");
        fire(onComplete);
    }

    private synchronized boolean onPlayerError(MediaPlayer failedPlayer, int what, int extra) {
        if (player != failedPlayer) return true;
        Logger.error("MediaPlayer error: what={} extra={}", what, extra);
        fire(onError);
        // PlayerController's error path retries stale URLs once and advances when
        // that fails. Firing completion too would race a second auto-advance against
        // that recovery and could replace the retried track.
        return true;
    }

    @Override
    public synchronized void pause() {
        wantPlay = false;
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    public synchronized void resume() {
        wantPlay = true;
        requestFocus();
        if (player != null && prepared) {
            player.start();
        }
    }

    @Override
    public synchronized boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    @Override
    public synchronized void seek(long ms) {
        long target = Math.max(0L, ms);
        if (player != null && prepared) {
            // SEEK_CLOSEST lands on the exact frame instead of the previous sync frame:
            // a basic seekTo undershoots by up to a keyframe interval, which made a
            // lyric tap (and the progress bar) land just before the target so the
            // PREVIOUS line highlighted.
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } else {
            pendingSeekMs = target;
        }
    }

    @Override
    public synchronized long position() {
        if (player != null && prepared) {
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0L;
            }
        }
        return 0L;
    }

    @Override
    public synchronized long duration() {
        if (player != null && prepared) {
            try {
                int d = player.getDuration();
                return d > 0 ? d : 0L;
            } catch (IllegalStateException e) {
                return 0L;
            }
        }
        return 0L;
    }

    @Override
    public synchronized void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyVolume();
    }

    private void applyVolume() {
        if (player != null && prepared) {
            float effective = ducked ? volume * 0.3f : volume;
            player.setVolume(effective, effective);
        }
    }

    @Override
    public synchronized void setOnStarted(Runnable callback) {
        this.onStarted = callback;
    }

    @Override
    public synchronized void setOnPaused(Runnable callback) {
        this.onPaused = callback;
    }

    @Override
    public synchronized void setOnResumed(Runnable callback) {
        this.onResumed = callback;
    }

    @Override
    public synchronized void setOnError(Runnable callback) {
        this.onError = callback;
    }

    @Override
    public synchronized void setOnComplete(Runnable callback) {
        this.onComplete = callback;
    }

    @Override
    public synchronized void release() {
        releasePlayer();
        abandonFocus();
    }

    // --- Audio focus ------------------------------------------------------

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .setWillPauseWhenDucked(false)
                .build();
        focusRequest = req;
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
        hasFocus = false;
        ducked = false;
        resumeOnGain = false;
    }

    private synchronized void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app took over for good: pause, don't auto-resume.
                resumeOnGain = false;
                if (player != null && prepared && player.isPlaying()) {
                    player.pause();
                    wantPlay = false;
                    fire(onPaused);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Call / brief interruption: pause and remember to resume on regain.
                if (player != null && prepared && player.isPlaying()) {
                    player.pause();
                    wantPlay = false;
                    resumeOnGain = true;
                    fire(onPaused);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null && prepared && player.isPlaying()) {
                    ducked = true;
                    applyVolume();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (ducked) {
                    ducked = false;
                    applyVolume();
                }
                if (resumeOnGain) {
                    resumeOnGain = false;
                    if (player != null && prepared) {
                        player.start();
                        wantPlay = true;
                        fire(onResumed);
                    }
                }
                break;
            default:
                break;
        }
    }

    private static void fire(Runnable r) {
        if (r != null) r.run();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                // Detach first so callbacks queued by reset/release cannot act on a
                // subsequently assigned player through the backend's shared fields.
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.reset();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        prepared = false;
    }
}
