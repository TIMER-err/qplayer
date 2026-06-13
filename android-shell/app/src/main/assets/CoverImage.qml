import QtQuick
import md3.Core

// Rounded album-cover image with a glyph placeholder shown until the cover
// decodes. `source` accepts a local asset path or an http(s) URL — the engine
// Image fetches + decodes remote sources off-thread. Rounding is done by the
// Image's own `radius` (a clipRRect at draw time), NOT a layer.effect mask: the
// mask path allocates an offscreen surface per instance every frame, so a grid
// of covers paid one saveLayer per card per frame while scrolling. clipRRect is
// a cheap per-frame clip with no offscreen allocation.
Item {
    id: cover

    property string source: ""
    property real radius: 8
    property string icon: "music_note"
    property int iconSize: 26

    // Placeholder underneath; the cover image draws over it once decoded.
    Rectangle {
        anchors.fill: parent
        radius: cover.radius
        color: Theme.color.surfaceContainerHighest
        Text {
            anchors.centerIn: parent
            text: cover.icon
            font.family: Theme.iconFont.name
            font.pixelSize: cover.iconSize
            color: Theme.color.onSurfaceVariantColor
        }
    }

    Image {
        anchors.fill: parent
        source: cover.source
        radius: cover.radius
        fillMode: "PreserveAspectCrop"
    }
}
