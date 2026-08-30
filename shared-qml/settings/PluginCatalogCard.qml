import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// The trusted catalog is kept in its own card so SettingsPage can place it in
// the second desktop column while reusing the same content in the single-column
// phone layout.
SettingCard {
    RowLayout {
        Layout.fillWidth: true
        SettingTitle { Layout.fillWidth: true; text: "受信插件目录" }
        Button {
            type: "text"
            icon: "refresh"
            text: player.pluginCatalogLoading ? "正在验证…" : "重新验证"
            enabled: !player.pluginCatalogLoading && !player.pluginInstallBusy
            onClicked: player.refreshPluginCatalog()
        }
    }
    SettingDesc {
        text: "目录由 QPlayer 签名验证；插件包由各自发布者签名并托管在独立项目中。QPlayer 不捆绑或托管任何音源。"
    }
    Repeater {
        model: player.pluginCatalogEntries
        delegate: ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            RowLayout {
                Layout.fillWidth: true
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 1
                    SettingTitle { text: modelData.name + "  " + modelData.version }
                    SettingDesc { text: modelData.description }
                }
                Button {
                    type: modelData.installed && !modelData.updateAvailable
                          ? "outlined" : "filledTonal"
                    text: modelData.updateAvailable ? "更新"
                          : (modelData.installed ? "已安装" : "下载并安装")
                    enabled: (!modelData.installed || modelData.updateAvailable)
                             && !player.pluginInstallBusy
                    onClicked: player.installCatalogPlugin(modelData.id)
                }
            }
            SettingDesc { text: "独立项目：" + modelData.homepage }
        }
    }
}
