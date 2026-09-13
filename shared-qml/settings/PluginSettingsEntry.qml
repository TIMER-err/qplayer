import QtQuick
import miuix.Core

SuperArrow {
    required property var pluginData
    title: pluginData.name
    summary: pluginData.version + " · " + i18n.t(pluginData.primary ? "plugin.entry.primary"
        : pluginData.enabled ? "plugin.entry.enabled" : "plugin.entry.disabled")
    rightText: i18n.t("plugin.entry.settings")
    onClicked: player.requestPluginSettings(pluginData.id)
}
