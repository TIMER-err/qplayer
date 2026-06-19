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
    property real tile: (width - 2 * pad - gap) / 2
    property real cardH: tile + 52

    clip: true
    contentWidth: width
    contentHeight: Math.ceil(count / 2) * (cardH + gap) + 2 * pad

    Item {
        width: grid.width
        height: grid.contentHeight
        // Cards sit at fixed x/y from their index and never reflow; skip re-measuring
        // them on unrelated version bumps (the play clock) while box + count hold.
        cachedLayout: true

        Repeater {
            model: grid.list
            PlaylistCard {
                tile: grid.tile
                x: grid.pad + (index % 2) * (grid.tile + grid.gap)
                y: grid.pad + Math.floor(index / 2) * (grid.cardH + grid.gap)
                name: modelData.name
                count: modelData.trackCount
                coverUrl: modelData.coverUrl
                coverThumbPath: modelData.coverThumbPath || ""
                onClicked: { grid.pendingPlaylist = modelData; grid.openPlaylist() }
            }
        }
    }
}
