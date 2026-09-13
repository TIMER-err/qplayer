import QtQuick
import miuix.Core

Item {
    id: root
    property bool refreshing: false
    property real threshold: 72
    property real maximumPull: 112
    property real indicatorHeight: 64
    property real contentHeight: contentContainer.childrenRect.height
    property alias flickable: viewport
    default property alias content: contentContainer.data
    readonly property real pullDistance: Math.max(0, maximumPull - viewport.contentY)
    readonly property real progress: Math.min(1, pullDistance / Math.max(1, threshold))
    readonly property real scrollOffset: Math.max(0, viewport.contentY - maximumPull)
    readonly property string refreshState: refreshing ? "refreshing" : (_completed ? "complete" : (progress >= 1 ? "ready" : (progress > 0 ? "pulling" : "idle")))
    property var refreshTexts: ["Pull down to refresh", "Release to refresh", "Refreshing…", "Refresh complete"]
    property bool _gesture: false
    property bool _completed: false
    property bool _ready: false
    property real _completion: 0
    readonly property real _stretch: refreshing || _completed ? 0 : Math.max(0, pullDistance - threshold)
    signal refreshRequested()
    implicitWidth: 320
    implicitHeight: 400
    clip: true

    // A reserved header inside the Flickable supplies pull travel without relying
    // on the host's unimplemented overscroll. Child controls keep native hit testing.
    function settlePull() {
        if (!_ready || viewport.moving) return
        var requested = _gesture && enabled && !refreshing && !_completed && progress >= 1
        _gesture = false
        if (requested) refreshRequested()
        if (refreshing) animateTo(maximumPull - indicatorHeight)
        else if (pullDistance > 0) animateTo(maximumPull)
    }
    function animateTo(position) {
        rebound.stop()
        rebound.to = Math.max(0, position)
        rebound.start()
    }
    onRefreshingChanged: {
        if (!_ready) return
        completeTimer.stop()
        _completed = !refreshing
        completionFade.stop()
        _completion = 0
        if (!refreshing) completionFade.restart()
        if (refreshing) animateTo(maximumPull - indicatorHeight)
        else completeTimer.restart()
    }
    Component.onCompleted: initialPosition.restart()
    Timer {
        id: initialPosition
        interval: 1
        onTriggered: {
            viewport.contentY = root.refreshing ? root.maximumPull - root.indicatorHeight : root.maximumPull
            root._ready = true
        }
    }
    Timer { id: settleTimer; interval: 1; onTriggered: root.settlePull() }
    Timer {
        id: completeTimer; interval: 500
        onTriggered: { root._completed = false; if (!root.refreshing) root.animateTo(root.maximumPull) }
    }
    NumberAnimation { id: completionFade; target: root; property: "_completion"; to: 1; duration: 350 }
    NumberAnimation { id: rebound; target: viewport; property: "contentY"; duration: 260; easing.type: Easing.OutCubic }
    Flickable {
        id: viewport
        objectName: "miuixRefreshFlickable"
        anchors.fill: parent
        opacity: root._ready ? 1 : 0
        contentWidth: width
        contentHeight: Math.max(height, root.contentHeight) + root.maximumPull
        flickableDirection: Flickable.VerticalFlick
        interactive: root.enabled && !root.refreshing && !root._completed
        clip: true
        onMovingChanged: {
            if (moving) { rebound.stop(); root._gesture = true }
            else settleTimer.restart()
        }
        onContentYChanged: {
            if (root._ready && !moving && !rebound.running && !root.refreshing && !root._completed) settleTimer.restart()
        }
        Item {
            width: viewport.width
            height: root.indicatorHeight + root._stretch
            y: root.maximumPull - height
            opacity: root.progress
            Rectangle {
                x: (parent.width - width) / 2; y: 4
                width: 20; height: 20 + root._stretch; radius: 10
                color: "transparent"
                border.width: 20 / 11
                border.color: Theme.color.onSurfaceSecondary
                visible: !root.refreshing
                opacity: root._completed ? Math.max(0, 0.65 - root._completion) : Math.max(0, root.progress - 0.2)
                scale: root._completed ? Math.max(0.9, 1 - root._completion) : 1
            }
            LoadingIndicator {
                x: (parent.width - width) / 2; y: 4
                visible: root.refreshing
                running: root.refreshing
                strokeWidth: 20 / 11; dotRadius: 20 / 11
                color: Theme.color.onSurfaceSecondary
            }
            Text {
                width: parent.width
                y: 32 + root._stretch
                text: root.refreshTexts[root.refreshing ? 2 : (root._completed ? 3 : (root.progress >= 1 ? 1 : 0))]
                horizontalAlignment: Text.AlignHCenter
                font.pixelSize: 14
                color: Theme.color.onSurfaceSecondary
            }
        }
        Item {
            id: contentContainer
            width: viewport.width
            height: root.contentHeight
            y: root.maximumPull
        }
    }
}
