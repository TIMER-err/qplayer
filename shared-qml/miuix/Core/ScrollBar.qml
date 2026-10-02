import QtQuick
import miuix.Core

// MD3 Style ScrollBar
Rectangle {
    id: scrollBarTrack
    
    // API
    property Flickable target: null
    property int orientation: Qt.Vertical // Qt.Vertical or Qt.Horizontal
    // Some viewports reserve leading scroll travel (PullToRefresh keeps its
    // resting position below a hidden pull header). Treat that resting position
    // as the logical start instead of drawing a partly-scrolled thumb.
    property real contentStart: 0
    
    // Styling
    property color trackColor: "transparent"
    property color thumbColor: Theme.color.outline
    property color thumbPressedColor: Theme.color.primary
    property real thumbOpacity: 0.8
    property real fadeDuration: 200
    
    // Dimensions
    implicitWidth: orientation === Qt.Vertical ? (isPressed ? 4 : 8) : parent.width
    implicitHeight: orientation === Qt.Vertical ? parent.height : (isPressed ? 4 : 8)
    
    // Visibility and range
    readonly property real viewportSize: !target ? 0
        : (orientation === Qt.Vertical ? target.height : target.width)
    readonly property real contentSize: !target ? 0
        : (orientation === Qt.Vertical ? target.contentHeight : target.contentWidth)
    readonly property real maximumContentPosition: Math.max(contentStart,
        contentSize - viewportSize)
    readonly property real scrollRange: Math.max(0, maximumContentPosition - contentStart)
    readonly property real contentPosition: !target ? contentStart
        : (orientation === Qt.Vertical ? target.contentY : target.contentX)
    visible: target && scrollRange > 0
    color: trackColor

    function setContentPosition(value) {
        if (!target) return
        var bounded = Math.max(contentStart, Math.min(value, maximumContentPosition))
        if (orientation === Qt.Vertical) target.contentY = bounded
        else target.contentX = bounded
    }

    // Internal state
    property bool isPressed: scrollMouseArea.pressed
    property bool isMoving: target && target.moving
    property bool isHovered: scrollMouseArea.containsMouse
    property bool recentlyScrolled: false
    property real observedPosition: contentPosition
    onObservedPositionChanged: {
        recentlyScrolled = true
        scrollIdleTimer.restart()
    }

    Timer {
        id: scrollIdleTimer
        // contentX/contentY changes arrive as separate wheel/touchpad ticks.
        // Bridge the tiny gap between them, then hand visibility straight back
        // to the component's original 200ms opacity transition.
        interval: 120
        repeat: false
        onTriggered: scrollBarTrack.recentlyScrolled = false
    }

    Behavior on implicitWidth { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
    Behavior on implicitHeight { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

    // qml4j currently does not honor preventStealing when deciding whether an
    // underlying Flickable may take over a MouseArea drag. Giving the MouseArea
    // an inert Drag.target selects the engine's captured drag path (the same
    // workaround used by Slider.qml), so pointer tracking continues outside the
    // narrow visual thumb.
    Item {
        id: dragProxy
        visible: false
        x: 0
        y: 0
    }

    // Thumb Component
    Rectangle {
        id: scrollBarThumb
        
        // Dimensions & Position
        x: orientation === Qt.Vertical ? (parent.width - width) / 2 : calculatePosition()
        y: orientation === Qt.Vertical ? calculatePosition() : (parent.height - height) / 2
        
        width: orientation === Qt.Vertical ? (isPressed ? 4 : 6) : calculateSize()
        height: orientation === Qt.Vertical ? calculateSize() : (isPressed ? 4 : 6)
        
        color: isPressed ? thumbPressedColor : thumbColor
        radius: (orientation === Qt.Vertical ? width : height) / 2
        
        opacity: (isHovered || isPressed || isMoving || recentlyScrolled) ? thumbOpacity : 0.0
        
        Behavior on opacity { NumberAnimation { duration: fadeDuration } }
        Behavior on color { ColorAnimation { duration: 150 } }
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
        Behavior on height { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

        function calculateSize() {
            if (!target) return 40
            var availableContent = Math.max(viewportSize, contentSize - contentStart)
            var ratio = viewportSize / availableContent
            var trackSize = orientation === Qt.Vertical
                ? scrollBarTrack.height : scrollBarTrack.width
            return Math.max(40, ratio * trackSize) + (isPressed ? 10 : 0)
        }
        
        function calculatePosition() {
            if (!target || scrollRange <= 0) return 0
            var trackSize = orientation === Qt.Vertical
                ? scrollBarTrack.height : scrollBarTrack.width
            var thumbSize = orientation === Qt.Vertical ? height : width
            var maxThumbPosition = Math.max(0, trackSize - thumbSize)
            var scrollRatio = (contentPosition - contentStart) / scrollRange
            return Math.max(0,
                Math.min(scrollRatio * maxThumbPosition, maxThumbPosition))
        }
    }

    MouseArea {
        id: scrollMouseArea
        anchors.fill: parent
        hoverEnabled: true
        // qml4j otherwise lets the underlying Flickable steal the pointer after
        // a short drag, which feels as if the thumb suddenly disconnected.
        preventStealing: true
        drag.target: dragProxy
        drag.axis: orientation === Qt.Vertical ? "YAxis" : "XAxis"
        drag.minimumX: -100000
        drag.maximumX: 100000
        drag.minimumY: -100000
        drag.maximumY: 100000
        
        property real pressedPos: 0
        property real initialContentPos: 0
        
        onPressed: (mouse) => {
            if (!target) return
            dragProxy.x = 0
            dragProxy.y = 0

            var mousePos = orientation === Qt.Vertical ? mouse.y : mouse.x
            var thumbPos = orientation === Qt.Vertical
                ? scrollBarThumb.y : scrollBarThumb.x
            var thumbSize = orientation === Qt.Vertical
                ? scrollBarThumb.height : scrollBarThumb.width

            if (mousePos < thumbPos || mousePos > thumbPos + thumbSize) {
                var trackSize = orientation === Qt.Vertical
                    ? scrollBarTrack.height : scrollBarTrack.width
                var maxThumbPosition = trackSize - thumbSize
                if (maxThumbPosition > 0) {
                    var clickRatio = Math.max(0,
                        Math.min((mousePos - thumbSize / 2) / maxThumbPosition, 1))
                    scrollBarTrack.setContentPosition(
                        scrollBarTrack.contentStart
                        + clickRatio * scrollBarTrack.scrollRange)
                }
            }
            // Continue dragging from either the original or jumped position.
            pressedPos = mousePos
            initialContentPos = scrollBarTrack.contentPosition
        }
        
        onPositionChanged: (mouse) => {
            if (!pressed || !target) return

            var mousePos = orientation === Qt.Vertical ? mouse.y : mouse.x
            var delta = mousePos - pressedPos
            var trackSize = orientation === Qt.Vertical
                ? scrollBarTrack.height : scrollBarTrack.width
            var thumbSize = orientation === Qt.Vertical
                ? scrollBarThumb.height : scrollBarThumb.width
            var maxThumbPosition = trackSize - thumbSize

            if (maxThumbPosition > 0) {
                scrollBarTrack.setContentPosition(initialContentPos
                    + delta / maxThumbPosition * scrollBarTrack.scrollRange)
            }
        }
    }
}
