package dev.t1m3.qplayer.desktop.audio.automix;

import dev.t1m3.qplayer.automix.BeatAnalyzer;
import dev.t1m3.qplayer.automix.KeyDetector;
import dev.t1m3.qplayer.util.Logger;

import dev.t1m3.qplayer.desktop.audio.PcmSource;

/**
 * Offline automix planner.
 *
 * ★ MODIFIED: The "best landing point" search is now strictly bounded by B's intro
 * (0ms to firstLyricMs). This ensures the transition lands on a rich musical moment
 * BEFORE the vocals start, which matches the definition of "前奏".
 */
public final class AutomixPlanner {

    private static final long ANALYSIS_WINDOW_MS = 12_000L;
    /**
     * Tempo-match is only worth stretching within this band — Apple's AutoMix is
     * conservative on purpose: a bigger ratio sounds like the song is being pulled
     * out of shape, so it degrades to a plain crossfade instead.
     */
    private static final float MIN_RATIO = 0.92f;
    private static final float MAX_RATIO = 1.08f;

    /** Fallback scan length if no lyric boundary is provided — 20s per the design doc
     *  (spec §5.1 "若无则回退到 20 秒"). Must fit inside the 16s automix lead window so
     *  planning finishes before the fire window. */
    private static final long FALLBACK_INTRO_SCAN_MS = 20_000L;

    /**
     * The transition plan.
     *
     * @param tempo   WSOLA factor (targetBpm/nativeBpm); 1 = no stretch
     * @param rmsA    window RMS of A's mix-out zone (for loudness compensation)
     * @param rmsB    window RMS of B's mix-in zone
     * @param outroMsA relative ms (from {@code aOutStartMs}) of the last quiet section
     *                 in A's outro, or -1 if none found
     */
    public record Plan(float tempo, float bpmA, float bpmB, float phaseMsA, float phaseMsB,
                float rmsA, float rmsB, float outroMsA,
                KeyDetector.Key keyA, KeyDetector.Key keyB) {}

    public interface Callback { void onPlan(Plan plan); }

    private AutomixPlanner() {}

    /**
     * Asynchronously plan the transition.
     *
     * @param introBoundaryMsB  The end of B's intro (first lyric start time) in ms.
     *                          If <= 0, a 20-second fallback is used.
     */
    public static void planAsync(String urlA, long aOutStartMs, String urlB, long bInStartMs,
                          long introBoundaryMsB, long aLastLyricEndMs, Callback cb) {
        Thread t = new Thread(() -> {
            Plan plan = null;
            try {
                plan = plan(urlA, aOutStartMs, urlB, bInStartMs, introBoundaryMsB,
                        aLastLyricEndMs);
            } catch (Throwable e) {
                Logger.error("automix planning failed", e);
            }
            cb.onPlan(plan);
        }, "automix-plan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Aggressive fallback: when a zone can't be analysed, still return a "plain fade"
     * plan so automix runs as a crossfade (B from 0, tempo 1, no phase lock) instead of
     * degrading to a hard cut. This is the "D" knob — prefer mixing over not mixing.
     */
    private static Plan degradedPlan(String urlA, String urlB, long bInStartMs, long aOutStartMs) {
        Logger.info("automix degraded to plain fade (analysis unavailable)");
        return new Plan(1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, null, null);
    }

    /**
     * Apple-AutoMix-style transition planner.
     *
     * <p>Apple's logic (as documented by Apple + analysed by the community):
     * <ol>
     *   <li><b>Vibe check</b> — only beat-match when the two BPMs are close; otherwise
     *       degrade to a plain crossfade. No hard time-stretch of mismatched tempos
     *       ("one song sounding like a chipmunk").</li>
     *   <li><b>Skip long quiet intros/outros</b> — mix OUT at A's outro start (the last
     *       energy dip before the end) and mix IN at B's intro END (first sustained
     *       musical entrance), not at fixed offsets. Structural, not positional.</li>
     *   <li><b>Subtle tempo alignment</b> — time-stretch is a light touch (±8%), and the
     *       incoming track's BPM is analysed at its intro-end, where the real groove is.</li>
     * </ol>
     */
    public static Plan plan(String urlA, long aOutStartMs, String urlB, long bInStartMs,
                     long introBoundaryMsB, long aLastLyricEndMs) {
        // ── A side: mix out AFTER the last lyric ends, so the overlap is A's instrumental
        //    tail (vocals already stopped) under B's intro — vocals never collide, which
        //    is the real reason a crossfade sounds "seamless". aLastLyricEndMs is the
        //    absolute track position of A's final vocal; add a short tail so the last word
        //    rings out before B comes in.
        float outroMs;
        if (aLastLyricEndMs > 0) {
            outroMs = Math.max(0f, aLastLyricEndMs - aOutStartMs + 1500f);  // +1.5s tail
        } else {
            // No lyrics available — fall back to structural energy detection.
            outroMs = findOutroLanding(urlA, aOutStartMs);
        }
        long aMixStart = aOutStartMs + Math.max(0L, (long) outroMs);

        ZoneResult za = analyzeZone(urlA, aMixStart);
        // If the detected outro sits too close to the track's end, the analysis window
        // (12s) overruns the stream and BeatAnalyzer gets too little data — fall back to
        // the scan start so we still get a usable BPM and the automix actually fires.
        if (za == null && outroMs > 0) {
            aMixStart = aOutStartMs;
            outroMs = 0f;
            za = analyzeZone(urlA, aMixStart);
        }
        // D: stay aggressive — if A can't be analysed, still plan a plain crossfade
        // (tempo 1, start at 0) instead of giving up and hard-cutting.
        if (za == null) return degradedPlan(urlA, urlB, bInStartMs, aOutStartMs);

        // ── B side: Apple picks the mix-in point across a WIDE region — often directly
        // ── B side: mix in as EARLY as possible — the first musically-viable point in
        //    the track, not a fixed section. Prefer the first LYRIC start (vocals = the
        //    most recognisable entry); if lyrics are unavailable, use the first sustained
        //    energy rise (intro end). Never jump to a chorus — B stays as early as it can.
        float introEndMs;
        if (introBoundaryMsB > 0) {
            // Mix in just BEFORE the first lyric so B's instrumental intro slides under
            // A's tail — by the time B's vocals start, A is already gone. Clamp so we
            // don't go negative; land 1.2s early so the entry is audible but vocals
            // still don't overlap A's.
            introEndMs = Math.max(0f, (float) introBoundaryMsB - 1200f);
        } else {
            introEndMs = findIntroEnd(urlB, bInStartMs, FALLBACK_INTRO_SCAN_MS);
            if (introEndMs < 0) introEndMs = 0f;
        }

        ZoneResult zb = analyzeZone(urlB, bInStartMs + (long) introEndMs);
        // If the intro-end is too close to the end of B (very short track), fall back
        // to the track start for a usable BPM.
        if (zb == null && introEndMs > 0) {
            introEndMs = 0f;
            zb = analyzeZone(urlB, bInStartMs);
        }
        // D: B unanalysable → still plan a plain crossfade rather than hard-cutting.
        if (zb == null) return degradedPlan(urlA, urlB, bInStartMs, aOutStartMs);

        // Apple-style: snap B's entry to the first BAR boundary at/after the intro end,
        // so B comes in on a phrase start (downbeat of a bar), not mid-measure.
        // gridB's barPhaseMs is relative to (bInStartMs + introEndMs); introEndMs itself
        // is relative to bInStartMs, so the snapped value is introEndMs + barPhaseRel.
        if (zb.grid.bpm() > 1f) {
            introEndMs += zb.grid.barPhaseMs();
        }

        BeatAnalyzer.BeatGrid gridA = za.grid;
        BeatAnalyzer.BeatGrid gridB = zb.grid;

        float bpmA = gridA.bpm();
        float bpmB = octaveNormalise(gridB.bpm(), bpmA);

        // ── Vibe check: only beat-match when BPMs are close; else plain fade. ──
        float tempo = bpmA / bpmB;
        if (tempo < MIN_RATIO || tempo > MAX_RATIO) tempo = 1f;

        // A's beat grid: Apple aligns to BARS ("the exact bar where an outro meets an
        // intro"), so the handover lands on a phrase boundary. phaseMsA is expressed
        // RELATIVE TO aOutStartMs (the GUI's fire clock base) and is the nearest BAR
        // boundary to the outro dip — the GUI computes the absolute fire time as
        // aOutStartMs + phaseMsA.
        float phaseA = gridA.barPhaseMs() + outroMs;
        if (outroMs >= 0f && bpmA > 1f) {
            float barPeriod = gridA.barPeriodMs();
            // Align to the bar boundary nearest the outro dip, in aOutStartMs-relative space.
            float k = Math.round((outroMs - phaseA) / barPeriod);
            float shifted = phaseA + k * barPeriod;
            if (shifted >= 0f && shifted <= ANALYSIS_WINDOW_MS + outroMs) phaseA = shifted;
        }

        Plan plan = new Plan(tempo, bpmA, bpmB, phaseA, introEndMs,
                za.rms, zb.rms, outroMs, za.key, zb.key);
        Logger.info("automix plan: bpmA=" + bpmA + " bpmB=" + bpmB +
                " tempo=" + tempo + " introEndMs=" + introEndMs +
                " outroMs=" + outroMs + " aMixStart=" + aMixStart +
                " rmsA=" + za.rms + " rmsB=" + zb.rms +
                " keyA=" + (za.key != null ? za.key.label() : "?")
                + " keyB=" + (zb.key != null ? zb.key.label() : "?")
                + " (boundary=" + introBoundaryMsB + ")" +
                " objScore=" + String.format(java.util.Locale.ROOT, "%.1f",
                        objectiveScore(tempo, bpmA, bpmB, phaseA, introEndMs, za.rms, zb.rms))
                + " (spec §12.3)");
        return plan;
    }

    /**
     * Spec §12.3 objective score: 60×(1−归一化频谱距离) + 30×(1−归一化相位差)
     * + 10×(1−归一化能量变异). The planner has no full spectrum (only zone RMS), so the
     * spectral term is proxied by the two mix zones' RMS mismatch; the phase term is the
     * two entry phases aligned mod the (matched) beat period. Used by the Harness grid
     * search / manual tuning to compare parameter combos — higher is better.
     */
    private static float objectiveScore(float tempo, float bpmA, float bpmB,
                                        float phaseA, float introEndMs,
                                        float rmsA, float rmsB) {
        float maxR = Math.max(rmsA, rmsB);
        float rmsMismatch = maxR > 1e-4f ? Math.abs(rmsA - rmsB) / maxR : 0f;
        // Spectral term (60) — proxied by zone RMS mismatch.
        float spectralTerm = 60f * (1f - rmsMismatch);
        // Energy term (10) — loudness continuity across the handover.
        float energyTerm = 10f * (1f - rmsMismatch);
        // Phase term (30) — B's entry phase vs A's fire phase, aligned mod the period.
        float phaseTerm = 30f;
        if (tempo != 1f && bpmA > 1f && bpmB > 1f) {
            float period = 60_000f / bpmA;        // B plays stretched to A's period
            float d = Math.abs(phaseA - introEndMs) % period;
            float norm = Math.min(d, period - d) / period;
            phaseTerm = 30f * (1f - norm);
        }
        return spectralTerm + phaseTerm + energyTerm;
    }

    /**
     * Find where B's intro ENDS (the first moment the track really "starts": a sustained
     * rise in energy, usually the drums/vocals kicking in). This is where a DJ drops the
     * incoming track — on the musical downbeat, not on dead air.
     *
     * <p>Apple's AutoMix behaves this way: the mix-in point is structural (end of intro),
     * not a fixed time offset. Falls back to the first meaningful onset if no clear
     * intro-end is found.
     */
    private static float findIntroEnd(String url, long startMs, long maxScanMs) {
        if (maxScanMs <= 0) maxScanMs = FALLBACK_INTRO_SCAN_MS;
        // 至少需要 1 秒数据，否则直接返回 0
        if (maxScanMs < 1000) return 0f;

        PcmSource pcm = null;
        try {
            pcm = PcmSource.open(url);
            int sr = pcm.sampleRate();
            int ch = Math.max(1, Math.min(2, pcm.channels()));
            if (startMs > 0) pcm.seek(startMs);

            int frameBytes = ch * 2;
            long wantFrames = (long) sr * maxScanMs / 1000L;
            if (wantFrames < sr) return 0f; // 太短则回到 0

            float[] mono = new float[(int) wantFrames];
            byte[] buf = new byte[16 * 1024 - (16 * 1024 % frameBytes)];
            int monoLen = 0;

            while (monoLen < mono.length) {
                int got = pcm.read(buf, 0, buf.length);
                if (got <= 0) break;
                int frames = got / frameBytes;
                for (int f = 0; f < frames && monoLen < mono.length; f++) {
                    int b = f * frameBytes;
                    int sum = 0;
                    for (int c = 0; c < ch; c++) {
                        int bi = b + c * 2;
                        sum += (short) ((buf[bi + 1] << 8) | (buf[bi] & 0xFF));
                    }
                    mono[monoLen++] = (sum / ch) / 32768f;
                }
            }
            int validLen = Math.min(monoLen, (int) (sr * maxScanMs / 1000L));
            if (validLen < sr / 4) return 0f;

            return detectIntroEnd(mono, validLen, sr);
        } catch (Throwable e) {
            Logger.error("intro end detection failed for " + url, e);
            return -1;
        } finally {
            if (pcm != null) try { pcm.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Energy-envelope intro-end detection. Hop RMS over the scan window; the intro end is
     * the first hop where RMS rises above a floor AND stays there — the "this is where the
     * song actually begins" boundary. Uses a short attack window so a single drum hit
     * doesn't count; the point is a SUSTAINED step up.
     *
     * <p>Fallback: if no sustained entrance is found (quiet intros, spoken openings),
     * use the old "richest moment" heuristic so we still land somewhere meaningful
     * instead of at 0.
     */
    private static float detectIntroEnd(float[] mono, int len, int sr) {
        int hop = 512; // ~12ms per hop
        int numHops = len / hop;
        if (numHops < 16) return 0f;

        float[] rms = new float[numHops];
        float maxRms = 0f;
        for (int h = 0; h < numHops; h++) {
            int base = h * hop;
            double sq = 0.0;
            for (int i = 0; i < hop && (base + i) < len; i++) {
                float s = mono[base + i];
                sq += (double) s * s;
            }
            rms[h] = (float) Math.sqrt(sq / hop);
            if (rms[h] > maxRms) maxRms = rms[h];
        }
        if (maxRms < 0.001f) return 0f;

        // Look for the first hop that is ≥ 40% of the window's peak AND is followed by a
        // run of ≥ 1.0s that stays above 30% — a sustained musical entrance, not a blip.
        float stepFloor = maxRms * 0.40f;
        float holdFloor = maxRms * 0.30f;
        int hold = Math.max(3, Math.round(1.0f * sr / hop));
        for (int h = 0; h < numHops - hold; h++) {
            if (rms[h] < stepFloor) continue;
            boolean sustained = true;
            for (int j = h; j < h + hold; j++) {
                if (rms[j] < holdFloor) { sustained = false; break; }
            }
            if (!sustained) continue;
            // 落点向前偏移（spec §5.3：最佳落点向前偏移 0.3~0.5 秒，默认 400ms），
            // 让我们落在强拍进入点之前（最后一个安静的节拍），给滑音留“准备”时间。
            // 偏移量是运行时可调参数 FIND_INTRO_OFFSET（spec §12.1，范围 0-1000ms）。
            float offsetSec = introOffsetMs() / 1000f;
            float ms = (h * hop) * 1000f / sr;
            return Math.max(0f, ms - offsetSec * 1000f);
        }
        // Fallback: no clear sustained entrance (quiet/spoken intro) — land on the
        // "richest" moment (RMS×flux peak) so we don't just start at 0.
        return richestMoment(mono, len, sr, rms);
    }

    /**
     * Old "best landing" heuristic kept as fallback: the hop where normalised RMS ×
     * spectral-flux peaks, biased away from the very start and very end. Never fails
     * harder than 0 — always returns a musical-ish point.
     */
    private static float richestMoment(float[] mono, int len, int sr, float[] rms) {
        int hop = 512;
        int numHops = rms.length;
        float[] flux = new float[numHops];
        float[] prevFrame = new float[hop];
        float maxFlux = 0f;
        for (int h = 0; h < numHops; h++) {
            int base = h * hop;
            float f = 0f;
            for (int i = 0; i < hop && (base + i) < len; i++) {
                float s = mono[base + i];
                f += Math.abs(s - prevFrame[i]);
                prevFrame[i] = s;
            }
            flux[h] = f / hop;
            if (flux[h] > maxFlux) maxFlux = flux[h];
        }
        float maxRms = 0f;
        for (float v : rms) if (v > maxRms) maxRms = v;
        if (maxRms < 0.001f || maxFlux < 0.001f) return 0f;

        float bestScore = -1f;
        int bestHop = 0;
        float totalSec = len / (float) sr;
        for (int h = 0; h < numHops; h++) {
            float s = (rms[h] / maxRms) * (flux[h] / maxFlux);
            float timeSec = (h * hop) / (float) sr;
            if (timeSec < 1.0f) s *= (timeSec / 1.0f);
            if (timeSec > totalSec - 0.3f) s *= ((totalSec - timeSec) / 0.3f);
            if (s > bestScore) { bestScore = s; bestHop = h; }
        }
        // 落点向前偏移 0.3~0.5 秒（默认 400ms），让滑音有“准备”时间（spec §5.3）；
        // 偏移量是运行时可调参数 FIND_INTRO_OFFSET（spec §12.1，范围 0-1000ms）。
        float offsetSec = introOffsetMs() / 1000f;
        int offsetHop = (int) (offsetSec * sr / hop);
        int landingHop = Math.max(0, bestHop - offsetHop);
        float landingMs = (landingHop * hop) * 1000f / sr;
        float maxMs = Math.max(500f, (totalSec - 0.1f) * 1000f);
        return Math.max(500f, Math.min(maxMs, landingMs));
    }

    // ── 以下为原有方法，未改动 ──────────────────────────────────────────────

    /** Beat grid + window RMS for one zone (RMS feeds the loudness compensation). */
    private record ZoneResult(BeatAnalyzer.BeatGrid grid, float rms, KeyDetector.Key key) {}

    private static ZoneResult analyzeZone(String url, long startMs) {
        if (url == null || url.isBlank()) return null;
        PcmSource pcm = null;
        try {
            pcm = PcmSource.open(url);
            int sr = pcm.sampleRate();
            int ch = Math.max(1, Math.min(2, pcm.channels()));
            if (startMs > 0) pcm.seek(startMs);

            int frameBytes = ch * 2;
            long wantFrames = (long) sr * ANALYSIS_WINDOW_MS / 1000L;
            float[] mono = new float[(int) wantFrames];
            byte[] buf = new byte[16 * 1024 - (16 * 1024 % frameBytes)];
            int monoLen = 0;

            while (monoLen < mono.length) {
                int got = pcm.read(buf, 0, buf.length);
                if (got <= 0) break;
                int frames = got / frameBytes;
                for (int f = 0; f < frames && monoLen < mono.length; f++) {
                    int b = f * frameBytes;
                    int sum = 0;
                    for (int c = 0; c < ch; c++) {
                        int bi = b + c * 2;
                        sum += (short) ((buf[bi + 1] << 8) | (buf[bi] & 0xFF));
                    }
                    mono[monoLen++] = (sum / ch) / 32768f;
                }
            }
            if (monoLen < mono.length) mono = java.util.Arrays.copyOf(mono, monoLen);

            if (monoLen < mono.length * 0.5) return null;   // zone overran the stream (near EOF) —
            // let plan() fall back to the scan start instead of fitting a garbage grid to a stub
            BeatAnalyzer.BeatGrid grid = BeatAnalyzer.analyze(mono, sr);
            if (grid == null) return null;
            // RMS of the whole analysed window — a stable loudness proxy for the zone.
            double sq = 0.0;
            for (float s : mono) sq += (double) s * s;
            float rms = mono.length > 0 ? (float) Math.sqrt(sq / mono.length) : 0f;
            // 调性检测 (Krumhansl–Schmuckler) — the "compatibility" dimension for the
            // transition decision; null for degenerate/degraded zones.
            KeyDetector.Key key = KeyDetector.detect(mono, sr);
            return new ZoneResult(grid, rms, key);
        } catch (Throwable e) {
            Logger.error("automix zone analysis failed for " + url, e);
            return null;
        } finally {
            if (pcm != null) try { pcm.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Find A's LAST energy dip within the outro zone (relative ms from {@code startMs}),
     * or -1 if the zone has no quiet section. The transition fires on the beat nearest
     * this dip so the handover lands in a "breathing" moment instead of mid-chorus.
     *
     * <p>Unlike a fixed-window scan, this reads until the stream ends, so the last dip
     * is the track's TRUE outro (the final quiet-down), not just a local lull.
     */
    private static float findOutroLanding(String url, long startMs) {
        PcmSource pcm = null;
        try {
            pcm = PcmSource.open(url);
            int sr = pcm.sampleRate();
            int ch = Math.max(1, Math.min(2, pcm.channels()));
            if (startMs > 0) pcm.seek(startMs);

            int frameBytes = ch * 2;
            // Read the whole tail of the track (bounded to a sane max).
            long wantFrames = (long) sr * 90_000L / 1000L;   // up to 90s of outro scanning
            float[] mono = new float[(int) wantFrames];
            byte[] buf = new byte[16 * 1024 - (16 * 1024 % frameBytes)];
            int monoLen = 0;

            while (monoLen < mono.length) {
                int got = pcm.read(buf, 0, buf.length);
                if (got <= 0) break;
                int frames = got / frameBytes;
                for (int f = 0; f < frames && monoLen < mono.length; f++) {
                    int b = f * frameBytes;
                    int sum = 0;
                    for (int c = 0; c < ch; c++) {
                        int bi = b + c * 2;
                        sum += (short) ((buf[bi + 1] << 8) | (buf[bi] & 0xFF));
                    }
                    mono[monoLen++] = (sum / ch) / 32768f;
                }
            }
            if (monoLen < sr / 2) return -1f;
            return detectOutroLanding(mono, monoLen, sr);
        } catch (Throwable e) {
            Logger.error("outro landing detection failed for " + url, e);
            return -1f;
        } finally {
            if (pcm != null) try { pcm.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Scan {@code mono} for the LAST run of ≥0.6s where per-hop RMS stays below 55% of the
     * zone mean — a real structural dip (outro/break), not a single-frame dip. Returns the
     * dip start in ms. Backwards scan so we grab the last (closest-to-end) quiet moment,
     * which is what a DJ would mix out of.
     *
     * <p>Fallback: if no sustained dip exists (fade-out outros, dense tail sections), fall
     * back to the single quietest hop near the end — still picks a breathing point rather
     * than giving up and returning -1 (which would pin the mix to a fixed offset).
     */
    /**
     * Find the START of A's structural outro — the point where the LAST high-energy
     * section (final chorus/drop) gives way to the calmer ending. This is where a DJ
     * mixes out: it's a musical boundary, not the literal last seconds of the file.
     *
     * <p>Algorithm: hop RMS envelope; find the last sustained run of high energy
     * (≥ 70% of the zone peak), then walk forward from its end until energy drops below
     * 55% of the mean — that drop is the outro start. Unlike the old "last quiet gap"
     * (which always lands in the final fade-out seconds), this lands 10-30s before the
     * end, on a real structural boundary. Falls back to the last quiet gap if no clear
     * outro structure exists (e.g. fade-out outros).
     */
    /**
     * Find the START of A's structural outro — the point where the LAST high-energy
     * section (final chorus/drop) gives way to the calmer ending. This is where a DJ
     * mixes out: it's a musical boundary, not the literal last seconds of the file.
     *
     * <p>Algorithm: hop-RMS envelope smoothed with a ~1.5s boxcar (removes beat-level
     * flutter so section boundaries show up); take the envelope's global maximum
     * (the last big section), then walk forward to the LAST sustained drop below half
     * that peak — that crossing is the outro start. The old peak×0.70 sustained-run
     * floor never fired on real music (peaks are kick/snare transients), so the
     * mix-out always landed in the final fade; the smoothed-relative version lands on
     * the real boundary. Falls back to the last quiet gap if no drop exists (fade-outs).
     */
    private static float detectOutroLanding(float[] mono, int len, int sr) {
        int hop = 1024;                       // ~23ms per hop
        int numHops = len / hop;
        if (numHops < 8) return -1f;

        float[] rms = new float[numHops];
        for (int h = 0; h < numHops; h++) {
            int base = h * hop;
            double sq = 0.0;
            for (int i = 0; i < hop && (base + i) < len; i++) {
                float s = mono[base + i];
                sq += (double) s * s;
            }
            rms[h] = (float) Math.sqrt(sq / hop);
        }

        // ── 1.5s smoothed envelope: section-level energy, beat flutter removed. ──
        int smoothWin = Math.max(3, Math.round(1.5f * sr / hop));
        float[] env = new float[numHops];
        float envMax = 0f;
        int argmax = 0;
        for (int h = 0; h < numHops; h++) {
            int lo = Math.max(0, h - smoothWin / 2);
            int hi = Math.min(numHops, h + smoothWin / 2 + 1);
            double s = 0.0;
            for (int j = lo; j < hi; j++) s += rms[j];
            env[h] = (float) (s / (hi - lo));
            if (env[h] > envMax) { envMax = env[h]; argmax = h; }
        }
        if (envMax < 1e-4f) return -1f;

        // ── last sustained drop below half the envelope peak = outro start ──
        float crossFloor = envMax * 0.5f;
        int needLow = Math.max(3, Math.round(1.2f * sr / hop));   // ≥1.2s below the floor
        int outroStart = -1;
        boolean below = false;
        int belowSince = -1;
        for (int h = argmax; h < numHops; h++) {
            if (env[h] < crossFloor) {
                if (!below) { below = true; belowSince = h; }
            } else if (below) {
                if (h - belowSince >= needLow) outroStart = belowSince;   // last sustained dip
                below = false;
            }
        }
        if (below && numHops - belowSince >= needLow) outroStart = belowSince;
        if (outroStart >= 0 && outroStart < numHops - 1) {
            return (outroStart * hop) * 1000f / sr;
        }

        // ── Fallback: last quiet gap (fade-out outros, no structural drop). ──
        float mean = 0.0f;
        for (float v : rms) mean += v;
        mean /= numHops;
        float thr = mean * 0.55f;
        int need = Math.max(1, Math.round(0.6f * sr / hop));
        for (int k = numHops - need; k >= 0; k--) {
            boolean quiet = true;
            for (int j = k; j < k + need; j++) {
                if (rms[j] > thr) { quiet = false; break; }
            }
            if (quiet) return (k * hop) * 1000f / sr;
        }
        // Last resort: quietest single hop in the final 15s.
        int scanFrom = Math.max(0, numHops - Math.round(15f * sr / hop));
        int quietest = scanFrom;
        for (int k = scanFrom; k < numHops; k++) {
            if (rms[k] < rms[quietest]) quietest = k;
        }
        return (quietest * hop) * 1000f / sr;
    }

    private static float octaveNormalise(float bpm, float ref) {
        if (bpm <= 0f) return bpm;
        float b = bpm;
        while (b < ref / 1.414f) b *= 2f;
        while (b > ref * 1.414f) b /= 2f;
        return b;
    }

    private static volatile float introOffsetMs = 400f;

    public static void setIntroOffsetMs(float ms) { introOffsetMs = ms; }

    private static float introOffsetMs() { return introOffsetMs; }
}
