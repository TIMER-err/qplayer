import QtQuick
import miuix.Core

// One animated scalar with spring behaviour, standing in for the spring type
// this engine does not have. See `FluidMotion.qml` for the reasoning behind the
// curve/duration mapping; this file is the animated value that applies it.
//
// The contract that makes everything else here interruptible:
//
//   · `value` is the *presentation* value — the number currently on screen.
//     Consumers bind to `value` and never write it.
//   · `target` is where it should be. Writing `target` mid-flight re-aims the
//     running animation instead of queueing a second one, and because the
//     NumberAnimation below never binds `from`, the new animation starts from
//     whatever `value` happens to be at that instant. So a half-open sheet can
//     be grabbed and pushed back without a jump (§3).
//   · `flick(velocity)` is §5's handoff: it sets the release velocity *and*
//     re-aims, so the motion leaves the release point at the finger's speed.
//     Retargeting without a flick keeps Apple's plain `response` timing, which
//     is what makes a menu that merely appeared feel wrong when it bounces.
//
// Deliberately NOT used for anything the pointer drives frame by frame: a drag
// writes `value` directly (1:1, §2) and only calls `flick` on release. An
// animation that owns a property while the finger is on it cannot track 1:1.
//
// One caveat worth knowing before choosing a `target`: qml4j evaluates a bound
// `target` *after* `Component.onCompleted`, so a value whose rest target is not 0
// starts at 0 and reaches its rest value on the first animation tick rather than
// at construction. So express a press as "how pressed is it" (rest 0, 1 at full
// press) and interpolate at the use site — `scale: 1 - 0.14 * dip.value` — rather
// than as an absolute value that happens to rest at 1.0.
Item {
    id: root

    // Non-visual: zero cost in layout, and `visible: false` keeps the engine
    // from recursing into this subtree during measure.
    visible: false
    width: 0
    height: 0

    // The live on-screen value. Bind to this, never write it.
    property real value: 0
    // Where it should be. Assign freely, any time, including mid-flight.
    property real target: 0

    // Apple's two spring parameters (§4). `response` is seconds to arrive, not
    // a duration; `damping` 1.0 is critically damped, < 1 overshoots once.
    property real response: FluidMotion.responseDefault
    property real damping: FluidMotion.dampingDefault

    // Release velocity in value-units per second, consumed by the next retarget.
    property real velocity: 0

    property real minDuration: FluidMotion.minDuration
    property real maxDuration: FluidMotion.maxDuration

    // §14 — under reduced motion the caller keeps its cross-fade and drops its
    // travel; `reduced` additionally forbids the overshoot curve.
    // Defaults to the app-level switch (`FluidMotion.reduced`, which reads the
    // existing `pageTransitionPreset == PAGE_TRANSITION_NONE` accessibility
    // preset) so §14's fallback reaches every animated value in the tree without
    // a binding at each call site. A caller can still force it either way.
    property bool reduced: FluidMotion.reduced

    // A change that is not a user-visible motion (a page merely being restacked,
    // a value restored rather than moved) lands instead of playing. Same flag the
    // construction guard uses, exposed so callers do not have to reach into the
    // animation — and must not, because re-declaring `onTargetChanged` on an
    // instance would replace the handler that does the retargeting.
    property bool instant: false

    // How close counts as arrived, in value units.
    property real settleEpsilon: 0.005
    readonly property bool settled: Math.abs(root.value - root.target) <= root.settleEpsilon

    // Resolved by _retarget(); read by the animation every tick.
    property real _duration: 200
    property int _easing: Easing.OutSine
    // Construction guard: the very first assignment of a bound `target` is the
    // initial value, not a user-visible change, so it must snap rather than
    // play. (qml4j's Behavior does the same thing with its own `enabled` flag.)
    property bool _primed: false

    // Re-aim at `target` from wherever `value` currently is. Velocity is a
    // one-shot handoff: it shapes this launch only.
    //
    // The animation's endpoint/duration/curve are written here on every retarget
    // rather than left as `to: root.target` / `duration: root._duration`
    // bindings. A binding on an animation child looks tidier but does not hold
    // up in this engine: the value the animation reads in prepare() can still be
    // the previous one, which made a re-aim animate to the value it was already
    // at — motion that reported `finished` without ever moving. Writing them is
    // also what makes the retarget atomic: endpoint, clock and curve all change
    // in the same frame as the restart, so an interrupted motion can never pick
    // up the old curve when it reverses (§3's "don't hard-cut velocity").
    function retarget() {
        if (!root._primed || root.instant) {
            anim.stop()
            root.velocity = 0
            root.value = root.target
            return
        }
        var distance = root.target - root.value
        var v = root.velocity
        root.velocity = 0
        var ms = FluidMotion.springDuration(distance, v, root.damping, root.response)
        // §14 — reduced motion replaces the spring with a short cross-fade, so
        // the clock is rescaled before the caller's own bounds clamp it. Without
        // this `reduced` only swapped the curve and the motion still took the
        // full `response` to arrive, which is the one thing §14 asks it not to do.
        ms = FluidMotion.duration(ms, root.reduced)
        if (ms < root.minDuration) ms = root.minDuration
        if (ms > root.maxDuration) ms = root.maxDuration
        root._duration = ms
        root._easing = FluidMotion.easing(FluidMotion.springEasing(root.damping), root.reduced)
        // No `from`: the engine reads `value` on the animation's first tick, so
        // this always launches from the live value (§3).
        anim.stop()
        anim.to = root.target
        anim.duration = ms
        anim.easing.type = root._easing
        anim.start()
    }

    // §5 — hand off a gesture's release velocity and re-aim in one step.
    function flick(releaseVelocity) {
        root.velocity = releaseVelocity
        root.retarget()
    }

    // §5+§6 — the release of a gesture in one call: pick the target the gesture
    // was *going* to, then hand off its velocity so there is no seam between
    // the finger and the animation. `releaseVelocity` is in value-units/second.
    function springTo(newTarget, releaseVelocity) {
        root.velocity = releaseVelocity
        if (root.target !== newTarget) root.target = newTarget
        else root.retarget()
    }

    // Emitted when the value actually arrives, so callers stop needing a
    // fixed-duration Timer to guess when the motion is over. `finished` is the
    // only signal qml4j animations have, so this is the honest place to hang it.
    signal arrived()

    // §2 — a drag writes the presentation value directly, 1:1 with the pointer.
    // It must also stop any running animation, or the two fight for the
    // property and the element does not stay glued to the finger.
    function track(v) {
        anim.stop()
        root.velocity = 0
        root.value = v
    }

    // Deliberate non-animated move, for construction and for reduced motion.
    function snap(v) {
        anim.stop()
        root.velocity = 0
        root.value = v
    }

    onTargetChanged: root.retarget()
    // A live `response` binding (press-in vs press-out) has to re-time an
    // in-flight move, otherwise the asymmetry only ever applies to the next one.
    onResponseChanged: root.retarget()
    onDampingChanged: root.retarget()

    // Order matters: reading `target` first forces its binding to evaluate, so
    // the construction-time change is still swallowed by the `_primed` guard.
    // Priming before the read would let that first evaluation play.
    Component.onCompleted: {
        root.value = root.target
        root._primed = true
    }

    // Endpoint, duration and curve are all assigned by retarget(); nothing here
    // is bound, deliberately (see the note there).
    NumberAnimation {
        id: anim
        target: root
        property: "value"
        onFinished: root.arrived()
    }
}
