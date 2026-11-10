import QtQuick
import design

// One search-mode pill (歌曲 / 专辑 / 歌手 / 歌单). Selected: accent-soft fill
// with accent text; otherwise an outlined pill with a hover wash.
Item {
    id: pill
    property string text: ""
    property bool selected: false
    signal clicked()

    width: label.implicitWidth + 2 * Ui.space4
    height: Ui.pillHeight

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: pill.selected ? Ui.accentSoft : "transparent"
        border.width: pill.selected ? 0 : 1
        border.color: Ui.hairline
        Behavior on color { ColorAnimation { duration: 200; easing.type: Easing.OutCubic } }
    }
    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: area.pressed ? Ui.surfacePressed : Ui.surfaceHover
        opacity: !pill.selected && (area.containsMouse || area.pressed) ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
    }
    Text {
        id: label
        x: (pill.width - implicitWidth) / 2
        y: (pill.height - implicitHeight) / 2
        text: pill.text
        color: pill.selected ? Ui.accent : Ui.textPrimary
        font.pixelSize: Ui.textCallout
        font.weight: Ui.weightBold
    }
    MouseArea {
        id: area
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: pill.clicked()
    }
}
