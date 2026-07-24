import QtQuick
import QtQuick.Layouts
import md3.Core

// Extracted out of SettingsPage.qml into its own file: qml4j compiles each QML
// file's root down to one JVM constructor, and appending this whole card's markup
// straight into the already-large SettingsPage.qml pushed that generated method
// past the JVM's 64KB bytecode limit (ASM MethodTooLargeException at runtime,
// not caught by `mvn package`). A separate top-level component gets its own
// generated class/constructor instead of adding to SettingsPage's.
//
// Independent of netease: a user-configured third-party music API (e.g. a
// self-hosted QQ音乐API / 歌曲宝API style reverse proxy). Rather than one field
// per project, this is a generic URL-template + JSON-path-mapping adapter — see
// player-core's customapi package.
ColumnLayout {
    id: root
    Layout.fillWidth: true
    spacing: 6

    Text {
        Layout.leftMargin: 20
        Layout.topMargin: 6
        text: "自定义 API 源"
        color: Theme.color.primary
        font.family: Theme.typography.titleSmall.family
        font.pixelSize: Theme.typography.titleSmall.size
    }
    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 12
        Layout.rightMargin: 12
        radius: 18
        color: Theme.color.surfaceContainerHighest
        implicitHeight: customApiCol.implicitHeight + 32

        ColumnLayout {
            id: customApiCol
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 16
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: "启用自定义 API 源"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyLarge.family
                    font.pixelSize: Theme.typography.bodyLarge.size
                }
                Switch {
                    checked: settings.customApiEnabled
                    onClicked: settings.customApiEnabled = checked
                }
            }
            // Manual line breaks, same reason and same fix as AboutSettingsCards.qml's
            // version text: qml4j's auto WordWrap for this Text never settled against
            // the card's real (later-resolved) width, no matter how the width was
            // sourced (Layout.fillWidth, explicit width: parent.width, an
            // externally-passed availableWidth, delaying first appearance) -- it kept
            // rendering the tail of the string cut off instead of wrapping. Hard-coding
            // the breaks sidesteps the auto-wrap path entirely.
            Text {
                text: "独立于网易云的搜索入口，\n用下面的字段接一个自建/第三方\n音乐 API"
                color: Theme.color.onSurfaceVariantColor
                font.family: Theme.typography.bodySmall.family
                font.pixelSize: Theme.typography.bodySmall.size
            }

            // Animated collapse while the source is disabled. Attempt #1 (an outer
            // Item starting at height:0 with clip:true, animating to a CHILD
            // ColumnLayout's implicitHeight) shipped broken: a zero-height clipped
            // Item's child apparently never gets measured in this engine, so that
            // child's implicitHeight stayed stuck at 0 forever. This version reads
            // `implicitHeight` off THIS SAME ColumnLayout (an intrinsic, bottom-up
            // measurement from its own children — independent of the top-down
            // `Layout.preferredHeight` that's what actually gets animated/clipped
            // here), so there's no separate always-zero ancestor to break the
            // measurement in the first place.
            ColumnLayout {
                id: advancedFields
                Layout.fillWidth: true
                Layout.preferredHeight: settings.customApiEnabled ? implicitHeight : 0
                clip: true
                opacity: settings.customApiEnabled ? 1 : 0
                Behavior on Layout.preferredHeight { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                Behavior on opacity { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                spacing: 12

            Text {
                Layout.fillWidth: true
                text: "URL 模板用 {keyword}/{id}\n占位符；字段填 JSON 路径，\n如 data.list、name、\nartists[].name（数组转\n字符串用 / 拼接多个值）"
                color: Theme.color.onSurfaceVariantColor
                font.family: Theme.typography.bodySmall.family
                font.pixelSize: Theme.typography.bodySmall.size
                wrapMode: Text.WordWrap
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.color.outlineVariant }

            // Each row: a small label + a TextField/"应用" pair, same
            // interaction as SettingsPage.qml's "本地音乐目录" field.
            Text { text: "搜索接口 URL 模板"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiSearchUrlField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "https://host/search?key={keyword}"
                    text: settings.customApiSearchUrl
                    onAccepted: settings.customApiSearchUrl = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiSearchUrl = customApiSearchUrlField.text }
            }

            Text { text: "搜索结果列表路径"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiSearchListPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 data.list"
                    text: settings.customApiSearchListPath
                    onAccepted: settings.customApiSearchListPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiSearchListPath = customApiSearchListPathField.text }
            }

            Text { text: "id 字段路径"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiIdPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 id"
                    text: settings.customApiIdPath
                    onAccepted: settings.customApiIdPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiIdPath = customApiIdPathField.text }
            }

            Text { text: "歌名字段路径"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiNamePathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 name"
                    text: settings.customApiNamePath
                    onAccepted: settings.customApiNamePath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiNamePath = customApiNamePathField.text }
            }

            Text { text: "歌手字段路径（可选）"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiArtistPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 artists[].name"
                    text: settings.customApiArtistPath
                    onAccepted: settings.customApiArtistPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiArtistPath = customApiArtistPathField.text }
            }

            Text { text: "专辑字段路径（可选）"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiAlbumPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 album.name"
                    text: settings.customApiAlbumPath
                    onAccepted: settings.customApiAlbumPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiAlbumPath = customApiAlbumPathField.text }
            }

            Text { text: "封面字段路径（可选）"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiCoverPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 pic"
                    text: settings.customApiCoverPath
                    onAccepted: settings.customApiCoverPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiCoverPath = customApiCoverPathField.text }
            }

            Text { text: "时长字段路径（可选，单位：秒）"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiDurationPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 duration"
                    text: settings.customApiDurationPath
                    onAccepted: settings.customApiDurationPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiDurationPath = customApiDurationPathField.text }
            }

            Text { text: "播放地址 URL 模板"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiUrlUrlField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "https://host/url?id={id}"
                    text: settings.customApiUrlUrl
                    onAccepted: settings.customApiUrlUrl = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiUrlUrl = customApiUrlUrlField.text }
            }

            Text { text: "播放地址结果路径"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiUrlResultPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 data.url"
                    text: settings.customApiUrlResultPath
                    onAccepted: settings.customApiUrlResultPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiUrlResultPath = customApiUrlResultPathField.text }
            }

            Text { text: "歌词接口 URL 模板（可选）"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiLyricUrlField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "https://host/lyric?id={id}"
                    text: settings.customApiLyricUrl
                    onAccepted: settings.customApiLyricUrl = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiLyricUrl = customApiLyricUrlField.text }
            }

            Text { text: "歌词结果路径（可选，纯 LRC 文本）"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiLyricResultPathField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 data.lyric"
                    text: settings.customApiLyricResultPath
                    onAccepted: settings.customApiLyricResultPath = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiLyricResultPath = customApiLyricResultPathField.text }
            }

            Text { text: "请求头（可选，多个用 ; 分隔）"; color: Theme.color.onSurfaceColor; font.pixelSize: 13 }
            RowLayout {
                Layout.fillWidth: true; spacing: 8
                TextField {
                    id: customApiHeadersField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "如 Authorization: Bearer xxx; X-Custom: 1"
                    text: settings.customApiHeaders
                    onAccepted: settings.customApiHeaders = text
                }
                Button { type: "tonal"; text: "应用"; onClicked: settings.customApiHeaders = customApiHeadersField.text }
            }
            } // advancedFields (ColumnLayout)
        }
    }
}
