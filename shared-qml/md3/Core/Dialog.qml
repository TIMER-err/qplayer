import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: control
    
    // API
    property string title: ""
    property string text: ""
    property string icon: ""
    
    property string acceptText: "OK"
    property string rejectText: "Cancel"
    property bool showAcceptButton: true
    property bool showRejectButton: true
    
    // Signals
    signal accepted()
    signal rejected()
    signal closed()
    
    // Custom content support
    default property alias content: contentPlaceholder.data
    
    // Theme Helpers
    property var _colors: Theme.color
    property var _typography: Theme.typography
    property var _shape: Theme.shape
    
    // Internal
    visible: false
    
    function open() {
        var root = control
        while (root.parent) {
            root = root.parent
        }
        
        if (root) {
            overlayLayer.parent = root
            overlayLayer.z = 99999
            overlayLayer.anchors.fill = root
            
            // Stop any running animations
            exitAnimation.stop()
            enterAnimation.stop()
            
            // Reset properties for entry
            animationWrapper.scale = 0.9
            animationWrapper.opacity = 0.0
            scrim.opacity = 0.0
            
            overlayLayer.visible = true
            enterAnimation.start()
        }
    }
    
    function close() {
        // Stop any running animations
        enterAnimation.stop()
        exitAnimation.stop()
        
        exitAnimation.start()
    }
    
    // Overlay Layer
    Item {
        id: overlayLayer
        visible: false
        
        // Animations
        ParallelAnimation {
            id: enterAnimation
            
            NumberAnimation { 
                target: scrim
                property: "opacity"
                from: 0.0
                to: 0.32
                duration: 150
                easing.type: Easing.OutQuad
            }
            
            NumberAnimation { 
                target: animationWrapper
                property: "scale"
                from: 0.9
                to: 1.0
                duration: 250
                easing.type: Easing.OutBack
                easing.overshoot: 1.0 // Gentle overshoot
            }
            
            NumberAnimation { 
                target: animationWrapper
                property: "opacity"
                from: 0.0
                to: 1.0
                duration: 150
            }
        }
        
        ParallelAnimation {
            id: exitAnimation
            onFinished: {
                overlayLayer.visible = false
                control.closed()
            }
            
            NumberAnimation { 
                target: scrim
                property: "opacity"
                from: 0.32
                to: 0.0
                duration: 150
            }
            
            NumberAnimation { 
                target: animationWrapper
                property: "opacity"
                from: 1.0
                to: 0.0
                duration: 100
            }
            
            // Optional: slight scale down on exit
             NumberAnimation { 
                target: animationWrapper
                property: "scale"
                from: 1.0
                to: 0.95
                duration: 100
            }
        }
        
        // Scrim
        Rectangle {
            id: scrim
            anchors.fill: parent
            color: "#000000"
            opacity: 0.0 // Controlled by animation
            
            MouseArea {
                anchors.fill: parent
                onClicked: control.close()
                onWheel: (wheel) => {} // Block scroll propagation
            }
        }
        
        // Wrapper for Dialog + Shadow to animate them together
        Item {
            id: animationWrapper
            anchors.centerIn: parent
            width: Math.min(560, Math.max(280, parent.width - 48))
            height: dialogContainer.height
            
            // Dialog Container
            Rectangle {
                id: dialogContainer
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                height: mainColumn.implicitHeight + 48 // Padding
                radius: 28 // MD3 Extra Large
                color: _colors.surfaceContainerHigh
                
                // Block clicks from passing through to scrim
                MouseArea {
                    anchors.fill: parent
                }

                ColumnLayout {
                    id: mainColumn
                    anchors.top: parent.top
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.topMargin: 24
                    anchors.leftMargin: 24
                    anchors.rightMargin: 24
                    spacing: 16
                    
                    // Icon
                    Text {
                        visible: control.icon !== ""
                        text: control.icon
                        font.family: Theme.iconFont.name
                        font.pixelSize: 24
                        color: _colors.secondary
                        Layout.alignment: Qt.AlignHCenter
                        Layout.bottomMargin: 0
                    }
                    
                    // Headline
                    Text {
                        visible: control.title !== ""
                        text: control.title
                        font.family: _typography.headlineSmall.family
                        font.pixelSize: _typography.headlineSmall.size
                        font.weight: _typography.headlineSmall.weight
                        color: _colors.onSurfaceColor
                        Layout.fillWidth: true
                        Layout.alignment: control.icon !== "" ? Qt.AlignHCenter : Qt.AlignLeft
                        horizontalAlignment: control.icon !== "" ? Text.AlignHCenter : Text.AlignLeft
                        wrapMode: Text.Wrap
                    }
                    
                    // Supporting Text
                    Text {
                        visible: control.text !== ""
                        text: control.text
                        font.family: _typography.bodyMedium.family
                        font.pixelSize: _typography.bodyMedium.size
                        font.weight: _typography.bodyMedium.weight
                        color: _colors.onSurfaceVariantColor
                        Layout.fillWidth: true
                        wrapMode: Text.Wrap
                    }
                    
                    // Custom Content
                    Item {
                        id: contentPlaceholder
                        Layout.fillWidth: true
                        Layout.preferredHeight: childrenRect.height
                        visible: children.length > 0
                    }
                    
                    // Actions
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.topMargin: 8
                        spacing: 8
                        Layout.alignment: Qt.AlignRight

                        Item { Layout.fillWidth: true } // Spacer

                        Button {
                            visible: control.showRejectButton
                            text: control.rejectText
                            type: "text"
                            onClicked: {
                                control.rejected()
                                control.close()
                            }
                        }

                        Button {
                            visible: control.showAcceptButton
                            text: control.acceptText
                            type: "filled" // Changed to filled as requested
                            onClicked: {
                                control.accepted()
                                control.close()
                            }
                        }
                    }
                }
            }
            
            // Shadow (MultiEffect)
            MultiEffect {
                source: dialogContainer
                anchors.fill: dialogContainer
                shadowEnabled: true
                shadowColor: Theme.color.shadow
                shadowBlur: 12
                shadowVerticalOffset: 4
                shadowOpacity: 0.3
                z: -1
            }
        }
    }
}

