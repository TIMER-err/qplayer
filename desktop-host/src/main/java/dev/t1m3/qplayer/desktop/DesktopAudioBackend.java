package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.desktop.audio.PcmSource;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * {@link AudioBackend} over OpenAL Soft (LWJGL). A single decoder thread owns
 * the OpenAL context, a streaming source and a small ring of buffers it keeps
 * topped up from a {@link PcmSource}; control methods mutate atomics the loop
 * polls. OpenAL replaces {@code javax.sound.sampled}, which has no usable Mixer
 * in the native image on macOS; the {@link PcmSource} decoders seek for real,
 * so dragging the progress bar no longer re-decodes from the start.
 *
 * <p>OpenAL is touched only from the audio thread (it is not safe to call
 * concurrently); the UI reads {@link #position()} off a volatile the loop
 * publishes.
 */
public final class DesktopAudioBackend implements AudioBackend {

    /** Buffer ring depth and per-buffer size. 4 × ~93 ms keeps a smooth feed
     *  while keeping seek/pause refill cheap. */
    private static final int NUM_BUFFERS = 4;
    private static final int CHUNK_BYTES = 16 * 1024;

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** -1 = no seek pending; ≥0 = jump to that ms on the next loop pass. */
    private final AtomicLong seekTargetMs = new AtomicLong(-1L);

    private volatile String source;
    private volatile float volume = 0.8f;
    private volatile Thread audioThread;
    private volatile Runnable onComplete;
    private volatile long positionMs = 0L;
    private volatile long durationMs = 0L;

    // OpenAL handles + current-track format. Audio-thread only.
    private long device = NULL;
    private long context = NULL;
    private int sourceId = 0;
    private int[] buffers;
    private int sampleRate = 44100;
    private int channels = 2;
    private int alFormat = AL_FORMAT_STEREO16;
    private ByteBuffer nativeChunk;
    private final byte[] stagingChunk = new byte[CHUNK_BYTES];
    private final ArrayDeque<Integer> queuedFrames = new ArrayDeque<>();
    private long seekBaseMs = 0L;
    private long framesSinceBase = 0L;

    @Override
    public void play(String src, long startMs) {
        if (src == null || src.isEmpty()) return;
        this.source = src;
        seekTargetMs.set(Math.max(0L, startMs));
        playing.set(true);
        ensureAudioThread();
    }

    @Override
    public void pause() {
        playing.set(false);
    }

    @Override
    public void resume() {
        if (source != null) playing.set(true);
    }

    @Override
    public boolean isPlaying() {
        return playing.get();
    }

    @Override
    public void seek(long ms) {
        seekTargetMs.set(Math.max(0L, ms));
    }

    @Override
    public long position() {
        return positionMs;
    }

    @Override
    public long duration() {
        return durationMs;
    }

    @Override
    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
    }

    @Override
    public void setOnComplete(Runnable callback) {
        this.onComplete = callback;
    }

    @Override
    public void release() {
        shuttingDown.set(true);
        playing.set(false);
        Thread t = audioThread;
        if (t != null) t.interrupt();
    }

    private void ensureAudioThread() {
        if (audioThread != null && audioThread.isAlive()) return;
        Thread t = new Thread(this::audioLoop, "qplayer-audio");
        t.setDaemon(true);
        audioThread = t;
        t.start();
    }

    private void audioLoop() {
        // The decoders resolve native libs / context CL just like the graphics
        // side already does; pin our class loader so anything ServiceLoader-y
        // resolves regardless of how the host launched us.
        Thread.currentThread().setContextClassLoader(DesktopAudioBackend.class.getClassLoader());
        try {
            initOpenAl();
        } catch (Throwable e) {
            Logger.exception(e);
            return;
        }
        while (!shuttingDown.get()) {
            boolean reachedEnd = false;
            try {
                reachedEnd = playCurrentSource();
            } catch (Throwable e) {
                Logger.exception(e);
                playing.set(false);
            }
            if (reachedEnd) {
                playing.set(false);
                Runnable cb = onComplete;
                if (cb != null) cb.run();
            }
            if (!playing.get() && seekTargetMs.get() < 0L && !shuttingDown.get()) {
                try {
                    Thread.sleep(60L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        releaseOpenAl();
    }

    /** Play {@link #source} until it ends, the source changes, or shutdown.
     *  @return true only on a natural end-of-track (so the loop fires onComplete). */
    private boolean playCurrentSource() throws Exception {
        String openSrc = source;
        if (openSrc == null) {
            playing.set(false);
            return false;
        }
        long startMs = Math.max(0L, seekTargetMs.getAndSet(-1L));
        PcmSource pcm = PcmSource.open(openSrc);
        try {
            sampleRate = pcm.sampleRate();
            channels = Math.max(1, Math.min(2, pcm.channels()));
            alFormat = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
            durationMs = pcm.durationMs();

            seekBaseMs = startMs > 0 ? pcm.seek(startMs) : 0L;
            boolean primed = primeFromCurrent(pcm);
            boolean draining = !primed;
            if (playing.get()) alSourcePlay(sourceId);

            while (!shuttingDown.get()) {
                // A different track requested → bail so the loop reopens it.
                if (!openSrc.equals(source)) return false;

                long seek = seekTargetMs.getAndSet(-1L);
                if (seek >= 0L) {
                    seekBaseMs = pcm.seek(seek);
                    draining = !primeFromCurrent(pcm);
                    if (playing.get()) alSourcePlay(sourceId);
                }

                alSourcef(sourceId, AL_GAIN, volume);

                if (!playing.get()) {
                    if (alGetSourcei(sourceId, AL_SOURCE_STATE) == AL_PLAYING) alSourcePause(sourceId);
                    publishPosition();
                    Thread.sleep(20L);
                    continue;
                }
                if (alGetSourcei(sourceId, AL_SOURCE_STATE) == AL_PAUSED) alSourcePlay(sourceId);

                // Recycle every buffer OpenAL is done with; refill unless drained.
                int processed = alGetSourcei(sourceId, AL_BUFFERS_PROCESSED);
                while (processed-- > 0) {
                    int buf = alSourceUnqueueBuffers(sourceId);
                    Integer frames = queuedFrames.pollFirst();
                    if (frames != null) framesSinceBase += frames;
                    if (!draining) {
                        int f = decodeInto(pcm, buf);
                        if (f > 0) {
                            alSourceQueueBuffers(sourceId, buf);
                            queuedFrames.addLast(f);
                        } else {
                            draining = true;
                        }
                    }
                }

                int queued = alGetSourcei(sourceId, AL_BUFFERS_QUEUED);
                int state = alGetSourcei(sourceId, AL_SOURCE_STATE);
                if (draining && queued == 0) {
                    publishPosition();
                    return true; // natural end of track
                }
                if (state == AL_STOPPED && queued > 0) {
                    alSourcePlay(sourceId); // underran — kick it back to life
                }

                publishPosition();
                Thread.sleep(8L);
            }
            return false;
        } finally {
            alSourceStop(sourceId);
            alSourcei(sourceId, AL_BUFFER, 0);
            queuedFrames.clear();
            pcm.close();
        }
    }

    /** Stop, drop the queue and fill the ring from the decoder's current spot.
     *  @return false if the decoder was already at EOF (nothing queued). */
    private boolean primeFromCurrent(PcmSource pcm) throws Exception {
        alSourceStop(sourceId);
        alSourcei(sourceId, AL_BUFFER, 0);
        queuedFrames.clear();
        framesSinceBase = 0L;
        boolean any = false;
        for (int b : buffers) {
            int f = decodeInto(pcm, b);
            if (f <= 0) break;
            alSourceQueueBuffers(sourceId, b);
            queuedFrames.addLast(f);
            any = true;
        }
        return any;
    }

    /** Decode one buffer's worth of PCM into {@code buf}. @return frame count, or 0 at EOF. */
    private int decodeInto(PcmSource pcm, int buf) throws Exception {
        int frameBytes = channels * 2;
        int want = CHUNK_BYTES - (CHUNK_BYTES % frameBytes);
        int got = 0;
        while (got < want) {
            int r = pcm.read(stagingChunk, got, want - got);
            if (r <= 0) break;
            got += r;
        }
        got -= got % frameBytes;
        if (got <= 0) return 0;
        nativeChunk.clear();
        nativeChunk.put(stagingChunk, 0, got).flip();
        alBufferData(buf, alFormat, nativeChunk, sampleRate);
        return got / frameBytes;
    }

    private void publishPosition() {
        int offset = alGetSourcei(sourceId, AL_SAMPLE_OFFSET); // frames into the head buffer
        long frames = framesSinceBase + Math.max(0, offset);
        positionMs = seekBaseMs + frames * 1000L / sampleRate;
    }

    private void initOpenAl() {
        device = alcOpenDevice((ByteBuffer) null);
        if (device == NULL) throw new IllegalStateException("OpenAL: no audio device");
        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        context = alcCreateContext(device, (IntBuffer) null);
        if (context == NULL || !alcMakeContextCurrent(context)) {
            throw new IllegalStateException("OpenAL: failed to create/make context current");
        }
        AL.createCapabilities(alcCaps);
        sourceId = alGenSources();
        buffers = new int[NUM_BUFFERS];
        for (int i = 0; i < NUM_BUFFERS; i++) buffers[i] = alGenBuffers();
        nativeChunk = MemoryUtil.memAlloc(CHUNK_BYTES);
    }

    private void releaseOpenAl() {
        try {
            if (sourceId != 0) {
                alSourceStop(sourceId);
                alSourcei(sourceId, AL_BUFFER, 0);
                alDeleteSources(sourceId);
            }
            if (buffers != null) {
                for (int b : buffers) alDeleteBuffers(b);
            }
            if (context != NULL) {
                alcMakeContextCurrent(NULL);
                alcDestroyContext(context);
            }
            if (device != NULL) alcCloseDevice(device);
        } catch (Throwable ignored) {
        } finally {
            if (nativeChunk != null) {
                MemoryUtil.memFree(nativeChunk);
                nativeChunk = null;
            }
        }
    }
}
