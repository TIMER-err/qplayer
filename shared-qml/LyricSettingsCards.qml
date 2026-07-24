import QtQuick
import QtQuick.Layouts
import md3.Core

// Extracted out of SettingsPage.qml, same reason as CustomApiSettingsCard.qml
// (JVM 64KB bytecode limit on the generated root constructor).
//
// 歌词 tab: font size/weight/line-spacing steppers, spring physics, active-line
// zoom, syllable glow, plain-LRC linear animation, edge blur, background mode.
ColumnLayout {
    id: root
    spacing: 14

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
            SegmentedButton {
                Layout.fillWidth: true
                buttons: [
                    { text: "极细", selected: settings.lyricFontWeight === 0 },
                    { text: "细", selected: settings.lyricFontWeight === 1 },
                    { text: "常规", selected: settings.lyricFontWeight === 2 },
                    { text: "中等", selected: settings.lyricFontWeight === 3 }
                ]
                onClicked: settings.lyricFontWeight = index
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

            // Apple-style spring physics toggle (scroll + per-字 lift).
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Text {
                        text: "弹簧动效"
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: Theme.typography.bodyLarge.size
                    }
                    Text {
                        Layout.fillWidth: true
                        text: "滚动与逐字上抬使用弹簧物理"
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: Theme.typography.bodySmall.size
                        wrapMode: Text.WordWrap
                    }
                }
                Switch {
                    checked: settings.lyricSpring
                    onClicked: settings.lyricSpring = checked
                }
            }

            // Active-line emphasis zoom toggle.
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Text {
                        text: "放大缩放"
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: Theme.typography.bodyLarge.size
                    }
                    Text {
                        Layout.fillWidth: true
                        text: "当前行放大、其余行略缩"
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: Theme.typography.bodySmall.size
                        wrapMode: Text.WordWrap
                    }
                }
                Switch {
                    checked: settings.lyricScale
                    onClicked: settings.lyricScale = checked
                }
            }

            // White glow behind sung syllables toggle.
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Text {
                        text: "演唱发光"
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: Theme.typography.bodyLarge.size
                    }
                    Text {
                        Layout.fillWidth: true
                        text: "已唱字词白色辉光(较耗电)"
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: Theme.typography.bodySmall.size
                        wrapMode: Text.WordWrap
                    }
                }
                Switch {
                    checked: settings.lyricGlow
                    onClicked: settings.lyricGlow = checked
                }
            }

            // Plain-LRC (no real per-word timing) lines: linear
            // front-to-back sweep vs the whole line lighting up together.
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Text {
                        text: "非逐字歌词线性动画"
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: Theme.typography.bodyLarge.size
                    }
                    Text {
                        Layout.fillWidth: true
                        text: "关闭时整行一起点亮"
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: Theme.typography.bodySmall.size
                        wrapMode: Text.WordWrap
                    }
                }
                Switch {
                    checked: settings.lyricLinearAnim
                    onClicked: settings.lyricLinearAnim = checked
                }
            }

            // Apple-Music edge blur: unfocused lines blur toward the edges.
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Text {
                        text: "边缘模糊"
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: Theme.typography.bodyLarge.size
                    }
                    Text {
                        Layout.fillWidth: true
                        text: "未聚焦歌词按远近渐进高斯模糊(较耗电)"
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: Theme.typography.bodySmall.size
                        wrapMode: Text.WordWrap
                    }
                }
                Switch {
                    checked: settings.lyricEdgeBlur
                    onClicked: settings.lyricEdgeBlur = checked
                }
            }

            // Fluid background mode: dynamic (animated) or static
            // (rendered once + cached, lighter on the GPU). Label + desc
            // stacked, radios on their own row so the desc has full width.
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    text: "背景动效"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Text {
                    Layout.fillWidth: true
                    text: "动态流动 / 静态(渲染一次,更省电)"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                    wrapMode: Text.WordWrap
                }
                RowLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 4
                    spacing: 16
                    RadioButton {
                        text: "动态"
                        checked: !settings.lyricBgStatic
                        onClicked: settings.lyricBgStatic = false
                    }
                    RadioButton {
                        text: "静态"
                        checked: settings.lyricBgStatic
                        onClicked: settings.lyricBgStatic = true
                    }
                    Item { Layout.fillWidth: true }
                }
            }
        }
    }
}
