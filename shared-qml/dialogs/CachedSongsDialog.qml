import QtQuick
import miuix.Core
import design
import "../components"

// Offline-cached online songs (cache/audio/*.cache) — the "已下载" route, despite
// the folder. Opened from the sidebar / overflow, which calls
// player.refreshCachedSongs() right before opening, so player.cachedSongs is a
// fresh snapshot of what's actually on disk. Tapping a row plays the cached list
// from that slot; the playback pipeline's cached-audio fast path serves it
// without touching the network.
//
// Same chrome as the queue: RouteHeader over a list whose scrolling header is a
// compact title block with the count and the primary actions.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    color: Ui.background

    readonly property real gutter: Ui.gutter(page.width)
    readonly property real listInset: Math.max(0, page.gutter - 12)
    readonly property bool compact: page.width < Ui.compactMax
    readonly property int trackCount: player.cachedSongs ? player.cachedSongs.length : 0
    readonly property bool empty: page.trackCount === 0
    readonly property real blockHeight: page.compact ? 76 : 88
    readonly property real blockProgress: Math.max(0, Math.min(1, (cachedList.contentY - 16) / 28))

    function playAll() {
        if (page.empty) return
        if (player.playMode === 1) player.setPlayMode(0)
        player.playCachedSong(0)
    }
    function shufflePlay() {
        if (page.empty) return
        player.setPlayMode(1)
        player.playCachedSong(Math.floor(Math.random() * page.trackCount))
    }

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    RouteHeader {
        id: header
        x: 0
        y: 0
        width: page.width
        title: i18n.t("nav.downloaded")
        titleOpacity: page.empty ? 1 : page.blockProgress
        elevated: cachedList.contentY > 1
        onBack: page.back()
    }

    Component {
        id: titleBlock
        Item {
            width: cachedList.width
            height: page.blockHeight

            Text {
                id: blockTitle
                x: 12
                y: 0
                width: Math.max(0, shuffleButton.x - x - Ui.space3)
                text: i18n.t("nav.downloaded")
                color: Ui.textPrimary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: page.compact ? Ui.textTitle1 : Ui.textDisplay
                font.weight: Ui.weightBold
            }
            Text {
                objectName: "cachedCountCaption"
                x: 12
                y: blockTitle.y + blockTitle.implicitHeight + 2
                width: Math.max(0, playAllButton.x - x - Ui.space3)
                text: i18n.t("cache.caption", page.trackCount)
                color: Ui.textSecondary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: Ui.textCallout
            }

            IconButton {
                id: shuffleButton
                objectName: "cachedShuffleButton"
                x: playAllButton.x - Ui.space2 - width
                y: playAllButton.y
                type: "filledTonal"
                icon: "shuffle"
                onClicked: page.shufflePlay()
            }
            Button {
                id: playAllButton
                objectName: "cachedPlayAllButton"
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
        id: cachedList
        objectName: "cachedSongList"
        x: page.listInset
        y: header.height
        width: Math.max(0, page.width - 2 * page.listInset)
        height: Math.max(0, page.height - y)
        bottomContentInset: page.bottomContentInset
        header: page.empty ? null : titleBlock
        headerHeight: page.empty ? 0 : page.blockHeight
        // Only hold row delegates while the page is actually shown (same guard
        // as QueuePage): a closed page is invisible but still in the tree.
        list: page.visible ? player.cachedSongs : null
        isLocal: true
        highlightCurrent: false
        songMenu: true
        cacheList: true
        onActivated: player.playCachedSong(cachedList.activatedIndex)
    }

    EmptyState {
        objectName: "cachedEmptyState"
        x: 0
        y: header.height
        width: page.width
        height: Math.max(0, page.height - y - page.bottomContentInset)
        visible: page.empty
        icon: "download_done"
        title: i18n.t("cache.empty.title")
        message: i18n.t("cache.empty.desc")
    }
}
