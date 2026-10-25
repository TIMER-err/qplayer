// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// QML/Skija port of miuix IosLiquidGlassNavigationBar; see miuix/NOTICE.md.
import QtQuick
import miuix.Core

Item {
    id: navigation
    property var items: []
    property int currentIndex: 0
    property real bottomInset: 0
    property int pendingIndex: 0
    signal navigate()
    readonly property real bottomPadding: bottomInset > 0 ? bottomInset + 8 : 36
    implicitHeight: 64 + bottomPadding

    Item {
        id: surface
        objectName: "liquidGlassNavigationSurface"
        x: 24
        width: Math.max(0, parent.width - 48)
        height: 64
        visible: navigation.visible
        property var normalContent: normalTabs
        property var accentContent: accentTabs
        property int itemCount: navigation.items.length
        property int selectedIndex: navigation.currentIndex
        property color surfaceColor: Theme.color.surfaceContainer
        property bool darkTheme: Theme.dark
        property real targetIndex: navigation.currentIndex
        property bool pointerDown: false
        property real pointerX: 0
        property real accumulatedDrag: 0
        property real accentScale: 1
        readonly property real tabWidth: itemCount > 0 ? Math.max(0, width - 8) / itemCount : 0

        function indexAt(x) {
            if (tabWidth <= 0) return selectedIndex
            return Math.max(0, Math.min(itemCount - 1, Math.floor((x - 4) / tabWidth)))
        }
        function cancel() {
            pointerDown = false
            targetIndex = selectedIndex
            accumulatedDrag = 0
        }
        onSelectedIndexChanged: {
            if (!surface.pointerDown) surface.targetIndex = surface.selectedIndex
        }
        onItemCountChanged: surface.cancel()
        onVisibleChanged: { if (!surface.visible) surface.cancel() }
        onEnabledChanged: { if (!surface.enabled) surface.cancel() }

        // These are draw sources, not a second visible/accessibility tab strip.
        // The native node replays them in the ordinary and refracted accent layers.
        Item {
            id: normalTabs
            visible: false
            width: Math.max(0, surface.width - 8)
            height: 56
            Repeater {
                model: navigation.items
                delegate: Item {
                    x: index * surface.tabWidth
                    width: surface.tabWidth
                    height: 56
                    Icon {
                        id: normalIcon
                        x: (parent.width - width) / 2
                        y: (parent.height - 23 - normalLabel.implicitHeight) / 2
                        width: 22
                        height: 22
                        name: modelData.icon || ""
                        color: Theme.color.onSurface
                    }
                    Text {
                        id: normalLabel
                        x: 0
                        y: normalIcon.y + 23
                        width: parent.width
                        text: modelData.text || ""
                        font.pixelSize: 11
                        font.weight: Font.Normal
                        horizontalAlignment: Text.AlignHCenter
                        maximumLineCount: 1
                        elide: Text.ElideRight
                        color: Theme.color.onSurface
                    }
                }
            }
        }
        Item {
            id: accentTabs
            visible: false
            width: Math.max(0, surface.width - 8)
            height: 56
            Repeater {
                model: navigation.items
                delegate: Item {
                    x: index * surface.tabWidth
                    width: surface.tabWidth
                    height: 56
                    scale: surface.accentScale
                    Icon {
                        id: accentIcon
                        x: (parent.width - width) / 2
                        y: (parent.height - 23 - accentLabel.implicitHeight) / 2
                        width: 22
                        height: 22
                        name: modelData.icon || ""
                        color: Theme.color.primary
                    }
                    Text {
                        id: accentLabel
                        x: 0
                        y: accentIcon.y + 23
                        width: parent.width
                        text: modelData.text || ""
                        font.pixelSize: 11
                        font.weight: Font.Normal
                        horizontalAlignment: Text.AlignHCenter
                        maximumLineCount: 1
                        elide: Text.ElideRight
                        color: Theme.color.primary
                    }
                }
            }
        }
        MouseArea {
            objectName: "liquidGlassNavigationGesture"
            anchors.fill: parent
            enabled: navigation.enabled && surface.itemCount > 0
            preventStealing: true
            onPressed: (mouse) => {
                surface.pointerX = mouse.x
                surface.accumulatedDrag = 0
                surface.targetIndex = surface.indexAt(mouse.x)
                surface.pointerDown = true
            }
            onPositionChanged: (mouse) => {
                if (!surface.pointerDown) return
                if (surface.tabWidth > 0 && mouse.x >= 0 && mouse.x <= surface.width
                        && surface.pointerX >= 0 && surface.pointerX <= surface.width) {
                    var dx = mouse.x - surface.pointerX
                    surface.targetIndex = Math.max(0, Math.min(surface.itemCount - 1,
                        surface.targetIndex + dx / surface.tabWidth))
                    surface.accumulatedDrag += dx
                }
                surface.pointerX = mouse.x
            }
            onReleased: {
                if (!surface.pointerDown) return
                surface.pointerDown = false
                surface.targetIndex = Math.max(0, Math.min(surface.itemCount - 1,
                    Math.round(surface.targetIndex)))
                surface.accumulatedDrag = 0
                navigation.pendingIndex = surface.targetIndex
                if (navigation.pendingIndex !== navigation.currentIndex) navigation.navigate()
            }
            onCanceled: surface.cancel()
        }
    }
}
