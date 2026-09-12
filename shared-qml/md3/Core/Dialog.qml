import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: control
    
    // API
    property string title: ""
    property string text: ""
    property string icon: ""
    
    property string acceptText: "OK"
    property string rejectText: "Cancel"
    property string rejectIcon: ""
    property string neutralText: ""
    property bool showAcceptButton: true
    property bool showRejectButton: true
    property bool showNeutralButton: false
    property bool closeOnScrim: true
    readonly property bool opened: overlayLayer.visible
    readonly property bool compactActionLayout: overlayLayer.width < 600
    
    // Signals
    signal accepted()
    signal rejected()
    signal neutral()
    signal closed()
    
    // Custom content support
    default property alias content: contentPlaceholder.data
    
    // Theme Helpers
    property var _colors: Theme.color
    property var _typography: Theme.typography
    property var _shape: Theme.shape
    
    // Internal
    visible: false
    
    // The overlay hosts itself on the topmost ancestor that actually has a size.
    // Walking blindly to the end of the parent chain is not enough: a dialog
    // opened from a property binding's construction-time firing runs before its
    // own .qml file's root Item has been parented into the scene, so the walk
    // stops at that still-detached (and permanently 0x0) Item. The overlay then
    // fills nothing -- no scrim, and the dialog itself lands at negative x/y,
    // rendering as a clipped fragment in the scene's top-left corner forever.
    function _hostRoot() {
        var node = control
        var best = null
        while (node) {
            if (node.width > 0 && node.height > 0) best = node
            node = node.parent
        }
        return best
    }

    property int _openRetries: 0

    Timer {
        id: openRetryTimer
        interval: 16
        repeat: false
        onTriggered: control.open()
    }

    function open() {
        var root = _hostRoot()

        // Not attached to a laid-out scene yet (opened mid-construction, or on
        // the very first frame). Retry for ~half a second rather than painting
        // the broken fragment described above.
        if (!root) {
            if (control._openRetries < 30) {
                control._openRetries = control._openRetries + 1
                openRetryTimer.restart()
            }
            return
        }
        control._openRetries = 0

        overlayLayer.parent = root
        overlayLayer.z = 99999
        overlayLayer.anchors.fill = root

        // Stop any running animations
        exitAnimation.stop()
        enterAnimation.stop()

        // Reset properties for entry
        animationWrapper.scale = 0.9
        animationWrapper.opacity = 0.0
        scrim.opacity = 0.0

        overlayLayer.visible = true
        enterAnimation.start()
    }
    
    function close() {
        // A close that lands while open() is still waiting for a laid-out scene
        // must cancel that wait, or the dialog pops up after being dismissed.
        openRetryTimer.stop()
        control._openRetries = 0
        // Stop any running animations
        enterAnimation.stop()
        exitAnimation.stop()
        
        exitAnimation.start()
    }
    
    // Overlay Layer
    Item {
        id: overlayLayer
        visible: false
        
        // Animations
        ParallelAnimation {
            id: enterAnimation
            
            NumberAnimation { 
                target: scrim
                property: "opacity"
                from: 0.0
                to: 0.32
                duration: 150
                easing.type: Easing.OutQuad
            }
            
            NumberAnimation { 
                target: animationWrapper
                property: "scale"
                from: 0.9
                to: 1.0
                duration: 250
                easing.type: Easing.OutBack
                easing.overshoot: 1.0 // Gentle overshoot
            }
            
            NumberAnimation { 
                target: animationWrapper
                property: "opacity"
                from: 0.0
                to: 1.0
                duration: 150
            }
        }
        
        ParallelAnimation {
            id: exitAnimation
            onFinished: {
                overlayLayer.visible = false
                control.closed()
            }
            
            NumberAnimation { 
                target: scrim
                property: "opacity"
                from: 0.32
                to: 0.0
                duration: 150
            }
            
            NumberAnimation { 
                target: animationWrapper
                property: "opacity"
                from: 1.0
                to: 0.0
                duration: 100
            }
            
            // Optional: slight scale down on exit
             NumberAnimation { 
                target: animationWrapper
                property: "scale"
                from: 1.0
                to: 0.95
                duration: 100
            }
        }
        
        // Scrim
        Rectangle {
            id: scrim
            anchors.fill: parent
            color: "#000000"
            opacity: 0.0 // Controlled by animation
            
            MouseArea {
                anchors.fill: parent
                onClicked: if (control.closeOnScrim) control.close()
                onWheel: (wheel) => {} // Block scroll propagation
            }
        }
        
        // Wrapper for Dialog + Shadow to animate them together
        Item {
            id: animationWrapper
            anchors.centerIn: parent
            width: Math.min(560, Math.max(0, parent.width - 48))
            height: dialogContainer.height
            
            // Dialog Container
            Rectangle {
                id: dialogContainer
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                // Grows with its content, but never past the screen: a dialog
                // whose content outgrows the viewport is centred, so it would
                // otherwise spill off BOTH ends with no way to reach either.
                // Past the cap the body scrolls instead (see bodyScroll).
                // 48 of breathing room top and bottom also keeps a maxed-out
                // dialog clear of the system bars the scene draws under.
                height: {
                    var natural = mainColumn.implicitHeight + 48 // Padding
                    var cap = overlayLayer.height - 96
                    return cap > 0 && natural > cap ? cap : natural
                }
                radius: 28 // MD3 Extra Large
                color: _colors.surfaceContainerHigh

                // Block clicks from passing through to scrim
                MouseArea {
                    anchors.fill: parent
                }

                ColumnLayout {
                    id: mainColumn
                    anchors.top: parent.top
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.topMargin: 24
                    anchors.leftMargin: 24
                    anchors.rightMargin: 24
                    // Explicit height (not just the anchors) so that once the
                    // container is capped the column knows it is over budget and
                    // shrinks its one fillHeight child -- bodyScroll -- by exactly
                    // the overflow. Uncapped this equals implicitHeight, so nothing
                    // shrinks and every existing dialog lays out as before.
                    height: dialogContainer.height - 48
                    spacing: 16

                    // Icon
                    Text {
                        visible: control.icon !== ""
                        text: control.icon
                        font.family: Theme.iconFont.name
                        font.pixelSize: 24
                        color: _colors.secondary
                        Layout.alignment: Qt.AlignHCenter
                        Layout.bottomMargin: 0
                    }
                    
                    // Headline
                    Text {
                        visible: control.title !== ""
                        text: control.title
                        font.family: _typography.headlineSmall.family
                        font.pixelSize: _typography.headlineSmall.size
                        font.weight: _typography.headlineSmall.weight
                        color: _colors.onSurfaceColor
                        Layout.fillWidth: true
                        Layout.alignment: control.icon !== "" ? Qt.AlignHCenter : Qt.AlignLeft
                        horizontalAlignment: control.icon !== "" ? Text.AlignHCenter : Text.AlignLeft
                        wrapMode: Text.Wrap
                    }
                    
                    // Supporting text and custom content scroll together; the
                    // icon, the headline and the action row stay pinned (MD3).
                    // bodyColumn carries an explicit width because qml4j does not
                    // propagate fillWidth reliably into a nested layout whose own
                    // width is merely implied -- without it Text.Wrap stops working.
                    Flickable {
                        id: bodyScroll
                        Layout.fillWidth: true
                        // The natural height. The enclosing ColumnLayout overrides
                        // it downwards (and only it, being the sole fillHeight
                        // child) when the capped container leaves less than this.
                        Layout.preferredHeight: bodyColumn.implicitHeight
                        Layout.fillHeight: true
                        visible: control.text !== "" || contentPlaceholder.children.length > 0
                        contentWidth: width
                        contentHeight: bodyColumn.implicitHeight
                        // Only clip once there is something to scroll. Content
                        // is allowed to paint slightly outside its own box -- an
                        // outlined TextField floats its label to y: -8 -- and a
                        // permanent clip would shave that off every dialog that
                        // fits on screen perfectly well.
                        clip: bodyColumn.implicitHeight > bodyScroll.height

                        ColumnLayout {
                            id: bodyColumn
                            width: bodyScroll.width
                            spacing: 16

                            // Supporting Text
                            Text {
                                visible: control.text !== ""
                                text: control.text
                                font.family: _typography.bodyMedium.family
                                font.pixelSize: _typography.bodyMedium.size
                                font.weight: _typography.bodyMedium.weight
                                color: _colors.onSurfaceVariantColor
                                Layout.fillWidth: true
                                wrapMode: Text.Wrap
                            }

                            // Custom Content
                            Item {
                                id: contentPlaceholder
                                Layout.fillWidth: true
                                Layout.preferredHeight: childrenRect.height
                                visible: children.length > 0
                            }
                        }
                    }

                    // Actions
                    ColumnLayout {
                        width: mainColumn.width
                        Layout.fillWidth: true
                        Layout.topMargin: 8
                        spacing: 4

                        // Three-action dialogs use two full-width rows: the two
                        // alternative recovery paths share the first row, while
                        // the recommended retry action owns the second row.
                        RowLayout {
                            Layout.fillWidth: true
                            visible: control.showNeutralButton
                                     && !control.compactActionLayout
                            spacing: 8

                            Button {
                                Layout.fillWidth: true
                                Layout.preferredWidth: 1
                                text: control.neutralText
                                type: "outlined"
                                onClicked: {
                                    control.neutral()
                                    control.close()
                                }
                            }

                            Button {
                                Layout.fillWidth: true
                                Layout.preferredWidth: 1
                                visible: control.showRejectButton
                                text: control.rejectText
                                icon: control.rejectIcon
                                type: "outlined"
                                onClicked: {
                                    control.rejected()
                                    control.close()
                                }
                            }
                        }

                        Button {
                            Layout.fillWidth: true
                            visible: control.showNeutralButton
                                     && control.compactActionLayout
                            text: control.neutralText
                            type: "outlined"
                            onClicked: {
                                control.neutral()
                                control.close()
                            }
                        }

                        Button {
                            Layout.fillWidth: true
                            visible: control.showNeutralButton
                                     && control.showRejectButton
                                     && control.compactActionLayout
                            text: control.rejectText
                            icon: control.rejectIcon
                            type: "outlined"
                            onClicked: {
                                control.rejected()
                                control.close()
                            }
                        }

                        Button {
                            Layout.fillWidth: true
                            visible: control.showNeutralButton
                                     && control.showAcceptButton
                            text: control.acceptText
                            type: "filled"
                            onClicked: {
                                control.accepted()
                                control.close()
                            }
                        }

                        Item {
                            id: pairedActions
                            Layout.fillWidth: true
                            visible: !control.showNeutralButton
                            // Translated labels may need more space than the viewport
                            // offers. Stack complete, wrapping buttons when they do.
                            readonly property bool stacked: (control.showRejectButton ? rejectAction.implicitWidth : 0)
                                                           + (control.showAcceptButton ? acceptAction.implicitWidth : 0)
                                                           + (control.showRejectButton && control.showAcceptButton ? 8 : 0) > width
                            implicitHeight: stacked
                                            ? (control.showRejectButton ? rejectAction.height : 0)
                                              + (control.showAcceptButton ? acceptAction.height : 0)
                                              + (control.showRejectButton && control.showAcceptButton ? 8 : 0)
                                            : Math.max(control.showRejectButton ? rejectAction.height : 0,
                                                       control.showAcceptButton ? acceptAction.height : 0)

                            Button {
                                id: rejectAction
                                width: pairedActions.stacked ? pairedActions.width : implicitWidth
                                height: implicitHeight
                                x: pairedActions.stacked ? 0 : pairedActions.width - width
                                   - (control.showAcceptButton ? acceptAction.width + 8 : 0)
                                visible: control.showRejectButton
                                wrapText: true
                                verticalPadding: 8
                                text: control.rejectText
                                icon: control.rejectIcon
                                type: "text"
                                onClicked: {
                                    control.rejected()
                                    control.close()
                                }
                            }

                            Button {
                                id: acceptAction
                                width: pairedActions.stacked ? pairedActions.width : implicitWidth
                                height: implicitHeight
                                x: pairedActions.width - width
                                y: pairedActions.stacked && control.showRejectButton ? rejectAction.height + 8 : 0
                                visible: control.showAcceptButton
                                wrapText: true
                                verticalPadding: 8
                                text: control.acceptText
                                type: "filled" // Changed to filled as requested
                                onClicked: {
                                    control.accepted()
                                    control.close()
                                }
                            }
                        }
                    }
                }
            }
            
            // Shadow (MultiEffect)
            MultiEffect {
                source: dialogContainer
                anchors.fill: dialogContainer
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
