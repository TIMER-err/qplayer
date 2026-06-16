import QtQuick
import QtQuick.Layouts
import md3.Core
Rectangle {
    id: root
    
    // Public properties
    property string title: "Title"
    property bool showNavigationIcon: true
    property alias navigationIcon: navIcon
    default property alias actions: actionsLayout.data
    
    // Signals
    signal navigationIconClicked()
    
    implicitWidth: parent ? parent.width : 640
    implicitHeight: 64
    color: Theme.color.surface
    
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 4 // Standard margin for icon
        anchors.rightMargin: 16
        spacing: 0
        
        // Navigation Icon
        IconButton {
            id: navIcon
            icon: "menu"
            visible: root.showNavigationIcon
            type: "standard"
            onClicked: root.navigationIconClicked()
        }
        
        // Spacing if no icon
        Item {
            width: root.showNavigationIcon ? 12 : 16
            visible: true
        }
        
        // Title
        Text {
            Layout.fillWidth: true
            text: root.title
            font.family: Theme.typography.titleLarge.family
            font.pixelSize: Theme.typography.titleLarge.size
            font.weight: Theme.typography.titleLarge.weight
            color: Theme.color.onSurfaceColor
            elide: Text.ElideRight
        }
        
        // Actions
        RowLayout {
            id: actionsLayout
            Layout.fillHeight: true
            spacing: 0
        }
    }
}

