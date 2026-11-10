import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.TOGGLE: the whole row toggles; the miuix Switch mirrors it.
// accessory "swatch" previews the Monet seed colour next to the switch.
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    // The Switch's own click assigns `checked`; keep the host value separate so
    // later host updates (another surface writing the key) still land.
    property bool storedChecked: spec ? settings.value(spec.key) === true : false
    property bool checked: storedChecked
    onStoredCheckedChanged: checked = storedChecked
    readonly property bool hasSwatch: spec ? spec.accessory === "swatch" : false

    function toggle() {
        if (!row.spec) return
        row.checked = !row.checked
        settings.setValue(row.spec.key, row.checked)
    }

    implicitHeight: frame.implicitHeight

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.spec ? i18n.t(row.spec.desc) : ""
        separator: row.separator
        controlWidth: switchControl.width + (row.hasSwatch ? swatch.width + Ui.space3 : 0)
        onClicked: row.toggle()
    }

    Rectangle {
        id: swatch
        objectName: "settingSwitchSwatch"
        visible: row.hasSwatch
        width: 20
        height: 20
        radius: 10
        x: switchControl.x - Ui.space3 - width
        y: (row.height - height) / 2
        color: Theme.color.primary
        border.width: 1
        border.color: Ui.hairline
    }

    Switch {
        id: switchControl
        objectName: "settingSwitchControl"
        x: row.width - Ui.space4 - width
        y: (row.height - height) / 2
        checked: row.checked
        toggleOnClick: false
        onClicked: row.toggle()
    }
}
