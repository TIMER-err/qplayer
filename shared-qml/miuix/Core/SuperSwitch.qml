import QtQuick
import miuix.Core

Item {
    id: superSwitchRoot
    property string title: ""
    property string summary: ""
    property bool checked: false
    property bool enabled: true
    signal clicked()
    width: parent ? parent.width : 320
    implicitHeight: preference.implicitHeight

    function toggle() {
        if (!superSwitchRoot.enabled) return
        superSwitchRoot.checked = !superSwitchRoot.checked
        superSwitchRoot.clicked()
    }

    BasicComponent {
        id: preference
        anchors.fill: parent
        title: superSwitchRoot.title
        summary: superSwitchRoot.summary
        enabled: superSwitchRoot.enabled
        onClicked: superSwitchRoot.toggle()
        endAction: Component {
            Switch {
                checked: superSwitchRoot.checked
                toggleOnClick: false
                enabled: superSwitchRoot.enabled
                onClicked: superSwitchRoot.toggle()
            }
        }
    }
}
