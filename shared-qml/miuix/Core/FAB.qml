import QtQuick
import QtQuick.Effects
import QtQuick.Layouts
import miuix.Core
Item {
    id: root

    property string icon: "add"
    property string text: ""
    property string type: "standard"
    property color containerColor: Theme.color.primary
    property color contentColor: Theme.color.onPrimary
    signal clicked()
    activeFocusOnTab: true
    Keys.onReturnPressed: { if (enabled) clicked(); event.accepted = true }
    Keys.onSpacePressed: { if (enabled) clicked(); event.accepted = true }

    property int fabSize: {
        switch (type) {
            case "small": return 40
            case "large": return 72
            default: return 60
        }
    }
    property int fabRadius: fabSize / 2
    property int iconSize: type === "large" ? 32 : 24
    property int elevationLevel: 4
    opacity: enabled ? 1 : 0.38

    implicitWidth: type === "extended" ? (rowLayout.implicitWidth + 32) : fabSize
    implicitHeight: fabSize

    Rectangle {
        id: shadowSource
        anchors.fill: parent
        radius: root.fabRadius
        color: root.containerColor
        visible: false
    }

    MultiEffect {
        anchors.fill: shadowSource
        source: shadowSource
        visible: true
        z: -1
        shadowEnabled: true
        shadowColor: Theme.color.shadow
        shadowBlur: 0.8
        shadowVerticalOffset: 4
        shadowOpacity: 0.18
    }

    Rectangle {
        anchors.fill: parent
        radius: root.fabRadius
        color: root.containerColor


    }

    Ripple {
        id: mouseArea
        anchors.fill: parent
        clipRadius: root.fabRadius
        rippleColor: root.contentColor
        enabled: root.enabled
        onClicked: { root.forceActiveFocus(); root.clicked() }
    }

    RowLayout {
        id: rowLayout
        anchors.centerIn: parent
        spacing: 8
        Icon {
            visible: root.icon !== ""
            name: root.icon
            width: root.iconSize
            height: root.iconSize
            color: root.contentColor
        }
        Text {
            visible: root.type === "extended" && root.text !== ""
            text: root.text
            font.pixelSize: 17
            color: root.contentColor
        }
    }
}
