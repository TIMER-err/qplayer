import QtQuick
import miuix.Core

// Pause is pinned to the row centre; prev/next sit at the same gap either
// side so the trio is a centred group. Play-mode hugs previous; like hugs
// next. Five buttons, matching upstream (the lyric-page queue action is gone).
// Fit from 2.5 buttons + 2 gaps from centre so like cannot run off-screen,
// then shrink the buttons if even a zero gap would overflow.
Item {
    id: row
    property real gap: 18
    height: btnSize
    implicitHeight: 40
    implicitWidth: 40 * 5 + gap * 4

    readonly property real btnSize: {
        var half = width / 2
        var wanted = Math.max(0, gap)
        if (2.5 * 40 + 2 * wanted <= half) return 40
        if (2.5 * 40 <= half) return 40
        return Math.max(1, half / 2.5)
    }
    readonly property real fitGap: {
        var half = width / 2
        var wanted = Math.max(0, gap)
        var b = row.btnSize
        if (2.5 * b + 2 * wanted <= half) return wanted
        return Math.max(0, (half - 2.5 * b) / 2)
    }

    IconButton {
        id: pauseBtn
        objectName: "lyricPauseButton"
        width: row.btnSize; height: row.btnSize
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        type: "filled"
        icon: player.playing ? "pause" : "play_arrow"
        onClicked: player.toggle()
    }
    IconButton {
        id: prevBtn
        objectName: "lyricPrevButton"
        width: row.btnSize; height: row.btnSize
        anchors.right: pauseBtn.left
        anchors.rightMargin: row.fitGap
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"; icon: "skip_previous"
        contentColor: "#FFFFFFFF"
        onClicked: player.prev()
    }
    IconButton {
        id: nextBtn
        objectName: "lyricNextButton"
        width: row.btnSize; height: row.btnSize
        anchors.left: pauseBtn.right
        anchors.leftMargin: row.fitGap
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"; icon: "skip_next"
        contentColor: "#FFFFFFFF"
        onClicked: player.next()
    }
    IconButton {
        objectName: "lyricPlayModeButton"
        width: row.btnSize; height: row.btnSize
        anchors.right: prevBtn.left
        anchors.rightMargin: row.fitGap
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"
        icon: player.playMode === 1 ? "shuffle"
              : (player.playMode === 2 ? "repeat_one" : "repeat")
        contentColor: player.playMode === 0 ? "#99FFFFFF" : "#FF82B1FF"
        onClicked: player.cyclePlayMode()
    }
    IconButton {
        objectName: "lyricLikeButton"
        width: row.btnSize; height: row.btnSize
        anchors.left: nextBtn.right
        anchors.leftMargin: row.fitGap
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"
        enabled: player.currentLikeable
        icon: player.currentLiked ? "favorite" : "favorite_border"
        contentColor: player.currentLiked ? "#FFFF5277" : "#99FFFFFF"
        onClicked: player.toggleLike()
    }
}
