import QtQuick
import miuix.Core
import design
import "."
import "../pages"

// 播放队列 as a floating panel: slides up from the bottom edge, docked to the
// right half of the window, dims the rest with a scrim. Closing goes back to
// whatever page was underneath, which is why it is not a route.
//
// Its own component rather than inline in Main.qml: the AOT compiler emits a
// component body as one constructor method, and Main.qml is already at the
// 64KB bytecode limit (adding this block inline failed the build with
// MethodTooLargeException).
Item {
    id: sheet
    z: 15000

    // Live open state, owned by the shell (app.queueSheetOpen).
    property bool open: false
    signal dismissRequested()

    // Left edge of the page content pane (i.e. right of the navigation rail). The
    // panel is docked to that edge and the scrim stops there too, so the rail is
    // neither covered nor dimmed and stays usable while the queue is open.
    property real contentLeft: 0
    readonly property real paneWidth: Math.max(0, width - sheet.contentLeft)

    // 0 closed .. 1 fully open: the panel's y and the scrim's alpha ride this one
    // eased value. Driven by the two animations below, NOT by a binding plus a
    // Behavior: on this engine a Behavior on a binding-assigned property is
    // skipped, which is why an earlier version popped open with no motion.
    property real k: 0
    readonly property bool active: sheet.open || sheet.k > 0.001

    visible: active
    enabled: active

    NumberAnimation {
        id: slideIn
        target: sheet
        property: "k"
        to: 1
        duration: Ui.motionSlow
        easing.type: Ui.easeEnter
    }
    NumberAnimation {
        id: slideOut
        target: sheet
        property: "k"
        to: 0
        duration: Ui.motionBase
        easing.type: Ui.easeLeave
    }
    onOpenChanged: {
        if (sheet.open) {
            slideOut.stop()
            slideIn.restart()
        } else {
            slideIn.stop()
            slideOut.restart()
        }
    }

    Rectangle {
        id: scrim
        objectName: "queueSheetScrim"
        x: sheet.contentLeft
        y: 0
        width: sheet.paneWidth
        height: parent.height
        color: "#000000"
        opacity: 0.45 * sheet.k
        MouseArea {
            anchors.fill: parent
            onClicked: sheet.dismissRequested()
        }
    }

    Rectangle {
        id: panel
        objectName: "queueSheetPanel"
        // Exactly the width of the page pane beside the rail: the queue lines up
        // with the page it floats over instead of covering the whole window, and
        // the rail on the left stays visible.
        x: sheet.contentLeft
        width: sheet.paneWidth
        // Tall enough to browse a real queue; the page behind stays visible above.
        height: Math.min(parent.height - 24, Math.max(320, parent.height * 0.86))
        y: parent.height - height * sheet.k
        // Docked to the right and bottom edges, so only the top corners round.
        radius: Ui.radiusXl
        bottomLeftRadius: 0
        bottomRightRadius: 0
        color: Ui.background
        clip: true

        QueuePage {
            objectName: "queueSheetPage"
            x: 0
            y: 0
            width: parent.width
            height: parent.height
            // QueuePage holds its row delegates only while it is visible, so a
            // closed sheet must not keep every SongRow in the queue alive.
            visible: sheet.active
            sheetMode: true
            bottomContentInset: 0
            onBack: sheet.dismissRequested()
        }
    }
}
