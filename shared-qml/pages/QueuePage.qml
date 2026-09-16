import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// The now-playing queue. The current track is highlighted (VirtualSongList
// highlights index === player.index, which is the queue position). Tap a row to
// jump; the trailing close button drops it from the queue.
//
// This page used to carry a second tab holding the single custom "play later"
// list. That list became the first of the user's local playlists (see
// LocalPlaylistStore's migration), which live on 我的 — keeping a second,
// separate entry point to the same idea here would just be two names for one
// thing.
Rectangle {
    id: page
    signal back()
    signal home()
    color: Theme.color.surface

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
                text: i18n.t("queue.count",
                             player.queueTracks ? player.queueTracks.length : 0)
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
        }

        VirtualSongList {
            id: q
            Layout.fillWidth: true
            Layout.fillHeight: true
            // Only hold row delegates while the page is actually shown (visible
            // tracks the open/close fade). A closed queue page is invisible but
            // still in the tree, so binding straight to the list kept N hidden
            // SongRow delegates alive after playing a big playlist — steady GC
            // pressure on every other screen. Null when closed disposes them.
            list: page.visible ? player.queueTracks : null
            isLocal: true
            removable: true
            // Long-press → add to a local playlist (+ provider actions for online
            // sourced rows); a local file sitting in the live queue gets the smaller
            // local-only menu — see SongContextMenu's filePath branch.
            songMenu: true
            onActivated: player.playQueueIndex(q.activatedIndex)
            onRemoveRequested: player.removeFromQueue(q.removeIndex)
        }
    }
    EmptyState {
        anchors.centerIn: parent
        visible: q.count === 0
        icon: "queue_music"
        title: i18n.t("queue.empty.title")
        message: i18n.t("queue.empty.desc")
    }

}
