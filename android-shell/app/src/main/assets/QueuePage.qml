import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Now-playing queue: header with back, then the live queue. The current track
// is highlighted (VirtualSongList highlights index === player.index, which is
// the queue position). Tap a row to jump; the trailing close button removes it.
Rectangle {
    id: page
    signal back()
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
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: "播放队列 (" + (player.queueTracks ? player.queueTracks.length : 0) + ")"
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
            // still in the tree, so binding straight to queueTracks kept N hidden
            // SongRow delegates alive after playing a big playlist — steady GC
            // pressure on every other screen. Null when closed disposes them.
            list: page.visible ? player.queueTracks : null
            isLocal: true
            removable: true
            onActivated: player.playQueueIndex(q.activatedIndex)
            onRemoveRequested: player.removeFromQueue(q.removeIndex)
        }
    }
}
