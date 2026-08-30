import QtQuick
import QtQuick.Layouts
import md3.Core

// First-run/upgrade source onboarding. QPlayer itself remains source-neutral;
// this dialog only surfaces the signed external catalog and the generic package
// picker. Older credentials are handed to the installed plugin by the core's
// delayed migration bridge after that plugin has started successfully.
Item {
    id: root

    readonly property bool opened: setupDialog.opened
    function open() { setupDialog.open() }
    function close() { setupDialog.close() }

    property bool sourceReadyWatch: !player.sourceSetupRequired
    onSourceReadyWatchChanged: if (sourceReadyWatch && setupDialog.opened) setupDialog.close()

    Dialog {
        id: setupDialog
        title: player.legacySourceMigrationAvailable ? "迁移旧版音源" : "配置音乐来源"
        icon: player.legacySourceMigrationAvailable ? "move_up" : "extension"
        closeOnScrim: false
        showAcceptButton: false
        rejectText: "暂时只使用本地音乐"
        text: player.legacySourceMigrationAvailable
            ? "检测到旧版在线歌曲或登录凭据。安装对应的音源插件后，播放队列会自动切换到新接口；可读取的登录凭据也会在验证成功后迁移，原始数据暂时保留以便回退。"
            : "核心不内置在线音源。请选择受信目录中的插件，或从文件导入其他插件；本地音乐无需安装插件即可继续使用。"
        onRejected: player.dismissSourceSetup()

        ColumnLayout {
            width: parent.width
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                visible: player.pluginCatalogLoading
                spacing: 10

                Text {
                    text: "sync"
                    font.family: Theme.iconFont.name
                    font.pixelSize: 20
                    color: Theme.color.primary
                    NumberAnimation on rotation {
                        running: player.pluginCatalogLoading
                        loops: Animation.Infinite
                        from: 360; to: 0
                        duration: 700
                    }
                }
                Text {
                    Layout.fillWidth: true
                    text: "正在验证受信插件目录…"
                    color: Theme.color.onSurfaceVariantColor
                    fontSize: 14
                }
            }

            Repeater {
                model: player.pluginCatalogEntries

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: catalogCardContent.implicitHeight + 24
                    radius: 16
                    color: Theme.color.surfaceContainerHighest

                    ColumnLayout {
                        id: catalogCardContent
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 12
                        spacing: 6

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 10
                            Text {
                                Layout.fillWidth: true
                                text: modelData.name
                                color: Theme.color.onSurfaceColor
                                fontSize: 17
                                font.weight: 600
                            }
                            Text {
                                text: modelData.version
                                color: Theme.color.onSurfaceVariantColor
                                fontSize: 12
                            }
                        }

                        Text {
                            Layout.fillWidth: true
                            text: modelData.description
                            color: Theme.color.onSurfaceVariantColor
                            fontSize: 13
                            wrapMode: Text.Wrap
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            Item { Layout.fillWidth: true }
                            Button {
                                text: player.legacySourceMigrationAvailable
                                      ? (modelData.installed ? "启用并迁移" : "安装并迁移")
                                      : (modelData.installed ? "启用并设为主音源" : "下载并安装")
                                type: modelData.installed ? "filledTonal" : "filled"
                                enabled: !player.pluginInstallBusy
                                onClicked: {
                                    if (modelData.installed) player.activateSourcePlugin(modelData.id)
                                    else player.installCatalogPlugin(modelData.id)
                                }
                            }
                        }
                    }
                }
            }

            Text {
                Layout.fillWidth: true
                visible: !player.pluginCatalogLoading
                         && (!player.pluginCatalogEntries
                             || player.pluginCatalogEntries.length === 0)
                text: "受信插件目录暂时不可用。你仍可导入本地插件包，或稍后重试。"
                color: Theme.color.error
                fontSize: 13
                wrapMode: Text.Wrap
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 8

                Button {
                    text: "重新验证目录"
                    type: "text"
                    visible: !player.pluginCatalogLoading
                             && (!player.pluginCatalogEntries
                                 || player.pluginCatalogEntries.length === 0)
                    enabled: !player.pluginInstallBusy
                    onClicked: player.refreshPluginCatalog()
                }
                Item { Layout.fillWidth: true }
                Button {
                    text: "从文件导入插件"
                    icon: "upload_file"
                    type: "outlined"
                    enabled: !player.pluginInstallBusy
                    onClicked: player.requestPluginImport()
                }
            }
        }
    }
}
