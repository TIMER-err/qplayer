import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// 搜索页：空输入显示搜索历史 + 热门搜索，输入时实时搜索，结果可点击播放。
Item {
    id: page
    // 0 = 折叠(5条), 1 = 展开(30条), 2 = 展开(70条), 3 = 展开全部(100条)
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

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 12
            spacing: 4

            // Type selector + search field merged into one rounded bar (same
            // outlined-pill look as the 一起听 invite-link field), instead of a
            // separate ComboBox and TextField -- neither component exposes a
            // "no own background/half-rounded" mode, so this is a small custom
            // composite (bare TextInput, kept as `id: query` so every other
            // query.text reference in this file needs no change) rather than a
            // reskin of the shared components everywhere else in the app uses.
            // Plain anchors, NOT nested RowLayouts -- same reason SongRow.qml gives
            // for its own layout (a Layout-in-Layout's height propagation isn't
            // reliable in qml4j; see CLAUDE.md's qml4j-limits section). Every
            // segment below is positioned off searchBar's own edges instead.
            Item {
                id: searchBar
                Layout.fillWidth: true
                Layout.preferredHeight: 52
                Layout.alignment: Qt.AlignVCenter

                property int modeIndex: 0
                readonly property var modeLabels: ["歌曲", "专辑", "歌手"]
                readonly property var modeKeys: ["song", "album", "artist"]
                // Reserve room for the clear button's own slot on the right,
                // whether or not it's currently visible -- avoids a reactive
                // anchor-TARGET switch (input area anchored to parent.right vs.
                // clearBtn.left depending on visibility), which is untested here.
                property real clearSlotW: 36

                function selectMode(i) {
                    searchBar.modeIndex = i
                    player.setSearchMode(searchBar.modeKeys[i])
                    if (query.text.length > 0) page.runSearchNow(false)
                }

                // Real MD3 outlined-field border: a notch cut into the top stroke
                // for the floating label to sit ON (not just "near the top inside"),
                // same component TextField.qml's own outlined mode uses -- matches
                // the 一起听 invite-link field's look exactly (that's a plain
                // TextField{type:"outlined"}, same OutlinedBorder underneath).
                OutlinedBorder {
                    anchors.fill: parent
                    cornerRadius: height / 2
                    strokeWidth: 1
                    strokeColor: Theme.color.outline
                    notchVisible: inputArea.isFloating
                    notchX: inputArea.x - 4
                    notchWidth: floatLabel.width + 8
                }
                OutlinedBorder {
                    anchors.fill: parent
                    cornerRadius: height / 2
                    strokeWidth: 2
                    strokeColor: Theme.color.primary
                    notchVisible: inputArea.isFloating
                    notchX: inputArea.x - 4
                    notchWidth: floatLabel.width + 8
                    opacity: query.activeFocus ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: 150 } }
                }

                // Type selector segment.
                Item {
                    id: typeSeg
                    anchors.left: parent.left
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    anchors.leftMargin: 4
                    width: 84

                    Text {
                        id: typeLabel
                        anchors.verticalCenter: parent.verticalCenter
                        x: 16
                        text: searchBar.modeLabels[searchBar.modeIndex]
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.left: typeLabel.right
                        anchors.leftMargin: 2
                        text: "arrow_drop_down"
                        font.family: Theme.iconFont.name
                        font.pixelSize: 20
                        color: Theme.color.onSurfaceVariantColor
                    }

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: typeMenu.open(searchBar, 0, searchBar.height)
                    }
                }

                Rectangle {
                    anchors.left: typeSeg.right
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    anchors.topMargin: 10
                    anchors.bottomMargin: 10
                    width: 1
                    color: Theme.color.outlineVariant
                }

                Text {
                    id: searchIcon
                    anchors.left: typeSeg.right
                    anchors.leftMargin: 13
                    anchors.verticalCenter: parent.verticalCenter
                    text: "search"
                    font.family: Theme.iconFont.name
                    font.pixelSize: 20
                    color: Theme.color.onSurfaceVariantColor
                }

                Item {
                    id: inputArea
                    anchors.left: searchIcon.right
                    anchors.leftMargin: 8
                    anchors.right: parent.right
                    anchors.rightMargin: searchBar.clearSlotW
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom

                    // MD3 floating label: centered like a placeholder while empty
                    // and unfocused, floats up to sit ON the outline's top stroke
                    // (through the OutlinedBorder notch above) once tapped/typed
                    // into -- the old TextField gave this field that behaviour for
                    // free; reimplemented by hand since the merged bar no longer
                    // wraps TextField (mirrors TextField.qml's own outlined-style
                    // label y/font.pixelSize transition and notch mechanism).
                    property bool isFloating: query.activeFocus || query.text.length > 0

                    Text {
                        id: floatLabel
                        x: 0
                        // Floating: straddle the top border stroke, like a real
                        // outlined field's label (-7 ~= half this label's own line
                        // height, so the 1-2px stroke passes through its middle).
                        y: inputArea.isFloating ? -7 : (inputArea.height - height) / 2
                        text: searchBar.modeIndex === 1 ? "搜索专辑"
                              : (searchBar.modeIndex === 2 ? "搜索歌手" : "搜索歌曲")
                        color: Theme.color.onSurfaceVariantColor
                        opacity: inputArea.isFloating ? 0.8 : 0.7
                        font.family: Theme.typography.bodyLarge.family
                        font.pixelSize: inputArea.isFloating ? 11 : 15
                        Behavior on y { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
                        Behavior on font.pixelSize { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
                    }

                    // Real-time search on every keystroke. searchLocal is a
                    // synchronous in-memory filter, but large libraries still
                    // make it expensive enough to debounce together with the
                    // two network sources.
                    TextInput {
                        id: query
                        anchors.fill: parent
                        // The floating label lives OUTSIDE this box now (on the
                        // outline's notch, y < 0 -- see floatLabel above), not
                        // stacked above the input text inside it like a "filled"
                        // TextField's label does, so typed text always gets the
                        // full height centered, in both the floating and resting
                        // states.
                        verticalAlignment: TextInput.AlignVCenter
                        color: Theme.color.onSurfaceColor
                        font.pixelSize: 15
                        font.family: Theme.typography.bodyLarge.family
                        selectionColor: Theme.color.primary
                        selectedTextColor: Theme.color.onPrimaryColor
                        clip: true
                        onTextChanged: {
                            // Clear the previous query's mixed-source rows
                            // immediately and invalidate its in-flight requests
                            // before waiting for debounce.
                            player.prepareSearch(text)
                            if (text.length > 0) searchDebounce.restart()
                            else { searchDebounce.stop(); page.historyExpandLevel = 0 }
                        }
                        onAccepted: {
                            if (query.text.length > 0) page.runSearchNow(true)
                            Qt.inputMethod.hide()
                            query.focus = false
                        }
                    }
                }

                IconButton {
                    visible: query.text.length > 0
                    type: "standard"; icon: "close"
                    anchors.right: parent.right
                    anchors.rightMargin: 2
                    anchors.verticalCenter: parent.verticalCenter
                    onClicked: { query.text = ""; query.forceActiveFocus() }
                }

                Menu {
                    id: typeMenu
                    // Small rounded corner (cornerSmall, 8dp) instead of the
                    // default menu's cornerExtraSmall -- same variant
                    // PlaylistContextMenu.qml uses for its card-associated popup.
                    outlined: true
                    model: [
                        { text: "歌曲", action: function() { searchBar.selectMode(0) } },
                        { text: "专辑", action: function() { searchBar.selectMode(1) } },
                        { text: "歌手", action: function() { searchBar.selectMode(2) } }
                    ]
                }
            }

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "filled"; icon: "search"
                onClicked: {
                    if (query.text.length > 0) page.runSearchNow(true)
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

            property int rowH: 52
            // 分段展开: 5条(折叠) -> 30条 -> 70条 -> 100条(全部)
            property int collapsedCount: 5
            property int firstExpandCount: 30
            property int secondExpandCount: 70
            property int fullCount: 100
            property int histCount: player.searchHistory ? player.searchHistory.length : 0
            // 纯三元表达式而非 { ... } block：qml4j 对 block 属性绑定兼容性差，
            // block 绑定失败会导致 displayCount 失效、布局高度算错。
            property int displayCount: histCount === 0 ? 0 : (page.historyExpandLevel === 0 ? Math.min(collapsedCount, histCount) : (page.historyExpandLevel === 1 ? Math.min(firstExpandCount, histCount) : (page.historyExpandLevel === 2 ? Math.min(secondExpandCount, histCount) : Math.min(fullCount, histCount))))
            property int hotCount: player.hotSearches ? player.hotSearches.length : 0
            property bool hasHistory: player.searchHistory && player.searchHistory.length > 0
            // 显示展开/收起按钮的条件：有超过 5 条历史记录
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
                        text: "搜索历史"
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

                    // 收起按钮：level >= 1 时显示；中间等级(1/2)且还有更多可展开时与展开按钮各占一半
                    Rectangle {
                        visible: page.historyExpandLevel >= 1
                        anchors.left: parent.left
                        width: page.historyExpandLevel >= 1 && page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount) ? parent.width / 2 - 4 : parent.width
                        height: parent.height
                        radius: 8
                        color: collapseMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: "收起"
                            font.pixelSize: 14; color: Theme.color.primary
                        }
                        MouseArea {
                            id: collapseMA
                            anchors.fill: parent
                            onClicked: page.historyExpandLevel = 0
                        }
                    }

                    // 展开更多按钮：level <= 2 且仍有更多时显示
                    Rectangle {
                        visible: page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 0 ? hotArea.collapsedCount : (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount))
                        anchors.right: parent.right
                        width: page.historyExpandLevel >= 1 && page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount) ? parent.width / 2 - 4 : parent.width
                        height: parent.height
                        radius: 8
                        color: expandMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: "展开更多"
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
                    text: "热门搜索"
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
                        model: player.searchMode === "album" ? albumGrid.results : null
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
                        model: player.searchMode === "artist" ? artistGrid.results : null
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
