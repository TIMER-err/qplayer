package dev.t1m3.qplayer.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;

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
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean resumeOnGain;
    private boolean ducked;

    public AndroidAudioBackend(Context ctx) {
        audioManager = (AudioManager) ctx.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public synchronized void play(String src, long startMs) {
        if (src == null || src.isEmpty()) return;
        releasePlayer();
        source = src;
        pendingSeekMs = Math.max(0L, startMs);
        wantPlay = true;
        prepared = false;
        requestFocus();

        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(p -> onPrepared());
        mp.setOnCompletionListener(p -> {
            Logger.info("MediaPlayer: completed");
            Runnable cb = onComplete;
            if (cb != null) cb.run();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            Logger.error("MediaPlayer error: what={} extra={}", what, extra);
            fire(onError);
            // Surface as completion so the controller can advance instead of stalling.
            Runnable cb = onComplete;
            if (cb != null) cb.run();
            return true;
        });
        player = mp;
        try {
            Logger.info("MediaPlayer: setDataSource + prepareAsync");
            mp.setDataSource(src);
            mp.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            Logger.error("MediaPlayer setDataSource failed: {}", e.getMessage());
            releasePlayer();
        }
    }

    private synchronized void onPrepared() {
        prepared = true;
        Logger.info("MediaPlayer: prepared, duration={}ms", player.getDuration());
        applyVolume();
        if (pendingSeekMs > 0L) {
            player.seekTo((int) pendingSeekMs);
        }
        if (wantPlay) {
            player.start();
            Runnable cb = onStarted;
            if (cb != null) cb.run();
        }
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
            player.seekTo((int) target);
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
            player.setVolume(volume, volume);
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
                    player.setVolume(volume * 0.3f, volume * 0.3f);
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
                player.reset();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        prepared = false;
    }
}
