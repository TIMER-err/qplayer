import QtQuick
import design

// One route-backed full-screen page. PageStack logic stays in Main.qml, while
// this component owns the repeated painting, z-order and transition policy.
// Motion values come from Main.qml so every route uses the preset selected in
// settings. Forward keeps the underlay static; Back keeps the restored page
// static and animates only the departing top page.
Loader {
    id: pageLoader

    required property var pageManager
    required property var motion
    required property string routeType
    property bool prewarming: false
    property bool present: pageManager.pageDepth(routeType) > 0
    // Zoom is the one depth-producing preset: pages below the top remain at the
    // matching Zoom Out endpoint. Pushing a route therefore moves the old top
    // out while the new one moves in; popping reverses both targets in one frame.
    property bool zoomUnderlay: motion.preset === 0 && present
                                && pageManager.currentOverlay !== "lyrics"
                                && pageManager.pageDepth(routeType)
                                   < pageManager.navigationStack.length
    property bool shownState: present && !zoomUnderlay
    property bool animateChange: present || pageManager.navigationDirection === "back"
                                 || (motion.preset === 0
                                     && pageManager.pageTransitionActive
                                     && pageManager.leavingPageType === routeType)

    width: parent ? parent.width : 0
    height: parent ? parent.height : 0
    visible: pageManager.pagePainted(routeType, opacity) || pageLoader.prewarming
    enabled: pageManager.currentOverlay === routeType
    z: pageManager.pageLayer(routeType, opacity)
    opacity: pageLoader.prewarming ? 0 : (shownState ? 1 : motion.hiddenOpacity)
    scale: shownState ? 1 : motion.hiddenScale
    x: shownState ? 0 : motion.hiddenX
    y: shownState ? 0 : motion.hiddenY

    Behavior on opacity {
        enabled: pageLoader.animateChange && motion.duration > 0
        NumberAnimation {
            duration: 200
            easing.type: pageLoader.present ? Ui.easeEnter : Ui.easeLeave
        }
    }
    Behavior on scale {
        enabled: pageLoader.animateChange && motion.duration > 0
        NumberAnimation {
            duration: 200
            easing.type: pageLoader.present ? Ui.easeEnter : Ui.easeLeave
        }
    }
    Behavior on x {
        enabled: pageLoader.animateChange && motion.duration > 0
        NumberAnimation {
            duration: 200
            easing.type: pageLoader.present ? Ui.easeEnter : Ui.easeLeave
        }
    }
    Behavior on y {
        enabled: pageLoader.animateChange && motion.duration > 0
        NumberAnimation {
            duration: 200
            easing.type: pageLoader.present ? Ui.easeEnter : Ui.easeLeave
        }
    }
}
