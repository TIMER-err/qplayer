import QtQuick
import miuix.Core

IconButton {
    property bool emphasized: false
    width: 40
    height: 44
    // Rounded-square state layer (radiusMd) reads tidier than a 40x44 oval on
    // the translucent lyric strip; geometry stays pinned to the native hit tests.
    cornerRadius: 14
    containerColor: emphasized ? desktopLyric.secondaryContainerColor : "transparent"
    contentColor: emphasized ? desktopLyric.onSecondaryContainerColor : desktopLyric.onSurfaceVariantColor
}
