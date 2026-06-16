import QtQuick
import md3.Core
import "."

// Full-list song view. Every row exists with a constant y (index * rowH) and
// binds to its modelData once, so scrolling changes ONLY contentY — which the
// Flickable applies as a paint-only translate (no changeVersion bump, hence no
// relayout). The renderer quick-rejects rows outside the viewport at paint time,
// so the hundreds of off-screen rows cost ~nothing to draw.
//
// This replaced a windowed/recycling pool: recycling rewrote each delegate's
// index/title/artist whenever the window shifted (every ~rowH px of scroll),
// and every one of those writes bumped the engine's global change version,
// which forced a full-tree relayout (incl. the MiniPlayer/BottomNav Layouts)
// on that frame. That per-shift relayout was the playlist-scroll stutter —
// HomePage stayed smooth precisely because its rows never change on scroll.
Flickable {
    id: view

    property var list
    property bool isLocal: false
    property bool removable: false
    property int rowH: 64
    property int activatedIndex: -1
    property int removeIndex: -1
    signal activated()
    signal removeRequested()

    property int count: list ? list.length : 0

    clip: true
    contentWidth: width
    contentHeight: count * rowH

    Item {
        width: view.width
        height: view.contentHeight
        // Rows sit at fixed y = index*rowH and never reflow, so the layout pass can
        // skip re-measuring them while this item's box and row count are unchanged.
        // Without it, the 5 Hz play clock's version bump re-measured every row of a
        // long list each tick (a stutter that scaled with list length).
        cachedLayout: true

        Repeater {
            model: view.list
            SongRow {
                width: view.width
                y: index * view.rowH
                rowTitle: view.isLocal ? modelData.title : modelData.name
                rowArtist: modelData.artist
                coverThumbPath: modelData.coverThumbPath || ""
                highlighted: view.isLocal && index === player.index
                removable: view.removable
                onActivated: { view.activatedIndex = index; view.activated() }
                onRemoveRequested: { view.removeIndex = index; view.removeRequested() }
            }
        }
    }
}
