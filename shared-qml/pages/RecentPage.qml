import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// Recent: the active source plugin's listening history (signed in).
Item {
    id: page
    signal requestLogin()

    VirtualSongList {
        id: recent
        anchors.fill: parent
        visible: player.loggedIn
        // Same guard as LocalPage/QueuePage: this page is always in the tree, so a
        // bare bind keeps a SongRow per history entry alive while Recent is hidden.
        list: page.visible ? (player.sourceContentActive
                              ? player.sourceRecentSongs : player.recentSongs) : null
        onActivated: player.playRecentSong(recent.activatedIndex)
    }

    EmptyState {
        anchors.centerIn: parent
        visible: !player.loggedIn
        icon: "history"
        title: i18n.t("nav.recent")
        message: i18n.t("recent.signInPrompt")
        actionText: i18n.t("recent.signInButton")
        onActionRequested: page.requestLogin()
    }
}
