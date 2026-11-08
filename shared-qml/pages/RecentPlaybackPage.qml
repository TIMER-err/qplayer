import QtQuick
import QtQuick.Layouts
import miuix.Core
import "../components"

Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    signal home()
    color: Theme.color.surface

    Component.onCompleted: player.loadPlaybackHistory()

    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            PageHeaderButtons {
                Layout.alignment: Qt.AlignVCenter
                onHome: page.home()
                onBack: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: i18n.t("recent.count", player.playbackHistoryTracks.length)
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
        }

        VirtualSongList {
            id: history
            objectName: "recentPlaybackList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            bottomContentInset: page.bottomContentInset
            list: page.visible ? player.playbackHistoryTracks : null
            isLocal: true
            onActivated: {
                var row = history.list && history.list[history.activatedIndex]
                if (row) player.playHistoryTrack(row.mediaId)
            }
        }
    }

    EmptyState {
        anchors.centerIn: parent
        visible: player.playbackHistoryTracks.length === 0
        icon: "history"
        title: i18n.t("recent.empty.title")
        message: i18n.t("recent.empty.desc")
    }
}
