import QtQuick
import md3.Core

// One song/track row. Plain anchors — NOT nested RowLayout/ColumnLayout: the
// Layout measure passes run for every visible row on every dirty frame (playback
// ticks the scene ~5x/s), which was a real source of stutter. `highlighted`
// marks the playing entry. A pre-cached local-file Image replaces the glyph
// when a thumbnail is available — zero network overhead while scrolling.
//
// Lazy image loading: when `lazyLoad` is true, the cover Image only sets its
// source when the row is within the Flickable viewport (+/- 3 rows preload).
// This prevents hundreds of off-screen images from being decoded into memory
// simultaneously in long playlists.
Rectangle {
    id: row

    property string rowTitle: ""
    property string rowArtist: ""
    property string coverThumbPath: ""
    property bool highlighted: false
    property bool removable: false
    property bool addable: false
    /** Enable lazy image loading based on viewport position. */
    property bool lazyLoad: false
    /** Parent Flickable's contentY (scroll offset). */
    property real flickContentY: 0
    /** Parent Flickable's visible height. */
    property real flickHeight: 0
    signal activated()
    signal removeRequested()
    signal addRequested()

    implicitHeight: 64
    color: "transparent"

    // Inset rounded hover highlight. Constant Rectangle (no per-frame allocation);
    // its opacity fades with hover instead of snapping the colour. The playing row
    // keeps its primary-tinted text/glyph rather than a fill.
    Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 8
        anchors.topMargin: 4
        anchors.bottomMargin: 4
        radius: 12
        color: Theme.color.surfaceContainerHigh
        opacity: ripple.containsMouse ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
    }

    // Cover sits 4px inside the hover pill on every side, and its radius is the
    // pill's radius minus that 4px gap (12 - 4 = 8) so the two rounded corners are
    // concentric: leftMargin 8(pill)+4, size 64-2*(4+4) = 48, radius 8.
    Item {
        id: leading
        anchors.left: parent.left
        anchors.leftMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        width: 48
        height: 48

        // Placeholder background (shown when no cover)
        Rectangle {
            anchors.fill: parent
            radius: 8
            color: Theme.color.surfaceContainerHighest
            visible: row.coverThumbPath == ""
            Text {
                anchors.centerIn: parent
                text: row.highlighted ? "equalizer" : "music_note"
                font.family: Theme.iconFont.name
                font.pixelSize: 22
                color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceVariantColor
            }
        }

        // Cover image with native clipRRect rounding (qml4j Image.radius).
        // When lazyLoad is on, source is set once when the row enters the viewport
        // preload zone and never cleared — this avoids re-fetching/re-decoding on
        // scroll-back while still preventing all-off-screen images from loading
        // at list creation time.
        Image {
            id: coverImg
            anchors.fill: parent
            visible: row.coverThumbPath != "" && (!row.lazyLoad || row._loadTriggered)
            source: (row.coverThumbPath != "" && (!row.lazyLoad || row._loadTriggered))
                   ? row.coverThumbPath : ""
            radius: 8
            fillMode: Image.PreserveAspectCrop
        }
    }

    Text {
        anchors.left: leading.right
        anchors.leftMargin: 14
        anchors.right: parent.right
        anchors.rightMargin: (row.removable || row.addable) ? 52 : 16
        anchors.bottom: parent.verticalCenter
        anchors.bottomMargin: 1
        text: row.rowTitle
        elide: Text.ElideRight
        color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceColor
        fontSize: 15
    }

    Text {
        anchors.left: leading.right
        anchors.leftMargin: 14
        anchors.right: parent.right
        anchors.rightMargin: (row.removable || row.addable) ? 52 : 16
        anchors.top: parent.verticalCenter
        anchors.topMargin: 2
        text: row.rowArtist
        elide: Text.ElideRight
        color: Theme.color.onSurfaceVariantColor
        fontSize: 12
    }

    // Tap + Material ripple, clipped to the inset pill shape. Idle cost is nil
    // (Ripple gates its MultiEffect on live-wave count); a wave only renders while
    // a row is being pressed. Reactive geometry, NOT anchors: under cachedLayout
    // (long lists) the measure pass that resolves anchors is skipped once the
    // container box is stable, so an anchor-sized ripple stays stuck at the row's
    // first (often zero, hence -16 after margins) width — the mispositioned/half/
    // crashing ripple. Width bindings track row.width and update without a re-measure.
    Ripple {
        id: ripple
        x: 8
        y: 4
        width: row.width - 16
        height: row.height - 8
        clipRadius: 12
        rippleColor: Theme.color.onSurfaceColor
        onClicked: row.activated()
    }

    // Lightweight remove control: a glyph + MouseArea with explicit geometry.
    // The md3 IconButton (internal Ripple + MouseArea) got stuck mispositioned and
    // untappable when a delegate was rebuilt on a queue removal under cachedLayout;
    // this single-pass control avoids that and is cheaper per row. Declared last so
    // it sits above the row tap.
    MouseArea {
        visible: row.removable
        width: 48
        height: parent.height
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        onClicked: row.removeRequested()

        Text {
            anchors.centerIn: parent
            text: "close"
            font.family: Theme.iconFont.name
            font.pixelSize: 20
            color: Theme.color.onSurfaceVariantColor
        }
    }

    MouseArea {
        visible: row.addable && !row.removable
        width: 48
        height: parent.height
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        onClicked: row.addRequested()

        Text {
            anchors.centerIn: parent
            text: "playlist_add"
            font.family: Theme.iconFont.name
            font.pixelSize: 20
            color: Theme.color.onSurfaceVariantColor
        }
    }

    // Whether this row is within (or near) the Flickable viewport.
    // Preload margin: 3 rows above/below the visible area.
    readonly property bool _inViewport: !row.lazyLoad
        || (row.y >= row.flickContentY - row.height * 3
            && row.y <= row.flickContentY + row.flickHeight + row.height * 3)

    // Latches to true the first time _inViewport becomes true, so that once
    // an image starts loading it is never unloaded (avoids re-fetch flicker).
    property bool _loadTriggered: row._inViewport
    on_InViewportChanged: { if (row._inViewport) row._loadTriggered = true; }
}
