import QtQuick
import miuix.Core
import design
import "."
import "../components"

// Drill-in album: the shared detail structure — a floating RouteHeader over a
// VirtualSongList whose header is the hero (cover, album name, artist link,
// year · track count, play). VirtualSongList handles arbitrarily long tracklists.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    color: Ui.background

    property bool loadingWatch: player.albumLoading
    onLoadingWatchChanged: if (player.albumLoading) tracks.contentY = 0

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    readonly property real gutter: Ui.gutter(page.width)
    property var albumTracks: player.openSourceAlbumId !== ""
                              ? player.sourceAlbumTracks : player.albumTracks
    readonly property int trackCount: page.albumTracks ? page.albumTracks.length : 0
    readonly property bool artistLinked: player.albumArtistMediaId !== "" || player.albumArtistId !== 0
    readonly property string heroMeta: {
        var songs = page.trackCount > 0 ? i18n.t("common.songCount", page.trackCount) : ""
        if (player.albumPublishYear === "") return songs
        return player.albumPublishYear + (songs !== "" ? " · " + songs : "")
    }

    function openArtist() {
        if (!page.artistLinked) return
        player.openMediaArtist(player.albumArtistMediaId !== ""
                               ? player.albumArtistMediaId : ("" + player.albumArtistId))
    }

    // Deterministic hero height (see PlaylistDetailPage).
    HeroHeader {
        id: heroMetrics
        visible: false
        width: page.width
        subtitle: player.albumArtistName
        meta: page.heroMeta
    }
    readonly property real heroTitleBottom: heroMetrics.compact
        ? heroMetrics.topInset + 8 + heroMetrics.coverSize + 18 + heroMetrics.titleLine * 2
        : heroMetrics.topInset + 8 + heroMetrics.coverSize - Ui.iconButton - 18
          - (page.heroMeta.length > 0 ? 20 : 0) - (player.albumArtistName.length > 0 ? 24 : 0)
    readonly property real titleProgress: player.albumLoading ? 1
        : Math.max(0, Math.min(1, (tracks.contentY - (page.heroTitleBottom - Ui.headerHeight - 16)) / 28))

    VirtualSongList {
        id: tracks
        anchors.fill: parent
        visible: opacity > 0.01
        opacity: player.albumLoading ? 0 : 1
        Behavior on opacity { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
        rowInset: page.gutter
        scrollBarTopInset: Ui.headerHeight
        bottomContentInset: page.bottomContentInset + 8
        headerHeight: heroMetrics.preferredHeight + 8
        header: Component {
            HeroHeader {
                width: tracks.width
                coverSource: player.albumCoverPath
                coverIcon: "album"
                kind: i18n.t("detail.kind.album")
                title: player.albumName
                subtitle: player.albumArtistName
                subtitleLink: page.artistLinked
                meta: page.heroMeta
                onSubtitleClicked: page.openArtist()

                Button {
                    objectName: "albumPlayAllButton"
                    type: "filled"
                    icon: "play_arrow"
                    text: i18n.t("list.playAll")
                    cornerRadius: Ui.radiusFull
                    verticalPadding: 8
                    horizontalPadding: 20
                    enabled: page.trackCount > 0
                    onClicked: player.playAlbumTrack(0)
                }
            }
        }
        // Drop the row delegates when the detail page is closed (see QueuePage):
        // an invisible detail otherwise keeps the whole album's SongRows alive.
        list: page.visible ? page.albumTracks : null
        onActivated: player.playAlbumTrack(tracks.activatedIndex)
    }

    LoadingIndicator {
        objectName: "detailLoadingIndicator"
        anchors.centerIn: parent
        visible: player.albumLoading
        running: player.albumLoading
    }

    // Header area: block taps from reaching hero controls scrolled underneath.
    MouseArea {
        width: page.width
        height: Ui.headerHeight
    }

    RouteHeader {
        width: page.width
        gutter: page.gutter
        title: player.albumName
        titleOpacity: page.titleProgress
        elevated: !player.albumLoading && page.titleProgress > 0.01
        onBack: page.back()
    }
}
