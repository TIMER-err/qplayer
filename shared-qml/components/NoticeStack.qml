import QtQuick
import "."

// Transient notices, stacked bottom-centre above `bottomInset` (the floating
// player bar on the main scene). Fixed slots, as in miuix ToastStack: a notice
// keeps its slot until it has faded out, so nothing reflows mid-fade. New
// notices take the lowest free slot; with all three busy the oldest is reused.
Item {
    id: control

    property int timeout: 3200
    property real bottomInset: 24
    readonly property int maxVisible: 3

    anchors.bottom: parent ? parent.bottom : undefined
    anchors.left: parent ? parent.left : undefined
    anchors.right: parent ? parent.right : undefined
    anchors.leftMargin: 16
    anchors.rightMargin: 16
    anchors.bottomMargin: bottomInset
    // Reservation only; Item does not clip, taller multi-line notices extend up.
    height: maxVisible * 44 + (maxVisible - 1) * 8

    function show(msg) {
        if (msg === undefined || msg === null || msg === "") return
        var s = firstEmpty()
        if (s === null) s = slot0
        s.entry = { text: msg }
        s.text = msg
        s.show()
    }

    function dismissSlot(slot) {
        slot.beginDismiss()
    }

    function firstEmpty() {
        if (slot0.entry === null) return slot0
        if (slot1.entry === null) return slot1
        if (slot2.entry === null) return slot2
        return null
    }

    NoticeCard {
        id: slot0
        anchors.bottom: parent.bottom
        width: parent.width
        timeout: control.timeout
        host: control
    }
    NoticeCard {
        id: slot1
        anchors.bottom: slot0.top
        anchors.bottomMargin: 8
        width: parent.width
        timeout: control.timeout
        host: control
    }
    NoticeCard {
        id: slot2
        anchors.bottom: slot1.top
        anchors.bottomMargin: 8
        width: parent.width
        timeout: control.timeout
        host: control
    }
}
