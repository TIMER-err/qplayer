import QtQuick
import miuix.Core

// Retains the existing MouseArea API while using Miuix's flat indication.
MouseArea {
    id: indicationRoot
    property color rippleColor: Theme.color.onBackground
    property real rippleOpacity: 0.10
    property real clipRadius: 0
    property alias clipTopLeftRadius: stateLayer.topLeftRadius
    property alias clipTopRightRadius: stateLayer.topRightRadius
    property alias clipBottomLeftRadius: stateLayer.bottomLeftRadius
    property alias clipBottomRightRadius: stateLayer.bottomRightRadius
    property bool longPressEnabled: false
    property int longPressMs: 480
    property real pressX: 0
    property real pressY: 0
    signal longPressed()

    hoverEnabled: true
    acceptedButtons: longPressEnabled ? (Qt.LeftButton | Qt.RightButton) : Qt.LeftButton

    Rectangle {
        id: stateLayer
        width: indicationRoot.width
        height: indicationRoot.height
        radius: indicationRoot.clipRadius
        color: indicationRoot.rippleColor
        opacity: !indicationRoot.enabled ? 0
            : (indicationRoot.containsMouse ? 0.06 : 0)
                + (indicationRoot.activeFocus ? 0.08 : 0)
                + (indicationRoot.pressed ? indicationRoot.rippleOpacity : 0)
        Behavior on opacity {
            NumberAnimation { duration: indicationRoot.pressed ? 120 : 200; easing.type: Easing.OutCubic }
        }
    }
    Timer {
        id: holdTimer
        interval: indicationRoot.longPressMs
        repeat: false
        onTriggered: indicationRoot.longPressed()
    }
    onPressed: (mouse) => {
        indicationRoot.pressX = mouse.x
        indicationRoot.pressY = mouse.y
        if (indicationRoot.longPressEnabled && mouse.button === Qt.RightButton) {
            indicationRoot.longPressed()
        } else if (indicationRoot.longPressEnabled) {
            holdTimer.restart()
        }
    }
    onPositionChanged: (mouse) => {
        if (holdTimer.running) {
            var dx = mouse.x - indicationRoot.pressX
            var dy = mouse.y - indicationRoot.pressY
            if (dx * dx + dy * dy > 100) holdTimer.stop()
        }
    }
    onReleased: holdTimer.stop()
    onCanceled: holdTimer.stop()
    onEnabledChanged: if (!enabled) holdTimer.stop()
}
