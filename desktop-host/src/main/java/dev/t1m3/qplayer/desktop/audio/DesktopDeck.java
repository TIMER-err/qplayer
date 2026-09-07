package dev.t1m3.qplayer.desktop.audio;

import dev.t1m3.qplayer.desktop.audio.automix.AudioTransportPcmSource;
import dev.t1m3.qplayer.desktop.audio.automix.RawPcmSource;
import dev.t1m3.qplayer.desktop.audio.automix.HighpassFadePcmSource;
import dev.t1m3.qplayer.desktop.audio.automix.VocalFadePcmSource;
import dev.t1m3.qplayer.desktop.audio.automix.WsolaPcmSource;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * OpenAL streaming audio player, ported from qplayer DesktopAudioBackend.
 * Uses JLayer (Mp3PcmSource) to decode MP3, feeds 16-bit PCM into an OpenAL
 * source ring. Reuses Minecraft's existing OpenAL context — no context
 * create/destroy, just alGenSources / alGenBuffers.
 */
public final class DesktopDeck {

    private static final int NUM_BUFFERS = 8;
    private static final int CHUNK_BYTES = 16 * 1024;

    private final dev.t1m3.qplayer.settings.SettingsCore settings;

    public DesktopDeck(dev.t1m3.qplayer.settings.SettingsCore settings) {
        this.settings = settings;
    }

    private final AtomicBoolean playing    = new AtomicBoolean(false);
    private final AtomicBoolean stopped    = new AtomicBoolean(true);
    private final AtomicLong    seekTarget = new AtomicLong(-1L);

    public volatile String      source;
    private volatile Thread      audioThread;
    // Long-lived worker lifecycle. The audio thread is created once (lazily, on the first
    // play) and kept alive until dispose(); stop()/switch only IDLE it. threadLock serialises
    // ensureThread() against the loop's exit so audioThread is never observed
    // half-torn-down. See ensureThread() for the race this closes.
    private final Object         threadLock = new Object();
    private volatile boolean     disposed   = false;
    private volatile LongConsumer onProgress;
    private volatile Runnable     onEnd;
    /** Called once when a track could not be opened/decoded (see the audio loop's catch). */
    private volatile Runnable     onError;
    private volatile Runnable     onStarted;
    private boolean startedNotified = false;
    private volatile long positionMs = 0L;
    private volatile long durationMs = 0L;

    // OpenAL handles (owned by the audio thread)
    private int sourceId = 0;
    private int[] buffers;
    // ── Sound-Physics-Remastered–style EFX (4 EAX bands + direct/send lowpass) ──
    private final ArrayDeque<Integer> queuedFrames = new ArrayDeque<>();
    private int    sampleRate     = 44100;
    private int    channels       = 2;
    private int    alFormat       = AL10.AL_FORMAT_STEREO16;
    private ByteBuffer nativeChunk;
    private final byte[] stagingChunk = new byte[CHUNK_BYTES];
    private long   seekBaseMs     = 0L;
    private long   framesSinceBase = 0L;

    // ── Play/pause gain fade ─────────────────────────────────────────────────
    // pause()/resume() only flip `playing` (intent — drives the icon + isPlaying()).
    // The audio loop ramps curGain toward that intent over FADE_MS so volume glides
    // instead of cutting. The AL source is only actually paused once curGain hits 0,
    // so the fade-out is audible; on resume it plays immediately and fades back up.
    private static final float FADE_MS = 1000f;
    private volatile float curGain = 1f;   // live source gain, ramped in the loop
    private long   gainLastNs = 0L;
    private long   lastDeviceCheckNs = 0L;

    // ── User master volume ───────────────────────────────────────────────────
    // A third, user-controlled multiplier on the final AL gain (curGain * xfadeGain
    // * masterVolume). 0..1, adjusted from the mini-player volume control.
    private volatile float masterVolume = 1f;

    // ── Automix loudness compensation ────────────────────────────────────────
    // A per-deck, per-track gain multiplier set by the mixer before the deck opens
    // (AutomixPlanner's rmsA/rmsB ratio). Brings a quiet incoming track up to the
    // outgoing deck's level so the overlap doesn't sound "loud → quiet → loud".
    // Independent of the pause fade (curGain) and crossfade (xfadeGain); final AL gain
    // is curGain * xfadeGain * masterVolume * gainComp. 1f = no compensation.
    private volatile float gainComp = 1f;

    // ── Crossfade (AudioMixer drives this on track switch) ───────────────────
    // A SECOND gain multiplier, independent of the pause fade (curGain). The final
    // AL gain is curGain * xfadeGain. On a crossfade the outgoing deck ramps xfade
    // 1→0 (then stops + idles, keeping its thread/context alive for reuse) while the
    // incoming deck ramps 0→1. When crossfade is off both stay at 1, so the AL gain
    // is exactly curGain — the single-deck path is byte-identical to before.
    private volatile float   xfadeGain   = 1f;    // live crossfade multiplier
    private volatile float   xfadeTarget = 1f;    // 0 = fade out, 1 = fade in
    private volatile float   xfadeMs     = 3000f; // ramp duration
    private volatile boolean fadeOutIdle = false; // true → stop + idle once xfadeGain hits 0
    /** When the current xfadeTarget was set — drives the DJ-curve progress (0→1 over xfadeMs). */
    private long xfadeStartNs = 0L;
    // Only the active deck writes the BEAT / VISUALIZER statics; the outgoing deck is
    // muted here so the two don't fight over them mid-crossfade.
    private volatile boolean feedsStatics = true;

    // ── Audio-transport (spectral glide) pending source for transport transition ─
    // When non-null, the NEXT open wraps the incoming source together with the
    // outgoing deck's source in an AudioTransportPcmSource so the transition is a
    // spectral frequency-glide instead of a plain gain crossfade.
    volatile String pendingTransportSrcA = null;  // A's URL (outgoing, set by mixer)
    volatile float  pendingTransportMs   = 0f;
    volatile long   pendingTransportSrcASeekMs = 0L;  // where A is read from (near its end)

    // ── Automix tempo-match ──────────────────────────────────────────────────
    // When != 1, the NEXT source opened is wrapped in a WsolaPcmSource so it plays
    // pitch-preservingly time-stretched (SoundTouch tempo: targetBpm/nativeBpm). Set by
    // AudioMixer's automix path via playFadeIn(url, ms, tempo) before the deck opens.
    // 1f = no stretch, source opens raw (byte-identical to the non-automix path).
    private volatile float pendingTempo = 1f;

    // ── Automix tempo ramp-back ──────────────────────────────────────────────
    // Apple's automix beat-matches B to A only during the OVERLAP; afterwards B eases
    // back to its own tempo (1.0) so it doesn't play the whole song pitch-shifted-in-feel.
    // The live WSOLA wrapper (null when the source opened un-stretched) has its tempo
    // ramped plannedTempo → 1.0 over RAMP_MS, starting once the RAMP_HOLD (the crossfade
    // overlap) has elapsed from fadeInStartNs. Both are runtime-tunable via
    // MusicSettings (spec §12: default 2600/10000, ranges 1500-4000/5000-15000;
    // SW_AUTOMIX_ENHANCED swaps in the tuned preset).
    private volatile WsolaPcmSource wsola = null;  // live stretcher, or null if un-stretched
    private volatile HighpassFadePcmSource highpass = null; // bass-fade wrapper, or null
    private volatile boolean pendingHighpass = false; // arm the bass-fade for the NEXT open
    private float  plannedTempo = 1f;   // the beat-matched tempo B entered at
    private float  liveTempo    = 1f;   // current tempo (ramps plannedTempo → 1.0)
    private long   fadeInStartNs = 0L;  // when B's source opened (ramp clock origin)
    // Content-time accumulator: when stretched, output frames advance SLOWER/FASTER than
    // content, so we sum output-frames × liveTempo to track the CONTENT position the lyric
    // clock needs. Unused (and untouched) on the un-stretched path.
    private double contentFramesSinceBase = 0.0;

    // ── Pre-arm (phase-locked automix entry) ──────────────────────────────────
    // armFadeIn() opens + primes + seeks B to its beat but holds it SILENT and paused
    // (armed=true, playing=false) so it's fully buffered and ready to sound on the exact
    // sample. releaseArmed() flips armed→false + playing→true; the source then starts from
    // its primed buffers within one loop tick (~10ms), so B's downbeat lands on A's beat
    // instead of drifting by B's open/decode latency. Un-armed paths never set this.
    private volatile boolean armed = false;
    /** True once the armed deck is open + primed and waiting (ready to sound within one tick). */
    private volatile boolean armedReady = false;

    // ── Automix stem entry (vocal suppression) ────────────────────────────────
    // When set, the NEXT open wraps the raw source together with a precomputed
    // instrumental stem (StemCache) in a VocalFadePcmSource: the overlap plays B's
    // instrumental (vocals suppressed), then the vocals ease back after the handover.
    private volatile String pendingStemPath   = null;
    private volatile long   pendingStemStartMs = 0L;

    public void setOnProgress(LongConsumer cb) { this.onProgress = cb; }
    public void setOnEnd(Runnable cb)           { this.onEnd      = cb; }
    public void setOnError(Runnable cb)         { this.onError    = cb; }
    public void setOnStarted(Runnable cb)       { this.onStarted  = cb; }

    // ── Public control ────────────────────────────────────────────────────

    public void play(String url, long startMs) {
        this.source = url;
        seekTarget.set(Math.max(0L, startMs));
        stopped.set(false);
        armed = false;
        pendingTempo = 1f;
        gainComp = 1f;
        pendingHighpass = false;
        pendingTransportSrcA = null;
        pendingTransportSrcASeekMs = 0L;
        pendingStemPath = null;
        xfadeGain = 1f;
        xfadeTarget = 1f;
        xfadeStartNs = System.nanoTime();
        fadeOutIdle = false;
        playing.set(true);
        ensureThread();
    }

    public void play(String url) {
        String prev = source;
        this.source = url;
        seekTarget.set(0L);
        stopped.set(false);
        armed = false;       // never inherit a stale automix arm
        pendingTempo = 1f;   // a plain start never inherits a stale automix stretch
        gainComp     = 1f;   // nor a stale loudness compensation
        pendingHighpass = false; // nor a stale bass-fade
        pendingTransportSrcA = null;   // nor a stale transport glide
        pendingTransportSrcASeekMs = 0L;
        pendingStemPath = null;        // nor a stale vocal-suppression stem
        // Reset crossfade state so a deck previously fadeOutToIdle()'d doesn't kill
        // the incoming track when xfadeGain reaches 0 with fadeOutIdle still true.
        // playFadeIn() already sets these explicitly; play() is a direct/hard start
        // so full gain immediately is correct.
        xfadeGain   = 1f;
        xfadeTarget = 1f;
        xfadeStartNs = System.nanoTime();
        fadeOutIdle = false;
        playing.set(true);
        ensureThread();
    }

    /**
     * Start a new track on this (idle) deck fading UP from silence over {@code ms}.
     * Used by AudioMixer as the incoming deck of a crossfade. curGain stays 1 (no
     * pause fade), xfadeGain ramps 0→1 so the final AL gain rises smoothly.
     */
    public void playFadeIn(String url, float ms) { playFadeIn(url, ms, 1f); }

    /**
     * As {@link #playFadeIn(String, float)} but time-stretches the incoming track to
     * {@code tempo} (SoundTouch semantics: targetBpm/nativeBpm) so automix can match it
     * to the outgoing deck's BPM. tempo == 1 skips the WSOLA wrapper entirely.
     */
    public void playFadeIn(String url, float ms, float tempo) { playFadeIn(url, ms, tempo, 0L); }

    /**
     * As {@link #playFadeIn(String, float, float)} but starts the incoming track at
     * {@code startMs} instead of 0 — used by automix so B enters on its first detected
     * beat (phaseMsB) rather than a silent intro, so the two tracks' downbeats line up.
     */
    public void playFadeIn(String url, float ms, float tempo, long startMs) {
        // If audio-transport is pending, we must start B from 0 to replace the intro
        if (pendingTransportSrcA != null) {
            startMs = 0L;
        }
        this.source = url;
        seekTarget.set(Math.max(0L, startMs));
        stopped.set(false);
        pendingTempo = tempo;
        pendingHighpass = true;   // fade-in path (automix/crossfade): ease the bass in
        curGain = 1f;
        xfadeGain = 0f;
        xfadeTarget = 1f;
        xfadeStartNs = System.nanoTime();
        xfadeMs = ms;
        fadeOutIdle = false;
        feedsStatics = true;
        playing.set(true);
        ensureThread();
    }

    /**
     * PRE-ARM the incoming automix track: open + prime + seek to {@code startMs} (B's first
     * beat) but hold it SILENT and paused until {@link #releaseArmed}. This pre-buffers B so
     * that on release it sounds within one loop tick — the basis for phase-locked beatmatch
     * (B's downbeat lands on A's beat instead of drifting by B's open/decode latency).
     * {@code tempo} is the WSOLA factor (targetBpm/nativeBpm); {@code ms} is the fade-in ramp
     * used later by releaseArmed.
     */
    public void armFadeIn(String url, float ms, float tempo, long startMs) {
        armFadeInStems(url, null, 0L, ms, tempo, startMs);
    }

    /**
     * As {@link #armFadeIn} but with a precomputed instrumental stem: the overlap plays
     * B with vocals suppressed ({@code stemPath}), then the vocals ease back in after the
     * handover. {@code stemStartMs} is the raw position the stem was cut at (B's entry),
     * so seeks stay aligned. {@code stemPath == null} behaves exactly like
     * {@link #armFadeIn} (the caller already fell back).
     */
    public void armFadeInStems(String url, String stemPath, long stemStartMs,
                               float ms, float tempo, long startMs) {
        this.source = url;
        this.pendingStemPath = stemPath;
        this.pendingStemStartMs = stemStartMs;
        seekTarget.set(Math.max(0L, startMs));
        stopped.set(false);
        pendingTempo = tempo;
        pendingHighpass = true;   // automix path: ease the bass in on entry
        curGain      = 1f;
        xfadeGain    = 0f;     // silent while armed
        xfadeTarget  = 0f;     // stays silent until released
        xfadeStartNs = System.nanoTime();
        xfadeMs      = ms;
        fadeOutIdle  = false;
        feedsStatics = false;  // A still owns the statics until the blend fires
        armed        = true;
        playing.set(false);    // held — the armed-wait in the loop keeps it primed but paused
        ensureThread();
    }

    /**
     * Release a deck armed by {@link #armFadeIn}: unfreeze it so it sounds immediately and
     * fades up over the arm's {@code ms}. Called on A's beat so B enters phase-locked.
     */
    public void releaseArmed() {
        if (!armed) return;
        feedsStatics = true;   // incoming deck now drives the visualiser
        xfadeTarget  = 1f;     // fade up
        xfadeStartNs = System.nanoTime();
        playing.set(true);
        armed        = false;  // the armed-wait loop sees this and falls through to playback
    }

    /**
     * Fade this (playing) deck DOWN to silence over {@code ms}, then stop it and idle
     * (thread + AL context stay alive for reuse as the next incoming deck). Used by
     * AudioMixer as the outgoing deck of a crossfade. Stops feeding the statics so the
     * incoming deck drives the visualiser during the overlap.
     */
    public void fadeOutToIdle(float ms) {
        xfadeTarget  = 0f;
        xfadeStartNs = System.nanoTime();
        xfadeMs      = ms;
        fadeOutIdle  = true;
        feedsStatics = false;
        // playing stays true so the loop keeps running and the ramp is audible.
    }

    /** Gate for the BEAT/VISUALIZER statics — only the mixer's active deck should feed them. */
    public void setFeedsStatics(boolean v) { this.feedsStatics = v; }

    /** True while this deck is armed, open and primed (ready to fire phase-locked). */
    public boolean armedReady() { return armedReady; }

    public void pause() { playing.set(false); }

    public void resume() {
        if (source != null && !stopped.get()) playing.set(true);
    }

    public float getMasterVolume() { return masterVolume; }

    public void setMasterVolume(float v) {
        masterVolume = Math.max(0f, Math.min(1f, v));
    }

    /** Set the automix loudness-compensation gain for the NEXT source this deck opens.
     *  1f = no compensation. The value is latched at open (like {@link #pendingTempo}). */
    public void setGainComp(float g) { gainComp = Math.max(0.25f, Math.min(4f, g)); }

    /**
     * Stop playback and IDLE the worker (it does NOT kill the thread or the AL context).
     * Sets {@code stopped=true} and clears the source; the worker notices within one poll tick
     * (≤40ms idle / 8ms while playing) and returns to its idle wait. The thread stays alive for
     * the deck's life so a subsequent play()/playFadeIn() reuses it — this is what closes the
     * switch race where a play() arriving during thread teardown found a still-alive-but-exiting
     * thread and never started a replacement, leaving the new source with nothing decoding it.
     *
     * <p>Deliberately does NOT interrupt the worker. interrupt() only breaks Thread.sleep (it
     * can't unblock a socket read anyway), and a switch fires stop() immediately followed by
     * play(): if the worker exited the old track WITHOUT consuming the interrupt (it left via the
     * source/stopped check, not a sleep), the stale interrupt flag lingers and then fires inside
     * the NEXT track's first sleep, aborting a freshly-opened source — the intermittent
     * "switch produces no audio", worst under rapid skipping when many stops stack interrupts.
     * Polling is enough here; only {@link #dispose()} (terminal) still interrupts.
     */
    public void stop() {
        stopped.set(true);
        playing.set(false);
        armed = false;
        source = null;
        positionMs = 0L;
    }

    /**
     * Real teardown: exit the worker thread and free its AL source/buffers/context. Unlike
     * {@link #stop()} (which only idles), this ends the deck's life. Only call when the deck
     * itself is being discarded (see {@link AudioMixer#dispose()}).
     */
    public void dispose() {
        disposed = true;
        stopped.set(true);
        playing.set(false);
        source = null;
        Thread t = audioThread;
        if (t != null) t.interrupt();
    }

    public void seek(long ms) { seekTarget.set(ms); }

    public boolean isPlaying() { return playing.get() && !stopped.get(); }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }

    // ── Audio thread ──────────────────────────────────────────────────────

    private void audioLoop() {
        // Catch Throwable, not Exception: a missing jlayer/ZXing class surfaces as
        // NoClassDefFoundError, which would otherwise kill this thread with no trace.
        try {
            initAl();
        } catch (Throwable e) {
            dev.t1m3.qplayer.util.Logger.error("AudioPlayer initAl failed", e);
            playing.set(false);
            return;
        }
        try {
            // Long-lived worker: it stays alive across stop()/idle and only exits on dispose().
            // stop() sets stopped=true and clears the source; the worker notices on its next poll
            // and returns to the idle wait below — it does NOT end the thread, and (unlike before)
            // is NOT interrupted. So a play()/playFadeIn() arriving after a stop reuses THIS same
            // worker instead of racing a half-torn-down one — the switch-goes-silent bug this fixes.
            // Only dispose() interrupts (terminal), caught below.
            while (!disposed) {
                boolean ended = false;
                try {
                    ended = playCurrentSource();
                } catch (InterruptedException e) {
                    // Woken by stop()/dispose(). If disposing, leave; otherwise fall through to
                    // the idle wait and pick up whatever the next play() sets as the source.
                    if (disposed) break;
                } catch (Throwable e) {
                    // Drop the source that just failed. The outer loop re-enters
                    // playCurrentSource() within ~40 ms, so keeping it would re-open (and for a
                    // network source re-DOWNLOAD) the same broken track forever: one bad lossless
                    // link produced hundreds of megabytes of temp files and a log flood. Failing
                    // once, loudly, and idling is what a user can actually act on.
                    String failed = source;
                    dev.t1m3.qplayer.util.Logger.error("audio loop failed"
                            + (failed == null ? "" : " (" + failed + ")"), e);
                    playing.set(false);
                    if (failed != null && failed.equals(source)) {
                        source = null;
                        // A stale seek target would otherwise keep the idle wait below from
                        // sleeping (see its condition), busy-spinning this thread.
                        seekTarget.set(-1L);
                    }
                    Runnable errCb = onError;
                    if (errCb != null && !disposed) {
                        try {
                            errCb.run();
                        } catch (Throwable ignored) {
                            // A listener must not take the audio thread down with it.
                        }
                    }
                }
                if (ended && !stopped.get() && !disposed) {
                    playing.set(false);
                    Runnable cb = onEnd;
                    if (cb != null) cb.run();
                }
                // Idle wait. Sleep briefly (so a fresh play()/seek() is noticed within a tick)
                // whenever there's nothing to decode: stopped, or paused with no pending seek.
                // Gating on stopped too prevents a busy-spin if a seek was left pending at stop
                // (playCurrentSource returns immediately while source==null). Only dispose()
                // interrupts this sleep.
                // `source == null` is part of the gate on purpose: with no source,
                // playCurrentSource() returns immediately without consuming seekTarget, so a
                // pending seek (the party bridge re-asserts one every server tick) would skip
                // the sleep and spin this thread at 100% CPU.
                // Do NOT reopen the AL device here — mid-idle teardown races the next play()
                // and can leave the source STOPPED with an empty queue. Device changes are
                // applied at play start / during the play loop with a proper reprime.
                if (!disposed && (stopped.get() || source == null
                        || (!playing.get() && seekTarget.get() < 0L))) {
                    try { Thread.sleep(40L); } catch (InterruptedException e) { if (disposed) break; }
                }
            }
        } finally {
            releaseAl();
            synchronized (threadLock) {
                if (audioThread == Thread.currentThread()) audioThread = null;
            }
        }
    }

    /**
     * Ensure a live worker thread exists, creating one lazily on first use. Serialised on
     * {@code threadLock} against the loop's exit (which nulls {@code audioThread} only for
     * itself, also under the lock) so we never observe a half-exited thread and skip starting
     * a replacement. Because {@link #stop()} now only idles the worker (never exits it), in the
     * steady state this simply finds the existing thread alive and returns — a new thread is
     * only ever started on the very first play or after an actual {@link #dispose()}/init crash.
     */
    private void ensureThread() {
        synchronized (threadLock) {
            disposed = false;
            Thread t = audioThread;
            if (t == null || !t.isAlive()) {
                t = new Thread(this::audioLoop, "music-audio");
                t.setDaemon(true);
                audioThread = t;
                t.start();
            }
        }
    }

    private boolean playCurrentSource() throws Exception {
        String openSrc = source;
        if (openSrc == null || stopped.get()) return false;

        // Clear any stale interrupt before opening the new source so a leftover flag (e.g. from a
        // prior wake) can't fire inside this track's first sleep and abort a freshly-opened deck.
        // dispose() sets disposed=true before it interrupts, so the outer loop still sees the exit
        // even though we clear the flag here.
        Thread.interrupted();

        startedNotified = false;
        long startMs = Math.max(0L, seekTarget.getAndSet(-1L));

        // Local file (file:/absolute/path) → decode off disk by format; else HTTP MP3 stream.
        // IMPORTANT: do network/decode open BEFORE touching OpenAL. A long HTTP open after
        // alcMakeContextCurrent lets Minecraft's sound thread steal the process-wide context,
        // so the subsequent alSourcePlay silently no-ops (stuck until the next seek).
        PcmSource pcm = openPcm(openSrc);

        // Automix stem entry (vocal suppression): wrap the raw source together with the
        // precomputed instrumental stem — the overlap plays B's instrumental, then the
        // vocals ease back after the handover. Falls back to the plain source on any error.
        String stemPath = pendingStemPath;
        long   stemStartMs = pendingStemStartMs;
        pendingStemPath = null;
        if (stemPath != null && !stemPath.isBlank()) {
            try {
                PcmSource stem = new RawPcmSource(stemPath);
                pcm = new VocalFadePcmSource(pcm, stem, xfadeMs, 2500f, stemStartMs);
                dev.t1m3.qplayer.util.Logger.info("automix stem entry active: " + stemPath);
            } catch (Exception e) {
                dev.t1m3.qplayer.util.Logger.error("automix stem open failed, falling back", e);
            }
        }

        // Audio-transport spectral glide: if the mixer pre-staged a source A URL, wrap B
        // together with a fresh A decode so the transition is a frequency-glide instead of
        // a plain gain crossfade. Only used on the incoming deck during a crossfade when
        // MusicSettings.audioTransport is on.
        String atSrcA   = pendingTransportSrcA;
        float  atMs     = pendingTransportMs;
        long   atSeekMs = pendingTransportSrcASeekMs;
        pendingTransportSrcA = null;
        pendingTransportSrcASeekMs = 0L;
        if (atSrcA != null && atMs > 0f && settings.bool("audioTransport")) {
            try {
                PcmSource srcA = openPcm(atSrcA);
                // Read A from near its end (its outro) so the glide starts from where A
                // actually is, not from its intro; B is read from its own start (its intro).
                if (atSeekMs > 0L) srcA.seek(atSeekMs);
                pcm = new AudioTransportPcmSource(srcA, pcm, atMs);
            } catch (Exception e) {
                dev.t1m3.qplayer.util.Logger.error("audio-transport open failed, falling back", e);
            }
        }

        // Automix tempo-match: wrap in the WSOLA stretcher so the deck reads pitch-preserved
        // time-stretched audio. tempo==1 means no stretch — leave the source raw.
        float tempo = pendingTempo;
        WsolaPcmSource w = (tempo != 1f) ? new WsolaPcmSource(pcm, tempo) : null;
        if (w != null) pcm = w;
        // Automix bass fade-in: high-pass the first ~2.5s of the incoming track so its
        // low end eases in instead of punching through the outgoing deck's. Only applied
        // on the automix path (armIncoming / playFadeIn set pendingHighpass).
        HighpassFadePcmSource highpass = null;
        if (pendingHighpass && settings.bool("highpassFade")) {
            highpass = new HighpassFadePcmSource(pcm);
            pcm = highpass;
        }
        // Ramp state: hold the matched tempo through the overlap, then ease to 1.0. Set here
        // (the authoritative open moment) so the ramp clock aligns with actual playback.
        this.wsola         = w;
        this.highpass      = highpass;
        this.plannedTempo  = tempo;
        this.liveTempo     = tempo;
        this.fadeInStartNs = System.nanoTime();

        try {
            // Bind AL to this thread and match MC's output device NOW — immediately before
            // queue/play, so nothing can steal the context between init and alSourcePlay.
            bindAlContext();

            sampleRate     = pcm.sampleRate();
            channels       = Math.max(1, Math.min(2, pcm.channels()));
            durationMs     = pcm.durationMs();
            alFormat       = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            seekBaseMs     = startMs > 0 ? pcm.seek(startMs) : 0L;
            framesSinceBase = 0L;
            curGain = 1f;   // a newly-loaded track starts at full volume (only pause/resume fades)

            boolean draining = !primeBuffers(pcm);

            // Pre-arm hold: the deck is open, primed and seeked to B's beat, but frozen SILENT
            // until releaseArmed() flips `armed` off. This is what makes the eventual entry
            // phase-locked — releasing a primed source sounds within one tick, so B's downbeat
            // lands on A's beat instead of drifting by the open/decode latency.
            armedReady = true;
            while (armed && !stopped.get()) {
                if (!openSrc.equals(source)) return false;   // superseded before release
                // Pre-ramp the crossfade gain while held silent, so the handover
                // starts near full volume instead of fading in from zero over the
                // whole automix window (that silent tail read as a "stutter").
                long gNow2 = System.nanoTime();
                float gdt2 = gainLastNs == 0L ? 0f : (gNow2 - gainLastNs) / 1e9f;
                gainLastNs = gNow2;
                if (xfadeGain < 1f) {
                    xfadeGain = Math.min(1f, xfadeGain + gdt2 / 1.5f);
                }
                publishPosition();
                Thread.sleep(2L);
            }
            armedReady = false;
            // Released: the tempo-ramp clock starts NOW (the audible moment), not at open —
            // otherwise a multi-second arm would eat into the beat-matched hold window.
            fadeInStartNs = System.nanoTime();
            gainLastNs = 0L;
            // Start the bass-fade sweep at the audible moment too (arm-prime reads must
            // not consume the fade; the sweep should begin as B actually sounds).
            if (highpass != null) highpass.resetFade();

            bindAlContext();
            if (playing.get()) AL10.alSourcePlay(sourceId);
            if (playing.get() && !startedNotified) {
                startedNotified = true;
                Runnable scb = onStarted;
                if (scb != null) scb.run();
            }

            while (!stopped.get()) {
                if (!openSrc.equals(source)) return false; // new track requested

                bindAlContext();

                long sk = seekTarget.getAndSet(-1L);
                if (sk >= 0L) {
                    seekBaseMs = pcm.seek(sk);
                    framesSinceBase = 0L;
                    draining = !primeBuffers(pcm);
                    if (playing.get()) AL10.alSourcePlay(sourceId);
                }

                // Ramp curGain toward the play/pause intent so volume glides (~FADE_MS)
                // instead of cutting. dt is wall time since the last loop tick.
                long gNow = System.nanoTime();
                float gdt = gainLastNs == 0L ? 0f : (gNow - gainLastNs) / 1e9f;
                gainLastNs = gNow;
                float gTarget = playing.get() ? 1f : 0f;
                float gStep = FADE_MS > 0f ? gdt / (FADE_MS / 1000f) : 1f;
                if (curGain < gTarget) curGain = Math.min(gTarget, curGain + gStep);
                else if (curGain > gTarget) curGain = Math.max(gTarget, curGain - gStep);

                // Crossfade multiplier ramps independently (driven by the mixer). Final AL
                // gain = curGain * xfadeGain. When not crossfading both targets are 1.
                //
                // DJ-curve crossfade instead of linear: a real DJ crossfader holds the
                // outgoing track near full for most of the overlap, then cuts it quickly at
                // the end, while the incoming track rises fast and then holds. This reads as
                // "one song takes over" instead of a symmetric volume swap.
                //   fade-out (target 0): p < 0.6 → 1, 0.6..1.0 → 1 → 0  (hold then cut)
                //   fade-in  (target 1): p < 0.4 → 0 → 1, 0.4..1.0 → 1  (fast rise, hold)
                float xfadeP = xfadeMs > 0f
                        ? (float) ((System.nanoTime() - xfadeStartNs) / 1e6f / xfadeMs)
                        : 1f;
                xfadeP = Math.max(0f, Math.min(1f, xfadeP));
                float want;
                if (xfadeTarget >= 1f) {
                    want = xfadeP < 0.4f ? xfadeP / 0.4f : 1f;         // fast rise, hold
                    // Continue from wherever the armed pre-ramp left the gain —
                    // never pull it back toward the curve's early values.
                    if (xfadeGain > want) want = xfadeGain;
                } else {
                    want = xfadeP < 0.6f ? 1f : 1f - (xfadeP - 0.6f) / 0.4f;  // hold, then cut
                }
                // Still ease toward the curve value so gain never steps (prevents zipper).
                float xStep = xfadeMs > 0f ? gdt / (xfadeMs / 1000f) : 1f;
                if (xfadeGain < want) xfadeGain = Math.min(want, xfadeGain + xStep);
                else if (xfadeGain > want) xfadeGain = Math.max(want, xfadeGain - xStep);
                AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0f, curGain * xfadeGain * masterVolume * gainComp));

                // Automix tempo ramp-back: hold the beat-matched tempo through the overlap
                // (effectiveAutomixRampHoldMs), then ease plannedTempo → 1.0 over
                // effectiveAutomixRampMs so B returns to its own speed. Only runs on a stretched deck (wsola != null).
                if (wsola != null && liveTempo != 1f) {
                    float rampHoldMs = (float) settings.intOf("automixRampHoldMs");
                    float rampMs     = (float) settings.intOf("automixRampMs");
                    float since = (System.nanoTime() - fadeInStartNs) / 1e6f; // ms since open
                    float t = (since - rampHoldMs) / rampMs;                  // 0→1 over the ramp
                    if (t <= 0f) {
                        liveTempo = plannedTempo;                             // still holding
                    } else if (t >= 1f) {
                        liveTempo = 1f;                                       // ramp done
                    } else {
                        float s = t * t * (3f - 2f * t);                     // smoothstep ease
                        liveTempo = plannedTempo + (1f - plannedTempo) * s;
                    }
                    wsola.setTempo(liveTempo);
                }

                // Faded out for a crossfade → stop this deck and idle (thread/context stay
                // alive so the mixer can reuse it as the next incoming deck).
                if (fadeOutIdle && xfadeGain <= 0.001f) {
                    fadeOutIdle = false;
                    playing.set(false);
                    source = null;      // don't let the outer loop replay this track
                    return false;
                }

                // Paused AND fully faded out → actually suspend the source and idle.
                if (!playing.get() && curGain <= 0.001f) {
                    if (AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING)
                        AL10.alSourcePause(sourceId);
                    publishPosition();
                    Thread.sleep(20L);
                    continue;
                }
                // Playing, or still fading out — keep the source running so the ramp is audible.
                if (AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED)
                    AL10.alSourcePlay(sourceId);

                // Recycle processed buffers, refill from decoder
                int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
                while (processed-- > 0) {
                    int buf = AL10.alSourceUnqueueBuffers(sourceId);
                    Integer frames = queuedFrames.pollFirst();
                    if (frames != null) {
                        framesSinceBase += frames;
                        // Content advances by output-frames × the tempo those frames played at.
                        // (liveTempo is ~constant across the ~90ms buffer, so using its current
                        // value is accurate to a few ms even mid-ramp.)
                        contentFramesSinceBase += frames * (double) liveTempo;
                    }
                    if (!draining) {
                        int f = decodeInto(pcm, buf);
                        if (f > 0) {
                            AL10.alSourceQueueBuffers(sourceId, buf);
                            queuedFrames.addLast(f);
                        } else {
                            draining = true;
                        }
                    }
                }

                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                int state  = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                if (draining && queued == 0) { publishPosition(); return true; }
                // Recover underrun / post-device-switch silence (STOPPED or INITIAL with data).
                if (playing.get() && curGain > 0.001f && queued > 0
                        && (state == AL10.AL_STOPPED || state == AL10.AL_INITIAL)) {
                    bindAlContext();
                    AL10.alSourcePlay(sourceId);
                }
                // Recover hard stall: intending to play but queue empty and not at EOS.
                if (playing.get() && curGain > 0.001f && !draining && queued == 0
                        && (state == AL10.AL_STOPPED || state == AL10.AL_INITIAL)) {
                    draining = !reopenOutputAndReprime(pcm);
                    queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                    if (queued == 0) draining = true;
                }

                publishPosition();
                LongConsumer prog = onProgress;
                if (prog != null) prog.accept(positionMs);

                Thread.sleep(8L);
            }
            return false;
        } finally {
            AL10.alSourceStop(sourceId);
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
            queuedFrames.clear();
            framesSinceBase = 0L;
            contentFramesSinceBase = 0.0;
            // Drop the stretcher reference so an idle deck doesn't hold a stale wrapper;
            // the next open re-establishes it (or leaves it null on an un-stretched start).
            wsola = null;
            highpass = null;
            pendingHighpass = false;
            liveTempo = 1f;
            pcm.close();
        }
    }

    private static PcmSource openPcm(String url) throws java.io.IOException {
        String src0 = url;
        if (src0.startsWith("file:")) src0 = src0.substring("file:".length());
        return PcmSource.open(src0);
    }

    private boolean primeBuffers(PcmSource pcm) throws Exception {
        AL10.alSourceStop(sourceId);
        AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
        queuedFrames.clear();
        framesSinceBase = 0L;
        contentFramesSinceBase = 0.0;   // content clock re-anchors with framesSinceBase
        contentFramesSinceBase = 0.0;
        boolean any = false;
        for (int b : buffers) {
            int f = decodeInto(pcm, b);
            if (f <= 0) break;
            AL10.alSourceQueueBuffers(sourceId, b);
            queuedFrames.addLast(f);
            any = true;
        }
        return any;
    }

    // Beat pulse in ~[0,1] driven by ONSET detection, not by raw loudness: each decoded
    // window's RMS vs a slow baseline fires a pulse on a rising edge (kick/snare), then
    // decays. Read by the reactive fluid background; 0 when idle/paused.
    private float beatBaseline = 0f;
    private float beatPrevEnergy = 0f;
    private float beatPulse = 0f;

    private static final int    FFT_SIZE  = 1024;
    private final float[]       fftBuffer = new float[FFT_SIZE];

    /**
     * Band-averaging spectrum identical to SoarClient MusicPlayer.updateSpectrum.
     * No actual FFT — divides the raw PCM window into equal amplitude bands.
     * Smoothing uses asymmetric lerp (fast rise, slow decay) instead of SimpleAnimation.
     */
    private void updateBeat(byte[] audioData, int bytes) {
        // Only the mixer's ACTIVE deck feeds the shared BEAT/VISUALIZER statics; a deck
        // that is fading out during a crossfade must not fight the incoming deck for them.
        if (!feedsStatics) return;
        // Fill fftBuffer from raw 16-bit PCM (little-endian, first channel only)
        int n = Math.min(bytes / 2, FFT_SIZE);
        double sq = 0.0;
        for (int i = 0; i < n; i++) {
            int idx = i * 2;
            if (idx + 1 < bytes) {
                short sample = (short)((audioData[idx + 1] << 8) | (audioData[idx] & 0xFF));
                float s = sample / 32768.0f;
                fftBuffer[i] = s;
                sq += s * s;
            }
        }

        // ── Onset-driven beat pulse ──────────────────────────────────────────
        // RMS = this window's energy. A slow baseline tracks the ambient level; a pulse
        // fires only when energy jumps ABOVE the baseline with a rising edge (energy > last
        // window's) — i.e. a transient/hit, not steady loudness. The pulse then decays each
        // window so it falls back between beats. This is what makes the value track rhythm.
        float rms = n > 0 ? (float) Math.sqrt(sq / n) : 0f;
        // Baseline: slow ease toward the current energy (rises slower than it falls, so a
        // sustained loud section doesn't swallow the baseline and kill sensitivity).
        float baseK = rms > beatBaseline ? 0.04f : 0.10f;
        beatBaseline += (rms - beatBaseline) * baseK;
        // Excess over baseline, only counting an upward step (onset).
        float excess = rms - beatBaseline * 1.10f;      // more sensitive: clear only 10% over ambient
        float rising = rms - beatPrevEnergy;            // rising edge
        beatPrevEnergy = rms;
        if (excess > 0f && rising > 0f) {
            // Trigger strength scaled by how hard it popped over the baseline. Higher gain
            // so ordinary hits punch to near-full, giving a stronger, more obvious pulse.
            float hit = Math.min(1f, excess * 11.0f);
            if (hit > beatPulse) beatPulse = hit;       // take the stronger of decay/new hit
        }
        // Decay the pulse toward 0 each window (~90 ms/window → falls in a few windows).
        beatPulse *= 0.62f;
        if (beatPulse < 0.002f) beatPulse = 0f;
    }

    /** Current 0..1 onset beat pulse (0 when idle/paused). */
    public float beatLevel() { return beatPulse; }

    private int decodeInto(PcmSource pcm, int buf) throws java.io.IOException {
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
        updateBeat(stagingChunk, got);
        nativeChunk.clear();
        nativeChunk.put(stagingChunk, 0, got).flip();
        AL10.alBufferData(buf, alFormat, nativeChunk, sampleRate);
        return got / frameBytes;
    }

    private void publishPosition() {
        // AL_SAMPLE_OFFSET = 0x1025 (AL11 constant) — output frames into the current buffer.
        int offset = Math.max(0, AL10.alGetSourcei(sourceId, 0x1025));
        if (wsola != null) {
            // Stretched: the deck outputs at a different rate than the CONTENT advances, so
            // report CONTENT time (what the lyric clock needs). contentFramesSinceBase already
            // sums dequeued output-frames × liveTempo; the not-yet-counted in-buffer offset is
            // scaled by the current liveTempo too. seekBaseMs is content time (the inner source
            // seek), so this stays exact once liveTempo settles at 1.0 (zero steady drift).
            double contentFrames = contentFramesSinceBase + offset * (double) liveTempo;
            positionMs = seekBaseMs + Math.round(contentFrames * 1000.0 / sampleRate);
        } else {
            // Un-stretched path: byte-identical to before.
            long frames = framesSinceBase + offset;
            positionMs = seekBaseMs + frames * 1000L / sampleRate;
        }
    }

    // OpenAL context for the audio thread.
    private long ownAlContext = 0L;
    /** Own device — thread-local context preferred; fall back to process-wide current. */
    private long ownAlDevice = 0L;
    /** True when this thread can use alcSetThreadContext (set in initAl). */
    private boolean useThreadLocalContext = false;

    private boolean reopenOutputAndReprime(PcmSource pcm) throws Exception {
        long pos = Math.max(0L, positionMs);
        try {
            seekBaseMs = pcm.seek(pos);
        } catch (Exception e) {
            Logger.error("[audio] seek after reopen failed", e);
            seekBaseMs = pos;
        }
        framesSinceBase = 0L;
        contentFramesSinceBase = 0.0;
        boolean ok = primeBuffers(pcm);
        if (ok && playing.get()) {
            AL10.alSourcePlay(sourceId);
        } else if (ok && !playing.get()) {
            if (AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                AL10.alSourcePause(sourceId);
            }
        }
        return ok;
    }

    private void bindAlContext() {
        if (ownAlContext == 0L) return;
        if (useThreadLocalContext) {
            org.lwjgl.openal.EXTThreadLocalContext.alcSetThreadContext(ownAlContext);
        } else {
            org.lwjgl.openal.ALC10.alcMakeContextCurrent(ownAlContext);
        }
    }

    private void initAl() {
        ownAlDevice = org.lwjgl.openal.ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
        if (ownAlDevice == 0L) throw new RuntimeException("Cannot open OpenAL device");
        ownAlContext = org.lwjgl.openal.ALC10.alcCreateContext(ownAlDevice, (java.nio.IntBuffer) null);
        if (ownAlContext == 0L) throw new RuntimeException("Cannot create OpenAL context");
        org.lwjgl.openal.ALCCapabilities alcCaps = org.lwjgl.openal.ALC.createCapabilities(ownAlDevice);
        useThreadLocalContext = alcCaps.ALC_EXT_thread_local_context;
        bindAlContext();
        org.lwjgl.openal.AL.createCapabilities(alcCaps);
        sourceId = AL10.alGenSources();
        buffers  = new int[NUM_BUFFERS];
        for (int i = 0; i < NUM_BUFFERS; i++) buffers[i] = AL10.alGenBuffers();
        nativeChunk = java.nio.ByteBuffer.allocateDirect(CHUNK_BYTES);
    }

    private void releaseAl() {
        try {
            if (sourceId != 0) {
                AL10.alSourceStop(sourceId);
                AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(sourceId);
                sourceId = 0;
            }
            if (buffers != null) {
                for (int b : buffers) AL10.alDeleteBuffers(b);
                buffers = null;
            }
        } catch (Exception ignored) {}
        try {
            if (ownAlContext != 0L) {
                try { org.lwjgl.openal.EXTThreadLocalContext.alcSetThreadContext(0L); } catch (Exception ignored2) {}
                try { org.lwjgl.openal.ALC10.alcDestroyContext(ownAlContext); } catch (Exception ignored2) {}
                ownAlContext = 0L;
            }
        } catch (Exception ignored) {}
        try {
            if (ownAlDevice != 0L) {
                org.lwjgl.openal.ALC10.alcCloseDevice(ownAlDevice);
                ownAlDevice = 0L;
            }
        } catch (Exception ignored) {}
        useThreadLocalContext = false;
    }
}
