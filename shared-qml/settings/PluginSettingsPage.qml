import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// Host-owned settings surface for one plugin. Third-party QML is never embedded
// into this realm: declared settings contributions open through the existing
// isolated PluginUiSession instead.
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
                                 : (catalogData ? catalogData.name : "插件")

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
                width: parent.width
                spacing: 14

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
                              ? ((page.pluginData.signed ? "已验证发布者签名" : "未验证来源")
                                 + "\n权限：" + (page.pluginData.permissions.length > 0
                                      ? page.pluginData.permissions : "无额外权限"))
                              : ""
                    }
                }

                SettingCard {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: page.pluginData === null && page.catalogData !== null

                    SettingTitle { text: "安装插件" }
                    SettingDesc {
                        text: "插件包将从独立项目下载，并在安装前验证目录摘要和发布者签名。"
                    }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        type: "filled"
                        icon: "download"
                        text: player.pluginInstallBusy ? "正在下载…" : "下载并安装"
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
                        SettingTitle { Layout.fillWidth: true; text: "启用插件" }
                        Switch {
                            checked: page.pluginData ? page.pluginData.enabled : false
                            onClicked: player.setSourcePluginEnabled(page.pluginId, checked)
                        }
                    }
                    SettingDesc {
                        text: page.pluginData && page.pluginData.enabled
                              ? "插件当前可以提供内容和功能。"
                              : "停用后不会执行插件代码，也不会提供在线内容。"
                    }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        visible: page.pluginData && page.pluginData.enabled
                        enabled: page.pluginData && !page.pluginData.primary
                                 && !player.pluginInstallBusy
                        type: page.pluginData && page.pluginData.primary
                              ? "filledTonal" : "outlined"
                        icon: page.pluginData && page.pluginData.primary ? "check" : "star"
                        text: page.pluginData && page.pluginData.primary
                              ? "当前主音源" : "设为主音源"
                        onClicked: player.setPrimarySourcePlugin(page.pluginId)
                    }
                }

                SettingCard {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: page.pluginData !== null && page.catalogData !== null
                             && page.catalogData.updateAvailable

                    SettingTitle { text: "发现新版本 " + (page.catalogData ? page.catalogData.version : "") }
                    SettingDesc { text: "更新仍会经过完整的包摘要与发布者签名验证。" }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        type: "filledTonal"
                        icon: "update"
                        text: player.pluginInstallBusy ? "正在下载…" : "更新"
                        enabled: !player.pluginInstallBusy
                        onClicked: player.installCatalogPlugin(page.pluginId)
                    }
                }

                Repeater {
                    model: page.settingsContributions
                    delegate: SettingCard {
                        Layout.fillWidth: true
                        Layout.leftMargin: 12
                        Layout.rightMargin: 12

                        RowLayout {
                            Layout.fillWidth: true
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                SettingTitle { text: "插件设置" }
                                SettingDesc { text: modelData.id }
                            }
                            Button {
                                type: "outlined"
                                icon: "open_in_new"
                                text: "打开"
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

                    SettingTitle { text: "移除插件" }
                    SettingDesc { text: "登录凭据和插件数据会保留，重新安装后可继续使用。" }
                    Button {
                        Layout.alignment: Qt.AlignRight
                        type: "outlined"
                        icon: "delete"
                        text: "移除"
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
                        text: "该插件已不存在"
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 15
                    }
                    Button {
                        Layout.alignment: Qt.AlignHCenter
                        type: "filledTonal"
                        text: "返回"
                        onClicked: page.back()
                    }
                }
            }
        }
    }
}
