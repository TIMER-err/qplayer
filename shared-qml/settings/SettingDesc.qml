import QtQuick
import QtQuick.Layouts
import miuix.Core

// A settings row's secondary line; collapses when there's nothing to say.
Text {
    Layout.fillWidth: true
    visible: text.length > 0
    color: Theme.color.onSurfaceVariantColor
    font.family: Theme.typography.bodySmall.family
    font.pixelSize: 14
    wrapMode: Text.WordWrap
}
