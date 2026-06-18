import QtQuick
import md3.Core

// One song/track row. Plain anchors — NOT nested RowLayout/ColumnLayout: the
// Layout measure passes run for every visible row on every dirty frame (playback
// ticks the scene ~5x/s), which was a real source of stutter. `highlighted`
// marks the playing entry. When coverThumbPath is set, a rounded Image (the
// engine fetches + caches the CDN thumbnail off-thread) replaces the glyph;
// otherwise the glyph placeholder shows.
Rectangle {
    id: row

    property string rowTitle: ""
    property string rowArtist: ""
    property string coverThumbPath: ""
    property bool highlighted: false
    property bool removable: false
    signal activated()
    signal removeRequested()

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

        // Cover image with native clipRRect rounding (qml4j Image.radius)
        Image {
            anchors.fill: parent
            visible: row.coverThumbPath != ""
            source: row.coverThumbPath
            radius: 8
            fillMode: Image.PreserveAspectCrop
        }
    }

    Text {
        anchors.left: leading.right
        anchors.leftMargin: 14
        anchors.right: parent.right
        anchors.rightMargin: row.removable ? 52 : 16
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
        anchors.rightMargin: row.removable ? 52 : 16
        anchors.top: parent.verticalCenter
        anchors.topMargin: 2
        text: row.rowArtist
        elide: Text.ElideRight
        color: Theme.color.onSurfaceVariantColor
        fontSize: 12
    }

    // Tap + Material ripple, clipped to the inset pill shape. Idle cost is nil
    // (Ripple gates its MultiEffect on live-wave count); a wave only renders while
    // a row is being pressed.
    Ripple {
        id: ripple
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 8
        anchors.topMargin: 4
        anchors.bottomMargin: 4
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
}
