package dev.t1m3.qplayer.desktop.audio;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.automix.KeyDetector;
import dev.t1m3.qplayer.automix.AutomixPlanner;
import dev.t1m3.qplayer.automix.StemCache;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.util.Logger;

import java.util.function.LongConsumer;

/**
 * Two-deck coordinator exposing the {@link AudioBackend} surface. Crossfades on
 * track switch and runs the Apple-style automix (beat-matched, tempo-stretched
 * overlap a few seconds before the current track ends). Ported from Melodify's
 * AudioMixer + MusicPlayerGui automix scheduler.
 */
public final class DesktopMixer implements AudioBackend {

    private static final float XFADE_MS = 2200f;
    private static final long AUTOMIX_FIRE_MS = 10_000L;
    private static final long FIRE_LOOKAHEAD_MS = 25L;
    private static final long ARM_TIMEOUT_MS = 20_000L;

    private final SettingsCore settings;
    private final DesktopDeck deckA;
    private final DesktopDeck deckB;
    private volatile DesktopDeck active;
    private volatile boolean switching = false;

    private volatile LongConsumer onProgress;
    private volatile Runnable onComplete;
    private volatile Runnable onStarted;
    private volatile Runnable onPaused;
    private volatile Runnable onResumed;
    private volatile Runnable onError;
    private volatile Runnable onAutomixFired;

    private volatile DesktopDeck armedDeck;
    private volatile boolean automixInFlight = false;
    private volatile long expectedDur = 0L;
    private volatile double automixBeatPeriodMs = 0.0;
    private volatile double automixBeatPhaseMs = 0.0;
    private volatile long automixFireWindowEndMs = Long.MAX_VALUE;
    private volatile long armStartedAtMs = 0L;
    private long automixPrevPosMs = 0L;

    private final Thread scheduler;
    private volatile boolean disposed = false;

    public DesktopMixer(SettingsCore settings) {
        this.settings = settings;
        this.deckA = new DesktopDeck(settings);
        this.deckB = new DesktopDeck(settings);
        this.active = deckA;
        deckA.setOnProgress(ms -> { if (active == deckA) fireProgress(ms); });
        deckB.setOnProgress(ms -> { if (active == deckB) fireProgress(ms); });
        deckA.setOnEnd(() -> { if (active == deckA) fireEnd(); });
        deckB.setOnEnd(() -> { if (active == deckB) fireEnd(); });
        deckA.setOnStarted(() -> { if (active == deckA) fireStarted(); });
        deckB.setOnStarted(() -> { if (active == deckB) fireStarted(); });
        deckA.setOnError(() -> { if (active == deckA || switching) fireError(); });
        deckB.setOnError(() -> { if (active == deckB || switching) fireError(); });
        scheduler = new Thread(this::schedulerLoop, "qplayer-automix");
        scheduler.setDaemon(true);
        scheduler.start();
    }

    private void fireProgress(long ms) {
        if (switching) return;
        LongConsumer c = onProgress;
        if (c != null) c.accept(ms);
    }

    private void fireEnd() {
        Runnable r = onComplete;
        if (r != null) r.run();
    }

    private void fireStarted() {
        Runnable r = onStarted;
        if (r != null) r.run();
    }

    private void fireError() {
        Runnable r = onError;
        if (r != null) r.run();
    }

    @Override
    public void setOnComplete(Runnable callback) { this.onComplete = callback; }

    @Override
    public void setOnStarted(Runnable callback) { this.onStarted = callback; }

    @Override
    public void setOnPaused(Runnable callback) { this.onPaused = callback; }

    @Override
    public void setOnResumed(Runnable callback) { this.onResumed = callback; }

    @Override
    public void setOnError(Runnable callback) { this.onError = callback; }

    @Override
    public void setOnAutomixFired(Runnable callback) { this.onAutomixFired = callback; }

    @Override
    public void play(String url, long startMs) {
        cancelArmed();
        boolean blend = active.isPlaying() && (settings.bool("crossfade")
                || settings.bool("audioTransport"));
        if (blend) {
            DesktopDeck outgoing = active;
            DesktopDeck incoming = (active == deckA) ? deckB : deckA;
            outgoing.fadeOutToIdle(XFADE_MS);
            incoming.setFeedsStatics(true);
            stageTransport(outgoing, incoming, XFADE_MS);
            incoming.playFadeIn(url, XFADE_MS, 1f, startMs);
            active = incoming;
        } else {
            active.setFeedsStatics(true);
            active.play(url, startMs);
        }
        switching = false;
    }

    private void stageTransport(DesktopDeck outgoing, DesktopDeck incoming, float ms) {
        if (!settings.bool("audioTransport")) return;
        String srcA = outgoing.source;
        if (srcA == null) return;
        long pos = outgoing.getPositionMs();
        long dur = outgoing.getDurationMs();
        long remaining = dur - pos;
        if (remaining < 500) return;
        float actualMs = ms;
        if (remaining < ms) actualMs = Math.max(500, remaining);
        incoming.pendingTransportSrcA = srcA;
        incoming.pendingTransportMs = actualMs;
        incoming.pendingTransportSrcASeekMs = Math.max(0L, pos);
    }

    @Override
    public boolean prepareAutomix(String urlB, long bStartMs, long aOutStartMs,
                                  long introBoundaryMsB, long aLastLyricEndMs,
                                  boolean glideAllowed, boolean stemSplit, float gainComp) {
        if (!active.isPlaying() || automixInFlight) return false;
        AutomixPlanner.setIntroOffsetMs(settings.intOf("automixIntroOffsetMs"));
        final String urlA = active.source;
        if (urlA == null || urlA.isBlank()) return false;
        automixInFlight = true;
        Thread t = new Thread(() -> {
            try {
                AutomixPlanner.Plan plan = AutomixPlanner.plan(
                        urlA, aOutStartMs, urlB, 0L, introBoundaryMsB, aLastLyricEndMs);
                if (plan == null || automixInFlight == false) return;
                applyPlan(plan, urlB, aOutStartMs, glideAllowed, stemSplit, gainComp);
            } catch (Throwable e) {
                Logger.error("automix planning failed", e);
                automixInFlight = false;
            }
        }, "automix-plan");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void applyPlan(AutomixPlanner.Plan plan, String urlB, long aOutStartMs,
                           boolean glideAllowedHint, boolean stemSplitHint, float gainCompHint) {
        float tempo = plan.tempo;
        long bStartMs = (long) plan.phaseMsB;
        float gainComp = gainCompHint;
        if (gainCompHint <= 0f && plan.rmsA > 0.001f && plan.rmsB > 0.001f) {
            gainComp = Math.max(0.5f, Math.min(2f, plan.rmsA / plan.rmsB));
        }
        boolean glide = glideAllowedHint && tempo != 1f
                && plan.keyA != null && plan.keyB != null
                && KeyDetector.harmonicDistance(plan.keyA, plan.keyB) <= 2;
        String stemPath = null;
        if (stemSplitHint) {
            try {
                stemPath = StemCache.ensure(urlB, Math.max(0L, bStartMs),
                        (int) (automixMs() + 3500f));
            } catch (Throwable e) {
                Logger.error("automix stem generation failed", e);
            }
        }
        if (!armIncoming(urlB, tempo, bStartMs, gainComp, glide && stemPath == null,
                stemPath, Math.max(0L, bStartMs))) {
            automixInFlight = false;
            return;
        }
        if (plan.bpmA > 1f) {
            automixBeatPeriodMs = 60_000.0 / plan.bpmA;
            automixBeatPhaseMs = aOutStartMs + plan.phaseMsA;
        } else {
            automixBeatPeriodMs = 0.0;
        }
        automixFireWindowEndMs = aOutStartMs + AUTOMIX_FIRE_MS;
        automixPrevPosMs = active.getPositionMs();
        armStartedAtMs = System.currentTimeMillis();
    }

    private boolean armIncoming(String url, float tempo, long startMs, float gainComp,
                                boolean glideAllowed, String stemPath, long stemStartMs) {
        if (!active.isPlaying()) return false;
        cancelArmed();
        DesktopDeck incoming = (active == deckA) ? deckB : deckA;
        if (glideAllowed && stemPath == null) stageTransport(active, incoming, automixMs());
        incoming.setGainComp(gainComp);
        incoming.armFadeInStems(url, stemPath, stemStartMs, automixMs(), tempo, startMs);
        armedDeck = incoming;
        return true;
    }

    @Override
    public boolean hasAutomixArmed() {
        return armedDeck != null;
    }

    @Override
    public void cancelAutomix() {
        automixInFlight = false;
        cancelArmed();
        automixBeatPeriodMs = 0.0;
        automixFireWindowEndMs = Long.MAX_VALUE;
    }

    private void cancelArmed() {
        DesktopDeck deck = armedDeck;
        armedDeck = null;
        if (deck != null && deck != active) deck.stop();
    }

    private void fireArmed() {
        DesktopDeck deck = armedDeck;
        armedDeck = null;
        automixInFlight = false;
        automixBeatPeriodMs = 0.0;
        automixFireWindowEndMs = Long.MAX_VALUE;
        if (deck == null || deck == active) return;
        active.fadeOutToIdle(automixMs());
        deck.releaseArmed();
        active = deck;
        switching = false;
        Runnable cb = onAutomixFired;
        if (cb != null) cb.run();
    }

    private void schedulerLoop() {
        while (!disposed) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                if (disposed) break;
            }
            try {
                DesktopDeck deck = armedDeck;
                if (deck == null || deck == active || !active.isPlaying()) continue;
                long pos = active.getPositionMs();
                long dur = expectedDur > 0L ? expectedDur : active.getDurationMs();
                long remaining = dur - pos;
                if (remaining > AUTOMIX_FIRE_MS) continue;
                if (System.currentTimeMillis() - armStartedAtMs > ARM_TIMEOUT_MS) {
                    cancelArmed();
                    continue;
                }
                // B is open + seek + primed before it enters the armed hold. Fire only
                // once that finished so the handover starts pre-buffered (network seek
                // from the track intro can take 1-2s). Hard caps: 3s wait since arm, or
                // A under 2.5s left — fire anyway so the transition never gets skipped.
                if (!deck.armedReady()) {
                    long waited = System.currentTimeMillis() - armStartedAtMs;
                    if (waited < 3000L && remaining > 2500L) continue;
                }
                boolean fire;
                if (automixBeatPeriodMs <= 0.0) {
                    fire = true;
                } else {
                    double prevRel = (automixPrevPosMs - automixBeatPhaseMs) / automixBeatPeriodMs;
                    double curRel = (pos - automixBeatPhaseMs) / automixBeatPeriodMs;
                    boolean crossed = Math.floor(curRel) > Math.floor(prevRel);
                    double frac = curRel - Math.floor(curRel);
                    double toNextBeatMs = (1.0 - frac) * automixBeatPeriodMs;
                    boolean nearBeat = toNextBeatMs <= FIRE_LOOKAHEAD_MS && toNextBeatMs > 0.0;
                    fire = crossed || nearBeat;
                    if (remaining < 4500) fire = true;
                }
                automixPrevPosMs = pos;
                if (fire) fireArmed();
            } catch (Throwable e) {
                Logger.exception(e);
            }
        }
    }

    private float automixMs() {
        boolean enhanced = settings.bool("automixEnhanced");
        int base = settings.intOf("automixMs");
        if (base <= 0) base = 4000;
        if (enhanced) base = 6000;
        return base;
    }

    @Override
    public void pause() { active.pause(); }

    @Override
    public void resume() { active.resume(); }

    @Override
    public boolean isPlaying() { return active.isPlaying(); }

    @Override
    public void seek(long ms) { active.seek(ms); }

    @Override
    public long position() {
        return active.getPositionMs();
    }

    @Override
    public void setExpectedDuration(long ms) {
        this.expectedDur = ms;
    }

    @Override
    public long duration() {
        return expectedDur > 0L ? expectedDur : active.getDurationMs();
    }

    @Override
    public void setVolume(float volume) {
        deckA.setMasterVolume(volume);
        deckB.setMasterVolume(volume);
    }

    @Override
    public void release() {
        disposed = true;
        scheduler.interrupt();
        cancelArmed();
        deckA.dispose();
        deckB.dispose();
        active = deckA;
    }

    @Override
    public float beatLevel() {
        return active.beatLevel();
    }
}
