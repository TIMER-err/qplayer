// QPlayer playback progress, retained from the pre-Miuix interface.
// Source attribution: LYRIC-PROGRESS-NOTICE.md.
import QtQuick
import QtQuick.Effects
import miuix.Core
Item {
    id: control

    property real value: 0.0
    property bool indeterminate: false
    property bool wavy: false
    // Kept separate from value so the one-off indeterminate -> determinate handoff
    // can catch up smoothly. Normal playback updates still copy value directly — a
    // permanent Behavior would restart on every 200ms clock tick and lag/freeze.
    property real _displayValue: Math.max(0.0, Math.min(1.0, value))
    property bool _catchingUp: false

    function beginDeterminateTransition() {
        catchUpAnim.stop()
        var targetValue = Math.max(0.0, Math.min(1.0, control.value))
        control._displayValue = 0.0
        if (targetValue <= 0.0) {
            control._catchingUp = false
            return
        }
        control._catchingUp = true
        catchUpAnim.to = targetValue
        catchUpAnim.restart()
    }

    // Enter indeterminate immediately. Both renderers keep their visual latch on exit
    // until their active sweep animation has naturally completed.
    onIndeterminateChanged: {
        if (indeterminate) {
            catchUpAnim.stop()
            control._catchingUp = false
            if (control.wavy)
                wavyCanvas._indet = true
            else {
                straightIndicator.beginSweep()
            }
        } else if (!control.wavy)
            straightIndicator.requestFinish()
    }
    onValueChanged: {
        var visualIndeterminate = control.wavy
                ? wavyCanvas._indet : straightIndicator._indet
        if (!control.indeterminate && !visualIndeterminate && !control._catchingUp)
            control._displayValue = Math.max(0.0, Math.min(1.0, control.value))
    }
    onWavyChanged: {
        // Switching styles must not leave the hidden Canvas's deferred state in
        // charge of the straight bar. Synchronize both representations immediately.
        if (!control.wavy) {
            wavyCanvas._indet = false
            if (control.indeterminate)
                straightIndicator.beginSweep()
            else
                straightIndicator.cancelSweep()
            if (!control.indeterminate && !control._catchingUp)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value))
        } else {
            straightIndicator.cancelSweep()
            wavyCanvas._indet = control.indeterminate
            if (!control.indeterminate && !control._catchingUp)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value))
        }
    }
    onVisibleChanged: {
        // A hidden straight bar does not keep restarting cycles. If it becomes
        // visible while loading is still active, resume with a fresh pair only
        // after both previously-running cycles have naturally stopped.
        if (control.visible && !control.wavy && control.indeterminate
                && straightIndicator._indet
                && (straightIndicator._bar1Done || straightIndicator._bar2Done))
            straightIndicator.beginSweep()
    }

    NumberAnimation {
        id: catchUpAnim
        target: control
        property: "_displayValue"
        duration: 450
        easing.type: Easing.OutCubic
        onFinished: {
            control._catchingUp = false
            if (!control.indeterminate)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value))
        }
    }

    implicitWidth: 200
    implicitHeight: wavy ? 16 : 4

    property var _colors: Theme.color

    // Animation control
    // Standard Linear Progress
    Rectangle {
        id: track
        anchors.fill: parent
        visible: !control.wavy
        color: _colors.surfaceContainerHighest
        radius: height / 2
        clip: true

        // Determinate Indicator
        Rectangle {
            visible: !straightIndicator._indet
            height: parent.height
            // The player publishes progress about every 200ms. A 200ms Behavior
            // here gets restarted by every update and can indefinitely trail/freeze
            // under qml4j, so use the live value just like the wavy canvas below.
            width: parent.width * control._displayValue
            color: _colors.primary
            radius: height / 2
        }

        // Item.clip is rectangular in qml4j, so it cannot preserve the track's
        // rounded outline when an animated bar crosses an edge. Mask the unchanged
        // MD3 animation output with the actual rounded-track shape instead.
        Rectangle {
            id: straightMask
            anchors.fill: parent
            radius: track.radius
            color: "black"
            visible: false
        }

        // Indeterminate Indicator
        Item {
            id: straightIndicator
            anchors.fill: parent
            visible: false

            // Like the wave, keep a visual indeterminate latch after loading ends.
            // Each of the two original animations completes its current independent
            // cycle; only after both finish do we grow the real progress from zero.
            property bool _indet: false
            property bool _bar1Done: true
            property bool _bar2Done: true

            function beginSweep() {
                bar1Anim.stop()
                bar2Anim.stop()
                bar1.x = -width
                bar1.width = 0.0
                bar2.x = -width
                bar2.width = 0.0
                _indet = true
                if (control.visible && !control.wavy) {
                    _bar1Done = false
                    _bar2Done = false
                    bar1Anim.restart()
                    bar2Anim.restart()
                } else {
                    _bar1Done = true
                    _bar2Done = true
                }
            }

            Component.onCompleted: {
                if (control.indeterminate && !control.wavy)
                    beginSweep()
            }

            function cancelSweep() {
                bar1Anim.stop()
                bar2Anim.stop()
                _bar1Done = true
                _bar2Done = true
                _indet = false
            }

            function barFinished(firstBar) {
                if (control.indeterminate && _indet && !control.wavy
                        && control.visible) {
                    if (firstBar)
                        bar1Anim.restart()
                    else
                        bar2Anim.restart()
                    return
                }
                if (firstBar)
                    _bar1Done = true
                else
                    _bar2Done = true
                finishIfReady()
            }

            function finishIfReady() {
                if (!_indet || control.indeterminate || !_bar1Done || !_bar2Done)
                    return
                _indet = false
                control.beginDeterminateTransition()
            }

            function requestFinish() {
                if (!_indet)
                    return
                if (!bar1Anim.running)
                    _bar1Done = true
                if (!bar2Anim.running)
                    _bar2Done = true
                finishIfReady()
            }

            // First bar
            Rectangle {
                id: bar1
                height: parent.height
                color: _colors.primary
                radius: height / 2

                SequentialAnimation {
                    id: bar1Anim
                    onFinished: straightIndicator.barFinished(true)

                    ParallelAnimation {
                        NumberAnimation { target: bar1; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar1; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar1; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }

            // Second bar (delayed)
            Rectangle {
                id: bar2
                height: parent.height
                color: _colors.primary
                radius: height / 2

                SequentialAnimation {
                    id: bar2Anim
                    onFinished: straightIndicator.barFinished(false)

                    PauseAnimation { duration: 1000 }

                    ParallelAnimation {
                        NumberAnimation { target: bar2; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar2; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar2; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }
        }

        MultiEffect {
            anchors.fill: parent
            source: straightIndicator
            visible: straightIndicator._indet
            maskEnabled: true
            maskSource: straightMask
        }
    }

    // Wavy Linear Progress
    Canvas {
        id: wavyCanvas
        visible: control.wavy
        anchors.fill: parent
        antialiasing: true
        renderTarget: Canvas.FramebufferObject
        renderStrategy: Canvas.Threaded

        // Trigger repaint when dependencies change
        property color trackColor: control._colors.surfaceContainerHighest
        property color activeColor: control._colors.primary
        // Normal playback follows value directly; only the one-off loading handoff
        // animates _displayValue (see catchUpAnim above).
        property real progress: control._displayValue

        onTrackColorChanged: requestPaint()
        onActiveColorChanged: requestPaint()
        onProgressChanged: requestPaint()

        property real phase: 0.0
        // Latched indeterminate state: follows control.indeterminate up immediately, but
        // defers the switch back to determinate to the next wave-cycle boundary so a bar
        // that stops loading mid-sweep finishes the sweep instead of snapping to value.
        property bool _indet: control.indeterminate
        property real _lastPhase: 0.0

        // Animation for phase shift (make it flow). Runs for determinate too so the
        // wave visibly flows and the Canvas keeps repainting (onPhaseChanged), instead
        // of freezing on the first paint with only the track drawn.
        NumberAnimation on phase {
            running: control.wavy && control.visible
            from: 0
            to: Math.PI * 2
            duration: 1000 // 1Hz wave frequency
            loops: Animation.Infinite
        }

        onPaint: {
            var ctx = getContext("2d");
            ctx.reset();

            var w = width;
            var h = height;
            var cy = h / 2;
            var lw = 4;
            ctx.lineWidth = lw;
            ctx.lineCap = "round";
            ctx.lineJoin = "round";
            // Inset by half the stroke so the round end-caps fall inside the canvas
            // instead of being clipped; clamp amplitude so peaks+caps stay in bounds.
            var m = lw / 2 + 1;
            var x0 = m, x1 = w - m;
            var amplitude = Math.min(h / 4, h / 2 - lw / 2);
            var frequency = 0.1; // Wave density
            // qml4j 0.2.19+ already fixes this wave's jagged look at the engine
            // level (Canvas 2D antialiasing + tighter offscreen-backing scale
            // quantization, TIMER-err/qml4j#2) — no need for a finer sampling step
            // here; halving it back to 2px avoids doubling this Canvas's per-frame
            // trig/lineTo cost for no visual gain.
            var step = 2;

            // Track (inactive)
            ctx.beginPath();
            ctx.strokeStyle = trackColor;
            ctx.appendSineWave(x0, x1, step, cy, amplitude, frequency, phase, false);
            ctx.stroke();

            // Indicator (active)
            ctx.beginPath();
            ctx.strokeStyle = activeColor;
            if (wavyCanvas._indet) {
                var indetProgress = (phase % (Math.PI * 2)) / (Math.PI * 2); // 0..1
                var span = x1 - x0;
                var barWidth = span * 0.5;
                var startX = x0 + (span + barWidth) * indetProgress - barWidth;
                var endXi = startX + barWidth;
                // Keep the same 2px sampling lattice as the old filtered loop.
                var firstXi = x0 + Math.max(0, Math.ceil((startX - x0) / step)) * step;
                ctx.appendSineWave(firstXi, Math.min(x1, endXi), step,
                                   cy, amplitude, frequency, phase, false);
                ctx.stroke();
            } else {
                var endX = x0 + (x1 - x0) * Math.max(0, Math.min(1, progress));
                ctx.appendSineWave(x0, endX, step, cy, amplitude,
                                   frequency, phase, true);
                ctx.stroke();
            }
        }

        onPhaseChanged: {
            requestPaint();
            // phase wraps 2π→0 at each loop boundary: latch the deferred indeterminate
            // switch there so the sweep completes before the bar returns to determinate.
            // The sweep has fully left the right edge at this point, so grow the real
            // progress from zero instead of snapping straight to the current position.
            if (phase < _lastPhase) {
                var wasIndeterminate = _indet
                _indet = control.indeterminate
                if (wasIndeterminate && !_indet)
                    control.beginDeterminateTransition()
            }
            _lastPhase = phase;
        }
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }
}
