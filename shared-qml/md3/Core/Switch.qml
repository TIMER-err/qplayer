import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: control
    
    property bool checked: false
    property string text: ""
    property bool enabled: true
    
    property bool showIcon: true 
    property string icon: "check"
    
    signal clicked()
    
    implicitWidth: rowLayout.implicitWidth
    implicitHeight: Math.max(32, rowLayout.implicitHeight)
    
    // Theme Colors Lookup
    property var _colors: Theme.color
    
    // Helper to resolve onSurface with alpha
    function disabledColor(alphaValue) {
        let c = Qt.color(_colors.onSurfaceColor)
        return Qt.rgba(c.r, c.g, c.b, alphaValue)
    }
    
    RowLayout {
        id: rowLayout
        anchors.centerIn: parent
        spacing: 12
        
        // Switch Container (Track + Thumb)
        Item {
            implicitWidth: 52
            implicitHeight: 32
            
            // Track
            Rectangle {
                anchors.fill: parent
                radius: 16
                
                color: {
                    if (!control.enabled) {
                        return control.checked ? control.disabledColor(0.12) : control.disabledColor(0.12)
                    }
                    return control.checked ? _colors.primary : _colors.surfaceContainerHighest
                }
                
                border.width: control.checked ? 0 : 2
                border.color: {
                    if (!control.enabled) {
                        return control.checked ? "transparent" : control.disabledColor(0.12)
                    }
                    return control.checked ? _colors.primary : _colors.outline
                }
                
                Behavior on color { ColorAnimation { duration: 150 } }
                Behavior on border.color { ColorAnimation { duration: 150 } }
            }
            
            // Thumb
            Rectangle {
                id: thumb
                width: control.checked ? 24 : 16
                height: width
                radius: width / 2
                
                anchors.verticalCenter: parent.verticalCenter
                // Calculate x position based on state
                // Unchecked: left margin 8 (center of 16px thumb is at 8+8=16 from left? No.)
                // Track width 52. 
                // Unchecked: Thumb (16) is at left with some margin. MD3: 7px margin for 16px thumb?
                // Let's align centers. 
                // Unchecked center x: 16 (approx)
                // Checked center x: 52 - 16 = 36 (approx)
                
                x: control.checked ? (parent.width - width - 4) : 8 // Simple positioning
                // Refined positioning:
                // Unchecked: margin 8 roughly implies x=8 if width=16. (Outline border is 2)
                // Checked: margin 4 roughly implies x=52-24-4 = 24.
                
                Behavior on x { NumberAnimation { duration: 150; easing.type: Easing.OutQuad } }
                Behavior on width { NumberAnimation { duration: 150; easing.type: Easing.OutQuad } }
                
                color: {
                    if (!control.enabled) {
                        return control.checked ? _colors.surface : control.disabledColor(0.38)
                    }
                    return control.checked ? _colors.onPrimaryColor : _colors.outline
                }
                
                // Icon (Checkmark)
                Text {
                    id: iconItem
                    anchors.centerIn: parent
                    text: control.icon
                    font.family: Theme.iconFont.name
                    font.pixelSize: 16
                    visible: control.showIcon
                    
                    // Scale animation for appearance
                    scale: control.checked ? 1 : 0
                    Behavior on scale { NumberAnimation { duration: 150; easing.type: Easing.OutBack } }
                    
                    color: {
                        if (!control.enabled) return control.disabledColor(0.38)
                        // In MD3, Checked icon on OnPrimary thumb is Primary color
                        return _colors.primary
                    }
                }

                Behavior on color { ColorAnimation { duration: 150 } }
            }
            
            // Ripple
            Ripple {
                anchors.fill: parent
                clipRadius: 16
                enabled: control.enabled
                rippleColor: control.checked ? _colors.primary : _colors.onSurfaceColor
                
                onClicked: {
                    control.checked = !control.checked
                    control.clicked()
                }
            }
        }
        
        // Label
        Text {
            text: control.text
            visible: control.text.length > 0
            font.family: Theme.typography.labelLarge.family
            font.pixelSize: Theme.typography.labelLarge.size
            font.weight: Theme.typography.labelLarge.weight
            color: control.enabled ? _colors.onSurfaceColor : control.disabledColor(0.38)
            Layout.fillWidth: true
            
            MouseArea {
                anchors.fill: parent
                enabled: control.enabled
                onClicked: {
                    control.checked = !control.checked
                    control.clicked()
                }
            }
        }
    }
}

