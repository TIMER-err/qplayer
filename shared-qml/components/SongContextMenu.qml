import QtQuick
import miuix.Core
import "."

// Long-press context menu for a song row. "add to playlist" fans the user's own
// playlists out as a submenu; "remove from playlist" appears only inside a playlist
// the user owns. Every
// action routes through the global `player` bridge, so the row only feeds this the
// song object + a little context — no cross-file signal plumbing. Built lazily (one
// per row, via a Loader) so idle lists don't pay for a menu subtree per track.
Menu {
    id: menu

    property var song: null
    property bool inOwnedPlaylist: false
    // Cached-songs list mode: the cache entry flips to "remove cache" (right-click
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
            items.push({ text: i18n.t("playlist.local.addTo"), icon: "playlist_add",
                         subItems: menu._localSubItems("local", s.filePath) })
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
                items.push({ text: i18n.t("menu.viewArtist"), icon: "person",
                             action: menu._openArtistPickerAction(mediaArtistIds, mediaArtistNames) })
            // The library mixes every signed-in source, but a playlist only accepts songs
            // from its own source: offering the others would just fail server-side.
            // Media ids are "<source>:<kind>:<native>", so the prefix is the filter.
            var sourcePrefix = ("" + songId).split(":")[0] + ":"
            var sourceLists = player.sourceMyPlaylists
            var sourceSubs = []
            var sourceCount = sourceLists ? sourceLists.length : 0
            for (var spi = 0; spi < sourceCount; spi++) {
                if (!sourceLists[spi].mutable) continue
                if (("" + sourceLists[spi].id).indexOf(sourcePrefix) !== 0) continue
                sourceSubs.push(menu._addMediaItem(sourceLists[spi], "" + songId))
            }
            if (sourceSubs.length > 0)
                items.push({ text: i18n.t("menu.addToPlaylist"), icon: "playlist_add", subItems: sourceSubs })
            if (menu.inOwnedPlaylist && player.openSourcePlaylistId !== ""
                    && ("" + player.openSourcePlaylistId).indexOf(sourcePrefix) === 0)
                items.push({ text: i18n.t("menu.removeFromPlaylist"), icon: "playlist_remove", action: menu._removeMediaPlaylistAction("" + songId) })
            items.push({ text: i18n.t("playlist.local.addTo"), icon: "playlist_add",
                         subItems: menu._localSubItems("media", "" + songId) })
            if (menu.inCacheList)
                items.push({ text: i18n.t("menu.removeCache"), icon: "delete", action: menu._removeMediaCacheAction("" + songId) })
            else
                items.push({ text: i18n.t("menu.cacheSong"), icon: "download", action: menu._cacheMediaAction("" + songId) })
            items.push({ text: i18n.t("menu.copyLink"), icon: "link", action: menu._shareMediaAction("" + songId) })
            menu.model = items
            return
        }
        // Provider rows, SearchRow and Track all preserve the full credits.
        // Older persisted Tracks may only have the first artist id, which still
        // provides a useful single-artist direct path.
        var artistIds = s.artistIdsCsv || (s.artistId ? ("" + s.artistId) : "")
        var artistNames = s.artistNamesCsv || s.artist || ""
        if (artistIds) {
            items.push({ text: i18n.t("menu.viewArtist"), icon: "person", action: menu._openArtistPickerAction(artistIds, artistNames) })
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
                items.push({ text: i18n.t("menu.addToPlaylist"), icon: "playlist_add", subItems: subs })
            }
            if (menu.inOwnedPlaylist) {
                items.push({ text: i18n.t("menu.removeFromPlaylist"), icon: "playlist_remove", action: menu._removeAction(songId) })
            }
        }
        // Local playlists: mixed-source, work signed-out.
        items.push({ text: i18n.t("playlist.local.addTo"), icon: "playlist_add",
                     subItems: menu._localSubItems("song", songId) })
        // Cache the track's audio for offline replay when the provider permits it;
        // the bridge skips it with a toast if it is already cached.
        // In the cached-songs list this flips to "remove cache" instead.
        if (menu.inCacheList) {
            items.push({ text: i18n.t("menu.removeCache"), icon: "delete", action: menu._removeCacheAction(songId) })
        } else {
            items.push({ text: i18n.t("menu.cacheSong"), icon: "download", action: menu._cacheAction(songId) })
        }
        // The source plugin owns share-link generation.
        items.push({ text: i18n.t("menu.copyLink"), icon: "link", action: menu._copyAction(songId) })
        menu.model = items
    }

    // Local playlists accept a song by any of the three identities a row can
    // carry, so one submenu builder serves all three branches above. `kind` says
    // which one `key` is: "media" (provider media id), "song" (legacy numeric
    // netease id) or "local" (file path).
    function _localSubItems(kind, key) {
        var subs = []
        var lists = player.localPlaylists
        var n = lists ? lists.length : 0
        for (var i = 0; i < n; i++) subs.push(menu._localItem(kind, key, lists[i]))
        if (n > 0) subs.push({ type: "separator" })
        subs.push({ text: i18n.t("playlist.local.newPlaylist"), icon: "playlist_add",
                    action: menu._addToNewLocalAction(kind, key) })
        return subs
    }

    // A playlist that already holds the song shows a check and removes it again,
    // so the submenu is a toggle per playlist rather than a one-way add.
    function _localItem(kind, key, pl) {
        var pid = "" + pl.id
        var has = kind === "media" ? player.isInLocalPlaylist(pid, key)
                : kind === "song" ? player.isSongInLocalPlaylist(pid, key)
                : player.isLocalFileInLocalPlaylist(pid, key)
        return {
            text: pl.name,
            icon: has ? "check" : "queue_music",
            action: has ? menu._removeFromLocalAction(kind, key, pid)
                        : menu._addToLocalAction(kind, key, pid)
        }
    }

    function _addToLocalAction(kind, key, pid) {
        return function() {
            if (kind === "media") player.addMediaToLocalPlaylist(pid, key)
            else if (kind === "song") player.addSongToLocalPlaylist(pid, key)
            else player.addLocalFileToLocalPlaylist(pid, key)
        }
    }

    function _removeFromLocalAction(kind, key, pid) {
        return function() {
            if (kind === "media") player.removeMediaFromLocalPlaylist(pid, key)
            else if (kind === "song") player.removeSongFromLocalPlaylist(pid, key)
            else player.removeLocalFileFromLocalPlaylist(pid, key)
        }
    }

    function _addToNewLocalAction(kind, key) {
        return function() {
            if (kind === "media") player.addMediaToNewLocalPlaylist(key)
            else if (kind === "song") player.addSongToNewLocalPlaylist(key)
            else player.addLocalFileToNewLocalPlaylist(key)
        }
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
