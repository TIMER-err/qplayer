import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: root
    
    // Properties
    property var model: [] // Array of {icon: "name", text: "label"}
    default property alias content: stackLayout.data
    property int currentIndex: 0
    property string type: "primary" // "primary" or "secondary"
    onCurrentIndexChanged: tabBar.updateIndicator(false)
    
    // Internal property to detect if any item has an icon (for Primary Tabs height)
    property bool _hasIcon: {
        for (var i = 0; i < model.length; i++) {
            if (model[i].icon && model[i].icon !== "") return true;
        }
        return false;
    }
    
    implicitWidth: 400
    implicitHeight: 300
    
    // Tab Bar
    Rectangle {
        id: tabBar
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        // Primary Tabs with icons are 72dp, otherwise 48dp (Secondary is always 48dp)
        height: (root.type === "primary" && root._hasIcon) ? 72 : 48
        color: Theme.color.surface
        
        RowLayout {
            anchors.fill: parent
            spacing: 0
            
            Repeater {
                id: tabRepeater
                model: root.model
                
                Item {
                    id: tabItem
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    
                    property bool selected: index === root.currentIndex
                    property var itemData: modelData
                    property real contentWidth: contentLayout.implicitWidth
                    
                    ColumnLayout {
                        id: contentLayout
                        anchors.centerIn: parent
                        spacing: 0
                        
                        // Icon (Only visible if model has icon)
                        Text {
                            visible: !!itemData.icon
                            Layout.alignment: Qt.AlignHCenter
                            text: itemData.icon || ""
                            font.family: Theme.iconFont.name
                            font.pixelSize: 24
                            color: tabItem.selected ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                        }

                        // Label
                        Text {
                            Layout.alignment: Qt.AlignHCenter
                            text: itemData.text || ""
                            font.family: Theme.typography.titleSmall.family
                            font.pixelSize: Theme.typography.titleSmall.size
                            font.weight: Theme.typography.titleSmall.weight
                            color: tabItem.selected ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                        }
                    }
                    
                    Ripple {
                        anchors.fill: parent
                        onClicked: root.currentIndex = index
                    }
                }
            }
        }
        
        // Sliding Indicator
        QtObject {
            id: indicatorProxy
            property real left: 0
            property real right: 0
        }

        Rectangle {
            id: indicator
            anchors.bottom: parent.bottom
            
            // Primary: 3dp height, rounded top corners (radius 3)
            // Secondary: 2dp height, no rounded corners (radius 0)
            height: root.type === "primary" ? 3 : 2
            radius: root.type === "primary" ? 3 : 0
            color: Theme.color.primary

            // Mask bottom corners if primary
            Rectangle {
                visible: root.type === "primary"
                anchors.bottom: parent.bottom
                width: parent.width
                height: parent.height / 2
                color: parent.color
            }
            
            x: indicatorProxy.left
            width: Math.max(0, indicatorProxy.right - indicatorProxy.left)
            
            ParallelAnimation {
                id: moveAnim
                property int duration: 240
                property bool moveRight: true
                
                NumberAnimation {
                    target: indicatorProxy
                    property: "left"
                    duration: moveAnim.duration
                    easing.type: moveAnim.moveRight ? Easing.InOutSine : Easing.OutSine
                }
                NumberAnimation {
                    target: indicatorProxy
                    property: "right"
                    duration: moveAnim.duration
                    easing.type: moveAnim.moveRight ? Easing.OutSine : Easing.InOutSine
                }
            }
        }

        function updateIndicator(instant) {
            var currentTab = tabRepeater.itemAt(root.currentIndex)
            if (!currentTab) return

            var targetX = currentTab.x
            var targetW = currentTab.width

            // Primary: Content width (short)
            if (root.type === "primary") {
                 targetX = currentTab.x + (currentTab.width - currentTab.contentWidth) / 2
                 targetW = currentTab.contentWidth
            }

            var targetRight = targetX + targetW

            if (instant) {
                indicatorProxy.left = targetX
                indicatorProxy.right = targetRight
            } else {
                moveAnim.moveRight = targetX > indicatorProxy.left
                moveAnim.animations[0].to = targetX
                moveAnim.animations[1].to = targetRight
                moveAnim.start()
            }
        }

        // Connections removed, handler moved to root

        
        // Wait for layout to settle
        Timer {
            interval: 10
            running: true
            repeat: false
            onTriggered: tabBar.updateIndicator(true)
        }
        
        // Bottom border
        Rectangle {
            anchors.bottom: parent.bottom
            width: parent.width
            height: 1
            color: Theme.color.surfaceVariant
            z: -1
        }
    }
    
    // Content Area
    StackLayout {
        id: stackLayout
        anchors.top: tabBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        currentIndex: root.currentIndex
        clip: true
    }
}

