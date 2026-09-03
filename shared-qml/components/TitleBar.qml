import QtQuick
import md3.Core
import "."

// Windows-only custom title bar (see WinFrameless.java / WindowChrome.java on the
// desktop host). Drag and resize are entirely native: WinFrameless's WM_NCHITTEST
// reports HTCAPTION over this bar's empty space before qml4j's own input pipeline
// ever sees those clicks (confirmed -- GLFW's mouse-button callback simply doesn't
// fire for a non-HTCLIENT click), so nothing here needs a MouseArea for dragging --
// only the three caption buttons are real click targets. Height is driven by
// settings.topInset (set once, Windows-only, from DesktopWindow.TITLE_BAR_HEIGHT),
// not a local constant, so it can never drift out of sync with the native
// hit-test math that reserves the same strip.
Rectangle {
    id: bar
    color: Theme.color.surface
    opacity: hostWindow.focused ? 1 : 0.6
    Behavior on opacity { NumberAnimation { duration: 150 } }

    Image {
        id: appIcon
        source: "app-icon.png"
        width: 18
        height: 18
        anchors.left: parent.left
        anchors.leftMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        sourceSize.width: Math.round(18 * player.pixelRatio)
        sourceSize.height: Math.round(18 * player.pixelRatio)
    }
    Text {
        anchors.left: appIcon.right
        anchors.leftMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        text: "QPlayer"
        font.family: Theme.typography.labelLarge.family
        font.pixelSize: Theme.typography.labelLarge.size
        color: Theme.color.onSurfaceColor
    }

    Row {
        id: buttons
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
                color: Theme.color.onSurfaceColor
                opacity: minMa.pressed ? 0.12 : (minMa.containsMouse ? 0.08 : 0)
            }
            Rectangle {
                anchors.centerIn: parent
                width: 10
                height: 1
                color: Theme.color.onSurfaceColor
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
                color: Theme.color.onSurfaceColor
                opacity: maxMa.pressed ? 0.12 : (maxMa.containsMouse ? 0.08 : 0)
            }
            Rectangle {
                visible: !hostWindow.maximized
                anchors.centerIn: parent
                width: 10
                height: 10
                color: "transparent"
                border.width: 1
                border.color: Theme.color.onSurfaceColor
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
                    border.color: Theme.color.onSurfaceColor
                }
                Rectangle {
                    x: 0
                    y: 3
                    width: 9
                    height: 9
                    color: bar.color
                    border.width: 1
                    border.color: Theme.color.onSurfaceColor
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
            Text {
                anchors.centerIn: parent
                text: "close"
                font.family: Theme.iconFont.name
                font.pixelSize: 16
                color: closeMa.containsMouse ? "white" : Theme.color.onSurfaceColor
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
