import QtQuick
import design

// Section title used by every scrolling page: bold title, optional caption and
// an optional trailing text action ("查看全部", "清空" ...).
Item {
    id: section
    property string title: ""
    property string caption: ""
    property string actionText: ""
    property bool actionEnabled: true
    signal actionRequested()

    implicitHeight: caption.length > 0 ? 52 : 40
    height: implicitHeight

    Text {
        id: titleText
        x: 0
        y: section.caption.length > 0 ? 4 : (section.height - implicitHeight) / 2
        width: Math.max(0, (actionLabel.visible ? actionLabel.x - 12 : section.width))
        text: section.title
        color: Ui.textPrimary
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textTitle2
        font.weight: Ui.weightBold
    }
    Text {
        x: 0
        y: titleText.y + titleText.implicitHeight + 2
        width: titleText.width
        visible: section.caption.length > 0
        text: section.caption
        color: Ui.textSecondary
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textCaption
    }

    Text {
        id: actionLabel
        objectName: "sectionHeaderAction"
        visible: section.actionText.length > 0
        x: section.width - implicitWidth
        y: titleText.y + (titleText.implicitHeight - implicitHeight) / 2
        text: section.actionText
        color: actionArea.containsMouse ? Ui.accent : Ui.textSecondary
        opacity: section.actionEnabled ? 1 : 0.4
        font.pixelSize: Ui.textCallout
        MouseArea {
            id: actionArea
            anchors.fill: parent
            enabled: section.actionEnabled && actionLabel.visible
            hoverEnabled: enabled
            cursorShape: Qt.PointingHandCursor
            onClicked: section.actionRequested()
        }
    }
}
