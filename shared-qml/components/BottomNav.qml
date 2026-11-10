import QtQuick
import miuix.Core
import design
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
        // Own hairline below instead of miuix's divider. Nothing blurs behind
        // the ordinary bar, so it stays nearly opaque to keep labels legible.
        showDivider: false
        backgroundColor: Ui.hexAlpha(Ui.background, 0.97)
        contentColor: Ui.textPrimary
        selectedContentColor: Ui.accent
        onActivated: (index) => { bar.pendingIndex = index; bar.navigate() }
    }
    // 1px top border for the ordinary bar (barHeight keeps its 65px: 1 hairline
    // + miuix's 64px item row).
    Rectangle {
        visible: !bar.liquidGlass
        width: parent.width
        height: 1
        color: Ui.hairline
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
