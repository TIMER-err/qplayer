import QtQuick
import miuix.Core
import design

// Mobile volume control, opened from the page's top app bar.
Item {
    id: control

    property bool active: false
    property int previousVolume: 80
    property int volumeWatch: settings.appVolume
    signal closed()

    onVolumeWatchChanged: if (volumeWatch > 0) previousVolume = volumeWatch
    onActiveChanged: {
        if (active) dialog.open()
        else if (dialog.opened) dialog.close()
    }

    Dialog {
        id: dialog
        objectName: "volumeDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "volume_up"
        title: i18n.t("settings.volume.title")
        text: i18n.t("settings.volume.desc")
        showAcceptButton: false
        rejectText: i18n.t("common.close")
        onClosed: control.closed()

        Item {
            width: dialog.contentWidth
            height: 64

            Rectangle {
                anchors.fill: parent
                radius: Ui.radiusLg
                color: Ui.surfaceRaised
            }

            IconButton {
                id: muteButton
                objectName: "volumeMuteButton"
                x: Ui.space2
                y: (parent.height - height) / 2
                type: "standard"
                icon: settings.appVolume === 0 ? "volume_off"
                      : (settings.appVolume < 50 ? "volume_down" : "volume_up")
                onClicked: {
                    if (settings.appVolume > 0) {
                        control.previousVolume = settings.appVolume
                        settings.setAppVolume(0)
                    } else {
                        settings.setAppVolume(control.previousVolume)
                    }
                }
            }

            Slider {
                id: volumeSlider
                objectName: "volumeSlider"
                property int persistedVolume: settings.appVolume
                x: muteButton.x + muteButton.width + Ui.space2
                y: (parent.height - height) / 2
                width: Math.max(0, valueText.x - Ui.space3 - x)
                from: 0
                to: 100
                stepSize: 1
                value: persistedVolume
                onPersistedVolumeChanged: if (!pressed) value = persistedVolume
                // Playback follows the thumb live; the setting persists on release.
                onMoved: player.setVolume(value / 100)
                onEditingFinished: settings.setAppVolume(Math.round(value))
            }

            Text {
                id: valueText
                x: parent.width - Ui.space4 - width
                y: (parent.height - implicitHeight) / 2
                width: 44
                text: Math.round(volumeSlider.value) + "%"
                horizontalAlignment: Text.AlignRight
                color: Ui.textPrimary
                font.pixelSize: Ui.textBody
                font.weight: Ui.weightBold
            }
        }
    }
}
