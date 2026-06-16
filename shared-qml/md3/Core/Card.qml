import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: root

    // Properties
    property string type: "elevated" // elevated, filled, outlined
    property real radius: 12
    property real padding: 16
    default property alias contentItem: contentContainer.data
    property alias color: root.containerColor
    
    // State properties
    property bool hovered: false 
    property bool pressed: false

    // Colors
    property color containerColor: {
        if (!enabled) return Theme.color.surfaceVariant // Disabled look
        switch (type) {
            case "elevated": return Theme.color.surfaceContainerLow
            case "filled": return Theme.color.surfaceContainerHighest
            case "outlined": return Theme.color.surface
            default: return Theme.color.surfaceContainerLow
        }
    }
    
    property color outlineColor: Theme.color.outline
    property color rippleColor: "transparent" // Disable ripple visual
    property color stateLayerColor: "transparent" // Disable state layer visual

    // Elevation
    property int elevationLevel: {
        if (type === "elevated") {
             return 3 
        }
        return 0
    }

    implicitWidth: 300
    implicitHeight: 200

    // Shadow Source (Background color & shape)
    Rectangle {
        id: shadowSource
        anchors.fill: parent
        radius: root.radius
        color: root.containerColor
        visible: elevationLevel <= 0 
    }

    // Shadow Effect
    MultiEffect {
        anchors.fill: shadowSource
        source: shadowSource
        visible: elevationLevel > 0
        z: -1
        
        shadowEnabled: true
        shadowColor: Theme.color.shadow
        shadowBlur: elevationLevel * 0.2
        shadowVerticalOffset: elevationLevel * 1.2
        shadowOpacity: 0.2 + (elevationLevel * 0.02)
    }

    // Main Container
    Rectangle {
        id: container
        anchors.fill: parent
        color: "transparent"
        radius: root.radius
        border.width: root.type === "outlined" ? 1 : 0
        border.color: root.outlineColor

        // State Layer (Hover/Press Overlay)
        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: root.stateLayerColor
            opacity: {
                if (root.pressed) return Theme.state.pressedStateLayerOpacity
                if (root.hovered) return Theme.state.hoverStateLayerOpacity
                return 0
            }
        }

        // Ripple
        Ripple {
            id: ripple
            anchors.fill: parent
            clipRadius: parent.radius
            rippleColor: root.rippleColor
            enabled: false // Explicitly disable ripple interaction
            onClicked: (mouse) => root.clicked()
        }
        
        // Content Container
        Item {
            id: contentContainer
            anchors.fill: parent
            anchors.margins: root.padding
        }
    }
    
    signal clicked()
}

