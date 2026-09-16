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
    // Start on whichever tab has something in it; local wins when both do, since
    // it is the one that works signed out.
    property bool showLocal: player.localPlaylistCount > 0 || !page.hasPlaylists

    // Card the delete confirmation is about. Captured when the menu action fires,
    // since the grid's pendingPlaylist moves on with the next card interaction.
    property var pendingRemoval

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TabRowWithContour {
            objectName: "libraryTabs"
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 8
            tabs: [i18n.t("playlist.local.title"), i18n.t("nav.library")]
            selectOnClick: false
            selectedTabIndex: page.showLocal ? 0 : 1
            onTabSelected: (index) => page.showLocal = (index === 0)
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            PlaylistGrid {
                id: localGrid
                anchors.fill: parent
                visible: page.showLocal
                list: page.showLocal ? player.localPlaylists : null
                onOpenPlaylist: {
                    page.pendingPlaylist = localGrid.pendingPlaylist
                    page.openLocalPlaylist()
                }
                onDeletePlaylistRequested: {
                    page.pendingRemoval = localGrid.pendingPlaylist
                    if (page.pendingRemoval) localDeleteDialog.open()
                }
            }

            PlaylistGrid {
                id: grid
                anchors.fill: parent
                visible: !page.showLocal && (player.loggedIn || page.hasPlaylists)
                list: !page.showLocal
                      ? ((player.sourceContentActive || page.hasPlaylists)
                         ? player.sourceMyPlaylists : player.myPlaylists)
                      : null
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
        onClicked: { nameField.text = ""; createDialog.open() }
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
        onAccepted: page.showLocal ? player.createLocalPlaylist(nameField.text)
                                   : player.createPlaylist(nameField.text)

        TextField {
            id: nameField
            anchors.left: parent.left
            anchors.right: parent.right
            type: "outlined"
            label: i18n.t("library.create.hint")
            onAccepted: { createDialog.accepted(); createDialog.close() }
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
