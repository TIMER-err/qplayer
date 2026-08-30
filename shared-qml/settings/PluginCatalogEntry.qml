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
            text: "download"
            font.family: Theme.iconFont.name
            font.pixelSize: 26
            color: Theme.color.primary
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            SettingTitle {
                text: card.pluginData.name
            }
            SettingDesc {
                text: card.pluginData.version + " · " + card.pluginData.description
            }
        }

        Button {
            type: "filledTonal"
            icon: "download"
            text: "查看"
            onClicked: player.requestPluginSettings(card.pluginData.id)
        }
    }
}
