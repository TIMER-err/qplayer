import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 本地: tracks scanned from the device Music folder, plus issue #15's sort/
// group-filter toolbar. Playback deliberately still plays through the ORIGINAL
// scan-order queue (player.play(i) indexes into player.tracks/library) rather
// than a reordered queue matching the current sort/filter — tapping a track
// resolves back to its original index by filePath (unique per local file) — so
// "now playing" highlighting also only lines up while the default (sortMode 0,
// groupMode 0) view is showing; VirtualSongList's highlightCurrent is switched
// off for any other view rather than risk highlighting the wrong row.
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
        var p = t.filePath.replace(/\\/g, "/");
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
            var v = groupMode === 1 ? (allTracks[i].artist || "未知艺术家") : folderOf(allTracks[i]);
            if (v !== "" && !seen[v]) { seen[v] = true; out.push(v); }
        }
        out.sort();
        return out;
    }
    // Reset a stale selection (e.g. the folder no longer exists) back to "全部"
    // instead of silently filtering to nothing.
    onGroupValuesChanged: if (groupValue !== "" && groupValues.indexOf(groupValue) < 0) groupValue = ""

    property var displayTracks: {
        var list = [];
        for (var i = 0; i < allTracks.length; i++) {
            var t = allTracks[i];
            if (groupMode === 1 && groupValue !== "" && (t.artist || "未知艺术家") !== groupValue) continue;
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
    // Only the untouched default view lines up 1:1 with player.tracks' indices —
    // see the file-level comment.
    property bool isDefaultView: sortMode === 0 && groupMode === 0

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
                text: "排序"
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
            }
            Repeater {
                model: ["默认", "标题", "艺术家", "时长"]
                Rectangle {
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
                text: "分组"
                color: Theme.color.onSurfaceVariantColor
                fontSize: 12
            }
            Repeater {
                model: ["全部", "按艺术家", "按文件夹"]
                Rectangle {
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
                    text: page.groupValue !== "" ? page.groupValue : "全部"
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
            // even while the 本地 tab is hidden behind 首页. A large device library
            // (thousands of files) then OOMs ~10s after launch. Null while hidden
            // builds nothing.
            list: page.visible ? page.displayTracks : null
            isLocal: true
            highlightCurrent: page.isDefaultView
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
            text: "当前筛选条件下没有匹配的歌曲"
            color: Theme.color.onSurfaceVariantColor
            fontSize: 15
        }
    }

    Text {
        anchors.centerIn: parent
        visible: player.libraryCount === 0
        text: typeof settings.musicFolder !== "undefined"
            ? "未找到本地音乐\n可在设置 → 本地中修改音乐目录"
            : "未找到本地音乐\n把歌曲放进 Music 文件夹"
        horizontalAlignment: Text.AlignHCenter
        color: Theme.color.onSurfaceVariantColor
        fontSize: 15
    }

    // Value picker for the "按艺术家/按文件夹" group modes above — a search box +
    // explicit-y-positioned list (Repeater has no built-in positioner in this
    // engine), same shape as FontPickerDialog.qml. Distinct artist/folder counts
    // are typically small (tens, not hundreds), so this stays un-windowed.
    Rectangle {
        id: valueDialog
        anchors.fill: parent
        opacity: page.valuePickerOpen ? 1 : 0
        visible: opacity > 0.01
        color: "#99000000"
        Behavior on opacity { NumberAnimation { duration: 150 } }

        MouseArea { anchors.fill: parent; onClicked: page.valuePickerOpen = false }

        property var filtered: {
            var q = valueSearchField.text.toLowerCase();
            var out = [];
            for (var i = 0; i < page.groupValues.length; i++) {
                var name = page.groupValues[i];
                if (q === "" || name.toLowerCase().indexOf(q) >= 0) out.push(name);
            }
            return out;
        }

        Rectangle {
            anchors.centerIn: parent
            width: Math.min(340, parent.width - 32)
            height: Math.min(420, parent.height - 32)
            radius: 24
            color: Theme.color.surfaceContainerHigh
            scale: page.valuePickerOpen ? 1 : 0.9
            Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

            MouseArea { anchors.fill: parent }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 16
                spacing: 12

                Text {
                    Layout.fillWidth: true
                    text: page.groupMode === 1 ? "选择艺术家" : "选择文件夹"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }

                TextField {
                    id: valueSearchField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "搜索"
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 40
                    radius: 8
                    color: allValuesMa.pressed ? Theme.color.surfaceContainerHighest : "transparent"
                    Text {
                        anchors.left: parent.left
                        anchors.leftMargin: 12
                        anchors.verticalCenter: parent.verticalCenter
                        text: "全部"
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    MouseArea {
                        id: allValuesMa
                        anchors.fill: parent
                        onClicked: { page.groupValue = ""; page.valuePickerOpen = false }
                    }
                }

                Flickable {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    contentWidth: width
                    contentHeight: valueDialog.filtered.length * 40

                    Item {
                        width: parent.width
                        height: valueDialog.filtered.length * 40

                        Repeater {
                            model: valueDialog.filtered
                            Rectangle {
                                width: parent.width
                                height: 40
                                y: index * 40
                                radius: 8
                                color: valueRowMa.pressed ? Theme.color.surfaceContainerHighest : "transparent"
                                Text {
                                    anchors.left: parent.left
                                    anchors.leftMargin: 12
                                    anchors.right: parent.right
                                    anchors.rightMargin: 12
                                    anchors.verticalCenter: parent.verticalCenter
                                    elide: Text.ElideRight
                                    text: modelData || ""
                                    color: Theme.color.onSurfaceColor
                                    fontSize: 14
                                }
                                MouseArea {
                                    id: valueRowMa
                                    anchors.fill: parent
                                    onClicked: { page.groupValue = modelData; page.valuePickerOpen = false }
                                }
                            }
                        }
                    }
                }

                Button {
                    Layout.alignment: Qt.AlignHCenter
                    type: "text"; text: "取消"
                    onClicked: page.valuePickerOpen = false
                }
            }
        }
    }
}
