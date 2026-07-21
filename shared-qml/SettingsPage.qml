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
                                text: "通过 gh-proxy 镜像下载应用更新"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                        }
                        Switch {
                            checked: settings.mirrorEnabled
                            onClicked: settings.mirrorEnabled = checked
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
                                    text: "滚动与逐字上抬使用弹簧物理"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
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
                                    text: "当前行放大、其余行略缩"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
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
                                    text: "已唱字词白色辉光(较耗电)"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.lyricGlow
                                onClicked: settings.lyricGlow = checked
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
                                    text: "未聚焦歌词按远近渐进高斯模糊(较耗电)"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
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

                Text {
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "存储"
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

                // Desktop-only: hidden on Android where AppSettings has no musicFolder.
                Text {
                    visible: typeof settings.musicFolder !== "undefined"
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "本地"
                    color: Theme.color.primary
                    font.family: Theme.typography.titleSmall.family
                    font.pixelSize: Theme.typography.titleSmall.size
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
        }
    }
}
