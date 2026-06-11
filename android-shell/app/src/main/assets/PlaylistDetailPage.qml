import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Drill-in playlist view: header with back + title, then the tracks.
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TopAppBar {
            Layout.fillWidth: true
            title: player.playlistTitle
            navigationIcon.icon: "arrow_back"
            onNavigationIconClicked: page.back()
        }

        VirtualSongList {
            id: tracks
            Layout.fillWidth: true
            Layout.fillHeight: true
            list: player.playlistTracks
            onActivated: player.playPlaylistTrack(tracks.activatedIndex)
        }
    }
}
