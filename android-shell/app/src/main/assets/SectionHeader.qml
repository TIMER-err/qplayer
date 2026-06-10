import QtQuick
import md3.Core

// A list section label ("搜索结果" / "本地音乐"). Plain Item so it sits in a
// Column positioner; height follows implicitHeight via the renderer.
Item {
    property alias text: label.text

    implicitHeight: 44

    Text {
        id: label
        anchors.left: parent.left
        anchors.leftMargin: 16
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 6
        color: Theme.color.primary
        fontSize: 16
    }
}
