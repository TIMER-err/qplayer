import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: control
    
    // API
    // Array of objects: { text: "Label", icon: "icon_name", selected: true/false, enabled: true, tooltip: "..." }
    property var buttons: [
        { text: "Day", icon: "menu", selected: false },
        { text: "Week", icon: "check", selected: true },
        { text: "Month", icon: "grid_view", selected: false },
        { text: "Year", icon: "calendar_today", selected: false }
    ] 
    property bool enabled: true
    property bool multiSelect: false
    
    signal clicked(int index)

    function _getDisabledColor(colorString, alpha) {
        let c = Qt.color(colorString)
        return Qt.rgba(c.r, c.g, c.b, alpha)
    }

    function _handleClicked(index) {
        var newButtons = []
        for (var i = 0; i < buttons.length; i++) {
            var item = buttons[i]
            // Shallow copy
            var newItem = {}
            for (var key in item) {
                newItem[key] = item[key]
            }
            
            if (multiSelect) {
                if (i === index) newItem.selected = !item.selected
            } else {
                newItem.selected = (i === index)
            }
            newButtons.push(newItem)
        }
        buttons = newButtons
        clicked(index)
    }

    implicitWidth: row.implicitWidth
    implicitHeight: 40

    // Content Clipping Container
    Rectangle {
        id: container
        anchors.fill: parent
        radius: height / 2
        color: "transparent"
        clip: true

        RowLayout {
            id: row
            anchors.fill: parent
            spacing: 0

            Repeater {
                model: control.buttons
                delegate: Rectangle {
                    id: segment
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    Layout.minimumWidth: 48
                    implicitWidth: Math.max(contentRow.implicitWidth + 24, 48)
                    // Selecting swaps the icon for a wider checkmark; animate the
                    // resulting width change instead of snapping.
                    Behavior on implicitWidth { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
                    
                    property bool isSelected: modelData.selected === true
                    property bool isSegmentEnabled: (modelData.enabled !== undefined ? modelData.enabled : true) && control.enabled
                    property bool isFirst: index === 0
                    property bool isLast: index === control.buttons.length - 1
                    
                    // Handle rounded corners for first and last items independently
                    radius: (isFirst || isLast) ? height / 2 : 0
                    
                    color: isSelected && isSegmentEnabled ? Theme.color.secondaryContainer : "transparent"

                    // Patch to make the inner side square for first item
                    Rectangle {
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.bottom: parent.bottom
                        width: parent.radius
                        color: parent.color
                        visible: segment.isFirst && !segment.isLast
                    }

                    // Patch to make the inner side square for last item
                    Rectangle {
                        anchors.left: parent.left
                        anchors.top: parent.top
                        anchors.bottom: parent.bottom
                        width: parent.radius
                        color: parent.color
                        visible: segment.isLast && !segment.isFirst
                    }

                    // Divider line (on the right, unless last)
                    Rectangle {
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.bottom: parent.bottom
                        width: 1
                        color: !control.enabled ? control._getDisabledColor(Theme.color.onSurfaceColor, 0.12) : Theme.color.outline
                        visible: index < control.buttons.length - 1
                        z: 1
                    }

                    // Content
                    Row {
                        id: contentRow
                        anchors.centerIn: parent
                        spacing: 8
                        
                        // Icon (Checkmark if selected, or provided icon). Content-sized;
                        // the segment animates its width when this swaps in/out.
                        Text {
                            text: segment.isSelected ? "check" : (modelData.icon || "")
                            font.family: Theme.iconFont.name
                            font.pixelSize: 18
                            color: {
                                if (!segment.isSegmentEnabled) return control._getDisabledColor(Theme.color.onSurfaceColor, 0.38)
                                if (segment.isSelected) return Theme.color.onSecondaryContainerColor
                                return Theme.color.onSurfaceColor
                            }
                            visible: text !== ""
                            verticalAlignment: Text.AlignVCenter
                            anchors.verticalCenter: parent.verticalCenter
                        }

                        Text {
                            text: modelData.text || ""
                            font.family: Theme.typography.labelLarge.family
                            font.pixelSize: Theme.typography.labelLarge.size
                            font.weight: Theme.typography.labelLarge.weight
                            color: {
                                if (!segment.isSegmentEnabled) return control._getDisabledColor(Theme.color.onSurfaceColor, 0.38)
                                if (segment.isSelected) return Theme.color.onSecondaryContainerColor
                                return Theme.color.onSurfaceColor
                            }
                            visible: text !== ""
                            verticalAlignment: Text.AlignVCenter
                            anchors.verticalCenter: parent.verticalCenter
                        }
                    }

                    // Ripple
                    Ripple {
                        anchors.fill: parent
                        rippleColor: Theme.color.onSurfaceColor
                        enabled: segment.isSegmentEnabled
                        onClicked: (mouse) => control._handleClicked(index)
                        
                        clipTopLeftRadius: segment.isFirst ? height / 2 : 0
                        clipBottomLeftRadius: segment.isFirst ? height / 2 : 0
                        clipTopRightRadius: segment.isLast ? height / 2 : 0
                        clipBottomRightRadius: segment.isLast ? height / 2 : 0
                    }
                }
            }
        }
    }

    // Border Overlay
    Rectangle {
        id: borderOverlay
        anchors.fill: parent
        radius: height / 2
        color: "transparent"
        border.width: 1
        border.color: !control.enabled ? control._getDisabledColor(Theme.color.onSurfaceColor, 0.12) : Theme.color.outline
        z: 2
    }
}

