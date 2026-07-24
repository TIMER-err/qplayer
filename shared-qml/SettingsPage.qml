import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// App settings overlay: appearance (dark-mode policy + Monet dynamic color) and
// an about section. Writes the `settings` context global, which drives
// StyleManager through the Bindings in Main.qml. Section containers are plain
// rounded rectangles sized to their content (md3 Card is fixed-size).
//
// Each category's actual card content lives in its own top-level component
// file (AppearanceSettingsCards.qml, PlaybackSettingsCards.qml + the older
// CustomApiSettingsCard.qml, LyricSettingsCards.qml, LocalSettingsCards.qml,
// AboutSettingsCards.qml) rather than inline here — qml4j compiles each QML
// file's root to one JVM constructor, and all 5 panels' markup inline in this
// one file pushed the generated method past the JVM's 64KB bytecode limit
// (MethodTooLargeException at runtime, not caught by `mvn package`).
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
            // vertically instead.
            Item {
                id: content
                width: parent.width

                Item {
                    id: panelAppearance
                    width: parent.width
                    implicitHeight: appearanceCards.implicitHeight
                    visible: page.currentCategory === "外观" || page.exitingCategory === "外观"
                    x: page.panelX("外观")
                    z: page.currentCategory === "外观" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

                    // Opaque backing so this panel fully occludes the other one
                    // still sliding out behind it — without it, gaps around/
                    // between cards let the other panel's cards show through
                    // mid-slide, which read as the two pages' items overlapping.
                    Rectangle {
                        anchors.fill: parent
                        color: Theme.color.surface
                    }

                    AppearanceSettingsCards {
                        id: appearanceCards
                        width: parent.width
                        onPickFont: page.fontPickerOpen = true
                    }
                } // end 外观

                Item {
                    id: panelPlayback
                    width: parent.width
                    implicitHeight: playbackCards.implicitHeight
                    visible: page.currentCategory === "播放" || page.exitingCategory === "播放"
                    x: page.panelX("播放")
                    z: page.currentCategory === "播放" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

                    Rectangle {
                        anchors.fill: parent
                        color: Theme.color.surface
                    }

                    ColumnLayout {
                        id: playbackCards
                        width: parent.width
                        spacing: 14
                        PlaybackSettingsCards { Layout.fillWidth: true }
                        // See CustomApiSettingsCard.qml — factored into its own file
                        // for the same 64KB-method reason noted above.
                        CustomApiSettingsCard {}
                    }
                } // end 播放

                Item {
                    id: panelLyric
                    width: parent.width
                    implicitHeight: lyricCards.implicitHeight
                    visible: page.currentCategory === "歌词" || page.exitingCategory === "歌词"
                    x: page.panelX("歌词")
                    z: page.currentCategory === "歌词" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

                    Rectangle {
                        anchors.fill: parent
                        color: Theme.color.surface
                    }

                    LyricSettingsCards {
                        id: lyricCards
                        width: parent.width
                    }
                } // end 歌词

                Item {
                    id: panelLocal
                    width: parent.width
                    implicitHeight: localCards.implicitHeight
                    visible: page.currentCategory === "本地" || page.exitingCategory === "本地"
                    x: page.panelX("本地")
                    z: page.currentCategory === "本地" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

                    Rectangle {
                        anchors.fill: parent
                        color: Theme.color.surface
                    }

                    LocalSettingsCards {
                        id: localCards
                        width: parent.width
                    }
                } // end 本地

                Item {
                    id: panelAbout
                    width: parent.width
                    implicitHeight: aboutCards.implicitHeight
                    visible: page.currentCategory === "关于" || page.exitingCategory === "关于"
                    x: page.panelX("关于")
                    z: page.currentCategory === "关于" ? 1 : 0
                    Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

                    Rectangle {
                        anchors.fill: parent
                        color: Theme.color.surface
                    }

                    AboutSettingsCards {
                        id: aboutCards
                        width: parent.width
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
