import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: root
    
    // Data Properties
    // columns: [{label: "Name", role: "name", width: 100}, ...]
    property var columns: []
    property var rowData: []
    
    // Style Properties
    property bool showCheckBoxes: false
    property bool showDividers: true
    property bool hoverEffect: true
    
    // Selection
    property var selectedIndices: []
    property bool allSelected: false
    property bool isIndeterminate: false

    function updateSelectedIndices() {
        var indices = []
        var all = true
        if (internalModel.count === 0) all = false
        
        for(var i=0; i<internalModel.count; i++) {
            if (internalModel.get(i).isSelected) {
                indices.push(i)
            } else {
                all = false
            }
        }
        selectedIndices = indices
        allSelected = all
        isIndeterminate = (indices.length > 0 && indices.length < internalModel.count)
    }

    function toggleAll(selected) {
        for(var i=0; i<internalModel.count; i++) {
            internalModel.setProperty(i, "isSelected", selected)
        }
        updateSelectedIndices()
    }
    
    implicitWidth: 400
    implicitHeight: 300
    
    // Internal Model for ListView
    ListModel {
        id: internalModel
    }
    
    onRowDataChanged: {
        internalModel.clear()
        for (var i = 0; i < rowData.length; i++) {
            var item = rowData[i]
            var newItem = {}
            for (var key in item) {
                newItem[key] = item[key]
            }
            newItem.isSelected = false
            internalModel.append(newItem)
        }
        updateSelectedIndices()
    }
    
    // Main Container
    Rectangle {
        anchors.fill: parent
        color: Theme.color.surface
        radius: 12
        border.color: Theme.color.outlineVariant
        border.width: 1
        clip: true
        
        ColumnLayout {
            anchors.fill: parent
            spacing: 0
            
            // Header
            Rectangle {
                z: 2 // Ensure header stays on top
                Layout.fillWidth: true
                Layout.preferredHeight: 56
                color: Theme.color.surface
                
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 16
                    anchors.rightMargin: 16
                    spacing: 0
                    
                    // Header Checkbox
                    Item {
                        visible: root.showCheckBoxes
                        Layout.preferredWidth: 48
                        Layout.fillHeight: true
                        Checkbox {
                            id: headerCheckbox
                            anchors.centerIn: parent
                            checked: root.allSelected
                            indeterminate: root.isIndeterminate
                            onClicked: {
                                // Toggle based on current state, ignoring Checkbox's internal toggle
                                root.toggleAll(!root.allSelected)
                                // Force re-bind to override any internal state change
                                checked = Qt.binding(function() { return root.allSelected })
                                indeterminate = Qt.binding(function() { return root.isIndeterminate })
                            }
                        }
                    }
                    
                    Repeater {
                        model: root.columns
                        
                        Item {
                            Layout.fillWidth: true
                            Layout.preferredWidth: modelData.width || 100
                            Layout.fillHeight: true
                            
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                anchors.left: parent.left
                                anchors.leftMargin: 16
                                text: modelData.label
                                color: Theme.color.onSurfaceVariantColor
                                font.pixelSize: 14
                                font.weight: Font.Medium
                            }
                        }
                    }
                }
                
                // Header Divider
                Rectangle {
                    anchors.bottom: parent.bottom
                    width: parent.width
                    height: 1
                    color: Theme.color.outlineVariant
                }
            }
            
            // Body
            ListView {
                id: tableView
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                model: internalModel
                boundsBehavior: Flickable.StopAtBounds
                
                delegate: Rectangle {
                    id: rowDelegate
                    width: tableView.width
                    height: 52
                    color: {
                        if (root.hoverEffect && ma.containsMouse) return Theme.color.surfaceContainerHighest
                        return "transparent"
                    }
                    
                    // Expose model to inner scope
                    property var rowModel: model
                    
                    MouseArea {
                        id: ma
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: {
                            if (root.showCheckBoxes) {
                                model.isSelected = !model.isSelected
                                root.updateSelectedIndices()
                            }
                        }
                    }
                    
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 0
                        
                        // Row Checkbox
                        Item {
                            visible: root.showCheckBoxes
                            Layout.preferredWidth: 48
                            Layout.fillHeight: true
                            Checkbox {
                                id: rowCheckbox
                                anchors.centerIn: parent
                                checked: model.isSelected
                                onClicked: {
                                    // Toggle based on current model state
                                    var newState = !model.isSelected
                                    model.isSelected = newState
                                    root.updateSelectedIndices()
                                    // Force re-bind
                                    checked = Qt.binding(function() { return model.isSelected })
                                }
                            }
                        }
                        
                        Repeater {
                            model: root.columns
                            
                            Item {
                                Layout.fillWidth: true
                                Layout.preferredWidth: modelData.width || 100
                                Layout.fillHeight: true
                                
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    anchors.left: parent.left
                                    anchors.leftMargin: 16
                                    text: rowDelegate.rowModel[modelData.role]
                                    color: Theme.color.onSurfaceColor
                                    font.pixelSize: 14
                                    elide: Text.ElideRight
                                    width: parent.width - 32
                                }
                            }
                        }
                    }
                    
                    // Row Divider
                    Rectangle {
                        visible: root.showDividers
                        anchors.bottom: parent.bottom
                        width: parent.width
                        height: 1
                        color: Theme.color.outlineVariant
                        opacity: 0.5
                    }
                }
            }
        }
    }
}

