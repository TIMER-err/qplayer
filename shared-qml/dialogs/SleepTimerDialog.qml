import QtQuick
import miuix.Core
import design
import "../components"

// Sleep timer: six duration tiles (number over unit) in a 3-column grid, plus a
// "cancel timer" action once a timer is running.
Item {
    id: control
    property bool active: false
    property var durations: [15, 30, 45, 60, 90, 120]
    signal closed()

    readonly property bool running: player.sleepTimerRemainingMinutes > 0
    readonly property real tileHeight: 72
    readonly property real gap: Ui.space2

    onActiveChanged: {
        if (active) dialog.open()
        else if (dialog.opened) dialog.close()
    }

    Dialog {
        id: dialog
        objectName: "sleepTimerDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "bedtime"
        title: i18n.t("sleepTimer.title")
        text: control.running
              ? i18n.t("sleepTimer.remaining", player.sleepTimerRemainingMinutes)
              : i18n.t("sleepTimer.description")
        showAcceptButton: false
        rejectText: i18n.t("common.close")
        onClosed: control.closed()

        Item {
            id: grid
            width: dialog.contentWidth
            readonly property real tileWidth: (width - control.gap * 2) / 3
            height: control.tileHeight * 2 + control.gap
                    + (control.running ? Ui.space4 + 48 : 0) + Ui.space1

            Repeater {
                model: control.durations
                Item {
                    id: tile
                    objectName: "sleepTimerPreset" + modelData
                    x: (index % 3) * (grid.tileWidth + control.gap)
                    y: Math.floor(index / 3) * (control.tileHeight + control.gap)
                    width: grid.tileWidth
                    height: control.tileHeight
                    Rectangle {
                        anchors.fill: parent
                        radius: Ui.radiusMd
                        color: tileArea.pressed ? Ui.surfacePressed
                               : (tileArea.containsMouse ? Ui.accentSoft : Ui.surfaceRaised)
                        Behavior on color { ColorAnimation { duration: 120 } }
                    }
                    Text {
                        id: tileValue
                        x: (tile.width - implicitWidth) / 2
                        y: (tile.height - implicitHeight - tileUnit.implicitHeight) / 2
                        text: "" + modelData
                        color: tileArea.containsMouse ? Ui.accent : Ui.textPrimary
                        font.pixelSize: Ui.textTitle2
                        font.weight: Ui.weightBold
                    }
                    Text {
                        id: tileUnit
                        x: (tile.width - implicitWidth) / 2
                        y: tileValue.y + tileValue.implicitHeight
                        text: i18n.t("sleepTimer.unit")
                        color: Ui.textSecondary
                        font.pixelSize: Ui.textCaption
                    }
                    MouseArea {
                        id: tileArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            player.setSleepTimer(modelData)
                            dialog.close()
                        }
                    }
                }
            }

            Button {
                objectName: "sleepTimerCancel"
                y: control.tileHeight * 2 + control.gap + Ui.space4
                width: parent.width
                height: 48
                visible: control.running
                type: "filledTonal"
                icon: "timer_off"
                contentColor: Ui.danger
                text: i18n.t("sleepTimer.cancel")
                onClicked: {
                    player.setSleepTimer(0)
                    dialog.close()
                }
            }
        }
    }
}
