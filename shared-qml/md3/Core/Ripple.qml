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

    // The wave currently held under the finger (fades once released).
    property var activeWave: null
    // Live wave count. The rounded-mask layer renders every frame it exists; idle
    // there's nothing to show, so gate it on this — otherwise many idle Ripples on
    // a screen dominate paint time.
    property int liveWaves: 0

    // Clip shape: the engine's layer.effect rounded-mask reads this Rectangle's
    // per-corner radii to round the ripple layer. Never painted directly.
    Rectangle {
        id: maskRect
        // Reactive geometry, NOT anchors.fill: anchors resolve only in the measure
        // pass, which a cachedLayout ancestor (long song lists) skips when its own
        // box is unchanged — leaving a stale (often negative, w-16) size after the
        // row's width binding settles. A width binding tracks root.width directly.
        x: 0
        y: 0
        width: root.width
        height: root.height
        radius: root.clipRadius
        color: "black"
        visible: false
    }

    // Live ripple waves, rounded-clipped via the engine's layer.effect mask path:
    // layer.enabled + layer.effect:MultiEffect{maskEnabled} makes the renderer clip
    // these CHILDREN to maskRect's rounded shape in their own (correct) transform.
    // The earlier form put the MultiEffect as a sibling with `source: rippleContent`;
    // the renderer draws that source at the wrong offset when the ripple has a
    // non-zero screen position (e.g. a row inside a scrolled/cached list), so the
    // masked output drifted toward the screen origin. clip:true keeps a plain
    // rectangular clip for the radius==0 case (where the rounded mask is a no-op).
    Item {
        id: rippleContent
        // Reactive geometry (see maskRect) — and gate the rounded-mask layer on a
        // positive size so a transient zero/negative width during construction can't
        // reach eraseOutsideRoundRect (Skija's Rect throws on negative extents).
        x: 0
        y: 0
        width: root.width
        height: root.height
        clip: true
        visible: root.liveWaves > 0 && width > 0 && height > 0
        layer.enabled: root.liveWaves > 0 && width > 0 && height > 0
        layer.effect: MultiEffect { maskEnabled: true; maskSource: maskRect }

        // qml4j divergence from upstream md3 Ripple.qml: upstream reuses one ripple
        // rectangle, so a new tap restarts the single wave. Material allows
        // overlapping ripples. Each press spawns an independent wave that expands and
        // holds at full opacity while pressed; on release it fades out and destroys
        // itself, so concurrent presses coexist without disturbing each other.
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

                // Expand + fade in to full opacity, then hold while pressed.
                NumberAnimation {
                    target: wave; property: "size"
                    from: 0; to: wave.targetSize
                    duration: 450; easing.type: Easing.OutQuart
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
    }

    onPressed: (mouse) => {
        root.activeWave = waveComponent.createObject(rippleContent, { startX: mouse.x, startY: mouse.y })
        root.liveWaves = root.liveWaves + 1
    }

    onReleased: { if (root.activeWave) { root.activeWave.held = false; root.activeWave = null } }
    onCanceled: { if (root.activeWave) { root.activeWave.held = false; root.activeWave = null } }
}
