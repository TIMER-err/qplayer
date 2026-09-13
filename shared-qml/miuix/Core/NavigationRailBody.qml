import QtQuick
import miuix.Core

Item {
    id: railRoot
    property var model: []
    property int currentIndex: 0
    property bool selectOnClick: true
    property bool extended: false
    property bool expandable: true
    property bool showToggle: expandable
    property bool showDivider: true
    property string sectionLabel: ""
    property real minWidth: 80
    property real expandedWidth: 240
    property real topInset: 0
    property real bottomInset: 0
    property color backgroundColor: Theme.color.surface
    property Component header: null
    property Component headerActions: null
    property Component footer: null
    property Component delegate: null
    signal itemClicked(int index, var itemData)

    property real _progress: expandable && extended ? 1 : 0
    Behavior on _progress { NumberAnimation { duration: 350; easing.type: Easing.OutCubic } }
    implicitWidth: minWidth + (Math.max(minWidth, expandedWidth) - minWidth) * _progress
    implicitHeight: 480

    function toggle() { if (enabled && expandable) extended = !extended }
    function select(index) {
        if (!enabled || index < 0 || index >= model.length || model[index].enabled === false) return
        if (selectOnClick) currentIndex = index
        itemClicked(index, model[index])
    }

    Rectangle { anchors.fill: parent; color: railRoot.backgroundColor }
    Rectangle {
        anchors.right: parent.right
        width: 1
        height: parent.height
        visible: railRoot.showDivider
        color: Theme.color.dividerLine
    }
    Flickable {
        id: viewport
        objectName: "miuixRailViewport"
        y: railRoot.topInset
        width: parent.width - (railRoot.showDivider ? 1 : 0)
        height: Math.max(0, footerLoader.y - y - (footerLoader.visible ? 24 : 0))
        contentWidth: width
        contentHeight: entries.height + 48
        flickableDirection: "VerticalFlick"
        clip: true
        Column {
            id: entries
            y: 24
            width: viewport.width
            Item {
                width: parent.width
                height: 80
                visible: railRoot.showToggle
                IconButton {
                    objectName: "miuixRailToggle"
                    x: (railRoot.minWidth - width) / 2 * (1 - railRoot._progress) + 12 * railRoot._progress
                    width: 56
                    height: 56
                    icon: "view_sidebar"
                    enabled: railRoot.enabled
                    onClicked: railRoot.toggle()
                }
            }
            Loader {
                id: headerLoader
                width: parent.width
                height: item ? item.implicitHeight : 0
                sourceComponent: railRoot.header
                visible: railRoot.header !== null
            }
            Item { width: parent.width; height: 24; visible: railRoot.header !== null }
            Loader {
                width: parent.width
                height: item ? item.implicitHeight : 0
                sourceComponent: railRoot.headerActions
                visible: railRoot.headerActions !== null
            }
            Item { width: parent.width; height: 24; visible: railRoot.headerActions !== null }
            Item {
                width: parent.width
                height: railRoot.sectionLabel.length > 0 ? 28 * railRoot._progress : 0
                visible: height > 0
                opacity: railRoot._progress
                Text {
                    x: 26
                    width: Math.max(0, parent.width - 52)
                    text: railRoot.sectionLabel
                    font.pixelSize: 14
                    color: Theme.color.onSurfaceVariantSummary
                    elide: Text.ElideRight
                }
            }
            Repeater {
                model: railRoot.model
                delegate: Item {
                    id: railItem
                    objectName: "miuixRailItem" + index
                    width: entries.width
                    height: Math.round((56 + (railRoot.expandable ? 8 : 0) + label.implicitHeight) * (1 - railRoot._progress)
                        + (28 + Math.max(28, label.implicitHeight)) * railRoot._progress)
                    property bool selected: index === railRoot.currentIndex
                    property var itemData: modelData
                    property bool available: railRoot.enabled && modelData.enabled !== false
                    property real iconX: (railRoot.minWidth - 28) / 2 * (1 - railRoot._progress) + 26 * railRoot._progress
                    property real iconY: (12 + (railRoot.expandable ? 4 : 0)) * (1 - railRoot._progress)
                        + (height - 28) / 2 * railRoot._progress
                    property real labelLimit: width * (1 - railRoot._progress)
                        + Math.max(0, width - 2 * 12 - 2 * 14 - 28 - 16) * railRoot._progress
                    Item {
                        anchors.fill: parent
                        visible: railRoot.delegate === null
                        opacity: railItem.available ? 1 : 0.35
                        SmoothRectangle {
                            id: indicator
                            objectName: "miuixRailIndicator" + index
                            x: railRoot.expandable ? railItem.iconX - 14 : 0
                            y: railRoot.expandable ? railItem.iconY - (4 + 10 * railRoot._progress) : 0
                            width: railRoot.expandable ? 56 * (1 - railRoot._progress) + (railItem.width - 24) * railRoot._progress : parent.width
                            height: railRoot.expandable ? 36 + 20 * railRoot._progress : parent.height
                            radius: railRoot.expandable ? 16 : 0
                            color: railRoot.expandable && railItem.selected ? Theme.color.surfaceContainerHigh : "transparent"
                            SmoothRectangle {
                                anchors.fill: parent
                                radius: indicator.radius
                                color: Theme.color.onBackground
                                opacity: (pointer.containsMouse ? 0.06 : 0) + (pointer.pressed ? 0.10 : 0)
                                Behavior on opacity { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                            }
                        }
                        BadgedBox {
                            objectName: "miuixRailIcon" + index
                            x: railItem.iconX
                            y: railItem.iconY
                            width: 28
                            height: 28
                            badgeBounds: ({x: -railItem.iconX, y: -railItem.iconY, width: railItem.width, height: railItem.height})
                            badge: modelData.badge !== undefined && modelData.badge !== null ? badgeContent : null
                            Icon {
                                width: 28
                                height: 28
                                name: modelData.icon || ""
                                color: Theme.color.onSurfaceContainer
                            }
                            Component {
                                id: badgeContent
                                Badge {
                                    objectName: "miuixRailBadge" + railItem.index
                                    text: String(railItem.itemData.badge)
                                }
                            }
                        }
                        Text {
                            id: label
                            objectName: "miuixRailLabel" + index
                            // Allocate the available label slot, not the unshaped
                            // natural width: glyph shaping can be slightly wider.
                            width: railItem.labelLimit
                            x: (railItem.iconX + 14 - width / 2) * (1 - railRoot._progress) + 70 * railRoot._progress
                            y: (44 + (railRoot.expandable ? 8 : 0)) * (1 - railRoot._progress)
                                + (railItem.height - height) / 2 * railRoot._progress
                            text: modelData.text || ""
                            horizontalAlignment: railRoot._progress > 0.5 ? Text.AlignLeft : Text.AlignHCenter
                            font.pixelSize: 12 + 4 * railRoot._progress
                            font.weight: 57
                            color: Theme.color.onSurfaceContainer
                            elide: Text.ElideRight
                            maximumLineCount: 1
                        }
                    }
                    MouseArea {
                        id: pointer
                        anchors.fill: parent
                        hoverEnabled: true
                        enabled: railItem.available
                        onClicked: railRoot.select(index)
                    }
                    Loader {
                        anchors.fill: parent
                        sourceComponent: railRoot.delegate
                        property var itemData: railItem.itemData
                        property int itemIndex: index
                        property bool selected: railItem.selected
                    }
                }
            }
        }
    }
    Loader {
        id: footerLoader
        objectName: "miuixRailFooter"
        y: railRoot.height - railRoot.bottomInset - height
        width: parent.width
        height: item ? item.implicitHeight : 0
        sourceComponent: railRoot.footer
        visible: railRoot.footer !== null
    }
}
