import QtQuick
import miuix.Core

Item {
    id: control
    property bool active: false
    property var durations: [15, 30, 45, 60, 90, 120]
    signal closed()

    onActiveChanged: {
        if (active) dialog.open()
        else if (dialog.opened) dialog.close()
    }

    Dialog {
        id: dialog
        objectName: "sleepTimerDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t("sleepTimer.title")
        text: player.sleepTimerRemainingMinutes > 0
              ? i18n.t("sleepTimer.remaining", player.sleepTimerRemainingMinutes)
              : i18n.t("sleepTimer.description")
        showAcceptButton: false
        rejectText: i18n.t("common.close")
        onClosed: control.closed()

        Item {
            width: dialog.contentWidth
            height: player.sleepTimerRemainingMinutes > 0 ? 152 : 104

            Repeater {
                model: control.durations
                Button {
                    objectName: "sleepTimerPreset" + modelData
                    x: (index % 3) * (parent.width + 8) / 3
                    y: Math.floor(index / 3) * 52
                    width: (parent.width - 16) / 3
                    height: 44
                    type: "filledTonal"
                    text: i18n.t("sleepTimer.minutes", modelData)
                    onClicked: {
                        player.setSleepTimer(modelData)
                        dialog.close()
                    }
                }
            }

            Button {
                objectName: "sleepTimerCancel"
                y: 108
                width: parent.width
                height: 44
                visible: player.sleepTimerRemainingMinutes > 0
                text: i18n.t("sleepTimer.cancel")
                onClicked: {
                    player.setSleepTimer(0)
                    dialog.close()
                }
            }
        }
    }
}
