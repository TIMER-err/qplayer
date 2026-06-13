import QtQuick
import md3.Core
import "."

// Bottom transport bar: cover placeholder + title/artist, a tappable progress
// line, and like / prev / play-pause / next. Spans the full window width.
//
// Plain anchors — NOT nested RowLayout/ColumnLayout. This bar is always visible
// and the play clock sets player.positionMs ~5x/s; every such set bumps the
// engine change version and forces a whole-tree settleLayout that frame. With
// Layout containers here, that relayout re-ran their measure/fill-distribution
// passes 5x/s (and on every list-scroll frame that coincided), so anchors keep
// the unavoidable relayout cheap.
Rectangle {
    id: mini

    signal lyricsRequested()

    function fmt(ms) {
        if (ms <= 0) return "0:00";
        var s = Math.floor(ms / 1000);
        var m = Math.floor(s / 60);
        var r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    implicitHeight: 84
    color: Theme.color.surfaceContainerHigh

    // progress line along the very top edge
    Rectangle {
        id: track
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 3
        color: Theme.color.surfaceContainerHighest

        Rectangle {
            anchors.left: parent.left
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            width: player.durationMs > 0
                   ? parent.width * Math.min(1, player.positionMs / player.durationMs) : 0
            color: Theme.color.primary
        }
        MouseArea {
            anchors.fill: parent
            anchors.topMargin: -10
            anchors.bottomMargin: -10
            onClicked: {
                if (player.durationMs > 0)
                    player.seek(Math.round(mouseX / width * player.durationMs));
            }
        }
    }

    // Right-side transport cluster, anchored right-to-left so the title/artist
    // region can fill the gap to its left.
    IconButton {
        id: nextBtn
        anchors.right: parent.right
        anchors.rightMargin: 4
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: 1
        type: "standard"; icon: "skip_next"; onClicked: player.next()
    }
    IconButton {
        id: playBtn
        anchors.right: nextBtn.left
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: 1
        type: "filled"
        icon: player.playing ? "pause" : "play_arrow"
        onClicked: player.toggle()
    }
    IconButton {
        id: likeBtn
        anchors.right: playBtn.left
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: 1
        type: "standard"
        icon: player.currentLiked ? "favorite" : "favorite_border"
        onClicked: player.toggleLike()
    }

    CoverImage {
        id: cover
        anchors.left: parent.left
        anchors.leftMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: 1
        width: 52; height: 52
        radius: 8
        source: player.coverUrl
    }

    // Cover + title/artist: tap anywhere here to open the lyric page.
    Item {
        anchors.left: cover.right
        anchors.leftMargin: 12
        anchors.right: likeBtn.left
        anchors.rightMargin: 4
        anchors.top: track.bottom
        anchors.bottom: parent.bottom

        Text {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.verticalCenter
            anchors.bottomMargin: 1
            text: player.title.length > 0 ? player.title : "未播放"
            color: Theme.color.onSurfaceColor
            fontSize: 15
            elide: Text.ElideRight
        }
        Text {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.verticalCenter
            anchors.topMargin: 2
            text: player.artist + (player.durationMs > 0
                  ? "   " + fmt(player.positionMs) + " / " + fmt(player.durationMs) : "")
            color: Theme.color.onSurfaceVariantColor
            fontSize: 12
            elide: Text.ElideRight
        }

        MouseArea {
            anchors.fill: parent
            onClicked: mini.lyricsRequested()
        }
    }
}
