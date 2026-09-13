import QtQuick
import miuix.Core

BasicComponent {
    id: row
    property var spec: null
    property string providerText: spec && spec.provider.length > 0 ? settings.info(spec.provider) : ""
    title: spec ? i18n.t(spec.title) : ""
    summary: {
        if (!row.spec) return ""
        var desc = i18n.t(row.spec.desc)
        return !row.spec.inlineProvider && row.providerText.length > 0
            ? row.providerText + (desc.length > 0 ? "\n" + desc : "") : desc
    }
    property string rightText: spec && spec.inlineProvider ? providerText : (spec ? i18n.t(spec.button) : "")
    startAction: Component {
        Icon {
            objectName: "settingActionLeadingIcon"
            name: row.spec ? row.spec.icon : ""
            implicitWidth: name.length > 0 ? 24 : 0
            implicitHeight: implicitWidth
            width: implicitWidth
            height: implicitHeight
            color: Theme.color.onSurfaceVariantColor
        }
    }
    endAction: Component {
        Row {
            spacing: row.rightText.length > 0 ? 8 : 0
            Text {
                text: row.rightText
                font.pixelSize: 14
                color: Theme.color.onSurfaceVariantActions
                anchors.verticalCenter: parent.verticalCenter
            }
            Icon {
                name: "chevron_right"
                width: 10; height: 16
                anchors.verticalCenter: parent.verticalCenter
                color: Theme.color.onSurfaceVariantActions
            }
        }
    }
    onClicked: if (spec) settings.invoke(spec.action)
}
