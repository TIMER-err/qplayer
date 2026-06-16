import QtQuick
import QtQuick.Effects
import md3.Core
Item {
    id: control
    
    // API
    property real from: 0.0
    property real to: 1.0
    property real value: 0.0
    property real stepSize: 0.0
    property bool snapMode: false // If true, snaps to steps
    property bool enabled: true
    property bool tickMarksEnabled: false
    property bool valueLabelEnabled: false

    // Range Mode API
    property bool rangeMode: false
    property real firstValue: from
    property real secondValue: to
    
    // State properties
    readonly property alias pressed: mouseArea.pressed
    readonly property alias hovered: mouseArea.containsMouse
    
    // Signals
    signal moved()
    signal firstMoved()
    signal secondMoved()
    
    implicitWidth: 200
    implicitHeight: 44 // Touch target height
    
    // Theme
    property var _colors: Theme.color
    property color _onSurfaceColor: _colors.onSurfaceColor
    property color _inverseSurfaceColor: _colors.inverseSurface
    property color _inverseOnSurfaceColor: _colors.inverseOnSurface
    property var _shape: Theme.shape
    property var _state: Theme.state
    
    // Internal
    property real _range: to - from
    property real _position: (value - from) / _range
    property real _firstPos: (firstValue - from) / _range
    property real _secondPos: (secondValue - from) / _range

    // Logic for interacting with handles
    property int _draggingHandle: 0 // 0: None, 1: First/Value, 2: Second
    property int _closestHandle: 1 // 1 or 2, determines which handle is targeted by hover/click

    function setValue(v) {
        var newValue = Math.max(from, Math.min(to, v))
        if (stepSize > 0) {
            var steps = Math.round((newValue - from) / stepSize)
            newValue = from + (steps * stepSize)
            newValue = Math.max(from, Math.min(to, newValue))
        }
        if (control.value !== newValue) {
            control.value = newValue
            control.moved()
        }
    }

    function setFirstValue(v) {
        var upperLimit = rangeMode ? secondValue : to
        var val = Math.max(from, Math.min(upperLimit, v))
        if (stepSize > 0) {
            var steps = Math.round((val - from) / stepSize)
            val = from + (steps * stepSize)
            val = Math.max(from, Math.min(upperLimit, val))
        }
        if (firstValue !== val) {
            firstValue = val
            if (rangeMode) firstMoved()
            // In single mode, we might not use firstValue, but keeping it synced could be useful?
            // For now, keep them separate.
        }
    }

    function setSecondValue(v) {
        var lowerLimit = firstValue
        var val = Math.max(lowerLimit, Math.min(to, v))
        if (stepSize > 0) {
             var steps = Math.round((val - from) / stepSize)
             val = from + (steps * stepSize)
             val = Math.max(lowerLimit, Math.min(to, val))
        }
        if (secondValue !== val) {
            secondValue = val
            if (rangeMode) secondMoved()
        }
    }

    function updateFromMouse(mouseX) {
        // mouseX is relative to trackContainer
        var padding = trackContainer.height / 2
        var availableWidth = trackContainer.width - (padding * 2)
        var relativeX = mouseX - padding
        var pos = Math.max(0, Math.min(1, relativeX / availableWidth))
        var rawValue = from + (pos * _range)
        
        if (!rangeMode) {
            setValue(rawValue)
        } else {
            // Determine which handle to move if not already dragging
            if (_draggingHandle === 0) {
                var dist1 = Math.abs(rawValue - firstValue)
                var dist2 = Math.abs(rawValue - secondValue)
                if (dist1 <= dist2) {
                    _draggingHandle = 1
                } else {
                    _draggingHandle = 2
                }
            }
            
            if (_draggingHandle === 1) {
                setFirstValue(rawValue)
            } else if (_draggingHandle === 2) {
                setSecondValue(rawValue)
            }
        }
    }
    
    // Track Container
    Item {
        id: trackContainer
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        height: 16
        
        // Internal geometry
        property real thumbWidth: 4
        property real gap: 6
        property real padding: height / 2
        property real availableWidth: width - (padding * 2)
        property real startX: padding + (control.rangeMode ? (control._firstPos * availableWidth) : 0)
        property real endX: padding + (control.rangeMode ? (control._secondPos * availableWidth) : (control._position * availableWidth))

        // Inactive Track (Background) - Split into Left and Right segments
        // Left Segment (Range Mode only: Before first handle)
        Rectangle {
            visible: control.rangeMode
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, trackContainer.startX - (trackContainer.gap + trackContainer.thumbWidth / 2))
            height: parent.height
            radius: height / 2
            color: control.enabled ? _colors.surfaceContainerHighest : Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.12)
        }

        // Right Segment (After last handle)
        Rectangle {
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, parent.width - (trackContainer.endX + (trackContainer.gap + trackContainer.thumbWidth / 2)))
            height: parent.height
            radius: height / 2
            color: control.enabled ? _colors.surfaceContainerHighest : Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.12)
        }
        
        // Active Track (Fill)
        Rectangle {
            id: activeTrack
            x: control.rangeMode ? (trackContainer.startX + (trackContainer.gap + trackContainer.thumbWidth / 2)) : 0
            width: Math.max(0, (control.rangeMode ? 
                   (trackContainer.endX - trackContainer.startX - (2 * trackContainer.gap) - trackContainer.thumbWidth) : 
                   (trackContainer.endX - (trackContainer.gap + trackContainer.thumbWidth / 2))))
            anchors.verticalCenter: parent.verticalCenter
            height: parent.height
            radius: height / 2
            color: control.enabled ? _colors.primary : Qt.rgba(_onSurfaceColor.r, _onSurfaceColor.g, _onSurfaceColor.b, 0.38)
        }

        // Tick Marks
        Repeater {
            model: control.tickMarksEnabled && control.stepSize > 0 ? Math.floor(control._range / control.stepSize) + 1 : 0
            
            Rectangle {
                width: 4
                height: 4
                radius: 2
                anchors.verticalCenter: parent.verticalCenter
                x: trackContainer.padding + ((index * control.stepSize / control._range) * trackContainer.availableWidth) - (width / 2)
                
                color: {
                    var stepVal = control.from + (index * control.stepSize)
                    var isActive = false
                    if (control.rangeMode) {
                        isActive = stepVal >= control.firstValue && stepVal <= control.secondValue
                    } else {
                        isActive = stepVal <= control.value
                    }
                    return isActive ? _colors.onPrimaryColor : _colors.onSurfaceVariantColor
                }
                opacity: 0.38
                visible: true // Ensure visibility
            }
        }
    }
    
    // Handle 1 (or Main Handle)
    Item {
        id: handle1
        property real pos: control.rangeMode ? control._firstPos : control._position
        x: trackContainer.padding + (trackContainer.availableWidth * pos) - (width / 2)
        anchors.verticalCenter: parent.verticalCenter
        width: 44
        height: 44
        z: 10
        
        // States for visual feedback
        property bool isHovered: control.rangeMode ? (mouseArea.containsMouse && control._closestHandle === 1) : mouseArea.containsMouse
        property bool isPressed: control.rangeMode ? (mouseArea.pressed && control._draggingHandle === 1) : mouseArea.pressed

        // State Layer (Hover/Press effect) - Keeping circle for touch target feedback?
        // New spec says "pressing the thumb adjusts its width".
        // Let's implement the vertical bar thumb.
        
        Rectangle {
            id: thumbVisual
            anchors.centerIn: parent
            width: 4
            height: 44
            radius: 2
            color: _colors.primary
            visible: control.enabled
            
            // Interaction State (Width change)
            states: [
                State {
                    name: "pressed"
                    when: handle1.isPressed
                    PropertyChanges { target: thumbVisual; width: 2 } // Thinner on press? Or wider? Result #3 says "become a very thin line".
                },
                State {
                    name: "hovered"
                    when: handle1.isHovered && !handle1.isPressed
                    PropertyChanges { target: thumbVisual; width: 6 } // Slightly wider on hover?
                }
            ]
            transitions: Transition {
                NumberAnimation { properties: "width"; duration: 200; easing.type: Easing.OutCubic }
            }
        }
        
        // Value Label (Tooltip)
        Item {
            id: valueLabel1
            visible: control.valueLabelEnabled && (handle1.isPressed || handle1.isHovered)
            y: -36
            anchors.horizontalCenter: parent.horizontalCenter
            width: 28
            height: 28
            
            opacity: visible ? 1 : 0
            scale: visible ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 100 } }
            Behavior on scale { NumberAnimation { duration: 100; easing.type: Easing.OutCubic } }
            
            // Balloon shape
            Rectangle {
                anchors.centerIn: parent
                width: 28
                height: 28
                radius: 14
                color: _colors.primary
                rotation: 45
                
                // Tail
                Rectangle {
                    width: 14
                    height: 14
                    color: _colors.primary
                    anchors.bottom: parent.bottom
                    anchors.right: parent.right
                }
            }
            
            Text {
                anchors.centerIn: parent
                text: Math.round((control.rangeMode ? control.firstValue : control.value) * 100) / 100
                color: _colors.onPrimaryColor
                font.pixelSize: 12
                font.weight: Font.Medium
            }
        }


    }
    
    // Handle 2 (Only for Range Mode)
    Item {
        id: handle2
        visible: control.rangeMode
        x: trackContainer.padding + (trackContainer.availableWidth * control._secondPos) - (width / 2)
        anchors.verticalCenter: parent.verticalCenter
        width: 44
        height: 44
        z: 11
        
        property bool isHovered: mouseArea.containsMouse && control._closestHandle === 2
        property bool isPressed: mouseArea.pressed && control._draggingHandle === 2

        Rectangle {
            id: thumbVisual2
            anchors.centerIn: parent
            width: 4
            height: 44
            radius: 2
            color: _colors.primary
            visible: control.enabled
            
            states: [
                State {
                    name: "pressed"
                    when: handle2.isPressed
                    PropertyChanges { target: thumbVisual2; width: 2 }
                },
                State {
                    name: "hovered"
                    when: handle2.isHovered && !handle2.isPressed
                    PropertyChanges { target: thumbVisual2; width: 6 }
                }
            ]
            transitions: Transition {
                NumberAnimation { properties: "width"; duration: 200; easing.type: Easing.OutCubic }
            }
        }
        
        // Value Label
        Item {
            id: valueLabel2
            visible: control.valueLabelEnabled && (handle2.isPressed || handle2.isHovered)
            y: -36
            anchors.horizontalCenter: parent.horizontalCenter
            width: 28
            height: 28
            
            opacity: visible ? 1 : 0
            scale: visible ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 100 } }
            Behavior on scale { NumberAnimation { duration: 100; easing.type: Easing.OutCubic } }
            
            Rectangle {
                anchors.centerIn: parent
                width: 28
                height: 28
                radius: 14
                color: _colors.primary
                rotation: 45
                Rectangle {
                    width: 14
                    height: 14
                    color: _colors.primary
                    anchors.bottom: parent.bottom
                    anchors.right: parent.right
                }
            }
            Text {
                anchors.centerIn: parent
                text: Math.round(control.secondValue * 100) / 100
                color: _colors.onPrimaryColor
                font.pixelSize: 12
                font.weight: Font.Medium
            }
        }


    }
    
    MouseArea {
        id: mouseArea
        anchors.fill: parent
        anchors.leftMargin: -10 
        anchors.rightMargin: -10
        hoverEnabled: true
        enabled: control.enabled
        preventStealing: true
        
        onPressed: (mouse) => {
            var localPos = trackContainer.mapFromItem(mouseArea, mouse.x, mouse.y)
            control.updateFromMouse(localPos.x)
        }
        
        onReleased: {
            control._draggingHandle = 0
        }
        
        onPositionChanged: (mouse) => {
            var localPos = trackContainer.mapFromItem(mouseArea, mouse.x, mouse.y)
            
            if (pressed) {
                control.updateFromMouse(localPos.x)
            } else {
                // Update closest handle for hover effect
                if (control.rangeMode) {
                    var padding = trackContainer.height / 2
                    var availableWidth = trackContainer.width - (padding * 2)
                    var relativeX = localPos.x - padding
                    var pos = Math.max(0, Math.min(1, relativeX / availableWidth))
                    var val = from + (pos * _range)
                    var dist1 = Math.abs(val - firstValue)
                    var dist2 = Math.abs(val - secondValue)
                    if (dist1 <= dist2) {
                        control._closestHandle = 1
                    } else {
                        control._closestHandle = 2
                    }
                } else {
                    control._closestHandle = 1
                }
            }
        }
    }
}

