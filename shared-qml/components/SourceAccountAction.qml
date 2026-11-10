import QtQuick
import miuix.Core
import design
import "."

// One tappable action line in SourceAccountDialog (re-login / sign out): a
// leading glyph and label with an inset hover pill; `danger` tints it red.
Item {
    id: action
    property string icon: ""
    property string text: ""
    property bool danger: false
    signal activated()

    readonly property color tint: danger ? Ui.danger : Ui.textPrimary

    Rectangle {
        x: 0
        y: 2
        width: action.width
        height: Math.max(0, action.height - 4)
        radius: Ui.radiusMd
        color: area.pressed ? Ui.surfacePressed
               : (area.containsMouse ? Ui.surfaceHover : "transparent")
        Behavior on color { ColorAnimation { duration: 120 } }
    }
    Glyph {
        x: Ui.space4
        y: (action.height - height) / 2
        name: action.icon
        color: action.danger ? Ui.danger : Ui.textSecondary
    }
    Text {
        x: Ui.space4 + Ui.iconSize + 14
        y: (action.height - implicitHeight) / 2
        width: Math.max(0, action.width - x - Ui.space4)
        text: action.text
        color: action.tint
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textHeadline
    }
    MouseArea {
        id: area
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: action.activated()
    }
}
