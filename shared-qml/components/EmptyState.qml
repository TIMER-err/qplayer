import QtQuick
import miuix.Core

// Explicit geometry, not a Column. qml4j Column sizes to the children's
// implicit (unwrapped) text width, so wrapMode never sees a box smaller than
// the line and the caption stays one overflowing row — the 64px badge then
// looks like it owns the whole stack. Bind every label to this item's width
// (independent of the icon) and wrap anywhere, including CJK.
Item {
    id: state
    property string icon: "music_note"
    property string title: ""
    property string message: ""
    property string actionText: ""
    signal actionRequested()

    width: {
        var avail = (parent && parent.width > 80) ? (parent.width - 48) : 360
        return Math.max(240, Math.min(360, avail))
    }
    height: actionBtn.visible
            ? (actionBtn.y + actionBtn.height)
            : (messageText.visible ? (messageText.y + messageText.height)
                                   : (titleText.visible ? (titleText.y + titleText.height) : 64))

    SmoothRectangle {
        id: badge
        objectName: "emptyStateBadge"
        width: 64
        height: 64
        x: (state.width - width) / 2
        y: 0
        radius: 22
        color: Theme.color.secondaryContainer
        Icon { anchors.centerIn: parent; name: state.icon; width: 30; height: 30; color: Theme.color.primary }
    }
    Text {
        id: titleText
        objectName: "emptyStateTitle"
        y: 76
        width: state.width
        text: state.title
        visible: text.length > 0
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.WrapAnywhere
        font.pixelSize: 20
        font.weight: Font.DemiBold
        color: Theme.color.onSurfaceColor
    }
    Text {
        id: messageText
        objectName: "emptyStateMessage"
        y: titleText.visible ? (titleText.y + titleText.height + 12) : 76
        width: state.width
        text: state.message
        visible: text.length > 0
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.WrapAnywhere
        font.pixelSize: 14
        color: Theme.color.onSurfaceVariantSummary
    }
    Button {
        id: actionBtn
        x: (state.width - width) / 2
        y: (messageText.visible ? messageText.y + messageText.height
                                : (titleText.visible ? titleText.y + titleText.height : 64)) + 12
        text: state.actionText
        visible: text.length > 0
        type: "filledTonal"
        onClicked: state.actionRequested()
    }
}
