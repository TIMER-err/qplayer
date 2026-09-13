import QtQuick
import miuix.Core

Button {
    property bool danger: false
    signal activated()
    type: "text"
    contentColor: danger ? Theme.color.error : Theme.color.onSurfaceColor
    onClicked: activated()
}
