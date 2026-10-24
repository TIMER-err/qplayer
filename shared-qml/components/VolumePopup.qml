import QtQuick
import miuix.Core

// Reparent only the overlay, like Menu, so the MiniPlayer never clips the popup.
Item {
    id: control
    width: 0
    height: 0

    property var anchorItem: null
    property real targetX: 0
    property real targetY: 0
    property int previousVolume: 80
    property int volumeWatch: settings.appVolume
    onVolumeWatchChanged: if (volumeWatch > 0) previousVolume = volumeWatch

    function open(target) {
        exitAnim.stop()
        if (!overlayLayer.visible) {
            popupContainer.scale = 0.8
            popupContainer.opacity = 0
        }
        var root = control
        while (root.parent) root = root.parent
        control.anchorItem = target
        overlayLayer.parent = root
        overlayLayer.anchors.fill = root
        var position = root.mapFromItem(target, 0, 0)
        control.targetX = position.x + target.width / 2
        control.targetY = root.mapFromItem(control, 0, 0).y
        volumeSlider.value = settings.appVolume
        overlayLayer.visible = true
        overlayLayer.forceActiveFocus()
        enterAnim.restart()
    }

    function close() {
        if (!overlayLayer.visible || exitAnim.running) return
        enterAnim.stop()
        exitAnim.restart()
    }

    function handleBack() {
        if (!overlayLayer.visible) return false
        var anchor = control.anchorItem
        control.close()
        if (anchor) anchor.forceActiveFocus()
        return true
    }

    Item {
        id: overlayLayer
        visible: false
        z: 99999
        Keys.onEscapePressed: {
            control.handleBack()
            event.accepted = true
        }

        MouseArea {
            anchors.fill: parent
            onPressed: control.close()
        }

        Rectangle {
            id: popupContainer
            objectName: "miniPlayerVolumePopup"
            width: Math.max(0, Math.min(280, overlayLayer.width - 32))
            height: 72
            x: Math.max(16, Math.min(control.targetX - width / 2, overlayLayer.width - width - 16))
            y: Math.max(16, Math.min(control.targetY - height - 12, overlayLayer.height - height - 16))
            radius: 16
            color: Theme.color.surfaceContainerHigh
            border.width: 1
            border.color: Theme.color.outlineVariant
            scale: 0.8
            opacity: 0

            ParallelAnimation {
                id: enterAnim
                NumberAnimation { target: popupContainer; property: "scale"; to: 1; duration: 200; easing.type: Easing.OutCubic }
                NumberAnimation { target: popupContainer; property: "opacity"; to: 1; duration: 150 }
            }
            ParallelAnimation {
                id: exitAnim
                onFinished: {
                    // Keep the child list intact even when resize starts the exit.
                    overlayLayer.visible = false
                    control.anchorItem = null
                }
                NumberAnimation { target: popupContainer; property: "scale"; to: 0.8; duration: 150; easing.type: Easing.InCubic }
                NumberAnimation { target: popupContainer; property: "opacity"; to: 0; duration: 150 }
            }

            MouseArea { anchors.fill: parent }

            IconButton {
                id: muteButton
                objectName: "miniPlayerMuteButton"
                anchors.left: parent.left
                anchors.leftMargin: 12
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
                objectName: "miniPlayerVolumeSlider"
                property int persistedVolume: settings.appVolume
                anchors.left: muteButton.right
                anchors.leftMargin: 8
                anchors.right: valueText.left
                anchors.rightMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                from: 0
                to: 100
                stepSize: 1
                value: persistedVolume
                onPersistedVolumeChanged: if (!pressed) value = persistedVolume
                onMoved: player.setVolume(value / 100)
                onEditingFinished: settings.setAppVolume(Math.round(value))
            }

            Text {
                id: valueText
                anchors.right: parent.right
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 40
                text: Math.round(volumeSlider.value) + "%"
                horizontalAlignment: Text.AlignRight
                color: Theme.color.onSurfaceColor
                font.pixelSize: 14
            }
        }
    }
}
