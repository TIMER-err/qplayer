import QtQuick
import miuix.Core

// ── B1/B2's driver: the pointer state a glass surface needs ──────────────────
//
// Ported from Kyant0's AndroidLiquidGlass (Apache-2.0),
// `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt`.
// That class is the whole of their touch-following specular highlight, and this
// file is its QML side:
//
//   · on pointer-down it records `startPosition = down.position` and snaps the
//     highlight there (their `onDragStart`);
//   · every move snaps the highlight to the finger (their `onDrag` ->
//     `positionAnimation.snapTo(change.position)`);
//   · on release it *fades* the highlight to nothing while the position
//     springs back to `startPosition` (their `onDragEnd`); and
//   · `offset`, the warp term a consumer can use, is
//     `positionAnimation.value - startPosition`.
//
// What it deliberately does NOT do is sample anything: the reference's own
// fallback for a device without runtime shaders is a flat
// `Color.White.copy(alpha = 0.25f * progress)` over the surface, and its shader
// path adds a smoothstep disc of `radius = size.minDimension * 1.5f`. Both are
// handled by `Glass.qml`, which draws a two-disc approximation of that
// smoothstep (a radial gradient is not available to a QML `Rectangle` here, and
// a `Canvas` per pointer move would be the per-frame pass the brief forbids).
//
// ── spring approximation, stated honestly ──────────────────────────────────
// qml4j has no spring. Their `pressProgressAnimationSpec` is
// `spring(1f, 1000f, 0.001f)` — DampedDragAnimation.kt:35-36 — i.e. damping
// ratio 1.0 (critically damped, no overshoot) and stiffness 1000, whose natural
// frequency is sqrt(1000) = 31.6 rad/s and therefore whose response is
// 2*pi/31.6 ≈ 0.2s. Easing.OutSine over 0.2s is the closest curve the engine
// has: it starts gently, arrives at exactly 1, and its slope at t=1 is 0, which
// is the defining property of a critically damped arrival. `MotionValue`
// carries it, and because a PropertyAnimation here never binds `from`, the
// progress is always re-aimed from the *presentation* value, so a press that is
// reversed mid-fade turns around instead of restarting (§3).
//
// Their position spring is the under-damped `spring(0.5f, 300f, ...)`
// (InteractiveHighlight.kt:29-30) — damping 0.5, response ≈ 0.36s — which is
// what makes the highlight *settle* rather than stop dead. Easing.OutBack is the
// only overshooting curve the engine has, so that is what 0.5 maps to; it peaks
// at ~+10% where their spring peaks at ~+16%, i.e. it is the conservative
// approximation, and the excess is confined to one short frame by
// `MotionValue.maxDuration`.
//
// ── the limitation ────────────────────────────────────────────────────────
// In qml4j only the topmost item at a point receives a press, so a probe
// declared under a row of buttons sees nothing where they overlap. That is why
// `press()`/`move()`/`release()` are public: a control that owns a click area in
// front of the glass feeds the same probe from its own MouseArea, so the glass
// reacts to a press on a button too. There is no `propagateComposedEvents`
// escape hatch here (it does not work in this engine).
Item {
    id: probe

    // Whether a finger is down on the surface. Plain state; the *spring* is
    // `progress`, so a consumer can always read either the boolean or the
    // continuous value.
    property bool down: false

    // Where the highlight sits, in the surface's own coordinates.
    property real touchX: 0
    property real touchY: 0

    // Where the finger first landed: the point the highlight returns to when it
    // is let go (their `startPosition`).
    property real homeX: 0
    property real homeY: 0

    // §14 — reduced motion. The highlight still appears (a press must always
    // give feedback), but it does not travel: the position snaps home instead of
    // springing, and the progress ramp keeps its cross-fade without the
    // overshoot curve.
    property bool reduced: FluidMotion.reduced

    // The reference's `pressProgress`, 0..1, as it is on screen right now.
    readonly property real progress: layer.value

    // Where the highlight *is on screen*, as opposed to where the finger is:
    // their `positionAnimation.value`. A drag snaps it to the pointer
    // (`snapTo(change.position)`), so the two agree the whole time the finger is
    // down — and they part company on release, when the pointer no longer exists
    // and the highlight springs back to `homeX`/`homeY`. This is the pair a glass
    // surface should draw from, and the pair the host is told about.
    readonly property real highlightX: posX.value
    readonly property real highlightY: posY.value

    // The reference's `offset`: how far the highlight has been dragged from
    // where the finger landed. Their `LiquidButton.kt:72-77` turns this into a
    // `tanh` warp of the whole surface; kept here because it is the term a
    // caller needs for that, and it costs nothing.
    readonly property real offsetX: posX.value - probe.homeX
    readonly property real offsetY: posY.value - probe.homeY

    // Their `inspectDragGestures` callbacks, as functions.
    function press(x, y) {
        probe.down = true
        probe.homeX = x
        probe.homeY = y
        probe.touchX = x
        probe.touchY = y
        posX.track(x)
        posY.track(y)
    }

    function move(x, y) {
        if (!probe.down) return
        probe.touchX = x
        probe.touchY = y
        posX.track(x)
        posY.track(y)
    }

    function release() {
        if (!probe.down) return
        probe.down = false
        if (probe.reduced) {
            posX.snap(probe.homeX)
            posY.snap(probe.homeY)
        } else {
            posX.target = probe.homeX
            posY.target = probe.homeY
        }
    }

    // Their press/release progress. Damping 1.0 / response 0.2 = their
    // spring(1f, 1000f) — see the header.
    MotionValue {
        id: layer
        objectName: "glassPressProgress"
        target: probe.down ? 1 : 0
        damping: FluidMotion.dampingDefault
        response: 0.2
        reduced: probe.reduced
        minDuration: 40
        maxDuration: 320
    }

    // Their position spring: damping 0.5, stiffness 300 -> response ≈ 0.36s.
    MotionValue {
        id: posX
        objectName: "glassPressX"
        damping: FluidMotion.dampingMomentum
        response: 0.36
        reduced: probe.reduced
    }
    MotionValue {
        id: posY
        objectName: "glassPressY"
        damping: FluidMotion.dampingMomentum
        response: 0.36
        reduced: probe.reduced
    }

    // Dead space on the surface. Anything declared after this in the same item
    // sits above it and keeps its own clicks; a control in front of the glass
    // forwards its press here instead (see the header).
    MouseArea {
        anchors.fill: parent
        onPressed: (mouse) => probe.press(mouse.x, mouse.y)
        onPositionChanged: (mouse) => probe.move(mouse.x, mouse.y)
        onReleased: probe.release()
        onCanceled: probe.release()
    }
}
