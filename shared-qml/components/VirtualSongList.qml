import QtQuick
import miuix.Core
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
    objectName: "virtualSongList"

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
    // "remove from playlist" inside a playlist the user owns.
    property bool songMenu: true
    // Unified/mixed lists can decide eligibility per model row (SearchRow exposes
    // menuEnabled). Homogeneous lists keep the old all-rows behavior by default.
    property bool menuEligibilityFromModel: false
    property bool ownedPlaylist: false
    // Rows belong to the cached-songs list: with songMenu on, the row menu's
    // the cache entry becomes "remove cache" (see SongContextMenu.inCacheList).
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
    // Drag-to-reorder. The model is NOT touched while the finger is down: the
    // carried row lifts out of the list and follows the cursor, and the rows it
    // passes slide aside to preview the gap it would land in. Exactly one
    // moveRequested fires, on release, followed by reorderCommitted (where a
    // caller should persist).
    //
    // The earlier version applied the move on every row crossed. That read as
    // stiff — each crossing snapped the row to the next slot with no motion in
    // between — and on a long playlist it was genuinely slow, because applying a
    // move re-publishes the whole track list through the bridge and every
    // published row is rebuilt into a fresh JS array. One gesture across fifty
    // rows meant fifty of those.
    property bool reorderable: false
    property int moveFrom: -1
    property int moveTo: -1
    signal moveRequested()
    signal reorderCommitted()
    // Where the carried row started, in list positions. -1 when idle; stays put
    // for the whole gesture, since the model does not change under it.
    property int _dragFrom: -1
    // Where it would land if released now.
    property int _dropIndex: -1
    // Top of the carried row in content coordinates — the cursor position minus
    // wherever inside the row the grip was taken.
    property real _dragFloatY: 0
    property real _dragGrabOffset: 0
    // Finger position within the viewport during a drag, kept so the auto-scroll
    // tick can recompute the target row while the finger itself is still.
    property real _dragViewportY: 0
    // Pixels per tick the auto-scroll is currently applying; 0 = not scrolling.
    property real _autoScrollStep: 0
    /** How deep into the top/bottom edge a drag has to reach before the list
     *  starts scrolling itself, capped so a short list still has a neutral middle. */
    property real autoScrollEdge: Math.min(64, view.height / 4)
    readonly property var _dragRow: view._dragFrom >= 0 && view.list
                                    && view._dragFrom < view.count
                                    ? view.list[view._dragFrom] : null

    /** Turn a content-space cursor position into the slot the carried row would
     *  drop into. Shared by the drag itself and the auto-scroll tick. */
    function _applyDragTarget(contentPos) {
        if (view._dragFrom < 0 || view.count <= 0) return
        var maxY = Math.max(0, (view.count - 1) * view.rowH)
        view._dragFloatY = Math.max(0, Math.min(maxY, contentPos - view._dragGrabOffset))
        // Measure from the carried row's middle, so the gap opens when the row
        // has actually covered its neighbour rather than when its top edge grazes it.
        var target = Math.floor((view._dragFloatY + view.rowH / 2) / view.rowH)
        if (target < 0) target = 0
        if (target > view.count - 1) target = view.count - 1
        view._dropIndex = target
    }

    /** End the gesture, applying the single move it adds up to. */
    function _endDrag() {
        var from = view._dragFrom
        var to = view._dropIndex
        view._dragFrom = -1
        view._dropIndex = -1
        view._autoScrollStep = 0
        if (from < 0) return
        if (to >= 0 && to !== from) {
            view.moveFrom = from
            view.moveTo = to
            view.moveRequested()
        }
        view.reorderCommitted()
    }

    /** Dragging against either edge scrolls the list, so a row can be moved
     *  further than one screenful without letting go. Speed ramps with depth so
     *  a fingertip just inside the edge creeps instead of lurching. */
    function _updateAutoScroll(viewportPos) {
        // When something else owns the scrolling (PullToRefresh), it is not ours
        // to drive — leave it alone rather than fighting it.
        if (view.scrollViewport) { view._autoScrollStep = 0; return }
        var edge = view.autoScrollEdge
        if (edge <= 0) { view._autoScrollStep = 0; return }
        if (viewportPos < edge) {
            view._autoScrollStep = -view._scrollSpeed(edge - viewportPos, edge)
        } else if (viewportPos > view.height - edge) {
            view._autoScrollStep = view._scrollSpeed(viewportPos - (view.height - edge), edge)
        } else {
            view._autoScrollStep = 0
        }
    }

    function _scrollSpeed(depth, edge) {
        var ramp = Math.max(0, Math.min(1, depth / edge))
        return 2 + ramp * 14
    }

    Timer {
        id: autoScrollTimer
        objectName: "virtualSongListAutoScroll"
        interval: 16
        repeat: true
        running: view.reorderable && view._dragFrom >= 0 && view._autoScrollStep !== 0
        onTriggered: {
            var maxY = Math.max(0, view.contentHeight - view.height)
            var next = Math.max(0, Math.min(maxY, view.contentY + view._autoScrollStep))
            if (next === view.contentY) return
            view.contentY = next
            // The finger has not moved, but what is under it has: re-derive the
            // target from the same viewport position against the new offset.
            view._applyDragTarget(view._dragViewportY + next)
        }
    }
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
    property int buffer: 3
    // A PullToRefresh can own scrolling while this list only supplies windowed rows.
    property var scrollViewport: null
    readonly property real viewportHeight: scrollViewport ? scrollViewport.height : height
    readonly property real viewportY: scrollViewport ? scrollViewport.scrollOffset : contentY
    interactive: !scrollViewport
    property int window: Math.min(count, Math.ceil(Math.max(0, viewportHeight) / Math.max(1, rowH)) + 2 * buffer + 1)
    // Global index of the topmost live row, clamped so the window never runs past
    // either end (and stays full at the tail, pinned to count-window).
    property int first: {
        var f = Math.floor(viewportY / rowH) - buffer;
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
        if (viewportY + viewportHeight >= contentHeight - loadMoreThresholdRows * rowH)
            loadMoreRequested()
    }

    onContentYChanged: requestMoreIfNeeded()
    onViewportYChanged: requestMoreIfNeeded()
    onContentHeightChanged: {
        contentY = Math.max(0, Math.min(contentY, Math.max(0, contentHeight - height)))
        requestMoreIfNeeded()
    }
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
                objectName: "virtualSongRow"
                height: view.rowH
                width: view.width
                // The gap preview is added here rather than animated on `y` itself:
                // `y` also jumps when the window recycles this delegate onto another
                // index, and that jump must stay instant. Dropped the moment the
                // gesture ends so the settled row does not glide in from the gap.
                y: index * view.rowH + (view._dragFrom >= 0 ? reorderShift : 0)
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
                flickContentY: view.viewportY
                flickHeight: view.viewportHeight
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
                reorderable: view.reorderable
                // The carried row is drawn once, by the floating copy below, so
                // its slot in the list simply empties out.
                visible: view._dragFrom !== index
                // Slide aside to preview the gap. Read straight off the view's
                // properties (not via a helper function) so the engine records
                // them all as dependencies of this binding.
                reorderShift: {
                    if (view._dragFrom < 0 || view._dropIndex < 0) return 0
                    if (view._dragFrom < view._dropIndex) {
                        return (index > view._dragFrom && index <= view._dropIndex)
                               ? -view.rowH : 0
                    }
                    if (view._dragFrom > view._dropIndex) {
                        return (index >= view._dropIndex && index < view._dragFrom)
                               ? view.rowH : 0
                    }
                    return 0
                }
                onReorderPressed: {
                    view._dragFrom = index
                    view._dropIndex = index
                    view._dragGrabOffset = reorderGrabOffset
                    view._dragFloatY = index * view.rowH
                    view._dragViewportY = reorderContentY - view.viewportY
                }
                onReorderDragged: {
                    // A press always precedes this, but a delegate recycled onto
                    // another index mid-gesture must not re-seat the drag.
                    if (view._dragFrom < 0) return
                    view._dragViewportY = reorderContentY - view.viewportY
                    view._updateAutoScroll(view._dragViewportY)
                    view._applyDragTarget(reorderContentY)
                }
                onReorderReleased: view._endDrag()
            }
        }

        // The carried row, lifted out of the list. A separate item rather than the
        // delegate itself: auto-scrolling can carry a row far past the live window,
        // and a recycled delegate would take the row being dragged with it.
        SongRow {
            objectName: "reorderFloatingRow"
            visible: view._dragFrom >= 0 && view._dragRow !== null
            z: 5
            width: view.width
            height: view.rowH
            y: view._dragFloatY
            dragging: true
            reorderable: true
            // A preview, not a target: no context menu, and the live gesture already
            // owns the pointer. `removable` is mirrored even though the button does
            // nothing here, because it decides where the grip sits — otherwise the
            // grip would jump sideways the moment the row lifts.
            song: null
            menuEnabled: false
            removable: view.removable
            highlighted: false
            rowTitle: view._dragRow ? (view.isLocal ? view._dragRow.title : view._dragRow.name) : ""
            rowArtist: view._dragRow ? view._dragRow.artist : ""
            coverThumbPath: view._dragRow ? (view._dragRow.coverThumbPath || "") : ""
            tag: view._dragRow ? (view._dragRow.kindLabel || "") : ""
            offlineReady: view.showOfflineBadge
                          && !!(view._dragRow && view._dragRow.cachedOffline)
        }
    }
}
