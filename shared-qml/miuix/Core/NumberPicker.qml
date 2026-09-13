import QtQuick
import miuix.Core

// Keep a bounded strip: range size never changes the number of live delegates.
Item {
    id: numberPickerRoot

    property int value: from
    property var range: [0, 10]
    property int from: range.length > 0 ? Number(range[0]) : 0
    property int to: range.length > 1 ? Number(range[1]) : 10
    property bool wrapAround: false
    property int visibleItemCount: 5
    property real itemHeight: 45
    property var label: null
    property color selectedTextColor: enabled ? Theme.color.onSurfaceColor : Theme.color.disabledOnSecondary
    property color unselectedTextColor: enabled ? Theme.color.onSurfaceSecondary : Theme.color.disabledOnSecondary
    property bool _settling: false
    property bool _updatingValue: false
    property bool _initialized: false
    activeFocusOnTab: true
    Keys.onUpPressed: { step(-1); event.accepted = true }
    Keys.onDownPressed: { step(1); event.accepted = true }
    function step(delta) {
        if (!enabled) return
        snap.stop()
        _settling = false
        value = from + _wrapIndex(value - from + delta)
        _contentOffset = (value - from) * itemHeight
    }
    function normalize() {
        if (!_initialized || _dragging || _settling) return
        var next = Math.max(from, Math.min(Math.max(from, to), value))
        if (next !== value) value = next
        _contentOffset = (next - from) * itemHeight
    }
    function resetRange() {
        snap.stop(); _settling = false; _dragging = false; _velocity = 0
        normalize()
    }
    onFromChanged: resetRange()
    onToChanged: resetRange()
    onItemHeightChanged: resetRange()


    // Scroll offset in pixels, measured from the first item in the range.
    property real _contentOffset: (value - from) * itemHeight
    property real _dragStartOffset: 0
    property real _pressY: 0
    property real _sampleY: 0
    property real _sampleTime: 0
    property real _velocity: 0
    property bool _dragging: false

    // Item index at the centre. Changes only when the wheel crosses an item, so the
    // per-slot label bindings stay idle during a drag.
    readonly property int _rounded: Math.round(_contentOffset / itemHeight)
    readonly property int _half: Math.floor(visibleItemCount / 2)

    implicitWidth: 72
    implicitHeight: visibleItemCount * itemHeight
    clip: true

    function _itemCount() {
        return Math.max(1, to - from + 1)
    }

    function _textFor(number) {
        if (typeof label === "function") return label(number)
        return String(number)
    }

    function _wrapIndex(index) {
        var count = _itemCount()
        if (wrapAround) return ((index % count) + count) % count
        return Math.max(0, Math.min(count - 1, index))
    }

    function _inRange(index) {
        return wrapAround || (index >= 0 && index < _itemCount())
    }

    function _settle() {
        var velocity = Date.now() - _sampleTime < 100 ? _velocity : 0
        var travel = velocity * Math.abs(velocity) / 12000
        var index = Math.round((_contentOffset + travel) / itemHeight)
        if (!wrapAround) index = Math.max(0, Math.min(_itemCount() - 1, index))
        _settling = true
        snap.duration = Math.max(180, Math.min(600, Math.abs(velocity) / 6))
        snap.to = index * itemHeight
        snap.restart()
        var next = from + _wrapIndex(index)
        _updatingValue = true
        if (next !== value) value = next
        _updatingValue = false
    }

    onValueChanged: {
        if (!_updatingValue) { snap.stop(); _settling = false; _dragging = false; _velocity = 0; normalize() }
    }
    Component.onCompleted: initialValue.restart()
    Timer {
        id: initialValue
        interval: 1
        onTriggered: { numberPickerRoot._initialized = true; numberPickerRoot.normalize() }
    }
    NumberAnimation {
        id: snap
        target: numberPickerRoot; property: "_contentOffset"
        duration: 180; easing.type: Easing.OutCubic
        onFinished: numberPickerRoot._settling = false
    }

    // Only the bounded visible strip participates in a drag.
    Item {
        id: strip
        width: numberPickerRoot.width
        height: numberPickerRoot.itemHeight
        y: numberPickerRoot.height / 2 - numberPickerRoot.itemHeight / 2
            - (numberPickerRoot._contentOffset - numberPickerRoot._rounded * numberPickerRoot.itemHeight)

        Repeater {
            id: slots
            model: numberPickerRoot.visibleItemCount + 2

            delegate: Item {
                id: slot
                objectName: "miuixNumberSlot" + index
                property real distance: Math.min(1, Math.abs(slotOffset - (numberPickerRoot._contentOffset / numberPickerRoot.itemHeight - numberPickerRoot._rounded)) / (numberPickerRoot._half + 0.5))
                opacity: (1 - distance) * (1 - distance * 0.5)
                scale: 1 - 0.2 * distance
                property int slotOffset: index - (numberPickerRoot._half + 1)
                width: numberPickerRoot.width
                height: numberPickerRoot.itemHeight
                y: slotOffset * numberPickerRoot.itemHeight
                visible: numberPickerRoot._inRange(numberPickerRoot._rounded + slotOffset)

                Text {
                    anchors.centerIn: parent
                    width: parent.width
                    text: numberPickerRoot._textFor(numberPickerRoot.from
                        + numberPickerRoot._wrapIndex(numberPickerRoot._rounded + slot.slotOffset))
                    horizontalAlignment: Text.AlignHCenter
                    elide: Text.ElideRight
                    font.family: Theme.typography.titleMedium.family
                    font.pixelSize: 32
                    font.weight: Font.DemiBold
                    color: Qt.rgba(
                        numberPickerRoot.selectedTextColor.r * (1 - slot.distance) + numberPickerRoot.unselectedTextColor.r * slot.distance,
                        numberPickerRoot.selectedTextColor.g * (1 - slot.distance) + numberPickerRoot.unselectedTextColor.g * slot.distance,
                        numberPickerRoot.selectedTextColor.b * (1 - slot.distance) + numberPickerRoot.unselectedTextColor.b * slot.distance,
                        numberPickerRoot.selectedTextColor.a * (1 - slot.distance) + numberPickerRoot.unselectedTextColor.a * slot.distance)

                }
            }
        }
    }

    // A wheel owns vertical drags outright: preventStealing keeps the enclosing
    // page from taking the gesture at the 10px threshold.
    MouseArea {
        anchors.fill: parent
        preventStealing: true
        enabled: numberPickerRoot.enabled

        onPressed: (mouse) => {
            snap.stop()
            numberPickerRoot._settling = false
            numberPickerRoot.forceActiveFocus()
            numberPickerRoot._dragging = true
            numberPickerRoot._dragStartOffset = numberPickerRoot._contentOffset
            numberPickerRoot._pressY = mouse.y
            numberPickerRoot._sampleY = mouse.y
            numberPickerRoot._sampleTime = Date.now()
            numberPickerRoot._velocity = 0
        }
        onPositionChanged: (mouse) => {
            if (!pressed || !numberPickerRoot._dragging) return
            var now = Date.now()
            var elapsed = now - numberPickerRoot._sampleTime
            if (elapsed > 0) {
                numberPickerRoot._velocity = Math.max(-3000, Math.min(3000,
                    (numberPickerRoot._sampleY - mouse.y) * 1000 / elapsed))
                numberPickerRoot._sampleTime = now
                numberPickerRoot._sampleY = mouse.y
            }
            var next = numberPickerRoot._dragStartOffset - (mouse.y - numberPickerRoot._pressY)
            if (!numberPickerRoot.wrapAround) {
                var maxOffset = (numberPickerRoot._itemCount() - 1) * numberPickerRoot.itemHeight
                next = Math.max(0, Math.min(maxOffset, next))
            }
            numberPickerRoot._contentOffset = next
        }
        onReleased: {
            numberPickerRoot._dragging = false
            numberPickerRoot._settle()
        }
        onCanceled: {
            numberPickerRoot._velocity = 0
            numberPickerRoot._dragging = false
            numberPickerRoot._settle()
        }
    }
}
