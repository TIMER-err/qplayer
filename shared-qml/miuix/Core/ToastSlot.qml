import QtQuick
import miuix.Core

// One fixed notification slot. ToastStack owns ordering; this component owns the
// floating card, lifetime progress and interruptible entrance/exit motion.
Item {
    id: row

    property var host: null
    property var entry: null
    property string text: ""
    property int timeout: 4000
    property real _offsetY: 14
    property real _life: 0

    width: parent ? parent.width : 0
    height: card.height
    visible: false
    opacity: 0

    function show() {
        hideAnim.stop()
        lifeAnim.stop()
        _life = 0
        if (!visible) {
            opacity = 0
            _offsetY = 14
            card.scale = 0.96
            visible = true
        }
        showAnim.restart()
        if (timeout > 0) lifeAnim.restart()
    }

    function beginDismiss() {
        if (!visible) return
        lifeAnim.stop()
        showAnim.stop()
        hideAnim.restart()
    }

    ParallelAnimation {
        id: showAnim
        NumberAnimation {
            target: row
            property: "opacity"
            to: 1
            duration: 180
            easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: row
            property: "_offsetY"
            to: 0
            duration: 220
            easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: card
            property: "scale"
            to: 1
            duration: 220
            easing.type: Easing.OutBack
        }
    }

    ParallelAnimation {
        id: hideAnim
        onFinished: {
            row.entry = null
            row.visible = false
        }
        NumberAnimation {
            target: row
            property: "opacity"
            to: 0
            duration: 140
            easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: row
            property: "_offsetY"
            to: 10
            duration: 140
            easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: card
            property: "scale"
            to: 0.97
            duration: 140
            easing.type: Easing.InCubic
        }
    }

    NumberAnimation {
        id: lifeAnim
        target: row
        property: "_life"
        from: 0
        to: 1
        duration: row.timeout
        onFinished: row.beginDismiss()
    }

    Item {
        id: card
        x: (row.width - width) / 2
        y: row._offsetY
        width: Math.max(0, Math.min(520, row.width))
        height: Math.max(68, message.implicitHeight + 28)
        transformOrigin: Item.Center
        scale: 0.96

        Surface {
            anchors.fill: parent
            radius: 20
            containerColor: Theme.color.surfaceContainerHighest
            borderWidth: 1
            borderColor: Theme.color.outlineVariant
            shadowElevation: 6
        }

        Rectangle {
            id: leading
            x: 14
            y: (parent.height - height) / 2
            width: 40
            height: 40
            radius: 20
            color: Theme.color.secondaryContainer

            Icon {
                anchors.centerIn: parent
                name: "notifications"
                width: 21
                height: 21
                color: Theme.color.primary
            }
        }

        Text {
            id: message
            x: 66
            y: (parent.height - implicitHeight) / 2
            width: Math.max(0, parent.width - x - 52)
            text: row.text
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.bodyMedium.family
            font.pixelSize: Theme.typography.bodyMedium.size
            wrapMode: Text.Wrap
            maximumLineCount: 3
            elide: Text.ElideRight
        }

        Item {
            id: dismiss
            x: parent.width - width - 10
            y: (parent.height - height) / 2
            width: 36
            height: 36

            Ripple {
                anchors.fill: parent
                clipRadius: 18
                rippleColor: Theme.color.onSurfaceColor
                onClicked: {
                    if (row.entry && row.host) row.host.dismissSlot(row)
                }
            }

            Icon {
                anchors.centerIn: parent
                name: "close"
                width: 19
                height: 19
                color: Theme.color.onSurfaceVariantColor
            }
        }

        Rectangle {
            x: 20
            y: parent.height - 4
            width: parent.width - 40
            height: 2
            radius: 1
            color: Theme.color.outlineVariant
            clip: true

            Rectangle {
                width: parent.width * Math.max(0, 1 - row._life)
                height: parent.height
                radius: 1
                color: Theme.color.primary
            }
        }
    }
}
