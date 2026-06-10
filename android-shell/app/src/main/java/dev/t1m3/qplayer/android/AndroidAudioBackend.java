package dev.t1m3.qplayer.android;

import android.media.AudioAttributes;
import android.media.MediaPlayer;

import dev.t1m3.qplayer.audio.AudioBackend;

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

    @Override
    public synchronized void play(String src, long startMs) {
        if (src == null || src.isEmpty()) return;
        releasePlayer();
        source = src;
        pendingSeekMs = Math.max(0L, startMs);
        wantPlay = true;
        prepared = false;

        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(p -> onPrepared());
        mp.setOnCompletionListener(p -> {
            Runnable cb = onComplete;
            if (cb != null) cb.run();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            // Surface as completion so the controller can advance instead of stalling.
            Runnable cb = onComplete;
            if (cb != null) cb.run();
            return true;
        });
        player = mp;
        try {
            mp.setDataSource(src);
            mp.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            releasePlayer();
        }
    }

    private synchronized void onPrepared() {
        prepared = true;
        applyVolume();
        if (pendingSeekMs > 0L) {
            player.seekTo((int) pendingSeekMs);
        }
        if (wantPlay) {
            player.start();
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
    public synchronized void setOnComplete(Runnable callback) {
        this.onComplete = callback;
    }

    @Override
    public synchronized void release() {
        releasePlayer();
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
