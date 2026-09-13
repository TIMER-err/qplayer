import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// SettingSpec.RADIO — an int index shown as a radio group (background motion:
// animated/static).
ColumnLayout {
    id: row
    property var spec: null
    spacing: 4

    SettingTitle { text: row.spec ? i18n.t(row.spec.title) : "" }
    SettingDesc { text: row.spec ? i18n.t(row.spec.desc) : "" }
    ColumnLayout {
        Layout.fillWidth: true
        Layout.topMargin: 4
        spacing: 8
        Repeater {
            model: row.spec ? row.spec.options : []
            RadioButton {
                objectName: "settingRadioOption" + index
                Layout.fillWidth: true
                text: i18n.t(modelData)
                checked: settings.value(row.spec.key) === index
                onClicked: settings.setValue(row.spec.key, index)
            }
        }

    }
}
