import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.STEPPER: an int with −/+ buttons. Clamping lives in SettingsCore
// (bump()), so the range is never repeated here; `scale` divides the stored value
// for display only (line spacing persists as 200 and shows as 2.00×).
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    property int value: row.spec ? settings.value(row.spec.key) : 0
    property string display: {
        if (!row.spec) return ""
        var v = row.spec.scale > 1 ? (row.value / row.spec.scale).toFixed(2) : ("" + row.value)
        return v + row.spec.unit
    }
    readonly property real buttonSize: 36
    readonly property real valueWidth: Math.max(52, valueText.implicitWidth + Ui.space3)

    implicitHeight: frame.implicitHeight

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.height
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.spec ? i18n.t(row.spec.desc) : ""
        separator: row.separator
        clickable: false
        controlWidth: row.buttonSize * 2 + row.valueWidth
    }

    IconButton {
        objectName: "settingStepperDecrease"
        x: row.width - Ui.space4 - row.buttonSize * 2 - row.valueWidth
        y: (row.height - height) / 2
        width: row.buttonSize
        height: row.buttonSize
        type: "filledTonal"
        icon: "remove"
        enabled: row.spec && row.value > row.spec.min
        onClicked: settings.bump(row.spec.key, -1)
    }
    Text {
        id: valueText
        objectName: "settingStepperValue"
        x: row.width - Ui.space4 - row.buttonSize - row.valueWidth
        y: (row.height - implicitHeight) / 2
        width: row.valueWidth
        horizontalAlignment: Text.AlignHCenter
        text: row.display
        color: Ui.textPrimary
        font.pixelSize: Ui.textBody
        font.weight: Ui.weightBold
    }
    IconButton {
        objectName: "settingStepperIncrease"
        x: row.width - Ui.space4 - row.buttonSize
        y: (row.height - height) / 2
        width: row.buttonSize
        height: row.buttonSize
        type: "filledTonal"
        icon: "add"
        enabled: row.spec && row.value < row.spec.max
        onClicked: settings.bump(row.spec.key, 1)
    }
}
