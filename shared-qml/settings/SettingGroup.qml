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
    // **可折叠分组**（2026-11-15 用户要求）：像"接法开关"这种 20 多项的组，
    // 默认收起，只显示一行「接法开关 · 23 项 ⌄」，点开才铺开 ✓
    readonly property bool collapsible: section.groupData
        && (section.groupData.id === "automixStyles")
    property bool expanded: false
    readonly property int rowCount: section.groupData && section.groupData.rows
        ? section.groupData.rows.length : 0

    readonly property real captionHeight: 30
    visible: firstVisibleRow >= 0
    implicitHeight: visible ? captionHeight + card.height : 0

    SettingCaption {
        id: caption
        x: Ui.space4
        y: 2
        width: Math.max(0, section.width - (section.collapsible ? Ui.space10 : Ui.space8))
        text: section.groupData
              ? i18n.t("settings.group." + section.groupData.id)
                + (section.collapsible ? " · " + rowCount + (section.expanded ? " ⌃" : " ⌄") : "")
              : ""
    }

    MouseArea {
        // 只有可折叠组的标题区接点击（其余组的标题不响应任何东西）
        visible: section.collapsible
        x: 0
        y: 0
        width: section.width
        height: section.captionHeight
        onClicked: section.expanded = !section.expanded
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
                visible: section.rowVisible(rowSpec) && (!section.collapsible || section.expanded)
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
        Component { id: chipsRow; SettingChipsRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: textRow; SettingTextRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: colorRow; SettingColorRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: pathRow; SettingPathRow { spec: rowSpec; separator: rowSeparator } }
        Component { id: actionRow; SettingActionRow { spec: rowSpec; separator: rowSeparator } }
    }
}
