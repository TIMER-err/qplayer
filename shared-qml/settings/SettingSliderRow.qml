import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.SLIDER: an integer-backed slider. `dots` enables fixed-step
// snapping plus one tick dot per step; without it the thumb moves continuously.
// The value is written ONLY when the drag ends (Slider.editingFinished): some
// settings apply live and heavily (uiScale rescales the whole window), and a
// write per pointer move would fight the drag that produced it. While dragging,
// the value pill previews the pending value.
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    property int storedValue: row.spec ? settings.value(row.spec.key) : 0
    onStoredValueChanged: valueSlider.value = storedValue

    function displayValue(value) {
        if (!row.spec) return ""
        var stored = Math.round(value)
        var shown = row.spec.scale > 1
                ? (stored / row.spec.scale).toFixed(2)
                : ("" + stored)
        return shown + row.spec.unit
    }

    readonly property real sliderY: frame.height + Ui.space2
    implicitHeight: sliderY + valueSlider.height + Ui.space2 + rangeMin.implicitHeight + Ui.space4

    SettingRowFrame {
        id: frame
        width: row.width
        height: implicitHeight
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.spec ? i18n.t(row.spec.desc) : ""
        separator: row.separator
        clickable: false
        minHeight: 0
        bottomPadding: 0
        controlWidth: valuePill.width
    }

    Rectangle {
        id: valuePill
        objectName: "settingSliderValue"
        x: row.width - Ui.space4 - width
        y: frame.topPadding
        width: Math.max(56, valueLabel.implicitWidth + Ui.space5)
        height: 28
        radius: height / 2
        color: valueSlider.pressed ? Ui.accent : Ui.accentSoft
        Behavior on color { ColorAnimation { duration: 120 } }
        Text {
            id: valueLabel
            x: (parent.width - implicitWidth) / 2
            y: (parent.height - implicitHeight) / 2
            text: row.displayValue(valueSlider.value)
            color: valueSlider.pressed ? Ui.onAccent : Ui.accent
            font.pixelSize: Ui.textCallout
            font.weight: Ui.weightBold
        }
    }

    Slider {
        id: valueSlider
        objectName: "settingSlider"
        x: Ui.space4
        y: row.sliderY
        width: Math.max(0, row.width - Ui.space8)
        from: row.spec ? row.spec.min : 0
        to: row.spec ? row.spec.max : 1
        value: row.storedValue
        stepSize: row.spec && row.spec.dots ? row.spec.step : 0
        tickMarksEnabled: row.spec ? row.spec.dots : false
        onEditingFinished: {
            var committed = Math.round(value)
            settings.setValue(row.spec.key, committed)
            valueSlider.value = committed
        }
    }

    Text {
        id: rangeMin
        x: Ui.space4
        y: valueSlider.y + valueSlider.height + Ui.space2
        text: row.spec ? row.displayValue(row.spec.min) : ""
        color: Ui.textTertiary
        font.pixelSize: Ui.textCaption
    }
    Text {
        x: row.width - Ui.space4 - implicitWidth
        y: rangeMin.y
        text: row.spec ? row.displayValue(row.spec.max) : ""
        color: Ui.textTertiary
        font.pixelSize: Ui.textCaption
    }
}
