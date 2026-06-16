import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: root
    
    // Properties
    property var model: [] // Array of {icon: "name", text: "label"}
    default property alias content: stackLayout.data
    property int currentIndex: 0
    
    implicitWidth: 640
    implicitHeight: 480
    
    // Content Area
    StackLayout {
        id: stackLayout
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: navBar.top
        currentIndex: root.currentIndex
        clip: true
    }
    
    // Navigation Bar
    Rectangle {
        id: navBar
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        height: 80
        color: Theme.color.surfaceContainer
        
        RowLayout {
            anchors.fill: parent
            spacing: 8
            anchors.margins: 12
            
            Repeater {
                model: root.model
                
                Item {
                    id: navItem
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    
                    property bool selected: index === root.currentIndex
                    property var itemData: modelData
                    
                    // MouseArea for the whole item to trigger click (e.g. label), 
                    // but visual ripple is only on pill (handled by inner Ripple)
                    MouseArea {
                        anchors.fill: parent
                        onClicked: root.currentIndex = index
                        z: -1 // Ensure it's behind the content
                    }
                    
                    ColumnLayout {
                        anchors.centerIn: parent
                        spacing: 4
                        
                        // Icon Pill
                        Item {
                            id: iconPill
                            Layout.alignment: Qt.AlignHCenter
                            width: 64
                            height: 32
                            
                            Rectangle {
                                id: indicatorBg
                                anchors.centerIn: parent
                                height: parent.height
                                radius: 16
                                color: navItem.selected ? Theme.color.secondaryContainer : "transparent"
                                
                                // State-driven width for asymmetric animation (animate in, snap out)
                                width: 0
                                
                                states: State {
                                    name: "selected"
                                    when: navItem.selected
                                    PropertyChanges { target: indicatorBg; width: 64 }
                                }
                                
                                transitions: Transition {
                                    from: ""
                                    to: "selected"
                                    NumberAnimation { property: "width"; duration: 150; easing.type: Easing.OutQuad }
                                }
                            }

                            // qml4j divergence: upstream only comments about a ripple
                            // but never adds one; give the pill its MD3 state layer.
                            Ripple {
                                anchors.fill: parent
                                clipRadius: 16
                                rippleColor: navItem.selected ? Theme.color.onSecondaryContainerColor : Theme.color.onSurfaceVariantColor
                                onClicked: root.currentIndex = index
                            }

                            Text {
                                anchors.centerIn: parent
                                text: itemData.icon || ""
                                font.family: Theme.iconFont.name
                                font.pixelSize: 24
                                color: navItem.selected ? Theme.color.onSecondaryContainerColor : Theme.color.onSurfaceVariantColor
                            }
                        }
                        
                        // Label
                        Text {
                            Layout.alignment: Qt.AlignHCenter
                            text: itemData.text || ""
                            font.family: Theme.typography.labelMedium.family
                            font.pixelSize: Theme.typography.labelMedium.size
                            font.weight: navItem.selected ? Font.Bold : Font.Normal
                            color: navItem.selected ? Theme.color.onSurfaceColor : Theme.color.onSurfaceVariantColor
                        }
                    }
                }
            }
        }
    }
}

