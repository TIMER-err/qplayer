import QtQuick
import miuix.Core
import design
import "."
import "../components"

// The now-playing queue, hosted in QueueSheet's floating panel. The current track
// is highlighted (VirtualSongList highlights index === player.index, which is the
// queue position). Tap a row to jump; the trailing close button drops it from the
// queue and the grip beside it drags it to a new position.
//
// Layout: a grab handle over the list; the list's scrolling header is a compact
// title block (title, track count, clear, "jump to current").
//
// This page used to carry a second tab holding the single custom "play later"
// list. That list became the first of the user's local playlists (see
// LocalPlaylistStore's migration), which live on 我的.
Rectangle {
    id: page
    property real bottomContentInset: 0
    readonly property real handleHeight: 20
    signal back()
    color: Ui.background
    // The sheet panel's top corners.
    radius: Ui.radiusXl

    readonly property real gutter: Ui.gutter(page.width)
    readonly property real listInset: Math.max(0, page.gutter - 12)
    readonly property bool compact: page.width < Ui.compactMax
    readonly property int trackCount: player.queueTracks ? player.queueTracks.length : 0
    readonly property bool empty: page.trackCount === 0
    readonly property real blockHeight: page.compact ? 76 : 88

    function locateCurrent() { q.scrollToIndex(player.index) }

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    // Grab handle: signals "this panel can be dismissed" the same way a bottom
    // sheet does, without claiming the drag that row reordering needs. Tapping it
    // closes, like the scrim.
    Rectangle {
        objectName: "queueSheetGrabber"
        anchors.horizontalCenter: parent.horizontalCenter
        y: 8
        width: 36
        height: 4
        radius: 2
        color: Ui.textTertiary
        MouseArea {
            anchors.fill: parent
            anchors.margins: -10
            onClicked: page.back()
        }
    }

    Component {
        id: titleBlock
        Item {
            width: q.width
            height: page.blockHeight

            Text {
                id: blockTitle
                x: 12
                y: 0
                width: Math.max(0, clearButton.x - x - Ui.space3)
                text: i18n.t("nav.queue")
                color: Ui.textPrimary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: page.compact ? Ui.textTitle1 : Ui.textDisplay
                font.weight: Ui.weightBold
            }
            Text {
                objectName: "queueCountCaption"
                x: 12
                y: blockTitle.y + blockTitle.implicitHeight + 2
                width: blockTitle.width
                text: i18n.t("common.songCount", page.trackCount)
                color: Ui.textSecondary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: Ui.textCallout
            }

            Button {
                id: locateButton
                objectName: "queueLocateButton"
                x: parent.width - 12 - width
                y: Math.max(0, (blockTitle.implicitHeight - height) / 2)
                height: Ui.iconButton
                type: "filledTonal"
                icon: "my_location"
                text: i18n.t("queue.locate")
                cornerRadius: Ui.radiusFull
                enabled: player.index >= 0
                onClicked: page.locateCurrent()
            }

            // 清空播放列表: destructive, so it asks first (same Dialog pattern the
            // playlist pages use for 删除歌单).
            IconButton {
                id: clearButton
                objectName: "queueClearButton"
                x: locateButton.x - width - 6
                y: locateButton.y
                width: Ui.iconButton
                height: Ui.iconButton
                type: "standard"
                icon: "delete_sweep"
                onClicked: clearDialog.open()
            }
        }
    }

    VirtualSongList {
        id: q
        objectName: "queueSongList"
        x: page.listInset
        y: page.handleHeight
        width: Math.max(0, page.width - 2 * page.listInset)
        height: Math.max(0, page.height - y)
        bottomContentInset: page.bottomContentInset
        header: page.empty ? null : titleBlock
        headerHeight: page.empty ? 0 : page.blockHeight
        // Only hold row delegates while the page is actually shown (visible
        // tracks the open/close fade). A closed queue page is invisible but
        // still in the tree, so binding straight to the list kept N hidden
        // SongRow delegates alive after playing a big playlist — steady GC
        // pressure on every other screen. Null when closed disposes them.
        list: page.visible ? player.queueTracks : null
        isLocal: true
        removable: true
        // Drag-to-reorder: this list IS the live queue, so a drop reorders
        // playback order in place; the controller keeps the playing track's
        // index right (see moveQueueItem).
        reorderable: true
        queueList: true
        // Long-press → add to a local playlist (+ provider actions for online
        // sourced rows); a local file sitting in the live queue gets the smaller
        // local-only menu — see SongContextMenu's filePath branch.
        songMenu: true
        onActivated: player.playQueueIndex(q.activatedIndex)
        onRemoveRequested: player.removeFromQueue(q.removeIndex)
        onMoveRequested: player.moveQueueItem(q.moveFrom, q.moveTo)
    }

    EmptyState {
        objectName: "queueEmptyState"
        x: 0
        y: page.handleHeight
        width: page.width
        height: Math.max(0, page.height - y - page.bottomContentInset)
        visible: page.empty
        icon: "queue_music"
        title: i18n.t("queue.empty.title")
        message: i18n.t("queue.empty.desc")
    }

    // Confirmation for 清空播放列表: emptying the queue also stops playback, so it
    // is not something to do on a single stray tap.
    Dialog {
        id: clearDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "delete_sweep"
        title: i18n.t("queue.clear.title")
        text: i18n.t("queue.clear.body", page.trackCount)
        acceptText: i18n.t("queue.clear.action")
        rejectText: i18n.t("common.cancel")
        // Stay on the page: it falls through to the empty state, which is the
        // clearest confirmation that the queue is gone.
        onAccepted: player.clearQueue()
    }
}
