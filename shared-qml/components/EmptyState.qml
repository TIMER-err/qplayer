import QtQuick
import miuix.Core

Item {
    id: state
    property string icon: "music_note"
    property string title: ""
    property string message: ""
    property string actionText: ""
    signal actionRequested()
    readonly property real copyWidth: Math.max(0, Math.min(360, state.width - 48))
    // Occupy the available region and centre the actual content inside it. If this
    // wrapper tracks content.implicitHeight, qml4j resolves centerIn while the
    // height is still zero; when the Column is measured later, the placeholder
    // hangs below the parent's vertical centre by half its final height.
    width: parent ? parent.width : 0
    height: parent ? parent.height : 0
    Column {
        id: content
        objectName: "emptyStateContent"
        width: state.copyWidth
        height: implicitHeight
        x: (state.width - width) / 2
        y: Math.max(0, (state.height - height) / 2)
        spacing: 12

        SmoothRectangle {
            objectName: "emptyStateBadge"
            width: 64
            height: 64
            x: (state.copyWidth - width) / 2
            radius: 22
            color: Theme.color.secondaryContainer
            Icon { anchors.centerIn: parent; name: state.icon; width: 30; height: 30; color: Theme.color.primary }
        }
        Text {
            objectName: "emptyStateTitle"
            width: state.copyWidth
            text: state.title
            visible: text.length > 0
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            font.pixelSize: 20
            font.weight: Font.DemiBold
            color: Theme.color.onSurfaceColor
        }
        Text {
            objectName: "emptyStateMessage"
            width: state.copyWidth
            text: state.message
            visible: text.length > 0
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            font.pixelSize: 14
            color: Theme.color.onSurfaceVariantSummary
        }
        Button {
            x: (state.copyWidth - width) / 2
            text: state.actionText
            visible: text.length > 0
            type: "filledTonal"
            onClicked: state.actionRequested()
        }
    }
}
