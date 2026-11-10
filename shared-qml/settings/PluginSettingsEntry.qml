import QtQuick
import miuix.Core
import design
import "."

// One installed plugin on the settings Plugins tab: opens its settings page.
Item {
    id: row
    property var pluginData: null
    property bool separator: false

    implicitHeight: frame.implicitHeight

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        icon: "extension"
        iconColor: row.pluginData && row.pluginData.enabled ? Ui.accent : Ui.textTertiary
        title: row.pluginData ? row.pluginData.name : ""
        summary: row.pluginData
                 ? row.pluginData.version + " · " + i18n.t(row.pluginData.primary ? "plugin.entry.primary"
                       : row.pluginData.enabled ? "plugin.entry.enabled" : "plugin.entry.disabled")
                 : ""
        separator: row.separator
        value: i18n.t("plugin.entry.settings")
        indicator: "chevron_right"
        onClicked: if (row.pluginData) player.requestPluginSettings(row.pluginData.id)
    }
}
