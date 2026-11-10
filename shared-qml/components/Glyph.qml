import QtQuick
import design

// A Material Symbols ligature drawn at a fixed square size. Use this instead of
// ad-hoc Text items that set the icon font family by hand.
Text {
    id: glyph
    property string name: ""
    property int size: Ui.iconSize
    width: size
    height: size
    text: name
    color: Ui.textPrimary
    font.family: Ui.iconFont
    font.pixelSize: size
    horizontalAlignment: Text.AlignHCenter
}
