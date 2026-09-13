import QtQuick
import miuix.Core
import "."

// Context actions that are valid for playlist cards on both home and library.
// The model is populated only while opening, keeping a grid of cards cheap while
// idle and ensuring every closure captures the current playlist id.
Menu {
    id: menu

    outlined: true
    property var playlistId: 0
    signal openRequested()

    function rebuild() {
        var pid = menu.playlistId
        if (!pid) {
            menu.model = []
            return
        }
        menu.model = [
            { text: i18n.t("menu.playNow"), icon: "play_arrow", action: menu._playAction(pid) },
            { text: i18n.t("menu.openPlaylist"), icon: "queue_music", action: menu._openAction() },
            { type: "separator" },
            { text: i18n.t("menu.copyLink"), icon: "link", action: menu._copyAction(pid) }
        ]
    }

    function _playAction(pid) {
        return function() { player.playMediaPlaylist("" + pid) }
    }

    function _openAction() {
        return function() { menu.openRequested() }
    }

    function _copyAction(pid) {
        return function() {
            if (("" + pid).indexOf(":") >= 0) player.shareMedia("" + pid)
            else player.copyPlaylistLink(pid)
        }
    }
}
