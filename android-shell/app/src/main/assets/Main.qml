import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Player screen. Layout is driven by ColumnLayout/RowLayout + Layout.* — no
// hand-coded x/y. Icons are Material Symbols glyphs (IconButton.icon), not
// emoji. Everything reactive binds to the `player` context global.
Rectangle {
    id: root
    color: Theme.color.surface

    property bool showLog: false

    function fmt(ms) {
        if (ms <= 0) return "0:00";
        var s = Math.floor(ms / 1000);
        var m = Math.floor(s / 60);
        var r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // --- search bar -------------------------------------------------
        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 76
            color: Theme.color.surfaceContainer

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 4
                spacing: 4

                TextField {
                    id: query
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignVCenter
                    type: "filled"
                    leadingIcon: "search"
                    label: "搜索网易云歌曲"
                    onAccepted: player.search(query.text)
                }

                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    type: "filled"
                    icon: "search"
                    onClicked: player.search(query.text)
                }

                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    type: "standard"
                    icon: "bug_report"
                    onClicked: root.showLog = !root.showLog
                }
            }
        }

        // --- scrollable content -----------------------------------------
        // Column (positioner) gives a definite height for contentHeight, and
        // the lists bind their List Property directly as the model so a new
        // result set re-binds modelData even when the row count is unchanged.
        Flickable {
            id: scroll
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentHeight: content.height

            Column {
                id: content
                width: scroll.width
                spacing: 0

                SectionHeader {
                    width: content.width
                    text: "搜索结果"
                    visible: player.resultCount > 0
                }

                Repeater {
                    model: player.searchResults
                    SongRow {
                        width: content.width
                        rowTitle: modelData.name
                        rowArtist: modelData.artist
                        onActivated: player.playSearchResult(index)
                    }
                }

                SectionHeader {
                    width: content.width
                    text: "本地音乐"
                    visible: player.libraryCount > 0
                }

                Repeater {
                    model: player.tracks
                    SongRow {
                        width: content.width
                        rowTitle: modelData.title
                        rowArtist: modelData.artist
                        highlighted: index === player.index
                        onActivated: player.play(index)
                    }
                }
            }
        }

        // --- mini player ------------------------------------------------
        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 80
            color: Theme.color.surfaceContainerHigh

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 16
                anchors.rightMargin: 4
                spacing: 8

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
                    type: "standard"
                    icon: player.currentLiked ? "favorite" : "favorite_border"
                    onClicked: player.toggleLike()
                }
                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    type: "standard"
                    icon: "skip_previous"
                    onClicked: player.prev()
                }
                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    type: "filled"
                    icon: player.playing ? "pause" : "play_arrow"
                    onClicked: player.toggle()
                }
                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    type: "standard"
                    icon: "skip_next"
                    onClicked: player.next()
                }
            }
        }
    }

    // --- log overlay ----------------------------------------------------
    Rectangle {
        visible: root.showLog
        anchors.fill: parent
        color: Theme.color.surfaceContainerHighest

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 8
                spacing: 4
                Text {
                    Layout.fillWidth: true
                    text: "日志"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }
                IconButton { type: "standard"; icon: "delete"; onClicked: player.clearLog() }
                IconButton { type: "standard"; icon: "close"; onClicked: root.showLog = false }
            }

            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.margins: 12
                clip: true
                contentHeight: logText.implicitHeight
                Text {
                    id: logText
                    width: parent.width
                    text: player.logText
                    color: Theme.color.onSurfaceColor
                    fontSize: 12
                    wrapMode: Text.WrapAnywhere
                }
            }
        }
    }
}
