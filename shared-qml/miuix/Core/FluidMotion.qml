pragma Singleton
import QtQuick

// Apple's *Designing Fluid Interfaces* maths, translated to qml4j.
//
// ── The engine limitation, stated honestly ─────────────────────────────────
// qml4j has no spring type. `DragHandler` is a stub (`active`/`translation`
// properties exist but drag tracking is not wired), `Easing` carries only
// Linear/Quad/Cubic/Quart/Sine/Back and an `overshoot`/`amplitude`/`period`
// triple that `Easings.apply` never reads, and animations emit one signal,
// `finished` — `stopped`/`runningChanged` are load-time errors. So "use a
// spring" cannot be taken literally here; it has to be *approximated*.
//
// ── The approximation, and why it is this one ──────────────────────────────
// The single engine behaviour that makes the approximation legitimate is that
// `PropertyAnimation` ignores an omitted `from`: `prepare()` reads
// `MemberAccess.readMember(target, prop)` on the animation's first tick. Every
// animation started without an explicit `from` therefore begins at the
// *presentation* value — which is precisely §3's "always animate from the
// current value, never the target value". Interruptibility is free; what is
// missing is the curve.
//
// Apple parameterises a spring as (dampingRatio, response). Response is not a
// duration — it is roughly how long the value takes to arrive. Mapped onto the
// curves that exist:
//
//   damping 1.0 (default, critically damped, no overshoot)
//       Response is  x(t) = 1 - (1 + t/tau)·e^(-t/tau),  tau = response/4.6,
//       so it is ~95% settled at t = response. Sampled against every curve the
//       engine has, Easing.OutSine over `response` seconds tracks it within
//       ~5% end to end and shares its two defining features: a gentle start
//       and an arrival at zero velocity (OutSine's slope is 0 at t = 1).
//       =>  springEasing(1.0) = Easing.OutSine, duration = response.
//           OutSine vs the spring, as a fraction of the distance:
//             t/response  0.2   0.4   0.6   0.8   1.0
//             spring      .235  .549  .762  .882  .954
//             OutSine     .309  .588  .809  .951  1.00
//
//   damping ~0.8 (momentum releases *only*, per §4)
//       An under-damped spring overshoots once and returns. Easing.OutBack is
//       the only overshooting curve available; it peaks at ~+10% around
//       t = 0.79 and settles at exactly 1, where a 0.8 spring would peak at
//       ~+1.5%. That is bouncier than prescribed, so `springEasing` hands
//       OutBack back only for gesture-driven motion and never for something
//       that merely appeared — and `springDuration`'s clamp keeps the excess
//       confined to a short, single frame of overshoot.
//
//   velocity handoff (§5) — the seam between drag and animation
//       There is no way to pass an initial velocity to a NumberAnimation, so
//       it is baked into the *duration*. Every available curve has a fixed
//       initial slope k, and the animation's leaving speed is k·distance/
//       duration. Setting
//           duration = k · distance / |velocity|
//       makes it leave the release point at exactly the finger's speed.
//       `springDuration` clamps that into [minDuration, maxDuration] so a
//       1px/frame drag cannot stretch to three seconds and a flick cannot
//       finish in one frame.
//
//   momentum projection (§6) — `project` is Apple's shipped function verbatim,
//       the exponential-decay form, not the textbook v²/(2·decel).
//
// ── What this file is NOT ──────────────────────────────────────────────────
// It holds the maths only. `MotionValue.qml` is the animated scalar that uses
// it, `DragTracker.qml` is the velocity history it reads. Nothing here keeps
// state, so the same numbers reach every component.
QtObject {
    id: fluid

    // §4 — the concrete values Apple ships.
    //   Move / reposition  damping 1.0, response 0.4
    //   Rotation           damping 0.8, response 0.4
    //   Drawer / sheet     damping 0.8, response 0.3
    // §4's default rule is damping 1.0 everywhere and bounce only where the
    // gesture itself carried momentum, so that is the pair of defaults here.
    readonly property real dampingDefault: 1.0
    readonly property real dampingMomentum: 0.8
    readonly property real responseDefault: 0.35
    readonly property real responseMove: 0.4
    readonly property real responseSheet: 0.3

    // §6 — decelerationRate 0.998 for normal scroll feel, 0.99 for snappier.
    readonly property real decelerationRate: 0.998

    // §10 — ~10px of hysteresis before a drag commits to a direction.
    readonly property real dragThreshold: 10

    // A touch above the raw response: below this the eye reads the move as a
    // jump rather than as motion, above maxDuration it reads as lag (§1).
    readonly property real minDuration: 90
    readonly property real maxDuration: 760

    // A burst of pointermove events can share one millisecond reading; without
    // a floor the divide is unbounded. 8ms ≈ a 120Hz frame, so a genuine
    // 120Hz pointer stream is measured exactly and a faster burst is capped.
    readonly property real minSampleMs: 8

    // How far back the *shape* estimator (`DragTracker.shapeVelocity()`) may reach, in
    // milliseconds. The release estimator (`DragTracker.velocity()`) keeps its two
    // intervals and is untouched by this: four other components settle and project from
    // that one, and widening its window moved a flick's landing point by 320px.
    //
    // `minSampleMs` on its own is not enough, and believing it was is what made
    // the tab capsule twitch under a held drag. A pointer stream faster than
    // 125Hz — a desktop mouse is routinely 4-8ms between moves — lands *on*
    // that floor: the numerator of `newest - oldest` keeps growing with the
    // real interval while the denominator is pinned at 8ms, so one constant
    // hand speed is reported as numbers that differ by 2-4x from one event to
    // the next. The estimate therefore has to span a *duration*, not a sample
    // count: across a window both terms scale with elapsed time and the event
    // rate drops out of the quotient entirely.
    //
    // 60ms is ~4 frames — long enough that even a 60Hz stream still averages
    // four events, short enough that a change of direction is felt in the shape
    // rather than lagging behind it (the consumer damps what it reads here on
    // top of this window, so this number is not the whole latency).
    readonly property real velocityWindowMs: 60

    // §14 — reduced motion replaces slides/springs with a short cross-fade.
    readonly property real reducedDuration: 160

    // ── the app-level switch, read once, here ───────────────────────────────
    // §14 says to respond to the platform signal and bake it into the
    // components. This project has no `prefers-reduced-motion`, but it already
    // has an accessibility fallback with exactly that meaning:
    // `PAGE_TRANSITION_NONE` (`SettingsCatalog.PAGE_TRANSITION_KEY` == 4), which
    // `components/PageMotion.qml` treats as "drop the travel, keep the
    // cross-fade". Reading it from the one singleton every motion component
    // already imports — rather than threading it through dozens of call sites,
    // most of which live in files that are not ours to edit — is what makes the
    // fallback actually reach the controls instead of existing only on paper.
    //
    // `settings` is a context object the shell injects into every document in
    // this app (`QmlView.context("settings", ...)`). A context that does not
    // provide it — a component gallery, a bare unit-test scene — gets `false`,
    // which is the behaviour every component had before, so nothing regresses.
    // Components still declare their own `reduceMotion` property with this as the
    // default, so a caller can always override either way.
    function readReducedMotion() {
        if (typeof settings === "undefined" || settings === null) return false
        if (typeof settings.value !== "function") return false
        return settings.value("pageTransitionPreset") === 4
    }
    readonly property bool reduced: readReducedMotion()

    // ── curves ────────────────────────────────────────────────────────────
    // §3/§4: which curve stands in for a spring of this damping ratio.
    function springEasing(dampingRatio) {
        return (dampingRatio === undefined || dampingRatio >= 0.9)
            ? Easing.OutSine : Easing.OutBack
    }

    // The initial slope (per unit of normalised time) of the curve
    // `springEasing` picked. Used by `springDuration` to convert a handoff
    // velocity into a duration; the numbers are the analytic derivatives at
    // t = 0 of the curves in the engine's own Easings table.
    function initialSlope(dampingRatio) {
        return (dampingRatio === undefined || dampingRatio >= 0.9) ? 1.5707963 : 4.70158
    }

    // §5 — duration that makes the curve leave at the finger's own velocity.
    // distance is in the animated property's units, velocity in units/second,
    // and both are read as magnitudes. With no usable velocity this collapses
    // to Apple's `response`, i.e. the motion is never made slower or faster
    // than the spring would have been on its own.
    function springDuration(distance, velocity, dampingRatio, response) {
        var r = response === undefined ? fluid.responseDefault : response
        var d = dampingRatio === undefined ? fluid.dampingDefault : dampingRatio
        var fallback = r * 1000
        var v = velocity === undefined ? 0 : Math.abs(velocity)
        var x = Math.abs(distance === undefined ? 0 : distance)
        if (v < 1 || x < 0.5) return fluid.clampDuration(fallback)
        // k · distance / |velocity| seconds -> milliseconds.
        return fluid.clampDuration(1000 * fluid.initialSlope(d) * x / v)
    }

    function clampDuration(ms) {
        return Math.max(fluid.minDuration, Math.min(fluid.maxDuration, ms))
    }

    // §6 — Apple's momentum projection, verbatim from the WWDC sample code.
    // Signed: a negative velocity projects backwards. 1000px/s ≈ 499px.
    function project(velocity, deceleration) {
        var d = deceleration === undefined ? fluid.decelerationRate : deceleration
        return (velocity / 1000) * d / (1 - d)
    }

    // §6 — the resting point the gesture is *going* to, which is what the
    // snap should be chosen from instead of the release point. `stops` is a
    // sorted-or-not array of candidate positions; the nearest wins.
    function nearestStop(projected, stops) {
        if (!stops || stops.length === 0) return projected
        var best = stops[0]
        var bestD = Math.abs(stops[0] - projected)
        for (var i = 1; i < stops.length; i++) {
            var dd = Math.abs(stops[i] - projected)
            if (dd < bestD) { bestD = dd; best = stops[i] }
        }
        return best
    }

    // §9 — rubber-banding. The further past the bound, the less the element
    // follows, so an edge reads as "responsive, but there is nothing more
    // here" instead of frozen. `constant` 0.55 is Apple's.
    function rubberband(overshoot, dimension, constant) {
        var c = constant === undefined ? 0.55 : constant
        var dim = dimension === undefined || dimension <= 0 ? 1 : dimension
        return (overshoot * dim * c) / (dim + c * Math.abs(overshoot))
    }

    // §9 — inverse of the above: given how far the pointer went past the
    // bound, how far the element should have followed. Used by drags whose
    // bounds are known in advance.
    function resistedDelta(delta, limit, dimension) {
        if (limit === undefined || limit <= 0) return delta
        var excess = Math.abs(delta) - limit
        if (excess <= 0) return delta
        return (delta < 0 ? -limit : limit)
            + (delta < 0 ? -1 : 1) * fluid.rubberband(excess, dimension)
    }

    // §14 — reduced motion is "a gentler, non-vestibular equivalent", not no
    // feedback at all: a short opacity cross-fade. Callers keep their fade and
    // drop the travel; this only rescales the clock.
    function duration(ms, reduced) {
        return reduced ? fluid.reducedDuration : ms
    }

    // §14 — and drop elastic/overshoot entirely under reduced motion.
    function easing(easingType, reduced) {
        if (!reduced) return easingType
        return easingType === Easing.OutBack || easingType === Easing.InBack
            ? Easing.OutQuad : easingType
    }

    // §7 — "mirror the easing on reversible transitions so the outbound path
    // matches the return path". An Out* curve's inverse is the matching In*
    // curve with the same exponent, which is the cubic-bézier mirror the skill
    // describes. Linear is its own inverse.
    function mirrorEasing(easingType) {
        switch (easingType) {
            case Easing.OutQuad: return Easing.InQuad
            case Easing.InQuad: return Easing.OutQuad
            case Easing.OutCubic: return Easing.InCubic
            case Easing.InCubic: return Easing.OutCubic
            case Easing.OutQuart: return Easing.InQuart
            case Easing.InQuart: return Easing.OutQuart
            case Easing.OutSine: return Easing.InSine
            case Easing.InSine: return Easing.OutSine
            case Easing.OutBack: return Easing.InBack
            case Easing.InBack: return Easing.OutBack
            default: return easingType
        }
    }

    // §5/§6 — the whole release decision in one place, so every drag in the
    // app settles by the same law: where the gesture was *going* picks the
    // stop, the gesture's velocity picks the duration, and its sign — not the
    // release position — decides whether the sheet reverses at all.
    function settleDuration(projectedTarget, currentValue, velocity, dampingRatio, response) {
        return fluid.springDuration(projectedTarget - currentValue, velocity,
                                    dampingRatio, response)
    }
}
