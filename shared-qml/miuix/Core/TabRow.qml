import QtQuick
import miuix.Core

Item {
    id: tabRowRoot

    property var tabs: []
    property int selectedTabIndex: 0
    property bool contour: false
    property bool equalWidth: contour
    property real minWidth: contour ? 62 : 76
    property real maxWidth: contour ? 84 : 98
    property real itemSpacing: contour ? 5 : 9
    property real cornerRadius: contour ? 8 : 12
    property color backgroundColor: contour ? Theme.color.surface : "transparent"
    property color contentColor: Theme.color.onSurfaceVariantSummary
    property color selectedBackgroundColor: Theme.color.surfaceContainer
    property color selectedContentColor: Theme.color.onBackground
    property bool selectOnClick: true
    signal tabSelected(int index)
    activeFocusOnTab: true
    Keys.onLeftPressed: { _select(Math.max(0, selectedTabIndex - 1)); event.accepted = true }
    Keys.onRightPressed: { _select(Math.min(tabs.length - 1, selectedTabIndex + 1)); event.accepted = true }
    property int _lastSelected: -1
    property real _indicatorX: 0
    property real _indicatorWidth: 0

    readonly property real _contourPadding: contour ? 5 : 0
    readonly property real _tabWidth: _calculateTabWidth()
    readonly property real _tabsWidth: equalWidth
        ? (tabs.length > 0 ? tabs.length * _tabWidth + (tabs.length - 1) * itemSpacing : 0)
        : tabsRow.implicitWidth
    readonly property real _contentWidth: _tabsWidth + _contourPadding * 2
    readonly property real _contentOffset: Math.max(0, (width - _contentWidth) / 2)

    implicitWidth: 320
    implicitHeight: contour ? 45 : 42

    function _calculateTabWidth() {
        var count = tabs.length
        if (count === 0) return minWidth
        var available = Math.max(0, width - _contourPadding * 2)
        var contentWidth = available - (count - 1) * itemSpacing
        if (contentWidth <= 0) return minWidth
        var ideal = contentWidth / count
        if (ideal < minWidth) return minWidth
        if (ideal > maxWidth) {
            var totalMax = maxWidth * count + (count - 1) * itemSpacing
            return totalMax < available ? ideal : maxWidth
        }
        return ideal
    }

    function _select(index) {
        if (!enabled || index < 0 || index >= tabs.length) return
        if (selectOnClick) selectedTabIndex = index
        tabSelected(index)
    }

    function _ensureSelected() {
        scrollAnimation.stop()
        if (tabs.length === 0) { viewport.contentX = 0; _lastSelected = -1; return }
        var index = Math.max(0, Math.min(selectedTabIndex, tabs.length - 1))
        var item = tabRepeater.itemAt(index)
        if (!item) return
        _indicatorX = _contourPadding + item.x
        _indicatorWidth = item.width
        var target = _indicatorX - (width - item.width) / 2
        var maxScroll = Math.max(0, viewport.contentWidth - viewport.width)
        target = Math.max(0, Math.min(maxScroll, target))
        if (_lastSelected >= 0 && _lastSelected !== selectedTabIndex) {
            scrollAnimation.to = target
            scrollAnimation.start()
        } else {
            viewport.contentX = target
        }
        _lastSelected = selectedTabIndex
    }

    onSelectedTabIndexChanged: settleTimer.restart()
    onWidthChanged: settleTimer.restart()
    onTabsChanged: settleTimer.restart()
    on_TabWidthChanged: settleTimer.restart()
    on_TabsWidthChanged: settleTimer.restart()
    onEqualWidthChanged: settleTimer.restart()
    onItemSpacingChanged: settleTimer.restart()
    Component.onCompleted: settleTimer.restart()

    SmoothRectangle {
        objectName: "miuixTabBackground"
        x: tabRowRoot._contentOffset
        width: Math.min(tabRowRoot.width, tabRowRoot._contentWidth)
        height: parent.height
        radius: tabRowRoot.contour ? tabRowRoot.cornerRadius + tabRowRoot._contourPadding : 0
        color: tabRowRoot.backgroundColor
    }

    Flickable {
        id: viewport
        objectName: "miuixTabViewport"
        anchors.fill: parent
        clip: true
        interactive: contentWidth > width
        flickableDirection: "HorizontalFlick"
        contentWidth: Math.max(width, tabRowRoot._tabsWidth + tabRowRoot._contourPadding * 2)
        contentHeight: height

        Item {
            id: tabContent
            x: tabRowRoot._contentOffset
            y: tabRowRoot._contourPadding
            width: tabRowRoot._contentWidth
            height: Math.max(0, viewport.height - tabRowRoot._contourPadding * 2)

            SmoothRectangle {
                objectName: "miuixTabIndicator"
                visible: tabRowRoot.selectedTabIndex >= 0 && tabRowRoot.selectedTabIndex < tabRowRoot.tabs.length
                x: tabRowRoot._indicatorX
                y: 0
                width: tabRowRoot._indicatorWidth
                height: parent.height
                radius: tabRowRoot.cornerRadius
                color: tabRowRoot.selectedBackgroundColor
                Behavior on x { NumberAnimation { duration: tabRowRoot.contour ? 200 : 0; easing.type: Easing.Linear } }
            }

            Row {
                id: tabsRow
                x: tabRowRoot._contourPadding
                width: tabRowRoot._tabsWidth
                height: parent.height
                spacing: tabRowRoot.itemSpacing

                Repeater {
                    id: tabRepeater
                    model: tabRowRoot.tabs
                    delegate: Item {
                        objectName: "miuixTabItem" + index
                        width: tabRowRoot.equalWidth ? tabRowRoot._tabWidth
                            : Math.max(tabRowRoot.minWidth, Math.ceil(widthProbe.implicitWidth) + 24)
                        height: tabsRow.height

                        // Measure the bold state so selecting a tab never changes
                        // its width or shifts the neighboring tabs.
                        Text {
                            id: widthProbe
                            text: modelData
                            font.family: Theme.typography.bodyMedium.family
                            font.pixelSize: tabRowRoot.contour ? 14 : 16
                            font.weight: Font.Bold
                            opacity: 0
                        }

                        SmoothRectangle {
                            anchors.fill: parent
                            radius: tabRowRoot.cornerRadius
                            color: "transparent"
                            borderWidth: tabRowRoot.contour || index === tabRowRoot.selectedTabIndex ? 0 : 1
                            borderColor: Theme.color.outline
                        }

                        Text {
                            objectName: "miuixTabLabel" + index
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.leftMargin: 12
                            anchors.rightMargin: 12
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData
                            elide: tabRowRoot.equalWidth ? Text.ElideRight : Text.ElideNone
                            maximumLineCount: 1
                            horizontalAlignment: Text.AlignHCenter
                            font.family: Theme.typography.bodyMedium.family
                            font.pixelSize: tabRowRoot.contour ? 14 : 16
                            font.weight: index === tabRowRoot.selectedTabIndex ? Font.Bold : Font.Normal
                            color: index === tabRowRoot.selectedTabIndex
                                ? tabRowRoot.selectedContentColor : tabRowRoot.contentColor
                        }

                        MouseArea {
                            anchors.fill: parent
                            enabled: tabRowRoot.enabled
                            onPressed: { scrollAnimation.stop(); settleTimer.stop() }
                            onClicked: tabRowRoot._select(index)
                        }
                    }
                }
            }
        }
    }

    NumberAnimation {
        id: scrollAnimation
        target: viewport
        property: "contentX"
        duration: 200
        easing.type: Easing.OutCubic
    }
    Timer {
        id: settleTimer
        interval: 40
        repeat: false
        onTriggered: tabRowRoot._ensureSelected()
    }
}
