import QtQuick
import miuix.Core

// Overlay scrollbar for declaration directly inside its target Flickable. A
// Flickable translates direct children with its content, so compensate with the
// current content position to keep the track fixed to the viewport.
ScrollBar {
    id: control

    objectName: "viewportScrollBar"
    x: !target ? 0 : (orientation === Qt.Vertical
        ? target.contentX + target.width - width : target.contentX)
    y: !target ? 0 : (orientation === Qt.Vertical
        ? target.contentY : target.contentY + target.height - height)
    width: !target ? 0 : (orientation === Qt.Vertical ? implicitWidth : target.width)
    height: !target ? 0 : (orientation === Qt.Vertical ? target.height : implicitHeight)
    z: 1000
}
