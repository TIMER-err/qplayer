import QtQuick
import miuix.Core

Item {
    id: badgeRoot

    property string text: ""
    property color containerColor: Theme.color.error
    property color contentColor: Theme.color.onErrorColor
    default property alias content: contentContainer.data

    readonly property bool _hasContent: text.length > 0 || contentContainer.children.length > 0
    readonly property real _contentWidth: text.length > 0
        ? badgeText.implicitWidth
        : Math.max(0, contentContainer.childrenRect.width)
    readonly property real _contentHeight: text.length > 0
        ? badgeText.implicitHeight
        : Math.max(0, contentContainer.childrenRect.height)

    implicitWidth: _hasContent ? Math.max(16, _contentWidth + 8) : 6
    implicitHeight: _hasContent ? Math.max(16, _contentHeight) : 6

    Rectangle {
        anchors.fill: parent
        radius: Math.min(width, height) / 2
        color: badgeRoot.containerColor
    }

    Item {
        id: contentContainer
        x: (badgeRoot.width - badgeRoot._contentWidth) / 2
        y: (badgeRoot.height - badgeRoot._contentHeight) / 2
        width: badgeRoot._contentWidth
        height: badgeRoot._contentHeight
        visible: badgeRoot._hasContent
    }

    Text {
        id: badgeText
        anchors.centerIn: parent
        visible: badgeRoot.text.length > 0
        text: badgeRoot.text
        font.pixelSize: 11
        color: badgeRoot.contentColor
    }
}
