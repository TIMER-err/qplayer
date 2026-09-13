import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// SettingSpec.SLIDER — an integer-backed slider. `dots` enables both fixed-step
// snapping and one tick dot per step; without it the thumb moves continuously
// and the final value is rounded only when the pointer is released.
ColumnLayout {
    id: row
    property var spec: null
    property int storedValue: row.spec ? settings.value(row.spec.key) : 0
    onStoredValueChanged: valueSlider.value = storedValue
    spacing: 4

    function displayValue(value) {
        if (!row.spec) return ""
        var stored = Math.round(value)
        var shown = row.spec.scale > 1
                ? (stored / row.spec.scale).toFixed(2)
                : ("" + stored)
        return shown + row.spec.unit
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        SettingTitle { text: row.spec ? i18n.t(row.spec.title) : "" }
        Text {
            text: row.displayValue(valueSlider.value)
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.bodyLarge.family
            font.pixelSize: Theme.typography.bodyLarge.size
        }
    }

    Slider {
        id: valueSlider
        Layout.fillWidth: true
        Layout.topMargin: 2
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

    SettingDesc { text: row.spec ? i18n.t(row.spec.desc) : "" }
}
