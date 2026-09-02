import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
import "."

// QML chrome for the lyric page, composited on top of the host-drawn fluid
// backdrop + per-syllable lyrics. Transparent everywhere except the title band
// (top) and the transport band (bottom), so the host lyrics show through the
// middle. Visibility/opacity follow player.lyricSlide (published by the host) so
// it fades in lockstep with the host layer.
Item {
    id: overlay
    signal closeRequested()

    // Landscape (wide) layout: cover + transport on the left, lyrics on the right
    // half (host-drawn). Driven by aspect so a desktop window, tablet, or phone in
    // landscape all adopt it. coverOnly (no lyrics / instrumental) centers the cover.
    property bool landscape: overlay.width > overlay.height
    // OR of the automatic no-lyrics/instrumental detection and the user's manual
    // lyrics<->cover toggle (the button below / tapping the cover to return).
    property bool coverOnly: player.lyricsCoverOnly || player.coverModeManual
    property bool offsetPanelOpen: false
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
        if (offsetPanelOpen) offsetSlider.value = player.lyricOffsetMs
    }
    // The page can also close via Esc / Android back, which bypasses this QML
    // entirely (PlayerController.pressBack), so mirror the other direction too. A
    // plain binding + local onChanged, not Connections { target: player }: player is
    // a PlayerController, not a QObject, and qml4j's Connections codegen requires a
    // QObject target (a non-QObject target crashes with a ClassCastException at
    // Property.fireListeners on load).
    property bool lyricsOpenMirror: player.lyricsOpen
    onLyricsOpenMirrorChanged: if (!lyricsOpenMirror) overlay.offsetPanelOpen = false


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

    // --- portrait: big centred cover for no-lyric / instrumental tracks. It zooms +
    // fades in/out on the lyrics↔cover switch (SPlayer's zoom transition) — no big/small
    // morph, title/artist stay put. Landscape has its own cover in the left chrome.
    PlaybackCoverImage {
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
        fadeIn: true
        playing: player.playing
        layer.enabled: visible
        layer.effect: MultiEffect {
            shadowEnabled: true
            shadowColor: "#CC000000"
            shadowBlur: 0.65
            shadowVerticalOffset: 8
            shadowOpacity: 0.46
            blurMax: 48
        }
        // Only the resolved local cover path, not the coverUrl fallback: on a track
        // switch coverPath clears to "" until the new art is ready, so the placeholder
        // shows through (fadeIn) instead of the previous song's cover lingering.
        source: player.coverPath
        // Zoom + fade in step with the host lyric column's own zoom (SPlayer's whole-
        // content zoom): cover grows in as the lyrics shrink out, and vice versa.
        property bool shown: overlay.coverOnly && player.lyricSlide > 0.25
        opacity: shown ? 1 : 0
        baseScale: shown ? 1 : 0.95
        Behavior on opacity { NumberAnimation { duration: 250; easing.type: Easing.OutCubic } }
        Behavior on baseScale { NumberAnimation { duration: 300; easing.type: Easing.OutBack } }

        // Tap the cover to switch back to lyrics. A no-op when lyricsCoverOnly (no
        // lyrics for this track) is what's actually forcing cover view — there's
        // nothing to switch back to, so coverOnly just stays true either way.
        MouseArea {
            anchors.fill: parent
            onClicked: player.setCoverMode(false)
        }
    }

    // --- top: dismiss + title + artist ---------------------------------
    IconButton {
        id: backBtn
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.left: parent.left
        anchors.leftMargin: 6
        type: "standard"
        icon: "expand_more"
        contentColor: "#FFFFFFFF"
        onClicked: overlay.closeRequested()
    }

    // Quick lyric-timing offset adjust: some LRC files don't quite line up with the
    // audio, so this lets the offset be nudged without leaving the lyric page. A
    // local panel, NOT an md3 Dialog: Dialog.open() reparents to the true QML root,
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
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.right: parent.right
        anchors.rightMargin: 6
        type: "standard"
        icon: "sync"
        contentColor: "#FFFFFFFF"
        onClicked: overlay.offsetPanelOpen = !overlay.offsetPanelOpen
    }

    // Switch to cover view (issue #15: quick lyrics<->cover switching, à la 网易云).
    // Hidden once already in cover view — tap the cover itself to come back. Same
    // top row as offsetBtn, to its left — NOT stacked below (offsetPanel drops down
    // from there and would overlap a button placed underneath).
    IconButton {
        id: coverModeBtn
        visible: !overlay.coverOnly
        anchors.top: parent.top
        anchors.topMargin: overlay.topPad
        anchors.right: offsetBtn.left
        anchors.rightMargin: 6
        type: "standard"
        icon: "image"
        contentColor: "#FFFFFFFF"
        onClicked: player.setCoverMode(true)
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
    Rectangle {
        id: offsetPanel
        // Keep painting until the exit fade reaches zero; binding visible directly
        // to offsetPanelOpen would cut the zoom-out off on its first frame.
        visible: overlay.offsetPanelOpen || opacity > 0.01
        opacity: overlay.offsetPanelOpen ? 1 : 0
        scale: overlay.offsetPanelOpen ? 1 : 0.9
        transformOrigin: Item.TopRight
        Behavior on opacity {
            NumberAnimation { duration: overlay.offsetPanelOpen ? 160 : 120; easing.type: Easing.OutCubic }
        }
        Behavior on scale {
            NumberAnimation {
                duration: overlay.offsetPanelOpen ? 220 : 150
                easing.type: overlay.offsetPanelOpen ? Easing.OutBack : Easing.InCubic
            }
        }
        z: 2
        anchors.top: offsetBtn.bottom
        anchors.topMargin: 8
        anchors.right: parent.right
        anchors.rightMargin: 6
        width: Math.min(320, parent.width - 24)
        height: 176
        radius: 20
        color: Theme.color.surfaceContainerHigh
        border.width: 1
        border.color: Theme.color.outlineVariant

        MouseArea { anchors.fill: parent }

        Text {
            id: offsetTitle
            anchors.top: parent.top
            anchors.topMargin: 18
            anchors.left: parent.left
            anchors.leftMargin: 18
            text: "歌词偏移"
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.titleSmall.family
            font.pixelSize: Theme.typography.titleSmall.size
        }
        Text {
            anchors.top: parent.top
            anchors.topMargin: 16
            anchors.right: parent.right
            anchors.rightMargin: 18
            text: (offsetSlider.value > 0 ? "+" : "") + offsetSlider.value + " ms"
            color: Theme.color.primary
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: Theme.typography.titleMedium.size
            font.weight: Theme.typography.titleMedium.weight
        }
        Text {
            id: offsetCaption
            anchors.top: offsetTitle.bottom
            anchors.topMargin: 4
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 18
            anchors.rightMargin: 18
            text: "仅对当前歌曲生效 · 负值提前，正值延后"
            color: Theme.color.onSurfaceVariantColor
            font.family: Theme.typography.bodySmall.family
            font.pixelSize: Theme.typography.bodySmall.size
            elide: Text.ElideRight
        }

        Slider {
            id: offsetSlider
            anchors.top: offsetCaption.bottom
            anchors.topMargin: 10
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 18
            anchors.rightMargin: 18
            from: -5000
            to: 5000
            stepSize: 50
            snapMode: true
            value: 0
            // Keep dragging entirely local. Updating PlayerController on every 50 ms
            // step re-enters the QML tree (lyric index + persistence) during the
            // pointer callback and interrupts the gesture in qml4j.
            onEditingFinished: player.setLyricOffset(value)
        }

        Text {
            anchors.top: offsetSlider.bottom
            anchors.topMargin: 1
            anchors.left: parent.left
            anchors.leftMargin: 18
            text: "提前 5 秒"
            color: Theme.color.onSurfaceVariantColor
            font.family: Theme.typography.labelSmall.family
            font.pixelSize: Theme.typography.labelSmall.size
        }
        Text {
            anchors.top: offsetSlider.bottom
            anchors.topMargin: 1
            anchors.horizontalCenter: parent.horizontalCenter
            text: "0"
            color: Theme.color.onSurfaceVariantColor
            font.family: Theme.typography.labelSmall.family
            font.pixelSize: Theme.typography.labelSmall.size
        }
        Text {
            anchors.top: offsetSlider.bottom
            anchors.topMargin: 1
            anchors.right: parent.right
            anchors.rightMargin: 18
            text: "延后 5 秒"
            color: Theme.color.onSurfaceVariantColor
            font.family: Theme.typography.labelSmall.family
            font.pixelSize: Theme.typography.labelSmall.size
        }
        Button {
            id: resetBtn
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 8
            anchors.right: parent.right
            anchors.rightMargin: 10
            type: "text"
            text: "重置为 0"
            enabled: offsetSlider.value !== 0
            onClicked: {
                offsetSlider.value = 0
                player.resetLyricOffset()
            }
        }
    }

    MarqueeText {
        id: titleText
        visible: !overlay.landscape
        anchors.top: backBtn.bottom
        anchors.topMargin: 2
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.title
        textColor: "#FFFFFFFF"
        fontFamily: Theme.typography.titleLarge.family
        fontSize: 22
    }
    MarqueeText {
        id: artistText
        visible: !overlay.landscape
        anchors.top: titleText.bottom
        anchors.topMargin: 4
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.artist
        textColor: "#B3FFFFFF"
        fontSize: 14
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
            wavy: settings.value("lyricProgressStyle") === 0
            visible: player.lyricSlide > 0.001
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

            PlaybackCoverImage {
                id: lCover
                anchors.top: parent.top
                anchors.horizontalCenter: parent.horizontalCenter
                width: landscapeChrome.coverSize
                height: landscapeChrome.coverSize
                radius: Math.min(width, height) * 0.06
                iconSize: 64
                fadeIn: true
                playing: player.playing
                source: player.coverPath
                layer.enabled: visible
                layer.effect: MultiEffect {
                    shadowEnabled: true
                    shadowColor: "#CC000000"
                    shadowBlur: 0.65
                    shadowVerticalOffset: 8
                    shadowOpacity: 0.46
                    blurMax: 48
                }

                // Same tap-to-return as the portrait cover above; harmless (no-op)
                // when already showing lyrics.
                MouseArea {
                    anchors.fill: parent
                    onClicked: player.setCoverMode(false)
                }
            }
            MarqueeText {
                id: lTitle
                anchors.top: lCover.bottom
                anchors.topMargin: 20
                anchors.left: parent.left
                anchors.right: parent.right
                text: player.title
                textColor: "#FFFFFFFF"
                fontFamily: Theme.typography.titleLarge.family
                fontSize: 20
                centered: true
            }
            MarqueeText {
                id: lArtist
                anchors.top: lTitle.bottom
                anchors.topMargin: 4
                anchors.left: parent.left
                anchors.right: parent.right
                text: player.artist
                textColor: "#B3FFFFFF"
                fontSize: 13
                centered: true
            }
            MouseArea {
                anchors.fill: lArtist
                enabled: player.playingArtistIdsCsv !== "" || player.playingArtistId !== 0
                hoverEnabled: enabled
                cursorShape: Qt.PointingHandCursor
                onClicked: player.openPlayingArtist()
            }
            LinearProgress {
                id: lProgress
                anchors.top: lArtist.bottom
                anchors.topMargin: 22
                anchors.left: parent.left
                anchors.right: parent.right
                wavy: settings.value("lyricProgressStyle") === 0
                visible: player.lyricSlide > 0.001
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

    // The lyric page is composited in a separate host pass, above Main.qml's
    // normal scene. Mirror transient notifications here so they remain visible.
    ToastStack {
        id: lyricSnack
        z: 100
    }
}
