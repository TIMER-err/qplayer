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
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""

    readonly property real fieldY: frame.height + Ui.space2
    implicitHeight: fieldY + Math.max(field.height, applyButton.height) + Ui.space4

    SettingRowFrame {
        id: frame
        width: row.width
        height: implicitHeight
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.spec ? i18n.t(row.spec.desc) : ""
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
