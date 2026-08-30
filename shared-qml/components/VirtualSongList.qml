import QtQuick
import md3.Core
import "."

// Virtualized song list. Only the rows near the viewport are instantiated: the
// Repeater windows the model to [first, first+window), giving each delegate its
// GLOBAL index so a row positioned by `y: index*rowH` still lands at its absolute
// content offset. Scrolling changes contentY (a paint-only translate); only when
// it crosses a row boundary does `first` change, and because `window` is constant
// the Repeater slides its existing delegates in place (rewriting each one's index
// + modelData) rather than rebuilding. So a list of any length — a several-
// thousand-track local library or playlist — costs ~`window` live delegates, not
// one SongRow per track (which used to OOM the heap on large libraries).
//
// This replaces the earlier "build every row, paint-cull off-screen" approach: that
// kept the whole list's delegates alive at once, which was smooth to scroll (no
// per-shift relayout) but did not bound memory. Windowing bounds memory; the
// per-boundary relayout it reintroduces is absorbed by cachedLayout on the content
// item (only the ~window moved rows re-measure; the rest of the tree is cached).
Flickable {
    id: view

    property var list
    property bool isLocal: false
    // Decoupled from isLocal: a list can use the local title/artist field mapping
    // (isLocal) without actually being the live queue (e.g. the custom-playlist tab),
    // so "now playing" highlighting needs its own opt-out for those.
    property bool highlightCurrent: true
    // player.index is a position in whatever queue is currently loaded, not in
    // THIS list -- for a list that isn't guaranteed to BE the live queue (e.g.
    // LocalPage's full library view, always visible regardless of what's actually
    // playing), matching by index alone can coincidentally light up an unrelated
    // row. Set true to match by modelData.filePath against player.currentFilePath
    // instead -- correct for any local-file list, live queue or not.
    property bool highlightByFilePath: false
    property bool removable: false
    // Every real song row gets the same right-click/long-press menu. Callers can
    // still turn it off for a deliberately read-only list; ownedPlaylist unlocks
    // "从此歌单移除" inside a playlist the user owns.
    property bool songMenu: true
    // Unified/mixed lists can decide eligibility per model row (SearchRow exposes
    // menuEnabled). Homogeneous lists keep the old all-rows behavior by default.
    property bool menuEligibilityFromModel: false
    property bool ownedPlaylist: false
    // Rows belong to the cached-songs list: with songMenu on, the row menu's
    // "缓存此歌曲" entry becomes "删除缓存" (see SongContextMenu.inCacheList).
    property bool cacheList: false
    // Shows SongRow's offline "cached, plays without network" badge for rows whose
    // modelData.cachedOffline is true. Off by default so this stays a no-op for
    // every list except a playlist detail page that's actually in an offline state
    // (see PlaylistDetailPage's player.playlistOffline binding) — not meaningful,
    // and would just clutter every row, during normal online browsing.
    property bool showOfflineBadge: false
    property int rowH: 64
    property int activatedIndex: -1
    property int removeIndex: -1
    // Optional incremental-data hook. SearchPage enables this so reaching the
    // tail asks the controller for another API page without coupling this generic
    // virtual list to a specific data source.
    property bool loadMoreEnabled: false
    property int loadMoreThresholdRows: 6
    signal activated()
    signal removeRequested()
    signal loadMoreRequested()

    property int count: list ? list.length : 0

    // Live-delegate window: viewport height in rows plus a buffer above and below.
    // Constant once `height` settles (it does not depend on contentY), so a scroll
    // that only slides the window keeps the Repeater's in-place update fast path.
    property int buffer: 6
    property int window: Math.min(count, Math.ceil(height / rowH) + 2 * buffer + 1)
    // Global index of the topmost live row, clamped so the window never runs past
    // either end (and stays full at the tail, pinned to count-window).
    property int first: {
        var f = Math.floor(contentY / rowH) - buffer;
        var maxFirst = count - window;
        if (f > maxFirst) f = maxFirst;
        if (f < 0) f = 0;
        return f;
    }

    clip: true
    contentWidth: width
    contentHeight: count * rowH

    function requestMoreIfNeeded() {
        if (!loadMoreEnabled || contentHeight <= 0) return
        if (contentY + height >= contentHeight - loadMoreThresholdRows * rowH)
            loadMoreRequested()
    }

    onContentYChanged: requestMoreIfNeeded()
    onContentHeightChanged: requestMoreIfNeeded()
    onHeightChanged: requestMoreIfNeeded()

    Item {
        width: view.width
        height: view.contentHeight
        // The windowed rows sit at fixed y = index*rowH; only the ~window rows that
        // slide on a boundary cross re-measure, so cache the rest (incl. the 5 Hz
        // play clock's version bump) instead of re-measuring the content each frame.
        cachedLayout: true

        Repeater {
            model: view.list
            windowStart: view.first
            windowCount: view.window
            SongRow {
                // `index` is the GLOBAL row index (the Repeater windows internally).
                width: view.width
                y: index * view.rowH
                rowTitle: view.isLocal ? modelData.title : modelData.name
                rowArtist: modelData.artist
                rowArtistId: modelData.artistMediaId || modelData.artistId || 0
                rowArtistIdsCsv: modelData.artistIdsCsv || ""
                rowArtistNamesCsv: modelData.artistNamesCsv || ""
                coverThumbPath: modelData.coverThumbPath || ""
                // Only present on SearchPage.qml's unified list (SearchRow.kindLabel);
                // every other model shape leaves this "" so no tag renders.
                tag: modelData.kindLabel || ""
                lazyLoad: true
                flickContentY: view.contentY
                flickHeight: view.height
                highlighted: view.isLocal && view.highlightCurrent && (view.highlightByFilePath
                    ? (player.currentFilePath !== "" && modelData.filePath === player.currentFilePath)
                    : index === player.index)
                offlineReady: view.showOfflineBadge && !!modelData.cachedOffline
                removable: view.removable
                song: view.songMenu && (!view.menuEligibilityFromModel || !!modelData.menuEnabled)
                      ? modelData : null
                menuEnabled: view.songMenu
                             && (!view.menuEligibilityFromModel || !!modelData.menuEnabled)
                inOwnedPlaylist: view.ownedPlaylist
                inCacheList: view.cacheList
                onActivated: { view.activatedIndex = index; view.activated() }
                onRemoveRequested: { view.removeIndex = index; view.removeRequested() }
            }
        }
    }
}
