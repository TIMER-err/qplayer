import QtQuick
import miuix.Core

NavigationBar {
    id: bar
    property int pendingIndex: 0
    property var items: []
    signal navigate()
    implicitHeight: 65
    model: bar.items
    selectOnClick: false
    selectedContentColor: Theme.color.primary
    onActivated: (index) => { bar.pendingIndex = index; bar.navigate() }
}
