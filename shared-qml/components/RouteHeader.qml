import QtQuick
import miuix.Core
import design

// One header for every drill-in route: a single Back button, a title that can
// fade in once the page's hero has scrolled away, and right-aligned actions.
// Root destinations stay reachable through the sidebar / bottom navigation, so
// routes no longer carry a separate Home button.
Item {
    id: header
    property string title: ""
    // 0..1. Detail pages bind this to their scroll position so the title only
    // appears after the hero title has left the viewport.
    property real titleOpacity: 1
    // Draw a background and hairline once content scrolls underneath.
    property bool elevated: false
    property real gutter: Ui.gutter(width)
    default property alias actions: actionRow.data
    signal back()

    height: Ui.headerHeight

    Rectangle {
        anchors.fill: parent
        color: Ui.background
        opacity: header.elevated ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 160 } }
    }
    Rectangle {
        x: 0
        y: parent.height - 1
        width: parent.width
        height: 1
        color: Ui.hairline
        opacity: header.elevated ? 1 : 0
    }

    IconButton {
        id: backButton
        objectName: "routeBackButton"
        // The 22 px glyph is inset 9 px in the 40 px button: align the glyph,
        // not the button box, with the page gutter used by the content below.
        x: Math.max(4, header.gutter - 9)
        y: (header.height - height) / 2
        type: "standard"
        icon: "arrow_back"
        onClicked: header.back()
    }

    Text {
        id: titleText
        objectName: "routeHeaderTitle"
        x: backButton.x + backButton.width + 8
        y: (header.height - implicitHeight) / 2
        width: Math.max(0, actionRow.x - x - 12)
        text: header.title
        color: Ui.textPrimary
        opacity: header.titleOpacity
        visible: opacity > 0.01
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: Ui.textTitle2
        font.weight: Ui.weightBold
    }

    Row {
        id: actionRow
        x: header.width - width - Math.max(4, header.gutter - 9)
        y: (header.height - height) / 2
        height: Ui.iconButton
        spacing: 2
    }
}
