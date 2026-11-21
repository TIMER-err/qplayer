import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.COLOR: a swatch that opens the shared picker dialog. An empty
// stored value means "this setting's automatic default", so the swatch previews
// that default rather than going blank.
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
    property string stored: spec ? String(settings.value(spec.key) || "") : ""
    property color autoColor: spec && spec.key === "desktopLyricSungColor"
        ? Theme.color.primary : Theme.color.onSurfaceVariantColor
    property color shownColor: row.stored.length > 0 ? row.stored : row.autoColor

    implicitHeight: frame.implicitHeight

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.descText()
        separator: row.separator
        indicator: "chevron_right"
        controlWidth: swatch.width
        onClicked: if (row.spec) settings.openColorPicker(row.spec.key)
    }

    Rectangle {
        id: swatch
        objectName: "settingColorSwatch"
        width: 24
        height: 24
        radius: 12
        x: row.width - Ui.space4 - 20 - Ui.space2 - width
        y: (row.height - height) / 2
        color: row.shownColor
        border.width: 1
        border.color: Ui.hairline
    }
}
