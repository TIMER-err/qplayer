import QtQuick
import miuix.Core
import design
import "../components"

// Chrome shared by every settings row: optional leading glyph, title and
// summary, an optional trailing value + indicator (chevron / unfold), an inset
// hover pill and the hairline that separates the row from the one above it.
//
// Row components COMPOSE this (they never derive from it: qml4j drops children
// and user properties across a custom-type inheritance chain). A row that owns
// a trailing control (Switch, stepper, swatch) reserves its width through
// controlWidth and positions the control itself at width - 16 - controlWidth.
Item {
    id: frame
    property string title: ""
    property string summary: ""
    property string icon: ""
    property color iconColor: Ui.textSecondary
    // Trailing text drawn by the frame (current dropdown choice, "选择目录" ...).
    property string value: ""
    // Action rows ("设为主音源") tint their trailing text with the accent.
    property color valueColor: Ui.textSecondary
    // Trailing glyph: "chevron_right", "unfold_more", "" for none.
    property string indicator: ""
    property color indicatorColor: Ui.textTertiary
    property real controlWidth: 0
    property bool clickable: true
    // Width of a custom leading item (avatar) the row draws itself at x = 16.
    property real leadingWidth: 0
    property bool rowEnabled: true
    property bool separator: false
    property real topPadding: 14
    property real bottomPadding: 14
    property real minHeight: 56
    signal clicked()

    readonly property real padding: Ui.space4
    readonly property real textX: leadingWidth > 0 ? padding + leadingWidth + 14
                                  : (icon.length > 0 ? padding + Ui.iconSize + 14 : padding)
    readonly property real valueWidth: value.length > 0
        ? Math.min(valueText.implicitWidth, Math.max(0, width * 0.38)) : 0
    readonly property real indicatorWidth: indicator.length > 0 ? 20 : 0
    readonly property real trailingWidth: controlWidth
        + valueWidth + (valueWidth > 0 && indicatorWidth > 0 ? 4 : 0) + indicatorWidth
        + (controlWidth > 0 && (valueWidth > 0 || indicatorWidth > 0) ? 8 : 0)
    readonly property real textWidth: Math.max(0, width - textX - padding
        - (trailingWidth > 0 ? trailingWidth + Ui.space4 : 0))
    readonly property real labelsHeight: titleText.implicitHeight
        + (summaryText.visible ? 3 + summaryText.implicitHeight : 0)
    readonly property bool hovered: hit.containsMouse
    readonly property bool pressed: hit.pressed

    implicitHeight: Math.max(minHeight, labelsHeight + topPadding + bottomPadding)

    Rectangle {
        objectName: "settingRowHover"
        x: 4
        y: 2
        width: Math.max(0, frame.width - 8)
        height: Math.max(0, frame.height - 4)
        radius: Ui.radiusLg - 4
        visible: frame.clickable && frame.rowEnabled
        color: frame.pressed ? Ui.surfacePressed
               : (frame.hovered ? Ui.surfaceHover : "transparent")
        Behavior on color { ColorAnimation { duration: 120 } }
    }

    Rectangle {
        objectName: "settingRowSeparator"
        x: frame.textX
        y: 0
        width: Math.max(0, frame.width - frame.textX - frame.padding)
        height: 1
        visible: frame.separator
        color: Ui.hairline
    }

    Glyph {
        // Centred on the label block; a tall wrapped block keeps it on the title line.
        y: titleText.y + (frame.labelsHeight <= 48 ? (frame.labelsHeight - height) / 2
                          : (titleText.implicitHeight - height) / 2)
        x: frame.padding
        visible: frame.icon.length > 0
        name: frame.icon
        color: frame.iconColor
        opacity: frame.rowEnabled ? 1 : 0.45
    }

    Text {
        id: titleText
        x: frame.textX
        y: Math.max(frame.topPadding,
                    (frame.height - frame.topPadding - frame.bottomPadding - frame.labelsHeight) / 2
                    + frame.topPadding)
        width: frame.textWidth
        text: frame.title
        color: Ui.textPrimary
        opacity: frame.rowEnabled ? 1 : 0.45
        wrapMode: Text.Wrap
        font.pixelSize: Ui.textHeadline
        font.weight: Ui.weightRegular
    }

    Text {
        id: summaryText
        x: frame.textX
        y: titleText.y + titleText.implicitHeight + 3
        width: frame.textWidth
        visible: frame.summary.length > 0
        text: frame.summary
        color: Ui.textSecondary
        opacity: frame.rowEnabled ? 1 : 0.45
        wrapMode: Text.Wrap
        font.pixelSize: Ui.textCallout
    }

    Text {
        id: valueText
        objectName: "settingRowValue"
        x: frame.width - frame.padding - frame.indicatorWidth
           - (frame.indicatorWidth > 0 ? 4 : 0) - width
        y: (frame.height - implicitHeight) / 2
        width: frame.valueWidth
        visible: frame.value.length > 0
        text: frame.value
        color: frame.valueColor
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textCallout
    }

    Glyph {
        x: frame.width - frame.padding - width
        y: (frame.height - height) / 2
        size: 20
        visible: frame.indicator.length > 0
        name: frame.indicator
        color: frame.indicatorColor
        opacity: frame.rowEnabled ? 1 : 0.45
    }

    MouseArea {
        id: hit
        anchors.fill: parent
        enabled: frame.clickable && frame.rowEnabled
        hoverEnabled: enabled
        cursorShape: Qt.PointingHandCursor
        onClicked: frame.clicked()
    }
}
