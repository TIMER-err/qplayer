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
        list: player.tracks
        isLocal: true
        onActivated: player.play(local.activatedIndex)
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
