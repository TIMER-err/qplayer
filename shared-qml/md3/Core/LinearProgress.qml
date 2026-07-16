import QtQuick
import md3.Core
Item {
    id: control
    
    property real value: 0.0
    property bool indeterminate: false
    property bool wavy: false
    // Enter indeterminate immediately; the wavy canvas defers the exit to a cycle boundary.
    onIndeterminateChanged: if (indeterminate) wavyCanvas._indet = true
    
    implicitWidth: 200
    implicitHeight: wavy ? 16 : 4
    
    property var _colors: Theme.color
    
    // Animation control
    property bool _initialized: false
    Component.onCompleted: _initialized = true
    
    property real _visualValue: Math.max(0.0, Math.min(1.0, control.value))
    Behavior on _visualValue {
        enabled: control._initialized
        NumberAnimation { duration: 200; easing.type: Easing.OutQuad }
    }
    
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
            visible: !control.indeterminate
            height: parent.height
            width: parent.width * control._visualValue
            color: _colors.primary
            radius: height / 2
        }
        
        // Indeterminate Indicator
        Item {
            anchors.fill: parent
            visible: control.indeterminate
            
            // First bar
            Rectangle {
                id: bar1
                height: parent.height
                color: _colors.primary
                radius: height / 2
                
                SequentialAnimation {
                    running: control.indeterminate && control.visible && !control.wavy
                    loops: Animation.Infinite
                    
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
                    running: control.indeterminate && control.visible && !control.wavy
                    loops: Animation.Infinite
                    
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
        // Raw value, NOT _visualValue: its Behavior restarts every frame when the
        // caller updates value per-frame (smooth source), which freezes it. Callers
        // that want easing should smooth the value they pass in.
        property real progress: control.value

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
            // The real fix for this wave's jagged look was an engine-side bug
            // (qml4j Canvas 2D paints never turned on antialiasing, plus the
            // offscreen-backing resolution was quantized too coarsely for common
            // device scales — see TIMER-err/qml4j#2). Until qplayer picks up a
            // qml4j release with that fix, a finer sampling step at least halves
            // the facet size; cheap enough for a ~200px-wide bar.
            var step = 1;

            // Track (inactive)
            ctx.beginPath();
            ctx.strokeStyle = trackColor;
            for (var x = x0; x <= x1; x += step) {
                var y = cy + amplitude * Math.sin((x * frequency) + phase);
                if (x === x0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
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
                var begun = false;
                for (var xi = x0; xi <= x1; xi += step) {
                    if (xi >= startX && xi <= endXi) {
                        var yi = cy + amplitude * Math.sin((xi * frequency) + phase);
                        if (!begun) { ctx.moveTo(xi, yi); begun = true; }
                        else ctx.lineTo(xi, yi);
                    }
                }
                ctx.stroke();
            } else {
                var endX = x0 + (x1 - x0) * Math.max(0, Math.min(1, progress));
                var started = false;
                for (var xd = x0; xd < endX; xd += step) {
                    var yd = cy + amplitude * Math.sin((xd * frequency) + phase);
                    if (!started) { ctx.moveTo(xd, yd); started = true; }
                    else ctx.lineTo(xd, yd);
                }
                // End exactly at endX (not the last sampling step) so the active tip
                // advances continuously instead of snapping to the sampling grid.
                if (started) {
                    var yEnd = cy + amplitude * Math.sin((endX * frequency) + phase);
                    ctx.lineTo(endX, yEnd);
                }
                ctx.stroke();
            }
        }
        
        onPhaseChanged: {
            requestPaint();
            // phase wraps 2π→0 at each loop boundary: latch the deferred indeterminate
            // switch there so the sweep completes before the bar returns to determinate.
            if (phase < _lastPhase) _indet = control.indeterminate;
            _lastPhase = phase;
        }
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }
}

