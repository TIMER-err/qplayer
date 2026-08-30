import QtQuick
import QtQuick.Layouts
import md3.Core
import "../components"

// Offline-cached online songs (cache/audio/*.cache). Opened by the top-bar
// download icon, which calls player.refreshCachedSongs() right before opening,
// so player.cachedSongs is a fresh snapshot of what's actually on disk. Same
// shape as the queue overlay: full-screen surface with a back button + title,
// and a VirtualSongList whose rows show the cached cover thumbnail (local file
// where available) + title/artist. Tapping a row plays it from disk — the
// playback pipeline's cached-audio fast path serves diskCache.getAudio without
// touching the network.
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
                text: "已缓存歌曲 (" + (player.cachedSongs ? player.cachedSongs.length : 0) + ")"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
        }

        VirtualSongList {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            // Only hold row delegates while the page is actually shown (same guard
            // as QueuePage): a closed page is invisible but still in the tree.
            list: page.visible ? player.cachedSongs : null
            isLocal: true
            highlightCurrent: false
            songMenu: true
            cacheList: true
            onActivated: player.playCachedSong(list.activatedIndex)
        }
    }
}
