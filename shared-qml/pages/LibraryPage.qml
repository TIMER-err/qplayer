import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// Library: the signed-in user's playlists. Tapping one opens its detail. Prompts to
// log in when signed out.
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()
    signal requestLogin()

    // Playlists are aggregated across sources, so a signed-in secondary source
    // still fills this page while the primary one is signed out.
    property bool hasPlaylists: player.playlistCount > 0

    // Card the delete confirmation is about. Captured when the menu action fires,
    // since the grid's pendingPlaylist moves on with the next card interaction.
    property var pendingRemoval

    PlaylistGrid {
        id: grid
        anchors.fill: parent
        visible: player.loggedIn || page.hasPlaylists
        list: (player.sourceContentActive || page.hasPlaylists)
              ? player.sourceMyPlaylists : player.myPlaylists
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

    // New-playlist entry point.
    FAB {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: 16
        anchors.bottomMargin: 16
        visible: player.loggedIn && (!player.sourceContentActive
                                     || player.sourcePlaylistMutationAvailable)
        type: "standard"
        icon: "add"
        onClicked: { nameField.text = ""; createDialog.open() }
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: createDialog
        icon: "playlist_add"
        title: i18n.t("library.create.title")
        acceptText: i18n.t("library.create.accept")
        rejectText: i18n.t("common.cancel")
        onAccepted: player.createPlaylist(nameField.text)

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

    EmptyState {
        anchors.centerIn: parent
        visible: !player.loggedIn && !page.hasPlaylists
        icon: "library_music"
        title: i18n.t("nav.library")
        message: i18n.t("library.signInPrompt")
        actionText: i18n.t("library.signInButton")
        onActionRequested: page.requestLogin()
    }
}
