import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: control

    // Properties
    property string text: ""
    property string placeholderText: ""
    property string label: ""
    property string leadingIcon: ""
    property string trailingIcon: ""
    property string supportingText: ""
    property string errorText: ""
    property bool error: errorText.length > 0
    property string type: "filled" // "filled", "outlined"
    property bool enabled: true
    property bool readOnly: false
    property color labelBackgroundColor: Theme.color.surface // Background color for label mask in outlined mode
    
    // Password features
    property bool isPassword: false
    property bool passwordVisible: false
    
    // Signals
    signal accepted()
    signal editingFinished()
    signal trailingIconClicked()

    implicitWidth: 280
    implicitHeight: 56 + (supportingText || errorText ? 20 : 0)

    // Theme Helpers
    property var _colors: Theme.color
    property var _typography: Theme.typography
    property var _shape: Theme.shape
    
    // Type conversion helper for colors to ensure rgba() works
    property color _onSurfaceColor: _colors.onSurfaceColor

    // Internal State
    property bool focused: textInput.activeFocus
    property bool hasContent: text.length > 0
    property bool isFloating: focused || hasContent

    // Colors
    property color _containerColor: {
        if (type === "filled") {
            return enabled ? _colors.surfaceContainerHighest : Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.04)
        }
        return "transparent"
    }
    
    property color _contentColor: enabled ? _colors.onSurfaceColor : Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.38)
    
    property color _labelColor: {
        if (!enabled) return Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.38)
        if (error) return _colors.error
        if (focused) return _colors.primary
        return _colors.onSurfaceVariantColor
    }
    
    property color _indicatorColor: {
        if (!enabled) return Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.38)
        if (error) return _colors.error
        if (focused) return _colors.primary
        return _colors.onSurfaceVariantColor
    }
    
    property color _outlineColor: {
        if (!enabled) return Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.12)
        if (error) return _colors.error
        return _colors.outline
    }

    // Main Container
    Rectangle {
        id: container
        width: parent.width
        height: 56
        radius: type === "filled" ? _shape.cornerExtraSmall : _shape.cornerExtraSmall
        
        color: _containerColor
        
        // Base Border for Outlined (Always 1px, static color)
        border.width: type === "outlined" ? 1 : 0
        border.color: type === "outlined" ? _outlineColor : "transparent"

        // Focus Border for Outlined (Overlay, 2px, animates opacity)
        Rectangle {
            id: focusBorder
            anchors.fill: parent
            radius: parent.radius
            color: "transparent"
            border.width: 2
            border.color: error ? _colors.error : _colors.primary
            opacity: (type === "outlined" && focused) ? 1 : 0
            visible: type === "outlined"
            
            Behavior on opacity { NumberAnimation { duration: 150 } }
        }

        // Active Indicator for Filled
        Rectangle {
            visible: type === "filled"
            width: parent.width
            height: focused ? 2 : 1
            color: _indicatorColor
            anchors.bottom: parent.bottom

            Behavior on height { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
            Behavior on color { ColorAnimation { duration: 150 } }
        }

        // Layout
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: leadingIcon ? 12 : 16
            anchors.rightMargin: (trailingIcon || isPassword) ? 12 : 16
            spacing: 0

            // Leading Icon
            Text {
                text: control.leadingIcon
                font.family: Theme.iconFont.name
                font.pixelSize: 24
                visible: control.leadingIcon !== ""
                color: _contentColor
                Layout.preferredWidth: 24
                Layout.preferredHeight: 24
                Layout.rightMargin: 16
                verticalAlignment: Text.AlignVCenter
                horizontalAlignment: Text.AlignHCenter
            }

            // Text Area Container
            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true
                
                // Label
                Text {
                    id: labelText
                    text: control.label
                    color: _labelColor
                    
                    // Position logic
                    // Filled: Center (y=16 approx) -> Top (y=8)
                    // Outlined: Center (y=16) -> Top Border (y=-8 approx)
                    
                    property real targetY: {
                        if (type === "filled") return control.isFloating ? 8 : 16
                        return control.isFloating ? -8 : 16
                    }
                    
                    y: targetY
                    
                    // X position adjustment if leading icon exists? No, handled by RowLayout parent
                    
                    font.pixelSize: control.isFloating ? 12 : 16
                    font.family: _typography.bodyLarge.family
                    font.weight: _typography.bodyLarge.weight
                    
                    Behavior on y { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    Behavior on font.pixelSize { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    
                    // Background for Outlined Label (to hide border behind label)
                    Rectangle {
                        visible: type === "outlined" && control.isFloating
                        color: control.labelBackgroundColor // Use specified background color to mask border
                        anchors.fill: parent
                        anchors.margins: -4
                        z: -1
                    }
                }

                // Input
                TextInput {
                    id: textInput
                    anchors.fill: parent
                    anchors.topMargin: type === "filled" ? (control.isFloating ? 24 : 0) : (control.isFloating ? 16 : 0)
                    anchors.bottomMargin: type === "filled" ? (control.isFloating ? 8 : 0) : (control.isFloating ? 16 : 0)
                    verticalAlignment: control.isFloating ? TextInput.AlignBottom : TextInput.AlignVCenter
                    
                    text: control.text
                    color: _contentColor
                    font.pixelSize: 16
                    font.family: _typography.bodyLarge.family
                    selectionColor: _colors.primary
                    selectedTextColor: _colors.onPrimaryColor
                    enabled: control.enabled
                    readOnly: control.readOnly
                    clip: true
                    
                    echoMode: control.isPassword && !control.passwordVisible ? TextInput.Password : TextInput.Normal
                    passwordCharacter: "•"
                    
                    onTextChanged: control.text = text
                    onAccepted: {
                        control.accepted()
                        Qt.inputMethod.hide()
                        textInput.focus = false
                    }
                    onEditingFinished: control.editingFinished()
                    
                    // Placeholder
                    Text {
                        text: control.placeholderText
                        visible: !control.hasContent && !control.label && !textInput.activeFocus
                        color: _contentColor
                        opacity: 0.5
                        anchors.fill: parent
                        verticalAlignment: TextInput.AlignVCenter
                        font: parent.font
                    }
                }
            }

            // Trailing Icon / Password Toggle
            Item {
                visible: control.trailingIcon !== "" || control.isPassword
                Layout.preferredWidth: 24
                Layout.preferredHeight: 24
                Layout.leftMargin: 16
                
                // Custom Trailing Icon
                Text {
                    text: control.trailingIcon
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    visible: control.trailingIcon !== "" && !control.isPassword
                    anchors.centerIn: parent
                    color: control.error ? _colors.error : _contentColor
                    verticalAlignment: Text.AlignVCenter
                    horizontalAlignment: Text.AlignHCenter

                    MouseArea {
                        anchors.fill: parent
                        // Only clickable if it's meant to be interactive? 
                        // MD3 doesn't specify strictly, but clear buttons usually are.
                        // Increase touch target slightly
                        anchors.margins: -12
                        enabled: control.enabled
                        onClicked: control.trailingIconClicked()
                        cursorShape: Qt.PointingHandCursor
                    }
                }

                // Password Toggle
                IconButton {
                    visible: control.isPassword
                    icon: control.passwordVisible ? "visibility_off" : "visibility"
                    anchors.centerIn: parent
                    onClicked: control.passwordVisible = !control.passwordVisible
                    type: "standard" // Use standard (no background) for icon button
                }
                
                // Error Icon (if error and no other trailing icon/password)
                Text {
                    text: "error"
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    visible: control.error && !control.trailingIcon && !control.isPassword
                    anchors.centerIn: parent
                    color: _colors.error
                    verticalAlignment: Text.AlignVCenter
                    horizontalAlignment: Text.AlignHCenter
                }
            }
        }
    }

    // Supporting Text
    Text {
        anchors.top: container.bottom
        anchors.topMargin: 4
        anchors.left: container.left
        anchors.leftMargin: 16
        anchors.right: container.right
        anchors.rightMargin: 16
        
        text: control.error ? control.errorText : control.supportingText
        color: control.error ? _colors.error : _colors.onSurfaceVariantColor
        font.pixelSize: 12
        visible: text !== ""
    }
}

