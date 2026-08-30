import QtQuick
import md3.Core
import "."

// Context actions that are valid for playlist cards from both 推荐 and 我的.
// The model is populated only while opening, keeping a grid of cards cheap while
// idle and ensuring every closure captures the current playlist id.
Menu {
    id: menu

    outlined: true
    property var playlistId: 0
    signal openRequested()

    function rebuild() {
        var pid = menu.playlistId
        if (!pid) {
            menu.model = []
            return
        }
        menu.model = [
            { text: "立即播放", icon: "play_arrow", action: menu._playAction(pid) },
            { text: "打开歌单", icon: "queue_music", action: menu._openAction() },
            { type: "separator" },
            { text: "复制链接", icon: "link", action: menu._copyAction(pid) }
        ]
    }

    function _playAction(pid) {
        return function() { player.playMediaPlaylist("" + pid) }
    }

    function _openAction() {
        return function() { menu.openRequested() }
    }

    function _copyAction(pid) {
        return function() {
            if (("" + pid).indexOf(":") >= 0) player.shareMedia("" + pid)
            else player.copyPlaylistLink(pid)
        }
    }
}
