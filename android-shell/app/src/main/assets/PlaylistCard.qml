import QtQuick
import QtQuick.Layouts
import md3.Core

// A playlist tile sized by the enclosing GridLayout (Layout.fillWidth → each of
// the two columns gets half the width, so margins stay even). Height tracks the
// (square) cover + two text lines. Plain Item, no sizing loop. Placeholder cover.
Item {
    id: card

    property string name: ""
    property int count: 0
    signal clicked()

    Layout.fillWidth: true
    implicitHeight: width + 52

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
