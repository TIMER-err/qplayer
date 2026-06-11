import QtQuick
import md3.Core

// A playlist tile built on the MD3 Card (filled, with its own ripple + clicked).
// Placeholder cover glyph for now — network covers are a later pass.
Card {
    id: card

    property string name: ""
    property int count: 0

    width: 160
    height: 196
    type: "filled"
    padding: 8

    Column {
        anchors.fill: parent
        spacing: 8

        Rectangle {
            width: parent.width
            height: width
            radius: 12
            color: Theme.color.surfaceContainerHighest
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
}
