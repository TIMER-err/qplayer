import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// Drill-in playlist view: header with back + title, then the tracks.
Rectangle {
    id: page
    signal back()
    signal home()
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
            PageHeaderButtons {
                Layout.alignment: Qt.AlignVCenter
                onHome: page.home()
                onBack: page.back()
            }
            // Keep the marquee at its text-height and let RowLayout position that
            // box, rather than stretching an intermediate Item to the whole header.
            // qml4j did not consistently propagate the stretched wrapper's height
            // into the nested marquee, which left the glyph run above center.
            MarqueeText {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: player.playlistTitle
                textColor: Theme.color.onSurfaceColor
                fontFamily: page.width < 600
                            ? Theme.typography.titleMedium.family
                            : Theme.typography.titleLarge.family
                fontSize: page.width < 600
                          ? Theme.typography.titleMedium.size
                          : Theme.typography.titleLarge.size
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading
                         && tracks.list && tracks.list.length > 0
                         && (player.openSourcePlaylistId === ""
                             || player.sourceHeartRecommendationAvailable)
                enabled: !player.intelligenceLoading
                icon: "auto_awesome"
                contentColor: player.intelligenceLoading
                              ? Theme.color.primary
                              : Theme.color.onSurfaceVariantColor
                onClicked: {
                    if (player.openSourcePlaylistId !== "")
                        player.startMediaIntelligenceMode(player.openSourcePlaylistId)
                    else player.startIntelligenceMode(player.openPlaylistId)
                }
            }
            // Collect (subscribe) this playlist. Shown only once loaded and only for
            // playlists that aren't the user's own; filled when already collected. The
            // initial state comes from playlist/detail, so it's correct on open.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && !player.playlistOwned
                         && (player.openSourcePlaylistId === ""
                             || player.sourcePlaylistMutationAvailable)
                icon: player.playlistSubscribed ? "bookmark" : "bookmark_border"
                contentColor: player.playlistSubscribed ? Theme.color.primary : Theme.color.onSurfaceColor
                onClicked: player.togglePlaylistSubscribe()
            }
            // Change cover — own playlists only. Both hosts install the same picker
            // callback: Android keeps its system gallery picker, while desktop opens
            // a cross-platform image file chooser.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && player.playlistOwned
                         && player.openSourcePlaylistId === ""
                icon: "image"
                onClicked: player.pickPlaylistCover(player.openPlaylistId)
            }
            // Delete — only your own playlists, and never the "我喜欢的音乐" default
            // (the first playlist, which can't be removed). Confirms first.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && player.playlistDeletable
                icon: "delete"
                onClicked: deleteDialog.open()
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
                list: page.visible ? (player.openSourcePlaylistId !== ""
                                     ? player.sourcePlaylistTracks : player.playlistTracks) : null
                // Long-press a track → add to another playlist, and (in your own
                // playlist) remove it from this one. Not login-gated: "加入播放列表"
                // (local list) works signed-out too.
                songMenu: true
                ownedPlaylist: player.playlistOwned
                showOfflineBadge: player.playlistOffline
                onActivated: player.playPlaylistTrack(tracks.activatedIndex)
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

    // Delete confirmation. On accept the controller removes it and refreshes 我的;
    // we drill back out since this playlist no longer exists.
    Dialog {
        id: deleteDialog
        icon: "delete"
        title: "删除歌单"
        text: "确定删除歌单「" + player.playlistTitle + "」吗？此操作无法撤销。"
        acceptText: "删除"
        rejectText: "取消"
        onAccepted: {
            if (player.openSourcePlaylistId !== "")
                player.deleteMediaPlaylist(player.openSourcePlaylistId)
            else player.deletePlaylist(player.openPlaylistId)
            page.back()
        }
    }

}
