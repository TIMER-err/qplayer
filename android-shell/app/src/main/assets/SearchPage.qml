import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 搜索: query field + netease results.
Item {
    id: page

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 12
            spacing: 4

            TextField {
                id: query
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                type: "filled"
                leadingIcon: "search"
                label: "搜索网易云歌曲"
                onAccepted: player.search(query.text)
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "filled"; icon: "search"
                onClicked: player.search(query.text)
            }
        }

        VirtualSongList {
            id: results
            Layout.fillWidth: true
            Layout.fillHeight: true
            list: player.searchResults
            onActivated: player.playSearchResult(results.activatedIndex)
        }
    }
}
