import QtQuick
import md3.Core

// Rounded album-cover image with a glyph placeholder shown until the cover
// decodes. `source` accepts a local asset path or an http(s) URL — the engine
// Image fetches + decodes remote sources off-thread. Rounding is done with a
// layer.effect mask: the renderer clips the layer to the mask Rectangle's
// corner radius (same mechanism the md3 carousel uses for its cards).
Item {
    id: cover

    property string source: ""
    property real radius: 8
    property string icon: "music_note"
    property int iconSize: 26

    layer.enabled: true
    layer.effect: MultiEffect {
        maskEnabled: true
        maskSource: maskRect
    }

    Rectangle {
        id: maskRect
        anchors.fill: parent
        radius: cover.radius
        visible: false
    }

    // Placeholder underneath; the cover image draws over it once decoded.
    Rectangle {
        anchors.fill: parent
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
        fillMode: "PreserveAspectCrop"
    }
}
