import QtQuick
import QtQuick.Layouts
import md3.Core
Item {
    id: control

    property bool running: true
    property bool withContainer: false
    property color color: Theme.color.primary
    property color containerColor: Theme.color.surfaceContainerHighest
    property real size: 48

    onColorChanged: canvas.requestPaint()
    // Force a repaint when the blob (re)starts: the animation normally drives paints
    // via rotationValue changes, but the first trigger after the Canvas becomes visible
    // could be dropped, leaving only the container ring.
    onRunningChanged: if (running) canvas.requestPaint()

    implicitWidth: size
    implicitHeight: size

    // Container
    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: control.containerColor
        visible: control.withContainer
        opacity: control.withContainer ? 1.0 : 0.0
    }

    // Canvas for Expressive Morphing Shape
    Canvas {
        id: canvas
        anchors.fill: parent
        anchors.margins: control.withContainer ? parent.width * 0.25 : parent.width * 0.1
        visible: control.running

        // Animation State
        property real rotationValue: 0
        property real morphProgress: 0 
        
        // Configuration
        // Sequence: 10 -> 9 -> 5 -> 2 -> 8 -> 4 -> 2
        property var lobeSequence: [10, 9, 5, 2, 8, 4, 2]
        readonly property int sequenceLength: 7
        
        // Animation Loop
        ParallelAnimation {
            running: control.running
            loops: Animation.Infinite
            
            // 1. Continuous Rotation
            NumberAnimation {
                target: canvas
                property: "rotationValue"
                from: 0
                to: 360
                duration: 3000 // Faster rotation for energetic feel
                easing.type: Easing.Linear
                loops: Animation.Infinite
            }
            
            // 2. Shape Morphing Cycle
            // "Rapidly change shape -> Hold -> Repeat"
            // Direct morph between shapes without intermediate circles
            SequentialAnimation {
                loops: Animation.Infinite
                
                // Step 0 -> 1 (10 -> 9)
                PauseAnimation { duration: 800 }
                NumberAnimation { target: canvas; property: "morphProgress"; from: 0; to: 1; duration: 600; easing.type: Easing.InOutQuart }
                
                // Step 1 -> 2 (9 -> 5)
                PauseAnimation { duration: 800 }
                NumberAnimation { target: canvas; property: "morphProgress"; from: 1; to: 2; duration: 600; easing.type: Easing.InOutQuart }
                
                // Step 2 -> 3 (5 -> 2)
                PauseAnimation { duration: 800 }
                NumberAnimation { target: canvas; property: "morphProgress"; from: 2; to: 3; duration: 600; easing.type: Easing.InOutQuart }
                
                // Step 3 -> 4 (2 -> 8)
                PauseAnimation { duration: 800 }
                NumberAnimation { target: canvas; property: "morphProgress"; from: 3; to: 4; duration: 600; easing.type: Easing.InOutQuart }
                
                // Step 4 -> 5 (8 -> 4)
                PauseAnimation { duration: 800 }
                NumberAnimation { target: canvas; property: "morphProgress"; from: 4; to: 5; duration: 600; easing.type: Easing.InOutQuart }
                
                // Step 5 -> 6 (4 -> 2)
                PauseAnimation { duration: 800 }
                NumberAnimation { target: canvas; property: "morphProgress"; from: 5; to: 6; duration: 600; easing.type: Easing.InOutQuart }
                
                // Step 6 -> 7 (2 -> 10 [Loop])
                PauseAnimation { duration: 800 }
                NumberAnimation { target: canvas; property: "morphProgress"; from: 6; to: 7; duration: 600; easing.type: Easing.InOutQuart }
                
                ScriptAction { script: canvas.morphProgress = 0 }
            }
        }

        onRotationValueChanged: canvas.requestPaint()
        onMorphProgressChanged: canvas.requestPaint()
        onVisibleChanged: if (visible) requestPaint()
        Component.onCompleted: if (control.running) requestPaint()

        onPaint: {
            var ctx = getContext("2d");
            ctx.reset();
            
            var w = width;
            var h = height;
            var cx = w / 2;
            var cy = h / 2;
            var maxR = Math.min(w, h) / 2;

            ctx.translate(cx, cy);
            ctx.rotate(rotationValue * Math.PI / 180);

            // Morph Logic
            var t = morphProgress;
            var idx1 = Math.floor(t) % sequenceLength;
            var idx2 = (idx1 + 1) % sequenceLength;
            var factor = t - Math.floor(t);
            
            // Draw Blob
            ctx.beginPath();
            ctx.fillStyle = control.color;
            
            var points = 100; // Increased resolution for higher lobe counts (10 lobes needs more points)
            var step = (Math.PI * 2) / points;

            for (var i = 0; i <= points; i++) {
                var angle = i * step;
                
                // Get Radius for current angle for both shapes
                var r1 = getRadius(idx1, angle, maxR);
                var r2 = getRadius(idx2, angle, maxR);
                
                // Interpolate radius
                var r = r1 + (r2 - r1) * factor;
                
                var x = Math.cos(angle) * r;
                var y = Math.sin(angle) * r;
                
                if (i === 0) {
                    ctx.moveTo(x, y);
                } else {
                    ctx.lineTo(x, y);
                }
            }
            
            ctx.closePath();
            ctx.fill();
        }
        
        // Shape Geometry Function
        function getRadius(idx, angle, maxR) {
            var lobes = lobeSequence[idx];
            var baseR = maxR * 0.85; // Base size
            var varR = maxR * 0.15;  // Variation amplitude
            
            // Standard lobe formula: base + var * sin(lobes * angle)
            // For odd lobes, this naturally works.
            // For even lobes (2, 4, 8, 10), it also works.
            
            return baseR + varR * Math.sin(lobes * angle);
        }
    }
}

