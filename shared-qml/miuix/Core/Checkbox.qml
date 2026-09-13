import QtQuick
import QtQuick.Layouts
import miuix.Core
Item {
    id: control

    property bool checked: false
    property bool indeterminate: false
    property string text: ""
    property bool enabled: true
    property bool toggleOnClick: true
    readonly property bool _visualChecked: checked || indeterminate
    signal clicked()
    activeFocusOnTab: true
    function activate() {
        if (!enabled) return
        if (toggleOnClick) { checked = !checked; indeterminate = false; }
        clicked()
    }
    Keys.onSpacePressed: { activate(); event.accepted = true }
    Keys.onReturnPressed: { activate(); event.accepted = true }

    property var _colors: Theme.color

    implicitWidth: rowLayout.implicitWidth
    implicitHeight: Math.max(26, rowLayout.implicitHeight)

    RowLayout {
        id: rowLayout
        anchors.verticalCenter: parent.verticalCenter
        anchors.left: parent.left
        spacing: 12

        Rectangle {
            implicitWidth: 26
            implicitHeight: 26
            width: 26
            height: 26
            radius: 13
            color: {
                if (!control.enabled)
                    return control._visualChecked ? _colors.disabledPrimary : _colors.disabledSecondary
                return control._visualChecked ? _colors.primary : _colors.secondary
            }
            Behavior on color { ColorAnimation { duration: 180; easing.type: Easing.OutCubic } }

            Icon {
                anchors.centerIn: parent
                name: control.indeterminate ? "remove" : "check"
                width: 16
                height: 16
                color: control.enabled ? _colors.onPrimary : _colors.disabledOnPrimary
                opacity: control._visualChecked ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 80 } }
            }

            MouseArea {
                anchors.fill: parent
                enabled: control.enabled
                onClicked: {
                    if (control.toggleOnClick) {
                        control.checked = !control.checked
                        control.indeterminate = false
                    }
                    control.clicked()
                }
            }
        }

        Text {
            text: control.text
            visible: control.text.length > 0
            font.family: Theme.typography.labelLarge.family
            font.pixelSize: 17
            color: control.enabled ? _colors.onSurfaceColor : _colors.disabledOnSecondaryVariant
            MouseArea {
                anchors.fill: parent
                enabled: control.enabled
                onClicked: {
                    if (control.toggleOnClick) {
                        control.checked = !control.checked
                        control.indeterminate = false
                    }
                    control.clicked()
                }
            }
        }
    }
}
