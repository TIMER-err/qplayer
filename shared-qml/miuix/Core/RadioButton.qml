// Check geometry adapted from basic/RadioButton.kt (Apache-2.0).
import QtQuick
import QtQuick.Shapes
import miuix.Core

Item {
    id: radioRoot
    property bool checked: false
    property string text: ""
    signal clicked()
    activeFocusOnTab: true
    Keys.onReturnPressed: { if (enabled) clicked(); event.accepted = true }
    Keys.onSpacePressed: { if (enabled) clicked(); event.accepted = true }
    implicitWidth: 26 + (text.length > 0 ? 12 + label.implicitWidth : 0)
    implicitHeight: Math.max(26, label.implicitHeight)

    Shape {
        id: mark
        objectName: "miuixRadioMark"
        width: 56
        height: 56
        x: -15
        y: (radioRoot.height - height) / 2
        scale: 26 / 56 * (hitArea.pressed ? 0.85 : 1)
        opacity: radioRoot.checked ? 1 : 0
        property real progress: radioRoot.checked ? 1 : 0
        property bool ready: false
        onProgressChanged: updateCheck()
        Component.onCompleted: { ready = true; updateCheck() }
        Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
        Behavior on opacity { NumberAnimation { duration: radioRoot.checked ? 10 : 150; easing.type: Easing.InOutCubic } }
        Behavior on progress { NumberAnimation { duration: 300; easing.type: Easing.InOutCubic } }
        function updateCheck() {
            if (!ready) return
            var first = Math.sqrt(12.2 * 12.2 + 11.8 * 11.8)
            var second = Math.sqrt(20.9 * 20.9 + 24.8 * 24.8)
            var distance = progress * (first + second)
            var a = Math.min(1, distance / first)
            var b = Math.max(0, Math.min(1, (distance - first) / second))
            shortStroke.x = 10.9 + 12.2 * a
            shortStroke.y = 29 + 11.8 * a
            longStroke.x = distance <= first ? shortStroke.x : 23.1 + 20.9 * b
            longStroke.y = distance <= first ? shortStroke.y : 40.8 - 24.8 * b
        }
        ShapePath {
            startX: 10.9
            startY: 29
            fillColor: "transparent"
            strokeColor: radioRoot.enabled ? Theme.color.primary : Theme.color.disabledPrimary
            strokeWidth: 7
            capStyle: "RoundCap"
            joinStyle: "RoundJoin"
            PathLine { id: shortStroke }
            PathLine { id: longStroke }
        }
    }
    Text {
        id: label
        x: 38
        width: Math.max(0, radioRoot.width - x)
        anchors.verticalCenter: parent.verticalCenter
        visible: radioRoot.text.length > 0
        text: radioRoot.text
        wrapMode: Text.Wrap
        font.pixelSize: 17
        color: radioRoot.enabled ? Theme.color.onSurfaceColor : Theme.color.disabledOnSecondaryVariant
    }
    MouseArea {
        id: hitArea
        anchors.fill: parent
        enabled: radioRoot.enabled
        onClicked: { radioRoot.forceActiveFocus(); radioRoot.clicked() }
    }
}
