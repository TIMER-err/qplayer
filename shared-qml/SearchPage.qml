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
                // Real-time search on every keystroke
                onTextChanged: {
                    if (text.length > 0) player.search(text)
                    else page.historyExpanded = false
                }
                onAccepted: {
                    if (query.text.length > 0) {
                        player.search(query.text)
                        player.addSearchHistory(query.text)
                    }
                }
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "filled"; icon: "search"
                onClicked: {
                    if (query.text.length > 0) {
                        player.search(query.text)
                        player.addSearchHistory(query.text)
                    }
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

            property int rowH: 44
            property int histCount: player.searchHistory
                ? (player.searchHistory.length <= 10 || page.historyExpanded
                   ? player.searchHistory.length : 10)
                : 0
            property int hotCount: player.hotSearches ? player.hotSearches.length : 0
            property bool hasHistory: player.searchHistory && player.searchHistory.length > 0
            property bool showExpand: player.searchHistory
                ? (player.searchHistory.length > 10 && !page.historyExpanded) : false

            // section y-offsets (explicit, no Column)
            property int histHeaderY: hasHistory ? 16 : 0
            property int histHeaderH: hasHistory ? 48 : 0
            property int histRowsY: histHeaderY + histHeaderH
            property int histRowsH: histCount * rowH
            property int expandY: histRowsY + histRowsH
            property int expandH: showExpand ? 40 : 0
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
                        model: hotArea.histCount

                        Item {
                            width: hotArea.width - 32
                            height: hotArea.rowH
                            y: index * hotArea.rowH

                            Rectangle {
                                anchors.fill: parent
                                radius: 8
                                color: rowMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                            }
                            Text {
                                x: 12
                                anchors.verticalCenter: parent.verticalCenter
                                text: "history"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 20
                                color: Theme.color.onSurfaceVariantColor
                            }
                            Text {
                                x: 44; width: parent.width - 44 - 44
                                anchors.verticalCenter: parent.verticalCenter
                                text: player.searchHistory && player.searchHistory[index] ? player.searchHistory[index] : ""
                                font.pixelSize: 15
                                color: Theme.color.onSurfaceColor
                                elide: Text.ElideRight
                            }
                            Text {
                                x: parent.width - 40; width: 40
                                anchors.verticalCenter: parent.verticalCenter
                                horizontalAlignment: Text.AlignHCenter
                                text: "close"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 18
                                color: Theme.color.onSurfaceVariantColor
                            }
                            // left portion → navigate to search
                            MouseArea {
                                id: rowMA
                                x: 0; y: 0; width: parent.width - 44; height: parent.height
                                onClicked: {
                                    var kw = player.searchHistory && player.searchHistory[index] ? player.searchHistory[index] : ""
                                    if (kw.length > 0) {
                                        query.text = kw
                                        player.search(kw)
                                        player.addSearchHistory(kw)
                                    }
                                }
                            }
                            // right 44px → remove
                            MouseArea {
                                x: parent.width - 44; y: 0; width: 44; height: parent.height
                                onClicked: player.removeSearchHistory(index)
                            }
                        }
                    }
                }

                // --- Expand button ---
                Item {
                    x: 16; y: hotArea.expandY
                    width: hotArea.width - 32; height: hotArea.expandH
                    visible: hotArea.showExpand

                    Rectangle {
                        anchors.fill: parent; radius: 8
                        color: expandMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: "展开更多"; font.pixelSize: 14; color: Theme.color.primary
                        }
                        MouseArea { id: expandMA; anchors.fill: parent; onClicked: page.historyExpanded = true }
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
                            width: hotArea.width
                            height: hotArea.rowH
                            y: index * hotArea.rowH

                            Rectangle {
                                anchors.fill: parent
                                anchors.leftMargin: 12; anchors.rightMargin: 12
                                radius: 8
                                color: hma.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                            }

                            Row {
                                anchors.left: parent.left; anchors.leftMargin: 24
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 8

                                Text {
                                    text: "search"
                                    font.family: Theme.iconFont.name; font.pixelSize: 20
                                    color: Theme.color.onSurfaceVariantColor
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                                Text {
                                    text: modelData || ""
                                    font.pixelSize: 15; color: Theme.color.onSurfaceColor
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                            }

                            MouseArea {
                                id: hma; anchors.fill: parent
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

        // --- Search results (shown when input is not empty) ---
        VirtualSongList {
            id: results
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length > 0
            list: player.searchResults
            onActivated: player.playSearchResult(results.activatedIndex)
        }
    }
}
