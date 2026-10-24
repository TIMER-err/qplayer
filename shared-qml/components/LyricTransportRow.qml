import QtQuick
import miuix.Core

// Three clusters so the pause button sits on the row's true centre:
//   left  = play-mode
//   mid   = prev / pause / next
//   right = like + queue
// A single 6-wide Row cannot centre an odd-positioned filled button.
Item {
    id: row
    signal queueRequested()
    property real gap: 18
    height: 40
    implicitHeight: 40
    implicitWidth: 40 * 6 + gap * 5

    Row {
        anchors.left: parent.left
        anchors.verticalCenter: parent.verticalCenter
        spacing: row.gap
        IconButton {
            type: "standard"
            icon: player.playMode === 1 ? "shuffle"
                  : (player.playMode === 2 ? "repeat_one" : "repeat")
            contentColor: player.playMode === 0 ? "#99FFFFFF" : "#FF82B1FF"
            onClicked: player.cyclePlayMode()
        }
    }
    Row {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        spacing: row.gap
        IconButton {
            type: "standard"; icon: "skip_previous"
            contentColor: "#FFFFFFFF"
            onClicked: player.prev()
        }
        IconButton {
            type: "filled"
            icon: player.playing ? "pause" : "play_arrow"
            onClicked: player.toggle()
        }
        IconButton {
            type: "standard"; icon: "skip_next"
            contentColor: "#FFFFFFFF"
            onClicked: player.next()
        }
    }
    Row {
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        spacing: row.gap
        IconButton {
            type: "standard"
            enabled: player.currentLikeable
            icon: player.currentLiked ? "favorite" : "favorite_border"
            contentColor: player.currentLiked ? "#FFFF5277" : "#99FFFFFF"
            onClicked: player.toggleLike()
        }
        IconButton {
            type: "standard"
            icon: "queue_music"
            contentColor: "#FFFFFFFF"
            onClicked: row.queueRequested()
        }
    }
}
