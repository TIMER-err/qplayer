import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: root

    // Properties
    property string icon: "add"
    property string text: "" // Only for extended FAB
    property string type: "standard" // standard, small, large, extended
    property color containerColor: Theme.color.primaryContainer
    property color contentColor: Theme.color.onPrimaryContainerColor
    
    // Signals
    signal clicked()

    // Internal geometry
    property int fabSize: {
        switch(type) {
            case "small": return 40
            case "large": return 96
            default: return 56 // standard and extended height
        }
    }
    
    property int fabRadius: {
        switch(type) {
            case "small": return Theme.shape.cornerMedium // 12
            case "large": return Theme.shape.cornerExtraLarge // 28
            default: return Theme.shape.cornerLarge // 16 (standard and extended)
        }
    }
    
    property int iconSize: {
        switch(type) {
            case "large": return 36
            default: return 24
        }
    }

    // Elevation
    property int elevationLevel: {
        if (!enabled) return Theme.elevation.level0
        if (mouseArea.pressed) return Theme.elevation.level3
        if (mouseArea.containsMouse) return Theme.elevation.level4
        return Theme.elevation.level3
    }

    implicitWidth: type === "extended" ? (rowLayout.implicitWidth + 32) : fabSize
    implicitHeight: fabSize

    // Shadow Source (Background color & shape)
    Rectangle {
        id: shadowSource
        anchors.fill: parent
        radius: root.fabRadius
        color: root.containerColor
        visible: elevationLevel === 0 // Hide when elevated (MultiEffect renders it)
    }

    // Shadow Effect (Only visible when elevated)
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

    // Background Container (Transparent, handles interactions)
    Rectangle {
        id: container
        anchors.fill: parent
        color: "transparent" // Rendered by shadowSource/MultiEffect
        radius: root.fabRadius
        
        // Ripple
        Ripple {
            anchors.fill: parent
            clipRadius: parent.radius
            rippleColor: root.contentColor
            onClicked: root.clicked()
        }
        
        // MouseArea for hover/press state
        MouseArea {
            id: mouseArea
            anchors.fill: parent
            hoverEnabled: true
            acceptedButtons: Qt.NoButton // Let clicks pass through to Ripple
            propagateComposedEvents: true
        }
    }

    // Content
    RowLayout {
        id: rowLayout
        anchors.centerIn: parent
        spacing: 12
        
        // Icon
        Text {
            visible: root.icon !== ""
            text: root.icon
            font.family: Theme.iconFont.name
            font.pixelSize: root.iconSize
            color: root.contentColor
            Layout.alignment: Qt.AlignVCenter
        }
        
        // Label (Extended only)
        Text {
            visible: root.type === "extended" && root.text !== ""
            text: root.text
            font.family: Theme.typography.labelLarge.family
            font.pixelSize: Theme.typography.labelLarge.size
            font.weight: Theme.typography.labelLarge.weight
            color: root.contentColor
            Layout.alignment: Qt.AlignVCenter
        }
    }
}

