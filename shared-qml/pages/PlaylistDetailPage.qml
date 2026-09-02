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
    onLoadingWatchChanged: {
        if (player.playlistLoading) {
            tracks.contentY = 0
            page.filterText = ""
        }
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

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 48
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            color: "transparent"
            visible: !player.playlistLoading

            Text {
                id: pfSearchIcon
                anchors.left: parent.left
                anchors.leftMargin: 13
                anchors.verticalCenter: parent.verticalCenter
                text: "search"
                font.family: Theme.iconFont.name
                font.pixelSize: 20
                color: Theme.color.onSurfaceVariantColor
            }

            Item {
                id: pfInputArea
                anchors.left: pfSearchIcon.right
                anchors.leftMargin: 8
                anchors.right: parent.right
                anchors.rightMargin: 36
                anchors.top: parent.top
                anchors.bottom: parent.bottom

                property bool isFloating: pfField.activeFocus || pfField.text.length > 0

                Text {
                    id: pfFloatLabel
                    x: 0
                    y: pfInputArea.isFloating ? -7 : (pfInputArea.height - height) / 2
                    text: "搜索歌单内歌曲"
                    color: Theme.color.onSurfaceVariantColor
                    opacity: pfInputArea.isFloating ? 0.8 : 0.7
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: pfInputArea.isFloating ? 11 : 15
                    Behavior on y { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
                    Behavior on font.pixelSize { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
                }

                TextInput {
                    id: pfField
                    anchors.fill: parent
                    verticalAlignment: TextInput.AlignVCenter
                    color: Theme.color.onSurfaceColor
                    font.pixelSize: 15
                    font.family: Theme.typography.bodyLarge.family
                    selectionColor: Theme.color.primary
                    selectedTextColor: Theme.color.onPrimaryColor
                    clip: true
                    text: page.filterText
                    onTextChanged: page.filterText = text
                }
            }

            OutlinedBorder {
                anchors.fill: parent
                cornerRadius: height / 2
                strokeWidth: 1
                strokeColor: Theme.color.outline
                notchVisible: pfInputArea.isFloating
                notchX: pfInputArea.x - 4
                notchWidth: pfFloatLabel.width + 8
            }
            OutlinedBorder {
                anchors.fill: parent
                cornerRadius: height / 2
                strokeWidth: 2
                strokeColor: Theme.color.primary
                notchVisible: pfInputArea.isFloating
                notchX: pfInputArea.x - 4
                notchWidth: pfFloatLabel.width + 8
                opacity: pfField.activeFocus ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 150 } }
            }

            IconButton {
                visible: pfField.text.length > 0
                type: "standard"; icon: "close"
                anchors.right: parent.right
                anchors.rightMargin: 2
                anchors.verticalCenter: parent.verticalCenter
                onClicked: { pfField.text = ""; page.filterText = "" }
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
                list: page.visible ? page.filteredTracks : null
                // Long-press a track → add to another playlist, and (in your own
                // playlist) remove it from this one. Not login-gated: "加入播放列表"
                // (local list) works signed-out too.
                songMenu: true
                ownedPlaylist: player.playlistOwned
                showOfflineBadge: player.playlistOffline
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
                anchors.centerIn: parent
                visible: player.playlistLoading
                running: player.playlistLoading
                withContainer: true
                size: 56
            }

            Text {
                anchors.centerIn: parent
                visible: !player.playlistLoading && page.filterText !== ""
                         && page.filteredTracks && page.filteredTracks.length === 0
                text: "没有匹配的歌曲"
                fontSize: 16
                color: Theme.color.onSurfaceVariantColor
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
