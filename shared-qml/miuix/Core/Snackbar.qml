import QtQuick
import miuix.Core

Item {
    id: control
    property string text: ""
    property string actionText: ""
    property bool withDismissAction: false
    property int timeout: 4000
    property real maxWidth: 600
    property real bottomInset: 0
    property color containerColor: Theme.color.onSecondaryVariant
    property color contentColor: Theme.color.secondaryVariant
    readonly property bool opened: visible && !_closing
    property bool _closing: false
    signal actionClicked()
    signal closed()
    visible: false
    opacity: 0
    z: 999
    anchors.bottom: parent ? parent.bottom : undefined
    anchors.horizontalCenter: parent ? parent.horizontalCenter : undefined
    anchors.bottomMargin: 8 + bottomInset
    width: Math.max(0, Math.min(maxWidth, (parent ? parent.width : 320) - 24))
    implicitHeight: Math.max(label.implicitHeight, action.visible ? action.height : 0, dismiss.visible ? 26 : 0) + 24
    readonly property bool _stacked: action.visible && action.implicitWidth > width * 0.4
    height: implicitHeight + (_stacked ? 34 : 0)

    function open() {
        hideAnim.stop()
        _closing = false
        visible = true
        showAnim.restart()
        hideTimer.stop()
        if (timeout > 0) hideTimer.restart()
    }
    function close() {
        if (!visible || _closing) return
        hideTimer.stop()
        showAnim.stop()
        _closing = true
        hideAnim.restart()
    }
    Keys.onEscapePressed: { close(); event.accepted = true }
    Timer { id: hideTimer; interval: control.timeout; onTriggered: control.close() }
    NumberAnimation { id: showAnim; target: control; property: "opacity"; to: 1; duration: 200; easing.type: Easing.OutCubic }
    NumberAnimation {
        id: hideAnim; target: control; property: "opacity"; to: 0; duration: 150
        onFinished: { control.visible = false; control._closing = false; control.closed() }
    }
    SmoothRectangle { anchors.fill: parent; radius: 16; color: control.containerColor }
    Text {
        id: label
        x: 12
        y: (control.height - (control._stacked ? 34 : 0) - implicitHeight) / 2
        width: Math.max(0, control.width - 24 - (dismiss.visible ? 28 : 0)
            - (action.visible && !control._stacked ? action.width + 8 : 0))
        text: control.text
        font.pixelSize: 16
        color: control.contentColor
        wrapMode: Text.Wrap
    }
    Button {
        id: action
        objectName: "miuixSnackbarAction"
        visible: control.actionText !== ""
        text: control.actionText
        width: Math.min(implicitWidth, Math.max(0, control.width - 24))
        height: 26
        x: control.width - width - 12 - (dismiss.visible ? 28 : 0)
        y: control._stacked ? control.height - height - 12 : (control.height - height) / 2
        cornerRadius: 50
        contentItem: Text {
            text: control.actionText
            width: Math.max(0, action.width - 24)
            font.pixelSize: 15
            color: Theme.color.onPrimary
            elide: Text.ElideRight
            horizontalAlignment: Text.AlignHCenter
        }
        horizontalPadding: 12
        verticalPadding: 0
        onClicked: { control.actionClicked(); control.close() }
    }
    IconButton {
        id: dismiss
        visible: control.withDismissAction
        icon: "close"
        width: 26; height: 26
        x: control.width - width - 12
        y: 12
        contentColor: control.contentColor
        onClicked: control.close()
    }
}
