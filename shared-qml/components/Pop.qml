import QtQuick
import design

// Short scale pulse for a control the user just acted on (like, play/pause):
// out to `peak`, then back to 1. Call restart() from the click handler.
SequentialAnimation {
    id: pop
    property var target: null
    property real peak: 1.2
    NumberAnimation {
        target: pop.target
        property: "scale"
        to: pop.peak
        duration: Ui.motionInstant
        easing.type: Ui.easeEnter
    }
    NumberAnimation {
        target: pop.target
        property: "scale"
        to: 1
        duration: Ui.motionBase
        easing.type: Ui.easeEnter
    }
}
