import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "."
import "../components"

// Local: tracks scanned from the device Music folder, plus issue #15's sort/
// group-filter toolbar. Playback deliberately still plays through the ORIGINAL
// scan-order queue (player.play(i) indexes into player.tracks/library) rather
// than a reordered queue matching the current sort/filter — tapping a track
// resolves back to its original index by filePath (unique per local file).
// "Now playing" highlighting matches by that same filePath (VirtualSongList's
// highlightByFilePath) rather than by row index, since this list is always the
// full library regardless of what's actually queued/playing.
//
// Layout: Main draws the large "本地" title above. The song list owns the whole
// page; its scrolling header carries the summary line, the Play all / Shuffle
// actions and the sort + group filter chips, so the songs get the full height
// once the user scrolls. The header height is derived only from the page width
// (one chip row when wide, two otherwise), never from measured children.
Item {
    id: page
    property real bottomContentInset: 0

    property int sortMode: 0     // 0 default(scan order) 1 title 2 artist 3 duration
    property bool sortDesc: false
    property int groupMode: 0    // 0 all 1 by artist 2 by folder
    property string groupValue: ""
    property bool valuePickerOpen: false

    property var allTracks: player.tracks || []

    readonly property real gutter: Ui.gutter(page.width)
    // VirtualSongList's rows put the cover 12px inside the list; shifting the
    // list by gutter-12 lands covers (and the header content) on the gutter.
    readonly property real listInset: Math.max(0, page.gutter - 12)
    readonly property real contentWidth: Math.max(0, page.width - 2 * page.gutter)
    readonly property bool filterSingleRow: page.contentWidth >= 880
    readonly property real chipHeight: 32
    readonly property real actionsY: 34
    readonly property real chipsY: page.actionsY + Ui.iconButton + Ui.space4
    readonly property real headerHeight: page.chipsY
                                         + (page.filterSingleRow ? page.chipHeight
                                                                 : 2 * page.chipHeight + Ui.space2)
                                         + Ui.space4

    function folderOf(t) {
        if (!t || !t.filePath) return "";
        // qml4j's expression parser doesn't accept a regex literal here (tested —
        // `.replace(/\\/g, "/")` throws a QmlSyntaxException at parse time), so
        // backslash-to-slash normalization goes through split/join instead.
        var p = t.filePath.split("\\").join("/");
        var idx = p.lastIndexOf("/");
        return idx >= 0 ? p.substring(0, idx) : "";
    }
    function folderName(path) {
        var idx = path.lastIndexOf("/");
        return idx >= 0 && idx < path.length - 1 ? path.substring(idx + 1) : path;
    }

    // Distinct values for the value-picker, refreshed whenever the group mode or
    // the underlying library changes.
    property var groupValues: {
        if (groupMode === 0) return [];
        var seen = {};
        var out = [];
        for (var i = 0; i < allTracks.length; i++) {
            var v = groupMode === 1 ? (allTracks[i].artist || i18n.t("local.unknownArtist"))
                                    : folderOf(allTracks[i]);
            if (v !== "" && !seen[v]) { seen[v] = true; out.push(v); }
        }
        out.sort();
        return out;
    }
    // Reset a stale selection (e.g. the folder no longer exists) back to "all"
    // instead of silently filtering to nothing.
    onGroupValuesChanged: if (groupValue !== "" && groupValues.indexOf(groupValue) < 0) groupValue = ""

    property var displayTracks: {
        var list = [];
        for (var i = 0; i < allTracks.length; i++) {
            var t = allTracks[i];
            if (groupMode === 1 && groupValue !== ""
                    && (t.artist || i18n.t("local.unknownArtist")) !== groupValue) continue;
            if (groupMode === 2 && groupValue !== "" && folderOf(t) !== groupValue) continue;
            list.push(t);
        }
        if (sortMode !== 0) {
            list.sort(function(a, b) {
                var av, bv;
                if (sortMode === 1) { av = (a.title || "").toLowerCase(); bv = (b.title || "").toLowerCase(); }
                else if (sortMode === 2) { av = (a.artist || "").toLowerCase(); bv = (b.artist || "").toLowerCase(); }
                else { av = a.durationMs || 0; bv = b.durationMs || 0; }
                if (av < bv) return -1;
                if (av > bv) return 1;
                return 0;
            });
            if (sortDesc) list.reverse();
        }
        return list;
    }

    // "6 首歌曲 · 24 分钟" for whatever the current filter shows.
    readonly property string summaryText: {
        var total = 0;
        var rows = page.displayTracks;
        for (var i = 0; i < rows.length; i++) total += rows[i].durationMs || 0;
        var minutes = Math.round(total / 60000);
        var duration = minutes >= 60
            ? i18n.t("local.duration.hours", Math.floor(minutes / 60), minutes % 60)
            : i18n.t("local.duration.minutes", minutes);
        return i18n.t("local.summary", rows.length, duration);
    }

    // Position of a displayed row in player.tracks (the scan-order queue).
    function libraryIndexOf(t) {
        if (!t) return -1;
        var all = player.tracks;
        for (var i = 0; i < all.length; i++) {
            if (all[i].filePath === t.filePath) return i;
        }
        return -1;
    }
    function playAll() {
        var rows = page.displayTracks;
        if (rows.length === 0) return;
        // "Play all" means in order: leave shuffle, keep repeat-one/list-loop as chosen.
        if (player.playMode === 1) player.setPlayMode(0);
        var i = page.libraryIndexOf(rows[0]);
        if (i >= 0) player.play(i);
    }
    function shufflePlay() {
        var rows = page.displayTracks;
        if (rows.length === 0) return;
        player.setPlayMode(1);
        var i = page.libraryIndexOf(rows[Math.floor(Math.random() * rows.length)]);
        if (i >= 0) player.play(i);
    }
    function selectSort(index) {
        if (index === page.sortMode && index !== 0) {
            page.sortDesc = !page.sortDesc;
            return;
        }
        page.sortMode = index;
        page.sortDesc = false;
    }
    function selectGroup(index) {
        if (index === page.groupMode) {
            if (index !== 0) page.valuePickerOpen = true;
            return;
        }
        page.groupMode = index;
        page.groupValue = "";
        // Picking "by artist/folder" is only half a choice; ask for the value now.
        if (index !== 0) page.valuePickerOpen = true;
    }

    Component {
        id: localHeader
        Item {
            id: header
            width: local.width
            height: page.headerHeight

            Text {
                id: summary
                objectName: "localSummary"
                x: 12
                y: Ui.space1
                width: page.contentWidth
                text: page.summaryText
                color: Ui.textSecondary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: Ui.textCallout
            }

            Button {
                id: playAllButton
                objectName: "localPlayAllButton"
                x: 12
                y: page.actionsY
                height: Ui.iconButton
                type: "filled"
                icon: "play_arrow"
                text: i18n.t("list.playAll")
                cornerRadius: Ui.radiusFull
                enabled: page.displayTracks.length > 0
                onClicked: page.playAll()
            }
            Button {
                objectName: "localShuffleButton"
                x: playAllButton.x + playAllButton.width + Ui.space2
                y: page.actionsY
                height: Ui.iconButton
                type: "filledTonal"
                icon: "shuffle"
                text: i18n.t("list.shuffle")
                cornerRadius: Ui.radiusFull
                enabled: page.displayTracks.length > 0
                onClicked: page.shufflePlay()
            }

            // ---- Sort chips: tapping the active one flips the direction ----
            Glyph {
                id: sortGlyph
                x: 12
                y: page.chipsY + (page.chipHeight - height) / 2
                name: "swap_vert"
                size: 20
                color: Ui.textTertiary
            }
            Row {
                id: sortChips
                x: sortGlyph.x + sortGlyph.width + Ui.space2
                y: page.chipsY
                height: page.chipHeight
                spacing: Ui.space2
                Repeater {
                    model: [i18n.t("local.sort.default"), i18n.t("local.sort.title"),
                            i18n.t("local.sort.artist"), i18n.t("local.sort.duration")]
                    Rectangle {
                        id: sortChip
                        objectName: "localSortChip" + index
                        property bool active: index === page.sortMode
                        readonly property bool showArrow: active && index !== 0
                        width: Math.max(48, sortLabelProbe.implicitWidth + 2 * Ui.space3
                                            + (showArrow ? 18 : 0))
                        height: page.chipHeight
                        radius: height / 2
                        color: active ? Ui.accentSoft
                                      : (sortArea.containsMouse ? Ui.surfacePressed : Ui.surfaceSunken)
                        Behavior on color { ColorAnimation { duration: 120 } }
                        Text {
                            id: sortLabelProbe
                            text: modelData
                            opacity: 0
                            font.pixelSize: Ui.textCallout
                            font.weight: Ui.weightBold
                        }
                        Text {
                            x: Ui.space3 + (sortLabelProbe.implicitWidth - implicitWidth) / 2
                            y: (parent.height - implicitHeight) / 2
                            text: modelData
                            color: sortChip.active ? Ui.accent : Ui.textPrimary
                            font.pixelSize: Ui.textCallout
                            font.weight: sortChip.active ? Ui.weightBold : Ui.weightRegular
                        }
                        Glyph {
                            visible: sortChip.showArrow
                            x: parent.width - Ui.space3 - width + 2
                            y: (parent.height - height) / 2
                            name: page.sortDesc ? "arrow_downward" : "arrow_upward"
                            size: 16
                            color: Ui.accent
                        }
                        MouseArea {
                            id: sortArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: page.selectSort(index)
                        }
                    }
                }
            }

            Rectangle {
                visible: page.filterSingleRow
                x: sortChips.x + sortChips.width + Ui.space4
                y: page.chipsY + 6
                width: 1
                height: page.chipHeight - 12
                color: Ui.hairline
            }

            // ---- Group chips + the chosen artist/folder ----
            Glyph {
                id: groupGlyph
                x: page.filterSingleRow ? sortChips.x + sortChips.width + 2 * Ui.space4 + 1 : 12
                y: (page.filterSingleRow ? page.chipsY : page.chipsY + page.chipHeight + Ui.space2)
                   + (page.chipHeight - height) / 2
                name: "filter_list"
                size: 20
                color: Ui.textTertiary
            }
            Row {
                id: groupChips
                x: groupGlyph.x + groupGlyph.width + Ui.space2
                y: page.filterSingleRow ? page.chipsY : page.chipsY + page.chipHeight + Ui.space2
                height: page.chipHeight
                spacing: Ui.space2
                Repeater {
                    model: [i18n.t("local.group.all"), i18n.t("local.group.artist"),
                            i18n.t("local.group.folder")]
                    Rectangle {
                        id: groupChip
                        objectName: "localGroupChip" + index
                        property bool active: index === page.groupMode
                        width: Math.max(48, groupLabelProbe.implicitWidth + 2 * Ui.space3)
                        height: page.chipHeight
                        radius: height / 2
                        color: active ? Ui.accentSoft
                                      : (groupArea.containsMouse ? Ui.surfacePressed : Ui.surfaceSunken)
                        Behavior on color { ColorAnimation { duration: 120 } }
                        Text {
                            id: groupLabelProbe
                            text: modelData
                            opacity: 0
                            font.pixelSize: Ui.textCallout
                            font.weight: Ui.weightBold
                        }
                        Text {
                            x: Ui.space3 + (groupLabelProbe.implicitWidth - implicitWidth) / 2
                            y: (parent.height - implicitHeight) / 2
                            text: modelData
                            color: groupChip.active ? Ui.accent : Ui.textPrimary
                            font.pixelSize: Ui.textCallout
                            font.weight: groupChip.active ? Ui.weightBold : Ui.weightRegular
                        }
                        MouseArea {
                            id: groupArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: page.selectGroup(index)
                        }
                    }
                }
            }

            Rectangle {
                id: valueChip
                objectName: "localGroupValueButton"
                readonly property bool chosen: page.groupValue !== ""
                readonly property real available: Math.max(0, 12 + page.contentWidth - x)
                visible: page.groupMode !== 0 && available >= 72
                x: groupChips.x + groupChips.width + Ui.space2
                y: groupChips.y
                width: Math.min(available, Math.max(72, valueLabel.implicitWidth + Ui.space3 + 28))
                height: page.chipHeight
                radius: height / 2
                color: valueArea.containsMouse ? Ui.surfacePressed : Ui.surfaceRaised
                border.width: 1
                border.color: chosen ? Ui.accent : Ui.hairline
                Text {
                    id: valueLabel
                    x: Ui.space3
                    y: (parent.height - implicitHeight) / 2
                    width: Math.max(0, parent.width - x - 28)
                    text: valueChip.chosen
                          ? (page.groupMode === 2 ? page.folderName(page.groupValue) : page.groupValue)
                          : i18n.t(page.groupMode === 1 ? "local.pick.artist" : "local.pick.folder")
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    color: valueChip.chosen ? Ui.accent : Ui.textSecondary
                    font.pixelSize: Ui.textCallout
                    font.weight: valueChip.chosen ? Ui.weightBold : Ui.weightRegular
                }
                Glyph {
                    x: parent.width - width - 8
                    y: (parent.height - height) / 2
                    name: "expand_more"
                    size: 18
                    color: valueChip.chosen ? Ui.accent : Ui.textSecondary
                }
                MouseArea {
                    id: valueArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: page.valuePickerOpen = true
                }
            }
        }
    }

    VirtualSongList {
        id: local
        objectName: "localSongList"
        x: page.listInset
        y: 0
        width: Math.max(0, page.width - 2 * page.listInset)
        height: page.height
        visible: player.libraryCount > 0
        bottomContentInset: page.bottomContentInset
        header: localHeader
        headerHeight: page.headerHeight
        // Guard with page.visible (see QueuePage): every page is instantiated at
        // startup and only toggled by visibility, but a bare `list: ...` still
        // builds live delegates the moment the startup scan populates tracks —
        // even while the Local tab is hidden behind Home. A large device library
        // (thousands of files) then OOMs ~10s after launch. Null while hidden
        // builds nothing.
        list: page.visible ? page.displayTracks : null
        isLocal: true
        highlightCurrent: true
        highlightByFilePath: true
        // Long-press → add/remove this file from the custom playlist (issue #15's
        // local-favorites ask) — see SongContextMenu's filePath branch.
        songMenu: true
        onActivated: {
            var i = page.libraryIndexOf(local.list[local.activatedIndex]);
            if (i >= 0) player.play(i);
        }
    }

    // The filter matched nothing: say so right under the chips, inside the
    // scrolled header's footprint (the header itself stays usable).
    Text {
        objectName: "localNoMatches"
        x: page.gutter
        y: page.headerHeight + Ui.space8
        width: page.contentWidth
        visible: player.libraryCount > 0 && page.displayTracks.length === 0
        text: i18n.t("local.noMatches")
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        color: Ui.textSecondary
        font.pixelSize: Ui.textBody
    }

    // Centred in the part of the panel the player bar does not cover.
    EmptyState {
        objectName: "localEmptyState"
        x: 0
        y: 0
        width: page.width
        height: Math.max(0, page.height - page.bottomContentInset)
        visible: player.libraryCount === 0
        icon: "folder_open"
        title: i18n.t("nav.local")
        message: i18n.t(settings.has("musicFolder") ? "local.empty.desktop" : "local.empty.android")
    }

    onValuePickerOpenChanged: {
        if (valuePickerOpen) { valueSearchField.text = ""; valueDialog.open() }
        else if (valueDialog.opened) valueDialog.close()
    }
    Dialog {
        id: valueDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t(page.groupMode === 1 ? "local.pick.artist" : "local.pick.folder")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onClosed: page.valuePickerOpen = false
        // Rows call this rather than writing page.groupValue themselves: inside
        // the dialog's reparented content `page` does not resolve to this page
        // (the write silently landed on undefined), while the dialog's own
        // members still see the document scope.
        function choose(value) {
            page.groupValue = value
            valueDialog.close()
        }
        readonly property string current: page.groupValue
        property var filtered: {
            var q = valueSearchField.text.toLowerCase()
            var out = []
            for (var i = 0; i < page.groupValues.length; i++) {
                var name = page.groupValues[i]
                if (q === "" || name.toLowerCase().indexOf(q) >= 0) out.push(name)
            }
            return out
        }
        ColumnLayout {
            width: parent.width
            spacing: Ui.space2
            TextField {
                id: valueSearchField
                overlayField: true
                Layout.fillWidth: true
                label: i18n.t("nav.search")
                leadingIcon: "search"
            }
            SuperArrow {
                Layout.fillWidth: true
                title: i18n.t("local.group.all")
                onClicked: valueDialog.choose("")
            }
            Flickable {
                id: valueList
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(280, Math.max(104, page.height - 300))
                clip: true
                contentWidth: width
                contentHeight: valueDialog.filtered.length * 52
                onContentHeightChanged: contentY = Math.min(contentY, Math.max(0, contentHeight - height))
                Item {
                    width: valueList.width
                    height: valueList.contentHeight
                    cachedLayout: true
                    Repeater {
                        model: page.valuePickerOpen ? valueDialog.filtered : null
                        windowStart: Math.max(0, Math.floor(valueList.contentY / 52) - 3)
                        windowCount: Math.ceil(valueList.height / 52) + 7
                        Button {
                            width: valueList.width
                            height: 48
                            y: index * 52
                            text: modelData
                            type: valueDialog.current === modelData ? "filledTonal" : "text"
                            cornerRadius: Ui.radiusMd
                            onClicked: valueDialog.choose(modelData)
                        }
                    }
                }

                ViewportScrollBar {
                    target: valueList
                }
            }
        }
    }
}
