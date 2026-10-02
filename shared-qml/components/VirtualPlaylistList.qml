import QtQuick
import miuix.Core
import "."

// Virtualized playlist list — the list-view alternative to PlaylistGrid. Same
// windowing and drag-to-reorder mechanics as VirtualSongList (see its header
// comment for the rationale); the two were not merged into one component
// because a song row and a playlist row show different fields and open
// different menus, and threading that through one generic delegate was more
// conditionals than the ~150 lines this duplicates.
Flickable {
    id: view
    objectName: "virtualPlaylistList"

    property var list
    property var pendingPlaylist
    property bool reorderable: false
    property int revealedIndex: -1
    onReorderableChanged: revealedIndex = -1
    onCountChanged: revealedIndex = -1
    property int rowH: 64

    signal openPlaylist()
    signal deletePlaylistRequested()
    signal unsubscribePlaylistRequested()
    property int moveFrom: -1
    property int moveTo: -1
    signal moveRequested()
    signal reorderCommitted()

    property int _dragFrom: -1
    property int _dropIndex: -1
    property real _dragFloatY: 0
    property real _dragGrabOffset: 0
    property real _dragViewportY: 0
    property real _autoScrollStep: 0
    property real autoScrollEdge: Math.min(64, view.height / 4)
    readonly property var _dragRow: view._dragFrom >= 0 && view.list
                                    && view._dragFrom < view.count
                                    ? view.list[view._dragFrom] : null
    // Watchdog: see VirtualSongList's identical timer — something outside the
    // gesture (an OS screenshot tool, alt-tab, ...) can swallow the mouse-up
    // before onReorderReleased/onCanceled fires, leaving the carried row stuck
    // until an app restart. Auto-release once no drag activity has landed for
    // a while, since a real drag keeps producing onReorderDragged every frame.
    property real _dragLastActivityMs: 0
    Timer {
        interval: 1000
        repeat: true
        running: view._dragFrom >= 0
        onTriggered: {
            if (Date.now() - view._dragLastActivityMs > 15000) view._endDrag()
        }
    }

    function _applyDragTarget(contentPos) {
        if (view._dragFrom < 0 || view.count <= 0) return
        var maxY = Math.max(0, (view.count - 1) * view.rowH)
        view._dragFloatY = Math.max(0, Math.min(maxY, contentPos - view._dragGrabOffset))
        var target = Math.floor((view._dragFloatY + view.rowH / 2) / view.rowH)
        if (target < 0) target = 0
        if (target > view.count - 1) target = view.count - 1
        view._dropIndex = target
    }

    function _endDrag() {
        var from = view._dragFrom
        var to = view._dropIndex
        view._dragFrom = -1
        view._dropIndex = -1
        view._autoScrollStep = 0
        view.revealedIndex = -1
        if (from < 0) return
        if (to >= 0 && to !== from) {
            view.moveFrom = from
            view.moveTo = to
            view.moveRequested()
        }
        view.reorderCommitted()
    }

    function _updateAutoScroll(viewportPos) {
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
        interval: 16
        repeat: true
        running: view.reorderable && view._dragFrom >= 0 && view._autoScrollStep !== 0
        onTriggered: {
            var maxY = Math.max(0, view.contentHeight - view.height)
            var next = Math.max(0, Math.min(maxY, view.contentY + view._autoScrollStep))
            if (next === view.contentY) return
            view.contentY = next
            view._applyDragTarget(view._dragViewportY + next)
        }
    }

    property int count: list ? list.length : 0
    property int buffer: 3
    readonly property real viewportHeight: height
    readonly property real viewportY: contentY
    property int window: Math.min(count, Math.ceil(Math.max(0, viewportHeight) / Math.max(1, rowH)) + 2 * buffer + 1)
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

    Item {
        width: view.width
        height: view.contentHeight
        cachedLayout: true

        Repeater {
            model: view.list
            windowStart: view.first
            windowCount: view.window
            PlaylistListRow {
                objectName: "virtualPlaylistListRow"
                height: view.rowH
                width: view.width
                y: index * view.rowH + (view._dragFrom >= 0 ? reorderShift : 0)
                playlistId: modelData.id
                rowName: modelData.name
                rowSubtitle: modelData.trackCount > 0
                             ? i18n.t("common.songCount", modelData.trackCount)
                             : i18n.t("common.songCountEmpty")
                coverUrl: modelData.coverUrl || ""
                coverThumbPath: modelData.coverThumbPath || ""
                deletable: !!modelData.deletable
                subscribed: !!modelData.subscribed
                visible: view._dragFrom !== index
                reorderable: view.reorderable
                controlsRevealed: view.reorderable ? (view.revealedIndex === index) : true
                onRevealToggled: view.revealedIndex = (view.revealedIndex === index) ? -1 : index
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
                onActivated: { view.pendingPlaylist = modelData; view.openPlaylist() }
                onDeleteRequested: {
                    view.pendingPlaylist = modelData
                    view.deletePlaylistRequested()
                }
                onUnsubscribeRequested: {
                    view.pendingPlaylist = modelData
                    view.unsubscribePlaylistRequested()
                }
                onReorderPressed: {
                    view._dragFrom = index
                    view._dropIndex = index
                    view._dragGrabOffset = reorderGrabOffset
                    view._dragFloatY = index * view.rowH
                    view._dragViewportY = reorderContentY - view.viewportY
                    view._dragLastActivityMs = Date.now()
                }
                onReorderDragged: {
                    if (view._dragFrom < 0) return
                    view._dragLastActivityMs = Date.now()
                    view._dragViewportY = reorderContentY - view.viewportY
                    view._updateAutoScroll(view._dragViewportY)
                    view._applyDragTarget(reorderContentY)
                }
                onReorderReleased: view._endDrag()
            }
        }

        PlaylistListRow {
            objectName: "playlistReorderFloatingRow"
            visible: view._dragFrom >= 0 && view._dragRow !== null
            z: 5
            width: view.width
            height: view.rowH
            y: view._dragFloatY
            dragging: true
            reorderable: true
            rowName: view._dragRow ? view._dragRow.name : ""
            rowSubtitle: view._dragRow && view._dragRow.trackCount > 0
                         ? i18n.t("common.songCount", view._dragRow.trackCount)
                         : i18n.t("common.songCountEmpty")
            coverUrl: view._dragRow ? (view._dragRow.coverUrl || "") : ""
            coverThumbPath: view._dragRow ? (view._dragRow.coverThumbPath || "") : ""
        }
    }
}
