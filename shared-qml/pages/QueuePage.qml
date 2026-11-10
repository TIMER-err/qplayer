import QtQuick
import miuix.Core
import design
import "."
import "../components"

// The now-playing queue. The current track is highlighted (VirtualSongList
// highlights index === player.index, which is the queue position). Tap a row to
// jump; the trailing close button drops it from the queue.
//
// Layout: a RouteHeader (single Back) over the list; the list's scrolling header
// is a compact title block (title, track count, "jump to current"). The header
// title fades in once that block has scrolled away, and the locate action moves
// up into the RouteHeader at the same moment so it stays reachable.
//
// This page used to carry a second tab holding the single custom "play later"
// list. That list became the first of the user's local playlists (see
// LocalPlaylistStore's migration), which live on 我的.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    color: Ui.background

    readonly property real gutter: Ui.gutter(page.width)
    readonly property real listInset: Math.max(0, page.gutter - 12)
    readonly property bool compact: page.width < Ui.compactMax
    readonly property int trackCount: player.queueTracks ? player.queueTracks.length : 0
    readonly property bool empty: page.trackCount === 0
    readonly property real blockHeight: page.compact ? 76 : 88
    readonly property real blockProgress: Math.max(0, Math.min(1, (q.contentY - 16) / 28))

    function locateCurrent() { q.scrollToIndex(player.index) }

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    RouteHeader {
        id: header
        x: 0
        y: 0
        width: page.width
        title: i18n.t("nav.queue")
        titleOpacity: page.empty ? 1 : page.blockProgress
        elevated: q.contentY > 1
        onBack: page.back()

        IconButton {
            objectName: "queueLocateHeaderButton"
            visible: !page.empty && page.blockProgress > 0.5
            type: "standard"
            icon: "my_location"
            enabled: player.index >= 0
            onClicked: page.locateCurrent()
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
                width: Math.max(0, locateButton.x - x - Ui.space3)
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
        }
    }

    VirtualSongList {
        id: q
        objectName: "queueSongList"
        x: page.listInset
        y: header.height
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
        // Long-press → add to a local playlist (+ provider actions for online
        // sourced rows); a local file sitting in the live queue gets the smaller
        // local-only menu — see SongContextMenu's filePath branch.
        songMenu: true
        onActivated: player.playQueueIndex(q.activatedIndex)
        onRemoveRequested: player.removeFromQueue(q.removeIndex)
    }

    EmptyState {
        objectName: "queueEmptyState"
        x: 0
        y: header.height
        width: page.width
        height: Math.max(0, page.height - y - page.bottomContentInset)
        visible: page.empty
        icon: "queue_music"
        title: i18n.t("queue.empty.title")
        message: i18n.t("queue.empty.desc")
    }
}
