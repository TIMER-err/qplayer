import QtQuick
import miuix.Core
import design
import "."
import "../components"

// A local playlist: songs the user gathered from any mix of sources, plus the
// source playlists this one follows. Opening the page already triggers a sync of
// those (the controller applies its own cooldown), so the hero's sync button is
// only for forcing one — it is never the only way to get fresh content.
//
// Same structure as PlaylistDetailPage: a floating RouteHeader over one
// VirtualSongList whose header is the hero, the followed-sources panel and the
// filter field.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    signal openQueue()
    color: Ui.background

    property string playlistId: player.openLocalPlaylistId
    property var tracksList: player.localPlaylistTracks
    property var subscriptions: player.localPlaylistSubscriptions
    property bool showSources: false

    onPlaylistIdChanged: {
        tracks.contentY = 0
        page.filterText = ""
        page.showSources = false
        page.exitSelectMode()
    }

    property bool selectMode: false
    function toggleSelectMode() {
        page.selectMode = !page.selectMode
        if (!page.selectMode) tracks.clearSelection()
    }
    function exitSelectMode() {
        page.selectMode = false
        tracks.clearSelection()
    }

    property string filterText: ""
    property var filteredTracks: {
        var all = page.tracksList
        if (!all) return all
        var q = page.filterText.trim().toLowerCase()
        if (q === "") return all
        var out = []
        for (var i = 0; i < all.length; i++) {
            var t = all[i]
            var hit = (t.title && t.title.toLowerCase().indexOf(q) >= 0)
                   || (t.artist && t.artist.toLowerCase().indexOf(q) >= 0)
            if (hit) out.push(t)
        }
        return out
    }
    readonly property int totalCount: page.tracksList ? page.tracksList.length : 0
    readonly property int sourceCount: page.subscriptions ? page.subscriptions.length : 0

    // The filter can hide rows but never reorders them, so map a visible row
    // back to its index in the real playlist.
    function playlistIndexOf(visibleIndex) {
        var rows = page.filteredTracks
        var all = page.tracksList
        if (!rows || !all || visibleIndex < 0 || visibleIndex >= rows.length) return -1
        var selected = rows[visibleIndex]
        for (var i = 0; i < all.length; i++) {
            if (all[i] === selected) return i
        }
        return -1
    }

    function openRename() {
        renameField.text = player.localPlaylistTitle
        renameDialog.open()
    }

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    readonly property real gutter: Ui.gutter(page.width)

    // ---- Hero inputs -------------------------------------------------------------
    readonly property string sortLabel: {
        var f = player.localPlaylistSortField
        if (f === "") return ""
        var label = i18n.t("playlist.local.sort." + f)
        return label + (player.localPlaylistSortDescending ? " ↓" : " ↑")
    }
    readonly property string heroSubtitle: page.sourceCount > 0
                                           ? i18n.t("detail.sourcesCount", page.sourceCount) : ""
    readonly property string heroMeta: player.localPlaylistSyncing
        ? i18n.t("playlist.local.syncing")
        : (page.totalCount > 0 ? i18n.t("common.songCount", page.totalCount)
                               : i18n.t("common.songCountEmpty"))
          + (page.sortLabel !== "" ? " · " + page.sortLabel : "")
    readonly property bool showFilter: page.totalCount > 8 || page.filterText !== ""
    readonly property real sourcesHeight: page.showSources && page.sourceCount > 0
                                          ? 44 + page.sourceCount * 72 : 0
    readonly property real toolbarHeight: page.sourcesHeight + (page.showFilter ? 76 : 8)

    // Deterministic hero height (see PlaylistDetailPage).
    HeroHeader {
        id: heroMetrics
        visible: false
        width: page.width
        subtitle: page.heroSubtitle
        meta: page.heroMeta
    }
    readonly property real heroTitleBottom: heroMetrics.compact
        ? heroMetrics.topInset + 8 + heroMetrics.coverSize + 18 + heroMetrics.titleLine * 2
        : heroMetrics.topInset + 8 + heroMetrics.coverSize - Ui.iconButton - 18
          - (page.heroMeta.length > 0 ? 20 : 0) - (page.heroSubtitle.length > 0 ? 24 : 0)
    readonly property real titleProgress: Math.max(0, Math.min(1,
        (tracks.contentY - (page.heroTitleBottom - Ui.headerHeight - 16)) / 28))

    VirtualSongList {
        id: tracks
        anchors.fill: parent
        rowInset: page.gutter
        scrollBarTopInset: Ui.headerHeight
        bottomContentInset: page.bottomContentInset + (page.selectMode ? 72 : 0)
        headerHeight: heroMetrics.preferredHeight + page.toolbarHeight
        header: Component {
            Item {
                width: tracks.width
                height: tracks.headerHeight

                HeroHeader {
                    id: hero
                    width: parent.width
                    // The playlist's own cover: the picked image when there is
                    // one, otherwise derived from the songs by the controller.
                    coverSource: player.localPlaylistCover
                    coverIcon: "queue_music"
                    kind: i18n.t("detail.kind.localPlaylist")
                    title: player.localPlaylistTitle
                    subtitle: page.heroSubtitle
                    subtitleLink: true
                    meta: page.heroMeta
                    onSubtitleClicked: page.showSources = !page.showSources

                    Button {
                        objectName: "localPlaylistPlayAllButton"
                        type: "filled"
                        icon: "play_arrow"
                        text: i18n.t("list.playAll")
                        cornerRadius: Ui.radiusFull
                        verticalPadding: 8
                        horizontalPadding: 20
                        enabled: page.totalCount > 0
                        onClicked: player.playLocalPlaylist(page.playlistId)
                    }
                    // Sorting is state you glance at while browsing, so it stays
                    // one tap away; accent while a sort is applied.
                    IconButton {
                        id: sortButton
                        objectName: "localPlaylistSortButton"
                        type: "filledTonal"
                        visible: page.totalCount > 0
                        icon: "sort"
                        contentColor: player.localPlaylistSortField !== ""
                                      ? Ui.accent : Theme.color.onSecondaryVariant
                        onClicked: {
                            sortMenu.rebuild()
                            sortMenu.open(sortButton, 0, sortButton.height)
                        }
                    }
                    IconButton {
                        objectName: "localPlaylistSyncButton"
                        type: "filledTonal"
                        visible: page.sourceCount > 0
                        enabled: !player.localPlaylistSyncing
                        icon: "sync"
                        onClicked: player.refreshLocalPlaylist(page.playlistId)
                    }
                }

                // The cover itself opens the cover menu (pick / reset).
                Item {
                    id: coverButton
                    objectName: "localPlaylistCoverButton"
                    x: hero.compact ? (hero.width - hero.coverSize) / 2 : hero.gutter
                    y: hero.topInset + 8
                    width: hero.coverSize
                    height: hero.coverSize
                    Rectangle {
                        width: parent.width
                        height: parent.height
                        radius: Ui.radiusLg
                        color: Ui.surfaceAlpha(0.65)
                        opacity: coverArea.containsMouse ? 1 : 0
                        Behavior on opacity { NumberAnimation { duration: 120 } }
                        Glyph {
                            width: parent.width
                            height: parent.height
                            size: 36
                            name: "edit"
                            color: Ui.textPrimary
                        }
                    }
                    MouseArea {
                        id: coverArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            coverMenu.rebuild()
                            coverMenu.open(coverButton, coverButton.width / 2, coverButton.height / 2)
                        }
                    }
                }

                // Followed source playlists, collapsed by default (the hero's
                // subtitle toggles them): reference information about where the
                // songs came from, not the content itself.
                Item {
                    objectName: "localPlaylistSources"
                    visible: page.sourcesHeight > 0
                    x: hero.gutter
                    y: hero.height
                    width: hero.width - 2 * hero.gutter
                    height: page.sourcesHeight

                    SectionHeader {
                        width: parent.width
                        title: i18n.t("playlist.local.followedSources")
                        actionText: i18n.t("common.collapse")
                        onActionRequested: page.showSources = false
                    }
                    Repeater {
                        model: page.showSources ? page.subscriptions : null
                        Rectangle {
                            objectName: "localPlaylistSourceCard"
                            property var sub: modelData
                            y: 44 + index * 72
                            width: parent.width
                            height: 64
                            radius: Ui.radiusMd
                            color: Ui.surfaceRaised
                            CoverImage {
                                x: 12
                                y: 12
                                width: 40
                                height: 40
                                radius: Ui.radiusSm
                                source: sub.artworkUrl
                                icon: "queue_music"
                                iconSize: 20
                            }
                            Text {
                                x: 64
                                y: 13
                                width: Math.max(0, parent.width - 64 - 56)
                                text: sub.name && sub.name.length > 0 ? sub.name : sub.sourcePlaylistId
                                color: Ui.textPrimary
                                font.pixelSize: Ui.textBody
                                elide: Text.ElideRight
                                maximumLineCount: 1
                            }
                            Text {
                                x: 64
                                y: 35
                                width: Math.max(0, parent.width - 64 - 56)
                                text: {
                                    if (sub.lastError && sub.lastError.length > 0) return sub.lastError
                                    var who = sub.providerName && sub.providerName.length > 0
                                              ? sub.providerName : sub.provider
                                    return who + " · " + i18n.t("playlist.local.followedCount", sub.trackCount)
                                }
                                color: sub.lastError && sub.lastError.length > 0
                                       ? Ui.danger : Ui.textSecondary
                                font.pixelSize: Ui.textCaption
                                elide: Text.ElideRight
                                maximumLineCount: 1
                            }
                            IconButton {
                                x: parent.width - width - 12
                                y: (parent.height - height) / 2
                                type: "standard"
                                icon: "bookmark_remove"
                                contentColor: Ui.textSecondary
                                onClicked: player.unfollowSourcePlaylist(page.playlistId, sub.sourcePlaylistId)
                            }
                        }
                    }
                }

                TextField {
                    objectName: "localPlaylistFilterField"
                    overlayField: true
                    visible: page.showFilter
                    x: hero.gutter
                    y: hero.height + page.sourcesHeight + 8
                    width: hero.compact ? hero.width - 2 * hero.gutter
                                        : Math.min(420, hero.width - 2 * hero.gutter)
                    height: implicitHeight
                    // Text inputs win hit tests over every MouseArea: stop taking
                    // input once the field slides under the floating header.
                    enabled: tracks.contentY <= y - Ui.headerHeight + 4
                    cornerRadius: Ui.radiusXl
                    backgroundColor: Ui.surfaceRaised
                    labelColor: Ui.textSecondary
                    label: i18n.t("playlist.searchInside")
                    leadingIcon: "search"
                    text: page.filterText
                    onTextChanged: page.filterText = text
                }
            }
        }
        list: page.visible ? page.filteredTracks : null
        isLocal: true
        songMenu: true
        removable: true
        selectMode: page.selectMode
        // Rows match the playing song by media identity / file path, which is
        // correct in this playlist's own order as well as the queue's.
        highlightCurrent: true
        // Dragging only makes sense while the rows are in the stored order, and
        // the filter hides rows, which would make a dropped position mean
        // something other than what it looks like.
        reorderable: !page.selectMode && player.localPlaylistReorderable && page.filterText === ""
        onMoveRequested: player.moveLocalPlaylistTrack(
                             page.playlistId, tracks.moveFrom, tracks.moveTo)
        onReorderCommitted: player.commitLocalPlaylistOrder(page.playlistId)
        onActivated: {
            var i = page.playlistIndexOf(tracks.activatedIndex)
            if (i >= 0) player.playLocalPlaylistIndex(page.playlistId, i)
        }
        onRemoveRequested: {
            var i = page.playlistIndexOf(tracks.removeIndex)
            if (i >= 0) player.removeFromLocalPlaylistAt(page.playlistId, i)
        }
    }

    // Empty and no-match states sit under the header block, not over the hero.
    Item {
        x: 0
        y: tracks.headerHeight - tracks.contentY
        width: page.width
        height: 260
        visible: page.totalCount === 0
        EmptyState {
            icon: "queue_music"
            title: i18n.t("playlist.local.tracksEmpty.title")
            message: i18n.t("playlist.local.tracksEmpty.desc")
        }
    }

    Text {
        x: page.gutter
        y: tracks.headerHeight - tracks.contentY + 24
        width: page.width - 2 * page.gutter
        visible: page.filterText !== "" && page.filteredTracks
                 && page.filteredTracks.length === 0 && page.totalCount > 0
        horizontalAlignment: Text.AlignHCenter
        text: i18n.t("playlist.noMatches")
        color: Ui.textSecondary
        font.pixelSize: Ui.textBody
    }

    ScrollToTopFab {
        objectName: "localPlaylistScrollTopButton"
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: page.gutter
        anchors.bottomMargin: 16 + page.bottomContentInset + (page.selectMode ? 64 : 0)
        target: tracks
    }

    Rectangle {
        objectName: "localPlaylistSelectBar"
        visible: page.selectMode
        width: Math.min(560, page.width - 2 * page.gutter)
        height: 56
        x: (page.width - width) / 2
        y: page.height - page.bottomContentInset - height - 4
        radius: Ui.radiusXl
        color: Ui.surfaceRaised
        border.width: 1
        border.color: Ui.hairline
        MouseArea { anchors.fill: parent }
        Text {
            x: 20
            y: (parent.height - implicitHeight) / 2
            width: Math.max(0, localSelectActions.x - x - 8)
            text: i18n.t("playlist.selectCount", tracks.selectedN)
            color: Ui.textPrimary
            elide: Text.ElideRight
            font.pixelSize: Ui.textCallout
            font.weight: Ui.weightBold
        }
        Row {
            id: localSelectActions
            x: parent.width - width - 8
            y: (parent.height - height) / 2
            height: Ui.iconButton
            spacing: 2
            IconButton {
                type: "standard"
                icon: "done_all"
                onClicked: tracks.selectAll()
            }
            IconButton {
                objectName: "localPlaylistUnlikeSelected"
                type: "standard"
                icon: "heart_minus"
                // Only for a selection that is entirely liked songs.
                visible: tracks.selectionAllLiked
                onClicked: {
                    player.unlikeMediaMany(tracks.selectedKey)
                    page.exitSelectMode()
                }
            }
            IconButton {
                objectName: "localPlaylistDeleteSelected"
                type: "standard"
                icon: "delete"
                enabled: tracks.selectedN > 0
                contentColor: enabled ? Ui.danger : Theme.color.disabledOnSecondaryVariant
                onClicked: {
                    player.removeMediaManyFromLocalPlaylist(page.playlistId, tracks.selectedKey)
                    page.exitSelectMode()
                }
            }
        }
    }

    // Header area: block taps from reaching hero controls scrolled underneath.
    MouseArea {
        width: page.width
        height: Ui.headerHeight
    }

    RouteHeader {
        width: page.width
        gutter: page.gutter
        title: player.localPlaylistTitle !== "" ? player.localPlaylistTitle
                                                : i18n.t("detail.kind.localPlaylist")
        titleOpacity: page.titleProgress
        elevated: page.titleProgress > 0.01
        onBack: page.back()

        IconButton {
            objectName: "localPlaylistSelectButton"
            type: "standard"
            visible: page.totalCount > 0 || page.selectMode
            icon: page.selectMode ? "close" : "checklist"
            onClicked: page.toggleSelectMode()
        }
        IconButton {
            id: overflowButton
            objectName: "localPlaylistOverflowButton"
            type: "standard"
            icon: "more_vert"
            onClicked: {
                overflowMenu.rebuild()
                overflowMenu.open(overflowButton, 0, overflowButton.height)
            }
        }
    }

    // Sorting is a view over the stored order. Tapping the active field flips the
    // direction; tapping another switches to it ascending. "Custom" is the stored
    // order itself and has no direction, and is the only mode rows can be dragged
    // in — a drag in a sorted view would have nowhere to land.
    Menu {
        id: sortMenu
        outlined: true
        function rebuild() {
            var fields = [
                { key: "",         label: i18n.t("playlist.local.sort.custom") },
                { key: "title",    label: i18n.t("playlist.local.sort.title") },
                { key: "artist",   label: i18n.t("playlist.local.sort.artist") },
                { key: "duration", label: i18n.t("playlist.local.sort.duration") },
                { key: "added",    label: i18n.t("playlist.local.sort.added") },
                { key: "source",   label: i18n.t("playlist.local.sort.source") }
            ]
            var items = []
            for (var i = 0; i < fields.length; i++) items.push(sortMenu._item(fields[i]))
            sortMenu.model = items
        }
        function _item(field) {
            var active = player.localPlaylistSortField === field.key
            var descending = player.localPlaylistSortDescending
            var mark = ""
            if (active) {
                mark = field.key === "" ? "check"
                     : (descending ? "arrow_downward" : "arrow_upward")
            }
            return {
                text: field.label,
                icon: mark,
                action: sortMenu._apply(field.key, active, descending)
            }
        }
        function _apply(key, active, descending) {
            return function() {
                var flip = active && key !== "" ? !descending : false
                player.setLocalPlaylistSort(page.playlistId, key, flip)
            }
        }
    }

    // Picking is one action, resetting only exists once something was picked.
    Menu {
        id: coverMenu
        outlined: true
        function rebuild() {
            var items = [{
                text: i18n.t("playlist.local.pickCover"), icon: "image",
                action: function() { player.pickLocalPlaylistCover(page.playlistId) }
            }]
            if (player.localPlaylistCustomCover) {
                items.push({
                    text: i18n.t("playlist.local.clearCover"), icon: "hide_image",
                    action: function() { player.clearLocalPlaylistCover(page.playlistId) }
                })
            }
            coverMenu.model = items
        }
    }

    // Rare actions, at every width. Play, sort and sync live in the hero.
    Menu {
        id: overflowMenu
        outlined: true
        function rebuild() {
            var items = [{
                text: i18n.t("queue.tab.queue"), icon: "queue_music",
                action: function() { page.openQueue() }
            }]
            if (player.playlistTransferAvailable) {
                items.push({
                    text: i18n.t("playlist.local.export"), icon: "file_upload",
                    action: function() { player.requestLocalPlaylistExport(page.playlistId) }
                })
            }
            items.push({
                text: i18n.t("playlist.local.pickCover"), icon: "image",
                action: function() { player.pickLocalPlaylistCover(page.playlistId) }
            })
            if (player.localPlaylistCustomCover) {
                items.push({
                    text: i18n.t("playlist.local.clearCover"), icon: "hide_image",
                    action: function() { player.clearLocalPlaylistCover(page.playlistId) }
                })
            }
            items.push({ type: "separator" })
            items.push({
                text: i18n.t("playlist.local.rename"), icon: "edit",
                action: function() { page.openRename() }
            })
            items.push({
                text: i18n.t("common.delete"), icon: "delete",
                action: function() { deleteDialog.open() }
            })
            overflowMenu.model = items
        }
    }

    Dialog {
        id: renameDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "edit"
        title: i18n.t("playlist.local.rename.title")
        acceptText: i18n.t("common.save")
        rejectText: i18n.t("common.cancel")
        onAccepted: player.renameLocalPlaylist(page.playlistId, renameField.text)

        TextField {
            id: renameField
            overlayField: true
            anchors.left: parent.left
            anchors.right: parent.right
            type: "outlined"
            label: i18n.t("library.create.hint")
            onAccepted: { renameDialog.accepted(); renameDialog.close() }
        }
    }

    Dialog {
        id: deleteDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "delete"
        title: i18n.t("playlist.delete.title")
        text: i18n.t("playlist.local.delete.body", player.localPlaylistTitle)
        acceptText: i18n.t("common.delete")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            player.deleteLocalPlaylist(page.playlistId)
            page.back()
        }
    }
}
