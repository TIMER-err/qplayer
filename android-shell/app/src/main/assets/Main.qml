import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SPlayer-style shell: left rail + paged content + bottom mini player, with a
// playlist-detail overlay, the QR login dialog and the debug log on top.
Rectangle {
    id: app
    color: Theme.color.surface

    property int page: 0
    property bool detailOpen: false
    property bool loginOpen: false
    property bool showLog: false

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // content region: rail + pages, with the detail overlay on top
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            RowLayout {
                anchors.fill: parent
                spacing: 0

                Sidebar {
                    id: sidebar
                    Layout.fillHeight: true
                    currentIndex: app.page
                    loggedIn: player.loggedIn
                    userName: player.userName
                    onNavigate: {
                        app.page = sidebar.pendingIndex;
                        if (sidebar.pendingIndex === 2) player.loadMyPlaylists();
                        else if (sidebar.pendingIndex === 3) player.loadRecent();
                    }
                    onAccount: if (!player.loggedIn) app.loginOpen = true
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    spacing: 0

                    // slim top bar (debug access)
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 44
                        Layout.rightMargin: 4
                        Item { Layout.fillWidth: true }
                        IconButton {
                            Layout.alignment: Qt.AlignVCenter
                            type: "standard"; icon: "bug_report"
                            onClicked: app.showLog = !app.showLog
                        }
                    }

                    StackLayout {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        currentIndex: app.page

                        HomePage {
                            id: home
                            onOpenPlaylist: { player.openPlaylist(home.pendingPlaylist.id); app.detailOpen = true }
                        }
                        SearchPage {}
                        LibraryPage {
                            id: library
                            onOpenPlaylist: { player.openPlaylist(library.pendingPlaylist.id); app.detailOpen = true }
                            onRequestLogin: app.loginOpen = true
                        }
                        RecentPage {
                            onRequestLogin: app.loginOpen = true
                        }
                        LocalPage {}
                    }
                }
            }

            PlaylistDetailPage {
                anchors.fill: parent
                visible: app.detailOpen
                onBack: app.detailOpen = false
            }
        }

        MiniPlayer { Layout.fillWidth: true }
    }

    LoginDialog {
        active: app.loginOpen
        onClosed: app.loginOpen = false
    }

    // --- debug log overlay ---------------------------------------------
    Rectangle {
        visible: app.showLog
        anchors.fill: parent
        color: Theme.color.surfaceContainerHighest

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 8
                spacing: 4
                Text {
                    Layout.fillWidth: true
                    text: "日志"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }
                IconButton { type: "standard"; icon: "delete"; onClicked: player.clearLog() }
                IconButton { type: "standard"; icon: "close"; onClicked: app.showLog = false }
            }

            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.margins: 12
                clip: true
                contentHeight: logText.height
                Text {
                    id: logText
                    width: parent.width
                    text: player.logText
                    color: Theme.color.onSurfaceColor
                    fontSize: 12
                    wrapMode: Text.WrapAnywhere
                }
            }
        }
    }
}
