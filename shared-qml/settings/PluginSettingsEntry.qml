import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

SettingCard {
    id: card

    required property var pluginData

    RowLayout {
        Layout.fillWidth: true
        spacing: 12

        Text {
            text: "extension"
            font.family: Theme.iconFont.name
            font.pixelSize: 26
            color: card.pluginData.enabled
                   ? Theme.color.primary
                   : Theme.color.onSurfaceVariantColor
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            SettingTitle {
                text: card.pluginData.name
            }
            SettingDesc {
                text: card.pluginData.version
                      + (card.pluginData.primary ? " · 主音源"
                         : (card.pluginData.enabled ? " · 已启用" : " · 已停用"))
            }
        }

        Button {
            type: "outlined"
            icon: "settings"
            text: "设置"
            onClicked: player.requestPluginSettings(card.pluginData.id)
        }
    }

    SettingDesc {
        text: "权限：" + (card.pluginData.permissions.length > 0
              ? card.pluginData.permissions : "无额外权限")
    }
}
