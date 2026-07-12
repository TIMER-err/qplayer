import QtQuick
import QtQuick.Effects
import md3.Core
MouseArea {
    id: root

    property color rippleColor: Theme.color.onSurfaceColor
    property real rippleOpacity: 0.12
    property real clipRadius: 0
    property alias clipTopLeftRadius: maskRect.topLeftRadius
    property alias clipTopRightRadius: maskRect.topRightRadius
    property alias clipBottomLeftRadius: maskRect.bottomLeftRadius
    property alias clipBottomRightRadius: maskRect.bottomRightRadius

    hoverEnabled: true

    // Opt-in long-press. When enabled, a stationary hold for `longPressMs` emits
    // longPressed(); dragging (a scroll) past a small slop or releasing cancels it,
    // so it never fires while flicking a list. Off by default → zero cost for the
    // many Ripples on buttons/cards that don't want it. pressX/pressY hold the last
    // press point (menus open there); the signal is param-less so it survives the
    // cross-file handler path.
    property bool longPressEnabled: false
    property int longPressMs: 480
    property real pressX: 0
    property real pressY: 0
    signal longPressed()

    Timer {
        id: holdTimer
        interval: root.longPressMs
        repeat: false
        onTriggered: root.longPressed()
    }

    // The wave currently held under the finger (fades once released).
    property var activeWave: null
    // Live wave count. The masked MultiEffect renders every frame it exists; idle
    // there's nothing to show, so gate it on this — otherwise many idle Ripples on a
    // screen dominate paint time.
    property int liveWaves: 0

    // Reactive geometry on the internals (width binding, NOT anchors.fill): anchors
    // resolve only in the measure pass, which a cachedLayout ancestor (long song
    // lists) skips once its own box is stable — leaving these stuck at a row's stale
    // first width. A binding tracks root.width without needing a re-measure.

    // Rounded-rect shape the MultiEffect clips the waves to (its per-corner radii).
    Rectangle {
        id: maskRect
        x: 0
        y: 0
        width: root.width
        height: root.height
        radius: root.clipRadius
        color: "black"
        visible: false
    }

    // Invisible wave host — drawn (masked) only through the MultiEffect below.
    Item {
        id: rippleContent
        x: 0
        y: 0
        width: root.width
        height: root.height
        visible: false
    }

    MultiEffect {
        x: 0
        y: 0
        width: root.width
        height: root.height
        source: rippleContent
        visible: root.liveWaves > 0
        maskEnabled: true
        maskSource: maskRect
    }

    // qml4j divergence from upstream md3 Ripple.qml: upstream reuses one ripple
    // rectangle, so a new tap restarts the single wave. Material allows overlapping
    // ripples. Each press spawns an independent wave that expands and holds at full
    // opacity while pressed; on release it fades out and destroys itself, so
    // concurrent presses coexist without disturbing each other.
    Component {
        id: waveComponent
        Rectangle {
            id: wave
            property real startX: 0
            property real startY: 0
            property real targetSize: Math.max(root.width, root.height) * 2.5
            property real size: 0
            // Set false by the Ripple on release -> triggers fade-out + self-destruct.
            property bool held: true

            width: size
            height: size
            radius: size / 2
            x: startX - size / 2
            y: startY - size / 2
            color: root.rippleColor
            opacity: 0

            // Expand + fade in to full opacity, then hold while pressed. Linear: a
            // constant-speed expand is the look this ripple shipped with for months —
            // it was written as OutQuart but qml4j didn't implement Quart until 0.2.16,
            // so it silently ran linear. Any ease-out (Quart, even Cubic) shoots the
            // wave out too fast; keep it linear to match the familiar feel.
            NumberAnimation {
                target: wave; property: "size"
                from: 0; to: wave.targetSize
                duration: 450; easing.type: Easing.Linear
                running: true
            }
            NumberAnimation {
                target: wave; property: "opacity"
                from: 0; to: root.rippleOpacity
                duration: 90
                running: true
            }

            // On release (held -> false): ensure full, then fade out.
            SequentialAnimation {
                running: !wave.held
                NumberAnimation { target: wave; property: "opacity"; to: root.rippleOpacity; duration: 60 }
                NumberAnimation { target: wave; property: "opacity"; to: 0; duration: 300; easing.type: Easing.InQuad }
            }
            Timer {
                running: !wave.held; interval: 380; repeat: false
                onTriggered: { root.liveWaves = Math.max(0, root.liveWaves - 1); wave.destroy() }
            }
        }
    }

    onPressed: (mouse) => {
        root.activeWave = waveComponent.createObject(rippleContent, { startX: mouse.x, startY: mouse.y })
        root.liveWaves = root.liveWaves + 1
        if (root.longPressEnabled) { root.pressX = mouse.x; root.pressY = mouse.y; holdTimer.restart() }
    }

    // Cancel the pending long-press once the finger travels past a small slop — this
    // is what a scroll drag looks like. Guarded on holdTimer.running so hover moves
    // (hoverEnabled is on) don't pay for the math.
    onPositionChanged: (mouse) => {
        if (holdTimer.running) {
            var dx = mouse.x - root.pressX, dy = mouse.y - root.pressY
            if (dx * dx + dy * dy > 100) holdTimer.stop()
        }
    }

    onReleased: { holdTimer.stop(); if (root.activeWave) { root.activeWave.held = false; root.activeWave = null } }
    onCanceled: { holdTimer.stop(); if (root.activeWave) { root.activeWave.held = false; root.activeWave = null } }
}
