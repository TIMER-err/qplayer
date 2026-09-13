import QtQuick
import QtQuick.Layouts
import miuix.Core

// Shared chrome for one generated settings row (see SettingsPage.qml): a rounded
// surface card whose height follows its content. Row components fill it with a
// title row and an optional description.
//
// Children land as DIRECT children of the inner ColumnLayout on purpose —
// Layout.fillWidth only reliably reaches a Layout's immediate child in this
// engine, so a description one level deeper would overflow instead of wrapping.
Card {
    id: card

    default property alias content: col.data

    radius: 20
    color: Theme.color.surfaceContainer
    implicitHeight: col.implicitHeight + 32

    ColumnLayout {
        id: col
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        // Same rhythm the hand-written cards used between their rows.
        spacing: 14
    }
}
