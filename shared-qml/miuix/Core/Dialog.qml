import QtQuick
import miuix.Core

Item {
    id: dialogRoot
    property string title: ""
    property string text: ""
    property string icon: ""
    property string acceptText: "OK"
    property string rejectText: "Cancel"
    property string rejectIcon: ""
    property string neutralText: ""
    property bool showAcceptButton: true
    property bool showRejectButton: true
    property bool showNeutralButton: false
    property bool closeOnScrim: true
    property bool closeOnEscape: true
    property var _previousFocus: null
    function activeItem(item) {
        if (item.activeFocus) return item
        for (var i = 0; i < item.children.length; i++) {
            var found = activeItem(item.children[i])
            if (found) return found
        }
        return null
    }
    property real maxWidth: 420
    property real outsideMargin: 12
    property real padding: 24
    property real cornerRadius: 32
    property real topInset: 0
    property real bottomInset: 0
    property int resizeDuration: 240
    property bool largeScreen: overlayLayer.width >= 840 && overlayLayer.height >= 480
    readonly property bool opened: overlayLayer.visible
    readonly property bool compactActionLayout: actionRow.stacked
    default property alias content: contentPlaceholder.data
    signal accepted()
    signal rejected()
    signal neutral()
    signal closed()

    visible: false
    property real _progress: 0
    property bool _closing: false
    readonly property real _availableHeight: Math.max(0, overlayLayer.height - topInset - bottomInset - outsideMargin * 2)

    function open() {
        if (opened && !_closing) return
        var host = dialogRoot
        while (host.parent) host = host.parent
        if (!opened) _previousFocus = activeItem(host)
        exitAnimation.stop()
        _closing = false
        overlayLayer.parent = host
        overlayLayer.anchors.fill = host
        if (!opened) _progress = 0
        overlayLayer.visible = true
        overlayLayer.forceActiveFocus()
        enterAnimation.start()
    }
    function close() {
        if (!opened || _closing) return
        enterAnimation.stop()
        _closing = true
        exitAnimation.start()
    }

    NumberAnimation {
        id: enterAnimation
        target: dialogRoot
        property: "_progress"
        to: 1
        duration: 350
        easing.type: Easing.OutCubic
    }
    NumberAnimation {
        id: exitAnimation
        target: dialogRoot
        property: "_progress"
        to: 0
        duration: 260
        easing.type: Easing.OutCubic
        onFinished: {
            overlayLayer.visible = false
            overlayLayer.anchors.fill = undefined
            overlayLayer.parent = dialogRoot
            dialogRoot._closing = false
            if (dialogRoot._previousFocus) dialogRoot._previousFocus.forceActiveFocus()
            dialogRoot._previousFocus = null
            dialogRoot.closed()
        }
    }

    Item {
        id: overlayLayer
        Keys.onEscapePressed: { if (dialogRoot.closeOnEscape) dialogRoot.close(); event.accepted = true }
        visible: false
        z: 99999
        Rectangle {
            anchors.fill: parent
            color: Theme.color.windowDimming
            opacity: dialogRoot._progress
        }
        MouseArea {
            anchors.fill: parent
            onClicked: if (dialogRoot.closeOnScrim) dialogRoot.close()
            onWheel: (wheel) => { wheel.accepted = true }
        }
        Item {
            id: panel
            objectName: "miuixDialogPanel"
            width: Math.max(0, Math.min(dialogRoot.maxWidth, overlayLayer.width - dialogRoot.outsideMargin * 2))
            height: Math.min(bodyColumn.height + actions.height + dialogRoot.padding * 2 + (actions.height > 0 ? 12 : 0),
                dialogRoot.largeScreen ? dialogRoot._availableHeight * 2 / 3 : dialogRoot._availableHeight)
            Behavior on height {
                enabled: overlayLayer.visible && dialogRoot._progress >= 1 && !dialogRoot._closing
                NumberAnimation { duration: dialogRoot.resizeDuration; easing.type: Easing.OutCubic }
            }
            x: (overlayLayer.width - width) / 2
            y: dialogRoot.largeScreen
                ? dialogRoot.topInset + (dialogRoot._availableHeight - height) / 2 + dialogRoot.outsideMargin
                : overlayLayer.height - dialogRoot.bottomInset - dialogRoot.outsideMargin - height
                    + (1 - dialogRoot._progress) * overlayLayer.height
            scale: dialogRoot.largeScreen ? 0.8 + 0.2 * dialogRoot._progress : 1
            opacity: dialogRoot.largeScreen ? dialogRoot._progress : 1
            SmoothRectangle {
                anchors.fill: parent
                radius: dialogRoot.cornerRadius
                color: Theme.color.background
            }
            MouseArea {
                anchors.fill: parent
                onWheel: (wheel) => { wheel.accepted = true }
            }
            Flickable {
                id: bodyViewport
                objectName: "miuixDialogViewport"
                x: dialogRoot.padding
                y: dialogRoot.padding
                width: Math.max(0, panel.width - dialogRoot.padding * 2)
                height: Math.max(0, panel.height - dialogRoot.padding * 2 - actions.height - (actions.height > 0 ? 12 : 0))
                contentWidth: width
                contentHeight: bodyColumn.height
                flickableDirection: "VerticalFlick"
                clip: true
                Column {
                    id: bodyColumn
                    width: bodyViewport.width
                    spacing: 12
                    Icon {
                        name: dialogRoot.icon
                        visible: name.length > 0
                        width: 28
                        height: 28
                        x: (parent.width - width) / 2
                        color: Theme.color.primary
                    }
                    Text {
                        width: parent.width
                        visible: text.length > 0
                        text: dialogRoot.title
                        font.pixelSize: 18
                        font.weight: 57
                        horizontalAlignment: Text.AlignHCenter
                        wrapMode: Text.Wrap
                        color: Theme.color.onBackground
                    }
                    Text {
                        width: parent.width
                        visible: text.length > 0
                        text: dialogRoot.text
                        font.pixelSize: 16
                        horizontalAlignment: Text.AlignHCenter
                        wrapMode: Text.Wrap
                        color: Theme.color.onSurfaceSecondary
                    }
                    Item {
                        id: contentPlaceholder
                        width: parent.width
                        height: childrenRect.height
                        visible: children.length > 0
                    }
                }
            }
            Column {
                id: actions
                x: dialogRoot.padding
                y: panel.height - dialogRoot.padding - height
                width: Math.max(0, panel.width - dialogRoot.padding * 2)
                spacing: 12
                Button {
                    width: parent.width
                    visible: dialogRoot.showNeutralButton
                    text: dialogRoot.neutralText
                    type: "filledTonal"
                    onClicked: { dialogRoot.neutral(); dialogRoot.close() }
                }
                Item {
                    id: actionRow
                    width: parent.width
                    property bool stacked: dialogRoot.showAcceptButton && dialogRoot.showRejectButton
                        && width < acceptButton.implicitWidth + rejectButton.implicitWidth + 12
                    height: stacked ? 108 : (dialogRoot.showAcceptButton || dialogRoot.showRejectButton ? 48 : 0)
                    property real buttonWidth: !stacked && dialogRoot.showAcceptButton && dialogRoot.showRejectButton
                        ? Math.max(0, (width - 12) / 2) : width
                    Button {
                        id: rejectButton
                        objectName: "miuixDialogReject"
                        width: actionRow.buttonWidth
                        height: 48
                        visible: dialogRoot.showRejectButton
                        text: dialogRoot.rejectText
                        icon: dialogRoot.rejectIcon
                        type: "filledTonal"
                        onClicked: { dialogRoot.rejected(); dialogRoot.close() }
                    }
                    Button {
                        id: acceptButton
                        objectName: "miuixDialogAccept"
                        x: !actionRow.stacked && dialogRoot.showRejectButton ? actionRow.buttonWidth + 12 : 0
                        y: actionRow.stacked ? 60 : 0
                        width: actionRow.buttonWidth
                        height: 48
                        visible: dialogRoot.showAcceptButton
                        text: dialogRoot.acceptText
                        onClicked: { dialogRoot.accepted(); dialogRoot.close() }
                    }
                }
            }
        }
    }
}
