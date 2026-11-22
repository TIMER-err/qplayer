import QtQuick
import miuix.Core
import design

// Quality selection panel for the lyric page. Dropped down from the lyric page's
// tune button or quality badge. A local panel within lyricChrome subtree so it
// renders seamlessly on top of the host fluid backdrop without root reparenting.
Item {
    id: panel
    property bool open: false
    signal qualityChosen(string quality)

    readonly property var qualities: ["standard", "higher", "exhigh", "lossless", "hires"]
    readonly property real buttonHeight: 40
    readonly property real gap: Ui.space2

    visible: panel.open || opacity > 0.01
    opacity: panel.open ? 1 : 0
    scale: panel.open ? 1 : 0.8
    transformOrigin: Item.Center
    Behavior on opacity {
        NumberAnimation { duration: 150; easing.type: Easing.Linear }
    }
    Behavior on scale {
        NumberAnimation { duration: 250; easing.type: Easing.Linear }
    }
    width: Math.min(280, parent.width - 32)
    height: 60 + qualities.length * (buttonHeight + gap) + 12

    SmoothRectangle {
        anchors.fill: parent
        radius: Ui.radiusLg
        color: Theme.color.surfaceContainerHigh
        borderWidth: 1
        borderColor: Ui.hairline
    }

    MouseArea { anchors.fill: parent }

    Text {
        id: qualityTitle
        anchors.top: parent.top
        anchors.topMargin: 16
        anchors.left: parent.left
        anchors.leftMargin: 18
        text: i18n.t("settings.playbackQuality.title")
        color: Ui.textPrimary
        font.pixelSize: Ui.textHeadline
        font.weight: Ui.weightBold
    }

    Item {
        anchors.top: qualityTitle.bottom
        anchors.topMargin: 12
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 18
        anchors.rightMargin: 18
        height: panel.qualities.length * (panel.buttonHeight + panel.gap)

        Repeater {
            model: panel.qualities
            Button {
                objectName: "playbackQuality_" + modelData
                y: index * (panel.buttonHeight + panel.gap)
                width: parent.width
                height: panel.buttonHeight
                type: modelData === player.playbackQuality ? "filled" : "filledTonal"
                text: i18n.t("menu.download." + modelData)
                onClicked: {
                    player.setPlaybackQuality(modelData)
                    panel.open = false
                    panel.qualityChosen(modelData)
                }
            }
        }
    }
}
