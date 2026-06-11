import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Phone shell: TopAppBar + paged content + mini player + bottom navigation,
// with a playlist-detail overlay, QR login dialog, a Snackbar for transient
// messages and the debug log on top.
Rectangle {
    id: app
    color: Theme.color.surface

    property int page: 0
    property bool detailOpen: false
    property bool loginOpen: false
    property bool showLog: false

    property var titles: ["推荐", "搜索", "我的", "最近", "本地"]

    // surface player toasts in a Snackbar
    property string toastWatch: player.toast
    onToastWatchChanged: if (player.toast.length > 0) { snack.text = player.toast; snack.open() }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TopAppBar {
            Layout.fillWidth: true
            title: app.titles[app.page]
            showNavigationIcon: false

            IconButton {
                type: "standard"
                icon: player.loggedIn ? "account_circle" : "login"
                onClicked: if (!player.loggedIn) app.loginOpen = true
            }
            IconButton {
                type: "standard"
                icon: "bug_report"
                onClicked: app.showLog = !app.showLog
            }
        }

        // content region: pages + detail overlay
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            StackLayout {
                anchors.fill: parent
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

            PlaylistDetailPage {
                anchors.fill: parent
                visible: app.detailOpen
                onBack: app.detailOpen = false
            }
        }

        MiniPlayer { Layout.fillWidth: true }

        BottomNav {
            id: bottomNav
            Layout.fillWidth: true
            currentIndex: app.page
            onNavigate: {
                app.page = bottomNav.pendingIndex;
                if (bottomNav.pendingIndex === 2) player.loadMyPlaylists();
                else if (bottomNav.pendingIndex === 3) player.loadRecent();
            }
        }
    }

    LoginDialog {
        active: app.loginOpen
        onClosed: app.loginOpen = false
    }

    Snackbar { id: snack }

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
