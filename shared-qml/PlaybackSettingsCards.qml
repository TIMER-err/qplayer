import QtQuick
import QtQuick.Layouts
import md3.Core

// Extracted out of SettingsPage.qml, same reason as CustomApiSettingsCard.qml
// (JVM 64KB bytecode limit on the generated root constructor).
//
// 播放 tab, part 1: source-unblock toggle + download mirror toggle.
// CustomApiSettingsCard.qml (the other, larger part of this tab) stays a
// separate file/component, instantiated alongside this one.
ColumnLayout {
    id: root
    spacing: 14

    // Source-switching toggle: grey/VIP/trial netease tracks fall back
    // to the unblock sources (gdstudio / bodian / kuwo) before skipping.
    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: unblockRow.implicitHeight + 32

        RowLayout {
            id: unblockRow
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            spacing: 12

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    text: "音源解锁"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Text {
                    Layout.fillWidth: true
                    text: "灰色/VIP/试听歌曲自动尝试其他音源"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                    wrapMode: Text.WordWrap
                }
            }
            Switch {
                checked: settings.unblockEnabled
                onClicked: settings.unblockEnabled = checked
            }
        }
    }

    // GitHub download mirror: route app-update APK downloads through the
    // gh-proxy mirror (faster/reachable on mainland networks).
    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: mirrorRow.implicitHeight + 32

        RowLayout {
            id: mirrorRow
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            spacing: 12

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    text: "下载加速镜像"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Text {
                    Layout.fillWidth: true
                    text: "通过 gh-proxy 镜像下载应用更新"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                    wrapMode: Text.WordWrap
                }
            }
            Switch {
                checked: settings.mirrorEnabled
                onClicked: settings.mirrorEnabled = checked
            }
        }
    }
}
