import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// Drill-in album view: header with back + title, then cover/artist/track-count,
// then the tracklist (VirtualSongList handles arbitrarily long tracklists).
Rectangle {
    id: page
    signal back()
    signal home()
    color: Theme.color.surface

    property bool loadingWatch: player.albumLoading
    onLoadingWatchChanged: if (player.albumLoading) tracks.contentY = 0

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

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
                text: player.albumName
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                verticalAlignment: Text.AlignVCenter
                elide: Text.ElideRight
            }
        }

        // Album info: cover + artist (tap to open their page) + release year/track count.
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 96
            visible: !player.albumLoading

            CoverImage {
                id: cover
                x: 16; y: 8
                width: 80; height: 80
                radius: 10
                icon: "album"
                iconSize: 30
                fadeIn: true
                source: player.albumCoverPath
            }

            Text {
                id: artistLine
                anchors.left: cover.right
                anchors.leftMargin: 14
                anchors.right: parent.right
                anchors.rightMargin: 16
                anchors.top: cover.top
                anchors.topMargin: 6
                text: player.albumArtistName
                color: (artistArea.enabled && artistArea.containsMouse)
                       ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                fontSize: 14
                elide: Text.ElideRight
            }
            MouseArea {
                id: artistArea
                anchors.left: artistLine.left
                anchors.right: artistLine.right
                anchors.top: artistLine.top
                anchors.bottom: artistLine.bottom
                enabled: player.albumArtistMediaId !== "" || player.albumArtistId !== 0
                hoverEnabled: enabled
                cursorShape: Qt.PointingHandCursor
                onClicked: player.openMediaArtist(player.albumArtistMediaId !== ""
                                                  ? player.albumArtistMediaId
                                                  : ("" + player.albumArtistId))
            }

            Text {
                anchors.left: artistLine.left
                anchors.right: artistLine.right
                anchors.top: artistLine.bottom
                anchors.topMargin: 8
                text: player.albumPublishYear !== ""
                      ? (player.albumPublishYear + ((tracks.list && tracks.list.length > 0)
                             ? (" · " + tracks.list.length + " 首歌曲") : ""))
                      : ((tracks.list && tracks.list.length > 0)
                             ? (tracks.list.length + " 首歌曲") : "")
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            VirtualSongList {
                id: tracks
                anchors.fill: parent
                visible: !player.albumLoading
                // Drop the row delegates when the detail page is closed (see
                // QueuePage): an invisible detail otherwise keeps the whole
                // album's SongRows alive after you return.
                list: page.visible ? (player.openSourceAlbumId !== ""
                                      ? player.sourceAlbumTracks : player.albumTracks) : null
                onActivated: player.playAlbumTrack(tracks.activatedIndex)
            }

            LoadingIndicator {
                anchors.centerIn: parent
                visible: player.albumLoading
                running: player.albumLoading
                withContainer: true
                size: 56
            }
        }
    }
}
