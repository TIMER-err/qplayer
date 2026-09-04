import QtQuick
import md3.Core
import "."

// Two-column playlist grid via absolute positioning inside a Flickable — the
// only layout primitive that behaves in qml4j here (GridLayout/Flow collapsed or
// thrashed when nested in a Column/Flickable). Cards get a fixed `tile` width and
// explicit x/y from their index.
Flickable {
    id: grid

    property var list
    property real gap: 12
    property real pad: 12
    property var pendingPlaylist
    signal openPlaylist()

    property int count: list ? list.length : 0
    // Responsive column count: keep each card at least ~200dp wide, so a phone shows
    // 2, a tablet/medium window 3, and a wide desktop window 4+. Width-driven, so it
    // adapts on both desktop and Android (landscape / large screens).
    property real minTile: 200
    property int cols: Math.max(2, Math.floor((width - 2 * pad + gap) / (minTile + gap)))
    property real tile: (width - 2 * pad - (cols - 1) * gap) / cols
    property real cardH: tile + 72
    property int cardRowH: Math.max(1, Math.round(cardH + gap))
    property int rowWindow: {
        var rows = Math.ceil(count / Math.max(1, cols))
        var vis = Math.ceil(height / cardRowH) + 3
        return Math.min(rows, Math.max(0, vis))
    }
    property int firstRow: {
        var f = Math.floor((contentY - pad) / cardRowH) - 1
        var maxF = Math.max(0, Math.ceil(count / Math.max(1, cols)) - rowWindow)
        if (f > maxF) f = maxF
        if (f < 0) f = 0
        return f
    }
    property int firstCard: firstRow * cols
    property int cardWindow: rowWindow * cols

    clip: true
    contentWidth: width
    contentHeight: Math.ceil(count / cols) * (cardH + gap) + 2 * pad

    Item {
        width: grid.width
        height: grid.contentHeight
        // Cards sit at fixed x/y from their index and never reflow; skip re-measuring
        // them on unrelated version bumps (the play clock) while box + count hold.
        cachedLayout: true

        Repeater {
            model: grid.list
            windowStart: grid.firstCard
            windowCount: grid.cardWindow
            PlaylistCard {
                playlistId: modelData.id
                tile: grid.tile
                x: grid.pad + (index % grid.cols) * (grid.tile + grid.gap)
                y: grid.pad + Math.floor(index / grid.cols) * (grid.cardH + grid.gap)
                name: modelData.name
                count: modelData.trackCount
                coverUrl: modelData.coverUrl
                coverThumbPath: modelData.coverThumbPath || ""
                onClicked: { grid.pendingPlaylist = modelData; grid.openPlaylist() }
            }
        }
    }
}
