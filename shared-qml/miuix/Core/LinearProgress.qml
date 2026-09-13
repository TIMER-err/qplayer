import QtQuick
import miuix.Core

Item {
    id: control
    property real value: 0
    property bool indeterminate: false
    property bool wavy: false
    property color foregroundColor: enabled ? Theme.color.primary : Theme.color.disabledPrimarySlider
    property color backgroundColor: Theme.color.secondaryContainer
    readonly property real progress: Math.max(0, Math.min(1, value))
    property real _phase: 0
    implicitWidth: 200
    implicitHeight: wavy ? 12 : 6

    NumberAnimation on _phase {
        from: 0; to: 1; duration: 1250
        loops: Animation.Infinite
        running: control.indeterminate && control.visible
    }
    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: control.backgroundColor
        visible: !control.wavy
    }
    Rectangle {
        objectName: "miuixLinearProgressFill"
        visible: !control.wavy && !control.indeterminate
        height: control.height
        width: Math.min(control.width, height + Math.max(0, control.width - height) * control.progress)
        radius: height / 2
        color: control.foregroundColor
    }
    Rectangle {
        visible: !control.wavy && control.indeterminate
        x: control._phase * control.width
        width: Math.min(0.45, 1 - control._phase) * control.width
        height: control.height
        radius: height / 2
        color: control.foregroundColor
    }
    Rectangle {
        visible: !control.wavy && control.indeterminate && control._phase > 0.55
        width: Math.max(0, control._phase - 0.55) * control.width
        height: control.height
        radius: height / 2
        color: control.foregroundColor
    }
    onProgressChanged: wave.requestPaint()
    on_PhaseChanged: if (wavy) wave.requestPaint()
    onForegroundColorChanged: wave.requestPaint()
    onBackgroundColorChanged: wave.requestPaint()
    onWavyChanged: wave.requestPaint()
    onIndeterminateChanged: wave.requestPaint()
    Canvas {
        id: wave
        anchors.fill: parent
        visible: control.wavy
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()
            ctx.lineWidth = Math.min(4, height / 3)
            ctx.lineCap = "round"
            for (var pass = 0; pass < 2; pass++) {
                ctx.beginPath()
                ctx.strokeStyle = pass === 0 ? control.backgroundColor : control.foregroundColor
                var start = pass === 1 && control.indeterminate ? control._phase : 0
                var end = pass === 0 ? 1 : (control.indeterminate ? Math.min(1, start + 0.45) : control.progress)
                for (var x = 2 + (width - 4) * start; x <= 2 + (width - 4) * end; x += 2) {
                    var y = height / 2 + Math.sin(x / 8 - control._phase * Math.PI * 2) * Math.max(0, height / 2 - 3)
                    if (x === 2 + (width - 4) * start) ctx.moveTo(x, y)
                    else ctx.lineTo(x, y)
                }
                ctx.stroke()
            }
        }
    }
}
