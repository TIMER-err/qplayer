import QtQuick
import miuix.Core
import design

// Global playback quality picker dialog.
Item {
    property bool openWatch: player.playbackQualityOpen
    onOpenWatchChanged: {
        if (player.playbackQualityOpen) dialog.open()
        else if (dialog.opened) dialog.close()
    }

    Dialog {
        id: dialog
        objectName: "playbackQualityDialog"
        readonly property var qualities: ["standard", "higher", "exhigh", "lossless", "hires"]
        readonly property real buttonHeight: 48
        readonly property real gap: Ui.space2

        function chooseQuality(quality) {
            player.setPlaybackQuality(quality)
            dialog.close()
        }

        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "tune"
        title: i18n.t("settings.playbackQuality.title")
        text: player.playbackQualityTitle
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onRejected: player.closePlaybackQualityDialog()
        onClosed: {
            if (player.playbackQualityOpen) player.closePlaybackQualityDialog()
        }

        Item {
            width: dialog.contentWidth
            height: dialog.qualities.length * (dialog.buttonHeight + dialog.gap)

            Repeater {
                model: dialog.qualities
                Button {
                    objectName: "playbackQualityDialog_" + modelData
                    y: index * (dialog.buttonHeight + dialog.gap)
                    width: parent.width
                    height: dialog.buttonHeight
                    type: modelData === player.playbackQuality ? "filled" : "filledTonal"
                    text: i18n.t("menu.download." + modelData)
                    onClicked: dialog.chooseQuality(modelData)
                }
            }
        }
    }
}
