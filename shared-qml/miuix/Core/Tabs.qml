import QtQuick
import QtQuick.Layouts
import miuix.Core
Item {
    id: root
    
    // Properties
    property var model: [] // Array of {icon: "name", text: "label"}
    default property alias content: stackLayout.data
    property int currentIndex: 0
    property string type: "primary" // "primary" or "secondary"
    onCurrentIndexChanged: tabBar.updateIndicator(false)
    
    // Internal property to detect if any item has an icon (for Primary Tabs height)
    property bool _hasIcon: {
        for (var i = 0; i < model.length; i++) {
            if (model[i].icon && model[i].icon !== "") return true;
        }
        return false;
    }
    
    implicitWidth: 400
    implicitHeight: 300
    
    // Tab Bar
    Rectangle {
        id: tabBar
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        // Primary Tabs with icons are 72dp, otherwise 48dp (Secondary is always 48dp)
        height: (root.type === "primary" && root._hasIcon) ? 72 : 48
        color: Theme.color.surface
        
        RowLayout {
            anchors.fill: parent
            spacing: 0
            
            Repeater {
                id: tabRepeater
                model: root.model
                
                Item {
                    id: tabItem
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    
                    property bool selected: index === root.currentIndex
                    property var itemData: modelData
                    property real contentWidth: contentLayout.implicitWidth

                    // RowLayout recomputes delegate geometry when the window is
                    // resized, but currentIndex does not change, so the indicator's
                    // old absolute coordinates otherwise remain behind. Update from
                    // the selected delegate after its actual geometry has changed.
                    onXChanged: if (selected) tabBar.updateIndicator(true)
                    onWidthChanged: if (selected) tabBar.updateIndicator(true)
                    onContentWidthChanged: if (selected) tabBar.updateIndicator(true)
                    
                    ColumnLayout {
                        id: contentLayout
                        anchors.centerIn: parent
                        spacing: 0
                        
                        // Icon (Only visible if model has icon)
                        Text {
                            visible: !!itemData.icon
                            Layout.alignment: Qt.AlignHCenter
                            text: itemData.icon || ""
                            font.family: Theme.iconFont.name
                            font.pixelSize: 24
                            color: tabItem.selected ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                        }

                        // Label
                        Text {
                            Layout.alignment: Qt.AlignHCenter
                            text: itemData.text || ""
                            font.family: Theme.typography.titleSmall.family
                            font.pixelSize: Theme.typography.titleSmall.size
                            font.weight: Theme.typography.titleSmall.weight
                            color: tabItem.selected ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                        }
                    }
                    
                    Ripple {
                        anchors.fill: parent
                        onClicked: root.currentIndex = index
                    }
                }
            }
        }
        
        // Sliding Indicator
        QtObject {
            id: indicatorProxy
            property real left: 0
            property real right: 0
        }

        Rectangle {
            id: indicator
            anchors.bottom: parent.bottom
            
            // Primary: 3dp height, rounded top corners (radius 3)
            // Secondary: 2dp height, no rounded corners (radius 0)
            height: root.type === "primary" ? 3 : 2
            radius: root.type === "primary" ? 3 : 0
            color: Theme.color.primary

            // Mask bottom corners if primary
            Rectangle {
                visible: root.type === "primary"
                anchors.bottom: parent.bottom
                width: parent.width
                height: parent.height / 2
                color: parent.color
            }
            
            x: indicatorProxy.left
            width: Math.max(0, indicatorProxy.right - indicatorProxy.left)
            
            ParallelAnimation {
                id: moveAnim
                property int duration: 240
                property bool moveRight: true
                
                NumberAnimation {
                    target: indicatorProxy
                    property: "left"
                    duration: moveAnim.duration
                    easing.type: moveAnim.moveRight ? Easing.InOutSine : Easing.OutSine
                }
                NumberAnimation {
                    target: indicatorProxy
                    property: "right"
                    duration: moveAnim.duration
                    easing.type: moveAnim.moveRight ? Easing.OutSine : Easing.InOutSine
                }
            }
        }

        function updateIndicator(instant) {
            var currentTab = tabRepeater.itemAt(root.currentIndex)
            if (!currentTab) return

            var targetX = currentTab.x
            var targetW = currentTab.width

            // Primary: Content width (short)
            if (root.type === "primary") {
                 targetX = currentTab.x + (currentTab.width - currentTab.contentWidth) / 2
                 targetW = currentTab.contentWidth
            }

            var targetRight = targetX + targetW

            if (instant) {
                indicatorProxy.left = targetX
                indicatorProxy.right = targetRight
            } else {
                moveAnim.moveRight = targetX > indicatorProxy.left
                moveAnim.animations[0].to = targetX
                moveAnim.animations[1].to = targetRight
                moveAnim.start()
            }
        }

        // Connections removed, handler moved to root

        
        // Wait for layout to settle. A single fixed-delay shot raced the very first
        // app launch (QML still compiling/laying out its first frame alongside
        // everything else starting up) and lost -- the tab row's width was still 0
        // at the 10ms mark, so the indicator computed a 0-width rect and never
        // became visible until the next manual tab switch. Retry instead of
        // trusting one delay: keep ticking until the current tab actually has a
        // measured width, with a capped number of attempts so a genuinely empty
        // model doesn't spin forever.
        Timer {
            id: indicatorSettleTimer
            interval: 50
            running: true
            repeat: true
            property int attempts: 0
            onTriggered: {
                var currentTab = tabRepeater.itemAt(root.currentIndex)
                attempts++
                if (currentTab && currentTab.width > 0) {
                    tabBar.updateIndicator(true)
                    running = false
                } else if (attempts >= 60) {
                    // Genuinely gave up (60 * 50ms = 3s) -- leave the timer running
                    // rather than settling for a no-op updateIndicator() call that
                    // would otherwise leave the indicator permanently stuck at 0
                    // width until the next real tab switch. Reset the counter and
                    // keep trying at the same cadence; harmless once the row is
                    // actually laid out, since the very next tick then succeeds
                    // and stops the timer for good.
                    attempts = 0
                }
            }
        }
        
        // Bottom border
        Rectangle {
            anchors.bottom: parent.bottom
            width: parent.width
            height: 1
            color: Theme.color.surfaceVariant
            z: -1
        }
    }
    
    // Content Area
    StackLayout {
        id: stackLayout
        anchors.top: tabBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        currentIndex: root.currentIndex
        clip: true
    }
}
