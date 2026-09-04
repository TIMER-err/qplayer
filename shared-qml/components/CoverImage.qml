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
    // Cross-fade the art in when the source changes (e.g. the lyric page on a track
    // switch): the old art fades out to the placeholder, the new one fades in once
    // decoded. Off by default so list rows / cards keep swapping instantly.
    property bool fadeIn: false
    // qml4j decodes at the sourceSize present when `source` is first seen and
    // does not re-decode if sourceSize later changes. Width is 0 before the
    // first layout, which would decode the full 512–1024px asset onto the Java
    // heap. Latch the pixel size once laid out so the first decode is already
    // at display resolution; refresh only on a real resize.
    property int decodeW: 0
    property int decodeH: 0

    function latchDecodeSize() {
        var w = Math.round(width * player.pixelRatio)
        var h = Math.round(height * player.pixelRatio)
        if (w <= 0 || h <= 0) return
        if (decodeW === 0 || Math.abs(w - decodeW) >= 32 || Math.abs(h - decodeH) >= 32) {
            decodeW = w
            decodeH = h
        }
    }

    onWidthChanged: latchDecodeSize()
    onHeightChanged: latchDecodeSize()
    Component.onCompleted: latchDecodeSize()

    // Placeholder underneath; the cover image draws over it once decoded.
    Rectangle {
        anchors.fill: parent
        radius: cover.radius
        color: Theme.color.surfaceContainerHighest
        Text {
            // Reactive width/height (not anchors.fill / centerIn, which the skipped layout
            // pass leaves unresolved for an off-screen tile) give the node a real box size;
            // the glyph then self-centres at paint time. See SongRow's placeholder.
            width: parent.width
            height: parent.height
            horizontalAlignment: Text.AlignHCenter
            text: cover.icon
            font.family: Theme.iconFont.name
            font.pixelSize: cover.iconSize
            color: Theme.color.onSurfaceVariantColor
        }
    }

    Image {
        id: img
        anchors.fill: parent
        source: cover.decodeW > 0 ? cover.source : ""
        radius: cover.radius
        fillMode: "PreserveAspectCrop"
        // Without this, qml4j's decoder (ImageLoader.decodeRaster) has no target
        // size to shrink to at LOAD time, so it keeps the full source resolution
        // (now up to 1024px for playlist/album covers) and the draw-time scale
        // down to a small grid tile falls back to Painter's plain bilinear
        // SamplingMode.LINEAR (no mipmap) -- that aliases badly on any
        // high-frequency detail in the art (fine text, a repeating pattern),
        // showing up as moiré. Setting sourceSize routes the SAME downscale
        // through decodeRaster's mipmap-quality FilterMipmap pass instead, done
        // once at decode time rather than approximated every frame.
        //
        // Scale by the device pixel ratio: QML is authored in logical pixels and
        // qml4j hands sourceSize to decodeRaster verbatim, so a bare cover.width
        // decodes a 1x image that the renderer then upscales into the framebuffer
        // -- visibly soft on every display scaled above 100%. Latched (see
        // decodeW) so the first decode is not the full-res asset at width 0.
        sourceSize.width: cover.decodeW
        sourceSize.height: cover.decodeH
        // Fade the art in on a source change (fadeIn only). Driven by source presence,
        // NOT the Image's load status: a status-gated opacity would deadlock — opacity 0
        // makes the renderer skip painting the node, and the decode that advances status
        // only runs while painting. An empty source draws nothing (the placeholder shows
        // through); once a source is set the art fades up as it decodes.
        opacity: (!cover.fadeIn || cover.source !== "") ? 1 : 0
        Behavior on opacity {
            enabled: cover.fadeIn
            NumberAnimation { duration: 260; easing.type: Easing.OutCubic }
        }
    }
}
