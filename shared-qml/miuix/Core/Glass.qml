import QtQuick
import QtQuick.Effects
import miuix.Core

// Liquid-glass material.
//
// Apple's materials are a *functional layer*: translucent chrome floating over
// content, defined by (1) what shows through, (2) the light the material catches on
// its top edge, (3) the hairline that separates it from the page, and (4) a shadow
// that lifts it. qml4j has no backdrop filter — MultiEffect in this engine only
// implements masking and shadows — so the blur itself is not reproducible here.
// What IS reproducible is everything around it, and that is what this component
// builds, in the same layer order a real material uses:
//
//   shadow   soft lift, drawn outside the shape
//   base     the material's own translucency over whatever is behind it
//   sheen    the vertical light falloff: brighter at the top, gone by mid-height
//   rim      the outer hairline that separates the material from the page
//   glow     the thin bright halo a thick glass edge refracts (optional)
//
// The base shape is a squircle (SmoothRectangle, the same continuous-corner path
// miuix-squircle draws), so a floating surface has Apple's corner silhouette rather
// than a circular arc. The sheen band uses a plain rounded rectangle because qml4j
// only fills *gradients* on a uniform-radius rect — inset by the rim width, the two
// silhouettes agree to within a fraction of a pixel along the straight edges.
Item {
    id: glass

    // 0 ultraThin · 1 thin · 2 regular · 3 thick · 4 chrome
    // Weight encodes hierarchy: thin for a small chip, thick for a sheet, chrome
    // for the bars that frame the whole window.
    property int variant: 2
    property real radius: Theme.metrics.sheetRadius
    property color tint: {
        switch (variant) {
            case 0: return Theme.material.ultraThin
            case 1: return Theme.material.thin
            case 3: return Theme.material.thick
            case 4: return Theme.material.chrome
            default: return Theme.material.regular
        }
    }
    // A surface may override the material outright (e.g. an accent-tinted sheet).
    property bool tintOverride: false
    property color tintColor: tint
    property bool sheenVisible: true
    property bool rimVisible: true
    property bool shadowVisible: false
    property real shadowElevation: 10
    property real shadowOpacity: 0.18
    property bool glowVisible: false
    // Cheaper silhouette for small controls (a chip, a segmented segment): a circular
    // arc instead of the continuous-corner path.
    property bool squircle: true
    property real rimWidth: 1
    // The desktop host composites this pane itself (backdrop snapshot, GPU blur,
    // refraction shader, rim and sheen). Everything below then draws NOTHING, because
    // a material drawn twice is twice as opaque as the design says it is.
    property bool hostRendered: false
    readonly property bool _draw: !hostRendered

    // ── B1: the touch-following specular highlight ──────────────────────────
    // `pressProgress` is the reference's `InteractiveHighlight.pressProgress`
    // (or `DampedDragAnimation.pressProgress`) — 0..1, on screen right now,
    // normally fed from a `GlassPress`. Everything the press scales reads it, so
    // one number drives the shine, the rim highlight, the lift, the inner shadow
    // and the wash together, and a surface can never end up half-pressed.
    property real pressProgress: 0
    // Their shine only exists where a finger is: `touchActive` is the boolean
    // gate, `touchX`/`touchY` are the pointer in this surface's coordinates.
    property bool interactive: false
    property bool touchActive: false
    property real touchX: 0
    property real touchY: 0
    // Their runtime-shader path adds a flat `White @ 0.08 * progress` over the
    // whole surface on top of the radial shine; the no-shader fallback is a
    // single `White @ 0.25 * progress`. The host compositor draws the real
    // specular, so a pane it owns wants neither — `interactive` is what turns
    // the QML fallback on, and `shineFullSurface` picks which of the two it is.
    property bool shineFullSurface: false

    // ── B2: the press-driven lens, on every glass control ──────────────────
    // From their `components/LiquidBottomTabs.kt:246-265` (the indicator) and
    // `LiquidSlider.kt:168-195` / `LiquidToggle.kt:158-185` (the same set at
    // smaller radii). All three default to 0 = off, so a surface that does not
    // opt in is drawn exactly as it was; a control sets them and they scale from
    // 0 to their resting value with `pressProgress`:
    //
    //   lens(10dp * progress, 14dp * progress)     -> host: glassLens/glassRefraction
    //   Highlight.Default.copy(alpha = progress)   -> pressRimHighlight (rest 0.5)
    //   Shadow(alpha = progress)                   -> pressShadowAlpha (rest 0.1)
    //   InnerShadow(radius = 8dp * progress,       -> pressInnerShadowRadius (rest 8)
    //               alpha  = progress)
    //
    // `Highlight.Default` is `White @ 0.5` plus a 1dp stroke (Highlight.kt:11-20
    // and HighlightStyle.kt:46-49: width 0.5dp, blurRadius width/2, angle 45,
    // falloff 1); `Shadow.Default` is `Black @ 0.1`, radius 24dp, offset
    // (0, radius/6); `InnerShadow.Default` is `Black @ 0.15`, radius 24dp,
    // offset (0, radius). Only the parts a QML material can draw are here — the
    // refraction band itself is the host shader's, and its numbers belong on the
    // pane node, not on this component.
    property real pressRimHighlight: 0
    property real pressShadowAlpha: 0
    property real pressInnerShadowRadius: 0

    // ── B3: the material wash cross-fades while pressed ────────────────────
    // Their `onDrawSurface` (LiquidBottomTabs.kt:277-281) is
    //     drawRect(Black/White @ 0.1, alpha = 1 - progress)
    //     drawRect(Black @ 0.03 * progress)
    // i.e. the container colour is a *wash* that gets out of the way as the real
    // glass arrives, and 3% black goes down in its place.
    //
    // `washWeight` is how much of the resting tint is that wash rather than the
    // material's own body. Their indicator is a wash, so it sets 1.0; a chrome
    // material must not, or pressing the top bar would fade the bar itself out
    // and let the page show through it, which is the opposite of the "settle
    // into a real material" read the rule is for. That is a deliberate
    // deviation, not an oversight: default 0.35, controls pass 1.0.
    property real washWeight: 0.35
    // `Black @ 0.03 * progress` — applied at every `washWeight`, because it is
    // the half of the rule that makes a held surface *thicken*.
    readonly property real pressDarken: 0.03 * pressProgress

    // ── B4: scroll-driven glass ────────────────────────────────────────────
    // As content slides under the surface the material thickens to iOS 27's
    // uniform scroll-edge alpha, and (opt-in) its tint leans away from whatever
    // it is sitting over so it stays legible. Both come from `GlassScroll`,
    // which the scrolling containers report to — see that file for why the
    // channel is a singleton and why this costs no per-frame pass.
    property bool scrollAdaptive: true
    // On by DEFAULT, deliberately. Two of the surfaces that need this treatment
    // have their `Glass` in files this line does not own — the side rail
    // (`glassRail`, AppRail.qml:44) and the tab-bar tray (NavigationBar.qml:160)
    // — and neither passes any of these flags, so they only ever receive the
    // defaults declared here. Flipping the default is therefore how the rail and
    // the tray get the same 20pt reveal + luminance adaptation as the top bar and
    // the now-playing bar, with no property added to either file.
    //
    // Both flags are inert at scroll offset 0 (`GlassScroll.reveal` is 0, so the
    // veil keeps its own alpha and `adapt(..., k = 0)` returns the colour
    // untouched), so this only changes a surface that actually has content
    // passing under it.
    property bool luminanceAdaptive: true
    // §14 — under reduced motion a press still has to give feedback: "reduced
    // motion doesn't mean no feedback, it means a gentler, non-vestibular
    // equivalent … keep opacity/color changes that aid comprehension". Almost
    // everything this component does with the press *is* an opacity (the shine,
    // the rim highlight, the 0.03 darkening), so those are kept and only their
    // clock shortens (`MotionValue.reduced` -> `FluidMotion.reducedDuration`).
    // The one thing that genuinely *travels* is the inner shadow's radius, and
    // that is what this flag switches off: the lip appears as a static
    // transition instead of growing. The shine's position travel belongs to
    // `GlassPress.reduced`, which snaps it home instead of springing.
    property bool reduceMotion: FluidMotion.reduced
    // 1 at any real press under reduced motion, the press progress otherwise.
    readonly property real _lensGrowth: reduceMotion ? (pressProgress > 0.0005 ? 1 : 0)
                                                     : pressProgress

    property color effectiveTint: tintOverride ? tintColor : tint
    property color rimColor: Theme.material.hairline
    property color sheenColor: Theme.material.sheen
    property color glowColor: Theme.material.rimLight

    // iOS 27's uniform scroll-edge alpha — the number SettingsPage /
    // PluginSettingsPage / AccountPage already reveal to over the first 20pt of
    // scroll, so the shell's chrome thickens on the same curve they do.
    readonly property real scrollRevealAlpha: Theme.metrics.ios27.scrollEdgeFill(Theme.dark).a
    // B4, readable: how far content has scrolled under this surface, 0..1, and
    // which end of the luminance range that content is.
    readonly property real scrollReveal: GlassScroll.reveal
    readonly property bool overDark: GlassScroll.overDark
    // NaN-safe view of the same channel. `GlassScroll` is a *channel* that scrolling
    // containers report into, and one that reports a non-numeric offset leaves
    // `reveal` as NaN — which then travels into the material silently, because every
    // step of the arithmetic swallows it: `0 * NaN` is NaN, `Math.max(x, NaN)` is NaN,
    // and a NaN channel in `Qt.rgba` comes out as opaque black. Measured, in the shell
    // (not in a standalone scene): the segmented control's plate resolved
    // `_bodyColor` to **#d1000000** — a white @0.82 sheet turned into a black slab,
    // with the sheet's alpha still on it, which is why it reads as a painted black
    // chip rather than as a missing surface. A material's resting colour is not
    // allowed to depend on the channel being sane, so NaN means "nothing has
    // scrolled" (0) here and every reader below goes through this.
    readonly property real scrollRevealSafe: scrollReveal >= 0 ? scrollReveal : 0
    // The veil, once the reveal has had its say. One expression, because a `{ }`
    // block binding never re-evaluates in this engine. `Math.max` because a
    // material that is already more opaque than the scroll-edge fill must not be
    // *thinned* by content arriving underneath it.
    readonly property real _revealedAlpha: scrollAdaptive
        ? Math.max(effectiveTint.a,
                   GlassScroll.veilAlpha(effectiveTint.a, glass.scrollRevealAlpha,
                                         glass.scrollRevealSafe))
        : effectiveTint.a
    // …and once the press wash has too.
    readonly property real _bodyAlpha: _revealedAlpha * (1 - washWeight * pressProgress)
    // The luminance adaptation: over a light backdrop the material leans darker,
    // over a dark one lighter. 0.22 is a floor — enough to keep a 30%-alpha
    // material legible without turning the chrome into a different material.
    // The alpha of the veil as it is actually drawn, and the tint it is drawn
    // with: the two numbers the reveal and the press wash move.
    readonly property real veilAlpha: _bodyAlpha
    // `GlassScroll.adapt` multiplies by the channel whatever `amount` is, so a NaN
    // there poisons the tint even for a surface that asked for no adaptation at all
    // (`0 * NaN` is NaN, and a NaN channel is black — see `scrollRevealSafe`).
    function _adapt(color, amount) {
        return glass.scrollReveal >= 0 ? GlassScroll.adapt(color, amount) : color
    }
    readonly property color _bodyColor: _adapt(
        Qt.rgba(effectiveTint.r, effectiveTint.g, effectiveTint.b, _bodyAlpha),
        luminanceAdaptive ? 0.22 : 0)
    readonly property color _edgeColor: _adapt(rimColor, luminanceAdaptive ? 0.35 : 0)

    default property alias content: contentItem.data

    // B2 — `Shadow(alpha = progress)` from their indicator: the lift is part of
    // the press, not a constant. A surface with `shadowVisible` keeps its
    // resting lift and gains `pressShadowAlpha` on top of it; a surface that
    // only wants the press-time lift sets `pressShadowAlpha` alone.
    readonly property real _shadowOpacity: shadowOpacity + pressShadowAlpha * pressProgress
    // B2 — `Highlight.Default.copy(alpha = progress)`: the rim highlight rises
    // from the resting 0.55 to 0.55 + pressRimHighlight. A control passes 0.45
    // so a full press lands on 1.0, their `Highlight.Default` alpha.
    readonly property real _glowOpacity: 0.55 + glass.pressRimHighlight * glass.pressProgress

    // ---------------------------------------------------------------- shadow
    //
    // This engine's MultiEffect draws its source **and** the shadow behind it
    // (`Painter.drawShadowed` composites `ImageFilter.makeDropShadow`, and Skia's
    // DropShadow keeps the input image — only `makeDropShadowOnly` drops it). So the
    // item handed to the effect here is the material's own body, not the invisible
    // black silhouette Qt's own MultiEffect idiom uses: a black one is composited into
    // the shadow layer and then shows *through* the translucent body. Measured on the
    // segmented control's plate, a white @0.82 sheet with a lift photographed #D1D1D1
    // where the same sheet without one photographed #FAFAFB — 41/255, exactly the 18%
    // of black the body's alpha let through. The lift was darkening every translucent
    // surface instead of lifting it, which is the opposite of what a material does with
    // light, and it is why a restyled surface can still read as a filled box. The body
    // is therefore drawn *inside* this layer, and the standalone `base` below stands
    // down while the layer is up.
    readonly property bool _shadowActive: _draw && (shadowVisible || _shadowOpacity > 0.001)
    readonly property bool _drawBase: _draw && !_shadowActive
    Loader {
        anchors.fill: parent
        active: glass._shadowActive
        sourceComponent: Component {
            Item {
                // The silhouette the shadow is cast from, painted with the material's
                // own veil so the layer it lands in already carries the body. Both
                // shapes are `visible: false` — the effect draws its source whatever
                // its visibility (it goes through `drawForced`), the ordinary child
                // pass does not — and only the one `MultiEffect.source` names is ever
                // drawn, which is how the squircle and the plain silhouette can both
                // be declared here without either being painted twice.
                SmoothRectangle {
                    id: shadowSquircle
                    anchors.fill: parent
                    radius: glass.radius
                    color: glass._bodyColor
                    visible: false
                }
                Rectangle {
                    id: shadowPlain
                    anchors.fill: parent
                    radius: glass.radius
                    color: glass._bodyColor
                    visible: false
                }
                MultiEffect {
                    anchors.fill: parent
                    source: glass.squircle ? shadowSquircle : shadowPlain
                    shadowEnabled: true
                    shadowColor: Theme.color.shadow
                    shadowBlur: 0.6
                    shadowOpacity: glass._shadowOpacity
                    shadowVerticalOffset: Math.max(2, glass.shadowElevation * 0.45)
                }
            }
        }
    }

    // ------------------------------------------------------------------ body
    SmoothRectangle {
        id: base
        objectName: "glassBody"
        anchors.fill: parent
        visible: glass._drawBase && glass.squircle
        radius: glass.radius
        color: glass._bodyColor
        borderWidth: 0
    }
    Rectangle {
        anchors.fill: parent
        visible: glass._drawBase && !glass.squircle
        radius: glass.radius
        color: glass._bodyColor
    }

    // B3 — `drawRect(Color.Black.copy(alpha = 0.03f * progress))`, the second
    // half of their `onDrawSurface`. It is the half that makes a held surface
    // *thicken*: the wash above it fades away while this comes in, so the net
    // read at a full press is "the paint left and the glass arrived".
    Rectangle {
        objectName: "glassPressDarken"
        anchors.fill: parent
        visible: glass._draw && glass.pressDarken > 0.0005
        radius: glass.radius
        color: Theme.color.shadow
        opacity: glass.pressDarken
    }

    // B2 — `InnerShadow(radius = 8f.dp * progress, alpha = progress)`.
    //
    // Approximation, stated: the engine has no inner-shadow effect (MultiEffect
    // implements masking and *outer* shadows only). A blurred inset stroke is
    // therefore drawn as a band along the top inside edge — which is where
    // `InnerShadow`'s default offset of (0, +radius) puts its cast — using the
    // vertical `Gradient` a `Rectangle` here is able to fill. The falloff is
    // linear where theirs is blurred, and it is one band rather than a ring; the
    // read (a dark inner lip that deepens as the glass thickens) is the same.
    Rectangle {
        objectName: "glassPressInnerShadow"
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.leftMargin: glass.rimWidth
        anchors.rightMargin: glass.rimWidth
        anchors.topMargin: glass.rimWidth
        visible: glass._draw && glass.pressInnerShadowRadius > 0.01
                     && glass._lensGrowth > 0.001
        radius: Math.max(0, glass.radius - glass.rimWidth)
        height: Math.max(1, glass.pressInnerShadowRadius * 2 * glass._lensGrowth)
        gradient: Gradient {
            GradientStop {
                position: 0.0
                color: Qt.rgba(Theme.color.shadow.r, Theme.color.shadow.g,
                               Theme.color.shadow.b, 0.15 * glass.pressProgress)
            }
            GradientStop {
                position: 1.0
                color: Qt.rgba(Theme.color.shadow.r, Theme.color.shadow.g,
                               Theme.color.shadow.b, 0)
            }
        }
    }

    // The light falloff. Starts at the very top of the material and is gone by the
    // middle, which is what makes a flat translucent fill read as a lit surface.
    Rectangle {
        anchors.fill: parent
        anchors.margins: glass.rimWidth
        visible: glass._draw && glass.sheenVisible
        radius: Math.max(0, glass.radius - glass.rimWidth)
        gradient: Gradient {
            GradientStop { position: 0.0; color: glass.sheenColor }
            GradientStop { position: 0.55; color: Qt.rgba(glass.sheenColor.r, glass.sheenColor.g,
                                                            glass.sheenColor.b, 0) }
            GradientStop { position: 1.0; color: Qt.rgba(glass.sheenColor.r, glass.sheenColor.g,
                                                          glass.sheenColor.b, 0) }
        }
    }

    // The refraction halo: a hair's breadth of light just outside the edge.
    // B2 — it is also the QML stand-in for `Highlight.Default.copy(alpha =
    // progress)`, the rim highlight that rises with the press (the host draws
    // the real one from `glassHighlight`, and for a pane it composites this
    // layer is not drawn at all).
    Rectangle {
        objectName: "glassRimHighlight"
        anchors.fill: parent
        visible: glass._draw && glass.glowVisible
        radius: glass.radius + glass.rimWidth
        color: "transparent"
        border.width: glass.rimWidth
        border.color: glass.glowColor
        opacity: glass._glowOpacity
    }

    // ------------------------------------------------------------------- rim
    SmoothRectangle {
        anchors.fill: parent
        visible: glass._draw && glass.rimVisible && glass.squircle
        radius: glass.radius
        color: "transparent"
        borderWidth: glass.rimWidth
        borderColor: glass._edgeColor
    }
    Rectangle {
        anchors.fill: parent
        visible: glass._draw && glass.rimVisible && !glass.squircle
        radius: glass.radius
        color: "transparent"
        border.width: glass.rimWidth
        border.color: glass._edgeColor
    }

    // ------------------------------------------------------- B1: touch shine
    // Above the material and its edge, below the content: a highlight a finger
    // catches is light *on* the glass, and the title inside a toolbar must stay
    // crisp over it.
    GlassShine {
        id: shineLayer
        objectName: "glassShine"
        anchors.fill: parent
        visible: glass._draw && glass.interactive
        progress: glass.pressProgress
        touchX: glass.touchX
        touchY: glass.touchY
        touchActive: glass.touchActive
        fullSurfaceWash: glass.shineFullSurface
    }

    // --------------------------------------------------------------- content
    Item {
        id: contentItem
        anchors.fill: parent
    }
}
