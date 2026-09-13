import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import miuix.Core
Item {
    id: control
    
    // API
    property var model: [] // Array of objects: { text, icon, trailingText, trailingIcon, type: "item"|"separator", action: func, enabled: bool, subItems: [] }
    property int menuPadding: 12
    // Cap the popup width so a long item label (e.g. a playlist name in a submenu)
    // can't stretch the menu nearly across the screen; the item text elides instead.
    property real maxWidth: 288
    // Cap the popup height; a longer list (many playlists) scrolls inside.
    property real maxHeight: 420
    // A submenu belongs to its parent popup and therefore must not evict it from
    // the scene-wide active-menu slot.
    property var ownerMenu: null
    property var activeSubMenu: null
    // Opt-in surface variant (surfaceContainerLow + a smaller corner radius,
    // same drop shadow as the default) that reads closer to the outlined
    // playlist card the playlist context menu opens from. Off by default so
    // every other Menu consumer (song rows, ComboBox) is unaffected.
    property bool outlined: false

    // Anchor for the popup, in menuRoot coordinates. open() records the raw desired
    // position; popupContainer.x/y then CLAMP it against menuRoot reactively, so the
    // menu stays on-screen even when its size is still settling (the model was just
    // rebuilt) at open() time — a one-shot clamp read stale/zero dimensions and let
    // the grown popup overflow.
    property var menuRoot: null
    property real targetX: 0
    property real targetY: 0
    // The item the popup is anchored to + the fixed offset from it, re-read every
    // followTimer tick so a wheel/flick scroll underneath moves the popup along
    // with its anchor instead of leaving it stranded over content that scrolled
    // away. mapFromItem walks plain (non-reactive) property reads internally, so
    // this can only track via polling, not a binding.
    property var _anchorItem: null
    property real _anchorXOffset: 0
    property real _anchorYOffset: 0
    
    // Theme Colors
    property var _colors: Theme.color
    property var _shape: Theme.shape
    property var _typography: Theme.typography
    property var _elevation: Theme.elevation
    property var _state: Theme.state

    // Signals
    signal closed()
    property int currentIndex: -1
    readonly property bool opened: overlayLayer.visible
    function moveSelection(step) {
        var index = currentIndex
        for (var i = 0; i < model.length; i++) {
            index = (index + step + model.length) % model.length
            if (model[index].type !== "separator" && model[index].enabled !== false) {
                currentIndex = index
                var entry = entries.itemAt(index)
                if (entry && entry.item) {
                    var top = entry.y
                    menuFlick.contentY = Math.max(0, Math.min(top, top + entry.height - menuFlick.height))
                }
                return
            }
        }
    }
    function activateSelection() {
        var entry = entries.itemAt(currentIndex)
        if (entry && entry.item) entry.item.activate()
    }


    // Hidden by default, takes no space
    visible: false
    width: 0
    height: 0
    
    // The Overlay Layer
    Item {
        id: overlayLayer
        visible: false
        Keys.onEscapePressed: { control.close(); event.accepted = true }
        Keys.onDownPressed: { control.moveSelection(1); event.accepted = true }
        Keys.onUpPressed: { control.moveSelection(-1); event.accepted = true }
        Keys.onReturnPressed: { control.activateSelection(); event.accepted = true }
        
        // Helper to close menu
        function close() { 
            startExitAnimation()
        }
        
        function forceClose() {
             overlayLayer.visible = false
             overlayLayer.parent = control
             overlayLayer.anchors.fill = undefined
        }
        
        // Scrim. Dismiss on press (not just click) so the menu also closes the moment
        // an outside scroll/flick begins — a click is press+move+release and never
        // fires when the finger drags to scroll the content underneath.
        MouseArea {
            anchors.fill: parent
            onPressed: overlayLayer.close()
            z: -1
        }
        
        // Popup Container (This scales up/down, carrying shadow and content)
        Item {
            id: popupContainer
            width: Math.max(0, Math.min(control.maxWidth, control.menuRoot ? control.menuRoot.width - 16 : control.maxWidth))
            height: Math.max(0, Math.min(control.maxHeight, control.menuRoot ? control.menuRoot.height - 16 : control.maxHeight, contentColumn.implicitHeight + (control.menuPadding * 2)))
            // Reactive on-screen clamp (see control.targetX/Y): recomputes whenever the
            // popup's own width/height settle, so a menu opened before layout finished
            // slides fully into view instead of overflowing.
            x: control.menuRoot
               ? Math.max(8, Math.min(control.targetX, control.menuRoot.width - width - 8))
               : control.targetX
            y: control.menuRoot
               ? Math.max(8, Math.min(control.targetY, control.menuRoot.height - height - 8))
               : control.targetY
            
            // Continue from current values when an open interrupts a pending close.
            scale: 0.8
            opacity: 0
            transformOrigin: Item.TopLeft

            ParallelAnimation {
                id: enterAnim
                NumberAnimation { target: popupContainer; property: "scale"; to: 1.0; duration: 200; easing.type: Easing.OutCubic }
                NumberAnimation { target: popupContainer; property: "opacity"; to: 1.0; duration: 150 }
            }
            ParallelAnimation {
                id: exitAnim
                onFinished: control._finishClose()
                NumberAnimation { target: popupContainer; property: "opacity"; to: 0.0; duration: 150 }
                NumberAnimation { target: popupContainer; property: "scale"; to: 0.8; duration: 150; easing.type: Easing.InCubic }
            }

            // Shadow Source
            Rectangle {
                id: shadowSource
                anchors.fill: parent
                radius: menuBackground.radius
                color: _colors.surfaceContainer
                visible: false
            }
            
            // Shadow Effect
            MultiEffect {
                source: shadowSource
                anchors.fill: shadowSource
                shadowEnabled: true
                shadowColor: _colors.shadow
                shadowBlur: _elevation.level2 * 0.5
                shadowVerticalOffset: _elevation.level2
                shadowOpacity: 0.2
                z: 0
                // Opacity is inherited from parent (popupContainer), no need to double apply
            }

            // Menu Background & Content
            Rectangle {
                id: menuBackground
                z: 1
                anchors.fill: parent
                color: control.outlined ? _colors.surfaceContainerLow : _colors.surfaceContainer
                radius: control.outlined ? 12 : 16
                clip: true
                
                Flickable {
                    id: menuFlick
                    anchors.fill: parent
                    anchors.topMargin: control.menuPadding
                    anchors.bottomMargin: control.menuPadding
                    contentWidth: width
                    contentHeight: contentColumn.implicitHeight
                    clip: true

                    ColumnLayout {
                        id: contentColumn
                        spacing: 0
                        width: menuFlick.width

                        Repeater {
                            id: entries
                            model: control.model
                            delegate: Loader {
                                Layout.fillWidth: true
                                sourceComponent: modelData.type === "separator" ? separatorComponent : itemComponent

                                property var itemData: modelData
                                property int itemIndex: index

                                required property var modelData
                                required property int index
                            }
                        }
                    }
                }
            }
        }
    }

    // Whether the anchor is still genuinely on screen: reachable from menuRoot
    // through an unbroken chain of visible ancestors. A page swap (StackLayout,
    // a tab switch) commonly hides the anchor's whole page without moving it --
    // its scene position stays a valid on-screen coordinate, so the bounds check
    // in the timer below can't catch that alone; a hidden ancestor anywhere up
    // the chain means the anchor (and the popup floating over whatever replaced
    // it) needs to go too.
    function _anchorReachable(item) {
        var n = item
        while (n) {
            if (n.visible === false) return false
            if (n === control.menuRoot) return true
            n = n.parent
        }
        return false
    }

    // Closes the popup the moment its anchor moves at all -- a wheel scroll
    // underneath included, which the scrim's press-to-dismiss below never
    // sees (a wheel notch is not a press). Simpler than trying to carry the
    // popup along with the scroll: it just goes away, like a click outside it.
    Timer {
        id: followTimer
        interval: 16
        repeat: true
        running: overlayLayer.visible && control._anchorItem !== null
        onTriggered: {
            // Already closing (its own exit animation is running): stop recomputing
            // -- calling close() again here would restart that animation every
            // tick and it would never actually finish.
            if (!control._anchorItem || !control.menuRoot || exitAnim.running) return
            if (!control._anchorReachable(control._anchorItem)) {
                control.close()
                return
            }
            var pos = control.menuRoot.mapFromItem(control._anchorItem, 0, 0)
            var nx = pos.x + control._anchorXOffset
            var ny = pos.y + control._anchorYOffset
            if (Math.abs(nx - control.targetX) > 1 || Math.abs(ny - control.targetY) > 1) {
                control.close()
            }
        }
    }

    // Animation Helpers
    function startEntranceAnimation() {
        exitAnim.stop()
        enterAnim.restart()
    }

    function startExitAnimation() {
        if (!overlayLayer.visible || exitAnim.running) return
        enterAnim.stop()
        exitAnim.restart()
    }

    // Components
    Component {
        id: separatorComponent
        Item {
            implicitWidth: 112
            implicitHeight: 17 // 1px + 16dp padding
            Layout.fillWidth: true
            Rectangle {
                anchors.centerIn: parent
                width: parent.width
                height: 1
                color: _colors.outlineVariant
            }
        }
    }
    
    Component {
        id: itemComponent
        Item {
            id: menuItem
            implicitWidth: Math.max(112, row.implicitWidth + 24)
            implicitHeight: 56
            objectName: "miuixMenuItem" + parent.itemIndex
            Layout.fillWidth: true
            
            property bool itemEnabled: itemData.enabled !== undefined ? itemData.enabled : true
            property bool hasSubMenu: !!itemData.subItems && itemData.subItems.length > 0
            
            // Submenu Loader. qml4j resolves Loader.source against the RESOURCE ROOT
            // (assets/), not the defining file's directory as Qt does — so a bare
            // "Menu.qml" fails to load (this file lives at md3/Core/) and the submenu
            // silently never appears. Use the full module-relative path.
            Loader {
                id: subMenuLoader
                active: hasSubMenu
                source: "miuix/Core/Menu.qml"
                onLoaded: {
                    item.model = itemData.subItems
                    item.ownerMenu = control
                }
            }
            
            // State Layer
            Rectangle {
                anchors.fill: parent
                color: _colors.onSurfaceColor
                opacity: {
                    if (!itemEnabled) return 0
                    if (control.currentIndex === menuItem.parent.itemIndex) return 0.08
                    return 0
                }
                Behavior on opacity { NumberAnimation { duration: 150 } }
            }

            // Ripple — the sole pointer handler. The desktop hover MouseArea that used
            // to sit on top (submenu-on-hover) swallowed touch presses under qml4j, so
            // taps never reached this Ripple; removed. Submenus open on tap below.
            Ripple {
                id: itemRipple
                anchors.fill: parent
                enabled: itemEnabled
                rippleColor: _colors.onSurfaceColor
                onClicked: menuItem.activate()
            }
            function activate() {
                if (!itemEnabled) return
                if (hasSubMenu) {
                    if (subMenuLoader.item) subMenuLoader.item.open(menuItem, menuItem.width, -12)
                } else {
                    if (typeof itemData.action === "function") itemData.action()
                    var menu = control
                    while (menu.ownerMenu) menu = menu.ownerMenu
                    menu.close()
                }
            }

            RowLayout {
                id: row
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 12
                
                // Icon
                Text {
                    visible: !!itemData.icon
                    text: itemData.icon || ""
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Text
                Text {
                    text: itemData.text || ""
                    font.family: _typography.labelLarge.family
                    font.pixelSize: 16
                    font.weight: Font.Normal
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    elide: Text.ElideRight
                    verticalAlignment: Text.AlignVCenter
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Trailing Text
                Text {
                    visible: !!itemData.trailingText
                    text: itemData.trailingText || ""
                    font.family: _typography.labelLarge.family
                    font.pixelSize: 16
                    font.weight: Font.Normal
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    horizontalAlignment: Text.AlignRight
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Trailing Icon (or Arrow for submenu)
                Text {
                    visible: !!itemData.trailingIcon || hasSubMenu
                    text: hasSubMenu ? "arrow_right" : (itemData.trailingIcon || "")
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    color: _colors.onSurfaceVariantColor
                    opacity: itemEnabled ? 1 : 0.38
                    Layout.alignment: Qt.AlignVCenter
                }
            }
            
        }
    }
    
    // Logic
    function open(target, xOffset, yOffset) {
        if (!target) return
        
        // Find Root
        var root = control
        while (root.parent) {
            root = root.parent
        }
        
        if (root) {
            // Every song row owns its own Menu instance. Enforce one top-level
            // popup per scene before reparenting this overlay, otherwise repeated
            // right-clicks accumulate independent full-window overlays and menus.
            if (control.ownerMenu) {
                if (control.ownerMenu.activeSubMenu
                        && control.ownerMenu.activeSubMenu !== control) {
                    control.ownerMenu.activeSubMenu.dismissImmediately()
                }
                control.ownerMenu.activeSubMenu = control
            } else {
                PopupRegistry.claim(root, control)
            }

            overlayLayer.parent = root
            overlayLayer.z = 99999
            overlayLayer.anchors.fill = root
            
            var targetPos = root.mapFromItem(target, 0, 0)
            // Record the desired anchor; popupContainer.x/y clamp it reactively against
            // menuRoot so the popup can't overflow even if its size settles after open().
            control.menuRoot = root
            control.targetX = targetPos.x + (xOffset !== undefined ? xOffset : 0)
            control.targetY = targetPos.y + (yOffset !== undefined ? yOffset : 0)
            // followTimer re-derives targetX/Y from these every tick so a scroll
            // underneath carries the popup along (see its own comment).
            control._anchorItem = target
            control._anchorXOffset = xOffset !== undefined ? xOffset : 0
            control._anchorYOffset = yOffset !== undefined ? yOffset : 0

            overlayLayer.visible = true
            overlayLayer.forceActiveFocus()
            control.currentIndex = -1
            startEntranceAnimation()
        }
    }
    
    function close() {
        overlayLayer.close()
    }

    function dismissImmediately() {
        enterAnim.stop()
        exitAnim.stop()
        _finishClose()
    }

    function _finishClose() {
        if (!overlayLayer.visible) return
        if (control.activeSubMenu) {
            control.activeSubMenu.dismissImmediately()
            control.activeSubMenu = null
        }
        if (control._anchorItem) control._anchorItem.forceActiveFocus()
        control._anchorItem = null
        overlayLayer.forceClose()
        if (control.ownerMenu) {
            if (control.ownerMenu.activeSubMenu === control)
                control.ownerMenu.activeSubMenu = null
        } else PopupRegistry.release(control)
        control.closed()
    }
}
