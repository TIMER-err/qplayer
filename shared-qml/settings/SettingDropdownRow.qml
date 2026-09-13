import QtQuick
import miuix.Core

SuperDropdown {
    id: row
    property var spec: null
    objectName: spec ? "setting_" + spec.key : ""
    title: spec ? i18n.t(spec.title) : ""
    summary: spec ? i18n.t(spec.desc) : ""
    items: {
        var out = []
        if (row.spec) for (var i = 0; i < row.spec.options.length; i++)
            out.push(i18n.t(row.spec.options[i]))
        return out
    }
    selectOnClick: false
    currentIndex: spec ? settings.value(spec.key) : -1
    onActivated: (index) => settings.setValue(row.spec.key, index)
}
