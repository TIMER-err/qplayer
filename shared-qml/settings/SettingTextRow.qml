import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// SettingSpec.TEXT — a string, committed on Enter or via the apply button (never per
// keystroke: these are URLs and JSON paths, and every write rebuilds the
// custom-API config).
ColumnLayout {
    id: row
    property var spec: null
    spacing: 4

    SettingTitle { text: row.spec ? i18n.t(row.spec.title) : "" }
    SettingDesc { text: row.spec ? i18n.t(row.spec.desc) : "" }
    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        TextField {
            id: field
            Layout.fillWidth: true
            type: "outlined"
            label: row.spec ? i18n.t(row.spec.hint) : ""
            text: row.spec ? settings.value(row.spec.key) : ""
            onAccepted: settings.setValue(row.spec.key, field.text)
        }
        Button {
            type: "filledTonal"; text: i18n.t("common.apply")
            onClicked: settings.setValue(row.spec.key, field.text)
        }
    }
}
