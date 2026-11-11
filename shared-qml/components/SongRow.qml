import QtQuick
import miuix.Core
import design
import "."

// One song/track row: 48px cover, title + artist, optional source badge and
// duration, then the trailing controls. Plain explicit geometry — NOT nested
// RowLayout/ColumnLayout and NOT anchors: the Layout measure passes ran for every
// visible row on every dirty frame (playback ticks the scene ~5x/s), and a row
// first realized off-screen under cachedLayout never gets the anchor pass at all.
// `highlighted` marks the playing entry. No per-row timers or layer effects.
//
// VirtualSongList already bounds live rows to the viewport plus its preload
// margin. Row covers follow those reused slots directly, without another set of
// scroll-position bindings and viewport checks on every row.
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
    // Track length; drawn on wide rows only (0 hides it).
    property real durationMs: 0
    property bool showDuration: true
    // Horizontal inset of the row content: the cover starts here and the trailing
    // controls end this far from the right edge. Lists aligned to the page gutter
    // pass Ui.gutter(width); the default keeps the historical 12px.
    property real contentInset: 12
    // Small per-row source badge (provider name / local) for lists that
    // mix rows from more than one source — see SearchPage.qml. Empty (default)
    // shows nothing, so every other VirtualSongList caller is unaffected.
    property string tag: ""
    property bool highlighted: false
    property bool removable: false
    property bool selectMode: false
    property bool selected: false
    signal selectionToggled()
    // Shows a drag handle that starts a reorder. Only the handle does — the rest
    // of the row keeps its tap and long-press behaviour.
    property bool reorderable: false
    // Gates the drag handle / remove button while reorderable: sitting right next
    // to each other at the row's edge, they were an easy misclick during normal
    // scrolling. While reorderable, they only show once revealed (see
    // revealToggled below); every other list keeps them always on.
    property bool controlsRevealed: true
    signal revealToggled()
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
    signal reorderCanceled()
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
    // One SongContextMenu owned by the containing list. Rows given one build no
    // menu of their own: a Menu per row was a third of every row's scene nodes,
    // and every opened playlist rebuilt them all.
    property var sharedMenu: null
    readonly property var _menu: row.sharedMenu !== null ? row.sharedMenu : menuLoader.item
    // Desktop pointer users get a visible "more" button for the same menu (it
    // fades in on hover); touch keeps long-press. Reorderable rows need it most:
    // there a right-click reveals the drag grip instead of the menu.
    property bool showMoreButton: settings.has("desktopLyricEnabled")
    // Latched when a long-press fired, so the release's click doesn't also play the
    // song; cleared when the menu closes (or on the guarded click).
    property bool _menuArmed: false
    // Small "cached, plays offline" badge on the cover's corner. Only meaningful
    // (and only ever true) while the containing list is actually in an offline
    // state — see VirtualSongList.showOfflineBadge — so it stays invisible during
    // normal online browsing instead of cluttering every row with a checkmark.
    property bool offlineReady: false
    // Per-row favorite button (search results — a row here need not be playing,
    // or even played yet, unlike the mini player's own heart which only ever
    // reflects the current track). Off by default so every other caller is
    // unaffected; VirtualSongList wires both from PlayerController per row.
    property bool showLikeButton: false
    property bool likeable: true
    property bool liked: false
    signal likeToggled()
    property bool showDownloadButton: false
    signal downloadRequested()
    signal menuDownloadRequested()
    onSongChanged: {
        row._menuArmed = false
        if (row._menuOpen) row._menu.dismissImmediately()
    }
    signal activated()
    signal removeRequested()

    implicitHeight: 64
    color: "transparent"

    // ---- Trailing slot geometry -------------------------------------------------
    // Right to left: remove, then the drag grip or the "more" button (a revealed
    // grip takes over the more button's slot), download, like, duration, badge.
    readonly property real _wash: Math.max(0, row.contentInset - 4)
    readonly property bool _removeShown: row.removable && (!row.reorderable || row.controlsRevealed)
    readonly property bool _gripShown: row.reorderable && row.controlsRevealed
    readonly property bool _moreShown: row.showMoreButton && row.menuEnabled && row.song !== null
                                       && !row.selectMode && !row._gripShown && !row.dragging
    readonly property real _rightEdge: row.width - row.contentInset
    readonly property real _removeX: row._rightEdge - 40
    // Removable rows keep the remove slot reserved even while reveal-gated, so
    // revealing the controls never shifts the duration column.
    readonly property real _slot2Right: row.removable ? row._removeX : row._rightEdge
    readonly property real _slot2X: row._slot2Right - 44
    // The more slot stays reserved while select mode hides the button, so the
    // duration column does not jump when the mode toggles.
    readonly property real _downloadRight: (row._gripShown || row._moreShown
                                            || (row.showMoreButton && row.song !== null && !row.dragging))
                                           ? row._slot2X : row._slot2Right
    readonly property bool _downloadShown: row.showDownloadButton && !row.selectMode
    readonly property real _downloadX: row._downloadRight - 36
    readonly property real _likeRight: row._downloadShown ? row._downloadX : row._downloadRight
    readonly property real _likeX: row._likeRight - 36
    readonly property real _controlsLeft: row.showLikeButton ? row._likeX : row._likeRight
    readonly property bool _durationShown: row.showDuration && row.durationMs > 0
                                           && row.width >= Ui.compactMax
    readonly property real _durationRight: row._controlsLeft - (row._controlsLeft < row._rightEdge ? 8 : 0)
    readonly property real _textX: row.contentInset + 48 + 14
    readonly property bool _emphasized: row.selectMode ? row.selected : row.highlighted

    // Inset rounded wash: playing, selected and carried rows. Hover/press is the
    // Ripple's own state layer. Explicit geometry, not anchors.fill + margins: a
    // row first realized off-screen never gets the layout pass cachedLayout skips,
    // and an anchored background stays 0x0 there. The carried row of a drag is
    // realized hidden and needs this to be the opaque thing that lifts it off the
    // list — without it the row underneath reads straight through it.
    Rectangle {
        id: rowBackground
        objectName: "songRowBackground"
        x: row._wash
        y: 4
        width: Math.max(0, row.width - 2 * row._wash)
        height: Math.max(0, row.height - 8)
        radius: Ui.radiusMd
        // While carried the row needs to read as detached from the list: an opaque
        // raised fill plus an accent outline (no shadows on rows).
        color: row.dragging ? Ui.surfaceRaised : Ui.accentSoft
        // In select mode the selection is the only emphasis; otherwise the
        // playing row is.
        opacity: (row.dragging || row._emphasized) ? 1 : 0
        border.width: row.dragging ? 2 : 0
        border.color: Ui.accent
        Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
    }

    // Cover sits 4px inside the wash; radius 10 inside the wash's 14 keeps the
    // corners concentric.
    Item {
        id: leading
        objectName: "songRowLeading"
        readonly property real cornerRadius: Ui.radiusSm
        x: row.contentInset
        y: (row.height - height) / 2
        width: 48
        height: 48

        // Placeholder background (shown when no cover). Explicit size, not
        // anchors.fill: the carried row of a drag is realized hidden under
        // cachedLayout, and unresolved anchors leave this box 0×0 so the
        // decoded cover paints from the top-left at source aspect — every
        // artwork overflows by a different amount. See CoverImage.qml.
        Rectangle {
            objectName: "songRowCoverPlaceholder"
            width: leading.width
            height: leading.height
            radius: leading.cornerRadius
            color: Ui.surfaceSunken
            // Always drawn: it is the loading state until the art decodes on top
            // (decoding only advances while painted, so a reused row can sit
            // undecoded for a few frames). The glyph is only for rows without
            // art: under a cover it would be one more text draw per visible row
            // on every scrolled frame.
            // Sized to the box via reactive width/height bindings — NOT anchors,
            // which run in the layout pass that cachedLayout skips for a row first
            // realized off-screen. With a real box size the glyph self-centres at
            // paint time (AlignHCenter + the icon font's vertical metrics).
            Glyph {
                width: parent.width
                height: parent.height
                size: 22
                name: "music_note"
                visible: !row.highlighted && row.coverThumbPath === ""
                color: Ui.textTertiary
            }
        }

        // The virtual window is the preload bound. A reused slot changes directly
        // from one cover path to the next, never through an empty scroll gate.
        Image {
            id: coverImg
            objectName: "songRowCover"
            width: leading.width
            height: leading.height
            clip: true
            visible: coverImg.source !== ""
            source: row.coverThumbPath
            radius: leading.cornerRadius
            // PreserveAspectCrop is broken on non-square sources in this engine:
            // it scales from the top-left instead of cropping centred, so every
            // cover overflows its 48×48 box by a different amount — most visible
            // on the floating row of a drag, which has no neighbour to clip it.
            // PreserveAspectFit + clip never overflows.
            fillMode: "PreserveAspectFit"
            // Decode-time downscale (mipmap-quality) scaled by the device pixel
            // ratio, see CoverImage.qml. Fixed size, so no reuse-staleness risk
            // from VirtualSongList's windowing.
            sourceSize.width: Math.round(48 * player.pixelRatio)
            sourceSize.height: Math.round(48 * player.pixelRatio)
            // Fade the art in as it loads. Keyed on source presence (not load
            // status, which would deadlock — opacity 0 skips the paint that
            // advances the decode). A reused row whose source swaps path→path
            // keeps opacity 1, so scrolling doesn't re-fade every cover.
            opacity: coverImg.source !== "" ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        }

        // Now-playing veil: the page surface at 60% over the art with an accent
        // equalizer glyph, legible in both themes without a new colour.
        Rectangle {
            objectName: "songRowNowPlaying"
            visible: row.highlighted && !row.selectMode
            width: leading.width
            height: leading.height
            radius: leading.cornerRadius
            color: row.coverThumbPath == "" ? "transparent" : Ui.surfaceAlpha(0.6)
            Glyph {
                width: parent.width
                height: parent.height
                size: 24
                name: "equalizer"
                color: Ui.accent
            }
        }

        // "Cached, plays offline" badge — bottom-right corner of the cover.
        Rectangle {
            visible: row.offlineReady && !row.selectMode
            width: 16
            height: 16
            radius: 8
            x: parent.width - width + 2
            y: parent.height - height + 2
            color: Ui.accent
            border.width: 1.5
            border.color: Ui.background
            Glyph {
                width: parent.width
                height: parent.height
                size: 11
                name: "check"
                color: Ui.onAccent
            }
        }

        Rectangle {
            objectName: "songRowSelectMark"
            visible: row.selectMode
            width: 20
            height: 20
            radius: 10
            x: parent.width - width + 2
            y: parent.height - height + 2
            color: row.selected ? Ui.accent : Ui.surfaceAlpha(0.7)
            border.width: 1.5
            border.color: row.selected ? Ui.background : Ui.textSecondary
            Glyph {
                width: parent.width
                height: parent.height
                size: 13
                name: row.selected ? "check" : ""
                color: Ui.onAccent
            }
        }
    }

    // Everything between the text column and the trailing controls: the source
    // badge sits left of the duration, both right-aligned against the controls.
    readonly property real _badgeRight: row._durationShown
                                        ? durationText.x - 12 : row._durationRight - 4
    readonly property real _textRight: tagPill.visible ? tagPill.x - 12
                                       : (row._durationShown ? durationText.x - 16 : row._durationRight - 8)

    Text {
        id: titleText
        objectName: "songRowTitle"
        x: row._textX
        // Fixed slots, not `height/2 - height`. The reorder floating copy is
        // born visible: false under cachedLayout, so it never gets the measure
        // that would give `height` a real line; subtracting 0 then parks the
        // title on the artist line for the whole drag.
        y: row.height / 2 - 21
        width: Math.max(0, row._textRight - x)
        text: row.rowTitle
        elide: Text.ElideRight
        maximumLineCount: 1
        color: row._emphasized ? Ui.accent : Ui.textPrimary
        font.pixelSize: Ui.textBody
        font.weight: row._emphasized ? Ui.weightBold : Ui.weightRegular
    }

    Text {
        id: artistText
        objectName: "songRowArtist"
        x: titleText.x
        y: row.height / 2 + 3
        width: titleText.width
        text: row.rowArtist
        elide: Text.ElideRight
        maximumLineCount: 1
        color: ((row.rowArtistIdsCsv !== "" || row.rowArtistId !== 0)
                && artistArea.containsMouse)
               ? Ui.accent : Ui.textSecondary
        font.pixelSize: Ui.textCaption
    }

    // Unconstrained shaping probe for the real rendered glyph width. The visible
    // Text spans the column so it can elide, therefore its own width is the
    // available column width rather than the artist text's painted width. Only
    // the desktop artist link needs it; touch rows skip the extra shaping that
    // every recycled row would otherwise pay.
    Text {
        id: artistProbe
        visible: false
        text: artistArea.enabled ? row.rowArtist : ""
        font.pixelSize: artistText.font.pixelSize
    }

    Text {
        id: durationText
        objectName: "songRowDuration"
        visible: row._durationShown
        // Fixed box, right-aligned: the reorder floating row is realized hidden
        // under cachedLayout and never measures, so implicit sizes read 0 there.
        width: 48
        x: row._durationRight - width
        y: row.height / 2 - 9
        horizontalAlignment: Text.AlignRight
        text: Ui.formatTime(row.durationMs)
        color: Ui.textTertiary
        font.pixelSize: Ui.textCaption
    }

    // Keep the source badge inside the row and leave at least 100dp for the
    // title when a badge fits. Reactive geometry also follows recycled tags
    // under cachedLayout, without waiting for a fresh anchor-layout pass.
    Rectangle {
        id: tagPill
        objectName: "songSourceBadge"
        visible: row.tag !== "" && !row.removable && width >= 32
        x: Math.max(0, row._badgeRight - width)
        y: (row.height - height) / 2
        radius: height / 2
        color: Ui.surfaceSunken
        border.width: 1
        border.color: Ui.hairline
        width: Math.min(Math.max(36, row.tag.length * 12 + 16), 112,
                        Math.max(0, row._badgeRight - row._textX - 100))
        height: 20

        Text {
            id: tagText
            objectName: "songSourceBadgeLabel"
            x: 8
            // Center the measured line explicitly: qml4j accepts
            // verticalAlignment but it does not affect painting.
            y: (parent.height - implicitHeight) / 2
            width: Math.max(0, parent.width - 16)
            horizontalAlignment: Text.AlignHCenter
            text: row.tag
            elide: Text.ElideRight
            maximumLineCount: 1
            font.pixelSize: Ui.textMicro
            color: Ui.textSecondary
        }
    }

    // Tap + flat indication, clipped to the inset wash shape. Its hover layer is
    // the row's hover wash. Reactive geometry, NOT anchors: under cachedLayout
    // (long lists) the measure pass that resolves anchors is skipped once the
    // container box is stable, so an anchor-sized ripple stays stuck at the row's
    // first (often zero) width.
    Ripple {
        id: ripple
        x: row._wash
        y: 4
        width: Math.max(0, row.width - 2 * row._wash)
        height: Math.max(0, row.height - 8)
        clipRadius: rowBackground.radius
        rippleColor: Ui.textPrimary
        longPressEnabled: row.reorderable || (row.menuEnabled && row.song !== null)
        onClicked: {
            if (row._menuArmed) { row._menuArmed = false; return }
            if (row.selectMode) row.selectionToggled()
            else row.activated()
        }
        onLongPressed: {
            if (row.selectMode) { row.selectionToggled(); return }
            row._menuArmed = true
            // While reorderable, a long-press/right-click reveals the drag handle
            // and remove button instead of the context menu — see controlsRevealed.
            if (row.reorderable) row.revealToggled()
            else row._openMenu(ripple, ripple.pressX, ripple.pressY)
        }
        onContextMenuRequested: {
            if (row.selectMode) { row.selectionToggled(); return }
            row._menuArmed = true
            if (row.reorderable) row.revealToggled()
            else row._openMenu(ripple, ripple.pressX, ripple.pressY)
        }
    }

    // Tap only the actually-painted artist text. The separate probe measures the
    // unelided glyph run; min() clips the target to the visible/elided width.
    // Desktop only: on a phone the artist line fills much of a 64px row and a tap
    // meant to play the song kept opening the artist page. Touch users reach the
    // artist through the long-press menu ("查看歌手") instead.
    MouseArea {
        id: artistArea
        x: artistText.x
        y: artistText.y
        width: Math.min(artistText.width, artistProbe.implicitWidth)
        height: 18
        enabled: settings.has("desktopLyricEnabled") && !row.selectMode
                 && (row.rowArtistIdsCsv !== "" || (row.rowArtistId !== 0 && row.rowArtistId !== ""))
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

    // Row-owned context menu, only for rows outside a list that shares one
    // (sharedMenu). Loaded eagerly for those rows: qml4j's Loader is async, and a
    // menu created after the row exists also disturbs the row's hit-testing, so
    // it must be ready from the start. The model stays empty until rebuild().
    Loader {
        id: menuLoader
        active: row.menuEnabled && row.sharedMenu === null
        sourceComponent: SongContextMenu {
            song: row.song
            inOwnedPlaylist: row.inOwnedPlaylist
            inCacheList: row.inCacheList
            onClosed: row._menuArmed = false
            onDownloadChosen: row.menuDownloadRequested()
        }
    }
    // A shared menu counts as this row's only while this row opened it.
    readonly property bool _menuOpen: row._menu !== null && row._menu.opened
                                      && (row.sharedMenu === null || row.sharedMenu.menuRow === row)
    function _openMenu(anchor, ax, ay) {
        if (row.song === null || row._menu === null) return
        if (row.sharedMenu !== null) {
            var m = row.sharedMenu
            if (m.opened) m.dismissImmediately()
            m.song = row.song
            m.inOwnedPlaylist = row.inOwnedPlaylist
            m.inCacheList = row.inCacheList
            m.menuRow = row
        }
        row._menu.rebuild()
        row._menu.open(anchor, ax, ay)
    }

    // Reorder grip. Explicit geometry, like every other child here: anchors do
    // not survive the skipped measure pass under cachedLayout.
    Item {
        id: dragHandle
        objectName: "songRowDragHandle"
        visible: row._gripShown
        width: 44
        height: 44
        x: Math.max(0, row._slot2X)
        y: (row.height - height) / 2

        // Not an Icon: that centres its glyph with anchors.centerIn, which the
        // skipped measure pass drops. Same self-centring pattern as the
        // placeholder glyph above — size the Text to the box and let the icon
        // font's own vertical metrics do the rest.
        Glyph {
            objectName: "songRowDragGlyph"
            width: parent.width
            height: parent.height
            size: 22
            name: "drag_handle"
            color: handleArea.pressed ? Ui.accent : Ui.textSecondary
        }

        MouseArea {
            id: handleArea
            x: 0
            y: 0
            width: parent.width
            height: parent.height
            enabled: row._gripShown
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
            onCanceled: row.reorderCanceled()
        }
    }

    // Hover-revealed menu button (desktop). Always hittable while shown so the
    // pointer never chases a button that fades out from under it.
    IconButton {
        id: moreButton
        objectName: "songRowMoreButton"
        visible: row._moreShown
        width: 40
        height: 40
        x: Math.max(0, row._slot2X + 2)
        y: (row.height - height) / 2
        type: "standard"
        icon: "more_horiz"
        contentColor: Ui.textSecondary
        opacity: (ripple.containsMouse || moreButton.hovered || row._menuOpen) ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
        onClicked: row._openMenu(moreButton, 0, moreButton.height)
    }

    // Explicit geometry follows recycled queue rows without a new anchor pass.
    // Stays visible: true whenever removable (never toggled by reveal) so the
    // NORMAL in-list delegate gets measured from creation. VirtualSongList's
    // reorderFloatingRow is always born visible:false and never gets a measure
    // pass later, so an IconButton's anchors.centerIn'd glyph inside it would
    // render pinned to the box's top-left. A self-sized glyph sidesteps that.
    Item {
        id: removeButton
        objectName: "queueRemoveButton"
        visible: row.removable
        opacity: row._removeShown ? 1 : 0
        enabled: row._removeShown
        width: 40
        height: 40
        x: Math.max(0, row._removeX)
        y: (row.height - height) / 2

        Rectangle {
            width: parent.width
            height: parent.height
            radius: width / 2
            color: Ui.surfaceHover
            opacity: removeArea.containsMouse ? 1 : 0
        }
        Glyph {
            width: parent.width
            height: parent.height
            size: 22
            name: "close"
            color: removeArea.pressed ? Ui.accent : Ui.textSecondary
        }

        MouseArea {
            id: removeArea
            x: 0
            y: 0
            width: parent.width
            height: parent.height
            enabled: removeButton.enabled
            hoverEnabled: enabled
            onClicked: row.removeRequested()
        }
    }

    // Always on when shown — not reveal-gated like the drag grip/remove button,
    // since a search row is never reorderable/removable to begin with.
    IconButton {
        objectName: "songRowLikeButton"
        visible: row.showLikeButton
        enabled: row.likeable
        width: 36
        height: 36
        x: Math.max(0, row._likeX)
        y: (row.height - height) / 2
        type: "standard"
        icon: row.liked ? "favorite" : "favorite_border"
        contentColor: row.liked ? Ui.like : Ui.textSecondary
        onClicked: row.likeToggled()
    }

    IconButton {
        objectName: "songRowDownloadButton"
        visible: row._downloadShown
        width: 36
        height: 36
        x: Math.max(0, row._downloadX)
        y: (row.height - height) / 2
        type: "standard"
        icon: "download"
        contentColor: Ui.textSecondary
        onClicked: row.downloadRequested()
    }
}
