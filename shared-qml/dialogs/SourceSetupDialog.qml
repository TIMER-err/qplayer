import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "../components"
import "../settings"

// First-run/upgrade source onboarding. QPlayer itself remains source-neutral;
// this dialog only surfaces the built-in source repositories and the generic
// package picker. Older credentials are handed to the installed plugin by the core's
// delayed migration bridge after that plugin has started successfully.
Item {
    id: root

    readonly property bool opened: setupDialog.opened
    function open() { setupDialog.open() }
    function close() { setupDialog.close() }

    property bool sourceReadyWatch: !player.sourceSetupRequired
    onSourceReadyWatchChanged: if (sourceReadyWatch && setupDialog.opened) setupDialog.close()

    readonly property bool catalogEmpty: !player.pluginCatalogEntries
                                         || player.pluginCatalogEntries.length === 0

    Dialog {
        id: setupDialog
        objectName: "sourceSetupDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t(player.legacySourceMigrationAvailable ? "source.setup.migrate.title"
                      : "source.setup.title")
        icon: player.legacySourceMigrationAvailable ? "move_up" : "extension"
        closeOnScrim: false
        showAcceptButton: false
        rejectText: i18n.t("source.setup.localOnly")
        text: player.legacySourceMigrationAvailable
            ? i18n.t("source.setup.migrate.body")
            : i18n.t("source.setup.body")
        onRejected: player.dismissSourceSetup()

        SettingCard {
            width: setupDialog.contentWidth
            height: implicitHeight

            Item {
                id: loadingRow
                Layout.fillWidth: true
                visible: player.pluginCatalogLoading
                implicitHeight: 56
                Glyph {
                    x: Ui.space4
                    y: (loadingRow.height - height) / 2
                    name: "sync"
                    color: Ui.accent
                    NumberAnimation on rotation {
                        running: player.pluginCatalogLoading && loadingRow.visible
                        loops: Animation.Infinite
                        from: 360; to: 0
                        duration: 700
                    }
                }
                Text {
                    x: Ui.space4 + Ui.iconSize + 14
                    y: (loadingRow.height - implicitHeight) / 2
                    width: Math.max(0, loadingRow.width - x - Ui.space4)
                    text: i18n.t("plugin.catalog.loading")
                    color: Ui.textSecondary
                    elide: Text.ElideRight
                    font.pixelSize: Ui.textCallout
                }
            }

            Repeater {
                model: player.pluginCatalogEntries
                Item {
                    id: catalogRow
                    objectName: "sourceSetupEntry"
                    Layout.fillWidth: true
                    // Stacked: the description wraps over the full width and the
                    // action sits underneath, right-aligned (the dialog is narrow).
                    implicitHeight: catalogFrame.implicitHeight + catalogButton.height + Ui.space4
                    SettingRowFrame {
                        id: catalogFrame
                        width: catalogRow.width
                        height: implicitHeight
                        icon: modelData.installed ? "extension" : "download"
                        iconColor: Ui.accent
                        title: modelData.name + "  " + modelData.version
                        summary: modelData.description
                        separator: index > 0 || player.pluginCatalogLoading
                        clickable: false
                        bottomPadding: Ui.space2
                    }
                    Button {
                        id: catalogButton
                        x: catalogRow.width - Ui.space4 - width
                        y: catalogFrame.height
                        text: player.legacySourceMigrationAvailable
                              ? i18n.t(modelData.installed
                                       ? "source.setup.enableMigrate"
                                       : "source.setup.installMigrate")
                              : i18n.t(modelData.installed
                                       ? "source.setup.enablePrimary"
                                       : "plugin.page.install.button")
                        type: modelData.installed ? "filledTonal" : "filled"
                        cornerRadius: Ui.radiusFull
                        enabled: !player.pluginInstallBusy
                        onClicked: {
                            if (modelData.installed) player.activateSourcePlugin(modelData.id)
                            else player.installCatalogPlugin(modelData.id)
                        }
                    }
                }
            }

            Item {
                id: failedRow
                Layout.fillWidth: true
                visible: !player.pluginCatalogLoading && root.catalogEmpty
                implicitHeight: failedFrame.implicitHeight
                SettingRowFrame {
                    id: failedFrame
                    width: failedRow.width
                    height: failedRow.height
                    icon: "cloud_off"
                    iconColor: Ui.danger
                    title: i18n.t("source.setup.retry")
                    summary: i18n.t("source.setup.catalogFailed")
                    indicator: "chevron_right"
                    rowEnabled: !player.pluginInstallBusy
                    onClicked: player.refreshPluginCatalog()
                }
            }

            Item {
                id: importRow
                Layout.fillWidth: true
                implicitHeight: importFrame.implicitHeight
                SettingRowFrame {
                    id: importFrame
                    width: importRow.width
                    height: importRow.height
                    icon: "upload_file"
                    title: i18n.t("source.setup.import")
                    separator: true
                    indicator: "chevron_right"
                    rowEnabled: !player.pluginInstallBusy
                    onClicked: player.requestPluginImport()
                }
            }
        }
    }
}
