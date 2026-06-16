import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: control
    
    // API
    property var model: [] // Array of objects: { text, icon, trailingText, trailingIcon, type: "item"|"separator", action: func, enabled: bool, subItems: [] }
    property int menuPadding: 8
    
    // Theme Colors
    property var _colors: Theme.color
    property var _shape: Theme.shape
    property var _typography: Theme.typography
    property var _elevation: Theme.elevation
    property var _state: Theme.state

    // Signals
    signal closed()

    // Hidden by default, takes no space
    visible: false
    width: 0
    height: 0
    
    // The Overlay Layer
    Item {
        id: overlayLayer
        visible: false
        
        // Helper to close menu
        function close() { 
            startExitAnimation()
        }
        
        function forceClose() {
             overlayLayer.visible = false
             overlayLayer.parent = control
             overlayLayer.anchors.fill = undefined
        }
        
        // Scrim
        MouseArea {
            anchors.fill: parent
            onClicked: overlayLayer.close()
            z: -1
        }
        
        // Popup Container (This scales up/down, carrying shadow and content)
        Item {
            id: popupContainer
            width: Math.max(112, contentColumn.implicitWidth)
            height: contentColumn.implicitHeight + (control.menuPadding * 2)
            
            // Animation Properties
            scale: 0.8
            opacity: 0
            transformOrigin: Item.TopLeft
            
            states: [
                State {
                    name: "open"
                    PropertyChanges { target: popupContainer; scale: 1.0; opacity: 1.0 }
                },
                State {
                    name: "closed"
                    PropertyChanges { target: popupContainer; scale: 0.8; opacity: 0.0 }
                }
            ]
            
            transitions: [
                Transition {
                    from: "closed"; to: "open"
                    NumberAnimation { properties: "scale"; duration: 200; easing.type: Easing.OutCubic }
                    NumberAnimation { properties: "opacity"; duration: 150; easing.type: Easing.Linear }
                },
                Transition {
                    from: "open"; to: "closed"
                    NumberAnimation { properties: "opacity"; duration: 150; easing.type: Easing.Linear }
                    NumberAnimation { properties: "scale"; duration: 150; easing.type: Easing.InCubic }
                    onRunningChanged: {
                        if (!running && popupContainer.state === "closed") {
                             overlayLayer.forceClose()
                             control.closed()
                        }
                    }
                }
            ]

            // Shadow Source
            Rectangle {
                id: shadowSource
                anchors.fill: parent
                radius: menuBackground.radius
                color: _colors.surfaceContainer
                visible: false
            }
            
            // Shadow Effect
            MultiEffect {
                source: shadowSource
                anchors.fill: shadowSource
                shadowEnabled: true
                shadowColor: _colors.shadow
                shadowBlur: _elevation.level2 * 0.5
                shadowVerticalOffset: _elevation.level2
                shadowOpacity: 0.2
                z: 0
                // Opacity is inherited from parent (popupContainer), no need to double apply
            }
            
            // Menu Background & Content
            Rectangle {
                id: menuBackground
                z: 1
                anchors.fill: parent
                color: _colors.surfaceContainer
                radius: _shape.cornerExtraSmall
                clip: true
                
                ColumnLayout {
                    id: contentColumn
                    spacing: 0
                    anchors.top: parent.top
                    anchors.topMargin: control.menuPadding
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: control.menuPadding
                    anchors.left: parent.left
                    anchors.right: parent.right
                    
                    Repeater {
                        model: control.model
                        delegate: Loader {
                            Layout.fillWidth: true
                            sourceComponent: modelData.type === "separator" ? separatorComponent : itemComponent
                            
                            property var itemData: modelData
                            
                            required property var modelData
                            required property int index
                        }
                    }
                }
            }
        }
    }
    
    // Animation Helpers
    function startEntranceAnimation() {
        popupContainer.state = "closed" // Reset
        popupContainer.state = "open"
    }
    
    function startExitAnimation() {
        popupContainer.state = "closed"
    }

    // Components
    Component {
        id: separatorComponent
        Item {
            implicitWidth: 112
            implicitHeight: 17 // 1px + 16dp padding
            Layout.fillWidth: true
            Rectangle {
                anchors.centerIn: parent
                width: parent.width
                height: 1
                color: _colors.outlineVariant
            }
        }
    }
    
    Component {
        id: itemComponent
        Item {
            id: menuItem
            implicitWidth: Math.max(112, row.implicitWidth + 24)
            implicitHeight: 48
            Layout.fillWidth: true
            
            property bool itemEnabled: itemData.enabled !== undefined ? itemData.enabled : true
            property bool hasSubMenu: !!itemData.subItems && itemData.subItems.length > 0
            
            // Submenu Loader
            Loader {
                id: subMenuLoader
                active: hasSubMenu
                // Use local file reference for recursion
                source: "Menu.qml" 
                onLoaded: {
                    item.model = itemData.subItems
                }
            }
            
            // State Layer
            Rectangle {
                anchors.fill: parent
                color: _colors.onSurfaceColor
                opacity: {
                    if (!itemEnabled) return 0
                    if (mouseArea.pressed) return _state.pressedStateLayerOpacity
                    if (mouseArea.containsMouse || (subMenuLoader.status === Loader.Ready && subMenuLoader.item.visible)) return _state.hoverStateLayerOpacity
                    return 0
                }
                Behavior on opacity { NumberAnimation { duration: 150 } }
            }
            
            // Ripple
            Ripple {
                anchors.fill: parent
                enabled: itemEnabled
                rippleColor: _colors.onSurfaceColor
                onClicked: {
                    if (hasSubMenu) {
                        // Open submenu
                        var sub = subMenuLoader.item
                        if (sub) {
                             sub.open(menuItem, menuItem.width, -8) // Slight overlap top
                        }
                    } else {
                        if (itemData.action && typeof itemData.action === "function") {
                            itemData.action()
                        }
                        if (control && control.close) control.close()
                    }
                }
            }
            
            RowLayout {
                id: row
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 12
                
                // Icon
                Text {
                    visible: !!itemData.icon
                    text: itemData.icon || ""
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Text
                Text {
                    text: itemData.text || ""
                    font.family: _typography.labelLarge.family
                    font.pixelSize: _typography.labelLarge.size
                    font.weight: _typography.labelLarge.weight
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    elide: Text.ElideRight
                    verticalAlignment: Text.AlignVCenter
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Trailing Text
                Text {
                    visible: !!itemData.trailingText
                    text: itemData.trailingText || ""
                    font.family: _typography.labelLarge.family
                    font.pixelSize: _typography.labelLarge.size
                    font.weight: _typography.labelLarge.weight
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    horizontalAlignment: Text.AlignRight
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Trailing Icon (or Arrow for submenu)
                Text {
                    visible: !!itemData.trailingIcon || hasSubMenu
                    text: hasSubMenu ? "arrow_right" : (itemData.trailingIcon || "")
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    color: _colors.onSurfaceVariantColor
                    opacity: itemEnabled ? 1 : 0.38
                    Layout.alignment: Qt.AlignVCenter
                }
            }
            
            MouseArea {
                id: mouseArea
                anchors.fill: parent
                enabled: itemEnabled
                hoverEnabled: true
                acceptedButtons: Qt.NoButton
                
                // Auto-open submenu on hover (desktop style)
                Timer {
                    id: hoverTimer
                    interval: 200
                    onTriggered: {
                         if (hasSubMenu && mouseArea.containsMouse) {
                              var sub = subMenuLoader.item
                              if (sub) sub.open(menuItem, menuItem.width, -8)
                         }
                    }
                }
                
                onEntered: {
                    if (hasSubMenu) hoverTimer.start()
                }
                onExited: {
                    hoverTimer.stop()
                }
            }
        }
    }
    
    // Logic
    function open(target, xOffset, yOffset) {
        if (!target) return
        
        // Find Root
        var root = control
        while (root.parent) {
            root = root.parent
        }
        
        if (root) {
            overlayLayer.parent = root
            overlayLayer.z = 99999
            overlayLayer.anchors.fill = root
            
            var targetPos = root.mapFromItem(target, 0, 0)
            var finalX = targetPos.x + (xOffset !== undefined ? xOffset : 0)
            var finalY = targetPos.y + (yOffset !== undefined ? yOffset : 0)
            
            // Bounds check
            if (finalX + popupContainer.width > root.width) {
                 finalX = root.width - popupContainer.width - 8
                 popupContainer.transformOrigin = Item.TopRight // Animate from right if flipped
            } else {
                 popupContainer.transformOrigin = Item.TopLeft
            }
            
            if (finalY + popupContainer.height > root.height) {
                 finalY = root.height - popupContainer.height - 8
                 popupContainer.transformOrigin = (popupContainer.transformOrigin === Item.TopRight) ? Item.BottomRight : Item.BottomLeft
            }
            
            if (finalX < 8) finalX = 8
            if (finalY < 8) finalY = 8
            
            popupContainer.x = finalX
            popupContainer.y = finalY
            
            overlayLayer.visible = true
            startEntranceAnimation()
        }
    }
    
    function close() {
        overlayLayer.close()
    }
}
