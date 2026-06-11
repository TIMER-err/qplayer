import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 为我推荐: time-of-day greeting, recommended playlists grid, and (when signed
// in) the daily song picks.
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()

    function greeting() {
        var h = new Date().getHours();
        if (h < 6) return "夜深了";
        if (h < 12) return "早上好";
        if (h < 14) return "中午好";
        if (h < 18) return "下午好";
        return "晚上好";
    }

    Flickable {
        anchors.fill: parent
        clip: true
        contentHeight: col.height

        Column {
            id: col
            width: parent.width
            spacing: 0

            Item {
                width: parent.width; height: 72
                Text {
                    anchors.left: parent.left; anchors.leftMargin: 16
                    anchors.verticalCenter: parent.verticalCenter
                    text: page.greeting() + (player.loggedIn ? "，" + player.userName : "")
                    color: Theme.color.onSurfaceColor
                    fontSize: 26
                }
            }

            SectionHeader { width: col.width; text: "推荐歌单"; visible: recPlaylists.count > 0 }

            Flow {
                width: col.width
                Repeater {
                    id: recPlaylists
                    model: player.recommendPlaylists
                    PlaylistCard {
                        name: modelData.name
                        count: modelData.trackCount
                        onClicked: { page.pendingPlaylist = modelData; page.openPlaylist() }
                    }
                }
            }

            SectionHeader { width: col.width; text: "每日推荐"; visible: dailyRep.count > 0 }

            Repeater {
                id: dailyRep
                model: player.recommendations
                SongRow {
                    width: col.width
                    rowTitle: modelData.name
                    rowArtist: modelData.artist
                    onActivated: player.playRecommendation(index)
                }
            }

            Item {
                width: parent.width; height: 80
                visible: recPlaylists.count === 0 && dailyRep.count === 0
                Text {
                    anchors.centerIn: parent
                    text: "加载中…"
                    color: Theme.color.onSurfaceVariantColor
                    fontSize: 14
                }
            }
        }
    }
}
