package dev.t1m3.qplayer.audio;

/**
 * Platform audio playback primitive. Plays one source at a time and signals
 * completion; playlist advancement is the controller's job, not the backend's.
 *
 * <p>Desktop implements this over {@code javax.sound.sampled} + SPI decoders;
 * Android over {@code android.media.MediaPlayer}. Neither type leaks into
 * player-core, which only depends on this interface.
 *
 * <p>All methods are safe to call from the controller / UI thread; the impl
 * serialises onto its own audio thread as needed.
 */
public interface AudioBackend {

    /**
     * Begin playing {@code source} (a local file path or an {@code http(s)}
     * URL) from {@code startMs}. Replaces any current playback. Idempotent
     * enough to double as "restart": calling with the same source reopens it.
     */
    void play(String source, long startMs);

    /** Pause without releasing the source. */
    void pause();

    /** Resume after {@link #pause()}. No-op if nothing is loaded. */
    void resume();

    boolean isPlaying();

    /** Absolute seek within the current source. */
    void seek(long ms);

    /** Current play head in milliseconds (song time, seek-relative). */
    long position();

    /** Duration of the current source in ms, or 0 if unknown. */
    long duration();

    /** Linear gain 0.0–1.0. */
    void setVolume(float volume);

    /**
     * Callback invoked when the current source plays through to its end
     * (not on pause/seek/manual replace). May fire on the audio thread —
     * the controller marshals to the render thread itself.
     */
    void setOnComplete(Runnable callback);

    /**
     * Callback invoked when playback actually begins (e.g. after an async prepare),
     * so a media session can re-baseline its reported position. May fire on the
     * audio thread. Default no-op for backends that don't need it.
     */
    default void setOnStarted(Runnable callback) { }

    /**
     * Callbacks for playback paused / resumed by the backend itself (not the user) —
     * e.g. losing or regaining audio focus on a phone call. Let the controller keep
     * its intended-play state and the media session in sync. Default no-op.
     */
    default void setOnPaused(Runnable callback) { }

    default void setOnResumed(Runnable callback) { }

    /**
     * Callback invoked when the backend encounters a playback error (e.g.
     * MediaPlayer error, invalid source). May fire on the audio thread.
     * Default no-op.
     */
    default void setOnError(Runnable callback) { }

    /** Stop playback and free native resources. */
    void release();
}
