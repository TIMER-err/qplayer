import QtQuick
import miuix.Core
import design
import "."
import "../components"

// Search (root page under the "搜索" header): a pill search field and the mode
// pills stay pinned on top; below them either the idle view (history chips +
// ranked hot searches) or the live results for the selected mode. Every block
// is placed at explicit coordinates (no nested layouts) and long result sets
// are windowed.
Item {
    id: page
    property real bottomContentInset: 0
    property var pendingPlaylist
    signal openPlaylist()

    readonly property bool compact: width < Ui.compactMax
    property real pad: Ui.gutter(width)
    property real contentW: Math.max(0, width - 2 * pad)

    // Responsive, windowed card grids shared by catalog search results; same
    // tile rules as Home so cards look identical across pages.
    property real colGap: compact ? Ui.space3 : Ui.space4
    property real rowGap: Ui.space5
    property real minCardTile: 176
    property int gridCols: Math.max(2, Math.floor((contentW + colGap) / (minCardTile + colGap)))
    property real cardTile: (contentW - (gridCols - 1) * colGap) / gridCols
    // Same formula as the cards' own height (tile + caption slot).
    property real cardH: cardTile + Math.max(52, Math.round(72 * cardTile / 200))
    property real gridTop: Ui.space2

    // Pinned header: field, then the mode pills.
    property real fieldY: Ui.space2
    property real tabsY: fieldY + query.fieldHeight + Ui.space3
    property real headerH: tabsY + Ui.pillHeight + Ui.space3

    readonly property string mode: player.searchMode
    readonly property bool idle: query.text.length === 0

    // Coalesce rapid IME edits into one network/local search. Previously
    // every individual composition update synchronously filtered the full local
    // library and also queued two network searches, which could stall the render
    // thread and retain many obsolete result/cover generations after repeated use.
    Timer {
        id: searchDebounce
        interval: 350
        repeat: false
        onTriggered: page.runSearch()
    }

    function recordCurrentSearch() {
        var text = query.text
        if (text.length === 0) return
        player.addSearchHistory(text)
    }

    function runSearch() {
        var text = query.text
        if (text.length === 0) return
        if (player.searchMode === "album") {
            player.searchAlbums(text)
        } else if (player.searchMode === "artist") {
            player.searchArtists(text)
        } else if (player.searchMode === "playlist") {
            player.searchPlaylists(text)
        } else {
            player.search(text)
            player.searchLocal(text)
        }
    }

    function runSearchNow() {
        searchDebounce.stop()
        runSearch()
    }

    function searchFor(keyword) {
        if (keyword.length === 0) return
        query.text = keyword
        page.runSearchNow()
    }

    function selectMode(value) {
        if (player.searchMode === value) return
        player.setSearchMode(value)
        if (query.text.length > 0) page.runSearchNow()
    }

    Component.onCompleted: player.loadHotSearches()

    // ---- pinned header --------------------------------------------------------
    TextField {
        id: query
        objectName: "searchQuery"
        x: page.pad
        y: page.fieldY
        width: page.contentW
        z: 2
        cornerRadius: fieldHeight / 2
        backgroundColor: Ui.surfaceRaised
        labelColor: Ui.textSecondary
        useLabelAsPlaceholder: true
        label: i18n.t(player.searchMode === "album" ? "search.hint.albums"
            : player.searchMode === "artist" ? "search.hint.artists"
            : player.searchMode === "playlist" ? "search.hint.playlists" : "search.hint.songs")
        leadingIcon: "search"
        onTextChanged: {
            player.prepareSearch(text)
            if (text.length > 0) searchDebounce.restart()
            else { searchDebounce.stop(); idleView.historyExpanded = false }
        }
        onAccepted: {
            if (query.text.length > 0) page.runSearchNow()
            Qt.inputMethod.hide()
        }
    }

    // Mode pills: left-aligned with the field and the header title. Four fixed
    // items in a Row (no Repeater), so the positioner only sees static children.
    Row {
        id: modeTabs
        objectName: "searchModeTabs"
        x: page.pad
        y: page.tabsY
        z: 2
        height: Ui.pillHeight
        spacing: Ui.space2

        SearchModePill { text: i18n.t("search.mode.songs"); selected: page.mode === "song"; onClicked: page.selectMode("song") }
        SearchModePill { text: i18n.t("search.mode.albums"); selected: page.mode === "album"; onClicked: page.selectMode("album") }
        SearchModePill { text: i18n.t("search.mode.artists"); selected: page.mode === "artist"; onClicked: page.selectMode("artist") }
        SearchModePill { text: i18n.t("search.mode.playlists"); selected: page.mode === "playlist"; onClicked: page.selectMode("playlist") }
    }

    // Hairline under the pinned header once content scrolls beneath it.
    Rectangle {
        x: 0
        y: page.headerH - 1
        z: 2
        width: page.width
        height: 1
        color: Ui.hairline
        visible: page.idle ? idleView.contentY > 1
                 : (page.mode === "song" ? unifiedResults.contentY > 1
                    : page.mode === "album" ? albumGrid.contentY > 1
                    : page.mode === "artist" ? artistGrid.contentY > 1
                    : playlistGrid.contentY > 1)
    }

    // ---- idle: history + hot searches ----------------------------------------
    SearchIdleView {
        id: idleView
        x: 0
        y: page.headerH
        width: page.width
        height: Math.max(0, page.height - page.headerH)
        visible: page.idle
        pad: page.pad
        bottomContentInset: page.bottomContentInset
        onKeywordChosen: page.searchFor(idleView.chosenKeyword)
    }

    // ---- results ------------------------------------------------------------
    Item {
        id: resultsView
        x: 0
        y: page.headerH
        width: page.width
        height: Math.max(0, page.height - page.headerH)
        visible: !page.idle

        property int resultCount: page.mode === "song" ? unifiedResults.count
                                  : page.mode === "album" ? albumGrid.count
                                  : page.mode === "artist" ? artistGrid.count
                                  : playlistGrid.count
        // Results fade in as they arrive and out when a new query clears them.
        property real contentReveal: resultCount > 0 ? 1 : 0
        Behavior on contentReveal { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

        // One unified, always-scrollable list (providers first, then local;
        // player.searchRows is built in that order by
        // PlayerController.rebuildSearchRows()) instead of three independently
        // height-managed lists that fought each other for space.
        // SongRow insets its cover by 12px; shift the list so covers sit on the
        // page gutter like the field above.
        VirtualSongList {
            id: unifiedResults
            x: page.pad - 12
            width: page.width - 2 * (page.pad - 12)
            height: resultsView.height
            bottomContentInset: page.bottomContentInset
            visible: page.mode === "song"
            opacity: resultsView.contentReveal
            list: page.mode === "song" ? player.searchRows : null
            songMenu: true
            menuEligibilityFromModel: true
            showLikeButton: true
            loadMoreEnabled: player.searchHasMore && !player.searchLoading
            onLoadMoreRequested: player.loadMoreSearch()
            onActivated: {
                player.playSearchRow(unifiedResults.activatedIndex)
                page.recordCurrentSearch()
            }
            onLikeClicked: page.recordCurrentSearch()
            onDownloadClicked: page.recordCurrentSearch()
        }

        Flickable {
            id: albumGrid
            property real stride: page.cardH + page.rowGap
            property int totalRows: Math.ceil(count / page.gridCols)
            property int liveRows: Math.min(totalRows, Math.ceil(height / stride) + 3)
            property int firstRow: Math.max(0, Math.min(totalRows - liveRows, Math.floor(contentY / stride) - 1))
            onContentHeightChanged: contentY = Math.min(contentY, Math.max(0, contentHeight - height))
            anchors.fill: parent
            visible: page.mode === "album"
            opacity: resultsView.contentReveal
            clip: true
            contentWidth: width
            property var results: player.sourceContentActive
                                  ? player.sourceSearchAlbumResults : player.searchAlbumResults
            property int count: results ? results.length : 0
            contentHeight: page.gridTop + totalRows * stride + page.bottomContentInset

            Item {
                width: albumGrid.width
                height: albumGrid.contentHeight
                cachedLayout: true

                Repeater {
                    model: page.visible && page.mode === "album" ? albumGrid.results : null
                    windowStart: albumGrid.firstRow * page.gridCols
                    windowCount: albumGrid.liveRows * page.gridCols
                    AlbumCard {
                        albumId: modelData.id
                        tile: page.cardTile
                        x: page.pad + (index % page.gridCols) * (page.cardTile + page.colGap)
                        y: page.gridTop + Math.floor(index / page.gridCols) * albumGrid.stride
                        name: modelData.name
                        count: modelData.trackCount
                        subtitle: modelData.artistName || ""
                        coverUrl: modelData.coverUrl
                        coverThumbPath: modelData.coverThumbPath || ""
                        onClicked: player.openMediaAlbum("" + modelData.id)
                    }
                }
            }

            ViewportScrollBar {
                target: albumGrid
                bottomInset: page.bottomContentInset
            }
        }

        Flickable {
            id: artistGrid
            property real stride: page.cardH + page.rowGap
            property int totalRows: Math.ceil(count / page.gridCols)
            property int liveRows: Math.min(totalRows, Math.ceil(height / stride) + 3)
            property int firstRow: Math.max(0, Math.min(totalRows - liveRows, Math.floor(contentY / stride) - 1))
            onContentHeightChanged: contentY = Math.min(contentY, Math.max(0, contentHeight - height))
            anchors.fill: parent
            visible: page.mode === "artist"
            opacity: resultsView.contentReveal
            clip: true
            contentWidth: width
            property var results: player.sourceContentActive
                                  ? player.sourceSearchArtistResults : player.searchArtistResults
            property int count: results ? results.length : 0
            contentHeight: page.gridTop + totalRows * stride + page.bottomContentInset

            Item {
                width: artistGrid.width
                height: artistGrid.contentHeight
                cachedLayout: true

                Repeater {
                    model: page.visible && page.mode === "artist" ? artistGrid.results : null
                    windowStart: artistGrid.firstRow * page.gridCols
                    windowCount: artistGrid.liveRows * page.gridCols
                    ArtistCard {
                        artistId: modelData.id
                        tile: page.cardTile
                        x: page.pad + (index % page.gridCols) * (page.cardTile + page.colGap)
                        y: page.gridTop + Math.floor(index / page.gridCols) * artistGrid.stride
                        name: modelData.name
                        count: modelData.songCount || modelData.musicSize || 0
                        coverUrl: modelData.coverUrl
                        coverThumbPath: modelData.coverThumbPath || ""
                        onClicked: player.openMediaArtist("" + modelData.id)
                    }
                }
            }

            ViewportScrollBar {
                target: artistGrid
                bottomInset: page.bottomContentInset
            }
        }

        Flickable {
            id: playlistGrid
            objectName: "searchPlaylistGrid"
            anchors.fill: parent
            visible: page.mode === "playlist"
            opacity: resultsView.contentReveal
            clip: true
            contentWidth: width
            property var results: player.searchPlaylistResults
            property int count: results ? results.length : 0
            property real stride: page.cardH + page.rowGap
            property int totalRows: Math.ceil(count / page.gridCols)
            property int liveRows: Math.min(totalRows, Math.ceil(height / stride) + 3)
            property int firstRow: Math.max(0, Math.min(totalRows - liveRows, Math.floor(contentY / stride) - 1))
            contentHeight: page.gridTop + totalRows * stride + page.bottomContentInset
            property real pageRevision: player.searchPlaylistPageRevision
            onPageRevisionChanged: refillTimer.restart()

            // Recheck after bindings settle, including pages that add no new rows.
            Timer {
                id: refillTimer
                interval: 1
                repeat: false
                onTriggered: playlistGrid.requestMoreIfNeeded()
            }

            function requestMoreIfNeeded() {
                if (page.visible && player.searchMode === "playlist"
                        && player.searchHasMore && !player.searchLoading
                        && contentY + height >= contentHeight - page.bottomContentInset - 2 * stride)
                    player.loadMoreSearch()
            }
            onContentYChanged: requestMoreIfNeeded()
            onContentHeightChanged: {
                contentY = Math.min(contentY, Math.max(0, contentHeight - height))
            }
            onHeightChanged: requestMoreIfNeeded()

            Item {
                width: playlistGrid.width
                height: playlistGrid.contentHeight
                cachedLayout: true

                Repeater {
                    model: page.visible && page.mode === "playlist" ? playlistGrid.results : null
                    windowStart: playlistGrid.firstRow * page.gridCols
                    windowCount: playlistGrid.liveRows * page.gridCols
                    PlaylistCard {
                        objectName: "searchPlaylistCard" + index
                        playlistId: modelData.id
                        tile: page.cardTile
                        x: page.pad + (index % page.gridCols) * (page.cardTile + page.colGap)
                        y: page.gridTop + Math.floor(index / page.gridCols) * playlistGrid.stride
                        name: modelData.name
                        count: modelData.trackCount
                        playCount: modelData.playCount
                        coverUrl: modelData.coverUrl
                        coverThumbPath: modelData.coverThumbPath || ""
                        sourceName: modelData.sourceName || ""
                        onClicked: {
                            page.pendingPlaylist = modelData
                            page.recordCurrentSearch()
                            page.openPlaylist()
                        }
                    }
                }
            }

            ViewportScrollBar {
                target: playlistGrid
                bottomInset: page.bottomContentInset
            }
        }

        // Nothing to show yet: a spinner while the request runs, then a
        // "no results" state once it settled empty.
        LoadingIndicator {
            objectName: "searchLoadingIndicator"
            x: (resultsView.width - width) / 2
            y: Ui.space10
            visible: resultsView.resultCount === 0 && (player.searchLoading || searchDebounce.running)
            running: visible
            color: Ui.textSecondary
        }
        Item {
            width: resultsView.width
            height: Math.max(0, resultsView.height - page.bottomContentInset)
            visible: resultsView.resultCount === 0 && !player.searchLoading && !searchDebounce.running
            EmptyState {
                objectName: "searchNoResults"
                icon: "search_off"
                title: i18n.t("search.noResults.title")
                message: i18n.t("search.noResults.message")
            }
        }
    }
}
