import QtQuick
import miuix.Core
import design
import "."

// Album tile for the artist page and album search grids. Same visual language
// and geometry as PlaylistCard (flush cover, two-line name, caption, framing
// hover wash); no context menu -- albums don't have one.
Item {
    id: card

    property var albumId: 0
    property string name: ""
    property int count: 0
    // Optional caption (the album artist in search results); the track count
    // is shown when it is empty.
    property string subtitle: ""
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 130
    signal clicked()
    scale: cardRipple.pressed ? 0.97 : 1.0
    Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }

    // Same scale, caption slot and line budget as PlaylistCard, so every grid
    // of the app shares one row height formula: tile + textSlot.
    readonly property real _scale: tile / 200
    readonly property int titleSize: Math.max(12, Math.min(20, Math.round(15 * _scale)))
    readonly property int subtitleSize: Math.max(10, Math.min(14, Math.round(12 * _scale)))
    readonly property real textSlot: Math.max(52, Math.round(72 * _scale))
    readonly property int textGap: Math.max(6, Math.round(8 * _scale))
    // Line budget and per-name line count work exactly like PlaylistCard's
    // (probe-measured line boxes, character-estimated name width) so a card
    // recycled inside a cachedLayout grid never keeps a stale caption position.
    readonly property real titleLineH: titleProbe.implicitHeight
    readonly property real subtitleLineH: subtitleLabel.implicitHeight
    readonly property int titleLines: textGap + 2 * titleLineH + 2 + subtitleLineH <= textSlot ? 2 : 1
    readonly property int nameLines: titleLines > 1 && estimateWidth(name, titleSize) > width ? 2 : 1
    function estimateWidth(s, px) {
        var w = 0
        for (var i = 0; i < s.length; i++) {
            var c = s.charCodeAt(i)
            if (c >= 0x2E80) w += px
            else if (c === 32) w += px * 0.3
            else if (c >= 65 && c <= 90) w += px * 0.68
            else w += px * 0.56
        }
        return w
    }

    width: tile
    height: tile + textSlot
    implicitWidth: tile
    implicitHeight: tile + textSlot

    Rectangle {
        x: -6
        y: -6
        width: card.width + 12
        height: card.height + 10
        radius: Ui.radiusLg
        color: Ui.surfaceHover
        opacity: cardRipple.containsMouse ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
    }

    CoverImage {
        id: cover
        x: 0
        y: 0
        width: card.width
        height: card.width
        radius: Ui.radiusMd
        icon: "album"
        iconSize: Math.max(28, Math.round(44 * card._scale))
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }

    Text {
        id: titleProbe
        visible: false
        text: "Ag"
        font.pixelSize: card.titleSize
    }
    Text {
        id: nameLabel
        x: 0
        y: card.width + card.textGap
        width: card.width
        text: card.name
        color: Ui.textPrimary
        wrapMode: Text.Wrap
        maximumLineCount: card.nameLines
        elide: Text.ElideRight
        font.pixelSize: card.titleSize
        font.weight: Ui.weightRegular
    }

    Text {
        id: subtitleLabel
        x: 0
        y: nameLabel.y + card.nameLines * card.titleLineH + 2
        width: card.width
        text: card.subtitle.length > 0 ? card.subtitle
              : (card.count > 0 ? i18n.t("common.songCountShort", card.count) : "")
        color: Ui.textSecondary
        maximumLineCount: 1
        elide: Text.ElideRight
        font.pixelSize: card.subtitleSize
    }

    Ripple {
        id: cardRipple
        x: 0
        y: 0
        width: card.width
        height: card.height
        clipRadius: Ui.radiusMd
        rippleColor: Ui.textPrimary
        onClicked: card.clicked()
    }
}
