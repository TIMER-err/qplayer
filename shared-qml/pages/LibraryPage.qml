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

    PlaylistGrid {
        id: grid
        anchors.fill: parent
        visible: player.loggedIn || page.hasPlaylists
        list: (player.sourceContentActive || page.hasPlaylists)
              ? player.sourceMyPlaylists : player.myPlaylists
        onOpenPlaylist: { page.pendingPlaylist = grid.pendingPlaylist; page.openPlaylist() }
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
