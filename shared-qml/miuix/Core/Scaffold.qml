import QtQuick
import miuix.Core

// Ports miuix basic/Scaffold.kt: the basic miuix visual layout structure
// (top bar, bottom bar, FAB, floating toolbar, snackbar and popup host around
// a full-size body).
Item {
    id: scaffoldRoot

    property Component topBar: null
    property Component bottomBar: null
    property Component floatingActionButton: null
    // FabPosition: "start" | "center" | "end" | "endOverlay"
    property string floatingActionButtonPosition: "end"
    property Component floatingToolbar: null
    // ToolbarPosition: "topStart" | "centerStart" | "bottomStart" | "topEnd" |
    // "centerEnd" | "bottomEnd" | "topCenter" | "bottomCenter"
    property string floatingToolbarPosition: "bottomCenter"
    property Component snackbarHost: null
    property Component popupHost: null
    property color containerColor: Theme.color.surface

    // WindowInsets.systemBars ∪ WindowInsets.displayCutout upstream; qml4j has no
    // inset provider, so the host app feeds them in.
    property real topInset: 0
    property real bottomInset: 0
    property real leftInset: 0
    property real rightInset: 0

    default property alias content: contentContainer.data

    // The PaddingValues upstream hands to content(): bars win over insets.
    readonly property real contentTopPadding: _topBarEmpty ? topInset : topBarLoader.height
    readonly property real contentBottomPadding: _bottomBarEmpty ? bottomInset : bottomBarLoader.height
    readonly property real contentLeftPadding: leftInset
    readonly property real contentRightPadding: rightInset

    // FAB spacing above the bottom bar / bottom of the Scaffold.
    readonly property real _fabSpacing: 12
    // FloatingToolbar spacing above the bottom of the Scaffold.
    readonly property real _floatingToolbarSpacing: 4

    readonly property bool _topBarEmpty: !topBarLoader.item || (topBarLoader.width === 0 && topBarLoader.height === 0)
    readonly property bool _bottomBarEmpty: !bottomBarLoader.item || (bottomBarLoader.width === 0 && bottomBarLoader.height === 0)
    readonly property bool _fabEmpty: !fabLoader.item || (fabLoader.width === 0 && fabLoader.height === 0)
    readonly property bool _toolbarEmpty: !floatingToolbarLoader.item
        || (floatingToolbarLoader.width === 0 && floatingToolbarLoader.height === 0)

    readonly property real _fabLeft: {
        if (floatingActionButtonPosition === "start") return _fabSpacing + leftInset
        if (floatingActionButtonPosition === "end" || floatingActionButtonPosition === "endOverlay")
            return scaffoldRoot.width - _fabSpacing - fabLoader.width - rightInset
        return (scaffoldRoot.width - fabLoader.width + leftInset - rightInset) / 2
    }
    readonly property real _fabOffsetFromBottom: {
        if (_bottomBarEmpty || floatingActionButtonPosition === "endOverlay")
            return fabLoader.height + _fabSpacing + bottomInset
        return bottomBarLoader.height + fabLoader.height + _fabSpacing
    }

    readonly property real _toolbarAvailableWidth: scaffoldRoot.width - leftInset - rightInset
    readonly property real _toolbarAvailableHeight: scaffoldRoot.height - topBarLoader.height - topInset - bottomInset
    readonly property real _toolbarX: leftInset + _alignHorizontal(_toolbarAvailableWidth, floatingToolbarLoader.width)
    readonly property real _toolbarY: topBarLoader.height + topInset
        + _alignVertical(_toolbarAvailableHeight, floatingToolbarLoader.height) - _floatingToolbarSpacing
    readonly property bool _toolbarAtBottom: !_toolbarEmpty
        && (floatingToolbarPosition === "bottomStart"
            || floatingToolbarPosition === "bottomCenter"
            || floatingToolbarPosition === "bottomEnd")

    // The snackbar stays above whichever of FAB / bottom bar / docked toolbar is
    // closest to the bottom edge.
    readonly property real _snackbarBase: !_fabEmpty
        ? _fabOffsetFromBottom
        : (!_bottomBarEmpty ? bottomBarLoader.height : bottomInset)
    readonly property real _snackbarBottomMargin: Math.max(_snackbarBase,
        _toolbarAtBottom ? Math.max(0, scaffoldRoot.height - _toolbarY + _floatingToolbarSpacing) : 0)

    function _alignHorizontal(available, size) {
        var p = floatingToolbarPosition
        if (p === "topStart" || p === "centerStart" || p === "bottomStart") return 0
        if (p === "topEnd" || p === "centerEnd" || p === "bottomEnd") return available - size
        return (available - size) / 2
    }

    function _alignVertical(available, size) {
        var p = floatingToolbarPosition
        if (p === "topStart" || p === "topCenter" || p === "topEnd") return 0
        if (p === "centerStart" || p === "centerEnd") return (available - size) / 2
        return available - size
    }

    Surface {
        anchors.fill: parent
        containerColor: scaffoldRoot.containerColor

        // Declaration order mirrors the upstream place() order, which is what
        // gives each slot its drawing order.
        Item {
            id: contentContainer
            anchors.fill: parent
        }

        Loader {
            id: topBarLoader
            sourceComponent: scaffoldRoot.topBar
            x: 0
            y: 0
            width: scaffoldRoot.width
            height: item ? item.implicitHeight : 0
        }

        // qml4j divergence: upstream measures the snackbar and places it, while
        // Snackbar.qml anchors itself to its parent's bottom edge. The slot is
        // therefore a region ending where upstream would put the snackbar.
        Item {
            id: snackbarSlot
            x: scaffoldRoot.leftInset
            y: 0
            width: scaffoldRoot.width - scaffoldRoot.leftInset - scaffoldRoot.rightInset
            height: scaffoldRoot.height - scaffoldRoot._snackbarBottomMargin
            Loader {
                id: snackbarLoader
                anchors.fill: parent
                sourceComponent: scaffoldRoot.snackbarHost
            }
        }

        Loader {
            id: bottomBarLoader
            sourceComponent: scaffoldRoot.bottomBar
            x: 0
            y: scaffoldRoot.height - height
            width: scaffoldRoot.width
            height: item ? item.implicitHeight : 0
        }

        Loader {
            id: floatingToolbarLoader
            sourceComponent: scaffoldRoot.floatingToolbar
            x: scaffoldRoot._toolbarX
            y: scaffoldRoot._toolbarY
            width: item ? item.implicitWidth : 0
            height: item ? item.implicitHeight : 0
        }

        Loader {
            id: fabLoader
            sourceComponent: scaffoldRoot.floatingActionButton
            x: scaffoldRoot._fabLeft
            y: scaffoldRoot.height - scaffoldRoot._fabOffsetFromBottom
            width: item ? item.implicitWidth : 0
            height: item ? item.implicitHeight : 0
        }

        Loader {
            id: popupLoader
            sourceComponent: scaffoldRoot.popupHost
            anchors.fill: parent
        }
    }
}
