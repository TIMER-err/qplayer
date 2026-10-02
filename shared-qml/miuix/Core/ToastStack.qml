import QtQuick
import miuix.Core

// Stacked toast notifications, anchored bottom-center. Fixed-slot model: a toast
// occupies one slot from appearance until it fades out — nothing ever moves, so a
// fade can't fight a reflow (qml4j can't auto-arrange dynamic children, and an
// index-based re-layout would fade a shifted toast out and in simultaneously).
// New toasts fill the lowest empty slot; when all maxVisible slots are full the
// OLDEST (bottom slot) is closed immediately to make room.
Item {
    id: control

    property int timeout: 4000
    property int maxVisible: 3

    anchors.bottom: parent ? parent.bottom : undefined
    anchors.left: parent ? parent.left : undefined
    anchors.right: parent ? parent.right : undefined
    anchors.leftMargin: 16
    anchors.rightMargin: 16
    anchors.bottomMargin: 24
    // Multiline cards may exceed this reservation; Item deliberately does not
    // clip, and the bottom-anchored chain continues upward without reflow.
    height: maxVisible * 96 + (maxVisible - 1) * 10

    function show(msg) {
        if (msg === undefined || msg === null || msg === "") return
        var s = firstEmpty()
        if (s === null) s = slot0
        s.entry = { text: msg }
        s.text = msg
        s.show()
    }

    // Called by a slot when its toast's timer expires or its close icon is
    // tapped: fade that slot out; onFinished frees it for reuse.
    function dismissSlot(slot) {
        slot.beginDismiss()
    }

    // Lowest slot that isn't showing a toast (including one still fading out).
    function firstEmpty() {
        if (slot0.entry === null) return slot0
        if (slot1.entry === null) return slot1
        if (slot2.entry === null) return slot2
        return null
    }

    // Slot 0 is filled first and therefore becomes the oldest/bottom card.
    // Newer cards stack above it; a full stack reuses slot 0.
    ToastSlot {
        id: slot0
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        width: parent.width
        timeout: control.timeout
        host: control
    }
    ToastSlot {
        id: slot1
        anchors.bottom: slot0.top
        anchors.bottomMargin: 10
        anchors.horizontalCenter: parent.horizontalCenter
        width: parent.width
        timeout: control.timeout
        host: control
    }
    ToastSlot {
        id: slot2
        anchors.bottom: slot1.top
        anchors.bottomMargin: 10
        anchors.horizontalCenter: parent.horizontalCenter
        width: parent.width
        timeout: control.timeout
        host: control
    }
}
