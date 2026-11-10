import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.SEGMENTED: an int index over the spec's own labels. Wide rows
// keep the segmented control on the title line; narrow rows stack it below.
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    property var labels: {
        var out = []
        if (row.spec) for (var i = 0; i < row.spec.options.length; i++)
            out.push(i18n.t(row.spec.options[i]))
        return out
    }
    // Natural (text-measured) width: independent of the control's own width, so
    // the inline/stacked decision below cannot feed back into it.
    readonly property real tabsWidth: tabs._contentWidth
    // Inline once the control leaves the label column at least half the row.
    readonly property bool inline: row.width >= 520 && tabsWidth <= row.width * 0.5

    implicitHeight: inline ? Math.max(frame.implicitHeight, tabs.height + Ui.space5)
                           : frame.height + Ui.space2 + tabs.height + Ui.space4

    SettingRowFrame {
        id: frame
        width: row.width
        height: row.inline ? row.height : implicitHeight
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.spec ? i18n.t(row.spec.desc) : ""
        separator: row.separator
        clickable: false
        minHeight: row.inline ? 56 : 0
        bottomPadding: row.inline ? 14 : 0
        controlWidth: row.inline ? row.tabsWidth : 0
    }

    TabRowWithContour {
        id: tabs
        objectName: "settingSegmentedTabs"
        x: row.inline ? row.width - Ui.space4 - width : Ui.space4
        y: row.inline ? (row.height - height) / 2 : frame.height + Ui.space2
        width: row.inline ? row.tabsWidth : Math.max(0, row.width - Ui.space8)
        height: 40
        tabs: row.labels
        equalWidth: false
        cornerRadius: Ui.radiusSm
        backgroundColor: Ui.surfaceSunken
        selectedBackgroundColor: Ui.accentSoft
        selectedContentColor: Ui.accent
        contentColor: Ui.textSecondary
        selectOnClick: false
        selectedTabIndex: row.spec ? settings.value(row.spec.key) : 0
        onTabSelected: (index) => settings.setValue(row.spec.key, index)
    }
}
