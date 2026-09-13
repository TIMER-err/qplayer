import QtQuick
import miuix.Core

// Ports miuix basic/ColorPicker.kt's HsvColorPicker: a preview capsule above the
// hue / saturation / value / alpha sliders.
//
Item {
    id: colorPickerRoot

    property color color: Theme.color.primary
    property bool showPreview: true
    property string colorSpace: "HSV"
    property real spacing: 12
    property real sliderHeight: 26
    signal colorSelected(color newColor)

    property real _hue: 0
    property real _saturation: 1
    property real _value: 1
    property real _alpha: 1
    property real _first: 0
    property real _second: 0
    property real _third: 0
    readonly property string _space: colorSpace.toUpperCase()
    readonly property bool _hsv: _space === "HSV"
    function channelColor(x, y, z, alpha) { return ColorMath.fromChannels(_space, x, y, z, alpha) }
    function samples(axis, x, y, z, alpha, space) {
        var result = []
        for (var i = 0; i <= 12; i++) result.push(ColorMath.fromChannels(space,
            axis === 0 ? i / 12 : x, axis === 1 ? i / 12 : y, axis === 2 ? i / 12 : z, alpha))
        return result
    }
    onColorSpaceChanged: _adoptExternalColor()
    property bool _syncing: false

    implicitWidth: 320
    implicitHeight: layout.height

    function _hsvColor(h, s, v, a) {
        var hh = ((h % 360) + 360) % 360 / 60
        var c = v * s
        var x = c * (1 - Math.abs((hh % 2) - 1))
        var m = v - c
        var r = 0, g = 0, b = 0
        if (hh < 1) { r = c; g = x }
        else if (hh < 2) { r = x; g = c }
        else if (hh < 3) { g = c; b = x }
        else if (hh < 4) { g = x; b = c }
        else if (hh < 5) { r = x; b = c }
        else { r = c; b = x }
        return Qt.rgba(r + m, g + m, b + m, a)
    }

    function _toHsv(value) {
        var r = value.r, g = value.g, b = value.b
        var max = Math.max(r, g, b)
        var min = Math.min(r, g, b)
        var d = max - min
        var h = 0
        if (d > 0) {
            if (max === r) h = 60 * ((((g - b) / d) % 6 + 6) % 6)
            else if (max === g) h = 60 * ((b - r) / d + 2)
            else h = 60 * ((r - g) / d + 4)
        }
        return { h: h, s: max === 0 ? 0 : d / max, v: max }
    }

    readonly property color _selectedColor: _hsv ? _hsvColor(_hue, _saturation, _value, _alpha) : channelColor(_first, _second, _third, _alpha)

    function _emit() {
        _syncing = true
        color = _selectedColor
        _syncing = false
        colorSelected(_selectedColor)
    }

    function _adoptExternalColor() {
        if (_syncing) return
        var hsv = _toHsv(color)
        _hue = hsv.h
        _saturation = hsv.s
        _value = hsv.v
        _alpha = color.a
        if (!_hsv) {
            var channels = ColorMath.channels(_space, color)
            _first = channels[0]; _second = channels[1]; _third = channels[2]
        }
    }

    onColorChanged: _adoptExternalColor()
    Component.onCompleted: _adoptExternalColor()

    Column {
        id: layout
        width: parent.width
        spacing: colorPickerRoot.spacing

        Rectangle {
            width: parent.width
            height: 26
            radius: 13
            visible: colorPickerRoot.showPreview
            color: colorPickerRoot._selectedColor
        }

        ColorSlider {
            enabled: colorPickerRoot.enabled
            width: parent.width
            height: colorPickerRoot.sliderHeight
            value: colorPickerRoot._hsv ? colorPickerRoot._hue / 360 : colorPickerRoot._first
            colors: colorPickerRoot._hsv ? [] : colorPickerRoot.samples(0, colorPickerRoot._first, colorPickerRoot._second, colorPickerRoot._third, 1, colorPickerRoot._space)
            hue: true
            onMoved: (newValue) => {
                if (colorPickerRoot._hsv) colorPickerRoot._hue = newValue * 360
                else colorPickerRoot._first = newValue
                colorPickerRoot._emit()
            }
        }

        ColorSlider {
            enabled: colorPickerRoot.enabled
            width: parent.width
            height: colorPickerRoot.sliderHeight
            value: colorPickerRoot._hsv ? colorPickerRoot._saturation : colorPickerRoot._second
            colors: colorPickerRoot._hsv ? [] : colorPickerRoot.samples(1, colorPickerRoot._first, colorPickerRoot._second, colorPickerRoot._third, 1, colorPickerRoot._space)
            startColor: colorPickerRoot._hsvColor(colorPickerRoot._hue, 0, 1, 1)
            endColor: colorPickerRoot._hsvColor(colorPickerRoot._hue, 1, 1, 1)
            onMoved: (newValue) => {
                if (colorPickerRoot._hsv) colorPickerRoot._saturation = newValue
                else colorPickerRoot._second = newValue
                colorPickerRoot._emit()
            }
        }

        ColorSlider {
            enabled: colorPickerRoot.enabled
            width: parent.width
            height: colorPickerRoot.sliderHeight
            value: colorPickerRoot._hsv ? colorPickerRoot._value : colorPickerRoot._third
            colors: colorPickerRoot._hsv ? [] : colorPickerRoot.samples(2, colorPickerRoot._first, colorPickerRoot._second, colorPickerRoot._third, 1, colorPickerRoot._space)
            startColor: colorPickerRoot._hsvColor(colorPickerRoot._hue, colorPickerRoot._saturation, 0, 1)
            endColor: colorPickerRoot._hsvColor(colorPickerRoot._hue, colorPickerRoot._saturation, 1, 1)
            onMoved: (newValue) => {
                if (colorPickerRoot._hsv) colorPickerRoot._value = newValue
                else colorPickerRoot._third = newValue
                colorPickerRoot._emit()
            }
        }

        ColorSlider {
            enabled: colorPickerRoot.enabled
            width: parent.width
            height: colorPickerRoot.sliderHeight
            checkerboard: true
            value: colorPickerRoot._alpha
            startColor: Qt.rgba(colorPickerRoot._selectedColor.r, colorPickerRoot._selectedColor.g, colorPickerRoot._selectedColor.b, 0)
            endColor: Qt.rgba(colorPickerRoot._selectedColor.r, colorPickerRoot._selectedColor.g, colorPickerRoot._selectedColor.b, 1)
            onMoved: (newValue) => {
                colorPickerRoot._alpha = newValue
                colorPickerRoot._emit()
            }
        }
    }
}
