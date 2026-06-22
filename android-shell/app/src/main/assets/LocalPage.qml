import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 本地: tracks scanned from the device Music folder.
Item {
    id: page

    VirtualSongList {
        id: local
        anchors.fill: parent
        visible: player.libraryCount > 0
        // Guard with page.visible (see QueuePage): every page is instantiated at
        // startup and only toggled by visibility, but a bare `list: player.tracks`
        // still builds one SongRow per track the moment the startup scan populates
        // tracks — even while the 本地 tab is hidden behind 首页. A large device
        // library (thousands of files) then OOMs ~10s after launch with the user
        // sitting on 首页. Null while hidden builds nothing.
        list: page.visible ? player.tracks : null
        isLocal: true
        addable: true
        onActivated: player.play(local.activatedIndex)
        onAddRequested: player.addLocalTrackToQueue(local.addIndex)
    }

    Text {
        anchors.centerIn: parent
        visible: player.libraryCount === 0
        text: "未找到本地音乐\n把歌曲放进 Music 文件夹"
        horizontalAlignment: Text.AlignHCenter
        color: Theme.color.onSurfaceVariantColor
        fontSize: 15
    }
}
