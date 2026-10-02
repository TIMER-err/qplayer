import QtQuick
import miuix.Core
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
        x: 8
        y: 4
        width: Math.max(0, row.width - 16)
        height: Math.max(0, row.height - 8)
        radius: 16
        color: row.dragging ? Theme.color.surfaceContainerHighest : Theme.color.surfaceContainerHigh
        opacity: row.dragging ? 1 : (ripple.containsMouse ? 1 : 0)
        border.width: row.dragging ? 2 : 0
        border.color: Theme.color.primary
        Behavior on opacity { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
    }

    Item {
        id: leading
        readonly property real cornerRadius: Math.max(0, rowBackground.radius - 4)
        x: 12
        y: (row.height - height) / 2
        width: 48
        height: 48

        Rectangle {
            anchors.fill: parent
            radius: leading.cornerRadius
            color: Theme.color.surfaceContainerHighest
            visible: row.coverThumbPath === "" && row.coverUrl === ""
            Text {
                width: parent.width
                height: parent.height
                horizontalAlignment: Text.AlignHCenter
                text: "queue_music"
                font.family: Theme.iconFont.name
                font.pixelSize: 22
                color: Theme.color.onSurfaceVariantColor
            }
        }

        Image {
            anchors.fill: parent
            visible: row.coverThumbPath !== "" || row.coverUrl !== ""
            source: row.coverThumbPath || row.coverUrl
            radius: leading.cornerRadius
            fillMode: Image.PreserveAspectCrop
            sourceSize.width: Math.round(48 * player.pixelRatio)
            sourceSize.height: Math.round(48 * player.pixelRatio)
        }
    }

    // The overflow button is always reserved (it is always shown); the grip adds
    // its own width only while reorderable, whether or not it is revealed yet —
    // reserving by the revealed state instead would shift the title every reveal.
    readonly property real _rightReserve: 60 + (row.reorderable ? 48 : 0)

    Text {
        id: nameText
        x: 74
        y: row.height / 2 - 20
        width: Math.max(0, row.width - x - row._rightReserve)
        text: row.rowName
        elide: Text.ElideRight
        color: Theme.color.onSurfaceColor
        fontSize: 16
    }

    Text {
        x: nameText.x
        y: row.height / 2 + 4
        width: nameText.width
        text: row.rowSubtitle
        elide: Text.ElideRight
        color: Theme.color.onSurfaceVariantColor
        fontSize: 13
    }

    Ripple {
        id: ripple
        x: 8
        y: 4
        width: row.width - 16
        height: row.height - 8
        clipRadius: rowBackground.radius
        rippleColor: Theme.color.onSurfaceColor
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
        width: 44
        height: 44
        x: row.width - width - 16
        y: (row.height - height) / 2

        Text {
            width: parent.width
            height: parent.height
            horizontalAlignment: Text.AlignHCenter
            text: "more_vert"
            font.family: Theme.iconFont.name
            font.pixelSize: 22
            color: overflowArea.pressed ? Theme.color.primary : Theme.color.onSurfaceVariantColor
        }

        MouseArea {
            id: overflowArea
            anchors.fill: parent
            onClicked: row._openMenu()
        }
    }

    Item {
        id: dragHandle
        objectName: "playlistListRowDragHandle"
        visible: row.reorderable && row.controlsRevealed
        width: 44
        height: 44
        x: row.width - width - overflowButton.width - 16 - 4
        y: (row.height - height) / 2

        Text {
            width: parent.width
            height: parent.height
            horizontalAlignment: Text.AlignHCenter
            text: "drag_handle"
            font.family: Theme.iconFont.name
            font.pixelSize: 22
            color: handleArea.pressed ? Theme.color.primary : Theme.color.onSurfaceVariantColor
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
            onCanceled: row.reorderReleased()
        }
    }
}
