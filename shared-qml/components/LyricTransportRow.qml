import QtQuick
import miuix.Core
import design
import "."

// Lyric-page transport. Drawn over the host's dark fluid backdrop in every
// theme, so content is white and the play button is a solid white disc with a
// dark glyph (the strongest target on the page) rather than the Monet accent.
//
// Pause is pinned to the row centre and is ~1.25x the other buttons; prev/next
// sit at the same gap either side so the trio is a centred group. Play-mode
// hugs previous; like hugs next. Fit from (pause/2 + 2 buttons + 2 gaps) per
// half so like cannot run off-screen, then shrink every button if even a zero
// gap would overflow.
Item {
    id: row
    property real gap: 18
    height: playSize
    implicitHeight: 50
    implicitWidth: 40 * 4 + 50 + gap * 4

    // Half-row budget, in units of one side button: 2 side buttons + half the
    // 1.25x play button.
    readonly property real _units: 2 + 0.625
    readonly property real btnSize: {
        var half = width / 2
        if (row._units * 40 <= half) return 40
        return Math.max(1, half / row._units)
    }
    readonly property real playSize: Math.round(row.btnSize * 1.25)
    readonly property real fitGap: {
        var half = width / 2
        var wanted = Math.max(0, gap)
        var used = 2 * row.btnSize + row.playSize / 2
        if (used + 2 * wanted <= half) return wanted
        return Math.max(0, (half - used) / 2)
    }

    readonly property color fg: "#FFFFFFFF"
    readonly property color fgMuted: "#8CFFFFFF"

    IconButton {
        id: pauseBtn
        objectName: "lyricPauseButton"
        width: row.playSize; height: row.playSize
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        type: "filled"
        containerColor: row.fg
        contentColor: "#E6000000"
        icon: player.playing ? "pause" : "play_arrow"
        onClicked: { pausePop.restart(); player.toggle() }
    }
    IconButton {
        id: prevBtn
        objectName: "lyricPrevButton"
        width: row.btnSize; height: row.btnSize
        anchors.right: pauseBtn.left
        anchors.rightMargin: row.fitGap
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"; icon: "skip_previous"
        contentColor: row.fg
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
        contentColor: row.fg
        onClicked: player.next()
    }
    // Sequential repeat is the resting state (muted); shuffle / repeat-one are
    // "on" and read as full white, matching how the like heart lights up.
    IconButton {
        objectName: "lyricPlayModeButton"
        width: row.btnSize; height: row.btnSize
        anchors.right: prevBtn.left
        anchors.rightMargin: row.fitGap
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"
        icon: player.playMode === 1 ? "shuffle"
              : (player.playMode === 2 ? "repeat_one" : "repeat")
        contentColor: player.playMode === 0 ? row.fgMuted : row.fg
        onClicked: player.cyclePlayMode()
    }
    IconButton {
        id: likeBtn
        objectName: "lyricLikeButton"
        width: row.btnSize; height: row.btnSize
        anchors.left: nextBtn.right
        anchors.leftMargin: row.fitGap
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"
        enabled: player.currentLikeable
        icon: player.currentLiked ? "favorite" : "favorite_border"
        contentColor: !player.currentLikeable ? "#40FFFFFF"
                      : (player.currentLiked ? Ui.like : row.fgMuted)
        onClicked: { likePop.restart(); player.toggleLike() }
    }

    Pop { id: pausePop; target: pauseBtn; peak: 0.88 }
    Pop { id: likePop; target: likeBtn; peak: 1.3 }
}
