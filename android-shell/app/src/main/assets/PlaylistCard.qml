import QtQuick
import md3.Core

// A playlist tile. Its size is fixed from `tile` (computed by the page from the
// available width), set as implicitWidth so GridLayout sizes its columns
// correctly. No dependence on the layout-assigned width → no feedback loop.
// Placeholder cover glyph; network covers later.
Item {
    id: card

    property string name: ""
    property int count: 0
    property real tile: 160
    signal clicked()

    implicitWidth: tile
    implicitHeight: tile + 52

    Column {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: 4
        spacing: 6

        Rectangle {
            width: parent.width
            height: parent.width
            radius: 14
            color: cardMa.containsMouse ? Theme.color.surfaceContainerHighest
                                        : Theme.color.surfaceContainer
            Text {
                anchors.centerIn: parent
                text: "queue_music"
                font.family: Theme.iconFont.name
                font.pixelSize: 44
                color: Theme.color.onSurfaceVariantColor
            }
        }
        Text {
            width: parent.width
            text: card.name
            color: Theme.color.onSurfaceColor
            fontSize: 14
            elide: Text.ElideRight
        }
        Text {
            width: parent.width
            visible: card.count > 0
            text: card.count + " 首"
            color: Theme.color.onSurfaceVariantColor
            fontSize: 12
        }
    }

    MouseArea {
        id: cardMa
        anchors.fill: parent
        hoverEnabled: true
        onClicked: card.clicked()
    }
}
