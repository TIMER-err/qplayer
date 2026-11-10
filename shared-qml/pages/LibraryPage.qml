import QtQuick
import miuix.Core
import design
import "."
import "../components"

// Library: the user's own local playlists, and the playlists their signed-in
// sources own. They are split into two tabs rather than merged, because they
// behave differently — a local playlist works signed out, mixes every source and
// can be exported, while a source playlist lives on that service.
//
// Layout: Main draws the large "我的" title above this page. The page itself is
// a toolbar row (pill tabs on the left, view toggle + sort on the right) over a
// playlist grid or list, with floating create/import actions at the bottom
// right. Everything is placed at explicit x/y (no nested layouts).
Item {
    id: page
    property real bottomContentInset: 0
    property var pendingPlaylist
    signal openPlaylist()
    signal openLocalPlaylist()
    signal requestLogin()

    readonly property real gutter: Ui.gutter(page.width)
    readonly property bool compact: page.width < Ui.compactMax

    // Playlists are aggregated across sources, so a signed-in secondary source
    // still fills this page while the primary one is signed out.
    property bool hasPlaylists: player.playlistCount > 0
    // 我的歌单 is the default: this page is primarily about the account's own
    // playlists, and local ones are the secondary, offline-capable tab.
    property bool showLocal: false

    // Card the delete confirmation is about. Captured when the menu action fires,
    // since the grid's pendingPlaylist moves on with the next card interaction.
    property var pendingRemoval

    // Sources the create dialog can target: logged in, and able to create playlists.
    // Primary is always first (see PlayerController.refreshSourceAccounts), which
    // keeps index 0 the same default the single-source flow always had.
    property var creatableSources: {
        var out = []
        var list = player.sourceAccounts
        for (var i = 0; i < list.length; i++) {
            var row = list[i]
            if (row.loggedIn && row.canCreatePlaylist) out.push(row)
        }
        return out
    }
    property int selectedSourceIndex: 0
    readonly property bool canCreate: page.showLocal || page.creatableSources.length > 0
    readonly property bool canImport: page.showLocal && player.playlistTransferAvailable
    // Whether the current tab has playlists to present; view toggle and sort
    // are pointless over an empty or signed-out state.
    readonly property bool tabHasContent: page.showLocal ? player.localPlaylistCount > 0
                                                         : (player.loggedIn || page.hasPlaylists)
    // Trailing scroll space so the last row can scroll clear of the floating
    // actions as well as the player bar.
    readonly property real fabClearance: page.canCreate ? Ui.space4 + 60 : 0

    function openCreateDialog() {
        nameField.text = ""
        page.selectedSourceIndex = 0
        createDialog.open()
    }

    // ---- Toolbar -------------------------------------------------------------

    Row {
        id: tabRow
        objectName: "libraryTabs"
        x: page.gutter
        y: Ui.space1
        height: Ui.pillHeight
        spacing: Ui.space2
        property var labels: [i18n.t("library.tab.mine"), i18n.t("playlist.local.title")]
        property var counts: [player.playlistCount, player.localPlaylistCount]
        property int selectedTabIndex: page.showLocal ? 1 : 0

        Repeater {
            model: tabRow.labels
            Rectangle {
                id: pill
                objectName: "libraryTab" + index
                readonly property bool active: index === tabRow.selectedTabIndex
                readonly property int count: tabRow.counts[index] || 0
                // Measured in the bold face so selecting never changes the width.
                width: Math.max(64, labelProbe.implicitWidth
                                + (countText.visible ? countText.implicitWidth + 6 : 0)
                                + 2 * Ui.space4)
                height: Ui.pillHeight
                radius: height / 2
                color: pill.active ? Ui.accentSoft
                                   : (pillArea.containsMouse ? Ui.surfacePressed : Ui.surfaceSunken)
                Behavior on color { ColorAnimation { duration: 120 } }

                Text {
                    id: labelProbe
                    text: modelData
                    opacity: 0
                    font.pixelSize: Ui.textCallout
                    font.weight: Ui.weightBold
                }
                Text {
                    id: label
                    x: Ui.space4 + (labelProbe.implicitWidth - implicitWidth) / 2
                    y: (pill.height - implicitHeight) / 2
                    text: modelData
                    color: pill.active ? Ui.accent : Ui.textPrimary
                    font.pixelSize: Ui.textCallout
                    font.weight: pill.active ? Ui.weightBold : Ui.weightRegular
                }
                Text {
                    id: countText
                    x: Ui.space4 + labelProbe.implicitWidth + 6
                    y: (pill.height - implicitHeight) / 2
                    visible: pill.count > 0
                    text: "" + pill.count
                    color: pill.active ? Ui.accent : Ui.textTertiary
                    opacity: pill.active ? 0.75 : 1
                    font.pixelSize: Ui.textCaption
                }
                MouseArea {
                    id: pillArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: page.showLocal = (index === 1)
                }
            }
        }
    }

    Row {
        id: toolbarActions
        x: page.width - width - Math.max(4, page.gutter - 12)
        y: tabRow.y + (tabRow.height - height) / 2
        height: Ui.iconButton
        spacing: 2

        // Import belongs to the local tab (it creates a local playlist from a
        // file). A toolbar action rather than a second FAB, so the floating
        // area keeps one clear primary action and never stacks over cards.
        IconButton {
            objectName: "libraryImportButton"
            visible: page.canImport
            type: "standard"
            icon: "file_download"
            onClicked: player.requestLocalPlaylistImport()
        }

        IconButton {
            id: viewToggle
            objectName: "libraryViewToggle"
            visible: page.tabHasContent
            type: "standard"
            icon: contentArea.listView ? "grid_view" : "view_list"
            onClicked: settings.setValue("libraryListView", !contentArea.listView)
        }
        // Sort only applies to 我的: local playlists have no "source" to group
        // by, and are always draggable without picking a mode.
        IconButton {
            id: sortButton
            objectName: "librarySortButton"
            visible: !page.showLocal && page.tabHasContent
            type: "standard"
            icon: "sort"
            onClicked: { sortMenu.rebuild(); sortMenu.open(sortButton, 0, sortButton.height) }
        }
    }

    // ---- Content ---------------------------------------------------------------

    Item {
        id: contentArea
        x: 0
        y: tabRow.y + tabRow.height + Ui.space2
        width: page.width
        height: Math.max(0, page.height - y)
        readonly property bool listView: settings.value("libraryListView") === true
        readonly property real cardSize: settings.value("libraryCardSize") || 200
        readonly property real trailingInset: page.bottomContentInset + page.fabClearance
        // Reuse visited presentations without building the unused one.
        property bool gridLoaded: false
        property bool listLoaded: false
        function ensureViewLoaded() {
            if (settings.value("libraryListView") === true) contentArea.listLoaded = true
            else contentArea.gridLoaded = true
        }
        onListViewChanged: ensureViewLoaded()
        Component.onCompleted: ensureViewLoaded()

        PlaylistGrid {
            id: localGrid
            anchors.fill: parent
            bottomContentInset: contentArea.trailingInset
            pad: page.gutter
            topPadding: Ui.space2
            pageActive: page.visible
            // enabled, not just visible: an inactive grid/list still sits at the
            // same anchors.fill geometry as whichever one IS shown; set it on the
            // view itself, since qml4j does not inherit enabled for input.
            enabled: visible
            visible: page.showLocal && !contentArea.listView
            list: page.showLocal && contentArea.gridLoaded ? player.localPlaylists : null
            minTile: contentArea.cardSize
            // Same as the local list view — the only order these have.
            reorderable: true
            onOpenPlaylist: {
                page.pendingPlaylist = localGrid.pendingPlaylist
                page.openLocalPlaylist()
            }
            onDeletePlaylistRequested: {
                page.pendingRemoval = localGrid.pendingPlaylist
                if (page.pendingRemoval) localDeleteDialog.open()
            }
            onMoveRequested: player.moveLocalPlaylistCard(localGrid.moveFrom, localGrid.moveTo)
        }

        VirtualPlaylistList {
            id: localList
            anchors.fill: parent
            bottomContentInset: contentArea.trailingInset
            rowInset: page.gutter
            enabled: visible
            visible: page.showLocal && contentArea.listView
            list: page.showLocal && contentArea.listLoaded ? player.localPlaylists : null
            // The only order a set of local playlists has — no mode to pick.
            reorderable: true
            onOpenPlaylist: {
                page.pendingPlaylist = localList.pendingPlaylist
                page.openLocalPlaylist()
            }
            onDeletePlaylistRequested: {
                page.pendingRemoval = localList.pendingPlaylist
                if (page.pendingRemoval) localDeleteDialog.open()
            }
            onMoveRequested: player.moveLocalPlaylistCard(localList.moveFrom, localList.moveTo)
        }

        PlaylistGrid {
            id: grid
            anchors.fill: parent
            bottomContentInset: contentArea.trailingInset
            pad: page.gutter
            topPadding: Ui.space2
            pageActive: page.visible
            enabled: visible
            visible: !page.showLocal && !contentArea.listView && (player.loggedIn || page.hasPlaylists)
            list: !page.showLocal && contentArea.gridLoaded
                  ? ((player.sourceContentActive || page.hasPlaylists)
                     ? player.sourceMyPlaylists : player.myPlaylists)
                  : null
            minTile: contentArea.cardSize
            // Same rule as the list view: only draggable in custom mode.
            reorderable: player.librarySortMode === "custom"
            onOpenPlaylist: { page.pendingPlaylist = grid.pendingPlaylist; page.openPlaylist() }
            // Deleting is destructive and confirms first; un-collecting is reversible
            // and matches the detail page's own bookmark button, which acts at once.
            onDeletePlaylistRequested: {
                page.pendingRemoval = grid.pendingPlaylist
                if (page.pendingRemoval) deleteDialog.open()
            }
            onUnsubscribePlaylistRequested: {
                var target = grid.pendingPlaylist
                if (target) player.setMediaPlaylistSubscribed("" + target.id, false)
            }
            onMoveRequested: player.moveMyPlaylist(grid.moveFrom, grid.moveTo)
        }

        VirtualPlaylistList {
            id: sourceList
            anchors.fill: parent
            bottomContentInset: contentArea.trailingInset
            rowInset: page.gutter
            enabled: visible
            visible: !page.showLocal && contentArea.listView && (player.loggedIn || page.hasPlaylists)
            list: !page.showLocal && contentArea.listLoaded
                  ? ((player.sourceContentActive || page.hasPlaylists)
                     ? player.sourceMyPlaylists : player.myPlaylists)
                  : null
            // Only draggable in custom mode — a drag in the source-grouped view
            // has nowhere stable to land.
            reorderable: player.librarySortMode === "custom"
            onOpenPlaylist: { page.pendingPlaylist = sourceList.pendingPlaylist; page.openPlaylist() }
            onDeletePlaylistRequested: {
                page.pendingRemoval = sourceList.pendingPlaylist
                if (page.pendingRemoval) deleteDialog.open()
            }
            onUnsubscribePlaylistRequested: {
                var target = sourceList.pendingPlaylist
                if (target) player.setMediaPlaylistSubscribed("" + target.id, false)
            }
            onMoveRequested: player.moveMyPlaylist(sourceList.moveFrom, sourceList.moveTo)
        }

        // Empty states are centred in the part of the panel the player bar does
        // not cover, so they read as centred on screen.
        EmptyState {
            objectName: "libraryLocalEmptyState"
            x: 0
            y: 0
            width: contentArea.width
            height: Math.max(0, contentArea.height - page.bottomContentInset)
            visible: page.showLocal && player.localPlaylistCount === 0
            icon: "queue_music"
            title: i18n.t("playlist.local.empty.title")
            message: i18n.t("playlist.local.empty.desc")
        }

        EmptyState {
            objectName: "librarySignInState"
            x: 0
            y: 0
            width: contentArea.width
            height: Math.max(0, contentArea.height - page.bottomContentInset)
            visible: !page.showLocal && !player.loggedIn && !page.hasPlaylists
            icon: "library_music"
            title: i18n.t("library.tab.mine")
            message: i18n.t("library.signInPrompt")
            actionText: i18n.t("library.signInButton")
            actionIcon: "login"
            onActionRequested: page.requestLogin()
        }
    }

    // 我的's sort mode. "Custom" is the only mode a drag can land in — see
    // reorderable on sourceList above — so picking either entry here also
    // decides whether the drag handles do anything.
    Menu {
        id: sortMenu
        outlined: true
        function rebuild() {
            var modes = [
                { key: "source", label: i18n.t("library.sort.source") },
                { key: "custom", label: i18n.t("library.sort.custom") }
            ]
            var items = []
            for (var i = 0; i < modes.length; i++) items.push(sortMenu._item(modes[i]))
            sortMenu.model = items
        }
        function _item(entry) {
            return {
                text: entry.label,
                icon: player.librarySortMode === entry.key ? "check" : "",
                action: sortMenu._apply(entry.key)
            }
        }
        function _apply(key) {
            return function() { player.setLibrarySortMode(key) }
        }
    }

    // ---- Floating action ------------------------------------------------------------
    // New playlist is the primary action: an extended FAB with a label where
    // there is room, a round one on compact widths.

    FAB {
        id: createFab
        objectName: "createPlaylistFab"
        x: page.width - width - page.gutter
        y: page.height - page.bottomContentInset - Ui.space4 - height
        width: implicitWidth
        height: implicitHeight
        visible: page.canCreate
        type: page.compact ? "standard" : "extended"
        text: i18n.t("library.create.title")
        icon: "add"
        containerColor: Ui.accent
        contentColor: Ui.onAccent
        onClicked: page.openCreateDialog()
    }

    // ---- Dialogs -----------------------------------------------------------------

    Dialog {
        id: createDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "playlist_add"
        title: page.showLocal ? i18n.t("playlist.local.create.title")
                              : i18n.t("library.create.title")
        acceptText: i18n.t("library.create.accept")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            if (page.showLocal) { player.createLocalPlaylist(nameField.text); return }
            var sources = page.creatableSources
            if (sources.length === 0) return
            var idx = Math.max(0, Math.min(page.selectedSourceIndex, sources.length - 1))
            player.createPlaylist(nameField.text, sources[idx].providerId)
        }

        Column {
            width: parent.width
            spacing: Ui.space3

            // Only shown once there is an actual choice to make: a single logged-in,
            // playlist-capable source keeps the plain one-field dialog.
            Text {
                width: parent.width
                visible: sourcePicker.visible
                text: i18n.t("library.create.sourceLabel")
                color: Ui.textSecondary
                font.pixelSize: Ui.textCaption
            }
            TabRowWithContour {
                id: sourcePicker
                width: parent.width
                visible: !page.showLocal && page.creatableSources.length > 1
                tabs: {
                    var out = []
                    var sources = page.creatableSources
                    for (var i = 0; i < sources.length; i++) out.push(sources[i].sourceName)
                    return out
                }
                equalWidth: false
                selectedTabIndex: page.selectedSourceIndex
                onTabSelected: (index) => page.selectedSourceIndex = index
            }

            TextField {
                id: nameField
                overlayField: true
                width: parent.width
                type: "outlined"
                label: i18n.t("library.create.hint")
                onAccepted: { createDialog.accepted(); createDialog.close() }
            }
        }
    }

    Dialog {
        id: deleteDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "delete"
        title: i18n.t("playlist.delete.title")
        text: i18n.t("playlist.delete.body",
                     page.pendingRemoval ? page.pendingRemoval.name : "")
        acceptText: i18n.t("common.delete")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            var target = page.pendingRemoval
            if (!target) return
            // Provider-qualified ids carry their source; a bare number is the
            // legacy built-in netease id (same split the copy-link action makes).
            var pid = "" + target.id
            if (pid.indexOf(":") >= 0) player.deleteMediaPlaylist(pid)
            else player.deletePlaylist(target.id)
        }
    }

    Dialog {
        id: localDeleteDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "delete"
        title: i18n.t("playlist.delete.title")
        text: i18n.t("playlist.local.delete.body",
                     page.pendingRemoval ? page.pendingRemoval.name : "")
        acceptText: i18n.t("common.delete")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            var target = page.pendingRemoval
            if (target) player.deleteLocalPlaylist("" + target.id)
        }
    }
}
