import QtQuick
import md3.Core
import "."

// QML chrome for the lyric page, composited on top of the host-drawn fluid
// backdrop + per-syllable lyrics. Transparent everywhere except the title band
// (top) and the transport band (bottom), so the host lyrics show through the
// middle. Visibility/opacity follow player.lyricSlide (published by the host) so
// it fades in lockstep with the host layer.
Item {
    id: overlay

    // Smoothed playback fraction. player.positionMs lands at ~5 Hz, which makes the
    // wavy bar step; a Behavior eases between updates (per frame) so it flows. (Behavior
    // on a bound property animates in qml4j -- same as BottomNav's pill color.)
    property real seekFrac: player.durationMs > 0
                            ? Math.min(1, player.positionMs / player.durationMs) : 0
    Behavior on seekFrac { NumberAnimation { duration: 220; easing.type: Easing.Linear } }

    function fmt(ms) {
        if (ms <= 0) return "0:00";
        var s = Math.floor(ms / 1000), m = Math.floor(s / 60), r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    // Swallow taps on the empty (lyrics) area so they don't leak through.
    MouseArea { anchors.fill: parent }

    // --- top: dismiss + title + artist ---------------------------------
    IconButton {
        id: backBtn
        anchors.top: parent.top
        anchors.topMargin: settings.topInset + 6
        anchors.left: parent.left
        anchors.leftMargin: 6
        type: "standard"
        icon: "expand_more"
        contentColor: "#FFFFFFFF"
        onClicked: player.setLyricsOpen(false)
    }

    Text {
        id: titleText
        anchors.top: backBtn.bottom
        anchors.topMargin: 2
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.title
        color: "#FFFFFFFF"
        font.family: Theme.typography.titleLarge.family
        font.pixelSize: 22
        wrapMode: Text.WordWrap
    }
    Text {
        anchors.top: titleText.bottom
        anchors.topMargin: 4
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.artist
        color: "#B3FFFFFF"
        fontSize: 14
        elide: Text.ElideRight
    }

    // --- bottom: transport --------------------------------------------
    Item {
        id: transport
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.bottomMargin: settings.bottomInset + 12
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        height: 120

        // progress (md3 wavy) + seek
        LinearProgress {
            id: progress
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            wavy: true
            value: overlay.seekFrac
        }
        MouseArea {
            anchors.fill: progress
            anchors.topMargin: -10
            anchors.bottomMargin: -10
            onPressed: if (player.durationMs > 0)
                           player.seek(Math.round(mouseX / width * player.durationMs))
            onPositionChanged: if (pressed && player.durationMs > 0)
                                   player.seek(Math.round(Math.max(0, Math.min(width, mouseX)) / width * player.durationMs))
        }
        Text {
            anchors.left: parent.left
            anchors.top: progress.bottom
            anchors.topMargin: 6
            text: overlay.fmt(player.positionMs)
            color: "#B3FFFFFF"
            fontSize: 11
        }
        Text {
            anchors.right: parent.right
            anchors.top: progress.bottom
            anchors.topMargin: 6
            text: overlay.fmt(player.durationMs)
            color: "#B3FFFFFF"
            fontSize: 11
        }

        // transport buttons
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            spacing: 18
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
                icon: player.currentLiked ? "favorite" : "favorite_border"
                contentColor: player.currentLiked ? "#FFFF5277" : "#99FFFFFF"
                onClicked: player.toggleLike()
            }
        }
    }
}
