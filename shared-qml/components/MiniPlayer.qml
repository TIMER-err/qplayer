import QtQuick
import miuix.Core
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
SmoothRectangle {
    id: mini

    signal lyricsRequested()

    function fmt(ms) {
        if (ms <= 0) return "0:00";
        var s = Math.floor(ms / 1000);
        var m = Math.floor(s / 60);
        var r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    implicitHeight: 80
    radius: 20
    color: Theme.color.surfaceContainer

    // Loading sweep state: enter as soon as player.loading rises; leave only when the
    // current sweep pass ends (ScriptAction below), so a track that loads mid-sweep
    // finishes the pass before the position fill returns instead of snapping.
    property bool sweeping: false
    property bool _loading: player.loading
    property real _targetProgress: player.durationMs > 0
        ? Math.max(0.0, Math.min(1.0, player.positionMs / player.durationMs)) : 0.0
    property real _visualProgress: _targetProgress
    property bool _catchingUp: false

    on_LoadingChanged: {
        if (_loading) {
            progressCatchUp.stop()
            mini._catchingUp = false
            mini.sweeping = true
        }
    }
    on_TargetProgressChanged: {
        if (!mini.sweeping && !mini._catchingUp)
            mini._visualProgress = mini._targetProgress
    }

    function finishSweep() {
        mini.sweeping = false
        progressCatchUp.stop()
        mini._visualProgress = 0.0
        if (mini._targetProgress <= 0.0) {
            mini._catchingUp = false
            return
        }
        mini._catchingUp = true
        progressCatchUp.to = mini._targetProgress
        progressCatchUp.restart()
    }

    NumberAnimation {
        id: progressCatchUp
        target: mini
        property: "_visualProgress"
        duration: 450
        easing.type: Easing.OutCubic
        onFinished: {
            mini._catchingUp = false
            mini._visualProgress = mini._targetProgress
        }
    }

    // progress line along the very top edge
    Rectangle {
        id: track
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.leftMargin: 20
        anchors.rightMargin: 20
        anchors.topMargin: 1
        radius: 2
        height: 3
        color: Theme.color.surfaceContainerHighest
        // The loading sweep slides in from x = -sweep.width; clip so its off-left
        // portion is cut at this bar's edge instead of spilling onto the rail beside it.
        clip: true

        Rectangle {
            anchors.left: parent.left
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            visible: !mini.sweeping
            width: parent.width * mini._visualProgress
            color: Theme.color.primary
        }
        // Loading sweep: a segment sliding left-to-right while the next track resolves,
        // in place of the frozen position fill. Only ticks while sweeping + visible.
        Rectangle {
            id: sweep
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            visible: mini.sweeping
            width: track.width * 0.3
            color: Theme.color.primary
            SequentialAnimation {
                running: mini.sweeping && mini.visible
                loops: Animation.Infinite
                NumberAnimation {
                    target: sweep; property: "x"
                    from: -sweep.width; to: track.width
                    duration: 1000; easing.type: Easing.InOutSine
                }
                // End the sweep only at a pass boundary once loading is done.
                ScriptAction { onTrigger: if (!player.loading) mini.finishSweep() }
            }
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
        visible: mini.width >= 440
        width: visible ? 40 : 0
        anchors.right: playBtn.left
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: 1
        type: "standard"
        // Local tracks have no server-side favorite list — disable, don't toggle.
        enabled: player.currentLikeable
        icon: player.currentLiked ? "favorite" : "favorite_border"
        contentColor: player.currentLiked ? "#FF5277" : Theme.color.onSurfaceVariantColor
        onClicked: player.toggleLike()
    }
    IconButton {
        id: modeBtn
        visible: mini.width >= 520
        width: visible ? 40 : 0
        anchors.right: likeBtn.left
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: 1
        type: "standard"
        icon: player.playMode === 1 ? "shuffle"
              : (player.playMode === 2 ? "repeat_one" : "repeat")
        onClicked: player.cyclePlayMode()
    }

    CoverImage {
        id: cover
        objectName: "miniPlayerCover"
        anchors.left: parent.left
        anchors.leftMargin: 10
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: 1
        width: 60; height: 60
        radius: 10
        // Prefer the on-disk cached cover (shows offline) over the network url.
        source: player.coverPath !== "" ? player.coverPath : player.coverUrl

        // Tapping the cover itself opens the lyric page too — the title/artist
        // MouseArea below only spans cover.right onward, so the cover was dead
        // space on its own.
        MouseArea {
            anchors.fill: parent
            onClicked: mini.lyricsRequested()
        }
    }

    // Cover + title/artist: tap anywhere here to open the lyric page.
    Item {
        anchors.left: cover.right
        anchors.leftMargin: 12
        anchors.right: modeBtn.left
        anchors.rightMargin: 4
        anchors.top: track.bottom
        anchors.bottom: parent.bottom

        MarqueeText {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.verticalCenter
            anchors.bottomMargin: 1
            text: player.title.length > 0 ? player.title : i18n.t("player.idle")
            textColor: Theme.color.onSurfaceColor
            fontSize: 16
        }
        // Time on the right, pinned to its own width so it's never clipped; the
        // artist fills the space to its left and elides on its own.
        Text {
            id: timeText
            visible: mini.width >= 600
            anchors.right: parent.right
            anchors.top: parent.verticalCenter
            anchors.topMargin: 2
            text: player.durationMs > 0
                  ? fmt(player.positionMs) + " / " + fmt(player.durationMs) : ""
            color: Theme.color.onSurfaceVariantColor
            fontSize: 12
        }
        MarqueeText {
            anchors.left: parent.left
            anchors.right: timeText.visible && timeText.text.length > 0 ? timeText.left : parent.right
            anchors.rightMargin: timeText.visible && timeText.text.length > 0 ? 8 : 0
            anchors.top: parent.verticalCenter
            anchors.topMargin: 2
            text: player.artist
            textColor: Theme.color.onSurfaceVariantColor
            fontSize: 12
        }

        MouseArea {
            anchors.fill: parent
            onClicked: mini.lyricsRequested()
        }
    }
}
