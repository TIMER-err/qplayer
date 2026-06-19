import QtQuick
import md3.Core
import "."

// A playlist tile. Its size is fixed from `tile` (computed by the page from the
// available width), set as implicitWidth so GridLayout sizes its columns
// correctly. No dependence on the layout-assigned width → no feedback loop.
Item {
    id: card

    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
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

        CoverImage {
            width: parent.width
            height: parent.width
            radius: 14
            icon: "queue_music"
            iconSize: 44
            source: card.coverThumbPath || card.coverUrl
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
