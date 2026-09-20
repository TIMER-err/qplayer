import QtQuick
import QtQuick.Layouts
import miuix.Core
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
    property string playlistWatch: String(player.openSourcePlaylistId || player.openPlaylistId)
    onPlaylistWatchChanged: {
        tracks.contentY = 0
        page.filterText = ""
    }

    property string filterText: ""
    property var filteredTracks: {
        var all = player.openSourcePlaylistId !== ""
                  ? player.sourcePlaylistTracks : player.playlistTracks
        if (!all) return all
        var q = page.filterText.trim().toLowerCase()
        if (q === "") return all
        var out = []
        for (var i = 0; i < all.length; i++) {
            var t = all[i]
            var hit = (t.name && t.name.toLowerCase().indexOf(q) >= 0)
                   || (t.artist && t.artist.toLowerCase().indexOf(q) >= 0)
            if (hit) out.push(t)
        }
        return out
    }

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
                text: i18n.t("playlist.title")
                color: Theme.color.onSurfaceColor
                font.pixelSize: 24
                font.weight: Font.DemiBold
                elide: Text.ElideRight
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
            // Pull this whole playlist into a local one, which then follows it.
            // Unlike collecting below, this works signed out and across sources —
            // it copies the songs into a list QPlayer owns.
            IconButton {
                id: followButton
                objectName: "followIntoLocalButton"
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: !player.playlistLoading && player.openSourcePlaylistId !== ""
                icon: "library_add"
                onClicked: {
                    followMenu.rebuild()
                    followMenu.open(followButton, 0, followButton.height)
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
            // Change cover — own playlists only, and only when the playlist's own
            // source can do it (built-in netease, or a plugin that advertises
            // playlistCover). Both hosts install the same picker callback: Android
            // keeps its system gallery picker, while desktop opens a cross-platform
            // image file chooser.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && player.playlistOwned
                         && (player.openSourcePlaylistId === ""
                             || player.sourcePlaylistCoverAvailable)
                icon: "image"
                onClicked: player.pickPlaylistCover()
            }
            // Delete — only your own playlists, and never the default liked-songs
            // (the first playlist, which can't be removed). Confirms first.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && player.playlistDeletable
                icon: "delete"
                onClicked: deleteDialog.open()
            }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.topMargin: 8
            Layout.bottomMargin: 20
            visible: !player.playlistLoading
            spacing: 16
            CoverImage {
                Layout.preferredWidth: page.width >= 600 ? 128 : 96
                Layout.preferredHeight: width
                radius: 20
                source: player.playlistCoverPath
                icon: "queue_music"
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                Text {
                    Layout.fillWidth: true
                    text: player.playlistTitle
                    font.pixelSize: page.width >= 600 ? 28 : 22
                    font.weight: Font.DemiBold
                    color: Theme.color.onSurfaceColor
                    wrapMode: Text.Wrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                }
                Text {
                    text: i18n.t("common.songCount", page.filteredTracks ? page.filteredTracks.length : 0)
                    font.pixelSize: 14
                    color: Theme.color.onSurfaceVariantSummary
                }
                Button {
                    text: i18n.t("playlist.playAll")
                    icon: "play_arrow"
                    enabled: page.filteredTracks && page.filteredTracks.length > 0
                    onClicked: {
                        var first = page.filteredTracks[0]
                        var all = player.openSourcePlaylistId !== "" ? player.sourcePlaylistTracks : player.playlistTracks
                        for (var i = 0; i < all.length; i++) {
                            if (String(all[i].id) === String(first.id)) { player.playPlaylistTrack(i); return }
                        }
                    }
                }
            }
        }

        TextField {
            id: pfField
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 8
            visible: !player.playlistLoading
            label: i18n.t("playlist.searchInside")
            leadingIcon: "search"
            text: page.filterText
            onTextChanged: page.filterText = text
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            VirtualSongList {
                id: tracks
                anchors.fill: parent
                // Drop the row delegates when the detail page is closed (see
                // QueuePage): an invisible detail otherwise keeps the whole
                // playlist's SongRows alive after you return home.
                list: page.visible ? page.filteredTracks : null
                // Long-press a track → add to another playlist, and (in your own
                // playlist) remove it from this one. Not login-gated: adding to the queue
                // (local list) works signed-out too.
                songMenu: true
                ownedPlaylist: player.playlistOwned
                showOfflineBadge: player.playlistOffline
                // Own playlist, on a source that can save an order, and unfiltered —
                // a drop position means nothing while rows are hidden.
                reorderable: player.sourcePlaylistReorderable && page.filterText === ""
                onMoveRequested: player.moveSourcePlaylistTrack(tracks.moveFrom, tracks.moveTo)
                onReorderCommitted: player.commitSourcePlaylistOrder()
                onActivated: {
                    var selected = tracks.list[tracks.activatedIndex]
                    var all = player.openSourcePlaylistId !== ""
                              ? player.sourcePlaylistTracks : player.playlistTracks
                    if (!selected || !all) return
                    for (var i = 0; i < all.length; i++) {
                        if (String(all[i].id) === String(selected.id)) {
                            player.playPlaylistTrack(i)
                            return
                        }
                    }
                }
            }

            LoadingIndicator {
                objectName: "detailLoadingIndicator"
                anchors.centerIn: parent
                visible: player.playlistLoading && (!page.filteredTracks || page.filteredTracks.length === 0)
                running: visible
            }

            Text {
                anchors.centerIn: parent
                visible: !player.playlistLoading && page.filterText !== ""
                         && page.filteredTracks && page.filteredTracks.length === 0
                text: i18n.t("playlist.noMatches")
                fontSize: 16
                color: Theme.color.onSurfaceVariantColor
            }
        }
    }

    // Destinations for the follow button: every local playlist, toggling, plus a
    // "new playlist" entry that names itself after this playlist.
    Menu {
        id: followMenu
        outlined: true
        function rebuild() {
            var sourceId = "" + player.openSourcePlaylistId
            var items = []
            var lists = player.localPlaylists
            var n = lists ? lists.length : 0
            for (var i = 0; i < n; i++) items.push(followMenu._item(sourceId, lists[i]))
            if (n > 0) items.push({ type: "separator" })
            items.push({ text: i18n.t("playlist.local.newPlaylist"), icon: "playlist_add",
                         action: followMenu._newAction(sourceId) })
            followMenu.model = items
        }
        function _item(sourceId, pl) {
            var pid = "" + pl.id
            var followed = player.isSourcePlaylistFollowed(pid, sourceId)
            return {
                text: pl.name,
                icon: followed ? "check" : "queue_music",
                action: followed ? followMenu._unfollow(pid, sourceId)
                                 : followMenu._follow(pid, sourceId)
            }
        }
        function _follow(pid, sourceId) {
            return function() { player.followSourcePlaylist(pid, sourceId) }
        }
        function _unfollow(pid, sourceId) {
            return function() { player.unfollowSourcePlaylist(pid, sourceId) }
        }
        function _newAction(sourceId) {
            return function() { player.followSourcePlaylistInNewLocalPlaylist(sourceId) }
        }
    }

    // Delete confirmation. On accept the controller removes it and refreshes the library;
    // we drill back out since this playlist no longer exists.
    Dialog {
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        id: deleteDialog
        icon: "delete"
        title: i18n.t("playlist.delete.title")
        text: i18n.t("playlist.delete.body", player.playlistTitle)
        acceptText: i18n.t("common.delete")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            if (player.openSourcePlaylistId !== "")
                player.deleteMediaPlaylist(player.openSourcePlaylistId)
            else player.deletePlaylist(player.openPlaylistId)
            page.back()
        }
    }

}
