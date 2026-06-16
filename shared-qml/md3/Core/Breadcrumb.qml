import QtQuick
import QtQuick.Layouts
import md3.Core
RowLayout {
    id: root
    
    property var model: [] // Array of strings or objects { text: "Home", action: function(){} }
    
    spacing: 4
    
    Repeater {
        model: root.model
        
        RowLayout {
            spacing: 4
            
            property var itemData: modelData
            property bool isLast: index === root.model.length - 1
            
            // Text Item
            Text {
                text: typeof itemData === "string" ? itemData : itemData.text
                font.family: Theme.typography.labelLarge.family
                font.pixelSize: Theme.typography.labelLarge.size
                color: isLast ? Theme.color.onSurfaceColor : Theme.color.onSurfaceVariantColor
                
                MouseArea {
                    anchors.fill: parent
                    enabled: !isLast && typeof itemData !== "string" && itemData.action
                    cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
                    onClicked: if (itemData.action) itemData.action()
                }
            }
            
            // Separator
            Text {
                visible: !isLast
                text: "chevron_right"
                font.family: Theme.iconFont.name
                font.pixelSize: 18
                color: Theme.color.onSurfaceVariantColor
            }
        }
    }
}

