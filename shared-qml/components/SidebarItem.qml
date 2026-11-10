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
    // The root destinations share one sliding selection pill owned by the rail;
    // their own pill then only draws hover and press states.
    property bool pillDrawnByParent: false
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
        color: item.selected ? (item.pillDrawnByParent ? "transparent" : Ui.accentSoft)
               : (area.pressed ? Ui.surfacePressed
                  : (area.containsMouse ? Ui.surfaceHover : "transparent"))
        Behavior on color { ColorAnimation { duration: 140 } }
    }

    // Content dips slightly while pressed; hit-testing ignores scale, so the
    // pill's MouseArea keeps its full box.
    property real pressScale: area.pressed ? 0.96 : 1
    Behavior on pressScale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

    Glyph {
        x: item.expanded ? pill.x + 14 : pill.x + (pill.width - width) / 2
        y: pill.y + (pill.height - height) / 2
        name: item.icon
        scale: item.pressScale
        color: item.selected ? Ui.accent : Ui.textSecondary
        Behavior on color { ColorAnimation { duration: 160 } }
    }

    Text {
        x: pill.x + 48
        y: pill.y + (pill.height - implicitHeight) / 2
        width: Math.max(0, pill.width - 60)
        visible: item.expanded
        text: item.text
        color: item.selected ? Ui.accent : Ui.textPrimary
        Behavior on color { ColorAnimation { duration: 160 } }
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
