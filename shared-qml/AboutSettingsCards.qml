import QtQuick
import QtQuick.Layouts
import md3.Core

// Extracted out of SettingsPage.qml, same reason as CustomApiSettingsCard.qml
// (JVM 64KB bytecode limit on the generated root constructor).
//
// 关于 tab: app name/version, tagline, GitHub link.
ColumnLayout {
    id: root
    spacing: 14

    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: aboutRow.implicitHeight + 32

        RowLayout {
            id: aboutRow
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 16
            anchors.rightMargin: 8
            spacing: 12

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 3
                RowLayout {
                    spacing: 8
                    Text {
                        id: nameText
                        text: "QPlayer"
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: Theme.typography.bodyLarge.size
                    }
                    Text {
                        text: player.appVersion.length > 0 ? "v" + player.appVersion : ""
                        color: Theme.color.primary
                        font.family: Theme.typography.labelMedium.family
                        font.pixelSize: Theme.typography.labelMedium.size
                        Layout.alignment: Qt.AlignVCenter
                    }
                }
                // Manual line breaks — qml4j's auto-wrap mis-measures the
                // width here, so the breaks are hard-coded instead.
                Text {
                    text: "网易云音乐第三方客户端\nMaterial You 风格 · Apple Music 风逐字歌词\n由自研 qml4j 引擎强力驱动 · Skia 渲染后端"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                }
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                icon: "link"
                onClicked: player.openExternalUrl("https://github.com/TIMER-err/qplayer")
            }
        }
    }
}
