import QtQuick
import miuix.Core
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
    // Small per-row source badge (provider name / local) for lists that
    // mix rows from more than one source — see SearchPage.qml. Empty (default)
    // shows nothing, so every other VirtualSongList caller is unaffected.
    property string tag: ""
    property bool highlighted: false
    property bool removable: false
    // Shows a drag handle that starts a reorder. Only the handle does — the rest
    // of the row keeps its tap and long-press behaviour.
    property bool reorderable: false
    /** Finger position in the list's content coordinates, published for the list
     *  to turn into a target row (it owns rowH and the model). */
    property real reorderContentY: 0
    /** Where inside the row the grip was grabbed, so the list can keep that exact
     *  point under the finger instead of snapping the row's top to it. */
    property real reorderGrabOffset: 0
    /** True for the row being carried: it lifts off the list and follows the
     *  finger rather than sitting in its slot. */
    property bool dragging: false
    /** Vertical offset opening a gap for the carried row. The list writes the
     *  target; the Behavior below is what makes neighbours glide instead of
     *  teleporting. Deliberately NOT a Behavior on `y`: `y` also jumps when the
     *  virtual window recycles a delegate onto a different index, and animating
     *  that would send rows flying across the screen on every scroll.
     */
    property real reorderShift: 0
    Behavior on reorderShift {
        NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
    }
    // The lift itself. Small on purpose — this is a 64px row, not a card.
    scale: row.dragging ? 1.03 : 1.0
    Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }
    signal reorderPressed()
    signal reorderDragged()
    signal reorderReleased()
    // Long-press context menu. `song` is the raw model item (needs `.id`); the
    // menu only arms when `menuEnabled` (provider-backed lists opt in — local rows
    // have no playlist-track id). `inOwnedPlaylist` + `ownerPlaylistId` unlock the
    // "remove from playlist" entry, set only by the owned-playlist detail list.
    property var song: null
    property bool menuEnabled: row.song !== null
    property bool inOwnedPlaylist: false
    // Cached-songs list mode (see SongContextMenu.inCacheList): flips the menu's
    // cache entry to "remove cache".
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
    onSongChanged: {
        row._menuArmed = false
        if (menuLoader.item && menuLoader.item.opened) menuLoader.item.dismissImmediately()
    }
    onCoverThumbPathChanged: row._loadTriggered = row._inViewport
    signal activated()
    signal removeRequested()

    implicitHeight: 64
    color: "transparent"

    // Inset rounded hover highlight. Constant Rectangle (no per-frame allocation);
    // its opacity fades with hover instead of snapping the colour. The playing row
    // keeps its primary-tinted text/glyph rather than a fill.
    Rectangle {
        id: rowBackground
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 8
        anchors.topMargin: 4
        anchors.bottomMargin: 4
        radius: 16
        // While carried the row needs to read as detached from the list. qml4j's
        // MultiEffect accepts shadow properties but does not paint them yet, so the
        // lift is done with an opaque raised fill plus an outline instead.
        color: row.dragging ? Theme.color.surfaceContainerHighest
             : (row.highlighted ? Theme.color.primaryContainer : Theme.color.surfaceContainerHigh)
        opacity: row.dragging ? 1 : (row.highlighted ? 0.65 : (ripple.containsMouse ? 1 : 0))
        border.width: row.dragging ? 1 : 0
        border.color: Theme.color.outlineVariant
        Behavior on opacity { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
        Behavior on color { ColorAnimation { duration: 150 } }
    }

    // Cover sits 4px inside the hover background. Subtract that inset from
    // the outer radius (16 - 4 = 12) so the rounded corners are concentric.
    Item {
        id: leading
        readonly property real cornerRadius: Math.max(0, rowBackground.radius - 4)
        anchors.left: parent.left
        anchors.leftMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        width: 48
        height: 48

        // Placeholder background (shown when no cover)
        Rectangle {
            anchors.fill: parent
            radius: leading.cornerRadius
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
            radius: leading.cornerRadius
            fillMode: Image.PreserveAspectCrop
            // See CoverImage.qml: decode-time downscale (mipmap-quality)
            // instead of a plain bilinear draw-time scale, which aliases
            // into moiré, scaled by the device pixel ratio so it decodes at
            // display resolution instead of 1x. Fixed size (the leading box is
            // always 48x48), so no reuse-staleness risk from VirtualSongList's
            // windowing.
            sourceSize.width: Math.round(48 * player.pixelRatio)
            sourceSize.height: Math.round(48 * player.pixelRatio)
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
    property real _rightReserve: (row.removable ? 68 : (tagPill.visible ? (tagPill.width + 24) : 16))
                                 + (row.reorderable ? 44 : 0)

    Text {
        id: titleText
        objectName: "songRowTitle"
        x: 74
        y: row.height / 2 - height - 1
        width: Math.max(0, row.width - x - row._rightReserve)
        text: row.rowTitle
        elide: Text.ElideRight
        color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceColor
        fontSize: 16
    }

    Text {
        id: artistText
        x: titleText.x
        y: row.height / 2 + 2
        width: titleText.width
        text: row.rowArtist
        elide: Text.ElideRight
        color: ((row.rowArtistIdsCsv !== "" || row.rowArtistId !== 0)
                && artistArea.containsMouse)
               ? Theme.color.primary : Theme.color.onSurfaceVariantColor
        fontSize: 13
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

    // Keep the source badge inside the row and leave at least 100dp for the
    // title when a badge fits. Reactive geometry also follows recycled tags
    // under cachedLayout, without waiting for a fresh anchor-layout pass.
    Rectangle {
        id: tagPill
        objectName: "songSourceBadge"
        visible: row.tag !== "" && !row.removable && width >= 32
        x: Math.max(0, row.width - width - 12)
        y: (row.height - height) / 2
        radius: 8
        color: Theme.color.surfaceContainerHighest
        width: Math.min(Math.max(36, row.tag.length * 12 + 16), 112, Math.max(0, row.width - 198))
        height: 20

        Text {
            id: tagText
            objectName: "songSourceBadgeLabel"
            x: 8
            // Center the measured line explicitly. Stretching the Text to the
            // pill's height and leaning on verticalAlignment does not work here:
            // qml4j accepts the property but it does not affect painting (same
            // reason TextField.qml positions its label by hand), so the glyphs
            // stayed at the top of the pill.
            y: (parent.height - implicitHeight) / 2
            width: Math.max(0, parent.width - 16)
            horizontalAlignment: Text.AlignHCenter
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
        clipRadius: rowBackground.radius
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

    // Reorder grip. Explicit geometry, like every other child here: anchors do
    // not survive the skipped measure pass under cachedLayout.
    Item {
        id: dragHandle
        objectName: "songRowDragHandle"
        visible: row.reorderable
        width: 44
        height: 44
        x: Math.max(0, row.width - width - (row.removable ? 72 : 16))
        y: (row.height - height) / 2

        Icon {
            name: "drag_handle"
            width: 22
            height: 22
            x: (parent.width - width) / 2
            y: (parent.height - height) / 2
            color: handleArea.pressed ? Theme.color.primary
                                      : Theme.color.onSurfaceVariantColor
        }

        MouseArea {
            id: handleArea
            x: 0
            y: 0
            width: parent.width
            height: parent.height
            enabled: row.reorderable
            // The row lives in a Flickable: without this the first few pixels of
            // a vertical drag are taken for scrolling and the reorder never starts.
            preventStealing: true
            cursorShape: Qt.SizeVerCursor
            onPressed: (mouse) => {
                // Where in the row the finger landed. The list keeps this point
                // under the cursor for the whole gesture, so the carried row does
                // not jump when the drag starts.
                row.reorderGrabOffset = dragHandle.y + mouse.y
                row.reorderContentY = row.y + row.reorderGrabOffset
                row.reorderPressed()
            }
            onPositionChanged: (mouse) => {
                if (!handleArea.pressed) return
                // row.y is this row's offset inside the list content, so adding
                // the grip's offset and the finger's own position inside it gives
                // a content-space coordinate the list can map to a row.
                row.reorderContentY = row.y + dragHandle.y + mouse.y
                row.reorderDragged()
            }
            onReleased: row.reorderReleased()
            onCanceled: row.reorderReleased()
        }
    }

    // Explicit geometry follows recycled queue rows without a new anchor pass.
    IconButton {
        objectName: "queueRemoveButton"
        visible: row.removable
        width: 40
        height: 40
        x: Math.max(0, row.width - width - 16)
        y: (row.height - height) / 2
        icon: "close"
        type: "standard"
        onClicked: row.removeRequested()
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
