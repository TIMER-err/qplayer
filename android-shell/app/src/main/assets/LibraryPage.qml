import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 我的: the signed-in user's playlists. Tapping one opens its detail. Prompts to
// log in when signed out.
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()
    signal requestLogin()

    PlaylistGrid {
        id: grid
        anchors.fill: parent
        visible: player.loggedIn
        list: player.myPlaylists
        onOpenPlaylist: { page.pendingPlaylist = grid.pendingPlaylist; page.openPlaylist() }
    }

    ColumnLayout {
        anchors.centerIn: parent
        spacing: 12
        visible: !player.loggedIn
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: "登录后查看你的歌单"
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
