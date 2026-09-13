import QtQuick
import miuix.Core

IconButton {
    property bool emphasized: false
    width: 40
    height: 44
    containerColor: emphasized ? desktopLyric.secondaryContainerColor : "transparent"
    contentColor: emphasized ? desktopLyric.onSecondaryContainerColor : desktopLyric.onSurfaceVariantColor
}
