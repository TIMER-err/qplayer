import QtQuick
import miuix.Core

Item {
    id: control
    property string text: ""
    property string title: ""
    property string actionText: ""
    property int timeout: 2500
    property var anchorItem: null
    property string placement: "below"
    property real spacing: 8
    property real maxWidth: title !== "" || actionText !== "" ? 320 : 200
    property bool _closing: false
    property var _owner: null
    property real _anchorX: 0
    property real _anchorY: 0
    property bool _above: false
    readonly property bool rich: title !== "" || actionText !== ""
    readonly property real padding: rich ? 16 : 12
    readonly property bool opened: visible && !_closing
    signal actionClicked()
    signal closed()
    z: 99999
    visible: false
    opacity: 0
    width: Math.max(0, Math.min(maxWidth, (parent ? parent.width : 320) - 16, measure.implicitWidth + padding * 2))
    height: label.implicitHeight + (rich ? 32 : 16) + (heading.visible ? heading.implicitHeight + 8 : 0) + (action.visible ? 38 : 0)
    x: parent ? Math.max(8, Math.min(anchorItem ? _anchorX : (parent.width - width) / 2, parent.width - width - 8)) : 0
    y: parent ? Math.max(8, Math.min(anchorItem ? _anchorY : (parent.height - height) / 2, parent.height - height - 8)) : 0

    function position() {
        if (!anchorItem || !parent) return
        var p = parent.mapFromItem(anchorItem, 0, 0)
        _anchorX = p.x + (anchorItem.width - width) / 2
        _above = placement === "above"
        if (placement === "below" && p.y + anchorItem.height + spacing + height > parent.height - 8) _above = true
        if (_above && p.y - height - spacing < 8) _above = false
        _anchorY = _above ? p.y - height - spacing : p.y + anchorItem.height + spacing
        if (placement === "left" || placement === "right") {
            _anchorY = p.y + (anchorItem.height - height) / 2
            _anchorX = placement === "left" ? p.x - width - spacing : p.x + anchorItem.width + spacing
            if (_anchorX < 8) _anchorX = p.x + anchorItem.width + spacing
            if (_anchorX + width > parent.width - 8) _anchorX = p.x - width - spacing
        }
    }
    function open() {
        hideAnim.stop()
        _closing = false
        if (!_owner) _owner = parent
        var host = control
        while (host.parent) host = host.parent
        parent = host
        visible = true
        position()
        showAnim.restart()
        hideTimer.stop()
        if (timeout > 0) hideTimer.restart()
    }
    function close() {
        if (!visible || _closing) return
        showAnim.stop(); hideTimer.stop(); _closing = true; hideAnim.restart()
    }
    Timer { interval: 40; repeat: true; running: control.visible && control.anchorItem !== null; onTriggered: control.position() }
    Timer { id: hideTimer; interval: control.timeout; onTriggered: control.close() }
    NumberAnimation { id: showAnim; target: control; property: "opacity"; to: 1; duration: 200; easing.type: Easing.OutCubic }
    NumberAnimation {
        id: hideAnim; target: control; property: "opacity"; to: 0; duration: 150
        onFinished: { control.visible = false; if (control._owner) control.parent = control._owner; control._owner = null; control._closing = false; control.closed() }
    }
    Keys.onEscapePressed: { close(); event.accepted = true }
    SmoothRectangle { anchors.fill: parent; radius: control.rich ? 16 : 12; color: Theme.color.onSecondaryVariant }
    Text { id: measure; visible: false; text: control.title.length > control.text.length ? control.title : control.text; font.pixelSize: 14 }
    Text {
        id: heading
        x: control.padding; y: 16; width: Math.max(0, control.width - x * 2)
        text: control.title; visible: text !== ""; font.pixelSize: 16; font.weight: Font.Medium
        color: Theme.color.secondaryVariant; wrapMode: Text.Wrap
    }
    Text {
        id: label
        x: control.padding; y: heading.visible ? heading.y + heading.implicitHeight + 8 : (control.rich ? 16 : 8)
        width: Math.max(0, control.width - x * 2)
        text: control.text; font.pixelSize: 14; color: Theme.color.secondaryVariant; wrapMode: Text.Wrap
    }
    Button {
        id: action
        visible: control.actionText !== ""; text: control.actionText
        x: control.padding; y: label.y + label.implicitHeight + 8
        height: 30; width: Math.min(implicitWidth, Math.max(0, control.width - x * 2))
        cornerRadius: 8
        onClicked: { control.actionClicked(); control.close() }
    }
}
