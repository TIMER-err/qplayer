import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "."

// One catalog group: a caption and a raised card whose rows are generated from
// the group's SettingSpecs. Rows are separated by hairlines; the first VISIBLE
// row (dependsOn can hide leading rows) draws none.
Item {
    id: section
    property var groupData
    function rowVisible(spec) {
        return spec && (spec.dependsOn.length === 0
                        || settings.value(spec.dependsOn) === true)
    }
    property int firstVisibleRow: {
        var specs = section.groupData ? section.groupData.rows : null
        if (!specs) return -1
        for (var i = 0; i < specs.length; i++) {
            if (section.rowVisible(specs[i])) return i
        }
        return -1
    }
    readonly property real captionHeight: 30
    visible: firstVisibleRow >= 0
    implicitHeight: visible ? captionHeight + card.height : 0

    SettingCaption {
        x: Ui.space4
        y: 2
        width: Math.max(0, section.width - Ui.space8)
        text: section.groupData ? i18n.t("settings.group." + section.groupData.id) : ""
    }

    SettingCard {
        id: card
        y: section.captionHeight
        width: section.width
        height: implicitHeight

        Repeater {
            model: section.groupData ? section.groupData.rows : null
            Loader {
                Layout.fillWidth: true
                property var rowSpec: modelData
                property bool rowSeparator: index > section.firstVisibleRow
                visible: section.rowVisible(rowSpec)
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

    Item {
        visible: false
        Component { id: switchRow; SettingSwitchRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: stepperRow; SettingStepperRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: sliderRow; SettingSliderRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: segmentedRow; SettingSegmentedRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: radioRow; SettingRadioRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: dropdownRow; SettingDropdownRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: textRow; SettingTextRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: colorRow; SettingColorRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: pathRow; SettingPathRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: actionRow; SettingActionRow { spec: rowSpec; separator: rowSeparator } }
    }
}
