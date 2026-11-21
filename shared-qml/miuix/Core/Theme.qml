pragma Singleton
import QtQuick
import miuix.Core

QtObject {
    id: themeRoot
    property bool dynamicColors: false
    readonly property color seedColor: StyleManager.seedColor
    function setSeedColor(value) { StyleManager.seedColor = String(value) }
    function setDark(value) { StyleManager.isDarkTheme = value; dark = value }
    property QtObject monet: MonetScheme { dark: themeRoot.dark }
    property bool dark: StyleManager.isDarkTheme
    property var color: dynamicColors ? monet : (dark ? schemes.dark : schemes.light)
    property QtObject schemes: QtObject {
        property QtObject light: QtObject {
            property color primaryVariant: "#3482FF"
            property color onPrimaryVariant: "#AECDFF"
            property color secondaryContainerVariant: "#F0F0F0"
            property color onSecondaryContainerVariant: "#A8A8A8"
            property color tertiaryContainerVariant: "#EAF2FF"
            property color disabledOnSurface: "#B2B2B2"
            property color onSurfaceContainerVariant: "#959595"
            property color onSurfaceContainerHighest: "#000000"
            property color onPrimaryContainer: onPrimaryContainerColor
            property color onSecondary: onSecondaryColor
            property color onSecondaryContainer: onSecondaryContainerColor
            property color onTertiaryContainer: onTertiaryContainerColor
            property color onSurface: onSurfaceColor
            property color onError: onErrorColor
            property color onErrorContainer: onErrorContainerColor
            property color disabledSecondary: "#F0F0F0"
            property color disabledOnSecondary: "#FCFCFC"
            property color disabledPrimaryButton: "#C2D9FF"
            property color disabledOnPrimaryButton: "#FFFFFF"
            property color disabledPrimarySlider: "#B8CFF5"
            property color sliderKeyPoint: "#4DA3B3CD"
            property color sliderKeyPointForeground: "#6EB5FF"
            property color primary: "#3482FF"
            property color onPrimaryColor: "#ffffff"
            property color primaryContainer: "#5D9BFF"
            property color onPrimaryContainerColor: "#ffffff"
            property color secondary: "#E6E6E6"
            property color onSecondaryColor: "#ffffff"
            property color secondaryContainer: "#F0F0F0"
            property color onSecondaryContainerColor: "#303030"
            property color tertiary: "#3482FF"
            property color onTertiaryColor: "#ffffff"
            property color tertiaryContainer: "#EAF2FF"
            property color onTertiaryContainerColor: "#3482FF"
            property color error: "#E94634"
            property color onErrorColor: "#ffffff"
            property color errorContainer: "#FDF6F4"
            property color onErrorContainerColor: "#410002"
            property color background: "#ffffff"
            property color onBackgroundColor: "#000000"
            property color surface: "#F7F7F7"
            property color onSurfaceColor: "#000000"
            property color surfaceVariant: "#ffffff"
            property color onSurfaceVariantColor: "#959595"
            property color outline: "#D9D9D9"
            property color outlineVariant: "#E0E0E0"
            property color shadow: "#000000"
            property color scrim: "#000000"
            property color inverseSurface: "#242424"
            property color inverseOnSurface: "#F2F2F2"
            property color inversePrimary: "#277AF7"
            property color surfaceDim: "#E8E8E8"
            property color surfaceBright: "#ffffff"
            property color surfaceContainerLowest: "#ffffff"
            property color surfaceContainerLow: "#ffffff"
            property color surfaceContainer: "#ffffff"
            property color surfaceContainerHigh: "#E8E8E8"
            property color surfaceContainerHighest: "#E8E8E8"
            property color onSurfaceContainer: "#000000"
            property color onSurfaceContainerHigh: "#A2A2A2"
            property color onPrimary: "#ffffff"
            property color onBackground: "#000000"
            property color onBackgroundVariant: "#8C93B0"
            property color onSurfaceSecondary: "#CC000000"
            property color onSurfaceVariantSummary: "#99000000"
            property color onSurfaceVariantActions: "#66000000"
            property color dividerLine: "#E0E0E0"
            property color disabledPrimary: "#C2D9FF"
            property color disabledOnPrimary: "#F3F8FF"
            property color secondaryVariant: "#F0F0F0"
            property color onSecondaryVariant: "#303030"
            property color disabledSecondaryVariant: "#F2F2F2"
            property color disabledOnSecondaryVariant: "#B2B2B2"
            property color sliderBackground: "#0F000000"
            property color windowDimming: "#4D000000"
        }
        property QtObject dark: QtObject {
            property color primaryVariant: "#0073DD"
            property color onPrimaryVariant: "#99C7F1"
            property color secondaryContainerVariant: "#4F4F4F"
            property color onSecondaryContainerVariant: "#959595"
            property color tertiaryContainerVariant: "#505050"
            property color disabledOnSurface: "#666666"
            property color onSurfaceContainerVariant: "#737373"
            property color onSurfaceContainerHighest: "#E9E9E9"
            property color onPrimaryContainer: onPrimaryContainerColor
            property color onSecondary: onSecondaryColor
            property color onSecondaryContainer: onSecondaryContainerColor
            property color onTertiaryContainer: onTertiaryContainerColor
            property color onSurface: onSurfaceColor
            property color onError: onErrorColor
            property color onErrorContainer: onErrorContainerColor
            property color disabledSecondary: "#3F3F3F"
            property color disabledOnSecondary: "#797979"
            property color disabledPrimaryButton: "#253E64"
            property color disabledOnPrimaryButton: "#677893"
            property color disabledPrimarySlider: "#44587C"
            property color sliderKeyPoint: "#4D7A8AA6"
            property color sliderKeyPointForeground: "#5DAAFF"
            property color primary: "#277AF7"
            property color onPrimaryColor: "#ffffff"
            property color primaryContainer: "#338FE4"
            property color onPrimaryContainerColor: "#ffffff"
            property color secondary: "#505050"
            property color onSecondaryColor: "#ffffff"
            property color secondaryContainer: "#434343"
            property color onSecondaryContainerColor: "#D9D9D9"
            property color tertiary: "#4788FF"
            property color onTertiaryColor: "#ffffff"
            property color tertiaryContainer: "#2B3B54"
            property color onTertiaryContainerColor: "#4788FF"
            property color error: "#F12522"
            property color onErrorColor: "#ffffff"
            property color errorContainer: "#2E0603"
            property color onErrorContainerColor: "#FFDAD6"
            property color background: "#242424"
            property color onBackgroundColor: "#F2F2F2"
            property color surface: "#000000"
            property color onSurfaceColor: "#F2F2F2"
            property color surfaceVariant: "#242424"
            property color onSurfaceVariantColor: "#737373"
            property color outline: "#404040"
            property color outlineVariant: "#393939"
            property color shadow: "#000000"
            property color scrim: "#000000"
            property color inverseSurface: "#F2F2F2"
            property color inverseOnSurface: "#242424"
            property color inversePrimary: "#3482FF"
            property color surfaceDim: "#000000"
            property color surfaceBright: "#2D2D2D"
            property color surfaceContainerLowest: "#000000"
            property color surfaceContainerLow: "#242424"
            property color surfaceContainer: "#242424"
            property color surfaceContainerHigh: "#242424"
            property color surfaceContainerHighest: "#2D2D2D"
            property color onSurfaceContainer: "#E6FFFFFF"
            property color onSurfaceContainerHigh: "#666666"
            property color onPrimary: "#ffffff"
            property color onBackground: "#E6FFFFFF"
            property color onBackgroundVariant: "#787E96"
            property color onSurfaceSecondary: "#CCFFFFFF"
            property color onSurfaceVariantSummary: "#80FFFFFF"
            property color onSurfaceVariantActions: "#66FFFFFF"
            property color dividerLine: "#393939"
            property color disabledPrimary: "#253E64"
            property color disabledOnPrimary: "#677993"
            property color secondaryVariant: "#434343"
            property color onSecondaryVariant: "#D9D9D9"
            property color disabledSecondaryVariant: "#404040"
            property color disabledOnSecondaryVariant: "#707170"
            property color sliderBackground: "#26FFFFFF"
            property color windowDimming: "#99000000"
        }
    }
    property QtObject metrics: QtObject {
        property real cardRadius: 16
        property real buttonRadius: 16
        property real rowMinHeight: 56
        property real rowPadding: 16
        property real titleSize: 17
        property real summarySize: 14
        property real switchWidth: 49
        property real switchHeight: 28
        property real sliderHeight: 28
    }
    property QtObject elevation: QtObject {
        property real level0: 0
        property real level1: 1
        property real level2: 3
        property real level3: 6
        property real level4: 8
    }
    property QtObject state: QtObject {
        property real hoverStateLayerOpacity: 0.08
        property real pressedStateLayerOpacity: 0.12
        property real focusStateLayerOpacity: 0.12
    }
    property QtObject iconFont: QtObject {
        property string name: "Material Symbols Rounded"
    }
    property QtObject typography: QtObject {
        property QtObject displayLarge: QtObject {
            property string family: "Roboto"
            property int size: 57
            property int weight: 50
            property real lineHeight: 64
        }
        property QtObject displayMedium: QtObject {
            property string family: "Roboto"
            property int size: 45
            property int weight: 50
            property real lineHeight: 52
        }
        property QtObject displaySmall: QtObject {
            property string family: "Roboto"
            property int size: 36
            property int weight: 50
            property real lineHeight: 44
        }
        property QtObject headlineLarge: QtObject {
            property string family: "Roboto"
            property int size: 32
            property int weight: 50
            property real lineHeight: 40
        }
        property QtObject headlineMedium: QtObject {
            property string family: "Roboto"
            property int size: 28
            property int weight: 50
            property real lineHeight: 36
        }
        property QtObject headlineSmall: QtObject {
            property string family: "Roboto"
            property int size: 17
            property int weight: 57
            property real lineHeight: 22
        }
        property QtObject titleLarge: QtObject {
            property string family: "Roboto"
            property int size: 20
            property int weight: 57
            property real lineHeight: 26
        }
        property QtObject titleMedium: QtObject {
            property string family: "Roboto"
            property int size: 16
            property int weight: 57
            property real lineHeight: 24
        }
        property QtObject titleSmall: QtObject {
            property string family: "Roboto"
            property int size: 14
            property int weight: 57
            property real lineHeight: 20
        }
        property QtObject labelLarge: QtObject {
            property string family: "Roboto"
            property int size: 17
            property int weight: 57
            property real lineHeight: 22
        }
        property QtObject labelMedium: QtObject {
            property string family: "Roboto"
            property int size: 14
            property int weight: 57
            property real lineHeight: 20
        }
        property QtObject labelSmall: QtObject {
            property string family: "Roboto"
            property int size: 12
            property int weight: 57
            property real lineHeight: 16
        }
        property QtObject bodyLarge: QtObject {
            property string family: "Roboto"
            property int size: 16
            property int weight: 50
            property real lineHeight: 24
        }
        property QtObject bodyMedium: QtObject {
            property string family: "Roboto"
            property int size: 14
            property int weight: 50
            property real lineHeight: 20
        }
        property QtObject bodySmall: QtObject {
            property string family: "Roboto"
            property int size: 12
            property int weight: 50
            property real lineHeight: 16
        }
    }
    property QtObject shape: QtObject {
        property int extraSmall: 8
        property int small: 12
        property int cornerSmall: 12
        property int cornerMedium: 16
        property int cornerLarge: 16
        property int cornerExtraLarge: 28
        property int cornerFull: 999
    }
}
