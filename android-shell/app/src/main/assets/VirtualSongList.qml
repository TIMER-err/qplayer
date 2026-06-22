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
    property bool removable: false
    property bool addable: false
    property int rowH: 64
    property int activatedIndex: -1
    property int removeIndex: -1
    property int addIndex: -1
    signal activated()
    signal removeRequested()
    signal addRequested()

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
                coverThumbPath: modelData.coverThumbPath || ""
                lazyLoad: true
                flickContentY: view.contentY
                flickHeight: view.height
                highlighted: view.isLocal && index === player.index
                removable: view.removable
                addable: view.addable
                onActivated: { view.activatedIndex = index; view.activated() }
                onRemoveRequested: { view.removeIndex = index; view.removeRequested() }
                onAddRequested: { view.addIndex = index; view.addRequested() }
            }
        }
    }
}
