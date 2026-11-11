import QtQuick
import miuix.Core
import design

// One notice slot of NoticeStack: a compact inverse-surface pill that hugs its
// text (up to three wrapped lines), rises in, and fades out after `timeout`.
// Tapping it dismisses it early. NoticeStack owns ordering and reuse.
Item {
    id: slot

    property var host: null
    property var entry: null
    property string text: ""
    property int timeout: 3200
    property real maxWidth: 440
    property real _offsetY: 12

    readonly property real _padX: 20
    readonly property real _textMax: Math.max(0, Math.min(slot.maxWidth, slot.width) - 2 * slot._padX)

    width: parent ? parent.width : 0
    height: pill.height
    visible: false
    opacity: 0

    function show() {
        hideAnim.stop()
        lifeTimer.stop()
        if (!visible) {
            opacity = 0
            _offsetY = 12
            pill.scale = 0.94
            visible = true
        }
        showAnim.restart()
        if (timeout > 0) lifeTimer.restart()
    }

    function beginDismiss() {
        if (!visible) return
        lifeTimer.stop()
        showAnim.stop()
        hideAnim.restart()
    }

    ParallelAnimation {
        id: showAnim
        NumberAnimation { target: slot; property: "opacity"; to: 1; duration: 160; easing.type: Easing.OutCubic }
        NumberAnimation { target: slot; property: "_offsetY"; to: 0; duration: 240; easing.type: Easing.OutCubic }
        NumberAnimation { target: pill; property: "scale"; to: 1; duration: 240; easing.type: Easing.OutBack }
    }
    ParallelAnimation {
        id: hideAnim
        onFinished: { slot.entry = null; slot.visible = false }
        NumberAnimation { target: slot; property: "opacity"; to: 0; duration: 140; easing.type: Easing.InCubic }
        NumberAnimation { target: slot; property: "_offsetY"; to: 6; duration: 140; easing.type: Easing.InCubic }
    }
    Timer {
        id: lifeTimer
        interval: slot.timeout
        onTriggered: slot.beginDismiss()
    }

    // Unconstrained probe: the pill is as wide as one line of text, capped.
    Text {
        id: probe
        visible: false
        text: slot.text
        font.pixelSize: Ui.textCallout
    }

    Rectangle {
        id: pill
        objectName: "notificationCard"
        readonly property real textWidth: Math.min(slot._textMax, Math.ceil(probe.implicitWidth))
        width: textWidth + 2 * slot._padX
        height: Math.max(44, message.implicitHeight + 24)
        x: (slot.width - width) / 2
        y: slot._offsetY
        radius: Math.min(22, height / 2)
        color: Theme.color.inverseSurface
        transformOrigin: Item.Bottom
        scale: 0.94

        Text {
            id: message
            x: slot._padX
            y: (parent.height - implicitHeight) / 2
            width: pill.textWidth
            text: slot.text
            color: Theme.color.inverseOnSurface
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            maximumLineCount: 3
            elide: Text.ElideRight
            font.pixelSize: Ui.textCallout
        }

        MouseArea {
            objectName: "notificationDismiss"
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: if (slot.entry && slot.host) slot.host.dismissSlot(slot)
        }
    }
}
