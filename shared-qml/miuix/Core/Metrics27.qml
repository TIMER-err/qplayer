import QtQuick

// Metrics measured from Apple's iOS 27 UI kit (Figma 27.0.2).
//
// These live in their own component rather than inside Theme.metrics because qml4j
// generates one constructor per document: Theme.qml had grown to the JVM's 64KB
// method limit, and adding a few dozen more properties to it made the engine fail
// with MethodTooLargeException at load time. A separate document keeps each
// constructor small (and gives the next person somewhere to put numbers).
QtObject {
    readonly property real rowMinHeight: 44
        // The measured rows. Anything that is a list row should use these.
    readonly property real rowRegular: 52
    readonly property real rowTall: 68
    readonly property real rowPaddingV: 11
        // Separator inset: 16 plain, 56 once a row has a leading glyph.
    readonly property real separatorInset: 16
    readonly property real separatorInsetIcon: 56
        // Section headers, with the three weights the kit actually ships.
    readonly property real headerNested: 42
    readonly property real headerProminent: 45
    readonly property real headerExtraProminent: 48
    readonly property real sectionFooter: 32
        // Bars.
    readonly property real navBar: 54
    readonly property real navBarControl: 44
        // A tab bar is 95 = 16 + 54 + 25, and its glass tray overhangs 4pt a side.
    readonly property real tabBar: 95
    readonly property real tabBarTray: 62
    readonly property real tabBarTrayOverhang: 4
    readonly property real bottomBar: 84
    readonly property real bottomBarControl: 48
        // Buttons come in exactly three sizes.
    readonly property real buttonSmall: 28
    readonly property real buttonMedium: 34
    readonly property real buttonLarge: 50
        // …and the dialog's own action button is a fourth, separately measured number:
        // §4.16 measures the alert/dialog button at 48pt and a pill (`SPC:309` —
        // "Buttons are 48pt tall pills"), which is not one of the three ladder heights
        // above. It is the height every action button inside a dialog uses, whether it
        // sits in the dialog's own action row (`Dialog.qml`) or in a row the dialog's
        // content brings with it (`ModelManagerDialog.qml`), so the number lives here
        // once instead of being written twice.
    readonly property real buttonDialog: 48
    readonly property real rowPadding: 16
    // Page skeleton: plain content is 16pt from the edges, body copy stops at 672pt,
    // and grouped sections sit 16pt apart (32pt between major blocks).
    readonly property real contentInset: 16
    readonly property real readableWidth: 672
    readonly property real sectionGap: 16
    readonly property real blockGap: 32
    // The gap between two *distinct controls* stacked inside one card. SPC:31-45 — the
    // kit's spacing scale is 0/4/8/12/16/20/24…, and 8 is the step it uses between two
    // rows of a form; it is the same number as `sliderLabelGap` below. It is named here
    // because the audio-studio page was writing `spacing: 2` — the gap *inside one label
    // block*, which is what BasicComponent's title/summary pair uses — for every one of
    // its fifteen stacked controls, which is the difference between a form and a pile.
    readonly property real controlGap: 8
    // Grouped form (CR:42-44, §3.3). The *cards* take the grouped list's own 20pt inset
    // (350pt of usable width on a 390pt canvas) while the section header and footer text
    // stay on the page's 16pt content margin (SPC:56-60) — that 4pt is the whole reason a
    // section reads as a label for the card instead of as another row inside it.
    readonly property real groupedInset: 20
    // Header band = pad 16 / label 13-18 / pad 10 = 42 (SPC:276-283).
    readonly property real headerPaddingTop: 16
    readonly property real headerPaddingBottom: 10
    // Footer band = 32 with pad 16/16/8/6 (SPC:281); 8 + 18 + 6 = the 32pt it measures.
    readonly property real footerPaddingTop: 8
    readonly property real footerPaddingBottom: 6
    // Disclosure affordance (§4.10): an 8x13 chevron in tertiaryLabel, 8pt from the
    // value it follows and 16pt from the card's trailing edge.
    readonly property real chevronWidth: 8
    readonly property real chevronHeight: 13
    readonly property real chevronGap: 8
    // Color well on a list row: the kit's Rounded image is 30pt on a regular row (§4.4).
    readonly property real swatchSize: 30
    readonly property real swatchRadius: 7
    // Text field (§4.7): 44pt single line, radius 10, padding H 12 / V 11, and a 0.5pt
    // outline that goes to 2pt in the accent when focused.
    readonly property real fieldHeight: 44
    readonly property real fieldRadius: 10
    readonly property real fieldPaddingH: 12
    readonly property real fieldPaddingV: 11
    readonly property real fieldOutline: 0.5
    readonly property real fieldOutlineFocused: 2
    // Segmented control (§4.6): a 32pt track holding a 28pt indicator inside a 2pt
    // inset, and that inset is also what makes the outer radius read as 9 and the
    // indicator as 7 (outer = height/2 − 7). Labels are footnote 13, semibold on the
    // selected segment — TabRow's contour mode already implements exactly this, so
    // these are the numbers a page passes to it rather than new geometry.
    readonly property real segmentedHeight: 32
    readonly property real segmentedIndicator: 28
    readonly property real segmentedInset: 2
    readonly property real segmentedRadius: 9
    readonly property real segmentedIndicatorRadius: 7
    readonly property real segmentedSegmentMin: 44
    readonly property real segmentedTrack: 32
    // A segmented control is *sized to its segments*, and the kit gives the whole
    // control a floor by segment count: 150 / 200 / 270 / 320pt for 2 / 3 / 4 / 5
    // (SC §4.6). Past five segments there is no measured minimum because the kit
    // says not to build one (SC:282) — the caller decides what to do instead.
    function segmentedMinTotal(count) {
        if (count <= 2) return 150
        if (count === 3) return 200
        if (count === 4) return 270
        if (count === 5) return 320
        return 0
    }
    // A segmented label's own box: 12pt of padding either side of the widest label.
    readonly property real segmentedLabelPadding: 12
    // ---- the segmented control as a *material* (§4.6 + §2.2/§2.3) -------------
    //
    // §4.6 measures the selected indicator's fill as `segmentedControlSelectedFill`
    // — opaque white in light, white @0.27 in dark — over a `fills.tertiary` track,
    // and closes the row with its lift: `0 3px 8px rgba(0,0,0,0.12)` plus a tight
    // `0 3px 1px rgba(0,0,0,0.04)`. An opaque plate is a paint, not a control
    // surface: it has no veil, no rim and no sheen, so the control reads as "a
    // coloured track with a white chip" — the same flat-fill read as the page
    // background, which is exactly what makes a restyle look like nothing changed.
    //
    // The plate therefore keeps the kit's light/dark ladder but as a *veil*
    // (`segmentedPlateVeil`) — the alpha ladder of `miscellaneous.
    // segmentedControlSelectedFill` one step further from opaque, because the
    // shell's page is a wash rather than the white sheet the kit assumed — and the
    // rim, the sheen and the measured lift come from `Glass.qml`. The track keeps
    // the kit's `fills.tertiary` (the kit's own *small* glass is the same value:
    // `#747480 @0.08` light / `#767680 @0.12` dark, §2.2) and gains the same rim
    // and sheen, so the well the plate slides in is a material too.
    function segmentedPlateVeil(dark) {
        return dark ? Qt.rgba(1, 1, 1, 0.30) : Qt.rgba(1, 1, 1, 0.82)
    }
    // The lift, straight off §4.6: blur 8 / offsetY 3 / black @0.12. The tight
    // second shadow (1px, @0.04) is not drawn — this renderer has one shadow layer
    // per surface and a 4%-alpha 1pt band under a 12% one is below what it shows.
    readonly property real segmentedPlateShadowBlur: 8
    readonly property real segmentedPlateShadowOffset: 3
    readonly property real segmentedPlateShadowOpacity: 0.12
    // §4.6: 0.3s snappy for the plate's travel (the weight cross-fade, 0.15s, is the
    // label's own — this renderer has no text-weight animation, so the label simply
    // switches and only the sheet travels).
    readonly property real segmentedMotion: 300
    // §4.10/DC: the checkmark of a selected list row is a *trailing* affordance —
    // the sidebar gives the checkmark a trailing column of its own (SDB:260), and
    // nothing in the kit puts it in front of the label.
    readonly property real checkmarkSize: 16
    // Toggle (§4.8): a 51x31 track whose 27pt knob travels 20pt, inside a 51x44 touch
    // target. The off state is a fill, not a colour — only the on state takes the accent.
    readonly property real switchWidth: 51
    readonly property real switchHeight: 31
    readonly property real switchKnob: 27
    readonly property real switchTouch: 44
    // Slider (§4.9): a 28pt knob on a 4pt track, inside a 44pt touch row. The track is
    // `fills.tertiary`; a discrete control with a handful of options is a segmented
    // control instead, which is why nothing here draws fewer than four steps.
    readonly property real sliderThumb: 28
    readonly property real sliderTrack: 4
    readonly property real sliderTouch: 44
    // The label line and the track under it are 8pt apart — the kit's own spacing
    // baseline (SPC:31-45) — so a slider row reads as [label][value] over one control.
    readonly property real sliderLabelGap: 8
        // Search field (§4.5): the field's own padding is 8, its glyph 17pt, and the
        // gap between that glyph and the text another 8. The container padding around
        // it is `contentInset` like any other content.
    readonly property real searchFieldPadding: 8
    readonly property real searchFieldGlyph: 17
    readonly property real searchFieldGap: 8
        // Toolbar icon button: 44×44 in the top bar (48×48 in the bottom bar).
    readonly property real iconButton: 44
    // ---- scroll edge effect / uniform toolbar (§2.5, MAT:389-421; CR:145) -----
    //
    // iOS 27 does not let scrolled content show through the top bar unevenly: it
    // paints one uniform bar across the top edge out of the *hard* scroll-edge
    // material — Light #ffffff @0.85, Dark #000000 @0.55, closed by a 1pt edge
    // (#000000 @0.05 / @0.1) — and the bar is transparent at offset 0, reaching full
    // opacity across the first 20pt of scroll. `blurRadius 6` is the one number this
    // renderer cannot honour: qml4j has no backdrop filter, so the veil is painted
    // over the shell's own wash instead of over a blurred copy of the content.
    readonly property real scrollEdgeReveal: 20
    readonly property real scrollEdgeLine: 1
    function scrollEdgeFill(dark) {
        return dark ? Qt.rgba(0, 0, 0, 0.55) : Qt.rgba(1, 1, 1, 0.85)
    }
    function scrollEdgeHairline(dark) {
        return dark ? Qt.rgba(0, 0, 0, 0.1) : Qt.rgba(0, 0, 0, 0.05)
    }
}
