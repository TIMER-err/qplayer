import QtQuick
import miuix.Core

// ── B1: the touch-following specular highlight, QML-material path ────────────
//
// From Kyant0's AndroidLiquidGlass (Apache-2.0),
// `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt:44-88`.
// While a finger is down their glass catches a shine at the pointer. Two draws:
//
//   InteractiveHighlight.kt:65-68
//       drawRect(Color.White.copy(0.08f * progress), blendMode = BlendMode.Plus)
//   InteractiveHighlight.kt:44-54 + :72-77   (the runtime-shader path)
//       half4 main(float2 coord) {
//           float dist = distance(coord, position);
//           float intensity = smoothstep(radius, radius * 0.5, dist);
//           return color * intensity;
//       }
//       color  = Color.White.copy(0.15f * progress)
//       radius = size.minDimension * 1.5f
//       position = (x, y).coerceIn(0, size)          // clamped into the surface
//
// and, on a device with no runtime shaders (`shader == null`), the single
// fallback draw at InteractiveHighlight.kt:84-87:
//
//       drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)
//
// ── the approximations, both stated ─────────────────────────────────────────
// 1. **`smoothstep` with no shader.** A QML `Rectangle` here fills a `Gradient`
//    on a uniform-radius rect and nothing else — there is no `RadialGradient`
//    element, and `Canvas.createRadialGradient` (which `AmbientBackdrop.qml`
//    does have) would have to be re-painted on every pointer move, i.e. exactly
//    the per-frame pass the brief forbids. So the radial falloff is drawn as two
//    concentric discs instead. Their plateau is `dist <= radius * 0.5`, i.e. a
//    disc of diameter `size.minDimension * 1.5`, and the ramp reaches zero at
//    `dist = radius`, i.e. diameter `size.minDimension * 3`. Setting the outer
//    disc to 35% of the plateau's alpha and the inner one to 65% reproduces the
//    shader's area-weighted mean (the true mean is 62.5% plateau / 37.5% ring;
//    two stacked alphas composite to 1-(1-0.65c)(1-0.35c) ≈ 0.77c over the
//    plateau and 0.35c over the ring). Stated plainly: this is a two-step
//    staircase standing in for a smoothstep, not the smoothstep.
// 2. **Additive blending.** Their shine is `BlendMode.Plus`. The engine has no
//    blend mode on `Rectangle`, so the shine is stacked as translucent white
//    over the material instead of added to it — which reads the same on a light
//    material and slightly weaker on a dark one.
Item {
    id: shine
    clip: true

    // `InteractiveHighlight.pressProgress` (0..1) — everything here scales with
    // it and nothing is drawn at 0.
    property real progress: 0
    // The pointer, already in this item's coordinates. Clamped into the surface
    // exactly as their shader uniforms are.
    property real touchX: 0
    property real touchY: 0
    property bool touchActive: false
    // Their `Color.White.copy(0.08f * progress)` over the whole surface. Only
    // true when the caller's material really is their shader path; the honest
    // fallback for a QML material is the single 0.25 draw below.
    property bool fullSurfaceWash: false

    readonly property real _minDimension: Math.min(shine.width, shine.height)
    readonly property real _cx: Math.max(0, Math.min(shine.width, shine.touchX))
    readonly property real _cy: Math.max(0, Math.min(shine.height, shine.touchY))
    readonly property bool _on: shine.progress > 0.001 && shine.width > 0 && shine.height > 0

    // The shader path's flat term: White @ 0.08 * progress.
    Rectangle {
        objectName: "glassShineFallback"
        anchors.fill: parent
        visible: shine._on && shine.fullSurfaceWash
        color: "#FFFFFF"
        opacity: 0.08 * shine.progress
    }

    // The no-shader fallback: White @ 0.25 * progress, the whole surface.
    Rectangle {
        anchors.fill: parent
        visible: shine._on && !shine.fullSurfaceWash
        color: "#FFFFFF"
        opacity: 0.25 * shine.progress
    }

    // The radial shine. `visible` rather than `opacity` alone so nothing is
    // rasterised while the finger is away.
    Rectangle {
        objectName: "glassShineOuter"
        visible: shine._on && shine.touchActive
        width: shine._minDimension * 3.0
        height: width
        radius: width / 2
        x: shine._cx - width / 2
        y: shine._cy - height / 2
        color: "#FFFFFF"
        opacity: 0.15 * shine.progress * 0.35
    }
    Rectangle {
        objectName: "glassShineInner"
        visible: shine._on && shine.touchActive
        width: shine._minDimension * 1.5
        height: width
        radius: width / 2
        x: shine._cx - width / 2
        y: shine._cy - height / 2
        color: "#FFFFFF"
        opacity: 0.15 * shine.progress * 0.65
    }
}
