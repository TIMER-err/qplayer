import QtQuick
import miuix.Core

// A compact playlist affordance that stays out of the way until the user has
// moved past the header and the first few rows. Source and local playlist pages
// share the same threshold and component-library styling.
FAB {
    id: control

    property Flickable target: null
    property real revealDistance: target
                                  ? Math.min(240, Math.max(128, target.height / 2))
                                  : 240

    readonly property bool revealed: target && target.contentY > revealDistance

    type: "standard"
    icon: "vertical_align_top"
    transformOrigin: Item.Center
    opacity: revealed ? 1 : 0
    scale: revealed ? 1 : 0.82
    enabled: revealed
    Behavior on opacity {
        NumberAnimation { duration: 160; easing.type: Easing.OutCubic }
    }
    Behavior on scale {
        NumberAnimation { duration: 160; easing.type: Easing.OutBack }
    }

    onClicked: {
        if (!target) return
        scrollAnimation.stop()
        scrollAnimation.start()
    }

    NumberAnimation {
        id: scrollAnimation
        target: control.target
        property: "contentY"
        to: 0
        duration: 220
        easing.type: Easing.OutCubic
    }
}
