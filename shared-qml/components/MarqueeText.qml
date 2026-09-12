import QtQuick
import QtQuick.Effects

// A single-line Text that elides normally when it fits, and auto-scrolls
// continuously with faded edges when it overflows -- for song/playlist titles
// too long to read via a static "..." truncation.
//
// The edge fade is an alpha mask over the text itself. Painting a background-
// coloured gradient on top only works on a known solid fill; translucent lyric
// chrome, cover-derived colours and animated backgrounds otherwise reveal a
// dark strip. The offscreen effect is enabled only for overflowing text.
//
// Plain Item + anchors-compatible sizing (no Layout wrapper), so it drops into
// both Layout-managed parents and anchors-only ones alike.
Item {
    id: root

    property string text: ""
    property color textColor: "black"
    property real fontSize: 14
    property string fontFamily: ""
    property int fontWeight: Font.Normal
    property bool centered: false
    property real fadeWidth: 20
    // Gap between the tail of one copy and the beginning of the next, written as
    // real spacer glyphs rather than a pixel offset: both copies are shaped into
    // ONE line, so no width arithmetic can ever place the next copy on top of the
    // previous one's tail. Plain spaces keep the font selection (which is picked
    // from the string's own script) identical to the single-copy probe.
    property string gapText: "      "
    // Scroll pace (px/s) and the pause held at the beginning of each cycle.
    property real speed: 32
    property int pauseMs: 1000
    // What the marquee actually paints: two copies separated by the gap.
    property string loopText: root.text + root.gapText + root.text

    implicitHeight: probe.implicitHeight
    // A plain Item's height does not default to implicitHeight in qml4j. Bind
    // the real height so anchors.fill hit targets work unless a caller sizes it.
    height: probe.implicitHeight
    clip: true

    // Hidden probe: measure the real unwrapped width.
    Text {
        id: probe
        visible: false
        text: root.text
        font.family: root.fontFamily
        font.pixelSize: root.fontSize
        font.weight: root.fontWeight
    }
    // The scrolling line's own width. One cycle travels loopWidth - probeWidth,
    // i.e. exactly one copy plus the gap, measured without a trailing space whose
    // advance a shaper may or may not report.
    Text {
        id: loopProbe
        visible: false
        text: root.loopText
        font.family: root.fontFamily
        font.pixelSize: root.fontSize
        font.weight: root.fontWeight
    }
    property bool overflowing: root.width > 0 && probe.implicitWidth > root.width
    property real cycleWidth: Math.max(0, loopProbe.implicitWidth - probe.implicitWidth)

    // A running NumberAnimation snapshots its endpoints. Resizing or changing
    // the measured line must retire that run, including its child animations,
    // before starting a fresh pause/cycle with the current geometry.
    function restartScrolling() {
        scrollAnimation.stop()
        label.scrollX = 0
        if (root.overflowing && root.visible) scrollAnimation.start()
    }

    onWidthChanged: restartScrolling()
    onTextChanged: restartScrolling()
    onCycleWidthChanged: restartScrolling()
    onOverflowingChanged: restartScrolling()
    onVisibleChanged: restartScrolling()
    Component.onCompleted: restartScrolling()

    // The common, zero-offscreen-cost path for text that fits. Emptied rather than
    // only hidden while scrolling, so the two paths can never paint at once.
    Text {
        visible: !root.overflowing
        anchors.fill: parent
        text: root.overflowing ? "" : root.text
        color: root.textColor
        font.family: root.fontFamily
        font.pixelSize: root.fontSize
        font.weight: root.fontWeight
        elide: Text.ElideRight
        horizontalAlignment: root.centered ? Text.AlignHCenter : Text.AlignLeft
        verticalAlignment: Text.AlignVCenter
    }

    // MultiEffect renders an invisible source Item, as it does for Ripple.qml.
    // A root-sized source preserves the moving label's coordinates and clips
    // the complete marquee before the mask is applied.
    Item {
        id: marqueeContent
        x: 0
        y: 0
        width: root.width
        height: root.height
        visible: false
        clip: true

        // Both copies in one shaped line: the second one enters the viewport
        // before the first has left it, and the shaper -- not a computed offset --
        // is what keeps them apart.
        Text {
            id: label
            property real scrollX: 0
            x: scrollX
            anchors.verticalCenter: parent.verticalCenter
            width: loopProbe.implicitWidth
            text: root.overflowing ? root.loopText : ""
            color: root.textColor
            font.family: root.fontFamily
            font.pixelSize: root.fontSize
            font.weight: root.fontWeight
            elide: Text.ElideNone
            horizontalAlignment: Text.AlignLeft

            // Scroll exactly one copy plus the gap, so the second copy ends up
            // where the first one started. Rewinding before the pause (rather
            // than relying on the endpoint alone) keeps the held frame at the
            // exact start of the text even if the measured cycle width is a
            // fraction off the shaped one.
            SequentialAnimation {
                id: scrollAnimation
                loops: Animation.Infinite
                ScriptAction { script: label.scrollX = 0 }
                PauseAnimation { duration: root.pauseMs }
                NumberAnimation {
                    target: label
                    property: "scrollX"
                    from: 0
                    to: -root.cycleWidth
                    duration: root.cycleWidth / root.speed * 1000
                    easing.type: Easing.Linear
                }
            }
        }
    }

    // Alpha-only mask: transparent at the viewport boundaries and opaque
    // through the middle. RGB never reaches the final image.
    Rectangle {
        id: marqueeMask
        x: 0
        y: 0
        width: root.width
        height: root.height
        visible: false
        property real edgeFraction: Math.min(0.5, root.fadeWidth / Math.max(1, root.width))
        // Fade an edge only while a text copy actually crosses that boundary.
        // During the initial pause label starts exactly at x=0, so its first
        // glyph remains fully opaque instead of being mistaken for overflow.
        property bool fadeLeft: label.scrollX < 0
                                && label.scrollX + probe.implicitWidth > 0
        property real nextX: label.scrollX + root.cycleWidth
        property bool fadeRight: (label.scrollX < root.width
                                  && label.scrollX + probe.implicitWidth > root.width)
                                 || (nextX < root.width
                                     && nextX + probe.implicitWidth > root.width)
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop {
                position: 0.0
                color: marqueeMask.fadeLeft ? Qt.rgba(1, 1, 1, 0) : "white"
            }
            GradientStop { position: marqueeMask.edgeFraction; color: "white" }
            GradientStop { position: 1.0 - marqueeMask.edgeFraction; color: "white" }
            GradientStop {
                position: 1.0
                color: marqueeMask.fadeRight ? Qt.rgba(1, 1, 1, 0) : "white"
            }
        }
    }

    MultiEffect {
        visible: root.overflowing
        x: 0
        y: 0
        width: root.width
        height: root.height
        source: marqueeContent
        maskEnabled: true
        maskSource: marqueeMask
        // Qt's defaults treat every non-zero mask alpha as fully visible.
        // Widen the lower threshold so the gradient remains a gradual fade.
        maskThresholdMin: 0.5
        maskSpreadAtMin: 1.0
    }
}
