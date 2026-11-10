import QtQuick
import miuix.Core
import design
import "."

// Full-window debug log opened from Settings. The log string is rebuilt by the
// controller only while this overlay is shown (player.setLogVisible).
Rectangle {
    id: overlay
    signal closeRequested()
    color: Ui.background

    // Swallow pointer input so nothing underneath reacts while the log is open.
    MouseArea { anchors.fill: parent }

    RouteHeader {
        id: header
        x: 0
        y: settings.topInset
        width: parent.width
        title: i18n.t("log.title")
        elevated: logScroller.contentY > 1
        onBack: overlay.closeRequested()
        IconButton {
            objectName: "debugLogClear"
            type: "standard"
            icon: "delete"
            onClicked: player.clearLog()
        }
    }

    Flickable {
        id: logScroller
        x: Ui.gutter(overlay.width)
        y: header.y + header.height
        width: overlay.width - 2 * x
        height: overlay.height - y - settings.bottomInset
        clip: true
        contentHeight: logText.height + 24
        Text {
            id: logText
            width: logScroller.width
            text: player.logText
            color: Ui.textSecondary
            font.pixelSize: Ui.textCaption
            wrapMode: Text.WrapAnywhere
        }
        ViewportScrollBar { target: logScroller }
    }
}
