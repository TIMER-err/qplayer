import QtQuick
import miuix.Core

SuperSwitch {
    id: row
    property var spec: null
    objectName: spec ? "setting_" + spec.key : ""
    title: spec ? i18n.t(spec.title) : ""
    summary: spec ? i18n.t(spec.desc) : ""
    // SuperSwitch owns its immediate click state; mirror later host updates too.
    property bool storedChecked: spec ? settings.value(spec.key) === true : false
    checked: storedChecked
    onStoredCheckedChanged: checked = storedChecked
    onClicked: settings.setValue(spec.key, checked)
}
