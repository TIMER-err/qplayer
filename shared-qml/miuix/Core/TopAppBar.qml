import QtQuick
import QtQuick.Layouts
import miuix.Core

Rectangle {
    id: root
    property string title: "Title"
    property string largeTitle: title
    property string subtitle: ""
    property bool large: true
    property bool showNavigationIcon: true
    property alias navigationIcon: navIcon
    default property alias actions: actionsLayout.data
    property var flickable: null
    property real scrollOffset: flickable ? flickable.contentY : 0
    property real topInset: 0
    property real titlePadding: 26
    property real collapsedHeight: 52
    readonly property real expansionHeight: large ? largeLabel.implicitHeight + (subtitle !== "" ? subtitleLabel.implicitHeight + 8 : 4) : 0
    readonly property real collapsedFraction: expansionHeight > 0 ? Math.max(0, Math.min(1, scrollOffset / expansionHeight)) : 1
    signal navigationIconClicked()

    implicitWidth: parent ? parent.width : 640
    implicitHeight: topInset + collapsedHeight + expansionHeight * (1 - collapsedFraction)
        + (!large && subtitle !== "" ? subtitleLabel.implicitHeight + 8 : 0)
    color: Theme.color.surface
    clip: true

    IconButton {
        id: navIcon
        x: 16
        y: root.topInset + (root.collapsedHeight - height) / 2
        icon: "arrow_back"
        visible: root.showNavigationIcon
        onClicked: root.navigationIconClicked()
    }
    Text {
        objectName: "miuixAppBarSmallTitle"
        x: Math.max(root.titlePadding, navIcon.visible ? navIcon.x + navIcon.width + 8 : 0)
        y: root.topInset + (root.collapsedHeight - implicitHeight) / 2
        width: Math.max(0, actionsLayout.x - x - 12)
        text: root.title
        font.pixelSize: 20
        font.weight: Font.Medium
        color: Theme.color.onSurfaceColor
        elide: Text.ElideRight
        opacity: !root.large || root.collapsedFraction > 0.5 ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
    }
    RowLayout {
        id: actionsLayout
        x: root.width - width - 16
        y: root.topInset + (root.collapsedHeight - height) / 2
        height: root.collapsedHeight
        spacing: 0
    }
    Text {
        id: largeLabel
        objectName: "miuixAppBarLargeTitle"
        x: root.titlePadding
        y: root.topInset + root.collapsedHeight - root.expansionHeight * root.collapsedFraction
        width: Math.max(0, root.width - x * 2)
        text: root.largeTitle
        font.pixelSize: 32
        color: Theme.color.onSurfaceColor
        elide: Text.ElideRight
        visible: root.large
        opacity: Math.max(0, 1 - root.collapsedFraction * 3)
    }
    Text {
        id: subtitleLabel
        x: root.titlePadding
        y: root.large ? largeLabel.y + largeLabel.implicitHeight : root.topInset + root.collapsedHeight
        width: Math.max(0, root.width - x * 2)
        text: root.subtitle
        font.pixelSize: 14
        color: Theme.color.onSurfaceSecondary
        elide: Text.ElideRight
        visible: root.subtitle !== ""
        opacity: root.large ? largeLabel.opacity : 1
    }
}
