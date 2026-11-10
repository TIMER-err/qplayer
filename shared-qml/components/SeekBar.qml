import QtQuick
import design

// Playback progress with click and drag scrubbing. While the pointer is down the
// bar previews the pointer position; the seek is committed once, on release, so
// the transport is not re-entered on every move. A loading sweep can replace the
// fill while a track resolves.
Item {
    id: bar
    property real value: 0          // 0..1 playback position
    property bool sweeping: false
    property real sweepPhase: 0     // 0..1, host-driven
    property bool interactive: true
    property real trackHeight: 4
    // Ratio chosen by the last completed gesture; read in onSeekRequested.
    property real pendingRatio: 0
    readonly property bool scrubbing: area.pressed && interactive
    readonly property real shownValue: scrubbing ? _preview : value
    property real _preview: 0
    signal seekRequested()

    implicitHeight: 16

    function _ratioAt(px) { return Math.max(0, Math.min(1, px / Math.max(1, bar.width))) }

    Rectangle {
        id: track
        x: 0
        y: (bar.height - height) / 2
        width: bar.width
        height: (area.containsMouse || bar.scrubbing) && bar.interactive
                ? bar.trackHeight + 2 : bar.trackHeight
        radius: height / 2
        color: Ui.dark ? "#26FFFFFF" : "#17000000"
        clip: true

        Rectangle {
            x: 0
            y: 0
            height: parent.height
            width: parent.width * bar.shownValue
            radius: parent.radius
            visible: !bar.sweeping
            color: Ui.accent
        }
        Rectangle {
            visible: bar.sweeping
            width: track.width * 0.3
            height: parent.height
            radius: parent.radius
            x: -width + (track.width + width) * (1 - Math.cos(bar.sweepPhase * Math.PI)) / 2
            color: Ui.accent
        }
    }

    Rectangle {
        objectName: "seekBarKnob"
        visible: bar.interactive && !bar.sweeping && (area.containsMouse || bar.scrubbing)
        width: 12
        height: 12
        radius: 6
        x: Math.max(0, Math.min(bar.width - width, bar.width * bar.shownValue - width / 2))
        y: (bar.height - height) / 2
        color: Ui.accent
    }

    MouseArea {
        id: area
        anchors.fill: parent
        enabled: bar.interactive
        hoverEnabled: enabled
        preventStealing: true
        cursorShape: Qt.PointingHandCursor
        onPressed: bar._preview = bar._ratioAt(mouseX)
        onPositionChanged: if (pressed) bar._preview = bar._ratioAt(mouseX)
        onReleased: {
            bar.pendingRatio = bar._ratioAt(mouseX)
            bar.seekRequested()
        }
    }
}
