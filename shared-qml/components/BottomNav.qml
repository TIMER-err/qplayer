import QtQuick
import miuix.Core
import "."

Item {
    id: bar
    property int currentIndex: 0
    property real bottomInset: 0
    property bool liquidGlass: false
    property int pendingIndex: 0
    property var items: []
    signal navigate()
    readonly property real barHeight: liquidGlass
        ? 64 + (bottomInset > 0 ? bottomInset + 8 : 36)
        : 65 + bottomInset
    implicitHeight: barHeight

    NavigationBar {
        anchors.fill: parent
        visible: !bar.liquidGlass
        model: bar.items
        currentIndex: bar.currentIndex
        bottomInset: bar.bottomInset
        selectOnClick: false
        selectedContentColor: Theme.color.primary
        onActivated: (index) => { bar.pendingIndex = index; bar.navigate() }
    }
    LiquidGlassNavigation {
        id: glassNavigation
        anchors.fill: parent
        visible: bar.liquidGlass
        items: bar.items
        currentIndex: bar.currentIndex
        bottomInset: bar.bottomInset
        onNavigate: {
            bar.pendingIndex = glassNavigation.pendingIndex
            bar.navigate()
        }
    }
}
