import QtQuick
import md3.Core

// One song/track row. Plain anchors — NOT nested RowLayout/ColumnLayout: the
// Layout measure passes run for every visible row on every dirty frame (playback
// ticks the scene ~5x/s), which was a real source of stutter. `highlighted`
// marks the playing entry. A pre-cached local-file Image replaces the glyph
// when a thumbnail is available — zero network overhead while scrolling.
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

    // Inset rounded hover highlight. One constant Rectangle per row (no per-frame
    // allocation), so the long-list fast path is unaffected. The playing row keeps
    // its primary-tinted text/glyph rather than a fill, so it reads on either theme.
    Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 8
        anchors.topMargin: 4
        anchors.bottomMargin: 4
        radius: 12
        color: ma.containsMouse ? Theme.color.surfaceContainerHigh : "transparent"
    }

    Item {
        id: leading
        anchors.left: parent.left
        anchors.leftMargin: 10
        anchors.verticalCenter: parent.verticalCenter
        width: 44
        height: 44

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

    MouseArea {
        id: ma
        anchors.fill: parent
        hoverEnabled: true
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
