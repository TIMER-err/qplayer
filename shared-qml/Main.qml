import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "components"
import "dialogs"
import "pages"
import "settings"

// Phone shell: TopAppBar + paged content + mini player + bottom navigation,
// with a playlist-detail overlay, QR login dialog, a Snackbar for transient
// messages and the debug log on top.
Rectangle {
    id: app
    color: Theme.color.surface

    property int page: 0
    property int nextPage: 0
    property bool loginOpen: false
    property bool showLog: false
    // Full-screen destinations share one navigation stack. Each drill-in keeps
    // its source underneath, so queue -> artist -> back restores the queue. Artist
    // and album routes also retain their ids, allowing deeper navigation to restore
    // the correct earlier entity instead of whichever one was loaded most recently.
    property var navigationStack: []
    property var currentRoute: navigationStack.length > 0
                               ? navigationStack[navigationStack.length - 1] : null
    property string currentOverlay: currentRoute ? currentRoute.type : ""
    property bool detailOpen: currentOverlay === "detail"
    property bool settingsOpen: currentOverlay === "settings"
    property bool accountOpen: currentOverlay === "account"
    property bool cacheListOpen: currentOverlay === "cachedSongs"
    // Source-independent destinations (local files, cached songs, queue,
    // settings and lyrics) remain usable without a plugin. Every online-source
    // destination shares one setup affordance instead of each page inventing a
    // different empty/loading state.
    property bool showSourceSetupPrompt: {
        if (!player.sourceSetupRequired) return false
        if (app.currentOverlay !== "") {
            return app.currentOverlay === "detail"
                    || app.currentOverlay === "artist"
                    || app.currentOverlay === "album"
                    || app.currentOverlay === "account"
        }
        // Home already owns the same action alongside its retry state.
        return app.page === 1 || app.page === 2
    }
    property bool syncingPageState: false
    // forward: new top page enters over an unchanged previous page.
    // back: only the departing top page exits, revealing an unchanged previous page.
    // replace: old path disappears immediately and the new root page may enter.
    property string navigationDirection: "replace"
    property string transitionUnderlayType: ""
    property string leavingPageType: ""
    property bool pageTransitionActive: false
    // SettingsCatalog.PAGE_TRANSITION_*: every full-page destination, including
    // the separately composited lyric page, uses this same motion vocabulary.
    // Zoom is the default; the final preset is an instant accessibility fallback.
    property int pageTransitionPreset: settings.value("pageTransitionPreset")
    // Build only the initial home page on startup. Once visited, a page remains
    // loaded so navigation state and close animations are preserved.
    property bool searchLoaded: false
    property bool libraryLoaded: false
    property bool localLoaded: false
    property bool detailLoaded: false
    property bool artistLoaded: false
    property bool albumLoaded: false
    property bool queueLoaded: false
    property bool settingsLoaded: false
    property bool pluginSettingsLoaded: false
    property bool accountLoaded: false
    property bool cacheListLoaded: false
    property var playerPluginActions: {
        var out = []
        var rows = player.pluginUiContributions || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].placement === "playerAction") out.push(rows[i])
        }
        return out
    }
    function footerActions() {
        var out = [{ action: "download", icon: "download", text: "已下载" }]
        for (var i = 0; i < app.playerPluginActions.length; i++) {
            var item = app.playerPluginActions[i]
            out.push({ action: "plugin", icon: item.icon, text: item.label,
                       pluginId: item.pluginId, contributionId: item.id })
        }
        out.push({ action: "account", icon: player.loggedIn ? "account_circle" : "login",
                   text: player.loggedIn ? "账户" : "登录" })
        out.push({ action: "settings", icon: "settings", text: "设置" })
        return out
    }
    // openArtist/openAlbum are called from reusable child components that cannot
    // see this root id. The controller publishes an event revision for every call
    // (not just false -> true), and the page manager turns it into a route push.
    property real pageNavigationWatch: player.pageNavigationRevision
    onPageNavigationWatchChanged: {
        if (pageNavigationWatch <= 0) return
        if (player.pageNavigationTarget === "artist")
            app.pushPage("artist", player.pageNavigationEntityId)
        else if (player.pageNavigationTarget === "album")
            app.pushPage("album", player.pageNavigationEntityId)
    }
    property real pluginSettingsNavigationWatch: player.pluginSettingsRevision
    onPluginSettingsNavigationWatchChanged: {
        if (pluginSettingsNavigationWatch > 0)
            app.pushPage("pluginSettings", player.pluginSettingsId)
    }
    // Host-side closes are uncommon (normal back now pops the stack), but mirror
    // one if it happens so the route cannot remain logically open after its
    // separately-rendered lyric layer has closed.
    property bool lyricsOpenWatch: player.lyricsOpen
    onLyricsOpenWatchChanged: {
        if (app.syncingPageState) return
        if (lyricsOpenWatch && app.topPageType() !== "lyrics")
            app.pushPage("lyrics", 0)
        else if (!lyricsOpenWatch && app.topPageType() === "lyrics")
            app.popPage()
    }
    // Menu.open() registers the one top-level popup currently attached to this
    // scene. Song rows each own a lazy menu instance, so without a scene-wide
    // owner repeated right-clicks can leave every row's overlay open at once.
    property var activeMenu: null

    property var titles: ["推荐", "搜索", "我的", "本地"]
    property bool showLocalTab: settings.value("showLocalTab")
    onShowLocalTabChanged: {
        if (!showLocalTab && app.page === 3) app.switchTo(0)
    }

    // Responsive breakpoints (MD3): compact < 600, medium 600–839, expanded ≥ 840.
    // The wide layout (a NavigationRail on the left instead of the bottom bar) is
    // driven purely by the available width, so a tablet, a desktop window, or even
    // a phone in landscape adopts it automatically once the width threshold is met.
    property bool wide: app.width >= 600
    property bool expanded: app.width >= 840
    // Java has no window-layout awareness of its own -- mirror this over so
    // playlist covers fetch/cache at a resolution matching how big they're
    // actually shown (512 compact, 1024 once wide). Decided once per playlist
    // load, not continuously re-fetched on every resize.
    onWideChanged: player.setWideLayout(wide)
    Component.onCompleted: player.setWideLayout(wide)

    // Shared nav model for both the bottom bar and the rail.
    property var navItems: showLocalTab
        ? [
            { icon: "recommend",     text: "推荐" },
            { icon: "search",        text: "搜索" },
            { icon: "library_music", text: "我的" },
            { icon: "folder",        text: "本地" }
          ]
        : [
            { icon: "recommend",     text: "推荐" },
            { icon: "search",        text: "搜索" },
            { icon: "library_music", text: "我的" }
          ]

    // Rebuild the debug log string only while it's actually shown (its set() forces a
    // full relayout, which periodically stuttered the scene when always rebuilt).
    onShowLogChanged: player.setLogVisible(app.showLog)

    // isDarkTheme follows the settings policy. seedColor (Monet) is driven from
    // PlayerController in Java -- a QML Binding on StyleManager.seedColor would not
    // re-fire when the cover seed changed.
    Binding {
        target: StyleManager; property: "isDarkTheme"
        value: settings.resolvedDark
    }

    // System back press (hardware + gesture): the host bumps player.backTick; pop the
    // topmost open overlay/page, and only ask the host to exit when nothing is open.
    property int backTick: player.backTick
    onBackTickChanged: app.handleBack()

    function handleBack() {
        // An unreadable credential envelope requires an explicit decision. Letting
        // outside click / Android back dismiss it would leave the app in an unclear
        // half-logged-in state with no path to retry or start over.
        if ((credentialNoticeDialog.opened && player.credentialNoticeType === 3)
                || credentialFallbackConfirmDialog.opened
                || credentialReloginUnavailableDialog.opened
                || pluginDialogs.handleBack()) return;
        if (player.songArtistPickerOpen) { player.closeSongArtistPicker(); return; }
        if (app.showLog)            { app.showLog = false; return; }
        if (app.loginOpen)          { app.loginOpen = false; return; }
        if (app.navigationStack.length > 0) { app.popPage(); return; }
        if (app.page !== 0)         { app.switchTo(0); return; }
        player.requestExit();
    }

    function ensurePageLoaded(which) {
        if (which === "detail") app.detailLoaded = true
        if (which === "artist") app.artistLoaded = true
        if (which === "album") app.albumLoaded = true
        if (which === "settings") app.settingsLoaded = true
        if (which === "pluginSettings") app.pluginSettingsLoaded = true
        if (which === "account") app.accountLoaded = true
        if (which === "cachedSongs") app.cacheListLoaded = true
        if (which === "queue") app.queueLoaded = true
    }

    function topRouteValue() {
        return app.navigationStack.length > 0
                ? app.navigationStack[app.navigationStack.length - 1] : null
    }

    function topPageType() {
        var route = app.topRouteValue()
        return route ? route.type : ""
    }

    function syncPageState(which) {
        app.syncingPageState = true
        player.setLyricsOpen(which === "lyrics")
        player.setQueueOpen(which === "queue")
        player.setArtistPageOpen(which === "artist")
        player.setAlbumPageOpen(which === "album")
        app.syncingPageState = false
    }

    // Top-level destinations opened from the app chrome replace the previous
    // path. Drill-ins use pushPage() so Back can restore their source page.
    function replacePage(which, entityId) {
        var departing = app.topRouteValue()
        var leavingRoot = app.navigationStack.length === 0 && which !== ""
        var animateReplace = app.pageTransitionPreset === 0 && departing
                             && departing.type !== which
        pageTransitionCleanup.stop()
        app.navigationDirection = "replace"
        app.transitionUnderlayType = ""
        app.leavingPageType = animateReplace ? departing.type : ""
        app.pageTransitionActive = animateReplace
        if (app.pageTransitionActive) pageTransitionCleanup.restart()
        app.ensurePageLoaded(which)
        app.navigationStack = which !== ""
                ? [{ type: which, entityId: entityId || 0 }] : []
        if (app.pageTransitionPreset === 0 && leavingRoot) rootPageMotion.exit()
        app.syncPageState(which)
    }

    function pushPage(which, entityId) {
        if (which === "") return
        app.ensurePageLoaded(which)
        var id = entityId || 0
        var current = app.topRouteValue()
        if (current && current.type === which && current.entityId == id) {
            app.syncPageState(which)
            return
        }
        app.navigationDirection = "forward"
        app.transitionUnderlayType = current ? current.type : ""
        app.leavingPageType = ""
        app.pageTransitionActive = app.transitionUnderlayType !== ""
        if (app.pageTransitionActive) pageTransitionCleanup.restart()
        var next = []
        for (var i = 0; i < app.navigationStack.length; i++)
            next.push(app.navigationStack[i])
        next.push({ type: which, entityId: id })
        app.navigationStack = next
        if (app.pageTransitionPreset === 0 && which !== "lyrics"
                && (!current || (current.type === "lyrics"
                                 && app.navigationStack.length === 1)))
            rootPageMotion.exit()
        app.syncPageState(which)
    }

    function restoreCurrentPage(route) {
        if (!route) return
        if (route.type === "artist")
            player.openMediaArtist("" + route.entityId)
        else if (route.type === "album")
            player.openMediaAlbum("" + route.entityId)
        else if (route.type === "detail")
            player.openMediaPlaylist("" + route.entityId)
    }

    function popPage() {
        if (app.navigationStack.length === 0) return
        var departing = app.topRouteValue()
        app.navigationDirection = "back"
        app.transitionUnderlayType = ""
        app.leavingPageType = departing ? departing.type : ""
        app.pageTransitionActive = app.leavingPageType !== ""
        if (app.pageTransitionActive) pageTransitionCleanup.restart()
        var next = []
        for (var i = 0; i < app.navigationStack.length - 1; i++)
            next.push(app.navigationStack[i])
        app.navigationStack = next
        var route = next.length > 0 ? next[next.length - 1] : null
        if (app.pageTransitionPreset === 0 && route && route.type === "lyrics")
            rootPageMotion.showImmediately()
        if (app.pageTransitionPreset === 0 && !route
                && (!departing || departing.type !== "lyrics"))
            rootPageMotion.enter()
        app.restoreCurrentPage(route)
        app.syncPageState(route ? route.type : "")
    }

    function clearPages() {
        pageTransitionCleanup.stop()
        app.navigationDirection = "replace"
        app.transitionUnderlayType = ""
        app.leavingPageType = ""
        app.pageTransitionActive = false
        app.navigationStack = []
        app.syncPageState("")
    }

    function goHome() {
        // Jump to the bottom/root destination underneath the current route stack,
        // not to a hard-coded navigation tab. A playlist opened from Search or My
        // Music must therefore return to Search/My Music with their state intact.
        // Drop every overlay immediately, then animate that existing root back in.
        rootPageMotion.stopAnimations()
        app.clearPages()
        rootPageMotion.enter()
    }

    // Give the active route a declaration-order-independent z. This matters while
    // the outgoing loader is still fading: queue is declared after artist in this
    // file, but queue -> artist must put artist on top immediately.
    function pageDepth(which) {
        for (var i = app.navigationStack.length - 1; i >= 0; i--) {
            if (app.navigationStack[i].type === which) return i + 1
        }
        return 0
    }

    function pageLayer(which, paintedOpacity) {
        var depth = app.pageDepth(which)
        if (depth > 0) return 100 + depth
        // A page popped by Back keeps top priority only for its own exit; a page
        // removed by replace is hidden immediately and never covers the newcomer.
        if ((app.navigationDirection === "back"
                || (app.navigationDirection === "replace"
                    && app.pageTransitionPreset === 0
                    && app.leavingPageType === which))
                && paintedOpacity > 0.001) return 1000
        return 1
    }

    function pagePainted(which, paintedOpacity) {
        if (app.currentOverlay === which) return true
        if (!app.pageTransitionActive) return false
        if (app.navigationDirection === "forward")
            return app.transitionUnderlayType === which
        if (app.navigationDirection === "back")
            return app.leavingPageType === which && paintedOpacity > 0.001
        if (app.navigationDirection === "replace" && app.pageTransitionPreset === 0)
            return app.leavingPageType === which && paintedOpacity > 0.001
        return false
    }

    Timer {
        id: pageTransitionCleanup
        interval: rootPageMotion.duration + 40
        repeat: false
        onTriggered: {
            app.pageTransitionActive = false
            app.transitionUnderlayType = ""
            app.leavingPageType = ""
        }
    }

    // Root destinations use the same configurable exit/swap/entry sequence as
    // route-backed pages. Keeping only one page painted avoids two expensive QML
    // trees being laid out during the transition.
    function switchTo(idx) {
        var returningFromRoute = app.navigationStack.length > 0
        app.clearPages()
        if (idx === app.page) {
            if (app.pageTransitionPreset === 0 && returningFromRoute)
                rootPageMotion.enter()
            return
        }
        if (idx === 1) app.searchLoaded = true
        if (idx === 2) app.libraryLoaded = true
        if (idx === 3) app.localLoaded = true
        app.nextPage = idx;
        if (idx === 2) player.loadMyPlaylists();
        rootPageMotion.transition();
    }

    PageMotion {
        id: rootPageMotion
        preset: app.pageTransitionPreset
        onSwapRequested: app.page = app.nextPage
        onPresetChanged: {
            // A Zoom route keeps the root destination parked at its Zoom Out
            // endpoint. Other presets leave the underlay static as before.
            if (preset === 0 && app.navigationStack.length > 0)
                rootPageMotion.prepareHidden()
            else
                rootPageMotion.showImmediately()
        }
    }

    // Surface player toasts in both render passes. The host composites lyricChrome
    // over the normal QML scene while the lyric page is open, so a root-only toast
    // exists but is hidden behind that pass.
    function showToast(message) {
        snack.show(message)
        lyricOverlay.showToast(message)
    }
    property string toastWatch: player.toast
    onToastWatchChanged: if (player.toast.length > 0) app.showToast(player.toast)

    // Chrome is absolute/anchor-positioned, NOT a ColumnLayout. The play clock
    // sets player.positionMs ~5x/s; each set bumps the engine change version and
    // forces a whole-tree settleLayout that frame (and on coinciding scroll
    // frames). Layout containers in the always-visible chrome re-ran their
    // measure/fill passes every one of those ticks; anchors keep it cheap.
    // Wide-screen navigation rail (left), shown in place of the bottom bar once the
    // window is wide enough; collapses to width 0 (and hides) on compact widths so
    // the content reclaims the full width. Expands to a labelled rail at ≥ 840.
    NavigationRail {
        id: rail
        anchors.left: parent.left
        anchors.leftMargin: settings.leftInset
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        visible: app.wide
        extended: app.expanded
        width: app.wide ? implicitWidth : 0
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        currentIndex: app.page
        model: app.navItems
        sectionLabel: "导航"
        onItemClicked: app.switchTo(index)

        // Rail header: the app mark in the top-left corner, which only the wide
        // layout has room for (the compact layout's top-left is the TopAppBar's
        // title). The logo slides from centred (collapsed rail) to left-aligned
        // beside the name (extended rail) on the same 200ms curve the rail's own
        // width animates with; the name itself just fades, so the two states
        // don't fight over the 80px collapsed width.
        //
        // The desktop custom title bar (TitleBar.qml, below) already draws this
        // same icon+"QPlayer" mark once topInset reserves space for it -- showRailBrand
        // hides the rail's own copy in that case so the two don't stack. Still need
        // implicitHeight: 64 + settings.topInset unconditionally so the nav items
        // themselves don't creep up under the title bar.
        property bool showRailBrand: !hostWindow.available

        header: Item {
            // 桌面端：标题栏已遮挡 topInset 区域，header 只需 topInset 即可
            // （0~topInset 被标题栏遮挡不可见，导航项从 topInset+12 开始）
            // 移动端：需要额外 64px 给 Logo
            implicitHeight: rail.showRailBrand ? (64 + settings.topInset) : settings.topInset

            Image {
                id: railLogo
                width: 32
                height: 32
                // topInset is the reserved system/custom-title-bar strip. Centre
                // the brand in the 64px rail header below it so native desktop
                // decorations cannot cover its top edge when topInset is 0.
                y: settings.topInset + (parent.height - settings.topInset - height) / 2
                x: app.expanded ? 24 : (parent.width - width) / 2
                Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                visible: rail.showRailBrand
                source: "app-icon.png"
                // Decode straight to ~2x the drawn size. Without this the 256px
                // source is resampled to 32 at draw time with plain bilinear
                // (SamplingMode.LINEAR), which at an 8:1 ratio aliases the disc's
                // grooves badly; sourceSize routes it through the loader's
                // mipmapped downscale instead. The artwork already carries its own
                // rounded corners, so no radius here — clipping them a second time
                // just re-aliases the edge.
                sourceSize.width: 64
                sourceSize.height: 64
            }
            Text {
                anchors.left: railLogo.right
                anchors.leftMargin: 12
                anchors.verticalCenter: railLogo.verticalCenter
                text: "QPlayer"
                opacity: (app.expanded && rail.showRailBrand) ? 1 : 0
                visible: opacity > 0.01
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleMedium.family
                font.pixelSize: Theme.typography.titleMedium.size
            }
        }

        // Brand mark below the header strip: the app icon + "QPlayer" wordmark.
        // On Windows the header strip itself sits behind the custom title bar
        // (which already draws the same mark), so showRailBrand is false there
        // and this copy shows instead — the rail still reads as the app. When
        // showRailBrand is true (mobile/edge-to-edge), the header's own logo
        // already covers it, so this hides (implicitHeight collapses to 0) to
        // avoid a second logo.
        headerActions: Item {
            implicitHeight: visible ? 56 : 0
            visible: !rail.showRailBrand

                Image {
                    id: actionsLogo
                    width: 32
                    height: 32
                    anchors.verticalCenter: parent.verticalCenter
                    // 与 rail header 的 logo 一致：扩展时左对齐，收起时居中，
                    // 而不是固定在居中偏左的位置。
                    x: app.expanded ? 24 : (parent.width - width) / 2
                    Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    source: "app-icon.png"
                    sourceSize.width: 64
                    sourceSize.height: 64
                }
                Text {
                    anchors.left: actionsLogo.right
                    anchors.leftMargin: 8
                    anchors.verticalCenter: actionsLogo.verticalCenter
                    text: "QPlayer"
                    opacity: app.expanded ? 1 : 0
                    visible: opacity > 0.01
                    Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.titleLarge.family
                    font.pixelSize: Theme.typography.titleLarge.size
                }
        }

        // Secondary destinations live at the bottom of the wide rail instead of
        // competing with page-level actions in the top bar. Labels fade with the
        // extended rail; the compact rail keeps the same icon targets.
        footer: Item {
            // Feature actions are contributed by plugins; the host only supplies
            // stable navigation placement and an isolated UI launcher.
            implicitHeight: 20 + app.footerActions().length * 48

            Rectangle {
                x: 12
                y: 0
                width: parent.width - 24
                height: 1
                color: Theme.color.outlineVariant
            }

            Repeater {
                model: app.footerActions()

                Item {
                    id: footerAction
                    x: 0
                    y: 10 + index * 48
                    width: parent.width
                    height: 48

                    Rectangle {
                        id: footerState
                        property color hoverColor: Theme.color.surfaceContainerHighest
                        x: app.expanded ? 12 : (parent.width - 48) / 2
                        y: 2
                        width: app.expanded ? parent.width - 24 : 48
                        height: 44
                        radius: 22
                        color: footerRipple.containsMouse
                               ? hoverColor
                               : Qt.rgba(hoverColor.r, hoverColor.g, hoverColor.b, 0)
                        Behavior on color { ColorAnimation { duration: 140 } }
                    }

                    Text {
                        x: app.expanded ? 28 : (parent.width - width) / 2
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.icon
                        font.family: Theme.iconFont.name
                        font.pixelSize: 22
                        color: Theme.color.onSurfaceVariantColor
                        Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    }

                    Text {
                        x: 64
                        anchors.verticalCenter: parent.verticalCenter
                        width: parent.width - 76
                        text: modelData.text
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.labelLarge.family
                        font.pixelSize: Theme.typography.labelLarge.size
                        elide: Text.ElideRight
                        opacity: app.expanded ? 1 : 0
                        visible: opacity > 0.01
                        Behavior on opacity { NumberAnimation { duration: 160 } }
                    }

                    Ripple {
                        id: footerRipple
                        x: footerState.x
                        y: footerState.y
                        width: footerState.width
                        height: footerState.height
                        clipRadius: footerState.radius
                        rippleColor: Theme.color.onSurfaceColor
                        onClicked: {
                            if (modelData.action === "download") {
                                player.refreshCachedSongs()
                                app.replacePage("cachedSongs", 0)
                            } else if (modelData.action === "plugin") {
                                player.requestPluginUi(modelData.pluginId,
                                                       modelData.contributionId)
                            } else if (modelData.action === "account") {
                                if (player.loggedIn) app.replacePage("account", 0)
                                else app.loginOpen = true
                            } else {
                                app.replacePage("settings", 0)
                            }
                        }
                    }
                }
            }
        }
    }

    TopAppBar {
        id: topBar
        anchors.top: parent.top
        anchors.topMargin: settings.topInset   // clear the status bar (edge-to-edge)
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        height: 64
        title: app.titles[app.page]
        showNavigationIcon: false

        IconButton {
            // This setting only exists on desktop hosts. Its presence, rather than
            // a responsive layout breakpoint, decides whether the action is shown.
            visible: settings.has("desktopLyricEnabled")
            type: "standard"
            icon: "subtitles"
            contentColor: settings.value("desktopLyricEnabled")
                          ? Theme.color.primary : Theme.color.onSurfaceVariantColor
            onClicked: settings.setValue("desktopLyricEnabled",
                                         !settings.value("desktopLyricEnabled"))
        }
        IconButton {
            type: "standard"
            icon: "queue_music"
            onClicked: app.replacePage("queue", 0)
        }
        IconButton {
            visible: !app.wide
            type: "standard"
            icon: "download"
            onClicked: {
                player.refreshCachedSongs()
                app.replacePage("cachedSongs", 0)
            }
        }
        Repeater {
            model: !app.wide ? app.playerPluginActions : []
            delegate: IconButton {
                type: "standard"
                icon: modelData.icon
                onClicked: player.requestPluginUi(modelData.pluginId, modelData.id)
            }
        }
        IconButton {
            visible: !app.wide
            type: "standard"
            icon: player.loggedIn ? "account_circle" : "login"
            onClicked: if (player.loggedIn) app.replacePage("account", 0); else app.loginOpen = true
        }
        IconButton {
            visible: !app.wide
            type: "standard"
            icon: "settings"
            onClicked: app.replacePage("settings", 0)
        }
    }

    // Content region. pageWrap clips root/route motion at its edges.
    Item {
        id: pageWrap
        anchors.top: topBar.bottom
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: mini.top
        clip: true

        Item {
            id: pageBody
            width: parent.width
            height: parent.height

            // Root destinations have their own transform layer. Route loaders are
            // siblings below, so the old root can Zoom Out while a new route Zooms
            // In without the route inheriting its underlay's transform.
            Item {
                id: rootPages
                anchors.fill: parent
                x: rootPageMotion.contentX
                y: rootPageMotion.contentY
                scale: rootPageMotion.contentScale
                opacity: rootPageMotion.contentOpacity

                // Pages stacked + toggled by visibility (was a StackLayout). The
                // engine doesn't recurse into an invisible child's subtree during
                // measure, so only the current page is laid out each frame.
                HomePage {
                    id: home
                    anchors.fill: parent
                    visible: app.page === 0
                    onOpenPlaylist: {
                        player.openMediaPlaylist("" + home.pendingPlaylist.id)
                        app.replacePage("detail", home.pendingPlaylist.id)
                    }
                }
                Loader {
                    anchors.fill: parent
                    active: app.searchLoaded
                    visible: app.page === 1
                    sourceComponent: Component { SearchPage {} }
                }
                Loader {
                    anchors.fill: parent
                    active: app.libraryLoaded
                    visible: app.page === 2
                    sourceComponent: Component {
                        LibraryPage {
                            id: libraryPage
                            onOpenPlaylist: {
                                player.openMediaPlaylist("" + libraryPage.pendingPlaylist.id)
                                app.replacePage("detail", libraryPage.pendingPlaylist.id)
                            }
                            onRequestLogin: app.loginOpen = true
                        }
                    }
                }
                Loader {
                    anchors.fill: parent
                    active: app.localLoaded
                    visible: app.page === 3
                    sourceComponent: Component { LocalPage {} }
                }
            }

            // The shared loader implements stack-aware z/visibility and the
            // forward/back transition contract once for every full-screen page.
            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "detail"
                active: app.detailLoaded
                sourceComponent: Component {
                    PlaylistDetailPage {
                        onHome: app.goHome()
                        onBack: app.popPage()
                    }
                }
            }

            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "artist"
                active: app.artistLoaded
                sourceComponent: Component {
                    ArtistDetailPage {
                        onHome: app.goHome()
                        onBack: app.popPage()
                    }
                }
            }

            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "album"
                active: app.albumLoaded
                sourceComponent: Component {
                    AlbumDetailPage {
                        onHome: app.goHome()
                        onBack: app.popPage()
                    }
                }
            }

            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "queue"
                active: app.queueLoaded
                sourceComponent: Component {
                    QueuePage {
                        onHome: app.goHome()
                        onBack: app.popPage()
                    }
                }
            }

            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "settings"
                active: app.settingsLoaded
                sourceComponent: Component {
                    SettingsPage {
                        onHome: app.goHome()
                        onBack: app.popPage()
                        onOpenDebugLog: app.showLog = true
                    }
                }
            }

            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "account"
                active: app.accountLoaded
                sourceComponent: Component {
                    AccountPage {
                        onHome: app.goHome()
                        onBack: app.popPage()
                    }
                }
            }

            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "pluginSettings"
                active: app.pluginSettingsLoaded
                sourceComponent: Component {
                    PluginSettingsPage {
                        pluginId: player.pluginSettingsId
                        onHome: app.goHome()
                        onBack: app.popPage()
                    }
                }
            }

            ManagedPageLoader {
                pageManager: app
                motion: rootPageMotion
                routeType: "cachedSongs"
                active: app.cacheListLoaded
                sourceComponent: Component {
                    CachedSongsDialog {
                        onHome: app.goHome()
                        onBack: app.popPage()
                    }
                }
            }

            SourceSetupPrompt {
                anchors.fill: parent
                visible: app.showSourceSetupPrompt
                z: 3000
            }
        }
    }

    MiniPlayer {
        id: mini
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: bottomNav.top
        height: 84
        onLyricsRequested: app.pushPage("lyrics", 0)
    }

    // Bottom navigation (compact). On wide layouts the rail replaces it, so collapse
    // it to height 0 + hidden; the mini player (anchored to bottomNav.top) then sits
    // flush at the bottom without a conditional anchor.
    BottomNav {
        id: bottomNav
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: parent.bottom
        visible: !app.wide
        // Nav content sits in the top 76; the extra height is background that fills
        // behind the gesture/navigation bar (edge-to-edge).
        height: app.wide ? 0 : (76 + settings.bottomInset)
        currentIndex: app.page
        items: app.navItems
        onNavigate: app.switchTo(bottomNav.pendingIndex)
    }

    // Lyric page chrome (title / wavy progress / transport), over the host-drawn
    // fluid backdrop + lyrics. Lyrics deliberately keep their own bottom-sheet
    // transition instead of following the configurable navigation-page preset.
    LyricOverlay {
        id: lyricOverlay
        objectName: "lyricChrome"   // host renders this subtree in its own pass, over the fluid
        property real transitionEase: {
            var s = player.lyricSlide
            return s * s * (3 - 2 * s)
        }
        x: settings.leftInset
        width: parent.width - settings.leftInset - settings.rightInset
        height: parent.height
        visible: player.lyricSlide > 0.001
        // Desktop hides its custom title bar while the lyric page is open (see the
        // TitleBar below), so the three title buttons sit flush at the very top.
        topPad: hostWindow.available ? 6 : settings.topInset + 6
        onCloseRequested: app.popPage()
        y: (1 - transitionEase) * height
    }

    LoginDialog {
        active: app.loginOpen
        onClosed: app.loginOpen = false
    }

    SongArtistsDialog { id: songArtistsDialog }

    // App-wide because onboarding can install a plugin before SettingsPage has
    // ever been instantiated.
    PluginDialogs { id: pluginDialogs }

    // New-version dialog: the host's startup check sets player.updateAvailable when a
    // newer GitHub release exists; the update button downloads the APK in-app (through
    // the mirror) and hands it to the system installer.
    Dialog {
        id: updateDialog
        title: "发现新版本"
        icon: "system_update"
        text: "新版本 " + player.updateVersion + " 现已发布"
        acceptText: "立即更新"
        rejectText: "稍后"
        onAccepted: player.startUpdateDownload()

        Flickable {
            width: parent.width
            height: Math.min(notesText.height, 260)
            contentHeight: notesText.height
            clip: true
            Text {
                id: notesText
                width: parent.width
                text: player.updateNotes
                color: Theme.color.onSurfaceVariantColor
                fontSize: 13
                wrapMode: Text.Wrap
            }
        }
    }

    Dialog {
        id: graphicsFallbackDialog
        title: "图形后端回退"
        icon: "warning"
        text: "Vulkan 图形后端初始化失败，QPlayer 已自动使用 OpenGL 继续运行，并已更新设置。"
        acceptText: "知道了"
        showRejectButton: false
        Component.onCompleted: {
            if (settings.graphicsFallbackNotice) graphicsFallbackDialog.open()
        }
    }

    Dialog {
        id: credentialNoticeDialog
        title: player.credentialNoticeType === 1
            ? "登录凭据保护已启用"
            : (player.credentialNoticeType === 2
                ? "系统密钥库不可用" : "无法读取登录凭据")
        icon: player.credentialNoticeType === 1 ? "verified_user" : "warning"
        text: player.credentialNoticeType === 1
            ? "您的登录凭据已加密，并由系统密钥库保护。"
            : (player.credentialNoticeType === 2
                ? "无法使用系统密钥库，登录凭据已回退到仅当前用户可读的本地密钥保护。此模式的安全性低于系统密钥库，请确保本机账户和文件权限安全。"
                : "系统密钥库未能及时返回解密密钥，可能尚未解锁。QPlayer 已中断凭据恢复以避免阻塞启动，现有密文和密钥均未被重置。请先解锁系统密钥库（Linux 上为 KWallet/Keyring）后重试；也可以清除旧凭据后重新登录并继续使用系统加密，或回退普通加密。")
        rejectText: "回退普通加密"
        rejectIcon: player.credentialNoticeType === 3 ? "warning" : ""
        showRejectButton: player.credentialNoticeType === 3
        neutralText: "重新登录并加密"
        showNeutralButton: player.credentialNoticeType === 3
        closeOnScrim: player.credentialNoticeType !== 3
        acceptText: player.credentialNoticeType === 3 ? "重试" : "知道了"
        onAccepted: {
            if (player.credentialNoticeType === 3) player.retryCredentialUnlock()
        }
        onRejected: {
            if (player.credentialNoticeType === 3) {
                fallbackConfirmOpenTimer.restart()
            }
        }
        onNeutral: {
            if (player.credentialNoticeType === 3) player.prepareEncryptedRelogin()
        }
    }

    Dialog {
        id: credentialReloginUnavailableDialog
        title: "系统密钥库仍不可用"
        icon: "warning"
        text: "QPlayer 无法在登录前访问系统密钥库，因此没有清除现有登录凭据，也没有进入登录界面。请先解锁系统密钥库（Linux 上为 KWallet/Keyring），返回后再重试。"
        acceptText: "返回"
        showRejectButton: false
        closeOnScrim: false
        onAccepted: credentialNoticeRestoreTimer.restart()
    }

    Dialog {
        id: credentialFallbackConfirmDialog
        title: "确认回退普通加密"
        icon: "warning"
        text: "系统密钥库当前无法解锁现有登录凭据。继续后，这份不可解密的登录凭据将被清除，QPlayer 会永久切换为仅当前用户可读的本地密钥保护；其安全性低于系统密钥库。"
        acceptText: "继续回退"
        rejectText: "取消"
        closeOnScrim: false
        onAccepted: {
            if (player.fallbackCredentialsToOwnerOnly()) {
                fallbackLoginOpenTimer.restart()
            }
        }
        onRejected: credentialNoticeRestoreTimer.restart()
    }

    // Dialog emits accepted/rejected before its 100 ms exit animation finishes.
    // Delay the next modal so two full-screen scrims never race for the same root.
    Timer {
        id: fallbackConfirmOpenTimer
        interval: 130
        repeat: false
        onTriggered: credentialFallbackConfirmDialog.open()
    }
    Timer {
        id: credentialNoticeRestoreTimer
        interval: 130
        repeat: false
        onTriggered: credentialNoticeDialog.open()
    }
    Timer {
        id: fallbackLoginOpenTimer
        interval: 130
        repeat: false
        onTriggered: app.loginOpen = true
    }
    Timer {
        id: encryptedReloginOpenTimer
        interval: 130
        repeat: false
        onTriggered: app.loginOpen = true
    }
    Timer {
        id: encryptedReloginUnavailableOpenTimer
        interval: 130
        repeat: false
        onTriggered: credentialReloginUnavailableDialog.open()
    }

    property real credentialReloginWatch: player.credentialReloginRevision
    onCredentialReloginWatchChanged: {
        if (player.credentialReloginRevision <= 0) return
        if (player.credentialReloginResult === 1) encryptedReloginOpenTimer.restart()
        else encryptedReloginUnavailableOpenTimer.restart()
    }

    property real credentialNoticeWatch: player.credentialNoticeRevision
    onCredentialNoticeWatchChanged: {
        if (player.credentialNoticeRevision > 0) credentialNoticeDialog.open()
    }

    property bool graphicsFallbackWatch: settings.graphicsFallbackNotice
    onGraphicsFallbackWatchChanged: {
        if (settings.graphicsFallbackNotice) graphicsFallbackDialog.open()
    }

    property bool updateWatch: player.updateAvailable
    onUpdateWatchChanged: if (player.updateAvailable) updateDialog.open()

    // In-app update download progress, driven by the host (-1 idle, 0..100, -2 fail).
    property int updateProgWatch: player.updateProgress
    onUpdateProgWatchChanged: if (player.updateProgress === -2) app.showToast("更新下载失败，请稍后重试")

    Rectangle {
        visible: player.updateProgress >= 0 && player.updateProgress < 100
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: parent.bottom
        height: 48 + settings.bottomInset
        color: Theme.color.surfaceContainerHigh
        z: 9000
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 14
            text: "正在下载更新… " + player.updateProgress + "%"
            color: Theme.color.onSurfaceColor
            fontSize: 14
        }
    }

    ToastStack {
        id: snack
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.leftMargin: 16
        anchors.rightMargin: settings.rightInset + 16
        z: 20000
    }

    // Windows-only custom title bar (see TitleBar.qml / WinFrameless.java /
    // WindowChrome.java). hostWindow is registered on EVERY platform (a real,
    // functional WindowChrome on Windows desktop, a no-op WindowChromeStub
    // everywhere else -- Android's QmlGLSurfaceView and DesktopWindow both
    // register it unconditionally) precisely so this component can gate purely
    // on hostWindow.available rather than the identifier's mere existence --
    // qml4j's compiler rejects an undeclared top-level identifier at compile
    // time, even inside a typeof guard on a branch that never runs, so
    // hostWindow being simply absent on some platforms is not an option here.
    // High z so its caption buttons stay click-priority-correct over any
    // current/future full-window overlay -- the shared Theme.color.surface
    // token underneath means z-order never affects visual seamlessness, only
    // click routing.
    TitleBar {
        // A cold-start qml4j quirk (same general class as the Tabs indicator's
        // documented cold-start settle issue) left this reserved top strip painted
        // as only a few stray px on the very first frames, indefinitely, regardless
        // of anchors vs plain x/y/width positioning -- reproduced down to a bare
        // colored Rectangle with none of this component's own logic. The real fix
        // is DesktopWindow.nudgeResizeOnce(), a one-time real WM_SIZE round trip
        // right after the first frame shows, which reliably un-sticks it.
        x: 0
        y: 0
        width: parent.width
        // The lyric page is fully immersive on desktop: the custom bar hides while
        // it's open so LyricOverlay's own three title buttons can sit flush at the
        // top (see LyricOverlay.topPad). Symmetric with LyricOverlay.visible.
        visible: hostWindow.available && !(player.lyricSlide > 0.001)
        height: settings.topInset
        z: 10000
    }

    // --- debug log overlay ---------------------------------------------
    Rectangle {
        visible: app.showLog
        anchors.fill: parent
        color: Theme.color.surfaceContainerHighest

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 8
                spacing: 4
                Text {
                    Layout.fillWidth: true
                    text: "日志"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }
                IconButton { type: "standard"; icon: "delete"; onClicked: player.clearLog() }
                IconButton { type: "standard"; icon: "close"; onClicked: app.showLog = false }
            }

            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.margins: 12
                clip: true
                contentHeight: logText.height
                Text {
                    id: logText
                    width: parent.width
                    text: player.logText
                    color: Theme.color.onSurfaceColor
                    fontSize: 12
                    wrapMode: Text.WrapAnywhere
                }
            }
        }
    }
}
