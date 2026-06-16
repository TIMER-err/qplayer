import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: control
    
    property bool checked: false
    property string text: ""
    property bool enabled: true
    
    signal clicked()
    
    implicitWidth: rowLayout.implicitWidth
    implicitHeight: Math.max(48, rowLayout.implicitHeight)
    
    // Theme Colors Lookup
    property var _colors: Theme.color
    
    // Helper to resolve onSurface with alpha
    function disabledColor(alphaValue) {
        let c = Qt.color(_colors.onSurfaceColor)
        return Qt.rgba(c.r, c.g, c.b, alphaValue)
    }
    
    RowLayout {
        id: rowLayout
        anchors.verticalCenter: parent.verticalCenter
        anchors.left: parent.left
        spacing: 0
        
        // Radio Button Container (Touch Target)
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
                rippleColor: control.checked ? _colors.primary : _colors.onSurfaceColor
                
                onClicked: {
                    control.clicked()
                    // Note: RadioButton usually doesn't toggle off when clicked if already checked
                    if (!control.checked) {
                        control.checked = true
                    }
                }
            }
            
            // Radio Button Visual
            Rectangle {
                anchors.centerIn: parent
                width: 20
                height: 20
                radius: 10
                
                color: "transparent"
                border.width: 2
                border.color: {
                    if (!control.enabled) {
                        return control.disabledColor(0.38)
                    }
                    return control.checked ? _colors.primary : _colors.onSurfaceVariantColor
                }
                
                // Inner Circle (Selection Indicator)
                Rectangle {
                    anchors.centerIn: parent
                    width: 10
                    height: 10
                    radius: 5
                    color: {
                        if (!control.enabled) {
                            return control.disabledColor(0.38)
                        }
                        return _colors.primary
                    }
                    visible: control.checked
                    
                    scale: visible ? 1 : 0
                    Behavior on scale { NumberAnimation { duration: 100; easing.type: Easing.OutQuad } }
                }
                
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
                    control.clicked()
                    if (!control.checked) {
                        control.checked = true
                    }
                }
            }
        }
    }
}

