import QtQuick
import miuix.Core

Column {
    id: state
    property string icon: "music_note"
    property string title: ""
    property string message: ""
    property string actionText: ""
    signal actionRequested()
    width: Math.max(0, Math.min(360, parent.width - 48))
    spacing: 12
    SmoothRectangle {
        objectName: "emptyStateBadge"
        width: 64
        height: 64
        x: (state.width - width) / 2
        radius: 22
        color: Theme.color.secondaryContainer
        Icon { anchors.centerIn: parent; name: state.icon; width: 30; height: 30; color: Theme.color.primary }
    }
    Text {
        width: state.width
        text: state.title
        visible: text.length > 0
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        font.pixelSize: 20
        font.weight: Font.DemiBold
        color: Theme.color.onSurfaceColor
    }
    Text {
        width: state.width
        text: state.message
        visible: text.length > 0
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        font.pixelSize: 14
        color: Theme.color.onSurfaceVariantSummary
    }
    Button {
        x: (state.width - width) / 2
        text: state.actionText
        visible: text.length > 0
        type: "filledTonal"
        onClicked: state.actionRequested()
    }
}
