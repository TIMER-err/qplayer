import QtQuick
import miuix.Core
import design
import "."

// One sidebar destination: icon + label pill. Collapses to an icon-only pill on
// the narrow rail. Selection uses the soft accent wash with accent content.
Item {
    id: item
    property string icon: ""
    property string text: ""
    property bool selected: false
    property bool expanded: true
    signal clicked()

    height: 44

    Rectangle {
        id: pill
        objectName: item.objectName.length > 0 ? item.objectName + "Pill" : ""
        x: item.expanded ? 12 : (item.width - width) / 2
        y: 2
        width: item.expanded ? item.width - 24 : 56
        height: item.height - 4
        radius: Ui.radiusMd
        color: item.selected ? Ui.accentSoft
               : (area.pressed ? Ui.surfacePressed
                  : (area.containsMouse ? Ui.surfaceHover : "transparent"))
        Behavior on color { ColorAnimation { duration: 120 } }
    }

    Glyph {
        x: item.expanded ? pill.x + 14 : pill.x + (pill.width - width) / 2
        y: pill.y + (pill.height - height) / 2
        name: item.icon
        color: item.selected ? Ui.accent : Ui.textSecondary
    }

    Text {
        x: pill.x + 48
        y: pill.y + (pill.height - implicitHeight) / 2
        width: Math.max(0, pill.width - 60)
        visible: item.expanded
        text: item.text
        color: item.selected ? Ui.accent : Ui.textPrimary
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textBody
        font.weight: item.selected ? Ui.weightBold : Ui.weightRegular
    }

    MouseArea {
        id: area
        x: pill.x
        y: pill.y
        width: pill.width
        height: pill.height
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: item.clicked()
    }
}
