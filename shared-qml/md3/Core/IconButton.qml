import QtQuick
import QtQuick.Effects
import md3.Core
Item {
    id: control

    property string icon: ""
    // Types: "filled", "filledTonal", "outlined", "standard"
    property string type: "standard"
    property bool enabled: true

    implicitWidth: 40
    implicitHeight: 40

    // State
    property bool hovered: enabled && ripple.containsMouse
    property bool pressed: enabled && ripple.pressed
    property bool focused: enabled && activeFocus
    
    signal clicked()

    // Theme Colors Lookup
    property var _colors: Theme.color
    property var _elevation: Theme.elevation
    property var _shape: Theme.shape
    property var _state: Theme.state

    // Helper to resolve onSurface with alpha
    property color _onSurfaceColor: _colors.onSurfaceColor
    function disabledColor(alphaValue) {
        return Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, alphaValue)
    }

    // Colors Logic
    property color containerColor: {
        if (!enabled) return disabledColor(0.12) // Disabled container
        switch (type) {
            case "filled": return _colors.primary
            case "filledTonal": return _colors.secondaryContainer
            case "outlined": return "transparent"
            case "standard": return "transparent"
            default: return "transparent"
        }
    }

    property color contentColor: {
        if (!enabled) return disabledColor(0.38) // Disabled content
        switch (type) {
            case "filled": return _colors.onPrimaryColor
            case "filledTonal": return _colors.onSecondaryContainerColor
            case "outlined": return _colors.onSurfaceVariantColor
            case "standard": return _colors.onSurfaceVariantColor
            default: return _colors.onSurfaceVariantColor
        }
    }

    property color stateLayerColor: {
        switch (type) {
            case "filled": return _colors.onPrimaryColor
            case "filledTonal": return _colors.onSecondaryContainerColor
            default: return _colors.onSurfaceVariantColor
        }
    }

    property color outlineColor: {
        if (!enabled) return disabledColor(0.12)
        if (focused) return _colors.primary
        return _colors.outline
    }

    // Shadow Source (Background color & shape)
    Rectangle {
        id: shadowSource
        anchors.fill: parent
        radius: _shape.cornerFull
        color: containerColor
    }

    // Container
    Item {
        id: container
        anchors.fill: parent

        // Background
        Rectangle {
            id: backgroundRect
            anchors.fill: parent
            radius: _shape.cornerFull
            color: "transparent" // Rendered by shadowSource
            border.width: type === "outlined" ? 1 : 0
            border.color: type === "outlined" ? outlineColor : "transparent"

            // State Layer
            Rectangle {
                anchors.fill: parent
                radius: parent.radius
                color: stateLayerColor
                opacity: {
                    if (pressed) return _state.pressedStateLayerOpacity
                    if (hovered) return _state.hoverStateLayerOpacity
                    if (focused) return _state.focusStateLayerOpacity
                    return 0
                }
            }
            
            // Ripple Effect
            Ripple {
                id: ripple
                anchors.fill: parent
                clipRadius: backgroundRect.radius
                rippleColor: stateLayerColor
                enabled: control.enabled
                onClicked: (mouse) => control.clicked()
            }

            // Icon
            Text {
                anchors.centerIn: parent
                text: control.icon
                font.family: Theme.iconFont.name
                font.pixelSize: 24
                font.weight: Font.Normal
                color: contentColor
                visible: control.icon !== ""
            }
        }
    }
}

