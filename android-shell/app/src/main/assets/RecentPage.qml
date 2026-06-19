import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 最近: netease listen history (signed in).
Item {
    id: page
    signal requestLogin()

    VirtualSongList {
        id: recent
        anchors.fill: parent
        visible: player.loggedIn
        // Same guard as LocalPage/QueuePage: this page is always in the tree, so a
        // bare bind keeps a SongRow per history entry alive while 最近 is hidden.
        list: page.visible ? player.recentSongs : null
        onActivated: player.playRecentSong(recent.activatedIndex)
    }

    ColumnLayout {
        anchors.centerIn: parent
        spacing: 12
        visible: !player.loggedIn
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: "登录后查看最近播放"
            color: Theme.color.onSurfaceVariantColor
            fontSize: 15
        }
        Button {
            Layout.alignment: Qt.AlignHCenter
            type: "filled"; text: "扫码登录"
            onClicked: page.requestLogin()
        }
    }
}
