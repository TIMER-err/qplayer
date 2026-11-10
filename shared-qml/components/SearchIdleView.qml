import QtQuick
import miuix.Core
import design
import "."

// Search page body while the field is empty: recent searches as removable
// chips (two rows, expandable) and the hot-search ranking (accent numerals for
// the top three; two columns when there is room). Picking an entry publishes
// it through chosenKeyword + keywordChosen() (no parameterised signals).
Item {
    id: view
    property real pad: Ui.space4
    property real bottomContentInset: 0
    property bool historyExpanded: false
    property string chosenKeyword: ""
    signal keywordChosen()
    readonly property real contentY: flick.contentY

    readonly property real contentW: Math.max(0, width - 2 * pad)
    property int histCount: player.searchHistory ? player.searchHistory.length : 0
    property int hotCount: player.hotSearches ? player.hotSearches.length : 0
    property bool hasHistory: histCount > 0

    function choose(keyword) {
        if (keyword.length === 0) return
        view.chosenKeyword = keyword
        view.keywordChosen()
    }

    // ---- history chips --------------------------------------------------------
    // Chip widths come from each label's measured implicitWidth; the delegates
    // report them here and one binding flows them into rows.
    readonly property int chipH: 36
    readonly property int chipGap: Ui.space2
    readonly property int chipPadL: 14
    readonly property int chipCloseW: 34
    property var chipWidths: []
    property int chipRevision: 0
    function setChipWidth(i, w) {
        if (view.chipWidths[i] === w) return
        view.chipWidths[i] = w
        view.chipRevision++
    }
    property var chipLayout: {
        var rev = view.chipRevision
        var maxW = view.contentW
        var xs = []
        var ys = []
        var ws = []
        var x = 0
        var row = 0
        for (var i = 0; i < view.histCount; i++) {
            var w = Math.min(maxW, (view.chipWidths[i] || 0) + view.chipPadL + view.chipCloseW)
            if (x > 0 && x + w > maxW) {
                row++
                x = 0
            }
            xs.push(x)
            ys.push(row * (view.chipH + view.chipGap))
            ws.push(w)
            x += w + view.chipGap
        }
        return { xs: xs, ys: ys, ws: ws, rows: view.histCount > 0 ? row + 1 : 0, rev: rev }
    }
    readonly property int collapsedRows: 2
    property bool canExpand: chipLayout.rows > collapsedRows
    property int shownRows: historyExpanded ? chipLayout.rows : Math.min(chipLayout.rows, collapsedRows)
    property real chipsH: shownRows > 0 ? shownRows * chipH + (shownRows - 1) * chipGap : 0

    // ---- vertical rhythm -----------------------------------------------------
    property real histHdrY: Ui.space1
    property real chipsY: histHdrY + 40 + Ui.space1
    property real toggleY: chipsY + chipsH + Ui.space1
    property real toggleH: canExpand ? 36 : 0
    property real histBlockH: hasHistory ? toggleY + toggleH : 0
    property real hotHdrY: hasHistory ? histBlockH + Ui.space4 : Ui.space1
    property real hotRowsY: hotHdrY + 40 + Ui.space1
    property int hotCols: contentW >= 560 ? 2 : 1
    property int hotPerCol: Math.ceil(hotCount / hotCols)
    property real hotRowH: 48
    property real hotColGap: Ui.space6
    property real hotColW: (contentW - (hotCols - 1) * hotColGap) / hotCols
    property real totalH: (hotCount > 0 ? hotRowsY + hotPerCol * hotRowH : histBlockH) + Ui.space4

    EmptyState {
        objectName: "searchIdleEmptyState"
        visible: !view.hasHistory && view.hotCount === 0
        icon: "search"
        title: i18n.t("nav.search")
        message: i18n.t("search.empty.desc")
    }

    Flickable {
        id: flick
        anchors.fill: parent
        clip: true
        contentWidth: width
        contentHeight: view.totalH + view.bottomContentInset

        SectionHeader {
            x: view.pad
            y: view.histHdrY
            width: view.contentW
            visible: view.hasHistory
            title: i18n.t("search.history")
            actionText: i18n.t("search.history.clear")
            onActionRequested: player.clearSearchHistory()
        }

        Item {
            objectName: "searchHistoryChips"
            x: view.pad
            y: view.chipsY
            width: view.contentW
            height: view.chipsH
            visible: view.hasHistory
            clip: true

            Repeater {
                model: view.histCount
                Item {
                    id: chip
                    objectName: "searchHistoryChip" + index
                    property string keyword: player.searchHistory && player.searchHistory[index]
                                             ? "" + player.searchHistory[index] : ""
                    property real measured: chipLabel.implicitWidth
                    onMeasuredChanged: view.setChipWidth(index, measured)
                    Component.onCompleted: view.setChipWidth(index, measured)
                    x: view.chipLayout.xs[index] || 0
                    y: view.chipLayout.ys[index] || 0
                    width: view.chipLayout.ws[index] || 0
                    height: view.chipH

                    Rectangle {
                        anchors.fill: parent
                        radius: height / 2
                        color: Ui.surfaceRaised
                    }
                    Rectangle {
                        anchors.fill: parent
                        radius: height / 2
                        color: openArea.pressed ? Ui.surfacePressed : Ui.surfaceHover
                        opacity: openArea.containsMouse || openArea.pressed ? 1 : 0
                        Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                    }
                    Text {
                        id: chipLabel
                        x: view.chipPadL
                        y: (chip.height - implicitHeight) / 2
                        width: Math.max(0, chip.width - view.chipPadL - view.chipCloseW)
                        text: chip.keyword
                        color: Ui.textPrimary
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        font.pixelSize: Ui.textCallout
                    }
                    Glyph {
                        x: chip.width - view.chipCloseW + (view.chipCloseW - 4 - size) / 2
                        y: (chip.height - size) / 2
                        name: "close"
                        size: 16
                        color: removeArea.containsMouse ? Ui.danger : Ui.textTertiary
                    }
                    MouseArea {
                        id: openArea
                        x: 0
                        y: 0
                        width: Math.max(0, chip.width - view.chipCloseW)
                        height: chip.height
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: view.choose(chip.keyword)
                    }
                    MouseArea {
                        id: removeArea
                        objectName: "searchHistoryRemove"
                        x: Math.max(0, chip.width - view.chipCloseW)
                        y: 0
                        width: view.chipCloseW
                        height: chip.height
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: player.removeSearchHistory(index)
                    }
                }
            }
        }

        // Expand / collapse past two rows of chips.
        Item {
            objectName: "searchHistoryToggle"
            x: view.pad - Ui.space2
            y: view.toggleY
            width: toggleLabel.x + toggleLabel.implicitWidth + Ui.space3
            height: view.toggleH
            visible: view.hasHistory && view.canExpand

            Rectangle {
                anchors.fill: parent
                radius: Ui.radiusSm
                color: Ui.surfaceHover
                opacity: toggleArea.containsMouse ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
            }
            Glyph {
                id: toggleIcon
                x: Ui.space2
                y: (parent.height - size) / 2
                name: "expand_more"
                rotation: view.historyExpanded ? 180 : 0
                Behavior on rotation { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                size: 18
                color: Ui.textSecondary
            }
            Text {
                id: toggleLabel
                x: toggleIcon.x + toggleIcon.width + Ui.space1
                y: (parent.height - implicitHeight) / 2
                text: i18n.t(view.historyExpanded ? "common.collapse" : "search.expandMore")
                color: Ui.textSecondary
                font.pixelSize: Ui.textCallout
            }
            MouseArea {
                id: toggleArea
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: view.historyExpanded = !view.historyExpanded
            }
        }

        SectionHeader {
            x: view.pad
            y: view.hotHdrY
            width: view.contentW
            visible: view.hotCount > 0
            title: i18n.t("search.hot")
        }

        // Ranked hot searches, column-major so the ranking reads top-down.
        Repeater {
            model: player.hotSearches
            Item {
                id: hotRow
                objectName: "searchHotRow" + index
                property string keyword: modelData ? modelData.toString() : ""
                property int col: Math.floor(index / Math.max(1, view.hotPerCol))
                property int row: index % Math.max(1, view.hotPerCol)
                x: view.pad - Ui.space2 + col * (view.hotColW + view.hotColGap)
                y: view.hotRowsY + row * view.hotRowH
                width: view.hotColW + 2 * Ui.space2
                height: view.hotRowH

                Rectangle {
                    anchors.fill: parent
                    radius: Ui.radiusSm
                    color: hotArea.pressed ? Ui.surfacePressed : Ui.surfaceHover
                    opacity: hotArea.containsMouse || hotArea.pressed ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                }
                Text {
                    id: rank
                    x: Ui.space2
                    y: (hotRow.height - implicitHeight) / 2
                    width: 28
                    text: (index + 1).toString()
                    color: index < 3 ? Ui.accent : Ui.textTertiary
                    font.pixelSize: Ui.textHeadline
                    font.weight: Ui.weightBold
                }
                Text {
                    x: rank.x + rank.width + Ui.space2
                    y: (hotRow.height - implicitHeight) / 2
                    width: Math.max(0, hotRow.width - x - Ui.space2)
                    text: hotRow.keyword
                    color: Ui.textPrimary
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    font.pixelSize: Ui.textBody
                    font.weight: index < 3 ? Ui.weightBold : Ui.weightRegular
                }
                MouseArea {
                    id: hotArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: view.choose(hotRow.keyword)
                }
            }
        }

        ViewportScrollBar {
            target: flick
            bottomInset: view.bottomContentInset
        }
    }
}
