import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// App settings overlay: appearance (dark-mode policy + Monet dynamic color) and
// an about section. Writes the `settings` context global, which drives
// StyleManager through the Bindings in Main.qml. Section containers are plain
// rounded rectangles sized to their content (md3 Card is fixed-size).
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    // Catch-all so taps on empty areas don't fall through to the page beneath.
    // Declared first (lowest z); the controls above still receive their events.
    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: "设置"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
            }
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: width
            contentHeight: content.implicitHeight + 24

            ColumnLayout {
                id: content
                width: parent.width
                spacing: 14

                Text {
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "外观"
                    color: Theme.color.primary
                    font.family: Theme.typography.titleSmall.family
                    font.pixelSize: Theme.typography.titleSmall.size
                }

                // Dark-mode policy: self-managed three-way segment (avoids relying on
                // a cross-file signal parameter) writing settings.darkMode.
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
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            Button {
                                Layout.fillWidth: true
                                type: settings.darkMode === 0 ? "filled" : "outlined"
                                text: "跟随系统"
                                onClicked: settings.darkMode = 0
                            }
                            Button {
                                Layout.fillWidth: true
                                type: settings.darkMode === 1 ? "filled" : "outlined"
                                text: "浅色"
                                onClicked: settings.darkMode = 1
                            }
                            Button {
                                Layout.fillWidth: true
                                type: settings.darkMode === 2 ? "filled" : "outlined"
                                text: "深色"
                                onClicked: settings.darkMode = 2
                            }
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

                Text {
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "播放"
                    color: Theme.color.primary
                    font.family: Theme.typography.titleSmall.family
                    font.pixelSize: Theme.typography.titleSmall.size
                }

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
                                text: "灰色/VIP/试听歌曲自动尝试其他音源"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                        }
                        Switch {
                            checked: settings.unblockEnabled
                            onClicked: settings.unblockEnabled = checked
                        }
                    }
                }

                Text {
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "歌词"
                    color: Theme.color.primary
                    font.family: Theme.typography.titleSmall.family
                    font.pixelSize: Theme.typography.titleSmall.size
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    radius: 18
                    color: Theme.color.surfaceContainerHighest
                    implicitHeight: lyricCol.implicitHeight + 32

                    ColumnLayout {
                        id: lyricCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 16
                        spacing: 14

                        // Font size stepper.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            Text {
                                Layout.fillWidth: true
                                text: "字号"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "−"
                                onClicked: settings.lyricFontSize = Math.max(14, settings.lyricFontSize - 1)
                            }
                            Text {
                                text: settings.lyricFontSize + " px"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "+"
                                onClicked: settings.lyricFontSize = Math.min(40, settings.lyricFontSize + 1)
                            }
                        }

                        // Font weight segment.
                        Text {
                            text: "字重"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 6
                            Button {
                                Layout.fillWidth: true
                                type: settings.lyricFontWeight === 0 ? "filled" : "outlined"
                                text: "极细"
                                onClicked: settings.lyricFontWeight = 0
                            }
                            Button {
                                Layout.fillWidth: true
                                type: settings.lyricFontWeight === 1 ? "filled" : "outlined"
                                text: "细"
                                onClicked: settings.lyricFontWeight = 1
                            }
                            Button {
                                Layout.fillWidth: true
                                type: settings.lyricFontWeight === 2 ? "filled" : "outlined"
                                text: "常规"
                                onClicked: settings.lyricFontWeight = 2
                            }
                            Button {
                                Layout.fillWidth: true
                                type: settings.lyricFontWeight === 3 ? "filled" : "outlined"
                                text: "中等"
                                onClicked: settings.lyricFontWeight = 3
                            }
                        }

                        // Line-spacing stepper (percent of font size, shown as ×).
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            Text {
                                Layout.fillWidth: true
                                text: "行间距"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "−"
                                onClicked: settings.lyricLineSpacing = Math.max(100, settings.lyricLineSpacing - 5)
                            }
                            Text {
                                text: (settings.lyricLineSpacing / 100).toFixed(2) + "×"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "+"
                                onClicked: settings.lyricLineSpacing = Math.min(250, settings.lyricLineSpacing + 5)
                            }
                        }
                    }
                }

                Text {
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "关于"
                    color: Theme.color.primary
                    font.family: Theme.typography.titleSmall.family
                    font.pixelSize: Theme.typography.titleSmall.size
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    radius: 18
                    color: Theme.color.surfaceContainerHighest
                    implicitHeight: aboutCol.implicitHeight + 32

                    ColumnLayout {
                        id: aboutCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 16
                        spacing: 2
                        Text {
                            text: "qplayer"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        Text {
                            text: "基于 qml4j 纯 Java QML 引擎"
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: Theme.typography.bodySmall.size
                        }
                    }
                }
            }
        }
    }
}
