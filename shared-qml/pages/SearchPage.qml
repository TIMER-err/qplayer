import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// Search: history + hot keywords while empty, live results while typing.
Item {
    id: page
    // 0 = collapsed (5), 1 = 30, 2 = 70, 3 = everything (100)
    property int historyExpandLevel: 0

    // Album/artist result grid geometry (playlist-card style), shared by both
    // card grids below -- same responsive-column math as HomePage's playlist
    // grid / ArtistDetailPage's album grid.
    property real gridPad: 16
    property real gridGap: 12
    property real minCardTile: 130
    property int gridCols: Math.max(2, Math.floor((width - 2 * gridPad + gridGap) / (minCardTile + gridGap)))
    property real cardTile: (width - 2 * gridPad - (gridCols - 1) * gridGap) / gridCols
    property real cardH: cardTile + 56

    // Coalesce rapid IME edits into one network/local search. Previously
    // every individual composition update synchronously filtered the full local
    // library and also queued two network searches, which could stall the render
    // thread and retain many obsolete result/cover generations after repeated use.
    Timer {
        id: searchDebounce
        interval: 350
        repeat: false
        onTriggered: page.runSearch(false)
    }

    function runSearch(addHistory) {
        var text = query.text
        if (text.length === 0) return
        if (player.searchMode === "album") {
            player.searchAlbums(text)
        } else if (player.searchMode === "artist") {
            player.searchArtists(text)
        } else {
            player.search(text)
            player.searchLocal(text)
        }
        if (addHistory) player.addSearchHistory(text)
    }

    function runSearchNow(addHistory) {
        searchDebounce.stop()
        runSearch(addHistory)
    }

    Component.onCompleted: player.loadHotSearches()

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        ColumnLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.topMargin: 12
            Layout.bottomMargin: 12
            spacing: 12
            TextField {
                id: query
                objectName: "searchQuery"
                Layout.fillWidth: true
                label: i18n.t(searchBar.modeIndex === 1 ? "search.hint.albums"
                    : searchBar.modeIndex === 2 ? "search.hint.artists" : "search.hint.songs")
                leadingIcon: "search"
                onTextChanged: {
                    player.prepareSearch(text)
                    if (text.length > 0) searchDebounce.restart()
                    else { searchDebounce.stop(); page.historyExpandLevel = 0 }
                }
                onAccepted: {
                    if (query.text.length > 0) page.runSearchNow(true)
                    Qt.inputMethod.hide()
                }
            }
            TabRowWithContour {
                equalWidth: false
                id: searchBar
                Layout.fillWidth: true
                Layout.preferredHeight: 42
                property int modeIndex: selectedTabIndex
                tabs: [i18n.t("search.mode.songs"), i18n.t("search.mode.albums"), i18n.t("search.mode.artists")]
                onTabSelected: (index) => {
                    player.setSearchMode(["song", "album", "artist"][index])
                    if (query.text.length > 0) page.runSearchNow(false)
                }
            }
        }


        // --- History + Hot searches (shown when input is empty) ---
        // Explicit index-positioned rows in a plain Item, NOT a Column positioner:
        // qml4j lays Repeater delegates out by their own x/y, it does not flow
        // dynamically-created children through a positioner (same idiom as HomePage /
        // VirtualSongList). A Column here left the rows unpositioned/zero-width.
        Item {
            id: hotArea
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length === 0

            EmptyState {
                anchors.centerIn: parent
                visible: hotArea.histCount === 0 && hotArea.hotCount === 0
                icon: "search"
                title: i18n.t("nav.search")
                message: i18n.t("search.empty.desc")
            }
            property int rowH: 52
            // Staged expansion: 5 (collapsed) -> 30 -> 70 -> 100 (all)
            property int collapsedCount: 5
            property int firstExpandCount: 30
            property int secondExpandCount: 70
            property int fullCount: 100
            property int histCount: player.searchHistory ? player.searchHistory.length : 0
            // A pure conditional expression rather than a { ... } block: qml4j
            // handles block property bindings poorly, and a failed one would leave
            // displayCount stale and the layout height wrong.
            property int displayCount: histCount === 0 ? 0 : (page.historyExpandLevel === 0 ? Math.min(collapsedCount, histCount) : (page.historyExpandLevel === 1 ? Math.min(firstExpandCount, histCount) : (page.historyExpandLevel === 2 ? Math.min(secondExpandCount, histCount) : Math.min(fullCount, histCount))))
            property int hotCount: player.hotSearches ? player.hotSearches.length : 0
            property bool hasHistory: player.searchHistory && player.searchHistory.length > 0
            // The expand/collapse control only appears past 5 history entries.
            property bool showExpandToggle: histCount > collapsedCount

            // section y-offsets (explicit, no Column)
            property int histHeaderY: hasHistory ? 16 : 0
            property int histHeaderH: hasHistory ? 48 : 0
            property int histRowsY: histHeaderY + histHeaderH
            property int histRowsH: displayCount * rowH
            property int expandY: histRowsY + histRowsH
            property int expandH: showExpandToggle ? 40 : 0
            property int dividerY: expandY + expandH + (hasHistory && hotCount > 0 ? 8 : 0)
            property int dividerH: hasHistory && hotCount > 0 ? 1 : 0
            property int hotHeaderY: dividerY + dividerH + (hotCount > 0 ? 8 : 0)
            property int hotHeaderH: hotCount > 0 ? 40 : 0
            property int hotRowsY: hotHeaderY + hotHeaderH
            property int totalH: hotRowsY + hotCount * rowH + 16

            Flickable {
                anchors.fill: parent
                clip: true
                contentWidth: width
                contentHeight: hotArea.totalH

                // --- History header ---
                Item {
                    x: 16; y: hotArea.histHeaderY
                    width: hotArea.width - 32
                    height: hotArea.histHeaderH
                    visible: hotArea.hasHistory

                    Text {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        text: i18n.t("search.history")
                        font.pixelSize: 18
                        font.weight: Font.DemiBold
                        color: Theme.color.onSurfaceColor
                    }
                    IconButton {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        type: "standard"; icon: "delete_sweep"
                        onClicked: player.clearSearchHistory()
                    }
                }

                // --- History rows ---
                Item {
                    x: 16; y: hotArea.histRowsY
                    width: hotArea.width - 32
                    height: hotArea.histRowsH

                    Repeater {
                        model: hotArea.displayCount

                        Item {
                            width: hotArea.width - 32
                            height: hotArea.rowH
                            y: index * hotArea.rowH

                            Rectangle {
                                x: 0; y: 4
                                width: parent.width; height: parent.height - 8
                                radius: 14
                                color: Theme.color.surfaceContainerLow
                                border.width: historyOpenRipple.containsMouse || historyRemoveRipple.containsMouse ? 1.5 : 1
                                border.color: historyOpenRipple.containsMouse || historyRemoveRipple.containsMouse
                                              ? Theme.color.outline
                                              : Theme.color.outlineVariant

                                Rectangle {
                                    anchors.fill: parent
                                    radius: parent.radius
                                    color: Theme.color.onSurfaceColor
                                    opacity: historyOpenRipple.containsMouse || historyRemoveRipple.containsMouse ? 0.04 : 0
                                    Behavior on opacity {
                                        NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
                                    }
                                }
                            }

                            Rectangle {
                                x: 10; width: 32; height: 32; radius: 16
                                anchors.verticalCenter: parent.verticalCenter
                                color: Theme.color.secondaryContainer

                                Text {
                                    anchors.centerIn: parent
                                    text: "history"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 18
                                    color: Theme.color.onSecondaryContainerColor
                                }
                            }
                            Text {
                                x: 54; width: parent.width - 54 - 48
                                anchors.verticalCenter: parent.verticalCenter
                                text: player.searchHistory && player.searchHistory[index] ? player.searchHistory[index] : ""
                                font.pixelSize: 15
                                font.weight: Font.Medium
                                color: Theme.color.onSurfaceColor
                                elide: Text.ElideRight
                            }

                            Rectangle {
                                x: parent.width - 45
                                width: 1; height: 20
                                anchors.verticalCenter: parent.verticalCenter
                                color: Theme.color.outlineVariant
                                opacity: 0.75
                            }
                            Text {
                                x: parent.width - 44; width: 44
                                anchors.verticalCenter: parent.verticalCenter
                                horizontalAlignment: Text.AlignHCenter
                                text: "close"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 18
                                color: historyRemoveRipple.containsMouse
                                       ? Theme.color.error
                                       : Theme.color.onSurfaceVariantColor
                                Behavior on color { ColorAnimation { duration: 120 } }
                            }

                            Ripple {
                                id: historyOpenRipple
                                x: 0; y: 4
                                width: parent.width - 44; height: parent.height - 8
                                clipTopLeftRadius: 14
                                clipBottomLeftRadius: 14
                                rippleColor: Theme.color.onSurfaceColor
                                onClicked: {
                                    var kw = player.searchHistory && player.searchHistory[index] ? player.searchHistory[index] : ""
                                    if (kw.length > 0) {
                                        query.text = kw
                                        page.runSearchNow(true)
                                    }
                                }
                            }
                            Ripple {
                                id: historyRemoveRipple
                                x: parent.width - 44; y: 4
                                width: 44; height: parent.height - 8
                                clipTopRightRadius: 14
                                clipBottomRightRadius: 14
                                rippleColor: Theme.color.error
                                onClicked: player.removeSearchHistory(index)
                            }
                        }
                    }
                }

                // --- Expand / collapse buttons ---
                Item {
                    x: 16; y: hotArea.expandY
                    width: hotArea.width - 32; height: hotArea.expandH
                    visible: hotArea.showExpandToggle

                    // Collapse: shown from level 1 up; at the middle levels it shares
                    // the row with the expand control.
                    Rectangle {
                        visible: page.historyExpandLevel >= 1
                        anchors.left: parent.left
                        width: page.historyExpandLevel >= 1 && page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount) ? parent.width / 2 - 4 : parent.width
                        height: parent.height
                        radius: 8
                        color: collapseMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: i18n.t("common.collapse")
                            font.pixelSize: 14; color: Theme.color.primary
                        }
                        MouseArea {
                            id: collapseMA
                            anchors.fill: parent
                            onClicked: page.historyExpandLevel = 0
                        }
                    }

                    // Expand: shown up to level 2 while more entries remain.
                    Rectangle {
                        visible: page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 0 ? hotArea.collapsedCount : (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount))
                        anchors.right: parent.right
                        width: page.historyExpandLevel >= 1 && page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount) ? parent.width / 2 - 4 : parent.width
                        height: parent.height
                        radius: 8
                        color: expandMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: i18n.t("search.expandMore")
                            font.pixelSize: 14; color: Theme.color.primary
                        }
                        MouseArea {
                            id: expandMA
                            anchors.fill: parent
                            onClicked: {
                                if (page.historyExpandLevel === 0) page.historyExpandLevel = 1
                                else if (page.historyExpandLevel === 1) page.historyExpandLevel = 2
                                else if (page.historyExpandLevel === 2) page.historyExpandLevel = 3
                            }
                        }
                    }
                }

                // --- Divider ---
                Rectangle {
                    x: 16; y: hotArea.dividerY
                    width: hotArea.width - 32; height: hotArea.dividerH
                    color: Theme.color.outlineVariant
                    visible: hotArea.dividerH > 0
                }

                // --- Hot searches header ---
                Text {
                    x: 16; y: hotArea.hotHeaderY
                    text: i18n.t("search.hot")
                    font.pixelSize: 18; font.weight: Font.DemiBold
                    color: Theme.color.onSurfaceColor
                    visible: hotArea.hotCount > 0
                }

                // --- Hot search rows ---
                Item {
                    x: 0; y: hotArea.hotRowsY
                    width: hotArea.width
                    height: hotArea.hotCount * hotArea.rowH

                    Repeater {
                        model: player.hotSearches

                        Item {
                            id: hotRow
                            width: hotArea.width
                            height: hotArea.rowH
                            y: index * hotArea.rowH
                            property string keyword: modelData ? modelData.toString() : ""

                            Rectangle {
                                x: 16; y: 4
                                width: parent.width - 32; height: parent.height - 8
                                radius: 14
                                color: Theme.color.surfaceContainerLow
                                border.width: hotRipple.containsMouse ? 1.5 : 1
                                border.color: hotRipple.containsMouse
                                              ? Theme.color.outline
                                              : Theme.color.outlineVariant

                                Rectangle {
                                    anchors.fill: parent
                                    radius: parent.radius
                                    color: Theme.color.onSurfaceColor
                                    opacity: hotRipple.containsMouse ? 0.04 : 0
                                    Behavior on opacity {
                                        NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
                                    }
                                }
                            }

                            Rectangle {
                                x: 26; width: 32; height: 32; radius: 16
                                anchors.verticalCenter: parent.verticalCenter
                                color: index < 3
                                       ? Theme.color.primaryContainer
                                       : Theme.color.surfaceContainerHighest

                                Text {
                                    anchors.centerIn: parent
                                    text: (index + 1).toString()
                                    font.pixelSize: 13
                                    font.weight: Font.DemiBold
                                    color: index < 3
                                           ? Theme.color.onPrimaryContainerColor
                                           : Theme.color.onSurfaceVariantColor
                                }
                            }

                            Text {
                                x: 70; width: parent.width - 70 - 52
                                anchors.verticalCenter: parent.verticalCenter
                                text: hotRow.keyword
                                font.pixelSize: 15
                                font.weight: Font.Medium
                                color: Theme.color.onSurfaceColor
                                elide: Text.ElideRight
                            }

                            Text {
                                x: parent.width - 48; width: 24
                                anchors.verticalCenter: parent.verticalCenter
                                horizontalAlignment: Text.AlignHCenter
                                text: "arrow_outward"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 18
                                color: hotRipple.containsMouse
                                       ? Theme.color.primary
                                       : Theme.color.onSurfaceVariantColor
                                Behavior on color { ColorAnimation { duration: 120 } }
                            }

                            Ripple {
                                id: hotRipple
                                x: 16; y: 4
                                width: parent.width - 32; height: parent.height - 8
                                clipRadius: 14
                                rippleColor: Theme.color.onSurfaceColor
                                onClicked: {
                                    var kw = hotRow.keyword
                                    if (kw.length === 0) return
                                    query.text = kw
                                    page.runSearchNow(true)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Search results (shown when input is not empty) ---
        // Song mode keeps the unified list (enabled providers + local library);
        // album/artist mode use their own playlist-cover-style card grids.
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length > 0

            // One unified, always-scrollable list (providers first, then local;
            // player.searchRows is built in that order by
            // PlayerController.rebuildSearchRows()) instead of three independently
            // height-managed VirtualSongLists: those fought each other for space in
            // qml4j's ColumnLayout (which hands a fillHeight child whatever room is
            // left after already-placed siblings rather than pre-reserving room for
            // every sibling like real Qt does), squeezing whichever section came
            // after the fillHeight one down to nothing under a short window.
            //
            // SearchRow carries a canonical provider id or local path, so the same
            // right-click/long-press interaction remains
            // available even though all three sources share one visual list.
            VirtualSongList {
                id: unifiedResults
                anchors.fill: parent
                visible: player.searchMode === "song"
                list: player.searchMode === "song" ? player.searchRows : null
                songMenu: true
                menuEligibilityFromModel: true
                loadMoreEnabled: player.searchHasMore && !player.searchLoading
                onLoadMoreRequested: player.loadMoreSearch()
                onActivated: player.playSearchRow(unifiedResults.activatedIndex)
            }

            Flickable {
                id: albumGrid
                property real stride: page.cardH + page.gridGap
                property int totalRows: Math.ceil(count / page.gridCols)
                property int liveRows: Math.min(totalRows, Math.ceil(height / stride) + 3)
                property int firstRow: Math.max(0, Math.min(totalRows - liveRows, Math.floor(contentY / stride) - 1))
                onContentHeightChanged: contentY = Math.min(contentY, Math.max(0, contentHeight - height))
                anchors.fill: parent
                visible: player.searchMode === "album"
                clip: true
                contentWidth: width
                property var results: player.sourceContentActive
                                      ? player.sourceSearchAlbumResults : player.searchAlbumResults
                property int count: results ? results.length : 0
                contentHeight: Math.ceil(count / page.gridCols) * (page.cardH + page.gridGap) + page.gridGap

                Item {
                    width: albumGrid.width
                    height: albumGrid.contentHeight
                    cachedLayout: true

                    Repeater {
                        model: page.visible && player.searchMode === "album" ? albumGrid.results : null
                        windowStart: albumGrid.firstRow * page.gridCols
                        windowCount: albumGrid.liveRows * page.gridCols
                        AlbumCard {
                            albumId: modelData.id
                            tile: page.cardTile
                            x: page.gridPad + (index % page.gridCols) * (page.cardTile + page.gridGap)
                            y: page.gridGap + Math.floor(index / page.gridCols) * (page.cardH + page.gridGap)
                            name: modelData.name
                            count: modelData.trackCount
                            coverUrl: modelData.coverUrl
                            coverThumbPath: modelData.coverThumbPath || ""
                            onClicked: player.openMediaAlbum("" + modelData.id)
                        }
                    }
                }
            }

            Flickable {
                id: artistGrid
                property real stride: page.cardH + page.gridGap
                property int totalRows: Math.ceil(count / page.gridCols)
                property int liveRows: Math.min(totalRows, Math.ceil(height / stride) + 3)
                property int firstRow: Math.max(0, Math.min(totalRows - liveRows, Math.floor(contentY / stride) - 1))
                onContentHeightChanged: contentY = Math.min(contentY, Math.max(0, contentHeight - height))
                anchors.fill: parent
                visible: player.searchMode === "artist"
                clip: true
                contentWidth: width
                property var results: player.sourceContentActive
                                      ? player.sourceSearchArtistResults : player.searchArtistResults
                property int count: results ? results.length : 0
                contentHeight: Math.ceil(count / page.gridCols) * (page.cardH + page.gridGap) + page.gridGap

                Item {
                    width: artistGrid.width
                    height: artistGrid.contentHeight
                    cachedLayout: true

                    Repeater {
                        model: page.visible && player.searchMode === "artist" ? artistGrid.results : null
                        windowStart: artistGrid.firstRow * page.gridCols
                        windowCount: artistGrid.liveRows * page.gridCols
                        ArtistCard {
                            artistId: modelData.id
                            tile: page.cardTile
                            x: page.gridPad + (index % page.gridCols) * (page.cardTile + page.gridGap)
                            y: page.gridGap + Math.floor(index / page.gridCols) * (page.cardH + page.gridGap)
                            name: modelData.name
                            count: modelData.musicSize
                            coverUrl: modelData.coverUrl
                            coverThumbPath: modelData.coverThumbPath || ""
                            onClicked: player.openMediaArtist("" + modelData.id)
                        }
                    }
                }
            }
        }
    }
}
