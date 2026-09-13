import QtQuick
import miuix.Core

Item {
    id: fieldRoot
    property alias text: input.text
    property string placeholderText: ""
    property string label: ""
    property bool useLabelAsPlaceholder: false
    property string leadingIcon: ""
    property string trailingIcon: ""
    property string supportingText: ""
    property string errorText: ""
    property bool error: errorText.length > 0
    property string type: "filled"
    property bool readOnly: false
    property bool isPassword: false
    property bool passwordVisible: false
    property bool clearButtonEnabled: true
    property real cornerRadius: 16
    property real horizontalPadding: 16
    property real verticalPadding: 16
    property color backgroundColor: Theme.color.secondaryContainer
    property color labelColor: Theme.color.onSecondaryContainerColor
    property color borderColor: error ? Theme.color.error : Theme.color.primary
    readonly property bool focused: input.activeFocus
    readonly property bool hasContent: text.length > 0
    readonly property bool isFloating: label.length > 0 && hasContent && !useLabelAsPlaceholder
    readonly property real fieldHeight: Math.max(56, verticalPadding * 2 + 24)
    readonly property real _lineY: (fieldHeight - 24) / 2
    readonly property string _action: isPassword ? "password" : trailingIcon.length > 0 ? "custom"
        : error ? "error" : clearButtonEnabled && hasContent && enabled && !readOnly ? "clear" : ""
    signal accepted()
    signal editingFinished()
    signal trailingIconClicked()
    signal cleared()

    implicitWidth: 280
    implicitHeight: fieldHeight + (support.visible ? support.implicitHeight + 6 : 0)

    function focusInput() { if (enabled) input.forceActiveFocus() }
    function clear() {
        if (!enabled || readOnly) return
        input.text = ""
        focusInput()
        cleared()
    }

    SmoothRectangle {
        id: background
        objectName: "miuixTextFieldBackground"
        width: parent.width
        height: fieldRoot.fieldHeight
        radius: fieldRoot.cornerRadius
        color: fieldRoot.backgroundColor
        borderColor: fieldRoot.focused || fieldRoot.error ? fieldRoot.borderColor : Theme.color.outline
        borderWidth: fieldRoot.enabled && (fieldRoot.focused || fieldRoot.error) ? 2 : fieldRoot.type === "outlined" ? 1 : 0
        Behavior on borderWidth { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
    }
    MouseArea {
        width: parent.width
        height: fieldRoot.fieldHeight
        enabled: fieldRoot.enabled
        onClicked: fieldRoot.focusInput()
    }
    Icon {
        x: 12
        y: (fieldRoot.fieldHeight - height) / 2
        width: 24
        height: 24
        visible: fieldRoot.leadingIcon.length > 0
        name: fieldRoot.leadingIcon
        color: fieldRoot.enabled ? Theme.color.onSurfaceColor : Theme.color.disabledOnSecondaryVariant
    }
    Item {
        id: textArea
        x: fieldRoot.leadingIcon.length > 0 ? 48 : fieldRoot.horizontalPadding
        width: Math.max(0, fieldRoot.width - x - (fieldRoot._action.length > 0 ? 48 : fieldRoot.horizontalPadding))
        height: fieldRoot.fieldHeight
        Text {
            id: labelText
            objectName: "miuixTextFieldLabel"
            width: parent.width
            // Center the measured line explicitly; this also works on hosts
            // where Text.verticalAlignment does not yet affect painting.
            y: fieldRoot.isFloating ? fieldRoot._lineY - fieldRoot.verticalPadding / 2
                : (fieldRoot.fieldHeight - implicitHeight) / 2
            text: fieldRoot.label
            visible: text.length > 0 && !(fieldRoot.useLabelAsPlaceholder && fieldRoot.hasContent)
            elide: Text.ElideRight
            maximumLineCount: 1
            font.pixelSize: fieldRoot.isFloating ? 10 : 17
            font.weight: 57
            color: fieldRoot.enabled ? fieldRoot.labelColor : Theme.color.disabledOnSecondaryVariant
            Behavior on y { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
            Behavior on font.pixelSize { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
        }
        Text {
            objectName: "miuixTextFieldPlaceholder"
            width: parent.width
            y: (fieldRoot.fieldHeight - implicitHeight) / 2
            text: fieldRoot.placeholderText
            visible: fieldRoot.label.length === 0 && !fieldRoot.hasContent
            elide: Text.ElideRight
            maximumLineCount: 1
            font.pixelSize: 17
            color: fieldRoot.enabled ? fieldRoot.labelColor : Theme.color.disabledOnSecondaryVariant
        }
        TextInput {
            id: input
            objectName: "miuixTextFieldInput"
            width: parent.width
            height: 24
            y: fieldRoot._lineY + (fieldRoot.isFloating ? fieldRoot.verticalPadding / 2 : 0)
            verticalAlignment: TextInput.AlignVCenter
            font.pixelSize: 17
            color: fieldRoot.enabled ? Theme.color.onSurfaceColor : Theme.color.disabledOnSecondaryVariant
            selectionColor: Theme.color.primary
            selectedTextColor: Theme.color.onPrimary
            enabled: fieldRoot.enabled
            readOnly: fieldRoot.readOnly
            clip: true
            echoMode: fieldRoot.isPassword && !fieldRoot.passwordVisible ? TextInput.Password : TextInput.Normal
            passwordCharacter: "•"
            onAccepted: {
                fieldRoot.accepted()
                focus = false
                Qt.inputMethod.hide()
            }
            onEditingFinished: fieldRoot.editingFinished()
        }
    }
    IconButton {
        id: action
        objectName: "miuixTextFieldAction"
        width: 40
        height: 40
        x: fieldRoot.width - width - 4
        y: (fieldRoot.fieldHeight - height) / 2
        visible: fieldRoot._action.length > 0
        enabled: fieldRoot.enabled && fieldRoot._action !== "error"
        contentColor: fieldRoot._action === "error" ? Theme.color.error
            : fieldRoot.enabled ? Theme.color.onSurfaceColor : Theme.color.disabledOnSecondaryVariant
        icon: fieldRoot._action === "password" ? (fieldRoot.passwordVisible ? "visibility_off" : "visibility")
            : fieldRoot._action === "custom" ? fieldRoot.trailingIcon
            : fieldRoot._action === "error" ? "error" : "close"
        onClicked: {
            if (fieldRoot._action === "password") fieldRoot.passwordVisible = !fieldRoot.passwordVisible
            else if (fieldRoot._action === "custom") fieldRoot.trailingIconClicked()
            else if (fieldRoot._action === "clear") fieldRoot.clear()
        }
    }
    Text {
        id: support
        objectName: "miuixTextFieldSupport"
        x: fieldRoot.horizontalPadding
        y: fieldRoot.fieldHeight + 6
        width: Math.max(0, fieldRoot.width - fieldRoot.horizontalPadding * 2)
        text: fieldRoot.error ? fieldRoot.errorText : fieldRoot.supportingText
        visible: text.length > 0
        wrapMode: Text.Wrap
        font.pixelSize: 14
        color: fieldRoot.error ? Theme.color.error : Theme.color.onSurfaceVariantSummary
    }
}
