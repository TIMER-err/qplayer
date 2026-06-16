import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: control
    
    // API
    property date selectedDate: new Date()
    property string title: "Select date"
    
    // Signals
    signal accepted(date date)
    signal rejected()
    signal closed()
    
    // Theme Helpers
    property var _colors: Theme.color
    property var _typography: Theme.typography
    property var _shape: Theme.shape
    
    // Internal State
    property date _viewDate: new Date() // For navigating months without changing selection
    property int _inputMode: 0 // 0: Calendar, 1: Year List, 2: Text Input
    
    // Initialize view date on open
    onVisibleChanged: {
        if (visible) {
            _viewDate = new Date(selectedDate)
            _inputMode = 0
        }
    }

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
    

    function getDaysInMonth(year, month) {
        return new Date(year, month + 1, 0).getDate();
    }
    
    function getFirstDayOfMonth(year, month) {
        return new Date(year, month, 1).getDay();
    }
    
    function isSameDay(d1, d2) {
        return d1.getFullYear() === d2.getFullYear() &&
               d1.getMonth() === d2.getMonth() &&
               d1.getDate() === d2.getDate();
    }

    // Overlay
    Item {
        id: overlayLayer
        visible: false
        
        // Animations (Copied from Dialog)
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
        }
        
        // Scrim
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
        
        // Wrapper
        Item {
            id: animationWrapper
            anchors.centerIn: parent
            width: 360 // Standard mobile width often used for pickers
            height: pickerContainer.height
            
            Rectangle {
                id: pickerContainer
                width: parent.width
                height: mainColumn.implicitHeight
                radius: 28
                color: _colors.surfaceContainerHigh
                clip: true 
                
                // Block clicks from passing through to scrim
                MouseArea {
                    anchors.fill: parent
                }
                
                Behavior on height { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

                ColumnLayout {
                    id: mainColumn
                    width: parent.width
                    spacing: 0
                    
                    // Header
                    ColumnLayout {
                        Layout.fillWidth: true
                        Layout.margins: 24
                        spacing: 36 // Space between "Select date" and Date
                        
                        Text {
                            text: control.title
                            font.family: _typography.labelMedium.family
                            font.pixelSize: _typography.labelMedium.size
                            color: _colors.onSurfaceVariantColor
                        }
                        
                        RowLayout {
                            Layout.fillWidth: true
                            
                            Text {
                                text: Qt.formatDate(control.selectedDate, "ddd, MMM d")
                                font.family: _typography.headlineLarge.family
                                font.pixelSize: 32 // Headline Large approx
                                font.weight: _typography.headlineLarge.weight
                                color: _colors.onSurfaceColor
                                Layout.fillWidth: true
                            }
                            
                            IconButton {
                                icon: control._inputMode === 2 ? "calendar_today" : "edit"
                                onClicked: control._inputMode = (control._inputMode === 2 ? 0 : 2)
                            }
                        }
                    }
                    
                    // Divider
                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: _colors.outlineVariant
                        visible: control._inputMode === 2 // Only visible in input mode as per common material patterns, or always? Image shows it in input mode.
                    }
                    
                    // Spacer for divider if not visible or just padding
                    Item { 
                        Layout.fillWidth: true; 
                        height: control._inputMode === 2 ? 16 : 16 
                    }
                    
                    // Content Area
                    StackLayout {
                        currentIndex: control._inputMode
                        Layout.fillWidth: true
                        Layout.preferredHeight: {
                            if (control._inputMode === 2) return inputViewWrapper.implicitHeight
                            return 340
                        }
                        
                        // 0: Calendar View
                        ColumnLayout {
                            id: calendarViewLayout
                            Layout.fillWidth: true
                            Layout.preferredHeight: 340
                            spacing: 0
                            
                            // Navigation
                            RowLayout {
                                Layout.fillWidth: true
                                Layout.leftMargin: 12
                                Layout.rightMargin: 12
                                
                                Button {
                                    type: "text"
                                    text: Qt.formatDate(_viewDate, "MMMM yyyy")
                                    icon: "arrow_drop_down"
                                    onClicked: control._inputMode = 1
                                }
                                
                                Item { Layout.fillWidth: true }
                                
                                IconButton {
                                    icon: "chevron_left"
                                    onClicked: _viewDate = new Date(_viewDate.getFullYear(), _viewDate.getMonth() - 1, 1)
                                }
                                
                                IconButton {
                                    icon: "chevron_right"
                                    onClicked: _viewDate = new Date(_viewDate.getFullYear(), _viewDate.getMonth() + 1, 1)
                                }
                            }
                            
                            // Days of Week
                            Row {
                                Layout.fillWidth: true
                                Layout.topMargin: 8
                                Layout.leftMargin: 12
                                Layout.rightMargin: 12
                                spacing: 0
                                Repeater {
                                    model: ["S", "M", "T", "W", "T", "F", "S"]
                                    Item {
                                        width: (parent.width) / 7
                                        height: 40
                                        Text {
                                            anchors.centerIn: parent
                                            text: modelData
                                            font.family: _typography.bodySmall.family
                                            font.pixelSize: _typography.bodySmall.size
                                            color: _colors.onSurfaceColor
                                        }
                                    }
                                }
                            }
                            
                            // Calendar Grid
                            GridLayout {
                                Layout.fillWidth: true
                                Layout.leftMargin: 12
                                Layout.rightMargin: 12
                                Layout.bottomMargin: 12
                                columns: 7
                                rowSpacing: 0
                                columnSpacing: 0
                                
                                // Empty cells for start of month
                                Repeater {
                                    model: getFirstDayOfMonth(_viewDate.getFullYear(), _viewDate.getMonth())
                                    Item { 
                                        Layout.fillWidth: true
                                        Layout.preferredHeight: width
                                    }
                                }
                                
                                // Days
                                Repeater {
                                    model: getDaysInMonth(_viewDate.getFullYear(), _viewDate.getMonth())
                                    
                                    Item {
                                        id: dayDelegate
                                        property int dayNum: index + 1
                                        property date dateValue: new Date(_viewDate.getFullYear(), _viewDate.getMonth(), dayNum)
                                        property bool isSelected: isSameDay(dateValue, control.selectedDate)
                                        property bool isToday: isSameDay(dateValue, new Date())
                                        
                                        Layout.fillWidth: true
                                        Layout.preferredHeight: width // Square aspect ratio approx
                                        
                                        Rectangle {
                                            anchors.centerIn: parent
                                            width: 40
                                            height: 40
                                            radius: 20
                                            color: isSelected ? _colors.primary : "transparent"
                                            
                                            // Today indicator (outline if not selected)
                                            border.width: !isSelected && isToday ? 1 : 0
                                            border.color: !isSelected && isToday ? _colors.primary : "transparent"
                                            
                                            Text {
                                                anchors.centerIn: parent
                                                text: dayNum
                                                font.family: _typography.bodyMedium.family
                                                font.pixelSize: _typography.bodyMedium.size
                                                color: isSelected ? _colors.onPrimaryColor : _colors.onSurfaceColor
                                            }
                                            
                                            MouseArea {
                                                anchors.fill: parent
                                                onClicked: control.selectedDate = dateValue
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Item { Layout.fillHeight: true } // Spacer
                        }
                        
                        // 1: Year List View
                        ListView {
                            id: yearListView
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            clip: true
                            model: 200 // 1900 to 2099
                            
                            delegate: Item {
                                width: ListView.view.width
                                height: 56
                                property int year: 1900 + index
                                property bool isSelected: year === _viewDate.getFullYear()
                                
                                Rectangle {
                                    anchors.centerIn: parent
                                    width: 100
                                    height: 40
                                    radius: 20
                                    color: isSelected ? _colors.primary : "transparent"
                                    
                                    Text {
                                        anchors.centerIn: parent
                                        text: year
                                        font.family: _typography.bodyLarge.family
                                        font.pixelSize: isSelected ? 24 : 16
                                        font.weight: isSelected ? Font.Bold : Font.Normal
                                        color: isSelected ? _colors.onPrimaryColor : _colors.onSurfaceColor
                                    }
                                    
                                    MouseArea {
                                        anchors.fill: parent
                                        onClicked: {
                                            // Update view date keeping same month/day if possible
                                            _viewDate = new Date(year, _viewDate.getMonth(), Math.min(_viewDate.getDate(), getDaysInMonth(year, _viewDate.getMonth())))
                                            control._inputMode = 0
                                        }
                                    }
                                }
                            }
                            
                            onVisibleChanged: {
                                if (visible) {
                                    positionViewAtIndex(_viewDate.getFullYear() - 1900, ListView.Center)
                                }
                            }
                        }
                        
                        // 2: Input View
                        Item {
                            id: inputViewWrapper
                            Layout.fillWidth: true
                            implicitHeight: inputViewLayout.implicitHeight + 48 // Top + Bottom margins

                            ColumnLayout {
                                id: inputViewLayout
                                anchors.fill: parent
                                anchors.margins: 24
                                spacing: 16

                                TextField {
                                    id: dateInput
                                    Layout.fillWidth: true
                                    label: "Enter Date"
                                    placeholderText: "MM/DD/YYYY"
                                    text: Qt.formatDate(control.selectedDate, "MM/dd/yyyy")
                                    type: "outlined"
                                    labelBackgroundColor: _colors.surfaceContainerHigh
                                    
                                    onAccepted: {
                                        var parts = text.split('/')
                                        if (parts.length === 3) {
                                            var m = parseInt(parts[0]) - 1
                                            var d = parseInt(parts[1])
                                            var y = parseInt(parts[2])
                                            var newDate = new Date(y, m, d)
                                            if (!isNaN(newDate.getTime()) && newDate.getMonth() === m) {
                                                control.selectedDate = newDate
                                                control._viewDate = newDate
                                                control._inputMode = 0
                                                errorText = ""
                                                return
                                            }
                                        }
                                        errorText = "Invalid date"
                                    }
                                }
                                
                                Text {
                                    text: "Format: MM/DD/YYYY"
                                    font.family: _typography.bodySmall.family
                                    font.pixelSize: _typography.bodySmall.size
                                    color: _colors.onSurfaceVariantColor
                                }
                            }
                        }
                    }
                    
                    // Actions
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.margins: 24
                        spacing: 8
                        Layout.alignment: Qt.AlignRight
                        
                        Item { Layout.fillWidth: true }
                        
                        Button {
                            text: "Cancel"
                            type: "text"
                            onClicked: {
                                control.close()
                            }
                        }
                        
                        Button {
                            text: "OK"
                            type: "text"
                            onClicked: {
                                if (control._inputMode === 2) {
                                    // Try to accept input first
                                    dateInput.accepted()
                                    if (dateInput.errorText === "") {
                                        control.accepted(control.selectedDate)
                                        control.close()
                                    }
                                } else {
                                    control.accepted(control.selectedDate)
                                    control.close()
                                }
                            }
                        }
                    }
                    
                    Item { Layout.fillWidth: true; height: 12 } // Bottom padding
                }
            }
            
            // Shadow
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

