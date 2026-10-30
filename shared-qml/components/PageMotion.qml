import QtQuick

// Shared state machine for root-page transitions. Keeping the animation objects
// out of Main.qml avoids qml4j's 64KB generated-constructor limit and gives both
// root navigation and ManagedPageLoader one source of motion parameters.
Item {
    id: motion

    // SettingsCatalog.PAGE_TRANSITION_*.
    property int preset: 0
    property int duration: preset === 4 ? 0 : 220
    // Slide presets also cross-fade. Pure translation left an abruptly opaque
    // page moving over its source and looked disconnected from the other presets.
    property real hiddenOpacity: 0
    property real hiddenScale: preset === 0 ? 0.94 : 1
    property real hiddenX: preset === 2 ? 44 : 0
    property real hiddenY: preset === 3 ? 40 : 0

    // Which way a swap travels: +1 moves to a destination on the right, -1 back
    // to one on the left. A lateral swap that left and re-entered on the same
    // side read as a plain cross-fade, because the page never crossed anything.
    property int direction: 1
    readonly property real exitX: -direction * hiddenX
    readonly property real exitY: -direction * hiddenY
    readonly property real entryX: direction * hiddenX
    readonly property real entryY: direction * hiddenY

    property real contentOpacity: 1
    property real contentScale: 1
    property real contentX: 0
    property real contentY: 0

    signal swapRequested()

    function prepareHidden() {
        motion.contentOpacity = motion.hiddenOpacity
        motion.contentScale = motion.hiddenScale
        motion.contentX = motion.hiddenX
        motion.contentY = motion.hiddenY
    }

    function stopAnimations() {
        transitionAnim.stop()
        entryAnim.stop()
        exitAnim.stop()
    }

    function showImmediately() {
        motion.stopAnimations()
        motion.contentOpacity = 1
        motion.contentScale = 1
        motion.contentX = 0
        motion.contentY = 0
    }

    function restore() {
        motion.stopAnimations()
        entryAnim.restart()
    }

    function transition() {
        entryAnim.stop()
        exitAnim.stop()
        transitionAnim.restart()
    }

    function enter() {
        transitionAnim.stop()
        exitAnim.stop()
        motion.prepareHidden()
        entryAnim.restart()
    }

    function exit() {
        transitionAnim.stop()
        entryAnim.stop()
        exitAnim.restart()
    }

    SequentialAnimation {
        id: transitionAnim
        ParallelAnimation {
            NumberAnimation {
                target: motion; property: "contentOpacity"; to: motion.hiddenOpacity
                duration: motion.duration; easing.type: Easing.InCubic
            }
            NumberAnimation {
                target: motion; property: "contentScale"; to: motion.hiddenScale
                duration: motion.duration; easing.type: Easing.InCubic
            }
            NumberAnimation {
                target: motion; property: "contentX"; to: motion.exitX
                duration: motion.duration; easing.type: Easing.InCubic
            }
            NumberAnimation {
                target: motion; property: "contentY"; to: motion.exitY
                duration: motion.duration; easing.type: Easing.InCubic
            }
        }
        ScriptAction {
            onTrigger: {
                motion.swapRequested()
                // The incoming page waits on the far side of where the outgoing
                // one left, so the pair reads as one movement across the screen.
                motion.contentOpacity = motion.hiddenOpacity
                motion.contentScale = motion.hiddenScale
                motion.contentX = motion.entryX
                motion.contentY = motion.entryY
            }
        }
        ParallelAnimation {
            NumberAnimation {
                target: motion; property: "contentOpacity"; to: 1
                duration: motion.duration; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: motion; property: "contentScale"; to: 1
                duration: motion.duration; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: motion; property: "contentX"; to: 0
                duration: motion.duration; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: motion; property: "contentY"; to: 0
                duration: motion.duration; easing.type: Easing.OutCubic
            }
        }
    }

    ParallelAnimation {
        id: entryAnim
        NumberAnimation {
            target: motion; property: "contentOpacity"; to: 1
            duration: motion.duration; easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: motion; property: "contentScale"; to: 1
            duration: motion.duration; easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: motion; property: "contentX"; to: 0
            duration: motion.duration; easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: motion; property: "contentY"; to: 0
            duration: motion.duration; easing.type: Easing.OutCubic
        }
    }

    ParallelAnimation {
        id: exitAnim
        NumberAnimation {
            target: motion; property: "contentOpacity"; to: motion.hiddenOpacity
            duration: motion.duration; easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: motion; property: "contentScale"; to: motion.hiddenScale
            duration: motion.duration; easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: motion; property: "contentX"; to: motion.hiddenX
            duration: motion.duration; easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: motion; property: "contentY"; to: motion.hiddenY
            duration: motion.duration; easing.type: Easing.InCubic
        }
    }
}
