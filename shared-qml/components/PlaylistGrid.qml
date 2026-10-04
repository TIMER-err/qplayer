import QtQuick
import miuix.Core
import "."

// Two-column playlist grid via absolute positioning inside a Flickable — the
// only layout primitive that behaves in qml4j here (GridLayout/Flow collapsed or
// thrashed when nested in a Column/Flickable). Cards get a fixed `tile` width and
// explicit x/y from their index.
//
// Drag-to-reorder: unlike VirtualPlaylistList's 1D row shift, a grid move can
// span both axes (last card of a row moving to the head of the next one), so
// the "gap preview" is done by re-deriving every affected card's on-screen index
// (see _effectiveIndex on the delegate) rather than shifting by a fixed pixel
// offset — index -> (row, col) -> (x, y) already existed for the static layout,
// this just also applies it to a shifted index while a drag is live.
Flickable {
    id: grid
    objectName: "virtualPlaylistGrid"

    property var list
    property real gap: 16
    property real pad: width >= 840 ? 28 : 16
    property var pendingPlaylist
    signal openPlaylist()
    // Card-menu actions. Like openPlaylist they publish the target through
    // pendingPlaylist, so the page handling them knows which card it was without
    // the grid needing to own a confirmation dialog.
    signal deletePlaylistRequested()
    signal unsubscribePlaylistRequested()

    property bool reorderable: false
    property int moveFrom: -1
    property int moveTo: -1
    signal moveRequested()
    signal reorderCommitted()

    property int _dragFrom: -1
    property int _dropIndex: -1
    property real _dragFloatX: 0
    property real _dragFloatY: 0
    property real _dragGrabOffsetX: 0
    property real _dragGrabOffsetY: 0
    // Pointer position relative to the viewport (not content), kept so the
    // auto-scroll tick can recompute the drop target while the finger itself
    // has not moved — same trick VirtualPlaylistList uses for its 1D case.
    property real _dragViewportY: 0
    property real _autoScrollStep: 0
    property real autoScrollEdge: Math.min(64, grid.height / 4)
    readonly property var _dragRow: grid._dragFrom >= 0 && grid.list
                                    && grid._dragFrom < grid.count
                                    ? grid.list[grid._dragFrom] : null
    // Watchdog: see VirtualSongList's identical timer — something outside the
    // gesture (an OS screenshot tool, alt-tab, ...) can swallow the mouse-up
    // before onReorderReleased/onCanceled fires, leaving the carried card
    // stuck until an app restart. Auto-release once no drag activity has
    // landed for a while, since a real drag keeps producing onReorderDragged
    // every frame the finger moves.
    property real _dragLastActivityMs: 0
    Timer {
        interval: 1000
        repeat: true
        running: grid._dragFrom >= 0
        onTriggered: {
            if (Date.now() - grid._dragLastActivityMs > 15000) grid._endDrag()
        }
    }

    function _indexToX(index) {
        return grid.pad + (index % grid.cols) * (grid.tile + grid.gap)
    }
    function _indexToY(index) {
        return grid.pad + Math.floor(index / grid.cols) * (grid.cardH + grid.gap)
    }
    function _indexAt(cx, cy) {
        var col = Math.floor((cx - grid.pad) / (grid.tile + grid.gap))
        var row = Math.floor((cy - grid.pad) / grid.cardRowH)
        col = Math.max(0, Math.min(grid.cols - 1, col))
        if (row < 0) row = 0
        var index = row * grid.cols + col
        return Math.max(0, Math.min(grid.count - 1, index))
    }

    /** Turn a content-space pointer position into the slot the carried card
     *  would drop into, from the card's own centre (not the raw pointer) — it
     *  has to actually cover a neighbour's slot, not merely graze it. */
    function _applyDragTarget(pointerContentX, pointerContentY) {
        if (grid._dragFrom < 0 || grid.count <= 0) return
        var maxX = Math.max(0, grid.width - grid.tile)
        var maxY = Math.max(0, grid.contentHeight - grid.cardH)
        grid._dragFloatX = Math.max(0, Math.min(maxX, pointerContentX - grid._dragGrabOffsetX))
        grid._dragFloatY = Math.max(0, Math.min(maxY, pointerContentY - grid._dragGrabOffsetY))
        var centerX = grid._dragFloatX + grid.tile / 2
        var centerY = grid._dragFloatY + grid.cardH / 2
        grid._dropIndex = grid._indexAt(centerX, centerY)
    }

    function _endDrag() {
        var from = grid._dragFrom
        var to = grid._dropIndex
        grid._dragFrom = -1
        grid._dropIndex = -1
        grid._autoScrollStep = 0
        if (from < 0) return
        if (to >= 0 && to !== from) {
            grid.moveFrom = from
            grid.moveTo = to
            grid.moveRequested()
        }
        grid.reorderCommitted()
    }

    function _updateAutoScroll(viewportPos) {
        var edge = grid.autoScrollEdge
        if (edge <= 0) { grid._autoScrollStep = 0; return }
        if (viewportPos < edge) {
            grid._autoScrollStep = -grid._scrollSpeed(edge - viewportPos, edge)
        } else if (viewportPos > grid.height - edge) {
            grid._autoScrollStep = grid._scrollSpeed(viewportPos - (grid.height - edge), edge)
        } else {
            grid._autoScrollStep = 0
        }
    }

    function _scrollSpeed(depth, edge) {
        var ramp = Math.max(0, Math.min(1, depth / edge))
        return 2 + ramp * 14
    }

    Timer {
        interval: 16
        repeat: true
        running: grid.reorderable && grid._dragFrom >= 0 && grid._autoScrollStep !== 0
        onTriggered: {
            var maxY = Math.max(0, grid.contentHeight - grid.height)
            var next = Math.max(0, Math.min(maxY, grid.contentY + grid._autoScrollStep))
            if (next === grid.contentY) return
            grid.contentY = next
            grid._applyDragTarget(grid._dragFloatX + grid._dragGrabOffsetX, grid._dragViewportY + next)
        }
    }

    property int count: list ? list.length : 0
    // Responsive column count: keep each card at least ~200dp wide, so a phone shows
    // 2, a tablet/medium window 3, and a wide desktop window 4+. Width-driven, so it
    // adapts on both desktop and Android (landscape / large screens).
    property real minTile: 200
    property int cols: Math.max(2, Math.floor((width - 2 * pad + gap) / (minTile + gap)))
    property real tile: (width - 2 * pad - (cols - 1) * gap) / cols
    // Keep in sync with PlaylistCard.textSlot (tile/200 of the 72dp caption).
    property real cardH: tile + Math.max(52, Math.round(72 * tile / 200))
    property real cardRowH: Math.max(1, cardH + gap)
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
    contentHeight: count > 0 ? Math.ceil(count / cols) * cardRowH - gap + 2 * pad : 0
    onContentHeightChanged: contentY = Math.max(0, Math.min(contentY, Math.max(0, contentHeight - height)))

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
                objectName: "virtualPlaylistCard"
                playlistId: modelData.id
                tile: grid.tile
                // While a drag is live, every card between the lifted slot and the
                // current drop target renders one slot closer to where it started —
                // exactly the "gap preview" VirtualPlaylistList does with a pixel
                // shift, just expressed as an index (see the file header comment).
                property int _effectiveIndex: {
                    if (grid._dragFrom < 0 || grid._dropIndex < 0 || index === grid._dragFrom) {
                        return index
                    }
                    if (grid._dragFrom < grid._dropIndex) {
                        return (index > grid._dragFrom && index <= grid._dropIndex) ? index - 1 : index
                    }
                    if (grid._dragFrom > grid._dropIndex) {
                        return (index >= grid._dropIndex && index < grid._dragFrom) ? index + 1 : index
                    }
                    return index
                }
                x: grid._indexToX(_effectiveIndex)
                y: grid._indexToY(_effectiveIndex)
                Behavior on x {
                    enabled: grid._dragFrom >= 0
                    NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
                }
                Behavior on y {
                    enabled: grid._dragFrom >= 0
                    NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
                }
                // The carried card is drawn once, by the floating copy below, so
                // its slot in the grid simply empties out.
                visible: grid._dragFrom !== index
                reorderable: grid.reorderable
                showOverflow: grid.reorderable
                name: modelData.name
                count: modelData.trackCount
                playCount: modelData.playCount || 0
                coverUrl: modelData.coverUrl
                coverThumbPath: modelData.coverThumbPath || ""
                sourceName: modelData.sourceName || ""
                deletable: !!modelData.deletable
                subscribed: !!modelData.subscribed
                onClicked: { grid.pendingPlaylist = modelData; grid.openPlaylist() }
                onDeleteRequested: {
                    grid.pendingPlaylist = modelData
                    grid.deletePlaylistRequested()
                }
                onUnsubscribeRequested: {
                    grid.pendingPlaylist = modelData
                    grid.unsubscribePlaylistRequested()
                }
                onReorderPressed: {
                    grid._dragFrom = index
                    grid._dropIndex = index
                    grid._dragGrabOffsetX = reorderGrabOffsetX
                    grid._dragGrabOffsetY = reorderGrabOffsetY
                    grid._dragFloatX = grid._indexToX(index)
                    grid._dragFloatY = grid._indexToY(index)
                    grid._dragViewportY = reorderContentY - grid.contentY
                    grid._dragLastActivityMs = Date.now()
                }
                onReorderDragged: {
                    if (grid._dragFrom < 0) return
                    grid._dragLastActivityMs = Date.now()
                    grid._dragViewportY = reorderContentY - grid.contentY
                    grid._updateAutoScroll(grid._dragViewportY)
                    grid._applyDragTarget(reorderContentX, reorderContentY)
                }
                onReorderReleased: grid._endDrag()
            }
        }

        // The carried card, lifted out of the grid. A separate item rather than
        // the delegate itself: auto-scrolling can carry a card far past the live
        // window, and a recycled delegate would take the card being dragged with it.
        PlaylistCard {
            objectName: "playlistCardReorderFloating"
            visible: grid._dragFrom >= 0 && grid._dragRow !== null
            z: 5
            tile: grid.tile
            width: grid.tile
            height: grid.cardH
            x: grid._dragFloatX
            y: grid._dragFloatY
            dragging: true
            name: grid._dragRow ? grid._dragRow.name : ""
            count: grid._dragRow ? grid._dragRow.trackCount : 0
            playCount: grid._dragRow ? (grid._dragRow.playCount || 0) : 0
            coverUrl: grid._dragRow ? grid._dragRow.coverUrl : ""
            coverThumbPath: grid._dragRow ? (grid._dragRow.coverThumbPath || "") : ""
            sourceName: grid._dragRow ? (grid._dragRow.sourceName || "") : ""
        }
    }

    ViewportScrollBar {
        target: grid
    }
}
