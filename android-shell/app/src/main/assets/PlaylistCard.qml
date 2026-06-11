import QtQuick
import md3.Core

// A playlist tile. Plain Item with explicit sizes — do NOT base it on Card whose
// implicitHeight tracks content while the content fills it back (sizing loop that
// thrashes layout every frame). Placeholder cover glyph; network covers later.
Item {
    id: card

    property string name: ""
    property int count: 0
    signal clicked()

    width: 150
    implicitHeight: 212

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
