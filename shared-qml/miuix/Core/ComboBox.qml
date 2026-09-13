import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import miuix.Core
Item {
    id: control
    
    // API
    property var model: [] // Array of strings or objects { text, icon, ... }
    property int currentIndex: -1
    property string label: ""
    property string leadingIcon: ""
    property string trailingIcon: "arrow_drop_down"
    property string type: "filled" // "filled" | "outlined"
    property bool enabled: true
    
    // Read-only
    property string currentText: {
        if (currentIndex < 0 || currentIndex >= model.length) return ""
        var item = model[currentIndex]
        return (typeof item === 'string') ? item : (item.text || "")
    }
    
    property var currentValue: {
        if (currentIndex < 0 || currentIndex >= model.length) return null
        var item = model[currentIndex]
        return (typeof item === 'string') ? item : (item.value !== undefined ? item.value : item.text)
    }

    signal activated(int index)

    implicitWidth: 240
    implicitHeight: 56
    
    // Theme
    property var _colors: Theme.color
    property var _shape: Theme.shape
    property var _typography: Theme.typography
    property var _state: Theme.state
    
    // Internal State
    property bool menuOpen: false
    property bool isFocused: menuOpen || activeFocus
    // A label-less outlined combo (the settings dropdown) must not create the
    // floating-label notch. Previously currentText alone floated an empty Text,
    // whose background mask rendered as a small dark square at the top-left.
    property bool isFloated: label.length > 0
            && (isFocused || currentText.length > 0 || leadingIcon !== "")
    property bool _actuallyFloated: label.length > 0
            && (isFocused || currentText.length > 0)

    // Menu Model Adapter
    property var _menuModel: {
        var m = []
        for(var i=0; i<model.length; i++) {
            var item = model[i]
            var itemText = (typeof item === 'string') ? item : item.text
            var itemIcon = (typeof item === 'object' && item.icon) ? item.icon : ""
            
            m.push({
                text: itemText,
                icon: itemIcon,
                action: function(idx) { 
                    return function() { 
                        control.currentIndex = idx; 
                        control.activated(idx) 
                    } 
                }(i)
            })
        }
        return m
    }

    // Background
    Rectangle {
        id: background
        anchors.fill: parent
        radius: (control.type === "filled") ? _shape.cornerExtraSmall : _shape.cornerExtraSmall
        
        color: (control.type === "filled") ? _colors.surfaceContainerHighest : "transparent"
        
        border.width: 0

        OutlinedBorder {
            anchors.fill: parent
            visible: control.type === "outlined"
            cornerRadius: parent.radius
            strokeWidth: 1
            strokeColor: _colors.outline
            notchVisible: control._actuallyFloated && control.label.length > 0
            notchX: content.x + labelText.x - 4
            notchWidth: labelText.width * labelText.scale + 8
        }
        
        // Focus Border for Outlined (Overlay)
        OutlinedBorder {
            anchors.fill: parent
            cornerRadius: parent.radius
            strokeWidth: 2
            strokeColor: _colors.primary
            notchVisible: control._actuallyFloated && control.label.length > 0
            notchX: content.x + labelText.x - 4
            notchWidth: labelText.width * labelText.scale + 8
            opacity: (control.type === "outlined" && control.isFocused) ? 1 : 0
            visible: control.type === "outlined"
            
            Behavior on opacity { NumberAnimation { duration: 150 } }
        }

        // Bottom Line for Filled
        Rectangle {
            visible: control.type === "filled"
            anchors.bottom: parent.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            height: control.isFocused ? 2 : 1
            color: control.isFocused ? _colors.primary : _colors.onSurfaceVariantColor

            Behavior on height { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
            Behavior on color { ColorAnimation { duration: 150 } }
        }
    }

    // Interaction
    MouseArea {
        id: mouseArea
        anchors.fill: parent
        enabled: control.enabled
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: {
            control.forceActiveFocus()
            control.menuOpen = true
            menu.open(control, 0, control.height)
        }
    }
    
    // Content Layout
    Item {
        id: content
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        
        // Leading Icon
        Text {
            id: iconDisplay
            visible: control.leadingIcon !== ""
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            text: control.leadingIcon
            font.family: Theme.iconFont.name
            font.pixelSize: 24
            color: control.enabled ? _colors.onSurfaceVariantColor : _colors.onSurfaceColor
            opacity: control.enabled ? 1 : 0.38
        }
        
        // Floating Label
        Text {
            id: labelText
            text: control.label
            visible: control.label.length > 0
            
            // Layout Logic:
            // restingX: If icon present, 16 (icon) + 16 (gap) = 32? No.
            // iconDisplay width is implicitWidth (24px) usually.
            // Previous code used anchors.left: iconDisplay.right (with margin 16).
            // So X was iconDisplay.x + iconDisplay.width + 16.
            // iconDisplay.x is 0 (relative to this Item).
            // So restingX = iconDisplay.width + 16.
            // floatedX = 0 (Aligned with container start, effectively "above" icon).
            
            property real restingX: iconDisplay.visible ? (iconDisplay.width + 16) : 0
            property real floatedX: 0
            
            x: control._actuallyFloated ? floatedX : restingX
            y: control._actuallyFloated ? (control.type === "filled" ? 8 : -10) : (control.height - height) / 2
            scale: control._actuallyFloated ? 0.75 : 1.0
            transformOrigin: Item.TopLeft
            
            font.family: _typography.bodyLarge.family
            font.pixelSize: _typography.bodyLarge.size
            color: control.isFocused ? _colors.primary : _colors.onSurfaceVariantColor
            
            Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            Behavior on y { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            Behavior on color { ColorAnimation { duration: 150 } }
            
        }
        
        // Selected Text
        Text {
            visible: control.currentText.length > 0
            anchors.left: parent.left
            anchors.leftMargin: iconDisplay.visible ? (iconDisplay.width + 16) : 0
            anchors.right: trailingIconDisplay.left
            anchors.rightMargin: 16
            anchors.verticalCenter: parent.verticalCenter
            anchors.verticalCenterOffset: (control.type === "filled" && control._actuallyFloated) ? 8 : 0 
            
            text: control.currentText
            font.family: _typography.bodyLarge.family
            font.pixelSize: _typography.bodyLarge.size
            color: control.enabled ? _colors.onSurfaceColor : _colors.onSurfaceColor
            opacity: control.enabled ? 1 : 0.38
            elide: Text.ElideRight
        }
        
        // Trailing Icon
        Text {
            id: trailingIconDisplay
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            text: control.trailingIcon
            font.family: Theme.iconFont.name
            font.pixelSize: 24
            color: control.enabled ? _colors.onSurfaceVariantColor : _colors.onSurfaceColor
            opacity: control.enabled ? 1 : 0.38
            
            rotation: control.menuOpen ? 180 : 0
            Behavior on rotation { NumberAnimation { duration: 200 } }
        }
    }
    
    // The Menu
    Menu {
        id: menu
        model: control._menuModel
        width: control.width
        onClosed: control.menuOpen = false
    }
}
