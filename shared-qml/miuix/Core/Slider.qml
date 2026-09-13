import QtQuick
import miuix.Core

Item {
    id: control
    property real from: 0
    property real to: 1
    property real value: 0
    property real stepSize: 0
    property bool snapMode: false
    property bool enabled: true
    property bool tickMarksEnabled: false
    property bool valueLabelEnabled: false
    property bool rangeMode: false
    property real firstValue: from
    property real secondValue: to
    property bool reverseDirection: false
    readonly property alias pressed: mouseArea.pressed
    readonly property alias hovered: mouseArea.containsMouse
    signal moved()
    signal firstMoved()
    signal secondMoved()
    signal editingFinished()

    implicitWidth: 200
    implicitHeight: Theme.metrics.sliderHeight
    property var _colors: Theme.color
    readonly property real _range: to - from
    readonly property real _thumbRadius: Math.min(height, width) / 2
    readonly property real _available: Math.max(0, width - 2 * _thumbRadius)
    readonly property real _centerX: positionFor(rangeMode ? firstValue : value)
    readonly property real _secondX: positionFor(secondValue)
    readonly property int _tickCount: tickMarksEnabled && stepSize > 0 && _range > 0
        ? Math.min(200, Math.floor(_range / stepSize) + 1) : 0
    property real _pressX: 0
    property real _pressY: 0
    property bool _dragActive: false
    property bool _secondActive: false

    function fraction(v) {
        return _range <= 0 ? 0 : Math.max(0, Math.min(1, (v - from) / _range))
    }
    function positionFor(v) {
        var f = fraction(v)
        return _thumbRadius + (reverseDirection ? 1 - f : f) * _available
    }
    function bounded(v) {
        if (_range <= 0) return from
        var n = Math.max(from, Math.min(to, v))
        if (stepSize > 0) n = from + Math.round((n - from) / stepSize) * stepSize
        return Math.max(from, Math.min(to, n))
    }
    function setValue(v) {
        var n = bounded(v)
        if (control.value !== n) {
            control.value = n
            control.moved()
        }
    }
    function setFirstValue(v) {
        var n = Math.min(bounded(v), Math.max(from, Math.min(to, secondValue)))
        if (control.firstValue !== n) {
            control.firstValue = n
            control.firstMoved()
        }
    }
    function setSecondValue(v) {
        var n = Math.max(bounded(v), Math.max(from, Math.min(to, firstValue)))
        if (control.secondValue !== n) {
            control.secondValue = n
            control.secondMoved()
        }
    }
    function valueFromX(px) {
        var f = _available <= 0 ? 0 : Math.max(0, Math.min(1, (px - _thumbRadius) / _available))
        return from + (reverseDirection ? 1 - f : f) * Math.max(0, _range)
    }
    function updateAt(px) {
        if (!rangeMode) setValue(valueFromX(px))
        else if (_secondActive) setSecondValue(valueFromX(px))
        else setFirstValue(valueFromX(px))
    }
    function thumbScale(cx, second) {
        var active = pressed && (!rangeMode || _secondActive === second)
        var over = hovered && Math.abs(mouseArea.mouseX - cx) <= _thumbRadius * 1.22
        return enabled && (active || over) ? 1.127 : 1
    }

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: control.enabled ? _colors.sliderBackground : _colors.disabledSecondary
    }
    Rectangle {
        x: control.rangeMode ? Math.max(0, Math.min(control._centerX, control._secondX) - control._thumbRadius)
            : (control.reverseDirection ? control._centerX - control._thumbRadius : 0)
        width: control.rangeMode ? Math.min(control.width, Math.abs(control._secondX - control._centerX) + 2 * control._thumbRadius)
            : (control.reverseDirection ? control.width - x : control._centerX + control._thumbRadius)
        height: parent.height
        radius: height / 2
        color: control.enabled ? _colors.primary : _colors.disabledPrimarySlider
    }
    Repeater {
        model: control._tickCount
        delegate: Rectangle {
            property real tickValue: control.from + index * control.stepSize
            property bool inSelection: control.rangeMode
                ? tickValue >= control.firstValue && tickValue <= control.secondValue
                : tickValue <= control.value
            width: 7.71
            height: width
            radius: width / 2
            x: control.positionFor(tickValue) - width / 2
            y: (control.height - height) / 2
            color: inSelection ? _colors.sliderKeyPointForeground : _colors.sliderKeyPoint
        }
    }
    Rectangle {
        width: control.height * 0.72
        height: width
        radius: width / 2
        x: control._centerX - width / 2
        anchors.verticalCenter: parent.verticalCenter
        scale: control.thumbScale(control._centerX, false)
        color: control.enabled ? _colors.onPrimary : _colors.disabledOnPrimary
        Behavior on scale { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
    }
    Rectangle {
        visible: control.rangeMode
        width: control.height * 0.72
        height: width
        radius: width / 2
        x: control._secondX - width / 2
        anchors.verticalCenter: parent.verticalCenter
        scale: control.thumbScale(control._secondX, true)
        color: control.enabled ? _colors.onPrimary : _colors.disabledOnPrimary
        Behavior on scale { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
    }
    Rectangle {
        visible: control.valueLabelEnabled && control.pressed
        width: valueText.implicitWidth + 16
        height: 28
        x: Math.max(0, Math.min(control.width - width,
            (control.rangeMode && control._secondActive ? control._secondX : control._centerX) - width / 2))
        y: -36
        radius: 8
        color: _colors.inverseSurface
        Text {
            id: valueText
            anchors.centerIn: parent
            text: Math.round((control.rangeMode ? (control._secondActive ? control.secondValue : control.firstValue) : control.value) * 100) / 100
            font.pixelSize: 13
            color: _colors.inverseOnSurface
        }
    }
    // Delay capture until horizontal intent is clear, so vertical page scrolling
    // can still begin on the slider. A tap alone leaves the value unchanged.
    MouseArea {
        id: mouseArea
        anchors.fill: parent
        enabled: control.enabled
        hoverEnabled: true
        preventStealing: control._dragActive
        onPressed: (mouse) => {
            control._pressX = mouse.x
            control._pressY = mouse.y
            control._dragActive = false
            control._secondActive = control.rangeMode
                && Math.abs(mouse.x - control._secondX) < Math.abs(mouse.x - control._centerX)
        }
        onPositionChanged: (mouse) => {
            if (!pressed) return
            if (!control._dragActive) {
                var dx = mouse.x - control._pressX
                var dy = mouse.y - control._pressY
                if (Math.abs(dx) < 8 || Math.abs(dx) <= Math.abs(dy)) return
                control._dragActive = true
            }
            control.updateAt(mouse.x)
        }
        onReleased: {
            if (control._dragActive) control.editingFinished()
            control._dragActive = false
        }
        onCanceled: control._dragActive = false
    }
}
