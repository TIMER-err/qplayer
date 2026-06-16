import QtQuick
import md3.Core

// MD3 Style ScrollBar
Rectangle {
    id: scrollBarTrack
    
    // API
    property Flickable target: null
    property int orientation: Qt.Vertical // Qt.Vertical or Qt.Horizontal
    
    // Styling
    property color trackColor: "transparent"
    property color thumbColor: Theme.color.outline
    property color thumbPressedColor: Theme.color.primary
    property real thumbOpacity: 0.8
    property real fadeDuration: 200
    
    // Dimensions
    implicitWidth: orientation === Qt.Vertical ? (isPressed ? 4 : 8) : parent.width
    implicitHeight: orientation === Qt.Vertical ? parent.height : (isPressed ? 4 : 8)
    
    // Visibility logic
    visible: target && (orientation === Qt.Vertical ? target.contentHeight > target.height : target.contentWidth > target.width)
    color: trackColor
    
    // Internal state
    property bool isPressed: scrollMouseArea.pressed
    property bool isMoving: target && target.moving
    property bool isHovered: scrollMouseArea.containsMouse
    
    Behavior on implicitWidth { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
    Behavior on implicitHeight { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

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
        
        opacity: (isHovered || isPressed || isMoving) ? thumbOpacity : 0.0
        
        Behavior on opacity { NumberAnimation { duration: fadeDuration } }
        Behavior on color { ColorAnimation { duration: 150 } }
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
        Behavior on height { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

        function calculateSize() {
            if (!target) return 40
            
            var baseSize = 0
            if (orientation === Qt.Vertical) {
                var ratio = target.height / target.contentHeight
                baseSize = Math.max(40, ratio * scrollBarTrack.height)
            } else {
                var ratio = target.width / target.contentWidth
                baseSize = Math.max(40, ratio * scrollBarTrack.width)
            }
            
            return baseSize + (isPressed ? 10 : 0) // Make it longer when pressed
        }
        
        function calculatePosition() {
            if (!target) return 0
            
            if (orientation === Qt.Vertical) {
                if (target.contentHeight <= target.height) return 0
                // When pressed, the thumb height changes, so we must use the current thumb height
                // to calculate bounds to avoid jumping.
                // However, we are binding 'y' to this.
                // If 'height' changes due to animation, we need the position to be stable relative to center or top?
                // Standard behavior: The thumb expands from center or just expands.
                // If we simply use the current height, the thumb will shift as it grows.
                // Let's rely on standard scrollbar behavior: The visual thumb grows, but the logical center represents position.
                // But for simple implementation, let's just use current height.
                
                var maxThumbY = scrollBarTrack.height - height
                var scrollRatio = target.contentY / (target.contentHeight - target.height)
                return Math.max(0, Math.min(scrollRatio * maxThumbY, maxThumbY))
            } else {
                if (target.contentWidth <= target.width) return 0
                var maxThumbX = scrollBarTrack.width - width
                var scrollRatio = target.contentX / (target.contentWidth - target.width)
                return Math.max(0, Math.min(scrollRatio * maxThumbX, maxThumbX))
            }
        }
    }

    MouseArea {
        id: scrollMouseArea
        anchors.fill: parent
        hoverEnabled: true
        
        property real pressedPos: 0
        property real initialContentPos: 0
        
        onPressed: (mouse) => {
            if (!target) return
            
            var mousePos = (orientation === Qt.Vertical) ? mouse.y : mouse.x
            var thumbPos = (orientation === Qt.Vertical) ? scrollBarThumb.y : scrollBarThumb.x
            var thumbSize = (orientation === Qt.Vertical) ? scrollBarThumb.height : scrollBarThumb.width
            
            if (mousePos >= thumbPos && mousePos <= thumbPos + thumbSize) {
                // Clicked on thumb
                pressedPos = mousePos
                initialContentPos = (orientation === Qt.Vertical) ? target.contentY : target.contentX
            } else {
                // Clicked on track: Jump
                var trackSize = (orientation === Qt.Vertical) ? scrollBarTrack.height : scrollBarTrack.width
                var maxThumbPos = trackSize - thumbSize
                var clickRatio = Math.max(0, Math.min((mousePos - thumbSize / 2) / maxThumbPos, 1.0))
                
                if (orientation === Qt.Vertical) {
                    var newContentY = clickRatio * (target.contentHeight - target.height)
                    target.contentY = Math.max(0, Math.min(newContentY, target.contentHeight - target.height))
                    // For simplicity, we don't start dragging immediately after jump here
                } else {
                    var newContentX = clickRatio * (target.contentWidth - target.width)
                    target.contentX = Math.max(0, Math.min(newContentX, target.contentWidth - target.width))
                }
            }
        }
        
        onPositionChanged: (mouse) => {
            if (!pressed || !target) return
            
            var mousePos = (orientation === Qt.Vertical) ? mouse.y : mouse.x
            var delta = mousePos - pressedPos
            
            var trackSize = (orientation === Qt.Vertical) ? scrollBarTrack.height : scrollBarTrack.width
            var thumbSize = (orientation === Qt.Vertical) ? scrollBarThumb.height : scrollBarThumb.width
            var maxThumbPos = trackSize - thumbSize
            
            if (maxThumbPos > 0) {
                var relativeDelta = delta / maxThumbPos
                
                if (orientation === Qt.Vertical) {
                    var maxContentY = target.contentHeight - target.height
                    var newContentY = initialContentPos + (relativeDelta * maxContentY)
                    target.contentY = Math.max(0, Math.min(newContentY, maxContentY))
                } else {
                    var maxContentX = target.contentWidth - target.width
                    var newContentX = initialContentPos + (relativeDelta * maxContentX)
                    target.contentX = Math.max(0, Math.min(newContentX, maxContentX))
                }
            }
        }
    }
}
