import QtQuick
import miuix.Core
import design
import "."
import "../components"

// Recently played tracks, newest first. Same chrome as the queue: RouteHeader
// over a list whose scrolling header is a compact title block with the count
// and the primary actions (play the history from the top, or shuffle it).
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    color: Ui.background

    readonly property real gutter: Ui.gutter(page.width)
    readonly property real listInset: Math.max(0, page.gutter - 12)
    readonly property bool compact: page.width < Ui.compactMax
    readonly property var rows: player.playbackHistoryTracks || []
    readonly property bool empty: page.rows.length === 0
    readonly property real blockHeight: page.compact ? 76 : 88
    readonly property real blockProgress: Math.max(0, Math.min(1, (history.contentY - 16) / 28))

    function playFrom(i) {
        var row = page.rows[i]
        if (row) player.playHistoryTrack(row.mediaId)
    }
    function playAll() {
        if (page.empty) return
        if (player.playMode === 1) player.setPlayMode(0)
        page.playFrom(0)
    }
    function shufflePlay() {
        if (page.empty) return
        player.setPlayMode(1)
        page.playFrom(Math.floor(Math.random() * page.rows.length))
    }

    Component.onCompleted: player.loadPlaybackHistory()

    MouseArea { anchors.fill: parent }

    RouteHeader {
        id: header
        x: 0
        y: 0
        width: page.width
        title: i18n.t("nav.recent")
        titleOpacity: page.empty ? 1 : page.blockProgress
        elevated: history.contentY > 1
        onBack: page.back()
    }

    Component {
        id: titleBlock
        Item {
            width: history.width
            height: page.blockHeight

            Text {
                id: blockTitle
                x: 12
                y: 0
                width: Math.max(0, shuffleButton.x - x - Ui.space3)
                text: i18n.t("nav.recent")
                color: Ui.textPrimary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: page.compact ? Ui.textTitle1 : Ui.textDisplay
                font.weight: Ui.weightBold
            }
            Text {
                objectName: "recentCountCaption"
                x: 12
                y: blockTitle.y + blockTitle.implicitHeight + 2
                width: Math.max(0, playAllButton.x - x - Ui.space3)
                text: i18n.t("common.songCount", page.rows.length)
                color: Ui.textSecondary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: Ui.textCallout
            }

            IconButton {
                id: shuffleButton
                objectName: "recentShuffleButton"
                x: playAllButton.x - Ui.space2 - width
                y: playAllButton.y
                type: "filledTonal"
                icon: "shuffle"
                onClicked: page.shufflePlay()
            }
            Button {
                id: playAllButton
                objectName: "recentPlayAllButton"
                x: parent.width - 12 - width
                y: Math.max(0, (blockTitle.implicitHeight - height) / 2)
                height: Ui.iconButton
                type: "filled"
                icon: "play_arrow"
                text: i18n.t("list.playAll")
                cornerRadius: Ui.radiusFull
                onClicked: page.playAll()
            }
        }
    }

    VirtualSongList {
        id: history
        objectName: "recentPlaybackList"
        x: page.listInset
        y: header.height
        width: Math.max(0, page.width - 2 * page.listInset)
        height: Math.max(0, page.height - y)
        bottomContentInset: page.bottomContentInset
        header: page.empty ? null : titleBlock
        headerHeight: page.empty ? 0 : page.blockHeight
        list: page.visible ? player.playbackHistoryTracks : null
        isLocal: true
        onActivated: page.playFrom(history.activatedIndex)
    }

    EmptyState {
        objectName: "recentEmptyState"
        x: 0
        y: header.height
        width: page.width
        height: Math.max(0, page.height - y - page.bottomContentInset)
        visible: page.empty
        icon: "history"
        title: i18n.t("recent.empty.title")
        message: i18n.t("recent.empty.desc")
    }
}
