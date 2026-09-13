import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// Host-owned settings surface for one plugin. No third-party QML exists anywhere:
// a declared settings contribution opens the shared PluginDialog, which QPlayer
// renders from the plugin's validated description.
Rectangle {
    id: page
    signal back()
    signal home()

    required property string pluginId
    color: Theme.color.surface

    property var pluginData: {
        var rows = player.sourcePlugins || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].id === page.pluginId) return rows[i]
        }
        return null
    }
    property var catalogData: {
        var rows = player.pluginCatalogEntries || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].id === page.pluginId) return rows[i]
        }
        return null
    }
    property var settingsContributions: {
        var out = []
        var rows = player.pluginUiContributions || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].pluginId === page.pluginId && rows[i].placement === "settings")
                out.push(rows[i])
        }
        return out
    }
    property string displayName: pluginData ? pluginData.name
                                 : (catalogData ? catalogData.name
                                    : i18n.t("plugin.page.fallbackName"))

    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4

            PageHeaderButtons {
                Layout.alignment: Qt.AlignVCenter
                onHome: page.home()
                onBack: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: page.displayName
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: width
            contentHeight: content.implicitHeight + 24

            ColumnLayout {
                id: content
                width: Math.min(904, parent.width)
                x: (parent.width - width) / 2
                spacing: 16

                SettingCard {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    Layout.topMargin: 6
                    visible: page.pluginData !== null || page.catalogData !== null

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            text: "extension"
                            font.family: Theme.iconFont.name
                            font.pixelSize: 28
                            color: Theme.color.primary
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            SettingTitle { text: page.displayName }
                            SettingDesc {
                                text: page.pluginId + " · "
                                      + (page.pluginData ? page.pluginData.version
                                         : page.catalogData.version)
                            }
                        }
                    }
                    SettingDesc {
                        visible: page.catalogData !== null
                                 && page.catalogData.description.length > 0
                        text: page.catalogData ? page.catalogData.description : ""
                    }
                    SettingDesc {
                        visible: page.pluginData !== null
                        text: page.pluginData
                              ? (i18n.t(page.pluginData.signed ? "plugin.page.signed"
                                        : "plugin.page.unsigned")
                                 + "\n" + i18n.t("plugin.entry.permissions",
                                          page.pluginData.permissions.length > 0
                                          ? page.pluginData.permissions
                                          : i18n.t("plugin.entry.noPermissions")))
                              : ""
                    }
                }

                SettingCard {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: page.pluginData === null && page.catalogData !== null

                    SettingTitle { text: i18n.t("plugin.page.install.title") }
                    SettingDesc { text: i18n.t("plugin.page.install.desc") }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        type: "filled"
                        icon: "download"
                        text: i18n.t(player.pluginInstallBusy ? "plugin.page.install.busy"
                                     : "plugin.page.install.button")
                        enabled: !player.pluginInstallBusy
                        onClicked: player.installCatalogPlugin(page.pluginId)
                    }
                }

                SettingCard {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: page.pluginData !== null

                    RowLayout {
                        Layout.fillWidth: true
                        SettingTitle {
                            Layout.fillWidth: true
                            text: i18n.t("plugin.page.enable.title")
                        }
                        Switch {
                            checked: page.pluginData ? page.pluginData.enabled : false
                            onClicked: player.setSourcePluginEnabled(page.pluginId, checked)
                        }
                    }
                    SettingDesc {
                        text: i18n.t(page.pluginData && page.pluginData.enabled
                                     ? "plugin.page.enable.on" : "plugin.page.enable.off")
                    }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        visible: page.pluginData && page.pluginData.enabled
                        enabled: page.pluginData && !page.pluginData.primary
                                 && !player.pluginInstallBusy
                        type: page.pluginData && page.pluginData.primary
                              ? "filledTonal" : "outlined"
                        icon: page.pluginData && page.pluginData.primary ? "check" : "star"
                        text: i18n.t(page.pluginData && page.pluginData.primary
                                     ? "plugin.page.primary.current"
                                     : "plugin.page.primary.set")
                        onClicked: player.setPrimarySourcePlugin(page.pluginId)
                    }
                }

                SettingCard {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: page.pluginData !== null && page.catalogData !== null
                             && page.catalogData.updateAvailable

                    SettingTitle {
                        text: i18n.t("plugin.page.update.title",
                                     page.catalogData ? page.catalogData.version : "")
                    }
                    SettingDesc { text: i18n.t("plugin.page.update.desc") }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        type: "filledTonal"
                        icon: "update"
                        text: i18n.t(player.pluginInstallBusy ? "plugin.page.install.busy"
                                     : "plugin.page.update.button")
                        enabled: !player.pluginInstallBusy
                        onClicked: player.installCatalogPlugin(page.pluginId)
                    }
                }

                Card {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: page.settingsContributions.length > 0
                    implicitHeight: contributions.implicitHeight
                    ColumnLayout {
                        id: contributions
                        width: parent.width
                        spacing: 0
                        Repeater {
                            model: page.settingsContributions
                            SuperArrow {
                                Layout.fillWidth: true
                                title: i18n.t("plugin.page.settings.title")
                                summary: modelData.id
                                rightText: i18n.t("plugin.page.settings.open")
                                onClicked: player.requestPluginUi(modelData.pluginId, modelData.id)
                            }
                        }
                    }
                }

                SettingCard {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: page.pluginData !== null

                    SettingTitle { text: i18n.t("plugin.page.remove.title") }
                    SettingDesc { text: i18n.t("plugin.page.remove.desc") }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        type: "outlined"
                        icon: "delete"
                        text: i18n.t("plugin.page.remove.button")
                        enabled: !player.pluginInstallBusy
                        onClicked: player.requestSourcePluginRemoval(page.pluginId)
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 48
                    visible: page.pluginData === null && page.catalogData === null
                    spacing: 12
                    Text {
                        Layout.alignment: Qt.AlignHCenter
                        text: i18n.t("plugin.page.missing")
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 15
                    }
                    Button {
                        Layout.alignment: Qt.AlignHCenter
                        type: "filledTonal"
                        text: i18n.t("common.back")
                        onClicked: page.back()
                    }
                }
            }
        }
    }
}
