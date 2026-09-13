import QtQuick
import miuix.Core

Item {
    id: control
    property bool running: true
    property bool withContainer: false
    property color color: Theme.color.primary
    property color containerColor: Theme.color.secondaryContainer
    property real size: 20
    property real strokeWidth: 2
    property real dotRadius: 2
    implicitWidth: size
    implicitHeight: size

    Rectangle {
        anchors.fill: parent
        radius: Math.min(width, height) / 2
        color: control.withContainer ? control.containerColor : "transparent"
        border.width: control.strokeWidth
        border.color: control.color
    }
    Item {
        anchors.fill: parent
        NumberAnimation on rotation {
            from: 0; to: 360; duration: 800
            loops: Animation.Infinite
            running: control.running && control.visible
        }
        Rectangle {
            width: control.dotRadius * 2
            height: width
            radius: control.dotRadius
            color: control.color
            x: (parent.width - width) / 2
            y: control.strokeWidth / 2 + control.dotRadius
        }
    }
}
