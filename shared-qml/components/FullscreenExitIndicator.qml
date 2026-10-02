import QtQuick
import miuix.Core

// Top-level desktop affordance for the deliberate hold-to-exit gesture.
// Visibility is retained through the exit animation instead of being bound
// directly to `active`, so a released key fades the card out cleanly.
Item {
    id: control

    property bool fullscreen: false
    property bool holding: false
    property real progress: 0
    property bool _entryHint: false

    implicitWidth: 260
    implicitHeight: 128
    visible: false
    opacity: 0
    scale: 0.9
    transformOrigin: Item.Center

    function reveal() {
        hideAnim.stop()
        if (!visible) {
            opacity = 0
            scale = 0.9
            visible = true
        }
        showAnim.restart()
    }

    function conceal() {
        if (!visible) return
        showAnim.stop()
        hideAnim.restart()
    }

    onFullscreenChanged: {
        if (fullscreen) {
            _entryHint = true
            entryTimer.restart()
            reveal()
        } else {
            _entryHint = false
            entryTimer.stop()
            if (!holding) conceal()
        }
    }
    onHoldingChanged: {
        if (holding) {
            _entryHint = false
            entryTimer.stop()
            reveal()
        } else if (!_entryHint) {
            conceal()
        }
    }
    Component.onCompleted: {
        if (fullscreen) {
            _entryHint = true
            entryTimer.restart()
            reveal()
        }
    }

    Timer {
        id: entryTimer
        interval: 2400
        repeat: false
        onTriggered: {
            control._entryHint = false
            if (!control.holding) control.conceal()
        }
    }

    ParallelAnimation {
        id: showAnim
        NumberAnimation {
            target: control
            property: "opacity"
            to: 1
            duration: 160
            easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: control
            property: "scale"
            to: 1
            duration: 220
            easing.type: Easing.OutBack
        }
    }

    ParallelAnimation {
        id: hideAnim
        onFinished: {
            if (!control.holding && !control._entryHint) control.visible = false
            else control.reveal()
        }
        NumberAnimation {
            target: control
            property: "opacity"
            to: 0
            duration: 130
            easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: control
            property: "scale"
            to: 0.94
            duration: 130
            easing.type: Easing.InCubic
        }
    }

    Surface {
        anchors.fill: parent
        radius: 28
        containerColor: Theme.color.surfaceContainerHigh
        borderWidth: 1
        borderColor: Theme.color.outlineVariant
        shadowElevation: 6
    }

    Rectangle {
        id: iconContainer
        x: (parent.width - width) / 2
        y: 14
        width: 52
        height: 52
        radius: 26
        color: Theme.color.primaryContainer

        Icon {
            anchors.centerIn: parent
            name: "fullscreen_exit"
            width: 24
            height: 24
            color: Theme.color.onPrimaryContainerColor
        }
    }

    CircularProgress {
        x: (parent.width - width) / 2
        y: 12
        width: 56
        height: 56
        value: control.progress
        strokeWidth: 4
        showTrack: true
    }

    Text {
        x: 16
        y: control.holding ? 73 : 84
        width: parent.width - 32
        height: 24
        text: i18n.t("fullscreen.exitHint")
        color: Theme.color.onSurfaceColor
        font.pixelSize: 16
        font.weight: Font.DemiBold
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
        Behavior on y {
            NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
        }
    }

    Text {
        x: 16
        y: 98
        width: parent.width - 32
        height: 18
        text: i18n.t("fullscreen.releaseHint")
        color: Theme.color.onSurfaceVariantColor
        font.pixelSize: 12
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        opacity: control.holding ? 1 : 0
        Behavior on opacity {
            NumberAnimation { duration: 120; easing.type: Easing.OutCubic }
        }
        elide: Text.ElideRight
    }
}
