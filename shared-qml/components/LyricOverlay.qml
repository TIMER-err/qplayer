import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import miuix.Core
import design
import "."

// QML chrome for the lyric page, composited on top of the host-drawn fluid
// backdrop + per-syllable lyrics. Transparent everywhere except the title band
// (top) and the transport band (bottom), so the host lyrics show through the
// middle. Visibility/opacity follow player.lyricSlide (published by the host) so
// it fades in lockstep with the host layer.
Item {
    id: overlay
    signal closeRequested()
    property bool prewarming: false

    // Landscape (wide) layout: cover + transport on the left, lyrics on the right
    // half (host-drawn). Driven by aspect so a desktop window, tablet, or phone in
    // landscape all adopt it. coverOnly (no lyrics / instrumental) centers the cover.
    property bool landscape: overlay.width > overlay.height
    // OR of the automatic no-lyrics/instrumental detection and the user's manual
    // lyrics<->cover toggle (the button below / tapping the cover to return).
    property bool coverOnly: player.lyricsCoverOnly || player.coverModeManual
    // Host-eased progress of the full-width layout change, 0 (cover + lyrics in
    // the right half) .. 1 (lyrics across the page, chrome along the bottom).
    // Eased in LyricCompositor rather than by a Behavior here so this chrome and
    // the host-drawn column move on exactly the same value.
    property real fullWidthK: player.lyricFullWidth
    property bool offsetPanelOpen: false
    property var miniPlayer: null
    readonly property real miniCoverSize: {
        if (miniPlayer && miniPlayer.coverSize !== undefined && miniPlayer.coverSize > 0)
            return miniPlayer.coverSize
        var isGlass = settings.has("liquidGlassNavigation") && settings.value("liquidGlassNavigation") === true
        var isWideBar = overlay.landscape || overlay.width >= 720
        return isGlass && !isWideBar ? 60 : (isWideBar ? 52 : 48)
    }
    readonly property real miniCoverRadius: {
        if (miniPlayer && miniPlayer.coverRadius !== undefined && miniPlayer.coverRadius > 0)
            return miniPlayer.coverRadius
        var isGlass = settings.has("liquidGlassNavigation") && settings.value("liquidGlassNavigation") === true
        var isWideBar = overlay.landscape || overlay.width >= 720
        return isGlass && !isWideBar ? (miniCoverSize / 2) : Ui.radiusSm
    }
    readonly property real miniCoverX: {
        if (miniPlayer && miniPlayer.coverX !== undefined && miniPlayer.coverX > 0)
            return miniPlayer.coverX - overlay.x
        var sideW = overlay.landscape ? (overlay.width >= 840 ? 232 : 80) : 0
        var isGlass = settings.has("liquidGlassNavigation") && settings.value("liquidGlassNavigation") === true
        var margin = isGlass ? 24 : 12
        var isWideBar = overlay.landscape || overlay.width >= 720
        var pad = isGlass && !isWideBar ? 10 : 12
        return sideW + margin + pad - overlay.x
    }
    readonly property real miniCoverY: {
        if (miniPlayer && miniPlayer.coverY !== undefined && miniPlayer.coverY > 0)
            return miniPlayer.coverY - overlay.y
        var isGlass = settings.has("liquidGlassNavigation") && settings.value("liquidGlassNavigation") === true
        var isWideBar = overlay.landscape || overlay.width >= 720
        var barH = isGlass && !isWideBar ? 80 : 64
        var cSize = miniCoverSize
        var botMargin = overlay.landscape ? (isGlass ? 24 : 12) : ((settings.bottomInset > 0 ? settings.bottomInset : 0) + 72)
        var defY = overlay.height - botMargin - barH + (barH - cSize) / 2
        return defY - overlay.y
    }
    readonly property real chromeOpacity: Math.min(1, Math.max(0, (player.lyricSlide - 0.20) / 0.65))

    readonly property real targetCoverSize: overlay.landscape
        ? Math.max(120, Math.min((overlay.coverOnly ? overlay.width : overlay.width / 2) - 96, overlay.height - 248, 360))
        : Math.max(160, Math.min(overlay.width - 96, overlay.height - 360, 420))

    readonly property real targetCoverX: {
        if (overlay.landscape) {
            var colW = overlay.coverOnly ? overlay.width : overlay.width / 2
            return (colW - targetCoverSize) / 2
        } else {
            return (overlay.width - targetCoverSize) / 2
        }
    }

    readonly property real targetCoverY: {
        if (overlay.landscape) {
            var colH = targetCoverSize + 196
            return (overlay.height - colH) / 2
        } else {
            return (overlay.height - targetCoverSize) / 2
        }
    }
    // Top row inset for the three title buttons. Desktop overrides this to 6
    // (Main.qml hides the custom title bar while the lyric page is open, so the
    // buttons sit flush at the very top of the window); mobile keeps the status
    // bar inset so they clear the system bar.
    property real topPad: settings.topInset + 6
    onOffsetPanelOpenChanged: {
        player.setLyricOffsetPanelOpen(offsetPanelOpen)
        // Synchronize once on entry. Do not feed every player property change back
        // into Slider.value while its MouseArea is dragging: that re-entrant write
        // interrupts qml4j's active gesture as soon as the thumb moves one step.
        if (offsetPanelOpen) offsetPanel.syncFromPlayer()
    }
    // Host actions can also close the lyric page, so clear the local panel when
    // the authoritative page state changes. Mirror it with a plain binding, not
    // Connections { target: player }: player is
    // a PlayerController, not a QObject, and qml4j's Connections codegen requires a
    // QObject target (a non-QObject target crashes with a ClassCastException at
    // Property.fireListeners on load).
    property bool lyricsOpenMirror: player.lyricsOpen
    onLyricsOpenMirrorChanged: if (!lyricsOpenMirror) overlay.offsetPanelOpen = false

    function handleBack() {
        if (!overlay.offsetPanelOpen && offsetPanel.opacity <= 0.01) return false
        overlay.offsetPanelOpen = false
        return true
    }

    // Chrome palette. The page always sits on the host's dark fluid backdrop
    // (cover-tinted, darkened), so its controls are white in both themes.
    readonly property color chromeFg: "#FFFFFFFF"
    readonly property color chromeFgSecondary: "#B3FFFFFF"
    readonly property color chromeFgTertiary: "#80FFFFFF"
    // Translucent white disc behind a toggled-on top-row button.
    readonly property color chromeActiveFill: "#2EFFFFFF"

    function fmt(ms) {
        if (ms <= 0) return "0:00";
        var s = Math.floor(ms / 1000), m = Math.floor(s / 60), r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    function showToast(message) {
        lyricSnack.show(message)
    }

    // Swallow taps on the empty (lyrics) area so they don't leak through.
    MouseArea { anchors.fill: parent }

    // --- Hero Cover: unified cover for both portrait and landscape transitions ---
    // Smoothly flies and scales from miniCover (bottom player) to the target position.
    PlaybackCoverImage {
        id: heroCover
        explicitDecodeSize: 800
        animationActive: player.lyricSlide > 0.001
        readonly property bool heroAnimating: player.lyricSlide > 0.001 && player.lyricSlide < 0.999
        heroProgress: heroAnimating ? overlay.transitionEase : (player.lyricSlide >= 0.999 ? 1.0 : 0.0)
        visible: overlay.landscape ? (overlay.fullWidthK < 0.995) : (overlay.coverOnly || heroAnimating || opacity > 0.01)

        x: heroAnimating
            ? ((1 - overlay.transitionEase) * overlay.miniCoverX + overlay.transitionEase * overlay.targetCoverX)
            : (overlay.targetCoverX - (overlay.landscape ? (overlay.coverOnly ? overlay.width : overlay.width / 2) * overlay.fullWidthK : 0))
        y: heroAnimating
            ? ((1 - overlay.transitionEase) * overlay.miniCoverY + overlay.transitionEase * overlay.targetCoverY)
            : overlay.targetCoverY
        width: heroAnimating
            ? ((1 - overlay.transitionEase) * overlay.miniCoverSize + overlay.transitionEase * overlay.targetCoverSize)
            : overlay.targetCoverSize
        height: width
        radius: heroAnimating
            ? ((1 - overlay.transitionEase) * overlay.miniCoverRadius + overlay.transitionEase * (overlay.targetCoverSize * 0.06))
            : (overlay.targetCoverSize * 0.06)
        iconSize: overlay.landscape ? 64 : 72
        fadeIn: true
        playing: player.playing
        source: player.coverPath

        layer.enabled: visible && !heroCover.vinyl
            && (overlay.prewarming || (player.lyricSlide >= 0.995
                && (!overlay.landscape || (Math.abs(landscapeChrome.width - (overlay.coverOnly ? overlay.width : overlay.width / 2)) < 0.5
                                           && Math.abs(overlay.targetCoverSize - landscapeChrome.targetCoverSize) < 0.5))))
        layer.effect: MultiEffect {
            shadowEnabled: true
            shadowColor: "#CC000000"
            shadowBlur: 0.65
            shadowVerticalOffset: 8
            shadowOpacity: 0.46
            blurMax: 48
        }

        // Portrait: fades out when lyrics are showing once expanded. Landscape: stays visible.
        property bool portraitShown: overlay.coverOnly && player.lyricSlide > 0.25
        opacity: overlay.landscape ? 1.0 : (heroAnimating ? 1.0 : (portraitShown ? 1.0 : 0.0))
        baseScale: overlay.landscape ? 1.0 : ((heroAnimating || portraitShown) ? 1.0 : 0.95)
        Behavior on opacity { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
        Behavior on baseScale { NumberAnimation { duration: 260; easing.type: Easing.OutBack } }

        MouseArea {
            anchors.fill: parent
            onClicked: player.setCoverMode(false)
        }
    }

    // --- top: dismiss + title + artist ---------------------------------
    IconButton {
        id: backBtn
        opacity: overlay.chromeOpacity
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.left: parent.left
        anchors.leftMargin: 6
        type: "standard"
        icon: "expand_more"
        contentColor: overlay.chromeFg
        onClicked: overlay.closeRequested()
    }

    // 播放页常亮 (Android only): the screen stays awake while a track is playing
    // and is allowed to sleep as soon as playback pauses. It describes how THIS
    // page behaves, so it can be toggled right here as well as from its Android-only
    // toggle in the 播放 settings list (same reasoning as the full-width layout
    // button below). Left of the top row, next to the
    // dismiss button (the right cluster belongs to view switching), because the
    // right-hand buttons come and go with cover mode and a slot anchored to a
    // hidden sibling would leave a hole. `settings.has` is false on the desktop
    // host, where there is no display timeout to defeat.
    IconButton {
        id: keepAwakeBtn
        objectName: "lyricKeepAwakeBtn"
        visible: settings.has("keepScreenOnPlayerPage")
        opacity: overlay.chromeOpacity
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.left: backBtn.right
        anchors.leftMargin: 6
        type: "standard"
        // Derived binding: read it in a handler only for the pre-toggle state,
        // and compute the new value before writing so the toast cannot be built
        // from a not-yet-flushed binding (DirtyQueue flushes later).
        property bool awake: settings.value("keepScreenOnPlayerPage") === true
        icon: "light_mode"
        containerColor: keepAwakeBtn.awake ? overlay.chromeActiveFill : "transparent"
        contentColor: overlay.chromeFg
        onClicked: {
            var next = !keepAwakeBtn.awake
            settings.setValue("keepScreenOnPlayerPage", next)
            overlay.showToast(i18n.t(next ? "lyric.keepAwake.on" : "lyric.keepAwake.off"))
        }
    }

    // Quick lyric-timing offset adjust: some LRC files don't quite line up with the
    // audio, so this lets the offset be nudged without leaving the lyric page. A
    // local panel, NOT a root Dialog: Dialog.open() reparents to the true QML root,
    // but the host only re-renders THIS subtree (objectName "lyricChrome") while the
    // lyric page fully covers the main scene — a reparented overlay would never draw.
    //
    // Top-right, mirroring backBtn's top-left: the host-drawn lyric column claims its
    // own tap-to-seek/drag-to-scroll gesture over most of the body (LyricCompositor.
    // lyricsScrollable) BEFORE QML ever sees the pointer event, so this corner is
    // explicitly carved out there (OFFSET_BTN_CORNER_*) to keep the button itself
    // reliably clickable; the panel's own content is exempted while open via
    // lyricOffsetPanelOpen (see onOffsetPanelOpenChanged above).
    IconButton {
        id: offsetBtn
        opacity: overlay.chromeOpacity
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.right: parent.right
        anchors.rightMargin: 6
        type: "standard"
        icon: "sync"
        containerColor: overlay.offsetPanelOpen || player.lyricOffsetMs !== 0
                        ? overlay.chromeActiveFill : "transparent"
        contentColor: overlay.chromeFg
        onClicked: overlay.offsetPanelOpen = !overlay.offsetPanelOpen
    }

    // Switch to cover view (issue #15: quick lyrics<->cover switching).
    // Hidden once already in cover view — tap the cover itself to come back. Same
    // top row as offsetBtn, to its left — NOT stacked below (offsetPanel drops down
    // from there and would overlap a button placed underneath).
    IconButton {
        id: coverModeBtn
        objectName: "lyricCoverModeBtn"
        visible: !overlay.coverOnly
        opacity: overlay.chromeOpacity
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.right: offsetBtn.left
        anchors.rightMargin: 6
        type: "standard"
        icon: "image"
        contentColor: overlay.chromeFg
        onClicked: player.setCoverMode(true)
    }

    // Full-width layout: a way of looking at this page, so it is toggled here
    // rather than from the settings list. Same top row, left of the cover switch.
    // Only where the layout exists at all: a window wider than it is tall, on a
    // host that has the setting (desktop — settings.has is false on Android).
    IconButton {
        id: fullWidthBtn
        objectName: "lyricFullWidthBtn"
        visible: overlay.landscape && !overlay.coverOnly
                 && settings.has("lyricFullWidth")
        opacity: overlay.chromeOpacity
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.right: coverModeBtn.left
        anchors.rightMargin: 6
        type: "standard"
        // NOT `on`: that is a reserved word in the QML grammar (`Behavior on x`),
        // and a property of that name fails to parse.
        property bool wide: settings.value("lyricFullWidth") === true
        icon: fullWidthBtn.wide ? "fullscreen_exit" : "fullscreen"
        containerColor: fullWidthBtn.wide ? overlay.chromeActiveFill : "transparent"
        contentColor: overlay.chromeFg
        // No confirmation toast: the page rearranging itself IS the feedback, and
        // a snackbar for it would cover the progress bar it just moved down there.
        onClicked: settings.setValue("lyricFullWidth", !fullWidthBtn.wide)
    }

    MouseArea {
        id: offsetScrim
        visible: overlay.offsetPanelOpen
        anchors.fill: parent
        z: 1
        onClicked: overlay.offsetPanelOpen = false
    }

    // Fixed-step slider: one control scales cleanly down to phone width, unlike the
    // previous row of four buttons which could overflow this card.
    LyricOffsetPanel {
        id: offsetPanel
        open: overlay.offsetPanelOpen
        z: 2
        anchors.top: offsetBtn.bottom
        anchors.topMargin: 8
        anchors.right: parent.right
        anchors.rightMargin: 6
    }

    MarqueeText {
        id: titleText
        visible: !overlay.landscape
        opacity: overlay.chromeOpacity
        anchors.top: backBtn.bottom
        anchors.topMargin: 2
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.title
        textColor: overlay.chromeFg
        fontSize: Ui.textTitle1
        fontWeight: Ui.weightBold
    }
    MarqueeText {
        id: artistText
        visible: !overlay.landscape
        opacity: overlay.chromeOpacity
        anchors.top: titleText.bottom
        anchors.topMargin: 4
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.artist
        textColor: overlay.chromeFgSecondary
        fontSize: Ui.textBody
    }
    // Jump to the artist's page. Main.qml's page stack keeps the lyric route
    // underneath and closes its separately-rendered host layer while the artist
    // route is current; Back therefore returns here instead of losing context.
    // Disabled (no pointer/tap) for local/custom tracks, which have no provider
    // artist identity to open.
    MouseArea {
        anchors.fill: artistText
        enabled: player.playingArtistIdsCsv !== "" || player.playingArtistId !== 0
        hoverEnabled: enabled
        cursorShape: Qt.PointingHandCursor
        onClicked: player.openPlayingArtist()
    }

    // --- bottom: transport (portrait) ---------------------------------
    Item {
        id: transport
        visible: !overlay.landscape
        opacity: overlay.chromeOpacity
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.bottomMargin: settings.bottomInset + 12
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        height: 120

        // progress (optional wave) + seek. The wavy phase is an infinite animation gated
        // on the bar's OWN `visible` (control.visible) — own visibility is not the
        // ancestor-effective one, so when the lyric page is closed (this whole
        // overlay invisible) the bar's own visible stayed true and the animation
        // kept ticking every frame, bumping the change version and defeating the
        // renderer's idle layout-skip. Tie its visibility to the page being shown.
        LyricProgress {
            id: progress
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.topMargin: 18
            wavy: settings.value("lyricProgressStyle") === 0
            visible: player.lyricSlide > 0.001 && !overlay.landscape
            // While the next track loads, sweep instead of showing a frozen position.
            indeterminate: player.loading
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
            color: overlay.chromeFgTertiary
            font.pixelSize: Ui.textMicro
        }
        Text {
            anchors.right: parent.right
            anchors.top: progress.bottom
            anchors.topMargin: 6
            text: overlay.fmt(player.durationMs)
            color: overlay.chromeFgTertiary
            font.pixelSize: Ui.textMicro
        }

        LyricTransportRow {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 8
            gap: 18
        }
    }

    // --- landscape: cover + title + transport on the left -------------
    // The host draws the lyrics in the right half (or, when coverOnly, nothing — and
    // this column centers across the full width). Plain anchors, no positioner: the
    // play clock republishes positionMs/lyricProgress ~5x/s and a Column/Layout here
    // would re-run its distribution pass each of those frames (see MiniPlayer).
    //
    // Occupies only the left half (full width when coverOnly) so it cannot cover the
    // host lyric column. qml4j hit-tests clip to parent bounds even without clip:true,
    // so the inner column must be as wide as the transport row — not the cover.
    Item {
        id: landscapeChrome
        objectName: "lyricLandscapeChrome"
        // Leaves by sliding off the left edge on the host's eased full-width
        // value, the way the cover column slides when switching cover<->lyrics,
        // rather than dissolving in place. Hidden outright once gone, so its
        // cover and controls stop hit-testing over a page whose chrome has moved
        // to the bottom.
        visible: overlay.landscape && overlay.fullWidthK < 0.995
        x: -width * overlay.fullWidthK
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: overlay.coverOnly ? overlay.width : overlay.width / 2
        Behavior on width { NumberAnimation { duration: 420; easing.type: Easing.OutBack } }

        readonly property real targetCoverSize:
            Math.max(120, Math.min(width - 96, overlay.height - 248, 360))
        property real coverSize: targetCoverSize
        Behavior on coverSize { NumberAnimation { duration: 420; easing.type: Easing.OutBack } }
        // Transport row spans the column; floor keeps prev/pause/next hittable.
        readonly property real transportMinWidth: 280

        Item {
            id: col
            width: Math.max(landscapeChrome.coverSize,
                            Math.min(parent.width - 48, landscapeChrome.transportMinWidth))
            // cover + (title 26 + artist 18 + gaps + progress + labels + buttons 48).
            height: landscapeChrome.coverSize + 196
            anchors.verticalCenter: parent.verticalCenter
            anchors.horizontalCenter: parent.horizontalCenter

            // Invisible placeholder reserving space for the top-level heroCover
            Item {
                id: coverPlaceholder
                anchors.top: parent.top
                anchors.horizontalCenter: parent.horizontalCenter
                width: landscapeChrome.coverSize
                height: landscapeChrome.coverSize
            }

            Item {
                id: colControls
                anchors.top: parent.top
                anchors.topMargin: landscapeChrome.coverSize + 20
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                opacity: overlay.chromeOpacity

                MarqueeText {
                    id: lTitle
                    anchors.top: parent.top
                    anchors.left: parent.left
                    anchors.right: parent.right
                    text: player.title
                    textColor: overlay.chromeFg
                    fontSize: Ui.textTitle2 + 2
                    fontWeight: Ui.weightBold
                    centered: true
                }
                MarqueeText {
                    id: lArtist
                    anchors.top: lTitle.bottom
                    anchors.topMargin: 4
                    anchors.left: parent.left
                    anchors.right: parent.right
                    text: player.artist
                    textColor: overlay.chromeFgSecondary
                    fontSize: Ui.textCallout
                    centered: true
                }
                MouseArea {
                    anchors.fill: lArtist
                    enabled: player.playingArtistIdsCsv !== "" || player.playingArtistId !== 0
                    hoverEnabled: enabled
                    cursorShape: Qt.PointingHandCursor
                    onClicked: player.openPlayingArtist()
                }
                LyricProgress {
                    id: lProgress
                    anchors.top: lArtist.bottom
                    anchors.topMargin: 22
                    anchors.left: parent.left
                    anchors.right: parent.right
                    wavy: settings.value("lyricProgressStyle") === 0
                    visible: player.lyricSlide > 0.001 && overlay.landscape && overlay.fullWidthK < 0.995
                    indeterminate: player.loading
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
                    color: overlay.chromeFgTertiary
                    font.pixelSize: Ui.textMicro
                }
                Text {
                    anchors.right: parent.right
                    anchors.top: lProgress.bottom
                    anchors.topMargin: 6
                    text: overlay.fmt(player.durationMs)
                    color: overlay.chromeFgTertiary
                    font.pixelSize: Ui.textMicro
                }
                LyricTransportRow {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    gap: Math.max(4, Math.min(18, (col.width - 200) / 4))
                }
            }
        }
    }

    // --- landscape, full width: no cover, lyrics across the whole page, and the
    // chrome reduced to a band along the bottom with the progress bar last.
    // Crossfades with landscapeChrome above on player.lyricFullWidth, which the
    // HOST eases (LyricCompositor) — the lyric column widens and its unassigned
    // lines drift to centre on that same value, so the band and the lyrics move
    // as one gesture instead of two animations racing each other.
    //
    // Its own Item rather than a reshaped landscapeChrome: that one spans the
    // full height, and a full-width copy of it would sit over the host lyric
    // column. This reserves only the band the host excludes from its own
    // scroll/seek region (L_FULLWIDTH_BOTTOM).
    Item {
        id: wideChrome
        objectName: "lyricWideChrome"
        readonly property real k: overlay.fullWidthK
        visible: overlay.landscape && k > 0.005
        anchors.left: parent.left
        anchors.right: parent.right
        height: 152
        // Pushes up from below the bottom edge — a plain translation, no fade, so
        // it arrives the same way the cover column travels rather than
        // materialising in place.
        y: parent.height - height * k

        MarqueeText {
            id: wTitle
            anchors.left: parent.left
            anchors.leftMargin: 28
            anchors.top: parent.top
            anchors.topMargin: 18
            width: Math.max(140, parent.width * 0.24)
            text: player.title
            textColor: overlay.chromeFg
            fontSize: Ui.textTitle2
            fontWeight: Ui.weightBold
        }
        MarqueeText {
            id: wArtist
            anchors.left: wTitle.left
            anchors.top: wTitle.bottom
            anchors.topMargin: 3
            width: wTitle.width
            text: player.artist
            textColor: overlay.chromeFgSecondary
            fontSize: Ui.textCallout
        }
        MouseArea {
            anchors.fill: wArtist
            enabled: player.playingArtistIdsCsv !== "" || player.playingArtistId !== 0
            hoverEnabled: enabled
            cursorShape: Qt.PointingHandCursor
            onClicked: player.openPlayingArtist()
        }

        LyricTransportRow {
            id: wTransport
            objectName: "lyricWideTransport"
            anchors.left: parent.left
            anchors.leftMargin: 28
            anchors.right: parent.right
            anchors.rightMargin: 28
            anchors.top: parent.top
            anchors.topMargin: 16
            gap: 20
        }

        Text {
            anchors.right: parent.right
            anchors.rightMargin: 28
            anchors.verticalCenter: wTransport.verticalCenter
            text: overlay.fmt(player.positionMs) + " / " + overlay.fmt(player.durationMs)
            color: overlay.chromeFgTertiary
            font.pixelSize: Ui.textCaption
        }

        LyricProgress {
            id: wProgress
            objectName: "lyricWideProgress"
            anchors.left: parent.left
            anchors.leftMargin: 28
            anchors.right: parent.right
            anchors.rightMargin: 28
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 30
            wavy: settings.value("lyricProgressStyle") === 0
            visible: player.lyricSlide > 0.001 && overlay.landscape && overlay.fullWidthK > 0.005
            indeterminate: player.loading
            value: player.lyricProgress
        }
        MouseArea {
            anchors.fill: wProgress
            anchors.topMargin: -12
            anchors.bottomMargin: -12
            onPressed: if (player.durationMs > 0)
                           player.seek(Math.round(mouseX / width * player.durationMs))
            onPositionChanged: if (pressed && player.durationMs > 0)
                                   player.seek(Math.round(Math.max(0, Math.min(width, mouseX)) / width * player.durationMs))
        }
    }

    // The lyric page is composited in a separate host pass, above Main.qml's
    // normal scene. Mirror transient notifications here so they remain visible.
    NoticeStack {
        id: lyricSnack
        z: 100
    }
}
