import QtQuick
import miuix.Core
import design
import "."
import "../components"

// Drill-in artist: round-avatar hero, then "About" (expandable bio), "Top songs"
// (first ten, expandable) and the album grid — one Flickable with absolute
// positioning, the same primitive HomePage uses to mix a grid with a song list.
// Songs and albums are windowed against the scroll position. The RouteHeader
// floats over the hero like on the other detail pages.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    color: Ui.background

    property bool loadingWatch: player.artistLoading
    onLoadingWatchChanged: if (player.artistLoading) {
        scroller.contentY = 0
        page.bioExpanded = false
        page.songsExpanded = false
    }

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    readonly property real gutter: Ui.gutter(page.width)
    readonly property bool compact: page.width < Ui.compactMax
    property real gap: page.compact ? 12 : 16
    property real rowH: Ui.rowHeight
    property real sectionH: 44
    property bool bioExpanded: false
    property bool songsExpanded: false
    readonly property int songPreview: 10

    property var visibleAlbums: player.openSourceArtistId !== ""
                                ? player.sourceArtistAlbums : player.artistAlbums
    property var visibleSongs: player.openSourceArtistId !== ""
                               ? player.sourceArtistSongs : player.artistSongs
    property int albumCount: visibleAlbums ? visibleAlbums.length : 0
    property int songCount: visibleSongs ? visibleSongs.length : 0
    readonly property string heroMeta: (page.albumCount > 0 || page.songCount > 0)
                                       ? i18n.t("artist.counts", page.albumCount, page.songCount) : ""

    // ---- Vertical rhythm (content coordinates) -----------------------------------
    // Two invisible probes (same width/font as the real bio Text) compare a
    // 3-line-capped render against the unclamped one — measure, don't guess.
    property real bioWidth: Math.max(0, Math.min(Ui.readableMax, width - 2 * gutter))
    property bool hasBio: player.artistBriefDesc !== ""
    property bool bioOverflows: bioFullProbe.contentHeight > bioCappedProbe.contentHeight + 1
    property real bioHdrY: hero.preferredHeight + 8
    property real bioTextY: bioHdrY + sectionH
    property real bioH: !hasBio ? 0
        : (bioExpanded ? bioFullProbe.contentHeight : bioCappedProbe.contentHeight)
    property real bioBottom: hasBio ? bioTextY + bioH + 24 : hero.preferredHeight + 8

    property int shownSongs: songsExpanded ? songCount : Math.min(songCount, songPreview)
    property real songsHdrY: bioBottom
    property real songsTop: songsHdrY + (songCount > 0 ? sectionH : 0)
    property real songsBottom: songsTop + shownSongs * rowH + (songCount > 0 ? 24 : 0)

    property real minAlbumTile: page.compact ? 132 : 160
    property int albumCols: Math.max(2, Math.floor((width - 2 * gutter + gap) / (minAlbumTile + gap)))
    property real albumTile: (width - 2 * gutter - (albumCols - 1) * gap) / albumCols
    // The card owns its caption height; read it from an unpainted probe.
    property real albumCardH: albumProbe.height
    property real albumStep: albumCardH + gap
    property real albumsHdrY: songsBottom
    property real albumsTop: albumsHdrY + (albumCount > 0 ? sectionH : 0)
    property real albumsGridH: albumCount > 0 ? Math.ceil(albumCount / albumCols) * albumStep : 0
    property real contentBottom: albumsTop + albumsGridH + 16

    AlbumCard {
        id: albumProbe
        visible: false
        tile: page.albumTile
    }

    // RouteHeader title fades in as the hero title slides under it.
    readonly property real heroTitleBottom: hero.compact
        ? hero.topInset + 8 + hero.coverSize + 18 + hero.titleLine * 2
        : hero.topInset + 8 + hero.coverSize - Ui.iconButton - 18
          - (page.heroMeta.length > 0 ? 20 : 0)
    readonly property real titleProgress: player.artistLoading ? 1
        : Math.max(0, Math.min(1, (scroller.contentY - (page.heroTitleBottom - Ui.headerHeight - 16)) / 28))

    Flickable {
        id: scroller
        anchors.fill: parent
        visible: opacity > 0.01
        opacity: player.artistLoading ? 0 : 1
        Behavior on opacity { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
        clip: true
        contentWidth: width
        contentHeight: page.contentBottom + page.bottomContentInset

        Item {
            width: scroller.width
            height: scroller.contentHeight
            cachedLayout: true

            HeroHeader {
                id: hero
                width: parent.width
                coverSource: player.artistCoverPath
                coverIcon: "person"
                round: true
                kind: i18n.t("detail.kind.artist")
                title: player.artistName
                meta: page.heroMeta

                Button {
                    objectName: "artistPlayTopButton"
                    type: "filled"
                    icon: "play_arrow"
                    text: i18n.t("detail.playTop")
                    cornerRadius: Ui.radiusFull
                    verticalPadding: 8
                    horizontalPadding: 20
                    enabled: page.songCount > 0
                    onClicked: player.playArtistSong(0)
                }
            }

            // ---- About ----------------------------------------------------------
            SectionHeader {
                visible: page.hasBio
                x: page.gutter
                y: page.bioHdrY
                width: page.bioWidth
                title: i18n.t("detail.bio")
                actionText: page.bioOverflows
                            ? i18n.t(page.bioExpanded ? "common.collapse" : "common.expand") : ""
                onActionRequested: page.bioExpanded = !page.bioExpanded
            }
            // Hidden measuring probes — never painted.
            Text {
                id: bioFullProbe
                visible: false
                width: page.bioWidth
                text: player.artistBriefDesc
                wrapMode: Text.WrapAnywhere
                font.pixelSize: Ui.textCallout
            }
            Text {
                id: bioCappedProbe
                visible: false
                width: page.bioWidth
                text: player.artistBriefDesc
                wrapMode: Text.WrapAnywhere
                maximumLineCount: 3
                elide: Text.ElideRight
                font.pixelSize: Ui.textCallout
            }
            Text {
                objectName: "artistBio"
                visible: page.hasBio
                x: page.gutter
                y: page.bioTextY
                width: page.bioWidth
                text: player.artistBriefDesc
                // WrapAnywhere, not WordWrap: a long CJK run with no break point
                // overflowed instead of wrapping.
                wrapMode: Text.WrapAnywhere
                maximumLineCount: page.bioExpanded ? 9999 : 3
                elide: page.bioExpanded ? Text.ElideNone : Text.ElideRight
                color: Ui.textSecondary
                font.pixelSize: Ui.textCallout
            }
            MouseArea {
                x: page.gutter
                y: page.bioTextY
                width: page.bioWidth
                height: page.bioH
                enabled: page.bioOverflows
                onClicked: page.bioExpanded = !page.bioExpanded
            }

            // ---- Top songs --------------------------------------------------------
            SectionHeader {
                visible: page.songCount > 0
                x: page.gutter
                y: page.songsHdrY
                width: parent.width - 2 * page.gutter
                title: i18n.t("artist.topSongs")
                actionText: page.songCount > page.songPreview
                            ? (page.songsExpanded ? i18n.t("common.collapse")
                                                  : i18n.t("detail.showAllSongs", page.songCount))
                            : ""
                onActionRequested: page.songsExpanded = !page.songsExpanded
            }
            Repeater {
                model: page.visible ? page.visibleSongs : null
                windowStart: page.songsExpanded
                    ? Math.max(0, Math.min(page.songCount - Math.ceil(scroller.height / page.rowH) - 7,
                        Math.floor((scroller.contentY - page.songsTop) / page.rowH) - 3))
                    : 0
                windowCount: Math.min(page.shownSongs, Math.ceil(scroller.height / page.rowH) + 7)
                SongRow {
                    width: scroller.width
                    height: page.rowH
                    y: page.songsTop + index * page.rowH
                    contentInset: page.gutter
                    rowTitle: modelData.name
                    rowArtist: modelData.artist
                    rowArtistId: modelData.artistMediaId || modelData.artistId || 0
                    rowArtistIdsCsv: modelData.artistIdsCsv || ""
                    rowArtistNamesCsv: modelData.artistNamesCsv || ""
                    coverThumbPath: modelData.coverThumbPath || ""
                    durationMs: modelData.durationMs || 0
                    highlighted: player.currentMediaId !== ""
                                 && ("" + (modelData.mediaId || modelData.id)) === player.currentMediaId
                    song: modelData
                    sharedMenu: artistSongMenu
                    onActivated: player.playArtistSong(index)
                }
            }
            SongContextMenu {
                id: artistSongMenu
                onClosed: if (menuRow) menuRow._menuArmed = false
            }

            // ---- Albums -----------------------------------------------------------
            SectionHeader {
                visible: page.albumCount > 0
                x: page.gutter
                y: page.albumsHdrY
                width: parent.width - 2 * page.gutter
                title: i18n.t("artist.albums")
            }
            Repeater {
                model: page.visible ? page.visibleAlbums : null
                windowStart: Math.max(0, Math.min(
                    Math.ceil(page.albumCount / page.albumCols) - Math.ceil(scroller.height / page.albumStep) - 3,
                    Math.floor((scroller.contentY - page.albumsTop) / page.albumStep) - 1)) * page.albumCols
                windowCount: (Math.ceil(scroller.height / page.albumStep) + 3) * page.albumCols
                AlbumCard {
                    albumId: modelData.id
                    tile: page.albumTile
                    x: page.gutter + (index % page.albumCols) * (page.albumTile + page.gap)
                    y: page.albumsTop + Math.floor(index / page.albumCols) * page.albumStep
                    name: modelData.name
                    count: modelData.trackCount
                    coverUrl: modelData.coverUrl
                    coverThumbPath: modelData.coverThumbPath || ""
                    onClicked: player.openMediaAlbum("" + modelData.id)
                }
            }
        }

        ViewportScrollBar {
            target: scroller
            y: scroller.contentY + Ui.headerHeight
            bottomInset: page.bottomContentInset + Ui.headerHeight
        }
    }

    LoadingIndicator {
        objectName: "detailLoadingIndicator"
        anchors.centerIn: parent
        visible: player.artistLoading
        running: player.artistLoading
    }

    // Header area: block taps from reaching hero controls scrolled underneath.
    MouseArea {
        width: page.width
        height: Ui.headerHeight
    }

    RouteHeader {
        width: page.width
        gutter: page.gutter
        title: player.artistName
        titleOpacity: page.titleProgress
        elevated: !player.artistLoading && page.titleProgress > 0.01
        onBack: page.back()
    }
}
