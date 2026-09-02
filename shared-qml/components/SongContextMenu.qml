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
    // Cached-songs list mode: the "缓存此歌曲" entry flips to "删除缓存" (right-click
    // on CachedSongsDialog rows), so you can drop a song's offline copy on disk.
    property bool inCacheList: false

    // Rebuild the model from the current song + the live playlist list, so it reflects
    // a playlist just created. Called by the row right before open().
    function rebuild() {
        var items = []
        var s = menu.song
        if (!s) { menu.model = items; return }
        // Local-source Tracks (filePath set, e.g. a local file sitting in LocalPage or
        // the live queue) have no provider identity at all — a much smaller menu, just
        // the custom-playlist toggle, keyed by path instead of a provider media ID.
        if (s.filePath) {
            if (player.isLocalInCustomPlaylist(s.filePath)) {
                items.push({ text: "移出播放列表", icon: "playlist_remove", action: menu._removeLocalCustomAction(s.filePath) })
            } else {
                items.push({ text: "加入播放列表", icon: "playlist_add", action: menu._addLocalCustomAction(s.filePath) })
            }
            menu.model = items
            return
        }
        // Search-result/playlist-track rows hand over a provider DTO (".mediaId");
        // queue and custom-playlist rows may hand over a compatibility Track instead.
        var songId = s.mediaId ? s.mediaId : (s.id !== undefined ? s.id : s.neteaseId)
        if (!songId) { menu.model = items; return }
        if (("" + songId).indexOf(":") >= 0) {
            var mediaArtistIds = s.artistIdsCsv || s.artistMediaId || ""
            var mediaArtistNames = s.artistNamesCsv || s.artist || ""
            if (mediaArtistIds)
                items.push({ text: "查看歌手", icon: "person",
                             action: menu._openArtistPickerAction(mediaArtistIds, mediaArtistNames) })
            if (player.loggedIn && player.sourcePlaylistMutationAvailable) {
                var sourceLists = player.sourceMyPlaylists
                var sourceSubs = []
                var sourceCount = sourceLists ? sourceLists.length : 0
                for (var spi = 0; spi < sourceCount; spi++) {
                    if (!sourceLists[spi].mutable) continue
                    sourceSubs.push(menu._addMediaItem(sourceLists[spi], "" + songId))
                }
                if (sourceSubs.length > 0)
                    items.push({ text: "添加到歌单", icon: "playlist_add", subItems: sourceSubs })
                if (menu.inOwnedPlaylist && player.openSourcePlaylistId !== "")
                    items.push({ text: "从此歌单移除", icon: "playlist_remove", action: menu._removeMediaPlaylistAction("" + songId) })
            }
            if (player.isMediaInCustomPlaylist("" + songId))
                items.push({ text: "移出播放列表", icon: "playlist_remove", action: menu._removeMediaCustomAction("" + songId) })
            else
                items.push({ text: "加入播放列表", icon: "playlist_add", action: menu._addMediaCustomAction("" + songId) })
            if (menu.inCacheList)
                items.push({ text: "删除缓存", icon: "delete", action: menu._removeMediaCacheAction("" + songId) })
            else
                items.push({ text: "缓存此歌曲", icon: "download", action: menu._cacheMediaAction("" + songId) })
            items.push({ text: "复制链接", icon: "link", action: menu._shareMediaAction("" + songId) })
            menu.model = items
            return
        }
        // Provider rows, SearchRow and Track all preserve the full credits.
        // Older persisted Tracks may only have the first artist id, which still
        // provides a useful single-artist direct path.
        var artistIds = s.artistIdsCsv || (s.artistId ? ("" + s.artistId) : "")
        var artistNames = s.artistNamesCsv || s.artist || ""
        if (artistIds) {
            items.push({ text: "查看歌手", icon: "person", action: menu._openArtistPickerAction(artistIds, artistNames) })
        }
        if (player.loggedIn) {
            var pls = player.myPlaylists
            var n = pls ? pls.length : 0
            var subs = []
            for (var i = 0; i < n; i++) {
                // Only playlists the user created — you can't add tracks to a
                // subscribed/collected one.
                if (!pls[i].owned) continue
                subs.push(menu._addItem(pls[i], songId))
            }
            if (subs.length > 0) {
                items.push({ text: "添加到歌单", icon: "playlist_add", subItems: subs })
            }
            if (menu.inOwnedPlaylist) {
                items.push({ text: "从此歌单移除", icon: "playlist_remove", action: menu._removeAction(songId) })
            }
        }
        // Custom "play later" list: local-only, works signed-out.
        if (player.isInCustomPlaylist(songId)) {
            items.push({ text: "移出播放列表", icon: "playlist_remove", action: menu._removeCustomAction(songId) })
        } else {
            items.push({ text: "加入播放列表", icon: "playlist_add", action: menu._addCustomAction(songId) })
        }
        // Cache the track's audio for offline replay when the provider permits it;
        // the bridge skips it with a toast if it is already cached.
        // In the cached-songs list this flips to "删除缓存" instead.
        if (menu.inCacheList) {
            items.push({ text: "删除缓存", icon: "delete", action: menu._removeCacheAction(songId) })
        } else {
            items.push({ text: "缓存此歌曲", icon: "download", action: menu._cacheAction(songId) })
        }
        // The source plugin owns share-link generation.
        items.push({ text: "复制链接", icon: "link", action: menu._copyAction(songId) })
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
    function _addMediaItem(pl, songId) {
        var pid = "" + pl.id
        return {
            text: pl.name, icon: "queue_music",
            action: function() { player.addMediaToPlaylist(pid, songId) }
        }
    }
    function _removeMediaPlaylistAction(songId) {
        return function() { player.removeMediaFromCurrentPlaylist(songId) }
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
    function _addMediaCustomAction(mediaId) {
        return function() { player.addMediaToCustomPlaylist(mediaId) }
    }
    function _removeMediaCustomAction(mediaId) {
        return function() { player.removeMediaFromCustomPlaylist(mediaId) }
    }
    function _copyMediaAction(mediaId) {
        return function() { player.copyMediaReference(mediaId) }
    }
    function _shareMediaAction(mediaId) {
        return function() { player.shareMedia(mediaId) }
    }
    function _cacheMediaAction(mediaId) {
        return function() { player.cacheMediaSong(mediaId) }
    }
    function _removeMediaCacheAction(mediaId) {
        return function() { player.removeMediaCache(mediaId) }
    }
    function _openMediaArtistAction(mediaId) {
        return function() { player.openMediaArtist(mediaId) }
    }
    function _addLocalCustomAction(filePath) {
        return function() { player.addLocalToCustomPlaylist(filePath) }
    }
    function _removeLocalCustomAction(filePath) {
        return function() { player.removeLocalFromCustomPlaylist(filePath) }
    }
    function _copyAction(songId) {
        return function() { player.copySongLink(songId) }
    }
    function _openArtistPickerAction(idsCsv, namesCsv) {
        return function() { player.openSongArtistPicker(idsCsv, namesCsv) }
    }
    function _cacheAction(songId) {
        return function() { player.cacheSong(songId) }
    }
    function _removeCacheAction(songId) {
        return function() { player.deleteCachedSong(songId) }
    }
}
