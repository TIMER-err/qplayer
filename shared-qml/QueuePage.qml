import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Now-playing queue + the local custom "play later" list, switched by a segmented
// button. The queue tab's current track is highlighted (VirtualSongList highlights
// index === player.index, which is the queue position); the custom tab never
// highlights (its positions don't correspond to the live queue). Tap a row to jump;
// the trailing close button removes it from whichever list is showing.
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    // false = live queue, true = custom playlist.
    property bool showCustom: false

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
                text: page.showCustom
                    ? ("播放列表 (" + (player.customPlaylistTracks ? player.customPlaylistTracks.length : 0) + ")")
                    : ("播放队列 (" + (player.queueTracks ? player.queueTracks.length : 0) + ")")
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
        }

        SegmentedButton {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 8
            buttons: [
                { text: "播放队列", selected: !page.showCustom },
                { text: "播放列表", selected: page.showCustom }
            ]
            onClicked: page.showCustom = (index === 1)
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
            list: page.visible ? (page.showCustom ? player.customPlaylistTracks : player.queueTracks) : null
            isLocal: true
            highlightCurrent: !page.showCustom
            removable: true
            // Long-press → custom-playlist add/remove (+ copy link etc. for netease-
            // sourced rows); a local file sitting in the live queue gets the smaller
            // local-only menu — see SongContextMenu's filePath branch.
            songMenu: true
            onActivated: page.showCustom
                ? player.playCustomPlaylistIndex(q.activatedIndex)
                : player.playQueueIndex(q.activatedIndex)
            onRemoveRequested: page.showCustom
                ? player.removeFromCustomPlaylistIndex(q.removeIndex)
                : player.removeFromQueue(q.removeIndex)
        }
    }
}
