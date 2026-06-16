import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: control
    
    property bool checked: false
    property bool indeterminate: false
    property string text: ""
    property bool enabled: true
    
    // Read-only property to determine visual state
    readonly property bool _visualChecked: checked || indeterminate
    
    signal clicked()
    
    // Theme Colors Lookup
    property var _colors: Theme.color
    
    // Helper to resolve onSurface with alpha
    function disabledColor(alphaValue) {
        let c = Qt.color(_colors.onSurfaceColor)
        return Qt.rgba(c.r, c.g, c.b, alphaValue)
    }
    
    implicitWidth: rowLayout.implicitWidth
    implicitHeight: Math.max(48, rowLayout.implicitHeight)
    
    // Opacity handled internally for specific parts
    
    RowLayout {
        id: rowLayout
        anchors.verticalCenter: parent.verticalCenter
        anchors.left: parent.left
        spacing: 0
        
        // Checkbox Container (Touch Target)
        Item {
            implicitWidth: 48
            implicitHeight: 48
            
            // Ripple
            Ripple {
                anchors.centerIn: parent
                width: 40
                height: 40
                clipRadius: 20
                enabled: control.enabled
                rippleColor: control.checked || control.indeterminate ? _colors.primary : _colors.onSurfaceColor
                
                onClicked: {
                    control.checked = !control.checked
                    control.indeterminate = false // clear indeterminate on interaction
                    control.clicked()
                }
            }
            
            // Checkbox Visual
            Rectangle {
                anchors.centerIn: parent
                width: 18
                height: 18
                radius: 2
                
                color: {
                    if (!control.enabled) {
                        return control._visualChecked ? control.disabledColor(0.38) : "transparent"
                    }
                    return control._visualChecked ? _colors.primary : "transparent"
                }
                
                border.width: control._visualChecked ? 0 : 2
                border.color: {
                    if (!control.enabled) {
                        return control.disabledColor(0.38)
                    }
                    return _colors.onSurfaceVariantColor
                }
                
                // Icon
                Text {
                    anchors.centerIn: parent
                    text: control.indeterminate ? "remove" : "check"
                    font.family: Theme.iconFont.name
                    font.pixelSize: 14 
                    color: {
                        if (!control.enabled) return _colors.surface // White/Surface on Gray box
                        return _colors.onPrimaryColor
                    }
                    visible: control._visualChecked
                    scale: visible ? 1 : 0
                    Behavior on scale { NumberAnimation { duration: 100 } }
                }
                
                Behavior on color { ColorAnimation { duration: 100 } }
                Behavior on border.color { ColorAnimation { duration: 100 } }
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
            Layout.leftMargin: 4
            Layout.rightMargin: 16 
            
            MouseArea {
                anchors.fill: parent
                enabled: control.enabled
                onClicked: {
                    control.checked = !control.checked
                    control.indeterminate = false
                    control.clicked()
                }
            }
        }
    }
}

