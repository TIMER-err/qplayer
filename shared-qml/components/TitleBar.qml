import QtQuick
import miuix.Core
import design
import "."

// AppKit supplies the macOS controls. Windows uses the caption buttons below.
Rectangle {
    id: bar
    color: Ui.background
    // Unfocused: dim the content, not the bar, so the strip keeps matching the
    // page background beneath it.
    readonly property color glyphColor: hostWindow.focused ? Ui.textPrimary : Ui.textTertiary

    Image {
        id: appIcon
        source: "app-icon.png"
        width: 18
        height: 18
        anchors.left: parent.left
        anchors.leftMargin: hostWindow.nativeButtonsEnd > 0 ? hostWindow.nativeButtonsEnd + 10 : 12
        anchors.verticalCenter: parent.verticalCenter
        sourceSize.width: Math.round(18 * player.pixelRatio)
        sourceSize.height: Math.round(18 * player.pixelRatio)
    }
    Text {
        anchors.left: appIcon.right
        anchors.leftMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        text: "QPlayer"
        font.pixelSize: Ui.textCaption
        font.weight: Ui.weightBold
        color: hostWindow.focused ? Ui.textSecondary : Ui.textTertiary
    }

    Row {
        id: buttons
        visible: hostWindow.nativeButtonsEnd <= 0
        anchors.right: parent.right
        anchors.top: parent.top
        height: parent.height
        spacing: 0

        // Minimize
        Rectangle {
            width: hostWindow.buttonWidthPx
            height: buttons.height
            color: "transparent"
            Rectangle {
                anchors.fill: parent
                color: minMa.pressed ? Ui.surfacePressed : (minMa.containsMouse ? Ui.surfaceHover : "transparent")
            }
            Rectangle {
                anchors.centerIn: parent
                width: 10
                height: 1
                color: bar.glyphColor
            }
            MouseArea {
                id: minMa
                anchors.fill: parent
                hoverEnabled: true
                onClicked: hostWindow.minimize()
            }
        }

        // Maximize / restore -- glyph swaps on hostWindow.maximized, which tracks
        // the real OS state (also updates when maximized via double-click/Snap/
        // drag-to-edge, not just this button -- see DesktopWindow's
        // glfwSetWindowMaximizeCallback).
        Rectangle {
            width: hostWindow.buttonWidthPx
            height: buttons.height
            color: "transparent"
            Rectangle {
                anchors.fill: parent
                color: maxMa.pressed ? Ui.surfacePressed : (maxMa.containsMouse ? Ui.surfaceHover : "transparent")
            }
            Rectangle {
                visible: !hostWindow.maximized
                anchors.centerIn: parent
                width: 10
                height: 10
                radius: 2
                color: "transparent"
                border.width: 1
                border.color: bar.glyphColor
            }
            Item {
                visible: hostWindow.maximized
                anchors.centerIn: parent
                width: 12
                height: 12
                Rectangle {
                    x: 2
                    y: 0
                    width: 9
                    height: 9
                    color: "transparent"
                    border.width: 1
                    border.color: bar.glyphColor
                }
                Rectangle {
                    x: 0
                    y: 3
                    width: 9
                    height: 9
                    color: bar.color
                    border.width: 1
                    border.color: bar.glyphColor
                }
            }
            MouseArea {
                id: maxMa
                anchors.fill: parent
                hoverEnabled: true
                onClicked: hostWindow.toggleMaximize()
            }
        }

        // Close -- red hover fill matches the native Windows 11 caption button
        // convention, unlike the two neutral-tint buttons above.
        Rectangle {
            width: hostWindow.buttonWidthPx
            height: buttons.height
            color: closeMa.pressed ? "#C42B1C" : (closeMa.containsMouse ? "#E81123" : "transparent")
            Glyph {
                anchors.centerIn: parent
                name: "close"
                size: 16
                color: closeMa.containsMouse ? "white" : bar.glyphColor
            }
            MouseArea {
                id: closeMa
                anchors.fill: parent
                hoverEnabled: true
                onClicked: hostWindow.close()
            }
        }
    }
}
