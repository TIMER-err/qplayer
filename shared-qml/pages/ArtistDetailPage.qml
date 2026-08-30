import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// Drill-in artist view: header with back + name, then profile (avatar + bio),
// an album grid, and hot songs -- all one Flickable with absolute positioning,
// the same layout primitive HomePage.qml uses to mix a grid with a song list
// (a real Repeater-in-Layout can't host two differently-shaped sections).
Rectangle {
    id: page
    signal back()
    signal home()
    color: Theme.color.surface

    property bool loadingWatch: player.artistLoading
    onLoadingWatchChanged: if (player.artistLoading) { scroller.contentY = 0; page.bioExpanded = false }

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    property real pad: 16
    property real gap: 12
    property real avatarSize: 72
    property real profileTop: 12
    property bool bioExpanded: false
    // Two invisible measuring probes (same width/font as the real bio Text
    // below) compare a 3-line-capped render against the unclamped one -- the
    // same "measure with a hidden Text, don't guess from char count" idiom
    // AlbumCard's nameProbe uses, just for wrapped height instead of width.
    property real bioWidth: Math.max(0, width - 2 * pad)
    property bool bioOverflows: bioFullProbe.contentHeight > bioCappedProbe.contentHeight + 1
    // Room reserved under the avatar for the bio (collapsed 3 lines, or the
    // full text once expanded) plus the 展开/收起 toggle when it's needed.
    property real descReserve: player.artistBriefDesc === "" ? 0
        : 10 + (bioExpanded ? bioFullProbe.contentHeight : bioCappedProbe.contentHeight)
              + (bioOverflows ? 28 : 0)
    property real profileH: avatarSize + descReserve
    property var visibleAlbums: player.openSourceArtistId !== ""
                                ? player.sourceArtistAlbums : player.artistAlbums
    property var visibleSongs: player.openSourceArtistId !== ""
                               ? player.sourceArtistSongs : player.artistSongs
    property int albumCount: visibleAlbums ? visibleAlbums.length : 0
    property int songCount: visibleSongs ? visibleSongs.length : 0
    property real minAlbumTile: 130
    property int albumCols: Math.max(2, Math.floor((width - 2 * pad + gap) / (minAlbumTile + gap)))
    property real albumTile: (width - 2 * pad - (albumCols - 1) * gap) / albumCols
    property real albumCardH: albumTile + 56
    property real albumsHdrY: profileTop + profileH + 8
    property real albumsTop: albumsHdrY + (albumCount > 0 ? 40 : 0)
    property real albumsGridH: albumCount > 0 ? Math.ceil(albumCount / albumCols) * (albumCardH + gap) : 0
    property real songsHdrY: albumsTop + albumsGridH + (albumCount > 0 ? 4 : 0)
    property real songsTop: songsHdrY + (songCount > 0 ? 40 : 0)
    property real rowH: 64

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Custom header: qml4j can't set a sub-property of TopAppBar's
        // navigationIcon alias (navigationIcon.icon) via grouped binding.
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            PageHeaderButtons {
                Layout.alignment: Qt.AlignVCenter
                onHome: page.home()
                onBack: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: player.artistName
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                verticalAlignment: Text.AlignVCenter
                elide: Text.ElideRight
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            Flickable {
                id: scroller
                anchors.fill: parent
                visible: !player.artistLoading
                clip: true
                contentWidth: width
                contentHeight: page.songsTop + page.songCount * page.rowH + 24

                Item {
                    width: scroller.width
                    height: scroller.contentHeight
                    cachedLayout: true

                    CoverImage {
                        id: avatar
                        x: page.pad; y: page.profileTop
                        width: page.avatarSize; height: page.avatarSize
                        radius: page.avatarSize / 2
                        icon: "person"
                        iconSize: 32
                        fadeIn: true
                        source: player.artistCoverPath
                    }

                    MarqueeText {
                        id: nameText
                        anchors.left: avatar.right
                        anchors.leftMargin: 14
                        anchors.right: parent.right
                        anchors.rightMargin: page.pad
                        anchors.top: avatar.top
                        anchors.topMargin: 4
                        height: 26
                        text: player.artistName
                        textColor: Theme.color.onSurfaceColor
                        fontSize: 18
                        fontWeight: Font.Medium
                    }

                    Text {
                        anchors.left: avatar.right
                        anchors.leftMargin: 14
                        anchors.right: parent.right
                        anchors.rightMargin: page.pad
                        anchors.top: nameText.bottom
                        anchors.topMargin: 4
                        text: (page.albumCount > 0 || page.songCount > 0)
                              ? (page.albumCount + " 张专辑 · " + page.songCount + " 首热门歌曲")
                              : ""
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 13
                        elide: Text.ElideRight
                    }

                    // Hidden measuring probes -- never painted, just used to compare
                    // the capped-vs-full wrapped height (see page.bioOverflows above).
                    Text {
                        id: bioFullProbe
                        visible: false
                        width: page.bioWidth
                        text: player.artistBriefDesc
                        wrapMode: Text.WrapAnywhere
                        fontSize: 12
                    }
                    Text {
                        id: bioCappedProbe
                        visible: false
                        width: page.bioWidth
                        text: player.artistBriefDesc
                        wrapMode: Text.WrapAnywhere
                        maximumLineCount: 3
                        elide: Text.ElideRight
                        fontSize: 12
                    }

                    Text {
                        id: bioText
                        anchors.left: parent.left
                        anchors.leftMargin: page.pad
                        anchors.right: parent.right
                        anchors.rightMargin: page.pad
                        anchors.top: avatar.bottom
                        anchors.topMargin: 10
                        visible: player.artistBriefDesc !== ""
                        text: player.artistBriefDesc
                        // WrapAnywhere, not WordWrap: some bios overflowed
                        // instead of wrapping -- a long CJK run with no break
                        // point qml4j's word-boundary detection could find
                        // (see [[qml4j-cjk-wordwrap-fix]] for the underlying
                        // engine bug/PR; WrapAnywhere sidesteps needing that
                        // detection to be complete at all).
                        wrapMode: Text.WrapAnywhere
                        maximumLineCount: page.bioExpanded ? 9999 : 3
                        elide: page.bioExpanded ? Text.ElideNone : Text.ElideRight
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 12
                    }

                    // 展开/收起 -- bottom-right of the bio block, only shown once it
                    // actually overflows 3 lines.
                    Text {
                        id: bioToggle
                        visible: page.bioOverflows
                        anchors.right: parent.right
                        anchors.rightMargin: page.pad
                        anchors.top: bioText.bottom
                        anchors.topMargin: 2
                        height: 24
                        text: page.bioExpanded ? "收起" : "展开"
                        color: Theme.color.primary
                        fontSize: 12
                        font.weight: Font.Medium
                    }
                    MouseArea {
                        anchors.fill: bioToggle
                        visible: page.bioOverflows
                        onClicked: page.bioExpanded = !page.bioExpanded
                    }

                    Text {
                        visible: page.albumCount > 0
                        x: page.pad; y: page.albumsHdrY; height: 40
                        verticalAlignment: Text.AlignVCenter
                        text: "专辑"
                        color: Theme.color.primary
                        fontSize: 16
                    }

                    Repeater {
                        model: page.visibleAlbums
                        AlbumCard {
                            albumId: modelData.id
                            tile: page.albumTile
                            x: page.pad + (index % page.albumCols) * (page.albumTile + page.gap)
                            y: page.albumsTop + Math.floor(index / page.albumCols) * (page.albumCardH + page.gap)
                            name: modelData.name
                            count: modelData.trackCount
                            coverUrl: modelData.coverUrl
                            coverThumbPath: modelData.coverThumbPath || ""
                            onClicked: player.openMediaAlbum("" + modelData.id)
                        }
                    }

                    Text {
                        visible: page.songCount > 0
                        x: page.pad; y: page.songsHdrY; height: 40
                        verticalAlignment: Text.AlignVCenter
                        text: "热门歌曲"
                        color: Theme.color.primary
                        fontSize: 16
                    }

                    Repeater {
                        model: page.visibleSongs
                        SongRow {
                            width: scroller.width
                            y: page.songsTop + index * page.rowH
                            rowTitle: modelData.name
                            rowArtist: modelData.artist
                            rowArtistId: modelData.artistMediaId || modelData.artistId || 0
                            rowArtistIdsCsv: modelData.artistIdsCsv || ""
                            rowArtistNamesCsv: modelData.artistNamesCsv || ""
                            coverThumbPath: modelData.coverThumbPath || ""
                            song: modelData
                            onActivated: player.playArtistSong(index)
                        }
                    }
                }
            }

            LoadingIndicator {
                anchors.centerIn: parent
                visible: player.artistLoading
                running: player.artistLoading
                withContainer: true
                size: 56
            }
        }
    }
}
