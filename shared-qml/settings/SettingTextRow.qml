import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.TEXT: a string, committed on Enter or via the apply button (never
// per keystroke: these are URLs and JSON paths, and every write rebuilds the
// custom-API config).
Item {
    id: row
    property var spec: null
    // 活文案：provider 返回的"说明的活版本"（2026-11-15 ✓ 你的行一直有 ✓ 2.1.1 只有 action 行有）
    property string providerText: spec && spec.provider && spec.provider.length > 0 ? settings.info(spec.provider) : ""
    // 有活文案就顶替静态 desc ✓ 否则回落静态说明
    function descText() {
        if (providerText.length > 0) return providerText
        return spec ? i18n.t(spec.desc) : ""
    }
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""

    readonly property real fieldY: frame.height + Ui.space2
    implicitHeight: fieldY + Math.max(field.height, applyButton.height) + Ui.space4

    SettingRowFrame {
        id: frame
        width: row.width
        height: implicitHeight
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.descText()
        separator: row.separator
        clickable: false
        minHeight: 0
        bottomPadding: 0
    }

    TextField {
        id: field
        overlayField: true
        x: Ui.space4
        y: row.fieldY
        width: Math.max(0, applyButton.x - Ui.space2 - x)
        type: "outlined"
        label: row.spec ? i18n.t(row.spec.hint) : ""
        text: row.spec ? settings.value(row.spec.key) : ""
        onAccepted: settings.setValue(row.spec.key, field.text)
    }
    Button {
        id: applyButton
        x: row.width - Ui.space4 - width
        y: row.fieldY + Math.max(0, (field.height - height) / 2)
        type: "filledTonal"
        text: i18n.t("common.apply")
        onClicked: settings.setValue(row.spec.key, field.text)
    }
}
