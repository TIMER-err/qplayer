import QtQuick
import miuix.Core
import "."

// Compact artist tile for SearchPage's artist-mode grid. Same card shape as
// AlbumCard/PlaylistCard (playlist-cover style), just a "person" placeholder
// and a song-count line instead of a track count.
Item {
    id: card

    property var artistId: 0
    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 130
    signal clicked()

    implicitWidth: tile
    implicitHeight: tile + 56

    SmoothRectangle {
        x: 0; y: 0
        width: card.width; height: card.height
        radius: 20
        color: cardRipple.containsMouse ? Theme.color.surfaceContainerHigh : Theme.color.surfaceContainer
    }

    CoverImage {
        id: cover
        x: 6; y: 6
        width: card.width - 12
        height: card.width - 12
        radius: width / 2
        icon: "person"
        iconSize: 32
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }

    MarqueeText {
        x: 8
        y: card.width - 2
        width: card.width - 16
        height: 32
        text: card.name
        textColor: Theme.color.onSurfaceColor
        fontSize: 15
        fontWeight: Font.Medium
    }

    Text {
        x: 8
        y: card.width + 30
        width: card.width - 16
        height: 18
        verticalAlignment: Text.AlignVCenter
        text: card.count > 0 ? i18n.t("common.songCount", card.count) : ""
        color: Theme.color.onSurfaceVariantColor
        fontSize: 12
        elide: Text.ElideRight
    }

    Ripple {
        id: cardRipple
        x: 0; y: 0
        width: card.width; height: card.height
        clipRadius: 20
        rippleColor: Theme.color.onSurfaceColor
        onClicked: card.clicked()
    }
}
