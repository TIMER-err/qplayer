import QtQuick
import QtQuick.Layouts
import miuix.Core

// A settings row's main label. Elides rather than growing into whatever control
// shares its row: a translated title is routinely much longer than the original.
Text {
    Layout.fillWidth: true
    wrapMode: Text.Wrap
    color: Theme.color.onSurfaceColor
    font.family: Theme.typography.bodyLarge.family
    font.pixelSize: 17
    font.weight: Font.DemiBold
}
