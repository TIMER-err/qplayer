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
    // Straight from the playlist DTO, and mutually exclusive in practice: a
    // playlist you own can be deleted, one you merely follow can be un-collected.
    // Both stay false for anonymous/recommended cards, which then keep the plain
    // three-entry menu.
    property bool deletable: false
    property bool subscribed: false
    signal openRequested()
    // Raised rather than acted on here: deleting needs a confirmation the page
    // owns, and a Dialog per card would instantiate one for every grid tile.
    signal deleteRequested()
    signal unsubscribeRequested()

    function rebuild() {
        var pid = menu.playlistId
        if (!pid) {
            menu.model = []
            return
        }
        var items = [
            { text: i18n.t("menu.playNow"), icon: "play_arrow", action: menu._playAction(pid) },
            { text: i18n.t("menu.openPlaylist"), icon: "queue_music", action: menu._openAction() },
            { type: "separator" },
            { text: i18n.t("menu.copyLink"), icon: "link", action: menu._copyAction(pid) }
        ]
        if (menu.subscribed) {
            items.push({ text: i18n.t("menu.unsubscribePlaylist"), icon: "bookmark_remove",
                         action: menu._unsubscribeAction() })
        }
        if (menu.deletable) {
            items.push({ text: i18n.t("menu.deletePlaylist"), icon: "delete",
                         action: menu._deleteAction() })
        }
        menu.model = items
    }

    function _playAction(pid) {
        return function() { player.playMediaPlaylist("" + pid) }
    }

    function _openAction() {
        return function() { menu.openRequested() }
    }

    function _deleteAction() {
        return function() { menu.deleteRequested() }
    }

    function _unsubscribeAction() {
        return function() { menu.unsubscribeRequested() }
    }

    function _copyAction(pid) {
        return function() {
            if (("" + pid).indexOf(":") >= 0) player.shareMedia("" + pid)
            else player.copyPlaylistLink(pid)
        }
    }
}
