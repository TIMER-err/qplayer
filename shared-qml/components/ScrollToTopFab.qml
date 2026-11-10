import QtQuick
import miuix.Core
import design

// A compact "back to top" affordance that stays out of the way until the user
// has moved past the header and the first few rows. A quiet raised surface
// rather than an accent FAB: it is a navigation aid, not the page's primary
// action. Opacity-only motion (scale bounces are unreliable in qml4j), and it
// is hidden — not just transparent — while concealed, because transparent
// items still receive hit tests.
FAB {
    id: control

    property Flickable target: null
    property real revealDistance: target
                                  ? Math.min(240, Math.max(128, target.height / 2))
                                  : 240

    readonly property bool revealed: target && target.contentY > revealDistance

    type: "small"
    icon: "vertical_align_top"
    containerColor: Ui.surfaceRaised
    contentColor: Ui.textPrimary
    width: implicitWidth
    height: implicitHeight
    opacity: revealed ? 1 : 0
    visible: opacity > 0.01
    enabled: revealed
    Behavior on opacity {
        NumberAnimation { duration: 160; easing.type: Easing.OutCubic }
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
