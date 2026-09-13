import QtQuick
import miuix.Core
Item {
    id: root
    property alias text: label.text
    implicitWidth: parent ? parent.width : 320
    implicitHeight: 30

    Text {
        id: label
        x: 28
        y: 8
        width: parent.width - 56
        height: 14
        font.pixelSize: 14
        font.weight: 75
        color: Theme.color.onBackgroundVariant
    }
}
