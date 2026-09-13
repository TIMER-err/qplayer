import QtQuick
import QtQuick.Effects
import miuix.Core
Item {
    id: cardRoot

    property string type: "filled"
    property real radius: Theme.metrics.cardRadius
    property real padding: 0
    default property alias contentItem: contentContainer.data
    property alias color: cardRoot.containerColor
    property bool hovered: false
    property bool pressed: false
    property color containerColor: Theme.color.surfaceContainer
    property color outlineColor: Theme.color.outline
    property color rippleColor: "transparent"
    property color stateLayerColor: "transparent"
    property int elevationLevel: 0
    signal clicked()

    implicitWidth: 300
    implicitHeight: 200

    layer.enabled: true
    layer.effect: MultiEffect {
        maskEnabled: true
        maskSource: maskRect
    }

    Rectangle {
        id: maskRect
        anchors.fill: parent
        radius: cardRoot.radius
        color: "#000000"
        visible: false
    }

    Rectangle {
        anchors.fill: parent
        radius: cardRoot.radius
        color: cardRoot.containerColor
        border.width: cardRoot.type === "outlined" ? 1 : 0
        border.color: cardRoot.outlineColor
    }

    Item {
        id: contentContainer
        anchors.fill: parent
        anchors.margins: cardRoot.padding
    }
}
