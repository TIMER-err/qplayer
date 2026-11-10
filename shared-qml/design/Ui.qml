pragma Singleton
import QtQuick
import miuix.Core

// App-level design tokens. Every page, card and header reads spacing, radii,
// type sizes and semantic colours from here instead of repeating literals.
// Colours derive from the Miuix theme, so Monet and dark mode keep working.
QtObject {
    id: ui

    // ---- Breakpoints (logical px, after the interface-scale setting) ----------
    readonly property int compactMax: 600
    readonly property int expandedMin: 840

    // ---- Spacing ----------------------------------------------------------------
    readonly property int space1: 4
    readonly property int space2: 8
    readonly property int space3: 12
    readonly property int space4: 16
    readonly property int space5: 20
    readonly property int space6: 24
    readonly property int space8: 32
    readonly property int space10: 40

    // Horizontal page gutter for a page of the given width.
    function gutter(width) {
        return width >= ui.expandedMin ? 32 : (width >= ui.compactMax ? 24 : 16)
    }
    // Content column width cap for reading-heavy pages (settings, account).
    readonly property int readableMax: 920

    // ---- Radii ------------------------------------------------------------------
    readonly property int radiusXs: 6
    readonly property int radiusSm: 10
    readonly property int radiusMd: 14
    readonly property int radiusLg: 20
    readonly property int radiusXl: 28
    readonly property int radiusFull: 999

    // ---- Type scale (pixel sizes). qml4j renders weight >= DemiBold as bold and
    // everything lower as regular, so only two weights are used. --------------
    readonly property int textDisplay: 32
    readonly property int textTitle1: 26
    readonly property int textTitle2: 20
    readonly property int textHeadline: 16
    readonly property int textBody: 15
    readonly property int textCallout: 14
    readonly property int textCaption: 12
    readonly property int textMicro: 11
    readonly property int weightBold: Font.DemiBold
    readonly property int weightRegular: Font.Normal

    // ---- Component metrics --------------------------------------------------------
    readonly property int headerHeight: 64
    readonly property int iconButton: 40
    readonly property int iconSize: 22
    readonly property int rowHeight: 64
    readonly property int pillHeight: 36
    readonly property int sidebarWide: 232
    readonly property int sidebarNarrow: 80
    readonly property int playerBarHeight: 76
    readonly property int heroCoverWide: 200
    readonly property int heroCoverCompact: 148

    // ---- Motion -------------------------------------------------------------------
    readonly property int durationShort: 120
    readonly property int durationMedium: 200
    readonly property int durationLong: 320

    // ---- Semantic colours -----------------------------------------------------------
    readonly property bool dark: Theme.dark
    readonly property color background: Theme.color.surface
    readonly property color surfaceRaised: Theme.color.surfaceContainer
    readonly property color surfaceSunken: dark ? "#14FFFFFF" : "#0A000000"
    readonly property color surfaceHover: dark ? "#14FFFFFF" : "#0D000000"
    readonly property color surfacePressed: dark ? "#24FFFFFF" : "#17000000"
    readonly property color hairline: dark ? "#1FFFFFFF" : "#14000000"
    readonly property color textPrimary: Theme.color.onSurfaceColor
    readonly property color textSecondary: Theme.color.onSurfaceVariantSummary
    readonly property color textTertiary: Theme.color.onSurfaceVariantActions
    readonly property color accent: Theme.color.primary
    readonly property color onAccent: Theme.color.onPrimaryColor
    readonly property color accentSoft: Qt.rgba(Qt.color(Theme.color.primary).r,
                                                Qt.color(Theme.color.primary).g,
                                                Qt.color(Theme.color.primary).b,
                                                dark ? 0.22 : 0.12)
    readonly property color danger: Theme.color.error
    readonly property color like: "#FF4D6D"
    readonly property color scrim: dark ? "#99000000" : "#52000000"

    // "#AARRGGBB" for colour c at the given alpha. GradientStop.color is a string
    // in qml4j, so gradients must receive hex rather than a Qt.rgba value.
    function hexAlpha(c, alpha) {
        var v = Qt.color(c)
        function part(channel) {
            var n = Math.round(Math.max(0, Math.min(1, channel)) * 255)
            var s = n.toString(16)
            return s.length < 2 ? "0" + s : s
        }
        return "#" + part(alpha) + part(v.r) + part(v.g) + part(v.b)
    }
    // Accent wash used behind detail heroes: the theme accent faded to transparent.
    function accentAlpha(alpha) { return ui.hexAlpha(Theme.color.primary, alpha) }
    function surfaceAlpha(alpha) { return ui.hexAlpha(Theme.color.surface, alpha) }
    // Accent wash as a colour value for Rectangle.color (not for GradientStop).
    function accentColor(alpha) {
        var c = Qt.color(Theme.color.primary)
        return Qt.rgba(c.r, c.g, c.b, alpha)
    }

    readonly property string iconFont: Theme.iconFont.name

    function formatTime(ms) {
        if (!(ms > 0)) return "0:00"
        var s = Math.floor(ms / 1000)
        var m = Math.floor(s / 60)
        var r = s % 60
        return m + ":" + (r < 10 ? "0" + r : r)
    }
}
