import QtQuick
import miuix.Core
Item {
    id: control
    property alias text: field.text
    property string placeholderText: "Search"
    property bool enabled: true
    property bool clearButtonEnabled: true
    readonly property bool active: field.activeFocus
    signal accepted()
    signal cleared()

    function clear() {
        field.text = ""
        field.forceActiveFocus()
        control.cleared()
    }

    implicitWidth: parent ? parent.width : 320
    implicitHeight: 45
    height: 45

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: Theme.color.surfaceContainerHigh
    }

    Icon {
        x: 16
        anchors.verticalCenter: parent.verticalCenter
        name: "search"
        width: 20
        height: 20
        color: Theme.color.onSurfaceContainerHigh
    }

    Text {
        x: 44
        anchors.verticalCenter: parent.verticalCenter
        width: field.width
        elide: Text.ElideRight
        text: control.placeholderText
        font.pixelSize: 17
        font.weight: 57
        color: Theme.color.onSurfaceContainerHigh
        visible: field.text.length === 0
    }

    TextInput {
        id: field
        x: 44
        y: 0
        width: Math.max(0, control.width - (clearAction.visible ? 88 : 60))
        height: control.height
        verticalAlignment: Text.AlignVCenter
        font.pixelSize: 17
        font.weight: 57
        color: Theme.color.onSurfaceContainer
        enabled: control.enabled
        onAccepted: control.accepted()
    }

    MouseArea {
        width: clearAction.visible ? parent.width - 44 : parent.width
        height: parent.height
        enabled: control.enabled && !field.activeFocus
        onClicked: field.forceActiveFocus()
    }

    IconButton {
        id: clearAction
        anchors.right: parent.right
        anchors.rightMargin: 6
        anchors.verticalCenter: parent.verticalCenter
        width: 32
        height: 32
        icon: "close"
        visible: control.clearButtonEnabled && field.text.length > 0
        enabled: control.enabled
        onClicked: control.clear()
    }
}
