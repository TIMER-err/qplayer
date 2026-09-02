import QtQuick
import md3.Core
import "."

// One song/track row. Plain anchors — NOT nested RowLayout/ColumnLayout: the
// Layout measure passes run for every visible row on every dirty frame (playback
// ticks the scene ~5x/s), which was a real source of stutter. `highlighted`
// marks the playing entry. A pre-cached local-file Image replaces the glyph
// when a thumbnail is available — zero network overhead while scrolling.
//
// Lazy image loading: when `lazyLoad` is true, the cover Image only sets its
// source when the row is within the Flickable viewport (+/- 3 rows preload).
// This prevents hundreds of off-screen images from being decoded into memory
// simultaneously in long playlists.
Rectangle {
    id: row

    property string rowTitle: ""
    property string rowArtist: ""
    // Id of rowArtist's (first-listed) artist -- 0 for local tracks / unknown,
    // which disables the artist-name tap target below.
    property var rowArtistId: 0
    // Full credited-artist list. Clicking a multi-artist line opens the shared
    // picker; a single id goes straight to the artist page in PlayerController.
    property string rowArtistIdsCsv: ""
    property string rowArtistNamesCsv: ""
    property string coverThumbPath: ""
    // Small per-row source badge (provider name / "本地") for lists that
    // mix rows from more than one source — see SearchPage.qml. Empty (default)
    // shows nothing, so every other VirtualSongList caller is unaffected.
    property string tag: ""
    property bool highlighted: false
    property bool removable: false
    // Long-press context menu. `song` is the raw model item (needs `.id`); the
    // menu only arms when `menuEnabled` (provider-backed lists opt in — local rows
    // have no playlist-track id). `inOwnedPlaylist` + `ownerPlaylistId` unlock the
    // "从此歌单移除" entry, set only by the owned-playlist detail list.
    property var song: null
    property bool menuEnabled: row.song !== null
    property bool inOwnedPlaylist: false
    // Cached-songs list mode (see SongContextMenu.inCacheList): flips the menu's
    // "缓存此歌曲" entry to "删除缓存".
    property bool inCacheList: false
    // Latched when a long-press fired, so the release's click doesn't also play the
    // song; cleared when the menu closes (or on the guarded click).
    property bool _menuArmed: false
    /** Enable lazy image loading based on viewport position. */
    property bool lazyLoad: false
    /** Parent Flickable's contentY (scroll offset). */
    property real flickContentY: 0
    /** Parent Flickable's visible height. */
    property real flickHeight: 0
    // Small "cached, plays offline" badge on the cover's corner. Only meaningful
    // (and only ever true) while the containing list is actually in an offline
    // state — see VirtualSongList.showOfflineBadge — so it stays invisible during
    // normal online browsing instead of cluttering every row with a checkmark.
    property bool offlineReady: false
    signal activated()
    signal removeRequested()

    implicitHeight: 64
    color: "transparent"

    // Inset rounded hover highlight. Constant Rectangle (no per-frame allocation);
    // its opacity fades with hover instead of snapping the colour. The playing row
    // keeps its primary-tinted text/glyph rather than a fill.
    Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 8
        anchors.topMargin: 4
        anchors.bottomMargin: 4
        radius: 12
        color: Theme.color.surfaceContainerHigh
        opacity: ripple.containsMouse ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
    }

    // Cover sits 4px inside the hover pill on every side, and its radius is the
    // pill's radius minus that 4px gap (12 - 4 = 8) so the two rounded corners are
    // concentric: leftMargin 8(pill)+4, size 64-2*(4+4) = 48, radius 8.
    Item {
        id: leading
        anchors.left: parent.left
        anchors.leftMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        width: 48
        height: 48

        // Placeholder background (shown when no cover)
        Rectangle {
            anchors.fill: parent
            radius: 8
            color: Theme.color.surfaceContainerHighest
            visible: row.coverThumbPath == ""
            Text {
                // Size to the box via reactive width/height bindings — NOT anchors.fill /
                // centerIn, which run in the layout pass that cachedLayout skips for a row
                // first realized off-screen, leaving the node 0x0 and the glyph at the
                // box's top-left until a page switch rebuilt it. With a real box size the
                // glyph self-centres at paint time (AlignHCenter + the icon vertical baseline).
                width: parent.width
                height: parent.height
                horizontalAlignment: Text.AlignHCenter
                text: row.highlighted ? "equalizer" : "music_note"
                font.family: Theme.iconFont.name
                font.pixelSize: 22
                color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceVariantColor
            }
        }

        // Cover image with native clipRRect rounding (qml4j Image.radius).
        // When lazyLoad is on, source is set once when the row enters the viewport
        // preload zone and never cleared — this avoids re-fetching/re-decoding on
        // scroll-back while still preventing all-off-screen images from loading
        // at list creation time.
        Image {
            id: coverImg
            anchors.fill: parent
            visible: row.coverThumbPath != "" && (!row.lazyLoad || row._loadTriggered)
            source: (row.coverThumbPath != "" && (!row.lazyLoad || row._loadTriggered))
                   ? row.coverThumbPath : ""
            radius: 8
            fillMode: Image.PreserveAspectCrop
            // See CoverImage.qml: decode-time downscale (mipmap-quality)
            // instead of a plain bilinear draw-time scale, which aliases
            // into moiré. Fixed size (the leading box is always 48x48), so
            // no reuse-staleness risk from VirtualSongList's windowing.
            sourceSize.width: width
            sourceSize.height: height
            // Fade the art in as it loads, like the lyric-page cover. Keyed on source
            // presence (not load status, which would deadlock — opacity 0 skips the
            // paint that advances the decode). A reused row whose source swaps path→path
            // keeps opacity 1, so scrolling doesn't re-fade every cover.
            opacity: coverImg.source !== "" ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
        }

        // "Cached, plays offline" badge — bottom-right corner of the cover.
        Rectangle {
            visible: row.offlineReady
            width: 16
            height: 16
            radius: 8
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.margins: -2
            color: Theme.color.primary
            border.width: 1.5
            border.color: Theme.color.surface
            Text {
                anchors.centerIn: parent
                text: "check"
                font.family: Theme.iconFont.name
                font.pixelSize: 11
                color: Theme.color.onPrimaryColor
            }
        }
    }

    // How much room the title/artist lines leave on the right: the remove "×"
    // and the source tag are mutually exclusive in practice (no caller sets
    // both), but sizing for whichever is present keeps text from sliding under it.
    property int _rightReserve: row.removable ? 52 : (row.tag !== "" ? (tagPill.width + 24) : 16)

    Text {
        anchors.left: leading.right
        anchors.leftMargin: 14
        anchors.right: parent.right
        anchors.rightMargin: row._rightReserve
        anchors.bottom: parent.verticalCenter
        anchors.bottomMargin: 1
        text: row.rowTitle
        elide: Text.ElideRight
        color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceColor
        fontSize: 15
    }

    Text {
        id: artistText
        anchors.left: leading.right
        anchors.leftMargin: 14
        anchors.right: parent.right
        anchors.rightMargin: row._rightReserve
        anchors.top: parent.verticalCenter
        anchors.topMargin: 2
        text: row.rowArtist
        elide: Text.ElideRight
        color: ((row.rowArtistIdsCsv !== "" || row.rowArtistId !== 0)
                && artistArea.containsMouse)
               ? Theme.color.primary : Theme.color.onSurfaceVariantColor
        fontSize: 12
    }

    // Unconstrained shaping probe for the real rendered glyph width. The visible
    // Text is anchored across the row so it can elide, therefore its own width is
    // the available column width rather than the artist text's painted width.
    Text {
        id: artistProbe
        visible: false
        text: row.rowArtist
        font.family: artistText.font.family
        font.pixelSize: artistText.font.pixelSize
    }

    // Small source badge (see `tag` above), pinned to the right edge. Sized off
    // row.tag's own length rather than tagText.implicitWidth: this row is one of
    // VirtualSongList's windowed delegates, which get REUSED across different
    // model rows as the list scrolls (index/modelData rewritten in place, not
    // recreated) — a recycled Text's implicitWidth didn't reliably recompute when
    // its text changed afterward, so a delegate that first showed a short tag
    // (e.g. "本地") then got recycled for a longer one (e.g. "自定义源") kept the
    // old, too-narrow pill width and the label overflowed outside it. `row.tag`
    // itself already updates correctly on reuse (rowTitle/rowArtist prove that),
    // so deriving width from the string directly sidesteps the whole thing.
    Rectangle {
        id: tagPill
        visible: row.tag !== ""
        anchors.right: parent.right
        anchors.rightMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        radius: 8
        color: Theme.color.surfaceContainerHighest
        width: Math.max(36, row.tag.length * 12 + 16)
        height: 20

        Text {
            id: tagText
            anchors.centerIn: parent
            text: row.tag
            elide: Text.ElideRight
            fontSize: 11
            color: Theme.color.onSurfaceVariantColor
        }
    }

    // Tap + Material ripple, clipped to the inset pill shape. Idle cost is nil
    // (Ripple gates its MultiEffect on live-wave count); a wave only renders while
    // a row is being pressed. Reactive geometry, NOT anchors: under cachedLayout
    // (long lists) the measure pass that resolves anchors is skipped once the
    // container box is stable, so an anchor-sized ripple stays stuck at the row's
    // first (often zero, hence -16 after margins) width — the mispositioned/half/
    // crashing ripple. Width bindings track row.width and update without a re-measure.
    Ripple {
        id: ripple
        x: 8
        y: 4
        width: row.width - 16
        height: row.height - 8
        clipRadius: 12
        rippleColor: Theme.color.onSurfaceColor
        longPressEnabled: row.menuEnabled && row.song !== null
        onClicked: {
            if (row._menuArmed) { row._menuArmed = false; return }
            row.activated()
        }
        onLongPressed: { row._menuArmed = true; row._openMenu() }
    }

    // Tap only the actually-painted artist text. The separate probe measures the
    // unelided glyph run; min() clips the target to the visible/elided width.
    MouseArea {
        id: artistArea
        anchors.left: artistText.left
        anchors.verticalCenter: artistText.verticalCenter
        width: Math.min(artistText.width, artistProbe.implicitWidth)
        height: 20
        enabled: row.rowArtistIdsCsv !== "" || (row.rowArtistId !== 0 && row.rowArtistId !== "")
        hoverEnabled: enabled
        cursorShape: Qt.PointingHandCursor
        onClicked: {
            if (row.rowArtistIdsCsv !== "")
                player.openSongArtistPicker(row.rowArtistIdsCsv, row.rowArtistNamesCsv)
            else if (("" + row.rowArtistId).indexOf(":") >= 0)
                player.openMediaArtist("" + row.rowArtistId)
            else
                player.openArtist(row.rowArtistId)
        }
    }

    // Context menu, built only for rows that opt in (menuEnabled). Loaded eagerly
    // for those rows — qml4j's Loader is async, so `item` isn't available the
    // instant you flip `active`; instantiating up front guarantees it's ready when
    // the long-press fires. Idle cost stays low: the menu's model is empty until
    // rebuild() runs on open, so no submenu delegates exist until then.
    Loader {
        id: menuLoader
        active: row.menuEnabled
        sourceComponent: SongContextMenu {
            song: row.song
            inOwnedPlaylist: row.inOwnedPlaylist
            inCacheList: row.inCacheList
            onClosed: row._menuArmed = false
        }
    }
    function _openMenu() {
        if (row.song === null || menuLoader.item === null) return
        menuLoader.item.rebuild()
        menuLoader.item.open(ripple, ripple.pressX, ripple.pressY)
    }

    // Lightweight remove control: a glyph + MouseArea with explicit geometry.
    // The md3 IconButton (internal Ripple + MouseArea) got stuck mispositioned and
    // untappable when a delegate was rebuilt on a queue removal under cachedLayout;
    // this single-pass control avoids that and is cheaper per row. Declared last so
    // it sits above the row tap.
    MouseArea {
        visible: row.removable
        width: 48
        height: parent.height
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        onClicked: row.removeRequested()

        Text {
            anchors.centerIn: parent
            text: "close"
            font.family: Theme.iconFont.name
            font.pixelSize: 20
            color: Theme.color.onSurfaceVariantColor
        }
    }

    // Whether this row is within (or near) the Flickable viewport.
    // Preload margin: 3 rows above/below the visible area.
    readonly property bool _inViewport: !row.lazyLoad
        || (row.y >= row.flickContentY - row.height * 3
            && row.y <= row.flickContentY + row.flickHeight + row.height * 3)

    // Latches to true the first time _inViewport becomes true, so that once
    // an image starts loading it is never unloaded (avoids re-fetch flicker).
    property bool _loadTriggered: row._inViewport
    on_InViewportChanged: { if (row._inViewport) row._loadTriggered = true; }
}
