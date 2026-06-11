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

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 60
            Layout.leftMargin: 4
            Layout.rightMargin: 12
            spacing: 8
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: player.playlistTitle
                color: Theme.color.onSurfaceColor
                fontSize: 20
                elide: Text.ElideRight
            }
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
