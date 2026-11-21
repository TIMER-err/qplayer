pragma Singleton
import QtQuick

// ── B4: scroll-driven glass, the cheap way ───────────────────────────────────
//
// In the reference (Kyant0's AndroidLiquidGlass) a glass surface knows what is
// passing under it because the backdrop is a real render layer it can sample.
// `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/destinations/
// LazyScrollContainerContent.kt` reads the scroll state and feeds it into the
// material; `AdaptiveLuminanceGlassContent.kt` derives the tint from the
// luminance of the content underneath. Nothing there samples the composited
// frame per drawing pass — the state comes from the scroll container.
//
// This project has no backdrop layer at all (qml4j's MultiEffect implements
// masking and shadows only), and the shell's chrome and the scrolling page are
// siblings under Main.qml with no reference to each other — qml4j also cannot
// reach a child's inner id from the root, and Main.qml is not ours to edit. So
// the one honest channel is this singleton: the scrolling container *reports*,
// the chrome *reads*. That is also the cheap option the brief asks for — a
// report is two scalars and a colour, assigned on the frames the container was
// already relaying out, with no per-frame full-frame pass anywhere.
//
// Who reports: `components/VirtualSongList.qml` (the song-list scroller seven
// pages share) and `components/PlaylistGrid.qml`, plus the pages that own their
// own top-level Flickable. Who reads: `Glass.qml`, so every material surface —
// the shell's top bar, the now-playing bar, a sheet, a dialog — responds the
// same way without any of them knowing which page is on screen.
QtObject {
    id: glassScroll

    // ── the reveal ──────────────────────────────────────────────────────────
    // iOS 27's uniform-toolbar rule, already implemented by SettingsPage /
    // PluginSettingsPage / AccountPage: the bar is transparent at offset 0 and
    // reaches full opacity across the first 20pt of scroll
    // (`Theme.metrics.ios27.scrollEdgeReveal`). Same distance here, so a page
    // that scrolls under the shell's top bar and a page that paints its own
    // uniform bar reveal on exactly the same frame.
    readonly property real revealDistance: 20

    // The container currently reporting. Kept so a page can retract its own
    // report without clobbering a newer one from the page that replaced it.
    property string owner: ""
    // Signed scroll offset of that container, in points. 0 = at the top.
    property real offset: 0
    // The colour the page paints immediately under the chrome. This is the
    // page's *own* material, chosen by the page — not a pixel sampled out of
    // the frame — which is the whole reason this stays cheap.
    property color backdrop: "transparent"
    // Relative luminance of `backdrop` (Rec.709 weights), 0 = black, 1 = white.
    property real backdropLuminance: 0.5

    readonly property real reveal: Math.max(0, Math.min(1, glassScroll.offset / glassScroll.revealDistance))
    readonly property bool scrollingUnder: glassScroll.reveal > 0.02
    // Where a surface sits over dark content it needs the *light* end of its
    // material to keep contrast, and vice versa. 0.42 is the midpoint of the
    // sRGB lightness range the kit's two page backgrounds occupy
    // (light #ffffff = 1.0, dark #000000 = 0.0) with room for a cover-derived
    // backdrop in between.
    readonly property bool overDark: glassScroll.backdropLuminance < 0.42

    // Rec.709 luma. `Application` never has to hand this a pre-computed number:
    // three multiplies per report is nothing next to a layout pass.
    function luminanceOf(c) {
        if (c === undefined || c === null) return 0.5
        return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b
    }

    // Called by the scrolling container on every contentY change. `token` is a
    // stable string per container ("local", "recent", "searchResults", ...).
    function report(token, y, backdropColor) {
        if (glassScroll.owner !== token) glassScroll.owner = token
        glassScroll.offset = y === undefined || y === null ? 0 : y
        glassScroll.backdrop = backdropColor === undefined ? "transparent" : backdropColor
        glassScroll.backdropLuminance = glassScroll.luminanceOf(glassScroll.backdrop)
        return true
    }

    // A container being torn down (or reset to the top) retracts its report,
    // but only if nothing newer has claimed the channel.
    function release(token) {
        if (glassScroll.owner !== token) return
        glassScroll.owner = ""
        glassScroll.offset = 0
        glassScroll.backdrop = "transparent"
        glassScroll.backdropLuminance = 0.5
        return true
    }

    // ── the effect of the reveal on a material ──────────────────────────────
    // At offset 0 nothing has passed under the surface yet, so the material sits
    // at its resting weight; as content slides under, the veil thickens toward
    // `fullAlpha` and it is the veil — not a hairline — that separates chrome
    // from content (the skill: "scroll edge effects, not hard dividers … only
    // where floating UI actually overlaps content"). `fullAlpha` is the caller's
    // choice: for chrome it is iOS 27's uniform scroll-edge alpha, which is what
    // SettingsPage / AccountPage already reveal to.
    function veilAlpha(restAlpha, fullAlpha, reveal) {
        var r = reveal === undefined ? glassScroll.reveal : reveal
        var f = fullAlpha === undefined ? 1 : fullAlpha
        return restAlpha + (f - restAlpha) * r
    }

    // The luminance adaptation. Over a light backdrop a material leans darker
    // and over a dark one it leans lighter, which is what keeps a translucent
    // surface legible without changing its identity. `amount` is how far the
    // caller wants to lean at full reveal: 0 disables it entirely.
    function adapt(color, amount) {
        var a = amount === undefined ? 0.25 : amount
        var target = glassScroll.overDark ? 1 : 0
        var k = a * glassScroll.reveal
        return Qt.rgba(color.r + (target - color.r) * k,
                       color.g + (target - color.g) * k,
                       color.b + (target - color.b) * k,
                       color.a)
    }
}
