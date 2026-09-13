import QtQuick
import QtQuick.Effects
import miuix.Core

Item {
    id: toolbarRoot
    objectName: "miuixFloatingToolbar"

    property color color: Theme.color.surfaceContainer
    property real cornerRadius: 50
    property real outSidePaddingHorizontal: 12
    property real outSidePaddingVertical: 8
    property real shadowElevation: 4
    property bool showDivider: false
    default property alias content: contentContainer.data

    implicitWidth: contentContainer.childrenRect.width + outSidePaddingHorizontal * 2
    implicitHeight: contentContainer.childrenRect.height + outSidePaddingVertical * 2

    readonly property real _surfaceWidth: Math.max(0, toolbarRoot.width - outSidePaddingHorizontal * 2)
    readonly property real _surfaceHeight: Math.max(0, toolbarRoot.height - outSidePaddingVertical * 2)

    // Keep the shadow source in the same component scope as the toolbar.
    Rectangle {
        id: shadowSource
        x: toolbarRoot.outSidePaddingHorizontal
        y: toolbarRoot.outSidePaddingVertical
        width: toolbarRoot._surfaceWidth
        height: toolbarRoot._surfaceHeight
        radius: toolbarRoot.cornerRadius
        color: toolbarRoot.color
        visible: false
    }
    MultiEffect {
        anchors.fill: shadowSource
        source: shadowSource
        visible: toolbarRoot.shadowElevation > 0
        shadowEnabled: true
        shadowColor: Theme.color.shadow
        shadowBlur: 1.0
        shadowVerticalOffset: toolbarRoot.shadowElevation
        shadowOpacity: 0.2
    }

    SmoothRectangle {
        x: toolbarRoot.outSidePaddingHorizontal
        y: toolbarRoot.outSidePaddingVertical
        width: toolbarRoot._surfaceWidth
        height: toolbarRoot._surfaceHeight
        radius: toolbarRoot.cornerRadius
        color: toolbarRoot.color
        borderWidth: toolbarRoot.showDivider ? 1 : 0
        borderColor: Theme.color.dividerLine
    }

    Rectangle {
        id: contentMask
        x: toolbarRoot.outSidePaddingHorizontal
        y: toolbarRoot.outSidePaddingVertical
        width: toolbarRoot._surfaceWidth
        height: toolbarRoot._surfaceHeight
        radius: toolbarRoot.cornerRadius
        color: "#000000"
        visible: false
    }

    // Clipped to the capsule so a pressed/hovered child's highlight cannot spill
    // past the toolbar's rounded ends.
    Item {
        id: contentContainer
        x: toolbarRoot.outSidePaddingHorizontal
        y: toolbarRoot.outSidePaddingVertical
        width: toolbarRoot._surfaceWidth
        height: toolbarRoot._surfaceHeight
        layer.enabled: true
        layer.effect: MultiEffect {
            maskEnabled: true
            maskSource: contentMask
        }
    }
}
