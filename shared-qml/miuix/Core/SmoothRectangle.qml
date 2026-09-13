// Geometry ported from miuix-squircle/SquirclePath.kt (Apache-2.0).
import QtQuick
import QtQuick.Shapes
import miuix.Core

Shape {
    id: smoothRect
    property color color: Theme.color.surfaceContainer
    property real radius: 16
    property real extension: 1.1
    property real borderWidth: 0
    property color borderColor: Theme.color.outline
    property bool _ready: false

    onRadiusChanged: updatePath()
    onExtensionChanged: updatePath()
    onBorderWidthChanged: updatePath()
    onWidthChanged: updatePath()
    onHeightChanged: updatePath()
    Component.onCompleted: { _ready = true; updatePath() }

    // A filled ring keeps both border contours explicit, avoiding stroke caps
    // at the closing seam and the GPU's thin curved-stroke tessellation.
    function updatePath() {
        if (!_ready) return
        var stroke = Math.min(Math.max(0, borderWidth), Math.min(width, height) / 2)
        updateContour([fillStart, fillTop, fillTR, fillRight, fillBR, fillBottom, fillBL, fillLeft, fillTL], stroke / 2)
        if (stroke > 0) {
            updateContour([outerStart, outerTop, outerTR, outerRight, outerBR, outerBottom, outerBL, outerLeft, outerTL], 0)
            updateContour([innerStart, innerTop, innerTR, innerRight, innerBR, innerBottom, innerBL, innerLeft, innerTL], stroke)
        }
    }

    // PathElement coordinates are assigned together when geometry changes.
    function updateContour(parts, inset) {
        var w = Math.max(0, width - 2 * inset)
        var h = Math.max(0, height - 2 * inset)
        var tile = Math.min(Math.max(0, radius - inset) * Math.max(1, Math.min(2, extension)), Math.min(w, h) / 2)
        var handle = tile * 0.357
        var right = width - inset
        var bottom = height - inset
        parts[0].x = inset + tile; parts[0].y = inset
        parts[1].x = right - tile; parts[1].y = inset
        parts[2].control1X = right - handle; parts[2].control1Y = inset
        parts[2].control2X = right; parts[2].control2Y = inset + handle
        parts[2].x = right; parts[2].y = inset + tile
        parts[3].x = right; parts[3].y = bottom - tile
        parts[4].control1X = right; parts[4].control1Y = bottom - handle
        parts[4].control2X = right - handle; parts[4].control2Y = bottom
        parts[4].x = right - tile; parts[4].y = bottom
        parts[5].x = inset + tile; parts[5].y = bottom
        parts[6].control1X = inset + handle; parts[6].control1Y = bottom
        parts[6].control2X = inset; parts[6].control2Y = bottom - handle
        parts[6].x = inset; parts[6].y = bottom - tile
        parts[7].x = inset; parts[7].y = inset + tile
        parts[8].control1X = inset; parts[8].control1Y = inset + handle
        parts[8].control2X = inset + handle; parts[8].control2Y = inset
        parts[8].x = inset + tile; parts[8].y = inset
    }

    ShapePath {
        fillColor: smoothRect.color
        strokeColor: "transparent"
        strokeWidth: 0
        PathMove { id: fillStart }
        PathLine { id: fillTop }
        PathCubic { id: fillTR }
        PathLine { id: fillRight }
        PathCubic { id: fillBR }
        PathLine { id: fillBottom }
        PathCubic { id: fillBL }
        PathLine { id: fillLeft }
        PathCubic { id: fillTL }
    }
    ShapePath {
        fillColor: smoothRect.borderWidth > 0 ? smoothRect.borderColor : "transparent"
        fillRule: "OddEvenFill"
        strokeColor: "transparent"
        strokeWidth: 0
        PathMove { id: outerStart }
        PathLine { id: outerTop }
        PathCubic { id: outerTR }
        PathLine { id: outerRight }
        PathCubic { id: outerBR }
        PathLine { id: outerBottom }
        PathCubic { id: outerBL }
        PathLine { id: outerLeft }
        PathCubic { id: outerTL }
        PathMove { id: innerStart }
        PathLine { id: innerTop }
        PathCubic { id: innerTR }
        PathLine { id: innerRight }
        PathCubic { id: innerBR }
        PathLine { id: innerBottom }
        PathCubic { id: innerBL }
        PathLine { id: innerLeft }
        PathCubic { id: innerTL }
    }
}
