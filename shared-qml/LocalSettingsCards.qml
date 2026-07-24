import QtQuick
import QtQuick.Layouts
import md3.Core

// Extracted out of SettingsPage.qml, same reason as CustomApiSettingsCard.qml
// (JVM 64KB bytecode limit on the generated root constructor).
//
// 本地 tab: disk cache size/usage/clear, cache folder (desktop-only), local
// music folder (desktop-only, both typeof-guarded — no counterpart in
// AppSettings/Android).
ColumnLayout {
    id: root
    spacing: 14

    // Cache controls are cross-platform (both Settings twins have
    // maxCacheSizeMB); only the "缓存目录" sub-fields below stay
    // desktop-only-guarded, same as before merging this and the
    // music-folder card into one 本地 tab.
    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: cacheCol.implicitHeight + 32

        ColumnLayout {
            id: cacheCol
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 16
            spacing: 14

            // Max cache size stepper.
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: "最大缓存"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Button {
                    type: "outlined"; text: "−"
                    onClicked: settings.maxCacheSizeMB = Math.max(50, settings.maxCacheSizeMB - 50)
                }
                Text {
                    text: settings.maxCacheSizeMB + " MB"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Button {
                    type: "outlined"; text: "+"
                    onClicked: settings.maxCacheSizeMB = Math.min(1024, settings.maxCacheSizeMB + 50)
                }
            }

            // Current usage + clear button.
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Text {
                        text: "当前占用"
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: Theme.typography.bodyLarge.size
                    }
                    Text {
                        id: cacheUsageText
                        text: player.cacheSizeMB + " MB"
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: Theme.typography.bodySmall.size
                    }
                }
                Button {
                    type: "outlined"; text: "清除缓存"
                    onClicked: { player.clearDiskCache(); cacheUsageText.text = player.cacheSizeMB + " MB" }
                }
            }

            // Desktop-only: hidden on Android where AppSettings has no cacheFolder.
            // Flattened directly into cacheCol (not wrapped in its own
            // ColumnLayout) — qml4j's Layout doesn't reliably propagate
            // fillWidth through a nested Layout, which silently breaks
            // Text.WordWrap below.
            Text {
                visible: typeof settings.cacheFolder !== "undefined"
                text: "缓存目录"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.bodyLarge.family
                font.pixelSize: Theme.typography.bodyLarge.size
            }
            Text {
                visible: typeof settings.cacheFolder !== "undefined"
                Layout.fillWidth: true
                text: "本地音乐库封面/歌词缓存与网易云缓存都存在这里；修改后不会自动搬运旧文件，会重新扫描并在新目录下重建缓存"
                color: Theme.color.onSurfaceVariantColor
                font.family: Theme.typography.bodySmall.family
                font.pixelSize: Theme.typography.bodySmall.size
                wrapMode: Text.WordWrap
            }
            RowLayout {
                visible: typeof settings.cacheFolder !== "undefined"
                Layout.fillWidth: true
                spacing: 8
                TextField {
                    id: cacheFolderField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "目录路径"
                    text: typeof settings.cacheFolder !== "undefined" ? settings.cacheFolder : ""
                    onAccepted: settings.cacheFolder = text
                }
                Button {
                    type: "tonal"; text: "应用"
                    onClicked: settings.cacheFolder = cacheFolderField.text
                }
            }
        }
    }

    Rectangle {
        visible: typeof settings.musicFolder !== "undefined"
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: localCol.implicitHeight + 32

        ColumnLayout {
            id: localCol
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 16
            spacing: 12

            Text {
                text: "本地音乐目录"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.bodyLarge.family
                font.pixelSize: Theme.typography.bodyLarge.size
            }
            Text {
                Layout.fillWidth: true
                text: "修改后将自动重新扫描该目录中的音乐文件"
                color: Theme.color.onSurfaceVariantColor
                font.family: Theme.typography.bodySmall.family
                font.pixelSize: Theme.typography.bodySmall.size
                wrapMode: Text.WordWrap
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                TextField {
                    id: musicFolderField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "目录路径"
                    text: typeof settings.musicFolder !== "undefined" ? settings.musicFolder : ""
                    onAccepted: settings.musicFolder = text
                }
                Button {
                    type: "tonal"; text: "应用"
                    onClicked: settings.musicFolder = musicFolderField.text
                }
            }
        }
    }
}
