import QtQuick
import md3.Core

// One song/track row. Plain anchors — NOT nested RowLayout/ColumnLayout: the
// Layout measure passes run for every visible row on every dirty frame (playback
// ticks the scene ~5x/s), which was a real source of stutter. `highlighted`
// marks the playing entry. Leading glyph only (album thumbnails were reverted:
// instantiating a CoverImage subtree per row cost too much on long lists).
Rectangle {
    id: row

    property string rowTitle: ""
    property string rowArtist: ""
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

    Text {
        id: glyph
        anchors.left: parent.left
        anchors.leftMargin: 16
        anchors.verticalCenter: parent.verticalCenter
        text: row.highlighted ? "equalizer" : "music_note"
        font.family: Theme.iconFont.name
        font.pixelSize: 22
        color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceVariantColor
    }

    Text {
        anchors.left: glyph.right
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
        anchors.left: glyph.right
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

    // Declared after the row MouseArea so it sits on top and consumes the tap.
    IconButton {
        visible: row.removable
        anchors.right: parent.right
        anchors.rightMargin: 4
        anchors.verticalCenter: parent.verticalCenter
        type: "standard"; icon: "close"
        onClicked: row.removeRequested()
    }
}
