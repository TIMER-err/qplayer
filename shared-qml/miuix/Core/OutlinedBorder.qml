import QtQuick

// Rounded outlined border with a Material floating-label notch. The notch is a
// real hole in the TOP stroke: three clipped views of the same transparent
// rounded Rectangle retain the sides/bottom/corners without painting a surface-
// coloured mask over whatever happens to sit behind the label.
Item {
    id: root

    property color strokeColor: "transparent"
    property real strokeWidth: 1
    property real cornerRadius: 4
    property bool notchVisible: false
    property real notchX: 0
    property real notchWidth: 0

    property real _notchLeft: Math.max(0, Math.min(width, notchX))
    property real _notchRight: Math.max(_notchLeft,
                                        Math.min(width, notchX + notchWidth))

    // No floating label: keep the ordinary single rounded outline.
    Rectangle {
        anchors.fill: parent
        visible: !root.notchVisible || root._notchRight <= root._notchLeft
        radius: root.cornerRadius
        color: "transparent"
        border.width: root.strokeWidth
        border.color: root.strokeColor
    }

    // With a notch, show the complete outline to the left and right of it.
    Item {
        id: leftClip
        x: 0
        y: 0
        width: root._notchLeft
        height: root.height
        clip: true
        visible: root.notchVisible && width > 0

        Rectangle {
            x: -leftClip.x
            y: -leftClip.y
            width: root.width
            height: root.height
            radius: root.cornerRadius
            color: "transparent"
            border.width: root.strokeWidth
            border.color: root.strokeColor
        }
    }

    Item {
        id: rightClip
        x: root._notchRight
        y: 0
        width: Math.max(0, root.width - x)
        height: root.height
        clip: true
        visible: root.notchVisible && width > 0

        Rectangle {
            x: -rightClip.x
            y: -rightClip.y
            width: root.width
            height: root.height
            radius: root.cornerRadius
            color: "transparent"
            border.width: root.strokeWidth
            border.color: root.strokeColor
        }
    }

    // Inside the notch's horizontal span, retain everything BELOW the top
    // stroke. This is what distinguishes clipping from a background-colour mask:
    // content below the floating label remains visible and untouched.
    Item {
        id: notchBodyClip
        x: root._notchLeft
        y: root.strokeWidth
        width: Math.max(0, root._notchRight - root._notchLeft)
        height: Math.max(0, root.height - y)
        clip: true
        visible: root.notchVisible && width > 0 && height > 0

        Rectangle {
            x: -notchBodyClip.x
            y: -notchBodyClip.y
            width: root.width
            height: root.height
            radius: root.cornerRadius
            color: "transparent"
            border.width: root.strokeWidth
            border.color: root.strokeColor
        }
    }
}
