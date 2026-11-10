import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.RADIO: an int index shown as a radio list, one option per line.
// Options are placed by index (qml4j does not position Repeater children).
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    readonly property real optionHeight: 44
    readonly property int optionCount: spec ? spec.options.length : 0
    property int current: spec ? settings.value(spec.key) : -1

    implicitHeight: frame.height + Ui.space1 + optionCount * optionHeight + Ui.space2

    SettingRowFrame {
        id: frame
        width: row.width
        height: implicitHeight
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.spec ? i18n.t(row.spec.desc) : ""
        separator: row.separator
        clickable: false
        minHeight: 0
        bottomPadding: Ui.space1
    }

    Repeater {
        model: row.spec ? row.spec.options : []
        Item {
            id: option
            x: Ui.space1
            y: frame.height + Ui.space1 + index * row.optionHeight
            width: Math.max(0, row.width - Ui.space2)
            height: row.optionHeight
            Rectangle {
                anchors.fill: parent
                radius: Ui.radiusMd
                color: optionArea.pressed ? Ui.surfacePressed
                       : (optionArea.containsMouse ? Ui.surfaceHover : "transparent")
            }
            MouseArea {
                id: optionArea
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: settings.setValue(row.spec.key, index)
            }
            RadioButton {
                objectName: "settingRadioOption" + index
                x: Ui.space3
                y: (option.height - height) / 2
                width: Math.max(0, option.width - Ui.space6)
                text: i18n.t(modelData)
                checked: row.current === index
                onClicked: settings.setValue(row.spec.key, index)
            }
        }
    }
}
