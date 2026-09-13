import QtQuick
import miuix.Core

// Shared full-screen page navigation. Home is deliberately left of Back: Home
// clears the complete nested route stack to reveal the current root destination,
// while Back pops exactly one route.
Item {
    id: control
    signal home()
    signal back()

    implicitWidth: 80
    implicitHeight: 40

    Row {
        anchors.fill: parent

        IconButton {
            type: "standard"
            icon: "home"
            onClicked: control.home()
        }
        IconButton {
            type: "standard"
            icon: "arrow_back"
            onClicked: control.back()
        }
    }
}
