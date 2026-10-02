import QtQuick
import miuix.Core
import "."

// Outlined playlist card shared by the home and library grids. Two pointer
// handlers, mutually exclusive on `reorderable`: the plain Ripple (unchanged,
// used everywhere reorderable is off — home, recommendations, artist pages)
// opens the context menu on a long-press/right-click; the drag-capable one
// (library grids only) uses that same gesture to pick the card up instead,
// since the overflow button below is the guaranteed, unambiguous way into the
// menu once dragging is a possibility on the card body itself.
Item {
    id: card

    property var playlistId: 0
    property string name: ""
    property int count: 0
    // Fallback subtitle for sources that only publish a play count on a card
    // (NetEase's home blocks, QQ's anonymous recommendations).
    property real playCount: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    // Set only where one grid mixes several sources (the library), so a card says which
    // account it came from.
    property string sourceName: ""
    // Card-menu eligibility, straight from the playlist DTO. The two are mutually
    // exclusive in practice: you can delete a playlist you own, and un-collect one
    // you merely follow.
    property bool deletable: false
    property bool subscribed: false
    // Library-grid opt-in for the always-there overflow button below. Off by
    // default so a plain recommendation card (home, artist page — never
    // reorderable, never at risk of the long-press ambiguity) stays exactly as
    // it was rather than growing a button nobody there asked for.
    property bool showOverflow: false
    property real tile: 160
    property bool _menuArmed: false
    onPlaylistIdChanged: {
        card._menuArmed = false
        if (cardMenu && cardMenu.opened) cardMenu.dismissImmediately()
    }
    signal clicked()
    signal deleteRequested()
    signal unsubscribeRequested()

    // Drag-to-reorder (library grids only). Same contract shape as
    // PlaylistListRow's, but 2D: the grid needs both axes to convert a pointer
    // position into a row/column drop target.
    property bool reorderable: false
    property real reorderContentX: 0
    property real reorderContentY: 0
    property real reorderGrabOffsetX: 0
    property real reorderGrabOffsetY: 0
    /** True for the card being carried: drawn once by the grid's own floating
     *  copy, so this instance (still sitting in the Repeater) just visually
     *  lifts in place until the model catches up on release. */
    property bool dragging: false
    signal reorderPressed()
    signal reorderDragged()
    signal reorderReleased()

    scale: card.dragging ? 1.05 : 1.0
    Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }

    implicitWidth: tile
    implicitHeight: tile + 72

    readonly property string subtitle: {
        if (count > 0) return i18n.t("common.songCount", count)
        if (playCount >= 100000000)
            return i18n.t("common.playCountYi", (playCount / 100000000).toFixed(1))
        if (playCount >= 10000)
            return i18n.t("common.playCountWan", Math.round(playCount / 10000))
        if (playCount > 0) return i18n.t("common.playCount", playCount)
        return i18n.t("common.songCountEmpty")
    }

    SmoothRectangle {
        id: container
        x: 0
        y: 0
        width: card.width
        height: card.height
        radius: 20
        color: card.dragging ? Theme.color.surfaceContainerHighest
             : (cardRipple.containsMouse ? Theme.color.surfaceContainerHigh : Theme.color.surfaceContainer)
        borderWidth: card.dragging ? 2 : 0
        borderColor: Theme.color.primary

        // A quiet state layer makes the whole tile read as interactive before the
        // press ripple starts, without washing out the cover artwork.
        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: Theme.color.onSurfaceColor
            opacity: card.dragging ? 0 : (cardRipple.containsMouse ? 0.04 : 0)
            Behavior on opacity {
                NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
            }
        }
    }

    CoverImage {
        id: cover
        x: 8
        y: 8
        width: card.width - 16
        height: card.width - 16
        radius: 16
        icon: "queue_music"
        iconSize: 44
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }

    // Fixed slot height (independent of name length) so every card in a row
    // keeps the count label at the same y and the grid never reflows around a
    // long neighbour; an overflowing name marquee-scrolls instead of eliding.
    MarqueeText {
        id: nameLabel
        x: 12
        y: card.width - 2
        width: card.width - 24
        height: 36
        text: card.name
        textColor: Theme.color.onSurfaceColor
        fontSize: 16
        fontWeight: Font.Medium
    }

    // Count is kept in a fixed row (including the zero case) so cards do not
    // reflow when an asynchronously refreshed playlist gains its first song.
    Text {
        x: 12
        y: card.width + 35
        width: card.width - 24
        height: 20
        verticalAlignment: Text.AlignVCenter
        text: card.subtitle + (card.sourceName ? " · " + card.sourceName : "")
        color: Theme.color.onSurfaceVariantColor
        fontSize: 12
        elide: Text.ElideRight
    }

    Ripple {
        id: cardRipple
        x: 0
        y: 0
        width: card.width
        height: card.height
        visible: !card.reorderable
        enabled: !card.reorderable
        clipRadius: 20
        rippleColor: Theme.color.onSurfaceColor
        longPressEnabled: true
        onClicked: {
            if (card._menuArmed) {
                card._menuArmed = false
                return
            }
            card.clicked()
        }
        onLongPressed: {
            card._menuArmed = true
            cardMenu.rebuild()
            cardMenu.open(cardRipple, cardRipple.pressX, cardRipple.pressY)
        }
    }

    // Reorderable cards get a plain MouseArea instead: a long-press (real hold,
    // not right-click — a reflex right-click starting a drag would be a nasty
    // surprise) picks the card up rather than opening the menu. No ripple while
    // this is active; the lift itself (see the container's dragging state) is
    // the feedback.
    MouseArea {
        id: dragArea
        objectName: "playlistCardDragArea"
        x: 0
        y: 0
        width: card.width
        height: card.height
        visible: card.reorderable
        enabled: card.reorderable
        preventStealing: true
        property real _downX: 0
        property real _downY: 0
        property bool _longFired: false

        Timer {
            id: holdTimer
            interval: 480
            onTriggered: {
                dragArea._longFired = true
                card.reorderGrabOffsetX = dragArea._downX
                card.reorderGrabOffsetY = dragArea._downY
                card.reorderContentX = card.x + card.reorderGrabOffsetX
                card.reorderContentY = card.y + card.reorderGrabOffsetY
                card.reorderPressed()
            }
        }
        onPressed: (mouse) => {
            dragArea._downX = mouse.x
            dragArea._downY = mouse.y
            dragArea._longFired = false
            holdTimer.restart()
        }
        onPositionChanged: (mouse) => {
            if (dragArea._longFired) {
                card.reorderContentX = card.x + mouse.x
                card.reorderContentY = card.y + mouse.y
                card.reorderDragged()
                return
            }
            if (holdTimer.running) {
                var dx = mouse.x - dragArea._downX
                var dy = mouse.y - dragArea._downY
                if (dx * dx + dy * dy > 100) holdTimer.stop()
            }
        }
        onReleased: {
            holdTimer.stop()
            if (dragArea._longFired) {
                card.reorderReleased()
                dragArea._longFired = false
                return
            }
            card.clicked()
        }
        onCanceled: {
            holdTimer.stop()
            if (dragArea._longFired) {
                card.reorderReleased()
                dragArea._longFired = false
            }
        }
    }

    // Always available regardless of reorderable/dragging state — same reasoning
    // as PlaylistListRow's own overflow button: a guaranteed, unambiguous way
    // into the menu that never depends on a long-press gesture landing right.
    Item {
        id: overflowButton
        objectName: "playlistCardOverflowButton"
        width: 32
        height: 32
        x: card.width - width - 10
        y: 10
        z: 3
        visible: card.showOverflow && !card.dragging

        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: Theme.color.scrim
            opacity: 0.45
        }
        Text {
            width: parent.width
            height: parent.height
            horizontalAlignment: Text.AlignHCenter
            text: "more_vert"
            font.family: Theme.iconFont.name
            font.pixelSize: 18
            color: "white"
        }
        MouseArea {
            anchors.fill: parent
            onClicked: {
                cardMenu.rebuild()
                cardMenu.open(overflowButton, 0, overflowButton.height)
            }
        }
    }

    PlaylistContextMenu {
        id: cardMenu
        playlistId: card.playlistId
        deletable: card.deletable
        subscribed: card.subscribed
        onOpenRequested: card.clicked()
        onDeleteRequested: card.deleteRequested()
        onUnsubscribeRequested: card.unsubscribeRequested()
        onClosed: card._menuArmed = false
    }
}
