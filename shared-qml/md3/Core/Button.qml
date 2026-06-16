import QtQuick
import QtQuick.Effects
import md3.Core
Item {
    id: control

    property string text: ""
    property string icon: "" // Icon source or character
    // Types: "elevated", "filled", "filledTonal", "outlined", "text"
    property string type: "filled"
    property bool enabled: true

    // Layout configuration
    property real horizontalPadding: type === "text" ? 12 : 24
    property real verticalPadding: 0
    property real spacing: 8

    implicitWidth: Math.max((control.contentItem ? control.contentItem.implicitWidth : contentRow.width) + horizontalPadding * 2, 48)
    implicitHeight: 40

    // State
    property bool hovered: enabled && ripple.containsMouse
    property bool pressed: enabled && ripple.pressed
    property bool focused: enabled && activeFocus
    
    // Custom Content Item
    property Item contentItem
    onContentItemChanged: {
        if (contentItem) {
            contentItem.parent = backgroundRect
            contentItem.anchors.centerIn = backgroundRect
        }
    }
    
    signal clicked()

    // Theme Colors Lookup
    property var _colors: Theme.color
    property var _typography: Theme.typography
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
            case "elevated": return _colors.surfaceContainerLow
            case "filled": return _colors.primary
            case "filledTonal": return _colors.secondaryContainer
            case "outlined": return "transparent"
            case "text": return "transparent"
            default: return _colors.primary
        }
    }

    property color contentColor: {
        if (!enabled) return disabledColor(0.38) // Disabled content
        switch (type) {
            case "elevated": return _colors.primary
            case "filled": return _colors.onPrimaryColor
            case "filledTonal": return _colors.onSecondaryContainerColor
            case "outlined": return _colors.primary
            case "text": return _colors.primary
            default: return _colors.onPrimaryColor
        }
    }

    property color stateLayerColor: {
         switch (type) {
            case "filled": return _colors.onPrimaryColor
            case "filledTonal": return _colors.onSecondaryContainerColor
            default: return _colors.primary // For elevated, outlined, text -> usually primary or onSurface
        }
    }

    property color outlineColor: {
        if (!enabled) return disabledColor(0.12)
        if (focused) return _colors.primary
        return _colors.outline
    }

    // Elevation
    property int elevationLevel: {
        if (!enabled) return _elevation.level0
        if (type === "elevated") {
            // User requested hover shadow to remain unchanged (Level 1)
            return _elevation.level1
        }
        if (type === "filled" || type === "filledTonal") {
             // MD3 spec suggests level1 on hover, but user prefers flat
             if (pressed) return _elevation.level0
             if (hovered) return _elevation.level0 // Was level1
             return _elevation.level0
        }
        return _elevation.level0
    }

    // Shadow Source (Background color & shape)
    Rectangle {
        id: shadowSource
        anchors.fill: parent
        radius: _shape.cornerFull
        color: containerColor
        visible: elevationLevel === 0 // Hide when elevated (MultiEffect renders it)
    }

    // Shadow Effect (Only visible when elevated)
    MultiEffect {
        anchors.fill: shadowSource
        source: shadowSource
        visible: elevationLevel > 0
        
        shadowEnabled: true
        shadowColor: _colors.shadow
        shadowBlur: elevationLevel * 0.06 // Reduced multiplier for subtler shadow (was 0.1)
        shadowVerticalOffset: elevationLevel * 1.5 // Reduced offset (was 2)
        shadowOpacity: 0.15 + (elevationLevel * 0.04) // Reduced opacity (was 0.2 + ...)
        blurMax: 32
        
        // Ensure shadow is drawn behind the source visually (although MultiEffect draws both)
        z: -1
    }

    // Container
    Item {
        id: container
        anchors.fill: parent

        // Background (Transparent, provides shape/border for children)
        Rectangle {
            id: backgroundRect
            anchors.fill: parent
            radius: _shape.cornerFull
            color: "transparent" // Rendered by shadowSource/MultiEffect
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
                // qml4j divergence: upstream Button omits this Behavior (unlike
                // Chip/Checkbox), so the state layer popped instantly on press.
                // Animate it to match the rest of MD3.
                Behavior on opacity { NumberAnimation { duration: 100 } }
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

            // Content (Text/Icon)
            Row {
                id: contentRow
                visible: !control.contentItem
                anchors.centerIn: parent
                spacing: control.spacing
                
                // Icon
                Text {
                    text: control.icon
                    font.family: Theme.iconFont.name
                    font.pixelSize: 18
                    font.weight: Font.Normal
                    color: contentColor
                    visible: control.icon !== ""
                    verticalAlignment: Text.AlignVCenter
                    anchors.verticalCenter: parent.verticalCenter
                }

                Text {
                    text: control.text
                    font.family: _typography.labelLarge.family
                    font.pixelSize: _typography.labelLarge.size
                    font.weight: _typography.labelLarge.weight
                    font.capitalization: Font.MixedCase
                    color: contentColor
                    verticalAlignment: Text.AlignVCenter
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
        }
    }
}

