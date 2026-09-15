import QtQuick
import miuix.Core

// A COLOR row: a swatch that opens the shared picker dialog. An empty stored
// value means "whatever this setting's automatic default is", so the swatch
// previews that default rather than going blank.
BasicComponent {
    id: row
    property var spec: null
    objectName: spec ? "setting_" + spec.key : ""
    property string stored: spec ? String(settings.value(spec.key) || "") : ""
    property color autoColor: spec && spec.key === "desktopLyricSungColor"
        ? Theme.color.primary : Theme.color.onSurfaceVariantColor
    property color shownColor: row.stored.length > 0 ? row.stored : row.autoColor
    title: spec ? i18n.t(spec.title) : ""
    summary: spec ? i18n.t(spec.desc) : ""
    endAction: Component {
        Row {
            spacing: 8
            Rectangle {
                objectName: "settingColorSwatch"
                width: 22
                height: 22
                radius: 11
                anchors.verticalCenter: parent.verticalCenter
                color: row.shownColor
                border.width: 1
                border.color: Theme.color.outline
            }
            Icon {
                name: "chevron_right"
                width: 10
                height: 16
                anchors.verticalCenter: parent.verticalCenter
                color: Theme.color.onSurfaceVariantActions
            }
        }
    }
    onClicked: if (spec) settings.openColorPicker(spec.key)
}
