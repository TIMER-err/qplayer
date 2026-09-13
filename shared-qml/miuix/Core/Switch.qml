import QtQuick
import QtQuick.Layouts
import miuix.Core
Item {
    id: control

    property bool checked: false
    property string text: ""
    property bool enabled: true
    property bool showIcon: false
    property string icon: "check"
    property bool toggleOnClick: true

    signal clicked()
    activeFocusOnTab: true
    function activate() {
        if (!enabled) return
        if (toggleOnClick) { checked = !checked; }
        clicked()
    }
    Keys.onSpacePressed: { activate(); event.accepted = true }
    Keys.onReturnPressed: { activate(); event.accepted = true }

    implicitWidth: rowLayout.implicitWidth
    implicitHeight: Math.max(28, rowLayout.implicitHeight)

    property var _colors: Theme.color

    RowLayout {
        id: rowLayout
        anchors.centerIn: parent
        spacing: 12

        Item {
            implicitWidth: 49
            implicitHeight: 28

            Rectangle {
                anchors.fill: parent
                radius: height / 2
                color: {
                    if (!control.enabled)
                        return control.checked ? _colors.disabledPrimary : _colors.disabledSecondary
                    return control.checked ? _colors.primary : _colors.secondary
                }
                Behavior on color { ColorAnimation { duration: 180; easing.type: Easing.OutCubic } }
            }

            Rectangle {
                id: thumb
                width: 20
                height: 20
                radius: 10
                anchors.verticalCenter: parent.verticalCenter
                x: control.checked ? 25 : 4
                scale: hit.pressed && control.enabled ? 1.127 : 1
                color: {
                    if (!control.enabled)
                        return control.checked ? _colors.disabledOnPrimary : _colors.disabledOnSecondary
                    return "#ffffff"
                }
                Icon {
                    anchors.centerIn: parent
                    width: 12
                    height: 12
                    visible: control.showIcon && control.checked
                    name: control.icon
                    color: control.enabled ? _colors.primary : _colors.disabledPrimary
                }
                Behavior on x { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                Behavior on color { ColorAnimation { duration: 180 } }
            }

            MouseArea {
                id: hit
                anchors.fill: parent
                enabled: control.enabled
                onClicked: {
                    if (control.toggleOnClick) control.checked = !control.checked
                    control.clicked()
                }
            }
        }

        Text {
            text: control.text
            visible: control.text.length > 0
            font.family: Theme.typography.labelLarge.family
            font.pixelSize: Theme.typography.labelLarge.size
            font.weight: Theme.typography.labelLarge.weight
            color: control.enabled ? _colors.onSurfaceColor : _colors.disabledOnSecondaryVariant
            Layout.fillWidth: true

            MouseArea {
                anchors.fill: parent
                enabled: control.enabled
                onClicked: {
                    if (control.toggleOnClick) control.checked = !control.checked
                    control.clicked()
                }
            }
        }
    }
}
