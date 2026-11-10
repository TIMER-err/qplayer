import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.ACTION: a tappable row that invokes a host action. A provider's
// live text (font name, cache size, app version) shows either inline on the
// right (inlineProvider) or as the first summary line.
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    property string providerText: spec && spec.provider.length > 0 ? settings.info(spec.provider) : ""

    implicitHeight: frame.implicitHeight

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        icon: row.spec ? row.spec.icon : ""
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: {
            if (!row.spec) return ""
            var desc = i18n.t(row.spec.desc)
            return !row.spec.inlineProvider && row.providerText.length > 0
                ? row.providerText + (desc.length > 0 ? "\n" + desc : "") : desc
        }
        separator: row.separator
        value: row.spec && row.spec.inlineProvider ? row.providerText
               : (row.spec ? i18n.t(row.spec.button) : "")
        indicator: "chevron_right"
        onClicked: if (row.spec) settings.invoke(row.spec.action)
    }
}
