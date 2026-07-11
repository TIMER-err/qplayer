import QtQuick
import md3.Core
import "."

// Long-press context menu for a song row. "添加到歌单" fans the user's own playlists
// out as a submenu; "从此歌单移除" appears only inside a playlist the user owns. Every
// action routes through the global `player` bridge, so the row only feeds this the
// song object + a little context — no cross-file signal plumbing. Built lazily (one
// per row, via a Loader) so idle lists don't pay for a menu subtree per track.
Menu {
    id: menu

    property var song: null
    property bool inOwnedPlaylist: false

    // Rebuild the model from the current song + the live playlist list, so it reflects
    // a playlist just created. Called by the row right before open().
    function rebuild() {
        var items = []
        var s = menu.song
        if (!s) { menu.model = items; return }
        if (player.loggedIn) {
            var pls = player.myPlaylists
            var n = pls ? pls.length : 0
            var subs = []
            for (var i = 0; i < n; i++) {
                // Only playlists the user created — you can't add tracks to a
                // subscribed/collected one.
                if (!pls[i].owned) continue
                subs.push(menu._addItem(pls[i], s.id))
            }
            if (subs.length > 0) {
                items.push({ text: "添加到歌单", icon: "playlist_add", subItems: subs })
            }
            if (menu.inOwnedPlaylist) {
                items.push({ text: "从此歌单移除", icon: "playlist_remove", action: menu._removeAction(s.id) })
            }
        }
        // Custom "play later" list: local-only, works signed-out.
        if (player.isInCustomPlaylist(s.id)) {
            items.push({ text: "移出播放列表", icon: "playlist_remove", action: menu._removeCustomAction(s.id) })
        } else {
            items.push({ text: "加入播放列表", icon: "playlist_add", action: menu._addCustomAction(s.id) })
        }
        // Copy link works signed-out too — any netease song has a shareable URL.
        items.push({ text: "复制链接", icon: "link", action: menu._copyAction(s.id) })
        menu.model = items
    }

    // Factory helpers give each closure a fresh scope, sidestepping the for-loop
    // variable-capture trap without an IIFE.
    function _addItem(pl, songId) {
        var pid = pl.id
        return {
            text: pl.name, icon: "queue_music",
            action: function() { player.addToPlaylist(pid, songId) }
        }
    }
    function _removeAction(songId) {
        return function() { player.removeFromCurrentPlaylist(songId) }
    }
    function _addCustomAction(songId) {
        return function() { player.addToCustomPlaylist(songId) }
    }
    function _removeCustomAction(songId) {
        return function() { player.removeFromCustomPlaylist(songId) }
    }
    function _copyAction(songId) {
        return function() { player.copySongLink(songId) }
    }
}
