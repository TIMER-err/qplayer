import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: control

    // Properties
    property string text: ""
    property string icon: "" // Leading icon source or character
    // Types: "assist", "filter", "input", "suggestion"
    property string type: "assist"
    property bool selected: false // For filter chips
    property bool showCloseIcon: type === "input"
    property bool enabled: true
    
    // Signals
    signal clicked()
    signal closeClicked()

    // Layout
    implicitWidth: Math.max(container.implicitWidth, 48) // Minimum touch target usually, but chips can be smaller visually.
                                                         // However, implicitWidth is for layout.
    // Selecting a filter chip reveals the checkmark, widening the content; animate
    // the width change instead of snapping.
    Behavior on implicitWidth { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
    implicitHeight: 32

    // Theme Colors Lookup
    property var _colors: Theme.color
    property var _typography: Theme.typography
    property var _shape: Theme.shape
    property var _state: Theme.state

    // Helper for disabled colors
    function disabledColor(colorVal, alphaValue) {
        let c = Qt.color(colorVal)
        return Qt.rgba(c.r, c.g, c.b, alphaValue)
    }

    function _getDisabledColor(colorString, alpha) {
        let c = Qt.color(colorString)
        return Qt.rgba(c.r, c.g, c.b, alpha)
    }

    // Color Logic
    property color containerColor: {
        if (!enabled) return "transparent" 
        
        if (type === "filter" && selected) {
            return _colors.secondaryContainer
        }
        return "transparent"
    }

    property color outlineColor: {
        if (!enabled) return _getDisabledColor(_colors.onSurfaceColor, 0.12)
        
        if (type === "filter" && selected) {
            return "transparent"
        }
        return _colors.outline
    }

    property color contentColor: {
        if (!enabled) return _getDisabledColor(_colors.onSurfaceColor, 0.38)
        
        if (type === "filter" && selected) {
            return _colors.onSecondaryContainerColor
        }
        return _colors.onSurfaceVariantColor
    }

    // State
    property bool hovered: enabled && mouseArea.containsMouse
    property bool pressed: enabled && mouseArea.pressed

    // Background & Border
    Rectangle {
        id: background
        anchors.fill: parent
        radius: _shape.cornerSmall // 8dp
        color: containerColor
        border.width: (type === "filter" && selected) ? 0 : 1
        border.color: outlineColor

        Behavior on color { ColorAnimation { duration: 150 } }
        Behavior on border.color { ColorAnimation { duration: 150 } }

        // State Layer
        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: contentColor
            opacity: {
                if (pressed) return _state.pressedStateLayerOpacity
                if (hovered) return _state.hoverStateLayerOpacity
                return 0
            }
            Behavior on opacity { NumberAnimation { duration: 150 } }
        }

        // Ripple
        Ripple {
            anchors.fill: parent
            clipRadius: background.radius
            enabled: control.enabled
            rippleColor: contentColor
            onClicked: {
                control.clicked()
                if (type === "filter") {
                    control.selected = !control.selected
                }
            }
        }
    }
    
    // Better Layout Structure
    RowLayout {
        id: container
        anchors.centerIn: parent
        spacing: 8
        
        // Dynamic Padding Logic
        // If has leading icon: Start padding 8
        // If no leading icon: Start padding 16
        
        Item { 
            Layout.preferredWidth: {
                if (type === "filter") {
                     // If selected (check shown) or icon shown: 8
                     if (control.selected || control.icon !== "") return 8
                     return 16
                }
                if (control.icon !== "") return 8
                return 16
            }
        }

        // Leading Icon (Assist/Input/Suggestion)
        Text {
            text: control.icon
            font.family: Theme.iconFont.name
            font.pixelSize: 18
            color: control.contentColor
            visible: control.icon !== "" && type !== "filter"
            Layout.alignment: Qt.AlignVCenter
        }

        // Filter Icon (Checkmark)
        Text {
            text: "check"
            font.family: Theme.iconFont.name
            font.pixelSize: 18
            color: control.contentColor
            visible: type === "filter" && control.selected
            Layout.alignment: Qt.AlignVCenter
            
            // Animation for checkmark?
            scale: visible ? 1 : 0
            Behavior on scale { NumberAnimation { duration: 150; easing.type: Easing.OutBack } }
        }
        
        // Filter Leading Icon (when not selected, optional)
        // For now, let's assume Filter chips only show check when selected, and maybe an icon when not selected if provided?
        // MD3: Filter chip can have a leading icon. When selected, it transforms to checkmark, or checkmark appears beside it?
        // Usually replaces it or appears. Let's simplify: 
        // If provided icon: show icon. If selected: show check.
        // If both: usually check replaces icon.
        Text {
            text: control.icon
            font.family: Theme.iconFont.name
            font.pixelSize: 18
            color: control.contentColor
            visible: type === "filter" && !control.selected && control.icon !== ""
            Layout.alignment: Qt.AlignVCenter
        }

        // Text Label
        Text {
            text: control.text
            font.family: _typography.labelLarge.family
            font.pixelSize: _typography.labelLarge.size
            font.weight: _typography.labelLarge.weight
            color: control.contentColor
            Layout.alignment: Qt.AlignVCenter
        }

        // Close Icon (Input Chip)
        Item {
            visible: control.showCloseIcon
            width: 18
            height: 18
            Layout.alignment: Qt.AlignVCenter
            
            Text {
                anchors.centerIn: parent
                text: "close"
                font.family: Theme.iconFont.name
                font.pixelSize: 18
                color: control.contentColor
            }
            
            MouseArea {
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: control.closeClicked()
            }
        }

        Item { 
            Layout.preferredWidth: {
                if (control.showCloseIcon) return 8
                return 16
            }
        }
    }
    
    // MouseArea for hover state (Ripple handles click)
    MouseArea {
        id: mouseArea
        anchors.fill: parent
        enabled: false 
        hoverEnabled: true
        acceptedButtons: Qt.NoButton
    }
}

