import QtQuick
import miuix.Core
import design
import "."

// Floating now-playing bar.
//
// Wide (>= 720 px, ordinary mode): cover + title on the left, centred transport
// with a scrubbable progress row underneath, volume on the right.
// Compact: cover + title, like / play / next on the right and a thin progress
// line along the bottom edge.
// Liquid glass (compact only): circular cover with a progress ring; the host
// samples the capsule's backdrop through liquidGlassMiniPlayerSurface.
//
// Plain x/y and anchors, NOT nested Layouts: this bar is always visible and the
// play clock updates player.positionMs ~5x/s, which forces a settle pass; layout
// containers here re-ran their measure passes on every tick.
SmoothRectangle {
    id: mini

    signal lyricsRequested()
    property bool liquidGlass: false

    implicitHeight: liquidGlass ? 80 : Ui.playerBarHeight
    radius: liquidGlass ? height / 2 : Ui.radiusLg
    color: liquidGlass ? "transparent" : Ui.surfaceRaised

    readonly property bool wideLayout: !liquidGlass && width >= 720
    readonly property bool desktopVolumeControl: settings.has("desktopLyricEnabled")
    readonly property real coverSize: liquidGlass ? 60 : (wideLayout ? 52 : 48)

    // Drawn before the ordinary controls: their hit regions and playback state
    // stay in QML while the host samples only this capsule's live backdrop.
    Item {
        objectName: "liquidGlassMiniPlayerSurface"
        anchors.fill: parent
        visible: mini.liquidGlass
        property color surfaceColor: Theme.color.surfaceContainer
        property bool darkTheme: Theme.dark
    }

    Rectangle {
        anchors.fill: parent
        visible: !mini.liquidGlass
        radius: mini.radius
        color: "transparent"
        border.width: 1
        border.color: Ui.hairline
    }

    // ---- Progress state (host-driven loading clock) ---------------------------
    // The host's frame-driven clock finishes the current loading cycle before
    // returning to the actual play position, without a loop-restart pause.
    property bool sweeping: false
    property real _sweepPhase: 0
    property real _sweepCycle: 0

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
    on_SweepCycleChanged: {
        if (mini._sweepCycle > 0 && !player.loading) mini.finishSweep()
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

    function seekTo(ratio) {
        if (player.durationMs > 0) player.seek(Math.round(ratio * player.durationMs))
    }

    VolumePopup { id: volumePopup }
    onWidthChanged: volumePopup.close()
    onYChanged: volumePopup.close()
    function handleBack() { return volumePopup.handleBack() }

    // ---- Cover ----------------------------------------------------------------
    CoverImage {
        id: cover
        objectName: "miniPlayerCover"
        x: mini.liquidGlass ? 10 : 12
        y: (mini.height - height) / 2
        width: mini.coverSize
        height: mini.coverSize
        radius: mini.liquidGlass ? width / 2 : Ui.radiusSm
        // Prefer the on-disk cached cover (shows offline) over the network url.
        source: player.coverPath !== "" ? player.coverPath : player.coverUrl

        MouseArea {
            anchors.fill: parent
            enabled: !mini.liquidGlass
            cursorShape: Qt.PointingHandCursor
            onClicked: mini.lyricsRequested()
        }
    }

    Item {
        id: coverProgress
        objectName: "miniPlayerProgressRing"
        x: cover.x - 4
        y: cover.y - 4
        width: cover.width + 8
        height: cover.height + 8
        visible: mini.liquidGlass
        enabled: visible
        CircularProgress {
            anchors.fill: parent
            strokeWidth: 3
            value: mini.liquidGlass ? (mini.sweeping ? 0.3 : mini._visualProgress) : 0
            startAngle: -90 + (mini.liquidGlass && mini.sweeping ? mini._sweepPhase * 360 : 0)
        }

        // Centre opens lyrics; the outer band seeks clockwise from twelve.
        MouseArea {
            anchors.fill: parent
            onClicked: (mouse) => {
                var dx = mouse.x - width / 2
                var dy = mouse.y - height / 2
                var distanceSq = dx * dx + dy * dy
                var innerRadius = Math.max(0, cover.width / 2 - 4)
                var outerRadius = width / 2 + 4
                if (distanceSq < innerRadius * innerRadius) {
                    mini.lyricsRequested()
                } else if (distanceSq <= outerRadius * outerRadius && player.durationMs > 0) {
                    var angle = Math.atan2(dy, dx) + Math.PI / 2
                    if (angle < 0) angle += Math.PI * 2
                    player.seek(Math.round(angle / (Math.PI * 2) * player.durationMs))
                }
            }
        }
    }

    // ---- Compact / glass transport (right-aligned) ----------------------------
    Item {
        id: compactCluster
        visible: !mini.wideLayout
        enabled: visible
        // Mobile keeps like at compact-phone widths. Desktop reserves the volume
        // control, so the optional like yields sooner as the window narrows.
        readonly property bool showLike: mini.width >= (mini.desktopVolumeControl ? 380 : 280)
        width: (mini.desktopVolumeControl ? 40 : 0) + (showLike ? 40 : 0) + 44 + 40 + 8
        height: 44
        x: mini.width - width - 6
        y: (mini.height - height) / 2

        IconButton {
            objectName: "miniPlayerVolumeButton"
            id: compactVolume
            visible: mini.desktopVolumeControl
            x: 0
            y: 2
            type: "standard"
            icon: settings.appVolume === 0 ? "volume_off"
                  : (settings.appVolume < 50 ? "volume_down" : "volume_up")
            onClicked: volumePopup.open(compactVolume)
        }
        IconButton {
            id: compactLike
            visible: compactCluster.showLike
            x: mini.desktopVolumeControl ? 40 : 0
            y: 2
            type: "standard"
            enabled: player.currentLikeable
            icon: player.currentLiked ? "favorite" : "favorite_border"
            contentColor: player.currentLiked ? Ui.like : Theme.color.onSurfaceVariantColor
            onClicked: { compactLikePop.restart(); player.toggleLike() }
        }
        IconButton {
            id: compactPlay
            objectName: "miniPlayerPlayButton"
            x: compactCluster.width - 44 - 40 - 8
            y: 0
            width: 44
            height: 44
            type: "filled"
            icon: player.playing ? "pause" : "play_arrow"
            onClicked: { compactPlayPop.restart(); player.toggle() }
        }
        IconButton {
            x: compactCluster.width - 40 - 4
            y: 2
            type: "standard"
            icon: "skip_next"
            onClicked: player.next()
        }
    }

    // Thin progress line along the bottom edge (ordinary compact bar).
    SeekBar {
        objectName: "miniPlayerCompactProgress"
        visible: !mini.liquidGlass && !mini.wideLayout
        interactive: visible
        x: 18
        y: mini.height - 9
        width: mini.width - 36
        height: 8
        trackHeight: 3
        value: mini._visualProgress
        sweeping: mini.sweeping
        sweepPhase: mini._sweepPhase
        onSeekRequested: mini.seekTo(pendingRatio)
    }

    // ---- Wide transport (centred) ----------------------------------------------
    Item {
        id: wideCluster
        objectName: "miniPlayerWideTransport"
        visible: mini.wideLayout
        enabled: visible
        width: Math.min(460, mini.width * 0.44)
        height: mini.height
        x: (mini.width - width) / 2
        y: 0

        Row {
            id: transportRow
            x: (wideCluster.width - width) / 2
            y: 6
            height: 44
            spacing: 6
            IconButton {
                y: 2
                type: "standard"
                icon: player.playMode === 1 ? "shuffle"
                      : (player.playMode === 2 ? "repeat_one" : "repeat")
                contentColor: Theme.color.onSurfaceVariantColor
                onClicked: player.cyclePlayMode()
            }
            IconButton {
                y: 2
                type: "standard"
                icon: "skip_previous"
                onClicked: player.prev()
            }
            IconButton {
                id: widePlay
                objectName: "miniPlayerWidePlayButton"
                width: 44
                height: 44
                type: "filled"
                icon: player.playing ? "pause" : "play_arrow"
                onClicked: { widePlayPop.restart(); player.toggle() }
            }
            IconButton {
                y: 2
                type: "standard"
                icon: "skip_next"
                onClicked: player.next()
            }
            IconButton {
                id: wideLike
                y: 2
                type: "standard"
                enabled: player.currentLikeable
                icon: player.currentLiked ? "favorite" : "favorite_border"
                contentColor: player.currentLiked ? Ui.like : Theme.color.onSurfaceVariantColor
                onClicked: { wideLikePop.restart(); player.toggleLike() }
            }
        }

        Text {
            id: positionText
            x: 0
            y: progressRow.y + (progressRow.height - implicitHeight) / 2
            width: 44
            horizontalAlignment: Text.AlignRight
            text: Ui.formatTime(wideSeek.scrubbing
                                ? wideSeek.shownValue * player.durationMs : player.positionMs)
            color: Ui.textTertiary
            font.pixelSize: Ui.textMicro
        }
        SeekBar {
            id: wideSeek
            objectName: "miniPlayerSeekBar"
            x: 52
            y: progressRow.y
            width: wideCluster.width - 104
            height: progressRow.height
            value: mini._visualProgress
            sweeping: mini.sweeping
            sweepPhase: mini._sweepPhase
            onSeekRequested: mini.seekTo(pendingRatio)
        }
        Text {
            x: wideCluster.width - 44
            y: positionText.y
            width: 44
            text: Ui.formatTime(player.durationMs)
            color: Ui.textTertiary
            font.pixelSize: Ui.textMicro
        }
        Item {
            id: progressRow
            y: 52
            width: wideCluster.width
            height: 16
        }
    }

    IconButton {
        id: wideVolume
        objectName: "miniPlayerWideVolumeButton"
        visible: mini.wideLayout && mini.desktopVolumeControl
        x: mini.width - width - 12
        y: (mini.height - height) / 2
        type: "standard"
        icon: settings.appVolume === 0 ? "volume_off"
              : (settings.appVolume < 50 ? "volume_down" : "volume_up")
        onClicked: volumePopup.open(wideVolume)
    }

    // ---- Title / artist: tap to open the lyric page ------------------------------
    Item {
        id: info
        x: cover.x + cover.width + 12
        width: Math.max(0, (mini.wideLayout ? wideCluster.x - 16 : compactCluster.x - 4) - x)
        y: 0
        height: mini.liquidGlass ? mini.height : mini.height - (mini.wideLayout ? 0 : 8)

        MarqueeText {
            x: 0
            width: parent.width
            y: parent.height / 2 - height - 1
            text: player.title.length > 0 ? player.title : i18n.t("player.idle")
            textColor: Ui.textPrimary
            fontSize: Ui.textHeadline
        }
        MarqueeText {
            x: 0
            width: parent.width
            y: parent.height / 2 + 3
            text: player.artist
            textColor: Ui.textSecondary
            fontSize: Ui.textCaption
        }
        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: mini.lyricsRequested()
        }
    }

    // Click feedback pulses; kept at the root because children declared on a
    // miuix IconButton instance are not reliably instantiated.
    Pop { id: compactLikePop; target: compactLike; peak: 1.3 }
    Pop { id: compactPlayPop; target: compactPlay; peak: 0.86 }
    Pop { id: widePlayPop; target: widePlay; peak: 0.86 }
    Pop { id: wideLikePop; target: wideLike; peak: 1.3 }
}
