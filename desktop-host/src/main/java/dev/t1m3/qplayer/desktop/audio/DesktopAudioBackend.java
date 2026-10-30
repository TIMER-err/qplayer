package dev.t1m3.qplayer.desktop.audio;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.SOFTReopenDevice;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER;
import static org.lwjgl.openal.EXTDisconnect.ALC_CONNECTED;
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

    /** Buffer ring depth and per-buffer size. primeFromCurrent() fills every
     *  buffer before alSourcePlay() is called, so this ring is also the
     *  pre-read buffer: 16 x ~93ms (each ~93ms at 44.1kHz/16-bit stereo)
     *  totals ~1.5s of decoded audio queued before a track actually starts
     *  sounding -- start-up still primes that many so the first note isn't
     *  delayed. Extra slots fill while playing whenever the download is
     *  ahead, building up to ~4.5s of cushion against a mid-song stall.
     *  Fine-grained per-buffer so seek/pause refill stays cheap.
     */
    private static final int NUM_BUFFERS = 48;
    private static final int PRIME_BUFFERS = 16;
    private static final int CHUNK_BYTES = 16 * 1024;
    private static final long DEVICE_CHECK_NANOS = 400_000_000L;

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** -1 = no seek pending; ≥0 = jump to that ms on the next loop pass. */
    private final AtomicLong seekTargetMs = new AtomicLong(-1L);
    /** Distinguishes rapid replacements even when the same path/URL is replayed. */
    private final AtomicLong sourceRevision = new AtomicLong();

    private volatile String source;
    private volatile Map<String, String> sourceHeaders = Collections.emptyMap();
    private volatile float volume = 0.8f;
    private volatile Thread audioThread;
    private volatile Runnable onComplete;
    private volatile Runnable onStarted;
    private volatile Runnable onError;
    /** Frozen at play(), guarded by this, not replaced by setters for a later request. */
    private Runnable requestOnComplete;
    private Runnable requestOnStarted;
    private Runnable requestOnError;
    private volatile long positionMs = 0L;
    private volatile long durationMs = 0L;
    /** Published before decoder construction; controls cancel IO, never native decoders. */
    private volatile SeekableByteSource activeBytes;
    private volatile SeekableByteSource liveBytesInUse;

    // OpenAL handles + current-track format. Audio-thread only.
    private long device = NULL;
    private long context = NULL;
    private int sourceId = 0;
    private int[] buffers;
    private int sampleRate = 44100;
    private int channels = 2;
    private int alFormat = AL_FORMAT_STEREO16;
    private boolean reopenDeviceSupported;
    private boolean disconnectSupported;
    private boolean outputNeedsReprime;
    private String lastDefaultDevice = "";
    private long lastDeviceCheckNanos;
    private ByteBuffer nativeChunk;
    private final byte[] stagingChunk = new byte[CHUNK_BYTES];
    private final ArrayDeque<Integer> queuedFrames = new ArrayDeque<>();
    /** OpenAL buffers waiting to be filled. Starved fills leave them here
     *  instead of treating a network hiccup as end-of-track. */
    private final ArrayDeque<Integer> idleBuffers = new ArrayDeque<>();
    private long seekBaseMs = 0L;
    private long framesSinceBase = 0L;
    private volatile String cachePendingPath;
    private volatile String cacheCompletePath;

    @Override
    public void play(String src, long startMs) {
        play(src, Collections.emptyMap(), startMs);
    }

    @Override
    public synchronized void play(String src, Map<String, String> headers, long startMs) {
        if (src == null || src.isEmpty()) return;
        long target = Math.max(0L, startMs);
        this.source = src;
        this.sourceHeaders = headers == null || headers.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        // Drop a previous remote track's cache binding before the audio thread
        // can see the new source. A stale pending path plus feedMode=1 (local)
        // used to fire "live recovered, switching back from cache" on local
        // files (issue #59). bindGrowingCacheForCurrent() re-applies a real
        // binding right after play() for remote tracks.
        this.cachePendingPath = null;
        this.cacheCompletePath = null;
        requestOnComplete = onComplete;
        requestOnStarted = onStarted;
        requestOnError = onError;
        sourceRevision.incrementAndGet();
        cancelReads();
        // Publish the new track's baseline synchronously. Until the audio thread
        // opens/decodes/primes the source it would otherwise keep exposing the old
        // track's final position, which makes MPRIS clients start the new progress
        // bar several seconds ahead.
        seekTargetMs.set(target);
        positionMs = target;
        playing.set(true);
        ensureAudioThread();
    }

    @Override
    public synchronized void pause() {
        playing.set(false);
        sourceRevision.incrementAndGet();
        seekTargetMs.set(positionMs);
        cancelReads();
    }

    @Override
    public synchronized void resume() {
        if (source != null) playing.set(true);
    }

    @Override
    public boolean isPlaying() {
        return playing.get();
    }

    @Override
    public void seek(long ms) {
        long target = Math.max(0L, ms);
        seekTargetMs.set(target);
        positionMs = target;
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
    public void setOnStarted(Runnable callback) {
        this.onStarted = callback;
    }

    @Override
    public void setOnError(Runnable callback) {
        this.onError = callback;
    }

    @Override
    public void bindGrowingCache(String pendingPath, String completePath, long durationMs) {
        this.cachePendingPath = pendingPath;
        this.cacheCompletePath = completePath;
    }


    @Override
    public synchronized void release() {
        shuttingDown.set(true);
        playing.set(false);
        sourceRevision.incrementAndGet();
        cancelReads();
        Thread t = audioThread;
        if (t != null) t.interrupt();
    }

    private void cancelReads() {
        SeekableByteSource active = activeBytes;
        SeekableByteSource live = liveBytesInUse;
        if (active != null) active.close();
        if (live != null && live != active) live.close();
    }

    private synchronized void activateBytes(SeekableByteSource bytes, long revision) throws IOException {
        if (shuttingDown.get() || revision != sourceRevision.get() || !playing.get()) {
            bytes.close();
            throw new java.io.InterruptedIOException("audio request cancelled");
        }
        activeBytes = bytes;
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
            // After a decoder/OpenAL failure, do not silently reopen the same source
            // at 0 while paused. Wait for the controller's error handler to request
            // a retry (play() publishes a seek target) or for normal playback state.
            if (!followDefaultOutputDevice() || !playing.get()) {
                try {
                    Thread.sleep(60L);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            boolean reachedEnd = false;
            long attemptRevision;
            Runnable attemptComplete;
            Runnable attemptStarted;
            Runnable attemptError;
            synchronized (this) {
                attemptRevision = sourceRevision.get();
                attemptComplete = requestOnComplete;
                attemptStarted = requestOnStarted;
                attemptError = requestOnError;
            }
            try {
                reachedEnd = playCurrentSource(attemptRevision, attemptStarted);
            } catch (InterruptedException e) {
                // Shutdown interrupts this thread mid-sleep; that's the intended
                // way out, not a fault worth an ERROR + stack trace on every exit.
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable e) {
                synchronized (this) {
                    if (shuttingDown.get() || attemptRevision != sourceRevision.get()) continue;
                    playing.set(false);
                }
                Logger.exception(e);
                if (attemptError != null) attemptError.run();
            }
            if (reachedEnd) {
                synchronized (this) {
                    if (attemptRevision != sourceRevision.get() || !playing.get()) continue;
                    playing.set(false);
                }
                if (attemptComplete != null) attemptComplete.run();
            }
        }
        releaseOpenAl();
    }

    /** Play {@link #source} until it ends, the source changes, or shutdown.
     *  @return true only on a natural end-of-track (so the loop fires onComplete). */
    private boolean playCurrentSource(long expectedRevision, Runnable startedCallback) throws Exception {
        long openRevision;
        String openSrc;
        Map<String, String> openHeaders;
        long startMs;
        synchronized (this) {
            openRevision = sourceRevision.get();
            openSrc = source;
            openHeaders = sourceHeaders;
            if (openRevision != expectedRevision || openSrc == null || !playing.get()) return false;
            startMs = Math.max(0L, seekTargetMs.getAndSet(-1L));
        }
        boolean remote = openSrc.startsWith("http://") || openSrc.startsWith("https://");
        // Kept permanently alongside PlayerController's own resolve-stage timing --
        // together they cover the whole click-to-audible path for the still-open
        // "switching feels like 3-5s" report. Measured 2-80ms per switch so far
        // (open + prime), nowhere close to accounting for that gap on its own.
        long tOpen0 = System.currentTimeMillis();
        SeekableByteSource liveBytes = SeekableByteSource.open(openSrc, openHeaders);
        PcmSource pcm;
        try {
            activateBytes(liveBytes, openRevision);
            liveBytesInUse = liveBytes;
            pcm = PcmSource.decode(remote ? new UnclosedByteSource(liveBytes) : liveBytes);
        } catch (IOException | RuntimeException e) {
            liveBytes.close();
            throw e;
        }
        Logger.info("audio: timing PcmSource.open() {}ms", System.currentTimeMillis() - tOpen0);
        feedLive = liveBytes;
        feedCache = null;
        feedMode = remote ? 0 : 1;
        feedLastCheckMs = 0L;
        feedLastSwitchMs = 0L;
        feedRevision = openRevision;
        try {
            sampleRate = pcm.sampleRate();
            channels = Math.max(1, Math.min(2, pcm.channels()));
            alFormat = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
            durationMs = pcm.durationMs();

            seekBaseMs = startMs > 0 ? pcm.seek(startMs) : 0L;
            long tPrime0 = System.currentTimeMillis();
            boolean primed = primeFromCurrent(pcm);
            Logger.info("audio: timing primeFromCurrent {}ms, total open-to-play {}ms",
                    System.currentTimeMillis() - tPrime0, System.currentTimeMillis() - tOpen0);
            boolean draining = !primed;
            boolean startPending = true;
            boolean started = false;
            outputNeedsReprime = false;
            // Opening/priming can take long enough for play() to replace the request.
            // Do not announce or start the stale decoder; in particular, its
            // onStarted must not cancel the new track's volume transition.
            if (openRevision != sourceRevision.get()) return false;

            while (!shuttingDown.get()) {
                // A different track requested → bail so the loop reopens it.
                if (openRevision != sourceRevision.get()) return false;
                if (!followDefaultOutputDevice()) {
                    Thread.sleep(60L);
                    continue;
                }

                long seek = seekTargetMs.getAndSet(-1L);
                if (seek >= 0L || outputNeedsReprime) {
                    // A disconnected source reports all queued buffers as processed.
                    // Discard that queue and resume from the last published position.
                    seekBaseMs = pcm.seek(seek >= 0L ? seek : positionMs);
                    draining = !primeFromCurrent(pcm);
                    outputNeedsReprime = false;
                    startPending = true;
                }

                alSourcef(sourceId, AL_GAIN, volume);

                if (!playing.get()) {
                    if (alGetSourcei(sourceId, AL_SOURCE_STATE) == AL_PLAYING) alSourcePause(sourceId);
                    publishPosition(openRevision);
                    Thread.sleep(20L);
                    continue;
                }
                if ((startPending || alGetSourcei(sourceId, AL_SOURCE_STATE) == AL_PAUSED)
                        && alGetSourcei(sourceId, AL_BUFFERS_QUEUED) > 0) {
                    alSourcePlay(sourceId);
                    startPending = false;
                    if (!isOutputConnected()) continue;
                    if (!started) {
                        started = true;
                        if (startedCallback != null) startedCallback.run();
                    }
                }

                // Recycle every buffer OpenAL is done with; refill unless drained.
                int processed = alGetSourcei(sourceId, AL_BUFFERS_PROCESSED);
                if (!isOutputConnected()) continue;
                while (processed-- > 0) {
                    int buf = alSourceUnqueueBuffers(sourceId);
                    Integer frames = queuedFrames.pollFirst();
                    if (frames != null) framesSinceBase += frames;
                    idleBuffers.addLast(buf);
                }
                if (!draining) draining = !fillIdleBuffers(pcm);

                int queued = alGetSourcei(sourceId, AL_BUFFERS_QUEUED);
                PcmSource switched = maybeSwitchFeed(pcm, queued);
                if (switched != pcm) {
                    pcm = switched;
                    draining = false;
                }
                if (!isOutputConnected()) continue;
                int state = alGetSourcei(sourceId, AL_SOURCE_STATE);
                if (draining && queued == 0) {
                    publishPosition(openRevision);
                    return true; // natural end of track
                }
                if (state == AL_STOPPED && queued > 0) {
                    alSourcePlay(sourceId); // underran — kick it back to life
                }

                publishPosition(openRevision);
                Thread.sleep(8L);
            }
            return false;
        } finally {
            alSourceStop(sourceId);
            alSourcei(sourceId, AL_BUFFER, 0);
            queuedFrames.clear();
            idleBuffers.clear();
            try { pcm.close(); } catch (Throwable ignored) { }
            if (feedCache != null) {
                try { feedCache.close(); } catch (Throwable ignored) { }
                feedCache = null;
            }
            try { liveBytes.close(); } catch (Throwable ignored) { }
            feedLive = null;
            activeBytes = null;
            liveBytesInUse = null;
        }
    }

    private static final long FEED_CHECK_MS = 400L;
    private static final long FEED_COOLDOWN_MS = 2_000L;
    private static final long CACHE_AHEAD_BYTES = 64L * 1024L;
    private SeekableByteSource feedLive;
    private GrowingFileByteSource feedCache;
    private int feedMode; // 0 live, 1 cache
    private long feedLastCheckMs;
    private long feedLastSwitchMs;
    private long feedRevision;

    private PcmSource maybeSwitchFeed(PcmSource pcm, int queued) throws Exception {
        // Feed switching only makes sense for a remote stream being shadowed by
        // a background cache download. A local file has nothing to switch to,
        // and leaving this armed for local sources lets a stale bound cache path
        // reopen the decoder mid-play (the "Stream Closed" / silent-stop crash
        // from issue #59's log).
        if (feedLive == null || !(feedLive instanceof HttpByteSource)) return pcm;
        long now = System.currentTimeMillis();
        if (now - feedLastCheckMs < FEED_CHECK_MS) return pcm;
        feedLastCheckMs = now;
        if (now - feedLastSwitchMs < FEED_COOLDOWN_MS) return pcm;

        String completePath = cacheCompletePath;
        String pendingPath = cachePendingPath;
        java.io.File complete = completePath == null ? null : new java.io.File(completePath);
        boolean completeReady = complete != null && complete.isFile() && complete.length() > 0;
        long cacheLen = 0L;
        if (completeReady && complete != null) cacheLen = complete.length();
        else if (pendingPath != null) {
            java.io.File pending = new java.io.File(pendingPath);
            if (pending.isFile()) cacheLen = pending.length();
        }
        long liveDl = feedLive.downloadedBytes();
        boolean starving = queued <= 3;

        if (completeReady && feedMode != 1 && starving && !pcm.hasBuffered(CHUNK_BYTES / 4)) {
            return switchToCache(pcm, completePath, true, now, "complete cache");
        }
        if (feedMode == 1 && completeReady && feedCache != null) {
            feedCache.markComplete();
            return pcm;
        }
        if (feedMode == 0 && starving && cacheLen > liveDl + CACHE_AHEAD_BYTES) {
            String path = completeReady ? completePath : pendingPath;
            if (path != null && new java.io.File(path).isFile()) {
                return switchToCache(pcm, path, completeReady, now,
                        "live stalled, cache ahead " + cacheLen + " vs " + liveDl);
            }
        }
        if (feedMode == 1 && !completeReady && liveDl > cacheLen + CACHE_AHEAD_BYTES) {
            Logger.info("audio: live recovered, switching back from cache");
            pcm.close();
            if (feedCache != null) {
                feedCache.close();
                feedCache = null;
            }
            activateBytes(feedLive, feedRevision);
            PcmSource next = PcmSource.decode(new UnclosedByteSource(feedLive));
            retune(pcm, next, positionMs);
            feedMode = 0;
            feedLastSwitchMs = now;
            return next;
        }
        return pcm;
    }

    private PcmSource switchToCache(PcmSource pcm, String path, boolean complete,
                                    long now, String reason) throws Exception {
        Logger.info("audio: switching to {} ({})", path, reason);
        pcm.close();
        if (feedCache != null) {
            feedCache.close();
            feedCache = null;
        }
        feedCache = new GrowingFileByteSource(path, complete, cacheCompletePath);
        activateBytes(feedCache, feedRevision);
        PcmSource next = PcmSource.decode(feedCache);
        retune(pcm, next, positionMs);
        feedMode = 1;
        feedLastSwitchMs = now;
        return next;
    }


    private void retune(PcmSource previous, PcmSource pcm, long posMs) throws Exception {
        if (previous instanceof Mp3PcmSource oldMp3 && pcm instanceof Mp3PcmSource newMp3) {
            newMp3.reuseSeekIndex(oldMp3);
        }
        sampleRate = pcm.sampleRate();
        channels = Math.max(1, Math.min(2, pcm.channels()));
        alFormat = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
        long reported = pcm.durationMs();
        if (reported > 0) durationMs = reported;
        seekBaseMs = posMs > 0 ? pcm.seek(posMs) : 0L;
        boolean primed = primeFromCurrent(pcm);
        if (playing.get() && primed) alSourcePlay(sourceId);
    }

    /** Stop and prime the decoder. @return false only after a real EOF;
     *  an empty, temporarily starved ring remains eligible for refill. */
    private boolean primeFromCurrent(PcmSource pcm) throws Exception {
        alSourceStop(sourceId);
        alSourcei(sourceId, AL_BUFFER, 0);
        queuedFrames.clear();
        idleBuffers.clear();
        framesSinceBase = 0L;
        for (int b : buffers) idleBuffers.addLast(b);
        int primed = 0;
        while (primed < PRIME_BUFFERS && !idleBuffers.isEmpty()) {
            int b = idleBuffers.peekFirst();
            int f = decodeInto(pcm, b);
            if (f < 0) return true;
            if (f == 0) return false;
            idleBuffers.pollFirst();
            alSourceQueueBuffers(sourceId, b);
            queuedFrames.addLast(f);
            primed++;
        }
        return true;
    }

    /** @return false when the decoder hit a real EOF. */
    private boolean fillIdleBuffers(PcmSource pcm) throws Exception {
        while (!idleBuffers.isEmpty()) {
            int buf = idleBuffers.peekFirst();
            int f = decodeInto(pcm, buf);
            if (f < 0) return true;
            if (f == 0) return false;
            idleBuffers.pollFirst();
            alSourceQueueBuffers(sourceId, buf);
            queuedFrames.addLast(f);
        }
        return true;
    }

    /** Decode one buffer's worth of PCM into {@code buf}.
     *  @return frame count, 0 at EOF, or -1 if the remote download has not
     *  caught up yet (caller must retry, not treat as end of track). */
    private int decodeInto(PcmSource pcm, int buf) throws Exception {
        int frameBytes = channels * 2;
        int want = CHUNK_BYTES - (CHUNK_BYTES % frameBytes);
        if (!pcm.hasBuffered(Math.max(frameBytes, want / 4))) return -1;
        int got = 0;
        boolean eof = false;
        while (got < want) {
            int r = pcm.read(stagingChunk, got, want - got);
            if (r < 0) { eof = true; break; }
            if (r == 0) break;
            got += r;
        }
        got -= got % frameBytes;
        if (got <= 0) return eof ? 0 : -1;
        nativeChunk.clear();
        nativeChunk.put(stagingChunk, 0, got).flip();
        alBufferData(buf, alFormat, nativeChunk, sampleRate);
        return got / frameBytes;
    }

    private void publishPosition(long expectedRevision) {
        // A play/seek request can arrive between this loop's source check and its
        // position publication. Do not overwrite the synchronous new baseline
        // with samples belonging to the previous source/seek epoch.
        if (expectedRevision != sourceRevision.get() || seekTargetMs.get() >= 0L) return;
        int offset = alGetSourcei(sourceId, AL_SAMPLE_OFFSET); // frames into the head buffer
        if (!isOutputConnected()) return;
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
        reopenDeviceSupported = alcCaps.ALC_SOFT_reopen_device;
        disconnectSupported = alcCaps.ALC_EXT_disconnect;
        lastDefaultDevice = defaultPlaybackDevice();
        sourceId = alGenSources();
        buffers = new int[NUM_BUFFERS];
        for (int i = 0; i < NUM_BUFFERS; i++) buffers[i] = alGenBuffers();
        nativeChunk = MemoryUtil.memAlloc(CHUNK_BYTES);
    }

    private boolean isOutputConnected() {
        return !disconnectSupported || alcGetInteger(device, ALC_CONNECTED) != ALC_FALSE;
    }

    /**
     * Follow default-output changes and recover a disconnected session even when
     * its device name is unchanged after sleep. Never drain a disconnected source:
     * OpenAL marks its entire queue processed without rendering those samples.
     */
    private boolean followDefaultOutputDevice() {
        boolean connected = isOutputConnected();
        if (!connected && !outputNeedsReprime) {
            outputNeedsReprime = true;
            Logger.warn("audio: output device disconnected; holding playback position");
        }
        if (!reopenDeviceSupported || device == NULL) return connected;
        long now = System.nanoTime();
        if (now - lastDeviceCheckNanos < DEVICE_CHECK_NANOS) return connected;
        lastDeviceCheckNanos = now;
        String current = defaultPlaybackDevice();
        if (connected && (current.isEmpty() || current.equals(lastDefaultDevice))) return true;
        if (!SOFTReopenDevice.alcReopenDeviceSOFT(device, (ByteBuffer) null, (IntBuffer) null)) {
            if (connected) Logger.warn("audio: failed to reopen OpenAL on {}", current);
            return isOutputConnected();
        }
        lastDefaultDevice = current;
        Logger.info("audio: reopened OpenAL on {}", current);
        return isOutputConnected();
    }

    private static String defaultPlaybackDevice() {
        String name = alcGetString(NULL, ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
        return name == null ? "" : name;
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
