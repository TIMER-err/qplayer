import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"

// Local: tracks scanned from the device Music folder, plus issue #15's sort/
// group-filter toolbar. Playback deliberately still plays through the ORIGINAL
// scan-order queue (player.play(i) indexes into player.tracks/library) rather
// than a reordered queue matching the current sort/filter — tapping a track
// resolves back to its original index by filePath (unique per local file).
// "Now playing" highlighting matches by that same filePath (VirtualSongList's
// highlightByFilePath) rather than by row index, since this list is always the
// full library regardless of what's actually queued/playing — an index match
// alone would coincidentally light up an unrelated row whenever something
// else (an online playlist, search results, ...) happens to be playing at the
// same position. Comparing by filePath also means it stays correct under any
// sort/filter, not just the default view.
//
// Plain tap-chips rather than a ComboBox/dropdown throughout: this app has no
// existing QML using a signal with parameters (onActivated(index) etc.), so
// staying with the proven onClicked-mutates-a-page-property pattern already
// used everywhere else avoids leaning on untested qml4j behavior.
Item {
    id: page

    property int sortMode: 0     // 0 default(scan order) 1 title 2 artist 3 duration
    property bool sortDesc: false
    property int groupMode: 0    // 0 all 1 by artist 2 by folder
    property string groupValue: ""
    property bool valuePickerOpen: false

    property var allTracks: player.tracks || []

    function folderOf(t) {
        if (!t || !t.filePath) return "";
        // qml4j's expression parser doesn't accept a regex literal here (tested —
        // `.replace(/\\/g, "/")` throws a QmlSyntaxException at parse time), so
        // backslash-to-slash normalization goes through split/join instead.
        var p = t.filePath.split("\\").join("/");
        var idx = p.lastIndexOf("/");
        return idx >= 0 ? p.substring(0, idx) : "";
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
    ColumnLayout {
        anchors.fill: parent
        spacing: 8
        visible: player.libraryCount > 0

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 12
            Layout.rightMargin: 12
            Layout.topMargin: 8
            spacing: 6

            Text {
                text: i18n.t("local.sort")
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
            }
            Repeater {
                model: [i18n.t("local.sort.default"), i18n.t("local.sort.title"),
                        i18n.t("local.sort.artist"), i18n.t("local.sort.duration")]
                Rectangle {
                    objectName: "localSortChip" + index
                    property bool active: index === page.sortMode
                    implicitWidth: chipText.implicitWidth + 20
                    implicitHeight: 28
                    radius: 14
                    color: active ? Theme.color.primary : Theme.color.surfaceContainerHighest
                    Text {
                        id: chipText
                        anchors.centerIn: parent
                        text: modelData
                        fontSize: 12
                        color: active ? Theme.color.onPrimaryColor : Theme.color.onSurfaceVariantColor
                    }
                    MouseArea { anchors.fill: parent; onClicked: page.sortMode = index }
                }
            }
            IconButton {
                type: "standard"
                enabled: page.sortMode !== 0
                icon: page.sortDesc ? "arrow_downward" : "arrow_upward"
                onClicked: page.sortDesc = !page.sortDesc
            }
            Item { Layout.fillWidth: true }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 12
            Layout.rightMargin: 12
            spacing: 6

            Text {
                text: i18n.t("local.group")
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
            }
            Repeater {
                model: [i18n.t("local.group.all"), i18n.t("local.group.artist"),
                        i18n.t("local.group.folder")]
                Rectangle {
                    objectName: "localGroupChip" + index
                    property bool active: index === page.groupMode
                    implicitWidth: gChipText.implicitWidth + 20
                    implicitHeight: 28
                    radius: 14
                    color: active ? Theme.color.primary : Theme.color.surfaceContainerHighest
                    Text {
                        id: gChipText
                        anchors.centerIn: parent
                        text: modelData
                        fontSize: 12
                        color: active ? Theme.color.onPrimaryColor : Theme.color.onSurfaceVariantColor
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: { page.groupMode = index; page.groupValue = "" }
                    }
                }
            }
            Rectangle {
                objectName: "localGroupValueButton"
                visible: page.groupMode !== 0
                Layout.fillWidth: true
                implicitHeight: 28
                radius: 14
                color: Theme.color.surfaceContainerHighest
                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: 12
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    elide: Text.ElideRight
                    text: page.groupValue !== "" ? page.groupValue : i18n.t("local.group.all")
                    fontSize: 12
                    color: Theme.color.onSurfaceColor
                }
                MouseArea { anchors.fill: parent; onClicked: page.valuePickerOpen = true }
            }
        }

        VirtualSongList {
            id: local
            Layout.fillWidth: true
            Layout.fillHeight: true
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
                var t = local.list[local.activatedIndex];
                if (!t) return;
                var all = player.tracks;
                for (var i = 0; i < all.length; i++) {
                    if (all[i].filePath === t.filePath) { player.play(i); return; }
                }
            }
        }

        Text {
            Layout.alignment: Qt.AlignHCenter
            Layout.topMargin: 40
            Layout.fillHeight: page.displayTracks.length === 0
            visible: page.displayTracks.length === 0
            text: i18n.t("local.noMatches")
            color: Theme.color.onSurfaceVariantColor
            fontSize: 15
        }
    }

    EmptyState {
        anchors.centerIn: parent
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
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        id: valueDialog
        title: i18n.t(page.groupMode === 1 ? "local.pick.artist" : "local.pick.folder")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onClosed: page.valuePickerOpen = false
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
            spacing: 8
            TextField {
                id: valueSearchField
                Layout.fillWidth: true
                label: i18n.t("nav.search")
                leadingIcon: "search"
            }
            SuperArrow {
                Layout.fillWidth: true
                title: i18n.t("local.group.all")
                onClicked: { page.groupValue = ""; valueDialog.close() }
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
                            type: page.groupValue === modelData ? "filledTonal" : "text"
                            onClicked: { page.groupValue = modelData; valueDialog.close() }
                        }
                    }
                }
            }
        }
    }
}
