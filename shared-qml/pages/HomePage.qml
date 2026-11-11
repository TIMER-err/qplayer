import QtQuick
import miuix.Core
import design
import "."
import "../components"

// Home (root page under the "推荐" header): a quiet greeting line, the daily
// picks hero, the source's titled sections, the recommended-playlist grid and
// the daily song list. Everything sits at absolute, index-derived positions in
// one PullToRefresh viewport (the layout primitive that behaves in qml4j), and
// every repeated block is windowed.
Item {
    id: page
    property real bottomContentInset: 0
    property var pendingPlaylist
    signal openPlaylist()

    readonly property bool compact: width < Ui.compactMax
    property real pad: Ui.gutter(width)
    property real colGap: compact ? Ui.space3 : Ui.space4
    property real rowGap: Ui.space5
    property real rowH: Ui.rowHeight
    // Responsive grid: ~176px minimum tile → 2 columns on a phone, 3 on a medium
    // window, 4+ on a wide one. Width-driven, so it adapts on desktop and Android.
    property real minTile: 176
    property int cols: Math.max(2, Math.floor((width - 2 * pad + colGap) / (minTile + colGap)))
    property real tile: (width - 2 * pad - (cols - 1) * colGap) / cols
    // Same formula as PlaylistCard.textSlot (tile + caption slot).
    property real cardH: tile + Math.max(52, Math.round(72 * tile / 200))
    property real cardRowH: Math.max(1, cardH + rowGap)

    property var homePlaylists: player.sourceContentActive
                                ? player.sourceRecommendPlaylists : player.recommendPlaylists
    property var homeSongs: player.sourceContentActive
                            ? player.sourceRecommendations : player.recommendations
    // Titled groups the source supplies (NetEase radar, ...) and the one flat
    // card list they all index into.
    property var homeSections: player.sourceContentActive ? player.sourceHomeSections : []
    property var sectionCards: player.sourceContentActive ? player.sourceSectionPlaylists : []
    property int recCount: homePlaylists ? homePlaylists.length : 0
    property int dailyCount: homeSongs ? homeSongs.length : 0
    property int sectionCount: homeSections ? homeSections.length : 0
    property int sectionCardCount: sectionCards ? sectionCards.length : 0
    property bool hasContent: recCount > 0 || dailyCount > 0 || sectionCardCount > 0

    // ---- vertical rhythm ----------------------------------------------------
    property real greetH: 36
    // Section header block: breathing room above, the 40px SectionHeader, a
    // small gap before the first card row.
    property real hdrTop: Ui.space4
    property real hdrH: hdrTop + 40 + Ui.space2
    property real heroH: compact ? 184 : 216
    property real heroBlockH: dailyCount > 0 ? heroH + Ui.space2 : 0
    property bool dailyFirst: settings.value("homeDailyFirst") === true
    // With "daily first" the songs follow the hero directly (the hero is their
    // header); otherwise they close the page under their own SectionHeader.
    property real dailyRowsH: dailyCount * rowH
    property real sectionsTop: greetH + heroBlockH + (dailyFirst && dailyCount > 0 ? dailyRowsH + Ui.space2 : 0)

    // Every section's header y and each of its cards' x/y, laid out once per
    // geometry change: the cards come as one flat list, so plain arrays keep the
    // per-card bindings a lookup instead of a search.
    property var sectionHeaderY: {
        var out = []
        var y = page.sectionsTop
        for (var i = 0; i < page.sectionCount; i++) {
            out.push(y)
            var rows = Math.ceil(page.homeSections[i].count / page.cols)
            y += page.hdrH + rows * page.cardRowH
        }
        return out
    }
    property real sectionsH: {
        if (page.sectionCount === 0) return 0
        var last = page.sectionCount - 1
        var rows = Math.ceil(page.homeSections[last].count / page.cols)
        return page.sectionHeaderY[last] + page.hdrH + rows * page.cardRowH
               - page.sectionHeaderY[0]
    }
    property var sectionCardX: {
        var out = []
        for (var i = 0; i < page.sectionCount; i++) {
            var section = page.homeSections[i]
            for (var j = 0; j < section.count; j++)
                out[section.start + j] = page.pad + (j % page.cols) * (page.tile + page.colGap)
        }
        return out
    }
    property var sectionCardY: {
        var out = []
        for (var i = 0; i < page.sectionCount; i++) {
            var section = page.homeSections[i]
            var top = page.sectionHeaderY[i] + page.hdrH
            for (var j = 0; j < section.count; j++)
                out[section.start + j] = top + Math.floor(j / page.cols) * page.cardRowH
        }
        return out
    }

    property real mainHdrY: sectionsTop + sectionsH
    property real mainHdrH: recCount > 0 ? hdrH : 0
    property real gridTop: mainHdrY + mainHdrH
    property real gridH: Math.ceil(recCount / cols) * cardRowH
    property real dailyHdrY: gridTop + gridH
    property real dailyTop: dailyFirst ? greetH + heroBlockH
                                       : dailyHdrY + (dailyCount > 0 ? hdrH : 0)
    property real contentH: (dailyFirst ? gridTop + gridH : dailyTop + dailyRowsH) + Ui.space4

    // A tap holds the "connecting" state for at least this long even if the
    // request itself fails near-instantly (e.g. no network at all) -- a flash
    // too quick to actually read isn't feedback. A genuinely slow real request
    // still keeps showing it past 3s (refreshBusy also watches homeLoading).
    // The first moments after start-up count as loading too, so a cold start
    // shows the skeleton instead of flashing the error state before loadHome.
    property bool refreshCooling: false
    property bool startupGrace: true
    // The pull indicator only reflects real requests; the start-up grace only
    // keeps the skeleton (not the indicator) up.
    property bool loadingFeedback: !player.sourceSetupRequired
                                   && (refreshCooling || player.homeLoading)
    property bool refreshBusy: loadingFeedback || (startupGrace && !player.sourceSetupRequired)
    Timer {
        id: refreshCoolTimer
        interval: 3000
        onTriggered: page.refreshCooling = false
    }
    Timer {
        interval: 1500
        running: true
        onTriggered: page.startupGrace = false
    }

    function refresh() {
        if (page.refreshBusy) return
        page.refreshCooling = true
        refreshCoolTimer.restart()
        player.loadHome()
    }

    function greeting() {
        var h = new Date().getHours();
        if (h < 6) return i18n.t("home.greeting.night");
        if (h < 12) return i18n.t("home.greeting.morning");
        if (h < 14) return i18n.t("home.greeting.noon");
        if (h < 18) return i18n.t("home.greeting.afternoon");
        return i18n.t("home.greeting.evening");
    }

    // Bring the daily list to the top of the viewport (hero body tap).
    function scrollToDaily() {
        var view = homeFlick.flickable
        var target = homeFlick.maximumPull + (page.dailyFirst ? page.greetH : page.dailyHdrY)
        dailyScroll.to = Math.max(0, Math.min(target, view.contentHeight - view.height))
        dailyScroll.restart()
    }
    NumberAnimation {
        id: dailyScroll
        target: homeFlick.flickable
        property: "contentY"
        duration: 320
        easing.type: Easing.OutCubic
    }

    property int gridWindowRows: {
        var rows = Math.ceil(recCount / Math.max(1, cols))
        var vis = Math.ceil(homeFlick.height / cardRowH) + 3
        return Math.min(rows, Math.max(0, vis))
    }
    property int firstGridRow: {
        var f = Math.floor((homeFlick.scrollOffset - gridTop) / cardRowH) - 1
        var maxR = Math.max(0, Math.ceil(recCount / Math.max(1, cols)) - gridWindowRows)
        if (f > maxR) f = maxR
        if (f < 0) f = 0
        return f
    }
    property int firstCard: firstGridRow * cols
    // Window sizes never depend on the scroll position: a window that grows from
    // 0 when its section scrolls into view builds every delegate (rows plus their
    // covers) in one frame, which stalled the animated scroll to the daily list.
    // A fixed count builds them once when the data lands; scrolling only rebinds
    // the recycled delegates.
    property int cardWindow: gridWindowRows * cols
    property int songWindow: Math.min(dailyCount, Math.ceil(homeFlick.height / rowH) + 10)
    property int firstSong: {
        var f = Math.floor((homeFlick.scrollOffset - dailyTop) / rowH) - 4
        var maxF = dailyCount - songWindow
        if (f > maxF) f = maxF
        if (f < 0) f = 0
        return f
    }

    // Section cards are sorted by vertical position. Binary search keeps
    // viewport updates logarithmic even when a source returns many sections.
    function lowerCard(y) {
        var low = 0, high = page.sectionCardCount
        while (low < high) {
            var middle = Math.floor((low + high) / 2)
            if (page.sectionCardY[middle] < y) low = middle + 1
            else high = middle
        }
        return low
    }
    // Keep the count stable while scrolling: changing it rebuilds every delegate
    // and drops decoded covers. Section headers can only reduce card density, so
    // a dense-grid bound safely covers the viewport plus two rows on either side.
    property int sectionWindow: Math.min(sectionCardCount,
        (Math.ceil(homeFlick.height / cardRowH) + 4) * cols)
    property int firstSectionCard: Math.max(0, Math.min(sectionCardCount - sectionWindow,
        lowerCard(homeFlick.scrollOffset - cardRowH)))

    PullToRefresh {
        id: homeFlick
        objectName: "homePullToRefresh"
        anchors.fill: parent
        clip: true
        contentHeight: page.contentH + page.bottomContentInset
        enabled: !player.sourceSetupRequired
        refreshing: page.loadingFeedback && page.hasContent
        refreshTexts: [i18n.t("refresh.pull"), i18n.t("refresh.release"), i18n.t("refresh.loading"), i18n.t("refresh.complete")]
        onRefreshRequested: page.refresh()

        Item {
            width: page.width
            height: page.contentH
            // Cards/rows have fixed index-derived positions; skip re-measuring the
            // whole page on unrelated version bumps (the play clock) once laid out.
            cachedLayout: true
            // Replaces the skeleton with a fade rather than a hard cut.
            opacity: page.hasContent ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }

            Text {
                objectName: "homeGreeting"
                x: page.pad
                y: (page.greetH - implicitHeight) / 2 - 2
                width: page.width - 2 * page.pad
                visible: page.hasContent
                text: player.loggedIn ? i18n.t("home.greeting.user", page.greeting(), player.userName)
                                      : page.greeting()
                color: Ui.textSecondary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: Ui.textCallout
            }

            HomeDailyHero {
                id: hero
                objectName: "homeDailyHero"
                visible: page.dailyCount > 0
                x: page.pad
                y: page.greetH
                width: page.width - 2 * page.pad
                height: page.heroH
                compact: page.compact
                songs: page.homeSongs
                songCount: page.dailyCount
                onPlayRequested: player.playRecommendation(0)
                onOpenRequested: page.scrollToDaily()
            }

            Repeater {
                model: page.homeSections
                SectionHeader {
                    x: page.pad
                    y: page.sectionHeaderY[index] + page.hdrTop
                    width: page.width - 2 * page.pad
                    title: modelData.title
                }
            }

            Repeater {
                model: page.sectionCards
                windowStart: page.firstSectionCard
                windowCount: page.sectionWindow
                PlaylistCard {
                    objectName: "homeSectionPlaylistCard"
                    playlistId: modelData.id
                    tile: page.tile
                    x: page.sectionCardX[index]
                    y: page.sectionCardY[index]
                    name: modelData.name
                    count: modelData.trackCount
                    playCount: modelData.playCount || 0
                    coverUrl: modelData.coverUrl
                    coverThumbPath: modelData.coverThumbPath || ""
                    onClicked: { page.pendingPlaylist = modelData; page.openPlaylist() }
                }
            }

            SectionHeader {
                objectName: "homeRecommendHeader"
                visible: page.recCount > 0
                x: page.pad
                y: page.mainHdrY + page.hdrTop
                width: page.width - 2 * page.pad
                title: i18n.t("home.recommendPlaylists")
            }

            Repeater {
                model: page.homePlaylists
                windowStart: page.firstCard
                windowCount: page.cardWindow
                PlaylistCard {
                    playlistId: modelData.id
                    tile: page.tile
                    x: page.pad + (index % page.cols) * (page.tile + page.colGap)
                    y: page.gridTop + Math.floor(index / page.cols) * page.cardRowH
                    name: modelData.name
                    count: modelData.trackCount
                    playCount: modelData.playCount || 0
                    coverUrl: modelData.coverUrl
                    coverThumbPath: modelData.coverThumbPath || ""
                    onClicked: { page.pendingPlaylist = modelData; page.openPlaylist() }
                }
            }

            SectionHeader {
                objectName: "homeDailyHeader"
                visible: !page.dailyFirst && page.dailyCount > 0
                x: page.pad
                y: page.dailyHdrY + page.hdrTop
                width: page.width - 2 * page.pad
                title: i18n.t("home.dailyRecommend")
                actionText: i18n.t("playlist.playAll")
                onActionRequested: player.playRecommendation(0)
            }

            // SongRow insets its own highlight by 8px and its cover by 12px; shift
            // the rows so the cover edge sits on the page gutter.
            Repeater {
                model: page.homeSongs
                windowStart: page.firstSong
                windowCount: page.songWindow
                SongRow {
                    x: page.pad - 12
                    width: page.width - 2 * (page.pad - 12)
                    height: page.rowH
                    y: page.dailyTop + index * page.rowH
                    rowTitle: modelData.name
                    rowArtist: modelData.artist
                    rowArtistId: modelData.artistMediaId || modelData.artistId || 0
                    rowArtistIdsCsv: modelData.artistIdsCsv || ""
                    rowArtistNamesCsv: modelData.artistNamesCsv || ""
                    coverThumbPath: modelData.coverThumbPath || ""
                    song: modelData
                    sharedMenu: homeSongMenu
                    onActivated: player.playRecommendation(index)
                }
            }
            SongContextMenu {
                id: homeSongMenu
                onClosed: if (menuRow) menuRow._menuArmed = false
            }
        }
    }

    // First load (or a refresh with nothing on screen yet): a skeleton of the
    // grid the content will occupy, instead of an empty page.
    Item {
        id: skeleton
        objectName: "homeSkeleton"
        anchors.fill: parent
        visible: page.refreshBusy && !page.hasContent
        property real phase: 0
        opacity: 0.55 + 0.45 * Math.abs(1 - phase)
        NumberAnimation on phase {
            running: skeleton.visible
            from: 0
            to: 2
            duration: 1600
            loops: Animation.Infinite
        }

        Rectangle {
            x: page.pad
            y: (page.greetH - height) / 2
            width: Math.min(160, page.width - 2 * page.pad)
            height: 12
            radius: height / 2
            color: Ui.surfacePressed
        }
        Rectangle {
            x: page.pad
            y: page.greetH + page.hdrTop + 10
            width: Math.min(140, page.width - 2 * page.pad)
            height: 20
            radius: Ui.radiusXs
            color: Ui.surfacePressed
        }
        Repeater {
            model: page.cols * 2
            Item {
                x: page.pad + (index % page.cols) * (page.tile + page.colGap)
                y: page.greetH + page.hdrH + Math.floor(index / page.cols) * page.cardRowH
                width: page.tile
                height: page.cardH
                Rectangle {
                    width: parent.width
                    height: parent.width
                    radius: Ui.radiusMd
                    color: Ui.surfacePressed
                }
                Rectangle {
                    y: parent.width + 10
                    width: parent.width * 0.82
                    height: 12
                    radius: height / 2
                    color: Ui.surfacePressed
                }
                Rectangle {
                    y: parent.width + 32
                    width: parent.width * 0.42
                    height: 10
                    radius: height / 2
                    color: Ui.surfacePressed
                }
            }
        }
    }

    // A source exists but the request came back empty or failed. The no-source
    // case is rendered by the shared setup prompt instead.
    Item {
        x: 0
        y: 0
        width: page.width
        height: Math.max(0, page.height - page.bottomContentInset)
        visible: !player.sourceSetupRequired && !page.refreshBusy && !page.hasContent
        EmptyState {
            objectName: "homeEmptyState"
            icon: "cloud_off"
            title: i18n.t("home.empty.title")
            message: i18n.t("home.empty.message")
            actionText: i18n.t("common.retry")
            onActionRequested: page.refresh()
        }
    }
}
