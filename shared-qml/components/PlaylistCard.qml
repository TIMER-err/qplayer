import QtQuick
import miuix.Core
import "."

// Outlined playlist card shared by the home and library grids. Ripple is the
// only pointer handler: a normal tap opens the playlist, while desktop right-
// click and a stationary mobile long-press open the context menu at the press
// position. Keeping one handler also prevents the release after a long-press
// from leaking through and opening the playlist behind the menu.
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
    property real tile: 160
    property bool _menuArmed: false
    onPlaylistIdChanged: {
        card._menuArmed = false
        if (cardMenu && cardMenu.opened) cardMenu.dismissImmediately()
    }
    signal clicked()

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
        color: cardRipple.containsMouse ? Theme.color.surfaceContainerHigh : Theme.color.surfaceContainer

        // A quiet state layer makes the whole tile read as interactive before the
        // press ripple starts, without washing out the cover artwork.
        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: Theme.color.onSurfaceColor
            opacity: cardRipple.containsMouse ? 0.04 : 0
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

    PlaylistContextMenu {
        id: cardMenu
        playlistId: card.playlistId
        onOpenRequested: card.clicked()
        onClosed: card._menuArmed = false
    }
}
