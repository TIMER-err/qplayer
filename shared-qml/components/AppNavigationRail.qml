import QtQuick
import miuix.Core
import design
import "."

// Wide-screen sidebar, shown instead of the bottom bar from 600 logical px.
// Expanded (>= 840): labelled 232 px sidebar. Medium: 80 px icon rail.
//
//   brand (mobile only; desktop has its own title bar)
//   root destinations: 推荐 / 搜索 / 本地 / 我的
//   资料库: recent, downloads, plugin player actions, sleep timer
//   footer (pinned): account and settings
//
// The middle part scrolls, so short windows and many plugin actions never clip
// a destination. Every secondary entry comes from AppActions, shared with the
// compact overflow menu.
//
// Split out of Main.qml to keep that file's generated constructor below the
// JVM's 64 KB method limit.
Item {
    id: rail

    property var pageManager
    property var appActions

    readonly property bool expanded: pageManager.expanded
    readonly property real brandHeight: hostWindow.available ? settings.topInset + 8
                                                             : settings.topInset + 64

    anchors.left: parent.left
    anchors.leftMargin: settings.leftInset
    anchors.top: parent.top
    anchors.bottom: parent.bottom
    visible: pageManager.wide
    width: pageManager.wide ? (pageManager.expanded ? Ui.sidebarWide : Ui.sidebarNarrow) : 0
    Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

    Rectangle { anchors.fill: parent; color: Ui.background }
    Rectangle {
        x: parent.width - 1
        y: 0
        width: 1
        height: parent.height
        color: Ui.hairline
    }

    // ---- Brand ----------------------------------------------------------------
    Image {
        id: railLogo
        visible: !hostWindow.available
        width: 32
        height: 32
        x: rail.expanded ? 24 : (rail.width - width) / 2
        y: settings.topInset + 16
        source: "app-icon.png"
        // Decode at the drawn device size; the mipmapped downscale avoids aliasing
        // the disc grooves. The artwork carries its own rounded corners.
        sourceSize.width: Math.round(32 * player.pixelRatio)
        sourceSize.height: Math.round(32 * player.pixelRatio)
    }
    Text {
        x: railLogo.x + railLogo.width + 12
        y: railLogo.y + (railLogo.height - implicitHeight) / 2
        visible: rail.expanded && !hostWindow.available
        text: "QPlayer"
        color: Ui.textPrimary
        font.pixelSize: Ui.textTitle2
        font.weight: Ui.weightBold
    }

    // ---- Scrolling destinations -------------------------------------------------
    Flickable {
        id: navScroll
        objectName: "navRailScroll"
        x: 0
        y: rail.brandHeight
        width: rail.width
        height: Math.max(0, footer.y - y)
        contentHeight: navContent.height
        clip: true

        Item {
            id: navContent
            width: navScroll.width
            height: rootList.height + libraryLabel.height + toolList.height + 12

            // One highlight for every destination in the rail -- the four root
            // pages AND the tools under 更多 -- so the eight entries behave as one
            // list: selecting 已下载 slides the same pill down out of 我的 instead of
            // fading in a second, differently-timed pill of its own. Declared first
            // so the items paint on top of it.
            Rectangle {
                id: navSelection
                objectName: "navRailSelection"
                readonly property int rootCount: rail.pageManager.navItems.length
                readonly property var tools: rail.appActions.items
                // Combined index, -1 while a route page (detail/lyrics/…) owns the
                // screen and no destination is current.
                readonly property int index: {
                    var pm = rail.pageManager
                    if (pm.currentOverlay === "")
                        return pm.nextPage
                    for (var i = 0; i < navSelection.tools.length; i++) {
                        if (navSelection.tools[i].route !== ""
                                && navSelection.tools[i].route === pm.currentOverlay)
                            return navSelection.rootCount + i
                    }
                    return -1
                }
                // Last destination the pill marked. While a route page hides the
                // pill it stays parked here and only fades, instead of sliding to
                // 推荐 on the way out and back down again on return.
                property int shownIndex: 0
                onIndexChanged: if (navSelection.index >= 0) navSelection.shownIndex = navSelection.index
                readonly property real targetY: navSelection.shownIndex < navSelection.rootCount
                    ? rootList.y + navSelection.shownIndex * 48 + 2
                    : toolList.y + (navSelection.shownIndex - navSelection.rootCount) * 48 + 2
                x: rail.expanded ? 12 : (navContent.width - width) / 2
                y: navSelection.targetY
                width: rail.expanded ? navContent.width - 24 : 56
                height: 40
                radius: Ui.radiusMd
                color: Ui.accentSoft
                opacity: navSelection.index >= 0 ? 1 : 0
                visible: opacity > 0.01
                // 160 ms, not 90: at 60 Hz a 90 ms glide is five frames, and with a
                // decelerate curve the first frame already covers half the distance,
                // so the eye reads it as a jump followed by a nudge -- "卡" without a
                // single dropped frame. 160 ms gives ~10 frames, still snappier than
                // the 260 ms that felt laggy. Literal values because a Behavior reads
                // its template's duration/easing once, before bindings are evaluated
                // (a Ui.* token there is silently ignored).
                Behavior on y { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                Behavior on opacity { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }
            }

            Item {
                id: rootList
                y: 4
                width: parent.width
                height: rail.pageManager.navItems.length * 48
                Repeater {
                    model: rail.pageManager.navItems
                    SidebarItem {
                        objectName: "navRailItem" + index
                        x: 0
                        y: index * 48
                        width: rootList.width
                        expanded: rail.expanded
                        icon: modelData.icon
                        text: modelData.text
                        selected: rail.pageManager.currentOverlay === ""
                                  && rail.pageManager.nextPage === index
                        pillDrawnByParent: true
                        onClicked: rail.pageManager.switchTo(index)
                    }
                }
            }

            Item {
                id: libraryLabel
                y: rootList.y + rootList.height + 8
                width: parent.width
                height: rail.expanded ? 36 : 17
                Text {
                    x: 26
                    y: 12
                    visible: rail.expanded
                    text: i18n.t("nav.moreSection")
                    color: Ui.textTertiary
                    font.pixelSize: Ui.textCaption
                    font.weight: Ui.weightBold
                }
                Rectangle {
                    visible: !rail.expanded
                    x: 24
                    y: 8
                    width: parent.width - 48
                    height: 1
                    color: Ui.hairline
                }
            }

            Item {
                id: toolList
                y: libraryLabel.y + libraryLabel.height
                width: parent.width
                height: rail.appActions.items.length * 48
                Repeater {
                    model: rail.appActions.items
                    SidebarItem {
                        objectName: "navRailAction" + index
                        x: 0
                        y: index * 48
                        width: toolList.width
                        expanded: rail.expanded
                        icon: modelData.icon
                        text: modelData.text
                        selected: modelData.route !== ""
                                  && rail.pageManager.currentOverlay === modelData.route
                        // The highlight is the rail's shared pill, so a tool entry
                        // slides out of whichever destination was current instead of
                        // cross-fading a pill of its own.
                        pillDrawnByParent: true
                        onClicked: rail.appActions.run(modelData)
                    }
                }
            }
        }

        ViewportScrollBar { target: navScroll }
    }

    // ---- Pinned footer ----------------------------------------------------------
    Item {
        id: footer
        x: 0
        width: rail.width
        height: (rail.appActions.desktopLyricAvailable ? 48 : 0) + 48 * 2 + 16
        y: rail.height - height - settings.bottomInset

        Rectangle {
            x: 16
            y: 0
            width: parent.width - 32
            height: 1
            color: Ui.hairline
        }
        SidebarItem {
            objectName: "navRailDesktopLyric"
            visible: rail.appActions.desktopLyricAvailable
            x: 0
            y: 8
            width: parent.width
            expanded: rail.expanded
            icon: "subtitles"
            text: i18n.t("settings.desktopLyric.title")
            selected: settings.value("desktopLyricEnabled") === true
            onClicked: settings.setValue("desktopLyricEnabled",
                                         !settings.value("desktopLyricEnabled"))
        }
        SidebarItem {
            objectName: "navRailAccount"
            x: 0
            y: 8 + (rail.appActions.desktopLyricAvailable ? 48 : 0)
            width: parent.width
            expanded: rail.expanded
            icon: rail.appActions.accountItem.icon
            text: rail.appActions.accountItem.text
            selected: rail.pageManager.currentOverlay === "account"
            onClicked: rail.appActions.run(rail.appActions.accountItem)
        }
        SidebarItem {
            objectName: "navRailSettings"
            x: 0
            y: 8 + (rail.appActions.desktopLyricAvailable ? 96 : 48)
            width: parent.width
            expanded: rail.expanded
            icon: "settings"
            text: i18n.t("nav.settings")
            selected: rail.pageManager.currentOverlay === "settings"
                      || rail.pageManager.currentOverlay === "pluginSettings"
            onClicked: rail.appActions.run(rail.appActions.settingsItem)
        }
    }
}
