import QtQuick
import QtQuick.Layouts
import miuix.Core

Item {
    id: navigationRoot
    // Entries: { icon, text, enabled, badge }. Content children follow the same order.
    property var model: []
    default property alias content: pages.data
    property int currentIndex: 0
    property bool selectOnClick: true
    property string mode: "iconAndText"
    property bool showDivider: true
    property real bottomInset: 0
    property color backgroundColor: Theme.color.surface
    property color contentColor: Theme.color.onSurfaceContainer
    property color selectedContentColor: Theme.color.onSurfaceContainer
    readonly property real barHeight: model.length > 0 ? 64 + (showDivider ? 1 : 0) + Math.max(0, bottomInset) : 0
    signal activated(int index)

    implicitWidth: 640
    implicitHeight: 480

    function select(index) {
        if (!enabled || index < 0 || index >= model.length || model[index].enabled === false) return
        if (selectOnClick) currentIndex = index
        activated(index)
    }

    StackLayout {
        id: pages
        objectName: "miuixNavigationPages"
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: bar.top
        currentIndex: navigationRoot.currentIndex
        clip: true
    }
    Rectangle {
        id: bar
        objectName: "miuixNavigationBar"
        anchors.bottom: parent.bottom
        width: parent.width
        height: navigationRoot.barHeight
        visible: navigationRoot.model.length > 0
        color: navigationRoot.backgroundColor
        MouseArea { anchors.fill: parent }
        Divider { width: parent.width; visible: navigationRoot.showDivider }
        Row {
            y: navigationRoot.showDivider ? 1 : 0
            width: parent.width
            height: 64
            Repeater {
                model: navigationRoot.model
                delegate: Item {
                    id: navItem
                    objectName: "miuixNavigationItem" + index
                    width: navigationRoot.model.length > 0 ? bar.width / navigationRoot.model.length : 0
                    height: 64
                    property bool selected: index === navigationRoot.currentIndex
                    property var itemData: modelData
                    property bool available: navigationRoot.enabled && modelData.enabled !== false
                    property real stateOpacity: !available ? 0.2 : pointer.pressed ? (selected ? 0.5 : 0.6) : selected ? 1 : 0.4
                    property color tint: selected ? navigationRoot.selectedContentColor : navigationRoot.contentColor
                    property bool hasLabel: navigationRoot.mode === "iconAndText"
                        || (navigationRoot.mode === "iconWithSelectedLabel" && selected)
                    BadgedBox {
                        id: navIcon
                        objectName: "miuixNavigationIcon" + index
                        x: (parent.width - width) / 2
                        y: navItem.hasLabel ? 8 : 19
                        width: 26
                        height: 26
                        badgeBounds: ({x: -x, y: -y, width: navItem.width, height: navItem.height})
                        badge: modelData.badge !== undefined && modelData.badge !== null ? badgeContent : null
                        Icon {
                            width: 26
                            height: 26
                            name: modelData.icon || ""
                            color: navItem.tint
                            opacity: navItem.stateOpacity
                        }
                        Component {
                            id: badgeContent
                            Badge {
                                objectName: "miuixNavigationBadge" + navItem.index
                                text: String(navItem.itemData.badge)
                            }
                        }
                        Behavior on y { NumberAnimation { duration: 300; easing.type: Easing.OutCubic } }
                    }
                    Text {
                        objectName: "miuixNavigationLabel" + index
                        x: 4
                        y: 34
                        width: Math.max(0, parent.width - 8)
                        text: modelData.text || ""
                        font.pixelSize: 12
                        font.weight: navItem.selected ? Font.Bold : Font.Normal
                        horizontalAlignment: Text.AlignHCenter
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        color: navItem.tint
                        opacity: navItem.hasLabel ? navItem.stateOpacity : 0
                        Behavior on opacity { NumberAnimation { duration: 300; easing.type: Easing.OutCubic } }
                    }
                    MouseArea {
                        id: pointer
                        anchors.fill: parent
                        enabled: navItem.available
                        onClicked: navigationRoot.select(index)
                    }
                }
            }
        }
    }
}
