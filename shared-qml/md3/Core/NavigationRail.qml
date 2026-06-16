import QtQuick
import QtQuick.Layouts
import md3.Core

Item {
    id: root
    property var model: [] // Array of {icon: "name", text: "label"}
    property int currentIndex: 0
    property bool extended: false
    
    // Width animation
    implicitWidth: extended ? 240 : 80
    Behavior on implicitWidth { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
    
    property Component header: null
    property Component footer: null
    property Component delegate: null // Optional custom delegate
    
    signal itemClicked(int index, var itemData)
    
    // Background
    Rectangle {
        anchors.fill: parent
        color: Theme.color.surface
    }
    
    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        
        // Header
        Loader {
            Layout.fillWidth: true
            sourceComponent: root.header
            visible: root.header !== null
        }
        
        // Items
        ColumnLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignHCenter
            Layout.topMargin: 12 // Space between Header and First Item
            spacing: 8// Space between rail items
            
            Repeater {
                model: root.model
                
                delegate: Item {
                    id: railItem
                    Layout.alignment: Qt.AlignHCenter
                    Layout.fillWidth: true
                    Layout.preferredHeight: 56 + (root.extended ? 0 : (itemLabel.visible ? 20 : 0)) // Adjust height based on label in rail mode
                    
                    Behavior on Layout.preferredHeight { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    
                    // Unified animation progress for perfect synchronization
                    property real expansionProgress: root.extended ? 1.0 : 0.0
                    Behavior on expansionProgress { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    
                    property bool selected: index === root.currentIndex
                    property var itemData: modelData
                    
                    // Helper to ensure we have a valid color object for RGBA extraction
                    property color _activeColor: Theme.color.secondaryContainer

                    // Indicator (Capsule / Full Width)
                    Rectangle {
                        id: indicator
                        height: 32 + (56 - 32) * railItem.expansionProgress // Interpolate height: 32 -> 56
                        radius: 16 + (28 - 16) * railItem.expansionProgress // Interpolate radius: 16 -> 28
                        color: railItem.selected ? railItem._activeColor : Qt.rgba(railItem._activeColor.r, railItem._activeColor.g, railItem._activeColor.b, 0)
                        
                        // Rail Mode: Centered horizontally, Top aligned
                        // Extended Mode: Fill width (minus margins), Centered vertically
                        
                        anchors.horizontalCenter: parent.horizontalCenter
                        width: root.extended ? (parent.width - 24) : 56
                        y: 0 // Always top aligned in item (item height adjusts)
                        
                        // Width follows parent width animation, no extra behavior needed to avoid lag
                        // Height and Radius are now driven by expansionProgress, so no Behavior needed on them directly
                        Behavior on color { ColorAnimation { duration: 200 } }
                    }
                    
                    // Icon
                    Text {
                        id: iconText
                        text: itemData.icon
                        font.family: Theme.iconFont.name
                        font.pixelSize: 24
                        color: railItem.selected ? Theme.color.onSecondaryContainerColor : Theme.color.onSurfaceVariantColor
                        
                        // Rail Mode: Center in indicator (56 width) -> 16dp margin
                        // Extended Mode: Left aligned inside indicator (margin 16) -> 16dp margin
                        // X is always relative to indicator + 16dp, so no animation needed for X relative to indicator
                        
                        anchors.left: indicator.left
                        anchors.leftMargin: 16
                        
                        // Vertical centering in indicator
                        // Start Y (Rail): (32 - 24)/2 = 4
                        // End Y (Extended): (56 - 24)/2 = 16
                        y: 4 + (16 - 4) * railItem.expansionProgress
                    }
                    
                    // Label
                    Text {
                        id: itemLabel
                        text: itemData.text
                        font.family: Theme.typography.labelMedium.family
                        font.pixelSize: Theme.typography.labelMedium.size
                        color: railItem.selected ? Theme.color.onSurfaceColor : Theme.color.onSurfaceVariantColor
                        visible: true
                        
                        // Rail Mode: Below indicator, centered in 80 width
                        // Extended Mode: Right of icon, vertically centered in 56 height
                        
                        // Interpolate position using expansionProgress for perfect synchronization
                        
                        // X Start (Rail): (80 - width) / 2
                        // X End (Extended): 12 (indicator.x) + 16 (icon margin) + 24 (icon width) + 12 (spacing) = 64
                        property real startX: (80 - width) / 2
                        property real endX: 64 
                        x: startX + (endX - startX) * railItem.expansionProgress
                        
                        // Y Start (Rail): 0 (indicator y) + 32 (indicator height) + 4 (spacing) = 36
                        // Y End (Extended): (56 - height) / 2
                        property real startY: 36
                        property real endY: (56 - height) / 2
                        y: startY + (endY - startY) * railItem.expansionProgress
                    }
                    
                    Ripple {
                        anchors.fill: indicator
                        clipRadius: indicator.radius
                        onClicked: {
                            root.currentIndex = index
                            root.itemClicked(index, itemData)
                        }
                    }
                }
            }
        }
        
        Item { Layout.fillHeight: true }
        
        // Footer
        Loader {
            Layout.fillWidth: true
            sourceComponent: root.footer
            visible: root.footer !== null
        }
    }
}
