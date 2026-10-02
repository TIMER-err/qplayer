import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// Library: the user's own local playlists, and the playlists their signed-in
// sources own. They are split into two tabs rather than merged, because they
// behave differently — a local playlist works signed out, mixes every source and
// can be exported, while a source playlist lives on that service.
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()
    signal openLocalPlaylist()
    signal requestLogin()

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

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        Item {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 8
            Layout.preferredHeight: 45

            TabRowWithContour {
                objectName: "libraryTabs"
                anchors.left: parent.left
                anchors.right: sortButton.left
                anchors.rightMargin: sortButton.visible ? 8 : 0
                height: parent.height
                tabs: [i18n.t("library.tab.mine"), i18n.t("playlist.local.title")]
                selectOnClick: false
                selectedTabIndex: page.showLocal ? 1 : 0
                onTabSelected: (index) => page.showLocal = (index === 1)
            }

            // Sort only applies to 我的: local playlists have no "source" to group
            // by, and are always draggable in list view without picking a mode.
            IconButton {
                id: sortButton
                objectName: "librarySortButton"
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: !page.showLocal
                type: "standard"
                icon: "sort"
                onClicked: { sortMenu.rebuild(); sortMenu.open(sortButton, 0, sortButton.height) }
            }
        }

        Item {
            id: contentArea
            Layout.fillWidth: true
            Layout.fillHeight: true
            readonly property bool listView: settings.value("libraryListView") === true
            readonly property real cardSize: settings.value("libraryCardSize") || 200

            PlaylistGrid {
                id: localGrid
                anchors.fill: parent
                // enabled, not just visible: an inactive grid/list still sits at the
                // same anchors.fill geometry as whichever one IS shown, and qml4j does
                // not skip an invisible item's children during hit-testing — without
                // this, a right-click landing on one of this grid's (unseen) cards
                // opens ITS plain context menu instead of the visible list row's.
                enabled: visible
                visible: page.showLocal && !contentArea.listView
                list: page.showLocal ? player.localPlaylists : null
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
                enabled: visible
                visible: page.showLocal && contentArea.listView
                list: page.showLocal ? player.localPlaylists : null
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
                enabled: visible
                visible: !page.showLocal && !contentArea.listView && (player.loggedIn || page.hasPlaylists)
                list: !page.showLocal
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
                enabled: visible
                visible: !page.showLocal && contentArea.listView && (player.loggedIn || page.hasPlaylists)
                list: !page.showLocal
                      ? ((player.sourceContentActive || page.hasPlaylists)
                         ? player.sourceMyPlaylists : player.myPlaylists)
                      : null
                // Only draggable in custom mode — same rule as the local song sort
                // menu: a drag in the source-grouped view has nowhere stable to land.
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

            EmptyState {
                anchors.centerIn: parent
                visible: page.showLocal && player.localPlaylistCount === 0
                icon: "queue_music"
                title: i18n.t("playlist.local.empty.title")
                message: i18n.t("playlist.local.empty.desc")
            }

            EmptyState {
                anchors.centerIn: parent
                visible: !page.showLocal && !player.loggedIn && !page.hasPlaylists
                icon: "library_music"
                title: i18n.t("nav.library")
                message: i18n.t("library.signInPrompt")
                actionText: i18n.t("library.signInButton")
                onActionRequested: page.requestLogin()
            }
        }
    }

    // 我的's sort mode. "Custom" is the only mode a drag can land in — see
    // reorderable on sourceList above — so picking either entry here also
    // decides whether the list view's drag handles do anything.
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

    // Import sits next to "new" on the local tab: both create a playlist.
    FAB {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: 16
        anchors.bottomMargin: 88
        visible: page.showLocal && player.playlistTransferAvailable
        type: "standard"
        icon: "file_upload"
        onClicked: player.requestLocalPlaylistImport()
    }

    // New-playlist entry point. A local playlist needs no account; a source one
    // needs both a session and a source that can create them.
    FAB {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: 16
        anchors.bottomMargin: 16
        visible: page.showLocal
                 || (player.loggedIn && (!player.sourceContentActive
                                         || player.sourcePlaylistMutationAvailable))
        type: "standard"
        icon: "add"
        onClicked: { nameField.text = ""; page.selectedSourceIndex = 0; createDialog.open() }
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: createDialog
        icon: "playlist_add"
        title: page.showLocal ? i18n.t("playlist.local.create.title")
                              : i18n.t("library.create.title")
        acceptText: i18n.t("library.create.accept")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            if (page.showLocal) { player.createLocalPlaylist(nameField.text); return }
            var sources = page.creatableSources
            var targetProvider = ""
            if (sources.length > 0) {
                var idx = Math.max(0, Math.min(page.selectedSourceIndex, sources.length - 1))
                targetProvider = sources[idx].providerId
            }
            player.createPlaylist(nameField.text, targetProvider)
        }

        Column {
            width: parent.width
            spacing: 12

            // Only shown once there is an actual choice to make: a single logged-in,
            // playlist-capable source keeps the old one-field dialog unchanged.
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
                width: parent.width
                type: "outlined"
                label: i18n.t("library.create.hint")
                onAccepted: { createDialog.accepted(); createDialog.close() }
            }
        }
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: deleteDialog
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
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        id: localDeleteDialog
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
