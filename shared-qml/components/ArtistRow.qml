import QtQuick
import miuix.Core
import design
import "."

// Compact horizontal artist entry used by the song-credits picker. It mirrors
// SongRow's long, narrow interaction shape with ArtistCard's round avatar.
Item {
    id: row

    property double artistId: 0
    property string name: ""
    property string coverUrl: ""
    property string coverThumbPath: ""
    signal activated()

    implicitHeight: Ui.rowHeight

    Rectangle {
        x: Ui.space1
        y: Ui.space1
        width: row.width - 2 * Ui.space1
        height: row.height - 2 * Ui.space1
        radius: Ui.radiusSm
        color: ripple.pressed ? Ui.surfacePressed : Ui.surfaceHover
        opacity: ripple.containsMouse || ripple.pressed ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
    }

    Rectangle {
        id: avatar
        x: Ui.space3
        y: (row.height - height) / 2
        width: 44
        height: 44
        radius: width / 2
        color: Ui.surfaceSunken
        Glyph {
            x: (parent.width - size) / 2
            y: (parent.height - size) / 2
            visible: row.coverThumbPath === "" && row.coverUrl === ""
            name: "person"
            color: Ui.textSecondary
        }
        Image {
            width: parent.width
            height: parent.height
            source: row.coverThumbPath !== "" ? row.coverThumbPath : row.coverUrl
            radius: width / 2
            fillMode: Image.PreserveAspectCrop
            // Decode at display resolution (device pixels), not the full 512px
            // asset: a plain draw-time downscale aliases into moiré.
            sourceSize.width: Math.round(44 * player.pixelRatio)
            sourceSize.height: Math.round(44 * player.pixelRatio)
        }
    }

    Text {
        x: avatar.x + avatar.width + Ui.space3
        y: (row.height - implicitHeight) / 2
        width: Math.max(0, row.width - x - Ui.space4)
        text: row.name
        elide: Text.ElideRight
        maximumLineCount: 1
        color: Ui.textPrimary
        font.pixelSize: Ui.textBody
    }

    Ripple {
        id: ripple
        x: Ui.space1
        y: Ui.space1
        width: row.width - 2 * Ui.space1
        height: row.height - 2 * Ui.space1
        clipRadius: Ui.radiusSm
        rippleColor: Ui.textPrimary
        onClicked: row.activated()
    }
}
