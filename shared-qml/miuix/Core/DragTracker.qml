import QtQuick
import miuix.Core

// §2 — "Track a short velocity/position history (last few pointermove events),
// not just the current point — you'll need velocity at release."
//
// One instance tracks ONE axis, because §3 is explicit: "Decompose 2D motion
// into independent X and Y springs. A single spring on a 2D distance desyncs
// when X and Y have different velocities." A 2D drag owns two of these and
// hands two candidates to the settle decision, rather than one averaged speed
// that is right for neither axis.
//
// The only clock the engine exposes to QML is `Date` (`DatePicker.qml` already
// relies on it), so samples carry wall-clock milliseconds. A property change
// handler and a burst of pointermove events can land inside the same
// millisecond, which would make the divide unbounded, so the window is floored
// at `FluidMotion.minSampleMs` (8ms ≈ a 120Hz frame).
//
// The estimate is taken across a **duration**, not across a fixed number of
// events: newest sample minus the oldest sample still inside
// `FluidMotion.velocityWindowMs`, both terms read from the same two samples, so
// the event rate cancels out of the quotient. This is the shape Android's own
// `VelocityTracker` has, and the reason for it is measurable: the previous
// version kept exactly three samples and divided by `max(t2 - t0, 8)`. At a
// desktop mouse's usual 4-8ms between moves that span *is* the floor, so the
// denominator stopped responding to time while the numerator kept scaling with
// it — the same steady hand speed came back as 2-4x different numbers from one
// event to the next, and the capsule's squash (which is a function of this
// number) visibly twitched for as long as the drag lasted. Holding more samples
// is what makes the window reachable at all; keeping only the newest few is
// correct for a *release* decision and wrong for a *continuous* shape driver.
//
// At a genuine 120Hz pointer stream the window is still measured, not assumed:
// eight slots are the hard cap, so a stream that slow simply reaches its
// anchor sooner and lands inside the 60ms window rather than at its edge.
Item {
    id: tracker

    visible: false
    width: 0
    height: 0

    // Samples kept, newest first (`_t0`/`_v0` is always the newest regardless of
    // how many are filled). Eight is the cheap end of "enough": it covers the
    // 60ms window down to a ~120Hz stream, and the shift is eight scalar
    // assignments — no arrays, no allocation per move event.
    readonly property int sampleCount: 8
    property real _t0: 0
    property real _v0: 0
    property real _t1: 0
    property real _v1: 0
    property real _t2: 0
    property real _v2: 0
    property real _t3: 0
    property real _v3: 0
    property real _t4: 0
    property real _v4: 0
    property real _t5: 0
    property real _v5: 0
    property real _t6: 0
    property real _v6: 0
    property real _t7: 0
    property real _v7: 0
    property int _count: 0

    // Total signed travel since `begin()`, in value units. §10's ~10px
    // hysteresis and §6's projection both read it.
    property real travel: 0
    // Where the gesture started, needed to respect the grab offset (§2).
    property real origin: 0
    property real last: 0

    // Slot readers by age (0 = newest). Written as a chain rather than as an
    // array because the ring is scalars on purpose: a JS array here would
    // allocate on every move event, which is the one thing this tracker is
    // shaped to avoid.
    function _slotT(i) {
        if (i === 0) return tracker._t0
        if (i === 1) return tracker._t1
        if (i === 2) return tracker._t2
        if (i === 3) return tracker._t3
        if (i === 4) return tracker._t4
        if (i === 5) return tracker._t5
        if (i === 6) return tracker._t6
        return tracker._t7
    }
    function _slotV(i) {
        if (i === 0) return tracker._v0
        if (i === 1) return tracker._v1
        if (i === 2) return tracker._v2
        if (i === 3) return tracker._v3
        if (i === 4) return tracker._v4
        if (i === 5) return tracker._v5
        if (i === 6) return tracker._v6
        return tracker._v7
    }

    // Signed velocity in value units per second, read at release.
    //
    // Deliberately left alone when the shape estimator below was added. Four other
    // components (NavigationDrawer, PullToRefresh, SideSheet, Slider) settle, project and
    // decide "flick or not" from this number, and widening its window silently retunes all
    // of them — `FluidInteractionMotionTest.aFlickTravelsFurtherThanASlowDragOfTheSameDistance`
    // caught exactly that (a flick projected 900px where the contract says 580px). A
    // release is one decision at one instant and the last two intervals are the honest
    // answer for it; a continuous shape is a different question, so it gets a different
    // function rather than a redefined one.
    function velocity() {
        if (tracker._count < 2) return 0
        var dt = tracker._t0 - tracker._t2
        if (dt < FluidMotion.minSampleMs) dt = FluidMotion.minSampleMs
        return ((tracker._v0 - tracker._v2) / dt) * 1000
    }

    // The estimate a *continuous* shape may be driven by, in value units per second.
    //
    // Same samples, different question: this one is read on every event of a drag, so what
    // it has to get right is a steady reading while the hand moves at a steady speed, not
    // one reading at one instant. It spans `FluidMotion.velocityWindowMs` — a duration
    // rather than a fixed count — so both terms of the quotient scale with elapsed time and
    // the event rate cancels out of it.
    //
    // `velocity()` cannot be used for this, and the difference is measurable. Its span is
    // two intervals; a desktop mouse delivers moves 4-8ms apart, which is at or under the
    // 8ms floor, so the denominator stops responding to time while the numerator keeps
    // scaling with it and one steady hand speed is reported as numbers 2-4x apart. Worse,
    // nothing is delivered while the finger is held still — and a careful drag pauses at
    // every direction change — so its span ends up covering the pause itself and the
    // quotient decays toward zero even though the pointer resumes at the speed it left off
    // at. Measured in the shell (one point per 16ms frame, held 400ms mid-drag): squash
    // 0.0401 -> 0.0060 and the drawn capsule 1.4358 -> 1.4034 in a single frame, then ~80ms
    // of ramp back. A hand does that continuously, which is what was reported as
    // "长按拖动胶囊大小一直抽搐" and why the shape was never the problem to begin with.
    function shapeVelocity() {
        if (tracker._count < 2) return 0
        // The anchor is the OLDEST sample still inside the window: walking in from the
        // oldest end gives the longest average the window allows, which is what suppresses
        // a single jittery event. Samples are in age order, so the walk stops as soon as
        // the window is entered.
        var i = tracker._count - 1
        while (i > 0 && (tracker._t0 - tracker._slotT(i)) > FluidMotion.velocityWindowMs) {
            i = i - 1
        }
        if (i === 0) return 0
        var dt = tracker._t0 - tracker._slotT(i)
        if (dt < FluidMotion.minSampleMs) dt = FluidMotion.minSampleMs
        return ((tracker._v0 - tracker._slotV(i)) / dt) * 1000
    }

    // Is `shapeVelocity()` describing *now*?
    //
    // A frame with no fresh evidence must not move the shape: across a pause the newest
    // interval is the pause itself, so the quotient it produces is a decay toward zero, not
    // a speed. Nothing *was* delivered during the pause (a pointer that is not moving sends
    // nothing), so "no estimate" is the honest answer, and a shape driver should hold what
    // it has. The next event is two samples deep again and reports the real speed.
    function shapeHasVelocity() {
        if (tracker._count < 2) return false
        return (tracker._t0 - tracker._t1) <= FluidMotion.velocityWindowMs
    }

    // §10 — a drag only commits to a direction once it has moved far enough
    // that the intent is unambiguous; below that it is still a tap.
    function committed(threshold) {
        var t = threshold === undefined ? FluidMotion.dragThreshold : threshold
        return Math.abs(tracker.travel) >= t
    }

    function begin(v) {
        var now = new Date().getTime()
        tracker._t0 = now
        tracker._v0 = v
        tracker._t1 = now
        tracker._v1 = v
        tracker._t2 = now
        tracker._v2 = v
        tracker._t3 = now
        tracker._v3 = v
        tracker._t4 = now
        tracker._v4 = v
        tracker._t5 = now
        tracker._v5 = v
        tracker._t6 = now
        tracker._v6 = v
        tracker._t7 = now
        tracker._v7 = v
        tracker._count = 1
        tracker.travel = 0
        tracker.origin = v
        tracker.last = v
    }

    function track(v) {
        if (tracker._count === 0) {
            tracker.begin(v)
            return
        }
        var now = new Date().getTime()
        tracker._t7 = tracker._t6
        tracker._v7 = tracker._v6
        tracker._t6 = tracker._t5
        tracker._v6 = tracker._v5
        tracker._t5 = tracker._t4
        tracker._v5 = tracker._v4
        tracker._t4 = tracker._t3
        tracker._v4 = tracker._v3
        tracker._t3 = tracker._t2
        tracker._v3 = tracker._v2
        tracker._t2 = tracker._t1
        tracker._v2 = tracker._v1
        tracker._t1 = tracker._t0
        tracker._v1 = tracker._v0
        tracker._t0 = now
        tracker._v0 = v
        if (tracker._count < tracker.sampleCount) tracker._count = tracker._count + 1
        tracker.travel = v - tracker.origin
        tracker.last = v
    }

    function reset() {
        tracker._count = 0
        tracker.travel = 0
        tracker.last = 0
        tracker.origin = 0
    }
}
