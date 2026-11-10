import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "."

// The content of one settings category: the catalog's groups plus the few
// host-owned blocks (credential protection on About, the plugin lists on
// Plugins). Every block is a direct child of this one ColumnLayout, and every
// block positions its own internals explicitly (no nested fillWidth chains).
ColumnLayout {
    id: panel
    property string category: ""
    property var groups: []
    // Wide master-detail shows the category name as the panel's large title.
    property bool showTitle: false

    property bool pluginsTab: panel.category === "plugins"
    property var installedPlugins: player.sourcePlugins || []
    property var availablePlugins: {
        var out = []
        var rows = player.pluginCatalogEntries || []
        for (var i = 0; i < rows.length; i++) {
            if (!rows[i].installed) out.push(rows[i])
        }
        return out
    }
    readonly property real captionHeight: 30

    spacing: Ui.space6

    Text {
        objectName: "settingsCategoryTitle"
        Layout.fillWidth: true
        Layout.leftMargin: Ui.space1
        visible: panel.showTitle
        text: panel.category.length > 0 ? i18n.t("settings.category." + panel.category) : ""
        color: Ui.textPrimary
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textTitle1
        font.weight: Ui.weightBold
    }

    // ---- About: credential protection fallback ---------------------------------
    SettingCard {
        objectName: "settingsCredentialCard"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.category === "about" && player.credentialOwnerOnlyFallback

        Item {
            id: credentialRow
            Layout.fillWidth: true
            implicitHeight: credentialFrame.implicitHeight
            SettingRowFrame {
                id: credentialFrame
                width: credentialRow.width
                height: credentialRow.height
                icon: "enhanced_encryption"
                iconColor: Ui.accent
                title: i18n.t("settings.credential.title")
                summary: i18n.t("settings.credential.desc")
                clickable: false
                controlWidth: credentialButton.width
            }
            Button {
                id: credentialButton
                x: credentialRow.width - Ui.space4 - width
                y: (credentialRow.height - height) / 2
                type: "filledTonal"
                enabled: !player.credentialProtectionBusy
                text: i18n.t(player.credentialProtectionBusy
                             ? "settings.credential.busy"
                             : "settings.credential.button")
                onClicked: player.reenableSystemCredentialProtection()
            }
        }
    }

    // ---- Plugins: installed -----------------------------------------------------
    Item {
        objectName: "settingsInstalledPlugins"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.pluginsTab
        implicitHeight: panel.captionHeight + installedCard.height

        SettingCaption {
            x: Ui.space4
            y: 2
            width: Math.max(0, parent.width - Ui.space8)
            text: i18n.t("plugin.installed")
        }
        SettingCard {
            id: installedCard
            y: panel.captionHeight
            width: parent.width
            height: implicitHeight

            Repeater {
                model: panel.pluginsTab ? panel.installedPlugins : null
                PluginSettingsEntry {
                    Layout.fillWidth: true
                    pluginData: modelData
                    separator: index > 0
                }
            }
            Item {
                id: noPluginsRow
                Layout.fillWidth: true
                visible: panel.installedPlugins.length === 0
                implicitHeight: noPluginsFrame.implicitHeight
                SettingRowFrame {
                    id: noPluginsFrame
                    width: noPluginsRow.width
                    height: noPluginsRow.height
                    icon: "extension_off"
                    title: i18n.t("plugin.none.title")
                    summary: i18n.t("plugin.none.desc")
                    clickable: false
                }
            }
        }
    }

    // ---- Plugins: catalog -------------------------------------------------------
    Item {
        objectName: "settingsAvailablePlugins"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.pluginsTab
                 && (panel.availablePlugins.length > 0 || player.pluginCatalogLoading)
        implicitHeight: panel.captionHeight + availableCard.height

        SettingCaption {
            x: Ui.space4
            y: 2
            width: Math.max(0, parent.width - Ui.space8)
            text: i18n.t("plugin.available")
        }
        SettingCard {
            id: availableCard
            y: panel.captionHeight
            width: parent.width
            height: implicitHeight

            Repeater {
                model: panel.pluginsTab ? panel.availablePlugins : null
                PluginCatalogEntry {
                    Layout.fillWidth: true
                    pluginData: modelData
                    separator: index > 0
                }
            }
            Item {
                id: catalogLoadingRow
                Layout.fillWidth: true
                visible: player.pluginCatalogLoading
                implicitHeight: 56
                LoadingIndicator {
                    x: Ui.space4
                    y: (catalogLoadingRow.height - height) / 2
                    running: player.pluginCatalogLoading && catalogLoadingRow.visible
                }
                Text {
                    x: Ui.space4 + Ui.iconSize + 14
                    y: (catalogLoadingRow.height - implicitHeight) / 2
                    width: Math.max(0, catalogLoadingRow.width - x - Ui.space4)
                    text: i18n.t("plugin.catalog.loading")
                    color: Ui.textSecondary
                    elide: Text.ElideRight
                    font.pixelSize: Ui.textCallout
                }
            }
        }
    }

    // ---- Plugins: import / refresh ----------------------------------------------
    SettingCard {
        objectName: "settingsPluginActions"
        Layout.fillWidth: true
        Layout.preferredHeight: implicitHeight
        visible: panel.pluginsTab

        Item {
            id: importRow
            Layout.fillWidth: true
            implicitHeight: importFrame.implicitHeight
            SettingRowFrame {
                id: importFrame
                width: importRow.width
                height: importRow.height
                icon: "upload_file"
                title: i18n.t("plugin.catalog.import")
                summary: i18n.t("plugin.catalog.desc")
                indicator: "chevron_right"
                rowEnabled: !player.pluginInstallBusy
                onClicked: player.requestPluginImport()
            }
        }
        Item {
            id: refreshRow
            Layout.fillWidth: true
            implicitHeight: refreshFrame.implicitHeight
            SettingRowFrame {
                id: refreshFrame
                width: refreshRow.width
                height: refreshRow.height
                icon: "refresh"
                title: i18n.t("plugin.catalog.refresh")
                separator: true
                indicator: "chevron_right"
                rowEnabled: !player.pluginCatalogLoading && !player.pluginInstallBusy
                onClicked: player.refreshPluginCatalog()
            }
        }
    }

    // ---- Catalog groups -----------------------------------------------------------
    Repeater {
        model: panel.groups
        SettingGroup {
            Layout.fillWidth: true
            Layout.preferredHeight: implicitHeight
            groupData: modelData
        }
    }
}
