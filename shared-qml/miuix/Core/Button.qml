import QtQuick
import miuix.Core
Item {
    id: control

    property string text: ""
    property string icon: ""
    property string type: "filled"
    property bool enabled: true
    property real horizontalPadding: type === "text" ? 12 : 16
    property real verticalPadding: 13
    property real cornerRadius: Theme.metrics.buttonRadius
    property real spacing: 8
    property bool hovered: enabled && pressArea.containsMouse
    property bool pressed: enabled && pressArea.pressed
    property bool focused: enabled && activeFocus
    property Item contentItem
    signal clicked()
    activeFocusOnTab: true
    Keys.onReturnPressed: { if (enabled) clicked(); event.accepted = true }
    Keys.onSpacePressed: { if (enabled) clicked(); event.accepted = true }

    property var _colors: Theme.color

    implicitWidth: Math.max((contentItem ? contentItem.implicitWidth : contentRow.width) + horizontalPadding * 2, 58)
    implicitHeight: Math.max(40, (contentItem ? contentItem.implicitHeight : contentRow.height) + verticalPadding * 2)

    onContentItemChanged: {
        if (contentItem) {
            contentItem.parent = backgroundRect
            contentItem.anchors.centerIn = backgroundRect
        }
    }

    property color containerColor: {
        if (!enabled) {
            if (type === "filled") return _colors.disabledPrimaryButton
            if (type === "text" || type === "outlined") return "transparent"
            return _colors.disabledSecondaryVariant
        }
        switch (type) {
            case "elevated": return _colors.surfaceContainer
            case "filled": return _colors.primary
            case "filledTonal": return _colors.secondaryVariant
            case "outlined": return "transparent"
            case "text": return "transparent"
            default: return _colors.primary
        }
    }

    property color contentColor: {
        if (!enabled) {
            if (type === "filled") return _colors.disabledOnPrimaryButton
            return _colors.disabledOnSecondaryVariant
        }
        switch (type) {
            case "elevated": return _colors.primary
            case "filled": return _colors.onPrimary
            case "filledTonal": return _colors.onSecondaryVariant
            case "outlined": return _colors.primary
            case "text": return _colors.onSecondaryVariant
            default: return _colors.onPrimary
        }
    }

    SmoothRectangle {
        id: backgroundRect
        anchors.fill: parent
        radius: control.cornerRadius
        color: containerColor
        borderWidth: type === "outlined" ? 1 : 0
        borderColor: enabled ? _colors.outline : _colors.disabledSecondaryVariant

        SmoothRectangle {
            objectName: "miuixButtonStateLayer"
            anchors.fill: parent
            radius: parent.radius
            color: _colors.onBackground
            opacity: (control.pressed ? 0.10 : 0) + (control.hovered ? 0.06 : 0) + (control.focused ? 0.08 : 0)
            Behavior on opacity { NumberAnimation { duration: control.pressed ? 120 : 200; easing.type: Easing.OutCubic } }
        }

        Row {
            id: contentRow
            visible: !control.contentItem
            anchors.centerIn: parent
            spacing: control.spacing

            Text {
                text: control.icon
                font.family: Theme.iconFont.name
                font.pixelSize: 18
                color: contentColor
                visible: control.icon !== ""
                anchors.verticalCenter: parent.verticalCenter
            }

            Text {
                text: control.text
                font.family: Theme.typography.labelLarge.family
                font.pixelSize: 17
                font.weight: 57
                color: contentColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }

    MouseArea {
        id: pressArea
        anchors.fill: parent
        enabled: control.enabled
        hoverEnabled: true
        onPressed: control.focus = false
        onClicked: control.clicked()
    }
}
