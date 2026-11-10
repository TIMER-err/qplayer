import QtQuick
import miuix.Core
import design
import "."

// Artist tile for the artist search grid: a round avatar with the name and
// song count centred underneath. Shares PlaylistCard's geometry (tile +
// textSlot) and hover wash so mixed grids line up.
Item {
    id: card

    property var artistId: 0
    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 130
    signal clicked()
    scale: cardRipple.pressed ? 0.97 : 1.0
    Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }

    readonly property real _scale: tile / 200
    readonly property int titleSize: Math.max(12, Math.min(20, Math.round(15 * _scale)))
    readonly property int subtitleSize: Math.max(10, Math.min(14, Math.round(12 * _scale)))
    readonly property real textSlot: Math.max(52, Math.round(72 * _scale))
    readonly property int textGap: Math.max(6, Math.round(8 * _scale))

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
        radius: card.width / 2
        icon: "person"
        iconSize: Math.max(28, Math.round(44 * card._scale))
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }

    Text {
        id: nameLabel
        x: 0
        y: card.width + card.textGap
        width: card.width
        text: card.name
        color: Ui.textPrimary
        horizontalAlignment: Text.AlignHCenter
        maximumLineCount: 1
        elide: Text.ElideRight
        font.pixelSize: card.titleSize
        font.weight: Ui.weightBold
    }

    Text {
        x: 0
        y: nameLabel.y + nameLabel.implicitHeight + 2
        width: card.width
        visible: card.count > 0
        text: i18n.t("common.songCount", card.count)
        color: Ui.textSecondary
        horizontalAlignment: Text.AlignHCenter
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
