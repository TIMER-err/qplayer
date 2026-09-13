import QtQuick
import miuix.Core

Item {
    id: superCheckboxRoot
    property string title: ""
    property string summary: ""
    property bool checked: false
    property bool enabled: true
    property bool indeterminate: false
    signal clicked()
    width: parent ? parent.width : 320
    implicitHeight: preference.implicitHeight

    function toggle() {
        if (!superCheckboxRoot.enabled) return
        superCheckboxRoot.checked = !superCheckboxRoot.checked
        superCheckboxRoot.indeterminate = false
        superCheckboxRoot.clicked()
    }

    BasicComponent {
        id: preference
        anchors.fill: parent
        title: superCheckboxRoot.title
        summary: superCheckboxRoot.summary
        enabled: superCheckboxRoot.enabled
        onClicked: superCheckboxRoot.toggle()
        startAction: Component {
            Checkbox {
                checked: superCheckboxRoot.checked
                indeterminate: superCheckboxRoot.indeterminate
                toggleOnClick: false
                enabled: superCheckboxRoot.enabled
                onClicked: superCheckboxRoot.toggle()
            }
        }
    }
}
