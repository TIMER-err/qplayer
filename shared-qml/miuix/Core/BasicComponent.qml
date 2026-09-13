import QtQuick
import miuix.Core

Item {
    id: rowRoot
    property string title: ""
    property string summary: ""
    property bool enabled: true
    property real horizontalPadding: Theme.metrics.rowPadding
    property real verticalPadding: Theme.metrics.rowPadding
    property real spacing: 8
    property Component startAction: null
    property Component endAction: null
    readonly property bool pressed: enabled && hit.pressed
    readonly property bool hovered: enabled && hit.containsMouse
    signal clicked()

    implicitWidth: 320
    width: parent ? parent.width : implicitWidth
    implicitHeight: Math.max(Theme.metrics.rowMinHeight,
        Math.max(labels.height, Math.max(startSlot.height, endSlot.height)) + verticalPadding * 2)

    Rectangle {
        anchors.fill: parent
        color: Theme.color.onBackground
        opacity: rowRoot.pressed ? 0.10 : (rowRoot.hovered ? 0.06 : 0)
        Behavior on opacity { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
    }
    MouseArea {
        id: hit
        anchors.fill: parent
        enabled: rowRoot.enabled
        hoverEnabled: true
        onClicked: rowRoot.clicked()
    }
    Loader {
        id: startSlot
        x: rowRoot.horizontalPadding
        anchors.verticalCenter: parent.verticalCenter
        sourceComponent: rowRoot.startAction
        width: item ? item.implicitWidth : 0
        height: item ? item.implicitHeight : 0
    }
    Column {
        id: labels
        x: rowRoot.horizontalPadding + startSlot.width + (startSlot.width > 0 ? rowRoot.spacing : 0)
        width: Math.max(0, rowRoot.width - x - rowRoot.horizontalPadding
            - endSlot.width - (endSlot.width > 0 ? rowRoot.spacing : 0))
        anchors.verticalCenter: parent.verticalCenter
        Text {
            width: parent.width
            text: rowRoot.title
            visible: text.length > 0
            font.pixelSize: Theme.metrics.titleSize
            font.weight: 57
            color: rowRoot.enabled ? Theme.color.onBackground : Theme.color.disabledOnSecondaryVariant
            wrapMode: Text.Wrap
        }
        Text {
            width: parent.width
            text: rowRoot.summary
            visible: text.length > 0
            font.pixelSize: Theme.metrics.summarySize
            color: rowRoot.enabled ? Theme.color.onSurfaceVariantSummary : Theme.color.disabledOnSecondaryVariant
            wrapMode: Text.Wrap
        }
    }
    Loader {
        id: endSlot
        x: rowRoot.width - rowRoot.horizontalPadding - width
        anchors.verticalCenter: parent.verticalCenter
        sourceComponent: rowRoot.endAction
        width: item ? Math.min(item.implicitWidth, Math.max(0, rowRoot.width - rowRoot.horizontalPadding * 2) * 0.4) : 0
        height: item ? item.implicitHeight : 0
    }
}
