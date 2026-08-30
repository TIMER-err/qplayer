import QtQuick
import QtQuick.Layouts
import md3.Core

// Compact picker opened from Settings. It only chooses a plugin; all controls
// and metadata live on that plugin's own full settings page.
Item {
    id: root

    readonly property bool opened: managerDialog.opened
    function open() { managerDialog.open() }
    function close() { managerDialog.close() }

    property var availableCatalog: {
        var out = []
        var rows = player.pluginCatalogEntries || []
        for (var i = 0; i < rows.length; i++) {
            if (!rows[i].installed) out.push(rows[i])
        }
        return out
    }

    Dialog {
        id: managerDialog
        title: "插件"
        icon: "extension"
        showAcceptButton: false
        rejectText: "关闭"

        ColumnLayout {
            width: parent.width
            spacing: 12

            Flickable {
                id: pluginList
                Layout.fillWidth: true
                Layout.preferredHeight: 320
                clip: true
                contentWidth: width
                contentHeight: listColumn.implicitHeight

                ColumnLayout {
                    id: listColumn
                    width: pluginList.width
                    spacing: 8

                    Text {
                        Layout.fillWidth: true
                        text: "已安装"
                        color: Theme.color.primary
                        fontSize: 13
                        font.weight: Font.DemiBold
                    }

                    Text {
                        Layout.fillWidth: true
                        visible: !player.sourcePlugins || player.sourcePlugins.length === 0
                        text: "尚未安装插件"
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }

                    Repeater {
                        model: player.sourcePlugins
                        delegate: Rectangle {
                            id: installedRow
                            Layout.fillWidth: true
                            implicitHeight: 64
                            radius: 16
                            color: Theme.color.surfaceContainerHighest

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 14
                                anchors.rightMargin: 10
                                spacing: 12

                                Text {
                                    text: "extension"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 22
                                    color: modelData.enabled
                                           ? Theme.color.primary
                                           : Theme.color.onSurfaceVariantColor
                                }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.name
                                        color: Theme.color.onSurfaceColor
                                        fontSize: 15
                                        font.weight: Font.DemiBold
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.version + (modelData.primary
                                              ? " · 主音源" : (modelData.enabled ? " · 已启用" : " · 已停用"))
                                        color: Theme.color.onSurfaceVariantColor
                                        fontSize: 12
                                        elide: Text.ElideRight
                                    }
                                }
                                Text {
                                    text: "chevron_right"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 22
                                    color: Theme.color.onSurfaceVariantColor
                                }
                            }

                            Ripple {
                                anchors.fill: parent
                                clipRadius: installedRow.radius
                                rippleColor: Theme.color.onSurfaceColor
                                onClicked: {
                                    root.close()
                                    player.requestPluginSettings(modelData.id)
                                }
                            }
                        }
                    }

                    Text {
                        Layout.fillWidth: true
                        Layout.topMargin: 6
                        visible: root.availableCatalog.length > 0
                        text: "可安装"
                        color: Theme.color.primary
                        fontSize: 13
                        font.weight: Font.DemiBold
                    }

                    Repeater {
                        model: root.availableCatalog
                        delegate: Rectangle {
                            id: catalogRow
                            Layout.fillWidth: true
                            implicitHeight: 64
                            radius: 16
                            color: Theme.color.surfaceContainerHighest

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 14
                                anchors.rightMargin: 10
                                spacing: 12

                                Text {
                                    text: "download"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 22
                                    color: Theme.color.primary
                                }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.name
                                        color: Theme.color.onSurfaceColor
                                        fontSize: 15
                                        font.weight: Font.DemiBold
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.version + " · " + modelData.description
                                        color: Theme.color.onSurfaceVariantColor
                                        fontSize: 12
                                        elide: Text.ElideRight
                                    }
                                }
                                Text {
                                    text: "chevron_right"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 22
                                    color: Theme.color.onSurfaceVariantColor
                                }
                            }

                            Ripple {
                                anchors.fill: parent
                                clipRadius: catalogRow.radius
                                rippleColor: Theme.color.onSurfaceColor
                                onClicked: {
                                    root.close()
                                    player.requestPluginSettings(modelData.id)
                                }
                            }
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        visible: player.pluginCatalogLoading
                        spacing: 8
                        LoadingIndicator {
                            running: player.pluginCatalogLoading
                            size: 24
                        }
                        Text {
                            Layout.fillWidth: true
                            text: "正在验证受信插件目录…"
                            color: Theme.color.onSurfaceVariantColor
                            fontSize: 13
                        }
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Button {
                    type: "text"
                    icon: "refresh"
                    text: "刷新目录"
                    enabled: !player.pluginCatalogLoading && !player.pluginInstallBusy
                    onClicked: player.refreshPluginCatalog()
                }
                Item { Layout.fillWidth: true }
                Button {
                    type: "outlined"
                    icon: "upload_file"
                    text: "从文件导入"
                    enabled: !player.pluginInstallBusy
                    onClicked: {
                        root.close()
                        player.requestPluginImport()
                    }
                }
            }
        }
    }
}
