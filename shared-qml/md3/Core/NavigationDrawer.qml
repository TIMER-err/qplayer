import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: root
    
    // Layout properties for non-modal usage
    anchors.fill: modal ? parent : undefined
    Layout.fillHeight: !modal
    Layout.preferredWidth: !modal ? drawerWidth : 0
    
    z: modal ? 100 : 0 // Above everything if modal
    visible: modal ? _isOpen : true // Hidden by default if modal

    // Public properties
    default property alias content: contentLayout.data
    property string title: "Navigation Drawer"
    property real drawerWidth: 360 // Standard drawer width
    property bool modal: true
    property int spacing: 8 // Global spacing for list items
    
    // Model for menu items
    property var model: []
    property int currentIndex: 0
    property var currentItem: null
    signal itemClicked(var itemData)

    // Internal state
    property bool _isOpen: false

    function open() {
        if (modal) {
            _isOpen = true
            scrim.opacity = 1
            drawerPanel.x = 0
        }
    }

    function close() {
        if (modal) {
            scrim.opacity = 0
            drawerPanel.x = -drawerPanel.width
            closeTimer.start()
        }
    }
    
    Timer {
        id: closeTimer
        interval: 300
        onTriggered: root._isOpen = false
    }

    // Scrim (Only for modal)
    Rectangle {
        id: scrim
        visible: root.modal
        anchors.fill: parent
        color: "#52000000" // Scrim opacity 32%
        opacity: 0
        Behavior on opacity { NumberAnimation { duration: 300; easing.type: Easing.OutCubic } }
        
        MouseArea {
            anchors.fill: parent
            onClicked: root.close()
        }
    }

    // Drawer Panel
    Rectangle {
        id: drawerPanel
        width: root.drawerWidth
        height: parent.height
        color: Theme.color.surfaceContainer
        
        // Position logic
        x: root.modal ? -width : 0
        
        Behavior on x { 
            enabled: root.modal
            NumberAnimation { duration: 300; easing.type: Easing.OutCubic } 
        }

        // Shadow (Only for modal)
        layer.enabled: root.modal
        layer.effect: MultiEffect {
            shadowEnabled: true
            shadowBlur: 1.0
            shadowColor: "#40000000"
            shadowVerticalOffset: 0
            shadowHorizontalOffset: 2
        }

        ColumnLayout {
            anchors.fill: parent
            spacing: 0
            
            // Header
            Item {
                Layout.preferredHeight: 64
                
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.left: parent.left
                    anchors.leftMargin: 24
                    text: root.title
                    font.pixelSize: Theme.typography.titleSmall.size
                    font.family: Theme.typography.titleSmall.family
                    font.weight: Font.Bold
                    color: Theme.color.onSurfaceVariantColor
                }
            }
            
            // Content Container
            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                contentHeight: itemsColumn.implicitHeight
                clip: true
                
                // If model is present, show list; otherwise show custom content
                visible: root.model && root.model.length > 0
                
                ColumnLayout {
                    id: itemsColumn
                    width: parent.width
                    spacing: root.spacing
                    
                    Repeater {
                        model: root.model
                        delegate: Loader {
                            Layout.fillWidth: true
                            sourceComponent: {
                                if (modelData.type === "divider") return dividerComponent
                                if (modelData.children) return groupComponent
                                return itemComponent
                            }
                            
                            property var itemData: modelData
                            property int modelIndex: index
                            Binding {
                                target: item
                                property: "index"
                                value: modelIndex
                            }
                            Binding {
                                target: item
                                property: "itemData"
                                value: itemData
                            }
                        }
                    }
                }
            }

            // Custom Content (fallback or addition)
            Item {
                id: contentLayout
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: !root.model || root.model.length === 0
                clip: true
            }
        }
    }
    
    // Components
    
    Component {
        id: dividerComponent
        Item {
            width: parent.width
            implicitHeight: 17 // 8 + 1 + 8
            property int index: -1
            property var itemData: null
            
            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.leftMargin: 16
                anchors.rightMargin: 16
                anchors.verticalCenter: parent.verticalCenter
                height: 1
                color: Theme.color.outlineVariant
            }
        }
    }
    
    Component {
        id: itemComponent
        Item {
            id: drawerItem
            width: parent.width
            implicitHeight: 56
            property int index: -1
            property var itemData: null
            
            property bool selected: {
                if (!itemData) return false
                if (root.currentItem && root.currentItem === itemData) return true
                if (root.currentItem && itemData.text && root.currentItem.text === itemData.text) return true
                return false
            }
            
            Rectangle {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                radius: 28
                color: selected ? Theme.color.secondaryContainer : "transparent"
                
                Ripple {
                    anchors.fill: parent
                    clipRadius: 28
                    onClicked: {
                        if (!itemData.children) {
                            root.currentIndex = index
                            root.currentItem = itemData
                            root.itemClicked(itemData)
                            root.close()
                        }
                    }
                }
            }
            
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 28
                anchors.rightMargin: 36
                spacing: 12
                
                Text {
                    text: itemData.icon || ""
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    color: selected ? Theme.color.onSecondaryContainerColor : Theme.color.onSurfaceVariantColor
                    visible: text !== ""
                }
                
                Text {
                    Layout.fillWidth: true
                    text: itemData.text
                    font.pixelSize: Theme.typography.labelLarge.size
                    font.family: Theme.typography.labelLarge.family
                    font.weight: Font.Medium
                    color: selected ? Theme.color.onSecondaryContainerColor : Theme.color.onSurfaceVariantColor
                    elide: Text.ElideRight
                }
                
                // Badge or count
                Text {
                    text: itemData.badge || ""
                    font.pixelSize: Theme.typography.labelLarge.size
                    font.family: Theme.typography.labelLarge.family
                    color: selected ? Theme.color.onSecondaryContainerColor : Theme.color.onSurfaceVariantColor
                    visible: text !== ""
                }
            }
        }
    }
    
    Component {
        id: groupComponent
        ColumnLayout {
            id: groupRoot
            width: parent.width
            spacing: 0
            property int index: -1
            property var itemData: null
            
            property bool expanded: false
            
            // Group Header
            Item {
                Layout.fillWidth: true
                implicitHeight: 56
                
                Rectangle {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    radius: 28
                    color: "transparent"
                    
                    Ripple {
                        anchors.fill: parent
                        clipRadius: 28
                        onClicked: groupRoot.expanded = !groupRoot.expanded
                    }
                }
                
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 28
                    anchors.rightMargin: 36
                    spacing: 12
                    
                    Text {
                        text: itemData.icon || ""
                        font.family: Theme.iconFont.name
                        font.pixelSize: 24
                        color: Theme.color.onSurfaceVariantColor
                        visible: text !== ""
                    }
                    
                    Text {
                        Layout.fillWidth: true
                        text: itemData.text
                        font.pixelSize: Theme.typography.labelLarge.size
                        font.family: Theme.typography.labelLarge.family
                        font.weight: Font.Medium
                        color: Theme.color.onSurfaceVariantColor
                    }
                    
                    // Arrow Icon
                    Text {
                        text: groupRoot.expanded ? "expand_less" : "expand_more"
                        font.family: Theme.iconFont.name
                        font.pixelSize: 24
                        color: Theme.color.onSurfaceVariantColor
                    }
                }
            }
            
            // Children
            Item {
                Layout.fillWidth: true
                Layout.leftMargin: 12 // Indent for children
                clip: true
                
                implicitHeight: groupRoot.expanded ? childrenColumn.implicitHeight : 0
                Behavior on implicitHeight {
                    NumberAnimation { duration: 200; easing.type: Easing.OutCubic }
                }
                
                opacity: groupRoot.expanded ? 1 : 0
                Behavior on opacity {
                    NumberAnimation { duration: 200 }
                }

                ColumnLayout {
                    id: childrenColumn
                    width: parent.width
                    spacing: root.spacing
                    
                    Repeater {
                        model: itemData.children
                        delegate: Loader {
                            Layout.fillWidth: true
                            sourceComponent: {
                                if (modelData.type === "divider") return dividerComponent
                                if (modelData.children) return groupComponent // Recursive!
                                return itemComponent
                            }
                            
                            property var itemData: modelData
                            property int modelIndex: index
                            Binding {
                                target: item
                                property: "index"
                                value: modelIndex
                            }
                            Binding {
                                target: item
                                property: "itemData"
                                value: itemData
                            }
                        }
                    }
                }
            }
        }
    }
}

