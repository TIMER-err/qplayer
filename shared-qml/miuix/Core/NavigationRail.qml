import QtQuick
import miuix.Core

// Keep caller-supplied Component factories outside the internal delegate owner.
Item {
    id: railRoot
    property alias model: body.model
    property alias currentIndex: body.currentIndex
    property alias selectOnClick: body.selectOnClick
    property alias extended: body.extended
    property alias expandable: body.expandable
    property alias showToggle: body.showToggle
    property alias showDivider: body.showDivider
    property alias sectionLabel: body.sectionLabel
    property alias minWidth: body.minWidth
    property alias expandedWidth: body.expandedWidth
    property alias topInset: body.topInset
    property alias bottomInset: body.bottomInset
    property alias backgroundColor: body.backgroundColor
    property alias header: body.header
    property alias headerActions: body.headerActions
    property alias footer: body.footer
    property alias delegate: body.delegate
    signal itemClicked(int index, var itemData)

    implicitWidth: body.implicitWidth
    implicitHeight: body.implicitHeight
    function toggle() { body.toggle() }
    function select(index) { body.select(index) }
    NavigationRailBody {
        id: body
        anchors.fill: parent
        enabled: railRoot.enabled
        onItemClicked: (index, itemData) => { railRoot.itemClicked(index, itemData) }
    }
}
