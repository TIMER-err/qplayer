import QtQuick
import md3.Core

// First end-to-end music player screen. Everything reactive is bound to the
// `player` context global (PlayerController); taps call its methods. Layout:
// search bar on top, a scroll of netease results + local library, a mini
// player pinned to the bottom.
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

    // --- search bar -----------------------------------------------------
    Rectangle {
        id: searchBar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 72
        color: Theme.color.surfaceContainer

        TextField {
            id: query
            anchors.left: parent.left
            anchors.leftMargin: 12
            anchors.verticalCenter: parent.verticalCenter
            width: parent.width - 120
            placeholderText: "搜索网易云歌曲"
            onAccepted: player.search(query.text)
        }

        Button {
            type: "filled"
            text: "搜索"
            anchors.right: logBtn.left
            anchors.rightMargin: 8
            anchors.verticalCenter: parent.verticalCenter
            onClicked: player.search(query.text)
        }

        Button {
            id: logBtn
            type: "text"
            text: "日志"
            anchors.right: parent.right
            anchors.rightMargin: 8
            anchors.verticalCenter: parent.verticalCenter
            onClicked: root.showLog = !root.showLog
        }
    }

    // --- scrollable content --------------------------------------------
    Flickable {
        id: scroll
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: searchBar.bottom
        anchors.bottom: mini.top
        clip: true
        contentHeight: content.height

        Column {
            id: content
            width: scroll.width
            spacing: 0

            Text {
                visible: player.resultCount > 0
                x: 16; height: 40
                text: "搜索结果"
                color: Theme.color.primary
                fontSize: 18
            }

            Repeater {
                model: player.resultCount
                Rectangle {
                    width: content.width
                    height: 56
                    color: rowMa.containsMouse ? Theme.color.surfaceContainerHigh
                                               : Theme.color.surface
                    Column {
                        anchors.left: parent.left
                        anchors.leftMargin: 16
                        anchors.verticalCenter: parent.verticalCenter
                        Text {
                            text: player.resultTitle(index)
                            color: Theme.color.onSurfaceColor
                            fontSize: 16
                        }
                        Text {
                            text: player.resultArtist(index)
                            color: Theme.color.onSurfaceVariantColor
                            fontSize: 13
                        }
                    }
                    MouseArea {
                        id: rowMa
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: player.playNetease(player.resultId(index))
                    }
                }
            }

            Text {
                visible: player.libraryCount > 0
                x: 16; height: 40
                text: "本地音乐"
                color: Theme.color.primary
                fontSize: 18
            }

            Repeater {
                model: player.libraryCount
                Rectangle {
                    width: content.width
                    height: 56
                    color: locMa.containsMouse ? Theme.color.surfaceContainerHigh
                                               : Theme.color.surface
                    Column {
                        anchors.left: parent.left
                        anchors.leftMargin: 16
                        anchors.verticalCenter: parent.verticalCenter
                        Text {
                            text: player.trackTitle(index)
                            color: index === player.index ? Theme.color.primary
                                                          : Theme.color.onSurfaceColor
                            fontSize: 16
                        }
                        Text {
                            text: player.trackArtist(index)
                            color: Theme.color.onSurfaceVariantColor
                            fontSize: 13
                        }
                    }
                    MouseArea {
                        id: locMa
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: player.play(index)
                    }
                }
            }
        }
    }

    // --- mini player ----------------------------------------------------
    Rectangle {
        id: mini
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: 96
        color: Theme.color.surfaceContainerHigh

        Column {
            anchors.left: parent.left
            anchors.leftMargin: 16
            anchors.top: parent.top
            anchors.topMargin: 12
            width: parent.width - 32
            Text {
                text: player.title.length > 0 ? player.title : "未播放"
                color: Theme.color.onSurfaceColor
                fontSize: 16
                width: parent.width
                elide: Text.ElideRight
            }
            Text {
                text: player.artist
                color: Theme.color.onSurfaceVariantColor
                fontSize: 13
            }
            Text {
                text: fmt(player.positionMs) + " / " + fmt(player.durationMs)
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
            }
        }

        Row {
            anchors.right: parent.right
            anchors.rightMargin: 12
            anchors.verticalCenter: parent.verticalCenter
            spacing: 8

            Button { type: "text"; text: "⏮"; onClicked: player.prev() }
            Button {
                type: "filled"
                text: player.playing ? "⏸" : "▶"
                onClicked: player.toggle()
            }
            Button { type: "text"; text: "⏭"; onClicked: player.next() }
        }
    }

    // --- log overlay ----------------------------------------------------
    Rectangle {
        id: logPanel
        visible: root.showLog
        anchors.fill: parent
        color: Theme.color.surfaceContainerHighest

        Row {
            id: logBar
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: 56
            spacing: 8
            Button { type: "text"; text: "关闭"; onClicked: root.showLog = false }
            Button { type: "text"; text: "清空"; onClicked: player.clearLog() }
        }

        Flickable {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: logBar.bottom
            anchors.bottom: parent.bottom
            anchors.margins: 12
            clip: true
            contentHeight: logText.height
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
