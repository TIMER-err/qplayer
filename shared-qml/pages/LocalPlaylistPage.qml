import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// A local playlist: songs the user gathered from any mix of sources, plus the
// source playlists this one follows. Opening the page already triggers a sync of
// those (the controller applies its own cooldown), so the header's sync button is
// only for forcing one — it is never the only way to get fresh content.
Rectangle {
    id: page
    signal back()
    signal home()
    color: Theme.color.surface

    property string playlistId: player.openLocalPlaylistId
    property var tracksList: player.localPlaylistTracks
    property var subscriptions: player.localPlaylistSubscriptions
    property bool showSources: false

    onPlaylistIdChanged: {
        tracks.contentY = 0
        page.filterText = ""
        page.showSources = false
    }

    property string filterText: ""
    property var filteredTracks: {
        var all = page.tracksList
        if (!all) return all
        var q = page.filterText.trim().toLowerCase()
        if (q === "") return all
        var out = []
        for (var i = 0; i < all.length; i++) {
            var t = all[i]
            var hit = (t.title && t.title.toLowerCase().indexOf(q) >= 0)
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
                text: i18n.t("playlist.local.title")
                color: Theme.color.onSurfaceColor
                font.pixelSize: 24
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }
            IconButton {
                objectName: "localPlaylistSyncButton"
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: page.subscriptions && page.subscriptions.length > 0
                enabled: !player.localPlaylistSyncing
                icon: "sync"
                contentColor: player.localPlaylistSyncing
                              ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                onClicked: player.refreshLocalPlaylist(page.playlistId)
            }
            IconButton {
                objectName: "localPlaylistExportButton"
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.playlistTransferAvailable
                icon: "file_download"
                onClicked: player.requestLocalPlaylistExport(page.playlistId)
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                icon: "edit"
                onClicked: {
                    renameField.text = player.localPlaylistTitle
                    renameDialog.open()
                }
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                icon: "delete"
                onClicked: deleteDialog.open()
            }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.topMargin: 8
            Layout.bottomMargin: 16
            spacing: 16
            CoverImage {
                Layout.preferredWidth: page.width >= 600 ? 128 : 96
                Layout.preferredHeight: width
                radius: 20
                source: page.tracksList && page.tracksList.length > 0
                        ? (page.tracksList[0].coverThumbPath || page.tracksList[0].coverUrl || "")
                        : ""
                icon: "queue_music"
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                Text {
                    Layout.fillWidth: true
                    text: player.localPlaylistTitle
                    font.pixelSize: page.width >= 600 ? 28 : 22
                    font.weight: Font.DemiBold
                    color: Theme.color.onSurfaceColor
                    wrapMode: Text.Wrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                }
                Text {
                    text: player.localPlaylistSyncing
                          ? i18n.t("playlist.local.syncing")
                          : i18n.t("common.songCount",
                                   page.tracksList ? page.tracksList.length : 0)
                    font.pixelSize: 14
                    color: Theme.color.onSurfaceVariantSummary
                }
                Button {
                    text: i18n.t("playlist.playAll")
                    icon: "play_arrow"
                    enabled: page.tracksList && page.tracksList.length > 0
                    onClicked: player.playLocalPlaylist(page.playlistId)
                }
            }
        }

        // Followed source playlists, collapsed by default: this is reference
        // information about where the songs came from, not the content itself.
        Button {
            Layout.leftMargin: 16
            Layout.bottomMargin: 8
            visible: page.subscriptions && page.subscriptions.length > 0
            type: "text"
            icon: page.showSources ? "expand_less" : "expand_more"
            text: i18n.t("playlist.local.followedSources")
                  + " (" + (page.subscriptions ? page.subscriptions.length : 0) + ")"
            onClicked: page.showSources = !page.showSources
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 8
            spacing: 8
            visible: page.showSources
            Repeater {
                model: page.showSources ? page.subscriptions : null
                Card {
                    Layout.fillWidth: true
                    implicitHeight: 64
                    property var sub: modelData
                    CoverImage {
                        id: sourceCover
                        x: 12
                        y: 12
                        width: 40
                        height: 40
                        radius: 10
                        source: sub.artworkUrl
                        icon: "queue_music"
                    }
                    Text {
                        x: 64
                        y: 12
                        width: Math.max(0, parent.width - 64 - 52)
                        text: sub.name && sub.name.length > 0 ? sub.name : sub.sourcePlaylistId
                        color: Theme.color.onSurfaceColor
                        font.pixelSize: 15
                        elide: Text.ElideRight
                    }
                    Text {
                        x: 64
                        y: 34
                        width: Math.max(0, parent.width - 64 - 52)
                        text: {
                            if (sub.lastError && sub.lastError.length > 0) return sub.lastError
                            var who = sub.providerName && sub.providerName.length > 0
                                      ? sub.providerName : sub.provider
                            var count = i18n.t("playlist.local.followedCount", sub.trackCount)
                            return who + " · " + count
                        }
                        color: sub.lastError && sub.lastError.length > 0
                               ? Theme.color.error : Theme.color.onSurfaceVariantSummary
                        font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                    IconButton {
                        x: parent.width - 52
                        y: 12
                        type: "standard"
                        icon: "bookmark_remove"
                        onClicked: player.unfollowSourcePlaylist(page.playlistId, sub.sourcePlaylistId)
                    }
                }
            }
        }

        TextField {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 8
            visible: page.tracksList && page.tracksList.length > 8
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
                list: page.visible ? page.filteredTracks : null
                isLocal: true
                songMenu: true
                removable: true
                // Positions here are the playlist's own, not the live queue's.
                highlightCurrent: false
                onActivated: {
                    // The filter can reorder nothing but it does hide rows, so map
                    // the tapped row back to its index in the real playlist.
                    var selected = tracks.list[tracks.activatedIndex]
                    var all = page.tracksList
                    if (!selected || !all) return
                    for (var i = 0; i < all.length; i++) {
                        if (all[i] === selected) {
                            player.playLocalPlaylistIndex(page.playlistId, i)
                            return
                        }
                    }
                }
                onRemoveRequested: {
                    var selected = tracks.list[tracks.removeIndex]
                    var all = page.tracksList
                    if (!selected || !all) return
                    for (var i = 0; i < all.length; i++) {
                        if (all[i] === selected) {
                            player.removeFromLocalPlaylistAt(page.playlistId, i)
                            return
                        }
                    }
                }
            }

            EmptyState {
                anchors.centerIn: parent
                visible: !page.tracksList || page.tracksList.length === 0
                icon: "queue_music"
                title: i18n.t("playlist.local.empty.title")
                message: i18n.t("playlist.local.empty.desc")
            }

            Text {
                anchors.centerIn: parent
                visible: page.filterText !== "" && page.filteredTracks
                         && page.filteredTracks.length === 0
                         && page.tracksList && page.tracksList.length > 0
                text: i18n.t("playlist.noMatches")
                fontSize: 16
                color: Theme.color.onSurfaceVariantColor
            }
        }
    }

    Dialog {
        id: renameDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "edit"
        title: i18n.t("playlist.local.rename.title")
        acceptText: i18n.t("common.save")
        rejectText: i18n.t("common.cancel")
        onAccepted: player.renameLocalPlaylist(page.playlistId, renameField.text)

        TextField {
            id: renameField
            anchors.left: parent.left
            anchors.right: parent.right
            type: "outlined"
            label: i18n.t("library.create.hint")
            onAccepted: { renameDialog.accepted(); renameDialog.close() }
        }
    }

    Dialog {
        id: deleteDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "delete"
        title: i18n.t("playlist.delete.title")
        text: i18n.t("playlist.local.delete.body", player.localPlaylistTitle)
        acceptText: i18n.t("common.delete")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            player.deleteLocalPlaylist(page.playlistId)
            page.back()
        }
    }
}
