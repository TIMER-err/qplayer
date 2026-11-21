import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.DROPDOWN: the current choice sits on the right; tapping the row
// opens miuix SuperDropdown's popup. The SuperDropdown is only the popup engine:
// it covers the row fully transparent and underneath the frame (whose MouseArea
// takes the pointer), so its own row chrome is never seen. Its popup reparents to
// the scene root when open, so it is unaffected by the engine's opacity.
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    property var labels: {
        var out = []
        if (row.spec) for (var i = 0; i < row.spec.options.length; i++)
            out.push(i18n.t(row.spec.options[i]))
        return out
    }
    property int current: spec ? settings.value(spec.key) : -1

    implicitHeight: frame.implicitHeight

    SuperDropdown {
        id: engine
        objectName: "settingDropdownEngine"
        width: row.width
        height: row.height
        opacity: 0
        items: row.labels
        selectOnClick: false
        currentIndex: row.current
        onActivated: (index) => settings.setValue(row.spec.key, index)
    }

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.spec ? i18n.t(row.spec.desc) : ""
        separator: row.separator
        value: row.current >= 0 && row.current < row.labels.length ? row.labels[row.current] : ""
        indicator: "unfold_more"
        onClicked: engine.openMenu()
    }
}
