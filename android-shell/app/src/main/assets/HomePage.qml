import QtQuick
import md3.Core
import "."

// 为我推荐: a greeting header over the recommended-playlists grid.
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()

    function greeting() {
        var h = new Date().getHours();
        if (h < 6) return "夜深了";
        if (h < 12) return "早上好";
        if (h < 14) return "中午好";
        if (h < 18) return "下午好";
        return "晚上好";
    }

    Text {
        id: hello
        anchors.left: parent.left
        anchors.leftMargin: 16
        anchors.top: parent.top
        height: 64
        verticalAlignment: Text.AlignVCenter
        text: page.greeting() + (player.loggedIn ? "，" + player.userName : "")
        color: Theme.color.onSurfaceColor
        fontSize: 26
    }

    PlaylistGrid {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: hello.bottom
        anchors.bottom: parent.bottom
        list: player.recommendPlaylists
        onOpenPlaylist: { page.pendingPlaylist = grid.pendingPlaylist; page.openPlaylist() }
        id: grid
    }

    Text {
        anchors.centerIn: parent
        visible: grid.count === 0
        text: "加载中…"
        color: Theme.color.onSurfaceVariantColor
        fontSize: 14
    }
}
