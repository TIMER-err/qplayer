import QtQuick
import miuix.Core
import design
import "."

// Centred placeholder for empty, signed-out and not-configured destinations:
// a soft accent badge, a bold title, a secondary message and an optional
// primary action. Every child is placed at an explicit y so the measured
// height is exact (no Column spacing guesses) and the block centres cleanly.
Item {
    id: state
    property string icon: "music_note"
    property string title: ""
    property string message: ""
    property string actionText: ""
    property string actionIcon: ""
    signal actionRequested()
    readonly property real copyWidth: Math.max(0, Math.min(360, state.width - 2 * Ui.space6))
    // Occupy the available region and centre the actual content inside it. If this
    // wrapper tracks content.implicitHeight, qml4j resolves centerIn while the
    // height is still zero; when the content is measured later, the placeholder
    // hangs below the parent's vertical centre by half its final height.
    width: parent ? parent.width : 0
    height: parent ? parent.height : 0

    Item {
        id: content
        objectName: "emptyStateContent"
        readonly property real titleBottom: titleText.visible
                                            ? titleText.y + titleText.implicitHeight : badge.height
        readonly property real messageBottom: messageText.visible
                                              ? messageText.y + messageText.implicitHeight : titleBottom
        width: state.copyWidth
        height: actionButton.visible ? actionButton.y + actionButton.height : messageBottom
        x: (state.width - width) / 2
        y: Math.max(0, (state.height - height) / 2)

        Rectangle {
            id: badge
            objectName: "emptyStateBadge"
            width: 64
            height: 64
            x: (state.copyWidth - width) / 2
            y: 0
            radius: Ui.radiusLg
            color: Ui.accentSoft

            // A faint outer ring gives the badge some presence without a shadow
            // layer (layers are offscreen passes in qml4j).
            Rectangle {
                x: -6
                y: -6
                width: parent.width + 12
                height: parent.height + 12
                radius: Ui.radiusLg + 6
                color: "transparent"
                border.width: 1
                border.color: Ui.accentColor(Ui.dark ? 0.22 : 0.14)
            }

            Glyph {
                x: (parent.width - width) / 2
                y: (parent.height - height) / 2
                name: state.icon
                size: 30
                color: Ui.accent
            }
        }

        Text {
            id: titleText
            objectName: "emptyStateTitle"
            y: badge.height + Ui.space5
            width: state.copyWidth
            text: state.title
            visible: text.length > 0
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            font.pixelSize: Ui.textTitle2
            font.weight: Ui.weightBold
            color: Ui.textPrimary
        }

        Text {
            id: messageText
            objectName: "emptyStateMessage"
            y: content.titleBottom + Ui.space2
            width: state.copyWidth
            text: state.message
            visible: text.length > 0
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            font.pixelSize: Ui.textCallout
            font.weight: Ui.weightRegular
            color: Ui.textSecondary
        }

        Button {
            id: actionButton
            objectName: "emptyStateAction"
            x: (state.copyWidth - width) / 2
            y: content.messageBottom + Ui.space6
            text: state.actionText
            icon: state.actionIcon
            visible: text.length > 0
            type: "filled"
            cornerRadius: Ui.radiusFull
            onClicked: state.actionRequested()
        }
    }
}
