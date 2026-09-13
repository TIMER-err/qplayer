import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// SettingSpec.SEGMENTED — an int index over the spec's own labels.
ColumnLayout {
    id: row
    property var spec: null
    property var labels: {
        var out = []
        if (row.spec) for (var i = 0; i < row.spec.options.length; i++)
            out.push(i18n.t(row.spec.options[i]))
        return out
    }
    spacing: 4

    SettingTitle { text: row.spec ? i18n.t(row.spec.title) : "" }
    SettingDesc { text: row.spec ? i18n.t(row.spec.desc) : "" }
    TabRowWithContour {
        Layout.fillWidth: true
        Layout.topMargin: 4
        tabs: row.labels
        equalWidth: false
        selectOnClick: false
        selectedTabIndex: row.spec ? settings.value(row.spec.key) : 0
        onTabSelected: (index) => settings.setValue(row.spec.key, index)
    }
}
