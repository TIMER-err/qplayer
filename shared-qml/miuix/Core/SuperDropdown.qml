import QtQuick
import QtQuick.Effects
import miuix.Core

Item {
    id: superDropdownRoot
    property string title: ""
    property string summary: ""
    // Entries may be strings or { text, summary, enabled } objects.
    property var items: []
    property int currentIndex: 0
    property bool enabled: true
    property bool selectOnClick: true
    property real popupWidth: 288
    property real popupMaxHeight: 420
    readonly property bool menuOpen: overlayLayer.visible
    readonly property string currentValue: currentIndex >= 0 && currentIndex < items.length ? itemText(items[currentIndex]) : ""
    signal clicked()
    signal activated(int index)
    activeFocusOnTab: true
    property int highlightedIndex: currentIndex
    function moveHighlight(delta) {
        for (var i = 1; i <= items.length; i++) {
            var next = (highlightedIndex + delta * i + items.length) % items.length
            if (itemEnabled(items[next])) { highlightedIndex = next; return }
        }
    }
    Keys.onReturnPressed: { openMenu(); event.accepted = true }
    Keys.onSpacePressed: { openMenu(); event.accepted = true }

    width: parent ? parent.width : 320
    implicitHeight: preference.implicitHeight
    property var _host: null
    property real _anchorX: 0
    property real _anchorTop: 0
    property real _anchorBottom: 0
    property real _progress: 0
    property bool _closing: false

    function itemText(item) { return typeof item === "string" ? item : (item.text || "") }
    function itemSummary(item) { return typeof item === "string" ? "" : (item.summary || "") }
    function itemEnabled(item) { return typeof item === "string" || item.enabled !== false }
    function select(index) {
        if (!enabled || index < 0 || index >= items.length || !itemEnabled(items[index])) return
        if (selectOnClick) currentIndex = index
        activated(index)
        closeMenu()
    }
    function positionPopup() {
        if (!_host) return
        var pos = _host.mapFromItem(superDropdownRoot, 0, 0)
        _anchorX = pos.x + width
        _anchorTop = pos.y
        _anchorBottom = pos.y + height
    }
    function revealSelection() {
        var selected = list.children[currentIndex + 1]
        if (selected && selected.y !== undefined) {
            viewport.contentY = Math.max(0, Math.min(selected.y - (viewport.height - selected.height) / 2,
                Math.max(0, list.height - viewport.height)))
        }
    }
    function openMenu() {
        if (!enabled || items.length === 0 || (menuOpen && !_closing)) return
        var host = superDropdownRoot
        while (host.parent) host = host.parent
        _host = host
        exitAnim.stop()
        _closing = false
        overlayLayer.parent = host
        overlayLayer.anchors.fill = host
        positionPopup()
        if (!menuOpen) _progress = 0
        overlayLayer.visible = true
        highlightedIndex = currentIndex
        overlayLayer.forceActiveFocus()
        viewport.contentY = 0
        enterAnim.start()
        selectionTimer.restart()
    }
    function closeMenu() {
        if (!menuOpen || _closing) return
        enterAnim.stop()
        _closing = true
        exitAnim.start()
    }
    onEnabledChanged: if (!enabled) closeMenu()
    onItemsChanged: if (items.length === 0) closeMenu()

    SuperArrow {
        id: preference
        anchors.fill: parent
        title: superDropdownRoot.title
        summary: superDropdownRoot.summary
        rightText: superDropdownRoot.currentValue
        indicator: "unfold_more"
        enabled: superDropdownRoot.enabled
        onClicked: { superDropdownRoot.openMenu(); superDropdownRoot.clicked() }
    }
    NumberAnimation {
        id: enterAnim
        target: superDropdownRoot
        property: "_progress"
        to: 1
        duration: 220
        easing.type: Easing.OutCubic
    }
    NumberAnimation {
        id: exitAnim
        target: superDropdownRoot
        property: "_progress"
        to: 0
        duration: 150
        easing.type: Easing.OutCubic
        onFinished: {
            overlayLayer.visible = false
            overlayLayer.anchors.fill = undefined
            overlayLayer.parent = superDropdownRoot
            superDropdownRoot._closing = false
        }
    }
    Timer {
        interval: 40
        repeat: true
        running: superDropdownRoot.menuOpen
        onTriggered: superDropdownRoot.positionPopup()
    }
    Timer {
        id: selectionTimer
        interval: 40
        onTriggered: superDropdownRoot.revealSelection()
    }
    Item {
        id: overlayLayer
        Keys.onEscapePressed: { superDropdownRoot.closeMenu(); event.accepted = true }
        Keys.onDownPressed: { superDropdownRoot.moveHighlight(1); event.accepted = true }
        Keys.onUpPressed: { superDropdownRoot.moveHighlight(-1); event.accepted = true }
        Keys.onReturnPressed: { superDropdownRoot.select(superDropdownRoot.highlightedIndex); event.accepted = true }
        visible: false
        z: 99999
        MouseArea {
            anchors.fill: parent
            onPressed: superDropdownRoot.closeMenu()
            onWheel: (wheel) => { wheel.accepted = true }
        }
        Item {
            id: popupContainer
            objectName: "miuixDropdownPanel"
            width: Math.max(0, Math.min(superDropdownRoot.popupWidth, overlayLayer.width - 16))
            height: Math.max(0, Math.min(list.height, superDropdownRoot.popupMaxHeight, overlayLayer.height - 16))
            x: Math.max(8, Math.min(superDropdownRoot._anchorX - width, overlayLayer.width - width - 8))
            y: Math.max(8, Math.min(superDropdownRoot._anchorBottom + height <= overlayLayer.height - 8
                ? superDropdownRoot._anchorBottom : superDropdownRoot._anchorTop - height, overlayLayer.height - height - 8))
            scale: 0.9 + 0.1 * superDropdownRoot._progress
            opacity: superDropdownRoot._progress
            transformOrigin: Item.TopRight

            SmoothRectangle {
                id: shadowSource
                anchors.fill: parent
                radius: 16
                color: Theme.color.surfaceContainer
                visible: false
            }
            MultiEffect {
                anchors.fill: parent
                source: shadowSource
                shadowEnabled: true
                shadowColor: Theme.color.shadow
                shadowBlur: 0.4
                shadowVerticalOffset: 4
                shadowOpacity: 0.12
            }
            Card {
                anchors.fill: parent
                MouseArea {
                    anchors.fill: parent
                    onWheel: (wheel) => { wheel.accepted = true }
                }
                Flickable {
                    id: viewport
                    objectName: "miuixDropdownViewport"
                    anchors.fill: parent
                    contentWidth: width
                    contentHeight: list.height
                    flickableDirection: "VerticalFlick"
                    clip: true
                    Column {
                        id: list
                        width: viewport.width
                        Repeater {
                            model: superDropdownRoot.items
                            delegate: Item {
                                id: optionRow
                                property bool selected: index === superDropdownRoot.currentIndex
                                property bool optionEnabled: superDropdownRoot.enabled && superDropdownRoot.itemEnabled(modelData)
                                property real topPadding: index === 0 ? 20 : 12
                                property real bottomPadding: index === superDropdownRoot.items.length - 1 ? 20 : 12
                                width: list.width
                                height: Math.max(20, labels.height) + topPadding + bottomPadding
                                Ripple {
                                    anchors.fill: parent
                                    enabled: optionRow.optionEnabled
                                    onClicked: superDropdownRoot.select(index)
                                }
                                Column {
                                    id: labels
                                    x: 20
                                    y: optionRow.topPadding
                                    width: Math.max(0, optionRow.width - 72)
                                    Text {
                                        width: parent.width
                                        text: superDropdownRoot.itemText(modelData)
                                        font.pixelSize: 16
                                        font.weight: 57
                                        wrapMode: Text.Wrap
                                        color: !optionRow.optionEnabled ? Theme.color.disabledOnSecondaryVariant
                                            : (optionRow.selected ? Theme.color.primary : Theme.color.onSurfaceContainer)
                                    }
                                    Text {
                                        width: parent.width
                                        text: superDropdownRoot.itemSummary(modelData)
                                        visible: text.length > 0
                                        font.pixelSize: 14
                                        wrapMode: Text.Wrap
                                        color: !optionRow.optionEnabled ? Theme.color.disabledOnSecondaryVariant
                                            : (optionRow.selected ? Theme.color.primary : Theme.color.onSurfaceVariantSummary)
                                    }
                                }
                                Icon {
                                    width: 20
                                    height: 20
                                    anchors.right: parent.right
                                    anchors.rightMargin: 20
                                    y: optionRow.topPadding + (Math.max(20, labels.height) - height) / 2
                                    name: "check"
                                    visible: optionRow.selected
                                    color: optionRow.optionEnabled ? Theme.color.primary : Theme.color.disabledOnSecondaryVariant
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
