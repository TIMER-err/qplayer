import QtQuick

// StyleManager owns HCT generation; this object only maps MD3 roles to Miuix.
QtObject {
    property bool dark: false
    readonly property var base: dark ? StyleManager.darkScheme : StyleManager.lightScheme
    function blend(foreground, background, alpha) {
        var f = parseInt(String(foreground).slice(-6), 16)
        var b = parseInt(String(background).slice(-6), 16)
        return Qt.rgba((Math.floor(f / 65536) * alpha + Math.floor(b / 65536) * (1 - alpha)) / 255,
                       ((Math.floor(f / 256) % 256) * alpha + (Math.floor(b / 256) % 256) * (1 - alpha)) / 255,
                       ((f % 256) * alpha + (b % 256) * (1 - alpha)) / 255, 1)
    }
    readonly property color disabledSecondary: blend(base.outlineVariant, base.surface, 0.5)
    readonly property color disabledOnSecondary: blend(base.onSurfaceColor, disabledSecondary, 0.38)
    readonly property color disabledPrimaryButton: disabledPrimary
    readonly property color disabledOnPrimaryButton: blend(base.onPrimaryColor, disabledPrimaryButton, 0.6)
    readonly property color disabledPrimarySlider: disabledPrimary
    readonly property color sliderKeyPoint: base.primary
    readonly property color sliderKeyPointForeground: base.surfaceContainerHigh
    readonly property color primary: base.primary
    readonly property color onPrimaryColor: base.onPrimaryColor
    readonly property color primaryContainer: base.primaryContainer
    readonly property color onPrimaryContainerColor: base.onPrimaryContainerColor
    readonly property color secondary: base.outlineVariant
    readonly property color onSecondaryColor: base.outline
    readonly property color secondaryContainer: base.secondaryContainer
    readonly property color onSecondaryContainerColor: base.onSecondaryContainerColor
    readonly property color tertiary: base.tertiary
    readonly property color onTertiaryColor: base.onTertiaryColor
    readonly property color tertiaryContainer: base.tertiaryContainer
    readonly property color onTertiaryContainerColor: base.onTertiaryContainerColor
    readonly property color error: base.error
    readonly property color onErrorColor: base.onErrorColor
    readonly property color errorContainer: base.errorContainer
    readonly property color onErrorContainerColor: base.onErrorContainerColor
    readonly property color background: base.background
    readonly property color onBackgroundColor: base.onBackgroundColor
    readonly property color surface: base.surface
    readonly property color onSurfaceColor: base.onSurfaceColor
    readonly property color surfaceVariant: base.surfaceVariant
    readonly property color onSurfaceVariantColor: base.onSurfaceVariantColor
    readonly property color outline: base.outline
    readonly property color outlineVariant: base.outlineVariant
    readonly property color shadow: base.shadow
    readonly property color scrim: base.scrim
    readonly property color inverseSurface: base.inverseSurface
    readonly property color inverseOnSurface: base.inverseOnSurface
    readonly property color inversePrimary: base.inversePrimary
    readonly property color surfaceDim: base.surfaceDim
    readonly property color surfaceBright: base.surfaceBright
    readonly property color surfaceContainerLowest: base.surfaceContainerLowest
    readonly property color surfaceContainerLow: base.surfaceContainerLow
    readonly property color surfaceContainer: base.surfaceContainer
    readonly property color surfaceContainerHigh: base.surfaceContainerHigh
    readonly property color surfaceContainerHighest: base.surfaceContainerHighest
    readonly property color onSurfaceContainer: base.onSurfaceColor
    readonly property color onSurfaceContainerHigh: blend(base.onSurfaceColor, base.surfaceContainerHigh, 0.8)
    readonly property color onPrimary: base.onPrimaryColor
    readonly property color onBackground: base.onBackgroundColor
    readonly property color onBackgroundVariant: base.primary
    readonly property color onSurfaceSecondary: blend(base.onSurfaceColor, base.surface, 0.8)
    readonly property color onSurfaceVariantSummary: base.onSurfaceVariantColor
    readonly property color onSurfaceVariantActions: base.onSurfaceVariantColor
    readonly property color dividerLine: base.outlineVariant
    readonly property color disabledPrimary: blend(base.primary, base.surface, 0.38)
    readonly property color disabledOnPrimary: blend(base.onPrimaryColor, disabledPrimary, 0.38)
    readonly property color secondaryVariant: base.surfaceContainerHigh
    readonly property color onSecondaryVariant: base.onSurfaceColor
    readonly property color disabledSecondaryVariant: blend(base.surfaceContainerHigh, base.surface, 0.6)
    readonly property color disabledOnSecondaryVariant: blend(base.onSurfaceColor, disabledSecondaryVariant, 0.38)
    readonly property color sliderBackground: blend(base.primary, base.surface, 0.2)
    readonly property color windowDimming: dark ? "#99000000" : "#4D000000"
    readonly property color primaryVariant: StyleManager.lightScheme.primaryContainer
    readonly property color onPrimaryVariant: StyleManager.lightScheme.onPrimaryContainerColor
    readonly property color secondaryContainerVariant: base.surfaceContainerHighest
    readonly property color onSecondaryContainerVariant: base.onSurfaceVariantColor
    readonly property color tertiaryContainerVariant: base.onTertiaryContainerColor
    readonly property color disabledOnSurface: base.onSurfaceColor
    readonly property color onSurfaceContainerVariant: base.onSurfaceVariantColor
    readonly property color onSurfaceContainerHighest: base.onSurfaceColor
    readonly property color onPrimaryContainer: onPrimaryContainerColor
    readonly property color onSecondary: onSecondaryColor
    readonly property color onSecondaryContainer: onSecondaryContainerColor
    readonly property color onTertiaryContainer: onTertiaryContainerColor
    readonly property color onSurface: onSurfaceColor
    readonly property color onError: onErrorColor
    readonly property color onErrorContainer: onErrorContainerColor
}
