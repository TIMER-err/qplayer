import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

ColumnLayout {
    id: section
    property var groupData
    spacing: 8
    SmallTitle {
        Layout.fillWidth: true
        text: i18n.t("settings.group." + section.groupData.id)
    }
    Card {
        Layout.fillWidth: true
        implicitHeight: rows.implicitHeight
        radius: 20
        ColumnLayout {
            id: rows
            width: parent.width
            spacing: 0
            Repeater {
                model: section.groupData.rows
                Loader {
                    Layout.fillWidth: true
                    property var rowSpec: modelData
                    property bool preferenceRow: rowSpec.type === "switch" || rowSpec.type === "dropdown" || rowSpec.type === "action" || rowSpec.type === "path" || rowSpec.type === "color"
                    Layout.leftMargin: preferenceRow ? 0 : 16
                    Layout.rightMargin: preferenceRow ? 0 : 16
                    Layout.topMargin: preferenceRow ? 0 : 16
                    Layout.bottomMargin: preferenceRow ? 0 : 16
                    visible: rowSpec.dependsOn.length === 0 || settings.value(rowSpec.dependsOn) === true
                    sourceComponent: rowSpec.type === "switch" ? switchRow
                        : rowSpec.type === "stepper" ? stepperRow
                        : rowSpec.type === "slider" ? sliderRow
                        : rowSpec.type === "segmented" ? segmentedRow
                        : rowSpec.type === "radio" ? radioRow
                        : rowSpec.type === "dropdown" ? dropdownRow
                        : rowSpec.type === "text" ? textRow
                        : rowSpec.type === "color" ? colorRow
                        : rowSpec.type === "path" ? pathRow : actionRow
                }
            }
        }
    }
    Item {
        visible: false
        Component { id: switchRow; SettingSwitchRow { spec: rowSpec } }
        Component { id: stepperRow; SettingStepperRow { spec: rowSpec } }
        Component { id: sliderRow; SettingSliderRow { spec: rowSpec } }
        Component { id: segmentedRow; SettingSegmentedRow { spec: rowSpec } }
        Component { id: radioRow; SettingRadioRow { spec: rowSpec } }
        Component { id: dropdownRow; SettingDropdownRow { spec: rowSpec } }
        Component { id: textRow; SettingTextRow { spec: rowSpec } }
        Component { id: colorRow; SettingColorRow { spec: rowSpec } }
        Component { id: pathRow; SettingPathRow { spec: rowSpec } }
        Component { id: actionRow; SettingActionRow { spec: rowSpec } }
    }
}
