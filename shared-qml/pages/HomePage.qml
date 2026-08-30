import QtQuick
import md3.Core
import "."
import "../components"

// 为我推荐: greeting + recommended-playlist grid + daily song picks, all in one
// Flickable with absolute positioning (the layout primitive that behaves here).
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()

    property real pad: 12
    property real gap: 12
    property real greetH: 64
    property real rowH: 64
    // Responsive grid: ~200dp min card width → 2 cols on a phone, 3–4 on a wide
    // window. Width-driven, so it adapts on desktop and on Android large screens.
    property real minTile: 200
    property int cols: Math.max(2, Math.floor((width - 2 * pad + gap) / (minTile + gap)))
    property real tile: (width - 2 * pad - (cols - 1) * gap) / cols
    property real cardH: tile + 72

    property var homePlaylists: player.sourceContentActive
                                ? player.sourceRecommendPlaylists : player.recommendPlaylists
    property var homeSongs: player.sourceContentActive
                            ? player.sourceRecommendations : player.recommendations
    property int recCount: homePlaylists ? homePlaylists.length : 0
    property int dailyCount: homeSongs ? homeSongs.length : 0
    property real gridH: Math.ceil(recCount / cols) * (cardH + gap)
    property real dailyHdrY: greetH + gridH + 4
    property real dailyTop: dailyHdrY + (dailyCount > 0 ? 40 : 0)

    // A tap holds the "尝试连接中" state for at least this long even if the
    // request itself fails near-instantly (e.g. no network at all) -- a flash
    // too quick to actually read isn't feedback. A genuinely slow real request
    // still keeps showing it past 3s (refreshBusy also watches homeLoading).
    property bool refreshCooling: false
    property bool refreshBusy: refreshCooling || player.homeLoading
    Timer {
        id: refreshCoolTimer
        interval: 3000
        onTriggered: page.refreshCooling = false
    }

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
        contentWidth: width
        contentHeight: page.dailyTop + page.dailyCount * page.rowH + 12

        Item {
            width: page.width
            height: page.dailyTop + page.dailyCount * page.rowH + 12
            // Cards/rows have fixed index-derived positions; skip re-measuring the
            // whole page on unrelated version bumps (the play clock) once laid out.
            cachedLayout: true

            Text {
                x: 16; y: 0; height: page.greetH
                verticalAlignment: Text.AlignVCenter
                text: page.greeting() + (player.loggedIn ? "，" + player.userName : "")
                color: Theme.color.onSurfaceColor
                fontSize: 26
            }

            Repeater {
                model: page.homePlaylists
                PlaylistCard {
                    playlistId: modelData.id
                    tile: page.tile
                    x: page.pad + (index % page.cols) * (page.tile + page.gap)
                    y: page.greetH + Math.floor(index / page.cols) * (page.cardH + page.gap)
                    name: modelData.name
                    count: modelData.trackCount
                    coverUrl: modelData.coverUrl
                    coverThumbPath: modelData.coverThumbPath || ""
                    onClicked: { page.pendingPlaylist = modelData; page.openPlaylist() }
                }
            }

            Text {
                visible: page.dailyCount > 0
                x: 16; y: page.dailyHdrY; height: 40
                verticalAlignment: Text.AlignVCenter
                text: "每日推荐"
                color: Theme.color.primary
                fontSize: 18
            }

            Repeater {
                model: page.homeSongs
                SongRow {
                    width: page.width
                    y: page.dailyTop + index * page.rowH
                    rowTitle: modelData.name
                    rowArtist: modelData.artist
                    rowArtistId: modelData.artistMediaId || modelData.artistId || 0
                    rowArtistIdsCsv: modelData.artistIdsCsv || ""
                    rowArtistNamesCsv: modelData.artistNamesCsv || ""
                    coverThumbPath: modelData.coverThumbPath || ""
                    song: modelData
                    onActivated: player.playRecommendation(index)
                }
            }
        }
    }

    // Empty either because it's still loading, or because loadHome() tried and
    // every request failed (offline/API down) -- refreshBusy tells those two
    // apart, since both otherwise look identical (both lists just empty).
    Item {
        anchors.centerIn: parent
        visible: page.recCount === 0 && page.dailyCount === 0
        width: emptyRow.width + 40
        height: 44

        Rectangle {
            id: refreshPill
            anchors.fill: parent
            radius: height / 2
            color: Theme.color.surfaceContainerHigh

            // MD3 state layer + Ripple — the same building block Button/
            // IconButton use everywhere else in the app, so it's already
            // proven to animate correctly in qml4j (unlike the `scale`
            // press-bounce tried here first, which never interpolated under
            // either an implicit Behavior or an explicit NumberAnimation).
            Rectangle {
                anchors.fill: parent
                radius: parent.radius
                color: Theme.color.onSecondaryContainerColor
                opacity: refreshRipple.pressed ? 0.12 : (refreshRipple.containsMouse ? 0.08 : 0)
                Behavior on opacity { NumberAnimation { duration: 100 } }
            }

            Ripple {
                id: refreshRipple
                anchors.fill: parent
                clipRadius: parent.radius
                rippleColor: Theme.color.onSecondaryContainerColor
                // Stays tappable while busy -- the ripple/highlight still responds
                // (disabling the MouseArea would kill that feedback entirely), the
                // tap itself just does nothing until the in-flight attempt settles.
                onClicked: {
                    if (page.refreshBusy) return;
                    page.refreshCooling = true;
                    refreshCoolTimer.restart();
                    player.loadHome();
                }
            }

            Row {
                id: emptyRow
                anchors.centerIn: parent
                spacing: 8
                Text {
                    id: refreshIcon
                    anchors.verticalCenter: parent.verticalCenter
                    text: "sync"
                    font.family: Theme.iconFont.name
                    font.pixelSize: 18
                    color: page.refreshBusy ? Theme.color.onSurfaceVariantColor : Theme.color.onSecondaryContainerColor
                    NumberAnimation on rotation {
                        running: page.refreshBusy
                        loops: Animation.Infinite
                        from: 360; to: 0
                        duration: 700
                    }
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: page.refreshBusy ? "尝试连接中" : "点击刷新"
                    color: page.refreshBusy ? Theme.color.onSurfaceVariantColor : Theme.color.onSecondaryContainerColor
                    fontSize: 14
                }
            }
        }
    }
}
