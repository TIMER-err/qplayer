import QtQuick
import QtQuick.Layouts
import miuix.Core

// One row of the toast stack, visually identical to the original Snackbar
// (rounded rect, auto height, wrapping text, close icon). The host (ToastStack)
// assigns it a toast via show() and the slot then owns it until it fades out on
// its own timer (or via beginDismiss(), e.g. the close icon). A toast NEVER moves
// between slots, so show() can safely cancel a running fade-out and a fade can
// never overlap a reflow. Fixed instances because qml4j can't auto-arrange
// dynamic children — no Repeater.
Item {
    id: row

    property var host: null
    property var entry: null
    property string text: ""
    property int timeout: 4000

    width: parent ? parent.width : 0
    height: background.height

    visible: false
    opacity: 0

    // Render a toast: fade in, arm the timer. Cancels any in-flight fade-out, so
    // an evicted slot (host closed the oldest toast) snaps to the new one.
    function show() {
        hideAnim.stop()
        visible = true
        showAnim.restart()
        timer.restart()
    }

    // Fade out; onFinished clears the slot so the host can reuse it.
    function beginDismiss() {
        timer.stop()
        hideAnim.restart()
    }

    NumberAnimation {
        id: showAnim
        target: row
        property: "opacity"
        to: 1.0
        duration: 200
        easing.type: Easing.OutQuad
    }

    NumberAnimation {
        id: hideAnim
        target: row
        property: "opacity"
        to: 0.0
        duration: 150
        easing.type: Easing.InQuad
        onFinished: {
            row.entry = null
            row.visible = false
        }
    }

    Timer {
        id: timer
        interval: row.timeout
        repeat: false
        onTriggered: {
            if (row.entry && row.host) row.host.dismissSlot(row)
        }
    }

    Rectangle {
        id: background
        width: row.width
        height: layout.implicitHeight + 28
        color: Theme.color.inverseSurface
        radius: 4

        RowLayout {
            id: layout
            anchors.fill: parent
            anchors.margins: 14
            spacing: 8

            Text {
                text: row.text
                color: Theme.color.inverseOnSurface
                font.family: Theme.typography.bodyMedium.family
                font.pixelSize: Theme.typography.bodyMedium.size
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                verticalAlignment: Text.AlignVCenter
            }

            // Close Icon
            Item {
                Layout.preferredWidth: 36
                Layout.preferredHeight: 36

                Ripple {
                    anchors.fill: parent
                    clipRadius: 18
                    rippleColor: Theme.color.inverseOnSurface
                    onClicked: {
                        if (row.entry && row.host) row.host.dismissSlot(row)
                    }
                }

                Text {
                    anchors.centerIn: parent
                    text: "close"
                    font.family: Theme.iconFont.name
                    font.pixelSize: 20
                    color: Theme.color.inverseOnSurface
                }
            }
        }
    }
}
