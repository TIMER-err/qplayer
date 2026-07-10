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

    // Landscape (wide) layout: cover + transport on the left, lyrics on the right
    // half (host-drawn). Driven by aspect so a desktop window, tablet, or phone in
    // landscape all adopt it. coverOnly (no lyrics / instrumental) centers the cover.
    property bool landscape: overlay.width > overlay.height
    property bool coverOnly: player.lyricsCoverOnly


    function fmt(ms) {
        if (ms <= 0) return "0:00";
        var s = Math.floor(ms / 1000), m = Math.floor(s / 60), r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    // Swallow taps on the empty (lyrics) area so they don't leak through.
    MouseArea { anchors.fill: parent }

    // --- portrait: big centred cover for no-lyric / instrumental tracks. It zooms +
    // fades in/out on the lyrics↔cover switch (SPlayer's zoom transition) — no big/small
    // morph, title/artist stay put. Landscape has its own cover in the left chrome.
    CoverImage {
        id: pCover
        // Big centred art for no-lyric / instrumental tracks. On the lyrics↔cover switch
        // it ZOOMS + FADES (SPlayer's zoom transition) — no big/small morph. Kept
        // rendering while fading out so the zoom-out plays over the appearing lyrics.
        visible: !overlay.landscape && (overlay.coverOnly || opacity > 0.01)
        property real coverSize: Math.max(160, Math.min(overlay.width - 96, overlay.height - 360, 420))
        width: coverSize
        height: coverSize
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        radius: Math.min(width, height) * 0.06
        iconSize: 72
        source: player.coverPath !== "" ? player.coverPath : player.coverUrl
        // Zoom + fade in step with the host lyric column's own zoom (SPlayer's whole-
        // content zoom): cover grows in as the lyrics shrink out, and vice versa.
        property bool shown: overlay.coverOnly && player.lyricSlide > 0.25
        opacity: shown ? 1 : 0
        scale: shown ? 1 : 0.95
        Behavior on opacity { NumberAnimation { duration: 250; easing.type: Easing.OutCubic } }
        Behavior on scale { NumberAnimation { duration: 300; easing.type: Easing.OutBack } }
    }

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
        visible: !overlay.landscape
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
        maximumLineCount: 2
        elide: Text.ElideRight
    }
    Text {
        id: artistText
        visible: !overlay.landscape
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

    // --- bottom: transport (portrait) ---------------------------------
    Item {
        id: transport
        visible: !overlay.landscape
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.bottomMargin: settings.bottomInset + 12
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        height: 120

        // progress (md3 wavy) + seek. The wavy phase is an infinite animation gated
        // on the bar's OWN `visible` (control.visible) — own visibility is not the
        // ancestor-effective one, so when the lyric page is closed (this whole
        // overlay invisible) the bar's own visible stayed true and the animation
        // kept ticking every frame, bumping the change version and defeating the
        // renderer's idle layout-skip. Tie its visibility to the page being shown.
        LinearProgress {
            id: progress
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.topMargin: 18
            wavy: true
            visible: player.lyricSlide > 0.001
            value: player.lyricProgress
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
            anchors.bottomMargin: 14
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
                enabled: player.currentLikeable
                icon: player.currentLiked ? "favorite" : "favorite_border"
                contentColor: player.currentLiked ? "#FFFF5277" : "#99FFFFFF"
                onClicked: player.toggleLike()
            }
        }
    }

    // --- landscape: cover + title + transport on the left -------------
    // The host draws the lyrics in the right half (or, when coverOnly, nothing — and
    // this column centers across the full width). Plain anchors, no positioner: the
    // play clock republishes positionMs/lyricProgress ~5x/s and a Column/Layout here
    // would re-run its distribution pass each of those frames (see MiniPlayer).
    Item {
        id: landscapeChrome
        visible: overlay.landscape
        anchors.fill: parent

        // Target region: half the page (cover left, lyrics right) or the whole width
        // when there's no side lyric column. Like SPlayer's content-left, the cover
        // column's centre-x AND size EASE between the two states (springy OutBack)
        // rather than snapping when lyrics appear/disappear.
        readonly property real regionW: overlay.coverOnly ? overlay.width : overlay.width / 2
        readonly property real targetCoverSize:
            Math.max(120, Math.min(regionW - 96, overlay.height - 248, 360))
        property real coverSize: targetCoverSize
        property real centerX: regionW / 2
        Behavior on coverSize { NumberAnimation { duration: 500; easing.type: Easing.OutBack } }
        Behavior on centerX { NumberAnimation { duration: 500; easing.type: Easing.OutBack } }

        Item {
            id: col
            width: landscapeChrome.coverSize
            // cover + (title 26 + artist 18 + gaps + progress + labels + buttons 48).
            height: landscapeChrome.coverSize + 196
            anchors.verticalCenter: parent.verticalCenter
            anchors.horizontalCenter: parent.left
            anchors.horizontalCenterOffset: landscapeChrome.centerX

            CoverImage {
                id: lCover
                anchors.top: parent.top
                anchors.horizontalCenter: parent.horizontalCenter
                width: landscapeChrome.coverSize
                height: landscapeChrome.coverSize
                radius: Math.min(width, height) * 0.06
                iconSize: 64
                source: player.coverPath !== "" ? player.coverPath : player.coverUrl
            }
            Text {
                id: lTitle
                anchors.top: lCover.bottom
                anchors.topMargin: 20
                anchors.left: parent.left
                anchors.right: parent.right
                text: player.title
                color: "#FFFFFFFF"
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: 20
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
            }
            Text {
                id: lArtist
                anchors.top: lTitle.bottom
                anchors.topMargin: 4
                anchors.left: parent.left
                anchors.right: parent.right
                text: player.artist
                color: "#B3FFFFFF"
                fontSize: 13
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
            }
            LinearProgress {
                id: lProgress
                anchors.top: lArtist.bottom
                anchors.topMargin: 22
                anchors.left: parent.left
                anchors.right: parent.right
                wavy: true
                visible: player.lyricSlide > 0.001
                value: player.lyricProgress
            }
            MouseArea {
                anchors.fill: lProgress
                anchors.topMargin: -10
                anchors.bottomMargin: -10
                onPressed: if (player.durationMs > 0)
                               player.seek(Math.round(mouseX / width * player.durationMs))
                onPositionChanged: if (pressed && player.durationMs > 0)
                                       player.seek(Math.round(Math.max(0, Math.min(width, mouseX)) / width * player.durationMs))
            }
            Text {
                anchors.left: parent.left
                anchors.top: lProgress.bottom
                anchors.topMargin: 6
                text: overlay.fmt(player.positionMs)
                color: "#B3FFFFFF"
                fontSize: 11
            }
            Text {
                anchors.right: parent.right
                anchors.top: lProgress.bottom
                anchors.topMargin: 6
                text: overlay.fmt(player.durationMs)
                color: "#B3FFFFFF"
                fontSize: 11
            }
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
                    enabled: player.currentLikeable
                    icon: player.currentLiked ? "favorite" : "favorite_border"
                    contentColor: player.currentLiked ? "#FFFF5277" : "#99FFFFFF"
                    onClicked: player.toggleLike()
                }
            }
        }
    }
}
