pragma Singleton
import QtQuick

QtObject {
    // MD3 dynamic color: the active (light/dark) scheme from the StyleManager singleton,
    // a role -> hex map. Theme.color.primary etc. read map keys.
    property var color: StyleManager.currentScheme
    // Both schemes (role -> hex map), e.g. the Color page shows light and dark side by side.
    property QtObject schemes: QtObject {
        property var light: StyleManager.lightScheme
        property var dark: StyleManager.darkScheme
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
            property int size: 24
            property int weight: 50
            property real lineHeight: 32
        }
        property QtObject titleLarge: QtObject {
            property string family: "Roboto"
            property int size: 22
            property int weight: 50
            property real lineHeight: 28
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
            property int size: 14
            property int weight: 57
            property real lineHeight: 20
        }
        property QtObject labelMedium: QtObject {
            property string family: "Roboto"
            property int size: 12
            property int weight: 57
            property real lineHeight: 16
        }
        property QtObject labelSmall: QtObject {
            property string family: "Roboto"
            property int size: 11
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
        property int extraSmall: 4
        property int small: 8
        property int cornerSmall: 8
        property int cornerMedium: 12
        property int cornerLarge: 16
        property int cornerExtraLarge: 28
        property int cornerFull: 999
    }
}
