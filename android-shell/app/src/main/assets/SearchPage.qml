import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 搜索页：空输入显示搜索历史 + 热门搜索，输入时实时搜索，结果可点击播放。
Item {
    id: page
    property bool historyExpanded: false

    Component.onCompleted: player.loadHotSearches()

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
                // Real-time search on every keystroke (no history here — history only on explicit confirm)
                onTextChanged: {
                    if (text.length > 0) player.search(text)
                    if (text.length === 0) page.historyExpanded = false
                }
                onAccepted: {
                    player.search(query.text)
                    player.addSearchHistory(query.text)
                }
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "filled"; icon: "search"
                onClicked: {
                    player.search(query.text)
                    player.addSearchHistory(query.text)
                }
            }
        }

        // --- Hot searches + history (shown when input is empty) ---
        Item {
            id: hotArea
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length === 0

            Flickable {
                anchors.fill: parent
                clip: true
                contentHeight: hotColumn.height + 32

                Column {
                    id: hotColumn
                    x: 16; y: 16
                    width: parent.width - 32
                    spacing: 12

                    // --- Search history section (hidden when empty) ---
                    Column {
                        width: parent.width
                        spacing: 4
                        visible: player.searchHistory.length > 0

                        Item {
                            width: parent.width
                            height: 44

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
                                type: "standard"
                                icon: "delete_sweep"
                                onClicked: player.clearSearchHistory()
                            }
                        }

                        Repeater {
                            model: player.searchHistory.length <= 10 || page.historyExpanded
                                   ? player.searchHistory.length : 10

                            Item {
                                width: parent.width
                                height: 44

                                Rectangle {
                                    anchors.fill: parent
                                    radius: 8
                                    color: hha.pressed ? Theme.color.surfaceContainerHigh : "transparent"

                                    Row {
                                        anchors.left: parent.left; anchors.leftMargin: 12
                                        anchors.verticalCenter: parent.verticalCenter
                                        spacing: 8

                                        Text {
                                            text: "history"
                                            font.family: Theme.iconFont.name
                                            font.pixelSize: 20
                                            color: Theme.color.onSurfaceVariantColor
                                            anchors.verticalCenter: parent.verticalCenter
                                        }
                                        Text {
                                            text: player.searchHistory[index] || ""
                                            font.pixelSize: 15
                                            color: Theme.color.onSurfaceColor
                                            anchors.verticalCenter: parent.verticalCenter
                                        }
                                    }

                                    MouseArea {
                                        id: hha
                                        anchors.fill: parent
                                        onClicked: {
                                            var kw = player.searchHistory[index]
                                            query.text = kw
                                            player.search(kw)
                                            player.addSearchHistory(kw)
                                        }
                                    }
                                }
                            }
                        }

                        // Expand button — visible when there are more than 10 history entries
                        Item {
                            width: parent.width
                            height: 40
                            visible: player.searchHistory.length > 10 && !page.historyExpanded

                            Rectangle {
                                anchors.fill: parent
                                radius: 8
                                color: expandMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"

                                Text {
                                    anchors.centerIn: parent
                                    text: "展开更多"
                                    font.pixelSize: 14
                                    color: Theme.color.primary
                                }

                                MouseArea {
                                    id: expandMA
                                    anchors.fill: parent
                                    onClicked: page.historyExpanded = true
                                }
                            }
                        }

                        // Divider between history and hot searches
                        Rectangle {
                            width: parent.width
                            height: 1
                            color: Theme.color.outlineVariant
                            visible: player.hotSearches.length > 0
                        }
                    }

                    // --- Hot searches section ---
                    Text {
                        text: "热门搜索"
                        font.pixelSize: 18
                        font.weight: Font.DemiBold
                        color: Theme.color.onSurfaceColor
                    }

                    Repeater {
                        model: player.hotSearches

                        Item {
                            width: parent.width
                            height: 44

                            Rectangle {
                                anchors.fill: parent
                                radius: 8
                                color: hma.pressed ? Theme.color.surfaceContainerHigh : "transparent"

                                Row {
                                    anchors.left: parent.left; anchors.leftMargin: 12
                                    anchors.verticalCenter: parent.verticalCenter
                                    spacing: 8

                                    Text {
                                        text: "search"
                                        font.family: Theme.iconFont.name
                                        font.pixelSize: 20
                                        color: Theme.color.onSurfaceVariantColor
                                        anchors.verticalCenter: parent.verticalCenter
                                    }
                                    Text {
                                        text: modelData || ""
                                        font.pixelSize: 15
                                        color: Theme.color.onSurfaceColor
                                        anchors.verticalCenter: parent.verticalCenter
                                    }
                                }

                                MouseArea {
                                    id: hma
                                    anchors.fill: parent
                                    onClicked: {
                                        query.text = modelData
                                        player.search(modelData)
                                        player.addSearchHistory(modelData)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Search results (shown when input is not empty) ---
        VirtualSongList {
            id: results
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length > 0
            list: player.searchResults
            addable: true
            onActivated: player.playSearchResult(results.activatedIndex)
            onAddRequested: player.addSearchResultToQueue(results.addIndex)
        }
    }
}
