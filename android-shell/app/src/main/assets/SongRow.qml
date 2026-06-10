import QtQuick
import QtQuick.Layouts
import md3.Core

// One song/track row: leading Material Symbols glyph + title/artist, whole-row
// tap. `highlighted` marks the currently playing entry.
Rectangle {
    id: row

    property string rowTitle: ""
    property string rowArtist: ""
    property bool highlighted: false
    signal activated()

    implicitHeight: 64
    color: ma.containsMouse ? Theme.color.surfaceContainerHigh : "transparent"

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        spacing: 14

        Text {
            Layout.alignment: Qt.AlignVCenter
            text: row.highlighted ? "equalizer" : "music_note"
            font.family: Theme.iconFont.name
            font.pixelSize: 22
            color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceVariantColor
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignVCenter
            spacing: 2

            Text {
                Layout.fillWidth: true
                text: row.rowTitle
                elide: Text.ElideRight
                color: row.highlighted ? Theme.color.primary : Theme.color.onSurfaceColor
                fontSize: 15
            }
            Text {
                Layout.fillWidth: true
                text: row.rowArtist
                elide: Text.ElideRight
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
            }
        }
    }

    MouseArea {
        id: ma
        anchors.fill: parent
        hoverEnabled: true
        onClicked: row.activated()
    }
}
