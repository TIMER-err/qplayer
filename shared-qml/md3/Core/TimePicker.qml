import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: control
    
    // API
    property int hour: new Date().getHours()
    property int minute: new Date().getMinutes()
    property bool is24Hour: true
    property string title: "Select time"
    
    // Signals
    signal accepted(int hour, int minute)
    signal rejected()
    signal closed()
    
    // Theme Helpers
    property var _colors: Theme.color
    property var _typography: Theme.typography
    
    // Internal State
    property int _mode: 0 // 0: Hour, 1: Minute
    property int _inputMode: 0 // 0: Dial, 1: Input
    property int _tempHour: hour
    property int _tempMinute: minute
    
    visible: false
    
    onVisibleChanged: {
        if (visible) {
            _tempHour = hour
            _tempMinute = minute
            _mode = 0 // Reset to Hour selection
            _inputMode = 0 // Reset to Dial
        }
    }
    
    function open() {
        var root = control
        while (root.parent) {
            root = root.parent
        }
        
        if (root) {
            overlayLayer.parent = root
            overlayLayer.z = 99999
            overlayLayer.anchors.fill = root
            
            // Stop animations
            exitAnimation.stop()
            enterAnimation.stop()
            
            // Reset
            animationWrapper.scale = 0.9
            animationWrapper.opacity = 0.0
            scrim.opacity = 0.0
            
            overlayLayer.visible = true
            enterAnimation.start()
        }
    }
    
    function close() {
        enterAnimation.stop()
        exitAnimation.stop()
        exitAnimation.start()
    }

    // Overlay
    Item {
        id: overlayLayer
        visible: false
        
        ParallelAnimation {
            id: enterAnimation
            NumberAnimation { target: scrim; property: "opacity"; from: 0.0; to: 0.32; duration: 150; easing.type: Easing.OutQuad }
            NumberAnimation { target: animationWrapper; property: "scale"; from: 0.9; to: 1.0; duration: 250; easing.type: Easing.OutBack; easing.overshoot: 1.0 }
            NumberAnimation { target: animationWrapper; property: "opacity"; from: 0.0; to: 1.0; duration: 150 }
        }
        
        ParallelAnimation {
            id: exitAnimation
            onFinished: {
                overlayLayer.visible = false
                control.closed()
            }
            NumberAnimation { target: scrim; property: "opacity"; from: 0.32; to: 0.0; duration: 150 }
            NumberAnimation { target: animationWrapper; property: "opacity"; from: 1.0; to: 0.0; duration: 100 }
            NumberAnimation { target: animationWrapper; property: "scale"; from: 1.0; to: 0.95; duration: 100 }
        }
        
        Rectangle {
            id: scrim
            anchors.fill: parent
            color: "#000000"
            opacity: 0.0
            MouseArea { 
                anchors.fill: parent 
                onClicked: control.close()
                onWheel: (wheel) => {} // Block scroll propagation
            }
        }
        
        Item {
            id: animationWrapper
            anchors.centerIn: parent
            width: 324 // MD3 Time Picker width
            height: pickerContainer.height
            
            Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

            Rectangle {
                id: pickerContainer
                width: parent.width
                height: mainColumn.implicitHeight
                radius: 28
                color: _colors.surfaceContainerHigh
                clip: true 
                
                Behavior on height { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
                
                // Block clicks from passing through to scrim
                MouseArea {
                    anchors.fill: parent
                }

                ColumnLayout {
                    id: mainColumn
                    width: parent.width
                    spacing: 0
                    
                    // Header
                    Text {
                        text: control._inputMode === 1 ? "Enter time" : control.title
                        font.family: _typography.labelMedium.family
                        font.pixelSize: _typography.labelMedium.size
                        font.weight: _typography.labelMedium.weight
                        color: _colors.onSurfaceVariantColor
                        Layout.topMargin: 24
                        Layout.leftMargin: 24
                        Layout.bottomMargin: 20
                    }
                    
                    StackLayout {
                        currentIndex: control._inputMode
                        Layout.fillWidth: true
                        Layout.preferredHeight: {
                            if (control._inputMode === 1) return inputViewWrapper.implicitHeight
                            return 340 // Dial view approximate height
                        }
                        
                        // 0: Dial Mode
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 0
                            
                            // Time Display (Big Boxes)
                            RowLayout {
                                Layout.fillWidth: true
                                Layout.alignment: Qt.AlignHCenter
                                spacing: 0
                                Layout.bottomMargin: 24
                                
                                Item { Layout.fillWidth: true }
                                
                                // Hour Box
                                Rectangle {
                                    width: 96
                                    height: 80
                                    radius: 8
                                    color: _mode === 0 ? _colors.primaryContainer : _colors.surfaceContainerHighest
                                    
                                    Text {
                                        anchors.centerIn: parent
                                        text: {
                                            var h = _tempHour
                                            if (!is24Hour) {
                                                h = h % 12 || 12
                                            }
                                            return h.toString().padStart(2, '0')
                                        }
                                        font.family: _typography.displayLarge.family
                                        font.pixelSize: 57 // Display Large
                                        color: _mode === 0 ? _colors.onPrimaryContainerColor : _colors.onSurfaceColor
                                    }
                                    
                                    MouseArea {
                                        anchors.fill: parent
                                        onClicked: _mode = 0
                                    }
                                }
                                
                                Text {
                                    text: ":"
                                    font.pixelSize: 57
                                    color: _colors.onSurfaceColor
                                    Layout.margins: 6
                                    Layout.alignment: Qt.AlignVCenter
                                }
                                
                                // Minute Box
                                Rectangle {
                                    width: 96
                                    height: 80
                                    radius: 8
                                    color: _mode === 1 ? _colors.primaryContainer : _colors.surfaceContainerHighest
                                    
                                    Text {
                                        anchors.centerIn: parent
                                        text: _tempMinute.toString().padStart(2, '0')
                                        font.family: _typography.displayLarge.family
                                        font.pixelSize: 57
                                        color: _mode === 1 ? _colors.onPrimaryContainerColor : _colors.onSurfaceColor
                                    }
                                    
                                    MouseArea {
                                        anchors.fill: parent
                                        onClicked: _mode = 1
                                    }
                                }
                                
                                Item { Layout.fillWidth: true }
                                
                                // AM/PM Selector (Simple vertical column)
                                ColumnLayout {
                                    visible: !control.is24Hour
                                    spacing: 0
                                    Layout.rightMargin: 24
                                    Layout.leftMargin: 12
                                    
                                    Rectangle {
                                        width: 38; height: 38
                                        color: _tempHour < 12 ? _colors.tertiaryContainer : _colors.surfaceContainerHighest
                                        border.width: 1
                                        border.color: _colors.outline
                                        radius: 8
                                        Text {
                                            anchors.centerIn: parent
                                            text: "AM"
                                            font.family: _typography.labelMedium.family
                                            color: _tempHour < 12 ? _colors.onTertiaryContainerColor : _colors.onSurfaceColor
                                        }
                                        MouseArea {
                                            anchors.fill: parent
                                            onClicked: if (_tempHour >= 12) _tempHour -= 12
                                        }
                                    }
                                    
                                    Rectangle {
                                        width: 38; height: 38
                                        color: _tempHour >= 12 ? _colors.tertiaryContainer : _colors.surfaceContainerHighest
                                        border.width: 1
                                        border.color: _colors.outline
                                        radius: 8
                                        Text {
                                            anchors.centerIn: parent
                                            text: "PM"
                                            font.family: _typography.labelMedium.family
                                            color: _tempHour >= 12 ? _colors.onTertiaryContainerColor : _colors.onSurfaceColor
                                        }
                                        MouseArea {
                                            anchors.fill: parent
                                            onClicked: if (_tempHour < 12) _tempHour += 12
                                        }
                                    }
                                }
                            }
                            
                            // Dial
                            Item {
                                id: dial
                                Layout.alignment: Qt.AlignHCenter
                                width: 256
                                height: 256
                                
                                Rectangle {
                                    anchors.fill: parent
                                    radius: 128
                                    color: _colors.surfaceContainerHighest
                                }
                                
                                // Center dot
                                Rectangle {
                                    anchors.centerIn: parent
                                    width: 8; height: 8; radius: 4
                                    color: _colors.primary
                                }
                                
                                // Hand
                                Item {
                                    id: hand
                                    anchors.centerIn: parent
                                    width: 256
                                    height: 256
                                    
                                    property real radius: {
                                        if (_mode === 1) return 108
                                        if (!is24Hour) return 108
                                        // 24h mode: 0-11 inner, 12-23 outer
                                        // Wait, 00, 13-23 are outer. 12, 1-11 are inner.
                                        // Let's use simple logic: if _tempHour is 12, 1-11 -> inner.
                                        // if _tempHour is 0, 13-23 -> outer.
                                        if (_tempHour === 0 || _tempHour > 12) return 108
                                        return 72
                                    }
                                    
                                    rotation: {
                                        if (_mode === 0) { // Hours
                                            var h = _tempHour % 12
                                            return h * 30
                                        } else { // Minutes
                                            return _tempMinute * 6
                                        }
                                    }
                                    
                                    Behavior on rotation {
                                        RotationAnimation {
                                            duration: 150
                                            direction: RotationAnimation.Shortest
                                        }
                                    }
                                    Behavior on radius { NumberAnimation { duration: 150; easing.type: Easing.OutQuad } }
                                    
                                    // Line
                                    Rectangle {
                                        anchors.horizontalCenter: parent.horizontalCenter
                                        anchors.bottom: parent.verticalCenter
                                        width: 2
                                        height: hand.radius - 24 // Connect to circle center
                                        color: _colors.primary
                                        antialiasing: true
                                    }
                                    
                                    // Selection Circle
                                    Rectangle {
                                        anchors.horizontalCenter: parent.horizontalCenter
                                        anchors.bottom: parent.verticalCenter
                                        anchors.bottomMargin: hand.radius - 24
                                        width: 48
                                        height: 48
                                        radius: 24
                                        color: _colors.primary
                                    }
                                }
                                
                                // Numbers Repeater
                                Repeater {
                                    model: (_mode === 0 && is24Hour) ? 24 : 12
                                    
                                    Item {
                                        width: 256; height: 256
                                        anchors.centerIn: parent
                                        
                                        property int value: {
                                            if (_mode === 1) return index * 5
                                            if (!is24Hour) return index === 0 ? 12 : index
                                            
                                            // 24h mapping
                                            // 0-11: Inner ring (12, 1-11)
                                            if (index === 0) return 12
                                            if (index < 12) return index
                                            // 12-23: Outer ring (00, 13-23)
                                            if (index === 12) return 0
                                            return index
                                        }
                                        
                                        property bool isInner: _mode === 0 && is24Hour && index < 12
                                        
                                        // Position calculation
                                        property real angleDeg: index * 30
                                        property real angleRad: (angleDeg - 90) * Math.PI / 180
                                        property real radius: (_mode === 1) ? 108 : (isInner ? 72 : 108)
                                        
                                        x: 128 + radius * Math.cos(angleRad) - width/2
                                        y: 128 + radius * Math.sin(angleRad) - height/2
                                        
                                        Text {
                                            anchors.centerIn: parent
                                            x: 128 + radius * Math.cos(angleRad) - width/2
                                            y: 128 + radius * Math.sin(angleRad) - height/2
                                            
                                            anchors.horizontalCenter: parent.horizontalCenter
                                            anchors.verticalCenter: parent.verticalCenter
                                            anchors.horizontalCenterOffset: radius * Math.cos(angleRad)
                                            anchors.verticalCenterOffset: radius * Math.sin(angleRad)
                                            
                                            text: value.toString().padStart(2, '0')
                                            font.family: _typography.bodyLarge.family
                                            font.pixelSize: isInner ? 12 : _typography.bodyLarge.size
                                            color: {
                                                // Selection Logic
                                                var currentVal = _mode === 0 ? _tempHour : _tempMinute
                                                
                                                if (_mode === 0) {
                                                    // Hour
                                                    if (value === currentVal) return _colors.onPrimaryColor
                                                } else {
                                                    // Minute
                                                    if (Math.abs(currentVal - value) < 3) return _colors.onPrimaryColor
                                                }
                                                return _colors.onSurfaceColor
                                            }
                                        }
                                    }
                                }
                                
                                // Interaction Area
                                MouseArea {
                                    anchors.fill: parent
                                    
                                    function updateTimeFromMouse(mouseX, mouseY) {
                                        var dx = mouseX - width/2
                                        var dy = mouseY - height/2
                                        var dist = Math.sqrt(dx*dx + dy*dy)
                                        var angle = Math.atan2(dy, dx) * 180 / Math.PI + 90
                                        if (angle < 0) angle += 360
                                        
                                        if (_mode === 0) { // Hours
                                            var h = Math.round(angle / 30) % 12
                                            if (h === 0) h = 12
                                            
                                            if (is24Hour) {
                                                // Determine if inner or outer based on click radius
                                                // Outer ~ 108, Inner ~ 72. Midpoint ~ 90.
                                                var isInner = dist < 90
                                                
                                                if (isInner) {
                                                    // 12, 1-11
                                                    if (h === 12) _tempHour = 12
                                                    else _tempHour = h
                                                } else {
                                                    // 00, 13-23
                                                    if (h === 12) _tempHour = 0
                                                    else _tempHour = h + 12
                                                }
                                            } else {
                                                // 12h logic
                                                if (_tempHour >= 12 && h !== 12) h += 12
                                                if (_tempHour < 12 && h === 12) h = 0
                                                _tempHour = h
                                                
                                                // Restore AM/PM
                                                var isPM = _tempHour >= 12
                                                if (isPM && _tempHour < 12) _tempHour += 12
                                                if (!isPM && _tempHour >= 12) _tempHour -= 12
                                            }
                                        } else { // Minutes
                                            var m = Math.round(angle / 6) % 60
                                            _tempMinute = m
                                        }
                                    }
                                    
                                    onPressed: (mouse) => updateTimeFromMouse(mouse.x, mouse.y)
                                    onPositionChanged: (mouse) => updateTimeFromMouse(mouse.x, mouse.y)
                                    onReleased: {
                                        if (_mode === 0) _mode = 1 // Auto switch to minute
                                    }
                                }
                            }
                        }
                        
                        // 1: Input Mode
                        Item {
                            id: inputViewWrapper
                            Layout.fillWidth: true
                            implicitHeight: inputViewLayout.implicitHeight + 48 // Add padding
                            
                            ColumnLayout {
                                id: inputViewLayout
                                anchors.centerIn: parent
                                spacing: 24
                                
                                RowLayout {
                                    spacing: 12
                                    
                                    TextField {
                                        id: hourInput
                                        Layout.preferredWidth: 96
                                        Layout.fillWidth: false
                                        text: {
                                            var h = _tempHour
                                            if (!control.is24Hour) {
                                                h = h % 12 || 12
                                            }
                                            return h.toString()
                                        }
                                        label: "Hour"
                                        type: "outlined"
                                        labelBackgroundColor: _colors.surfaceContainerHigh
                                        
                                        onEditingFinished: {
                                            var val = parseInt(text)
                                            if (isNaN(val)) return
                                            
                                            if (control.is24Hour) {
                                                if (val >= 0 && val <= 23) _tempHour = val
                                            } else {
                                                if (val >= 1 && val <= 12) {
                                                    var isPM = _tempHour >= 12
                                                    if (val === 12) val = 0
                                                    if (isPM) val += 12
                                                    _tempHour = val
                                                }
                                            }
                                        }
                                    }

                                    Binding {
                                        target: hourInput
                                        property: "text"
                                        value: {
                                            var h = _tempHour
                                            if (!control.is24Hour) h = h % 12 || 12
                                            return h.toString()
                                        }
                                        when: !hourInput.focused
                                    }
                                    
                                    Text {
                                        text: ":"
                                        font.pixelSize: 32
                                        color: _colors.onSurfaceColor
                                    }
                                    
                                    TextField {
                                        id: minuteInput
                                        Layout.preferredWidth: 96
                                        Layout.fillWidth: false
                                        text: _tempMinute.toString().padStart(2, '0')
                                        label: "Minute"
                                        type: "outlined"
                                        labelBackgroundColor: _colors.surfaceContainerHigh
                                        
                                        onEditingFinished: {
                                            var val = parseInt(text)
                                            if (!isNaN(val) && val >= 0 && val <= 59) {
                                                _tempMinute = val
                                            }
                                        }
                                    }

                                    Binding {
                                        target: minuteInput
                                        property: "text"
                                        value: _tempMinute.toString().padStart(2, '0')
                                        when: !minuteInput.focused
                                    }
                                }
                                
                                // AM/PM Segmented Button
                                RowLayout {
                                    visible: !control.is24Hour
                                    Layout.alignment: Qt.AlignHCenter
                                    spacing: 0
                                    
                                    Rectangle {
                                        width: 80; height: 40
                                        color: _tempHour < 12 ? _colors.tertiaryContainer : "transparent"
                                        border.width: 1
                                        border.color: _colors.outline
                                        radius: 8
                                        // Left corners only? MD3 usually full radius for segmented
                                        Text {
                                            anchors.centerIn: parent
                                            text: "AM"
                                            color: _tempHour < 12 ? _colors.onTertiaryContainerColor : _colors.onSurfaceColor
                                        }
                                        MouseArea { anchors.fill: parent; onClicked: if (_tempHour >= 12) _tempHour -= 12 }
                                    }
                                    
                                    Rectangle {
                                        width: 80; height: 40
                                        color: _tempHour >= 12 ? _colors.tertiaryContainer : "transparent"
                                        border.width: 1
                                        border.color: _colors.outline
                                        radius: 8
                                        Text {
                                            anchors.centerIn: parent
                                            text: "PM"
                                            color: _tempHour >= 12 ? _colors.onTertiaryContainerColor : _colors.onSurfaceColor
                                        }
                                        MouseArea { anchors.fill: parent; onClicked: if (_tempHour < 12) _tempHour += 12 }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Actions
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.margins: 24
                        Layout.topMargin: 24 // Increased margin to separate from dial
                        
                        IconButton {
                            icon: control._inputMode === 0 ? "keyboard" : "schedule"
                            onClicked: control._inputMode = (control._inputMode === 0 ? 1 : 0)
                        }
                        
                        Item { Layout.fillWidth: true }
                        
                        Button {
                            text: "Cancel"
                            type: "text"
                            onClicked: {
                                control.rejected()
                                control.close()
                            }
                        }
                        
                        Button {
                            text: "OK"
                            type: "text"
                            onClicked: {
                                // If in input mode, force update from text fields if they have focus?
                                // EditingFinished handles it.
                                control.accepted(_tempHour, _tempMinute)
                                control.close()
                            }
                        }
                    }
                    
                    Item { Layout.fillWidth: true; height: 12 }
                }
            }
            
            MultiEffect {
                source: pickerContainer
                anchors.fill: pickerContainer
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

