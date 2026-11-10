import QtQuick
import QtQuick.Layouts
import design

// The raised surface every settings group sits on. Rows land as DIRECT children
// of the inner ColumnLayout (Layout.fillWidth only reliably reaches a Layout's
// immediate child in qml4j) and draw their own inset hover pill and hairline,
// so the card needs no clipping mask (no offscreen layer per group).
Rectangle {
    id: card
    default property alias content: col.data
    radius: Ui.radiusLg
    color: Ui.surfaceRaised
    implicitHeight: col.implicitHeight + 8

    ColumnLayout {
        id: col
        x: 0
        y: 4
        width: card.width
        spacing: 0
    }
}
