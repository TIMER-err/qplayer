import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 我的: the signed-in user's playlists (favourites + created). Tapping one
// opens its detail. Prompts to log in when signed out.
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()
    signal requestLogin()

    Flickable {
        anchors.fill: parent
        clip: true
        contentHeight: col.height
        visible: player.loggedIn

        Column {
            id: col
            width: parent.width
            SectionHeader { width: col.width; text: "我的歌单" }
            Flow {
                width: col.width
                Repeater {
                    model: player.myPlaylists
                    PlaylistCard {
                        name: modelData.name
                        count: modelData.trackCount
                        onClicked: { page.pendingPlaylist = modelData; page.openPlaylist() }
                    }
                }
            }
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
            type: "filled"; text: "扫码登录"
            onClicked: page.requestLogin()
        }
    }
}
