import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.PATH: the chosen directory replaces the description once set.
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    property string selectedPath: spec ? String(settings.value(spec.key) || "") : ""

    implicitHeight: frame.implicitHeight

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        icon: "folder_open"
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.selectedPath.length > 0 ? row.selectedPath
                 : (row.spec ? i18n.t(row.spec.desc) : "")
        separator: row.separator
        value: i18n.t("settings.folder.choose")
        indicator: "chevron_right"
        onClicked: if (row.spec) settings.pickDirectory(row.spec.key)
    }
}
