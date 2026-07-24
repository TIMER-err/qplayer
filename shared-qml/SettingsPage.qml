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
    property bool fontPickerOpen: false

    // Category tab bar (issue: settings had grown into one long scroll with just
    // inline section labels — this replaces that with an explicit selector).
    // Fixed 5-category list, always the same on every platform: 存储's cache
    // controls are cross-platform (both Settings twins have maxCacheSizeMB) and
    // now live under 本地 alongside the music-folder picker (that one card still
    // individually typeof-guards itself for Android, same as before) — merging
    // them meant one fewer tab than the original 6-category cut.
    property string currentCategory: "外观"
    property var categories: ["外观", "播放", "歌词", "本地", "关于"]

    // Cross-slide transition state: exitingCategory stays non-empty for one
    // animation cycle after a switch so the old panel keeps rendering (at an
    // offset) while the new one slides in from the other side, matching the
    // underline's direction of travel. slideDir is which way that direction is.
    property string exitingCategory: ""
    property int slideDir: 1
    property real slideOffset: 48

    function selectCategory(name) {
        if (name === page.currentCategory) return
        var oldIdx = page.categories.indexOf(page.currentCategory)
        var newIdx = page.categories.indexOf(name)
        page.slideDir = newIdx > oldIdx ? 1 : -1
        page.exitingCategory = page.currentCategory
        page.currentCategory = name
        exitTimer.restart()
    }

    // Each category panel's x binding: 0 when active/settled, slid out toward
    // slideDir when exiting, parked on the entry side otherwise (so it's ready
    // to slide in next time it becomes active, whichever direction that is).
    function panelX(catName) {
        if (page.currentCategory === catName) return 0
        if (page.exitingCategory === catName) return page.slideDir > 0 ? -page.slideOffset : page.slideOffset
        return page.slideDir > 0 ? page.slideOffset : -page.slideOffset
    }

    Timer {
        id: exitTimer
        interval: 260
        repeat: false
        onTriggered: page.exitingCategory = ""
    }

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

        // Category selector: a plain Item (not a Flickable — 5 categories at
        // equal width always fits in one row, even on a narrow phone screen, so
        // there's nothing to scroll).
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 46
            Layout.topMargin: 2
            Layout.bottomMargin: 4
            Layout.leftMargin: 12
            Layout.rightMargin: 12

            // Even division of this Item's own width — every category label is
            // exactly 2 characters, so equal-width slots read as intentional
            // rather than cramped, and (more importantly) let the underline
            // below be pure arithmetic instead of having to introspect a
            // Repeater delegate's actual on-screen geometry.
            property int currentIndex: page.categories.indexOf(page.currentCategory)
            property real tabWidth: width / Math.max(1, page.categories.length)

            RowLayout {
                anchors.fill: parent
                spacing: 0
                Repeater {
                    model: page.categories
                    Item {
                        id: tabSlot
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        property bool active: modelData === page.currentCategory

                        Text {
                            anchors.centerIn: parent
                            text: modelData
                            fontSize: 15
                            color: tabSlot.active ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                            font.family: tabSlot.active
                                ? Theme.typography.titleSmall.family
                                : Theme.typography.bodyLarge.family
                            Behavior on color { ColorAnimation { duration: 200 } }
                        }
                        MouseArea {
                            anchors.fill: parent
                            onClicked: page.selectCategory(modelData)
                        }
                    }
                }
            }

            // Selection indicator: a short underline that slides + resizes to the
            // active tab's slot, Apple/Material "underlined tab" style, rather
            // than the earlier filled-pill chips.
            Rectangle {
                id: tabUnderline
                height: 2
                radius: 1
                color: Theme.color.primary
                y: parent.height - height - 2
                width: parent.tabWidth * 0.7
                x: parent.tabWidth * parent.currentIndex + (parent.tabWidth - width) / 2
                Behavior on x { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
                Behavior on width { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
            }
        }

        Flickable {
            id: settingsFlickable
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: width
            // Bound to whichever panel is active (not a sum of all 5 — only one
            // is ever "settled"; the exiting one is on its way off-screen).
            contentHeight: {
                switch (page.currentCategory) {
                case "外观": return panelAppearance.implicitHeight + 24
                case "播放": return panelPlayback.implicitHeight + 24
                case "歌词": return panelLyric.implicitHeight + 24
                case "本地": return panelLocal.implicitHeight + 24
                case "关于": return panelAbout.implicitHeight + 24
                default: return 0
                }
            }

            // Plain Item, not a Layout: the 5 category panels below are
            // absolutely positioned (x-offset) so two can overlap on-screen
            // during a cross-slide transition — a ColumnLayout would stack them
            // vertically instead. Each panel keeps Layout.fillWidth on ITS OWN
            // children unaffected (that's relative to the panel itself, which
            // stays a real ColumnLayout underneath).
            Item {
                id: content
                width: parent.width

                ColumnLayout {
                    id: panelAppearance
                    width: parent.width
                    visible: page.currentCategory === "外观" || page.exitingCategory === "外观"
                    x: page.panelX("外观")
                    z: page.currentCategory === "外观" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
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
                            onClicked: page.fontPickerOpen = true
                        }
                    }
                }
                } // end 外观

                ColumnLayout {
                    id: panelPlayback
                    width: parent.width
                    visible: page.currentCategory === "播放" || page.exitingCategory === "播放"
                    x: page.panelX("播放")
                    z: page.currentCategory === "播放" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
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

                // See CustomApiSettingsCard.qml — factored into its own file because
                // qml4j compiles each QML file's root to one JVM constructor, and this
                // card's markup inline here pushed SettingsPage's generated method past
                // the JVM's 64KB bytecode limit (MethodTooLargeException at runtime).
                CustomApiSettingsCard {}
                } // end 播放

                ColumnLayout {
                    id: panelLyric
                    width: parent.width
                    visible: page.currentCategory === "歌词" || page.exitingCategory === "歌词"
                    x: page.panelX("歌词")
                    z: page.currentCategory === "歌词" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
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
                                    text: "关闭时整行一起点亮"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
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

                } // end 歌词

                ColumnLayout {
                    id: panelLocal
                    width: parent.width
                    visible: page.currentCategory === "本地" || page.exitingCategory === "本地"
                    x: page.panelX("本地")
                    z: page.currentCategory === "本地" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
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
                } // end 本地

                ColumnLayout {
                    id: panelAbout
                    width: parent.width
                    visible: page.currentCategory === "关于" || page.exitingCategory === "关于"
                    x: page.panelX("关于")
                    z: page.currentCategory === "关于" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
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
                } // end 关于
            }
        }
    }

    FontPickerDialog {
        active: page.fontPickerOpen
        onClosed: page.fontPickerOpen = false
    }
}
