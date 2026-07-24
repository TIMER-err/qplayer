import QtQuick
import QtQuick.Layouts
import md3.Core

// Extracted out of SettingsPage.qml, same reason as CustomApiSettingsCard.qml:
// qml4j compiles each QML file's root to one JVM constructor, and adding this
// panel's cards inline (on top of the other 4 panels' cards) pushed
// SettingsPage's generated method past the JVM's 64KB bytecode limit
// (MethodTooLargeException at runtime, not caught by `mvn package`).
//
// 外观 tab: dark-mode policy, Monet dynamic color, bundled-vs-system font,
// specific font family picker.
ColumnLayout {
    id: root
    spacing: 14

    // Dark-mode policy: three-way segmented button writing settings.darkMode.
    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: darkCol.implicitHeight + 32

        ColumnLayout {
            id: darkCol
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 16
            spacing: 12

            Text {
                text: "深色模式"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.bodyLarge.family
                font.pixelSize: Theme.typography.bodyLarge.size
            }
            SegmentedButton {
                Layout.fillWidth: true
                buttons: [
                    { text: "跟随系统", selected: settings.darkMode === 0 },
                    { text: "浅色", selected: settings.darkMode === 1 },
                    { text: "深色", selected: settings.darkMode === 2 }
                ]
                onClicked: settings.darkMode = index
            }
        }
    }

    // Monet dynamic color toggle + live seed swatch.
    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: monetRow.implicitHeight + 32

        RowLayout {
            id: monetRow
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            spacing: 12

            Rectangle {
                Layout.preferredWidth: 40
                Layout.preferredHeight: 40
                radius: 20
                color: Theme.color.primary
                border.width: 1
                border.color: Theme.color.outlineVariant
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    text: "莫奈取色"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Text {
                    text: "随封面动态生成主题配色"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                }
            }
            Switch {
                checked: settings.monetEnabled
                onClicked: settings.monetEnabled = checked
            }
        }
    }

    // Bundled PingFang SC vs the OS's own default font (issue #15).
    // Takes effect immediately on the lyric page; other UI text (buttons,
    // this settings page, etc.) needs an app restart to pick it up, and
    // only actually changes on Windows — noted in the subtitle so it
    // isn't a silent surprise.
    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: fontRow.implicitHeight + 32

        RowLayout {
            id: fontRow
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
                    text: "使用系统默认字体"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Text {
                    Layout.fillWidth: true
                    text: "歌词页立即生效；其余界面文字需要重启软件，且目前只有 Windows 上会真正切换"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                    wrapMode: Text.WordWrap
                }
            }
            Switch {
                checked: settings.useSystemFont
                onClicked: settings.useSystemFont = checked
            }
        }
    }

    // Pick one specific installed font instead of just "system default"
    // (issue #15, desktop/Windows-only — see FontPickerDialog.qml and
    // DesktopWindow's registry lookup). Not present in AppSettings
    // (Android), hence the typeof guard.
    Rectangle {
        visible: typeof settings.lyricFontFamily !== "undefined"
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: fontFamilyRow.implicitHeight + 32

        RowLayout {
            id: fontFamilyRow
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
                    text: "指定字体"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Text {
                    Layout.fillWidth: true
                    text: settings.lyricFontFamily ? ("当前：" + settings.lyricFontFamily) : "未指定，跟随上方开关"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                    wrapMode: Text.WordWrap
                }
            }
            Button {
                type: "tonal"; text: "选择…"
                onClicked: root.pickFont()
            }
        }
    }

    signal pickFont()
}
