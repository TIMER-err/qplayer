import QtQuick

// Standard lyric page's *control* tokens, transcribed 1:1 from the local web
// prototype `/home/strokeshading/Documents/ui/ui/styles.css`.
//
// The prototype is the authority for every number in this file, and each value
// carries the selector it was read from. The lyric page is white-on-fluid in
// *both* appearances (it sits on the host-drawn fluid backdrop), so these are
// literals, not Theme colour tokens: `--primary` in the prototype is
// rgba(255,255,255,0.98) whether the app is light or dark, while Theme.color.primary
// is the *accent* and Theme.color.onSurfaceColor flips with the appearance.
//
// CSS px == dp: the prototype is authored for a 1:1 CSS-pixel desktop viewport,
// which is what the snapshot probe renders (uiScale 1). Nothing here is scaled.
//
// Reached as `Theme.metrics.lyric.*` — the same arrangement as Metrics27
// (`Theme.metrics.ios27`), and for the same reason: qml4j generates one
// constructor per document, so the numbers live in their own file.
QtObject {
    // ── .player-shell / .now-playing — the control column itself ────────────
    readonly property real shellMaxWidth: 1394      // .player-shell width: min(1394px, 100vw - 96px)
    readonly property real shellInset: 96
    readonly property real shellTopFraction: 0.19   // .player-shell padding-top: min(19vh, 196px)
    readonly property real shellTopMax: 196
    readonly property real shellGapMin: 112         // .player-shell gap: clamp(112px, 14.6vw, 280px)
    readonly property real shellGapFactor: 0.146
    readonly property real shellGapMax: 280

    // ≤1180px breakpoint: .player-shell { width: min(920px, 100vw - 56px); padding: 72px 0 120px;
    //   grid-template-columns: minmax(300px, 390px) minmax(0, 1fr); gap: 70px }
    readonly property real tightShellMaxWidth: 920
    readonly property real tightShellInset: 56
    readonly property real tightShellTop: 72
    readonly property real tightCoverMin: 300
    readonly property real tightCoverMax: 390
    readonly property real tightShellGap: 70

    // ≤760px breakpoint: .player-shell { width: min(430px, 100vw - 36px); padding-top: 28px; display: block }
    readonly property real compactShellMaxWidth: 430
    readonly property real compactShellInset: 36
    readonly property real compactShellTop: 28

    // ── .album-art-frame ────────────────────────────────────────────────────
    readonly property real coverSize: 434           // .album-art-frame width / aspect-ratio 1
    readonly property real coverRadius: 8           // .album-art-frame border-radius 8px
    readonly property color coverBackground: "#0BFFFFFF"    // rgba(255,255,255,0.045)
    readonly property color coverTopHighlight: "#0FFFFFFF"  // box-shadow inset 0 1px rgba(255,255,255,0.06)
    readonly property color coverShadow: "#1F090D1C"        // box-shadow 0 26px 65px rgba(9,13,28,0.12)
    readonly property real coverShadowOffset: 26
    readonly property real coverShadowBlur: 65
    readonly property color placeholderColor: "#85DCE2F5"   // .album-placeholder color rgba(220,226,245,0.52)
    readonly property real placeholderIconSize: 42          // .album-placeholder svg width/height 42px
    readonly property real placeholderGap: 12               // .album-placeholder gap 12px
    readonly property real placeholderFontSize: 15          // .album-placeholder font 500 15px/1
    readonly property real placeholderHoverScale: 1.08       // .album-placeholder:hover svg scale(1.08)

    // ── .track-row / .track-copy ────────────────────────────────────────────
    readonly property real trackRowHeight: 103      // .track-row height 103px
    readonly property real compactTrackRowHeight: 86 // ≤760px .track-row height 86px
    readonly property real trackCopyPaddingTop: 2   // .track-copy padding-top 2px
    readonly property real titleFontSize: 19        // .track-copy h1 font-size 19px
    readonly property real titleLineHeight: 24      // .track-copy h1 line-height 24px
    readonly property int titleWeight: 700          // .track-copy h1 font-weight 700
    readonly property real artistFontSize: 16       // .track-copy p font-size 16px
    readonly property real artistLineHeight: 21     // .track-copy p line-height 21px
    readonly property color primary: "#FAFFFFFF"    // :root --primary rgba(255,255,255,0.98)
    readonly property color artist: "#94D3DAF0"     // :root --muted rgba(211,218,240,0.58)

    // ── .glass-host / .glass-trigger / .ellipsis ────────────────────────────
    readonly property real triggerSize: 30          // .glass-trigger 30x30
    readonly property real dotSize: 3               // .ellipsis i width/height 3px
    readonly property real dotGap: 3                // .ellipsis gap 3px
    readonly property real glassBaseDiameter: 30    // C.glassBaseDiameter
    readonly property real glassIdleSize: 0.8       // C.glassIdleSize  -> 24px at rest
    readonly property real glassHoverSize: 1.2      // C.glassHoverSize -> 36px on hover
    readonly property real glassActiveSize: 0.5     // C.glassActiveSize -> 15px while pressed/open
    readonly property real glassButtonResponse: 0.5 // C.glassButtonResponse
    readonly property real glassButtonDamping: 0.9  // C.glassButtonDamping
    readonly property color glassTint: "#0F1A1A1A"  // C.glassTint [0.1,0.1,0.1,0.06]
    readonly property real glassAnchorDamping: 0.6  // C.glassAnchorDamping
    readonly property real glassAnchorResponse: 0.3 // C.glassAnchorResponse
    readonly property real glassOpenButtonOffsetX: 145   // C.glassOpenButtonOffsetX
    readonly property real glassOpenButtonOffsetY: -79   // C.glassOpenButtonOffsetY
    readonly property real glassOpenMenuOffsetX: 30      // C.glassOpenMenuOffsetX
    readonly property real glassOpenMenuOffsetY: -122    // C.glassOpenMenuOffsetY
    readonly property real glassContentPivotX: 130  // C.glassContentPivotX (content centre)
    readonly property real glassContentPivotY: 58   // C.glassContentPivotY

    // ── .glass-content (the two-item menu) ──────────────────────────────────
    readonly property real menuWidth: 260           // C.glassMenuWidth / .glass-content width
    readonly property real menuHeight: 116          // C.glassMenuHeight / .glass-content height
    readonly property real menuRadius: 40           // C.glassMenuRadius
    readonly property real menuPaddingTop: 10       // .glass-content padding-top 10px
    readonly property real menuItemWidth: 240       // .glass-content button width 240px
    readonly property real menuItemHeight: 42       // .glass-content button height 42px
    readonly property real menuItemRadius: 13       // .glass-content button border-radius 13px
    readonly property real menuItemMarginX: 10      // .glass-content button margin 0 10px 6px
    readonly property real menuItemMarginBottom: 6
    readonly property real menuItemPaddingLeft: 50  // .glass-content button padding 0 12px 0 50px
    readonly property real menuItemPaddingRight: 12
    readonly property real menuItemFontSize: 17     // .glass-content button font 400 17px/42px
    readonly property color menuItemColor: "#EBFFFFFF"      // rgba(255,255,255,0.92)
    readonly property color menuItemHover: "#1EFFFFFF"      // rgba(255,255,255,0.118)
    readonly property real menuDividerWidth: 220    // .glass-content button + button::before width 220px
    readonly property real menuDividerInsetLeft: 10 // ... left 10px
    readonly property real menuDividerTop: -4       // ... top -4px
    readonly property color menuDividerColor: "#1FFFFFFF"   // rgba(255,255,255,0.12)
    readonly property real menuOpenDuration: 0.42   // C.glassOpenDuration, bezier [0.32,0.72,0.24,1]
    readonly property real menuCloseDuration: 0.28  // C.glassCloseSizeDuration, bezier [0.42,0,0.40,1]
    readonly property real menuDotHideDuration: 0.08 // C.glassDotHideDuration, bezier [0,0,0.58,1]
    readonly property real menuDotShowDuration: 0.14 // C.glassDotShowDuration, bezier [0.42,0,1,1]
    readonly property real menuContentScaleClosed: 2 // C.glassContentScaleClosed
    readonly property real menuAuxResponse: 0.5     // C.glassOpenAuxResponse
    readonly property real menuAuxCloseResponse: 0.3 // C.glassCloseAuxResponse

    // ── .progress-block / .seek / .time-row ─────────────────────────────────
    // .progress-block is `height: 40px` in styles.css, but the reference screenshot's
    // transport row sits 30px below the seek block (play/pause ink rows 778-805 with the
    // 54px buttons centred at row 597), i.e. the shipped block was its *content* height:
    // 8 (seek) + 7 (.time-row margin-top) + 16 (line-height) = 31. Both cannot be true;
    // the reference wins (user ruling), so the row moves up 9px to where it is in
    // assets/reference.png. Set back to 40 for the current styles.css.
    readonly property real progressBlockHeight: 31
    readonly property real seekHeight: 8            // .seek height 8px / track height 8px
    readonly property real seekRadius: 4            // .seek::-webkit-slider-runnable-track border-radius 4px
    readonly property color seekFill: "#D1EBEFFF"   // linear-gradient rgba(235,239,255,0.82)
    readonly property color seekTrack: "#52D1D9F2"  // linear-gradient rgba(209,217,242,0.32)
    readonly property real timeRowGap: 7            // .time-row margin-top 7px
    readonly property real timeFontSize: 12         // .time-row font-size 12px
    readonly property real timeLineHeight: 16       // .time-row line-height 16px
    readonly property color timeColor: "#57CAD3EE"  // .time-row color rgba(202,211,238,0.34)

    // ── .transport / .control-button / .side-control ────────────────────────
    readonly property real transportHeight: 58      // .transport height 58px
    readonly property real controlSize: 54          // :root --control-size 54px
    readonly property real compactControlSize: 48   // ≤760px :root --control-size 48px
    readonly property real controlGap: 30           // .transport column-gap 30px
    readonly property real compactControlGap: 8     // ≤760px .transport column-gap 8px
    readonly property real sideWidth: 24            // .side-control width 24px
    readonly property real sideHeight: 38           // .side-control height 38px
    readonly property real sideFontSize: 21         // .side-control font-size 21px
    readonly property real repeatFontSize: 19       // .side-control.repeat font-size 19px
    readonly property color sideColor: "#7ADAE1F7"  // .side-control color rgba(218,225,247,0.48)
    readonly property real pressScale: 0.75         // ControlButton pointerdown scale.setTarget(0.75)
    readonly property real pressResponse: 0.35      // PRESS.response
    readonly property real pressDamping: 0.55       // PRESS.damping
    readonly property real iconSwapThreshold: 0.5   // ICON_SWAP_THRESHOLD
    readonly property real iconReturnDelay: 0.1     // ICON_RETURN_DELAY
    readonly property real stageSize: 30            // .icon-stage 30x30
    readonly property real playStageWidth: 28       // .play-stage width 28px
    readonly property real playStageHeight: 30      // .play-stage height 30px
    // .skip-stage / .skip-source-svg are 32x32 in styles.css, but the reference
    // screenshot's icon ink measures 28 x 16 px (the two-arrow figure spans x
    // 381-408 / y 784-799 in reference.png against a 25.43..105 box in the
    // 134-unit viewBox) — i.e. the shipped icon was drawn ~1.47x larger than the
    // 32px box produces. 47 px reproduces the screenshot's ink exactly
    // (79.57/134 * 47 = 27.9 px wide, 44.6/134 * 47 = 15.6 px tall); at 32 px the
    // ink is only 19 x 10.6 and the two arrows read as two unrelated specks.
    readonly property real skipStageSize: 47
    readonly property real skipViewBox: 134         // .skip-source-svg viewBox 0 0 134 134
    // .pause-bars { inset: 2px 4px } + .pause-bars i { width 8, height 26, radius 2 }.
    // The reference screenshot's bars measure 10 x 28 px (x 468-477 / 482-491 against
    // a 20px gap-free pair, rows 778-805) — the same ~1.2x the shipped icon was drawn
    // larger than today's styles.css, as with the skip figure above. These are the
    // reference's numbers; `pauseBarsInsetX: 2` with `space-between` in a 28px stage
    // puts the two 10px bars at x 2 and x 16, i.e. 2 / 4 / 2, which is what the
    // screenshot shows.
    readonly property real pauseBarsInsetX: 2
    readonly property real pauseBarsInsetY: 1
    readonly property real pauseBarWidth: 10
    readonly property real pauseBarRadius: 2
    // `.play-triangle` in styles.css is a CSS border triangle, i.e. sharp corners
    // (border-left 22px solid + 13px transparent top/bottom). The prototype's *other*
    // play glyph — `.album-placeholder svg`, viewBox 0 0 38 38 — IS rounded, with
    // r ~= 3.5 on a 34-unit triangle (about 10% of the edge). The transport triangle
    // uses that same 10% here: 10% of its 26px height = 2.6px of corner rounding,
    // with the outer box kept exactly at the CSS values. Set 0 for CSS-sharp corners.
    // Back to 0 (sharp): styles.css draws `.play-triangle` as a CSS border triangle and
    // the reference screenshot is in the pause state, so *neither* source supports a
    // radius — the 2.6 below was an inference from the prototype's other rounded play
    // glyph, and an inference must not override the prototype's explicit value. Kept as
    // a token because the radius is one number away if that ever changes.
    readonly property real playTriangleCorner: 0
    // .play-triangle { left 5, top 2, border 13px top/bottom, border-left 22px }
    readonly property real playTriangleLeft: 5
    readonly property real playTriangleTop: 2
    readonly property real playTriangleWidth: 22
    readonly property real playTriangleHeight: 26

    // ── skip-icon motion (app.js/ControlButton + source_algorithms.js C) ─────
    readonly property real skipShift: 40            // C.skipShift
    readonly property real skipIncomingOffset: 15   // C.skipIncomingOffset
    readonly property real skipPivotY: 67           // C.skipPivotY
    readonly property real skipNextIncomingPivotX: 29.48    // C.skipNextIncomingPivotX
    readonly property real skipNextOutgoingPivotX: 108.54   // C.skipNextOutgoingPivotX
    readonly property real skipPreviousIncomingPivotX: 104.52 // C.skipPreviousIncomingPivotX
    readonly property real skipPreviousOutgoingPivotX: 25.46  // C.skipPreviousOutgoingPivotX
    readonly property real skipIncomingAlphaGain: 1.5 // C.skipIncomingAlphaGain
    readonly property real skipLifetime: 0.7        // C.skipLifetime
    readonly property real skipOuterDamping: 0.55   // C.skipOuterDamping (shift + incoming)
    readonly property real skipOuterResponse: 0.4   // C.skipOuterResponse
    readonly property real skipOutgoingDamping: 1   // C.skipOutgoingDamping
    readonly property real skipOutgoingResponse: 0.12 // C.skipOutgoingResponse

    // ── idle / disabled ────────────────────────────────────────────────────
    // body[data-player-state="idle"] .progress-block, .transport { opacity: 0.34 }
    readonly property real idleOpacity: 0.34
}
