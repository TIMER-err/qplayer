import QtQuick
import miuix.Core
import design
import "."
import "../components"

// Drill-in source playlist. One scrolling surface: the hero (cover, title,
// source, count, actions) and the in-playlist filter are the VirtualSongList
// header, so on a phone the tracks get the whole viewport after the first swipe.
// The RouteHeader floats over it; its title fades in once the hero title has
// scrolled underneath, and it turns opaque at the same point.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    signal openQueue()
    color: Ui.background

    // Reset the scroll to the top whenever a new playlist starts loading, so the
    // previous playlist's scroll position doesn't carry over.
    property string playlistWatch: String(player.openSourcePlaylistId || player.openPlaylistId)
    onPlaylistWatchChanged: {
        tracks.contentY = 0
        page.filterText = ""
        page.exitSelectMode()
    }

    property var allTracks: player.openSourcePlaylistId !== ""
                            ? player.sourcePlaylistTracks : player.playlistTracks
    property string filterText: ""
    property var filteredTracks: {
        var all = page.allTracks
        if (!all) return all
        var q = page.filterText.trim().toLowerCase()
        if (q === "") return all
        var out = []
        for (var i = 0; i < all.length; i++) {
            var t = all[i]
            var hit = (t.name && t.name.toLowerCase().indexOf(q) >= 0)
                   || (t.artist && t.artist.toLowerCase().indexOf(q) >= 0)
            if (hit) out.push(t)
        }
        return out
    }
    readonly property int totalCount: page.allTracks ? page.allTracks.length : 0

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    readonly property real gutter: Ui.gutter(page.width)
    // Method calls alone are not reactive. Read the account projection as well
    // so a secondary provider signing in/out refreshes its own action gates.
    readonly property bool targetMutable: {
        var accounts = player.sourceAccounts
        return player.openSourcePlaylistId !== ""
            && player.canMutateMediaPlaylist(player.openSourcePlaylistId)
    }
    readonly property bool targetHeart: {
        var accounts = player.sourceAccounts
        return player.openSourcePlaylistId !== ""
            && player.canHeartMediaPlaylist(player.openSourcePlaylistId)
    }
    readonly property bool targetSignedIn: {
        var accounts = player.sourceAccounts
        return player.openSourcePlaylistId !== ""
            && player.providerLoggedIn(player.openSourcePlaylistId.split(":")[0])
    }
    readonly property bool canHeart: !player.playlistLoading && page.targetHeart && page.totalCount > 0
    // Pull this whole playlist into a local one, which then follows it. Unlike
    // collecting, this works signed out and across sources — it copies the songs
    // into a list QPlayer owns.
    readonly property bool canFollow: !player.playlistLoading && player.openSourcePlaylistId !== ""
    // Collect (subscribe) — only for playlists that aren't the user's own; the
    // initial state comes from playlist/detail, so it's correct on open.
    readonly property bool canSubscribe: !player.playlistLoading && !player.playlistOwned
                                         && page.targetMutable
    // Change cover — own playlists only, and only when the playlist's own source
    // can do it. Both hosts install the same picker callback.
    readonly property bool canChangeCover: !player.playlistLoading && player.playlistOwned
                                           && player.sourcePlaylistCoverAvailable && page.targetSignedIn
    // Delete — only your own playlists, and never the default liked-songs list.
    readonly property bool canDelete: !player.playlistLoading && player.playlistDeletable
                                      && page.targetMutable
    readonly property bool canSelect: !player.playlistLoading && page.filteredTracks
                                      && page.filteredTracks.length > 0
    property bool selectMode: false

    function toggleSelectMode() {
        page.selectMode = !page.selectMode
        if (!page.selectMode) tracks.clearSelection()
    }
    function selectedCsv() { return tracks.selectedKey }
    function exitSelectMode() {
        page.selectMode = false
        tracks.clearSelection()
    }

    function startHeart() {
        if (player.openSourcePlaylistId !== "")
            player.startMediaIntelligenceMode(player.openSourcePlaylistId)
        else player.startIntelligenceMode(player.openPlaylistId)
    }

    // Map a visible (possibly filtered) row back to its position in the playlist.
    function playVisible(visibleIndex) {
        var rows = page.filteredTracks
        var all = page.allTracks
        if (!rows || !all || visibleIndex < 0 || visibleIndex >= rows.length) return
        var id = String(rows[visibleIndex].id)
        for (var i = 0; i < all.length; i++) {
            if (String(all[i].id) === id) {
                player.playPlaylistTrack(i)
                return
            }
        }
    }

    // ---- Hero inputs -------------------------------------------------------------
    // The source's display name stands in for a creator line (the controller does
    // not publish playlist creators).
    readonly property string sourceName: {
        var id = "" + player.openSourcePlaylistId
        if (id === "") return player.openPlaylistId > 0 ? i18n.t("source.netease") : ""
        var prefix = id.split(":")[0]
        var rows = player.sourceAccounts
        var n = rows ? rows.length : 0
        for (var i = 0; i < n; i++) {
            if (rows[i].providerId === prefix) return rows[i].sourceName
        }
        return ""
    }
    readonly property string heroMeta: page.totalCount > 0
                                       ? i18n.t("common.songCount", page.totalCount) : ""
    readonly property bool showFilter: !player.playlistLoading
                                       && (page.totalCount > 8 || page.filterText !== "")
    readonly property real toolbarHeight: page.showFilter ? 76 : 8

    // Deterministic hero height: an unpainted twin fed the same inputs as the
    // header delegate, so headerHeight is known before the delegate exists.
    HeroHeader {
        id: heroMetrics
        visible: false
        width: page.width
        subtitle: page.sourceName
        meta: page.heroMeta
    }
    // Where the hero title ends in content coordinates: the RouteHeader title
    // fades in as it slides under the header.
    readonly property real heroTitleBottom: heroMetrics.compact
        ? heroMetrics.topInset + 8 + heroMetrics.coverSize + 18 + heroMetrics.titleLine * 2
        : heroMetrics.topInset + 8 + heroMetrics.coverSize - Ui.iconButton - 18
          - (page.heroMeta.length > 0 ? 20 : 0) - (page.sourceName.length > 0 ? 24 : 0)
    readonly property real titleProgress: player.playlistLoading ? 1
        : Math.max(0, Math.min(1, (tracks.contentY - (page.heroTitleBottom - Ui.headerHeight - 16)) / 28))

    VirtualSongList {
        id: tracks
        anchors.fill: parent
        // Fades in once the tracks arrive instead of popping over the spinner;
        // an in-place reload that keeps rows on screen does not blink.
        opacity: player.playlistLoading && (!page.filteredTracks || page.filteredTracks.length === 0) ? 0 : 1
        Behavior on opacity { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
        rowInset: page.gutter
        scrollBarTopInset: Ui.headerHeight
        // The floating select bar covers the last rows otherwise.
        bottomContentInset: page.bottomContentInset + (page.selectMode ? 72 : 0)
        headerHeight: player.playlistLoading ? Ui.headerHeight
                                             : heroMetrics.preferredHeight + page.toolbarHeight
        header: Component {
            Item {
                id: headerRoot
                width: tracks.width
                height: tracks.headerHeight
                visible: !player.playlistLoading

                HeroHeader {
                    id: hero
                    width: parent.width
                    coverSource: player.playlistCoverPath
                    coverIcon: "queue_music"
                    kind: i18n.t("playlist.title")
                    title: player.playlistTitle
                    subtitle: page.sourceName
                    meta: page.heroMeta

                    Button {
                        objectName: "playlistPlayAllButton"
                        type: "filled"
                        icon: "play_arrow"
                        text: i18n.t("list.playAll")
                        cornerRadius: Ui.radiusFull
                        verticalPadding: 8
                        horizontalPadding: 20
                        enabled: page.filteredTracks && page.filteredTracks.length > 0
                        onClicked: page.playVisible(0)
                    }
                    IconButton {
                        id: followButton
                        objectName: "followIntoLocalButton"
                        type: "filledTonal"
                        visible: page.canFollow
                        icon: "library_add"
                        onClicked: {
                            followMenu.rebuild()
                            followMenu.open(followButton, 0, followButton.height)
                        }
                    }
                    IconButton {
                        objectName: "playlistSubscribeButton"
                        type: "filledTonal"
                        visible: page.canSubscribe
                        icon: player.playlistSubscribed ? "bookmark" : "bookmark_border"
                        contentColor: player.playlistSubscribed ? Ui.accent : Theme.color.onSecondaryVariant
                        onClicked: player.togglePlaylistSubscribe()
                    }
                    IconButton {
                        objectName: "playlistHeartButton"
                        type: "filledTonal"
                        visible: page.canHeart
                        enabled: !player.intelligenceLoading
                        icon: "auto_awesome"
                        onClicked: page.startHeart()
                    }
                }

                // Own playlists: the cover itself is the "change cover" target.
                Item {
                    objectName: "playlistDetailCoverButton"
                    visible: page.canChangeCover
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
                        enabled: page.canChangeCover
                        hoverEnabled: enabled
                        cursorShape: Qt.PointingHandCursor
                        onClicked: player.pickPlaylistCover()
                    }
                }

                TextField {
                    objectName: "playlistFilterField"
                    overlayField: true
                    visible: page.showFilter
                    x: hero.gutter
                    y: hero.height + 8
                    width: hero.compact ? hero.width - 2 * hero.gutter
                                        : Math.min(420, hero.width - 2 * hero.gutter)
                    height: implicitHeight
                    // A text input is hit-tested before every MouseArea, so once
                    // the field slides under the floating header it must stop
                    // taking pointer input or it would swallow the Back button.
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
        // Drop the row delegates when the detail page is closed (see QueuePage):
        // an invisible detail otherwise keeps the whole playlist's SongRows alive
        // after you return home.
        list: page.visible ? page.filteredTracks : null
        // Long-press a track → add to another playlist, and (in your own
        // playlist) remove it from this one. Not login-gated.
        songMenu: true
        ownedPlaylist: player.playlistOwned
        showOfflineBadge: player.playlistOffline
        selectMode: page.selectMode
        // Own playlist, on a source that can save an order, and unfiltered —
        // a drop position means nothing while rows are hidden.
        reorderable: !page.selectMode && player.sourcePlaylistReorderable && page.filterText === ""
        onMoveRequested: player.moveSourcePlaylistTrack(tracks.moveFrom, tracks.moveTo)
        onReorderCommitted: player.commitSourcePlaylistOrder()
        onActivated: page.playVisible(tracks.activatedIndex)
    }

    LoadingIndicator {
        objectName: "detailLoadingIndicator"
        anchors.centerIn: parent
        visible: player.playlistLoading && (!page.filteredTracks || page.filteredTracks.length === 0)
        running: visible
    }

    Text {
        objectName: "playlistNoMatches"
        x: page.gutter
        y: tracks.headerHeight - tracks.contentY + 24
        width: page.width - 2 * page.gutter
        visible: !player.playlistLoading && page.filterText !== ""
                 && page.filteredTracks && page.filteredTracks.length === 0
        horizontalAlignment: Text.AlignHCenter
        text: i18n.t("playlist.noMatches")
        color: Ui.textSecondary
        font.pixelSize: Ui.textBody
    }

    ScrollToTopFab {
        objectName: "playlistScrollTopButton"
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: page.gutter
        anchors.bottomMargin: 16 + page.bottomContentInset + (page.selectMode ? 64 : 0)
        target: tracks
    }

    // Floating batch bar above the player bar while selecting.
    Rectangle {
        id: selectBar
        objectName: "playlistDetailSelectBar"
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
            width: Math.max(0, selectActions.x - x - 8)
            text: i18n.t("playlist.selectCount", tracks.selectedN)
            color: Ui.textPrimary
            elide: Text.ElideRight
            font.pixelSize: Ui.textCallout
            font.weight: Ui.weightBold
        }
        Row {
            id: selectActions
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
                objectName: "playlistDetailUnlikeSelected"
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
                objectName: "playlistDetailDeleteSelected"
                type: "standard"
                icon: "delete"
                visible: player.playlistOwned
                enabled: tracks.selectedN > 0
                contentColor: enabled ? Ui.danger : Theme.color.disabledOnSecondaryVariant
                onClicked: {
                    player.removeMediaManyFromCurrentPlaylist(tracks.selectedKey)
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
        id: header
        width: page.width
        gutter: page.gutter
        title: player.playlistTitle !== "" ? player.playlistTitle : i18n.t("playlist.title")
        titleOpacity: page.titleProgress
        elevated: page.titleProgress > 0.01
        onBack: page.back()

        IconButton {
            objectName: "playlistDetailSelectButton"
            type: "standard"
            visible: page.canSelect || page.selectMode
            icon: page.selectMode ? "close" : "checklist"
            onClicked: page.toggleSelectMode()
        }
        IconButton {
            id: overflowButton
            objectName: "playlistDetailOverflowButton"
            type: "standard"
            icon: "more_vert"
            onClicked: {
                overflowMenu.rebuild()
                overflowMenu.open(overflowButton, 0, overflowButton.height)
            }
        }
    }

    // Rare actions. Frequent ones live in the hero's action row.
    Menu {
        id: overflowMenu
        outlined: true
        function rebuild() {
            var items = [{
                text: i18n.t("queue.tab.queue"), icon: "queue_music",
                action: function() { page.openQueue() }
            }]
            // Same link sharing the playlist card's menu offers.
            if (player.openSourcePlaylistId !== "" || player.openPlaylistId > 0) {
                items.push({
                    text: i18n.t("menu.copyLink"), icon: "link",
                    action: function() {
                        if (player.openSourcePlaylistId !== "")
                            player.shareMedia("" + player.openSourcePlaylistId)
                        else player.copyPlaylistLink(player.openPlaylistId)
                    }
                })
            }
            if (page.canChangeCover) {
                items.push({
                    text: i18n.t("playlist.local.pickCover"), icon: "image",
                    action: function() { player.pickPlaylistCover() }
                })
            }
            if (page.canDelete) {
                items.push({ type: "separator" })
                items.push({
                    text: i18n.t("common.delete"), icon: "delete",
                    action: function() { deleteDialog.open() }
                })
            }
            overflowMenu.model = items
        }
    }

    // Destinations for the follow button: every local playlist, toggling, plus a
    // "new playlist" entry that names itself after this playlist.
    Menu {
        id: followMenu
        outlined: true
        function rebuild() {
            var sourceId = "" + player.openSourcePlaylistId
            var items = []
            var lists = player.localPlaylists
            var n = lists ? lists.length : 0
            for (var i = 0; i < n; i++) items.push(followMenu._item(sourceId, lists[i]))
            if (n > 0) items.push({ type: "separator" })
            items.push({ text: i18n.t("playlist.local.newPlaylist"), icon: "playlist_add",
                         action: followMenu._newAction(sourceId) })
            followMenu.model = items
        }
        function _item(sourceId, pl) {
            var pid = "" + pl.id
            var followed = player.isSourcePlaylistFollowed(pid, sourceId)
            return {
                text: pl.name,
                icon: followed ? "check" : "queue_music",
                action: followed ? followMenu._unfollow(pid, sourceId)
                                 : followMenu._follow(pid, sourceId)
            }
        }
        function _follow(pid, sourceId) {
            return function() { player.followSourcePlaylist(pid, sourceId) }
        }
        function _unfollow(pid, sourceId) {
            return function() { player.unfollowSourcePlaylist(pid, sourceId) }
        }
        function _newAction(sourceId) {
            return function() { player.followSourcePlaylistInNewLocalPlaylist(sourceId) }
        }
    }

    // Delete confirmation. On accept the controller removes it and refreshes the library;
    // we drill back out since this playlist no longer exists.
    Dialog {
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        id: deleteDialog
        icon: "delete"
        title: i18n.t("playlist.delete.title")
        text: i18n.t("playlist.delete.body", player.playlistTitle)
        acceptText: i18n.t("common.delete")
        rejectText: i18n.t("common.cancel")
        onAccepted: {
            if (player.openSourcePlaylistId !== "")
                player.deleteMediaPlaylist(player.openSourcePlaylistId)
            else player.deletePlaylist(player.openPlaylistId)
            page.back()
        }
    }
}
