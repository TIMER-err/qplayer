import QtQuick
import QtQuick.Layouts
import md3.Core

// Extracted out of SettingsPage.qml, same reason as CustomApiSettingsCard.qml
// (JVM 64KB bytecode limit on the generated root constructor).
//
// Each card keeps its title+control row and its description Text as DIRECT
// siblings inside one outer ColumnLayout — Layout.fillWidth only reliably
// reaches a Layout's IMMEDIATE child in this engine, so a description nested
// a level deeper (inside a second ColumnLayout alongside the row) overflows
// the card instead of wrapping. See CustomApiSettingsCard.qml's identical
// fix and the qml4j-gotchas memory.
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
        implicitHeight: unblockCol.implicitHeight + 32

        ColumnLayout {
            id: unblockCol
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 16
            spacing: 4

            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: "音源解锁"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Switch {
                    checked: settings.unblockEnabled
                    onClicked: settings.unblockEnabled = checked
                }
            }
            Text {
                Layout.fillWidth: true
                text: "灰色/VIP/试听歌曲\n自动尝试其他音源"
                color: Theme.color.onSurfaceVariantColor
                font.family: Theme.typography.bodySmall.family
                font.pixelSize: Theme.typography.bodySmall.size
                wrapMode: Text.WordWrap
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
        implicitHeight: mirrorCol.implicitHeight + 32

        ColumnLayout {
            id: mirrorCol
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 16
            spacing: 4

            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: "下载加速镜像"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Switch {
                    checked: settings.mirrorEnabled
                    onClicked: settings.mirrorEnabled = checked
                }
            }
            Text {
                Layout.fillWidth: true
                text: "通过 gh-proxy\n镜像下载应用更新"
                color: Theme.color.onSurfaceVariantColor
                font.family: Theme.typography.bodySmall.family
                font.pixelSize: Theme.typography.bodySmall.size
                wrapMode: Text.WordWrap
            }
        }
    }
}
