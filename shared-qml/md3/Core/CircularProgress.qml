import QtQuick
import md3.Core
Item {
    id: control
    
    property real value: 0.0 // 0.0 to 1.0
    property bool indeterminate: false
    property bool showTrack: true
    property bool wavy: false
    
    implicitWidth: 48
    implicitHeight: 48
    
    property var _colors: Theme.color
    
    // Explicit bindings to trigger repaint on theme change
    property color _primaryColor: Theme.color.primary
    property color _surfaceContainerHighestColor: Theme.color.surfaceContainerHighest
    
    on_PrimaryColorChanged: canvas.requestPaint()
    on_SurfaceContainerHighestColorChanged: canvas.requestPaint()
    
    // Internal animation properties
    property real _rotation: 0
    property real _arcOffset: 0
    property real _arcSweep: 0
    
    // Wavy properties
    property real _wavyPhase: 0
    
    NumberAnimation on _wavyPhase {
        running: control.wavy && control.visible && control.indeterminate
        from: 0
        to: Math.PI * 2
        duration: 2000
        loops: Animation.Infinite
    }
    
    onIndeterminateChanged: {
        if (!indeterminate) {
            _rotation = 0
            _arcOffset = 0
            _arcSweep = 0
            canvas.requestPaint()
        } else {
            // Reset animation state when entering indeterminate mode
            _rotation = 0
            _arcOffset = 0
            _arcSweep = 10
        }
    }
    
    onValueChanged: canvas.requestPaint()
    onShowTrackChanged: canvas.requestPaint()
    onWavyChanged: canvas.requestPaint()
    on_WavyPhaseChanged: canvas.requestPaint()

    // Indeterminate Animations
    ParallelAnimation {
        running: control.indeterminate && control.visible && !control.wavy
        loops: Animation.Infinite
        
        // Continuous body rotation
        NumberAnimation {
            target: control
            property: "_rotation"
            from: 0
            to: 360
            duration: 2000
        }
        
        // Arc expansion/contraction
        SequentialAnimation {
            // Expand (Head moves fast, Tail moves slow)
            ParallelAnimation {
                NumberAnimation { target: control; property: "_arcSweep"; from: 10; to: 300; duration: 1000; easing.type: Easing.InOutCubic }
                NumberAnimation { target: control; property: "_arcOffset"; from: 0; to: 50; duration: 1000; easing.type: Easing.InOutCubic }
            }
            // Contract (Head moves slow, Tail moves fast)
            ParallelAnimation {
                NumberAnimation { target: control; property: "_arcSweep"; from: 300; to: 10; duration: 1000; easing.type: Easing.InOutCubic }
                NumberAnimation { target: control; property: "_arcOffset"; from: 50; to: 360; duration: 1000; easing.type: Easing.InOutCubic }
            }
        }
    }
    
    // Wavy Indeterminate Animation (Just rotation)
    NumberAnimation {
        target: control
        property: "_rotation"
        from: 0
        to: 360
        duration: 6000
        loops: Animation.Infinite
        running: control.indeterminate && control.visible && control.wavy
    }

    // Trigger paint on animation changes
    on_RotationChanged: canvas.requestPaint()
    on_ArcOffsetChanged: canvas.requestPaint()
    on_ArcSweepChanged: canvas.requestPaint()
    
    Canvas {
        id: canvas
        anchors.fill: parent
        antialiasing: true
        renderTarget: Canvas.FramebufferObject
        renderStrategy: Canvas.Threaded
        
        onPaint: {
            var ctx = getContext("2d");
            ctx.reset();
            
            var w = width;
            var h = height;
            var centerX = w / 2;
            var centerY = h / 2;
            var lineWidth = 4;
            var radius = Math.min(w, h) / 2 - lineWidth / 2; 
            
            ctx.lineWidth = lineWidth;
            ctx.lineCap = "round";
            
            if (control.wavy) {
                // Wavy Circle Implementation
                var waveCount = 12; // Number of petals/waves
                var waveAmplitude = 3; // Depth of wave
                var safeRadius = radius - waveAmplitude; // Prevent clipping
                
                // Helper to draw wavy arc
                var drawWavyArc = function(startAngle, endAngle, color) {
                    ctx.beginPath();
                    ctx.strokeStyle = color;
                    
                    var step = 0.05; // radian step
                    // Ensure we cover the full range
                    if (endAngle < startAngle) endAngle += Math.PI * 2;
                    
                    var first = true;
                    
                    for (var a = startAngle; a <= endAngle; a += step) {
                        var r = safeRadius + waveAmplitude * Math.sin(a * waveCount + control._wavyPhase);
                        
                        // Convert polar to cartesian (Canvas 0 is 3 o'clock, adjust to 12 o'clock)
                        var adjustedA = a - Math.PI/2 + (control._rotation * Math.PI / 180);
                        
                        var x = centerX + r * Math.cos(adjustedA);
                        var y = centerY + r * Math.sin(adjustedA);
                        
                        if (first) {
                            ctx.moveTo(x, y);
                            first = false;
                        } else {
                            ctx.lineTo(x, y);
                        }
                    }
                    ctx.stroke();
                };
                
                // Draw Track
                if (control.showTrack) {
                    drawWavyArc(0, Math.PI * 2, control._surfaceContainerHighestColor);
                }
                
                // Draw Indicator
                var start = 0;
                var end = 0;
                
                if (control.indeterminate) {
                    drawWavyArc(0, Math.PI * 1.5, control._primaryColor);
                } else {
                    // Determinate
                    if (control.value > 0) {
                        end = control.value * Math.PI * 2;
                        drawWavyArc(0, end, control._primaryColor);
                    }
                }
                
            } else {
                // Standard Implementation
                // Draw Track (only for determinate)
                if (!control.indeterminate && control.showTrack) {
                    ctx.beginPath();
                    ctx.strokeStyle = control._surfaceContainerHighestColor;
                    ctx.arc(centerX, centerY, radius, 0, 2 * Math.PI);
                    ctx.stroke();
                }
                
                // Draw Indicator
                ctx.beginPath();
                ctx.strokeStyle = control._primaryColor;
                
                var startAngle, endAngle;
                
                if (control.indeterminate) {
                    // Rotated frame + expanding/contracting arc
                    // Canvas arc angles are in radians. 0 is 3 o'clock.
                    // We want to start from 12 o'clock (-PI/2) plus rotation.
                    
                    var rotationRad = (control._rotation - 90) * Math.PI / 180;
                    var offsetRad = control._arcOffset * Math.PI / 180;
                    var sweepRad = control._arcSweep * Math.PI / 180;
                    
                    startAngle = rotationRad + offsetRad;
                    endAngle = startAngle + sweepRad;
                    
                    ctx.arc(centerX, centerY, radius, startAngle, endAngle, false);
                    ctx.stroke();
                } else {
                    // Determinate
                    if (control.value > 0) {
                        startAngle = -Math.PI / 2; // -90 degrees (12 o'clock)
                        endAngle = startAngle + (control.value * 2 * Math.PI);
                        ctx.arc(centerX, centerY, radius, startAngle, endAngle, false);
                        ctx.stroke();
                    }
                }
            }
        }
    }
}

