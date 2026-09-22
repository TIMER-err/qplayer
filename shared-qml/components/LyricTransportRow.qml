import QtQuick
import miuix.Core

// The lyric page's five transport buttons. Shared by the two landscape chromes
// (the column beside the cover, and the full-width bottom band) so the row is
// described once -- LyricOverlay.qml compiles to a single JVM constructor and
// has a hard bytecode ceiling, so a second inline copy is worth avoiding.
Row {
    id: row
    // Default matches the classic column; the caller narrows it when space is tight.
    property real gap: 18
    spacing: row.gap

    IconButton {
        type: "standard"
        icon: player.playMode === 1 ? "shuffle"
              : (player.playMode === 2 ? "repeat_one" : "repeat")
        contentColor: player.playMode === 0 ? "#99FFFFFF" : "#FF82B1FF"
        onClicked: player.cyclePlayMode()
    }
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
    IconButton {
        type: "standard"
        enabled: player.currentLikeable
        icon: player.currentLiked ? "favorite" : "favorite_border"
        contentColor: player.currentLiked ? "#FFFF5277" : "#99FFFFFF"
        onClicked: player.toggleLike()
    }
}
