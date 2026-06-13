import QtQuick
import md3.Core
import "."

// One song/track row. Plain anchors — NOT nested RowLayout/ColumnLayout: the
// Layout measure passes run for every visible row on every dirty frame (playback
// ticks the scene ~5x/s), which was a real source of stutter. `highlighted`
// marks the playing entry. A leading album thumbnail is shown when `coverUrl` is
// set, otherwise a glyph; the cover Image fetches/decodes only when the row is
// actually painted (off-screen rows are culled), so a long list doesn't fetch
// every cover at once.
Rectangle {
    id: row

    property string rowTitle: ""
    property string rowArtist: ""
    property string coverUrl: ""
    property bool highlighted: false
    property bool removable: false
    signal activated()
    signal removeRequested()

    // Text starts past the leading slot — a wider gap when a thumbnail is shown.
    property real leadGap: row.coverUrl.length > 0 ? 68 : 52

    implicitHeight: 64
    color: ma.containsMouse ? Theme.color.surfaceContainerHigh : "transparent"

    CoverImage {
        id: cover
        visible: row.coverUrl.length > 0
        anchors.left: parent.left
        anchors.leftMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        width: 44; height: 44
        radius: 6
        iconSize: 20
        source: row.coverUrl
    }

    Text {
        id: glyph
        visible: row.coverUrl.length === 0
        anchors.left: parent.left
        anchors.leftMargin: 16
        anchors.verticalCenter: parent.verticalCenter
        text: row.highlighted ? "equalizer" : "music_note"
        font.family: Theme.iconFont.name
        font.pixelSize: 22
        color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceVariantColor
    }

    Text {
        anchors.left: parent.left
        anchors.leftMargin: row.leadGap
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
        anchors.left: parent.left
        anchors.leftMargin: row.leadGap
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
