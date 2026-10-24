import QtQuick
import miuix.Core

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
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t("settings.volume.title")
        text: i18n.t("settings.volume.desc")
        showAcceptButton: false
        rejectText: i18n.t("common.close")
        onClosed: control.closed()

        Item {
            width: dialog.contentWidth
            height: 56

            IconButton {
                id: muteButton
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
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
                property int persistedVolume: settings.appVolume
                anchors.left: muteButton.right
                anchors.leftMargin: 12
                anchors.right: valueText.left
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                from: 0
                to: 100
                stepSize: 1
                value: persistedVolume
                valueLabelEnabled: true
                onPersistedVolumeChanged: if (!pressed) value = persistedVolume
                onMoved: player.setVolume(value / 100)
                onEditingFinished: settings.setAppVolume(Math.round(value))
            }

            Text {
                id: valueText
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                width: 42
                text: Math.round(volumeSlider.value) + "%"
                horizontalAlignment: Text.AlignRight
                color: Theme.color.onSurfaceColor
                font.pixelSize: 14
            }
        }
    }
}
