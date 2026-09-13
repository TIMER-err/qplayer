import QtQuick
import QtQuick.Effects
import miuix.Core

// Ports miuix basic/Surface.kt: a shaped, colored box that clips its content,
// optionally draws a border/shadow and reacts to clicks with MiuixIndication
// (a flat overlay, not a wave ripple).
//
// The id is deliberately NOT `root`: a Surface hosts arbitrary user content, and
// that content's own `root.<x>` references must keep resolving to the user's root.
Item {
    id: surfaceRoot

    // SurfaceDefaults.Shape is RectangleShape, i.e. no rounding.
    property real radius: 0
    property color containerColor: Theme.color.surface
    property alias color: surfaceRoot.containerColor
    property color contentColor: Theme.color.onSurfaceColor
    // Compose passes a nullable BorderStroke; width 0 means "no border".
    property real borderWidth: 0
    property color borderColor: Theme.color.outline
    property real shadowElevation: 0
    // The clickable overload of Surface().
    property bool clickable: false
    property bool enabled: true
    default property alias content: contentContainer.data
    signal clicked()

    // qml4j divergence: upstream Box(propagateMinConstraints = true) wraps its
    // content. Deriving the size from childrenRect here feeds back through any
    // child that fills the Surface, so a Surface takes its size from anchors or
    // an explicit width/height instead.

    // qml4j divergence: Compose gets the shadow from graphicsLayer(shadowElevation);
    // here it is a blurred copy of the background drawn underneath it. Kept behind a
    // Loader so a flat Surface carries no effect node at all.
    Loader {
        anchors.fill: parent
        active: surfaceRoot.shadowElevation > 0
        sourceComponent: Component {
            Item {
                Rectangle {
                    id: shadowSource
                    anchors.fill: parent
                    radius: surfaceRoot.radius
                    color: surfaceRoot.containerColor
                    visible: false
                }
                MultiEffect {
                    anchors.fill: parent
                    source: shadowSource
                    shadowEnabled: true
                    shadowColor: Theme.color.shadow
                    shadowBlur: 1.0
                    shadowVerticalOffset: surfaceRoot.shadowElevation
                    shadowOpacity: 0.2
                }
            }
        }
    }

    Rectangle {
        anchors.fill: parent
        radius: surfaceRoot.radius
        color: surfaceRoot.containerColor
        border.width: surfaceRoot.borderWidth
        border.color: surfaceRoot.borderColor
    }

    Rectangle {
        id: maskRect
        anchors.fill: parent
        radius: surfaceRoot.radius
        color: "#000000"
        visible: false
    }

    Item {
        id: contentContainer
        anchors.fill: parent
        layer.enabled: surfaceRoot.radius > 0
        layer.effect: MultiEffect {
            maskEnabled: true
            maskSource: maskRect
        }
    }

    // MiuixIndication: +0.06 hovered, +0.10 pressed, drawn over the content.
    Loader {
        anchors.fill: parent
        active: surfaceRoot.clickable
        sourceComponent: Component {
            Item {
                Rectangle {
                    anchors.fill: parent
                    radius: surfaceRoot.radius
                    color: Theme.color.onBackground
                    opacity: clickArea.pressed ? 0.10 : (clickArea.containsMouse ? 0.06 : 0)
                    Behavior on opacity { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                }
                MouseArea {
                    id: clickArea
                    anchors.fill: parent
                    enabled: surfaceRoot.enabled
                    hoverEnabled: true
                    onClicked: surfaceRoot.clicked()
                }
            }
        }
    }
}
