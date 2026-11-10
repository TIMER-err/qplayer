import QtQuick
import miuix.Core
import design
import "."

// One installable catalog plugin on the settings Plugins tab: the row and its
// button both open the plugin's page, where installation is confirmed.
Item {
    id: row
    property var pluginData: null
    property bool separator: false

    implicitHeight: frame.implicitHeight

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        icon: "download"
        iconColor: Ui.accent
        title: row.pluginData ? row.pluginData.name : ""
        summary: row.pluginData ? row.pluginData.version + " · " + row.pluginData.description : ""
        separator: row.separator
        controlWidth: viewButton.width
        onClicked: if (row.pluginData) player.requestPluginSettings(row.pluginData.id)
    }

    Button {
        id: viewButton
        x: row.width - Ui.space4 - width
        y: (row.height - height) / 2
        type: "filledTonal"
        text: i18n.t("plugin.catalog.view")
        onClicked: if (row.pluginData) player.requestPluginSettings(row.pluginData.id)
    }
}
