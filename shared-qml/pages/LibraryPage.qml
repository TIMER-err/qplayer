import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

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
        list: player.sourceContentActive ? player.sourceMyPlaylists : player.myPlaylists
        onOpenPlaylist: { page.pendingPlaylist = grid.pendingPlaylist; page.openPlaylist() }
    }

    // New-playlist entry point.
    FAB {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: 16
        anchors.bottomMargin: 16
        visible: player.loggedIn && (!player.sourceContentActive
                                     || player.sourcePlaylistMutationAvailable)
        type: "standard"
        icon: "add"
        onClicked: { nameField.text = ""; createDialog.open() }
    }

    Dialog {
        id: createDialog
        icon: "playlist_add"
        title: "新建歌单"
        acceptText: "创建"
        rejectText: "取消"
        onAccepted: player.createPlaylist(nameField.text)

        TextField {
            id: nameField
            anchors.left: parent.left
            anchors.right: parent.right
            type: "outlined"
            label: "歌单名称"
            onAccepted: { createDialog.accepted(); createDialog.close() }
        }
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
            type: "filled"; text: "登录音源账号"
            onClicked: page.requestLogin()
        }
    }
}
