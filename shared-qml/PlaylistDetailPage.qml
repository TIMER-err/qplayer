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
            // Cover thumbnail. Owned playlists can tap it to change the cover; the
            // pencil badge is the only hint (no separate header button, to keep the
            // icon row from getting crowded) — matches SongRow's tap-target style.
            Item {
                Layout.alignment: Qt.AlignVCenter
                Layout.preferredWidth: 40
                Layout.preferredHeight: 40
                visible: !player.playlistLoading

                CoverImage {
                    anchors.fill: parent
                    radius: 8
                    source: player.playlistCoverPath
                }
                MouseArea {
                    anchors.fill: parent
                    enabled: player.playlistOwned
                    // Android has a real gallery picker (native intent, no QML dialog
                    // needed); desktop has no such picker, so it types a local path
                    // instead — same platform check used elsewhere (settings.musicFolder
                    // only exists on desktop's AppSettings).
                    onClicked: {
                        if (typeof settings.musicFolder === "undefined") {
                            player.pickPlaylistCover(player.openPlaylistId)
                        } else {
                            coverPathField.text = ""
                            coverDialog.open()
                        }
                    }
                }
                Rectangle {
                    visible: player.playlistOwned
                    width: 18; height: 18
                    radius: 9
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    anchors.margins: -2
                    color: Theme.color.primary
                    Text {
                        anchors.centerIn: parent
                        text: "edit"
                        font.family: Theme.iconFont.name
                        font.pixelSize: 12
                        color: Theme.color.onPrimaryColor
                    }
                }
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
                list: page.visible ? player.playlistTracks : null
                // Long-press a track → add to another playlist, and (in your own
                // playlist) remove it from this one.
                songMenu: player.loggedIn
                ownedPlaylist: player.playlistOwned
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
            player.deletePlaylist(player.openPlaylistId)
            page.back()
        }
    }

    // Custom cover, desktop path: paste/type a local image path (same convention
    // as the "本地音乐目录"/"缓存目录" settings — no native file picker on desktop)
    // and preview it before applying. Android instead uses a native gallery picker
    // (see the cover thumbnail's MouseArea above) and skips this dialog entirely.
    // Netease re-encodes/resizes on its end, so no local validation beyond "does
    // something decode".
    Dialog {
        id: coverDialog
        icon: "image"
        title: "更换歌单封面"
        acceptText: "应用"
        rejectText: "取消"
        showAcceptButton: coverPathField.text.trim() !== ""
        onAccepted: player.setPlaylistCover(player.openPlaylistId, coverPathField.text)

        ColumnLayout {
            width: parent.width
            spacing: 12

            // Plain letterboxed Image, not CoverImage's PreserveAspectCrop: qml4j
            // mis-scales/crops non-square local sources (confirmed — a wide photo
            // stretched past the box on the right instead of being cropped square).
            // PreserveAspectFit only needs a uniform scale-to-fit, no source-rect
            // crop math, so it doesn't hit that bug; clip guards any overflow anyway.
            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 160
                Layout.preferredHeight: 160
                radius: 12
                color: Theme.color.surfaceContainerHighest
                clip: true

                Image {
                    anchors.fill: parent
                    source: coverPathField.text.trim()
                    fillMode: "PreserveAspectFit"
                }
            }
            TextField {
                id: coverPathField
                Layout.fillWidth: true
                type: "outlined"
                label: "图片文件路径"
                onAccepted: { coverDialog.accepted(); coverDialog.close() }
            }
            // The TextField's own TextInput doesn't scroll to keep the caret visible
            // past its width (qml4j gap, same family as the missing Delete-key
            // support), so a long path silently hides whatever you just typed at the
            // end. Mirror the live value here, wrapped, so it's always fully visible.
            Text {
                Layout.fillWidth: true
                visible: coverPathField.text.length > 0
                text: coverPathField.text
                wrapMode: Text.WrapAnywhere
                color: Theme.color.onSurfaceVariantColor
                font.family: Theme.typography.bodySmall.family
                font.pixelSize: Theme.typography.bodySmall.size
            }
        }
    }
}
