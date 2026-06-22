import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Drill-in playlist view: header with back + title, then the tracks.
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    // Reset the scroll to the top whenever a new playlist starts loading, so the
    // previous playlist's scroll position doesn't carry over.
    property bool loadingWatch: player.playlistLoading
    onLoadingWatchChanged: if (player.playlistLoading) tracks.contentY = 0

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
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
            // Collect (subscribe) this playlist. Shown only once loaded and only for
            // playlists that aren't the user's own; filled when already collected. The
            // initial state comes from playlist/detail, so it's correct on open.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && !player.playlistOwned
                icon: player.playlistSubscribed ? "bookmark" : "bookmark_border"
                contentColor: player.playlistSubscribed ? Theme.color.primary : Theme.color.onSurfaceColor
                onClicked: player.togglePlaylistSubscribe()
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            VirtualSongList {
                id: tracks
                anchors.fill: parent
                visible: !player.playlistLoading
                // Drop the row delegates when the detail page is closed (see
                // QueuePage): an invisible detail otherwise keeps the whole
                // playlist's SongRows alive after you return home.
                list: page.visible ? player.playlistTracks : null
                addable: true
                onActivated: player.playPlaylistTrack(tracks.activatedIndex)
                onAddRequested: player.addPlaylistTrackToQueue(tracks.addIndex)
            }

            LoadingIndicator {
                anchors.centerIn: parent
                visible: player.playlistLoading
                running: player.playlistLoading
                withContainer: true
                size: 56
            }
        }
    }
}
