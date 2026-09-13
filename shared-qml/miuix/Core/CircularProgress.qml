import QtQuick
import miuix.Core
Item {
    id: control
    
    property real value: 0.0 // 0.0 to 1.0
    property bool indeterminate: false
    property bool showTrack: true
    property bool wavy: false
    property real strokeWidth: 4
    readonly property real progress: Math.max(0, Math.min(1, value))
    
    implicitWidth: 30
    implicitHeight: 30
    
    property var _colors: Theme.color
    
    // Explicit bindings to trigger repaint on theme change
    property color _primaryColor: enabled ? Theme.color.primary : Theme.color.disabledPrimarySlider
    property color _surfaceContainerHighestColor: Theme.color.secondaryContainer
    
    on_PrimaryColorChanged: canvas.requestPaint()
    on_SurfaceContainerHighestColorChanged: canvas.requestPaint()
    
    // Internal animation properties
    property real _rotation: 0
    property real _arcOffset: 0
    property real _arcSweep: 30
    
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
            _arcSweep = 30
        }
    }
    
    onValueChanged: canvas.requestPaint()
    onShowTrackChanged: canvas.requestPaint()
    onWavyChanged: canvas.requestPaint()
    on_WavyPhaseChanged: canvas.requestPaint()

    NumberAnimation on _rotation {
        from: 0; to: 360; duration: 1000
        loops: Animation.Infinite
        running: control.indeterminate && control.visible && !control.wavy
    }
    SequentialAnimation {
        running: control.indeterminate && control.visible && !control.wavy
        loops: Animation.Infinite
        NumberAnimation { target: control; property: "_arcSweep"; from: 30; to: 120; duration: 800 }
        NumberAnimation { target: control; property: "_arcSweep"; from: 120; to: 30; duration: 800 }
    }
    onStrokeWidthChanged: canvas.requestPaint()
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
            var lineWidth = Math.max(0, Math.min(control.strokeWidth, Math.min(w, h)));
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
                        end = control.progress * Math.PI * 2;
                        drawWavyArc(0, end, control._primaryColor);
                    }
                }
                
            } else {
                // Standard Implementation
                // Draw Track (only for determinate)
                if (control.showTrack) {
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
                    {
                        startAngle = -Math.PI / 2; // -90 degrees (12 o'clock)
                        endAngle = startAngle + ((0.1 + 359.9 * control.progress) * Math.PI / 180);
                        ctx.arc(centerX, centerY, radius, startAngle, endAngle, false);
                        ctx.stroke();
                    }
                }
            }
        }
    }
}

