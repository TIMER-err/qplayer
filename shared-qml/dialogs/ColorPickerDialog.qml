import QtQuick
import miuix.Core

// One dialog for every COLOR setting row. The row being edited is named by
// settings.colorPickerKey; an empty key means the dialog is closed.
//
// The picker emits on every slider move, so the choice is held here and only
// written on accept — a live write would persist the settings file dozens of
// times per drag. "Restore the default" writes an empty value back, which is
// how a colour row says "let the renderer pick".
Item {
    id: control
    anchors.fill: parent
    property string settingKey: settings.colorPickerKey
    property bool active: control.settingKey.length > 0
    property string stored: control.active ? String(settings.value(control.settingKey) || "") : ""
    property color autoColor: control.settingKey === "desktopLyricSungColor"
        ? Theme.color.primary : Theme.color.onSurfaceVariantColor
    property string pendingHex: ""

    function hexByte(value) {
        var v = Math.max(0, Math.min(255, Math.round(value * 255)))
        var s = v.toString(16)
        return s.length < 2 ? "0" + s : s
    }

    // Alpha is deliberately dropped: the desktop-lyric renderer owns opacity
    // (it fades the whole line during a transition), so a colour carrying its
    // own alpha would fight that animation.
    function toHex(c) {
        return "#" + control.hexByte(c.r) + control.hexByte(c.g) + control.hexByte(c.b)
    }

    onActiveChanged: {
        if (control.active) {
            control.pendingHex = control.stored
            picker.color = control.stored.length > 0 ? control.stored : control.autoColor
            dialog.open()
        } else if (dialog.opened) {
            dialog.close()
        }
    }

    Dialog {
        id: dialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t("color.picker.title")
        acceptText: i18n.t("common.confirm")
        rejectText: i18n.t("common.cancel")
        neutralText: i18n.t("color.picker.auto")
        showNeutralButton: true
        onAccepted: if (control.settingKey.length > 0 && control.pendingHex.length > 0)
            settings.setValue(control.settingKey, control.pendingHex)
        onNeutral: if (control.settingKey.length > 0) settings.setValue(control.settingKey, "")
        onClosed: settings.colorPickerKey = ""

        // No Layout wrapper: one child sized to the dialog is all this needs, and
        // qml4j's fillWidth propagation through a nested Layout is unreliable.
        ColorPicker {
            id: picker
            objectName: "settingColorPicker"
            width: dialog.contentWidth
            showPreview: true
            onColorSelected: (newColor) => control.pendingHex = control.toHex(newColor)
        }
    }
}
