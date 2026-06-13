import QtQuick
import QtQuick.Layouts
import md3.Core

// Bottom transport bar: cover placeholder + title/artist, a tappable progress
// line, and like / prev / play-pause / next. Spans the full window width.
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

    RowLayout {
        anchors.fill: parent
        anchors.topMargin: 3
        anchors.leftMargin: 12
        anchors.rightMargin: 4
        spacing: 12

        Rectangle {
            Layout.alignment: Qt.AlignVCenter
            width: 52; height: 52; radius: 8
            color: Theme.color.surfaceContainerHighest
            Text {
                anchors.centerIn: parent
                text: "music_note"
                font.family: Theme.iconFont.name
                font.pixelSize: 26
                color: Theme.color.onSurfaceVariantColor
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignVCenter
            spacing: 2
            Text {
                Layout.fillWidth: true
                text: player.title.length > 0 ? player.title : "未播放"
                color: Theme.color.onSurfaceColor
                fontSize: 15
                elide: Text.ElideRight
            }
            Text {
                Layout.fillWidth: true
                text: player.artist + (player.durationMs > 0
                      ? "   " + fmt(player.positionMs) + " / " + fmt(player.durationMs) : "")
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
                elide: Text.ElideRight
            }
        }

        IconButton {
            Layout.alignment: Qt.AlignVCenter
            type: "standard"; icon: "lyrics"
            onClicked: mini.lyricsRequested()
        }
        IconButton {
            Layout.alignment: Qt.AlignVCenter
            type: "standard"
            icon: player.currentLiked ? "favorite" : "favorite_border"
            onClicked: player.toggleLike()
        }
        IconButton {
            Layout.alignment: Qt.AlignVCenter
            type: "standard"; icon: "skip_previous"; onClicked: player.prev()
        }
        IconButton {
            Layout.alignment: Qt.AlignVCenter
            type: "filled"
            icon: player.playing ? "pause" : "play_arrow"
            onClicked: player.toggle()
        }
        IconButton {
            Layout.alignment: Qt.AlignVCenter
            type: "standard"; icon: "skip_next"; onClicked: player.next()
        }
    }
}
