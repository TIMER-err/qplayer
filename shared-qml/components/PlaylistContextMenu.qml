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
            { text: i18n.t("menu.openPlaylist"), icon: "queue_music", action: menu._openAction() }
        ]
        // Following pulls this playlist's songs into a local one and keeps them in
        // sync. Only source playlists can be followed — a local playlist is
        // already the destination, and following one into itself is meaningless.
        if (("" + pid).indexOf("local:playlist:") !== 0 && ("" + pid).indexOf(":") >= 0) {
            items.push({ text: i18n.t("playlist.local.addPlaylistTo"), icon: "library_add",
                         subItems: menu._followSubItems("" + pid) })
        }
        items.push({ type: "separator" })
        items.push({ text: i18n.t("menu.copyLink"), icon: "link", action: menu._copyAction(pid) })
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

    function _followSubItems(sourceId) {
        var subs = []
        var lists = player.localPlaylists
        var n = lists ? lists.length : 0
        for (var i = 0; i < n; i++) subs.push(menu._followItem(sourceId, lists[i]))
        if (n > 0) subs.push({ type: "separator" })
        subs.push({ text: i18n.t("playlist.local.newPlaylist"), icon: "playlist_add",
                    action: menu._followInNewAction(sourceId) })
        return subs
    }

    function _followItem(sourceId, pl) {
        var pid = "" + pl.id
        var followed = player.isSourcePlaylistFollowed(pid, sourceId)
        return {
            text: pl.name,
            icon: followed ? "check" : "queue_music",
            action: followed ? menu._unfollowAction(pid, sourceId)
                             : menu._followAction(pid, sourceId)
        }
    }

    function _followAction(pid, sourceId) {
        return function() { player.followSourcePlaylist(pid, sourceId) }
    }

    function _unfollowAction(pid, sourceId) {
        return function() { player.unfollowSourcePlaylist(pid, sourceId) }
    }

    function _followInNewAction(sourceId) {
        return function() { player.followSourcePlaylistInNewLocalPlaylist(sourceId) }
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
