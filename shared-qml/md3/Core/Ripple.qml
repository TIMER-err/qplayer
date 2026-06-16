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
    // Live wave count. The masked MultiEffect composite + mask layer render every
    // frame the effect exists; idle there's nothing to show, so gate both on this
    // — otherwise many idle Ripples on a screen dominate paint time.
    property int liveWaves: 0

    // Mask for clipping (defines the shape)
    Item {
        id: mask
        anchors.fill: parent
        layer.enabled: root.liveWaves > 0
        visible: false

        Rectangle {
            id: maskRect
            anchors.fill: parent
            radius: root.clipRadius
            color: "black"
        }
    }

    // Container that holds the live ripple waves (masked to the shape above).
    Item {
        id: rippleContent
        anchors.fill: parent
        visible: false
    }

    MultiEffect {
        source: rippleContent
        anchors.fill: parent
        visible: root.liveWaves > 0
        maskEnabled: true
        maskSource: mask
    }

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

    onPressed: (mouse) => {
        root.activeWave = waveComponent.createObject(rippleContent, { startX: mouse.x, startY: mouse.y })
        root.liveWaves = root.liveWaves + 1
    }

    onReleased: { if (root.activeWave) { root.activeWave.held = false; root.activeWave = null } }
    onCanceled: { if (root.activeWave) { root.activeWave.held = false; root.activeWave = null } }
}
