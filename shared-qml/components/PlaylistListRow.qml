import QtQuick
import miuix.Core
import design
import "."

// One playlist row for the library's list view (the compact alternative to
// PlaylistCard's grid tile). Mirrors SongRow's reorder-grip contract exactly
// (reorderPressed/Dragged/Released, reorderGrabOffset/reorderContentY) so
// VirtualPlaylistList can reuse VirtualSongList's drag/auto-scroll math
// unchanged — only the row's own visuals differ.
Rectangle {
    id: row

    property string rowName: ""
    property string rowSubtitle: ""
    property string coverUrl: ""
    property string coverThumbPath: ""
    property bool deletable: false
    property bool subscribed: false
    property var playlistId: 0

    property bool reorderable: false
    // Horizontal content inset: the cover starts here and the overflow glyph
    // ends this far from the right edge. The hover wash extends 4px beyond it,
    // matching SongRow so playlist and song lists read as one family.
    property real contentInset: 12
    readonly property real _washX: Math.max(0, row.contentInset - 4)
    property bool controlsRevealed: true
    signal revealToggled()
    property real reorderContentY: 0
    property real reorderGrabOffset: 0
    property bool dragging: false
    property real reorderShift: 0
    Behavior on reorderShift {
        NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
    }
    signal reorderPressed()
    signal reorderDragged()
    signal reorderReleased()
    signal reorderCanceled()

    signal activated()
    signal deleteRequested()
    signal unsubscribeRequested()

    property bool _menuArmed: false
    onPlaylistIdChanged: {
        row._menuArmed = false
        if (menuLoader.item && menuLoader.item.opened) menuLoader.item.dismissImmediately()
    }

    scale: row.dragging ? 1.03 : 1.0
    Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }

    implicitHeight: 64
    color: "transparent"

    Rectangle {
        id: rowBackground
        x: row._washX
        y: 4
        width: Math.max(0, row.width - 2 * row._washX)
        height: Math.max(0, row.height - 8)
        radius: Ui.radiusMd
        // Hover/press feedback comes from the Ripple's own state layer; this
        // surface only lifts the carried row off the list while dragging.
        color: Ui.surfaceRaised
        visible: row.dragging
        border.width: 2
        border.color: Ui.accent
    }

    Item {
        id: leading
        x: row.contentInset
        y: (row.height - height) / 2
        width: 48
        height: 48

        Rectangle {
            width: leading.width
            height: leading.height
            radius: Ui.radiusSm
            color: Ui.surfaceSunken
            visible: row.coverThumbPath === "" && row.coverUrl === ""
            Glyph {
                x: (parent.width - width) / 2
                y: (parent.height - height) / 2
                name: "queue_music"
                size: Ui.iconSize
                color: Ui.textTertiary
            }
        }

        Image {
            width: leading.width
            height: leading.height
            visible: row.coverThumbPath !== "" || row.coverUrl !== ""
            source: row.coverThumbPath || row.coverUrl
            radius: Ui.radiusSm
            fillMode: Image.PreserveAspectFit
            clip: true
            sourceSize.width: Math.round(48 * player.pixelRatio)
            sourceSize.height: Math.round(48 * player.pixelRatio)
        }
    }

    // The overflow button is always reserved (it is always shown); the grip adds
    // its own width only while reorderable, whether or not it is revealed yet —
    // reserving by the revealed state instead would shift the title every reveal.
    readonly property real _rightReserve: row.contentInset + 44 + (row.reorderable ? 44 : 0)

    Text {
        id: nameText
        x: row.contentInset + leading.width + Ui.space3
        // Fixed offsets, not implicitHeight: the carried row of a drag is realized
        // hidden under cachedLayout, where implicitHeight is still 0 and the two
        // lines would land on top of each other.
        y: Math.round(row.height / 2) - 21
        width: Math.max(0, row.width - x - row._rightReserve)
        text: row.rowName
        elide: Text.ElideRight
        maximumLineCount: 1
        color: Ui.textPrimary
        font.pixelSize: Ui.textBody
        font.weight: Ui.weightRegular
    }

    Text {
        x: nameText.x
        y: Math.round(row.height / 2) + 3
        width: nameText.width
        text: row.rowSubtitle
        elide: Text.ElideRight
        maximumLineCount: 1
        color: Ui.textSecondary
        font.pixelSize: Ui.textCaption
    }

    Ripple {
        id: ripple
        x: rowBackground.x
        y: rowBackground.y
        width: rowBackground.width
        height: rowBackground.height
        clipRadius: rowBackground.radius
        rippleColor: Ui.textPrimary
        longPressEnabled: true
        onClicked: {
            if (row._menuArmed) { row._menuArmed = false; return }
            row.activated()
        }
        onLongPressed: {
            row._menuArmed = true
            if (row.reorderable) row.revealToggled()
            else row._openMenu()
        }
        onContextMenuRequested: {
            row._menuArmed = true
            if (row.reorderable) row.revealToggled()
            else row._openMenu()
        }
    }

    Loader {
        id: menuLoader
        active: true
        sourceComponent: PlaylistContextMenu {
            playlistId: row.playlistId
            deletable: row.deletable
            subscribed: row.subscribed
            onOpenRequested: row.activated()
            onDeleteRequested: row.deleteRequested()
            onUnsubscribeRequested: row.unsubscribeRequested()
            onClosed: row._menuArmed = false
        }
    }
    function _openMenu() {
        if (menuLoader.item === null) return
        menuLoader.item.rebuild()
        menuLoader.item.open(overflowButton, 0, overflowButton.height)
    }

    // Always available, regardless of reorderable/revealed state: long-press is
    // still a shortcut (reveal when reorderable, menu otherwise), but a plain
    // click here is the one way into the menu that never depends on getting a
    // long-press/right-click gesture recognised correctly.
    Item {
        id: overflowButton
        objectName: "playlistListRowOverflowButton"
        width: 40
        height: 40
        // The glyph (22 in a 40 box) ends one pixel inside the content inset.
        x: row.width - row.contentInset - width + 8
        y: (row.height - height) / 2

        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: overflowArea.pressed ? Ui.surfacePressed : Ui.surfaceHover
            visible: overflowArea.containsMouse || overflowArea.pressed
        }
        Glyph {
            x: (parent.width - width) / 2
            y: (parent.height - height) / 2
            name: "more_vert"
            size: Ui.iconSize
            color: overflowArea.pressed ? Ui.accent : Ui.textSecondary
        }

        MouseArea {
            id: overflowArea
            anchors.fill: parent
            hoverEnabled: true
            onClicked: row._openMenu()
        }
    }

    Item {
        id: dragHandle
        objectName: "playlistListRowDragHandle"
        visible: row.reorderable && row.controlsRevealed
        width: 40
        height: 40
        x: overflowButton.x - width - Ui.space1
        y: (row.height - height) / 2

        Glyph {
            x: (parent.width - width) / 2
            y: (parent.height - height) / 2
            name: "drag_handle"
            size: Ui.iconSize
            color: handleArea.pressed ? Ui.accent : Ui.textSecondary
        }

        MouseArea {
            id: handleArea
            x: 0
            y: 0
            width: parent.width
            height: parent.height
            enabled: row.reorderable && row.controlsRevealed
            preventStealing: true
            cursorShape: Qt.SizeVerCursor
            onPressed: (mouse) => {
                row.reorderGrabOffset = dragHandle.y + mouse.y
                row.reorderContentY = row.y + row.reorderGrabOffset
                row.reorderPressed()
            }
            onPositionChanged: (mouse) => {
                if (!handleArea.pressed) return
                row.reorderContentY = row.y + dragHandle.y + mouse.y
                row.reorderDragged()
            }
            onReleased: row.reorderReleased()
            onCanceled: row.reorderCanceled()
        }
    }
}
