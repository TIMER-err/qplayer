import QtQuick
import miuix.Core
import design
import "."
import "../dialogs"
import "../components"

// App settings route. Nothing here knows what a setting IS: the categories and
// the rows come from player-core's SettingsCatalog through the `settings` context
// global (SettingsCore), and each row is rendered by whichever Setting*Row
// component matches its declared type. Adding a setting is a catalog entry —
// no edit here, and none in either platform's host code.
//
// Wide pages (>= Ui.expandedMin) are master-detail: the category list on the
// left, the selected category's groups in a readable column on the right.
// Narrower pages keep a horizontally scrolling category tab row.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    signal openDebugLog()
    color: Ui.background

    property var categories: settings.categories()
    property var categoryTabModel: {
        var out = []
        for (var i = 0; i < page.categories.length; i++)
            out.push(i18n.t("settings.category." + page.categories[i]))
        return out
    }

    // Category switching fades the panel out, swaps, then fades it back in while
    // rising. Selection highlights follow nextCategory so they react at once.
    property string currentCategory: page.categories.length > 0 ? page.categories[0] : ""
    property string nextCategory: page.currentCategory
    property real panelOpacity: 1
    property real panelShift: 0
    property var groups: settings.groups(page.currentCategory)

    readonly property bool wide: width >= Ui.expandedMin
    readonly property real gutter: Ui.gutter(width)
    readonly property real navX: Math.max(4, gutter - 12)
    readonly property real navWidth: 232
    readonly property real tabsHeight: 45
    readonly property real detailX: wide ? navX + navWidth : 0
    readonly property real detailWidth: Math.max(0, width - detailX)
    readonly property real detailTop: wide ? Ui.headerHeight : Ui.headerHeight + tabsHeight + Ui.space2
    readonly property real panelLeft: wide ? Ui.space4 : gutter
    readonly property real panelAvailable: Math.max(0, detailWidth - panelLeft - gutter)
    readonly property real panelWidth: Math.min(Ui.readableMax, panelAvailable)

    function categoryIcon(name) {
        if (name === "appearance") return "palette"
        if (name === "pages") return "dashboard"
        if (name === "playback") return "play_circle"
        if (name === "lyric") return "lyrics"
        if (name === "overlayLyric") return "subtitles"
        if (name === "desktopLyric") return "desktop_windows"
        if (name === "local") return "storage"
        if (name === "plugins") return "extension"
        if (name === "about") return "info"
        return "settings"
    }

    function selectCategory(name) {
        if (!name || name === page.nextCategory) return
        page.nextCategory = name
        categoryAnim.restart()
    }

    SequentialAnimation {
        id: categoryAnim
        NumberAnimation {
            target: page; property: "panelOpacity"; to: 0
            duration: 90; easing.type: Easing.OutCubic
        }
        ScriptAction {
            onTrigger: {
                page.currentCategory = page.nextCategory
                settingsFlickable.contentY = 0
                page.panelShift = 24
            }
        }
        ParallelAnimation {
            NumberAnimation {
                target: page; property: "panelOpacity"; from: 0; to: 1
                duration: 220; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: page; property: "panelShift"; from: 24; to: 0
                duration: 220; easing.type: Easing.OutCubic
            }
        }
    }

    // Catch-all so taps on empty areas don't fall through to the page beneath.
    MouseArea { anchors.fill: parent }

    // ---- Wide: category list -----------------------------------------------------
    Flickable {
        id: categoryList
        objectName: "settingsCategoryList"
        visible: page.wide
        x: page.navX
        y: Ui.headerHeight + Ui.space1
        width: page.navWidth
        height: Math.max(0, page.height - y)
        clip: true
        contentWidth: width
        contentHeight: page.categories.length * 44 + Ui.space4 + page.bottomContentInset

        Repeater {
            model: page.wide ? page.categories : null
            SidebarItem {
                objectName: "settingsCategory_" + modelData
                y: index * 44
                width: page.navWidth
                icon: page.categoryIcon(modelData)
                text: i18n.t("settings.category." + modelData)
                selected: modelData === page.nextCategory
                onClicked: page.selectCategory(modelData)
            }
        }
    }

    // ---- Compact / medium: category tabs -------------------------------------------
    TabRowWithContour {
        id: categoryTabs
        objectName: "settingsCategoryTabs"
        visible: !page.wide
        x: page.gutter
        y: Ui.headerHeight
        width: Math.max(0, page.width - page.gutter * 2)
        height: page.tabsHeight
        equalWidth: false
        cornerRadius: Ui.radiusSm
        backgroundColor: Ui.surfaceRaised
        selectedBackgroundColor: Ui.accentSoft
        selectedContentColor: Ui.accent
        contentColor: Ui.textSecondary
        tabs: page.categoryTabModel
        selectedTabIndex: page.categories.indexOf(page.nextCategory)
        selectOnClick: false
        onTabSelected: (index) => page.selectCategory(page.categories[index])
    }

    // ---- Detail: the selected category ----------------------------------------------
    Flickable {
        id: settingsFlickable
        objectName: "settingsScroller"
        x: page.detailX
        y: page.detailTop
        width: page.detailWidth
        height: Math.max(0, page.height - y)
        clip: true
        contentWidth: width
        contentHeight: panel.implicitHeight + panel.topPad + Ui.space8 + page.bottomContentInset

        SettingsPanel {
            id: panel
            objectName: "settingsSingleColumn"
            readonly property real topPad: page.wide ? Ui.space1 : Ui.space3
            x: page.panelLeft + (page.panelAvailable - page.panelWidth) / 2
            y: topPad + page.panelShift
            width: page.panelWidth
            opacity: page.panelOpacity
            category: page.currentCategory
            groups: page.groups
            showTitle: page.wide
        }

        ViewportScrollBar {
            target: settingsFlickable
            bottomInset: page.bottomContentInset
        }
    }

    RouteHeader {
        id: header
        width: page.width
        title: i18n.t("settings.title")
        elevated: settingsFlickable.contentY > 1
        onBack: page.back()
        IconButton {
            objectName: "settingsDebugAction"
            type: "standard"
            icon: "bug_report"
            onClicked: page.openDebugLog()
        }
    }

    FontPickerDialog {
        active: settings.fontPickerOpen
        targetKey: settings.fontPickerTarget
        onClosed: settings.fontPickerOpen = false
    }

    ColorPickerDialog { objectName: "settingsColorPicker" }
}
